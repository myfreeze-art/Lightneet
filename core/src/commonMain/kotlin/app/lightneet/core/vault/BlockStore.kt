package app.lightneet.core.vault

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.wipe
import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.storage.BlockPool
import app.lightneet.core.util.ByteReader
import app.lightneet.core.util.ByteWriter
import app.lightneet.core.util.ad

/** Which interleaved part of the pool a vault lives in. See [HiddenReserve]. */
internal class Region(private val reserve: HiddenReserve, val hidden: Boolean) {
    init {
        require(!hidden || reserve != HiddenReserve.NONE)
    }

    fun contains(index: Long): Boolean = reserve.isHidden(index) == hidden

    fun first(n: Int): List<Long> = generateSequence(0L) { it + 1 }.filter(::contains).take(n).toList()
}

/**
 * Encrypts whole blocks. On-disk block = `nonce (24) | ciphertext (capacity) | tag (16)`.
 * Plaintext is always padded to full [capacity], so every block is the same size and every
 * byte of it is indistinguishable from random.
 */
internal class BlockCodec(private val pool: BlockPool, private val crypto: Crypto) {
    val capacity = pool.blockSize - Crypto.NONCE_BYTES - Crypto.TAG_BYTES

    fun write(index: Long, plaintext: ByteArray, key: ByteArray, ad: ByteArray) {
        require(plaintext.size == capacity)
        val raw = ByteArray(pool.blockSize)
        val nonce = crypto.randomBytes(Crypto.NONCE_BYTES)
        val ct = ByteArray(capacity + Crypto.TAG_BYTES)
        crypto.seal(ct, plaintext, ad, nonce, key)
        nonce.copyInto(raw, 0)
        ct.copyInto(raw, Crypto.NONCE_BYTES)
        pool.write(index, raw)
    }

    fun read(index: Long, key: ByteArray, ad: ByteArray): ByteArray? {
        val raw = ByteArray(pool.blockSize)
        pool.read(index, raw)
        val nonce = raw.copyOfRange(0, Crypto.NONCE_BYTES)
        val ct = raw.copyOfRange(Crypto.NONCE_BYTES, raw.size)
        val out = ByteArray(capacity)
        return if (crypto.open(out, ct, ad, nonce, key)) out else null
    }
}

/**
 * Hands out free blocks of one region, lowest index first. "Free" is only known to the vault
 * that owns the region; nobody else can tell which blocks are in use.
 *
 * Only the main vault may grow the pool automatically. If the hidden vault did, the pool would
 * end up far larger than the main vault could ever explain — a tell-tale sign. When the hidden
 * region is full, the user enlarges the storage explicitly (a perfectly ordinary action).
 */
internal class Allocator(
    private val pool: BlockPool,
    private val region: Region,
    private val owned: MutableSet<Long>,
    private val canGrow: Boolean,
) {
    private var cursor = 0L

    val ownedCount: Int get() = owned.size

    fun capacityBlocks(): Long = (0 until pool.blockCount).count { region.contains(it) }.toLong()

    fun freeBlocks(): Long = capacityBlocks() - owned.count { it < pool.blockCount }

    fun allocate(): Long {
        while (true) {
            if (cursor >= pool.blockCount) {
                if (!canGrow) throw VaultFullException()
                pool.grow(1)
            }
            val i = cursor++
            if (region.contains(i) && owned.add(i)) return i
        }
    }

    fun release(index: Long) {
        if (owned.remove(index) && index < cursor) cursor = index
    }
}

/** The hidden region has no free blocks left; the storage must be enlarged explicitly. */
class VaultFullException : IllegalStateException("no free space left in this vault's region")

/**
 * Persists one vault's index using two alternating superblocks (A/B) for crash safety.
 *
 * A commit writes the new index into fresh blocks, fsyncs, then writes a superblock with a
 * higher generation into whichever of A/B is older, fsyncs again, and only then scrubs the
 * previous index blocks. If the phone dies at any point, one of the two superblocks still
 * points at a complete index.
 *
 * Index and superblock keys depend on the vault's *epoch* (random bytes kept in the sealed
 * header). Deleting files moves the vault to a new epoch: the new index is written under keys
 * derived from it, the header is updated, and from then on older copies of the index — which
 * still list the deleted files' keys — cannot be decrypted, even with the password.
 */
internal class IndexStore(
    private val crypto: Crypto,
    private val pool: BlockPool,
    private val codec: BlockCodec,
    private val master: ByteArray,
    private val superblocks: Pair<Long, Long>,
    epoch: ByteArray,
) {
    private var sbKey = keyFor("superblock", epoch)
    private var ixKey = keyFor("index", epoch)

    private var generation = 0L
    private var currentSuperblock = -1L
    var indexBlocks: List<Long> = emptyList()
        private set

    private fun keyFor(label: String, epoch: ByteArray) =
        crypto.keyedHash(master, "lightneet/v2/$label".encodeToByteArray() + epoch)

    fun superblockIndices() = listOf(superblocks.first, superblocks.second)

    /** Returns the newest readable index, or null if neither superblock is valid. */
    fun load(): VaultIndex? {
        val candidates = superblockIndices().mapNotNull { sb -> readSuperblock(sb)?.let { sb to it } }
            .sortedByDescending { it.second.generation }
        for ((sb, info) in candidates) {
            val bytes = readIndexBytes(info) ?: continue
            val index = runCatching { VaultIndex.decode(bytes) }.getOrNull() ?: continue
            bytes.wipe()
            generation = info.generation
            currentSuperblock = sb
            indexBlocks = info.blocks
            return index
        }
        return null
    }

    /**
     * Writes [index]. With [newEpoch], the index is written under keys of the new epoch and
     * [persistEpoch] is called once the new superblock is durable, *before* the old index is
     * scrubbed. If [persistEpoch] fails, the commit is rolled back and the old state stays valid.
     */
    fun commit(index: VaultIndex, allocator: Allocator, newEpoch: ByteArray? = null, persistEpoch: () -> Unit = {}) {
        val bytes = index.encode()
        val cap = codec.capacity
        val count = maxOf(1, (bytes.size + cap - 1) / cap)
        val gen = generation + 1
        val writeIx = newEpoch?.let { keyFor("index", it) } ?: ixKey
        val writeSb = newEpoch?.let { keyFor("superblock", it) } ?: sbKey
        val newBlocks = ArrayList<Long>(count)
        try {
            repeat(count) { newBlocks += allocator.allocate() }
            val chunk = ByteArray(cap)
            newBlocks.forEachIndexed { seq, block ->
                chunk.fill(0)
                val from = seq * cap
                bytes.copyInto(chunk, 0, from, minOf(bytes.size, from + cap))
                codec.write(block, chunk, writeIx, ad("ix", block, gen, seq.toLong()))
            }
            chunk.wipe()
            pool.sync()
            val target = if (currentSuperblock == superblocks.first) superblocks.second else superblocks.first
            writeSuperblock(target, SuperblockInfo(gen, bytes.size.toLong(), newBlocks), writeSb)
            pool.sync()
            if (newEpoch != null) persistEpoch()

            val old = indexBlocks
            generation = gen
            currentSuperblock = target
            indexBlocks = newBlocks
            if (newEpoch != null) {
                sbKey.wipe(); ixKey.wipe()
                sbKey = writeSb; ixKey = writeIx
            }
            old.forEach { pool.scrub(it); allocator.release(it) }
        } catch (t: Throwable) {
            newBlocks.forEach { runCatching { pool.scrub(it) }; allocator.release(it) }
            if (newEpoch != null) { writeIx.wipe(); writeSb.wipe() }
            throw t
        } finally {
            bytes.wipe()
        }
    }

    private fun readSuperblock(index: Long): SuperblockInfo? {
        val plain = codec.read(index, sbKey, ad("sb", index)) ?: return null
        val r = ByteReader(plain)
        if (!r.raw(4).contentEquals(SB_MAGIC)) return null
        val gen = r.u64()
        val length = r.u64()
        val n = r.u32()
        if (n < 0 || n > (plain.size - 24) / 8) return null
        return SuperblockInfo(gen, length, List(n) { r.u64() })
    }

    private fun writeSuperblock(index: Long, info: SuperblockInfo, key: ByteArray) {
        val w = ByteWriter(ByteArray(codec.capacity))
        w.raw(SB_MAGIC).u64(info.generation).u64(info.length).u32(info.blocks.size)
        info.blocks.forEach { w.u64(it) }
        codec.write(index, w.bytes, key, ad("sb", index))
    }

    private fun readIndexBytes(info: SuperblockInfo): ByteArray? {
        val cap = codec.capacity
        if (info.length > info.blocks.size.toLong() * cap) return null
        val out = ByteArray(info.length.toInt())
        info.blocks.forEachIndexed { seq, block ->
            if (block !in 0 until pool.blockCount) return null
            val plain = codec.read(block, ixKey, ad("ix", block, info.generation, seq.toLong())) ?: return null
            val from = seq * cap
            val n = minOf(cap, out.size - from)
            if (n > 0) plain.copyInto(out, from, 0, n)
            plain.wipe()
        }
        return out
    }

    fun close() {
        sbKey.wipe()
        ixKey.wipe()
    }

    private class SuperblockInfo(val generation: Long, val length: Long, val blocks: List<Long>)

    private companion object {
        val SB_MAGIC = "LNSB".encodeToByteArray()
    }
}

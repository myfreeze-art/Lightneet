package app.lightneet.core.vault

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.subkey
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
 * Hands out free blocks of one region, lowest index first, growing the pool when the
 * region is full. "Free" is only known to the vault that owns the region; nobody else can
 * tell which blocks are in use.
 */
internal class Allocator(private val pool: BlockPool, private val region: Region, private val owned: MutableSet<Long>) {
    private var cursor = 0L

    val ownedCount: Int get() = owned.size

    fun capacityBlocks(): Long = (0 until pool.blockCount).count { region.contains(it) }.toLong()

    fun allocate(): Long {
        while (true) {
            if (cursor >= pool.blockCount) pool.grow(1)
            val i = cursor++
            if (region.contains(i) && owned.add(i)) return i
        }
    }

    fun release(index: Long) {
        if (owned.remove(index) && index < cursor) cursor = index
    }
}

/**
 * Persists one vault's index using two alternating superblocks (A/B) for crash safety.
 *
 * A commit writes the new index into fresh blocks, fsyncs, then writes a superblock with a
 * higher generation into whichever of A/B is older, fsyncs again, and only then scrubs the
 * previous index blocks. If the phone dies at any point, one of the two superblocks still
 * points at a complete index.
 */
internal class IndexStore(
    private val crypto: Crypto,
    private val pool: BlockPool,
    private val codec: BlockCodec,
    master: ByteArray,
    private val superblocks: Pair<Long, Long>,
) {
    private val sbKey = crypto.subkey(master, "superblock")
    private val ixKey = crypto.subkey(master, "index")

    private var generation = 0L
    private var currentSuperblock = -1L
    var indexBlocks: List<Long> = emptyList()
        private set

    fun superblockIndices() = listOf(superblocks.first, superblocks.second)

    /** Returns the newest readable index, or null if neither superblock is valid. */
    fun load(): VaultIndex? {
        val candidates = superblockIndices().mapNotNull { sb -> readSuperblock(sb)?.let { sb to it } }
            .sortedByDescending { it.second.generation }
        for ((sb, info) in candidates) {
            val bytes = readIndexBytes(info) ?: continue
            val index = runCatching { VaultIndex.decode(bytes) }.getOrNull() ?: continue
            generation = info.generation
            currentSuperblock = sb
            indexBlocks = info.blocks
            return index
        }
        return null
    }

    fun commit(index: VaultIndex, allocator: Allocator) {
        val bytes = index.encode()
        val cap = codec.capacity
        val count = maxOf(1, (bytes.size + cap - 1) / cap)
        val gen = generation + 1
        val newBlocks = List(count) { allocator.allocate() }
        try {
            val chunk = ByteArray(cap)
            newBlocks.forEachIndexed { seq, block ->
                chunk.fill(0)
                val from = seq * cap
                bytes.copyInto(chunk, 0, from, minOf(bytes.size, from + cap))
                codec.write(block, chunk, ixKey, ad("ix", block, gen, seq.toLong()))
            }
            chunk.wipe()
            pool.sync()
            val target = if (currentSuperblock == superblocks.first) superblocks.second else superblocks.first
            writeSuperblock(target, SuperblockInfo(gen, bytes.size.toLong(), newBlocks))
            pool.sync()
            bytes.wipe()

            val old = indexBlocks
            generation = gen
            currentSuperblock = target
            indexBlocks = newBlocks
            old.forEach { pool.scrub(it); allocator.release(it) }
        } catch (t: Throwable) {
            newBlocks.forEach { allocator.release(it) }
            throw t
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

    private fun writeSuperblock(index: Long, info: SuperblockInfo) {
        val w = ByteWriter(ByteArray(codec.capacity))
        w.raw(SB_MAGIC).u64(info.generation).u64(info.length).u32(info.blocks.size)
        info.blocks.forEach { w.u64(it) }
        codec.write(index, w.bytes, sbKey, ad("sb", index))
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

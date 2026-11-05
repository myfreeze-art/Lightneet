package app.lightneet.core.vault

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.wipe
import app.lightneet.core.format.PanicMode
import app.lightneet.core.format.SlotPayload
import app.lightneet.core.storage.BlockPool
import app.lightneet.core.util.ad
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.BufferedSink
import okio.Source
import okio.buffer

enum class VaultKind { MAIN, HIDDEN }

class VaultLockedException : IllegalStateException("Vault is locked")
class CorruptedDataException(message: String) : IllegalStateException(message)

data class Usage(val usedBytes: Long, val regionBytes: Long, val poolBytes: Long)

/**
 * An unlocked vault. Holds the master key in memory until [close] — the app closes the
 * session whenever it locks, and every key and cached plaintext is wiped.
 *
 * All methods are blocking and thread-safe; call them off the main thread.
 */
class VaultSession internal constructor(
    private val vault: Vault,
    val kind: VaultKind,
    internal val slotIndex: Int,
    internal val payload: SlotPayload,
    private val crypto: Crypto,
    private val pool: BlockPool,
    private val region: Region,
    private val now: () -> Long,
    epoch: ByteArray,
) : AutoCloseable {
    private val lock = SynchronizedObject()
    private val codec = BlockCodec(pool, crypto)
    private val store = IndexStore(crypto, pool, codec, payload.masterKey, payload.superblockA to payload.superblockB, epoch)

    /**
     * Per-file keys, in guarded memory. In [index] every entry's `key` is empty; the keys are
     * copied in only while the index is serialised for a commit ([persist]).
     */
    private val fileKeys = crypto.newKeyRing()
    private lateinit var index: VaultIndex
    private lateinit var allocator: Allocator
    private var closed = false

    internal fun initialize(fresh: Boolean) {
        synchronized(lock) {
            if (fresh) {
                index = VaultIndex()
                allocator = Allocator(pool, region, store.superblockIndices().toHashSet(), canGrow)
                persist(index)
            } else {
                index = withoutKeys(store.load() ?: throw CorruptedDataException("vault index is unreadable"))
                val owned = HashSet<Long>()
                owned += store.superblockIndices()
                owned += store.indexBlocks
                index.entries.forEach { e -> e.ownedBlocks().forEach { owned += it } }
                allocator = Allocator(pool, region, owned, canGrow)
            }
            pool.touchAll()
        }
    }

    private val canGrow get() = kind == VaultKind.MAIN

    val isOpen: Boolean get() = synchronized(lock) { !closed }

    val blockCapacity: Int get() = codec.capacity

    // ---------------------------------------------------------------- browsing

    fun entries(): List<Entry> = read { index.entries }

    fun children(parentId: Long): List<Entry> = read { index.entries.filter { it.parentId == parentId } }

    fun entry(id: Long): Entry? = read { index.entries.firstOrNull { it.id == id } }

    /** Folders from the root down to [id] (inclusive), for breadcrumbs. */
    fun path(id: Long): List<Entry> = read {
        val byId = index.entries.associateBy { it.id }
        generateSequence(byId[id]) { byId[it.parentId] }.toList().reversed()
    }

    val settings: VaultSettings get() = read { index.settings }

    fun updateSettings(change: (VaultSettings) -> VaultSettings) = mutate { it.copy(settings = change(it.settings)) }

    /**
     * Enlarges the storage by [segments] × 64 MiB of fresh noise. Both regions grow together.
     * The hidden vault never grows the pool on its own (see [Allocator]); this is how its user
     * makes room — an ordinary action that reveals nothing.
     */
    fun growStorage(segments: Int, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        require(segments in 1..1024)
        synchronized(lock) {
            ensureOpen()
            pool.grow(segments, onProgress)
        }
    }

    fun usage(): Usage = read {
        Usage(
            usedBytes = allocator.ownedCount.toLong() * pool.blockSize,
            regionBytes = allocator.capacityBlocks() * pool.blockSize,
            poolBytes = pool.blockCount * pool.blockSize,
        )
    }

    // ---------------------------------------------------------------- changes

    fun createFolder(parentId: Long, name: String): Entry {
        var created: Entry? = null
        mutate { idx ->
            requireFolder(idx, parentId)
            val e = Entry(
                id = idx.nextId, parentId = parentId, name = uniqueName(idx, parentId, name),
                isFolder = true, createdAt = now(), modifiedAt = now(),
            )
            created = e
            idx.copy(nextId = idx.nextId + 1, entries = idx.entries + e)
        }
        return created!!
    }

    /**
     * Streams [source] into the vault. Memory use is one block regardless of file size.
     * If anything fails (including cancellation thrown from [onProgress]), every block written
     * so far is scrubbed and the index is left untouched.
     */
    fun importFile(
        parentId: Long,
        name: String,
        mimeType: String,
        source: Source,
        onProgress: (bytesDone: Long) -> Unit = {},
    ): Entry {
        read { requireFolder(index, parentId) }
        val id = reserveId()
        synchronized(lock) { fileKeys.putRandom(id) }
        val blocks = ArrayList<Long>()
        val chunk = ByteArray(codec.capacity)
        var size = 0L
        try {
            source.buffer().use { input ->
                while (true) {
                    chunk.fill(0)
                    var filled = 0
                    while (filled < chunk.size) {
                        val n = input.read(chunk, filled, chunk.size - filled)
                        if (n == -1) break
                        filled += n
                    }
                    if (filled == 0) break
                    synchronized(lock) {
                        ensureOpen()
                        val b = allocator.allocate()
                        blocks += b
                        codec.write(b, chunk, fileKeys, id, ad("fd", b, id, (blocks.size - 1).toLong()))
                    }
                    size += filled
                    onProgress(size)
                    if (filled < chunk.size) break
                }
            }
            chunk.wipe()
            var created: Entry? = null
            mutate { idx ->
                requireFolder(idx, parentId)
                val e = Entry(
                    id = id, parentId = parentId, name = uniqueName(idx, parentId, name), isFolder = false,
                    size = size, mimeType = mimeType, createdAt = now(), modifiedAt = now(),
                    blocks = blocks.toList(),
                )
                created = e
                idx.copy(entries = idx.entries + e)
            }
            return created!!
        } catch (t: Throwable) {
            chunk.wipe()
            synchronized(lock) {
                fileKeys.remove(id)
                if (!closed) blocks.forEach { pool.scrub(it); allocator.release(it) }
            }
            throw t
        }
    }

    fun rename(id: Long, newName: String) = mutate { idx ->
        val e = idx.entries.first { it.id == id }
        val name = uniqueName(idx, e.parentId, newName, ignoreId = id)
        idx.copy(entries = idx.entries.map { if (it.id == id) it.copy(name = name, modifiedAt = now()) else it })
    }

    fun move(ids: Collection<Long>, newParentId: Long) = mutate { idx ->
        requireFolder(idx, newParentId)
        val forbidden = descendantsOf(idx, ids)
        require(newParentId !in forbidden) { "cannot move a folder into itself" }
        var current = idx
        for (id in ids) {
            val e = current.entries.first { it.id == id }
            val name = uniqueName(current, newParentId, e.name, ignoreId = id)
            current = current.copy(entries = current.entries.map { if (it.id == id) it.copy(parentId = newParentId, name = name) else it })
        }
        current
    }

    /** Deletes entries (folders recursively). Their keys are forgotten and their blocks scrubbed. */
    fun delete(ids: Collection<Long>) {
        synchronized(lock) {
            ensureOpen()
            val doomed = descendantsOf(index, ids)
            val removed = index.entries.filter { it.id in doomed }
            val next = index.copy(entries = index.entries.filter { it.id !in doomed })
            // New epoch: old copies of the index (which still hold the deleted files' keys)
            // become undecryptable once the header records the new epoch.
            val epoch = crypto.randomBytes(Crypto.KEY_BYTES)
            persist(next, newEpoch = epoch) { vault.setEpoch(slotIndex, epoch) }
            index = next
            removed.forEach { e ->
                e.ownedBlocks().forEach { pool.scrub(it); allocator.release(it) }
                fileKeys.remove(e.id)
            }
        }
    }

    fun setThumbnail(id: Long, image: ByteArray) {
        synchronized(lock) {
            ensureOpen()
            val e = index.entries.first { it.id == id && !it.isFolder }
            val cap = codec.capacity
            val count = (image.size + cap - 1) / cap
            val blocks = List(count) { allocator.allocate() }
            val chunk = ByteArray(cap)
            blocks.forEachIndexed { seq, b ->
                chunk.fill(0)
                image.copyInto(chunk, 0, seq * cap, minOf(image.size, (seq + 1) * cap))
                codec.write(b, chunk, fileKeys, id, ad("th", b, id, seq.toLong()))
            }
            chunk.wipe()
            val old = e.thumbnailBlocks
            commit(index.copy(entries = index.entries.map { if (it.id == id) it.copy(thumbnailBlocks = blocks, thumbnailSize = image.size) else it }))
            old.forEach { pool.scrub(it); allocator.release(it) }
        }
    }

    fun thumbnail(id: Long): ByteArray? = synchronized(lock) {
        ensureOpen()
        val e = index.entries.firstOrNull { it.id == id } ?: return null
        if (e.thumbnailSize == 0) return null
        val out = ByteArray(e.thumbnailSize)
        val cap = codec.capacity
        e.thumbnailBlocks.forEachIndexed { seq, b ->
            val plain = codec.read(b, fileKeys, id, ad("th", b, id, seq.toLong())) ?: throw CorruptedDataException("thumbnail block failed authentication")
            val from = seq * cap
            plain.copyInto(out, from, 0, minOf(cap, out.size - from))
            plain.wipe()
        }
        out
    }

    // ---------------------------------------------------------------- reading

    fun openReader(id: Long): VaultFileReader {
        val e = read { index.entries.first { it.id == id && !it.isFolder } }
        return VaultFileReader(this, e)
    }

    /** Convenience for small files (text, images). */
    fun readAll(id: Long): ByteArray = openReader(id).use { r ->
        require(r.size <= Int.MAX_VALUE) { "file too large to load at once" }
        val out = ByteArray(r.size.toInt())
        var pos = 0
        while (pos < out.size) {
            val n = r.read(pos.toLong(), out, pos, out.size - pos)
            if (n <= 0) break
            pos += n
        }
        out
    }

    fun exportTo(id: Long, sink: BufferedSink, onProgress: (Long) -> Unit = {}) {
        openReader(id).use { r ->
            val buf = ByteArray(codec.capacity)
            var pos = 0L
            while (pos < r.size) {
                val n = r.read(pos, buf, 0, buf.size)
                if (n <= 0) break
                sink.write(buf, 0, n)
                pos += n
                onProgress(pos)
            }
            buf.wipe()
            sink.flush()
        }
    }

    internal fun readFileBlock(entry: Entry, seq: Int): ByteArray = synchronized(lock) {
        ensureOpen()
        val b = entry.blocks[seq]
        codec.read(b, fileKeys, entry.id, ad("fd", b, entry.id, seq.toLong())) ?: throw CorruptedDataException("data block failed authentication")
    }

    // ---------------------------------------------------------------- passwords & vault management

    /**
     * Password rules for every "set a password" action (change, panic, hidden):
     *
     *  - The same as this vault's current password → [SamePasswordException].
     *  - From the main vault, a password that happens to open the panic or hidden slot →
     *    that slot is **silently destroyed** and the operation proceeds. There is deliberately
     *    no "already in use" error: under coercion that error would let an attacker test
     *    guesses for the hidden password, without limit and without touching the attempt counter.
     *  - From the hidden vault, a password that opens another slot → [PasswordUnavailableException].
     */
    fun changePassword(newPassword: ByteArray) = vault.changePassword(this, newPassword)

    /** Only from the main vault: the panic slot must carry the main vault's key. */
    fun setPanicPassword(password: ByteArray, mode: PanicMode) {
        check(kind == VaultKind.MAIN) { "panic password is configured from the main vault" }
        vault.setPanicPassword(this, password, mode)
    }

    /** The main vault cannot know whether a panic password exists; this simply fills the slot with noise. */
    fun clearPanicPassword() {
        check(kind == VaultKind.MAIN)
        vault.clearSlot(Vault.panicSlot)
    }

    val canCreateHiddenVault: Boolean get() = kind == VaultKind.MAIN && vault.reserveAllowsHidden()

    /**
     * Creates (or replaces!) the hidden vault. The main vault cannot see whether one exists, so
     * if it does, it is destroyed. That is deliberate: under coercion, being forced to "create a
     * hidden vault" destroys the real one instead of revealing it. See [changePassword] for the
     * password rules.
     */
    fun createHiddenVault(password: ByteArray) {
        check(canCreateHiddenVault)
        vault.createHidden(this, password)
    }

    /** From inside the hidden vault: destroy it (its slot becomes noise). */
    fun destroyHiddenVault() {
        check(kind == VaultKind.HIDDEN)
        vault.clearSlot(Vault.hiddenSlot)
        close()
    }

    var maxAttempts: Int
        get() = vault.maxAttempts()
        set(value) = vault.setMaxAttempts(value)

    val protectionLevel get() = vault.protectionLevel

    /** Crypto-erases everything on this phone: every vault, every password. */
    fun destroyEverything() {
        close()
        vault.destroyEverything()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { pool.touchAll() }
            store.close()
            payload.masterKey.close()
            fileKeys.close()
            pool.close()
        }
        vault.sessionClosed(this)
    }

    // ---------------------------------------------------------------- internals

    private fun ensureOpen() {
        if (closed) throw VaultLockedException()
    }

    private inline fun <T> read(block: () -> T): T = synchronized(lock) {
        ensureOpen()
        block()
    }

    private fun reserveId(): Long = synchronized(lock) {
        ensureOpen()
        val id = index.nextId
        index = index.copy(nextId = id + 1)
        id
    }

    private fun mutate(change: (VaultIndex) -> VaultIndex) {
        synchronized(lock) {
            ensureOpen()
            commit(change(index))
        }
    }

    private fun commit(next: VaultIndex) {
        persist(next)
        index = next
    }

    /** Writes [next] with the file keys copied in for serialisation, and wipes those copies. */
    private fun persist(next: VaultIndex, newEpoch: ByteArray? = null, persistEpoch: () -> Unit = {}) {
        val copies = ArrayList<ByteArray>()
        val withKeys = next.copy(
            entries = next.entries.map { e ->
                if (e.id in fileKeys) e.copy(key = fileKeys.copyOut(e.id).also { copies += it }) else e
            },
        )
        try {
            store.commit(withKeys, allocator, newEpoch, persistEpoch)
        } finally {
            copies.forEach { it.wipe() }
        }
    }

    /** Moves every file key of a freshly loaded index into [fileKeys]. */
    private fun withoutKeys(loaded: VaultIndex): VaultIndex = loaded.copy(
        entries = loaded.entries.map { e ->
            if (e.key.isEmpty()) e else {
                fileKeys.put(e.id, e.key)
                e.copy(key = NO_KEY)
            }
        },
    )

    private fun requireFolder(idx: VaultIndex, id: Long) {
        require(id == Entry.ROOT_ID || idx.entries.any { it.id == id && it.isFolder }) { "no such folder: $id" }
    }

    private fun descendantsOf(idx: VaultIndex, roots: Collection<Long>): Set<Long> {
        val result = roots.toHashSet()
        var frontier = roots.toSet()
        while (frontier.isNotEmpty()) {
            val next = idx.entries.filter { it.parentId in frontier && it.id !in result }.map { it.id }.toSet()
            result += next
            frontier = next
        }
        return result
    }

    private fun uniqueName(idx: VaultIndex, parentId: Long, wanted: String, ignoreId: Long = -1): String {
        val clean = wanted.trim().replace('/', '_').ifEmpty { "file" }
        val taken = idx.entries.filter { it.parentId == parentId && it.id != ignoreId }.map { it.name.lowercase() }.toSet()
        if (clean.lowercase() !in taken) return clean
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        val stem = clean.substring(0, dot)
        val ext = clean.substring(dot)
        var n = 2
        while ("$stem ($n)$ext".lowercase() in taken) n++
        return "$stem ($n)$ext"
    }
}

/**
 * Random-access reader over one encrypted file. Decrypts one block at a time and keeps only
 * the most recent block in memory. Used by the image/text viewers, the video player
 * (seeking works without decrypting the beginning) and export.
 */
private val NO_KEY = ByteArray(0)

class VaultFileReader internal constructor(private val session: VaultSession, private val entry: Entry) : AutoCloseable {
    val size: Long = entry.size
    val name: String = entry.name
    val mimeType: String = entry.mimeType

    private val cap = session.blockCapacity
    private var cachedSeq = -1
    private var cached: ByteArray? = null

    /** Reads up to [length] bytes at [position]. Returns -1 at end of file. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (length == 0) return 0
        val seq = (position / cap).toInt()
        if (seq != cachedSeq) {
            cached?.wipe()
            cached = session.readFileBlock(entry, seq)
            cachedSeq = seq
        }
        val within = (position % cap).toInt()
        val n = minOf(length.toLong(), (cap - within).toLong(), size - position).toInt()
        cached!!.copyInto(into, offset, within, within + n)
        return n
    }

    override fun close() {
        cached?.wipe()
        cached = null
        cachedSeq = -1
    }
}

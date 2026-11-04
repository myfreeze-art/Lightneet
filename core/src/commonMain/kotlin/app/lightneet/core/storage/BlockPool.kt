package app.lightneet.core.storage

import app.lightneet.core.crypto.Crypto
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.FileHandle
import okio.FileSystem
import okio.Path

/**
 * The pool: a sequence of equally sized blocks stored in segment files
 * (`pool/s000000.dat`, `pool/s000001.dat`, …), each holding [segmentBlocks] blocks.
 *
 * The pool knows nothing about encryption or ownership. Every block it hands out is either
 * random noise (free) or ciphertext (used) — indistinguishable from the outside.
 */
class BlockPool(
    private val fs: FileSystem,
    dir: Path,
    val blockSize: Int,
    val segmentBlocks: Int,
    private val crypto: Crypto,
) : AutoCloseable {
    private val poolDir = dir / "pool"
    private val lock = SynchronizedObject()
    private val handles = HashMap<Int, FileHandle>()

    init {
        fs.createDirectories(poolDir)
    }

    val segmentBytes: Long get() = blockSize.toLong() * segmentBlocks

    var segmentCount: Int = countSegments()
        private set

    val blockCount: Long get() = synchronized(lock) { segmentCount.toLong() * segmentBlocks }

    private fun countSegments(): Int {
        var n = 0
        while (fs.exists(segmentPath(n))) n++
        return n
    }

    private fun segmentPath(n: Int) = poolDir / "s${n.toString().padStart(6, '0')}.dat"

    private fun handle(segment: Int): FileHandle =
        handles.getOrPut(segment) { fs.openReadWrite(segmentPath(segment), mustCreate = false, mustExist = true) }

    fun read(index: Long, into: ByteArray) {
        require(into.size == blockSize)
        synchronized(lock) {
            require(index in 0 until segmentCount.toLong() * segmentBlocks) { "block $index out of range" }
            val h = handle((index / segmentBlocks).toInt())
            val offset = (index % segmentBlocks) * blockSize
            var done = 0
            while (done < blockSize) {
                val n = h.read(offset + done, into, done, blockSize - done)
                check(n > 0) { "short read in block $index" }
                done += n
            }
        }
    }

    fun write(index: Long, data: ByteArray) {
        require(data.size == blockSize)
        synchronized(lock) {
            require(index in 0 until segmentCount.toLong() * segmentBlocks) { "block $index out of range" }
            val h = handle((index / segmentBlocks).toInt())
            h.write((index % segmentBlocks) * blockSize, data, 0, blockSize)
        }
    }

    /** Overwrites a block with fresh noise, making it look exactly like any other block. */
    fun scrub(index: Long) {
        val noise = ByteArray(blockSize)
        crypto.fillRandom(noise)
        write(index, noise)
    }

    /**
     * Appends [segments] segments filled with random bytes. A segment file is first written
     * under a temporary name and renamed when complete, so a crash never leaves a short one.
     */
    fun grow(segments: Int, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        synchronized(lock) {
            val total = segments * segmentBytes
            var written = 0L
            val chunk = ByteArray(blockSize)
            repeat(segments) {
                val target = segmentPath(segmentCount)
                val tmp = poolDir / "growing.tmp"
                fs.openReadWrite(tmp, mustCreate = false, mustExist = false).use { h ->
                    h.resize(0)
                    for (b in 0 until segmentBlocks) {
                        crypto.fillRandom(chunk)
                        h.write(b.toLong() * blockSize, chunk, 0, blockSize)
                        written += blockSize
                        if (b % 64 == 63) onProgress(written, total)
                    }
                    h.flush()
                }
                fs.atomicMove(tmp, target)
                segmentCount++
            }
            onProgress(total, total)
        }
    }

    /**
     * Updates the modification time of every segment file without changing a single byte:
     * the first byte of each segment is read and written back.
     *
     * Every session does this when it opens and closes. Otherwise file timestamps would show
     * that some segment changed at a time when the main vault recorded no activity — evidence
     * that another (hidden) vault exists.
     */
    fun touchAll() {
        synchronized(lock) {
            val one = ByteArray(1)
            for (seg in 0 until segmentCount) {
                val h = handle(seg)
                if (h.read(0, one, 0, 1) == 1) h.write(0, one, 0, 1)
                h.flush()
            }
        }
    }

    /** fsync every open segment. Called before a commit point (superblock write) is relied on. */
    fun sync() {
        synchronized(lock) { handles.values.forEach { it.flush() } }
    }

    override fun close() {
        synchronized(lock) {
            handles.values.forEach { runCatching { it.close() } }
            handles.clear()
        }
    }

    fun deleteAll() {
        close()
        fs.deleteRecursively(poolDir, mustExist = false)
    }
}

package app.lightneet.core.storage

import app.lightneet.core.device.DeviceBinding
import app.lightneet.core.format.Header
import app.lightneet.core.util.ByteReader
import app.lightneet.core.util.ByteWriter
import okio.FileSystem
import okio.Path

/**
 * Persists the [Header] sealed under a device-bound key.
 *
 * File layout: `u32 keyIdLength | keyId (UTF-8) | sealed header`.
 * The key id is not secret: without the secure hardware it is just a name.
 *
 * Writes are atomic (write temp file, fsync, rename), so the file on disk always pairs a
 * sealed header with the key id that can open it.
 */
class HeaderStore(
    private val fs: FileSystem,
    dir: Path,
    private val device: DeviceBinding,
) {
    private val file = dir / "header.bin"
    private val tmp = dir / "header.tmp"

    fun exists(): Boolean = fs.exists(file)

    /** Returns null when there is no header, or when it can no longer be opened (key destroyed). */
    fun read(): Loaded? {
        if (!fs.exists(file)) return null
        val bytes = fs.read(file) { readByteArray() }
        if (bytes.size < 4) return null
        val r = ByteReader(bytes)
        val idLength = r.u32()
        if (idLength !in 1..256 || bytes.size < 4 + idLength) return null
        val keyId = r.raw(idLength).decodeToString()
        val sealed = bytes.copyOfRange(r.pos, bytes.size)
        val plain = device.open(keyId, sealed) ?: return null
        val header = Header.decode(plain) ?: return null
        return Loaded(keyId, header)
    }

    /**
     * Seals [header] under a brand-new device key, atomically replaces the file, and only
     * then destroys [previousKeyId]. Any older copy of the file becomes permanently unreadable.
     */
    fun writeRotating(header: Header, previousKeyId: String?): String {
        val keyId = device.createKey()
        writeWith(keyId, header)
        if (previousKeyId != null && previousKeyId != keyId) device.destroyKey(previousKeyId)
        return keyId
    }

    private fun writeWith(keyId: String, header: Header) {
        val sealed = device.seal(keyId, header.encode())
        val id = keyId.encodeToByteArray()
        val out = ByteWriter(ByteArray(4 + id.size + sealed.size)).u32(id.size).raw(id).raw(sealed).bytes
        fs.openReadWrite(tmp, mustCreate = false, mustExist = false).use { handle ->
            handle.resize(0)
            handle.write(0, out, 0, out.size)
            handle.flush()
        }
        fs.atomicMove(tmp, file)
    }

    /** Crypto-erases the header: destroys its key and deletes the file. */
    fun destroy(keyId: String?) {
        if (keyId != null) device.destroyKey(keyId)
        fs.delete(file, mustExist = false)
        fs.delete(tmp, mustExist = false)
    }

    class Loaded(val keyId: String, val header: Header)
}

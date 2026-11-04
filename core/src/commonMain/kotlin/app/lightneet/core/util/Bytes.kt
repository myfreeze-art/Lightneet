package app.lightneet.core.util

/** Little-endian cursor over a byte array; used for the fixed binary layouts on disk. */
internal class ByteWriter(val bytes: ByteArray, var pos: Int = 0) {
    fun u8(v: Int) = apply { bytes[pos++] = v.toByte() }
    fun u32(v: Int) = apply { repeat(4) { bytes[pos++] = (v ushr (8 * it)).toByte() } }
    fun u64(v: Long) = apply { repeat(8) { bytes[pos++] = (v ushr (8 * it)).toByte() } }
    fun raw(src: ByteArray) = apply { src.copyInto(bytes, pos); pos += src.size }
    fun skip(n: Int) = apply { pos += n }
}

internal class ByteReader(val bytes: ByteArray, var pos: Int = 0) {
    fun u8(): Int = bytes[pos++].toInt() and 0xFF
    fun u32(): Int {
        var v = 0
        repeat(4) { v = v or ((bytes[pos++].toInt() and 0xFF) shl (8 * it)) }
        return v
    }
    fun u64(): Long {
        var v = 0L
        repeat(8) { v = v or ((bytes[pos++].toLong() and 0xFF) shl (8 * it)) }
        return v
    }
    fun raw(n: Int): ByteArray = bytes.copyOfRange(pos, pos + n).also { pos += n }
    fun skip(n: Int) = apply { pos += n }
}

/** Builds associated data for AEAD calls: a short domain tag followed by little-endian numbers. */
internal fun ad(tag: String, vararg numbers: Long): ByteArray {
    val t = tag.encodeToByteArray()
    val w = ByteWriter(ByteArray(t.size + 8 * numbers.size))
    w.raw(t)
    numbers.forEach { w.u64(it) }
    return w.bytes
}

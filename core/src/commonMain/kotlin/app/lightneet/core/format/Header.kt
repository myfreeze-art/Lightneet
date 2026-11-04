package app.lightneet.core.format

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.util.ByteReader
import app.lightneet.core.util.ByteWriter

/**
 * How much of the block pool is set aside for a hidden vault.
 *
 * The hidden region is interleaved with the main one (every 4th or every 2nd block), so it
 * grows together with the pool and the two vaults can never overwrite each other. Free
 * blocks of either region look exactly like used ones, so the reserve existing says nothing
 * about whether a hidden vault is actually in use.
 */
enum class HiddenReserve(val code: Int, val stride: Int) {
    NONE(0, 0),
    QUARTER(1, 4),
    HALF(2, 2);

    fun isHidden(blockIndex: Long): Boolean = stride != 0 && blockIndex % stride == (stride - 1).toLong()
}

data class KdfParams(val opsLimit: Long, val memLimitBytes: Long) {
    init {
        require(opsLimit in 1..0xFFFF_FFFFL)
        require(memLimitBytes >= 8 * 1024 && memLimitBytes % 1024 == 0L)
    }

    companion object {
        /** Used when calibration is impossible. ~1 s and 256 MiB on a mid-range phone. */
        val DEFAULT = KdfParams(opsLimit = 3, memLimitBytes = 256L * 1024 * 1024)
    }
}

/**
 * The vault header, as it looks *inside* the device-bound seal. See docs/SPEC.md §3.
 *
 * Every field except the slots is non-secret configuration. The four slots are always
 * present and always the same size; unused ones are random bytes, so nobody can tell how
 * many passwords exist.
 */
class Header(
    val blockSize: Int,
    val segmentBlocks: Int,
    val reserve: HiddenReserve,
    val kdf: KdfParams,
    val salt: ByteArray,
    var failedAttempts: Int,
    var maxAttempts: Int,
    val slots: Array<ByteArray>,
) {
    init {
        require(salt.size == Crypto.SALT_BYTES)
        require(slots.size == SLOT_COUNT && slots.all { it.size == SLOT_BYTES })
    }

    fun encode(): ByteArray {
        val w = ByteWriter(ByteArray(SIZE))
        w.raw(MAGIC).u8(VERSION).u8(reserve.code).skip(2)
        w.u32(blockSize).u32(segmentBlocks)
        w.u32(kdf.opsLimit.toInt()).u32((kdf.memLimitBytes / 1024).toInt())
        w.raw(salt).u32(failedAttempts).u32(maxAttempts)
        slots.forEach { w.raw(it) }
        check(w.pos == SIZE)
        return w.bytes
    }

    fun copy(): Header = Header(
        blockSize, segmentBlocks, reserve, kdf, salt.copyOf(), failedAttempts, maxAttempts,
        Array(SLOT_COUNT) { slots[it].copyOf() },
    )

    companion object {
        val MAGIC = "LNV1".encodeToByteArray()
        const val VERSION = 1
        const val SLOT_COUNT = 4
        const val SLOT_PAYLOAD_BYTES = 96
        const val SLOT_BYTES = Crypto.NONCE_BYTES + SLOT_PAYLOAD_BYTES + Crypto.TAG_BYTES
        const val SIZE = 48 + SLOT_COUNT * SLOT_BYTES

        /** Slot positions have fixed roles; the role is also written inside the encrypted payload. */
        const val SLOT_MAIN = 0
        const val SLOT_PANIC = 1
        const val SLOT_HIDDEN = 2
        const val SLOT_SPARE = 3

        fun decode(bytes: ByteArray): Header? {
            if (bytes.size != SIZE) return null
            val r = ByteReader(bytes)
            if (!r.raw(4).contentEquals(MAGIC)) return null
            if (r.u8() != VERSION) return null
            val reserveCode = r.u8()
            val reserve = HiddenReserve.entries.firstOrNull { it.code == reserveCode } ?: return null
            r.skip(2)
            val blockSize = r.u32()
            val segmentBlocks = r.u32()
            val ops = r.u32().toLong() and 0xFFFF_FFFFL
            val memKiB = r.u32().toLong() and 0xFFFF_FFFFL
            val salt = r.raw(Crypto.SALT_BYTES)
            val failed = r.u32()
            val max = r.u32()
            val slots = Array(SLOT_COUNT) { r.raw(SLOT_BYTES) }
            return Header(blockSize, segmentBlocks, reserve, KdfParams(ops, memKiB * 1024), salt, failed, max, slots)
        }
    }
}

enum class SlotType(val code: Int) { MAIN(1), HIDDEN(2), PANIC(3) }

enum class PanicMode(val code: Int) {
    /** Destroy the hidden vault, then open the main vault as if the main password had been typed. */
    DESTROY_HIDDEN(1),

    /** Destroy every vault and open a brand-new empty one that answers to the panic password. */
    DESTROY_ALL(2),
}

/** Decrypted contents of a key slot. */
class SlotPayload(
    val type: SlotType,
    val panicMode: PanicMode?,
    /** For MAIN/HIDDEN: that vault's master key. For PANIC: the main vault's master key. */
    val masterKey: ByteArray,
    val superblockA: Long,
    val superblockB: Long,
) {
    fun encode(): ByteArray {
        val w = ByteWriter(ByteArray(Header.SLOT_PAYLOAD_BYTES))
        w.u8(type.code).u8(panicMode?.code ?: 0).skip(6)
        w.raw(masterKey).u64(superblockA).u64(superblockB)
        return w.bytes
    }

    companion object {
        fun decode(bytes: ByteArray): SlotPayload? {
            val r = ByteReader(bytes)
            val typeCode = r.u8()
            val type = SlotType.entries.firstOrNull { it.code == typeCode } ?: return null
            val modeCode = r.u8()
            val mode = PanicMode.entries.firstOrNull { it.code == modeCode }
            r.skip(6)
            return SlotPayload(type, mode, r.raw(Crypto.KEY_BYTES), r.u64(), r.u64())
        }
    }
}

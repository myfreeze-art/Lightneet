package app.lightneet.core.format

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.SecretKey
import app.lightneet.core.crypto.wipe
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
 * many passwords exist. Each slot position also has a 32-byte *epoch*: random bytes mixed
 * into that vault's index keys and replaced whenever files are deleted, so an old copy of the
 * pool cannot reveal deleted files even if the password is obtained later.
 */
class Header(
    val blockSize: Int,
    val segmentBlocks: Int,
    val reserve: HiddenReserve,
    val kdf: KdfParams,
    val salt: ByteArray,
    var failedAttempts: Int,
    var maxAttempts: Int,
    val pepperKeyId: String,
    val epochs: Array<ByteArray>,
    val slots: Array<ByteArray>,
) {
    init {
        require(salt.size == Crypto.SALT_BYTES)
        require(pepperKeyId.encodeToByteArray().size in 1..PEPPER_ID_BYTES)
        require(epochs.size == SLOT_COUNT && epochs.all { it.size == EPOCH_BYTES })
        require(slots.size == SLOT_COUNT && slots.all { it.size == SLOT_BYTES })
    }

    fun encode(): ByteArray {
        val w = ByteWriter(ByteArray(SIZE))
        w.raw(MAGIC).u8(VERSION).u8(reserve.code).skip(2)
        w.u32(blockSize).u32(segmentBlocks)
        w.u32(kdf.opsLimit.toInt()).u32((kdf.memLimitBytes / 1024).toInt())
        w.raw(salt).u32(failedAttempts).u32(maxAttempts)
        val id = pepperKeyId.encodeToByteArray()
        w.raw(id).skip(PEPPER_ID_BYTES - id.size)
        epochs.forEach { w.raw(it) }
        slots.forEach { w.raw(it) }
        check(w.pos == SIZE)
        return w.bytes
    }

    companion object {
        val MAGIC = "LNV2".encodeToByteArray()
        const val VERSION = 2
        const val SLOT_COUNT = 4
        const val SLOT_PAYLOAD_BYTES = 96
        const val SLOT_BYTES = Crypto.NONCE_BYTES + SLOT_PAYLOAD_BYTES + Crypto.TAG_BYTES
        const val PEPPER_ID_BYTES = 64
        const val EPOCH_BYTES = 32
        const val SIZE = 48 + PEPPER_ID_BYTES + SLOT_COUNT * EPOCH_BYTES + SLOT_COUNT * SLOT_BYTES

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
            val idBytes = r.raw(PEPPER_ID_BYTES)
            val idLength = idBytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) PEPPER_ID_BYTES else it }
            if (idLength == 0) return null
            val pepperId = idBytes.copyOfRange(0, idLength).decodeToString()
            val epochs = Array(SLOT_COUNT) { r.raw(EPOCH_BYTES) }
            val slots = Array(SLOT_COUNT) { r.raw(SLOT_BYTES) }
            return runCatching {
                Header(blockSize, segmentBlocks, reserve, KdfParams(ops, memKiB * 1024), salt, failed, max, pepperId, epochs, slots)
            }.getOrNull()
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
    val masterKey: SecretKey,
    val superblockA: Long,
    val superblockB: Long,
) {
    fun encode(): ByteArray {
        val w = ByteWriter(ByteArray(Header.SLOT_PAYLOAD_BYTES))
        w.u8(type.code).u8(panicMode?.code ?: 0).skip(6)
        val key = ByteArray(masterKey.size)
        masterKey.copyInto(key)
        w.raw(key).u64(superblockA).u64(superblockB)
        key.wipe()
        return w.bytes
    }

    companion object {
        /** The master key moves into guarded memory; [bytes] still holds it and must be wiped by the caller. */
        fun decode(bytes: ByteArray, crypto: Crypto): SlotPayload? {
            val r = ByteReader(bytes)
            val typeCode = r.u8()
            val type = SlotType.entries.firstOrNull { it.code == typeCode } ?: return null
            val modeCode = r.u8()
            val mode = PanicMode.entries.firstOrNull { it.code == modeCode }
            r.skip(6)
            val key = r.raw(Crypto.KEY_BYTES)
            val a = r.u64()
            val b = r.u64()
            return SlotPayload(type, mode, crypto.secretKey(key), a, b)
        }
    }
}

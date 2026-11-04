package app.lightneet.core

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.opened
import app.lightneet.core.crypto.sealed
import app.lightneet.core.crypto.subkey
import app.lightneet.core.format.Header
import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.format.KdfParams
import app.lightneet.core.format.PanicMode
import app.lightneet.core.format.SlotPayload
import app.lightneet.core.format.SlotType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CryptoAndFormatTest {
    private val key = crypto.randomBytes(32)
    private val nonce = crypto.randomBytes(24)

    @Test
    fun aeadRoundTripAndTamperDetection() {
        val msg = "secret".encodeToByteArray()
        val ct = crypto.sealed(msg, "ad".encodeToByteArray(), nonce, key)
        assertContentEquals(msg, crypto.opened(ct, "ad".encodeToByteArray(), nonce, key))
        assertNull(crypto.opened(ct, "other".encodeToByteArray(), nonce, key), "wrong associated data must fail")
        ct[0] = (ct[0] + 1).toByte()
        assertNull(crypto.opened(ct, "ad".encodeToByteArray(), nonce, key), "flipped byte must fail")
    }

    @Test
    fun argon2idIsDeterministicAndSaltSensitive() {
        val salt = ByteArray(Crypto.SALT_BYTES) { 1 }
        val a = crypto.argon2id("pw".pw(), salt, 1, 1 shl 20)
        val b = crypto.argon2id("pw".pw(), salt, 1, 1 shl 20)
        val c = crypto.argon2id("pw".pw(), ByteArray(Crypto.SALT_BYTES) { 2 }, 1, 1 shl 20)
        assertContentEquals(a, b)
        assertFalse(a.contentEquals(c))
    }

    /** Known-answer test: the reference Argon2id output so other implementations (iOS) can be checked. */
    @Test
    fun argon2idKnownAnswer() {
        val out = crypto.argon2id("password".pw(), "somesaltsomesalt".pw(), 2, 1 shl 16)
        assertEquals(32, out.size)
        // Computed independently with argon2-cffi (reference C implementation); any port must match.
        assertEquals(ARGON_VECTOR, out.toHex())
    }

    @Test
    fun subkeysAreIndependent() {
        val master = crypto.randomBytes(32)
        assertFalse(crypto.subkey(master, "index").contentEquals(crypto.subkey(master, "superblock")))
    }

    @Test
    fun headerRoundTrip() {
        val h = Header(
            4096, 16, HiddenReserve.HALF, KdfParams(3, 1 shl 20), crypto.randomBytes(16), 2, 10,
            Array(Header.SLOT_COUNT) { crypto.randomBytes(Header.SLOT_BYTES) },
        )
        val d = assertNotNull(Header.decode(h.encode()))
        assertEquals(HiddenReserve.HALF, d.reserve)
        assertEquals(2, d.failedAttempts)
        assertEquals(10, d.maxAttempts)
        assertEquals(1L shl 20, d.kdf.memLimitBytes)
        for (i in 0 until Header.SLOT_COUNT) assertContentEquals(h.slots[i], d.slots[i])
    }

    @Test
    fun slotPayloadRoundTrip() {
        val p = SlotPayload(SlotType.PANIC, PanicMode.DESTROY_ALL, crypto.randomBytes(32), 7, 11)
        val d = assertNotNull(SlotPayload.decode(p.encode()))
        assertEquals(SlotType.PANIC, d.type)
        assertEquals(PanicMode.DESTROY_ALL, d.panicMode)
        assertContentEquals(p.masterKey, d.masterKey)
        assertEquals(7, d.superblockA)
        assertEquals(11, d.superblockB)
    }

    @Test
    fun hiddenReserveInterleaving() {
        assertEquals(listOf(3L, 7L, 11L), (0L until 12).filter { HiddenReserve.QUARTER.isHidden(it) })
        assertEquals(listOf(1L, 3L, 5L), (0L until 6).filter { HiddenReserve.HALF.isHidden(it) })
        assertEquals(emptyList(), (0L until 6).filter { HiddenReserve.NONE.isHidden(it) })
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ARGON_VECTOR = "1a98fba8e6394425c9bdc333a9c750aaf811df070f5fb4a5788e1cf4db5793a1"
    }
}

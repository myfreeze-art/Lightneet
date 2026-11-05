package app.lightneet.core

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.SecretKey
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    /** Keys in guarded memory must give exactly the same results as the plain-array functions. */
    @Test
    fun guardedKeysMatchPlainKeys() {
        val raw = crypto.randomBytes(32)
        val guarded = crypto.secretKey(raw.copyOf())
        val msg = "secret".encodeToByteArray()
        val ad = "ad".encodeToByteArray()

        val ct = crypto.sealed(msg, ad, nonce, guarded)
        assertContentEquals(ct, crypto.sealed(msg, ad, nonce, raw), "same key, same nonce, same ciphertext")
        assertContentEquals(msg, crypto.opened(ct, ad, nonce, guarded))
        assertNull(crypto.opened(ct, "other".encodeToByteArray(), nonce, guarded))

        val label = "lightneet/v2/index".encodeToByteArray()
        crypto.deriveKey(guarded, label).use { derived ->
            assertContentEquals(crypto.keyedHash(raw, label), derived.bytes())
        }
        guarded.close()
    }

    @Test
    fun guardedKeyWipesItsSourceAndRefusesUseAfterClose() {
        val source = crypto.randomBytes(32)
        val key = crypto.secretKey(source)
        assertTrue(source.all { it == 0.toByte() }, "the JVM copy is wiped at once")
        assertEquals(32, key.size)
        key.close()
        key.close() // idempotent
        assertTrue(key.isClosed)
        assertFailsWith<IllegalStateException> { crypto.sealed("x".encodeToByteArray(), ByteArray(0), nonce, key) }
    }

    @Test
    fun randomGuardedKeysDiffer() {
        val a = crypto.randomSecretKey()
        val b = crypto.randomSecretKey()
        assertFalse(a.bytes().contentEquals(b.bytes()))
        assertFalse(a.bytes().all { it == 0.toByte() })
        a.close(); b.close()
    }

    @Test
    fun keyRingMatchesPlainKeysAndGrows() {
        val ring = crypto.newKeyRing()
        val raw = (0 until 200).map { crypto.randomBytes(32) }
        raw.forEachIndexed { i, k -> ring.put(i.toLong(), k.copyOf()) } // grows 64 → 128 → 256
        assertEquals(200, ring.size)
        val msg = "secret".encodeToByteArray()
        val ad = "ad".encodeToByteArray()
        raw.forEachIndexed { i, k ->
            val out = ByteArray(msg.size + Crypto.TAG_BYTES)
            crypto.seal(out, msg, ad, nonce, ring, i.toLong())
            assertContentEquals(crypto.sealed(msg, ad, nonce, k), out, "slot $i survived growth")
            assertContentEquals(k, ring.copyOut(i.toLong()))
        }
        ring.remove(5)
        assertFalse(5L in ring)
        assertFailsWith<IllegalStateException> { ring.copyOut(5) }
        ring.putRandom(1000) // reuses the freed slot
        assertContentEquals(raw[6], ring.copyOut(6))
        assertFalse(ring.copyOut(1000).contentEquals(raw[5]))
        val src = crypto.randomBytes(32)
        ring.put(2000, src)
        assertTrue(src.all { it == 0.toByte() }, "put wipes the source")
        ring.close()
        ring.close()
        assertFailsWith<IllegalStateException> { ring.copyOut(6) }
    }

    private fun SecretKey.bytes() = ByteArray(size).also { copyInto(it) }

    @Test
    fun headerRoundTrip() {
        val h = Header(
            4096, 16, HiddenReserve.HALF, KdfParams(3, 1 shl 20), crypto.randomBytes(16), 2, 10,
            "lightneet-p-abc",
            Array(Header.SLOT_COUNT) { crypto.randomBytes(Header.EPOCH_BYTES) },
            Array(Header.SLOT_COUNT) { crypto.randomBytes(Header.SLOT_BYTES) },
        )
        val d = assertNotNull(Header.decode(h.encode()))
        assertEquals(HiddenReserve.HALF, d.reserve)
        assertEquals(2, d.failedAttempts)
        assertEquals(10, d.maxAttempts)
        assertEquals(1L shl 20, d.kdf.memLimitBytes)
        assertEquals("lightneet-p-abc", d.pepperKeyId)
        for (i in 0 until Header.SLOT_COUNT) {
            assertContentEquals(h.slots[i], d.slots[i])
            assertContentEquals(h.epochs[i], d.epochs[i])
        }
    }

    @Test
    fun slotPayloadRoundTrip() {
        val p = SlotPayload(SlotType.PANIC, PanicMode.DESTROY_ALL, crypto.randomSecretKey(), 7, 11)
        val d = assertNotNull(SlotPayload.decode(p.encode(), crypto))
        assertEquals(SlotType.PANIC, d.type)
        assertEquals(PanicMode.DESTROY_ALL, d.panicMode)
        assertContentEquals(p.masterKey.bytes(), d.masterKey.bytes())
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

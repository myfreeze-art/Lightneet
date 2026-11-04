package app.lightneet.core

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.opened
import app.lightneet.core.device.DeviceResult
import app.lightneet.core.device.DeviceUnavailableException
import app.lightneet.core.format.Header
import app.lightneet.core.storage.HeaderStore
import app.lightneet.core.util.ad
import app.lightneet.core.vault.CorruptedDataException
import app.lightneet.core.vault.UnlockResult
import app.lightneet.core.vault.VaultFullException
import app.lightneet.core.vault.VaultSession
import app.lightneet.core.vault.VaultState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One test (at least) per hole found in the self-review; see docs/THREAT_MODEL.md. */
class SecurityRegressionTest {
    private val f = Fixture()

    private fun open(password: String): VaultSession =
        assertIs<UnlockResult.Opened>(f.vault().unlock(password.pw())).session

    /**
     * Hole #1: the device key used to be involved once (to unseal the header); afterwards the
     * slots could be brute-forced offline. Now every guess needs the hardware pepper.
     */
    @Test
    fun headerPlaintextPlusArgonIsNotEnoughToOpenASlot() {
        f.create().first.lock()
        // The attacker has root: one keystore call gives the full header plaintext.
        val loaded = assertIs<HeaderStore.ReadResult.Loaded>(HeaderStore(f.fs, f.dir, f.device).read())
        val h = loaded.header
        // ...and the correct password, run through Argon2id exactly like the app does.
        val argonOnly = crypto.argon2id(Fixture.MAIN_PW.pw(), h.salt, h.kdf.opsLimit, h.kdf.memLimitBytes)
        val slot = h.slots[Header.SLOT_MAIN]
        val opened = crypto.opened(
            slot.copyOfRange(Crypto.NONCE_BYTES, slot.size), ad("slot", 0) + h.salt,
            slot.copyOfRange(0, Crypto.NONCE_BYTES), argonOnly,
        )
        assertNull(opened, "without the hardware pepper the slot must not open — offline brute force is useless")
    }

    @Test
    fun everyGuessUsesTheHardware() {
        f.create().first.lock()
        val before = f.device.pepperCalls
        repeat(3) { f.vault().unlock("guess $it".pw()) }
        assertEquals(before + 3, f.device.pepperCalls)
    }

    @Test
    fun losingThePepperKeyMakesTheStorageUnreadable() {
        f.create().first.lock()
        f.device.keys.keys.filter { it.startsWith("lightneet-p-") }.forEach(f.device::destroyKey)
        assertEquals(UnlockResult.Unreadable, f.vault().unlock(Fixture.MAIN_PW.pw()))
    }

    /** Hole #10: a temporary keystore failure must never look like "storage destroyed". */
    @Test
    fun temporaryHardwareFailureIsNotFatal() {
        val (_, s) = f.create()
        s.import("keep.txt", bytes(100))
        s.close()
        f.device.unavailable = true
        assertEquals(VaultState.UNAVAILABLE, f.vault().state())
        assertEquals(UnlockResult.TemporarilyUnavailable, f.vault().unlock(Fixture.MAIN_PW.pw()))
        f.device.unavailable = false
        assertEquals(VaultState.READY, f.vault().state())
        assertEquals(listOf("keep.txt"), open(Fixture.MAIN_PW).entries().map { it.name })
    }

    /** Hole #14: keys orphaned by a crash mid-rotation could open an older header copy. */
    @Test
    fun orphanedDeviceKeysAreRemoved() {
        f.create().first.lock()
        f.device.keys["lightneet-s-orphan"] = crypto.randomBytes(32)
        open(Fixture.MAIN_PW).close()
        assertFalse("lightneet-s-orphan" in f.device.keys)
        assertTrue(f.device.keys.keys.any { it.startsWith("lightneet-p-") }, "the pepper key must survive")
    }

    /** Hole #5: the hidden vault growing the pool would betray it. */
    @Test
    fun hiddenVaultNeverGrowsThePool() {
        val (_, main) = f.create(segments = 1)
        main.createHiddenVault("hidden one two three".pw())
        main.close()
        val hidden = open("hidden one two three")
        val poolBefore = hidden.usage().poolBytes
        assertFailsWith<VaultFullException> { hidden.import("big.bin", bytes(Fixture.BLOCK * 6)) }
        assertEquals(poolBefore, hidden.usage().poolBytes)
        assertTrue(hidden.entries().isEmpty())

        hidden.growStorage(2) // an explicit, ordinary "enlarge storage"
        val e = hidden.import("big.bin", bytes(Fixture.BLOCK * 6))
        assertContentEquals(bytes(Fixture.BLOCK * 6), hidden.readAll(e.id))
    }

    /**
     * Hole #8: deleting a file used to leave its key readable in old copies of the index. A copy
     * of the pool taken before the deletion is now useless even with the password.
     */
    @Test
    fun oldPoolCopiesDoNotRevealDeletedFiles() {
        val (_, s) = f.create()
        val secret = s.import("secret.txt", "launch codes".encodeToByteArray())
        s.import("other.txt", bytes(10))
        s.close()
        val snapshot = poolFiles().associateWith { it.readBytes() }

        open(Fixture.MAIN_PW).apply { delete(listOf(secret.id)); close() }
        assertEquals(listOf("other.txt"), open(Fixture.MAIN_PW).entries().map { it.name })

        // The attacker puts back the old pool next to the current header and has the password.
        snapshot.forEach { (file, bytes) -> file.writeBytes(bytes) }
        assertFailsWith<CorruptedDataException> { f.vault().unlock(Fixture.MAIN_PW.pw()) }
    }

    @Test
    fun failedEpochRotationRollsBackTheDelete() {
        val (_, s) = f.create()
        val e = s.import("stay.txt", "still here".encodeToByteArray())
        f.device.unavailable = true
        assertFailsWith<DeviceUnavailableException> { s.delete(listOf(e.id)) }
        f.device.unavailable = false
        assertEquals(listOf("stay.txt"), s.entries().map { it.name })
        s.close()
        val again = open(Fixture.MAIN_PW)
        assertEquals("still here", again.readAll(again.entries().single().id).decodeToString())
    }

    /** Hole #6: segment timestamps must not reveal which vault was active. */
    @Test
    fun everySessionTouchesEverySegment() {
        f.create(segments = 3).second.close()
        poolFiles().forEach { it.setLastModified(1_000_000L) }
        open(Fixture.MAIN_PW).close()
        assertTrue(poolFiles().all { it.lastModified() > 1_000_000L }, "all segment mtimes must move")
    }

    @Test
    fun deviceResultForMissingKey() {
        assertEquals(DeviceResult.KeyGone, f.device.open("nope", ByteArray(40)))
    }

    private fun poolFiles(): List<File> =
        (f.dir / "pool").toFile().listFiles()!!.filter { it.name.endsWith(".dat") }.sortedBy { it.name }
}

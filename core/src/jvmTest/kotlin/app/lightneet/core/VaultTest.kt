package app.lightneet.core

import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.vault.CorruptedDataException
import app.lightneet.core.vault.Entry
import app.lightneet.core.vault.UnlockResult
import app.lightneet.core.vault.VaultKind
import app.lightneet.core.vault.VaultLockedException
import app.lightneet.core.vault.VaultSession
import app.lightneet.core.vault.VaultState
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultTest {
    private val f = Fixture()

    private fun open(password: String): VaultSession {
        val r = f.vault().unlock(password.pw())
        return assertIs<UnlockResult.Opened>(r, "expected '$password' to open a vault").session
    }

    @Test
    fun createImportReopenRead() {
        val (_, s) = f.create()
        val data = bytes(50_000) // spans 13 blocks of 4 KiB
        val e = s.import("photo.jpg", data)
        assertEquals(50_000, e.size)
        s.close()

        val s2 = open(Fixture.MAIN_PW)
        val again = s2.children(Entry.ROOT_ID).single()
        assertEquals("photo.jpg", again.name)
        assertContentEquals(data, s2.readAll(again.id))

        // Random access straight into the middle, as the video player does when seeking.
        s2.openReader(again.id).use { r ->
            val buf = ByteArray(100)
            var got = 0
            while (got < 100) got += r.read(31_000L + got, buf, got, 100 - got)
            assertContentEquals(data.copyOfRange(31_000, 31_100), buf)
            assertEquals(-1, r.read(50_000, buf, 0, 1))
        }
    }

    @Test
    fun emptyAndExactBlockSizedFiles() {
        val (_, s) = f.create()
        val cap = s.blockCapacity
        val empty = s.import("empty.txt", ByteArray(0))
        val exact = s.import("exact.bin", bytes(cap * 2))
        assertContentEquals(ByteArray(0), s.readAll(empty.id))
        assertContentEquals(bytes(cap * 2), s.readAll(exact.id))
    }

    @Test
    fun poolGrowsForFilesBiggerThanThePool() {
        val (_, s) = f.create(segments = 1) // 16 blocks * 4 KiB = 64 KiB
        val big = bytes(400_000)
        val e = s.import("video.mp4", big)
        assertTrue(s.usage().poolBytes > 400_000)
        s.close()
        assertContentEquals(big, open(Fixture.MAIN_PW).readAll(e.id))
    }

    @Test
    fun wrongPasswordIsCountedAndPersisted() {
        val (v, s) = f.create()
        s.maxAttempts = 5
        v.lock()
        assertEquals(4, assertIs<UnlockResult.WrongPassword>(f.vault().unlock("nope".pw())).attemptsLeft)
        // A brand-new process (fresh Vault object) still remembers the failure.
        assertEquals(3, assertIs<UnlockResult.WrongPassword>(f.vault().unlock("nope".pw())).attemptsLeft)
        open(Fixture.MAIN_PW).close() // success resets the counter
        assertEquals(4, assertIs<UnlockResult.WrongPassword>(f.vault().unlock("nope".pw())).attemptsLeft)
    }

    @Test
    fun noLimitMeansUnlimitedAttempts() {
        f.create(maxAttempts = 0).first.lock()
        repeat(5) { assertNull(assertIs<UnlockResult.WrongPassword>(f.vault().unlock("x".pw())).attemptsLeft) }
        open(Fixture.MAIN_PW)
    }

    @Test
    fun attemptLimitDestroysEverything() {
        val (v, s) = f.create()
        s.import("a", bytes(10))
        s.maxAttempts = 3
        v.lock()
        repeat(2) { assertIs<UnlockResult.WrongPassword>(f.vault().unlock("bad".pw())) }
        assertEquals(UnlockResult.Destroyed, f.vault().unlock("bad".pw()))
        assertEquals(VaultState.NONE, f.vault().state())
        assertEquals(UnlockResult.NoVault, f.vault().unlock(Fixture.MAIN_PW.pw()))
        assertTrue(f.device.keys.isEmpty(), "device key must be destroyed")
        assertFalse(f.fs.exists(f.dir / "pool"))
    }

    /**
     * The classic attack on attempt counters: copy the files, try passwords, restore the copy.
     * Because each attempt re-seals the header under a new device key and destroys the old one,
     * the restored copy can no longer be opened at all.
     */
    @Test
    fun restoringAnOldHeaderCopyDoesNotResetTheCounter() {
        val (v, s) = f.create()
        s.maxAttempts = 3
        v.lock()
        val header = f.dir / "header.bin"
        val snapshot = f.fs.read(header) { readByteArray() }
        assertIs<UnlockResult.WrongPassword>(f.vault().unlock("guess1".pw()))
        f.fs.write(header) { write(snapshot) }
        assertEquals(UnlockResult.Unreadable, f.vault().unlock(Fixture.MAIN_PW.pw()))
        assertEquals(VaultState.UNREADABLE, f.vault().state())
    }

    @Test
    fun foldersMoveRenameAndRecursiveDelete() {
        val (_, s) = f.create()
        val docs = s.createFolder(Entry.ROOT_ID, "Docs")
        val inner = s.createFolder(docs.id, "Inner")
        val file = s.import("a.txt", "hello".encodeToByteArray(), parent = inner.id)
        val dup = s.import("a.txt", "again".encodeToByteArray(), parent = inner.id)
        assertEquals("a (2).txt", s.entry(dup.id)!!.name)

        s.rename(file.id, "b.txt")
        assertEquals("b.txt", s.entry(file.id)!!.name)
        assertEquals(listOf("Docs", "Inner", "b.txt"), s.path(file.id).map { it.name })
        assertFailsWith<IllegalArgumentException> { s.move(listOf(docs.id), inner.id) }
        s.move(listOf(file.id), Entry.ROOT_ID)
        assertEquals(Entry.ROOT_ID, s.entry(file.id)!!.parentId)

        val usedBefore = s.usage().usedBytes
        s.delete(listOf(docs.id))
        assertEquals(listOf("b.txt"), s.entries().map { it.name })
        assertTrue(s.usage().usedBytes < usedBefore)
        s.close()
        assertEquals(listOf("b.txt"), open(Fixture.MAIN_PW).entries().map { it.name })
    }

    @Test
    fun deletedBlocksAreScrubbedAndReused() {
        val (_, s) = f.create()
        val e = s.import("x", bytes(20_000))
        val raw = rawPool()
        s.delete(listOf(e.id))
        val after = rawPool()
        // Every block that held the file now holds fresh noise.
        assertFalse(raw.contentEquals(after))
        val e2 = s.import("y", bytes(20_000, seed = 9))
        assertContentEquals(bytes(20_000, seed = 9), s.readAll(e2.id))
    }

    @Test
    fun failedImportLeavesNoTrace() {
        val (_, s) = f.create()
        s.import("keep", bytes(100))
        val used = s.usage().usedBytes
        val failing: Source = object : ForwardingSource(Buffer().write(bytes(30_000))) {
            var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (total > 15_000) throw java.io.IOException("disk yanked")
                return super.read(sink, byteCount).also { total += it }
            }
        }
        assertFailsWith<java.io.IOException> { s.importFile(0, "broken", "", failing) }
        assertEquals(listOf("keep"), s.entries().map { it.name })
        assertEquals(used, s.usage().usedBytes)
    }

    @Test
    fun tamperedBlockIsDetected() {
        val (_, s) = f.create()
        val e = s.import("x", bytes(3000))
        s.close()
        // Flip one byte in every block: whichever holds the file will fail authentication.
        val seg = f.dir / "pool" / "s000000.dat"
        val bytes = f.fs.read(seg) { readByteArray() }
        val s2 = open(Fixture.MAIN_PW)
        for (i in 0 until Fixture.SEGMENT_BLOCKS) bytes[i * Fixture.BLOCK + 100] = (bytes[i * Fixture.BLOCK + 100] + 1).toByte()
        f.fs.write(seg) { write(bytes) }
        assertFailsWith<CorruptedDataException> { s2.readAll(e.id) }
    }

    @Test
    fun thumbnails() {
        val (_, s) = f.create()
        val e = s.import("p.jpg", bytes(5000))
        assertNull(s.thumbnail(e.id))
        s.setThumbnail(e.id, bytes(9000, seed = 3))
        s.setThumbnail(e.id, bytes(1200, seed = 4)) // replacing frees the old blocks
        s.close()
        val s2 = open(Fixture.MAIN_PW)
        assertContentEquals(bytes(1200, seed = 4), s2.thumbnail(e.id))
        assertContentEquals(bytes(5000), s2.readAll(e.id))
    }

    @Test
    fun lockingClosesTheSession() {
        val (v, s) = f.create()
        val e = s.import("x", bytes(10))
        v.lock()
        assertFalse(s.isOpen)
        assertFailsWith<VaultLockedException> { s.readAll(e.id) }
    }

    @Test
    fun changePassword() {
        val (_, s) = f.create()
        s.import("x", bytes(10))
        s.changePassword("new password 123".pw())
        s.close()
        assertIs<UnlockResult.WrongPassword>(f.vault().unlock(Fixture.MAIN_PW.pw()))
        assertEquals(1, open("new password 123").entries().size)
    }

    @Test
    fun settingsPersistPerVault() {
        val (_, s) = f.create()
        s.updateSettings { it.copy(autoLockSeconds = 30) }
        s.close()
        assertEquals(30, open(Fixture.MAIN_PW).settings.autoLockSeconds)
    }

    @Test
    fun allBlocksLookLikeNoise() {
        val (_, s) = f.create(segments = 4)
        s.import("text.txt", ByteArray(40_000) { 'a'.code.toByte() }) // maximally non-random plaintext
        s.close()
        val pool = rawPool()
        val counts = IntArray(256)
        pool.forEach { counts[it.toInt() and 0xFF]++ }
        val expected = pool.size / 256.0
        val chi2 = counts.sumOf { (it - expected) * (it - expected) / expected }
        // 255 degrees of freedom: p = 0.0001 threshold is ~347.
        assertTrue(chi2 < 347, "pool bytes deviate from uniform: chi2=$chi2")
        assertFalse(String(pool, Charsets.ISO_8859_1).contains("text.txt"))
        assertFalse(String(f.fs.read(f.dir / "header.bin") { readByteArray() }, Charsets.ISO_8859_1).contains("LNV1"))
    }

    private fun rawPool(): ByteArray {
        val out = Buffer()
        var i = 0
        while (true) {
            val p = f.dir / "pool" / "s${i.toString().padStart(6, '0')}.dat"
            if (!f.fs.exists(p)) break
            out.write(f.fs.read(p) { readByteArray() })
            i++
        }
        return out.readByteArray()
    }

    @Test
    fun attemptLimitIsOnByDefault() {
        f.create().first.lock()
        assertEquals(9, assertIs<UnlockResult.WrongPassword>(f.vault().unlock("x".pw())).attemptsLeft)
    }

    @Test
    fun noVault() {
        assertEquals(VaultState.NONE, f.vault().state())
        assertEquals(UnlockResult.NoVault, f.vault().unlock("x".pw()))
    }

    @Test
    fun mainKind() {
        val (_, s) = f.create(reserve = HiddenReserve.NONE)
        assertEquals(VaultKind.MAIN, s.kind)
        assertFalse(s.canCreateHiddenVault)
    }
}

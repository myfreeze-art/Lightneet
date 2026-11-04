package app.lightneet.core

import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.format.PanicMode
import app.lightneet.core.vault.Entry
import app.lightneet.core.vault.PasswordInUseException
import app.lightneet.core.vault.UnlockResult
import app.lightneet.core.vault.VaultKind
import app.lightneet.core.vault.VaultSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParanoidFeaturesTest {
    private val f = Fixture()

    private fun open(password: String): VaultSession =
        assertIs<UnlockResult.Opened>(f.vault().unlock(password.pw()), "'$password' should open").session

    private fun setUp(reserve: HiddenReserve = HiddenReserve.QUARTER): Long {
        val (_, main) = f.create(reserve = reserve)
        main.import("decoy.txt", "boring".encodeToByteArray())
        main.createHiddenVault(HIDDEN_PW.pw())
        main.close()
        val hidden = open(HIDDEN_PW)
        val secret = hidden.import("secret.pdf", bytes(30_000, seed = 42))
        hidden.close()
        return secret.id
    }

    @Test
    fun hiddenVaultIsSeparateAndInvisible() {
        val secretId = setUp()
        val main = open(Fixture.MAIN_PW)
        assertEquals(VaultKind.MAIN, main.kind)
        assertEquals(listOf("decoy.txt"), main.entries().map { it.name })
        main.close()

        val hidden = open(HIDDEN_PW)
        assertEquals(VaultKind.HIDDEN, hidden.kind)
        assertEquals(listOf("secret.pdf"), hidden.entries().map { it.name })
        assertContentEquals(bytes(30_000, seed = 42), hidden.readAll(secretId))
    }

    /** The main vault cannot see the hidden one, yet filling it up must never damage it. */
    @Test
    fun fillingTheMainVaultNeverOverwritesTheHiddenOne() {
        val secretId = setUp()
        val main = open(Fixture.MAIN_PW)
        repeat(6) { main.import("big$it.bin", bytes(60_000, seed = it)) } // forces several pool growths
        val ids = main.entries().filter { it.name.startsWith("big") }.map { it.id }
        main.delete(ids.take(3)) // frees + scrubs blocks, all inside the main region
        main.import("more.bin", bytes(90_000, seed = 77))
        main.close()

        val hidden = open(HIDDEN_PW)
        assertContentEquals(bytes(30_000, seed = 42), hidden.readAll(secretId))
        hidden.import("grow.bin", bytes(120_000, seed = 5)) // the hidden vault grows the pool too
        hidden.close()

        val main2 = open(Fixture.MAIN_PW)
        assertContentEquals(bytes(90_000, seed = 77), main2.readAll(main2.entries().first { it.name == "more.bin" }.id))
    }

    @Test
    fun hiddenVaultNeedsAReserve() {
        val (_, main) = f.create(reserve = HiddenReserve.NONE)
        assertFailsWith<IllegalStateException> { main.createHiddenVault(HIDDEN_PW.pw()) }
    }

    @Test
    fun halfReserveWorksToo() {
        val id = setUp(HiddenReserve.HALF)
        assertContentEquals(bytes(30_000, seed = 42), open(HIDDEN_PW).readAll(id))
    }

    @Test
    fun panicDestroysHiddenAndShowsMain() {
        setUp()
        open(Fixture.MAIN_PW).apply { setPanicPassword(PANIC_PW.pw(), PanicMode.DESTROY_HIDDEN); close() }

        val s = open(PANIC_PW)
        assertEquals(VaultKind.MAIN, s.kind, "panic must look like a normal login")
        assertEquals(listOf("decoy.txt"), s.entries().map { it.name })
        s.close()

        assertIs<UnlockResult.WrongPassword>(f.vault().unlock(HIDDEN_PW.pw()))
        assertEquals(listOf("decoy.txt"), open(Fixture.MAIN_PW).entries().map { it.name })
        // Using the panic password again behaves the same way.
        assertEquals(listOf("decoy.txt"), open(PANIC_PW).entries().map { it.name })
    }

    @Test
    fun panicDestroyAllShowsAFreshEmptyVault() {
        setUp()
        open(Fixture.MAIN_PW).apply { setPanicPassword(PANIC_PW.pw(), PanicMode.DESTROY_ALL); close() }

        val s = open(PANIC_PW)
        assertEquals(VaultKind.MAIN, s.kind)
        assertTrue(s.entries().isEmpty())
        s.import("new.txt", bytes(10))
        s.close()

        assertIs<UnlockResult.WrongPassword>(f.vault().unlock(Fixture.MAIN_PW.pw()))
        assertIs<UnlockResult.WrongPassword>(f.vault().unlock(HIDDEN_PW.pw()))
        assertEquals(listOf("new.txt"), open(PANIC_PW).entries().map { it.name })
    }

    @Test
    fun panicPasswordCanBeCleared() {
        setUp()
        open(Fixture.MAIN_PW).apply {
            setPanicPassword(PANIC_PW.pw(), PanicMode.DESTROY_HIDDEN)
            clearPanicPassword()
            close()
        }
        assertIs<UnlockResult.WrongPassword>(f.vault().unlock(PANIC_PW.pw()))
        open(HIDDEN_PW) // hidden vault survived
    }

    @Test
    fun passwordsMustBeDistinct() {
        setUp()
        val main = open(Fixture.MAIN_PW)
        assertFailsWith<PasswordInUseException> { main.setPanicPassword(Fixture.MAIN_PW.pw(), PanicMode.DESTROY_HIDDEN) }
        assertFailsWith<PasswordInUseException> { main.setPanicPassword(HIDDEN_PW.pw(), PanicMode.DESTROY_HIDDEN) }
        assertFailsWith<PasswordInUseException> { main.changePassword(HIDDEN_PW.pw()) }
        main.close()
        open(HIDDEN_PW) // still intact
    }

    @Test
    fun recreatingTheHiddenVaultReplacesIt() {
        setUp()
        open(Fixture.MAIN_PW).apply { createHiddenVault("another hidden".pw()); close() }
        assertIs<UnlockResult.WrongPassword>(f.vault().unlock(HIDDEN_PW.pw()))
        assertTrue(open("another hidden").entries().isEmpty())
    }

    @Test
    fun hiddenVaultCanDestroyItself() {
        setUp()
        open(HIDDEN_PW).destroyHiddenVault()
        assertIs<UnlockResult.WrongPassword>(f.vault().unlock(HIDDEN_PW.pw()))
        assertEquals(1, open(Fixture.MAIN_PW).children(Entry.ROOT_ID).size)
    }

    private companion object {
        const val HIDDEN_PW = "the real secret"
        const val PANIC_PW = "help me 911"
    }
}

package app.lightneet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.vault.Entry
import app.lightneet.core.vault.VaultKind
import app.lightneet.ui.AppViewModel
import app.lightneet.ui.Gate
import app.lightneet.ui.MainActivity
import app.lightneet.ui.Screen
import app.lightneet.ui.UnlockMessage
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * End-to-end run of the Android app on the JVM: real libsodium, real storage, real
 * ViewModel and Compose UI. Screenshots land in app/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestLightneetApp::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class AppFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: AppViewModel get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun typeIntoPasswordFields(vararg values: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val fields = mutableListOf<EditText>()
            fun walk(v: View) {
                if (v is EditText) fields += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(compose.activity.window.decorView)
            check(fields.size >= values.size) { "found ${fields.size} password fields" }
            values.forEachIndexed { i, text -> fields[i].setText(text) }
        }
        compose.waitForIdle()
    }

    @Test
    fun fullJourney() {
        // Robolectric ships an empty MIME table; a real phone knows these.
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("png", "image/png")
            addExtensionMimeTypeMapping("txt", "text/plain")
            addExtensionMimeTypeMapping("pdf", "application/pdf")
        }
        ShadowStatFs.registerStats(compose.activity.noBackupFilesDir, 64 * 1024 * 1024, 16 * 1024 * 1024, 16 * 1024 * 1024)

        // ---------------------------------------------------------------- setup
        waitFor { vm.gate.value == Gate.SETUP }
        shot("01_setup_intro")
        compose.onNodeWithText("Создать хранилище").performClick()
        typeIntoPasswordFields(PASSWORD, PASSWORD)
        waitFor { compose.onAllNodesWithTextCount("Отличный") > 0 }
        shot("02_setup_password")
        compose.onNodeWithText("Далее").performClick()
        shot("03_setup_options")

        // Create with one 64 MiB segment instead of the UI default, to keep the test quick.
        vm.create(PASSWORD.encodeToByteArray(), 1, HiddenReserve.QUARTER)
        waitFor { vm.gate.value == Gate.OPEN }
        shot("04_browser_empty")

        // ---------------------------------------------------------------- import
        val dir = compose.activity.cacheDir
        val uris = listOf(
            image(dir, "Закат.png", 0xFFF2C14E.toInt(), 0xFF8E3B46.toInt()),
            image(dir, "Море.png", 0xFF1D4E89.toInt(), 0xFF7FC8F8.toInt()),
            image(dir, "Лес.png", 0xFF2D6A4F.toInt(), 0xFFB7E4C7.toInt()),
            image(dir, "Ночь.png", 0xFF0B132B.toInt(), 0xFF5BC0BE.toInt()),
            text(dir, "пароли.txt", SECRET_TEXT),
            text(dir, "договор.pdf", "%PDF-1.4 fake"),
        )
        vm.import(uris + Uri.fromFile(File(dir, "Закат.png"))) // file:// must be refused
        waitFor { vm.progress.value == null && vm.entries.value.size == 6 }
        vm.createFolder("Документы")
        waitFor { vm.entries.value.size == 7 }
        compose.runOnUiThread { vm.offerDeleteOriginals.value = emptyList() }
        compose.runOnUiThread { vm.refresh() }
        val images = vm.entries.value.filter { it.mimeType.startsWith("image/") }
        assertEquals(4, images.size)
        assertTrue("every image gets an encrypted thumbnail", images.all { it.hasThumbnail })

        // The plaintext never touches the vault directory.
        val raw = File(compose.activity.noBackupFilesDir, "vault").walk().filter { it.isFile }.map { it.readBytes() }.toList()
        assertTrue(raw.none { String(it, Charsets.ISO_8859_1).contains("hunter2") })
        assertTrue(raw.none { String(it, Charsets.UTF_8).contains("пароли") })

        shot("05_browser_grid")
        compose.runOnUiThread { vm.toggleGrid() }
        shot("06_browser_list")
        compose.runOnUiThread { vm.toggleGrid() }

        // ---------------------------------------------------------------- viewer & settings
        val sunset = vm.entries.value.first { it.name == "Закат.png" }
        compose.mainClock.advanceTimeBy(6_000) // let the "imported" snackbar time out
        compose.runOnUiThread { vm.open(sunset) }
        waitFor { compose.onAllNodes(hasContentDescription("Закат.png")).fetchSemanticsNodes().isNotEmpty() }
        shot("07_viewer_image")
        val notes = vm.entries.value.first { it.name == "пароли.txt" }
        compose.runOnUiThread { vm.screen.value = Screen.Viewer(notes.id) }
        Thread.sleep(800)
        shot("08_viewer_text")
        compose.runOnUiThread {
            vm.loadSettings()
            vm.screen.value = Screen.Settings
        }
        waitFor { vm.settings.value != null }
        shot("09_settings")

        // ---------------------------------------------------------------- lock / unlock
        compose.runOnUiThread { vm.lock() }
        waitFor { vm.gate.value == Gate.LOCKED }
        typeIntoPasswordFields("неправильный")
        shot("10_unlock")
        vm.unlock("неправильный".encodeToByteArray())
        waitFor { vm.unlockMessage.value is UnlockMessage.Wrong }
        shot("11_unlock_wrong")

        vm.unlock(PASSWORD.encodeToByteArray())
        waitFor { vm.gate.value == Gate.OPEN }
        assertEquals(7, vm.entries.value.size)
        val reopened = vm.entries.value.first { it.name == "пароли.txt" }
        assertEquals(SECRET_TEXT, String(vm.sessionOrNull()!!.readAll(reopened.id)))

        // ---------------------------------------------------------------- hidden vault
        vm.createHidden(HIDDEN.encodeToByteArray())
        waitFor { vm.progress.value == null }
        compose.runOnUiThread { vm.lock() }
        waitFor { vm.gate.value == Gate.LOCKED }
        vm.unlock(HIDDEN.encodeToByteArray())
        waitFor { vm.gate.value == Gate.OPEN }
        assertEquals(VaultKind.HIDDEN, vm.kind.value)
        assertTrue(vm.entries.value.isEmpty())
        shot("12_hidden_vault_looks_the_same")
        assertEquals(Entry.ROOT_ID, vm.folderId.value)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private fun image(dir: File, name: String, from: Int, to: Int): Uri {
        val bmp = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 1200f, 900f, Paint().apply { shader = LinearGradient(0f, 0f, 1200f, 900f, from, to, Shader.TileMode.CLAMP) })
        c.drawCircle(820f, 330f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return asContentUri(f)
    }

    private fun text(dir: File, name: String, content: String): Uri {
        val f = File(dir, name)
        f.writeText(content)
        return asContentUri(f)
    }

    /** The app only accepts content:// URIs (as the system picker and "Share" deliver them). */
    private fun asContentUri(f: File): Uri {
        val uri = Uri.Builder().scheme("content").authority("test.files").appendPath(f.name).build()
        shadowOf(compose.activity.contentResolver).registerInputStreamSupplier(uri) { f.inputStream() }
        return uri
    }

    private companion object {
        const val PASSWORD = "лампа река кобальт орбита"
        const val HIDDEN = "совсем другой тайный пароль"
        const val SECRET_TEXT = "почта: hunter2\nбанк: 4321-correct-horse\n"
    }
}

package app.lightneet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import app.lightneet.core.format.HiddenReserve
import app.lightneet.data.VaultInputStream
import app.lightneet.security.LauncherDisguise
import app.lightneet.ui.AppViewModel
import app.lightneet.ui.Gate
import app.lightneet.ui.MainActivity
import app.lightneet.ui.Screen
import app.lightneet.ui.UnlockMessage
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayOutputStream
import java.io.File

/** Built-in camera, built-in password keyboard and launcher disguise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestLightneetApp::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class RoadmapFeaturesTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: AppViewModel get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val app: LightneetApp get() = compose.activity.application as LightneetApp

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun createVault() {
        ShadowStatFs.registerStats(compose.activity.noBackupFilesDir, 64 * 1024 * 1024, 16 * 1024 * 1024, 16 * 1024 * 1024)
        waitFor { vm.gate.value != Gate.LOADING }
        if (vm.gate.value != Gate.SETUP) error("unexpected gate ${vm.gate.value}")
        vm.create(PASSWORD.encodeToByteArray(), 1, HiddenReserve.QUARTER)
        waitFor { vm.gate.value == Gate.OPEN }
    }

    private fun passwordField(): EditText {
        compose.waitForIdle()
        var found: EditText? = null
        compose.runOnUiThread {
            fun walk(v: View) {
                if (v is EditText && found == null) found = v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(compose.activity.window.decorView)
        }
        return checkNotNull(found) { "no password field" }
    }

    private fun key(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("password_keyboard"))).performClick()

    @Test
    fun cameraPhotoIsEncryptedUprightAndNeverOnDisk() {
        createVault()
        // A 400×300 landscape frame the camera reports as "rotate by 90°".
        val frame = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(30, 80, 140))
            drawRect(0f, 0f, 100f, 300f, Paint().apply { color = Color.rgb(240, 190, 70) })
        }
        val jpeg = ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val marker = jpeg.copyOfRange(jpeg.size / 2, jpeg.size / 2 + 32)

        compose.runOnUiThread { vm.openCamera() }
        vm.savePhoto(jpeg, 90)
        waitFor { vm.photosTaken.value == 1 && vm.entries.value.size == 1 }

        assertTrue("the camera buffer is wiped after encryption", jpeg.all { it == 0.toByte() })
        val photo = vm.entries.value.single()
        assertTrue(photo.name.matches(Regex("IMG_\\d{8}_\\d{6}\\.jpg")))
        assertEquals("image/jpeg", photo.mimeType)
        assertTrue("an encrypted thumbnail is made", photo.hasThumbnail)

        val s = vm.sessionOrNull()!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        s.openReader(photo.id).use { BitmapFactory.decodeStream(VaultInputStream(it), null, bounds) }
        assertEquals("turned upright", 300, bounds.outWidth)
        assertEquals(400, bounds.outHeight)

        // Neither the original JPEG nor any file outside the vault appears on disk.
        val raw = File(compose.activity.noBackupFilesDir, "vault").walk().filter { it.isFile }.map { it.readBytes() }.toList()
        assertTrue(raw.none { String(it, Charsets.ISO_8859_1).contains(String(marker, Charsets.ISO_8859_1)) })
        val stray = listOf(compose.activity.filesDir, compose.activity.cacheDir, compose.activity.externalCacheDir)
            .filterNotNull().flatMap { it.walk().filter { f -> f.isFile && f.name.endsWith(".jpg") }.toList() }
        assertTrue("no plaintext JPEG files: $stray", stray.isEmpty())
    }

    @Test
    fun builtInKeyboardTypesIntoThePasswordField() {
        createVault()
        compose.runOnUiThread { app.prefs.setBuiltInKeyboard(true) }
        compose.runOnUiThread { vm.lock() }
        waitFor { vm.gate.value == Gate.LOCKED }
        val field = passwordField()
        compose.runOnUiThread { field.requestFocus() }
        compose.waitForIdle()
        assertFalse("the system keyboard is not used", field.showSoftInputOnFocus)
        compose.onNodeWithTag("password_keyboard").assertExists()

        // Type "Ab1" with shift and the symbols page, plus a stray letter removed by backspace.
        clickKeyDescribed("Shift")
        key("A")
        key("b")
        key("x")
        clickKeyDescribed("Стереть")
        key("?123")
        key("1")
        assertEquals("Ab1", field.text.toString())
        shot("13_builtin_keyboard")

        // Switch to Cyrillic and type the rest of the real password.
        compose.runOnUiThread { field.setText("") }
        key("ABC")
        key("EN")
        PASSWORD.forEach { c -> if (c == ' ') clickKeyDescribed("Пробел") else key(c.toString()) }
        assertEquals(PASSWORD, field.text.toString())
        clickKeyDescribed("Готово")
        waitFor { vm.gate.value == Gate.OPEN || vm.unlockMessage.value is UnlockMessage.Wrong }
        assertEquals(Gate.OPEN, vm.gate.value)

        compose.runOnUiThread { app.prefs.setBuiltInKeyboard(false) }
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    private fun clickKeyDescribed(description: String) =
        compose.onNode(hasClickLabel(description)).performClick()

    @Test
    fun launcherCanBeDisguisedAsCalculator() {
        createVault()
        val ctx = compose.activity
        assertFalse(LauncherDisguise.isDisguised(ctx))
        compose.runOnUiThread {
            vm.loadSettings()
            vm.screen.value = Screen.Settings
        }
        waitFor { vm.settings.value != null }
        compose.onNode(hasText("Маскировка под «Калькулятор»")).performClick()
        compose.waitForIdle()
        assertTrue(LauncherDisguise.isDisguised(ctx))
        val launchers = ctx.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(ctx.packageName), 0,
        )
        assertEquals(listOf("app.lightneet.Calculator"), launchers.map { it.activityInfo.name })
        assertEquals("Калькулятор", launchers.single().loadLabel(ctx.packageManager).toString())
        // After a lock the app is reopened through the launch intent: it must hit the disguise too.
        assertEquals("app.lightneet.Calculator", ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.component?.className)
        shot("14_settings_disguise")
        LauncherDisguise.set(ctx, false)
        assertFalse(LauncherDisguise.isDisguised(ctx))
    }

    private companion object {
        const val PASSWORD = "лампа река кобальт орбита"
    }
}

package app.lightneet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import app.lightneet.core.format.HiddenReserve
import app.lightneet.ui.AppViewModel
import app.lightneet.ui.Gate
import app.lightneet.ui.MainActivity
import app.lightneet.ui.Screen
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * Screenshots for the README, in Russian and English. Recorded with
 * `./gradlew :app:testDebugUnitTest --tests '*ReadmeScreenshotsTest*' -Proborazzi.test.record=true`
 * into app/build/outputs/roborazzi/readme/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestLightneetApp::class, sdk = [35])
@OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
class ReadmeScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: AppViewModel get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val app: LightneetApp get() = compose.activity.application as LightneetApp

    @Test
    @Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
    fun russian() = journey("ru", folder = "Документы", notes = "заметки.txt", contract = "договор.pdf", password = "лампа река")

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun english() = journey("en", folder = "Documents", notes = "notes.txt", contract = "contract.pdf", password = "lamp river")

    private fun journey(lang: String, folder: String, notes: String, contract: String, password: String) {
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("png", "image/png")
            addExtensionMimeTypeMapping("txt", "text/plain")
            addExtensionMimeTypeMapping("pdf", "application/pdf")
        }
        ShadowStatFs.registerStats(compose.activity.noBackupFilesDir, 64 * 1024 * 1024, 16 * 1024 * 1024, 16 * 1024 * 1024)
        fun shot(name: String) {
            compose.waitForIdle()
            captureScreenRoboImage("build/outputs/roborazzi/readme/${lang}_$name.png")
        }

        waitFor { vm.gate.value == Gate.SETUP }
        shot("1_setup")

        vm.create("$password кобальт орбита".encodeToByteArray(), 1, HiddenReserve.QUARTER)
        waitFor { vm.gate.value == Gate.OPEN }

        val dir = compose.activity.cacheDir
        val pictures = listOf(
            Scene("IMG_0412.jpg", 0xFFFFB067, 0xFF8E3B46, Kind.SUNSET),
            Scene("IMG_0415.jpg", 0xFF0B3C5D, 0xFF7FC8F8, Kind.SEA),
            Scene("IMG_0423.jpg", 0xFF1B4332, 0xFFB7E4C7, Kind.FOREST),
            Scene("IMG_0431.jpg", 0xFF0B132B, 0xFF5BC0BE, Kind.NIGHT),
            Scene("IMG_0440.jpg", 0xFF3D5A80, 0xFFE0FBFC, Kind.MOUNTAINS),
            Scene("IMG_0452.jpg", 0xFF22223B, 0xFFF2E9E4, Kind.CITY),
        ).map { picture(dir, it) }
        vm.import(pictures + text(dir, notes, "…") + text(dir, contract, "%PDF-1.4"))
        waitFor { vm.progress.value == null && vm.entries.value.size == 8 }
        vm.createFolder(folder)
        waitFor { vm.entries.value.size == 9 }
        compose.runOnUiThread { vm.offerDeleteOriginals.value = emptyList() }
        compose.mainClock.advanceTimeBy(8_000)
        compose.runOnUiThread { vm.refresh() }
        shot("2_browser")

        compose.runOnUiThread { vm.toggleGrid() }
        shot("3_list")
        compose.runOnUiThread { vm.toggleGrid() }
        compose.onNode(hasText(compose.activity.getString(R.string.add)), useUnmergedTree = true).performClick()
        shot("6_add_menu")

        val sea = vm.entries.value.first { it.name == "IMG_0415.jpg" }
        compose.runOnUiThread { vm.open(sea) }
        waitFor { compose.onAllNodes(hasContentDescription("IMG_0415.jpg")).fetchSemanticsNodes().isNotEmpty() }
        shot("4_viewer")

        compose.runOnUiThread {
            vm.loadSettings()
            vm.screen.value = Screen.Settings
        }
        waitFor { vm.settings.value != null }
        shot("5_settings")

        compose.runOnUiThread { app.prefs.setBuiltInKeyboard(true) }
        compose.runOnUiThread { vm.lock() }
        waitFor { vm.gate.value == Gate.LOCKED }
        compose.waitForIdle()
        compose.runOnUiThread {
            passwordField()?.apply {
                requestFocus()
                setText("lightneet")
                setSelection(length())
            }
        }
        if (lang == "ru") compose.onNode(hasText("EN")).performClick()
        shot("7_unlock_keyboard")
        compose.runOnUiThread { app.prefs.setBuiltInKeyboard(false) }
    }

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun passwordField(): EditText? {
        var found: EditText? = null
        fun walk(v: View) {
            if (v is EditText && found == null) found = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(compose.activity.window.decorView)
        return found
    }

    private enum class Kind { SUNSET, SEA, FOREST, NIGHT, MOUNTAINS, CITY }

    private class Scene(val name: String, val top: Long, val bottom: Long, val kind: Kind)

    /** Simple painted "photos" so the grid looks like a real camera roll. */
    private fun picture(dir: File, s: Scene): Uri {
        val w = 1200f
        val h = 1200f
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h, s.top.toInt(), s.bottom.toInt(), Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (s.kind) {
            Kind.SUNSET -> {
                p.shader = RadialGradient(600f, 760f, 260f, 0xFFFFF1C1.toInt(), 0x00FFB067, Shader.TileMode.CLAMP)
                c.drawCircle(600f, 760f, 260f, p)
                p.shader = null; p.color = 0xFF3A1F2B.toInt()
                c.drawRect(0f, 860f, w, h, p)
            }
            Kind.SEA -> {
                p.color = 0xFFFFFFFF.toInt(); p.alpha = 70
                for (i in 0 until 6) c.drawRect(0f, 700f + i * 80f, w, 712f + i * 80f, p)
                p.alpha = 255; p.color = 0xFFF8F4E3.toInt()
                c.drawCircle(900f, 260f, 90f, p)
            }
            Kind.FOREST -> {
                p.color = 0xFF081C15.toInt()
                for (i in 0 until 7) {
                    val x = i * 190f - 40f
                    c.drawPath(Path().apply { moveTo(x, h); lineTo(x + 120f, 420f + (i % 3) * 90f); lineTo(x + 240f, h); close() }, p)
                }
            }
            Kind.NIGHT -> {
                p.color = 0xFFFFFFFF.toInt()
                val r = java.util.Random(7)
                repeat(80) { c.drawCircle(r.nextFloat() * w, r.nextFloat() * 700f, 2f + r.nextFloat() * 3f, p) }
                p.color = 0xFFF2E8CF.toInt()
                c.drawCircle(860f, 260f, 110f, p)
                p.color = s.top.toInt()
                c.drawCircle(905f, 225f, 100f, p)
            }
            Kind.MOUNTAINS -> {
                p.color = 0xFF293241.toInt()
                c.drawPath(Path().apply { moveTo(0f, h); lineTo(380f, 420f); lineTo(760f, h); close() }, p)
                p.color = 0xFF4A5D7A.toInt()
                c.drawPath(Path().apply { moveTo(420f, h); lineTo(850f, 520f); lineTo(1300f, h); close() }, p)
                p.color = 0xFFF1F5F9.toInt()
                c.drawPath(Path().apply { moveTo(380f, 420f); lineTo(300f, 540f); lineTo(460f, 540f); close() }, p)
            }
            Kind.CITY -> {
                val r = java.util.Random(3)
                var x = 0f
                while (x < w) {
                    val bw = 90f + r.nextFloat() * 120f
                    val top = 450f + r.nextFloat() * 400f
                    p.color = 0xFF14142B.toInt()
                    c.drawRect(x, top, x + bw - 8f, h, p)
                    p.color = 0xFFFFD166.toInt()
                    var y = top + 30f
                    while (y < h - 30f) {
                        if (r.nextFloat() > 0.5f) c.drawRect(x + 16f, y, x + 32f, y + 18f, p)
                        if (r.nextFloat() > 0.5f) c.drawRect(x + bw - 44f, y, x + bw - 28f, y + 18f, p)
                        y += 46f
                    }
                    x += bw
                }
            }
        }
        val f = File(dir, s.name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return asContentUri(f)
    }

    private fun text(dir: File, name: String, content: String): Uri {
        val f = File(dir, name)
        f.writeText(content)
        return asContentUri(f)
    }

    private fun asContentUri(f: File): Uri {
        val uri = Uri.Builder().scheme("content").authority("test.files").appendPath(f.name).build()
        shadowOf(compose.activity.contentResolver).registerInputStreamSupplier(uri) { f.inputStream() }
        return uri
    }
}

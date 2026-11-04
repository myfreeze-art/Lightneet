package app.lightneet.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import app.lightneet.LightneetApp
import app.lightneet.ui.browser.BrowserScreen
import app.lightneet.ui.setup.SetupScreen
import app.lightneet.ui.settings.SettingsScreen
import app.lightneet.ui.theme.LightneetTheme
import app.lightneet.ui.unlock.UnlockScreen
import app.lightneet.ui.unlock.UnreadableScreen
import app.lightneet.ui.viewer.ViewerScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // No screenshots, no screen recording, blank preview in the recent-apps switcher.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveShared(intent)
        val autoLock = (application as LightneetApp).autoLock
        setContent {
            LightneetTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(vm, beforeExternal = autoLock::allowExternalActivity)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShared(intent)
    }

    /** "Share → Lightneet": hold the URIs until the vault is unlocked, then offer to import. */
    private fun receiveShared(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.pendingShared.value = vm.pendingShared.value + uris
    }
}

@Composable
private fun Root(vm: AppViewModel, beforeExternal: () -> Unit) {
    val gate by vm.gate.collectAsState()
    val screen by vm.screen.collectAsState()
    val unlockBusy by vm.unlockBusy.collectAsState()
    val unlockMessage by vm.unlockMessage.collectAsState()
    val setupProgress by vm.setupProgress.collectAsState()
    val pendingShared by vm.pendingShared.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    // Messages from an unlocked session must not linger on the lock screen.
    LaunchedEffect(gate) {
        if (gate != Gate.OPEN) snackbar.currentSnackbarData?.dismiss()
    }

    Box(Modifier.fillMaxSize()) {
        when (gate) {
            Gate.LOADING -> Unit
            Gate.SETUP -> SetupScreen(setupProgress, destroyedNotice = unlockMessage == UnlockMessage.Destroyed) { pw, segments, reserve ->
                vm.unlockMessage.value = null
                vm.create(pw, segments, reserve)
            }
            Gate.LOCKED -> UnlockScreen(unlockBusy, unlockMessage, pendingShared.size, vm::unlock)
            Gate.UNREADABLE -> UnreadableScreen(vm::eraseUnreadable)
            Gate.OPEN -> when (val s = screen) {
                Screen.Browser -> BrowserScreen(vm, snackbar, beforeExternal)
                is Screen.Viewer -> key(s.entryId) { ViewerScreen(vm, s.entryId, beforeExternal) }
                Screen.Settings -> SettingsScreen(vm, snackbar)
            }
        }
        if (gate != Gate.OPEN || screen is Screen.Viewer) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

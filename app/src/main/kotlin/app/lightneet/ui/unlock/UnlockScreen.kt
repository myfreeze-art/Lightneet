package app.lightneet.ui.unlock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lightneet.R
import app.lightneet.security.SecurePasswordField
import app.lightneet.security.rememberSecurePasswordState
import app.lightneet.ui.UnlockMessage
import app.lightneet.ui.common.ConfirmDialog
import app.lightneet.ui.common.DangerButton
import app.lightneet.ui.common.Gap
import app.lightneet.ui.common.Logo
import app.lightneet.ui.common.PrimaryButton
import app.lightneet.ui.theme.LnColors

/**
 * One field, one button — identical for every password (main, hidden, panic, wrong).
 * Nothing on this screen may hint at which vaults exist.
 */
@Composable
fun UnlockScreen(busy: Boolean, message: UnlockMessage?, pendingShared: Int, onUnlock: (ByteArray) -> Unit) {
    val password = rememberSecurePasswordState()
    val submit = { if (password.length > 0) onUnlock(password.takeBytes()) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Gap(96.dp)
        Logo(72.dp)
        Gap(20.dp)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Gap(40.dp)
        SecurePasswordField(password, stringResource(R.string.hint_password), onDone = submit, requestFocus = true)
        Gap(12.dp)
        val text = when (message) {
            is UnlockMessage.Wrong -> message.attemptsLeft?.let {
                pluralStringResource(R.plurals.wrong_password_left, it, it)
            } ?: stringResource(R.string.wrong_password)
            UnlockMessage.Destroyed -> stringResource(R.string.destroyed_title)
            null -> ""
        }
        Text(text, color = LnColors.Danger, style = MaterialTheme.typography.bodyMedium)
        Gap(16.dp)
        PrimaryButton(stringResource(R.string.unlock), submit, enabled = password.length > 0, busy = busy)
        if (pendingShared > 0) {
            Gap(20.dp)
            Text(
                pluralStringResource(R.plurals.pending_shared, pendingShared, pendingShared),
                color = LnColors.TextMuted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun UnreadableScreen(onErase: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Gap(80.dp)
        Logo(56.dp)
        Gap(24.dp)
        Text(stringResource(R.string.unreadable_title), style = MaterialTheme.typography.headlineMedium)
        Gap(12.dp)
        Text(stringResource(R.string.unreadable_body), color = LnColors.TextMuted)
        Gap(32.dp)
        DangerButton(stringResource(R.string.unreadable_erase), { confirm = true })
    }
    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.unreadable_erase), stringResource(R.string.unreadable_erase_confirm),
            stringResource(R.string.erase), onErase, { confirm = false }, danger = true,
        )
    }
}

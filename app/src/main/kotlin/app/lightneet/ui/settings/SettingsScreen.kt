package app.lightneet.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lightneet.R
import app.lightneet.core.device.ProtectionLevel
import app.lightneet.core.format.PanicMode
import app.lightneet.core.vault.VaultKind
import app.lightneet.security.PasswordStrength
import app.lightneet.security.SecurePasswordField
import app.lightneet.security.StrengthMeter
import app.lightneet.security.rememberPasswordScore
import app.lightneet.security.rememberSecurePasswordState
import app.lightneet.ui.AppViewModel
import app.lightneet.ui.Screen
import app.lightneet.ui.common.ConfirmDialog
import app.lightneet.ui.common.DangerButton
import app.lightneet.ui.common.Gap
import app.lightneet.ui.common.ProgressBanner
import app.lightneet.ui.common.SectionLabel
import app.lightneet.ui.common.formatSize
import app.lightneet.ui.theme.LnColors

private sealed interface SettingsDialog {
    data object ChangePassword : SettingsDialog
    data object Panic : SettingsDialog
    data object ClearPanic : SettingsDialog
    data object Hidden : SettingsDialog
    data object DestroyHidden : SettingsDialog
    data object DestroyAll : SettingsDialog
    data object Grow : SettingsDialog
}

private val AUTO_LOCK = listOf(0, 30, 60, 300)
private val ATTEMPTS = listOf(0, 5, 10, 20)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val s by vm.settings.collectAsState()
    val progress by vm.progress.collectAsState()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val back = { vm.screen.value = Screen.Browser }
    BackHandler(onBack = back)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val st = s ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            progress?.let { ProgressBanner(it, vm::cancelOperation) }

            SectionLabel(stringResource(R.string.settings_autolock))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AUTO_LOCK.forEach { sec ->
                    val label = when {
                        sec == 0 -> stringResource(R.string.autolock_now)
                        sec < 60 -> stringResource(R.string.autolock_seconds, sec)
                        else -> stringResource(R.string.autolock_minutes, sec / 60)
                    }
                    Chip(st.autoLockSeconds == sec, label) { vm.setAutoLock(sec) }
                }
            }
            Hint(stringResource(R.string.settings_autolock_hint))

            SectionLabel(stringResource(R.string.settings_attempts))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ATTEMPTS.forEach { n ->
                    Chip(st.maxAttempts == n, if (n == 0) stringResource(R.string.attempts_off) else n.toString()) { vm.setMaxAttempts(n) }
                }
            }
            Hint(stringResource(R.string.settings_attempts_hint))

            SectionLabel(stringResource(R.string.settings_passwords))
            Item(stringResource(R.string.change_password), null) { dialog = SettingsDialog.ChangePassword }
            if (st.kind == VaultKind.MAIN) {
                Item(stringResource(R.string.panic_password), stringResource(R.string.panic_password_hint)) { dialog = SettingsDialog.Panic }
                Item(stringResource(R.string.panic_clear), null) { dialog = SettingsDialog.ClearPanic }
                if (st.canCreateHidden) {
                    Item(stringResource(R.string.hidden_create), stringResource(R.string.hidden_create_hint)) { dialog = SettingsDialog.Hidden }
                }
            }

            SectionLabel(stringResource(R.string.settings_security))
            Info(
                stringResource(R.string.security_hardware),
                stringResource(
                    when (st.protection) {
                        ProtectionLevel.STRONGBOX -> R.string.level_strongbox
                        ProtectionLevel.TEE -> R.string.level_tee
                        ProtectionLevel.SOFTWARE -> R.string.level_software
                    },
                ),
            )
            Info(stringResource(R.string.security_used), "${formatSize(st.usage.usedBytes)} / ${formatSize(st.usage.regionBytes)}")
            Info(stringResource(R.string.security_pool), formatSize(st.usage.poolBytes))
            Item(stringResource(R.string.grow_storage), stringResource(R.string.grow_storage_hint)) { dialog = SettingsDialog.Grow }
            Info(stringResource(R.string.security_crypto), "Argon2id · XChaCha20-Poly1305")
            Info(stringResource(R.string.security_network), stringResource(R.string.security_network_value))

            SectionLabel(stringResource(R.string.settings_danger))
            if (st.kind == VaultKind.HIDDEN) {
                DangerButton(stringResource(R.string.hidden_destroy), { dialog = SettingsDialog.DestroyHidden })
                Gap(10.dp)
            }
            DangerButton(stringResource(R.string.destroy_all), { dialog = SettingsDialog.DestroyAll })
            Gap(40.dp)
        }
    }

    when (dialog) {
        SettingsDialog.ChangePassword -> PasswordDialog(stringResource(R.string.change_password), null, onDismiss = { dialog = null }) { pw, _ -> vm.changePassword(pw) }
        SettingsDialog.Panic -> PasswordDialog(stringResource(R.string.panic_password), stringResource(R.string.panic_dialog_body), withPanicMode = true, onDismiss = { dialog = null }) { pw, mode -> vm.setPanic(pw, mode) }
        SettingsDialog.Hidden -> PasswordDialog(stringResource(R.string.hidden_create), stringResource(R.string.hidden_dialog_body), onDismiss = { dialog = null }) { pw, _ -> vm.createHidden(pw) }
        SettingsDialog.ClearPanic -> ConfirmDialog(stringResource(R.string.panic_clear), stringResource(R.string.panic_clear_body), stringResource(R.string.clear), vm::clearPanic, { dialog = null })
        SettingsDialog.DestroyHidden -> ConfirmDialog(stringResource(R.string.hidden_destroy), stringResource(R.string.hidden_destroy_body), stringResource(R.string.erase), vm::destroyHidden, { dialog = null }, danger = true)
        SettingsDialog.DestroyAll -> ConfirmDialog(stringResource(R.string.destroy_all), stringResource(R.string.destroy_all_body), stringResource(R.string.erase), vm::destroyEverything, { dialog = null }, danger = true)
        SettingsDialog.Grow -> GrowDialog(onDismiss = { dialog = null }) { vm.growStorage(it) }
        null -> Unit
    }
}

private val GROW_OPTIONS = listOf(8, 16, 32, 64) // × 64 MiB

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrowDialog(onDismiss: () -> Unit, onGrow: (Int) -> Unit) {
    var segments by remember { mutableIntStateOf(16) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.grow_storage)) },
        text = {
            Column {
                Text(stringResource(R.string.grow_dialog_body), color = LnColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                Gap(12.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GROW_OPTIONS.forEach { n -> Chip(n == segments, "+" + formatSize(n * 64L * 1024 * 1024)) { segments = n } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onGrow(segments); onDismiss() }) { Text(stringResource(R.string.grow_action), color = LnColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = LnColors.TextMuted) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = LnColors.TextMuted)
}

@Composable
private fun Item(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LnColors.TextMuted)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = LnColors.TextMuted)
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, Modifier.weight(1f), color = LnColors.TextMuted)
        Text(value)
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    body: String?,
    withPanicMode: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (ByteArray, PanicMode) -> Unit,
) {
    val first = rememberSecurePasswordState()
    val second = rememberSecurePasswordState()
    val score = rememberPasswordScore(first)
    var mode by remember { mutableStateOf(PanicMode.DESTROY_HIDDEN) }
    val matches = first.length > 0 && first.sameAs(second)
    // Every password — including the panic one, which holds the main vault's key — must be strong.
    val ok = matches && score >= PasswordStrength.REQUIRED_SCORE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (body != null) {
                    Text(body, color = LnColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                    Gap(16.dp)
                }
                SecurePasswordField(first, stringResource(R.string.hint_new_password), requestFocus = true)
                Gap(8.dp)
                StrengthMeter(score)
                Gap(8.dp)
                SecurePasswordField(second, stringResource(R.string.hint_password_repeat))
                if (second.length > 0 && !matches) {
                    Gap(6.dp)
                    Text(stringResource(R.string.passwords_differ), color = LnColors.Danger, style = MaterialTheme.typography.bodySmall)
                }
                if (withPanicMode) {
                    Gap(12.dp)
                    ModeOption(PanicMode.DESTROY_HIDDEN, mode, R.string.panic_mode_hidden, R.string.panic_mode_hidden_body) { mode = it }
                    ModeOption(PanicMode.DESTROY_ALL, mode, R.string.panic_mode_all, R.string.panic_mode_all_body) { mode = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                val bytes = first.takeBytes()
                second.clear()
                onConfirm(bytes, mode)
                onDismiss()
            }) { Text(stringResource(R.string.save), color = if (ok) LnColors.Accent else LnColors.TextMuted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = LnColors.TextMuted) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun ModeOption(value: PanicMode, selected: PanicMode, title: Int, body: Int, onSelect: (PanicMode) -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(value == selected, onClick = { onSelect(value) }).padding(vertical = 4.dp)) {
        RadioButton(selected = value == selected, onClick = { onSelect(value) })
        Column(Modifier.padding(top = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = LnColors.TextMuted)
        }
    }
}

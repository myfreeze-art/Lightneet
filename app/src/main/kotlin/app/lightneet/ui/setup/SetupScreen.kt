package app.lightneet.ui.setup

import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lightneet.R
import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.vault.Vault
import app.lightneet.security.PasswordStrength
import app.lightneet.security.SecurePasswordField
import app.lightneet.security.StrengthMeter
import app.lightneet.security.rememberPasswordScore
import app.lightneet.security.rememberSecurePasswordState
import app.lightneet.ui.SetupProgress
import app.lightneet.ui.common.Gap
import app.lightneet.ui.common.InfoCard
import app.lightneet.ui.common.Logo
import app.lightneet.ui.common.PrimaryButton
import app.lightneet.ui.common.SectionLabel
import app.lightneet.ui.common.formatSize
import app.lightneet.ui.theme.LnColors

private const val SEGMENT_BYTES = Vault.DEFAULT_BLOCK_SIZE.toLong() * Vault.DEFAULT_SEGMENT_BLOCKS
private val SIZE_OPTIONS = listOf(8, 16, 32, 64, 128) // segments of 64 MiB: 512 MB … 8 GB

@Composable
fun SetupScreen(
    progress: SetupProgress?,
    destroyedNotice: Boolean,
    onCreate: (password: ByteArray, segments: Int, reserve: HiddenReserve) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf<ByteArray?>(null) }
    DisposableEffect(Unit) { onDispose { password?.fill(0) } }

    BackHandler(enabled = step > 0 && progress == null) {
        password?.fill(0)
        password = null
        step--
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        when {
            progress != null -> Creating(progress)
            step == 0 -> Intro(destroyedNotice) { step = 1 }
            step == 1 -> PasswordStep { bytes -> password = bytes; step = 2 }
            else -> OptionsStep { segments, reserve ->
                val pw = password ?: return@OptionsStep
                password = null
                onCreate(pw, segments, reserve)
            }
        }
    }
}

@Composable
private fun Intro(destroyedNotice: Boolean, onNext: () -> Unit) {
    Gap(40.dp)
    Logo(64.dp)
    Gap(24.dp)
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
    Gap(8.dp)
    Text(stringResource(R.string.setup_tagline), style = MaterialTheme.typography.bodyLarge, color = LnColors.TextMuted)
    Gap(28.dp)
    if (destroyedNotice) {
        InfoCard(stringResource(R.string.destroyed_title), stringResource(R.string.destroyed_body), accent = true)
        Gap(12.dp)
    }
    InfoCard(stringResource(R.string.setup_point_password_title), stringResource(R.string.setup_point_password_body))
    Gap(12.dp)
    InfoCard(stringResource(R.string.setup_point_offline_title), stringResource(R.string.setup_point_offline_body))
    Gap(12.dp)
    InfoCard(stringResource(R.string.setup_point_norecovery_title), stringResource(R.string.setup_point_norecovery_body), accent = true)
    Gap(32.dp)
    PrimaryButton(stringResource(R.string.setup_start), onNext)
}

@Composable
private fun PasswordStep(onNext: (ByteArray) -> Unit) {
    val first = rememberSecurePasswordState()
    val second = rememberSecurePasswordState()
    val score = rememberPasswordScore(first)
    val matches = first.length > 0 && first.length == second.length && first.sameAs(second)
    val ok = score >= PasswordStrength.REQUIRED_SCORE && matches

    Gap(24.dp)
    Text(stringResource(R.string.setup_password_title), style = MaterialTheme.typography.headlineMedium)
    Gap(8.dp)
    Text(stringResource(R.string.setup_password_body), color = LnColors.TextMuted)
    Gap(24.dp)
    SecurePasswordField(first, stringResource(R.string.hint_password), requestFocus = true)
    Gap(10.dp)
    StrengthMeter(score)
    Gap(14.dp)
    SecurePasswordField(second, stringResource(R.string.hint_password_repeat), onDone = { if (ok) onNext(first.takeBytes().also { second.clear() }) })
    Gap(8.dp)
    if (second.length > 0 && !matches) Text(stringResource(R.string.passwords_differ), color = LnColors.Danger, style = MaterialTheme.typography.bodySmall)
    else if (score in 0 until PasswordStrength.REQUIRED_SCORE) Text(stringResource(R.string.password_too_weak), color = LnColors.TextMuted, style = MaterialTheme.typography.bodySmall)
    Gap(24.dp)
    InfoCard(stringResource(R.string.setup_password_tip_title), stringResource(R.string.setup_password_tip_body))
    Gap(24.dp)
    PrimaryButton(stringResource(R.string.next), { onNext(first.takeBytes().also { second.clear() }) }, enabled = ok)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsStep(onCreate: (segments: Int, reserve: HiddenReserve) -> Unit) {
    val context = LocalContext.current
    val free = remember { StatFs(context.noBackupFilesDir.path).availableBytes }
    val allowed = SIZE_OPTIONS.filter { it * SEGMENT_BYTES < free * 0.9 }.ifEmpty { listOf(SIZE_OPTIONS.first()) }
    var segments by remember { mutableIntStateOf(allowed.getOrElse(1) { allowed.first() }) }
    var reserve by remember { mutableStateOf(HiddenReserve.QUARTER) }

    Gap(24.dp)
    Text(stringResource(R.string.setup_options_title), style = MaterialTheme.typography.headlineMedium)
    Gap(8.dp)
    Text(stringResource(R.string.setup_options_body), color = LnColors.TextMuted)

    SectionLabel(stringResource(R.string.setup_initial_size))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SIZE_OPTIONS.forEach { s ->
            FilterChip(
                selected = s == segments,
                enabled = s in allowed,
                onClick = { segments = s },
                label = { Text(formatSize(s * SEGMENT_BYTES)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
    }
    Gap(6.dp)
    Text(stringResource(R.string.setup_size_hint, formatSize(free)), style = MaterialTheme.typography.bodySmall, color = LnColors.TextMuted)

    SectionLabel(stringResource(R.string.setup_reserve))
    ReserveOption(HiddenReserve.NONE, reserve, R.string.reserve_none, R.string.reserve_none_body) { reserve = it }
    ReserveOption(HiddenReserve.QUARTER, reserve, R.string.reserve_quarter, R.string.reserve_quarter_body) { reserve = it }
    ReserveOption(HiddenReserve.HALF, reserve, R.string.reserve_half, R.string.reserve_half_body) { reserve = it }
    Gap(8.dp)
    Text(stringResource(R.string.setup_reserve_hint), style = MaterialTheme.typography.bodySmall, color = LnColors.TextMuted)
    Gap(28.dp)
    PrimaryButton(stringResource(R.string.setup_create), { onCreate(segments, reserve) })
}

@Composable
private fun ReserveOption(value: HiddenReserve, selected: HiddenReserve, title: Int, body: Int, onSelect: (HiddenReserve) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, onClick = { onSelect(value) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = value == selected, onClick = { onSelect(value) })
        Column(Modifier.padding(top = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = LnColors.TextMuted)
        }
    }
}

@Composable
private fun Creating(progress: SetupProgress) {
    Gap(120.dp)
    Logo(56.dp)
    Gap(28.dp)
    Text(
        stringResource(if (progress.stage == SetupProgress.Stage.CALIBRATING) R.string.setup_calibrating else R.string.setup_allocating),
        style = MaterialTheme.typography.titleLarge,
    )
    Gap(8.dp)
    Text(
        stringResource(if (progress.stage == SetupProgress.Stage.CALIBRATING) R.string.setup_calibrating_body else R.string.setup_allocating_body),
        color = LnColors.TextMuted,
    )
    Gap(24.dp)
    if (progress.stage == SetupProgress.Stage.CALIBRATING) LinearProgressIndicator(Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
}

package app.lightneet.security

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lightneet.LightneetApp
import app.lightneet.R
import app.lightneet.ui.theme.LnColors

/** The password field that currently has focus; the built-in keyboard types into it. */
internal object PasswordKeyboardTarget {
    var current: SecurePasswordState? by mutableStateOf(null)
}

@Composable
fun rememberBuiltInKeyboard(): State<Boolean> {
    val app = LocalContext.current.applicationContext as? LightneetApp
    return app?.prefs?.builtInKeyboard?.collectAsState() ?: remember { mutableStateOf(false) }
}

private enum class Layout { LATIN, CYRILLIC, SYMBOLS }

private val LATIN = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val CYRILLIC = listOf("йцукенгшщзхъ", "фывапролджэё", "ячсмитьбю")
private val SYMBOLS = listOf("1234567890", "@#$%&*-+=()", "!\"':;/?,._")
private val SYMBOLS_MORE = listOf("1234567890", "~`|\\<>[]{}^", "€£¥§°±×÷№")

/**
 * Lightneet's own keyboard for passwords. A system keyboard is another app: it may learn
 * words, sync them to a cloud or simply log keystrokes. This one lives inside Lightneet,
 * writes straight into the password field, and shows no key pop-ups that could be read
 * over the shoulder.
 *
 * Shown only when enabled in settings and a password field has focus.
 */
@Composable
fun PasswordKeyboard(modifier: Modifier = Modifier) {
    val enabled by rememberBuiltInKeyboard()
    val target = PasswordKeyboardTarget.current
    if (!enabled || target == null) return

    var layout by remember { mutableStateOf(Layout.LATIN) }
    var letters by remember { mutableStateOf(Layout.LATIN) }
    var shift by remember { mutableIntStateOf(0) } // 0 off, 1 once, 2 caps lock

    val rows = when (layout) {
        Layout.LATIN -> LATIN
        Layout.CYRILLIC -> CYRILLIC
        Layout.SYMBOLS -> if (shift > 0) SYMBOLS_MORE else SYMBOLS
    }
    fun press(c: Char) {
        target.type(if (shift > 0 && layout != Layout.SYMBOLS) c.uppercaseChar() else c)
        if (shift == 1 && layout != Layout.SYMBOLS) shift = 0
    }

    Column(
        modifier
            .fillMaxWidth()
            .testTag("password_keyboard")
            .background(LnColors.Surface)
            .navigationBarsPadding()
            .padding(horizontal = 3.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { i, row ->
            KeyRow {
                if (i == 2) {
                    val shiftLabel = if (layout == Layout.SYMBOLS) (if (shift > 0) "2/2" else "1/2") else null
                    Key(
                        weight = 1.5f, icon = if (shiftLabel == null) Icons.Outlined.ArrowUpward else null, label = shiftLabel,
                        highlighted = shift > 0, description = stringResource(R.string.kb_shift),
                    ) { shift = if (layout == Layout.SYMBOLS) (if (shift > 0) 0 else 1) else (shift + 1) % 3 }
                }
                row.forEach { c ->
                    val shown = if (shift > 0 && layout != Layout.SYMBOLS) c.uppercaseChar() else c
                    Key(label = shown.toString()) { press(c) }
                }
                if (i == 2) Key(weight = 1.5f, icon = Icons.AutoMirrored.Outlined.Backspace, description = stringResource(R.string.kb_backspace)) { target.backspace() }
            }
        }
        KeyRow {
            Key(weight = 1.6f, label = if (layout == Layout.SYMBOLS) "ABC" else "?123", special = true) {
                shift = 0
                layout = if (layout == Layout.SYMBOLS) letters else Layout.SYMBOLS
            }
            Key(weight = 1.3f, label = if (letters == Layout.LATIN) "EN" else "RU", special = true, description = stringResource(R.string.kb_language)) {
                letters = if (letters == Layout.LATIN) Layout.CYRILLIC else Layout.LATIN
                layout = letters
                shift = 0
            }
            Key(weight = 4.5f, label = " ", description = stringResource(R.string.kb_space)) { target.type(' ') }
            Key(weight = 1.6f, icon = Icons.Outlined.Check, highlighted = true, description = stringResource(R.string.kb_done)) { target.onDone() }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun RowScope.Key(
    weight: Float = 1f,
    label: String? = null,
    icon: ImageVector? = null,
    special: Boolean = false,
    highlighted: Boolean = false,
    description: String? = null,
    onClick: () -> Unit,
) {
    val bg = when {
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        special -> LnColors.SurfaceHigh
        else -> LnColors.Outline
    }
    Box(
        Modifier
            .weight(weight)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Icon(icon, description, tint = LnColors.Text)
            label != null -> Text(label, color = if (special) LnColors.TextMuted else LnColors.Text, fontSize = if (label.length > 1) 13.sp else 18.sp, maxLines = 1)
        }
    }
}

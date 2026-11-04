package app.lightneet.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lightneet.R
import app.lightneet.ui.theme.LnColors
import com.nulabinc.zxcvbn.Zxcvbn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.CharBuffer

/**
 * Password strength via zxcvbn, which estimates real guessability (dictionary words,
 * keyboard patterns, dates, l33t speak) rather than counting character classes.
 */
object PasswordStrength {
    private val zxcvbn by lazy { Zxcvbn() }

    /** Minimum zxcvbn score (0–4) accepted for vault passwords. */
    const val REQUIRED_SCORE = 3

    fun score(password: CharSequence): Int {
        if (password.isEmpty()) return -1
        val copy = CharArray(password.length) { password[it] }
        return try {
            zxcvbn.measure(CharBuffer.wrap(copy)).score
        } finally {
            copy.fill('\u0000')
        }
    }
}

@Composable
fun rememberPasswordScore(state: SecurePasswordState): Int {
    var score by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.length) {
        delay(120) // debounce typing
        val snapshot = CharArray(state.chars.length) { state.chars[it] }
        score = withContext(Dispatchers.Default) {
            try { PasswordStrength.score(CharBuffer.wrap(snapshot)) } finally { snapshot.fill('\u0000') }
        }
    }
    return score
}

@Composable
fun StrengthMeter(score: Int, modifier: Modifier = Modifier) {
    val (color, label) = when (score) {
        -1 -> LnColors.Outline to ""
        0, 1 -> LnColors.Danger to stringResource(R.string.strength_weak)
        2 -> Color(0xFFE38F3C) to stringResource(R.string.strength_fair)
        3 -> LnColors.Accent to stringResource(R.string.strength_good)
        else -> LnColors.Success to stringResource(R.string.strength_excellent)
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 4) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (score >= 0 && i <= maxOf(score - 1, 0) && score > 0 || (score == 0 && i == 0)) color else LnColors.Outline, MaterialTheme.shapes.small),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

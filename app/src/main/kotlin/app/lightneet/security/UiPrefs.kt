package app.lightneet.security

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only settings stored outside the vault, because they are needed on the lock screen,
 * before any key exists. They hold no secrets — see docs/THREAT_MODEL.md.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _builtInKeyboard = MutableStateFlow(prefs.getBoolean(KEY_KEYBOARD, false))

    /** Type passwords on Lightneet's own keyboard instead of the system one. */
    val builtInKeyboard: StateFlow<Boolean> = _builtInKeyboard.asStateFlow()

    fun setBuiltInKeyboard(on: Boolean) {
        prefs.edit { putBoolean(KEY_KEYBOARD, on) }
        _builtInKeyboard.value = on
    }

    private companion object {
        const val KEY_KEYBOARD = "builtin_keyboard"
    }
}

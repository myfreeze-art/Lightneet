package app.lightneet.security

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.lightneet.ui.theme.LnColors
import java.nio.CharBuffer

/**
 * Holds the password inside an Android [EditText] instead of a Kotlin `String`.
 *
 * Strings are immutable and linger in memory until garbage-collected; the EditText's buffer
 * can be read into a `CharArray`, encoded, and overwritten. The field also tells the keyboard
 * not to learn what is typed, opts out of autofill, and blocks copy/cut.
 */
@Stable
class SecurePasswordState {
    internal var editText: EditText? = null
    var length by mutableIntStateOf(0)
        internal set

    /** Read-only view for strength estimation. Do not store it. */
    val chars: CharSequence get() = editText?.text ?: ""

    /** Returns the password as UTF-8 and clears the field. The caller must wipe the result. */
    fun takeBytes(): ByteArray {
        val e = editText?.text ?: return ByteArray(0)
        val chars = CharArray(e.length)
        e.getChars(0, e.length, chars, 0)
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (encoded.hasArray()) encoded.array().fill(0)
        chars.fill('\u0000')
        clear()
        return bytes
    }

    /** True if both fields hold exactly the same characters (compared without making Strings). */
    fun sameAs(other: SecurePasswordState): Boolean {
        val a = chars
        val b = other.chars
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    fun clear() {
        editText?.text?.let { e ->
            e.replace(0, e.length, "\u0000".repeat(e.length))
            e.clear()
        }
    }
}

@Composable
fun rememberSecurePasswordState() = remember { SecurePasswordState() }

@Composable
fun SecurePasswordField(
    state: SecurePasswordState,
    hint: String,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    requestFocus: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    val textColor = LnColors.Text.toArgb()
    val hintColor = LnColors.TextMuted.toArgb()

    DisposableEffect(state) { onDispose { state.clear(); state.editText = null } }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx -> createField(ctx, state, hint, textColor, hintColor, onDone, requestFocus) },
                update = { field ->
                    val selection = field.selectionEnd
                    field.transformationMethod = if (visible) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                    field.setSelection(selection.coerceIn(0, field.length()))
                },
            )
        }
        IconButton(onClick = { visible = !visible }) {
            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null, tint = LnColors.TextMuted)
        }
    }
}

private fun createField(
    ctx: Context,
    state: SecurePasswordState,
    hint: String,
    textColor: Int,
    hintColor: Int,
    onDone: () -> Unit,
    requestFocus: Boolean,
): EditText = EditText(ctx).apply {
    background = null
    setPadding(0, 24, 0, 24)
    gravity = Gravity.CENTER_VERTICAL
    setTextColor(textColor)
    setHintTextColor(hintColor)
    this.hint = hint
    textSize = 17f
    isSingleLine = true
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    isLongClickable = false
    setTextIsSelectable(false)
    highlightColor = Color.TRANSPARENT
    customSelectionActionModeCallback = NoCopyPaste
    customInsertionActionModeCallback = NoCopyPaste
    setOnEditorActionListener { _, action, _ ->
        if (action == EditorInfo.IME_ACTION_DONE) { onDone(); true } else false
    }
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { state.length = s?.length ?: 0 }
    })
    state.editText = this
    if (requestFocus) post { requestFocus() }
}

private object NoCopyPaste : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
    override fun onDestroyActionMode(mode: ActionMode?) = Unit
}

package io.github.iseaprojects.rivo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import io.github.iseaprojects.rivo.core.i18n.Strings

/**
 * One-line text prompt used by library actions (playlist name, rename).
 *
 * The value lives here, not in the caller: a dialog that survives recomposition keeps what
 * the user typed, and confirmation is the only path that hands the text back.
 */
@Composable
fun TextPromptDialog(
    title: String,
    hint: String?,
    initial: String,
    strings: Strings,
    allowEmpty: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(title, initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                label = hint?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val trimmed = text.trim()
                    if (allowEmpty || trimmed.isNotEmpty()) onConfirm(trimmed)
                }),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = allowEmpty || text.isNotBlank(),
            ) {
                Text(strings.text("TXT_ANDROID_CONFIRM"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.text("TXT_ANDROID_CANCEL"))
            }
        },
    )
}

/** Yes/no gate for destructive library actions. */
@Composable
fun ConfirmDialog(
    message: String,
    strings: Strings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text("TXT_ANDROID_CONFIRM")) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(strings.text("TXT_ANDROID_CONFIRM"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.text("TXT_ANDROID_CANCEL"))
            }
        },
    )
}

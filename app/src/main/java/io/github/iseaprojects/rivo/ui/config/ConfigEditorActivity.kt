package io.github.iseaprojects.rivo.ui.config

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import io.github.iseaprojects.rivo.RivoApp
import io.github.iseaprojects.rivo.ui.theme.RivoTheme
import kotlinx.coroutines.launch

/**
 * Plain-text editor for `files/config`.
 *
 * The config is the contract: the same bytes are editable from the DocumentsProvider, a
 * desktop over MTP or an external editor. A phone still needs a way to change one value
 * without leaving the app, so this screen edits that file directly and imports it back
 * through [io.github.iseaprojects.rivo.core.config.ConfigStore.importText], which keeps comments,
 * unknown keys and the defaults-filling behaviour identical to a hand edit.
 */
class ConfigEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as RivoApp).graph
        val initial = runCatching { graph.config.configFile.readText() }.getOrDefault("")

        setContent {
            val theme by graph.themes.current.collectAsStateWithLifecycle()
            val strings by graph.strings.strings.collectAsState()
            RivoTheme(theme = theme) {
                ConfigEditorScreen(
                    initial = initial,
                    title = strings.text("TXT_ANDROID_CFG_EDITOR"),
                    saveLabel = strings.text("TXT_ANDROID_SAVE"),
                    cancelLabel = strings.text("TXT_MENU_BACK"),
                    savedMessage = strings.text("TXT_ANDROID_CONFIG_SAVED"),
                    errorMessage = strings.text("TXT_ANDROID_CONFIG_SAVE_FAILED"),
                    fontFamily = FontFamily.Monospace,
                    onSave = { text -> graph.config.importText(text) },
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun ConfigEditorScreen(
    initial: String,
    title: String,
    saveLabel: String,
    cancelLabel: String,
    savedMessage: String,
    errorMessage: String,
    fontFamily: FontFamily,
    onSave: suspend (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onClose) { Text(cancelLabel) }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { onSave(text) }
                                .onSuccess { snackbar.showSnackbar(savedMessage) }
                                .onFailure { snackbar.showSnackbar(errorMessage) }
                        }
                    },
                ) { Text(saveLabel) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = fontFamily),
                label = null,
            )
        }
    }
}

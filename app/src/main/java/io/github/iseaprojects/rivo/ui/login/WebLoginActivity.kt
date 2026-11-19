package io.github.iseaprojects.rivo.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.iseaprojects.rivo.BuildConfig
import io.github.iseaprojects.rivo.RivoApp
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.ui.theme.RivoTheme
import kotlinx.coroutines.launch

/**
 * WebView login used to obtain YouTube session cookies.
 *
 * yt-x reads cookies from the desktop browser profile; Android has no such thing, so the
 * user signs in here once and the app keeps the resulting cookie jar encrypted (see
 * `CookieRepository`). The WebView's own jar is wiped afterwards so a captured session
 * exists in exactly one place, under the app's Keystore-backed storage.
 *
 * Result: `RESULT_OK` plus `EXTRA_IMPORTED` (cookie count), or `RESULT_CANCELED` with
 * `EXTRA_ERROR`.
 */
class WebLoginActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as RivoApp).graph

        setContent {
            val strings by graph.strings.strings.collectAsState()
            val theme by graph.themes.current.collectAsState()
            RivoTheme(theme = theme) {
                LoginScreen(
                    strings = strings,
                    onCreated = { view ->
                        webView = view
                        configure(view)
                    },
                    onDone = { capture(graph.cookies, strings) },
                    onCancel = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        // Google refuses its login form in WebViews that look like embedded browsers.
        // A plain Chrome-like UA is what makes the flow complete.
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = CHROME_UA
        }
        // Keep every navigation inside this WebView: the login flow bounces between
        // accounts.google.com and youtube.com and must not leave for an external browser.
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String,
            ): Boolean = false
        }
        view.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F")
    }

    private fun capture(repository: io.github.iseaprojects.rivo.data.net.CookieRepository, strings: Strings) {
        lifecycleScope.launch {
            when (val result = repository.captureFromWebView()) {
                is io.github.iseaprojects.rivo.data.net.CookieRepository.Result.Ok -> {
                    // One copy of the session only: ours, encrypted.
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(EXTRA_IMPORTED, result.imported),
                    )
                    finish()
                }
                is io.github.iseaprojects.rivo.data.net.CookieRepository.Result.Failure -> {
                    setResult(
                        RESULT_CANCELED,
                        Intent().putExtra(
                            EXTRA_ERROR,
                            result.reason.ifBlank {
                                strings.text("TXT_ANDROID_LOGIN_FAILED")
                            },
                        ),
                    )
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_IMPORTED = "io.github.iseaprojects.rivo.extra.COOKIES_IMPORTED"
        const val EXTRA_ERROR = "io.github.iseaprojects.rivo.extra.LOGIN_ERROR"

        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}

@Composable
private fun LoginScreen(
    strings: Strings,
    onCreated: (WebView) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.text("TXT_ANDROID_LOGIN_TITLE"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = strings.text("TXT_ANDROID_LOGIN_HINT"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(strings.text("TXT_ANDROID_CANCEL", "Cancel"))
                }
                Button(onClick = onDone) {
                    Text(strings.text("TXT_ANDROID_LOGIN_DONE", "Done"))
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).also { view -> onCreated(view) }
                },
            )
        }
    }
}

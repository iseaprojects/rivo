package io.github.iseaprojects.rivo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.iseaprojects.rivo.RivoApp
import io.github.iseaprojects.rivo.core.launch.LaunchSpec
import io.github.iseaprojects.rivo.core.launch.LaunchSpecParser
import io.github.iseaprojects.rivo.di.AppGraph

/**
 * Single activity. Every entry point (launcher icon, rivo:// deep link, YouTube URL,
 * share sheet, static shortcut, Tasker, QS tile) funnels through the inbox held by
 * [RivoViewModel] and is normalized by [LaunchSpecParser], which is the Android
 * equivalent of yt-x's argv parser.
 *
 * The inbox lives in the view model so a rotation keeps both the menu back stack and
 * the "already handled" state. `lastEvent == null` is the only case where the launch
 * intent is pushed again, which is exactly the process-death case.
 */
class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph
    private val model: RivoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        graph = (application as RivoApp).graph
        if (model.inbox.lastEvent == null) {
            val initial = parseIntent(intent)
            if (!initial.isEmpty) model.inbox.push(initial)
        }

        setContent {
            RivoRoot(
                graph = graph,
                nav = model.nav,
                inbox = model.inbox,
                onExit = { finish() },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val spec = parseIntent(intent)
        if (!spec.isEmpty) model.inbox.push(spec)
    }

    override fun onResume() {
        super.onResume()
        // The config file is also editable through SAF/Termux while the app is alive.
        graph.config.reloadBlocking()
    }

    @Suppress("DEPRECATION") // Bundle.get(String) is deprecated in API 37; extras are heterogeneous on purpose.
    private fun parseIntent(intent: Intent?): LaunchSpec {
        val extras = intent?.extras
        val map: Map<String, Any?> = if (extras == null) {
            emptyMap()
        } else {
            extras.keySet().associateWith { key -> extras.get(key) }
        }
        return LaunchSpecParser.parse(
            dataUri = intent?.dataString,
            intentAction = intent?.action,
            mimeType = intent?.type,
            extras = map,
        )
    }
}

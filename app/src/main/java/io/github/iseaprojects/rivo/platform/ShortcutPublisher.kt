package io.github.iseaprojects.rivo.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import io.github.iseaprojects.rivo.R
import io.github.iseaprojects.rivo.core.commands.StoredCommand
import io.github.iseaprojects.rivo.ui.MainActivity
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes locally created custom commands as dynamic launcher shortcuts.
 *
 * The static shortcuts (`res/xml/shortcuts.xml`) cover the fixed screens; commands are user
 * data and change at runtime, which is exactly what dynamic shortcuts are for. Four is the
 * documented safe ceiling across launchers, so the list is capped rather than letting the
 * launcher truncate an arbitrary subset.
 */
object ShortcutPublisher {

    private const val MAX_SHORTCUTS = 4

    suspend fun publish(context: Context, commands: List<StoredCommand>) = withContext(Dispatchers.IO) {
        val shortcuts = commands
            .filter { it.name.isNotBlank() && it.url.isNotBlank() }
            .take(MAX_SHORTCUTS)
            .map { command ->
                val deepLink = "rivo://custom-cmd?name=${encode(command.name)}"
                ShortcutInfoCompat.Builder(context, "cmd-${command.id}")
                    .setShortLabel(command.name.take(40))
                    .setLongLabel(command.name)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_search))
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .setData(deepLink.toUri()),
                    )
                    .build()
            }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

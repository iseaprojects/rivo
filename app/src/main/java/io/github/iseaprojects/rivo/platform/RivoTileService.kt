package io.github.iseaprojects.rivo.platform

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.net.toUri
import io.github.iseaprojects.rivo.R
import io.github.iseaprojects.rivo.ui.MainActivity

/**
 * Quick Settings tile: one tap opens search.
 *
 * A tile cannot show a list, so it is a launcher for the screen that benefits most from
 * being one gesture away. The intent is the same `rivo://search` deep link a shortcut uses,
 * which keeps the tile outside the navigation model entirely.
 */
class RivoTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }

    // The PendingIntent overload only exists on Android 14+, so older devices must use the
    // Intent form that the SDK marks deprecated. It is the only way to collapse the shade
    // and launch in one step before UPSIDE_DOWN_CAKE.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("rivo://search".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

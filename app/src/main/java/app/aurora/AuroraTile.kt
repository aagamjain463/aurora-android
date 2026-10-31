package app.aurora

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A Quick Settings tile: lit while protection runs, naming it; a tap opens Aurora. */
class AuroraTile : TileService() {
    override fun onStartListening() {
        val app = applicationContext as? AuroraApplication ?: return
        val tile = qsTile ?: return
        val runs = app.runsNow()
        tile.state = if (runs.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            runs.isEmpty() -> getString(R.string.tile_off)
            runs.size == 1 -> runs.single().name
            else -> getString(R.string.tile_running, runs.size)
        }
        tile.updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun refresh(app: AuroraApplication) {
            runCatching { requestListeningState(app, ComponentName(app, AuroraTile::class.java)) }
        }
    }
}

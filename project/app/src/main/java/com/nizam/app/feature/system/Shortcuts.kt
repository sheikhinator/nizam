package com.nizam.app.feature.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nizam.app.MainActivity
import com.nizam.app.ui.nav.Routes

/** Long-press the Atlas icon: straight into the four things you reach for most. */
object Shortcuts {
    private fun open(c: Context, route: String) = Intent(c, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW).putExtra("route", route).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun publish(c: Context) = runCatching {
        val list = listOf(
            Triple("ask", "Ask Atlas", Routes.ASK) to android.R.drawable.ic_menu_help,
            Triple("voice", "Talk to Atlas", Routes.VOICE) to android.R.drawable.ic_btn_speak_now,
            Triple("prayer", "Prayer times", Routes.PRAYER) to android.R.drawable.ic_menu_view,
            Triple("money", "Add expense", Routes.FINANCE) to android.R.drawable.ic_menu_add
        ).map { (t, icon) ->
            ShortcutInfoCompat.Builder(c, t.first).setShortLabel(t.second).setLongLabel(t.second)
                .setIcon(IconCompat.createWithResource(c, icon)).setIntent(open(c, t.third)).build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(c, list)
    }
}

/** A Quick Settings tile: pull down the shade, tap, and Atlas is listening. */
class AskAtlasTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "Ask Atlas"
            icon = Icon.createWithResource(this@AskAtlasTile, android.R.drawable.ic_btn_speak_now)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).putExtra("route", Routes.VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else startActivityAndCollapse(i)
    }
}

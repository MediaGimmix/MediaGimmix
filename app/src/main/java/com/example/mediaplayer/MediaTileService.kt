package com.example.mediaplayer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The "Media" Quick Settings tile: tapping it opens the app and closes the
 * notification shade. Add it from the shade's edit (pencil) screen.
 */
class MediaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = "Media"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(R.string.app_name)
        // Your own icon: assets/icons/tile_media.png (white on transparent; Android tints it).
        // Without it Android shows its default tile icon.
        val bitmap = runCatching { assets.open("icons/tile_media.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        if (bitmap != null) tile.icon = Icon.createWithBitmap(bitmap)
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openApp() } else openApp()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

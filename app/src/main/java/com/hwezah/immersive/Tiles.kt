package com.hwezah.immersive

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings: one tile for immersive mode, one that cycles rotation. */
object Tiles {
    fun refresh(context: Context) {
        for (c in listOf(BarsTile::class.java, TurnTile::class.java)) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, c)) }
        }
    }
}

class BarsTile : TileService() {
    override fun onStartListening() {
        Prefs.init(this)
        qsTile?.apply {
            state = if (Prefs.immersive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Immersive"
            if (android.os.Build.VERSION.SDK_INT >= 29) subtitle = if (!ImmersiveService.running && !OverlayService.running) "Service off" else if (Prefs.immersive) "On" else "Off"
            updateTile()
        }
    }

    override fun onClick() {
        Prefs.init(this)
        Prefs.useImmersive(!Prefs.immersive)
        OverlayService.startIfNeeded(this)
        onStartListening()
    }
}

class TurnTile : TileService() {
    private val cycle = listOf(Turn.OFF, Turn.AUTO, Turn.LANDSCAPE, Turn.PORTRAIT)

    override fun onStartListening() {
        Prefs.init(this)
        qsTile?.apply {
            state = if (Prefs.turn == Turn.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            label = "Rotation"
            if (android.os.Build.VERSION.SDK_INT >= 29) subtitle = Prefs.turn.label
            updateTile()
        }
    }

    override fun onClick() {
        Prefs.init(this)
        val i = cycle.indexOf(Prefs.turn)
        Prefs.useTurn(cycle[(i + 1) % cycle.size])
        OverlayService.startIfNeeded(this)
        onStartListening()
    }
}

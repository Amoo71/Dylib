package com.amoo.roomlauncher

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.amoo.roomlauncher.govee.GoveeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Schnelleinstellungs-Kachel "Licht": Tippen schaltet die LED-Leiste an/aus – aus jeder App heraus. */
class LightTileService : TileService() {
    private var job: Job? = null
    private val app get() = application as RoomApp

    override fun onStartListening() {
        super.onStartListening()
        if (BtPerms.granted(this)) app.startBluetooth()
        job?.cancel()
        job = CoroutineScope(Dispatchers.Main.immediate).launch {
            combine(app.govee.power, app.govee.connection) { on, conn -> on to conn }.collect { (on, conn) ->
                val tile = qsTile ?: return@collect
                tile.state = when {
                    conn == GoveeController.Connection.NOT_CONFIGURED -> Tile.STATE_UNAVAILABLE
                    on -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.label = "Licht"
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        app.govee.togglePower()
    }
}

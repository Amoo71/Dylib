package com.amoo.roomlauncher

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.amoo.roomlauncher.airpods.AirPodsController
import com.amoo.roomlauncher.apps.AppRepository
import com.amoo.roomlauncher.board.BoardStore
import com.amoo.roomlauncher.theme.ThemeStore
import com.amoo.roomlauncher.govee.GoveeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass

class RoomApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: Prefs
        private set
    lateinit var govee: GoveeController
        private set
    lateinit var airpods: AirPodsController
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var board: BoardStore
        private set
    lateinit var theme: ThemeStore
        private set

    /** Zählt Home-Tasten-Drücke, damit offene Overlays geschlossen werden. */
    val homePresses = MutableStateFlow(0)

    private var bluetoothStarted = false

    override fun onCreate() {
        super.onCreate()
        // Erlaubt Reflection auf versteckte Bluetooth-APIs (L2CAP-Socket für AirPods).
        if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }

        prefs = Prefs(this)
        govee = GoveeController(this, prefs, appScope)
        airpods = AirPodsController(this, prefs, appScope)
        apps = AppRepository(this, prefs, appScope)
        board = BoardStore(this)
        theme = ThemeStore(this)
        apps.start()
    }

    fun startBluetooth() {
        if (bluetoothStarted) return
        bluetoothStarted = true
        govee.start()
        airpods.start()
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_ON) {
                        govee.connect()
                        airpods.onBluetoothOn()
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}

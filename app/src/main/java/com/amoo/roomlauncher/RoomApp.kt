package com.amoo.roomlauncher

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.amoo.roomlauncher.airpods.AirPodsController
import com.amoo.roomlauncher.apps.AppRepository
import com.amoo.roomlauncher.ble.BleHub
import com.amoo.roomlauncher.board.BoardStore
import com.amoo.roomlauncher.govee.GoveeController
import com.amoo.roomlauncher.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.lsposed.hiddenapibypass.HiddenApiBypass

class RoomApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: Prefs
        private set
    lateinit var ble: BleHub
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

    private var bluetoothStarted = false

    override fun onCreate() {
        super.onCreate()
        // Erlaubt ab Android 9 Reflection auf versteckte Bluetooth-APIs (AirPods-Steuerkanal).
        if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }

        prefs = Prefs(this)
        ble = BleHub(this)
        govee = GoveeController(this, prefs, appScope, ble)
        airpods = AirPodsController(this, prefs, appScope, ble)
        apps = AppRepository(this, appScope)
        board = BoardStore(this)
        theme = ThemeStore(this)
        apps.start()
    }

    fun startBluetooth() {
        if (bluetoothStarted) return
        bluetoothStarted = true
        govee.start()
        airpods.start()
        registerSystemReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> {
                            ble.restart()
                            govee.connect()
                            airpods.onBluetoothOn()
                        }
                        BluetoothAdapter.STATE_OFF -> ble.stop()
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }
}

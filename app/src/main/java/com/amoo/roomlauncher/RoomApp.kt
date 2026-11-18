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
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Darstellung der App-Icons (Original / Glas / Klar) – live änderbar. */
    lateinit var iconStyle: MutableStateFlow<Int>
        private set

    /** 60 fps (true) oder 30 fps (false) für animierte Hintergründe. */
    lateinit var smooth: MutableStateFlow<Boolean>
        private set

    private var bluetoothStarted = false

    override fun onCreate() {
        super.onCreate()
        // Erlaubt ab Android 9 Reflection auf versteckte Bluetooth-APIs (AirPods verbinden/trennen).
        if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }

        CrashLog.install(this)
        com.amoo.roomlauncher.ui.Fonts.init(this)
        prefs = Prefs(this)
        ble = BleHub(this)
        // Bekannte Leiste (H617E aus dem Bauplan) direkt eintragen → verbindet sofort über die MAC, ohne Suche
        if (prefs.goveeAddress == null && !prefs.goveeDefaultApplied) {
            prefs.goveeAddress = GoveeController.DEFAULT_MAC
            prefs.goveeName = GoveeController.DEFAULT_NAME
        }
        prefs.goveeDefaultApplied = true
        govee = GoveeController(this, prefs, appScope, ble)
        airpods = AirPodsController(this, prefs, appScope)
        apps = AppRepository(this, appScope)
        board = BoardStore(this)
        theme = ThemeStore(this)
        iconStyle = MutableStateFlow(prefs.iconStyle)
        smooth = MutableStateFlow(prefs.smooth)
        apps.start()
        watchBluetooth()
    }

    fun startBluetooth() {
        if (bluetoothStarted) return
        bluetoothStarted = true
        govee.start()
        airpods.start()
    }

    /** Bluetooth an/aus – live, damit Hinweise und Fehlermeldungen nie veralten. */
    val bluetoothOn = MutableStateFlow(false)

    private fun adapterEnabled(): Boolean = BleHub.bluetoothOn(this)

    /** Beobachtet den Bluetooth-Zustand ab App-Start (nicht erst, wenn die Geräte gestartet sind). */
    private fun watchBluetooth() {
        bluetoothOn.value = adapterEnabled()
        registerSystemReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> {
                            bluetoothOn.value = true
                            if (bluetoothStarted) {
                                ble.restart()
                                govee.onBluetoothOn()
                                airpods.onBluetoothOn()
                            }
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            bluetoothOn.value = false
                            ble.stop()
                        }
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }
}

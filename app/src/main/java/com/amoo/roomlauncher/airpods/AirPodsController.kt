package com.amoo.roomlauncher.airpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.amoo.roomlauncher.L
import com.amoo.roomlauncher.Prefs
import com.amoo.roomlauncher.registerSystemReceiver
import com.amoo.roomlauncher.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AirPods – bewusst schlank: Name und ob sie verbunden sind (für das Abzeichen oben),
 * plus Verbinden/Trennen und Gerät auswählen. Kein BLE-Dauerscan, kein Steuerkanal.
 */
@SuppressLint("MissingPermission")
class AirPodsController(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    data class PairedDevice(val name: String, val address: String)

    private val _audioConnected = MutableStateFlow(false)
    val audioConnected: StateFlow<Boolean> = _audioConnected.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    /** Name der gekoppelten AirPods (null = keine gekoppelt). */
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var a2dp: BluetoothProfile? = null
    @Volatile private var headset: BluetoothProfile? = null
    private var started = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dp = proxy else headset = proxy
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null else headset = null
        }
    }

    /** Audio-Profile anfordern – auch erneut, wenn Bluetooth beim Start noch aus war. */
    private fun bindProfiles() {
        val a = adapter ?: return
        if (a2dp == null) runCatching { a.getProfileProxy(context, profileListener, BluetoothProfile.A2DP) }
        if (headset == null) runCatching { a.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET) }
    }

    fun start() {
        if (started) return
        started = true
        bindProfiles()
        context.registerSystemReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = refresh()
            },
            IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
        )
        refresh()
        // Selbstheilung: alle 2 min abgleichen, falls ein Broadcast verloren ging (Broadcasts melden sonst alles sofort)
        scope.launch {
            while (isActive) {
                delay(120_000)
                bindProfiles()
                refresh()
            }
        }
    }

    fun onBluetoothOn() {
        bindProfiles()
        refresh()
    }

    fun pairedDevices(): List<PairedDevice> = runCatching {
        adapter?.bondedDevices.orEmpty()
            .map { PairedDevice(runCatching { it.name }.getOrNull() ?: it.address, it.address) }
            .sortedByDescending { it.name.contains("AirPods", true) || it.name.contains("Beats", true) }
    }.getOrDefault(emptyList())

    fun selectDevice(address: String) {
        prefs.airpodsAddress = address
        refresh()
    }

    private fun device(): BluetoothDevice? {
        val a = adapter ?: return null
        val bonded = runCatching { a.bondedDevices }.getOrNull().orEmpty()
        val saved = prefs.airpodsAddress
        return bonded.firstOrNull { it.address == saved }
            ?: bonded.firstOrNull { runCatching { it.name }.getOrNull()?.contains("AirPods", true) == true }
    }

    private fun refresh() {
        val dev = device()
        _deviceName.value = dev?.let { runCatching { it.name }.getOrNull() ?: it.address }
        val connected = dev != null && listOfNotNull(a2dp, headset).any {
            runCatching { it.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
        }
        if (connected != _audioConnected.value) L.i("AirPods: ${if (connected) "verbunden" else "getrennt"} (${dev?.address})")
        _audioConnected.value = connected
    }

    fun toggle() = if (_audioConnected.value) disconnect() else connect()

    fun connect() {
        val dev = device() ?: run {
            context.toast("Keine AirPods gekoppelt – bitte in den Bluetooth-Einstellungen koppeln")
            return
        }
        scope.launch(Dispatchers.IO) {
            var ok = false
            for (p in listOfNotNull(a2dp, headset)) ok = invoke(p, "connect", dev) || ok
            if (!ok) context.toast("Bitte über die Bluetooth-Einstellungen verbinden")
            delay(3000)
            refresh()
        }
    }

    fun disconnect() {
        val dev = device() ?: return
        scope.launch(Dispatchers.IO) {
            for (p in listOfNotNull(a2dp, headset)) invoke(p, "disconnect", dev)
            delay(1500)
            refresh()
        }
    }

    /** connect()/disconnect() der Audio-Profile sind versteckt, unter Android 7 aber per Reflection erreichbar. */
    private fun invoke(profile: BluetoothProfile, method: String, dev: BluetoothDevice): Boolean = try {
        val m = profile.javaClass.getMethod(method, BluetoothDevice::class.java)
        m.isAccessible = true
        (m.invoke(profile, dev) as? Boolean) ?: true
    } catch (e: Throwable) {
        L.w("AirPods $method fehlgeschlagen", e)
        false
    }
}

package com.amoo.roomlauncher.airpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.amoo.roomlauncher.Prefs
import com.amoo.roomlauncher.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream

/**
 * AirPods-Steuerung:
 *  1. Audio verbinden/trennen über die (versteckten) connect()/disconnect()-Methoden von A2DP/HFP.
 *  2. Akkustand aus Apples BLE-"Proximity Pairing"-Werbepaketen (funktioniert ohne Root).
 *  3. Geräuschkontrolle (ANC/Transparenz/Adaptiv/Aus) über das Apple Accessory Protocol (AAP)
 *     auf einem klassischen L2CAP-Kanal, PSM 0x1001 – wie in LibrePods / MagicPods / AirPods-Desktop.
 *     Android bietet dafür keine öffentliche API; es wird per Reflection versucht. Ob das klappt,
 *     hängt von Android-Version und Bluetooth-Stack des Geräts ab.
 */
@SuppressLint("MissingPermission")
class AirPodsController(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    enum class Noise(val code: Int, val label: String) {
        OFF(0x01, "Aus"),
        TRANSPARENCY(0x03, "Transparenz"),
        ADAPTIVE(0x04, "Adaptiv"),
        ANC(0x02, "ANC"),
    }

    data class Battery(
        val left: Int? = null,
        val right: Int? = null,
        val case: Int? = null,
        val chargingLeft: Boolean = false,
        val chargingRight: Boolean = false,
        val chargingCase: Boolean = false,
    )

    data class PairedDevice(val name: String, val address: String)

    private val _audioConnected = MutableStateFlow(false)
    val audioConnected: StateFlow<Boolean> = _audioConnected.asStateFlow()

    private val _battery = MutableStateFlow<Battery?>(null)
    val battery: StateFlow<Battery?> = _battery.asStateFlow()

    private val _model = MutableStateFlow<String?>(null)
    val model: StateFlow<String?> = _model.asStateFlow()

    private val _noise = MutableStateFlow<Noise?>(null)
    val noise: StateFlow<Noise?> = _noise.asStateFlow()

    private val _controlReady = MutableStateFlow(false)
    val controlReady: StateFlow<Boolean> = _controlReady.asStateFlow()

    private val _controlStatus = MutableStateFlow("Steuerkanal nicht verbunden")
    val controlStatus: StateFlow<String> = _controlStatus.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    private var started = false
    private var bleCallback: ScanCallback? = null
    private var lastAapBattery = 0L
    private var lastBleSeen = 0L

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private var controlJob: Job? = null

    fun start() {
        if (started) return
        started = true
        val a = adapter ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) a2dp = proxy else headset = proxy
                refreshConnectionState()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) a2dp = null else headset = null
            }
        }
        runCatching { a.getProfileProxy(context, listener, BluetoothProfile.A2DP) }
        runCatching { a.getProfileProxy(context, listener, BluetoothProfile.HEADSET) }

        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = refreshConnectionState()
            },
            IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        startBatteryScan()
        // Abgelaufene BLE-Akkuwerte ausblenden
        scope.launch {
            while (isActive) {
                delay(10_000)
                val now = System.currentTimeMillis()
                if (_battery.value != null && now - lastBleSeen > 90_000 && now - lastAapBattery > 90_000 && !_controlReady.value) {
                    _battery.value = null
                }
            }
        }
    }

    fun onBluetoothOn() {
        startBatteryScan()
        refreshConnectionState()
    }

    // ---------------------------------------------------------------- Gerät

    fun pairedDevices(): List<PairedDevice> = runCatching {
        adapter?.bondedDevices.orEmpty()
            .map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedByDescending { it.name.contains("AirPods", ignoreCase = true) || it.name.contains("Beats", true) }
    }.getOrDefault(emptyList())

    fun selectDevice(address: String) {
        closeControl()
        prefs.airpodsAddress = address
        refreshConnectionState()
    }

    private fun device(): BluetoothDevice? {
        val a = adapter ?: return null
        val bonded = runCatching { a.bondedDevices }.getOrNull().orEmpty()
        val saved = prefs.airpodsAddress
        return bonded.firstOrNull { it.address == saved }
            ?: bonded.firstOrNull { runCatching { it.name }.getOrNull()?.contains("AirPods", true) == true }
    }

    private fun refreshConnectionState() {
        val dev = device()
        _deviceName.value = dev?.let { runCatching { it.name }.getOrNull() }
        val connected = dev != null && listOfNotNull(a2dp, headset).any {
            runCatching { it.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
        }
        val was = _audioConnected.value
        _audioConnected.value = connected
        if (connected && !was) {
            scope.launch {
                delay(1500)
                openControl()
            }
        }
        if (!connected && was) closeControl()
    }

    fun connectAudio() {
        val dev = device() ?: run {
            context.toast("Keine AirPods gekoppelt – bitte zuerst in den Bluetooth-Einstellungen koppeln")
            return
        }
        scope.launch(Dispatchers.IO) {
            var ok = false
            for (p in listOfNotNull(a2dp, headset)) {
                ok = invokeProfile(p, "connect", dev) || ok
            }
            if (!ok) context.toast("Automatisches Verbinden nicht erlaubt – bitte über Bluetooth-Einstellungen verbinden")
            delay(3000)
            refreshConnectionState()
        }
    }

    fun disconnectAudio() {
        val dev = device() ?: return
        closeControl()
        scope.launch(Dispatchers.IO) {
            for (p in listOfNotNull(a2dp, headset)) invokeProfile(p, "disconnect", dev)
            delay(1500)
            refreshConnectionState()
        }
    }

    private fun invokeProfile(profile: BluetoothProfile, method: String, dev: BluetoothDevice): Boolean = try {
        val m = profile.javaClass.getMethod(method, BluetoothDevice::class.java)
        m.isAccessible = true
        (m.invoke(profile, dev) as? Boolean) ?: true
    } catch (e: Throwable) {
        false
    }

    // ---------------------------------------------------------------- Geräuschkontrolle (AAP)

    fun setNoise(mode: Noise) {
        val out = output
        if (out == null || !_controlReady.value) {
            context.toast("Steuerkanal nicht verbunden – versuche neu zu verbinden…")
            openControl()
            return
        }
        _noise.value = mode
        scope.launch(Dispatchers.IO) {
            try {
                write(Aap.noiseControl(mode.code))
            } catch (e: IOException) {
                _controlStatus.value = "Senden fehlgeschlagen: ${e.message}"
                closeControl()
            }
        }
    }

    fun openControl() {
        if (controlJob?.isActive == true) return
        val dev = device() ?: run {
            _controlStatus.value = "Keine AirPods ausgewählt"
            return
        }
        controlJob = scope.launch(Dispatchers.IO) {
            _controlStatus.value = "Verbinde Steuerkanal…"
            val s = try {
                createL2capSocket(dev)
            } catch (e: Throwable) {
                _controlStatus.value = "Steuerkanal auf diesem Android nicht verfügbar"
                return@launch
            }
            try {
                runCatching { adapter?.cancelDiscovery() }
                s.connect()
                socket = s
                output = s.outputStream
                write(Aap.HANDSHAKE)
                delay(250)
                write(Aap.SET_FEATURES)
                delay(100)
                write(Aap.REQUEST_NOTIFICATIONS)
                _controlReady.value = true
                _controlStatus.value = "Steuerkanal verbunden"
                val input = s.inputStream
                val buf = ByteArray(2048)
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) handlePacket(buf.copyOf(n))
                }
                _controlStatus.value = "Steuerkanal getrennt"
            } catch (e: IOException) {
                _controlStatus.value = "Steuerkanal fehlgeschlagen (${e.message ?: "IO"}) – evtl. Root/neueres Android nötig"
            } finally {
                closeSocketOnly()
            }
        }
    }

    private fun closeSocketOnly() {
        _controlReady.value = false
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    fun closeControl() {
        controlJob?.cancel()
        closeSocketOnly()
    }

    @Synchronized
    private fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("nicht verbunden")
        out.write(bytes)
        out.flush()
    }

    private fun handlePacket(p: ByteArray) {
        when (val msg = Aap.parse(p)) {
            is Aap.Message.NoiseMode -> _noise.value = Noise.entries.firstOrNull { it.code == msg.code }
            is Aap.Message.BatteryInfo -> {
                lastAapBattery = System.currentTimeMillis()
                val old = _battery.value ?: Battery()
                _battery.value = Battery(
                    left = msg.left,
                    right = msg.right,
                    case = msg.case ?: old.case,
                    chargingLeft = msg.chargingLeft,
                    chargingRight = msg.chargingRight,
                    chargingCase = if (msg.case != null) msg.chargingCase else old.chargingCase,
                )
            }
            null -> Unit
        }
    }

    /**
     * Erstellt einen klassischen L2CAP-Socket (TYPE_L2CAP = 3) zu PSM 0x1001.
     * Versucht nacheinander versteckte Methoden und bekannte Konstruktor-Signaturen.
     */
    private fun createL2capSocket(dev: BluetoothDevice): BluetoothSocket {
        val intT = Int::class.javaPrimitiveType
        val boolT = Boolean::class.javaPrimitiveType
        for (name in listOf("createL2capSocket", "createInsecureL2capSocket")) {
            try {
                val m = BluetoothDevice::class.java.getDeclaredMethod(name, intT)
                m.isAccessible = true
                return m.invoke(dev, Aap.PSM) as BluetoothSocket
            } catch (_: Throwable) {
            }
        }
        val uuid = ParcelUuid.fromString("74ec2172-0bf9-11ef-9262-0242ac120002")
        val dc = BluetoothDevice::class.java
        val pc = ParcelUuid::class.java
        for (c in BluetoothSocket::class.java.declaredConstructors) {
            val p = c.parameterTypes.toList()
            val args: Array<Any?> = when (p) {
                listOf(intT, intT, boolT, boolT, dc, intT, pc) ->
                    arrayOf<Any?>(TYPE_L2CAP, -1, true, true, dev, Aap.PSM, uuid)
                listOf(intT, intT, boolT, boolT, dc, intT, pc, boolT, boolT) ->
                    arrayOf<Any?>(TYPE_L2CAP, -1, true, true, dev, Aap.PSM, uuid, false, false)
                listOf(intT, boolT, boolT, dc, intT, pc) ->
                    arrayOf<Any?>(TYPE_L2CAP, true, true, dev, Aap.PSM, uuid)
                listOf(intT, boolT, boolT, dc, intT, pc, boolT, boolT) ->
                    arrayOf<Any?>(TYPE_L2CAP, true, true, dev, Aap.PSM, uuid, false, false)
                else -> continue
            }
            try {
                c.isAccessible = true
                return c.newInstance(*args) as BluetoothSocket
            } catch (_: Throwable) {
            }
        }
        throw IOException("Kein L2CAP-Socket möglich")
    }

    // ---------------------------------------------------------------- Akku via BLE

    private fun startBatteryScan() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        bleCallback?.let { runCatching { scanner.stopScan(it) } }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handleAd(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handleAd(it) }
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE_ID, byteArrayOf(0x07, 0x19), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        try {
            scanner.startScan(listOf(filter), settings, cb)
            bleCallback = cb
        } catch (_: Throwable) {
        }
    }

    private fun handleAd(result: ScanResult) {
        if (result.rssi < -75) return
        val data = result.scanRecord?.getManufacturerSpecificData(APPLE_ID) ?: return
        val info = ProximityParser.parse(data) ?: return
        lastBleSeen = System.currentTimeMillis()
        _model.value = info.model
        // Wenn der AAP-Kanal frische Werte liefert, haben die Vorrang.
        if (System.currentTimeMillis() - lastAapBattery < 60_000) return
        _battery.value = Battery(
            left = info.left, right = info.right, case = info.case,
            chargingLeft = info.chargingLeft, chargingRight = info.chargingRight, chargingCase = info.chargingCase,
        )
    }

    companion object {
        private const val APPLE_ID = 0x004C
        private const val TYPE_L2CAP = 3
    }
}

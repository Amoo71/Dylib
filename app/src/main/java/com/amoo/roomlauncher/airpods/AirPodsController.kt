package com.amoo.roomlauncher.airpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import com.amoo.roomlauncher.Prefs
import com.amoo.roomlauncher.registerSystemReceiver
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
import java.lang.reflect.InvocationTargetException

/**
 * AirPods-Steuerung, angelehnt an GreenPods (github.com/andrewkomkov/GreenPods):
 *
 *  - Audio verbinden/trennen über die versteckten connect()/disconnect()-Methoden von A2DP/HFP.
 *  - Akku, Laden und Ohrerkennung aus Apples BLE-Werbepaketen (läuft auf jedem Android).
 *  - Über AAP (L2CAP PSM 0x1001, Apple-Service-UUID, sicherer Kanal):
 *      Geräuschkontrolle Aus / Transparenz / Adaptiv / ANC, Gesprächserkennung,
 *      Stärke des adaptiven Audios, automatische Ohrerkennung an/aus, ANC mit einem AirPod.
 *    Android hat dafür keine offizielle API; der Kanal wird per Reflection gebaut.
 *    Ob die AirPods ihn annehmen, hängt vom Bluetooth-Stack ab (sicher ab Android 16 QPR3/17).
 */
@SuppressLint("MissingPermission")
class AirPodsController(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val hub: com.amoo.roomlauncher.ble.BleHub,
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

    data class InEar(val left: Boolean, val right: Boolean)

    data class PairedDevice(val name: String, val address: String)

    private val _audioConnected = MutableStateFlow(false)
    val audioConnected: StateFlow<Boolean> = _audioConnected.asStateFlow()

    private val _battery = MutableStateFlow<Battery?>(null)
    val battery: StateFlow<Battery?> = _battery.asStateFlow()

    private val _inEar = MutableStateFlow<InEar?>(null)
    val inEar: StateFlow<InEar?> = _inEar.asStateFlow()

    private val _model = MutableStateFlow<String?>(null)
    val model: StateFlow<String?> = _model.asStateFlow()

    private val _noise = MutableStateFlow<Noise?>(null)
    val noise: StateFlow<Noise?> = _noise.asStateFlow()

    private val _conversationAwareness = MutableStateFlow<Boolean?>(null)
    val conversationAwareness: StateFlow<Boolean?> = _conversationAwareness.asStateFlow()

    private val _adaptiveStrength = MutableStateFlow<Int?>(null)
    val adaptiveStrength: StateFlow<Int?> = _adaptiveStrength.asStateFlow()

    private val _earDetection = MutableStateFlow<Boolean?>(null)
    val earDetection: StateFlow<Boolean?> = _earDetection.asStateFlow()

    private val _oneBudAnc = MutableStateFlow<Boolean?>(null)
    val oneBudAnc: StateFlow<Boolean?> = _oneBudAnc.asStateFlow()

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
    @Volatile private var lastAapBattery = 0L
    @Volatile private var lastBleSeen = 0L

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private var controlJob: Job? = null
    private var retries = 0

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dp = proxy else headset = proxy
            refreshConnectionState()
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
                override fun onReceive(c: Context, intent: Intent) = refreshConnectionState()
            },
            IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
        )
        startBatteryScan()
        scope.launch {
            var n = 0
            while (isActive) {
                delay(10_000)
                // alle 30 s: Verbindungsstatus neu prüfen und Profile ggf. neu anfordern
                if (++n % 3 == 0) {
                    bindProfiles()
                    refreshConnectionState()
                    // Audio verbunden, Steuerkanal aber weg → still neu versuchen (max. alle 30 s)
                    if (_audioConnected.value && !_controlReady.value && controlJob?.isActive != true && retries < 3) {
                        retries++
                        openControl()
                    }
                }
                val now = System.currentTimeMillis()
                if (_battery.value != null && now - lastBleSeen > 90_000 && now - lastAapBattery > 90_000 && !_controlReady.value) {
                    _battery.value = null
                    _inEar.value = null
                }
            }
        }
    }

    fun onBluetoothOn() {
        bindProfiles()
        startBatteryScan()
        refreshConnectionState()
    }

    // ---------------------------------------------------------------- Gerät

    fun pairedDevices(): List<PairedDevice> = runCatching {
        adapter?.bondedDevices.orEmpty()
            .map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedByDescending { it.name.contains("AirPods", ignoreCase = true) || it.name.contains("Beats", true) }
    }.getOrDefault(emptyList())

    /** Alles frisch: Steuerkanal schließen, Profile neu holen, Status neu lesen. */
    fun resync() {
        closeControl()
        retries = 0
        bindProfiles()
        refreshConnectionState()
        if (_audioConnected.value) openControl()
    }

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
            retries = 0
            scope.launch {
                delay(1500)
                openControl()
            }
        }
        if (!connected && was) {
            closeControl()
            _controlStatus.value = "Steuerkanal nicht verbunden"
        }
    }

    fun connectAudio() {
        val dev = device() ?: run {
            context.toast("Keine AirPods gekoppelt – bitte zuerst in den Bluetooth-Einstellungen koppeln")
            return
        }
        scope.launch(Dispatchers.IO) {
            var ok = false
            for (p in listOfNotNull(a2dp, headset)) ok = invokeProfile(p, "connect", dev) || ok
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

    // ---------------------------------------------------------------- Befehle (AAP)

    fun setNoise(mode: Noise) {
        if (send(Aap.control(Aap.CMD_LISTENING_MODE, mode.code))) _noise.value = mode
    }

    fun setConversationAwareness(on: Boolean) {
        if (send(Aap.control(Aap.CMD_CONVERSATION_DETECT, Aap.toggle(on)))) _conversationAwareness.value = on
    }

    fun setAdaptiveStrength(value: Int) {
        val v = value.coerceIn(0, 100)
        _adaptiveStrength.value = v
        send(Aap.control(Aap.CMD_ADAPTIVE_STRENGTH, v), quiet = true)
    }

    fun setEarDetection(on: Boolean) {
        if (send(Aap.control(Aap.CMD_EAR_DETECTION, Aap.toggle(on)))) _earDetection.value = on
    }

    fun setOneBudAnc(on: Boolean) {
        if (send(Aap.control(Aap.CMD_ONE_BUD_ANC, Aap.toggle(on)))) _oneBudAnc.value = on
    }

    private fun send(packet: ByteArray, quiet: Boolean = false): Boolean {
        if (output == null || !_controlReady.value) {
            if (!quiet) context.toast("Steuerkanal nicht verbunden – verbinde neu…")
            openControl()
            return false
        }
        scope.launch(Dispatchers.IO) {
            try {
                write(packet)
            } catch (e: IOException) {
                _controlStatus.value = "Senden fehlgeschlagen: ${e.message}"
                closeControl()
            }
        }
        return true
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
                createAapSocket(dev)
            } catch (e: Throwable) {
                _controlStatus.value = "Steuerkanal: Android erlaubt den Apple-Kanal nicht (${e.javaClass.simpleName})"
                return@launch
            }
            var frames = 0
            try {
                runCatching { adapter?.cancelDiscovery() }
                s.connect()
                socket = s
                output = s.outputStream
                write(Aap.HANDSHAKE)
                write(Aap.SET_HOST_CAPABILITIES)
                write(Aap.REQUEST_NOTIFICATIONS)
                _controlReady.value = true
                _controlStatus.value = "Steuerkanal verbunden"
                // Wächter: ein Kanal, der nichts sendet, ist tot (z.B. hält ein anderes Gerät ihn).
                val watchdog = launch {
                    delay(6000)
                    if (frames == 0) {
                        _controlStatus.value = "Kanal offen, aber AirPods antworten nicht – evtl. hält ein anderes Gerät/App ihn"
                        runCatching { s.close() }
                    }
                }
                val input = s.inputStream
                val buf = ByteArray(4096)
                while (isActive) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    for (packet in Aap.split(buf.copyOf(n))) {
                        frames++
                        handlePacket(packet)
                    }
                }
                watchdog.cancel()
                if (frames > 0) _controlStatus.value = "Steuerkanal getrennt"
            } catch (e: IOException) {
                if (frames == 0) {
                    _controlStatus.value = "Steuerkanal fehlgeschlagen: ${e.message ?: "IO"} – dieser Bluetooth-Stack lässt AAP nicht zu"
                } else {
                    _controlStatus.value = "Steuerkanal getrennt"
                }
            } finally {
                closeSocketOnly()
            }
            // Bei getrennter, aber noch verbundener Audio-Verbindung einmal neu versuchen.
            if (frames > 0 && _audioConnected.value && retries < 3) {
                retries++
                delay(3000)
                controlJob = null
                openControl()
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
            is Aap.Message.Control -> when (msg.command) {
                Aap.CMD_LISTENING_MODE -> _noise.value = Noise.entries.firstOrNull { it.code == msg.value }
                Aap.CMD_CONVERSATION_DETECT -> _conversationAwareness.value = msg.value == 0x01
                Aap.CMD_ADAPTIVE_STRENGTH -> _adaptiveStrength.value = msg.value.coerceIn(0, 100)
                Aap.CMD_EAR_DETECTION -> _earDetection.value = msg.value == 0x01
                Aap.CMD_ONE_BUD_ANC -> _oneBudAnc.value = msg.value == 0x01
            }
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
            is Aap.Message.EarDetection, is Aap.Message.SpeechLevel, null -> Unit
        }
    }

    /**
     * Baut einen sicheren klassischen L2CAP-Socket (TYPE_L2CAP = 3, auth + encrypt) zu PSM 0x1001
     * mit Apples Service-UUID. Probiert die bekannten versteckten Konstruktoren der Android-Versionen
     * durch (Reihenfolge wie GreenPods' AapTransport).
     */
    private fun createAapSocket(dev: BluetoothDevice): BluetoothSocket {
        val uuid = ParcelUuid.fromString(Aap.SERVICE_UUID)
        val psm = Aap.PSM
        val candidates = buildList<Array<Any>> {
            adapter?.let { add(arrayOf(it, dev, TYPE_L2CAP, true, true, psm, uuid)) } // Android 16 QPR3+
            add(arrayOf(dev, TYPE_L2CAP, true, true, psm, uuid))
            add(arrayOf(dev, TYPE_L2CAP, 1, true, true, psm, uuid))
            add(arrayOf(TYPE_L2CAP, 1, true, true, dev, psm, uuid))
            add(arrayOf(TYPE_L2CAP, -1, true, true, dev, psm, uuid)) // ältere Versionen (fd = -1)
            add(arrayOf(TYPE_L2CAP, -1, true, true, dev, psm, uuid, false, false))
            add(arrayOf(TYPE_L2CAP, true, true, dev, psm, uuid))
        }
        var last: Throwable? = null
        for (args in candidates) {
            try {
                val types = args.map { a ->
                    when (a) {
                        is Int -> Int::class.javaPrimitiveType!!
                        is Boolean -> Boolean::class.javaPrimitiveType!!
                        is BluetoothAdapter -> BluetoothAdapter::class.java
                        is BluetoothDevice -> BluetoothDevice::class.java
                        is ParcelUuid -> ParcelUuid::class.java
                        else -> a.javaClass
                    }
                }.toTypedArray()
                val c = BluetoothSocket::class.java.getDeclaredConstructor(*types)
                c.isAccessible = true
                return c.newInstance(*args) as BluetoothSocket
            } catch (e: InvocationTargetException) {
                throw (e.cause as? IOException ?: IOException("L2CAP-Socket konnte nicht erstellt werden", e))
            } catch (e: Throwable) {
                last = e
            }
        }
        // Letzter Versuch: versteckte Hilfsmethode am Gerät.
        try {
            val m = BluetoothDevice::class.java.getDeclaredMethod("createL2capSocket", Int::class.javaPrimitiveType)
            m.isAccessible = true
            return m.invoke(dev, psm) as BluetoothSocket
        } catch (e: Throwable) {
            last = e
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // Liefert immerhin eine klare Fehlermeldung beim connect().
            return dev.createInsecureL2capChannel(psm)
        }
        throw IOException("Kein passender BluetoothSocket-Konstruktor", last)
    }

    // ---------------------------------------------------------------- Akku/Ohrerkennung via BLE

    private val advertListener = com.amoo.roomlauncher.ble.BleHub.Listener { _, rssi, record ->
        if (rssi < -75) return@Listener
        val data = com.amoo.roomlauncher.ble.BleHub.parse(record).manufacturer[APPLE_ID] ?: return@Listener
        val info = ProximityParser.parse(data) ?: return@Listener
        lastBleSeen = System.currentTimeMillis()
        _model.value = info.model
        _inEar.value = InEar(info.leftInEar, info.rightInEar)
        if (System.currentTimeMillis() - lastAapBattery < 60_000) return@Listener
        _battery.value = Battery(
            left = info.left, right = info.right, case = info.case,
            chargingLeft = info.chargingLeft, chargingRight = info.chargingRight, chargingCase = info.chargingCase,
        )
    }

    private fun startBatteryScan() = hub.add(advertListener)

    companion object {
        private const val APPLE_ID = 0x004C
        private const val TYPE_L2CAP = 3
    }
}

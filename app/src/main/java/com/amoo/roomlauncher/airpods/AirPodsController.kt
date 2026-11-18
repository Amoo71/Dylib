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

    data class InEar(val left: Boolean, val right: Boolean, val leftInCase: Boolean = false, val rightInCase: Boolean = false)

    data class PairedDevice(val name: String, val address: String)

    private val _audioConnected = MutableStateFlow(false)
    val audioConnected: StateFlow<Boolean> = _audioConnected.asStateFlow()

    private val _battery = MutableStateFlow<Battery?>(null)
    val battery: StateFlow<Battery?> = _battery.asStateFlow()

    private val _inEar = MutableStateFlow<InEar?>(null)
    val inEar: StateFlow<InEar?> = _inEar.asStateFlow()

    private val _podModel = MutableStateFlow(prefs.airpodsModelId?.let { PodModel.fromId(it) }?.takeIf { it != PodModel.UNKNOWN })
    /** Erkanntes Modell der eigenen AirPods (aus dem Werbepaket gelernt). */
    val podModel: StateFlow<PodModel?> = _podModel.asStateFlow()

    private val _model = MutableStateFlow(_podModel.value?.label)
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

    private val _controlUnsupported = MutableStateFlow(false)
    /** true = dieser Bluetooth-Stack lässt Apples Steuerkanal nicht zu (z. B. Android 7 ohne Root). */
    val controlUnsupported: StateFlow<Boolean> = _controlUnsupported.asStateFlow()
    private var deadChannels = 0

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
                    if (_audioConnected.value && !_controlReady.value && !_controlUnsupported.value &&
                        controlJob?.isActive != true && retries < 3
                    ) {
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
        deadChannels = 0
        _controlUnsupported.value = false
        bindProfiles()
        refreshConnectionState()
        if (_audioConnected.value) openControl()
    }

    fun selectDevice(address: String) {
        closeControl()
        if (address != prefs.airpodsAddress) {
            prefs.airpodsModelId = null
            _podModel.value = null
            _model.value = null
            synchronized(sightings) { sightings.clear() }
            bestAddress = null
        }
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
            if (!quiet) explainUnavailable()
            if (!_controlUnsupported.value && _audioConnected.value) openControl()
            return false
        }
        scope.launch(Dispatchers.IO) {
            try {
                write(packet)
            } catch (e: IOException) {
                _controlStatus.value = "Verbindung zum Steuerkanal abgerissen – verbinde neu"
                closeControl()
            }
        }
        return true
    }

    private var lastExplain = 0L

    /** Freundliche Erklärung statt kryptischer Fehler (höchstens alle 4 s). */
    fun explainUnavailable() {
        val now = System.currentTimeMillis()
        if (now - lastExplain < 4000) return
        lastExplain = now
        context.toast(
            when {
                !_audioConnected.value -> "Erst die AirPods verbinden"
                _controlUnsupported.value ->
                    "Android 7 blockiert Apples Steuerkanal – Modus bitte am AirPods-Stiel wechseln (gedrückt halten)"
                else -> "Steuerkanal wird verbunden …"
            },
        )
    }

    /** Typische Socket-Fehler verständlich machen. */
    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty().lowercase()
        return when {
            "broken pipe" in m || "socket might closed" in m || "read failed" in m ->
                "Android hat den Apple-Kanal geschlossen"
            "refused" in m -> "AirPods haben den Kanal abgelehnt"
            "timeout" in m || "timed out" in m -> "keine Antwort (Zeitüberschreitung)"
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    fun openControl() {
        if (controlJob?.isActive == true || _controlUnsupported.value) return
        val dev = device() ?: run {
            _controlStatus.value = "Keine AirPods ausgewählt"
            return
        }
        controlJob = scope.launch(Dispatchers.IO) {
            _controlStatus.value = "Verbinde Steuerkanal…"
            val s = try {
                createAapSocket(dev)
            } catch (e: Throwable) {
                markUnsupported("Android erlaubt den Apple-Kanal nicht")
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
                // Erst bereit, wenn die AirPods wirklich antworten – sonst landet der nächste Befehl im Nichts
                _controlStatus.value = "Warte auf Antwort der AirPods…"
                val watchdog = launch {
                    delay(6000)
                    if (frames == 0) runCatching { s.close() }
                }
                val input = s.inputStream
                val buf = ByteArray(4096)
                while (isActive) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    for (packet in Aap.split(buf.copyOf(n))) {
                        frames++
                        if (frames == 1) {
                            deadChannels = 0
                            _controlReady.value = true
                            _controlStatus.value = "Steuerkanal verbunden"
                        }
                        handlePacket(packet)
                    }
                }
                watchdog.cancel()
                if (frames > 0) _controlStatus.value = "Steuerkanal getrennt"
            } catch (e: IOException) {
                if (frames > 0) _controlStatus.value = "Steuerkanal getrennt (${friendly(e)})"
            } finally {
                closeSocketOnly()
            }
            if (frames == 0) {
                // Kanal ging auf, aber nichts kam zurück: typisch für Android-Stacks ohne AAP-Unterstützung
                if (++deadChannels >= 2) {
                    markUnsupported("AirPods antworten auf diesem Android nicht")
                } else {
                    _controlStatus.value = "Keine Antwort – versuche es gleich noch einmal"
                }
            } else if (_audioConnected.value && retries < 3) {
                // War schon verbunden und ist abgerissen → einmal neu versuchen
                retries++
                delay(3000)
                controlJob = null
                openControl()
            }
        }
    }

    /** Apples Steuerkanal geht auf diesem Gerät nicht – nicht weiter versuchen, sondern erklären. */
    private fun markUnsupported(reason: String) {
        _controlUnsupported.value = true
        _controlStatus.value = "$reason. ANC/Transparenz bitte am AirPods-Stiel wechseln (gedrückt halten). " +
            "Akku, Ohrerkennung und Verbinden funktionieren trotzdem."
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

    private class Sighting(val info: ProximityParser.Info, val rssi: Int, val at: Long)

    /** Zuletzt gesehene Apple-Werbepakete je (wechselnder) Adresse. */
    private val sightings = HashMap<String, Sighting>()
    @Volatile private var bestAddress: String? = null

    /** Für die Diagnose: Signalstärke der eigenen AirPods und Zahl ignorierter fremder Pakete. */
    @Volatile var lastRssi: Int? = null
        private set
    @Volatile var ignoredForeign = 0
        private set

    /** Gelerntes Modell vergessen (z. B. bei anderen AirPods) – wird beim nächsten Verbinden neu erkannt. */
    fun forgetModel() {
        prefs.airpodsModelId = null
        _podModel.value = null
        _model.value = null
        synchronized(sightings) { sightings.clear() }
        bestAddress = null
        ignoredForeign = 0
    }

    /**
     * Werbepakete aller AirPods in Reichweite → die eigenen herausfinden:
     *  1. Solange verbunden, lernt die App das Modell der nächsten AirPods und merkt es sich.
     *  2. Danach zählen nur Pakete genau dieses Modells – fremde AirPods im Raum werden ignoriert.
     *  3. Mehrere gleiche: das stärkste Signal gewinnt (mit 6 dB Trägheit, damit nichts springt).
     */
    private val advertListener = com.amoo.roomlauncher.ble.BleHub.Listener { device, rssi, record ->
        if (rssi < -85) return@Listener
        val data = com.amoo.roomlauncher.ble.BleHub.parse(record).manufacturer[APPLE_ID] ?: return@Listener
        val info = ProximityParser.parse(data) ?: return@Listener
        if (info.model == PodModel.UNKNOWN) return@Listener
        val learned = prefs.airpodsModelId
        if (learned != null && info.rawModelId != learned) {
            ignoredForeign++ // fremde AirPods in der Nähe
            return@Listener
        }

        val now = System.currentTimeMillis()
        val best = synchronized(sightings) {
            sightings[device.address] = Sighting(info, rssi, now)
            sightings.values.removeAll { now - it.at > 30_000 }
            val strongest = sightings.entries.maxByOrNull { it.value.rssi } ?: return@Listener
            val current = bestAddress?.let { sightings[it] }
            val pick = if (current != null && strongest.value.rssi < current.rssi + 6) bestAddress!! else strongest.key
            bestAddress = pick
            sightings[pick]
        } ?: return@Listener
        if (best.info !== info) return@Listener // nur das gewählte Gerät aktualisiert die Anzeige

        // Modell lernen, während die eigenen AirPods verbunden und nah sind
        if (learned == null && _audioConnected.value && rssi > -70) {
            prefs.airpodsModelId = info.rawModelId
        }
        lastBleSeen = now
        lastRssi = rssi
        _podModel.value = info.model
        _model.value = info.model.label
        _inEar.value = InEar(
            left = info.leftInEar,
            right = info.rightInEar,
            leftInCase = info.leftWear == ProximityParser.Wear.IN_CASE,
            rightInCase = info.rightWear == ProximityParser.Wear.IN_CASE,
        )
        if (now - lastAapBattery < 60_000) return@Listener
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

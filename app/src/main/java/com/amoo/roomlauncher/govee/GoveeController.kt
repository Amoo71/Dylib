package com.amoo.roomlauncher.govee

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.Uri
import com.amoo.roomlauncher.Prefs
import com.amoo.roomlauncher.ble.BleHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Steuert Govee-LED-Leisten direkt per Bluetooth LE – nach dem geprüften H617E-Bauplan:
 *
 *  - Verbinden direkt über die gespeicherte MAC; schlägt das fehl oder dauert > 5 s → gezielter Scan
 *    nur nach diesem Gerät. Nach einem Abbruch Queue leeren und nach 2,5 s neu verbinden.
 *  - Gegenprüfung: Schreib- (2b11) UND Notify-Characteristic (2b10) müssen da sein.
 *  - Notify einschalten, dann AA 01 + AA 04 abfragen, alle 2 s AA 01 als Keep-Alive.
 *  - Sende-Queue: immer nur ein Schreibvorgang (Write Without Response), 35 ms Pause für
 *    Power/Helligkeit, 30 ms für Segmente; gleiche Schlüssel werden gebündelt.
 *  - Segmente: Modell mit 15 Farben. Nach jedem (Neu-)Verbinden/Reset den KOMPLETTEN Stand senden
 *    (sonst ignoriert die Leiste Einzelsegmente), danach nur Änderungen – ein Paket pro Farbe,
 *    geplant erst wenn die Queue leer ist, und nur wenn die Leiste bekanntermaßen an ist.
 */
@SuppressLint("MissingPermission")
class GoveeController(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val hub: BleHub,
) {
    enum class Connection { NOT_CONFIGURED, DISCONNECTED, CONNECTING, CONNECTED }

    /** "Alle": Anzahl 0–15 leuchtet in der aktuellen Farbe. "Einzeln": Segmente mit dem Pinsel färben. */
    enum class SegMode { ALL, SINGLE }

    data class FoundDevice(val name: String, val address: String, val rssi: Int, val likelyGovee: Boolean)

    // ---------------------------------------------------------------- Zustand (für die UI)

    private val _connection = MutableStateFlow(if (prefs.goveeAddress == null) Connection.NOT_CONFIGURED else Connection.DISCONNECTED)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _deviceName = MutableStateFlow(prefs.goveeName)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _model = MutableStateFlow(GoveeProtocol.modelOf(prefs.goveeName))
    /** Erkanntes Modell, z. B. "H617E" (aus dem Bluetooth-Namen). */
    val model: StateFlow<String?> = _model.asStateFlow()

    private val _segmented = MutableStateFlow(prefs.goveeColorMode == GoveeProtocol.MODE_SEGMENTS)
    /** true = RGBIC-Leiste, 15 Segmente einzeln steuerbar. */
    val segmented: StateFlow<Boolean> = _segmented.asStateFlow()

    private val _power = MutableStateFlow(prefs.lastPower)
    val power: StateFlow<Boolean> = _power.asStateFlow()

    private val _brightness = MutableStateFlow(prefs.lastBrightness)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    /** Farbe für "Alle" (bzw. die ganze Leiste bei einfachen Modellen). */
    private val _color = MutableStateFlow(prefs.lastColor)
    val color: StateFlow<Int> = _color.asStateFlow()

    private val _brush = MutableStateFlow(prefs.goveeBrush)
    /** Pinselfarbe im Modus "Einzeln". */
    val brush: StateFlow<Int> = _brush.asStateFlow()

    private val _segMode = MutableStateFlow(if (prefs.goveeSegMode == 1) SegMode.SINGLE else SegMode.ALL)
    val segMode: StateFlow<SegMode> = _segMode.asStateFlow()

    private val _count = MutableStateFlow(prefs.goveeCount.coerceIn(0, GoveeProtocol.SEGMENTS))
    /** Anzahl leuchtender Segmente im Modus "Alle". */
    val count: StateFlow<Int> = _count.asStateFlow()

    private val _selected = MutableStateFlow(-1)
    /** Gewähltes Segment im Modus "Einzeln" (-1 = keins) – wird hervorgehoben. */
    val selected: StateFlow<Int> = _selected.asStateFlow()

    fun selectSegment(i: Int) {
        _selected.value = i.coerceIn(-1, GoveeProtocol.SEGMENTS - 1)
    }

    /** Segment-Modell: 15 RGB-Farben (0 = aus). */
    private val segModel = IntArray(GoveeProtocol.SEGMENTS).also { m ->
        val saved = prefs.goveeSegments
        if (saved != null) {
            saved.forEachIndexed { i, c -> m[i] = c and 0xFFFFFF }
        } else {
            for (i in 0 until GoveeProtocol.SEGMENTS) m[i] = if (i < prefs.goveeCount) prefs.lastColor and 0xFFFFFF else 0
        }
    }
    private val _segments = MutableStateFlow(segModel.toList())
    /** Aktuelle Farbe je Segment (RGB, 0 = aus) – für die Anzeige. */
    val segments: StateFlow<List<Int>> = _segments.asStateFlow()

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _sleepAt = MutableStateFlow(prefs.goveeSleepAt.takeIf { it > System.currentTimeMillis() } ?: 0L)
    /** Nacht-Timer: Zeitpunkt (ms), zu dem das Licht ausgeht – 0 = kein Timer. */
    val sleepAt: StateFlow<Long> = _sleepAt.asStateFlow()

    /** Gewählte Timer-Dauer in Minuten (für den Fortschrittsring). */
    val sleepMinutes: Int get() = prefs.goveeSleepMinutes

    // ---------------------------------------------------------------- Intern

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeAck: CompletableDeferred<Unit>? = null

    /** Gesendeter Segment-Stand und ob die Leiste synchron ist (false → kompletten Stand senden). */
    private val sent = IntArray(GoveeProtocol.SEGMENTS) { -1 }
    @Volatile private var synced = false
    @Volatile private var segDirty = true

    /** Power-Zustand der Leiste bekannt (Antwort auf AA 01 oder eigener Befehl)? */
    @Volatile private var powerKnown = false
    @Volatile private var pendingToggle = false
    @Volatile private var lastPowerCmd = 0L
    @Volatile private var lastBrightCmd = 0L
    @Volatile private var lastStatusAt = 0L

    private class Cmd(val bytes: ByteArray, val pauseMs: Long)

    /** Sende-Queue: Schlüssel → Befehl; ein neuer Befehl ersetzt einen wartenden mit gleichem Schlüssel. */
    private val queue = LinkedHashMap<String, Cmd>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var started = false
    private var reconnectJob: Job? = null
    private var connectTimeout: Job? = null
    private var fallbackJob: Job? = null
    private var knownJob: Job? = null
    private var scanStopJob: Job? = null
    private var failures = 0
    private var failedWrites = 0
    private var sleepJob: Job? = null
    @Volatile private var fading = false
    /** Helligkeit vor dem Ausdimmen des Nacht-Timers – wird beim nächsten Einschalten wiederhergestellt. */
    @Volatile private var restoreBrightness: Float? = null

    init {
        // Bereits eingerichtete Leiste: Modell aus dem Namen erkennen (einmalig, danach gilt die Einstellung)
        val m = GoveeProtocol.modelOf(prefs.goveeName)
        if (m != null && !prefs.goveeAutoDetected) {
            applyModelDefaults(m)
            prefs.goveeAutoDetected = true
        }
    }

    private fun applyModelDefaults(m: String?) {
        prefs.goveeColorMode = GoveeProtocol.colorModeFor(m)
        if (GoveeProtocol.brightness100(m)) prefs.goveeBrightness100 = true
        _segmented.value = prefs.goveeColorMode == GoveeProtocol.MODE_SEGMENTS
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { writerLoop() }
        scope.launch {
            while (true) {
                delay(2000)
                if (_connection.value != Connection.CONNECTED) continue
                enqueue("keepalive", GoveeProtocol.KEEP_ALIVE, 35)
                // Selbstheilung: Notify aktiv, aber seit 30 s keine Antwort → Verbindung hängt
                if (lastStatusAt > 0 && System.currentTimeMillis() - lastStatusAt > 30_000) {
                    lastStatusAt = 0
                    _lastError.value = "Leiste antwortet nicht mehr – verbinde neu"
                    reconnectNow()
                }
            }
        }
        armSleep()
        connect()
    }

    // ================================================================ Befehle

    fun setPower(on: Boolean) {
        _power.value = on
        prefs.lastPower = on
        powerKnown = true
        lastPowerCmd = System.currentTimeMillis()
        enqueue("power", GoveeProtocol.power(on), 35)
        if (on) {
            // Nach dem Nacht-Timer: wieder mit der gewohnten Helligkeit starten
            restoreBrightness?.let {
                restoreBrightness = null
                enqueue("bright", GoveeProtocol.brightness(it, prefs.goveeBrightness100), 35)
            }
        }
    }

    fun togglePower() {
        if (_connection.value == Connection.CONNECTED && !powerKnown) {
            pendingToggle = true // erst, wenn der echte Zustand bekannt ist
            return
        }
        setPower(!_power.value)
    }

    /** Halten des Power-Knopfs: Leiste an, alle 15 Segmente schwarz, Anzahl 0. */
    fun reset() {
        setPower(true)
        _count.value = 0
        prefs.goveeCount = 0
        for (i in segModel.indices) segModel[i] = 0
        synced = false
        segmentsChanged()
    }

    fun setBrightness(value: Float) {
        val v = value.coerceIn(0.01f, 1f)
        restoreBrightness = null
        if (fading) setSleepTimer(0) // Helligkeit von Hand geändert → Ausdimmen abbrechen
        _brightness.value = v
        prefs.lastBrightness = v
        lastBrightCmd = System.currentTimeMillis()
        enqueue("bright", GoveeProtocol.brightness(v, prefs.goveeBrightness100), 35)
    }

    /**
     * Farbe wählen. "Alle": färbt alle leuchtenden Segmente. "Einzeln": nur Pinselfarbe.
     * Einfache (nicht segmentierte) Modelle: ganze Leiste.
     */
    fun setColor(argb: Int) {
        if (!_segmented.value) {
            _color.value = argb
            prefs.lastColor = argb
            if (!_power.value) setPower(true)
            enqueue("color", GoveeProtocol.color(argb, prefs.goveeColorMode), 35)
            return
        }
        if (_segMode.value == SegMode.SINGLE) {
            _brush.value = argb
            prefs.goveeBrush = argb
            return
        }
        _color.value = argb
        prefs.lastColor = argb
        if (_count.value == 0) {
            // Farbe wählen bei 0 leuchtenden Segmenten → ganze Leiste
            _count.value = GoveeProtocol.SEGMENTS
            prefs.goveeCount = GoveeProtocol.SEGMENTS
        }
        fillAll()
        if (!_power.value) setPower(true)
    }

    /** Modus "Alle": wie viele Segmente (0–15) leuchten. */
    fun setCount(n: Int) {
        val c = n.coerceIn(0, GoveeProtocol.SEGMENTS)
        if (c == _count.value && _segMode.value == SegMode.ALL) return
        _count.value = c
        prefs.goveeCount = c
        if (_segMode.value == SegMode.ALL) fillAll()
        if (c > 0 && !_power.value) setPower(true)
    }

    fun setSegMode(m: SegMode) {
        if (_segMode.value == m) return
        _segMode.value = m
        prefs.goveeSegMode = if (m == SegMode.SINGLE) 1 else 0
        if (m == SegMode.ALL) fillAll() // zurück zu "Alle": wieder einheitlich
    }

    /** Modus "Einzeln": Segment [i] mit der Pinselfarbe färben. */
    fun paintSegment(i: Int) {
        if (i !in segModel.indices) return
        val c = _brush.value and 0xFFFFFF
        if (segModel[i] == c) return
        segModel[i] = c
        segmentsChanged()
        if (!_power.value) setPower(true)
    }

    /** Modus "Einzeln": Segment [i] ausschalten. */
    fun clearSegment(i: Int) {
        if (i !in segModel.indices || segModel[i] == 0) return
        segModel[i] = 0
        segmentsChanged()
    }

    private fun fillAll() {
        val rgb = _color.value and 0xFFFFFF
        for (i in segModel.indices) segModel[i] = if (i < _count.value) rgb else 0
        segmentsChanged()
    }

    private fun segmentsChanged() {
        _segments.value = segModel.toList()
        prefs.goveeSegments = segModel.toList()
        segDirty = true
        wake.trySend(Unit)
    }

    /** Eingebaute Szene. Danach muss der Segment-Stand beim nächsten Mal komplett gesendet werden. */
    fun setScene(id: Int) {
        if (!_power.value) setPower(true)
        synced = false
        enqueue("mode", GoveeProtocol.scene(id), 35)
    }

    /** Musikmodus (Mikrofon am Controller). */
    fun setMusicMode(sub: Int) {
        if (!_power.value) setPower(true)
        synced = false
        enqueue("mode", GoveeProtocol.music(sub, _color.value), 35)
    }

    /** Farb-Befehl manuell ändern (überschreibt die Modell-Erkennung). */
    fun setColorMode(mode: Int) {
        prefs.goveeColorMode = mode
        _segmented.value = mode == GoveeProtocol.MODE_SEGMENTS
        resendState()
    }

    // ================================================================ Nacht-Timer

    /** Wird am Ende des Nacht-Timers aufgerufen (Bildschirm aus, Apps schließen …). */
    var onSleepEnd: (() -> Unit)? = null

    /**
     * Nacht-Timer: Nach [minutes] Minuten geht das Licht aus (in der letzten Minute dimmt es sanft herunter),
     * danach [onSleepEnd] (Bildschirm aus, Apps schließen). Läuft auch weiter, wenn das Licht vorher von Hand
     * ausgeschaltet wurde. 0 = Timer aus. Übersteht einen Neustart der App (Zeitpunkt wird gespeichert).
     */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        if (fading) {
            // Abgebrochen mitten im Ausdimmen → alte Helligkeit sofort zurück
            fading = false
            restoreBrightness?.let {
                restoreBrightness = null
                _brightness.value = it
                enqueue("bright", GoveeProtocol.brightness(it, prefs.goveeBrightness100), 35)
            }
        }
        if (minutes <= 0) {
            _sleepAt.value = 0L
            prefs.goveeSleepAt = 0L
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        prefs.goveeSleepMinutes = minutes
        prefs.goveeSleepAt = at
        _sleepAt.value = at
        com.amoo.roomlauncher.L.i("Nacht-Timer: $minutes min")
        armSleep()
    }

    private fun armSleep() {
        sleepJob?.cancel()
        val at = _sleepAt.value
        if (at == 0L) return
        sleepJob = scope.launch {
            val fadeMs = 60_000L
            val wait = at - fadeMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (_power.value) {
                // Letzte Minute: in 12 Schritten herunterdimmen
                val from = _brightness.value
                restoreBrightness = from
                fading = true
                val steps = 12
                for (i in 1 until steps) {
                    val left = at - System.currentTimeMillis()
                    if (left <= 0) break
                    val v = (from * (1f - i / steps.toFloat())).coerceAtLeast(0.01f)
                    _brightness.value = v
                    enqueue("bright", GoveeProtocol.brightness(v, prefs.goveeBrightness100), 35)
                    delay(minOf(left, fadeMs / steps))
                }
                val rest = at - System.currentTimeMillis()
                if (rest > 0) delay(rest)
                if (_power.value) setPower(false)
                _brightness.value = from // Anzeige zeigt die gewohnte Helligkeit fürs nächste Einschalten
                fading = false
            } else {
                val rest = at - System.currentTimeMillis()
                if (rest > 0) delay(rest)
            }
            _sleepAt.value = 0L
            prefs.goveeSleepAt = 0L
            sleepJob = null
            com.amoo.roomlauncher.L.i("Nacht-Timer abgelaufen – Licht aus")
            // Nur wenn der Timer wirklich gerade abgelaufen ist (nicht Stunden später nach einem Neustart)
            if (System.currentTimeMillis() - at < 5 * 60_000L) onSleepEnd?.invoke()
        }
    }

    /** Aktuellen Stand erneut senden (z. B. nach Ändern von Farb-Befehl oder Helligkeitsskala). */
    fun resendState() {
        setBrightness(_brightness.value)
        if (_segmented.value) {
            synced = false
            segmentsChanged()
        } else {
            setColor(_color.value)
        }
    }

    /**
     * Externe Befehle (z. B. aus Tasker/Automatisierung):
     * lumen://power/on|off|toggle · lumen://reset · lumen://brightness/50 · lumen://level/8 · lumen://color/RRGGBB
 * · lumen://sleep/30 (Nacht-Timer in Minuten, 0 = aus)
     * Bis die Verbindung steht, bleiben sie in der Warteschlange.
     */
    fun command(uri: Uri): Boolean {
        val arg = uri.pathSegments.firstOrNull()
        when (uri.host?.lowercase()) {
            "power" -> when (arg) {
                "on" -> setPower(true)
                "off" -> setPower(false)
                "toggle" -> togglePower()
                else -> return false
            }
            "reset" -> reset()
            "sleep" -> setSleepTimer(arg?.toIntOrNull() ?: return false)
            "brightness" -> setBrightness((arg?.toIntOrNull() ?: return false).coerceIn(1, 100) / 100f)
            "level" -> {
                val n = arg?.toIntOrNull() ?: return false
                setSegMode(SegMode.ALL)
                setCount(n)
            }
            "color" -> {
                val rgb = arg?.removePrefix("#")?.toIntOrNull(16) ?: return false
                setColor(0xFF000000.toInt() or rgb)
            }
            else -> return false
        }
        if (_connection.value == Connection.DISCONNECTED) connect()
        return true
    }

    // ================================================================ Verbindung

    fun select(device: FoundDevice) {
        stopScan()
        failures = 0
        prefs.goveeAddress = device.address
        prefs.goveeName = device.name
        _deviceName.value = device.name
        val m = GoveeProtocol.modelOf(device.name)
        _model.value = m
        applyModelDefaults(m)
        prefs.goveeAutoDetected = true
        reconnectNow()
    }

    fun connect() {
        val address = prefs.goveeAddress ?: run {
            _connection.value = Connection.NOT_CONFIGURED
            return
        }
        if (_connection.value == Connection.CONNECTED || _connection.value == Connection.CONNECTING) return
        val a = adapter
        if (a == null || !BleHub.bluetoothOn(context)) {
            scheduleReconnect()
            return
        }
        if (_lastError.value == BT_OFF) _lastError.value = null
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _lastError.value = "Ungültige Adresse"
            return
        }
        connectGatt(device)
        // Direktverbindung steht nach 5 s nicht → gezielt nach genau diesem Gerät suchen
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(5_000)
            if (_connection.value == Connection.CONNECTING) {
                closeGatt()
                fallbackScan()
            }
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        com.amoo.roomlauncher.L.i("Govee: verbinde mit ${device.address}")
        _connection.value = Connection.CONNECTING
        _lastError.value = null
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _connection.value = Connection.DISCONNECTED
            _lastError.value = com.amoo.roomlauncher.BtPerms.missingText()
        }
    }

    /** Gefilterter Scan nur auf die eigene Leiste (MAC oder exakter Name), dann sofort verbinden. */
    private val fallbackListener: BleHub.Listener = BleHub.Listener { device, _, record ->
        val addr = prefs.goveeAddress ?: return@Listener
        val name = BleHub.parse(record).name
        if (device.address.equals(addr, true) || (name != null && name == prefs.goveeName)) {
            scope.launch { onFallbackFound(device) }
        }
    }

    private fun onFallbackFound(device: BluetoothDevice) {
        if (_connection.value != Connection.CONNECTING || gatt != null) return
        hub.remove(fallbackListener)
        fallbackJob?.cancel()
        connectGatt(device)
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(8_000)
            if (_connection.value == Connection.CONNECTING) {
                closeGatt()
                _connection.value = Connection.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    private fun fallbackScan() {
        com.amoo.roomlauncher.L.i("Govee: Direktverbindung klappt nicht – suche gezielt nach ${prefs.goveeAddress}")
        _connection.value = Connection.CONNECTING
        hub.add(fallbackListener, fast = true)
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            delay(20_000)
            hub.remove(fallbackListener)
            if (_connection.value == Connection.CONNECTING && gatt == null) {
                _lastError.value = "Leiste nicht gefunden – ist sie an und die Govee-App geschlossen?"
                _connection.value = Connection.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    private fun closeGatt() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        writeChar = null
        writeAck?.complete(Unit)
    }

    /** Abbruch: Queue leeren, Zustand zurücksetzen. */
    private fun onLinkLost() {
        connectTimeout?.cancel()
        knownJob?.cancel()
        synchronized(queue) { queue.clear() }
        synced = false
        segDirty = true
        powerKnown = false
        lastStatusAt = 0
        _connection.value = Connection.DISCONNECTED
    }

    /** Neu verbinden: 2,5 s, bei wiederholten Fehlern länger (max. 30 s). */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        if (prefs.goveeAddress == null) return
        val wait = (2_500L shl failures.coerceAtMost(4)).coerceAtMost(30_000L)
        failures++
        reconnectJob = scope.launch {
            delay(wait)
            if (_connection.value == Connection.DISCONNECTED) connect()
        }
    }

    /**
     * Hört die Tablet-Suche die Leiste? (Signalstärke + Alter.) Funkt sie gar nicht, ist sie aus,
     * außer Reichweite – oder noch mit der Govee-App am Handy verbunden (dann sendet sie nicht).
     */
    fun signalInfo(): String {
        if (_connection.value == Connection.CONNECTED) return "verbunden"
        val s = hub.lastSeen(prefs.goveeAddress) ?: return "kein Signal von der Leiste – aus, zu weit weg oder mit der Govee-App am Handy verbunden?"
        val age = (System.currentTimeMillis() - s.at) / 1000
        return if (age < 30) "Leiste funkt (${s.rssi} dBm, vor $age s)" else "zuletzt gehört vor $age s (${s.rssi} dBm)"
    }

    /** Bluetooth wurde eingeschaltet: veraltete Meldung weg und sofort verbinden. */
    fun onBluetoothOn() {
        if (_lastError.value == BT_OFF) _lastError.value = null
        reconnectNow()
    }

    /** Sofort (neu) verbinden – Knopf "Neu verbinden" oder Selbstheilung. */
    fun reconnectNow() {
        failures = 0
        reconnectJob?.cancel()
        fallbackJob?.cancel()
        hub.remove(fallbackListener)
        closeGatt()
        onLinkLost()
        if (prefs.goveeAddress == null) {
            _connection.value = Connection.NOT_CONFIGURED
            return
        }
        connect()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== g) {
                runCatching { g.close() }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
                return
            }
            val wasConnected = _connection.value == Connection.CONNECTED
            com.amoo.roomlauncher.L.i("Govee: Verbindung beendet (status=$status, newState=$newState, warVerbunden=$wasConnected)")
            runCatching { g.close() }
            gatt = null
            writeChar = null
            writeAck?.complete(Unit)
            scope.launch {
                onLinkLost()
                if (!wasConnected && status != BluetoothGatt.GATT_SUCCESS) {
                    // Direktverbindung gescheitert → gezielt suchen
                    fallbackScan()
                } else {
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                g.disconnect()
                return
            }
            val all = g.services.flatMap { it.characteristics }
            val w = all.firstOrNull { it.uuid == WRITE_UUID }
            val n = all.firstOrNull { it.uuid == NOTIFY_UUID }
            // Gegenprüfung: beide Characteristics müssen da sein – sonst ist es nicht die Leiste
            if (w == null || n == null) {
                com.amoo.roomlauncher.L.w("Govee: Characteristics fehlen (2b11=${w != null}, 2b10=${n != null})")
                _lastError.value = "Gerät hat keinen Govee-Steuerkanal – falsches Gerät?"
                g.disconnect()
                return
            }
            writeChar = w
            g.setCharacteristicNotification(n, true)
            val d = n.getDescriptor(CCCD_UUID)
            var started = false
            if (d != null) {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                started = runCatching { g.writeDescriptor(d) }.getOrDefault(false)
            }
            scope.launch {
                // auf onDescriptorWrite warten; zur Sicherheit nach 1 s trotzdem weiter
                if (started) delay(1_000)
                if (_connection.value == Connection.CONNECTING && gatt === g) onReady()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD_UUID) scope.launch { if (_connection.value == Connection.CONNECTING && gatt === g) onReady() }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { handleStatus(it) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(Unit)
        }
    }

    /** Verbindung steht: Zustand abfragen, Keep-Alive läuft, kompletter Segment-Stand folgt. */
    private fun onReady() {
        com.amoo.roomlauncher.L.i("Govee: bereit (Notify an, Modell=${_model.value}, Segmente=${_segmented.value})")
        connectTimeout?.cancel()
        failures = 0
        failedWrites = 0
        synced = false
        segDirty = true
        powerKnown = false
        _connection.value = Connection.CONNECTED
        _lastError.value = null
        enqueue("keepalive", GoveeProtocol.KEEP_ALIVE, 35)
        enqueue("q_bright", GoveeProtocol.QUERY_BRIGHTNESS, 35)
        // Keine Antwort innerhalb 2 s → angezeigter Power-Zustand gilt
        knownJob?.cancel()
        knownJob = scope.launch {
            delay(2_000)
            if (!powerKnown && _connection.value == Connection.CONNECTED) {
                powerKnown = true
                onPowerKnown()
            }
        }
    }

    private fun onPowerKnown() {
        if (pendingToggle) {
            pendingToggle = false
            setPower(!_power.value)
        }
        wake.trySend(Unit)
    }

    private fun handleStatus(v: ByteArray) {
        lastStatusAt = System.currentTimeMillis()
        val now = lastStatusAt
        when (val st = GoveeProtocol.parseStatus(v)) {
            is GoveeProtocol.Status.Power -> {
                if (!powerKnown) com.amoo.roomlauncher.L.i("Govee: Power-Status ${if (st.on) "an" else "aus"}")
                // Antwort kurz nach eigenem Power-Befehl ignorieren – sonst springt der Schalter zurück
                if (now - lastPowerCmd > 1_000) {
                    _power.value = st.on
                    prefs.lastPower = st.on
                }
                if (!powerKnown) {
                    powerKnown = true
                    scope.launch { onPowerKnown() }
                } else if (st.on) {
                    wake.trySend(Unit)
                }
            }
            is GoveeProtocol.Status.Brightness -> if (now - lastBrightCmd > 1_000) {
                val f = if (prefs.goveeBrightness100) st.raw / 100f else st.raw / 254f
                _brightness.value = f.coerceIn(0.01f, 1f)
            }
            is GoveeProtocol.Status.Color -> if (!_segmented.value) _color.value = st.argb
            null -> Unit
        }
    }

    // ================================================================ Sende-Queue

    private fun enqueue(key: String, bytes: ByteArray, pauseMs: Long) {
        synchronized(queue) {
            queue.remove(key) // neuer Wert ersetzt den alten
            queue[key] = Cmd(bytes, pauseMs)
        }
        when (_connection.value) {
            Connection.CONNECTED -> wake.trySend(Unit)
            Connection.DISCONNECTED -> connect()
            else -> Unit
        }
    }

    private fun ready() = gatt != null && writeChar != null && _connection.value == Connection.CONNECTED

    private suspend fun writerLoop() {
        while (true) {
            wake.receive()
            while (ready()) {
                val next = synchronized(queue) {
                    val k = queue.keys.firstOrNull()
                    if (k == null) null else queue.remove(k)
                }
                if (next != null) {
                    write(next.bytes)
                    delay(next.pauseMs)
                    continue
                }
                // Queue leer → jetzt (und erst jetzt) den neuesten Segment-Stand planen
                if (_segmented.value && segDirty && powerKnown && _power.value) {
                    segDirty = false
                    val plan = GoveeProtocol.planSegments(segModel.copyOf(), sent, synced)
                    var ok = true
                    for ((rgb, mask) in plan) {
                        if (!ready() || !write(GoveeProtocol.segment(rgb, mask))) {
                            ok = false
                            break
                        }
                        for (i in 0 until GoveeProtocol.SEGMENTS) if (mask and (1 shl i) != 0) sent[i] = rgb
                        delay(30)
                    }
                    if (ok) synced = true else segDirty = true
                    continue
                }
                break
            }
        }
    }

    /** Ein Schreibvorgang (Write Without Response); wartet kurz auf die Bestätigung des Stacks. */
    @Suppress("DEPRECATION")
    private suspend fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        val ack = CompletableDeferred<Unit>()
        writeAck = ack
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = bytes
        var ok = runCatching { g.writeCharacteristic(ch) }.getOrDefault(false)
        if (!ok) {
            delay(50) // Stack beschäftigt – einmal kurz warten
            ch.value = bytes
            ok = runCatching { g.writeCharacteristic(ch) }.getOrDefault(false)
        }
        if (!ok) {
            com.amoo.roomlauncher.L.w("Govee: Schreiben fehlgeschlagen (${bytes.take(3).joinToString(" ") { "%02x".format(it) }} …)")
            if (++failedWrites >= 6) {
                failedWrites = 0
                _lastError.value = "Senden klappt nicht – verbinde neu"
                scope.launch { reconnectNow() }
            }
            return false
        }
        failedWrites = 0
        withTimeoutOrNull(300) { ack.await() }
        return true
    }

    // ================================================================ Suche (Einrichtung)

    private val scanListener = BleHub.Listener { device, rssi, record ->
        val adv = BleHub.parse(record)
        val own = device.address.equals(prefs.goveeAddress, true)
        val name = adv.name ?: runCatching { device.name }.getOrNull() ?: (if (own) prefs.goveeName ?: device.address else null)
        if (name.isNullOrBlank()) return@Listener
        val f = FoundDevice(name, device.address, rssi, isLikelyGovee(name))
        _found.update { list ->
            (list.filter { it.address != f.address } + f)
                .sortedWith(compareByDescending<FoundDevice> { it.likelyGovee }.thenByDescending { it.rssi })
        }
    }

    fun startScan() {
        if (!BleHub.bluetoothOn(context)) {
            _lastError.value = BT_OFF
            return
        }
        if (_lastError.value == BT_OFF) _lastError.value = null
        stopScan()
        _found.value = emptyList()
        _scanning.value = true
        hub.add(scanListener, fast = true)
        scanStopJob = scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanStopJob?.cancel()
        hub.remove(scanListener)
        _scanning.value = false
    }

    /** MAC-Adresse von Hand eintragen (ohne Suche). Gibt false zurück, wenn die Adresse ungültig ist. */
    fun setManual(mac: String, name: String?): Boolean {
        val addr = mac.trim().uppercase().replace('-', ':')
        if (!BluetoothAdapter.checkBluetoothAddress(addr)) return false
        select(FoundDevice(name?.takeIf { it.isNotBlank() } ?: (hub.lastSeen(addr)?.name ?: "Govee-Licht"), addr, 0, true))
        return true
    }

    companion object {
        /** Die Leiste aus dem Bauplan – wird beim ersten Start eingetragen, damit sofort direkt verbunden wird. */
        const val DEFAULT_MAC = "E8:55:04:06:77:4E"
        const val DEFAULT_NAME = "Govee_H617E_774E"

        private const val BT_OFF = "Bluetooth ist aus"

        val SERVICE_UUID: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1910")
        val WRITE_UUID: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b11")
        val NOTIFY_UUID: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b10")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Szenen-IDs (H6127/H61xx laut Reverse Engineering). */
        val SCENES = listOf(
            0x00 to "Sonnenaufgang", 0x01 to "Sonnenuntergang", 0x04 to "Film", 0x05 to "Date",
            0x07 to "Romantisch", 0x08 to "Blinken", 0x09 to "Kerzenlicht", 0x0F to "Schneeflocke",
        )

        val MUSIC_MODES = listOf(0x00 to "Energisch", 0x01 to "Spektrum", 0x02 to "Rollen", 0x03 to "Rhythmus")

        fun isLikelyGovee(name: String): Boolean {
            val n = name.lowercase()
            return n.contains("govee") || n.startsWith("ihoment") || n.startsWith("gbk_") ||
                n.startsWith("minger") || Regex("^h[5-7][0-9]{3}").containsMatchIn(n)
        }
    }
}

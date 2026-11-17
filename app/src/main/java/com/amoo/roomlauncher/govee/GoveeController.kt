package com.amoo.roomlauncher.govee

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.amoo.roomlauncher.Prefs
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
import kotlin.math.roundToInt

/**
 * Steuert Govee-LED-Streifen/Lampen direkt über Bluetooth Low Energy.
 *
 * Protokoll (reverse engineered, u.a. govee_btled / Home-Assistant-Community):
 *  - Service 00010203-0405-0607-0809-0a0b0c0d1910, Schreib-Charakteristik ...2b11
 *  - 20-Byte-Pakete, letztes Byte = XOR über die ersten 19 Bytes
 *  - 33 01 xx       Power (01 an / 00 aus)
 *  - 33 04 xx       Helligkeit
 *  - 33 05 MM r g b Farbe (MM = Modus, modellabhängig 02 / 0D / 0B / 15)
 *  - AA 01          Keep-Alive, alle ~2 s
 */
@SuppressLint("MissingPermission")
class GoveeController(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val hub: com.amoo.roomlauncher.ble.BleHub,
) {
    enum class Connection { NOT_CONFIGURED, DISCONNECTED, CONNECTING, CONNECTED }

    data class FoundDevice(val name: String, val address: String, val rssi: Int, val likelyGovee: Boolean)

    private val _connection = MutableStateFlow(
        if (prefs.goveeAddress == null) Connection.NOT_CONFIGURED else Connection.DISCONNECTED,
    )
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _deviceName = MutableStateFlow(prefs.goveeName)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _power = MutableStateFlow(prefs.lastPower)
    val power: StateFlow<Boolean> = _power.asStateFlow()

    private val _brightness = MutableStateFlow(prefs.lastBrightness)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _color = MutableStateFlow(prefs.lastColor)
    val color: StateFlow<Int> = _color.asStateFlow()

    private val _model = MutableStateFlow(GoveeProtocol.modelOf(prefs.goveeName))
    /** Erkanntes Modell, z. B. "H6199" (aus dem Bluetooth-Namen). */
    val model: StateFlow<String?> = _model.asStateFlow()

    private val _segmented = MutableStateFlow(prefs.goveeColorMode == GoveeProtocol.MODE_SEGMENTS)
    /** true = RGBIC-Streifen, einzelne Segmente steuerbar. */
    val segmented: StateFlow<Boolean> = _segmented.asStateFlow()

    private val _segments = MutableStateFlow(prefs.goveeSegments ?: List(GoveeProtocol.SEGMENTS) { prefs.lastColor })
    /** Zuletzt gesetzte Farbe je Segment (für die Anzeige im Widget). */
    val segments: StateFlow<List<Int>> = _segments.asStateFlow()

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeAck: CompletableDeferred<Unit>? = null

    /** Ausstehende Befehle je Typ – neuere Werte ersetzen ältere (Slider-Drosselung). */
    private val pending = LinkedHashMap<String, ByteArray>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var started = false
    private var reconnectJob: Job? = null
    private var connectTimeout: Job? = null
    private var failures = 0
    private var silentWrites = 0
    private var scanStopJob: Job? = null

    init {
        // Schon eingerichtetes Licht: Modell aus dem Namen erkennen, falls noch der Standard-Befehl aktiv ist
        val model = GoveeProtocol.modelOf(prefs.goveeName)
        if (prefs.goveeColorMode == GoveeProtocol.MODE_CLASSIC && GoveeProtocol.isSegmented(model)) {
            prefs.goveeColorMode = GoveeProtocol.MODE_SEGMENTS
            _segmented.value = true
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { writerLoop() }
        scope.launch {
            while (true) {
                delay(2000)
                if (_connection.value == Connection.CONNECTED) enqueue("keepalive", GoveeProtocol.KEEP_ALIVE)
            }
        }
        connect()
    }

    // ---------------------------------------------------------------- Befehle

    fun setPower(on: Boolean) {
        _power.value = on
        prefs.lastPower = on
        enqueue("power", GoveeProtocol.power(on))
    }

    fun setBrightness(value: Float) {
        val v = value.coerceIn(0.01f, 1f)
        _brightness.value = v
        prefs.lastBrightness = v
        enqueue("brightness", GoveeProtocol.brightness(v, prefs.goveeBrightness100))
    }

    /** Ganze Leiste in einer Farbe. */
    fun setColor(argb: Int) {
        _color.value = argb
        prefs.lastColor = argb
        if (!_power.value) setPower(true)
        if (prefs.goveeColorMode == GoveeProtocol.MODE_SEGMENTS) {
            setSegmentsState(List(GoveeProtocol.SEGMENTS) { argb })
        }
        enqueue("color", GoveeProtocol.color(argb, prefs.goveeColorMode))
    }

    /** Nur die Segmente in [mask] (Bit 0 = Segment 1) färben – RGBIC-Streifen. */
    fun setSegmentColor(mask: Int, argb: Int) {
        if (mask == 0) return
        if (mask == GoveeProtocol.ALL_SEGMENTS) return setColor(argb)
        if (!_power.value) setPower(true)
        _color.value = argb
        prefs.lastColor = argb
        setSegmentsState(_segments.value.mapIndexed { i, c -> if (mask and (1 shl i) != 0) argb else c })
        enqueue("seg$mask", GoveeProtocol.color(argb, GoveeProtocol.MODE_SEGMENTS, mask))
    }

    private fun setSegmentsState(list: List<Int>) {
        _segments.value = list
        prefs.goveeSegments = list
    }

    /** Eingebaute Szenen: 33 05 04 <id>. */
    fun setScene(id: Int) {
        if (!_power.value) setPower(true)
        enqueue("color", GoveeProtocol.scene(id))
    }

    /** Musikmodus (Mikrofon im Controller). */
    fun setMusicMode(sub: Int) {
        if (!_power.value) setPower(true)
        enqueue("color", GoveeProtocol.music(sub, _color.value))
    }

    /** Farb-Befehl manuell ändern (überschreibt die automatische Modell-Erkennung). */
    fun setColorMode(mode: Int) {
        prefs.goveeColorMode = mode
        _segmented.value = mode == GoveeProtocol.MODE_SEGMENTS
        resendState()
    }

    /** Sendet die aktuellen Werte erneut (z.B. nach Ändern des Farbmodus). */
    fun resendState() {
        setBrightness(_brightness.value)
        setColor(_color.value)
    }

    // ---------------------------------------------------------------- Verbindung

    fun select(device: FoundDevice) {
        stopScan()
        failures = 0
        prefs.goveeAddress = device.address
        prefs.goveeName = device.name
        // Modell erkennen → passenden Farb-Befehl automatisch setzen (RGBIC-Streifen: Segmente)
        val model = GoveeProtocol.modelOf(device.name)
        _model.value = model
        prefs.goveeColorMode = GoveeProtocol.colorModeFor(model)
        _segmented.value = prefs.goveeColorMode == GoveeProtocol.MODE_SEGMENTS
        _deviceName.value = device.name
        disconnectInternal()
        _connection.value = Connection.DISCONNECTED
        connect()
    }

    fun connect() {
        val address = prefs.goveeAddress ?: run {
            _connection.value = Connection.NOT_CONFIGURED
            return
        }
        if (_connection.value == Connection.CONNECTED || _connection.value == Connection.CONNECTING) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            scheduleReconnect()
            return
        }
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _lastError.value = "Ungültige Adresse"
            return
        }
        _connection.value = Connection.CONNECTING
        _lastError.value = null
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _connection.value = Connection.DISCONNECTED
            _lastError.value = "Bluetooth-Berechtigung fehlt"
            return
        }
        // Manche Stacks melden sich bei einem ausgeschalteten Licht nie zurück.
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(15_000)
            if (_connection.value == Connection.CONNECTING) {
                _lastError.value = "Licht antwortet nicht – ist es eingeschaltet und in Reichweite?"
                disconnectInternal()
                _connection.value = Connection.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    private fun disconnectInternal() {
        reconnectJob?.cancel()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        writeChar = null
        writeAck?.complete(Unit)
    }

    /** Wiederverbinden mit wachsender Pause (4 s … 60 s), damit ein ausgeschaltetes Licht nicht stresst. */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        if (prefs.goveeAddress == null) return
        val wait = (4000L shl failures.coerceAtMost(4)).coerceAtMost(60_000L)
        failures++
        reconnectJob = scope.launch {
            delay(wait)
            if (_connection.value == Connection.DISCONNECTED) connect()
        }
    }

    /** Sofort (neu) verbinden – z. B. Knopf "Neu verbinden" oder Selbstheilung. */
    fun reconnectNow() {
        failures = 0
        reconnectJob?.cancel()
        connectTimeout?.cancel()
        disconnectInternal()
        if (prefs.goveeAddress == null) {
            _connection.value = Connection.NOT_CONFIGURED
            return
        }
        _connection.value = Connection.DISCONNECTED
        connect()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== g) {
                // Callback einer alten, bereits verworfenen Verbindung
                runCatching { g.close() }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connectTimeout?.cancel()
                runCatching { g.close() }
                if (gatt === g) {
                    gatt = null
                    writeChar = null
                }
                writeAck?.complete(Unit)
                _connection.value = Connection.DISCONNECTED
                scope.launch { scheduleReconnect() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                g.disconnect()
                return
            }
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(WRITE_UUID)
                ?: g.services.flatMap { it.characteristics }.firstOrNull { it.uuid == WRITE_UUID }
            if (ch == null) {
                _lastError.value = "Kein Govee-Steuerkanal gefunden – falsches Gerät?"
                g.disconnect()
                return
            }
            writeChar = ch
            // Status-Meldungen abonnieren (Antworten auf aa 01 / aa 04 / aa 05 01).
            g.getService(SERVICE_UUID)?.getCharacteristic(NOTIFY_UUID)?.let { n ->
                runCatching {
                    g.setCharacteristicNotification(n, true)
                    n.getDescriptor(CCCD_UUID)?.let { d ->
                        @Suppress("DEPRECATION")
                        d.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                }
            }
            connectTimeout?.cancel()
            failures = 0
            _connection.value = Connection.CONNECTED
            scope.launch {
                delay(400)
                enqueue("q_power", GoveeProtocol.packet(0xAA, 0x01))
                enqueue("q_bright", GoveeProtocol.packet(0xAA, 0x04))
                enqueue("q_color", GoveeProtocol.packet(0xAA, 0x05, 0x01))
            }
            wake.trySend(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { handleStatus(it) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            writeAck?.complete(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(Unit)
        }
    }

    /** Zeitpunkt der letzten eigenen Änderung – kurz danach keine (veralteten) Statusmeldungen übernehmen. */
    @Volatile private var lastLocalChange = 0L

    private fun handleStatus(v: ByteArray) {
        if (System.currentTimeMillis() - lastLocalChange < 2000) return
        when (val st = GoveeProtocol.parseStatus(v)) {
            is GoveeProtocol.Status.Power -> _power.value = st.on
            is GoveeProtocol.Status.Brightness -> {
                val f = if (prefs.goveeBrightness100) st.raw / 100f else st.raw / 254f
                _brightness.value = f.coerceIn(0.01f, 1f)
            }
            is GoveeProtocol.Status.Color -> _color.value = st.argb
            null -> Unit
        }
    }

    private fun enqueue(key: String, bytes: ByteArray) {
        if (!key.startsWith("q_") && key != "keepalive") lastLocalChange = System.currentTimeMillis()
        synchronized(pending) { pending[key] = bytes }
        when (_connection.value) {
            Connection.CONNECTED -> wake.trySend(Unit)
            Connection.DISCONNECTED -> connect()
            else -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writerLoop() {
        while (true) {
            wake.receive()
            while (true) {
                val g = gatt
                val ch = writeChar
                if (g == null || ch == null || _connection.value != Connection.CONNECTED) break
                val (key, item) = synchronized(pending) {
                    val k = pending.keys.firstOrNull()
                    if (k == null) null else k to pending.remove(k)!!
                } ?: break
                val ack = CompletableDeferred<Unit>()
                writeAck = ack
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = item
                val ok = runCatching { g.writeCharacteristic(ch) }.getOrDefault(false)
                if (ok) {
                    val acked = withTimeoutOrNull(800) { ack.await() } != null
                    // Mehrere Schreibvorgänge ohne Antwort = Verbindung hängt → neu verbinden
                    if (acked) {
                        silentWrites = 0
                    } else if (++silentWrites >= 4) {
                        silentWrites = 0
                        _lastError.value = "Licht antwortet nicht mehr – verbinde neu"
                        reconnectNow()
                        break
                    }
                    delay(20)
                } else {
                    // Stack beschäftigt (z. B. Descriptor-Write): später erneut, außer ein neuerer Wert kam schon
                    synchronized(pending) { if (key != "keepalive" && !pending.containsKey(key)) pending[key] = item }
                    delay(120)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Suche

    private val scanListener = com.amoo.roomlauncher.ble.BleHub.Listener { device, rssi, record ->
        val adv = com.amoo.roomlauncher.ble.BleHub.parse(record)
        val name = adv.name ?: runCatching { device.name }.getOrNull()
        if (name.isNullOrBlank()) return@Listener
        val f = FoundDevice(name, device.address, rssi, isLikelyGovee(name))
        _found.update { list ->
            (list.filter { it.address != f.address } + f)
                .sortedWith(compareByDescending<FoundDevice> { it.likelyGovee }.thenByDescending { it.rssi })
        }
    }

    fun startScan() {
        if (adapter?.isEnabled != true) {
            _lastError.value = "Bluetooth ist aus"
            return
        }
        stopScan()
        _found.value = emptyList()
        _scanning.value = true
        hub.add(scanListener, fast = true)
        scanStopJob = scope.launch {
            delay(15000)
            stopScan()
        }
    }

    fun stopScan() {
        scanStopJob?.cancel()
        hub.remove(scanListener)
        _scanning.value = false
    }

    companion object {
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

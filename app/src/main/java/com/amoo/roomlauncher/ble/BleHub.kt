package com.amoo.roomlauncher.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ein gemeinsamer BLE-Scan für alle Nutzer (AirPods-Akku dauerhaft, Govee-Suche zeitweise).
 *
 * Android 7 hat zwei Fallstricke, die hier abgefangen werden:
 *  - Ein Scan, der länger als 30 Minuten läuft, wird stillschweigend gedrosselt ("opportunistic").
 *    Deshalb wird er alle 20 Minuten neu gestartet.
 *  - Mehr als 5 Scan-Starts in 30 Sekunden sperren die App. Neustarts werden deshalb gebündelt.
 */
@SuppressLint("MissingPermission")
class BleHub(private val context: Context) {
    fun interface Listener {
        fun onAdvert(device: BluetoothDevice, rssi: Int, record: ByteArray?)
    }

    private class Entry(val listener: Listener, val fast: Boolean)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null
    private var runningFast = false
    private val starts = ArrayDeque<Long>()

    // ---- Selbstüberwachung
    @Volatile var lastResultAt = 0L
        private set
    @Volatile var scanning = false
        private set
    @Volatile var lastError: String? = null
        private set
    private var failures = 0
    private var lastBtReset = 0L

    /** Prüft jede Minute, ob der Scan noch lebt – Android 7 lässt ihn manchmal stillschweigend sterben. */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (callback != null && lastResultAt > 0 && now - lastResultAt > 3 * 60_000) {
                lastError = "Keine Signale seit 3 min – Scan neu gestartet"
                stopInternal()
                sync()
            } else if (callback == null && entries.isNotEmpty()) {
                sync()
            }
            handler.postDelayed(this, 60_000)
        }
    }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val periodicRestart = object : Runnable {
        override fun run() {
            if (callback != null) restart()
            handler.postDelayed(this, RESTART_EVERY_MS)
        }
    }

    init {
        handler.postDelayed(periodicRestart, RESTART_EVERY_MS)
        handler.postDelayed(watchdog, 60_000)
    }

    /** [fast] = schneller Suchmodus (z. B. beim Einrichten), sonst stromsparend. */
    fun add(l: Listener, fast: Boolean = false) {
        if (entries.none { it.listener === l }) entries.add(Entry(l, fast))
        scheduleSync()
    }

    fun remove(l: Listener) {
        entries.removeAll { it.listener === l }
        scheduleSync()
    }

    /** Mehrere Änderungen kurz hintereinander (z. B. "Erneut suchen") → nur ein Abgleich, nur ein Neustart. */
    private val syncRunnable = Runnable { sync() }

    private fun scheduleSync(delayMs: Long = 300) {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, delayMs)
    }

    /** Schneller Modus bleibt nach dem letzten schnellen Nutzer noch kurz an – verhindert Hin-und-Her-Neustarts. */
    private var fastUntil = 0L

    /** Zuletzt gesehene Geräte (Adresse → Signal), z. B. um zu zeigen, ob die Leiste überhaupt funkt. */
    class Seen(val rssi: Int, val at: Long, val name: String?)

    private val seen = java.util.concurrent.ConcurrentHashMap<String, Seen>()

    fun lastSeen(address: String?): Seen? = address?.let { seen[it.uppercase()] }

    fun restart() {
        handler.post {
            stopInternal()
            sync()
        }
    }

    fun stop() = handler.post { stopInternal() }

    /** Startet, stoppt oder wechselt den Scan-Modus passend zu den aktuellen Nutzern. */
    private fun sync() {
        if (entries.isEmpty()) {
            stopInternal()
            return
        }
        val now = System.currentTimeMillis()
        var wantFast = entries.any { it.fast }
        if (wantFast) {
            fastUntil = now + 5_000
        } else if (runningFast && now < fastUntil) {
            // noch kurz schnell weiterscannen, statt sofort neu zu starten
            wantFast = true
            scheduleSync(fastUntil - now + 50)
        }
        if (callback != null && wantFast == runningFast) return
        val scanner = adapter?.takeIf { runCatching { it.isEnabled }.getOrDefault(false) }?.bluetoothLeScanner ?: run {
            stopInternal()
            return
        }

        // Schutz vor Androids "zu viele Scans"-Sperre (5 Starts / 30 s): lieber den laufenden Scan behalten
        // (auch im "falschen" Modus) und später umschalten, als gar nicht zu scannen.
        while (starts.isNotEmpty() && now - starts.first() > 30_000) starts.removeFirst()
        if (starts.size >= 4) {
            com.amoo.roomlauncher.L.i("BLE-Scan: Neustart-Sperre aktiv, laufender Scan bleibt")
            scheduleSync(30_000 - (now - starts.first()) + 500)
            return
        }
        stopInternal()
        starts.addLast(now)

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = dispatch(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { dispatch(it) }
            override fun onScanFailed(errorCode: Int) {
                handler.post { onFailed(errorCode) }
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(if (wantFast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            scanner.startScan(null, settings, cb)
            callback = cb
            runningFast = wantFast
            scanning = true
            com.amoo.roomlauncher.L.i("BLE-Scan gestartet (${if (wantFast) "schnell" else "sparsam"}, ${entries.size} Nutzer)")
            if (lastResultAt == 0L) lastResultAt = System.currentTimeMillis()
        } catch (e: Throwable) {
            com.amoo.roomlauncher.L.w("BLE-Scan-Start fehlgeschlagen", e)
            lastError = "Scan-Start fehlgeschlagen: ${e.javaClass.simpleName}"
        }
    }

    /**
     * Android 7 meldet nach langer Laufzeit gern "APPLICATION_REGISTRATION_FAILED" (2) – dann hilft nur
     * ein Bluetooth-Neustart. Den macht die App selbst (höchstens alle 30 Minuten).
     */
    private fun onFailed(code: Int) {
        com.amoo.roomlauncher.L.w("BLE-Scan-Fehler $code")
        callback = null
        scanning = false
        failures++
        lastError = "Scan-Fehler $code"
        if (failures >= 3 && System.currentTimeMillis() - lastBtReset > 30 * 60_000L) {
            failures = 0
            resetBluetooth()
        } else {
            handler.postDelayed({ sync() }, 5_000L * failures)
        }
    }

    /** Bluetooth aus- und wieder einschalten (Selbstreparatur, auch manuell aus den Einstellungen). */
    @Suppress("DEPRECATION")
    fun resetBluetooth(): Boolean {
        val a = adapter ?: return false
        if (Build.VERSION.SDK_INT >= 33) return false // ab Android 13 nicht mehr erlaubt
        lastBtReset = System.currentTimeMillis()
        lastError = "Bluetooth wird neu gestartet…"
        stopInternal()
        return runCatching {
            a.disable()
            handler.postDelayed({ runCatching { a.enable() } }, 3_000)
            true
        }.getOrDefault(false)
    }

    private fun dispatch(r: ScanResult) {
        val now = System.currentTimeMillis()
        lastResultAt = now
        failures = 0
        val rec = r.scanRecord?.bytes
        val addr = r.device.address.uppercase()
        val name = r.scanRecord?.deviceName ?: seen[addr]?.name
        seen[addr] = Seen(r.rssi, now, name)
        if (seen.size > 400) seen.entries.removeAll { now - it.value.at > 60_000 }
        for (e in entries) e.listener.onAdvert(r.device, r.rssi, rec)
    }

    private fun stopInternal() {
        val cb = callback ?: return
        com.amoo.roomlauncher.L.i("BLE-Scan gestoppt")
        callback = null
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    companion object {
        private const val RESTART_EVERY_MS = 20 * 60 * 1000L

        /**
         * Ohne eingeschalteten Standort liefert Android 6–9 keine BLE-Suchergebnisse.
         * Achtung: Manche ROMs (z. B. LineageOS 14 auf dem Galaxy Tab E) haben gar kein "location_mode" –
         * dann ergäbe getInt() den Standardwert "aus", obwohl GPS/Netzwerk an sind. Deshalb zuerst den
         * LocationManager fragen und die Einstellungen nur als Rückfall nutzen.
         */
        fun locationEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (Build.VERSION.SDK_INT >= 28 && lm != null) return lm.isLocationEnabled
            val providers = lm?.let {
                runCatching {
                    it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }.getOrNull()
            }
            if (providers == true) return true
            val cr = context.contentResolver
            @Suppress("DEPRECATION")
            Settings.Secure.getString(cr, Settings.Secure.LOCATION_MODE)?.toIntOrNull()?.let {
                @Suppress("DEPRECATION")
                return it != Settings.Secure.LOCATION_MODE_OFF
            }
            @Suppress("DEPRECATION")
            Settings.Secure.getString(cr, Settings.Secure.LOCATION_PROVIDERS_ALLOWED)?.let { return it.isNotBlank() }
            return providers ?: true // unbekannt → lieber nicht fälschlich warnen
        }

        /** Bluetooth wirklich an? (Adapter-Zustand zusätzlich zu isEnabled.) */
        fun bluetoothOn(context: Context): Boolean {
            val a = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
            return runCatching { a.isEnabled || a.state == BluetoothAdapter.STATE_ON }.getOrDefault(false)
        }

        /** Werbedaten zerlegen: Gerätename und Herstellerdaten (Firmen-ID -> Bytes). */
        fun parse(record: ByteArray?): Advert {
            var name: String? = null
            var manu: HashMap<Int, ByteArray>? = null
            if (record != null) {
                var i = 0
                while (i < record.size) {
                    val len = record[i].toInt() and 0xFF
                    if (len == 0 || i + len >= record.size) break
                    val type = record[i + 1].toInt() and 0xFF
                    when (type) {
                        0x08, 0x09 -> name = String(record, i + 2, len - 1, Charsets.UTF_8).trim { it <= ' ' }
                        0xFF -> if (len >= 3) {
                            val id = (record[i + 2].toInt() and 0xFF) or ((record[i + 3].toInt() and 0xFF) shl 8)
                            if (manu == null) manu = HashMap(2)
                            manu[id] = record.copyOfRange(i + 4, i + 1 + len)
                        }
                    }
                    i += len + 1
                }
            }
            return Advert(name, manu ?: emptyMap())
        }
    }

    class Advert(val name: String?, val manufacturer: Map<Int, ByteArray>)
}

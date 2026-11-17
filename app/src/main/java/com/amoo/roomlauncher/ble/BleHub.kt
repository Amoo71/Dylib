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
    }

    /** [fast] = schneller Suchmodus (z. B. beim Einrichten), sonst stromsparend. */
    fun add(l: Listener, fast: Boolean = false) {
        if (entries.none { it.listener === l }) entries.add(Entry(l, fast))
        handler.post { sync() }
    }

    fun remove(l: Listener) {
        entries.removeAll { it.listener === l }
        handler.post { sync() }
    }

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
        val wantFast = entries.any { it.fast }
        if (callback != null && wantFast == runningFast) return
        stopInternal()
        val scanner = adapter?.takeIf { runCatching { it.isEnabled }.getOrDefault(false) }?.bluetoothLeScanner ?: return

        // Schutz vor Androids "zu viele Scans"-Sperre
        val now = System.currentTimeMillis()
        while (starts.isNotEmpty() && now - starts.first() > 30_000) starts.removeFirst()
        if (starts.size >= 4) {
            handler.postDelayed({ sync() }, 30_000 - (now - starts.first()) + 500)
            return
        }
        starts.addLast(now)

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = dispatch(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { dispatch(it) }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(if (wantFast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            scanner.startScan(null, settings, cb)
            callback = cb
            runningFast = wantFast
        } catch (_: Throwable) {
        }
    }

    private fun dispatch(r: ScanResult) {
        val rec = r.scanRecord?.bytes
        for (e in entries) e.listener.onAdvert(r.device, r.rssi, rec)
    }

    private fun stopInternal() {
        val cb = callback ?: return
        callback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    companion object {
        private const val RESTART_EVERY_MS = 20 * 60 * 1000L

        /** Ohne eingeschalteten Standort liefert Android 6–9 keine BLE-Suchergebnisse. */
        fun locationEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 28) {
                return (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled ?: true
            }
            @Suppress("DEPRECATION")
            val mode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
            @Suppress("DEPRECATION")
            return mode != Settings.Secure.LOCATION_MODE_OFF
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

package com.amoo.roomlauncher.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

/** Ein gemeinsamer BLE-Scan für alle Nutzer – funktioniert ab Android 4.3 (startLeScan) und neuer. */
@SuppressLint("MissingPermission")
class BleHub(private val context: Context) {
    fun interface Listener {
        fun onAdvert(device: BluetoothDevice, rssi: Int, record: ByteArray?)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var stopper: (() -> Unit)? = null

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun add(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        ensureRunning()
    }

    fun remove(l: Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) stop()
    }

    /** Nach dem Einschalten von Bluetooth neu starten. */
    fun restart() {
        stop()
        ensureRunning()
    }

    @Synchronized
    fun ensureRunning() {
        if (stopper != null || listeners.isEmpty()) return
        val a = adapter?.takeIf { runCatching { it.isEnabled }.getOrDefault(false) } ?: return
        val dispatch = { d: BluetoothDevice, rssi: Int, rec: ByteArray? -> listeners.forEach { it.onAdvert(d, rssi, rec) } }
        stopper = try {
            if (Build.VERSION.SDK_INT >= 21) {
                val scanner = a.bluetoothLeScanner ?: return
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) =
                        dispatch(result.device, result.rssi, result.scanRecord?.bytes)

                    override fun onBatchScanResults(results: MutableList<ScanResult>) =
                        results.forEach { dispatch(it.device, it.rssi, it.scanRecord?.bytes) }
                }
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb)
                ({ runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }; Unit })
            } else {
                val cb = BluetoothAdapter.LeScanCallback { d, rssi, rec -> dispatch(d, rssi, rec) }
                @Suppress("DEPRECATION")
                a.startLeScan(cb)
                ({
                    @Suppress("DEPRECATION")
                    runCatching { adapter?.stopLeScan(cb) }
                    Unit
                })
            }
        } catch (e: Throwable) {
            null
        }
    }

    @Synchronized
    fun stop() {
        stopper?.invoke()
        stopper = null
    }

    companion object {
        /** Werbedaten zerlegen: Gerätename und Herstellerdaten (Firmen-ID -> Bytes). */
        fun parse(record: ByteArray?): Advert {
            var name: String? = null
            val manu = HashMap<Int, ByteArray>()
            if (record != null) {
                var i = 0
                while (i < record.size) {
                    val len = record[i].toInt() and 0xFF
                    if (len == 0 || i + len >= record.size) break
                    val type = record[i + 1].toInt() and 0xFF
                    val data = record.copyOfRange(i + 2, i + 1 + len)
                    when (type) {
                        0x08, 0x09 -> name = String(data, Charsets.UTF_8).trim { it <= ' ' }
                        0xFF -> if (data.size >= 2) {
                            val id = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                            manu[id] = data.copyOfRange(2, data.size)
                        }
                    }
                    i += len + 1
                }
            }
            return Advert(name, manu)
        }
    }

    class Advert(val name: String?, val manufacturer: Map<Int, ByteArray>)
}

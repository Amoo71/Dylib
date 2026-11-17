package com.amoo.roomlauncher

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/** Einstellungen. Häufig geänderte Werte (Slider) werden gesammelt und verzögert gespeichert. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("room_launcher", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, (SharedPreferences.Editor) -> Unit>()
    private val flush = Runnable {
        val edits = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        if (edits.isNotEmpty()) sp.edit().apply { edits.forEach { it(this) } }.apply()
    }

    private fun later(key: String, edit: (SharedPreferences.Editor) -> Unit) {
        synchronized(pending) { pending[key] = edit }
        handler.removeCallbacks(flush)
        handler.postDelayed(flush, 600)
    }

    var goveeAddress: String?
        get() = sp.getString("govee_address", null)
        set(v) = sp.edit().putString("govee_address", v).apply()

    var goveeName: String?
        get() = sp.getString("govee_name", null)
        set(v) = sp.edit().putString("govee_name", v).apply()

    /** Farb-Unterbefehl: 0x02 (klassisch), 0x0D, 0x0B oder 0x15 (Segmente). Modellabhängig. */
    var goveeColorMode: Int
        get() = sp.getInt("govee_color_mode", 0x02)
        set(v) = sp.edit().putInt("govee_color_mode", v).apply()

    /** true = Helligkeit 0..100 (viele neuere Modelle), false = 0..255. */
    var goveeBrightness100: Boolean
        get() = sp.getBoolean("govee_brightness_100", false)
        set(v) = sp.edit().putBoolean("govee_brightness_100", v).apply()

    var lastPower: Boolean = sp.getBoolean("last_power", true)
        set(v) {
            field = v
            later("last_power") { it.putBoolean("last_power", v) }
        }

    var lastBrightness: Float = sp.getFloat("last_brightness", 1f)
        set(v) {
            field = v
            later("last_brightness") { it.putFloat("last_brightness", v) }
        }

    var lastColor: Int = sp.getInt("last_color", 0xFFFFB46B.toInt())
        set(v) {
            field = v
            later("last_color") { it.putInt("last_color", v) }
        }

    var airpodsAddress: String?
        get() = sp.getString("airpods_address", null)
        set(v) = sp.edit().putString("airpods_address", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    /** 60 fps (flüssig) oder 30 fps (sparsam) für animierte Hintergründe. */
    var smooth: Boolean
        get() = sp.getBoolean("smooth_60fps", true)
        set(v) = sp.edit().putBoolean("smooth_60fps", v).apply()

    /** Darstellung der App-Icons: 0 = Original, 1 = Glas-Kachel, 2 = Klar (einfarbig auf Glas). */
    var iconStyle: Int
        get() = sp.getInt("icon_style", 1)
        set(v) = sp.edit().putInt("icon_style", v).apply()
}

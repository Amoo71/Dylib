package com.amoo.roomlauncher

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("room_launcher", Context.MODE_PRIVATE)

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

    var lastPower: Boolean
        get() = sp.getBoolean("last_power", true)
        set(v) = sp.edit().putBoolean("last_power", v).apply()

    var lastBrightness: Float
        get() = sp.getFloat("last_brightness", 1f)
        set(v) = sp.edit().putFloat("last_brightness", v).apply()

    var lastColor: Int
        get() = sp.getInt("last_color", 0xFFFFB46B.toInt())
        set(v) = sp.edit().putInt("last_color", v).apply()

    var airpodsAddress: String?
        get() = sp.getString("airpods_address", null)
        set(v) = sp.edit().putString("airpods_address", v).apply()

    var favorites: List<String>?
        get() = sp.getString("favorites", null)?.split(",")?.filter { it.isNotBlank() }
        set(v) = sp.edit().putString("favorites", v?.joinToString(",")).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()
}

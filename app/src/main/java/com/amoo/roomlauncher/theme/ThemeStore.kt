package com.amoo.roomlauncher.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class BgType(val label: String) { RAIN("Regen"), BLACK("Schwarz"), COLORED("Farbig"), OWN("Eigenes") }

enum class BgEffect(val label: String) { NONE("Keiner"), RAIN("Regen"), BUBBLES("Blasen") }

enum class BubbleColor(val label: String) { GRAY("Grau"), WHITE("Weiß"), THEME("Farben") }

data class ThemeSettings(
    val type: BgType = BgType.BLACK,
    // Farbig
    val colors: List<Int> = listOf(0xFF2B1D8F.toInt(), 0xFF8A1D5C.toInt()),
    val gradientAngle: Float = 135f,
    // Blasen
    val bubbleCount: Int = 12,
    val bubbleSize: Float = 1f,
    val bubbleSpeed: Float = 1f,
    val bubbleOpacity: Float = 0.45f,
    val bubbleColor: BubbleColor = BubbleColor.GRAY,
    // Regen
    val rainIntensity: Float = 1f,
    val rainWind: Float = 0.35f,
    val rainSpeed: Float = 1f,
    // Eigenes
    val ownImage: String? = null,
    val ownEffect: BgEffect = BgEffect.NONE,
    val ownDim: Float = 0.25f,
    // Glas
    val glassBlur: Boolean = true,
    /** Dichte des Glases: 0 = fast unsichtbar, 1 = kräftig getönt. */
    val glassOpacity: Float = 0.3f,
) {
    /** Welche Partikel-Effekte gerade aktiv sind. */
    val showRain: Boolean get() = type == BgType.RAIN || (type == BgType.OWN && ownEffect == BgEffect.RAIN)
    val showBubbles: Boolean
        get() = type == BgType.BLACK || type == BgType.COLORED || (type == BgType.OWN && ownEffect == BgEffect.BUBBLES)

    fun toJson(): String = JSONObject().apply {
        put("type", type.name)
        put("colors", JSONArray(colors))
        put("angle", gradientAngle.toDouble())
        put("bCount", bubbleCount)
        put("bSize", bubbleSize.toDouble())
        put("bSpeed", bubbleSpeed.toDouble())
        put("bOpacity", bubbleOpacity.toDouble())
        put("bColor", bubbleColor.name)
        put("rInt", rainIntensity.toDouble())
        put("rWind", rainWind.toDouble())
        put("rSpeed", rainSpeed.toDouble())
        put("ownImage", ownImage ?: "")
        put("ownEffect", ownEffect.name)
        put("ownDim", ownDim.toDouble())
        put("blur", glassBlur)
        put("glassOpacity", glassOpacity.toDouble())
    }.toString()

    companion object {
        fun fromJson(s: String?): ThemeSettings {
            if (s.isNullOrBlank()) return ThemeSettings()
            return runCatching {
                val o = JSONObject(s)
                val d = ThemeSettings()
                val arr = o.optJSONArray("colors")
                ThemeSettings(
                    type = runCatching { BgType.valueOf(o.getString("type")) }.getOrDefault(d.type),
                    colors = if (arr != null && arr.length() > 0) (0 until arr.length()).map { arr.getLong(it).toInt() } else d.colors,
                    gradientAngle = o.optDouble("angle", d.gradientAngle.toDouble()).toFloat(),
                    bubbleCount = o.optInt("bCount", d.bubbleCount),
                    bubbleSize = o.optDouble("bSize", 1.0).toFloat(),
                    bubbleSpeed = o.optDouble("bSpeed", 1.0).toFloat(),
                    bubbleOpacity = o.optDouble("bOpacity", d.bubbleOpacity.toDouble()).toFloat(),
                    bubbleColor = runCatching { BubbleColor.valueOf(o.getString("bColor")) }.getOrDefault(d.bubbleColor),
                    rainIntensity = o.optDouble("rInt", 1.0).toFloat(),
                    rainWind = o.optDouble("rWind", d.rainWind.toDouble()).toFloat(),
                    rainSpeed = o.optDouble("rSpeed", 1.0).toFloat(),
                    ownImage = o.optString("ownImage").ifBlank { null },
                    ownEffect = runCatching { BgEffect.valueOf(o.getString("ownEffect")) }.getOrDefault(d.ownEffect),
                    ownDim = o.optDouble("ownDim", d.ownDim.toDouble()).toFloat(),
                    glassBlur = o.optBoolean("blur", true),
                    glassOpacity = o.optDouble("glassOpacity", 0.3).toFloat(),
                )
            }.getOrDefault(ThemeSettings())
        }
    }
}

class ThemeStore(context: Context) {
    private val sp = context.getSharedPreferences("room_theme", Context.MODE_PRIVATE)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val save = Runnable { sp.edit().putString("theme", _settings.value.toJson()).apply() }
    private val _settings = MutableStateFlow(ThemeSettings.fromJson(sp.getString("theme", null)))
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    /** Erhöht sich, wenn ein neues eigenes Bild gespeichert wurde (Datei bleibt gleich benannt). */
    private val _imageVersion = MutableStateFlow(sp.getInt("image_version", 0))
    val imageVersion: StateFlow<Int> = _imageVersion.asStateFlow()

    fun update(transform: (ThemeSettings) -> ThemeSettings) {
        _settings.value = transform(_settings.value)
        // Slider ändern sehr oft – gespeichert wird erst, wenn Ruhe ist.
        handler.removeCallbacks(save)
        handler.postDelayed(save, 500)
    }

    fun bumpImageVersion() {
        val v = _imageVersion.value + 1
        _imageVersion.value = v
        sp.edit().putInt("image_version", v).apply()
    }
}

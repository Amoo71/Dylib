package com.amoo.roomlauncher.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.amoo.roomlauncher.airpods.AirPodsController
import com.amoo.roomlauncher.apps.AppEntry
import com.amoo.roomlauncher.board.WidgetKind
import com.amoo.roomlauncher.board.WidgetSize
import com.amoo.roomlauncher.govee.GoveeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val PRESETS = intArrayOf(
    0xFFFFB46B.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD60A.toInt(),
    0xFF34C759.toInt(), 0xFF00C7BE.toInt(), 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(),
    0xFFFF2D87.toInt(),
)

private val RAINBOW = IntArray(7) { Color.HSVToColor(floatArrayOf((it * 60f) % 360f, 1f, 1f)) }

private fun hueOf(argb: Int): Float {
    val hsv = FloatArray(3)
    Color.colorToHSV(argb, hsv)
    return hsv[0]
}

fun Context.widgetView(
    kind: WidgetKind,
    size: WidgetSize,
    govee: GoveeController,
    airpods: AirPodsController,
    onSetupLight: () -> Unit,
    onSetupAirPods: () -> Unit,
): View = when (kind) {
    WidgetKind.CLOCK -> clockWidget(size)
    WidgetKind.LIGHT -> lightWidget(size, govee, onSetupLight)
    WidgetKind.AIRPODS -> airPodsWidget(size, airpods, onSetupAirPods)
}

// ------------------------------------------------------------------ Uhr

fun Context.clockWidget(size: WidgetSize): View {
    val small = size == WidgetSize.S
    val card = glassCard(20)
    card.gravity = Gravity.CENTER_VERTICAL
    val day = label("", if (small) 15f else 19f, C.RED, bold = true)
    val time = label("", if (small) 54f else 84f, thin = true)
    val date = label("", if (small) 13f else 16f, C.TEXT2)
    card.addView(day)
    card.addView(time, lp().margins(t = dp(2), b = dp(2)))
    card.addView(date)
    val tick = object : Runnable {
        override fun run() {
            val now = Date()
            day.text = SimpleDateFormat("EEEE", Locale.GERMANY).format(now)
            time.text = SimpleDateFormat("HH:mm", Locale.GERMANY).format(now)
            date.text = SimpleDateFormat("d. MMMM yyyy", Locale.GERMANY).format(now)
            card.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }
    card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = tick.run()
        override fun onViewDetachedFromWindow(v: View) {
            v.removeCallbacks(tick)
        }
    })
    tick.run()
    return card
}

// ------------------------------------------------------------------ Licht

private fun Context.lightHeader(govee: GoveeController, onSetup: (() -> Unit)?): LinearLayout {
    val title = label("Licht", 20f, bold = true)
    val status = label("", 12f, C.TEXT2)
    val dot = dot(C.TEXT3)
    val col = column().apply {
        addView(title)
        addView(row(dot, status, spacingDp = 6), lp().margins(t = dp(3)))
    }
    val header = row(col.also { it.layoutParams = lp(0, WRAP, 1f) })
    if (onSetup != null) header.addView(glassIconButton(Glyph.GEAR, 36, onSetup))
    header.bind(govee.connection) { c -> update(govee, c, status, dot) }
    header.bind(govee.deviceName) { update(govee, govee.connection.value, status, dot) }
    return header
}

private fun update(govee: GoveeController, c: GoveeController.Connection, status: TextView, dot: View) {
    status.text = when (c) {
        GoveeController.Connection.NOT_CONFIGURED -> "nicht eingerichtet"
        GoveeController.Connection.CONNECTED -> govee.deviceName.value ?: "verbunden"
        GoveeController.Connection.CONNECTING -> "verbinde…"
        GoveeController.Connection.DISCONNECTED -> "getrennt – verbinde neu…"
    }
    (dot.background as? RoundDrawable)?.let {
        it.color = when (c) {
            GoveeController.Connection.CONNECTED -> C.GREEN
            GoveeController.Connection.CONNECTING -> C.ORANGE
            GoveeController.Connection.DISCONNECTED -> C.RED
            GoveeController.Connection.NOT_CONFIGURED -> C.TEXT3
        }
        dot.invalidate()
    }
}

private fun Context.powerButton(govee: GoveeController, sizeDp: Int): PowerButton {
    val b = PowerButton(this, sizeDp) { govee.setPower(!govee.power.value) }
    b.bind(govee.power) { b.on = it }
    b.bind(govee.color) { b.ringColor = it }
    return b
}

private fun Context.brightnessSlider(govee: GoveeController, h: Int): GlassSlider {
    val s = GlassSlider(this, h) { govee.setBrightness(it) }
    s.bind(govee.brightness) { s.value = it }
    return s
}

private fun Context.hueSlider(govee: GoveeController, h: Int): GlassSlider {
    val s = GlassSlider(this, h, RAINBOW) { v ->
        govee.setColor(Color.HSVToColor(floatArrayOf((v * 360f).coerceIn(0f, 359.9f), 1f, 1f)))
    }
    s.value = hueOf(govee.color.value) / 360f
    return s
}

private fun Context.presetRow(govee: GoveeController, sizeDp: Int, hue: GlassSlider?): View {
    val swatches = PRESETS.map { c ->
        View(this).apply {
            val d = RoundDrawable(context, sizeDp / 2f, c, C.white(0.35f))
            background = d
            layoutParams = lp(dp(sizeDp), dp(sizeDp))
            pressable {
                govee.setColor(c)
                hue?.value = hueOf(c) / 360f
            }
        }
    }
    return hScroll(*swatches.toTypedArray(), spacingDp = 8)
}

fun Context.lightWidget(size: WidgetSize, govee: GoveeController, onSetup: () -> Unit): View = when (size) {
    WidgetSize.S -> glassCard(16).apply {
        addView(lightHeader(govee, null))
        addView(flexSpace())
        val pct = label("", 20f, bold = true)
        pct.bind(govee.brightness) { pct.text = "${(it * 100).roundToInt()} %" }
        addView(row(powerButton(govee, 56), pct, spacingDp = 12))
        addView(flexSpace())
        addView(brightnessSlider(govee, 38))
    }
    WidgetSize.M -> glassCard(18).apply {
        addView(lightHeader(govee, onSetup))
        addView(flexSpace())
        val hue = hueSlider(govee, 30)
        val sliders = column().apply {
            addView(brightnessSlider(govee, 38))
            addView(hue, lp().margins(t = dp(8)))
        }
        addView(row(powerButton(govee, 64), sliders.also { it.layoutParams = lp(0, WRAP, 1f) }, spacingDp = 14))
        addView(flexSpace())
        addView(presetRow(govee, 32, hue))
    }
    WidgetSize.L -> {
        val card = glassCard(22)
        card.addView(lightHeader(govee, onSetup))
        val content = column()
        val pct = label("", 14f, C.TEXT2, bold = true)
        pct.bind(govee.brightness) { pct.text = "Helligkeit ${(it * 100).roundToInt()} %" }
        val bright = column().apply {
            addView(pct)
            addView(brightnessSlider(govee, 48), lp().margins(t = dp(8)))
        }
        content.addView(row(powerButton(govee, 76), bright.also { it.layoutParams = lp(0, WRAP, 1f) }, spacingDp = 18), lp().margins(t = dp(16)))
        content.addView(label("Farbe", 14f, C.TEXT2, bold = true), lp().margins(t = dp(20)))
        val hue = hueSlider(govee, 38)
        content.addView(hue, lp().margins(t = dp(8)))
        content.addView(presetRow(govee, 42, hue), lp().margins(t = dp(14)))
        content.addView(label("Szenen", 14f, C.TEXT2, bold = true), lp().margins(t = dp(20)))
        content.addView(
            hScroll(*GoveeController.SCENES.map { (id, l) -> Chip(this, l) { govee.setScene(id) } }.toTypedArray()),
            lp().margins(t = dp(8)),
        )
        content.addView(label("Musik-Modus (Mikrofon am Controller)", 14f, C.TEXT2, bold = true), lp().margins(t = dp(16)))
        content.addView(
            hScroll(*GoveeController.MUSIC_MODES.map { (id, l) -> Chip(this, l) { govee.setMusicMode(id) } }.toTypedArray()),
            lp().margins(t = dp(8)),
        )
        val reconnect = glassButton("Jetzt verbinden") { govee.connect() }
        reconnect.bind(govee.connection) {
            reconnect.visibility = if (it == GoveeController.Connection.DISCONNECTED) View.VISIBLE else View.GONE
        }
        content.addView(reconnect, lp(WRAP, WRAP).margins(t = dp(16)))
        card.addView(scroll(content), lp(MATCH, 0, 1f))
        card
    }
}

fun Context.scroll(content: View): ScrollView = ScrollView(this).apply {
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    addView(content)
}

// ------------------------------------------------------------------ AirPods

private fun Context.airPodsHeader(airpods: AirPodsController, onSetup: (() -> Unit)?): LinearLayout {
    val title = label("AirPods", 20f, bold = true)
    val status = label("", 12f, C.TEXT2)
    val dot = dot(C.TEXT3)
    val col = column().apply {
        addView(title)
        addView(row(dot, status, spacingDp = 6), lp().margins(t = dp(3)))
    }
    val header = row(col.also { it.layoutParams = lp(0, WRAP, 1f) })
    if (onSetup != null) header.addView(glassIconButton(Glyph.GEAR, 36, onSetup))
    header.bind(airpods.deviceName) { title.text = it ?: airpods.model.value ?: "AirPods" }
    header.bind(airpods.audioConnected) { c ->
        status.text = if (c) "verbunden" + (airpods.model.value?.let { " · $it" } ?: "") else
            if (airpods.deviceName.value == null) "nicht gekoppelt" else "nicht verbunden"
        (dot.background as? RoundDrawable)?.color = if (c) C.GREEN else C.TEXT3
        dot.invalidate()
    }
    return header
}

private fun Context.miniBattery(name: String, pick: (AirPodsController.Battery?) -> Int?, airpods: AirPodsController, inEar: ((AirPodsController.InEar?) -> Boolean)? = null): LinearLayout {
    val title = label(name, 11f, C.TEXT3)
    val value = label("–", 17f, bold = true)
    val col = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(title)
        addView(value, lp(WRAP, WRAP).margins(t = dp(2)))
    }
    col.bind(airpods.battery) { b ->
        val p = pick(b)
        value.text = p?.let { "$it%" } ?: "–"
        value.setTextColor(if (p != null && p <= 20) C.RED else C.TEXT)
    }
    if (inEar != null) col.bind(airpods.inEar) { e -> title.text = if (inEar(e)) "$name · Ohr" else name }
    return col
}

private fun Context.batteryRow(airpods: AirPodsController): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    addView(miniBattery("Links", { it?.left }, airpods) { it?.left == true }, lp(0, WRAP, 1f))
    addView(miniBattery("Rechts", { it?.right }, airpods) { it?.right == true }, lp(0, WRAP, 1f))
    addView(miniBattery("Case", { it?.case }, airpods), lp(0, WRAP, 1f))
}

private fun Context.noiseSelector(airpods: AirPodsController): Segmented {
    val modes = AirPodsController.Noise.entries
    val seg = Segmented(this, modes.map { it.label }) { i -> airpods.setNoise(modes[i]) }
    seg.bind(airpods.noise) { n -> seg.selected = n?.let { modes.indexOf(it) } ?: -1 }
    seg.bind(airpods.controlReady) { seg.isEnabled = it }
    return seg
}

private fun Context.connectButton(airpods: AirPodsController): TextView {
    val b = glassButton("Verbinden", true) {
        if (airpods.audioConnected.value) airpods.disconnectAudio() else airpods.connectAudio()
    }
    b.bind(airpods.audioConnected) { b.text = if (it) "Trennen" else "Verbinden" }
    return b
}

fun Context.airPodsWidget(size: WidgetSize, airpods: AirPodsController, onSetup: () -> Unit): View = when (size) {
    WidgetSize.S -> glassCard(16).apply {
        addView(airPodsHeader(airpods, null))
        addView(flexSpace())
        addView(batteryRow(airpods))
        addView(flexSpace())
        val modes = AirPodsController.Noise.entries
        val b = glassButton("Modus", true) {
            if (!airpods.audioConnected.value) {
                airpods.connectAudio()
            } else {
                val cur = airpods.noise.value?.let { modes.indexOf(it) } ?: -1
                airpods.setNoise(modes[(cur + 1) % modes.size])
            }
        }
        val refresh = {
            b.text = if (!airpods.audioConnected.value) "Verbinden" else airpods.noise.value?.label ?: "Modus wechseln"
        }
        b.bind(airpods.noise) { refresh() }
        b.bind(airpods.audioConnected) { refresh() }
        addView(b, lp())
    }
    WidgetSize.M -> glassCard(18).apply {
        addView(airPodsHeader(airpods, onSetup))
        addView(flexSpace())
        addView(row(batteryRow(airpods).also { it.layoutParams = lp(0, WRAP, 1f) }, connectButton(airpods), spacingDp = 8))
        addView(flexSpace())
        addView(noiseSelector(airpods))
    }
    WidgetSize.L -> {
        val card = glassCard(22)
        card.addView(airPodsHeader(airpods, onSetup))
        val content = column()
        content.addView(batteryRow(airpods), lp().margins(t = dp(16)))
        val ctrl = glassButton("Steuerung verbinden") { airpods.openControl() }
        ctrl.bind(airpods.controlReady) { ctrl.visibility = if (it) View.GONE else View.VISIBLE }
        content.addView(row(connectButton(airpods), ctrl, spacingDp = 10), lp().margins(t = dp(14)))
        content.addView(label("Geräuschkontrolle", 14f, C.TEXT2, bold = true), lp().margins(t = dp(18)))
        content.addView(noiseSelector(airpods), lp().margins(t = dp(8)))

        val strengthLabel = label("", 13f, C.TEXT2)
        val strength = GlassSlider(this, 34) { airpods.setAdaptiveStrength((it * 100).roundToInt()) }
        val strengthBox = column().apply {
            addView(strengthLabel)
            addView(strength, lp().margins(t = dp(6)))
        }
        strengthBox.bind(airpods.adaptiveStrength) {
            strength.value = (it ?: 50) / 100f
            strengthLabel.text = "Adaptives Audio: ${it ?: 50} %  (weniger ↔ mehr Geräuschreduzierung)"
        }
        strengthBox.bind(airpods.noise) {
            strengthBox.visibility = if (it == AirPodsController.Noise.ADAPTIVE) View.VISIBLE else View.GONE
        }
        content.addView(strengthBox, lp().margins(t = dp(14)))

        content.addView(toggleRow("Gesprächserkennung", airpods.conversationAwareness, airpods.controlReady) { airpods.setConversationAwareness(it) }, lp().margins(t = dp(10)))
        content.addView(toggleRow("Automatische Ohrerkennung", airpods.earDetection, airpods.controlReady) { airpods.setEarDetection(it) })
        content.addView(toggleRow("ANC mit einem AirPod", airpods.oneBudAnc, airpods.controlReady) { airpods.setOneBudAnc(it) })
        val status = label("", 12f, C.TEXT3, lines = 3)
        status.bind(airpods.controlStatus) { status.text = it }
        content.addView(status, lp().margins(t = dp(8)))
        card.addView(scroll(content), lp(MATCH, 0, 1f))
        card
    }
}

private fun Context.toggleRow(
    text: String,
    value: kotlinx.coroutines.flow.StateFlow<Boolean?>,
    enabled: kotlinx.coroutines.flow.StateFlow<Boolean>,
    onChange: (Boolean) -> Unit,
): LinearLayout {
    val t = label(text, 15f)
    val sw = GlassSwitch(this, value.value == true) { onChange(it) }
    val r = row(t.also { it.layoutParams = lp(0, WRAP, 1f) }, sw)
    r.setPadding(0, dp(6), 0, dp(6))
    r.bind(value) { sw.checked = it == true }
    r.bind(enabled) {
        sw.isEnabled = it
        t.setTextColor(if (it) C.TEXT else C.TEXT3)
        sw.invalidate()
    }
    return r
}

// ------------------------------------------------------------------ App-Icon

fun Context.appIcon(entry: AppEntry?, onLaunch: () -> Unit): View {
    val box = FrameLayout(this)
    val col = column().apply { gravity = Gravity.CENTER }
    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (entry != null) setImageBitmap(entry.icon) else background = GlassDrawable(context, 16f)
    }
    val iconSize = dp(64)
    col.addView(img, lp(iconSize, iconSize).apply { gravity = Gravity.CENTER_HORIZONTAL })
    val name = label(entry?.label ?: "Fehlt", 12f).apply { gravity = Gravity.CENTER }
    col.addView(name, lp().margins(t = dp(6)))
    box.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    box.pressable(onClick = onLaunch)
    return box
}

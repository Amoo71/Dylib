package com.amoo.roomlauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.amoo.roomlauncher.airpods.AirPodsController
import com.amoo.roomlauncher.apps.AppEntry
import com.amoo.roomlauncher.board.WidgetKind
import com.amoo.roomlauncher.board.WidgetSize
import com.amoo.roomlauncher.govee.GoveeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
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

/** Kopfzeile eines Widgets: Titel, Status-Punkt + Text, optional Zahnrad. */
private class Header(context: Context, title: String, onSetup: (() -> Unit)?) : LinearLayout(context) {
    val title: TextView = context.label(title, 20f, bold = true)
    val status: TextView = context.label("", 12.5f, C.TEXT2)
    val dot: View = context.dot(C.TEXT3, 8)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val col = context.column().apply {
            addView(this@Header.title)
            addView(context.row(dot, status, spacingDp = 6), lp().margins(t = dp(4)))
        }
        addView(col, lp(0, WRAP, 1f))
        if (onSetup != null) addView(context.glassIconButton(Glyph.GEAR, 36, onClick = onSetup))
    }

    fun setStatus(text: String, color: Int) {
        status.text = text
        (dot.background as? RoundDrawable)?.color = color
        dot.invalidate()
    }
}

// ------------------------------------------------------------------ Uhr

private val DAY = SimpleDateFormat("EEEE", Locale.GERMANY)
private val TIME = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val DATE = SimpleDateFormat("d. MMMM yyyy", Locale.GERMANY)

fun Context.clockWidget(size: WidgetSize): View {
    val small = size == WidgetSize.S
    // Kein Glas-Kasten – die Uhr schwebt frei über dem Hintergrund (Schatten für Lesbarkeit)
    val card = column().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    fun TextView.shadow() = apply { setShadowLayer(dpf(10), 0f, dpf(2), 0x80000000.toInt()) }
    val day = label("", if (small) 16f else 20f, C.RED, bold = true).shadow()
    val time = label("", if (small) 62f else 104f, thin = true).shadow()
    val date = label("", if (small) 14f else 18f, C.TEXT2).shadow()
    card.addView(day)
    card.addView(time, lp().margins(t = dp(2), b = dp(4)))
    card.addView(date)
    val tick = object : Runnable {
        override fun run() {
            val now = Date()
            day.text = DAY.format(now)
            time.text = TIME.format(now)
            date.text = DATE.format(now)
            card.removeCallbacks(this)
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

/** Leuchtender Punkt in der aktuellen LED-Farbe. */
private class GlowDot(context: Context) : View(context) {
    var color = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    var on = true
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        layoutParams = lp(dp(30), dp(30))
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val col = if (on) color else 0xFF3A3A3C.toInt()
        p.shader = RadialGradient(c, c, c, intArrayOf(col, Color.argb(if (on) 90 else 0, Color.red(col), Color.green(col), Color.blue(col)), 0), floatArrayOf(0.3f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(c, c, c, p)
    }
}

private fun Context.lightHeader(govee: GoveeController, onSetup: (() -> Unit)?): View {
    val h = Header(this, "Licht", onSetup)
    val glow = GlowDot(this)
    h.addView(glow, 0, lp(dp(30), dp(30)).margins(r = dp(10)))
    fun refresh() {
        val c = govee.connection.value
        h.setStatus(
            when (c) {
                GoveeController.Connection.NOT_CONFIGURED -> "nicht eingerichtet"
                GoveeController.Connection.CONNECTED -> govee.deviceName.value ?: "verbunden"
                GoveeController.Connection.CONNECTING -> "verbinde…"
                GoveeController.Connection.DISCONNECTED -> "getrennt"
            },
            when (c) {
                GoveeController.Connection.CONNECTED -> C.GREEN
                GoveeController.Connection.CONNECTING -> C.ORANGE
                GoveeController.Connection.DISCONNECTED -> C.RED
                GoveeController.Connection.NOT_CONFIGURED -> C.TEXT3
            },
        )
    }
    h.bind(govee.connection) { refresh() }
    h.bind(govee.deviceName) { refresh() }
    glow.bind(govee.color) { glow.color = it }
    glow.bind(govee.power) { glow.on = it }
    return h
}

private fun Context.powerButton(govee: GoveeController, sizeDp: Int): PowerButton {
    val b = PowerButton(this, sizeDp) { govee.setPower(!govee.power.value) }
    b.bind(govee.power) { b.on = it }
    b.bind(govee.color) { b.ringColor = it }
    return b
}

private fun Context.brightnessSlider(govee: GoveeController, h: Int): GlassSlider {
    val s = GlassSlider(this, h, glyph = Glyph.SUN) { govee.setBrightness(it) }
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
            background = RoundDrawable(context, sizeDp / 2f, c, C.white(0.35f))
            layoutParams = lp(dp(sizeDp), dp(sizeDp))
            pressable {
                govee.setColor(c)
                hue?.value = hueOf(c) / 360f
            }
        }
    }
    return hScroll(*swatches.toTypedArray(), spacingDp = 8)
}

private fun Context.notConfigured(onSetup: () -> Unit): View = column().apply {
    addView(label("Noch kein Licht verbunden.", 14f, C.TEXT2, lines = 2))
    addView(glassButton("Licht einrichten", true, onSetup), lp(WRAP, WRAP).margins(t = dp(10)))
}

fun Context.lightWidget(size: WidgetSize, govee: GoveeController, onSetup: () -> Unit): View {
    val card = glassCard(if (size == WidgetSize.S) 16 else 20)
    card.addView(lightHeader(govee, if (size == WidgetSize.S) null else onSetup))
    val body = column()
    val setup = notConfigured(onSetup)
    card.addView(flexSpaceIf(size != WidgetSize.L))
    card.addView(body, if (size == WidgetSize.L) lp(MATCH, 0, 1f).margins(t = dp(12)) else lp())
    card.addView(setup, lp().margins(t = dp(8)))
    card.addView(flexSpaceIf(size != WidgetSize.L))
    body.bind(govee.connection) { c ->
        val none = c == GoveeController.Connection.NOT_CONFIGURED
        body.visibility = if (none) View.GONE else View.VISIBLE
        setup.visibility = if (none) View.VISIBLE else View.GONE
    }
    when (size) {
        WidgetSize.S -> {
            val pct = label("", 22f, light = true)
            pct.bind(govee.brightness) { pct.text = "${(it * 100).roundToInt()} %" }
            body.addView(row(powerButton(govee, 54), pct, spacingDp = 12))
            body.addView(brightnessSlider(govee, 38), lp().margins(t = dp(12)))
        }
        WidgetSize.M -> {
            val hue = hueSlider(govee, 30)
            val sliders = column().apply {
                addView(brightnessSlider(govee, 40))
                addView(hue, lp().margins(t = dp(10)))
            }
            body.addView(row(powerButton(govee, 66), sliders.also { it.layoutParams = lp(0, WRAP, 1f) }, spacingDp = 16))
            body.addView(presetRow(govee, 30, hue), lp().margins(t = dp(14)))
        }
        WidgetSize.L -> {
            val content = column()
            val pct = label("", 14f, C.TEXT2, bold = true)
            pct.bind(govee.brightness) { pct.text = "Helligkeit ${(it * 100).roundToInt()} %" }
            val bright = column().apply {
                addView(pct)
                addView(brightnessSlider(govee, 48), lp().margins(t = dp(8)))
            }
            content.addView(row(powerButton(govee, 76), bright.also { it.layoutParams = lp(0, WRAP, 1f) }, spacingDp = 18))
            content.addView(label("Farbe", 14f, C.TEXT2, bold = true), lp().margins(t = dp(20)))
            val hue = hueSlider(govee, 38)
            content.addView(hue, lp().margins(t = dp(8)))
            content.addView(presetRow(govee, 40, hue), lp().margins(t = dp(14)))
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
            val error = label("", 12.5f, C.ORANGE, lines = 2)
            error.bind(govee.lastError) {
                error.text = it.orEmpty()
                error.visibility = if (it == null) View.GONE else View.VISIBLE
            }
            content.addView(error, lp().margins(t = dp(14)))
            val reconnect = glassButton("Jetzt verbinden") { govee.reconnectNow() }
            reconnect.bind(govee.connection) {
                reconnect.visibility = if (it == GoveeController.Connection.DISCONNECTED) View.VISIBLE else View.GONE
            }
            content.addView(reconnect, lp(WRAP, WRAP).margins(t = dp(10)))
            body.addView(scroll(content), lp(MATCH, MATCH))
        }
    }
    return card
}

private fun Context.flexSpaceIf(flex: Boolean): View = if (flex) flexSpace() else spacer(0)

// ------------------------------------------------------------------ AirPods

private val SHORT_NOISE = mapOf(
    AirPodsController.Noise.OFF to "Aus",
    AirPodsController.Noise.TRANSPARENCY to "Transp.",
    AirPodsController.Noise.ADAPTIVE to "Adaptiv",
    AirPodsController.Noise.ANC to "ANC",
)

/** Kopfzeile: AirPods-Symbol, Name und Verbindungsstatus. */
private fun Context.airPodsHeader(airpods: AirPodsController, onSetup: (() -> Unit)?, compact: Boolean): View {
    val title = label("AirPods", if (compact) 17f else 20f, bold = true)
    val status = label("", 12f, C.TEXT2)
    val dot = dot(C.TEXT3, 7)
    val text = column().apply {
        addView(title)
        addView(row(dot, status, spacingDp = 5), lp().margins(t = dp(3)))
    }
    val h = row(
        AirPodsGlyph(this).apply { layoutParams = lp(dp(if (compact) 28 else 34), dp(if (compact) 28 else 34)) },
        text.also { it.layoutParams = lp(0, WRAP, 1f).margins(l = dp(10)) },
    )
    if (onSetup != null) h.addView(glassIconButton(Glyph.GEAR, 36, onClick = onSetup))
    fun refresh() {
        val name = airpods.deviceName.value
        val model = airpods.model.value
        title.text = name ?: model ?: "AirPods"
        val c = airpods.audioConnected.value
        status.text = when {
            c -> if (model != null && model != name) "verbunden · $model" else "verbunden"
            name == null -> "nicht gekoppelt"
            else -> "nicht verbunden"
        }
        (dot.background as? RoundDrawable)?.color = if (c) C.GREEN else C.TEXT3
        dot.invalidate()
    }
    h.bind(airpods.deviceName) { refresh() }
    h.bind(airpods.audioConnected) { refresh() }
    h.bind(airpods.model) { refresh() }
    return h
}

/** Ein Akku-Ring mit Prozentzahl darunter. */
private fun Context.ringItem(part: BatteryRing.Part, ringDp: Int, airpods: AirPodsController): LinearLayout {
    val ring = BatteryRing(this, part)
    val pct = label("–", 13.5f, C.TEXT2).apply { gravity = Gravity.CENTER }
    ring.bind(airpods.battery) { b ->
        val (p, ch) = when (part) {
            BatteryRing.Part.LEFT -> b?.left to (b?.chargingLeft == true)
            BatteryRing.Part.RIGHT -> b?.right to (b?.chargingRight == true)
            BatteryRing.Part.CASE -> b?.case to (b?.chargingCase == true)
        }
        ring.percent = p
        ring.charging = ch
        pct.text = p?.let { "$it %" } ?: "–"
        pct.setTextColor(if (p != null) C.TEXT else C.TEXT3)
    }
    if (part != BatteryRing.Part.CASE) {
        ring.bind(airpods.inEar) { e -> ring.inEar = if (part == BatteryRing.Part.LEFT) e?.left == true else e?.right == true }
    }
    return column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ring, lp(dp(ringDp), dp(ringDp)))
        addView(pct, lp(WRAP, WRAP).margins(t = dp(7)))
    }
}

private fun Context.ringRow(airpods: AirPodsController, ringDp: Int): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    for (part in BatteryRing.Part.entries) addView(ringItem(part, ringDp, airpods), lp(0, WRAP, 1f))
}

private fun Context.noiseSelector(airpods: AirPodsController, short: Boolean): Segmented {
    val modes = AirPodsController.Noise.entries
    val seg = Segmented(
        this,
        modes.map { if (short) SHORT_NOISE.getValue(it) else it.label },
        textSp = if (short) 12.5f else 14f,
        heightDp = if (short) 42 else 50,
    ) { i -> airpods.setNoise(modes[i]) }
    seg.bind(airpods.noise) { n -> seg.selected = n?.let { modes.indexOf(it) } ?: -1 }
    seg.bind(airpods.controlReady) { seg.isEnabled = it }
    return seg
}

private fun Context.connectButton(airpods: AirPodsController, highlighted: Boolean): TextView {
    val b = glassButton("Verbinden", highlighted) {
        if (airpods.audioConnected.value) airpods.disconnectAudio() else airpods.connectAudio()
    }
    b.bind(airpods.audioConnected) { b.text = if (it) "Trennen" else "Verbinden" }
    return b
}

fun Context.airPodsWidget(size: WidgetSize, airpods: AirPodsController, onSetup: () -> Unit): View = when (size) {
    WidgetSize.S -> glassCard(16).apply {
        addView(airPodsHeader(airpods, null, compact = true))
        addView(flexSpace())
        addView(ringRow(airpods, 56))
        addView(flexSpace())
        // Verbunden: Geräuschmodus direkt wählbar, sonst ein Verbinden-Knopf
        val bottom = FrameLayout(context)
        val seg = noiseSelector(airpods, short = true)
        val connect = glassButton("Verbinden", true) { airpods.connectAudio() }
        bottom.addView(seg, FrameLayout.LayoutParams(MATCH, WRAP))
        bottom.addView(connect, FrameLayout.LayoutParams(MATCH, WRAP))
        bottom.bind(airpods.audioConnected) { c ->
            seg.visibility = if (c) View.VISIBLE else View.GONE
            connect.visibility = if (c) View.GONE else View.VISIBLE
        }
        addView(bottom, lp())
    }
    WidgetSize.M -> glassCard(20).apply {
        addView(airPodsHeader(airpods, onSetup, compact = false))
        addView(flexSpace())
        addView(
            row(
                ringRow(airpods, 62).also { it.layoutParams = lp(0, WRAP, 1f) },
                connectButton(airpods, highlighted = false),
                spacingDp = 12,
            ),
        )
        addView(flexSpace())
        addView(noiseSelector(airpods, short = false))
    }
    WidgetSize.L -> {
        val card = glassCard(20)
        card.addView(airPodsHeader(airpods, onSetup, compact = false))
        val content = column()
        content.addView(ringRow(airpods, 76), lp().margins(t = dp(10)))
        val ctrl = glassButton("Steuerung verbinden") { airpods.openControl() }
        ctrl.bind(airpods.controlReady) { ctrl.visibility = if (it) View.GONE else View.VISIBLE }
        content.addView(row(connectButton(airpods, highlighted = false), ctrl, spacingDp = 10), lp().margins(t = dp(18)))
        content.addView(label("Geräuschkontrolle", 14f, C.TEXT2, bold = true), lp().margins(t = dp(18)))
        content.addView(noiseSelector(airpods, short = false), lp().margins(t = dp(8)))

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
        card.addView(scroll(content), lp(MATCH, 0, 1f).margins(t = dp(12)))
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
    r.setPadding(0, dp(7), 0, dp(7))
    r.bind(value) { sw.checked = it == true }
    r.bind(enabled) {
        sw.isEnabled = it
        t.setTextColor(if (it) C.TEXT else C.TEXT3)
        sw.invalidate()
    }
    return r
}

// ------------------------------------------------------------------ App-Icons

object IconStyle {
    const val ORIGINAL = 0
    const val GLASS = 1
    const val CLEAR = 2
    val labels = listOf("Original", "Glas", "Klar")
}

/** Icon-Kachel: Original, auf Glas, oder "klar" (einfarbig hell auf Glas) – wie iOS 26/27. */
class AppIconTile(context: Context, private val bitmap: Bitmap?, private val iconStyle: Int) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        if (iconStyle == IconStyle.CLEAR) colorFilter = CLEAR_FILTER
    }
    private val src = Rect()
    private val dst = RectF()

    init {
        if (iconStyle != IconStyle.ORIGINAL || bitmap == null) background = GlassDrawable(context, 18f, 1.1f)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val s = min(width, height).toFloat()
        val inset = if (iconStyle == IconStyle.ORIGINAL) 0f else s * 0.17f
        val left = (width - s) / 2f + inset
        val top = (height - s) / 2f + inset
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(left, top, left + s - inset * 2, top + s - inset * 2)
        canvas.drawBitmap(bmp, src, dst, paint)
    }

    companion object {
        private val CLEAR_FILTER = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0f)
                postConcat(ColorMatrix(floatArrayOf(1.15f, 0f, 0f, 0f, 45f, 0f, 1.15f, 0f, 0f, 45f, 0f, 0f, 1.15f, 0f, 45f, 0f, 0f, 0f, 0.92f, 0f)))
            },
        )
    }
}

/** App auf dem Board: Kachel + Name. */
fun Context.appIcon(entry: AppEntry?, style: Int, onLaunch: () -> Unit): View {
    val box = FrameLayout(this)
    val col = column().apply { gravity = Gravity.CENTER }
    col.addView(AppIconTile(this, entry?.icon, style), lp(dp(66), dp(66)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    val name = label(entry?.label ?: "", 12.5f).apply {
        gravity = Gravity.CENTER
        setShadowLayer(dpf(3), 0f, dpf(1), 0x99000000.toInt())
    }
    col.addView(name, lp().margins(t = dp(7)))
    box.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    box.pressable(onClick = onLaunch)
    return box
}

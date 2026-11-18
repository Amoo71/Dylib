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
import com.amoo.roomlauncher.toast
import kotlinx.coroutines.flow.StateFlow
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

/** Baut das Widget. [onOpenLight]/[onOpenAirPods] öffnen das Detail-Panel (alle Funktionen + Einrichtung). */
fun Context.widgetView(
    kind: WidgetKind,
    size: WidgetSize,
    govee: GoveeController,
    airpods: AirPodsController,
    onOpenLight: () -> Unit,
    onOpenAirPods: () -> Unit,
): View = when (kind) {
    WidgetKind.CLOCK -> clockWidget(size)
    WidgetKind.LIGHT -> lightWidget(size, govee, onOpenLight)
    WidgetKind.AIRPODS -> airPodsWidget(size, airpods, onOpenAirPods)
}

/**
 * Kopfzeile eines Widgets: [icon], Titel (Punktmatrix), Status-Punkt + Text, optional Zahnrad.
 * Ein Tipp auf die Kopfzeile öffnet das Detail-Panel.
 */
private class Header(context: Context, title: String, icon: View?, gear: Boolean, onOpen: () -> Unit) : LinearLayout(context) {
    val title: TextView = context.label(title, 19f, display = true)
    val status: TextView = context.label("", 12.5f, C.TEXT2)
    val dot: View = context.dot(C.TEXT3, 7)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (icon != null) addView(icon, (icon.layoutParams as? LayoutParams ?: lp(dp(30), dp(30))).margins(r = dp(10)))
        val col = context.column().apply {
            addView(this@Header.title)
            addView(context.row(dot, status, spacingDp = 6), lp().margins(t = dp(5)))
        }
        addView(col, lp(0, WRAP, 1f))
        if (gear) addView(context.glassIconButton(Glyph.GEAR, 36, onClick = onOpen))
        pressable(onClick = onOpen)
    }

    fun setStatus(text: String, color: Int) {
        status.text = text
        (dot.background as? RoundDrawable)?.color = color
        dot.invalidate()
    }
}

// ------------------------------------------------------------------ Uhr

private val DAY = SimpleDateFormat("EEEE", Locale.GERMANY)
private val HOURS = SimpleDateFormat("HH", Locale.GERMANY)
private val MINUTES = SimpleDateFormat("mm", Locale.GERMANY)
private val DATE = SimpleDateFormat("d. MMMM yyyy", Locale.GERMANY)

/** Uhr ohne Kasten – Punktmatrix-Ziffern schweben frei über dem Hintergrund. */
fun Context.clockWidget(size: WidgetSize): View {
    val small = size == WidgetSize.S
    // Alles mittig – waagerecht und senkrecht
    val card = column().apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    fun TextView.shadow() = apply {
        setShadowLayer(dpf(10), 0f, dpf(2), 0x8C000000.toInt())
        gravity = Gravity.CENTER
    }
    val sp = if (small) 66f else 112f
    val day = label("", if (small) 15f else 18f, C.RED, bold = true).shadow()
    val hours = label("", sp, display = true).shadow()
    val minutes = label("", sp, display = true).shadow()
    val time = row(hours, ColonDots(this, sp), minutes).apply { gravity = Gravity.CENTER }
    val date = label("", if (small) 14f else 17f, C.TEXT2).shadow()
    card.addView(day, lp(MATCH, WRAP))
    card.addView(time, lp(WRAP, WRAP).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(4)
        bottomMargin = dp(6)
    })
    card.addView(date, lp(MATCH, WRAP))
    val tick = object : Runnable {
        override fun run() {
            val now = Date()
            day.text = DAY.format(now).uppercase(Locale.GERMANY)
            hours.text = HOURS.format(now)
            minutes.text = MINUTES.format(now)
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
        p.shader = RadialGradient(
            c, c, c,
            intArrayOf(col, Color.argb(if (on) 90 else 0, Color.red(col), Color.green(col), Color.blue(col)), 0),
            floatArrayOf(0.3f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(c, c, c, p)
    }
}

private fun Context.lightHeader(govee: GoveeController, gear: Boolean, onOpen: () -> Unit): View {
    val glow = GlowDot(this)
    val h = Header(this, "Licht", glow, gear, onOpen)
    fun refresh() {
        val c = govee.connection.value
        h.setStatus(
            when (c) {
                GoveeController.Connection.NOT_CONFIGURED -> "nicht eingerichtet"
                GoveeController.Connection.CONNECTED -> govee.model.value?.let { "verbunden · $it" } ?: "verbunden"
                GoveeController.Connection.CONNECTING -> "verbinde…"
                GoveeController.Connection.DISCONNECTED -> "getrennt – verbinde neu"
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
    h.bind(govee.model) { refresh() }
    glow.bind(govee.color) { glow.color = it }
    glow.bind(govee.power) { glow.on = it }
    return h
}

/** Tippen = an/aus · 0,7 s halten = Reset (an, alle Segmente aus, Anzahl 0). */
internal fun Context.powerButton(govee: GoveeController, sizeDp: Int): PowerButton {
    val b = PowerButton(
        this, sizeDp,
        onHold = {
            govee.reset()
            toast("Licht zurückgesetzt")
        },
    ) { govee.togglePower() }
    b.bind(govee.power) { b.on = it }
    b.bind(govee.color) { b.ringColor = it }
    return b
}

internal fun Context.brightnessSlider(govee: GoveeController, h: Int): GlassSlider {
    val s = GlassSlider(this, h, glyph = Glyph.SUN) { govee.setBrightness(it) }
    s.bind(govee.brightness) { s.value = it }
    return s
}

internal fun Context.hueSlider(govee: GoveeController, h: Int): GlassSlider {
    val s = GlassSlider(this, h, RAINBOW) { v ->
        govee.setColor(Color.HSVToColor(floatArrayOf((v * 360f).coerceIn(0f, 359.9f), 1f, 1f)))
    }
    s.value = hueOf(govee.color.value) / 360f
    return s
}

internal fun Context.presetRow(govee: GoveeController, sizeDp: Int, hue: GlassSlider?): View {
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

/** Segment-Leiste, verbunden mit dem Licht: zeigt die echten Segmentfarben, Modus "Alle" oder "Einzeln". */
internal fun Context.segmentBar(govee: GoveeController, heightDp: Int): SegmentBar {
    val bar = SegmentBar(this)
    bar.layoutParams = lp(MATCH, dp(heightDp))
    bar.onCount = { govee.setCount(it) }
    bar.onPaint = { govee.paintSegment(it) }
    bar.onClear = { govee.clearSegment(it) }
    bar.bind(govee.segments) { bar.colors = it }
    bar.bind(govee.power) { bar.dimmed = !it }
    bar.bind(govee.segMode) { bar.single = it == GoveeController.SegMode.SINGLE }
    return bar
}

/** Farbwähler: "Alle" färbt die leuchtenden Segmente, "Einzeln" wählt die Pinselfarbe. */
internal fun Context.colorPicker(govee: GoveeController, heightDp: Int): ColorStrip =
    ColorStrip(this, heightDp) { c -> govee.setColor(c) }

/** Kleine Umschaltung "Alle | Einzeln" für RGBIC-Leisten. */
internal fun Context.segModeSwitch(govee: GoveeController, heightDp: Int = 34, textSp: Float = 12.5f): Segmented {
    val modes = GoveeController.SegMode.entries
    val seg = Segmented(this, listOf("Alle", "Einzeln"), textSp = textSp, heightDp = heightDp) { i -> govee.setSegMode(modes[i]) }
    seg.bind(govee.segMode) { seg.selected = modes.indexOf(it) }
    return seg
}

/**
 * Licht-Widget. Ist die Leiste erkannt, ist es kompakt:
 * Ein/Aus (halten = Reset) · Helligkeit · 15-Segment-Leiste · Farbwähler. Alles Weitere im Panel.
 */
fun Context.lightWidget(size: WidgetSize, govee: GoveeController, onOpen: () -> Unit): View {
    val small = size == WidgetSize.S
    val card = glassCard(if (small) 16 else 20)
    card.addView(lightHeader(govee, gear = !small, onOpen = onOpen))
    card.addView(flexSpace())

    val body = column()
    body.addView(
        row(
            powerButton(govee, if (small) 48 else 58),
            brightnessSlider(govee, if (small) 40 else 44).also { it.layoutParams = lp(0, WRAP, 1f) },
            spacingDp = 12,
        ),
    )
    // Segmente: nur bei RGBIC-Leisten (Alle/Einzeln umschalten im Panel)
    val segBox = column()
    segBox.addView(segmentBar(govee, if (small) 26 else 32), lp(MATCH, dp(if (small) 26 else 32)).margins(t = dp(if (small) 10 else 14)))
    segBox.bind(govee.segmented) { segBox.visibility = if (it) View.VISIBLE else View.GONE }
    body.addView(segBox)
    body.addView(colorPicker(govee, if (small) 30 else 34), lp().margins(t = dp(if (small) 10 else 12)))

    val setup = column().apply {
        addView(label("Noch kein Licht verbunden.", 14f, C.TEXT2, lines = 2))
        addView(glassButton("Licht einrichten", true, onOpen), lp(WRAP, WRAP).margins(t = dp(10)))
    }
    card.addView(body)
    card.addView(setup)
    card.addView(flexSpace())
    body.bind(govee.connection) { c ->
        val none = c == GoveeController.Connection.NOT_CONFIGURED
        body.visibility = if (none) View.GONE else View.VISIBLE
        setup.visibility = if (none) View.VISIBLE else View.GONE
    }
    return card
}

// ------------------------------------------------------------------ AirPods

private val SHORT_NOISE = mapOf(
    AirPodsController.Noise.OFF to "Aus",
    AirPodsController.Noise.TRANSPARENCY to "Transp.",
    AirPodsController.Noise.ADAPTIVE to "Adaptiv",
    AirPodsController.Noise.ANC to "ANC",
)

private fun Context.airPodsHeader(airpods: AirPodsController, gear: Boolean, onOpen: () -> Unit): View {
    val icon = AirPodsGlyph(this).apply { layoutParams = lp(dp(30), dp(30)) }
    val h = Header(this, "AirPods", icon, gear, onOpen)
    fun refresh() {
        val name = airpods.deviceName.value
        val model = airpods.model.value
        h.title.text = name ?: model ?: "AirPods"
        val c = airpods.audioConnected.value
        h.setStatus(
            when {
                c -> if (model != null && model != name) "verbunden · $model" else "verbunden"
                name == null -> "nicht gekoppelt"
                else -> "nicht verbunden"
            },
            if (c) C.GREEN else C.TEXT3,
        )
    }
    h.bind(airpods.deviceName) { refresh() }
    h.bind(airpods.audioConnected) { refresh() }
    h.bind(airpods.model) { refresh() }
    return h
}

/** Ein Akku-Ring mit Prozentzahl (Punktmatrix) darunter. */
private fun Context.ringItem(part: BatteryRing.Part, ringDp: Int, airpods: AirPodsController): LinearLayout {
    val ring = BatteryRing(this, part)
    val pct = label("–", 15f, C.TEXT2, display = true).apply { gravity = Gravity.CENTER }
    ring.bind(airpods.battery) { b ->
        val split = airpods.podModel.value?.splitBuds != false
        val (p, ch) = when (part) {
            // Over-Ear (AirPods Max): ein gemeinsamer Akku
            BatteryRing.Part.LEFT -> (if (split) b?.left else b?.left ?: b?.right) to (b?.chargingLeft == true || (!split && b?.chargingRight == true))
            BatteryRing.Part.RIGHT -> b?.right to (b?.chargingRight == true)
            BatteryRing.Part.CASE -> b?.case to (b?.chargingCase == true)
        }
        ring.percent = p
        ring.charging = ch
        pct.text = p?.let { "$it%" } ?: "–"
        pct.setTextColor(if (p != null) C.TEXT else C.TEXT3)
    }
    if (part != BatteryRing.Part.CASE) {
        ring.bind(airpods.inEar) { e ->
            val left = part == BatteryRing.Part.LEFT
            ring.inEar = if (left) e?.left == true else e?.right == true
            ring.inCase = if (left) e?.leftInCase == true else e?.rightInCase == true
        }
    }
    return column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ring, lp(dp(ringDp), dp(ringDp)))
        addView(pct, lp(WRAP, WRAP).margins(t = dp(8)))
    }
}

internal fun Context.ringRow(airpods: AirPodsController, ringDp: Int): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val items = BatteryRing.Part.entries.map { part -> part to ringItem(part, ringDp, airpods).also { addView(it, lp(0, WRAP, 1f)) } }
    // AirPods Max & Co.: nur ein Akku (kein rechts/Case)
    bind(airpods.podModel) { m ->
        val split = m?.splitBuds != false
        items.forEach { (part, v) -> v.visibility = if (split || part == BatteryRing.Part.LEFT) View.VISIBLE else View.GONE }
    }
}

internal fun Context.noiseSelector(airpods: AirPodsController, short: Boolean): Segmented {
    val modes = AirPodsController.Noise.entries
    val seg = Segmented(
        this,
        modes.map { if (short) SHORT_NOISE.getValue(it) else it.label },
        textSp = if (short) 12.5f else 14f,
        heightDp = if (short) 42 else 50,
    ) { i -> airpods.setNoise(modes[i]) }
    seg.bind(airpods.noise) { n -> seg.selected = n?.let { modes.indexOf(it) } ?: -1 }
    seg.bind(airpods.controlReady) { seg.isEnabled = it }
    seg.onDisabledTap = { airpods.explainUnavailable() }
    return seg
}

internal fun Context.connectButton(airpods: AirPodsController, highlighted: Boolean): TextView {
    val b = glassButton("Verbinden", highlighted) {
        if (airpods.audioConnected.value) airpods.disconnectAudio() else airpods.connectAudio()
    }
    b.bind(airpods.audioConnected) { b.text = if (it) "Trennen" else "Verbinden" }
    return b
}

fun Context.airPodsWidget(size: WidgetSize, airpods: AirPodsController, onOpen: () -> Unit): View =
    if (size == WidgetSize.S) {
        glassCard(16).apply {
            addView(airPodsHeader(airpods, gear = false, onOpen = onOpen))
            addView(flexSpace())
            addView(ringRow(airpods, 56))
            addView(flexSpace())
            // Verbunden: Geräuschmodus direkt wählbar, sonst ein Verbinden-Knopf
            val bottom = FrameLayout(context)
            val seg = noiseSelector(airpods, short = true)
            val connect = connectButton(airpods, highlighted = true)
            bottom.addView(seg, FrameLayout.LayoutParams(MATCH, WRAP))
            bottom.addView(connect, FrameLayout.LayoutParams(MATCH, WRAP))
            fun refresh() {
                // Modus-Auswahl nur, wenn verbunden UND das Modell überhaupt ANC/Transparenz hat
                val showModes = airpods.audioConnected.value && airpods.podModel.value?.noiseControl != false
                seg.visibility = if (showModes) View.VISIBLE else View.GONE
                connect.visibility = if (showModes) View.GONE else View.VISIBLE
            }
            bottom.bind(airpods.audioConnected) { refresh() }
            bottom.bind(airpods.podModel) { refresh() }
            addView(bottom, lp())
        }
    } else {
        glassCard(20).apply {
            addView(airPodsHeader(airpods, gear = true, onOpen = onOpen))
            addView(flexSpace())
            addView(
                row(
                    ringRow(airpods, 62).also { it.layoutParams = lp(0, WRAP, 1f) },
                    connectButton(airpods, highlighted = false),
                    spacingDp = 12,
                ),
            )
            addView(flexSpace())
            val seg = noiseSelector(airpods, short = false)
            seg.bind(airpods.podModel) { m -> seg.visibility = if (m?.noiseControl == false) View.GONE else View.VISIBLE }
            addView(seg)
        }
    }

internal fun Context.toggleRow(
    text: String,
    value: StateFlow<Boolean?>,
    enabled: StateFlow<Boolean>,
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

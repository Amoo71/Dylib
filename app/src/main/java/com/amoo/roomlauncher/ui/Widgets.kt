package com.amoo.roomlauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.amoo.roomlauncher.apps.AppEntry
import com.amoo.roomlauncher.board.WidgetKind
import com.amoo.roomlauncher.board.WidgetSize
import com.amoo.roomlauncher.govee.GoveeController
import com.amoo.roomlauncher.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Baut das Widget. [onOpenLight] öffnet die große Licht-Ansicht (alle Funktionen + Einrichtung). */
fun Context.widgetView(
    kind: WidgetKind,
    size: WidgetSize,
    govee: GoveeController,
    onOpenLight: () -> Unit,
): View = when (kind) {
    WidgetKind.CLOCK -> clockWidget(size)
    WidgetKind.LIGHT -> lightWidget(size, govee, onOpenLight)
}

/** Führt [block] sofort und dann alle [periodMs] aus, solange die View im Fenster hängt. */
internal fun View.everyWhileAttached(periodMs: Long, block: () -> Unit) {
    val r = object : Runnable {
        override fun run() {
            block()
            removeCallbacks(this)
            postDelayed(this, periodMs)
        }
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = r.run()
        override fun onViewDetachedFromWindow(v: View) {
            v.removeCallbacks(r)
        }
    })
    if (isAttachedToWindow) r.run() else block()
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

/** Restzeit des Nacht-Timers, z. B. "58 min" oder "1:24 h". */
internal fun sleepLeft(at: Long): String {
    val min = ((at - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(0)
    return if (min >= 60) "%d:%02d h".format(Locale.GERMANY, min / 60, min % 60) else "$min min"
}

/** Farbe, die gerade "zählt": im Modus "Einzeln" der Pinsel, sonst die Lichtfarbe. */
private fun GoveeController.activeColor(): Int =
    if (segmented.value && segMode.value == GoveeController.SegMode.SINGLE) brush.value else color.value

/** Verbindet den Farbrad-Regler mit dem Licht (inkl. Nacht-Timer-Anzeige). */
internal fun Context.lightDial(govee: GoveeController): LightDial {
    val dial = LightDial(this)
    dial.onColor = { govee.setColor(it) }
    dial.onBrightness = {
        govee.setBrightness(it)
        if (!govee.power.value) govee.setPower(true) // Helligkeit hochziehen schaltet das Licht ein
    }
    dial.onToggle = { govee.togglePower() }
    dial.onReset = {
        govee.reset()
        toast("Licht zurückgesetzt")
    }
    dial.bind(govee.power) { dial.on = it }
    dial.bind(govee.brightness) { dial.brightness = it }
    fun refreshColor() {
        dial.color = govee.activeColor()
    }
    dial.bind(govee.color) { refreshColor() }
    dial.bind(govee.brush) { refreshColor() }
    dial.bind(govee.segMode) { refreshColor() }
    dial.bind(govee.segmented) { refreshColor() }
    fun refreshSleep() {
        val at = govee.sleepAt.value
        if (at == 0L) {
            dial.sleep = null
            dial.sleepText = null
        } else {
            val total = govee.sleepMinutes.coerceAtLeast(1) * 60_000f
            dial.sleep = ((at - System.currentTimeMillis()) / total).coerceIn(0f, 1f)
            dial.sleepText = sleepLeft(at)
        }
    }
    dial.bind(govee.sleepAt) { refreshSleep() }
    dial.everyWhileAttached(10_000) { refreshSleep() }
    return dial
}

/** Lichtschein der Karte folgt Farbe, Helligkeit und An/Aus der Leiste. */
internal fun bindGlow(card: GlowCard, govee: GoveeController) {
    fun refresh() {
        val c = if (govee.segmented.value) LightColors.average(govee.segments.value) else govee.color.value
        card.setLight(c, govee.power.value, govee.brightness.value)
    }
    card.bind(govee.segments) { refresh() }
    card.bind(govee.color) { refresh() }
    card.bind(govee.power) { refresh() }
    card.bind(govee.brightness) { refresh() }
    card.bind(govee.segmented) { refresh() }
}

/** Status als Punkt + Text: verbunden / verbinde… / getrennt / nicht eingerichtet. */
internal fun Context.lightStatus(govee: GoveeController, sp: Float): LinearLayout {
    val dot = dot(C.TEXT3, 7)
    val text = label("", sp, C.TEXT2)
    val r = row(dot, text, spacingDp = 6)
    fun refresh() {
        val (t, c) = when (govee.connection.value) {
            GoveeController.Connection.NOT_CONFIGURED -> "nicht eingerichtet" to C.TEXT3
            GoveeController.Connection.CONNECTING -> "verbinde…" to C.ORANGE
            GoveeController.Connection.DISCONNECTED -> "getrennt" to C.RED
            GoveeController.Connection.CONNECTED ->
                (if (govee.power.value) "an" else "aus") + (govee.model.value?.let { " · $it" } ?: "") to C.GREEN
        }
        text.text = t
        (dot.background as? RoundDrawable)?.color = c
        dot.invalidate()
    }
    r.bind(govee.connection) { refresh() }
    r.bind(govee.power) { refresh() }
    r.bind(govee.model) { refresh() }
    return r
}

/** Mond-Pille mit Restzeit – nur sichtbar, wenn der Nacht-Timer läuft. */
internal fun Context.sleepPill(govee: GoveeController, sp: Float): LinearLayout {
    val icon = IconView(this, Glyph.MOON, LightColors.MOON).apply { layoutParams = lp(dp(sp + 1), dp(sp + 1)) }
    val text = label("", sp, LightColors.MOON, bold = true)
    val pill = row(icon, text, spacingDp = 6).apply {
        background = RoundDrawable(context, 16f, 0x26B9A8FF, 0x55B9A8FF)
        setPadding(dp(10), dp(5), dp(12), dp(5))
    }
    fun refresh() {
        val at = govee.sleepAt.value
        pill.visibility = if (at == 0L) View.GONE else View.VISIBLE
        if (at != 0L) text.text = sleepLeft(at)
    }
    pill.bind(govee.sleepAt) { refresh() }
    pill.everyWhileAttached(10_000) { refresh() }
    return pill
}

/** LED-Leiste, verbunden mit dem Licht: echte Segmentfarben, Modus "Alle" oder "Einzeln". */
internal fun Context.ledStrip(govee: GoveeController): LedStrip {
    val bar = LedStrip(this)
    bar.onCount = { govee.setCount(it) }
    bar.onPaint = { govee.paintSegment(it) }
    bar.onClear = { govee.clearSegment(it) }
    bar.bind(govee.segments) { bar.colors = it }
    bar.bind(govee.power) { bar.dimmed = !it }
    bar.bind(govee.segMode) { bar.single = it == GoveeController.SegMode.SINGLE }
    return bar
}

/** Kleine Umschaltung "Alle | Einzeln" für RGBIC-Leisten. */
internal fun Context.segModeSwitch(govee: GoveeController, heightDp: Int = 34, textSp: Float = 12.5f): Segmented {
    val modes = GoveeController.SegMode.entries
    val seg = Segmented(this, listOf("Alle", "Einzeln"), textSp = textSp, heightDp = heightDp) { i -> govee.setSegMode(modes[i]) }
    seg.bind(govee.segMode) { seg.selected = modes.indexOf(it) }
    return seg
}

/** Farbtupfer-Reihe; der zur aktuellen Farbe passende ist markiert. */
internal fun Context.swatchRow(govee: GoveeController, sizeDp: Int, spacingDp: Int = 6): View {
    val swatches = LightColors.PRESETS.map { c -> Swatch(this, c, sizeDp) { govee.setColor(it) } }
    val scroller = hScroll(*swatches.toTypedArray(), spacingDp = spacingDp)
    fun refresh() {
        val cur = govee.activeColor()
        swatches.forEach { it.picked = it.color == cur }
    }
    scroller.bind(govee.color) { refresh() }
    scroller.bind(govee.brush) { refresh() }
    scroller.bind(govee.segMode) { refresh() }
    return scroller
}

private fun Context.lightSetup(onOpen: () -> Unit): LinearLayout = column().apply {
    gravity = Gravity.CENTER
    addView(label("Noch kein Licht verbunden.", 14f, C.TEXT2, lines = 2).apply { gravity = Gravity.CENTER })
    addView(glassButton("Licht einrichten", true, onOpen), lp(WRAP, WRAP).apply {
        topMargin = dp(12)
        gravity = Gravity.CENTER_HORIZONTAL
    })
}

/**
 * Licht-Widget: Farbrad-Regler (außen Farbe, innen Helligkeit, Mitte an/aus · halten = Reset),
 * leuchtende LED-Leiste und eine Karte, die in der Lichtfarbe glüht.
 * Groß (M) zusätzlich: Prozent, Farbname, Nacht-Timer, Alle/Einzeln und Farbtupfer.
 */
fun Context.lightWidget(size: WidgetSize, govee: GoveeController, onOpen: () -> Unit): View {
    val small = size == WidgetSize.S
    val card = GlowCard(this, 30f)
    bindGlow(card, govee)
    val dial = lightDial(govee)
    card.anchor = dial

    val body: View
    if (small) {
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val top = row(
            label("Licht", 17f, display = true).also { it.layoutParams = lp(0, WRAP, 1f) },
            sleepPill(govee, 11.5f),
            lightStatus(govee, 11.5f),
            spacingDp = 8,
        ).apply { pressable(onClick = onOpen) }
        val strip = ledStrip(govee)
        strip.bind(govee.segmented) { strip.visibility = if (it) View.VISIBLE else View.GONE }
        body = column().apply {
            addView(top)
            addView(dial, lp(MATCH, 0, 1f).margins(t = dp(6), b = dp(4)))
            addView(strip, lp(MATCH, dp(24)).margins(t = dp(6)))
        }
    } else {
        card.setPadding(dp(18), dp(18), dp(20), dp(18))
        val pct = label("", 44f, display = true)
        val pctSign = label("%", 18f, C.TEXT2, display = true)
        val colorName = label("", 15f, C.TEXT2, bold = true)
        fun refreshValue() {
            val on = govee.power.value
            pct.text = if (on) "${(govee.brightness.value * 100).toInt().coerceAtLeast(1)}" else "AUS"
            pctSign.visibility = if (on) View.VISIBLE else View.GONE
            val segOff = govee.segmented.value && LightColors.average(govee.segments.value) == null
            colorName.text = if (segOff && govee.segMode.value == GoveeController.SegMode.ALL) "alle Segmente aus" else LightColors.name(govee.activeColor())
        }
        pct.bind(govee.power) { refreshValue() }
        pct.bind(govee.brightness) { refreshValue() }
        pct.bind(govee.color) { refreshValue() }
        pct.bind(govee.brush) { refreshValue() }
        pct.bind(govee.segments) { refreshValue() }
        pct.bind(govee.segMode) { refreshValue() }

        val header = row(
            column().apply {
                addView(label("Licht", 22f, display = true))
                addView(lightStatus(govee, 12.5f), lp().margins(t = dp(5)))
                layoutParams = lp(0, WRAP, 1f)
                pressable(onClick = onOpen)
            },
            glassIconButton(Glyph.GEAR, 36, onClick = onOpen),
        )
        val value = row(
            row(pct, pctSign, spacingDp = 3, center = false).apply { gravity = Gravity.BOTTOM },
            column().apply {
                addView(colorName)
                addView(sleepPill(govee, 12f), lp(WRAP, WRAP).margins(t = dp(6)))
                layoutParams = lp(0, WRAP, 1f).margins(l = dp(14))
            },
        )
        val segBox = column().apply {
            addView(
                row(
                    label("SEGMENTE", 11.5f, C.TEXT3, bold = true).also { it.layoutParams = lp(0, WRAP, 1f) },
                    segModeSwitch(govee, 30, 12f).also { it.layoutParams = lp(dp(140), WRAP) },
                ),
            )
            addView(ledStrip(govee), lp(MATCH, dp(28)).margins(t = dp(8)))
        }
        segBox.bind(govee.segmented) { segBox.visibility = if (it) View.VISIBLE else View.GONE }
        val right = column().apply {
            addView(header)
            addView(flexSpace())
            addView(value)
            addView(flexSpace())
            addView(segBox)
            addView(swatchRow(govee, 30), lp().margins(t = dp(12)))
        }
        body = row(
            dial.also { it.layoutParams = lp(WRAP, MATCH) },
            right.also { it.layoutParams = lp(0, MATCH, 1f).margins(l = dp(18)) },
            center = false,
        )
    }
    val setup = lightSetup(onOpen)
    card.addView(body, lp(MATCH, MATCH, 1f))
    card.addView(setup, lp(MATCH, MATCH, 1f))
    card.bind(govee.connection) { c ->
        val none = c == GoveeController.Connection.NOT_CONFIGURED
        body.visibility = if (none) View.GONE else View.VISIBLE
        setup.visibility = if (none) View.VISIBLE else View.GONE
    }
    return card
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

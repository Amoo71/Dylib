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
import kotlin.math.roundToInt

/** Baut das Widget. [onOpenLight] öffnet die große Licht-Ansicht (alle Funktionen + Einrichtung). */
fun Context.widgetView(
    kind: WidgetKind,
    size: WidgetSize,
    govee: GoveeController,
    glass: Boolean,
    onOpenLight: () -> Unit,
): View = when (kind) {
    WidgetKind.CLOCK -> clockWidget(size, glass)
    WidgetKind.LIGHT -> lightWidget(size, govee, glass, onOpenLight)
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

/** Uhr – standardmäßig ohne Kasten (Punktmatrix-Ziffern schweben frei), auf Wunsch im Glas-Container. */
fun Context.clockWidget(size: WidgetSize, glass: Boolean = false): View {
    val small = size == WidgetSize.S
    // Alles mittig – waagerecht und senkrecht
    val card = column().apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        if (glass) background = GlassDrawable(context, 30f)
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
    card.addView(batteryRow(small), lp(WRAP, WRAP).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(if (small) 6 else 10)
    })
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

/** Tablet-Akku: kleines Batterie-Symbol + Prozent, mittig unter der Uhr. Laden = grüner Blitz, ≤ 15 % = rot. */
private fun Context.batteryRow(small: Boolean): LinearLayout {
    val icon = BatteryGlyph(this)
    val pct = label("", if (small) 11.5f else 13f, C.TEXT2).apply { setShadowLayer(dpf(6), 0f, dpf(1), 0x8C000000.toInt()) }
    val row = row(icon.also { it.layoutParams = lp(dp(if (small) 20 else 23), dp(if (small) 10 else 12)) }, pct, spacingDp = 6)
    val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val p = if (level < 0) -1 else level * 100 / scale
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            icon.set(p, charging)
            pct.text = if (p < 0) "" else "$p %"
            pct.setTextColor(if (p in 0..15 && !charging) C.RED else C.TEXT2)
            row.visibility = if (p < 0) View.GONE else View.VISIBLE
        }
    }
    row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            // Sticky-Broadcast: liefert sofort den aktuellen Stand
            runCatching { registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) }
                .getOrNull()?.let { receiver.onReceive(this@batteryRow, it) }
        }

        override fun onViewDetachedFromWindow(v: View) {
            runCatching { unregisterReceiver(receiver) }
        }
    })
    return row
}

/** Kleines Batterie-Symbol mit Füllstand; beim Laden grün mit Blitz. */
private class BatteryGlyph(context: Context) : View(context) {
    private var percent = 0
    private var charging = false
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C.TEXT2
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = android.graphics.Path()
    private val r = RectF()

    fun set(p: Int, c: Boolean) {
        if (p == percent && c == charging) return
        percent = p
        charging = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val sw = maxOf(1f, h * 0.1f)
        outline.strokeWidth = sw
        val bodyW = w - h * 0.18f
        r.set(sw / 2, sw / 2, bodyW - sw / 2, h - sw / 2)
        canvas.drawRoundRect(r, h * 0.25f, h * 0.25f, outline)
        // Pol
        fill.color = C.TEXT2
        r.set(bodyW + sw * 0.3f, h * 0.32f, w, h * 0.68f)
        canvas.drawRoundRect(r, h * 0.1f, h * 0.1f, fill)
        // Füllstand
        val p = percent.coerceIn(0, 100) / 100f
        fill.color = when {
            charging -> C.GREEN
            percent <= 15 -> C.RED
            else -> C.TEXT
        }
        val inset = sw * 1.6f
        r.set(inset, inset, inset + (bodyW - inset * 2) * p, h - inset)
        if (p > 0f) canvas.drawRoundRect(r, h * 0.12f, h * 0.12f, fill)
        if (charging) {
            val cx = bodyW / 2
            val cy = h / 2
            bolt.reset()
            bolt.moveTo(cx + h * 0.08f, cy - h * 0.42f)
            bolt.lineTo(cx - h * 0.22f, cy + h * 0.06f)
            bolt.lineTo(cx - h * 0.01f, cy + h * 0.06f)
            bolt.lineTo(cx - h * 0.08f, cy + h * 0.42f)
            bolt.lineTo(cx + h * 0.22f, cy - h * 0.06f)
            bolt.lineTo(cx + h * 0.01f, cy - h * 0.06f)
            bolt.close()
            fill.color = 0xFF0B0B0D.toInt()
            canvas.drawPath(bolt, fill)
        }
    }
}

// ------------------------------------------------------------------ Licht (Lumen-Oberfläche)

/** Restzeit des Nacht-Timers, z. B. "58 min" oder "1:24 h". */
internal fun sleepLeft(at: Long): String {
    val min = ((at - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(0)
    return if (min >= 60) "%d:%02d h".format(Locale.GERMANY, min / 60, min % 60) else "$min min"
}

/** Akzentfarbe = aktuell gewählte Farbe: im Modus "Einzeln" der Pinsel, sonst die Lichtfarbe. */
internal fun GoveeController.activeColor(): Int =
    if (segmented.value && segMode.value == GoveeController.SegMode.SINGLE) brush.value else color.value

/** Bindet [block] an alles, was die Akzentfarbe ändert. */
private fun View.bindAccent(govee: GoveeController, block: (Int) -> Unit) {
    bind(govee.color) { block(govee.activeColor()) }
    bind(govee.brush) { block(govee.activeColor()) }
    bind(govee.segMode) { block(govee.activeColor()) }
    bind(govee.segmented) { block(govee.activeColor()) }
}

/** Statuszeile der Bühne: Punkt + "Verbunden"/"Sucht…" links, Modus-Chip rechts. */
internal fun Context.lumenStatusRow(govee: GoveeController, onOpen: (() -> Unit)?): LinearLayout {
    val dot = StatusDot(this)
    val text = roundLabel("", 11f, C.white(0.6f), 600)
    val chip = ModeChip(this) {
        govee.setSegMode(if (govee.segMode.value == GoveeController.SegMode.SINGLE) GoveeController.SegMode.ALL else GoveeController.SegMode.SINGLE)
    }
    chip.bind(govee.segMode) { chip.single = it == GoveeController.SegMode.SINGLE }
    chip.bind(govee.segmented) { chip.visibility = if (it) View.VISIBLE else View.GONE }
    val left = row(dot.also { it.layoutParams = lp(dp(14), dp(14)).margins(l = dp(1)) }, text, spacingDp = 2).apply {
        if (onOpen != null) pressable(onClick = onOpen)
    }
    fun refresh() {
        val (t, c) = when (govee.connection.value) {
            GoveeController.Connection.CONNECTED -> "Verbunden" to C.GREEN
            GoveeController.Connection.NOT_CONFIGURED -> "Nicht eingerichtet" to C.TEXT3
            else -> "Sucht…" to C.ORANGE
        }
        text.text = t
        dot.color = c
    }
    left.bind(govee.connection) { refresh() }
    return row(left, flexSpace().also { it.layoutParams = lp(0, 1, 1f) }, chip.also { it.layoutParams = lp(WRAP, WRAP).margins(r = dp(2)) })
}

/** Bühne: 3D-LED-Ring (mit Plattform und Power-Knopf) + Statuszeile. */
internal fun Context.lumenStage(govee: GoveeController, board: Boolean, onOpen: (() -> Unit)? = null): FrameLayout {
    val ring = LumenRing(this, board)
    ring.onCount = { govee.setCount(it) }
    ring.onSelect = { govee.selectSegment(it) }
    ring.onPaint = { govee.paintSegment(it) }
    ring.onClear = { govee.clearSegment(it) }
    ring.onToggle = { govee.togglePower() }
    ring.onReset = {
        govee.reset()
        toast("Alle Segmente zurückgesetzt")
    }
    // Einfache (nicht segmentierte) Leisten: alle 15 Felder in der Lichtfarbe
    fun refreshSegments() {
        ring.segments = if (govee.segmented.value) govee.segments.value else List(15) { govee.color.value and 0xFFFFFF }
    }
    ring.bind(govee.segments) { refreshSegments() }
    ring.bind(govee.segmented) { refreshSegments() }
    ring.bind(govee.color) { refreshSegments() }
    ring.bind(govee.power) { ring.power = it }
    ring.bind(govee.brightness) { ring.brightness = it }
    ring.bind(govee.segMode) { ring.single = it == GoveeController.SegMode.SINGLE }
    ring.bind(govee.selected) { ring.selected = it }
    ring.bindAccent(govee) { ring.accent = it }
    val statusH = dp(28)
    return FrameLayout(this).apply {
        addView(ring, FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = statusH })
        addView(lumenStatusRow(govee, onOpen), FrameLayout.LayoutParams(MATCH, statusH, Gravity.BOTTOM))
    }
}

/** Farbkarte: Titel + Hex-Code, Farbrad, Swatch mit "Alle aktiven"/"Pinselfarbe". */
internal fun Context.colorCard(govee: GoveeController, wheelDp: Int, swatchDp: Int): LinearLayout {
    val card = lumenPanel()
    val hex = roundLabel("", 15f)
    card.addView(row(lumenTitle("Farbe").also { it.layoutParams = lp(0, WRAP, 1f) }, hex))
    val wheel = HueWheel(this, wheelDp) { govee.setColor(it) }
    val swatch = LumenSwatch(this, swatchDp)
    val caption = roundLabel("", 13f, C.white(0.6f), 600).apply { gravity = Gravity.CENTER }
    card.bindAccent(govee) { c ->
        wheel.setColor(c)
        swatch.color = c or 0xFF000000.toInt()
        hex.text = LightColors.hex(c)
        caption.text = if (govee.segmented.value && govee.segMode.value == GoveeController.SegMode.SINGLE) "Pinselfarbe" else "Alle aktiven"
    }
    val right = column().apply {
        gravity = Gravity.CENTER
        addView(swatch, lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(caption, lp(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = -dp(6)
        })
    }
    // Das Farbrad hat Rand für Glow und Regler – optisch an die Kartenkante rücken
    card.addView(
        row(wheel.also { it.layoutParams = lp(WRAP, WRAP).margins(l = -dp(18)) }, right.also { it.layoutParams = lp(0, WRAP, 1f) }),
        lp().margins(t = -dp(6), b = -dp(14)),
    )
    return card
}

/** Helligkeitskarte: Sonne · Glow-Leiste · "NN %". Hochziehen schaltet das Licht ein. */
internal fun Context.brightnessCard(govee: GoveeController): LinearLayout {
    val card = lumenPanel()
    card.addView(lumenTitle("Helligkeit"))
    val bar = GlowBar(this) {
        govee.setBrightness(it)
        if (!govee.power.value) govee.setPower(true)
    }
    val pct = roundLabel("", 17f).apply {
        gravity = Gravity.END
        minWidth = dp(58)
    }
    bar.bind(govee.brightness) {
        bar.value = it
        pct.text = "${(it * 100).roundToInt().coerceAtLeast(1)} %"
    }
    card.addView(
        row(
            SunIcon(this).apply { layoutParams = lp(dp(22), dp(22)) },
            bar.also { it.layoutParams = lp(0, WRAP, 1f).margins(l = dp(10)) },
            pct.also { it.layoutParams = lp(WRAP, WRAP).margins(l = dp(10)) },
        ),
        lp().margins(t = dp(4)),
    )
    return card
}

private fun Context.lightSetup(onOpen: () -> Unit): LinearLayout = column().apply {
    gravity = Gravity.CENTER
    addView(roundLabel("Noch kein Licht verbunden.", 14f, C.TEXT2, 600).apply { gravity = Gravity.CENTER })
    addView(glassButton("Licht einrichten", true, onOpen), lp(WRAP, WRAP).apply {
        topMargin = dp(12)
        gravity = Gravity.CENTER_HORIZONTAL
    })
}

/** Akzent-Schein der Karte folgt der gewählten Farbe; bei ausgeschaltetem Licht schwächer. */
internal fun bindLumenGlow(view: View, bg: LumenBackground, govee: GoveeController) {
    view.bindAccent(govee) { bg.setAccent(it) }
    view.bind(govee.power) { bg.level = if (it) 1f else 0.35f }
}

/**
 * Licht-Widget im Stil der Lumen-App:
 *  - Klein: die Bühne mit 3D-LED-Ring, Plattform, Power-Knopf und Statuszeile.
 *  - Mittel: Bühne + Farbkarte (Farbrad) + Helligkeitskarte.
 * [glass] = false → ohne Glas-Container, komplett durchsichtig.
 */
fun Context.lightWidget(size: WidgetSize, govee: GoveeController, glass: Boolean, onOpen: () -> Unit): View {
    val card = LumenCard(this, glass)
    bindLumenGlow(card, card.glow, govee)
    val body: View = if (size == WidgetSize.S) {
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        lumenStage(govee, board = true, onOpen = onOpen)
    } else {
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        val right = column().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(colorCard(govee, 92, 40))
            addView(brightnessCard(govee), lp().margins(t = dp(10)))
        }
        row(
            lumenStage(govee, board = true, onOpen = onOpen).also { it.layoutParams = lp(0, MATCH, 1.05f) },
            right.also { it.layoutParams = lp(0, MATCH, 1f).margins(l = dp(12)) },
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

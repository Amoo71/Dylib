package com.amoo.roomlauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ------------------------------------------------------------------ Farben

object LightColors {
    /** Geschlossener Farbring: Warm → Warmweiß → Weiß → Regenbogen → zurück zu Warm. */
    val STOPS = intArrayOf(
        0xFFFF8A2B.toInt(), 0xFFFFC890.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF2B2B.toInt(), 0xFFFFE23A.toInt(),
        0xFF35E05C.toInt(), 0xFF2EE3E6.toInt(), 0xFF2F6BFF.toInt(), 0xFFC23BFF.toInt(), 0xFFFF8A2B.toInt(),
    )
    val POS = floatArrayOf(0f, 0.07f, 0.14f, 0.23f, 0.36f, 0.49f, 0.61f, 0.74f, 0.88f, 1f)

    val PRESETS = intArrayOf(
        0xFFFFB46B.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD60A.toInt(),
        0xFF34C759.toInt(), 0xFF00C7BE.toInt(), 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(),
        0xFFFF2D87.toInt(),
    )

    /** Mondlicht-Lila für den Nacht-Timer. */
    val MOON = 0xFFB9A8FF.toInt()

    fun colorAt(f: Float): Int {
        val x = ((f % 1f) + 1f) % 1f
        for (i in 0 until POS.size - 1) {
            if (x <= POS[i + 1]) return blend(STOPS[i], STOPS[i + 1], (x - POS[i]) / (POS[i + 1] - POS[i]))
        }
        return STOPS.last()
    }

    /** Position (0..1) auf dem Ring, die der Farbe am nächsten kommt. */
    fun fractionOf(argb: Int): Float {
        var best = 0f
        var bestD = Int.MAX_VALUE
        for (i in 0 until 360) {
            val f = i / 360f
            val c = colorAt(f)
            val dr = Color.red(c) - Color.red(argb)
            val dg = Color.green(c) - Color.green(argb)
            val db = Color.blue(c) - Color.blue(argb)
            val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (d < bestD) {
                bestD = d
                best = f
            }
        }
        return best
    }

    fun name(argb: Int): String {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        val h = hsv[0]
        val s = hsv[1]
        return when {
            hsv[2] < 0.05f -> "Aus"
            s < 0.12f -> "Weiß"
            s < 0.62f && h in 15f..50f -> "Warmweiß"
            h < 14f || h >= 340f -> "Rot"
            h < 42f -> "Orange"
            h < 68f -> "Gelb"
            h < 160f -> "Grün"
            h < 196f -> "Türkis"
            h < 250f -> "Blau"
            h < 290f -> "Lila"
            else -> "Pink"
        }
    }

    fun withAlpha(c: Int, a: Int): Int = (c and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Durchschnittsfarbe der leuchtenden Segmente (für den Lichtschein), null = alle aus. */
    fun average(rgbs: List<Int>): Int? {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (c in rgbs) {
            if (c and 0xFFFFFF == 0) continue
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
        }
        if (n == 0) return null
        return Color.rgb(r / n, g / n, b / n)
    }
}

// ------------------------------------------------------------------ Farbrad-Regler

/**
 * Der Licht-Regler:
 *  - außen ein Farbring (ziehen oder tippen wählt die Farbe),
 *  - innen ein Bogen für die Helligkeit (mit leichtem Einrasten alle 10 %),
 *  - in der Mitte die leuchtende Kugel: tippen = an/aus, halten = Reset,
 *  - optional ein Mondring für den Nacht-Timer.
 * Die Berührung gehört dem Regler – das Board springt dabei weder in Bearbeiten noch in die Vorschau.
 */
class LightDial(context: Context) : View(context) {
    var onColor: (Int) -> Unit = {}
    var onBrightness: (Float) -> Unit = {}
    var onToggle: () -> Unit = {}
    var onReset: () -> Unit = {}

    var color: Int = Color.WHITE
        set(v) {
            if (field == v) return
            field = v
            if (zone != Zone.RING) ringPos = LightColors.fractionOf(v)
            updateOrb()
            invalidate()
        }

    var brightness: Float = 1f
        set(v) {
            if (zone == Zone.ARC) return // während des Ziehens zählt der Finger
            showBrightness(v)
        }

    private fun showBrightness(v: Float) {
        val c = v.coerceIn(0f, 1f)
        if (brightnessShown != c) {
            brightnessShown = c
            invalidate()
        }
    }

    private var brightnessShown = 1f

    var on: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            glowAnim?.cancel()
            glowAnim = ValueAnimator.ofFloat(glow, if (v) 1f else 0f).apply {
                duration = 320
                interpolator = EASE_OUT
                addUpdateListener {
                    glow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    /** Nacht-Timer: verbleibender Anteil (1 → 0) oder null, und der Text in der Lücke unten. */
    var sleep: Float? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var sleepText: String? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private enum class Zone { NONE, RING, ARC, ORB }

    private var zone = Zone.NONE
    private var ringPos = LightColors.fractionOf(Color.WHITE)
    private var glow = 0f
    private var glowAnim: ValueAnimator? = null
    private var orbScale = 1f
    private var held = false
    private var lastTick = -1

    // Geometrie
    private var cx = 0f
    private var cy = 0f
    private var s = 0f
    private var ringW = 0f
    private var rRing = 0f
    private var arcW = 0f
    private var rArc = 0f
    private var rOrb = 0f

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val arcTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = C.white(0.12f)
    }
    private val arcGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbOn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.07f) }
    private val orbEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C.white(0.18f)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.display
        textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium
        textAlign = Paint.Align.LEFT
    }
    private val moonRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = LightColors.MOON
    }
    private val moonFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LightColors.MOON }
    private val moon = Path()
    private val oval = RectF()
    private val tmpPath = Path()

    private val hold = Runnable {
        held = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        animateOrb(1.1f) { animateOrb(1f) }
        onReset()
    }
    private var orbAnim: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val wm = MeasureSpec.getMode(widthMeasureSpec)
        val hm = MeasureSpec.getMode(heightMeasureSpec)
        val fallback = dp(220)
        when {
            wm == MeasureSpec.EXACTLY && hm == MeasureSpec.EXACTLY -> setMeasuredDimension(w, h)
            wm == MeasureSpec.EXACTLY -> setMeasuredDimension(w, if (hm == MeasureSpec.AT_MOST) min(w, h) else w)
            hm == MeasureSpec.EXACTLY -> setMeasuredDimension(if (wm == MeasureSpec.AT_MOST) min(w, h) else h, h)
            else -> {
                val sz = if (wm == MeasureSpec.AT_MOST && hm == MeasureSpec.AT_MOST) min(w, h) else fallback
                setMeasuredDimension(sz, sz)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        s = min(w, h).toFloat()
        val pad = dp(5)
        ringW = s * 0.095f
        rRing = s / 2 - pad - ringW / 2
        arcW = s * 0.05f
        rArc = rRing - ringW / 2 - s * 0.065f - arcW / 2
        rOrb = rArc - arcW / 2 - s * 0.07f

        ring.strokeWidth = ringW
        ring.shader = SweepGradient(cx, cy, LightColors.STOPS, LightColors.POS).apply {
            setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
        }
        ringEdge.strokeWidth = dp(1).toFloat()
        thumbRing.strokeWidth = max(2f, s * 0.012f)
        arcTrack.strokeWidth = arcW
        arcFill.strokeWidth = arcW
        arcGlow.strokeWidth = arcW * 2.6f
        orbEdge.strokeWidth = dp(1).toFloat()
        text.textSize = rOrb * 0.36f
        small.textSize = max(dp(10).toFloat(), s * 0.048f)
        moonRing.strokeWidth = max(2f, s * 0.011f)
        updateOrb()
    }

    private fun updateOrb() {
        if (s == 0f) return
        val c = color or 0xFF000000.toInt()
        halo.shader = RadialGradient(
            cx, cy, rOrb * 1.55f,
            intArrayOf(LightColors.withAlpha(c, 150), LightColors.withAlpha(c, 60), 0),
            floatArrayOf(0.55f, 0.75f, 1f), Shader.TileMode.CLAMP,
        )
        orbOn.shader = RadialGradient(
            cx, cy - rOrb * 0.25f, rOrb * 1.25f,
            intArrayOf(blend(c, Color.WHITE, 0.7f), blend(c, Color.WHITE, 0.25f), c),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        if (s == 0f) return
        val c = color or 0xFF000000.toInt()

        // Farbring mit feiner Glaskante
        canvas.drawCircle(cx, cy, rRing, ring)
        ringEdge.color = C.white(0.28f)
        canvas.drawCircle(cx, cy, rRing + ringW / 2, ringEdge)
        ringEdge.color = 0x40000000
        canvas.drawCircle(cx, cy, rRing - ringW / 2, ringEdge)
        val ta = Math.toRadians((ringPos * 360f - 90f).toDouble())
        val tx = cx + rRing * cos(ta).toFloat()
        val ty = cy + rRing * sin(ta).toFloat()
        val tr = ringW * 0.68f
        canvas.drawCircle(tx, ty + dp(1), tr + dp(1), thumbShadow)
        thumbFill.color = LightColors.colorAt(ringPos)
        canvas.drawCircle(tx, ty, tr, thumbFill)
        canvas.drawCircle(tx, ty, tr, thumbRing)

        // Helligkeits-Bogen: 135° → 405°, unten eine Lücke
        oval.set(cx - rArc, cy - rArc, cx + rArc, cy + rArc)
        canvas.drawArc(oval, 135f, 270f, false, arcTrack)
        val sweep = 270f * brightnessShown
        val lit = blend(c, Color.WHITE, 0.2f)
        if (glow > 0.01f && sweep > 0.5f) {
            arcGlow.color = LightColors.withAlpha(lit, (70 * glow).toInt())
            canvas.drawArc(oval, 135f, sweep, false, arcGlow)
        }
        arcFill.color = blend(C.white(0.5f), lit, glow)
        if (sweep > 0.5f) canvas.drawArc(oval, 135f, sweep, false, arcFill)
        val ka = Math.toRadians((135f + sweep).toDouble())
        canvas.drawCircle(cx + rArc * cos(ka).toFloat(), cy + rArc * sin(ka).toFloat(), arcW * 0.62f, knob)

        // Nacht-Timer: Mondring um die Kugel + Restzeit in der Lücke
        sleep?.let { f ->
            val rm = rOrb + s * 0.035f
            oval.set(cx - rm, cy - rm, cx + rm, cy + rm)
            moonRing.alpha = 60
            canvas.drawCircle(cx, cy, rm, moonRing)
            moonRing.alpha = 255
            canvas.drawArc(oval, -90f, 360f * f.coerceIn(0f, 1f), false, moonRing)
        }
        sleepText?.let { t ->
            val ty = cy + rArc + small.textSize * 0.35f
            val mr = small.textSize * 0.55f
            val tw = small.measureText(t)
            val total = mr * 2 + dp(5) + tw
            val left = cx - total / 2
            drawMoon(canvas, left + mr, ty - small.textSize * 0.35f, mr)
            small.color = LightColors.MOON
            canvas.drawText(t, left + mr * 2 + dp(5), ty, small)
        }

        // Kugel
        val r = rOrb * orbScale
        canvas.save()
        canvas.scale(orbScale, orbScale, cx, cy)
        if (glow > 0.01f) {
            halo.alpha = (255 * glow).toInt()
            canvas.drawCircle(cx, cy, rOrb * 1.55f, halo)
        }
        canvas.drawCircle(cx, cy, rOrb, orbOff)
        if (glow > 0.01f) {
            orbOn.alpha = (255 * glow).toInt()
            canvas.drawCircle(cx, cy, rOrb, orbOn)
        }
        canvas.drawCircle(cx, cy, rOrb, orbEdge)
        canvas.restore()

        // Power-Symbol + Helligkeit in Prozent
        val ink = blend(C.TEXT, 0xD9101012.toInt(), glow)
        glyph.color = ink
        val g = r * 0.42f
        glyph.strokeWidth = max(2f, g * 0.13f)
        val gy = cy - r * 0.16f
        oval.set(cx - g * 0.42f, gy - g * 0.34f, cx + g * 0.42f, gy + g * 0.5f)
        canvas.drawArc(oval, -60f, 300f, false, glyph)
        canvas.drawLine(cx, gy - g * 0.52f, cx, gy + g * 0.05f, glyph)
        text.color = ink
        text.textSize = r * 0.34f
        canvas.drawText(if (on) "${(brightnessShown * 100).toInt().coerceAtLeast(1)}%" else "AUS", cx, cy + r * 0.52f, text)
    }

    /** Kleine Mondsichel. */
    private fun drawMoon(canvas: Canvas, x: Float, y: Float, r: Float) {
        moon.reset()
        moon.addCircle(x, y, r, Path.Direction.CW)
        tmpPath.reset()
        tmpPath.addCircle(x + r * 0.55f, y - r * 0.35f, r * 0.85f, Path.Direction.CW)
        moon.op(tmpPath, Path.Op.DIFFERENCE)
        canvas.drawPath(moon, moonFill)
    }

    // ---------------------------------------------------------------- Berührung

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d = hypot(x - cx, y - cy)
                zone = when {
                    d > rRing + ringW * 0.9f -> Zone.NONE
                    d >= rRing - ringW * 0.9f -> Zone.RING
                    d >= rOrb + s * 0.02f -> Zone.ARC
                    else -> Zone.ORB
                }
                if (zone == Zone.NONE) return false // Ecken gehören der Karte (Halten → Vorschau)
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTick = -1
                when (zone) {
                    Zone.RING -> pickRing(x, y)
                    Zone.ARC -> pickArc(x, y, first = true)
                    Zone.ORB -> {
                        held = false
                        animateOrb(0.9f)
                        postDelayed(hold, 650)
                    }
                    Zone.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> when (zone) {
                Zone.RING -> pickRing(x, y)
                Zone.ARC -> pickArc(x, y, first = false)
                Zone.ORB -> if (hypot(x - cx, y - cy) > rOrb * 1.3f) {
                    removeCallbacks(hold)
                    animateOrb(1f)
                }
                Zone.NONE -> Unit
            }
            MotionEvent.ACTION_UP -> {
                if (zone == Zone.ORB) {
                    removeCallbacks(hold)
                    if (!held) {
                        animateOrb(1f)
                        if (hypot(x - cx, y - cy) <= rOrb * 1.3f) onToggle()
                    }
                } else if (zone == Zone.ARC) {
                    onBrightness(brightnessShown.coerceIn(0.01f, 1f))
                }
                zone = Zone.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (zone == Zone.ORB) animateOrb(1f)
                zone = Zone.NONE
            }
        }
        return true
    }

    private fun angle(x: Float, y: Float): Float {
        val deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        return (deg + 360f) % 360f
    }

    private fun pickRing(x: Float, y: Float) {
        val f = ((angle(x, y) + 90f) % 360f) / 360f
        ringPos = f
        val c = LightColors.colorAt(f)
        val tick = (f * 24).toInt()
        if (lastTick >= 0 && tick != lastTick) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastTick = tick
        color = c // zone == RING → die Ring-Position bleibt, wo der Finger ist
        invalidate()
        onColor(c)
    }

    private fun pickArc(x: Float, y: Float, first: Boolean) {
        val rel = (angle(x, y) - 135f + 360f) % 360f
        var v = when {
            rel <= 270f -> rel / 270f
            rel < 315f -> 1f
            else -> 0f
        }
        // Über die Lücke unten springen (100 % → 0 %) wird ignoriert
        if (!first && abs(v - brightnessShown) > 0.5f) v = if (brightnessShown > 0.5f) 1f else 0f
        v = v.coerceIn(0.01f, 1f)
        val tick = (v * 10).toInt()
        if (lastTick >= 0 && tick != lastTick) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastTick = tick
        showBrightness(v)
        onBrightness(v)
    }

    private fun animateOrb(to: Float, then: (() -> Unit)? = null) {
        orbAnim?.cancel()
        orbAnim = ValueAnimator.ofFloat(orbScale, to).apply {
            duration = if (to < 1f) 110 else 300
            interpolator = if (to < 1f) EASE_OUT else SPRING
            addUpdateListener {
                orbScale = it.animatedValue as Float
                invalidate()
            }
            if (then != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = then()
                })
            }
            start()
        }
    }
}

// ------------------------------------------------------------------ LED-Leiste

/**
 * Die 15 Segmente als leuchtende LEDs in einer dunklen Glasschiene (0 = aus).
 *  - Modus "Alle":    Tippen/Ziehen setzt die Anzahl leuchtender Segmente (ganz links = 0).
 *  - Modus "Einzeln": Tippen/Wischen malt mit dem Pinsel, Halten löscht das Segment.
 */
class LedStrip(context: Context, private val count: Int = 15) : View(context) {
    var colors: List<Int> = List(count) { 0 }
        set(v) {
            if (field == v) return
            field = v
            rebuild()
            invalidate()
        }
    var dimmed = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var single = false
    var onCount: (Int) -> Unit = {}
    var onPaint: (Int) -> Unit = {}
    var onClear: (Int) -> Unit = {}

    private val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val railEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C.white(0.12f)
    }
    private val offCell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.07f) }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.75f) }
    private val r = RectF()
    private var shaders: Array<Shader?> = arrayOfNulls(count)

    private val holdMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var downIndex = -1
    private var lastIndex = -1
    private var moved = false
    private var held = false
    private val hold = Runnable {
        held = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onClear(downIndex)
    }

    private val inset get() = height * 0.2f
    private val gap get() = dp(3).toFloat()
    private fun cellWidth(): Float = (width - inset * 2 - gap * (count - 1)) / count
    private fun cellLeft(i: Int): Float = inset + i * (cellWidth() + gap)
    private fun indexAt(x: Float): Int = ((x - inset) / (cellWidth() + gap)).toInt().coerceIn(0, count - 1)

    /** Anzahl für Modus "Alle": bis einschließlich des berührten Segments, ganz links = 0. */
    private fun countAt(x: Float): Int = if (x < inset + cellWidth() * 0.45f) 0 else indexAt(x) + 1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        railEdge.strokeWidth = dp(1).toFloat()
        rebuild()
    }

    private fun rebuild() {
        if (height == 0) return
        val top = inset
        val bottom = height - inset
        shaders = Array(count) { i ->
            val c = colors.getOrElse(i) { 0 } and 0xFFFFFF
            if (c == 0) null else {
                val full = 0xFF000000.toInt() or c
                LinearGradient(0f, top, 0f, bottom, blend(full, Color.WHITE, 0.45f), full, Shader.TileMode.CLAMP)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, rail)
        r.inset(railEdge.strokeWidth / 2, railEdge.strokeWidth / 2)
        canvas.drawRoundRect(r, h / 2, h / 2, railEdge)

        val w = cellWidth()
        val top = inset
        val bottom = h - inset
        val rad = min(w, bottom - top) * 0.38f
        val alpha = if (dimmed) 70 else 255
        // erst der Schein aller LEDs, dann die LEDs selbst
        for (i in 0 until count) {
            val c = colors.getOrElse(i) { 0 } and 0xFFFFFF
            if (c == 0) continue
            val left = cellLeft(i)
            val spread = inset * 0.8f
            r.set(left - spread * 0.4f, top - spread, left + w + spread * 0.4f, bottom + spread)
            glowP.color = 0xFF000000.toInt() or c
            glowP.alpha = if (dimmed) 25 else 85
            canvas.drawRoundRect(r, rad + spread, rad + spread, glowP)
        }
        for (i in 0 until count) {
            val left = cellLeft(i)
            r.set(left, top, left + w, bottom)
            val sh = shaders.getOrNull(i)
            if (sh == null) {
                canvas.drawRoundRect(r, rad, rad, offCell)
            } else {
                cell.shader = sh
                cell.alpha = alpha
                canvas.drawRoundRect(r, rad, rad, cell)
                if (!dimmed) {
                    chip.alpha = 170
                    canvas.drawCircle(r.centerX(), r.centerY() - (bottom - top) * 0.12f, min(w, bottom - top) * 0.13f, chip)
                }
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downIndex = indexAt(e.x)
                lastIndex = downIndex
                moved = false
                held = false
                if (single) postDelayed(hold, holdMs) else onCount(countAt(e.x))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val i = indexAt(e.x)
                if (single) {
                    if (i != downIndex) {
                        removeCallbacks(hold)
                        if (!held) {
                            if (!moved) onPaint(downIndex)
                            moved = true
                            if (i != lastIndex) onPaint(i)
                        }
                    }
                } else {
                    val n = countAt(e.x)
                    onCount(n)
                }
                if (i != lastIndex) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastIndex = i
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                if (single && !held && !moved) onPaint(downIndex)
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(hold)
        }
        return true
    }
}

// ------------------------------------------------------------------ Karte mit Lichtschein

/**
 * Glas-Karte, in der das Licht "scheint": ein weicher Schein in der LED-Farbe hinter dem Inhalt,
 * zentriert auf [anchor]. Ohne [glass] nur der Schein (z. B. im Panel).
 */
class GlowCard(context: Context, radiusDp: Float = 30f, private val glass: Boolean = true) : LinearLayout(context) {
    var anchor: View? = null

    private val radius = context.dpf(radiusDp)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shownColor = Color.WHITE
    private var level = 0f
    private var anim: ValueAnimator? = null
    private var key = 0L
    private val loc = IntArray(2)
    private val myLoc = IntArray(2)

    init {
        orientation = VERTICAL
        if (glass) background = GlassDrawable(context, radiusDp)
        setWillNotDraw(false)
    }

    /** Neue Lichtfarbe/-stärke – wird sanft übergeblendet. */
    fun setLight(color: Int?, on: Boolean, brightness: Float) {
        val toColor = color ?: shownColor
        val toLevel = if (on && color != null) 0.45f + 0.55f * brightness.coerceIn(0f, 1f) else 0f
        val fromColor = shownColor
        val fromLevel = level
        if (fromColor == toColor && fromLevel == toLevel) return
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = EASE_OUT
            addUpdateListener {
                val t = it.animatedValue as Float
                shownColor = blend(fromColor, toColor, t)
                level = fromLevel + (toLevel - fromLevel) * t
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (level < 0.01f || width == 0) return
        var ax = width / 2f
        var ay = height / 2f
        anchor?.let { a ->
            if (a.width > 0) {
                a.getLocationInWindow(loc)
                getLocationInWindow(myLoc)
                ax = loc[0] - myLoc[0] + a.width / 2f
                ay = loc[1] - myLoc[1] + a.height / 2f
            }
        }
        val rad = if (glass) max(width, height) * 0.8f else min(width, height) / 2f
        val a = (95 * level).toInt()
        val k = (ax.toInt().toLong() shl 48) xor (ay.toInt().toLong() shl 32) xor (shownColor.toLong() shl 8) xor a.toLong() xor rad.toLong()
        if (k != key || p.shader == null) {
            key = k
            p.shader = RadialGradient(
                ax, ay, rad,
                intArrayOf(LightColors.withAlpha(shownColor, a), LightColors.withAlpha(shownColor, a * 2 / 5), 0),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        if (glass) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            rect.inset(dpf(1.5f), dpf(1.5f))
            canvas.drawRoundRect(rect, radius, radius, p)
        } else {
            canvas.drawCircle(ax, ay, rad, p)
        }
    }

    private fun dpf(v: Float) = context.dpf(v)
}

// ------------------------------------------------------------------ Farbtupfer

/** Runder Farbtupfer mit Schein; ausgewählt = weißer Ring. */
class Swatch(context: Context, val color: Int, sizeDp: Int, onPick: (Int) -> Unit) : View(context) {
    var picked = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    init {
        layoutParams = lp(dp(sizeDp), dp(sizeDp))
        pressable { onPick(color) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val c = min(w, h) / 2f
        haloP.shader = RadialGradient(
            w / 2f, h / 2f, c,
            intArrayOf(LightColors.withAlpha(color, 120), 0), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP,
        )
        disc.shader = RadialGradient(
            w / 2f, h / 2f - c * 0.2f, c * 0.75f,
            blend(color, Color.WHITE, 0.45f), color, Shader.TileMode.CLAMP,
        )
        ringP.strokeWidth = max(2f, c * 0.1f)
    }

    override fun onDraw(canvas: Canvas) {
        val c = min(width, height) / 2f
        val x = width / 2f
        val y = height / 2f
        canvas.drawCircle(x, y, c, haloP)
        canvas.drawCircle(x, y, c * 0.58f, disc)
        if (picked) canvas.drawCircle(x, y, c * 0.82f, ringP)
    }
}

package com.amoo.roomlauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Licht-Oberfläche nach der Lumen-Remote-Spezifikation (GOVEE_H617E_UI.md):
 * 3D-LED-Ring auf geneigter Ebene, holografische Plattform, Power-Knopf, Mittenanzeige,
 * Statuszeile mit Modus-Chip, HSV-Farbrad, Glow-Helligkeitsleiste und Akzent-Hintergrund.
 * Alle Maße in dp (= pt der iOS-Vorlage).
 */

// ------------------------------------------------------------------ Farben

object LightColors {
    val BG = 0xFF0A0414.toInt()
    val MAGENTA = 0xFFC740FF.toInt()
    val DARK_GLASS = 0xBF1F0A2E.toInt() // rgba(0.12, 0.04, 0.18, 0.75)
    val WARM = 0xFFFFD180.toInt()
    val WARM_DARK = 0xFF401F0D.toInt()
    val MOON = 0xFFB9A8FF.toInt()

    fun withAlpha(c: Int, a: Int): Int = (c and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)
    fun withAlpha(c: Int, a: Float): Int = withAlpha(c, (a * 255).roundToInt())

    /** Helligkeit (HSV-Wert) einer Farbe skalieren. */
    fun value(c: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** Zweiter Hintergrund-Ton: Farbton +0,12, Helligkeit mindestens 0,5. */
    fun shifted(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + 0.12f * 360f) % 360f
        hsv[2] = max(0.5f, hsv[2])
        return Color.HSVToColor(hsv)
    }

    fun hex(c: Int) = String.format(Locale.ROOT, "#%06X", c and 0xFFFFFF)

    /** Durchschnittsfarbe der leuchtenden Segmente, null = alle aus. */
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

    /** HSV → RGB ohne JNI (für das Farbrad-Bild). */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = floor(hh).toInt()
        val f = hh - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Color.rgb((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
    }
}

private fun easeOut(p: Float): Float {
    val c = p.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}

private fun easeInOut(p: Float): Float {
    val c = p.coerceIn(0f, 1f)
    return if (c < 0.5f) 4 * c * c * c else 1 - (-2 * c + 2).let { it * it * it } / 2
}

/** Gedämpfte Feder: Start bei [from], schwingt auf 1 ein (0,45 s). */
private fun spring(from: Float, ms: Long): Float {
    if (ms < 0) return from
    val p = ms / 450f
    if (p >= 1f) return 1f
    return 1f + (from - 1f) * exp(-4.2f * p) * cos(3.4f * PI.toFloat() * p)
}

/** "Bewegung reduzieren": Animator-Dauer in den Entwickleroptionen auf 0. */
private fun reducedMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

/** Glow-Nachbau ohne Software-Rendering: drei breitere, durchsichtige Striche (Breitenfaktor, Deckkraft). */
private val GLOW_PASSES = arrayOf(1.0f to 0.16f, 0.6f to 0.24f, 0.28f to 0.36f)

// ------------------------------------------------------------------ Bühne: 3D-LED-Ring

/**
 * Der LED-Ring: 15 Segmente auf einer geneigten Ebene (Perspektive 650, Neigung 49°, beim Wischen 17°),
 * je 4 Lagen (Unterseite, Seite, Oberfläche mit Glow, Glanz), in der Mitte der Power-Knopf
 * bzw. kurz die Mittenanzeige.
 *
 * Effizienz: Jede Lage ist ein normaler Bogen, die Projektion steckt in einer Perspektiv-Matrix der
 * Zeichenfläche – die GPU zeichnet, die Bögen selbst bleiben gleich und werden gecacht.
 * Auf dem Board läuft die Animation mit höchstens 30 Bildern/s und pausiert, solange sie verdeckt ist.
 */
class LumenRing(context: Context, private val board: Boolean) : View(context) {

    companion object {
        private const val N = 15
        private const val REST = 0.86f // 49°
        private const val SWIPE = 0.30f // 17°
        private const val SWAY = 0.14f // ±8°
        private val live = WeakHashMap<LumenRing, Boolean>()

        /** Board-Widgets pausieren (z. B. während ein Panel offen ist). */
        var boardPaused = false
            set(v) {
                if (field == v) return
                field = v
                live.keys.toList().forEach { it.invalidate() }
            }

        /** 60 Bilder/s in der großen Ansicht (Einstellung "flüssig"), sonst 30. */
        var smooth = true
    }

    // ---- Eingänge
    var segments: List<Int> = List(N) { 0 }
        set(v) {
            field = v
            invalidate()
        }
    var power = false
        set(v) {
            if (field == v) return
            field = v
            if (v && isAttachedToWindow && !reduced) powerOnAt = SystemClock.uptimeMillis()
            invalidate()
        }
    var brightness = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var accent = 0xFFFF2F5A.toInt()
        set(v) {
            field = v or 0xFF000000.toInt()
            invalidate()
        }
    var single = false
        set(v) {
            if (field == v) return
            field = v
            if (isAttachedToWindow) wave()
            invalidate()
        }
    var selected = -1
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    // ---- Ausgänge
    var onCount: (Int) -> Unit = {}
    var onSelect: (Int) -> Unit = {}
    var onPaint: (Int) -> Unit = {}
    var onClear: (Int) -> Unit = {}
    var onToggle: () -> Unit = {}
    var onReset: () -> Unit = {}

    // ---- Zustand der Darstellung
    private val act = FloatArray(N)
    private val col = IntArray(N)
    private val popAt = LongArray(N) { -1L }
    private val popDur = IntArray(N) { 350 }
    private val popAmp = FloatArray(N)
    private val pops = FloatArray(N)
    private var onT = 0f
    private var shownAccent = accent
    private var tilt = REST
    private var tiltFrom = REST
    private var tiltTo = REST
    private var tiltAt = 0L
    private var tiltDur = 0
    private var swayAmp = 1f
    private var swayTarget = 1f
    private var powerOnAt = -1L
    private val t0 = SystemClock.uptimeMillis()
    private var lastStep = 0L
    private var lastDraw = 0L
    private var initialized = false
    private val reduced = reducedMotion(context)

    // Mittenanzeige
    private var midNum = ""
    private var midCap = ""
    private var midAlpha = 0f
    private var midUntil = 0L
    private var midPopAt = -1L

    // Power-Knopf
    private var press = 1f
    private var pressAnim: ValueAnimator? = null

    // ---- Geometrie
    private val d1 = context.dpf(1)
    private var rr = 0f // Radius R
    private var cx = 0f
    private var cy = 0f
    private var lw = 0f
    private var r = 0f
    private var capDeg = 0f
    private var gapDeg = 0f
    private var powerSize = 0f
    private val camera = context.dpf(650)

    // ---- Zeichenwerkzeuge
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.roundHeavy
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.roundSemi
        textAlign = Paint.Align.CENTER
        color = C.white(0.7f)
    }
    private val oval = RectF()
    private val m = Matrix()
    private val vals = FloatArray(9)
    private var haloKey = 0

    init {
        contentDescription = "Segmente"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (board) live[this] = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        live.remove(this)
        Choreographer.getInstance().removeFrameCallback(frame)
        scheduled = false
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Ohne Plattform: Ring mittig, etwas größer
        rr = max(40 * d1, minOf(w * 0.44f, h * 0.6f))
        cx = w / 2f
        cy = h * 0.47f
        lw = max(12 * d1, rr * 0.14f)
        r = rr - lw
        capDeg = Math.toDegrees(((lw / 2) / r).toDouble()).toFloat()
        gapDeg = Math.toDegrees(0.05).toFloat()
        powerSize = min(92 * d1, rr * 0.84f)
        val sc = (powerSize / (92 * d1)).coerceIn(0.6f, 1f)
        num.textSize = 46 * d1 * sc
        capP.textSize = 12 * d1 * sc.coerceAtLeast(0.8f)
        num.setShadowLayer(10 * d1, 0f, 0f, C.white(0.6f))
        haloKey = 0
    }

    // ---------------------------------------------------------------- Bildtakt

    private var scheduled = false
    private val frame = Choreographer.FrameCallback {
        scheduled = false
        if (canRun()) invalidate()
    }

    private fun canRun(): Boolean =
        isAttachedToWindow && windowVisibility == VISIBLE && isShown && !(board && boardPaused)

    private fun scheduleNext(now: Long) {
        if (scheduled || !canRun()) return
        scheduled = true
        val minMs = if (board || !smooth) 33L else 16L
        val wait = minMs - (now - lastDraw)
        if (wait > 6) Choreographer.getInstance().postFrameCallbackDelayed(frame, wait) else Choreographer.getInstance().postFrameCallback(frame)
    }

    /** Alle Übergänge weiterrechnen. true = es bewegt sich noch etwas. */
    private fun step(now: Long): Boolean {
        val dt = if (lastStep == 0L) 1000f else (now - lastStep).coerceIn(0, 100).toFloat()
        lastStep = now
        var busy = false
        val k = 1f - exp(-dt / 100f) // ≈ 0,3 s
        for (i in 0 until N) {
            val target = segments.getOrElse(i) { 0 } and 0xFFFFFF
            val on = if (power && target != 0) 1f else 0f
            act[i] = if (!initialized) on else act[i] + (on - act[i]) * k
            if (abs(act[i] - on) > 0.01f) busy = true else act[i] = on
            if (target != 0) {
                col[i] = if (!initialized || col[i] == 0) target else (blend(col[i] or 0xFF000000.toInt(), target or 0xFF000000.toInt(), k) and 0xFFFFFF)
                if (col[i] != target) busy = true
            }
            if (popAt[i] >= 0) busy = true
        }
        val onTarget = if (power) 1f else 0f
        onT = if (!initialized) onTarget else onT + (onTarget - onT) * (1f - exp(-dt / 115f))
        if (abs(onT - onTarget) > 0.01f) busy = true else onT = onTarget
        if (shownAccent != accent) {
            shownAccent = if (!initialized) accent else blend(shownAccent, accent, 1f - exp(-dt / 150f))
            busy = true
        }
        if (tiltDur > 0) {
            val p = (now - tiltAt) / tiltDur.toFloat()
            tilt = tiltFrom + (tiltTo - tiltFrom) * easeOut(p)
            if (p >= 1f) tiltDur = 0 else busy = true
        }
        swayAmp += (swayTarget - swayAmp) * (1f - exp(-dt / 220f))
        val midTarget = if (now < midUntil) 1f else 0f
        if (midAlpha != midTarget) {
            midAlpha = if (midTarget > midAlpha) min(1f, midAlpha + dt / 150f) else max(0f, midAlpha - dt / 300f)
            busy = true
        }
        if (powerOnAt >= 0) {
            if (now - powerOnAt > 1200) powerOnAt = -1 else busy = true
        }
        if (midPopAt >= 0 && now - midPopAt > 450) midPopAt = -1
        if (midUntil > now) busy = true
        initialized = true
        return busy
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        if (rr == 0f) return
        val now = SystemClock.uptimeMillis()
        val busy = step(now)
        lastDraw = now
        val t = (now - t0) / 1000f
        val motion = !reduced
        val twoPi = 2 * PI.toFloat()
        val floatY = if (motion) 5 * d1 * sin(twoPi * t / 6f) else 0f
        val phi = if (motion) SWAY * swayAmp * sin(twoPi * t / 8.4f) else 0f
        val k = 0.3f + 0.7f * brightness

        drawRing(canvas, now, floatY, phi, k)
        drawCenter(canvas, now)

        if (canRun() && (motion || busy)) scheduleNext(now)
    }

    /** Perspektiv-Matrix der Ring-Ebene für eine Lage in Höhe [z] (erst Neigung um X, dann Drehung um Y, dann Perspektive). */
    private fun plane(z: Float, phi: Float): Matrix {
        val st = sin(tilt)
        val ct = cos(tilt)
        val sp = sin(phi)
        val cp = cos(phi)
        vals[0] = cp
        vals[1] = st * sp
        vals[2] = z * ct * sp
        vals[3] = 0f
        vals[4] = ct
        vals[5] = -z * st
        vals[6] = sp / camera
        vals[7] = -st * cp / camera
        vals[8] = 1f - z * ct * cp / camera
        m.setValues(vals)
        return m
    }

    private inline fun inPlane(canvas: Canvas, z: Float, phi: Float, floatY: Float, block: () -> Unit) {
        canvas.save()
        canvas.translate(cx, cy + floatY)
        canvas.concat(plane(z, phi))
        block()
        canvas.restore()
    }

    private fun popZ(i: Int, now: Long): Float {
        val at = popAt[i]
        if (at < 0) return 0f
        val p = (now - at) / popDur[i].toFloat()
        if (p < 0f) return 0f
        if (p >= 1f) {
            popAt[i] = -1
            return 0f
        }
        return sin(PI.toFloat() * p)
    }

    private fun segStart(i: Int) = -90f + i * 24f + capDeg + gapDeg / 2
    private fun segSweep() = 24f - 2 * capDeg - gapDeg

    private fun drawRing(canvas: Canvas, now: Long, floatY: Float, phi: Float, k: Float) {
        val sweep = segSweep()
        val tOn = if (powerOnAt >= 0) now - powerOnAt else -1L
        for (i in 0 until N) pops[i] = popZ(i, now)

        // 1) Unterseite und 2) Seite – dunkles Glas oder abgedunkelte Segmentfarbe
        arc.strokeWidth = lw
        for (layer in 0..1) {
            val z = if (layer == 0) -lw * 0.75f else -lw * 0.38f
            val f = if (layer == 0) 0.30f else 0.55f
            inPlane(canvas, z, phi, floatY) {
                oval.set(-r, -r, r, r)
                for (i in 0 until N) {
                    val c = col[i]
                    val active = if (c == 0) LightColors.DARK_GLASS else LightColors.withAlpha(LightColors.value(c or 0xFF000000.toInt(), f), 0.9f)
                    arc.color = blend(LightColors.DARK_GLASS, active, act[i])
                    canvas.drawArc(oval, segStart(i), sweep, false, arc)
                }
            }
        }

        // 3) Oberfläche mit Glow (angehobene Segmente zuletzt)
        for (pass in 0..1) {
            for (i in 0 until N) {
                val lifted = pops[i] != 0f
                if ((pass == 0) == lifted) continue
                inPlane(canvas, lw * popAmp[i] * pops[i], phi, floatY) {
                    oval.set(-r, -r, r, r)
                    val c = col[i]
                    val a = act[i]
                    val fade = if (tOn >= 0) 0.1f + 0.9f * easeOut((tOn - i * 35f) / 350f) else 1f
                    val gr = (6 + (2 + 10 * k) * a + 14 * pops[i]) * d1
                    val ga = ((if (power) 0.22f else 0.05f) * (1 - a) + 0.95f * k * a) * fade
                    val gc = if (c == 0) LightColors.MAGENTA else blend(LightColors.MAGENTA, c or 0xFF000000.toInt(), a)
                    for ((wf, af) in GLOW_PASSES) {
                        arc.strokeWidth = lw + 2 * gr * wf
                        arc.color = LightColors.withAlpha(gc, ga * af)
                        canvas.drawArc(oval, segStart(i), sweep, false, arc)
                    }
                    arc.strokeWidth = lw
                    val inactive = if (c != 0) LightColors.withAlpha(c, 0.16f) else LightColors.withAlpha(LightColors.MAGENTA, 0.12f)
                    val activeC = if (c != 0) LightColors.withAlpha(c, (0.35f + 0.6f * k) * fade) else inactive
                    arc.color = blend(inactive, activeC, a)
                    canvas.drawArc(oval, segStart(i), sweep, false, arc)
                }
            }
        }

        // 4) Glanz an der Außenkante
        for (i in 0 until N) {
            inPlane(canvas, d1 + lw * popAmp[i] * pops[i], phi, floatY) {
                val rg = r + lw * 0.26f
                oval.set(-rg, -rg, rg, rg)
                val sel = single && i == selected
                arc.strokeWidth = if (sel) 3.5f * d1 else 2 * d1
                arc.color = if (sel) C.white(0.95f) else blend(C.white(0.16f), C.white(0.45f), act[i])
                canvas.drawArc(oval, segStart(i) + 1.146f, sweep - 2.29f, false, arc)
            }
        }

        // Einschalten: Lichtimpuls einmal um den Ring
        if (tOn in 0..900) {
            val e = easeInOut(tOn / 700f)
            val s = easeInOut((tOn - 200) / 700f)
            if (e > s) {
                inPlane(canvas, d1, phi, floatY) {
                    oval.set(-r, -r, r, r)
                    for ((wf, af) in GLOW_PASSES) {
                        arc.strokeWidth = lw * 0.45f + 2 * 12 * d1 * wf
                        arc.color = LightColors.withAlpha(shownAccent, 0.9f * af)
                        canvas.drawArc(oval, -90f + 360f * s, 360f * (e - s), false, arc)
                    }
                    arc.strokeWidth = lw * 0.45f
                    arc.color = C.white(0.9f)
                    canvas.drawArc(oval, -90f + 360f * s, 360f * (e - s), false, arc)
                }
            }
        }
    }

    /** Power-Knopf (bewegt sich nicht mit) bzw. die Mittenanzeige. */
    private fun drawCenter(canvas: Canvas, now: Long) {
        val vis = 1f - midAlpha
        if (vis > 0.01f) {
            val s = powerSize * press
            val rad = s / 2
            val scale = powerSize / (92 * d1)
            val glowR = rad + 24 * d1 * scale
            if (onT > 0.01f) {
                val key = shownAccent xor (glowR * 10).toInt()
                if (key != haloKey) {
                    haloKey = key
                    haloP.shader = RadialGradient(
                        cx, cy, glowR,
                        intArrayOf(shownAccent, shownAccent, LightColors.withAlpha(shownAccent, 0)),
                        floatArrayOf(0f, rad / glowR, 1f), Shader.TileMode.CLAMP,
                    )
                }
                haloP.alpha = (0.9f * onT * vis * 255).roundToInt()
                canvas.drawCircle(cx, cy, glowR, haloP)
            }
            // Glas
            fill.color = LightColors.withAlpha(0x1A1424, 0.85f * vis)
            canvas.drawCircle(cx, cy, rad, fill)
            fill.color = C.white(0.08f * vis)
            canvas.drawCircle(cx, cy, rad, fill)
            ring.strokeWidth = d1
            ring.color = C.white(0.25f * vis)
            canvas.drawCircle(cx, cy, rad - d1 / 2, ring)
            // Kern
            fill.color = blend(C.white(0.05f * vis), LightColors.withAlpha(shownAccent, 0.75f * vis), onT)
            canvas.drawCircle(cx, cy, rad - 10 * d1 * scale * press, fill)
            // Symbol (an: mit weißem Glow)
            val g = 32 * d1 * scale * press
            val gy = cy + g * 0.04f
            for (pass in 0..1) {
                if (pass == 0 && onT < 0.05f) continue
                glyph.strokeWidth = if (pass == 0) g * 0.32f else g * 0.12f
                glyph.color = if (pass == 0) C.white(0.22f * onT * vis) else C.white((0.5f + 0.5f * onT) * vis)
                oval.set(cx - g * 0.42f, gy - g * 0.36f, cx + g * 0.42f, gy + g * 0.48f)
                canvas.drawArc(oval, -60f, 300f, false, glyph)
                canvas.drawLine(cx, gy - g * 0.5f, cx, gy + g * 0.02f, glyph)
            }
        }
        if (midAlpha > 0.01f) {
            val sc = if (midPopAt >= 0) spring(1.18f, now - midPopAt) else 1f
            canvas.save()
            canvas.scale(sc, sc, cx, cy)
            num.alpha = (255 * midAlpha).roundToInt()
            canvas.drawText(midNum, cx, cy + num.textSize * 0.2f, num)
            canvas.restore()
            capP.alpha = (255 * 0.7f * midAlpha).roundToInt()
            canvas.drawText(midCap, cx, cy + num.textSize * 0.2f + capP.textSize * 1.6f, capP)
        }
    }

    // ---------------------------------------------------------------- Animationen

    private fun pop(i: Int, amp: Float = 0.9f, dur: Int = 350, delay: Long = 0) {
        if (i !in 0 until N || reduced) return
        popAt[i] = SystemClock.uptimeMillis() + delay
        popAmp[i] = amp
        popDur[i] = dur
        invalidate()
    }

    /** Lichtwelle beim Moduswechsel: nacheinander anheben. */
    private fun wave() {
        for (i in 0 until N) pop(i, 0.8f, 400, i * 30L)
    }

    private fun tiltTo(target: Float, ms: Int) {
        tiltFrom = tilt
        tiltTo = target
        tiltAt = SystemClock.uptimeMillis()
        tiltDur = ms
        invalidate()
    }

    private fun showMid(n: String, caption: String) {
        val now = SystemClock.uptimeMillis()
        if (n != midNum && midAlpha > 0.1f) midPopAt = now
        midNum = n
        midCap = com.amoo.roomlauncher.Lang.auto(caption)
        midUntil = now + 900
        invalidate()
    }

    private fun animatePress(to: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, to).apply {
            duration = if (to < 1f) 150 else 450
            interpolator = if (to < 1f) EASE_OUT else android.view.animation.OvershootInterpolator(2.2f)
            addUpdateListener {
                press = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ---------------------------------------------------------------- Gesten

    private enum class G { NONE, POWER, RING }

    private var g = G.NONE
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var pos = 0f
    private var lastAngle = 0f
    private var downSeg = 0
    private var held = false
    private var lastCount = -1
    private var lastSel = -1
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val holdPower = Runnable {
        held = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        animatePress(1f)
        if (!reduced) powerOnAt = SystemClock.uptimeMillis()
        onReset()
    }
    private val holdSeg = Runnable {
        if (!swiping && single && g == G.RING) {
            held = true
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClear(downSeg)
            pop(downSeg)
            showMid("${downSeg + 1}", "gelöscht")
        }
    }

    /** Winkel (0° = oben, im Uhrzeigersinn) und ob der Punkt auf dem Ring liegt – bei Neigung [t]. */
    private fun angleAt(x: Float, y: Float, t: Float): Pair<Float, Boolean> {
        val qx = x - cx
        val qy = (y - cy) / cos(t)
        val d = sqrt(qx * qx + qy * qy)
        val a = ((Math.toDegrees(atan2(qy, qx).toDouble()).toFloat() + 90f) % 360f + 360f) % 360f
        return a to (d > 0.45f * rr && d < 1.4f * rr)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = false
                swiping = false
                downX = x
                downY = y
                if (hypot(x - cx, y - cy) <= powerSize / 2 && midAlpha < 0.05f) {
                    g = G.POWER
                    parent?.requestDisallowInterceptTouchEvent(true)
                    animatePress(0.92f)
                    postDelayed(holdPower, 700)
                    return true
                }
                val (a, ok) = angleAt(x, y, tilt)
                if (!ok) return false // daneben: gehört der Karte (Halten → große Ansicht)
                g = G.RING
                parent?.requestDisallowInterceptTouchEvent(true)
                pos = a / 24f
                lastAngle = a
                downSeg = min(14, floor(a / 24f).toInt())
                if (single) postDelayed(holdSeg, 600)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (g == G.RING) {
                if (!swiping && hypot(x - downX, y - downY) > slop) {
                    swiping = true
                    removeCallbacks(holdSeg)
                    tiltTo(SWIPE, 250)
                    swayTarget = 0f
                    lastAngle = angleAt(x, y, SWIPE).first
                }
                if (swiping) {
                    val a = angleAt(x, y, SWIPE).first
                    var delta = a - lastAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastAngle = a
                    pos = (pos + delta / 24f).coerceIn(0f, 15f)
                    if (!single) {
                        val n = pos.roundToInt()
                        if (n != lastCount) {
                            lastCount = n
                            onCount(n)
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (n > 0) pop(n - 1)
                            showMid("$n", "von 15")
                        }
                    } else {
                        val s = min(14, floor(pos).toInt())
                        if (s != lastSel) {
                            lastSel = s
                            onSelect(s)
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            pop(s)
                            showMid("${s + 1}", "Segment")
                        }
                    }
                }
            } else if (g == G.POWER && hypot(x - cx, y - cy) > powerSize) {
                removeCallbacks(holdPower)
            }
            MotionEvent.ACTION_UP -> {
                when (g) {
                    G.POWER -> {
                        removeCallbacks(holdPower)
                        if (!held) {
                            animatePress(1f)
                            if (hypot(x - cx, y - cy) <= powerSize) {
                                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                onToggle()
                            }
                        }
                    }
                    G.RING -> {
                        removeCallbacks(holdSeg)
                        if (swiping) {
                            tiltTo(REST, 450)
                            swayTarget = 1f
                        } else if (!held) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (!single) {
                                onCount(downSeg + 1)
                                showMid("${downSeg + 1}", "von 15")
                            } else {
                                onSelect(downSeg)
                                onPaint(downSeg)
                                showMid("${downSeg + 1}", "Segment")
                            }
                            pop(downSeg)
                        }
                    }
                    G.NONE -> Unit
                }
                resetGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdPower)
                removeCallbacks(holdSeg)
                if (g == G.POWER) animatePress(1f)
                if (swiping) {
                    tiltTo(REST, 450)
                    swayTarget = 1f
                }
                resetGesture()
            }
        }
        return true
    }

    private fun resetGesture() {
        g = G.NONE
        swiping = false
        lastCount = -1
        lastSel = -1
    }
}

// ------------------------------------------------------------------ Modus-Chip

/** Glas-Kapsel "Alle" (gestrichelter Kreis) / "Einzeln" (Tipp-Symbol). Tippen wechselt den Modus. */
class ModeChip(context: Context, var onToggle: () -> Unit) : View(context) {
    var single = false
        set(v) {
            if (field == v) return
            field = v
            requestLayout()
            popAt = SystemClock.uptimeMillis()
            invalidate()
        }
    private var popAt = -1L
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.10f) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C.white(0.18f)
        strokeWidth = context.dpf(1)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.roundBold
        color = C.TEXT
        textSize = context.dpf(12.5f)
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = C.TEXT
        strokeWidth = context.dpf(1.6f)
    }
    private val rect = RectF()

    private val label get() = com.amoo.roomlauncher.Lang.auto(if (single) "Einzeln" else "Alle")

    init {
        isClickable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onToggle()
        }
        contentDescription = "Modus"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dp = context.dpf(1)
        setMeasuredDimension((10 * dp + 14 * dp + 5 * dp + text.measureText(label) + 12 * dp).toInt(), (28 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val dp = context.dpf(1)
        val now = SystemClock.uptimeMillis()
        val sc = if (popAt >= 0) spring(1.15f, now - popAt) else 1f
        if (popAt >= 0) {
            if (now - popAt > 450) popAt = -1 else postInvalidateOnAnimation()
        }
        canvas.save()
        canvas.scale(sc, sc, width / 2f, height / 2f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        rect.inset(dp / 2, dp / 2)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, bg)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, edge)
        val ix = 17 * dp
        val iy = height / 2f
        if (!single) {
            for (i in 0 until 8) canvas.drawArc(ix - 6 * dp, iy - 6 * dp, ix + 6 * dp, iy + 6 * dp, i * 45f, 26f, false, icon)
        } else {
            canvas.drawLine(ix, iy - 2 * dp, ix, iy + 6 * dp, icon)
            canvas.drawCircle(ix, iy - 2.5f * dp, 1.6f * dp, icon)
            canvas.drawArc(ix - 5.5f * dp, iy - 8 * dp, ix + 5.5f * dp, iy + 3 * dp, 200f, 140f, false, icon)
        }
        canvas.drawText(label, 29 * dp, iy - (text.descent() + text.ascent()) / 2, text)
        canvas.restore()
    }
}

/** Status-Punkt mit Glow in der eigenen Farbe. */
class StatusDot(context: Context) : View(context) {
    var color = C.TEXT3
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val c = min(width, height) / 2f
        p.shader = RadialGradient(width / 2f, height / 2f, c, intArrayOf(color, color, LightColors.withAlpha(color, 0)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(width / 2f, height / 2f, c, p)
    }
}

// ------------------------------------------------------------------ Farbrad

/**
 * HSV-Farbrad (Helligkeit 1): Farbton = Winkel (0° rechts, im Uhrzeigersinn), Sättigung = Abstand zur Mitte.
 * Neon-Rand und Regler in der gewählten Farbe. Ziehen auch außerhalb des Rads möglich.
 */
class HueWheel(context: Context, private val wheelDp: Int, var onColor: (Int) -> Unit = {}) : View(context) {
    /** Rand für Regler (16 × 1,3) + Glow (10) – sonst wird er am Kartenrand abgeschnitten. */
    private val pad = context.dpf(32)
    private var hue = 0.97f * 360f
    private var sat = 0.82f
    private var dragging = false
    private var knob = 1f
    private var knobAnim: ValueAnimator? = null
    private var bmp: Bitmap? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val neon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = context.dpf(3)
    }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)

    val current: Int get() = LightColors.hsv(hue, sat, 1f)

    /** Farbe von außen setzen (nicht während des Ziehens). */
    fun setColor(c: Int) {
        if (dragging) return
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.01f) hsv[0] = hue
        if (abs(hsv[0] - hue) > 0.5f || abs(hsv[1] - sat) > 0.005f) {
            hue = hsv[0]
            sat = hsv[1]
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (context.dpf(wheelDp) + pad * 2).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val d = context.dpf(wheelDp).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val px = IntArray(d * d)
        val half = d / 2f
        for (y in 0 until d) for (x in 0 until d) {
            val dx = x + 0.5f - half
            val dy = y + 0.5f - half
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > half) continue
            val hh = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
            val c = LightColors.hsv(hh, (dist / half).coerceAtMost(1f), 1f)
            px[y * d + x] = LightColors.withAlpha(c, (half - dist).coerceIn(0f, 1f)) // 1 px weicher Rand
        }
        b.setPixels(px, 0, d, 0, 0, d, d)
        bmp = b // altes Bild nicht recyceln – die GPU kann es noch zeichnen
    }

    override fun onDraw(canvas: Canvas) {
        val dp = context.dpf(1)
        val c = width / 2f
        val rad = context.dpf(wheelDp) / 2
        val cur = current
        // Neon-Rand mit Glow
        for ((wf, af) in GLOW_PASSES) {
            neon.strokeWidth = 3 * dp + 2 * 10 * dp * wf
            neon.color = LightColors.withAlpha(cur, 0.9f * af)
            canvas.drawCircle(c, c, rad + 4 * dp, neon)
        }
        neon.strokeWidth = 3 * dp
        neon.color = LightColors.withAlpha(cur, 0.9f)
        canvas.drawCircle(c, c, rad + 4 * dp, neon)
        bmp?.let { canvas.drawBitmap(it, c - it.width / 2f, c - it.height / 2f, bmpPaint) }
        // Regler
        val a = Math.toRadians(hue.toDouble())
        val kx = c + cos(a).toFloat() * sat * rad
        val ky = c + sin(a).toFloat() * sat * rad
        val kr = 16 * dp * knob
        glowP.shader = RadialGradient(kx, ky, kr + 10 * dp, intArrayOf(cur, cur, LightColors.withAlpha(cur, 0)), floatArrayOf(0f, kr / (kr + 10 * dp), 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(kx, ky, kr + 10 * dp, glowP)
        knobFill.color = cur
        canvas.drawCircle(kx, ky, kr, knobFill)
        canvas.drawCircle(kx, ky, kr - knobRing.strokeWidth / 2, knobRing)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                animateKnob(1.3f)
                pick(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> pick(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                animateKnob(1f)
            }
        }
        return true
    }

    private fun pick(x: Float, y: Float) {
        val c = width / 2f
        val rad = context.dpf(wheelDp) / 2
        val dx = x - c
        val dy = y - c
        hue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        sat = (sqrt(dx * dx + dy * dy) / rad).coerceIn(0f, 1f)
        invalidate()
        onColor(current)
    }

    private fun animateKnob(to: Float) {
        knobAnim?.cancel()
        knobAnim = ValueAnimator.ofFloat(knob, to).apply {
            duration = 180
            addUpdateListener {
                knob = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}

/** Runde Farbfläche mit Glow und weißem Rand (zeigt die gewählte Farbe). */
class LumenSwatch(context: Context, private val sizeDp: Int) : View(context) {
    var color = 0xFFFF2F5A.toInt()
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C.white(0.85f)
        strokeWidth = context.dpf(2)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dpf(sizeDp + 28).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val rad = context.dpf(sizeDp) / 2
        val gr = rad + context.dpf(14)
        glowP.shader = RadialGradient(c, c, gr, intArrayOf(color, LightColors.withAlpha(color, 0.95f), LightColors.withAlpha(color, 0)), floatArrayOf(0f, rad / gr, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(c, c, gr, glowP)
        p.color = color
        canvas.drawCircle(c, c, rad, p)
        canvas.drawCircle(c, c, rad - edge.strokeWidth / 2, edge)
    }
}

// ------------------------------------------------------------------ Helligkeit

/** Sonne in Warm mit Glow. */
class SunIcon(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LightColors.WARM
        strokeCap = Paint.Cap.ROUND
    }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        glowP.shader = RadialGradient(cx, cy, s / 2, LightColors.withAlpha(LightColors.WARM, 0.55f), LightColors.withAlpha(LightColors.WARM, 0), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, s / 2, glowP)
        p.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, s * 0.17f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.08f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            canvas.drawLine(
                cx + cos(a).toFloat() * s * 0.27f, cy + sin(a).toFloat() * s * 0.27f,
                cx + cos(a).toFloat() * s * 0.38f, cy + sin(a).toFloat() * s * 0.38f, p,
            )
        }
    }
}

/** Glow-Leiste 1–100: Bahn dunkelwarm → Warm → Weiß, weißer Knopf mit warmem Kern und Glow. */
class GlowBar(context: Context, var onChange: (Float) -> Unit = {}) : View(context) {
    var value = 1f
        set(v) {
            if (dragging) return
            val c = v.coerceIn(0.01f, 1f)
            if (field != c) {
                field = c
                invalidate()
            }
        }
    private var dragging = false
    private var knob = 1f
    private var knobAnim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C.white(0.18f)
        strokeWidth = context.dpf(1)
    }
    private val knobP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.95f) }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LightColors.WARM }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpf(36).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        track.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(LightColors.WARM_DARK, LightColors.WARM, Color.WHITE), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val dp = context.dpf(1)
        val cy = height / 2f
        rect.set(dp, cy - 7 * dp, width - dp, cy + 7 * dp)
        canvas.drawRoundRect(rect, 7 * dp, 7 * dp, track)
        canvas.drawRoundRect(rect, 7 * dp, 7 * dp, edge)
        val x = 15 * dp + (width - 30 * dp) * ((value * 100f - 1f) / 99f)
        val kr = 15 * dp * knob
        val gr = kr + (if (dragging) 16 else 10) * dp
        glowP.shader = RadialGradient(x, cy, gr, intArrayOf(LightColors.WARM, LightColors.withAlpha(LightColors.WARM, 0.9f), LightColors.withAlpha(LightColors.WARM, 0)), floatArrayOf(0f, kr / gr, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, cy, gr, glowP)
        canvas.drawCircle(x, cy, kr, knobP)
        canvas.drawCircle(x, cy, 7 * dp * knob, core)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                animateKnob(1.25f)
                pick(e.x)
            }
            MotionEvent.ACTION_MOVE -> pick(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                animateKnob(1f)
            }
        }
        return true
    }

    private fun pick(x: Float) {
        val dp = context.dpf(1)
        val f = ((x - 15 * dp) / max(1f, width - 30 * dp)).coerceIn(0f, 1f)
        val v = (1f + 99f * f).roundToInt() / 100f
        val old = value
        dragging = false
        value = v
        dragging = true
        if ((old * 20).toInt() != (v * 20).toInt()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
        onChange(v)
    }

    private fun animateKnob(to: Float) {
        knobAnim?.cancel()
        knobAnim = ValueAnimator.ofFloat(knob, to).apply {
            duration = 200
            interpolator = android.view.animation.OvershootInterpolator(1.4f)
            addUpdateListener {
                knob = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}

// ------------------------------------------------------------------ Hintergrund

/**
 * Akzent-Hintergrund der Licht-Oberfläche: Grundfarbe #0A0414 (optional) und zwei weiche Verläufe
 * in der gewählten Farbe (oben) und einem verschobenen Ton (unten links). Farbwechsel blenden in 0,45 s.
 */
class LumenBackground(private val radius: Float, private val base: Boolean, private val strength: Float = 1f) : Drawable() {
    private var shown = 0xFFFF2F5A.toInt()
    private var anim: ValueAnimator? = null
    private val baseP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LightColors.BG }
    private val top = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottom = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var key = 0L
    private var started = false

    /** 0..1: wie stark der Schein ist (z. B. schwächer, wenn das Licht aus ist). */
    var level = 1f
        set(v) {
            if (field != v) {
                field = v
                invalidateSelf()
            }
        }

    fun setAccent(c: Int) {
        val to = c or 0xFF000000.toInt()
        if (to == shown) return
        anim?.cancel()
        if (!started) {
            shown = to
            started = true
            invalidateSelf()
            return
        }
        val from = shown
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener {
                shown = blend(from, to, it.animatedValue as Float)
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        if (base) canvas.drawRoundRect(rect, radius, radius, baseP)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val k = (shown.toLong() and 0xFFFFFFFFL) xor (b.width().toLong() shl 32) xor (b.height().toLong() shl 48) xor ((level * 100).toLong() shl 20)
        if (k != key) {
            key = k
            top.shader = ellipse(b.left + 0.5f * w, b.top + 0.25f * h, 0.885f * w, 0.885f * h, LightColors.withAlpha(shown, 0.55f * strength * level))
            bottom.shader = ellipse(b.left + 0.1f * w, b.top + 1.0f * h, 1.055f * w, 1.055f * h, LightColors.withAlpha(LightColors.shifted(shown), 0.35f * strength * level))
        }
        canvas.drawRoundRect(rect, radius, radius, top)
        canvas.drawRoundRect(rect, radius, radius, bottom)
    }

    private fun ellipse(x: Float, y: Float, rx: Float, ry: Float, c: Int): Shader =
        RadialGradient(x, y, rx, c, LightColors.withAlpha(c, 0), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, x, y) })
        }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Karte für das Licht-Widget: Glas und darüber der Akzent-Schein.
 * Ohne Glas ([glass] = false) komplett durchsichtig – wie die Uhr.
 */
class LumenCard(context: Context, private val glass: Boolean, radiusDp: Float = 30f) : LinearLayout(context) {
    val glow = LumenBackground(context.dpf(radiusDp), base = false, strength = 0.6f)

    init {
        orientation = VERTICAL
        if (glass) background = GlassDrawable(context, radiusDp)
        setWillNotDraw(!glass)
        glow.callback = this
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === glow || super.verifyDrawable(who)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glow.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glass) glow.draw(canvas)
    }
}

/** Kleine Glas-Karte der Licht-Oberfläche (Radius 26, Rand Weiß 12 %, Innenabstand 14/18). */
fun Context.lumenPanel(): LinearLayout = column().apply {
    background = RoundDrawable(context, 26f, C.white(0.08f), C.white(0.12f))
    setPadding(dp(18), dp(14), dp(18), dp(14))
    clipToPadding = false // Glow von Farbrad und Reglern darf in den Rand reichen
}

/** Panel-Titel: 12, fett, Großbuchstaben, gesperrt. */
fun Context.lumenTitle(text: String): android.widget.TextView = label(com.amoo.roomlauncher.Lang.auto(text).uppercase(com.amoo.roomlauncher.Lang.locale), 12f, C.white(0.6f)).apply {
    typeface = Fonts.roundBold
    letterSpacing = 0.15f
}

/** Text in der Rundschrift (Nunito). */
fun Context.roundLabel(text: String, sp: Float, color: Int = C.TEXT, weight: Int = 700): android.widget.TextView = label(text, sp, color).apply {
    typeface = when {
        weight >= 900 -> Fonts.roundHeavy
        weight >= 700 -> Fonts.roundBold
        else -> Fonts.roundSemi
    }
}

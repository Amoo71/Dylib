package com.amoo.roomlauncher.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.amoo.roomlauncher.ui.Backdrop
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Eigenes Hintergrundbild: statisch oder GIF (über Movie). */
class BgImage(val bitmap: Bitmap?, @Suppress("DEPRECATION") val movie: Movie?) {
    /** Zwischenspeicher für das aktuelle GIF-Bild (in Originalgröße, Software-dekodiert). */
    val frame: Bitmap? = movie?.let { m -> Bitmap.createBitmap(max(1, m.width()), max(1, m.height()), Bitmap.Config.ARGB_8888) }
    private val frameCanvas = frame?.let { Canvas(it) }
    private var lastMs = -1

    val width: Int get() = bitmap?.width ?: frame?.width ?: 1
    val height: Int get() = bitmap?.height ?: frame?.height ?: 1

    /** Liefert das Bild für Zeitpunkt [t] (bei GIFs das passende Einzelbild). */
    @Suppress("DEPRECATION")
    fun current(t: Float): Bitmap? {
        bitmap?.let { return it }
        val m = movie ?: return null
        val c = frameCanvas ?: return null
        val dur = m.duration().takeIf { it > 0 } ?: 1000
        val ms = ((t * 1000).toLong() % dur).toInt()
        if (ms == lastMs) return frame // gleiches Bild (z. B. für den Milchglas-Durchgang)
        lastMs = ms
        m.setTime(ms)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        m.draw(c, 0f, 0f)
        return frame
    }

    companion object {
        fun file(context: Context): File = File(context.filesDir, "background_image")

        /** Nur im Hintergrund-Thread aufrufen. */
        @Suppress("DEPRECATION")
        fun load(context: Context): BgImage? {
            val f = file(context)
            if (!f.exists() || f.length() == 0L) return null
            return runCatching {
                val bytes = f.readBytes()
                if (bytes.size > 6 && String(bytes, 0, 3) == "GIF") {
                    val m = Movie.decodeByteArray(bytes, 0, bytes.size)
                    if (m != null && m.width() > 0 && m.duration() > 0) return@runCatching BgImage(null, m)
                }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (max(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                bmp?.let { BgImage(it, null) }
            }.getOrNull()
        }
    }
}

/**
 * Animierter Hintergrund (Regen, Blasen, Verläufe, Bild/GIF). Läuft vsync-synchron über den
 * Choreographer und liefert nebenbei den verschwommenen "Milchglas"-Hintergrund für alle Glasflächen.
 */
class BackgroundView(context: Context) : View(context) {
    var theme: ThemeSettings = ThemeSettings()
        set(v) {
            field = v
            gradientShader = null
            backdropDirty = true
            invalidate()
            ensureLoop()
        }

    var image: BgImage? = null
        set(v) {
            field = v
            backdropDirty = true
            invalidate()
            ensureLoop()
        }

    /** true = 60 fps, false = 30 fps. */
    var smooth: Boolean = true

    private val start = SystemClock.uptimeMillis()
    private val bg = Paint()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bubble = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint()
    private val rainPaints = Array(3) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(190, 205, 226)
        }
    }
    private var rainLines = FloatArray(0)
    private var gradientShader: Shader? = null
    private var gradientW = 0
    private val filters = HashMap<Int, PorterDuffColorFilter>()
    private val dst = RectF()
    private val srcRect = Rect()

    /** Weiche Kugel, einmal vorgerendert und dann skaliert/getönt gezeichnet. */
    private val bubbleBmp: Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            64f, 64f, 64f,
            intArrayOf(Color.WHITE, Color.argb(110, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        Canvas(b).drawCircle(64f, 64f, 64f, p)
    }

    // ---- Milchglas
    private var small: Bitmap? = null
    private var smallCanvas: Canvas? = null
    private var pixels = IntArray(0)
    private var tmp = IntArray(0)
    private var backdropDirty = true
    private var lastBackdrop = 0L

    // ---- Animations-Takt
    private var looping = false
    private var frame = 0L
    private val frameCallback = Choreographer.FrameCallback { tick() }

    private val isAnimated: Boolean
        get() = (theme.showRain && theme.rainIntensity > 0.02f) ||
            (theme.showBubbles && theme.bubbleCount > 0) ||
            (theme.type == BgType.OWN && image?.movie != null)

    private fun ensureLoop() {
        if (looping || !isAttachedToWindow || windowVisibility != VISIBLE) return
        looping = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun tick() {
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isAnimated) {
            looping = false
            return
        }
        frame++
        if (smooth || frame % 2 == 0L) invalidate()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        looping = false
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            backdropDirty = true
            invalidate()
            ensureLoop()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradientShader = null
        if (w > 0 && h > 0) {
            val sw = max(8, w / BACKDROP_DOWNSCALE)
            val sh = max(8, h / BACKDROP_DOWNSCALE)
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            smallCanvas = Canvas(small!!)
            pixels = IntArray(sw * sh)
            tmp = IntArray(sw * sh)
            backdropDirty = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        drawScene(canvas, w, h, t)
        // Milchglas höchstens ~30× pro Sekunde neu berechnen
        val now = SystemClock.uptimeMillis()
        if (Backdrop.enabled && (backdropDirty || (isAnimated && now - lastBackdrop >= 32))) {
            lastBackdrop = now
            backdropDirty = false
            renderBackdrop(w, h, t)
        }
    }

    private fun renderBackdrop(w: Float, h: Float, t: Float) {
        val bmp = small ?: return
        val c = smallCanvas ?: return
        val s = bmp.width / w
        c.save()
        c.scale(s, s)
        drawScene(c, w, h, t)
        c.restore()
        val bw = bmp.width
        val bh = bmp.height
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        boxBlur(pixels, tmp, bw, bh, 3)
        boxBlur(pixels, tmp, bw, bh, 3)
        bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        Backdrop.publish(bmp, s)
    }

    // ---------------------------------------------------------------- Szene

    private fun drawScene(canvas: Canvas, w: Float, h: Float, t: Float) {
        when (theme.type) {
            BgType.RAIN -> {
                if (gradientShader == null || gradientW != w.toInt()) {
                    gradientW = w.toInt()
                    gradientShader = LinearGradient(
                        0f, 0f, 0f, h,
                        intArrayOf(0xFF0B1322.toInt(), 0xFF121C2E.toInt(), 0xFF05070C.toInt()), null, Shader.TileMode.CLAMP,
                    )
                    glow.shader = RadialGradient(w * 0.72f, h * 0.12f, w * 0.6f, 0x402E5585, 0, Shader.TileMode.CLAMP)
                }
                bg.shader = gradientShader
                canvas.drawRect(0f, 0f, w, h, bg)
                canvas.drawCircle(w * 0.72f, h * 0.12f, w * 0.6f, glow)
            }
            BgType.BLACK -> canvas.drawColor(Color.BLACK)
            BgType.COLORED -> {
                val cols = theme.colors.ifEmpty { listOf(0xFF222222.toInt()) }
                if (cols.size == 1) {
                    canvas.drawColor(cols[0])
                } else {
                    if (gradientShader == null || gradientW != w.toInt()) {
                        gradientW = w.toInt()
                        val rad = theme.gradientAngle / 180f * PI.toFloat()
                        val r = max(w, h) / 2
                        val dx = cos(rad) * r
                        val dy = sin(rad) * r
                        gradientShader = LinearGradient(
                            w / 2 - dx, h / 2 - dy, w / 2 + dx, h / 2 + dy,
                            cols.toIntArray(), null, Shader.TileMode.CLAMP,
                        )
                    }
                    bg.shader = gradientShader
                    canvas.drawRect(0f, 0f, w, h, bg)
                }
            }
            BgType.OWN -> {
                canvas.drawColor(Color.BLACK)
                drawImage(canvas, w, h, t)
                if (theme.ownDim > 0f) {
                    dim.color = Color.argb((theme.ownDim.coerceIn(0f, 0.9f) * 255).toInt(), 0, 0, 0)
                    canvas.drawRect(0f, 0f, w, h, dim)
                }
            }
        }
        if (theme.showBubbles) drawBubbles(canvas, w, h, t)
        if (theme.showRain) drawRain(canvas, w, h, t)
    }

    private fun drawImage(canvas: Canvas, w: Float, h: Float, t: Float) {
        val im = image ?: return
        val bmp = im.current(t) ?: return
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        val scale = max(w / iw, h / ih)
        val dw = iw * scale
        val dh = ih * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        srcRect.set(0, 0, bmp.width, bmp.height)
        dst.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, srcRect, dst, img)
    }

    private fun filter(color: Int): PorterDuffColorFilter =
        filters.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    private fun drawBubbles(canvas: Canvas, w: Float, h: Float, t: Float) {
        val n = theme.bubbleCount.coerceIn(0, 40)
        val base = min(w, h)
        for (i in 0 until n) {
            val speed = (0.03f + rnd(i, 1) * 0.05f) * theme.bubbleSpeed
            val px = rnd(i, 2) * 2 * PI.toFloat()
            val py = rnd(i, 3) * 2 * PI.toFloat()
            val x = w * (0.5f + 0.48f * sin(t * speed * 1.3f + px) * (0.6f + 0.4f * rnd(i, 4)))
            val y = h * (0.5f + 0.48f * cos(t * speed + py) * (0.6f + 0.4f * rnd(i, 5)))
            // sanftes "Atmen" der Größe
            val r = base * (0.06f + rnd(i, 6) * 0.16f) * theme.bubbleSize * (0.92f + 0.08f * sin(t * 0.4f + px))
            val color = when (theme.bubbleColor) {
                BubbleColor.GRAY -> 0xFF8E8E93.toInt()
                BubbleColor.WHITE -> Color.WHITE
                BubbleColor.THEME -> lighten(theme.colors.ifEmpty { listOf(0xFF8E8E93.toInt()) }.let { it[i % it.size] })
            }
            bubble.colorFilter = filter(color)
            bubble.alpha = (theme.bubbleOpacity * (0.45f + 0.55f * rnd(i, 7)) * 255).roundToInt().coerceIn(0, 255)
            dst.set(x - r, y - r, x + r, y + r)
            canvas.drawBitmap(bubbleBmp, null, dst, bubble)
        }
    }

    private fun drawRain(canvas: Canvas, w: Float, h: Float, t: Float) {
        val n = (90 * theme.rainIntensity).roundToInt().coerceIn(0, 300)
        if (n == 0) return
        // Wind mit langsamen Böen
        val wind = theme.rainWind * (0.75f + 0.35f * sin(t * 0.35f) + 0.15f * sin(t * 1.1f))
        if (rainLines.size < n * 4 * 3) rainLines = FloatArray(n * 4 * 3)
        val counts = IntArray(3)
        for (i in 0 until n) {
            val depth = 0.4f + rnd(i, 11) * 0.6f // nah = schneller, länger, heller
            val layer = when {
                depth < 0.6f -> 0
                depth < 0.8f -> 1
                else -> 2
            }
            val speed = h * (0.35f + 0.25f * depth) * theme.rainSpeed
            val len = h * (0.025f + 0.035f * depth)
            val period = (h + len * 2) / speed
            val phase = rnd(i, 12) * period
            val p = ((t + phase) % period) / period
            val y = -len + p * (h + len * 2)
            val drift = wind * 0.35f * y
            val x0 = rnd(i, 13) * (w + h * 0.4f) - h * 0.2f
            val x = ((x0 + drift) % (w + 40f) + w + 40f) % (w + 40f) - 20f
            val dx = wind * 0.35f * len
            val k = (layer * n + counts[layer]) * 4
            rainLines[k] = x
            rainLines[k + 1] = y
            rainLines[k + 2] = x + dx
            rainLines[k + 3] = y + len
            counts[layer]++
        }
        val d = resources.displayMetrics.density
        for (layer in 0 until 3) {
            if (counts[layer] == 0) continue
            val p = rainPaints[layer]
            p.alpha = (255 * (0.16f + 0.14f * layer)).toInt()
            p.strokeWidth = (0.9f + 0.55f * layer) * d
            canvas.drawLines(rainLines, layer * n * 4, counts[layer] * 4, p)
        }
    }

    private fun lighten(c: Int): Int = Color.rgb(
        min(255, Color.red(c) + 64), min(255, Color.green(c) + 64), min(255, Color.blue(c) + 64),
    )

    private fun rnd(i: Int, salt: Int): Float {
        var x = i * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFFFF) / 16777215f
    }

    companion object {
        /** Milchglas-Auflösung: 1/10 des Bildschirms reicht, es wird ohnehin weichgezeichnet. */
        private const val BACKDROP_DOWNSCALE = 10

        /** Schneller Kastenfilter (horizontal + vertikal) – zweimal angewendet ≈ Gauß. */
        private fun boxBlur(px: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
            val div = 2 * r + 1
            for (y in 0 until h) {
                val row = y * w
                var a = 0
                var rr = 0
                var g = 0
                var b = 0
                for (i in -r..r) {
                    val p = px[row + i.coerceIn(0, w - 1)]
                    a += p ushr 24; rr += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                }
                for (x in 0 until w) {
                    tmp[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                    val pOut = px[row + (x - r).coerceIn(0, w - 1)]
                    val pIn = px[row + (x + r + 1).coerceIn(0, w - 1)]
                    a += (pIn ushr 24) - (pOut ushr 24)
                    rr += ((pIn shr 16) and 255) - ((pOut shr 16) and 255)
                    g += ((pIn shr 8) and 255) - ((pOut shr 8) and 255)
                    b += (pIn and 255) - (pOut and 255)
                }
            }
            for (x in 0 until w) {
                var a = 0
                var rr = 0
                var g = 0
                var b = 0
                for (i in -r..r) {
                    val p = tmp[i.coerceIn(0, h - 1) * w + x]
                    a += p ushr 24; rr += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                }
                for (y in 0 until h) {
                    px[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                    val pOut = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                    val pIn = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    a += (pIn ushr 24) - (pOut ushr 24)
                    rr += ((pIn shr 16) and 255) - ((pOut shr 16) and 255)
                    g += ((pIn shr 8) and 255) - ((pOut shr 8) and 255)
                    b += (pIn and 255) - (pOut and 255)
                }
            }
        }
    }
}

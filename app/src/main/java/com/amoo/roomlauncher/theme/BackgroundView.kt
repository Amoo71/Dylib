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
import android.view.View
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Eigenes Hintergrundbild: statisch oder GIF (über Movie – läuft auch auf Android 4.4). */
class BgImage(val bitmap: Bitmap?, @Suppress("DEPRECATION") val movie: Movie?) {
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
                    if (m != null && m.width() > 0) return@runCatching BgImage(null, m)
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

class BackgroundView(context: Context) : View(context) {
    var theme: ThemeSettings = ThemeSettings()
        set(v) {
            field = v
            gradientShader = null
            invalidate()
        }

    var image: BgImage? = null
        set(v) {
            field = v
            // Movie zeichnet nur in Software korrekt
            setLayerType(if (v?.movie != null) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE, null)
            invalidate()
        }

    private val start = SystemClock.uptimeMillis()
    private val bg = Paint()
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bubble = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rainPaints = Array(3) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(184, 199, 221)
        }
    }
    private val rainLines = Array(3) { FloatArray(0) }
    private var gradientShader: Shader? = null
    private val filters = HashMap<Int, PorterDuffColorFilter>()
    private val dst = RectF()
    private val srcRect = Rect()

    /** Weiche Kugel, einmal vorgerendert und dann skaliert/getönt gezeichnet. */
    private val bubbleBmp: Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            64f, 64f, 64f,
            intArrayOf(Color.WHITE, Color.argb(90, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawCircle(64f, 64f, 64f, p)
    }

    private val isAnimated: Boolean
        get() = theme.showRain || theme.showBubbles || image?.movie != null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradientShader = null
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        when (theme.type) {
            BgType.RAIN -> {
                if (gradientShader == null) {
                    gradientShader = LinearGradient(
                        0f, 0f, 0f, h,
                        intArrayOf(0xFF0B1220.toInt(), 0xFF111A2B.toInt(), 0xFF06080D.toInt()), null, Shader.TileMode.CLAMP,
                    )
                    glow.shader = RadialGradient(w * 0.7f, h * 0.15f, w * 0.6f, 0x332A4C7A, 0, Shader.TileMode.CLAMP)
                }
                bg.shader = gradientShader
                canvas.drawRect(0f, 0f, w, h, bg)
                canvas.drawCircle(w * 0.7f, h * 0.15f, w * 0.6f, glow)
            }
            BgType.BLACK -> canvas.drawColor(Color.BLACK)
            BgType.COLORED -> {
                val cols = theme.colors.ifEmpty { listOf(0xFF222222.toInt()) }
                if (cols.size == 1) {
                    canvas.drawColor(cols[0])
                } else {
                    if (gradientShader == null) {
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
                drawImage(canvas, t)
                if (theme.ownDim > 0f) canvas.drawColor(Color.argb((theme.ownDim.coerceIn(0f, 0.9f) * 255).toInt(), 0, 0, 0))
            }
        }
        if (theme.showBubbles) drawBubbles(canvas, t)
        if (theme.showRain) drawRain(canvas, t)
        if (isAnimated) postInvalidateDelayed(33)
    }

    @Suppress("DEPRECATION")
    private fun drawImage(canvas: Canvas, t: Float) {
        val im = image ?: return
        val iw = (im.bitmap?.width ?: im.movie?.width() ?: return).toFloat()
        val ih = (im.bitmap?.height ?: im.movie?.height() ?: return).toFloat()
        val scale = max(width / iw, height / ih)
        val dw = iw * scale
        val dh = ih * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        im.bitmap?.let {
            srcRect.set(0, 0, it.width, it.height)
            dst.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(it, srcRect, dst, img)
        }
        im.movie?.let { m ->
            val dur = m.duration().takeIf { it > 0 } ?: 1000
            m.setTime(((t * 1000).toLong() % dur).toInt())
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            m.draw(canvas, 0f, 0f)
            canvas.restore()
        }
    }

    private fun filter(color: Int): PorterDuffColorFilter =
        filters.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    private fun drawBubbles(canvas: Canvas, t: Float) {
        val n = theme.bubbleCount.coerceIn(0, 40)
        val w = width.toFloat()
        val h = height.toFloat()
        val base = min(w, h)
        for (i in 0 until n) {
            val speed = (0.03f + rnd(i, 1) * 0.05f) * theme.bubbleSpeed
            val px = rnd(i, 2) * 2 * PI.toFloat()
            val py = rnd(i, 3) * 2 * PI.toFloat()
            val x = w * (0.5f + 0.48f * sin(t * speed * 1.3f + px) * (0.6f + 0.4f * rnd(i, 4)))
            val y = h * (0.5f + 0.48f * cos(t * speed + py) * (0.6f + 0.4f * rnd(i, 5)))
            val r = base * (0.06f + rnd(i, 6) * 0.16f) * theme.bubbleSize
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

    private fun drawRain(canvas: Canvas, t: Float) {
        val n = (90 * theme.rainIntensity).roundToInt().coerceIn(0, 300)
        val w = width.toFloat()
        val h = height.toFloat()
        // Wind mit langsamen Böen
        val wind = theme.rainWind * (0.75f + 0.35f * sin(t * 0.35f) + 0.15f * sin(t * 1.1f))
        val counts = IntArray(3)
        for (layer in 0 until 3) if (rainLines[layer].size < n * 4) rainLines[layer] = FloatArray(n * 4)
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
            val arr = rainLines[layer]
            val k = counts[layer] * 4
            arr[k] = x
            arr[k + 1] = y
            arr[k + 2] = x + dx
            arr[k + 3] = y + len
            counts[layer]++
        }
        val d = resources.displayMetrics.density
        for (layer in 0 until 3) {
            val p = rainPaints[layer]
            p.alpha = (255 * (0.16f + 0.13f * layer)).toInt()
            p.strokeWidth = (0.9f + 0.5f * layer) * d
            if (counts[layer] > 0) canvas.drawLines(rainLines[layer], 0, counts[layer] * 4, p)
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
}

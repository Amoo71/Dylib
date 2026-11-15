package com.amoo.roomlauncher.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Eigenes Hintergrundbild – statisch (PNG/JPEG/WebP) oder animiert (GIF/animiertes WebP). */
class BgImage private constructor(
    private val bitmap: ImageBitmap? = null,
    private val drawable: Drawable? = null,
    @Suppress("DEPRECATION") private val movie: Movie? = null,
) {
    val width: Int = bitmap?.width ?: drawable?.intrinsicWidth ?: movie?.width() ?: 1
    val height: Int = bitmap?.height ?: drawable?.intrinsicHeight ?: movie?.height() ?: 1

    /** Zeichnet das Bild "center crop" über die ganze Fläche. */
    @Suppress("DEPRECATION")
    fun draw(scope: DrawScope, timeSec: Float) = with(scope) {
        val scale = max(size.width / width.coerceAtLeast(1), size.height / height.coerceAtLeast(1))
        val w = width * scale
        val h = height * scale
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        when {
            bitmap != null -> drawImage(
                bitmap,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            )
            drawable != null -> drawIntoCanvas { c ->
                drawable.setBounds(left.roundToInt(), top.roundToInt(), (left + w).roundToInt(), (top + h).roundToInt())
                drawable.draw(c.nativeCanvas)
            }
            movie != null -> drawIntoCanvas { c ->
                val dur = movie.duration().takeIf { it > 0 } ?: 1000
                movie.setTime(((timeSec * 1000).toLong() % dur).toInt())
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(left, top)
                nc.scale(scale, scale)
                movie.draw(nc, 0f, 0f)
                nc.restore()
            }
            else -> Unit
        }
    }

    companion object {
        fun file(context: Context): File = File(context.filesDir, "background_image")

        /** Lädt das gespeicherte Bild. Nur im Hintergrund-Thread aufrufen. */
        @Suppress("DEPRECATION")
        fun load(context: Context): BgImage? {
            val f = file(context)
            if (!f.exists() || f.length() == 0L) return null
            return runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    val src = ImageDecoder.createSource(f)
                    val d = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                        val longest = max(info.size.width, info.size.height)
                        if (longest > 2048) {
                            val s = 2048f / longest
                            decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                        }
                    }
                    if (d is AnimatedImageDrawable) {
                        d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        d.start()
                        BgImage(drawable = d)
                    } else {
                        BgImage(drawable = d)
                    }
                } else {
                    val head = f.inputStream().use { s -> ByteArray(6).also { s.read(it) } }
                    if (String(head).startsWith("GIF")) {
                        val m = f.inputStream().use { Movie.decodeStream(it) }
                        if (m != null) return@runCatching BgImage(movie = m)
                    }
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, opts)
                    var sample = 1
                    while (max(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
                    val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    bmp?.let { BgImage(bitmap = it.asImageBitmap()) }
                }
            }.getOrNull()
        }
    }
}

/** Alles, was nötig ist, um den Hintergrund irgendwo (auch hinter Glas) erneut zu zeichnen. */
class Backdrop(
    val theme: ThemeSettings,
    val time: MutableFloatState,
    val image: BgImage?,
    val screenSize: Size,
)

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

@Composable
fun rememberThemeClock(): MutableFloatState {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> t.floatValue = (now - start) / 1_000_000_000f }
        }
    }
    return t
}

@Composable
fun ThemeBackground(backdrop: Backdrop, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) { drawTheme(backdrop.theme, backdrop.time.floatValue, backdrop.image) }
}

// ------------------------------------------------------------------ Zeichnen

private fun rnd(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777215f
}

fun DrawScope.drawTheme(theme: ThemeSettings, t: Float, image: BgImage?) {
    when (theme.type) {
        BgType.RAIN -> {
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF111A2B), Color(0xFF06080D))),
            )
            // diffuses Licht hinter dem Regen
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0x332A4C7A), Color.Transparent),
                    center = Offset(size.width * 0.7f, size.height * 0.15f),
                    radius = size.width * 0.6f,
                ),
                radius = size.width * 0.6f,
                center = Offset(size.width * 0.7f, size.height * 0.15f),
            )
        }
        BgType.BLACK -> drawRect(Color.Black)
        BgType.COLORED -> {
            val cols = theme.colors.ifEmpty { listOf(0xFF222222.toInt()) }.map { Color(it) }
            if (cols.size == 1) {
                drawRect(cols[0])
            } else {
                val rad = theme.gradientAngle / 180f * PI.toFloat()
                val cx = size.width / 2
                val cy = size.height / 2
                val r = max(size.width, size.height) / 2
                val dx = cos(rad) * r
                val dy = sin(rad) * r
                drawRect(Brush.linearGradient(cols, start = Offset(cx - dx, cy - dy), end = Offset(cx + dx, cy + dy)))
            }
        }
        BgType.OWN -> {
            drawRect(Color.Black)
            image?.draw(this, t)
            if (theme.ownDim > 0f) drawRect(Color.Black.copy(alpha = theme.ownDim.coerceIn(0f, 0.9f)))
        }
    }
    if (theme.showBubbles) drawBubbles(theme, t)
    if (theme.showRain) drawRain(theme, t)
}

private fun DrawScope.drawBubbles(theme: ThemeSettings, t: Float) {
    val n = theme.bubbleCount.coerceIn(0, 40)
    val base = minOf(size.width, size.height)
    for (i in 0 until n) {
        val speed = (0.03f + rnd(i, 1) * 0.05f) * theme.bubbleSpeed
        val px = rnd(i, 2) * 2 * PI.toFloat()
        val py = rnd(i, 3) * 2 * PI.toFloat()
        val x = size.width * (0.5f + 0.48f * sin(t * speed * 1.3f + px) * (0.6f + 0.4f * rnd(i, 4)))
        val y = size.height * (0.5f + 0.48f * cos(t * speed + py) * (0.6f + 0.4f * rnd(i, 5)))
        val r = base * (0.06f + rnd(i, 6) * 0.16f) * theme.bubbleSize
        val color = when (theme.bubbleColor) {
            BubbleColor.GRAY -> Color(0xFF8E8E93)
            BubbleColor.WHITE -> Color.White
            BubbleColor.THEME -> theme.colors.ifEmpty { listOf(0xFF8E8E93.toInt()) }
                .let { Color(it[i % it.size]) }
                .let { c -> Color(red = (c.red + 0.25f).coerceAtMost(1f), green = (c.green + 0.25f).coerceAtMost(1f), blue = (c.blue + 0.25f).coerceAtMost(1f)) }
        }
        val a = theme.bubbleOpacity * (0.45f + 0.55f * rnd(i, 7))
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(alpha = a), color.copy(alpha = a * 0.35f), Color.Transparent),
                center = Offset(x, y),
                radius = r,
            ),
            radius = r,
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawRain(theme: ThemeSettings, t: Float) {
    val n = (90 * theme.rainIntensity).roundToInt().coerceIn(0, 300)
    // Wind mit langsamen Böen
    val wind = theme.rainWind * (0.75f + 0.35f * sin(t * 0.35f) + 0.15f * sin(t * 1.1f))
    val h = size.height
    for (i in 0 until n) {
        val depth = 0.4f + rnd(i, 11) * 0.6f // nah = schneller, länger, heller
        val speed = h * (0.35f + 0.25f * depth) * theme.rainSpeed
        val len = h * (0.025f + 0.035f * depth)
        val period = (h + len * 2) / speed
        val phase = rnd(i, 12) * period
        val p = ((t + phase) % period) / period
        val y = -len + p * (h + len * 2)
        val drift = wind * 0.35f * y
        val x0 = rnd(i, 13) * (size.width + h * 0.4f) - h * 0.2f
        val x = ((x0 + drift) % (size.width + 40f) + size.width + 40f) % (size.width + 40f) - 20f
        val dx = wind * 0.35f * len
        drawLine(
            color = Color(0xFFB8C7DD).copy(alpha = 0.10f + 0.28f * depth),
            start = Offset(x, y),
            end = Offset(x + dx, y + len),
            strokeWidth = 0.8f + 1.0f * depth,
            cap = StrokeCap.Round,
        )
    }
}

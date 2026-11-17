package com.amoo.roomlauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ Farben & Maße

object C {
    val TEXT = 0xF5FFFFFF.toInt()
    val TEXT2 = 0xA6FFFFFF.toInt()
    val TEXT3 = 0x66FFFFFF.toInt()
    val GREEN = 0xFF30D158.toInt()
    val BLUE = 0xFF0A84FF.toInt()
    val RED = 0xFFFF453A.toInt()
    val ORANGE = 0xFFFF9F0A.toInt()
    val YELLOW = 0xFFFFD60A.toInt()

    fun white(a: Float): Int = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

val EASE_OUT = DecelerateInterpolator(2f)
val SPRING = OvershootInterpolator(1.4f)

object Fonts {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
}

// ------------------------------------------------------------------ Flows an Views binden

/** Sammelt einen Flow, solange die View im Fenster hängt. StateFlows liefern sofort den aktuellen Wert. */
fun <T> View.bind(flow: Flow<T>, block: (T) -> Unit) {
    if (flow is StateFlow<T>) block(flow.value)
    var job: Job? = null
    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            job?.cancel()
            job = CoroutineScope(Dispatchers.Main.immediate).launch { flow.collect { block(it) } }
        }

        override fun onViewDetachedFromWindow(v: View) {
            job?.cancel()
            job = null
        }
    }
    addOnAttachStateChangeListener(listener)
    if (isAttachedToWindow) listener.onViewAttachedToWindow(this)
}

// ------------------------------------------------------------------ Milchglas-Hintergrund

/**
 * Der weichgezeichnete, verkleinerte Hintergrund, den alle Glasflächen "durchscheinen" lassen.
 * Wird vom [com.amoo.roomlauncher.theme.BackgroundView] laufend aktualisiert.
 */
object Backdrop {
    /** Aus = Glas ist nur transparent (spart Leistung). */
    var enabled = true
        set(v) {
            field = v
            invalidateAll()
        }

    var bitmap: Bitmap? = null
        private set

    /** Bitmap-Pixel pro Bildschirm-Pixel. */
    var scale = 1f
        private set

    private val hosts = WeakHashMap<View, Boolean>()

    fun register(v: View) {
        hosts[v] = true
    }

    fun publish(bmp: Bitmap, s: Float) {
        bitmap = bmp
        scale = s
        invalidateAll()
    }

    fun invalidateAll() {
        if (hosts.isEmpty()) return
        for (v in hosts.keys.toList()) if (v.isAttachedToWindow && v.isShown) v.invalidate()
    }
}

/**
 * "Liquid Glass": verschwommener Hintergrund (Milchglas), dunkle Tönung, heller Verlauf,
 * glänzende Kante und Lichtreflex oben.
 */
class GlassDrawable(
    context: Context,
    radiusDp: Float,
    strength: Float = 1f,
    private val baseColor: Int = 0x330A0C14,
    /** true = zeigt den verschwommenen Hintergrund (für Karten, Dock, Panels). */
    private val frosted: Boolean = true,
) : Drawable() {
    var strength: Float = strength
        set(v) {
            field = v
            updateShaders(bounds)
            invalidateSelf()
        }

    private val radius = context.dpf(radiusDp)
    private val strokeW = context.dpf(1)
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
    }
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = VIBRANCY
    }
    private var frostBitmap: Bitmap? = null
    private var frostShader: BitmapShader? = null
    private val matrix = Matrix()
    private val loc = IntArray(2)
    private val rect = RectF()

    override fun onBoundsChange(b: Rect) = updateShaders(b)

    private fun updateShaders(b: Rect) {
        rect.set(b)
        rect.inset(strokeW / 2, strokeW / 2)
        base.color = baseColor
        val s = strength
        fill.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            C.white(0.15f * s), C.white(0.03f * s), Shader.TileMode.CLAMP,
        )
        shine.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.top + max(1f, b.height() * 0.5f),
            C.white(0.10f * s), 0, Shader.TileMode.CLAMP,
        )
        stroke.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(C.white(0.60f * s), C.white(0.07f), C.white(0.08f), C.white(0.30f * s)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        if (frosted && Backdrop.enabled) drawFrost(canvas, r)
        canvas.drawRoundRect(rect, r, r, base)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, shine)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    /** Zeichnet den passenden Ausschnitt des verschwommenen Hintergrunds. */
    private fun drawFrost(canvas: Canvas, r: Float) {
        val host = callback as? View ?: return
        Backdrop.register(host)
        val bmp = Backdrop.bitmap ?: return
        if (bmp !== frostBitmap || frostShader == null) {
            frostBitmap = bmp
            frostShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            frost.shader = frostShader
        }
        host.getLocationInWindow(loc)
        val inv = 1f / Backdrop.scale
        matrix.setScale(inv, inv)
        matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
        frostShader?.setLocalMatrix(matrix)
        canvas.drawRoundRect(rect, r, r, frost)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Etwas mehr Sättigung und Helligkeit – wie iOS "Vibrancy". */
        private val VIBRANCY = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(1.35f)
                postConcat(ColorMatrix(floatArrayOf(1.05f, 0f, 0f, 0f, 10f, 0f, 1.05f, 0f, 0f, 10f, 0f, 0f, 1.05f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
            },
        )
    }
}

/** Einfarbig abgerundeter Hintergrund (optional mit Rand). */
class RoundDrawable(context: Context, radiusDp: Float, var color: Int, var strokeColor: Int = 0) : Drawable() {
    private val radius = context.dpf(radiusDp)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1)
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        if (color != 0) {
            p.color = color
            canvas.drawRoundRect(rect, r, r, p)
        }
        if (strokeColor != 0) {
            s.color = strokeColor
            rect.inset(s.strokeWidth / 2, s.strokeWidth / 2)
            canvas.drawRoundRect(rect, r, r, s)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ------------------------------------------------------------------ Text & Layout-Helfer

fun Context.label(
    text: CharSequence,
    sp: Float = 16f,
    color: Int = C.TEXT,
    bold: Boolean = false,
    light: Boolean = false,
    thin: Boolean = false,
    lines: Int = 1,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    includeFontPadding = false
    typeface = when {
        bold -> Fonts.medium
        thin -> Fonts.thin
        light -> Fonts.light
        else -> Fonts.regular
    }
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

/** Federndes Einsinken beim Drücken + Klick. */
fun View.pressable(onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    isClickable = true
    setOnClickListener { onClick() }
    if (onLongClick != null) {
        setOnLongClickListener {
            onLongClick()
            true
        }
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(110).setInterpolator(EASE_OUT).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(SPRING).start()
        }
        false
    }
}

fun Context.glassButton(text: String, highlighted: Boolean = false, onClick: () -> Unit): TextView =
    label(text, 15f, bold = true).apply {
        gravity = Gravity.CENTER
        background = if (highlighted) RoundDrawable(context, 22f, C.white(0.92f)) else GlassDrawable(context, 22f, 1f, frosted = false)
        if (highlighted) setTextColor(Color.BLACK)
        setPadding(dp(18), dp(11), dp(18), dp(11))
        pressable(onClick = onClick)
    }

/** Auswahl-Chip: weiß hinterlegt, wenn ausgewählt. */
class Chip(context: Context, text: String, selected: Boolean = false, onClick: (Chip) -> Unit) : TextView(context) {
    private val bg = RoundDrawable(context, 18f, 0)

    var chipSelected: Boolean = selected
        set(v) {
            field = v
            bg.color = if (v) C.white(0.92f) else C.white(0.10f)
            bg.strokeColor = if (v) 0 else C.white(0.12f)
            setTextColor(if (v) Color.BLACK else C.TEXT)
            invalidate()
        }

    init {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Fonts.medium
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        background = bg
        setPadding(dp(15), dp(9), dp(15), dp(9))
        chipSelected = selected
        pressable { onClick(this) }
    }
}

fun Context.hScroll(vararg children: View, spacingDp: Int = 8): HorizontalScrollView {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    children.forEachIndexed { i, v -> row.addView(v, (v.layoutParams as? LinearLayout.LayoutParams ?: lp(WRAP, WRAP)).margins(l = if (i > 0) dp(spacingDp) else 0)) }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(row)
    }
}

fun Context.row(vararg children: View, spacingDp: Int = 0, center: Boolean = true): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        if (center) gravity = Gravity.CENTER_VERTICAL
        children.forEachIndexed { i, v ->
            val p = (v.layoutParams as? LinearLayout.LayoutParams) ?: lp(WRAP, WRAP)
            if (i > 0 && spacingDp > 0) p.leftMargin = dp(spacingDp)
            addView(v, p)
        }
    }

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.spacer(dpH: Int): View = View(this).apply { layoutParams = lp(MATCH, dp(dpH)) }

fun Context.flexSpace(): View = View(this).apply { layoutParams = lp(MATCH, 0, 1f) }

fun Context.dot(color: Int, sizeDp: Int = 9): View = View(this).apply {
    background = RoundDrawable(context, sizeDp / 2f, color)
    layoutParams = lp(dp(sizeDp), dp(sizeDp))
}

fun Context.scroll(content: View): ScrollView = ScrollView(this).apply {
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    isVerticalFadingEdgeEnabled = true
    setFadingEdgeLength(dp(18))
    addView(content)
}

/** Glas-Karte mit vertikalem Inhalt (Milchglas). */
fun Context.glassCard(paddingDp: Int = 20, radiusDp: Float = 30f): LinearLayout = column().apply {
    background = GlassDrawable(context, radiusDp)
    setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
}

// ------------------------------------------------------------------ Icons

enum class Glyph { GEAR, GRID, POWER, CLOSE, PLUS, BOLT, SUN }

class IconView(context: Context, private val glyph: Glyph, var color: Int = C.TEXT) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        p.color = color
        f.color = color
        p.strokeWidth = s * 0.1f
        when (glyph) {
            Glyph.GEAR -> {
                p.strokeWidth = s * 0.12f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * s * 0.30f, cy + sn * s * 0.30f, cx + c * s * 0.45f, cy + sn * s * 0.45f, p)
                }
                p.strokeWidth = s * 0.09f
                canvas.drawCircle(cx, cy, s * 0.30f, p)
                canvas.drawCircle(cx, cy, s * 0.12f, p)
            }
            Glyph.GRID -> {
                val step = s / 3f
                val ox = cx - s / 2
                val oy = cy - s / 2
                for (x in 0..2) for (y in 0..2) canvas.drawCircle(ox + step * x + step / 2, oy + step * y + step / 2, step * 0.27f, f)
            }
            Glyph.POWER -> {
                p.strokeWidth = s * 0.11f
                canvas.drawArc(RectF(cx - s * 0.35f, cy - s * 0.3f, cx + s * 0.35f, cy + s * 0.4f), -60f, 300f, false, p)
                canvas.drawLine(cx, cy - s * 0.44f, cx, cy, p)
            }
            Glyph.CLOSE -> {
                val d = s * 0.26f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, p)
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, p)
            }
            Glyph.PLUS -> {
                val d = s * 0.3f
                canvas.drawLine(cx - d, cy, cx + d, cy, p)
                canvas.drawLine(cx, cy - d, cx, cy + d, p)
            }
            Glyph.BOLT -> {
                path.reset()
                path.moveTo(cx + s * 0.08f, cy - s * 0.45f)
                path.lineTo(cx - s * 0.25f, cy + s * 0.06f)
                path.lineTo(cx - s * 0.01f, cy + s * 0.06f)
                path.lineTo(cx - s * 0.08f, cy + s * 0.45f)
                path.lineTo(cx + s * 0.25f, cy - s * 0.06f)
                path.lineTo(cx + s * 0.01f, cy - s * 0.06f)
                path.close()
                canvas.drawPath(path, f)
            }
            Glyph.SUN -> {
                p.strokeWidth = s * 0.09f
                canvas.drawCircle(cx, cy, s * 0.17f, f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * s * 0.3f, cy + sn * s * 0.3f, cx + c * s * 0.42f, cy + sn * s * 0.42f, p)
                }
            }
        }
    }
}

fun Context.glassIconButton(glyph: Glyph, sizeDp: Int = 44, frosted: Boolean = false, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        background = GlassDrawable(context, sizeDp / 2f, 1f, frosted = frosted)
        addView(IconView(context, glyph), FrameLayout.LayoutParams((dp(sizeDp) * 0.48f).toInt(), (dp(sizeDp) * 0.48f).toInt(), Gravity.CENTER))
        layoutParams = lp(dp(sizeDp), dp(sizeDp))
        pressable(onClick = onClick)
    }

// ------------------------------------------------------------------ Slider

/** iOS-Kontrollzentrum-Slider: dicker Balken, der sich füllt – oder Verlauf mit Daumen. */
class GlassSlider(
    context: Context,
    private val heightDp: Int = 44,
    private val gradient: IntArray? = null,
    private val glyph: Glyph? = null,
    var onChange: (Float) -> Unit = {},
) : View(context) {
    var value: Float = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                invalidate()
            }
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.14f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.95f) }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(context.dpf(3), 0f, context.dpf(1), 0x55000000)
    }
    private val grad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val clip = Path()
    private val rect = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false

    init {
        // Der Schatten des Daumens braucht Software-Rendering nur für diesen kleinen View
        if (gradient != null) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(heightDp))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, h / 2f, h / 2f, Path.Direction.CW)
        if (gradient != null) grad.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, gradient, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (gradient != null) {
            canvas.drawRoundRect(rect, h / 2, h / 2, grad)
            val r = h / 2 - dp(4)
            canvas.drawCircle(h / 2 + (w - h) * value, h / 2, r, thumb)
        } else {
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(rect, track)
            canvas.drawRect(0f, 0f, w * value, h, fill)
            canvas.restore()
            if (glyph == Glyph.SUN) {
                // kleine Sonne links – dunkel auf hellem, hell auf dunklem Grund
                val cx = h / 2
                val cy = h / 2
                val s = h * 0.42f
                iconPaint.color = if (w * value > h * 0.8f) 0xCC000000.toInt() else C.white(0.8f)
                iconPaint.strokeWidth = s * 0.1f
                iconPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, s * 0.18f, iconPaint)
                iconPaint.style = Paint.Style.STROKE
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * s * 0.32f, cy + sn * s * 0.32f, cx + c * s * 0.46f, cy + sn * s * 0.46f, iconPaint)
                }
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) set(e.x)
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) set(e.x)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun set(x: Float) {
        val h = if (gradient != null) height.toFloat() else 0f
        value = ((x - h / 2) / max(1f, width - h)).coerceIn(0f, 1f)
        onChange(value)
    }
}

// ------------------------------------------------------------------ Schalter

class GlassSwitch(context: Context, checked: Boolean, var onToggle: (Boolean) -> Unit = {}) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private var pos = if (checked) 1f else 0f
    private var anim: ValueAnimator? = null

    var checked: Boolean = checked
        set(v) {
            if (field == v) return
            field = v
            anim?.cancel()
            anim = ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
                duration = 260
                interpolator = SPRING
                addUpdateListener {
                    pos = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    init {
        layoutParams = lp(dp(52), dp(32))
        pressable {
            if (isEnabled) {
                this.checked = !this.checked
                onToggle(this.checked)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        track.color = blend(C.white(0.16f), C.GREEN, pos.coerceIn(0f, 1f))
        if (!isEnabled) track.alpha = 90
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val r = h / 2 - dp(2)
        knob.alpha = if (isEnabled) 255 else 150
        canvas.drawCircle(h / 2 + (width - h) * pos, h / 2, r, knob)
    }
}

fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return Color.argb(
        ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
        ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)),
    )
}

// ------------------------------------------------------------------ Power-Knopf

class PowerButton(context: Context, sizeDp: Int, onClick: () -> Unit) : View(context) {
    var on = false
        set(v) {
            if (field != v) {
                field = v
                animateGlow()
            }
        }
    var ringColor = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    private var glow = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = RectF()

    init {
        layoutParams = lp(dp(sizeDp), dp(sizeDp))
        pressable(onClick = onClick)
    }

    private fun animateGlow() {
        ValueAnimator.ofFloat(glow, if (on) 1f else 0f).apply {
            duration = 240
            addUpdateListener {
                glow = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val c = s / 2
        fill.color = blend(C.white(0.10f), C.white(0.95f), glow)
        canvas.drawCircle(c, c, c - dp(2), fill)
        ring.strokeWidth = dp(3).toFloat()
        ring.color = blend(C.white(0.2f), ringColor, glow)
        canvas.drawCircle(c, c, c - dp(2), ring)
        glyph.color = blend(C.TEXT, Color.BLACK, glow)
        val g = s * 0.42f
        glyph.strokeWidth = g * 0.12f
        arc.set(c - g * 0.36f, c - g * 0.28f, c + g * 0.36f, c + g * 0.44f)
        canvas.drawArc(arc, -60f, 300f, false, glyph)
        canvas.drawLine(c, c - g * 0.46f, c, c + g * 0.02f, glyph)
    }
}

// ------------------------------------------------------------------ Segment-Auswahl (mit gleitender Pille)

class Segmented(
    context: Context,
    private val labels: List<String>,
    textSp: Float = 14f,
    private val heightDp: Int = 50,
    var onSelect: (Int) -> Unit,
) : View(context) {
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.92f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium
        textAlign = Paint.Align.CENTER
        textSize = context.dpf(textSp) * context.resources.configuration.fontScale
    }
    private val rect = RectF()
    private var pillPos = -1f
    private var anim: ValueAnimator? = null

    var selected: Int = -1
        set(v) {
            if (field == v) return
            val from = if (field < 0) v.toFloat() else pillPos
            field = v
            anim?.cancel()
            if (v < 0) {
                pillPos = -1f
                invalidate()
                return
            }
            anim = ValueAnimator.ofFloat(from, v.toFloat()).apply {
                duration = 320
                interpolator = SPRING
                addUpdateListener {
                    pillPos = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    init {
        background = GlassDrawable(context, 26f, 1f, frosted = false)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(heightDp))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = labels.size
        if (n == 0) return
        val inner = width - paddingLeft - paddingRight
        val segW = inner / n.toFloat()
        val h = height - paddingTop - paddingBottom.toFloat()
        if (pillPos >= 0f) {
            rect.set(paddingLeft + segW * pillPos, paddingTop.toFloat(), paddingLeft + segW * (pillPos + 1), paddingTop + h)
            canvas.drawRoundRect(rect, h / 2, h / 2, pill)
        }
        val baseline = paddingTop + h / 2 - (text.descent() + text.ascent()) / 2
        labels.forEachIndexed { i, l ->
            val over = 1f - min(1f, abs(pillPos - i))
            text.color = if (pillPos >= 0f) blend(if (isEnabled) C.TEXT else C.TEXT3, Color.BLACK, over) else if (isEnabled) C.TEXT else C.TEXT3
            canvas.drawText(l, paddingLeft + segW * (i + 0.5f), baseline, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && labels.isNotEmpty()) {
            val inner = width - paddingLeft - paddingRight
            val i = ((e.x - paddingLeft) / (inner / labels.size.toFloat())).toInt().coerceIn(0, labels.size - 1)
            onSelect(i)
        }
        return true
    }
}

// ------------------------------------------------------------------ Akku-Ring (wie das iOS-Batterie-Widget)

/** Runder Akku-Ring mit AirPod- bzw. Case-Symbol in der Mitte. */
class BatteryRing(context: Context, private val part: Part) : View(context) {
    enum class Part { LEFT, RIGHT, CASE }

    var percent: Int? = null
        set(v) {
            if (field != v) {
                animateTo(v)
                field = v
            }
        }
    var charging = false
        set(v) {
            field = v
            invalidate()
        }
    var inEar = false
        set(v) {
            field = v
            invalidate()
        }

    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C.white(0.13f)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val r = RectF()
    private val path = Path()

    private fun animateTo(v: Int?) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, (v ?: 0) / 100f).apply {
            duration = 600
            interpolator = EASE_OUT
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val sw = s * 0.085f
        val rad = s / 2 - sw / 2 - dp(1)
        track.strokeWidth = sw
        arc.strokeWidth = sw
        canvas.drawCircle(cx, cy, rad, track)
        val p = percent
        if (p != null && shown > 0f) {
            arc.color = when {
                charging -> C.GREEN
                p <= 20 -> C.RED
                else -> C.white(0.95f)
            }
            oval.set(cx - rad, cy - rad, cx + rad, cy + rad)
            canvas.drawArc(oval, -90f, 360f * shown, false, arc)
        }
        // Symbol in der Mitte
        glyph.color = if (p == null) C.white(0.35f) else C.white(0.9f)
        val g = s * 0.62f
        when (part) {
            Part.LEFT -> drawBud(canvas, cx, cy, g, mirror = false)
            Part.RIGHT -> drawBud(canvas, cx, cy, g, mirror = true)
            Part.CASE -> {
                r.set(cx - g * 0.36f, cy - g * 0.27f, cx + g * 0.36f, cy + g * 0.27f)
                canvas.drawRoundRect(r, g * 0.12f, g * 0.12f, glyph)
                glyph.color = 0xFF1C1C1E.toInt()
                glyph.strokeWidth = g * 0.04f
                canvas.drawLine(cx - g * 0.34f, cy - g * 0.08f, cx + g * 0.34f, cy - g * 0.08f, glyph)
            }
        }
        // Laden: kleiner grüner Blitz unten rechts
        if (charging && p != null) {
            val bx = cx + rad * 0.72f
            val by = cy + rad * 0.72f
            val br = s * 0.13f
            badge.color = C.GREEN
            canvas.drawCircle(bx, by, br, badge)
            badge.color = Color.BLACK
            path.reset()
            path.moveTo(bx + br * 0.15f, by - br * 0.65f)
            path.lineTo(bx - br * 0.4f, by + br * 0.1f)
            path.lineTo(bx - br * 0.02f, by + br * 0.1f)
            path.lineTo(bx - br * 0.15f, by + br * 0.65f)
            path.lineTo(bx + br * 0.4f, by - br * 0.1f)
            path.lineTo(bx + br * 0.02f, by - br * 0.1f)
            path.close()
            canvas.drawPath(path, badge)
        }
        // Im Ohr: blauer Punkt oben rechts
        if (inEar) {
            badge.color = C.BLUE
            canvas.drawCircle(cx + rad * 0.72f, cy - rad * 0.72f, s * 0.07f, badge)
        }
    }

    /** Ein AirPod: runder Kopf + Stiel. */
    private fun drawBud(canvas: Canvas, cx: Float, cy: Float, g: Float, mirror: Boolean) {
        canvas.save()
        if (mirror) canvas.scale(-1f, 1f, cx, cy)
        canvas.drawCircle(cx - g * 0.07f, cy - g * 0.16f, g * 0.19f, glyph)
        r.set(cx + g * 0.0f, cy - g * 0.16f, cx + g * 0.13f, cy + g * 0.36f)
        canvas.drawRoundRect(r, g * 0.065f, g * 0.065f, glyph)
        canvas.restore()
    }
}

/** Kleines AirPods-Symbol (zwei Ohrhörer) für Kopfzeilen. */
class AirPodsGlyph(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT }
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cy = height / 2f
        for ((i, cx) in listOf(width * 0.3f, width * 0.7f).withIndex()) {
            canvas.save()
            if (i == 1) canvas.scale(-1f, 1f, cx, cy)
            canvas.drawCircle(cx - s * 0.04f, cy - s * 0.17f, s * 0.15f, p)
            r.set(cx + s * 0.02f, cy - s * 0.17f, cx + s * 0.12f, cy + s * 0.32f)
            canvas.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
            canvas.restore()
        }
    }
}

package com.amoo.roomlauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ Farben

object C {
    val TEXT = 0xF5FFFFFF.toInt()
    val TEXT2 = 0x99FFFFFF.toInt()
    val TEXT3 = 0x66FFFFFF.toInt()
    val GREEN = 0xFF32D74B.toInt()
    val BLUE = 0xFF0A84FF.toInt()
    val RED = 0xFFFF453A.toInt()
    val ORANGE = 0xFFFF9F0A.toInt()

    fun white(a: Float): Int = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

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

// ------------------------------------------------------------------ Liquid Glass

/** "Liquid Glass": dunkle Tönung, heller Verlauf, glänzende Kante und Lichtreflex oben. */
class GlassDrawable(
    context: Context,
    radiusDp: Float,
    strength: Float = 1f,
    private val baseColor: Int = 0x400A0C14,
) : Drawable() {
    var strength: Float = strength
        set(v) {
            field = v
            onBoundsChange(bounds)
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
    private val rect = RectF()

    override fun onBoundsChange(b: Rect) {
        rect.set(b)
        rect.inset(strokeW / 2, strokeW / 2)
        base.color = baseColor
        val s = strength
        fill.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            C.white(0.17f * s), C.white(0.04f * s), Shader.TileMode.CLAMP,
        )
        shine.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.top + b.height() * 0.45f,
            C.white(0.10f * s), 0, Shader.TileMode.CLAMP,
        )
        stroke.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(C.white(0.55f * s), C.white(0.06f), C.white(0.24f * s)), null, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        canvas.drawRoundRect(rect, r, r, base)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, shine)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Einfarbig abgerundeter Hintergrund. */
class RoundDrawable(context: Context, radiusDp: Float, var color: Int, private val strokeColor: Int = 0) : Drawable() {
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
        p.color = color
        canvas.drawRoundRect(rect, r, r, p)
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

// ------------------------------------------------------------------ Bausteine

private val lightFace: Typeface by lazy { Typeface.create("sans-serif-light", Typeface.NORMAL) }
private val thinFace: Typeface by lazy { Typeface.create("sans-serif-thin", Typeface.NORMAL) }

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
        bold -> Typeface.DEFAULT_BOLD
        thin -> thinFace
        light -> lightFace
        else -> Typeface.DEFAULT
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
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        false
    }
}

fun Context.glassButton(text: String, highlighted: Boolean = false, onClick: () -> Unit): TextView =
    label(text, 15f, bold = true).apply {
        gravity = Gravity.CENTER
        background = GlassDrawable(context, 22f, if (highlighted) 1.8f else 1f)
        setPadding(dp(18), dp(11), dp(18), dp(11))
        pressable(onClick = onClick)
    }

/** Auswahl-Chip: weiß hinterlegt, wenn ausgewählt. */
class Chip(context: Context, text: String, selected: Boolean = false, onClick: (Chip) -> Unit) :
    TextView(context) {
    private val bg = RoundDrawable(context, 18f, 0)

    var chipSelected: Boolean = selected
        set(v) {
            field = v
            bg.color = if (v) C.white(0.9f) else C.white(0.10f)
            setTextColor(if (v) Color.BLACK else C.TEXT)
            invalidate()
        }

    init {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
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
    children.forEachIndexed { i, v -> row.addView(v, lp(WRAP, WRAP).margins(l = if (i > 0) dp(spacingDp) else 0)) }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
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

fun Context.dot(color: Int): View = View(this).apply {
    background = RoundDrawable(context, 5f, color)
    layoutParams = lp(dp(9), dp(9))
}

// ------------------------------------------------------------------ Icons

enum class Glyph { GEAR, GRID, POWER, CLOSE, MINUS }

class IconView(context: Context, private val glyph: Glyph, var color: Int = C.TEXT) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        p.color = color
        f.color = color
        p.strokeWidth = s * 0.1f
        when (glyph) {
            Glyph.GEAR -> {
                p.strokeWidth = s * 0.09f
                canvas.drawCircle(cx, cy, s * 0.2f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = cx + Math.cos(a).toFloat() * s * 0.3f
                    val y1 = cy + Math.sin(a).toFloat() * s * 0.3f
                    val x2 = cx + Math.cos(a).toFloat() * s * 0.44f
                    val y2 = cy + Math.sin(a).toFloat() * s * 0.44f
                    p.strokeWidth = s * 0.12f
                    canvas.drawLine(x1, y1, x2, y2, p)
                }
                p.strokeWidth = s * 0.09f
                canvas.drawCircle(cx, cy, s * 0.32f, p)
            }
            Glyph.GRID -> {
                val step = s / 3f
                val ox = cx - s / 2
                val oy = cy - s / 2
                for (x in 0..2) for (y in 0..2) canvas.drawCircle(ox + step * x + step / 2, oy + step * y + step / 2, step * 0.26f, f)
            }
            Glyph.POWER -> {
                p.strokeWidth = s * 0.11f
                val r = RectF(cx - s * 0.35f, cy - s * 0.3f, cx + s * 0.35f, cy + s * 0.4f)
                canvas.drawArc(r, -60f, 300f, false, p)
                canvas.drawLine(cx, cy - s * 0.44f, cx, cy, p)
            }
            Glyph.CLOSE -> {
                val d = s * 0.28f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, p)
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, p)
            }
            Glyph.MINUS -> canvas.drawLine(cx - s * 0.25f, cy, cx + s * 0.25f, cy, p)
        }
    }
}

fun Context.glassIconButton(glyph: Glyph, sizeDp: Int = 44, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        background = GlassDrawable(context, sizeDp / 2f)
        val icon = IconView(context, glyph)
        addView(icon, FrameLayout.LayoutParams((dp(sizeDp) * 0.5f).toInt(), (dp(sizeDp) * 0.5f).toInt(), Gravity.CENTER))
        layoutParams = lp(dp(sizeDp), dp(sizeDp))
        pressable(onClick = onClick)
    }

// ------------------------------------------------------------------ Slider

/** iOS-Kontrollzentrum-Slider: dicker Balken, der sich füllt – oder Verlauf mit Daumen. */
class GlassSlider(
    context: Context,
    private val heightDp: Int = 44,
    private val gradient: IntArray? = null,
    var onChange: (Float) -> Unit = {},
) : View(context) {
    var value: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.12f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.92f) }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x26000000
    }
    private val grad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false

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
            val cx = h / 2 + (w - h) * value
            thumbStroke.strokeWidth = dp(3).toFloat()
            canvas.drawCircle(cx, h / 2, r, thumb)
            canvas.drawCircle(cx, h / 2, r, thumbStroke)
        } else {
            canvas.drawRoundRect(rect, h / 2, h / 2, track)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(0f, 0f, w * value, h, fill)
            canvas.restore()
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
                duration = 180
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
            this.checked = !this.checked
            onToggle(this.checked)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        track.color = blend(C.white(0.16f), C.GREEN, pos)
        if (!isEnabled) track.alpha = 90
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val r = h / 2 - dp(2)
        canvas.drawCircle(h / 2 + (width - h) * pos, h / 2, r, knob)
    }
}

fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
    return Color.argb(
        ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
        ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)),
    )
}

// ------------------------------------------------------------------ Power-Knopf

class PowerButton(context: Context, sizeDp: Int, onClick: () -> Unit) : View(context) {
    var on = false
        set(v) {
            field = v
            invalidate()
        }
    var ringColor = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        layoutParams = lp(dp(sizeDp), dp(sizeDp))
        pressable(onClick = onClick)
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val c = s / 2
        fill.color = if (on) C.white(0.92f) else C.white(0.10f)
        canvas.drawCircle(c, c, c - dp(2), fill)
        ring.strokeWidth = dp(3).toFloat()
        ring.color = if (on) ringColor else C.white(0.2f)
        canvas.drawCircle(c, c, c - dp(2), ring)
        glyph.color = if (on) Color.BLACK else C.TEXT
        val g = s * 0.42f
        glyph.strokeWidth = g * 0.12f
        val r = RectF(c - g * 0.36f, c - g * 0.28f, c + g * 0.36f, c + g * 0.44f)
        canvas.drawArc(r, -60f, 300f, false, glyph)
        canvas.drawLine(c, c - g * 0.46f, c, c + g * 0.02f, glyph)
    }
}

// ------------------------------------------------------------------ Segment-Auswahl

class Segmented(context: Context, labels: List<String>, var onSelect: (Int) -> Unit) : LinearLayout(context) {
    private val items = ArrayList<TextView>()
    private val sel = ArrayList<RoundDrawable>()

    var selected: Int = -1
        set(v) {
            field = v
            items.forEachIndexed { i, t ->
                val on = i == v
                sel[i].color = if (on) C.white(0.9f) else 0
                t.setTextColor(if (on) Color.BLACK else if (isEnabled) C.TEXT else C.TEXT3)
                t.invalidate()
            }
        }

    init {
        orientation = HORIZONTAL
        background = GlassDrawable(context, 26f)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        labels.forEachIndexed { i, l ->
            val d = RoundDrawable(context, 22f, 0)
            val t = context.label(l, 14f, bold = true).apply {
                gravity = Gravity.CENTER
                background = d
                setPadding(dp(4), dp(12), dp(4), dp(12))
                pressable { onSelect(i) }
            }
            items += t
            sel += d
            addView(t, lp(0, MATCH, 1f))
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        selected = selected
    }
}

// ------------------------------------------------------------------ Glas-Karte

/** Glas-Karte mit vertikalem Inhalt. */
fun Context.glassCard(paddingDp: Int = 20): LinearLayout = column().apply {
    background = GlassDrawable(context, 30f)
    setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
}

package com.amoo.roomlauncher.board

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Ein Feld auf dem Board. [sizeLabel] != null -> Größe ist änderbar. */
data class Cell(val id: String, val w: Int, val h: Int, val sizeLabel: String? = null)

data class Placement(val cell: Cell, val col: Int, val row: Int)

/** Legt Elemente der Reihe nach ins erste freie Feld (wie iOS, ohne Lücken). */
fun pack(cells: List<Cell>, columns: Int): Pair<List<Placement>, Int> {
    val occ = ArrayList<BooleanArray>()
    fun ensure(r: Int) {
        while (occ.size <= r) occ.add(BooleanArray(columns))
    }
    fun free(r: Int, c: Int, w: Int, h: Int): Boolean {
        for (rr in r until r + h) {
            ensure(rr)
            for (cc in c until c + w) if (occ[rr][cc]) return false
        }
        return true
    }
    val out = ArrayList<Placement>()
    for (cell in cells) {
        val w = cell.w.coerceIn(1, columns)
        val h = cell.h.coerceAtLeast(1)
        var r = 0
        var placed = false
        while (!placed) {
            for (c in 0..columns - w) {
                if (free(r, c, w, h)) {
                    for (rr in r until r + h) for (cc in c until c + w) occ[rr][cc] = true
                    out += Placement(cell.copy(w = w, h = h), c, r)
                    placed = true
                    break
                }
            }
            r++
        }
    }
    return out to (out.maxOfOrNull { it.row + it.cell.h } ?: 0)
}

/**
 * Raster-Layout für Board und Dock.
 *  - Board: [columns] x [rows] sichtbar, Zellen füllen die Fläche.
 *  - Dock: [fixedCellDp] > 0, eine Reihe, Breite passt sich an.
 * Gedrückt halten -> Bearbeiten-Modus: wackeln, ziehen zum Verschieben, Badges zum Entfernen / Größe ändern.
 */
class BoardLayout(
    context: Context,
    private val columns: Int,
    private val rows: Int,
    private val fixedCellDp: Int = 0,
) : ViewGroup(context) {

    interface Listener {
        fun onEnterEdit()
        fun onMove(id: String, toIndex: Int)
        fun onRemove(id: String)
        fun onResize(id: String) {}
        fun onEmptyTap() {}
    }

    var listener: Listener? = null
    var gap: Int = dp(14)

    /** Höhe des sichtbaren Bereichs (wenn das Board in einer ScrollView liegt). */
    var viewportHeight: Int = 0
        set(v) {
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    private var cells: List<Cell> = emptyList()
    private var placements: List<Placement> = emptyList()
    private val views = HashMap<String, View>()
    private val lastPos = HashMap<String, IntArray>()
    private var cellW = 1
    private var cellH = 1

    // ---------------------------------------------------------------- Inhalte

    fun setCells(newCells: List<Cell>, factory: (Cell) -> View, recreate: (Cell) -> Boolean = { false }) {
        val ids = newCells.map { it.id }.toSet()
        views.keys.filter { it !in ids }.forEach { id ->
            removeView(views.remove(id))
            lastPos.remove(id)
        }
        for (c in newCells) {
            val existing = views[c.id]
            val old = cells.firstOrNull { it.id == c.id }
            if (existing == null || recreate(c) || (old != null && (old.w != c.w || old.h != c.h))) {
                if (existing != null) removeView(existing)
                val v = factory(c)
                views[c.id] = v
                addView(v)
            }
        }
        cells = newCells
        requestLayout()
        invalidate()
    }

    // ---------------------------------------------------------------- Messen & Anordnen

    private val isFixed get() = fixedCellDp > 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalW: Int
        val totalH: Int
        if (isFixed) {
            cellW = dp(fixedCellDp)
            cellH = cellW
            val n = max(1, cells.size)
            placements = cells.mapIndexed { i, c -> Placement(c.copy(w = 1, h = 1), i, 0) }
            totalW = if (cells.isEmpty()) 0 else n * cellW + (n - 1) * gap
            totalH = cellH
        } else {
            totalW = MeasureSpec.getSize(widthMeasureSpec)
            val viewport = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || viewportHeight > 0) {
                if (viewportHeight > 0) viewportHeight else MeasureSpec.getSize(heightMeasureSpec)
            } else {
                MeasureSpec.getSize(heightMeasureSpec)
            }
            cellW = max(1, (totalW - gap * (columns - 1)) / columns)
            cellH = max(1, (viewport - gap * (rows - 1)) / rows)
            val (pl, used) = pack(cells, columns)
            placements = pl
            totalH = max(viewport, used * cellH + max(0, used - 1) * gap)
        }
        for (p in placements) {
            val v = views[p.cell.id] ?: continue
            v.measure(
                MeasureSpec.makeMeasureSpec(p.cell.w * cellW + (p.cell.w - 1) * gap, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(p.cell.h * cellH + (p.cell.h - 1) * gap, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(totalW, totalH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (p in placements) {
            val v = views[p.cell.id] ?: continue
            val left = p.col * (cellW + gap)
            val top = p.row * (cellH + gap)
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
            val id = p.cell.id
            if (id == dragId) {
                v.translationX = dragLeft - left
                v.translationY = dragTop - top
            } else {
                val prev = lastPos[id]
                if (prev != null && (prev[0] != left || prev[1] != top)) {
                    v.translationX = (prev[0] - left).toFloat() + v.translationX
                    v.translationY = (prev[1] - top).toFloat() + v.translationY
                    v.animate().translationX(0f).translationY(0f).setDuration(220).start()
                }
            }
            lastPos[id] = intArrayOf(left, top)
        }
    }

    // ---------------------------------------------------------------- Bearbeiten-Modus

    private var wiggle: ValueAnimator? = null

    var editMode: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            wiggle?.cancel()
            if (v) {
                wiggle = ValueAnimator.ofFloat(-1f, 1f).apply {
                    duration = 130
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { a ->
                        val f = a.animatedValue as Float
                        var i = 0
                        for (p in placements) {
                            val view = views[p.cell.id] ?: continue
                            if (p.cell.id != dragId) {
                                val amp = if (p.cell.w * p.cell.h > 1) 0.7f else 1.6f
                                view.rotation = f * amp * (if (i % 2 == 0) 1f else -1f)
                            }
                            i++
                        }
                    }
                    start()
                }
            } else {
                views.values.forEach { it.rotation = 0f }
                endDrag()
            }
            invalidate()
        }

    // ---------------------------------------------------------------- Touch

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false
    private var candidate: String? = null
    private var dragId: String? = null
    private var dragLeft = 0f
    private var dragTop = 0f
    private var startLeft = 0f
    private var startTop = 0f
    private var handledByBadge = false

    private val longPress = Runnable {
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onEnterEdit()
        prepareCandidate(downX, downY)
    }

    private fun trackLongPress(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                longPressFired = false
                mainHandler.removeCallbacks(longPress)
                mainHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(e.x - downX, e.y - downY) > slop) mainHandler.removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mainHandler.removeCallbacks(longPress)
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return true
        trackLongPress(e)
        return longPressFired
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editMode) {
            trackLongPress(e)
            return true
        }
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x
                downY = y
                handledByBadge = false
                for (p in placements) {
                    val v = views[p.cell.id] ?: continue
                    if (removeRect(v).contains(x, y)) {
                        handledByBadge = true
                        listener?.onRemove(p.cell.id)
                        return true
                    }
                    if (p.cell.sizeLabel != null && resizeRect(v, p.cell.sizeLabel).contains(x, y)) {
                        handledByBadge = true
                        listener?.onResize(p.cell.id)
                        return true
                    }
                }
                if (!longPressFired) prepareCandidate(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (handledByBadge) return true
                val cand = candidate
                if (dragId == null && cand != null && hypot(x - downX, y - downY) > slop) {
                    dragId = cand
                    views[cand]?.let {
                        it.rotation = 0f
                        it.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
                        it.bringToFront()
                    }
                }
                val id = dragId ?: return true
                val v = views[id] ?: return true
                dragLeft = startLeft + (x - downX)
                dragTop = startTop + (y - downY)
                v.translationX = dragLeft - v.left
                v.translationY = dragTop - v.top
                checkReorder(id)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDrag = dragId != null
                endDrag()
                if (!wasDrag && !handledByBadge && candidate == null && e.actionMasked == MotionEvent.ACTION_UP &&
                    !longPressFired
                ) {
                    listener?.onEmptyTap()
                }
                candidate = null
                longPressFired = false
                handledByBadge = false
            }
        }
        return true
    }

    private fun prepareCandidate(x: Float, y: Float) {
        val p = placementAt(x, y)
        candidate = p?.cell?.id
        val v = p?.let { views[it.cell.id] }
        if (v != null) {
            startLeft = v.left.toFloat()
            startTop = v.top.toFloat()
            dragLeft = startLeft
            dragTop = startTop
        }
    }

    private fun endDrag() {
        val id = dragId ?: return
        dragId = null
        views[id]?.animate()?.translationX(0f)?.translationY(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(200)?.start()
    }

    private fun placementAt(x: Float, y: Float): Placement? = placements.firstOrNull { p ->
        val v = views[p.cell.id] ?: return@firstOrNull false
        x >= v.left && x < v.right && y >= v.top && y < v.bottom
    }

    private fun checkReorder(id: String) {
        val me = placements.firstOrNull { it.cell.id == id } ?: return
        val cx = dragLeft + (me.cell.w * cellW + (me.cell.w - 1) * gap) / 2f
        val cy = dragTop + (me.cell.h * cellH + (me.cell.h - 1) * gap) / 2f
        val col = (cx / (cellW + gap)).toInt()
        val row = if (isFixed) 0 else (cy / (cellH + gap)).toInt()
        val hit = placements.firstOrNull {
            it.cell.id != id && col >= it.col && col < it.col + it.cell.w && row >= it.row && row < it.row + it.cell.h
        }
        if (hit != null) {
            listener?.onMove(id, placements.indexOf(hit))
        } else if (isFixed && col >= placements.size) {
            listener?.onMove(id, placements.size - 1)
        } else if (!isFixed) {
            val last = placements.maxByOrNull { it.row * 100 + it.col } ?: return
            if (last.cell.id != id && (row > last.row + last.cell.h - 1 || (row >= last.row && col >= last.col + last.cell.w))) {
                listener?.onMove(id, placements.size - 1)
            }
        }
    }

    // ---------------------------------------------------------------- Badges

    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6303036.toInt() }
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x59FFFFFF
        strokeWidth = dp(1).toFloat()
    }
    private val badgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = dp(2.5f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(12).toFloat()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val tmp = RectF()

    private fun removeRect(v: View): RectF {
        val r = dp(14).toFloat()
        val cx = v.left + v.translationX + dp(10)
        val cy = v.top + v.translationY + dp(10)
        return RectF(cx - r - dp(6), cy - r - dp(6), cx + r + dp(6), cy + r + dp(6))
    }

    private fun resizeText(label: String) = "Größe: $label"

    private fun resizeRect(v: View, label: String): RectF {
        val tw = badgeText.measureText(resizeText(label))
        val right = v.right + v.translationX - dp(6)
        val bottom = v.bottom + v.translationY - dp(6)
        return RectF(right - tw - dp(24), bottom - dp(28), right, bottom)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        for (p in placements) {
            val v = views[p.cell.id] ?: continue
            val cx = v.left + v.translationX + dp(10)
            val cy = v.top + v.translationY + dp(10)
            val r = dp(14).toFloat()
            canvas.drawCircle(cx, cy, r, badgeFill)
            canvas.drawCircle(cx, cy, r, badgeStroke)
            canvas.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, badgeLine)
            val label = p.cell.sizeLabel ?: continue
            val rr = resizeRect(v, label)
            tmp.set(rr)
            canvas.drawRoundRect(tmp, dp(14).toFloat(), dp(14).toFloat(), badgeFill)
            canvas.drawRoundRect(tmp, dp(14).toFloat(), dp(14).toFloat(), badgeStroke)
            canvas.drawText(resizeText(label), rr.left + dp(12), rr.centerY() + dp(4), badgeText)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(longPress)
    }

    private fun dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
}

@Suppress("unused")
private fun absDiff(a: Float, b: Float) = abs(a - b)

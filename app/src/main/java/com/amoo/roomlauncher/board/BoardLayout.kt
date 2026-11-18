package com.amoo.roomlauncher.board

import android.animation.TimeAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.amoo.roomlauncher.ui.Backdrop
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Ein Feld auf dem Board. [sizeLabel] != null -> Größe ist änderbar.
 * [col]/[row] = fester Platz im Raster (Board); im Dock (eine Reihe) zählt nur die Reihenfolge.
 */
data class Cell(
    val id: String,
    val w: Int,
    val h: Int,
    val sizeLabel: String? = null,
    val col: Int = 0,
    val row: Int = 0,
    /** Widgets: Glas-Container an/aus (null = keine Option, z. B. Apps). */
    val glass: Boolean? = null,
)

data class Placement(val cell: Cell, val col: Int, val row: Int)

/**
 * Raster-Layout für Board und Dock.
 *  - Board: [columns] x [rows] sichtbar, Zellen füllen die Fläche. Jedes Element hat einen festen Platz
 *    und bleibt dort, wo es losgelassen wird (Lücken bleiben); ein Schatten zeigt beim Ziehen die Landestelle.
 *  - Dock: [fixedCellDp] > 0, eine Reihe, Breite passt sich an.
 * Leere Stelle (oder App) gedrückt halten -> Bearbeiten-Modus: wackeln, ziehen zum Verschieben, Badges zum Entfernen / Größe ändern.
 * Ein Widget gedrückt halten -> [Listener.onItemLongPress] (große Ansicht).
 */
class BoardLayout(
    context: Context,
    private val columns: Int,
    private val rows: Int,
    private val fixedCellDp: Int = 0,
) : ViewGroup(context) {

    interface Listener {
        fun onEnterEdit()
        fun onMove(id: String, toIndex: Int) {}

        /** Bearbeiten: Glas-Container des Widgets umschalten. */
        fun onToggleGlass(id: String) {}

        /** Board: Element an [col]/[row] abgelegt. */
        fun onDrop(id: String, col: Int, row: Int) {}
        fun onRemove(id: String)
        fun onResize(id: String) {}
        fun onEmptyTap() {}

        /** Gedrückthalten auf einem Element außerhalb des Bearbeitens. true = erledigt (z. B. Vorschau), sonst → Bearbeiten. */
        fun onItemLongPress(id: String, view: View): Boolean = false
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
    private var populated = false
    private var placements: List<Placement> = emptyList()
    private val views = HashMap<String, View>()
    private val lastPos = HashMap<String, IntArray>()
    private var cellW = 1
    private var cellH = 1

    // ---------------------------------------------------------------- Inhalte

    fun setCells(newCells: List<Cell>, factory: (Cell) -> View, recreate: (Cell) -> Boolean = { false }) {
        val ids = newCells.map { it.id }.toSet()
        views.keys.filter { it !in ids }.forEach { id ->
            val gone = views.remove(id) ?: return@forEach
            lastPos.remove(id)
            // Entfernen: kurz zusammenschrumpfen, dann raus
            gone.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(180).setInterpolator(EASE)
                .withEndAction { removeView(gone) }.start()
        }
        for (c in newCells) {
            val existing = views[c.id]
            val old = cells.firstOrNull { it.id == c.id }
            val resized = old != null && (old.w != c.w || old.h != c.h)
            if (existing == null || recreate(c) || resized) {
                if (existing != null) removeView(existing)
                val v = factory(c)
                views[c.id] = v
                addView(v)
                if (populated && (existing == null || resized)) {
                    // Neu oder Größe geändert: sanft aufploppen
                    v.scaleX = 0.85f
                    v.scaleY = 0.85f
                    v.alpha = 0f
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280).setInterpolator(SPRING).start()
                }
            }
        }
        populated = true
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
            placements = cells.map { Placement(it.copy(w = it.w.coerceIn(1, columns)), it.col.coerceIn(0, columns - it.w.coerceIn(1, columns)), it.row.coerceAtLeast(0)) }
            val used = placements.maxOfOrNull { it.row + it.cell.h } ?: 0
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
                    v.animate().translationX(0f).translationY(0f).setDuration(320).setInterpolator(SPRING)
                        .withEndAction { Backdrop.invalidateAll() }.start()
                }
            }
            lastPos[id] = intArrayOf(left, top)
        }
    }

    // ---------------------------------------------------------------- Bearbeiten-Modus

    /**
     * Wackeln wie auf dem iPhone: weiche Sinus-Bewegung statt Zickzack, jedes Element mit eigener Phase und
     * leicht eigenem Takt, Ausschlag passend zur Größe (große Widgets wackeln weniger weit), sanftes Ein- und Ausschwingen.
     */
    private var wiggle: TimeAnimator? = null
    private var wiggleAmp = 0f
    private var wiggleTarget = 0f

    var editMode: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            wiggleTarget = if (v) 1f else 0f
            if (wiggle == null) {
                wiggle = TimeAnimator().apply {
                    setTimeListener { anim, total, delta -> wiggleFrame(anim, total, delta) }
                    start()
                }
            }
            if (!v) endDrag()
            invalidate()
        }

    private fun wiggleFrame(anim: TimeAnimator, totalMs: Long, deltaMs: Long) {
        wiggleAmp += (wiggleTarget - wiggleAmp) * (1f - exp(-deltaMs / 120f))
        if (wiggleTarget == 0f && wiggleAmp < 0.02f) {
            wiggleAmp = 0f
            views.values.forEach { it.rotation = 0f }
            wiggle = null
            anim.cancel()
            return
        }
        val t = totalMs / 1000f
        for (p in placements) {
            val v = views[p.cell.id] ?: continue
            if (p.cell.id == dragId) {
                v.rotation = 0f
                continue
            }
            val h = p.cell.id.hashCode()
            val phase = ((h and 0xFFFF) / 65535f) * TAU
            val period = 0.25f + ((h ushr 16) and 0xFF) / 255f * 0.07f
            val half = hypot(v.width.toFloat(), v.height.toFloat()) / 2f
            val maxDeg = if (half > 0f) Math.toDegrees(dp(2.4f) / half.toDouble()).toFloat().coerceIn(0.35f, 1.8f) else 1f
            v.rotation = maxDeg * wiggleAmp * sin(t * TAU / period + phase)
        }
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
        // Auf einem Widget: große Ansicht statt Bearbeiten
        val p = placementAt(downX, downY)
        val v = p?.let { views[it.cell.id] }
        if (p != null && v != null && listener?.onItemLongPress(p.cell.id, v) == true) return@Runnable
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

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) mainHandler.removeCallbacks(longPress)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
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
                    val glass = p.cell.glass
                    if (glass != null && glassRect(v, glass).contains(x, y)) {
                        handledByBadge = true
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        listener?.onToggleGlass(p.cell.id)
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
                v.invalidate() // Milchglas folgt dem Finger
                if (isFixed) checkReorder(id) else updateDropTarget(id)
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
        val v = views[id]
        val target = dropTarget
        dropTarget = null
        invalidate()
        val me = placements.firstOrNull { it.cell.id == id }
        if (!isFixed && target != null && me != null && (target.first != me.col || target.second != me.row)) {
            // Ablegen: die View bleibt, wo der Finger ist – das neue Layout lässt sie von dort an ihren Platz gleiten
            v?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(220)?.start()
            listener?.onDrop(id, target.first, target.second)
            // Sicherheitsnetz: falls sich nichts bewegt hat, zurückgleiten
            postDelayed({
                if (dragId == null && v != null && v.left == me.col * (cellW + gap) && v.top == me.row * (cellH + gap) &&
                    (v.translationX != 0f || v.translationY != 0f) && v.animation == null
                ) {
                    v.animate().translationX(0f).translationY(0f).setDuration(300).setInterpolator(SPRING).start()
                }
            }, 120)
            return
        }
        v?.animate()?.translationX(0f)?.translationY(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(300)
            ?.setInterpolator(SPRING)?.withEndAction { Backdrop.invalidateAll() }?.start()
    }

    // ---------------------------------------------------------------- Landestelle (Board)

    private var dropTarget: Pair<Int, Int>? = null
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FFFFFF
        strokeWidth = dp(1.5f).toFloat()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(8).toFloat(), dp(6).toFloat()), 0f)
    }

    /** Wo würde das gezogene Element landen? Nächster freier Platz zur Fingerposition. */
    private fun updateDropTarget(id: String) {
        val me = placements.firstOrNull { it.cell.id == id } ?: return
        val stepX = (cellW + gap).toFloat()
        val stepY = (cellH + gap).toFloat()
        val wantCol = Math.round(dragLeft / stepX)
        val wantRow = Math.round(dragTop / stepY)
        val slots = placements.map { Slot(it.cell.id, it.col, it.row, it.cell.w, it.cell.h) }
        val t = Grid.nearestFree(slots, id, wantCol, wantRow, me.cell.w, me.cell.h, columns, rows)
        if (t != dropTarget) {
            dropTarget = t
            invalidate()
        }
    }

    private fun placementAt(x: Float, y: Float): Placement? = placements.firstOrNull { p ->
        val v = views[p.cell.id] ?: return@firstOrNull false
        x >= v.left && x < v.right && y >= v.top && y < v.bottom
    }

    private var lastReorderAt = 0L
    private var lastHitId: String? = null

    private fun checkReorder(id: String) {
        // Nicht bei jeder Fingerbewegung umsortieren – sonst springen große und kleine Elemente hin und her
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastReorderAt < 260) return
        val me = placements.firstOrNull { it.cell.id == id } ?: return
        val cx = dragLeft + (me.cell.w * cellW + (me.cell.w - 1) * gap) / 2f
        val cy = dragTop + (me.cell.h * cellH + (me.cell.h - 1) * gap) / 2f
        val col = (cx / (cellW + gap)).toInt()
        val row = if (isFixed) 0 else (cy / (cellH + gap)).toInt()
        val hit = placements.firstOrNull {
            it.cell.id != id && col >= it.col && col < it.col + it.cell.w && row >= it.row && row < it.row + it.cell.h
        }
        if (hit != null) {
            if (hit.cell.id == lastHitId && now - lastReorderAt < 700) return
            lastHitId = hit.cell.id
            lastReorderAt = now
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
        typeface = com.amoo.roomlauncher.ui.Fonts.medium
    }
    private val tmp = RectF()

    private fun removeRect(v: View): RectF {
        val r = dp(14).toFloat()
        val cx = v.left + v.translationX + dp(10)
        val cy = v.top + v.translationY + dp(10)
        return RectF(cx - r - dp(6), cy - r - dp(6), cx + r + dp(6), cy + r + dp(6))
    }

    private fun resizeText(label: String) = com.amoo.roomlauncher.Lang.auto("Größe") + ": " + com.amoo.roomlauncher.Lang.auto(label)

    private fun resizeRect(v: View, label: String): RectF {
        val tw = badgeText.measureText(resizeText(label))
        val right = v.right + v.translationX - dp(6)
        val bottom = v.bottom + v.translationY - dp(6)
        return RectF(right - tw - dp(24), bottom - dp(28), right, bottom)
    }

    private fun glassText(on: Boolean) = com.amoo.roomlauncher.Lang.auto(if (on) "Glas: an" else "Glas: aus")

    /** Knopf unten links: Glas-Container an/aus. */
    private fun glassRect(v: View, on: Boolean): RectF {
        val tw = badgeText.measureText(glassText(on))
        val left = v.left + v.translationX + dp(6)
        val bottom = v.bottom + v.translationY - dp(6)
        return RectF(left, bottom - dp(28), left + tw + dp(24), bottom)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Schatten der Landestelle hinter allem
        val t = dropTarget
        val id = dragId
        if (t != null && id != null) {
            placements.firstOrNull { it.cell.id == id }?.let { p ->
                val l = t.first * (cellW + gap).toFloat()
                val tp = t.second * (cellH + gap).toFloat()
                tmp.set(l, tp, l + p.cell.w * cellW + (p.cell.w - 1) * gap, tp + p.cell.h * cellH + (p.cell.h - 1) * gap)
                val r = if (p.cell.w * p.cell.h > 1) dp(30).toFloat() else dp(18).toFloat()
                canvas.drawRoundRect(tmp, r, r, ghostFill)
                canvas.drawRoundRect(tmp, r, r, ghostStroke)
            }
        }
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
            p.cell.glass?.let { on ->
                val gr = glassRect(v, on)
                tmp.set(gr)
                canvas.drawRoundRect(tmp, dp(14).toFloat(), dp(14).toFloat(), badgeFill)
                canvas.drawRoundRect(tmp, dp(14).toFloat(), dp(14).toFloat(), badgeStroke)
                canvas.drawText(glassText(on), gr.left + dp(12), gr.centerY() + dp(4), badgeText)
            }
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
        wiggle?.cancel()
        wiggle = null
        wiggleAmp = 0f
        views.values.forEach { it.rotation = 0f }
    }

    private fun dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
}

private val EASE = DecelerateInterpolator(2f)
private val SPRING = OvershootInterpolator(1.2f)
private const val TAU = 6.2831855f

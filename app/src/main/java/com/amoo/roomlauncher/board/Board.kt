package com.amoo.roomlauncher.board

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

data class Placement(val item: BoardItem, val col: Int, val row: Int, val w: Int, val h: Int)

/** Legt Elemente der Reihe nach in das erste freie Feld (wie iOS, nur ohne Lücken). */
fun pack(items: List<BoardItem>, columns: Int): Pair<List<Placement>, Int> {
    val occ = mutableListOf<BooleanArray>()
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
    val out = mutableListOf<Placement>()
    for (item in items) {
        val w = item.w.coerceAtMost(columns)
        val h = item.h
        var placed = false
        var r = 0
        while (!placed) {
            for (c in 0..columns - w) {
                if (free(r, c, w, h)) {
                    for (rr in r until r + h) for (cc in c until c + w) occ[rr][cc] = true
                    out += Placement(item, c, r, w, h)
                    placed = true
                    break
                }
            }
            r++
        }
    }
    val rows = out.maxOfOrNull { it.row + it.h } ?: 0
    return out to rows
}

/**
 * Langes Drücken -> [onLongPress]. Läuft im Initial-Pass, damit Buttons/Slider im Widget
 * danach keinen Klick mehr auslösen.
 */
fun Modifier.longPressToEdit(enabled: Boolean, onLongPress: () -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(onLongPress)
    if (!enabled) {
        this
    } else {
        this.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                        if (!c.pressed) return@withTimeoutOrNull true
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            return@withTimeoutOrNull true
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                }
                if (ended == null) {
                    cb()
                    // Rest der Geste schlucken
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                }
            }
        }
    }
}

/**
 * Das Launcher-Board: Raster mit [columns] Spalten und [visibleRows] sichtbaren Reihen.
 * Im Bearbeitungsmodus wackeln die Elemente und lassen sich ziehen.
 */
@Composable
fun Board(
    items: List<BoardItem>,
    editMode: Boolean,
    onEnterEdit: () -> Unit,
    onMove: (id: String, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 8,
    visibleRows: Int = 4,
    gap: Dp = 14.dp,
    editBadges: @Composable BoxScope.(item: BoardItem) -> Unit = {},
    itemContent: @Composable (item: BoardItem, editing: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val cellW = (maxWidth - gap * (columns - 1)) / columns
        val cellH = (maxHeight - gap * (visibleRows - 1)) / visibleRows
        val (placements, rows) = remember(items, columns) { pack(items, columns) }
        val contentH = maxOf(maxHeight, cellH * rows + gap * (rows - 1).coerceAtLeast(0))
        val cellWPx = with(density) { (cellW + gap).toPx() }
        val cellHPx = with(density) { (cellH + gap).toPx() }

        var dragId by remember { mutableStateOf<String?>(null) }
        var dragPos by remember { mutableStateOf(Offset.Zero) }
        val currentPlacements by rememberUpdatedState(placements)
        val moveCb by rememberUpdatedState(onMove)

        val wiggle = rememberInfiniteTransition(label = "wiggle")
        val angle by wiggle.animateFloat(
            initialValue = -1.1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(tween(130), RepeatMode.Reverse),
            label = "angle",
        )

        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState(), enabled = !editMode),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(contentH),
            ) {
                placements.forEachIndexed { index, p ->
                    key(p.item.id) {
                        val target = IntOffset((p.col * cellWPx).roundToInt(), (p.row * cellHPx).roundToInt())
                        val animated by animateIntOffsetAsState(target, spring(stiffness = 500f), label = "pos")
                        val dragging = dragId == p.item.id
                        val w = cellW * p.w + gap * (p.w - 1)
                        val h = cellH * p.h + gap * (p.h - 1)
                        val sign = if (index % 2 == 0) 1f else -1f
                        Box(
                            Modifier
                                .offset { if (dragging) IntOffset(dragPos.x.roundToInt(), dragPos.y.roundToInt()) else animated }
                                .size(w, h)
                                .zIndex(if (dragging) 10f else 0f)
                                .graphicsLayer {
                                    rotationZ = if (editMode && !dragging) angle * sign else 0f
                                    val s = if (dragging) 1.06f else 1f
                                    scaleX = s
                                    scaleY = s
                                }
                                .longPressToEdit(!editMode, onEnterEdit),
                        ) {
                            itemContent(p.item, editMode)
                            if (editMode) {
                                // Fängt alle Eingaben ab -> Ziehen zum Verschieben.
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .pointerInput(p.item.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    val pl = currentPlacements.firstOrNull { it.item.id == p.item.id }
                                                        ?: return@detectDragGestures
                                                    dragId = p.item.id
                                                    dragPos = Offset(pl.col * cellWPx, pl.row * cellHPx)
                                                },
                                                onDragEnd = { dragId = null },
                                                onDragCancel = { dragId = null },
                                            ) { change, amount ->
                                                change.consume()
                                                val id = dragId ?: return@detectDragGestures
                                                dragPos += amount
                                                val me = currentPlacements.firstOrNull { it.item.id == id } ?: return@detectDragGestures
                                                val cx = dragPos.x + me.w * cellWPx / 2f
                                                val cy = dragPos.y + me.h * cellHPx / 2f
                                                val col = (cx / cellWPx).toInt()
                                                val row = (cy / cellHPx).toInt()
                                                val hit = currentPlacements.firstOrNull {
                                                    it.item.id != id && col >= it.col && col < it.col + it.w &&
                                                        row >= it.row && row < it.row + it.h
                                                }
                                                if (hit != null) {
                                                    moveCb(id, currentPlacements.indexOf(hit))
                                                } else {
                                                    val last = currentPlacements.maxByOrNull { it.row * 100 + it.col }
                                                    if (last != null && (row > last.row + last.h - 1 ||
                                                            (row >= last.row && col >= last.col + last.w))
                                                    ) {
                                                        moveCb(id, currentPlacements.size - 1)
                                                    }
                                                }
                                            }
                                        },
                                )
                                editBadges(p.item)
                            }
                        }
                    }
                }
            }
        }
    }
}


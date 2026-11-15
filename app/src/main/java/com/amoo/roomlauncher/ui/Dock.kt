package com.amoo.roomlauncher.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.amoo.roomlauncher.apps.AppEntry
import com.amoo.roomlauncher.board.longPressToEdit
import kotlin.math.roundToInt

/** Liquid-Glass-Dock unten. Gedrückt halten -> Bearbeiten (ziehen zum Sortieren, "–" zum Entfernen). */
@Composable
fun Dock(
    components: List<String>,
    appMap: Map<String, AppEntry>,
    editMode: Boolean,
    onEnterEdit: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSize = 58.dp
    val spacing = 18.dp
    val stepPx = with(LocalDensity.current) { (iconSize + spacing).toPx() }
    val wiggle = rememberInfiniteTransition(label = "dockWiggle")
    val angle by wiggle.animateFloat(-1.5f, 1.5f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "a")
    val list by rememberUpdatedState(components)
    val moveCb by rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<String?>(null) }
    val dragX = remember { mutableFloatStateOf(0f) }

    GlassSurface(modifier, corner = 32.dp, strength = 1.1f) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            components.forEachIndexed { index, comp ->
                key(comp) {
                    val entry = appMap[comp]
                    val isDragging = dragging == comp
                    Box(
                        Modifier
                            .size(iconSize)
                            .zIndex(if (isDragging) 5f else 0f)
                            .graphicsLayer {
                                translationX = if (isDragging) dragX.floatValue else 0f
                                rotationZ = if (editMode && !isDragging) angle * (if (index % 2 == 0) 1f else -1f) else 0f
                                val s = if (isDragging) 1.1f else 1f
                                scaleX = s
                                scaleY = s
                            }
                            .longPressToEdit(!editMode, onEnterEdit),
                    ) {
                        if (entry != null) {
                            Image(
                                entry.icon,
                                contentDescription = entry.label,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .bouncy { if (!editMode) onLaunch(entry) },
                            )
                        } else {
                            Box(Modifier.fillMaxSize().glass(16.dp))
                        }
                        if (editMode) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(comp) {
                                        detectDragGestures(
                                            onDragStart = {
                                                dragging = comp
                                                dragX.floatValue = 0f
                                            },
                                            onDragEnd = {
                                                val from = list.indexOf(comp)
                                                val to = (from + (dragX.floatValue / stepPx).roundToInt()).coerceIn(0, list.size - 1)
                                                dragging = null
                                                dragX.floatValue = 0f
                                                if (from >= 0 && to != from) moveCb(comp, to)
                                            },
                                            onDragCancel = {
                                                dragging = null
                                                dragX.floatValue = 0f
                                            },
                                        ) { change, amount ->
                                            change.consume()
                                            dragX.floatValue += amount.x
                                        }
                                    },
                            )
                            RemoveBadge { onRemove(comp) }
                        }
                    }
                }
            }
            if (components.isNotEmpty()) {
                Box(
                    Modifier
                        .size(width = 1.dp, height = 42.dp)
                        .glass(1.dp, 1.5f),
                )
            }
            Box(
                Modifier
                    .size(iconSize)
                    .bouncy(onClick = onOpenAll)
                    .glass(18.dp, 1.3f),
                contentAlignment = Alignment.Center,
            ) {
                GridGlyph(TextPrimary, Modifier.size(22.dp))
            }
        }
    }
}

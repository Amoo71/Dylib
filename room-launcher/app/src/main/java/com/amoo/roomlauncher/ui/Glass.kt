package com.amoo.roomlauncher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

val TextPrimary = Color(0xF5FFFFFF)
val TextSecondary = Color(0x99FFFFFF)
val TextTertiary = Color(0x66FFFFFF)
val AccentGreen = Color(0xFF32D74B)
val AccentBlue = Color(0xFF0A84FF)
val AccentRed = Color(0xFFFF453A)
val AccentOrange = Color(0xFFFF9F0A)

/** "Liquid Glass": dunkle Tönung, heller Verlauf, glänzende Kante und Lichtreflex oben. */
fun Modifier.glass(corner: Dp = 28.dp, strength: Float = 1f): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .clip(shape)
        .background(Color(0x400A0C14), shape)
        .background(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.17f * strength), Color.White.copy(alpha = 0.04f * strength)),
            ),
            shape,
        )
        .drawBehind {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f * strength), Color.Transparent),
                    endY = size.height * 0.45f,
                ),
            )
        }
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.55f * strength),
                    Color.White.copy(alpha = 0.06f),
                    Color.White.copy(alpha = 0.24f * strength),
                ),
            ),
            shape,
        )
}

/** Klick mit federndem Einsinken statt Ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bouncy(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.55f), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

@Composable
fun T(
    text: String,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = TextPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, fontSize = size, fontWeight = weight, color = color, modifier = modifier, maxLines = maxLines)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .glass(32.dp)
            .padding(24.dp),
        content = content,
    )
}

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .bouncy(onClick = onClick)
            .glass(22.dp, if (highlighted) 1.8f else 1f)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, 15.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .bouncy(onClick = onClick)
            .glass(size / 2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .bouncy(onClick = onClick)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        T(text, 14.sp, FontWeight.SemiBold, if (selected) Color.Black else TextPrimary, maxLines = 1)
    }
}

/**
 * iOS-Kontrollzentrum-artiger Slider: dicker Balken, der sich füllt.
 * Mit [track] wird stattdessen ein Verlauf (z.B. Regenbogen) mit Daumen gezeigt.
 */
@Composable
fun GlassSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    fill: Brush = SolidColor(Color.White.copy(alpha = 0.92f)),
    track: Brush? = null,
) {
    val cb by rememberUpdatedState(onChange)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color.White.copy(alpha = 0.12f)),
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val v = value.coerceIn(0f, 1f)
        val input = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { cb((it.x / widthPx).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { cb((it.x / widthPx).coerceIn(0f, 1f)) },
                ) { change, _ ->
                    change.consume()
                    cb((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            }
        if (track != null) {
            Box(Modifier.fillMaxSize().background(track))
            val thumb = height - 10.dp
            val thumbPx = with(LocalDensity.current) { thumb.toPx() }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(((widthPx - thumbPx - 10f) * v + 5f).roundToInt(), 0) }
                    .size(thumb)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(3.dp, Color.Black.copy(alpha = 0.15f), CircleShape),
            )
        } else {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(v)
                    .background(fill),
            )
        }
        Box(input)
    }
}

@Composable
fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.11f
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(s * 0.15f, s * 0.2f),
            size = Size(s * 0.7f, s * 0.7f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(s / 2, s * 0.06f), Offset(s / 2, s * 0.5f), stroke, StrokeCap.Round)
    }
}

@Composable
fun GridGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = size.minDimension / 3f
        val r = step * 0.28f
        for (x in 0..2) for (y in 0..2) {
            drawCircle(color, r, Offset(step * x + step / 2, step * y + step / 2))
        }
    }
}

/** Animierter dunkler Hintergrund mit weichen Farbflächen, durch die das Glas wirkt. */
@Composable
fun LiquidBackground(accent: Color?) {
    val transition = rememberInfiniteTransition(label = "bg")
    val p by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "p",
    )
    val accentAnim by animateFloatAsState(if (accent != null) 1f else 0f, tween(800), label = "accent")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFF04050A))
        val w = size.width
        val h = size.height
        fun blob(cx: Float, cy: Float, r: Float, c: Color) {
            drawCircle(
                Brush.radialGradient(listOf(c, c.copy(alpha = c.alpha * 0.4f), Color.Transparent), Offset(cx, cy), r),
                r,
                Offset(cx, cy),
            )
        }
        blob(w * (0.12f + 0.10f * p), h * (0.15f + 0.20f * p), w * 0.48f, Color(0xFF3B2DFF).copy(alpha = 0.55f))
        blob(w * (0.88f - 0.10f * p), h * (0.25f + 0.25f * p), w * 0.40f, Color(0xFFFF2D87).copy(alpha = 0.33f))
        blob(w * (0.45f + 0.15f * p), h * (1.00f - 0.15f * p), w * 0.50f, Color(0xFF00B4FF).copy(alpha = 0.28f))
        if (accent != null || accentAnim > 0f) {
            val a = accent ?: Color.White
            blob(w * (0.65f - 0.2f * p), h * 0.55f, w * 0.38f, a.copy(alpha = 0.45f * accentAnim))
        }
        // leichte Vignette
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))))
    }
}

/** Modales Glas-Panel mit abgedunkeltem Hintergrund. */
@Composable
fun GlassSheet(
    title: String,
    onDismiss: () -> Unit,
    fillHeight: Boolean = false,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        val panel = Modifier
            .widthIn(max = 820.dp)
            .fillMaxWidth(0.94f)
            .let { if (fillHeight) it.fillMaxHeight(0.9f) else it.heightIn(max = 700.dp) }
            .clickable(remember { MutableInteractionSource() }, indication = null) { }
            .background(Color(0xD010121A), RoundedCornerShape(34.dp))
            .glass(34.dp, 1.2f)
            .padding(26.dp)
        Column(panel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, 26.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                GlassIconButton(Icons.Default.Close, 40.dp, onDismiss)
            }
            Spacer(Modifier.height(18.dp))
            if (scrollable) {
                Column(Modifier.verticalScroll(rememberScrollState()), content = content)
            } else {
                content()
            }
        }
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun HSpacer(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpacer(h: Dp) = Spacer(Modifier.height(h))

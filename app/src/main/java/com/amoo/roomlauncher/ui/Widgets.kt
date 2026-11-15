package com.amoo.roomlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amoo.roomlauncher.airpods.AirPodsController
import com.amoo.roomlauncher.apps.AppEntry
import com.amoo.roomlauncher.board.BoardItem
import com.amoo.roomlauncher.board.WidgetKind
import com.amoo.roomlauncher.board.WidgetSize
import com.amoo.roomlauncher.govee.GoveeController
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WidgetHost(
    item: BoardItem.Widget,
    govee: GoveeController,
    airpods: AirPodsController,
    onSetupLight: () -> Unit,
    onSetupAirPods: () -> Unit,
) {
    val m = Modifier.fillMaxSize()
    when (item.kind) {
        WidgetKind.CLOCK -> ClockWidget(item.size, m)
        WidgetKind.LIGHT -> when (item.size) {
            WidgetSize.S -> LightSmall(govee, m)
            WidgetSize.M -> LightMedium(govee, onSetupLight, m)
            WidgetSize.L -> LightCard(govee, onSetupLight, m)
        }
        WidgetKind.AIRPODS -> when (item.size) {
            WidgetSize.S -> AirPodsSmall(airpods, m)
            WidgetSize.M -> AirPodsMedium(airpods, onSetupAirPods, m)
            WidgetSize.L -> AirPodsCard(airpods, onSetupAirPods, m)
        }
    }
}

// ------------------------------------------------------------------ Uhr

@Composable
fun ClockWidget(size: WidgetSize, modifier: Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.GERMANY).format(now) }
    val day = remember(now) { SimpleDateFormat("EEEE", Locale.GERMANY).format(now) }
    val date = remember(now) { SimpleDateFormat("d. MMMM yyyy", Locale.GERMANY).format(now) }
    GlassCard(modifier, padding = 20.dp) {
        Spacer(Modifier.weight(1f))
        T(day, if (size == WidgetSize.S) 16.sp else 20.sp, FontWeight.SemiBold, AccentRed, maxLines = 1)
        T(time, if (size == WidgetSize.S) 54.sp else 84.sp, FontWeight.Light, maxLines = 1)
        T(date, if (size == WidgetSize.S) 13.sp else 16.sp, color = TextSecondary, maxLines = 1)
        Spacer(Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------ Licht

@Composable
private fun LightHeader(govee: GoveeController, onSetup: (() -> Unit)?) {
    val conn by govee.connection.collectAsState()
    val name by govee.deviceName.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Licht", 20.sp, FontWeight.Bold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when (conn) {
                        GoveeController.Connection.CONNECTED -> AccentGreen
                        GoveeController.Connection.CONNECTING -> AccentOrange
                        GoveeController.Connection.DISCONNECTED -> AccentRed
                        GoveeController.Connection.NOT_CONFIGURED -> TextTertiary
                    },
                )
                HSpacer(6.dp)
                T(
                    when (conn) {
                        GoveeController.Connection.NOT_CONFIGURED -> "nicht eingerichtet"
                        GoveeController.Connection.CONNECTED -> name ?: "verbunden"
                        GoveeController.Connection.CONNECTING -> "verbinde…"
                        GoveeController.Connection.DISCONNECTED -> "getrennt"
                    },
                    12.sp, color = TextSecondary, maxLines = 1,
                )
            }
        }
        if (onSetup != null) GlassIconButton(Icons.Default.Settings, 34.dp, onSetup)
    }
}

@Composable
private fun PowerButton(govee: GoveeController, size: androidx.compose.ui.unit.Dp) {
    val power by govee.power.collectAsState()
    val color by govee.color.collectAsState()
    Box(
        Modifier
            .size(size)
            .bouncy { govee.setPower(!power) }
            .clip(CircleShape)
            .background(if (power) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f))
            .border(3.dp, if (power) Color(color) else Color.White.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PowerGlyph(if (power) Color.Black else TextPrimary, Modifier.size(size * 0.42f))
    }
}

@Composable
fun LightSmall(govee: GoveeController, modifier: Modifier) {
    val brightness by govee.brightness.collectAsState()
    GlassCard(modifier, padding = 16.dp) {
        LightHeader(govee, null)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PowerButton(govee, 56.dp)
            HSpacer(10.dp)
            T("${(brightness * 100).roundToInt()} %", 20.sp, FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        GlassSlider(brightness, { govee.setBrightness(it) }, height = 38.dp)
    }
}

@Composable
fun LightMedium(govee: GoveeController, onSetup: () -> Unit, modifier: Modifier) {
    val brightness by govee.brightness.collectAsState()
    val color by govee.color.collectAsState()
    var hue by remember { mutableFloatStateOf(hueOf(color)) }
    GlassCard(modifier, padding = 18.dp) {
        LightHeader(govee, onSetup)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PowerButton(govee, 64.dp)
            HSpacer(14.dp)
            Column(Modifier.weight(1f)) {
                GlassSlider(brightness, { govee.setBrightness(it) }, height = 38.dp)
                VSpacer(8.dp)
                GlassSlider(
                    value = hue / 360f,
                    onChange = {
                        hue = it * 360f
                        govee.setColor(Color.hsv(hue.coerceIn(0f, 359.9f), 1f, 1f).toArgb())
                    },
                    track = rainbow,
                    height = 30.dp,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { c ->
                val selected = c.toArgb() == color
                Box(
                    Modifier
                        .size(32.dp)
                        .bouncy {
                            hue = hueOf(c.toArgb())
                            govee.setColor(c.toArgb())
                        }
                        .clip(CircleShape)
                        .background(c)
                        .border(if (selected) 3.dp else 1.dp, Color.White.copy(alpha = if (selected) 1f else 0.35f), CircleShape),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ AirPods

@Composable
private fun AirPodsHeader(airpods: AirPodsController, onSetup: (() -> Unit)?) {
    val connected by airpods.audioConnected.collectAsState()
    val name by airpods.deviceName.collectAsState()
    val model by airpods.model.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(name ?: model ?: "AirPods", 20.sp, FontWeight.Bold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (connected) AccentGreen else TextTertiary)
                HSpacer(6.dp)
                T(if (connected) "verbunden" else "nicht verbunden", 12.sp, color = TextSecondary, maxLines = 1)
            }
        }
        if (onSetup != null) GlassIconButton(Icons.Default.Settings, 34.dp, onSetup)
    }
}

@Composable
fun AirPodsSmall(airpods: AirPodsController, modifier: Modifier) {
    val connected by airpods.audioConnected.collectAsState()
    val battery by airpods.battery.collectAsState()
    val noise by airpods.noise.collectAsState()
    GlassCard(modifier, padding = 16.dp) {
        AirPodsHeader(airpods, null)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            MiniBattery("L", battery?.left)
            MiniBattery("R", battery?.right)
            MiniBattery("C", battery?.case)
        }
        Spacer(Modifier.weight(1f))
        if (connected) {
            // Tippen wechselt reihum den Geräuschmodus
            val entries = AirPodsController.Noise.entries
            GlassButton(noise?.label ?: "Modus", Modifier.fillMaxWidth(), highlighted = true) {
                val cur = noise?.let { entries.indexOf(it) } ?: -1
                val next = entries[(cur + 1) % entries.size]
                airpods.setNoise(next)
            }
        } else {
            GlassButton("Verbinden", Modifier.fillMaxWidth(), highlighted = true) { airpods.connectAudio() }
        }
    }
}

@Composable
private fun MiniBattery(label: String, pct: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        T(label, 11.sp, color = TextTertiary)
        T(pct?.let { "$it%" } ?: "–", 16.sp, FontWeight.SemiBold, if (pct != null && pct <= 20) AccentRed else TextPrimary)
    }
}

@Composable
fun AirPodsMedium(airpods: AirPodsController, onSetup: () -> Unit, modifier: Modifier) {
    val connected by airpods.audioConnected.collectAsState()
    val battery by airpods.battery.collectAsState()
    val inEar by airpods.inEar.collectAsState()
    val noise by airpods.noise.collectAsState()
    val ready by airpods.controlReady.collectAsState()
    GlassCard(modifier, padding = 18.dp) {
        AirPodsHeader(airpods, onSetup)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                MiniBattery(if (inEar?.left == true) "L · Ohr" else "Links", battery?.left)
                MiniBattery(if (inEar?.right == true) "R · Ohr" else "Rechts", battery?.right)
                MiniBattery("Case", battery?.case)
            }
            if (connected) {
                GlassButton("Trennen") { airpods.disconnectAudio() }
            } else {
                GlassButton("Verbinden", highlighted = true) { airpods.connectAudio() }
            }
        }
        Spacer(Modifier.weight(1f))
        NoiseSelector(noise, enabled = ready) { airpods.setNoise(it) }
    }
}

// ------------------------------------------------------------------ App-Icon

@Composable
fun AppIcon(entry: AppEntry?, onLaunch: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.bouncy(onClick = onLaunch), contentAlignment = Alignment.Center) {
        val iconSize = minOf(maxWidth, maxHeight - 22.dp) * 0.78f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (entry != null) {
                Image(entry.icon, contentDescription = entry.label, modifier = Modifier.size(iconSize))
            } else {
                Box(Modifier.size(iconSize).glass(16.dp))
            }
            VSpacer(5.dp)
            BasicText(
                entry?.label ?: "Fehlt",
                style = TextStyle(color = TextPrimary, fontSize = 12.sp, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Bearbeiten-Badges

@Composable
fun BoxScope.RemoveBadge(onRemove: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset((-8).dp, (-8).dp)
            .size(28.dp)
            .bouncy(onClick = onRemove)
            .clip(CircleShape)
            .background(Color(0xE6303036))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 12.dp, height = 2.5.dp)
                .background(Color.White),
        )
    }
}

@Composable
fun BoxScope.ResizeBadge(label: String, onResize: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .offset(6.dp, 6.dp)
            .bouncy(onClick = onResize)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(Color(0xE6303036))
            .border(1.dp, Color.White.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        T("⤡ $label", 12.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

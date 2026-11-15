package com.amoo.roomlauncher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amoo.roomlauncher.airpods.AirPodsController
import com.amoo.roomlauncher.govee.GoveeController
import kotlin.math.roundToInt

private val presets = listOf(
    Color(0xFFFFB46B), // Warmweiß
    Color(0xFFFFFFFF), // Kaltweiß
    Color(0xFFFF3B30),
    Color(0xFFFF9500),
    Color(0xFFFFD60A),
    Color(0xFF34C759),
    Color(0xFF00C7BE),
    Color(0xFF0A84FF),
    Color(0xFF5E5CE6),
    Color(0xFFBF5AF2),
    Color(0xFFFF2D87),
)

private val rainbow = Brush.horizontalGradient(
    (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) },
)

private fun hueOf(argb: Int): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    return hsv[0]
}

@Composable
fun LightCard(govee: GoveeController, onSetup: () -> Unit, modifier: Modifier = Modifier) {
    val conn by govee.connection.collectAsState()
    val power by govee.power.collectAsState()
    val brightness by govee.brightness.collectAsState()
    val color by govee.color.collectAsState()
    val name by govee.deviceName.collectAsState()
    val error by govee.lastError.collectAsState()
    var hue by remember { mutableFloatStateOf(hueOf(color)) }

    val (statusText, dot) = when (conn) {
        GoveeController.Connection.NOT_CONFIGURED -> "Kein Licht eingerichtet" to TextTertiary
        GoveeController.Connection.DISCONNECTED -> (error ?: "Getrennt – verbinde automatisch…") to AccentRed
        GoveeController.Connection.CONNECTING -> "Verbinde mit ${name ?: "Licht"}…" to AccentOrange
        GoveeController.Connection.CONNECTED -> (name ?: "Govee") + " · verbunden" to AccentGreen
    }

    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Licht", 28.sp, FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dot)
                    HSpacer(8.dp)
                    T(statusText, 14.sp, color = TextSecondary, maxLines = 1)
                }
            }
            GlassIconButton(Icons.Default.Settings, 44.dp, onSetup)
        }
        VSpacer(18.dp)

        if (conn == GoveeController.Connection.NOT_CONFIGURED) {
            T("Tippe auf das Zahnrad, um deinen Govee-LED-Streifen per Bluetooth zu suchen.", 15.sp, color = TextSecondary)
            VSpacer(16.dp)
            GlassButton("Licht einrichten", highlighted = true, onClick = onSetup)
            return@GlassCard
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ring by animateColorAsState(if (power) Color(color) else Color.White.copy(alpha = 0.2f), label = "ring")
                Box(
                    Modifier
                        .size(76.dp)
                        .bouncy { govee.setPower(!power) }
                        .clip(CircleShape)
                        .background(if (power) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f))
                        .border(3.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    PowerGlyph(if (power) Color.Black else TextPrimary, Modifier.size(32.dp))
                }
                HSpacer(20.dp)
                Column(Modifier.weight(1f)) {
                    T(
                        if (power) "Helligkeit ${(brightness * 100).roundToInt()} %" else "Aus",
                        14.sp, FontWeight.Medium, TextSecondary,
                    )
                    VSpacer(8.dp)
                    GlassSlider(brightness, { govee.setBrightness(it) }, height = 52.dp)
                }
            }
            VSpacer(22.dp)
            T("Farbe", 14.sp, FontWeight.Medium, TextSecondary)
            VSpacer(8.dp)
            GlassSlider(
                value = hue / 360f,
                onChange = {
                    hue = it * 360f
                    govee.setColor(Color.hsv(hue.coerceIn(0f, 359.9f), 1f, 1f).toArgb())
                },
                track = rainbow,
                height = 40.dp,
            )
            VSpacer(18.dp)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                presets.forEach { c ->
                    val selected = c.toArgb() == color
                    Box(
                        Modifier
                            .size(44.dp)
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
            if (conn != GoveeController.Connection.CONNECTED) {
                VSpacer(16.dp)
                GlassButton("Jetzt verbinden") { govee.connect() }
            }
        }
    }
}

@Composable
fun AirPodsCard(airpods: AirPodsController, onSetup: () -> Unit, modifier: Modifier = Modifier) {
    val connected by airpods.audioConnected.collectAsState()
    val battery by airpods.battery.collectAsState()
    val model by airpods.model.collectAsState()
    val noise by airpods.noise.collectAsState()
    val ready by airpods.controlReady.collectAsState()
    val controlStatus by airpods.controlStatus.collectAsState()
    val deviceName by airpods.deviceName.collectAsState()

    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(deviceName ?: model ?: "AirPods", 28.sp, FontWeight.Bold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (connected) AccentGreen else TextTertiary)
                    HSpacer(8.dp)
                    T(
                        when {
                            deviceName == null -> "Keine AirPods gekoppelt"
                            connected -> "Verbunden" + (model?.let { " · $it" } ?: "")
                            else -> "Nicht verbunden"
                        },
                        14.sp, color = TextSecondary, maxLines = 1,
                    )
                }
            }
            GlassIconButton(Icons.Default.Settings, 44.dp, onSetup)
        }
        VSpacer(18.dp)

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BatteryTile("Links", battery?.left, battery?.chargingLeft == true, Modifier.weight(1f))
                BatteryTile("Rechts", battery?.right, battery?.chargingRight == true, Modifier.weight(1f))
                BatteryTile("Case", battery?.case, battery?.chargingCase == true, Modifier.weight(1f))
            }
            VSpacer(20.dp)
            T("Geräuschkontrolle", 14.sp, FontWeight.Medium, TextSecondary)
            VSpacer(8.dp)
            NoiseSelector(noise, enabled = ready) { airpods.setNoise(it) }
            VSpacer(10.dp)
            T(controlStatus, 13.sp, color = TextTertiary)
            VSpacer(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (connected) {
                    GlassButton("Trennen") { airpods.disconnectAudio() }
                    if (!ready) GlassButton("Steuerung verbinden") { airpods.openControl() }
                } else {
                    GlassButton("Verbinden", highlighted = true) { airpods.connectAudio() }
                }
            }
        }
    }
}

@Composable
private fun BatteryTile(label: String, percent: Int?, charging: Boolean, modifier: Modifier) {
    Column(
        modifier
            .glass(22.dp, 0.8f)
            .padding(14.dp),
    ) {
        T(label, 13.sp, color = TextSecondary)
        VSpacer(4.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(percent?.let { "$it %" } ?: "–", 22.sp, FontWeight.SemiBold)
            if (charging) {
                HSpacer(6.dp)
                T("⚡", 16.sp, color = AccentGreen)
            }
        }
        VSpacer(8.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.15f)),
        ) {
            if (percent != null) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(percent / 100f)
                        .background(if (percent <= 20) AccentRed else AccentGreen),
                )
            }
        }
    }
}

@Composable
private fun NoiseSelector(
    selected: AirPodsController.Noise?,
    enabled: Boolean,
    onSelect: (AirPodsController.Noise) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .glass(27.dp)
            .padding(4.dp),
    ) {
        AirPodsController.Noise.entries.forEach { n ->
            val isSel = n == selected
            val bg by animateColorAsState(
                if (isSel) Color.White.copy(alpha = 0.9f) else Color.Transparent,
                label = "seg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .bouncy { onSelect(n) }
                    .clip(RoundedCornerShape(23.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                T(
                    n.label,
                    14.sp,
                    if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    when {
                        isSel -> Color.Black
                        enabled -> TextPrimary
                        else -> TextTertiary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

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

internal val presets = listOf(
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

internal val rainbow = Brush.horizontalGradient(
    (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) },
)

internal fun hueOf(argb: Int): Float {
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
            VSpacer(20.dp)
            T("Szenen", 14.sp, FontWeight.Medium, TextSecondary)
            VSpacer(8.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoveeController.SCENES.forEach { (id, label) -> GlassChip(label, false) { govee.setScene(id) } }
            }
            VSpacer(14.dp)
            T("Musik-Modus (Mikrofon am Controller)", 14.sp, FontWeight.Medium, TextSecondary)
            VSpacer(8.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoveeController.MUSIC_MODES.forEach { (id, label) -> GlassChip(label, false) { govee.setMusicMode(id) } }
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
    val inEar by airpods.inEar.collectAsState()
    val model by airpods.model.collectAsState()
    val noise by airpods.noise.collectAsState()
    val ready by airpods.controlReady.collectAsState()
    val controlStatus by airpods.controlStatus.collectAsState()
    val deviceName by airpods.deviceName.collectAsState()
    val ca by airpods.conversationAwareness.collectAsState()
    val strength by airpods.adaptiveStrength.collectAsState()
    val earDetection by airpods.earDetection.collectAsState()
    val oneBud by airpods.oneBudAnc.collectAsState()

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
        VSpacer(16.dp)

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BatteryTile("Links", battery?.left, battery?.chargingLeft == true, inEar?.left == true, Modifier.weight(1f))
                BatteryTile("Rechts", battery?.right, battery?.chargingRight == true, inEar?.right == true, Modifier.weight(1f))
                BatteryTile("Case", battery?.case, battery?.chargingCase == true, false, Modifier.weight(1f))
            }
            VSpacer(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (connected) {
                    GlassButton("Trennen", Modifier.weight(1f)) { airpods.disconnectAudio() }
                    if (!ready) GlassButton("Steuerung verbinden", Modifier.weight(1f)) { airpods.openControl() }
                } else {
                    GlassButton("Verbinden", Modifier.fillMaxWidth(), highlighted = true) { airpods.connectAudio() }
                }
            }
            VSpacer(18.dp)
            T("Geräuschkontrolle", 14.sp, FontWeight.Medium, TextSecondary)
            VSpacer(8.dp)
            NoiseSelector(noise, enabled = ready) { airpods.setNoise(it) }
            if (noise == AirPodsController.Noise.ADAPTIVE) {
                VSpacer(14.dp)
                T("Adaptives Audio: ${strength ?: 50} %  (weniger ↔ mehr Geräuschreduzierung)", 13.sp, color = TextSecondary)
                VSpacer(6.dp)
                GlassSlider(
                    value = (strength ?: 50) / 100f,
                    onChange = { airpods.setAdaptiveStrength((it * 100).roundToInt()) },
                    height = 36.dp,
                )
            }
            VSpacer(14.dp)
            ToggleRow("Gesprächserkennung", ca, ready) { airpods.setConversationAwareness(it) }
            ToggleRow("Automatische Ohrerkennung", earDetection, ready) { airpods.setEarDetection(it) }
            ToggleRow("ANC mit einem AirPod", oneBud, ready) { airpods.setOneBudAnc(it) }
            VSpacer(8.dp)
            T(controlStatus, 12.sp, color = TextTertiary)
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 15.sp, color = if (enabled) TextPrimary else TextTertiary, modifier = Modifier.weight(1f))
        GlassSwitch(value == true, enabled) { onChange(!(value ?: false)) }
    }
}

@Composable
fun GlassSwitch(checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val track by animateColorAsState(
        when {
            checked -> AccentGreen
            else -> Color.White.copy(alpha = 0.16f)
        },
        label = "track",
    )
    val knobX by androidx.compose.animation.core.animateDpAsState(if (checked) 22.dp else 2.dp, label = "knob")
    Box(
        Modifier
            .size(width = 52.dp, height = 32.dp)
            .bouncy(onClick = onToggle)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) track else track.copy(alpha = 0.35f)),
    ) {
        Box(
            Modifier
                .padding(start = knobX, top = 2.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
internal fun BatteryTile(label: String, percent: Int?, charging: Boolean, inEar: Boolean, modifier: Modifier) {
    Column(
        modifier
            .glass(22.dp, 0.8f)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            if (inEar) T("im Ohr", 11.sp, FontWeight.SemiBold, AccentBlue)
        }
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
internal fun NoiseSelector(
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

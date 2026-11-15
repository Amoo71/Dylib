package com.amoo.roomlauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amoo.roomlauncher.MainActivity
import com.amoo.roomlauncher.RoomApp
import com.amoo.roomlauncher.board.WidgetKind
import com.amoo.roomlauncher.theme.BgEffect
import com.amoo.roomlauncher.theme.BgImage
import com.amoo.roomlauncher.theme.BgType
import com.amoo.roomlauncher.theme.BubbleColor
import com.amoo.roomlauncher.theme.ThemeSettings
import com.amoo.roomlauncher.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class SettingsTab(val label: String) {
    BACKGROUND("Hintergrund"),
    WIDGETS("Widgets"),
    APPS("Apps"),
    DOCK("Dock"),
    DEVICES("Geräte"),
    GENERAL("Allgemein"),
}

private val swatches = listOf(
    0xFF0A0A0F, 0xFF1C1C2E, 0xFF2B1D8F, 0xFF3B2DFF, 0xFF0A84FF, 0xFF00B4D8, 0xFF0F766E, 0xFF34C759,
    0xFFA3E635, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A, 0xFFFF2D87, 0xFF8A1D5C, 0xFFBF5AF2, 0xFF5E5CE6,
).map { it.toInt() }

@Composable
fun SettingsSheet(
    app: RoomApp,
    activity: MainActivity,
    initialTab: SettingsTab,
    onOpenGovee: () -> Unit,
    onOpenAirPods: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(initialTab) }
    GlassSheet("Einstellungen", onDismiss, fillHeight = true, scrollable = false) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsTab.entries.forEach { t -> GlassChip(t.label, tab == t) { tab = t } }
        }
        VSpacer(18.dp)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when (tab) {
                SettingsTab.BACKGROUND -> BackgroundSettings(app)
                SettingsTab.WIDGETS -> WidgetSettings(app, onDismiss)
                SettingsTab.APPS -> AppSettings(app)
                SettingsTab.DOCK -> DockSettings(app)
                SettingsTab.DEVICES -> DeviceSettings(onOpenGovee, onOpenAirPods)
                SettingsTab.GENERAL -> GeneralSettings(app, activity, onDismiss)
            }
            VSpacer(24.dp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    VSpacer(14.dp)
    T(text, 15.sp, FontWeight.SemiBold, TextSecondary)
    VSpacer(10.dp)
}

@Composable
private fun LabeledSlider(label: String, value: Float, display: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(label, 15.sp, modifier = Modifier.width(170.dp))
        Box(Modifier.weight(1f)) { GlassSlider(value.coerceIn(0f, 1f), onChange, height = 34.dp) }
        T(display, 14.sp, color = TextSecondary, modifier = Modifier.padding(start = 12.dp).width(56.dp))
    }
    VSpacer(10.dp)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 16.sp, modifier = Modifier.weight(1f))
        GlassSwitch(checked, true) { onChange(!checked) }
    }
}

// ------------------------------------------------------------------ Hintergrund

@Composable
private fun ColumnScope.BackgroundSettings(app: RoomApp) {
    val theme by app.theme.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update: ((ThemeSettings) -> ThemeSettings) -> Unit = { app.theme.update(it) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { copyImage(context, uri) }
                if (ok) {
                    app.theme.bumpImageVersion()
                    update { it.copy(type = BgType.OWN, ownImage = "file") }
                } else {
                    context.toast("Bild konnte nicht geladen werden")
                }
            }
        }
    }

    T("Thema", 15.sp, FontWeight.SemiBold, TextSecondary)
    VSpacer(10.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BgType.entries.forEach { t -> GlassChip(t.label, theme.type == t) { update { it.copy(type = t) } } }
    }

    when (theme.type) {
        BgType.RAIN -> RainControls(theme, update)
        BgType.BLACK -> BubbleControls(theme, update, allowThemeColor = false)
        BgType.COLORED -> {
            SectionTitle("Farben (1 = einfarbig, mehrere = Verlauf, max. 4)")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                swatches.forEach { c ->
                    val idx = theme.colors.indexOf(c)
                    Box(
                        Modifier
                            .size(40.dp)
                            .bouncy {
                                update {
                                    val list = it.colors.toMutableList()
                                    if (c in list) {
                                        if (list.size > 1) list.remove(c)
                                    } else if (list.size < 4) {
                                        list.add(c)
                                    }
                                    it.copy(colors = list)
                                }
                            }
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(if (idx >= 0) 3.dp else 1.dp, Color.White.copy(alpha = if (idx >= 0) 1f else 0.3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (idx >= 0) T("${idx + 1}", 13.sp, FontWeight.Bold)
                    }
                }
            }
            VSpacer(12.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (theme.colors.size > 1) Brush.horizontalGradient(theme.colors.map { Color(it) })
                        else SolidColor(Color(theme.colors.firstOrNull() ?: 0)),
                    ),
            )
            VSpacer(12.dp)
            if (theme.colors.size > 1) {
                LabeledSlider("Verlaufswinkel", theme.gradientAngle / 360f, "${theme.gradientAngle.roundToInt()}°") { v ->
                    update { it.copy(gradientAngle = v * 360f) }
                }
            }
            BubbleControls(theme, update, allowThemeColor = true)
        }
        BgType.OWN -> {
            SectionTitle("Eigenes Bild oder GIF")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(if (theme.ownImage == null) "Bild/GIF auswählen" else "Anderes Bild wählen", highlighted = true) {
                    picker.launch("image/*")
                }
            }
            VSpacer(12.dp)
            LabeledSlider("Abdunkeln", theme.ownDim / 0.9f, "${(theme.ownDim * 100).roundToInt()} %") { v ->
                update { it.copy(ownDim = v * 0.9f) }
            }
            SectionTitle("Effekt darüber")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BgEffect.entries.forEach { e -> GlassChip(e.label, theme.ownEffect == e) { update { it.copy(ownEffect = e) } } }
            }
            when (theme.ownEffect) {
                BgEffect.RAIN -> RainControls(theme, update)
                BgEffect.BUBBLES -> BubbleControls(theme, update, allowThemeColor = false)
                BgEffect.NONE -> Unit
            }
        }
    }

    SectionTitle("Liquid Glass")
    SwitchRow("Echte Glas-Unschärfe (Android 12+)", theme.glassBlur) { on -> update { it.copy(glassBlur = on) } }
    if (android.os.Build.VERSION.SDK_INT < 31) {
        T("Dieses Gerät hat Android < 12: das Glas ist transparent statt unscharf.", 13.sp, color = TextTertiary)
    }
}

@Composable
private fun RainControls(theme: ThemeSettings, update: ((ThemeSettings) -> ThemeSettings) -> Unit) {
    SectionTitle("Regen")
    LabeledSlider("Stärke", theme.rainIntensity / 3f, "${(theme.rainIntensity * 100).roundToInt()} %") { v ->
        update { it.copy(rainIntensity = (v * 3f).coerceAtLeast(0.05f)) }
    }
    LabeledSlider("Wind", (theme.rainWind + 1f) / 2f, "${(theme.rainWind * 100).roundToInt()}") { v ->
        update { it.copy(rainWind = v * 2f - 1f) }
    }
    LabeledSlider("Geschwindigkeit", theme.rainSpeed / 2f, "${(theme.rainSpeed * 100).roundToInt()} %") { v ->
        update { it.copy(rainSpeed = (v * 2f).coerceAtLeast(0.1f)) }
    }
}

@Composable
private fun BubbleControls(
    theme: ThemeSettings,
    update: ((ThemeSettings) -> ThemeSettings) -> Unit,
    allowThemeColor: Boolean,
) {
    SectionTitle("Blasen")
    LabeledSlider("Anzahl", theme.bubbleCount / 40f, "${theme.bubbleCount}") { v ->
        update { it.copy(bubbleCount = (v * 40).roundToInt()) }
    }
    LabeledSlider("Größe", theme.bubbleSize / 2.5f, "${(theme.bubbleSize * 100).roundToInt()} %") { v ->
        update { it.copy(bubbleSize = (v * 2.5f).coerceAtLeast(0.2f)) }
    }
    LabeledSlider("Tempo", theme.bubbleSpeed / 4f, "${(theme.bubbleSpeed * 100).roundToInt()} %") { v ->
        update { it.copy(bubbleSpeed = (v * 4f).coerceAtLeast(0.1f)) }
    }
    LabeledSlider("Deckkraft", theme.bubbleOpacity, "${(theme.bubbleOpacity * 100).roundToInt()} %") { v ->
        update { it.copy(bubbleOpacity = v) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        T("Farbe", 15.sp, modifier = Modifier.width(170.dp))
        BubbleColor.entries.filter { allowThemeColor || it != BubbleColor.THEME }.forEach { c ->
            GlassChip(c.label, theme.bubbleColor == c) { update { it.copy(bubbleColor = c) } }
        }
    }
}

private fun copyImage(context: Context, uri: Uri): Boolean = runCatching {
    val target = BgImage.file(context)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
    } ?: return false
    target.length() > 0
}.getOrDefault(false)

// ------------------------------------------------------------------ Widgets

@Composable
private fun ColumnScope.WidgetSettings(app: RoomApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    T("Widget wählen und Größe antippen, um es zum Board hinzuzufügen.", 14.sp, color = TextSecondary)
    VSpacer(14.dp)
    WidgetKind.entries.forEach { kind ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .glass(20.dp, 0.8f)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T(kind.title, 18.sp, FontWeight.SemiBold)
                T(
                    when (kind) {
                        WidgetKind.CLOCK -> "Uhrzeit und Datum"
                        WidgetKind.LIGHT -> "Govee-LED: an/aus, Helligkeit, Farbe, Szenen"
                        WidgetKind.AIRPODS -> "Akku, Verbinden, ANC/Transparenz/Adaptiv"
                    },
                    13.sp, color = TextTertiary,
                )
            }
            kind.sizes.forEach { size ->
                HSpacer(8.dp)
                GlassChip("+ ${size.label}", false) {
                    app.board.addWidget(kind, size)
                    context.toast("${kind.title} (${size.label}) hinzugefügt")
                }
            }
        }
    }
    VSpacer(8.dp)
    T("Tipp: Auf dem Board ein Widget gedrückt halten → ziehen zum Verschieben, „⤡“ für die Größe, „–“ zum Entfernen.", 13.sp, color = TextTertiary)
}

// ------------------------------------------------------------------ Apps

@Composable
private fun ColumnScope.AppSettings(app: RoomApp) {
    val apps by app.apps.apps.collectAsState()
    val board by app.board.items.collectAsState()
    val dock by app.board.dock.collectAsState()
    var query by remember { mutableStateOf("") }
    val onBoard = remember(board) {
        board.filterIsInstance<com.amoo.roomlauncher.board.BoardItem.App>().map { it.component }.toSet()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .glass(20.dp, 0.8f)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        if (query.isEmpty()) T("App suchen", 16.sp, color = TextTertiary)
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    VSpacer(12.dp)
    apps.filter { query.isBlank() || it.label.contains(query.trim(), true) }.forEach { a ->
        val comp = a.component.flattenToString()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(a.icon, null, Modifier.size(40.dp))
            HSpacer(14.dp)
            T(a.label, 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
            GlassChip(if (comp in onBoard) "✓ Board" else "+ Board", comp in onBoard) {
                if (comp in onBoard) {
                    board.firstOrNull { it is com.amoo.roomlauncher.board.BoardItem.App && it.component == comp }
                        ?.let { app.board.remove(it.id) }
                } else {
                    app.board.addApp(comp)
                }
            }
            HSpacer(8.dp)
            GlassChip(if (comp in dock) "✓ Dock" else "+ Dock", comp in dock) {
                if (comp in dock) app.board.removeFromDock(comp) else app.board.addToDock(comp)
            }
        }
    }
}

// ------------------------------------------------------------------ Dock

@Composable
private fun ColumnScope.DockSettings(app: RoomApp) {
    val enabled by app.board.dockEnabled.collectAsState()
    val dock by app.board.dock.collectAsState()
    val apps by app.apps.apps.collectAsState()
    SwitchRow("Dock anzeigen", enabled) { app.board.setDockEnabled(it) }
    SectionTitle("Apps im Dock (${dock.size}/10)")
    if (dock.isEmpty()) T("Noch leer – im Tab „Apps“ mit „+ Dock“ hinzufügen.", 14.sp, color = TextTertiary)
    dock.forEachIndexed { i, comp ->
        val entry = apps.firstOrNull { it.component.flattenToString() == comp }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry != null) Image(entry.icon, null, Modifier.size(40.dp)) else Box(Modifier.size(40.dp).glass(12.dp))
            HSpacer(14.dp)
            T(entry?.label ?: comp.substringBefore('/'), 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (i > 0) GlassChip("←", false) { app.board.moveInDock(comp, i - 1) }
            HSpacer(6.dp)
            if (i < dock.size - 1) GlassChip("→", false) { app.board.moveInDock(comp, i + 1) }
            HSpacer(6.dp)
            GlassChip("Entfernen", false) { app.board.removeFromDock(comp) }
        }
    }
    VSpacer(10.dp)
    T("Tipp: Dock gedrückt halten → Icons ziehen zum Sortieren, „–“ zum Entfernen.", 13.sp, color = TextTertiary)
}

// ------------------------------------------------------------------ Geräte

@Composable
private fun ColumnScope.DeviceSettings(onOpenGovee: () -> Unit, onOpenAirPods: () -> Unit) {
    T("Bluetooth-Geräte einrichten", 14.sp, color = TextSecondary)
    VSpacer(14.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassButton("Govee-Licht einrichten", highlighted = true, onClick = onOpenGovee)
        GlassButton("AirPods einrichten", highlighted = true, onClick = onOpenAirPods)
    }
}

// ------------------------------------------------------------------ Allgemein

@Composable
private fun ColumnScope.GeneralSettings(app: RoomApp, activity: MainActivity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var keepOn by remember { mutableStateOf(app.prefs.keepScreenOn) }
    SwitchRow("Bildschirm immer an", keepOn) {
        keepOn = it
        app.prefs.keepScreenOn = it
        activity.applyKeepScreenOn(it)
    }
    SectionTitle("Launcher verlassen")
    T(
        "Solange Room Launcher der Standard-Startbildschirm ist, bringt die Home-Taste dich immer hierher zurück.",
        14.sp, color = TextSecondary,
    )
    VSpacer(12.dp)
    val launchers = remember { app.apps.otherLaunchers() }
    launchers.forEach { l ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .bouncy {
                    onDismiss()
                    app.apps.launchHome(l)
                }
                .glass(20.dp, 0.8f)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(l.icon, null, Modifier.size(40.dp))
            HSpacer(14.dp)
            T("${l.label} öffnen", 17.sp, FontWeight.SemiBold)
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassButton("Standard-Startbildschirm ändern") {
            val ok = runCatching {
                context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (!ok) context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        GlassButton("Android-Einstellungen") {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    VSpacer(18.dp)
    T("Room Launcher ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""}", 12.sp, color = TextTertiary)
}

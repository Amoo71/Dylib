package com.amoo.roomlauncher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amoo.roomlauncher.Prefs
import com.amoo.roomlauncher.airpods.AirPodsController
import com.amoo.roomlauncher.apps.AppEntry
import com.amoo.roomlauncher.apps.AppRepository
import com.amoo.roomlauncher.govee.GoveeController
import com.amoo.roomlauncher.toast

// ------------------------------------------------------------------ Alle Apps

@Composable
fun AppsSheet(repo: AppRepository, onDismiss: () -> Unit) {
    val apps by repo.apps.collectAsState()
    val favorites by repo.favorites.collectAsState()
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    GlassSheet("Apps", onDismiss, fillHeight = true, scrollable = false) {
        Box(
            Modifier
                .fillMaxWidth()
                .glass(20.dp, 0.8f)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            if (query.isEmpty()) T("Suchen", 17.sp, color = TextTertiary)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 17.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VSpacer(6.dp)
        T("Gedrückt halten = zum Dock hinzufügen / entfernen", 12.sp, color = TextTertiary)
        VSpacer(12.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(filtered, key = { it.component.flattenToString() }) { app ->
                val fav = app.packageName in favorites
                Column(
                    Modifier.bouncy(
                        onLongClick = {
                            repo.toggleFavorite(app.packageName)
                            context.toast(if (fav) "Aus dem Dock entfernt" else "Zum Dock hinzugefügt")
                        },
                    ) {
                        onDismiss()
                        repo.launch(app)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Image(app.icon, contentDescription = app.label, modifier = Modifier.size(64.dp))
                        if (fav) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(AccentBlue),
                            )
                        }
                    }
                    VSpacer(6.dp)
                    androidx.compose.material3.Text(
                        app.label,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Dock

@Composable
fun Dock(repo: AppRepository, onOpenAll: () -> Unit, modifier: Modifier = Modifier) {
    val apps by repo.apps.collectAsState()
    val favorites by repo.favorites.collectAsState()
    val favApps = remember(apps, favorites) {
        favorites.mapNotNull { p -> apps.firstOrNull { it.packageName == p } }
    }
    Row(
        modifier
            .glass(34.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        favApps.take(10).forEach { app ->
            Image(
                app.icon,
                contentDescription = app.label,
                modifier = Modifier
                    .size(60.dp)
                    .bouncy(onLongClick = { repo.toggleFavorite(app.packageName) }) { repo.launch(app) },
            )
        }
        if (favApps.isNotEmpty()) {
            Box(
                Modifier
                    .size(width = 1.dp, height = 44.dp)
                    .background(Color.White.copy(alpha = 0.22f)),
            )
        }
        Box(
            Modifier
                .size(60.dp)
                .bouncy(onClick = onOpenAll)
                .glass(18.dp, 1.3f),
            contentAlignment = Alignment.Center,
        ) {
            GridGlyph(TextPrimary, Modifier.size(24.dp))
        }
    }
}

// ------------------------------------------------------------------ Exit

@Composable
fun ExitSheet(
    repo: AppRepository,
    prefs: Prefs,
    onKeepScreenOn: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val launchers = remember { repo.otherLaunchers() }
    var keepOn by remember { mutableStateOf(prefs.keepScreenOn) }

    GlassSheet("Launcher verlassen", onDismiss) {
        T(
            "Die Home-Taste bringt dich immer hierher zurück, solange Room Launcher der Standard-Startbildschirm ist.",
            15.sp, color = TextSecondary,
        )
        VSpacer(18.dp)
        if (launchers.isNotEmpty()) {
            T("Anderen Startbildschirm öffnen", 14.sp, FontWeight.Medium, TextSecondary)
            VSpacer(10.dp)
            launchers.forEach { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .bouncy {
                            onDismiss()
                            repo.launchHome(l)
                        }
                        .glass(20.dp, 0.8f)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(l.icon, null, Modifier.size(40.dp))
                    HSpacer(14.dp)
                    T(l.label, 17.sp, FontWeight.SemiBold)
                }
            }
            VSpacer(8.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
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
        VSpacer(22.dp)
        T("Optionen", 14.sp, FontWeight.Medium, TextSecondary)
        VSpacer(10.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Bildschirm immer an", 17.sp, modifier = Modifier.weight(1f))
            GlassChip("An", keepOn) { keepOn = true; prefs.keepScreenOn = true; onKeepScreenOn(true) }
            HSpacer(8.dp)
            GlassChip("Aus", !keepOn) { keepOn = false; prefs.keepScreenOn = false; onKeepScreenOn(false) }
        }
    }
}

// ------------------------------------------------------------------ Govee einrichten

@Composable
fun GoveeSetupSheet(govee: GoveeController, prefs: Prefs, onDismiss: () -> Unit) {
    val found by govee.found.collectAsState()
    val scanning by govee.scanning.collectAsState()
    val error by govee.lastError.collectAsState()
    val currentName by govee.deviceName.collectAsState()
    var colorMode by remember { mutableIntStateOf(prefs.goveeColorMode) }
    var b100 by remember { mutableStateOf(prefs.goveeBrightness100) }

    DisposableEffect(Unit) {
        govee.startScan()
        onDispose { govee.stopScan() }
    }

    GlassSheet("Govee-Licht einrichten", onDismiss) {
        T("Aktuell: ${currentName ?: "keins"}", 15.sp, color = TextSecondary)
        T(
            "Wichtig: Govee-App auf dem Handy schließen – das Licht erlaubt nur eine Bluetooth-Verbindung gleichzeitig.",
            13.sp, color = TextTertiary,
        )
        VSpacer(16.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(if (scanning) "Suche Geräte…" else "Gefundene Geräte", 17.sp, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            GlassButton("Neu suchen") { govee.startScan() }
        }
        error?.let { T(it, 13.sp, color = AccentRed) }
        VSpacer(10.dp)
        if (found.isEmpty()) {
            T(if (scanning) "…" else "Nichts gefunden. Ist Bluetooth/Standort an und das Licht in der Nähe?", 14.sp, color = TextTertiary)
        }
        found.take(25).forEach { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .bouncy {
                        govee.select(f)
                        onDismiss()
                    }
                    .glass(18.dp, if (f.likelyGovee) 1.4f else 0.7f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    T(f.name, 17.sp, if (f.likelyGovee) FontWeight.SemiBold else FontWeight.Normal)
                    T("${f.address} · ${f.rssi} dBm", 12.sp, color = TextTertiary)
                }
                if (f.likelyGovee) T("Govee", 13.sp, FontWeight.Bold, AccentGreen)
            }
        }
        VSpacer(20.dp)
        T("Farb-Befehl (modellabhängig)", 14.sp, FontWeight.Medium, TextSecondary)
        VSpacer(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(0x02 to "Standard (02)", 0x0D to "Neuere H6xxx (0D)", 0x0B to "0B", 0x15 to "Segmente (15)").forEach { (code, label) ->
                GlassChip(label, colorMode == code) {
                    colorMode = code
                    prefs.goveeColorMode = code
                    govee.resendState()
                }
            }
        }
        VSpacer(14.dp)
        T("Helligkeitsskala", 14.sp, FontWeight.Medium, TextSecondary)
        VSpacer(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("0–255", !b100) { b100 = false; prefs.goveeBrightness100 = false; govee.resendState() }
            GlassChip("0–100", b100) { b100 = true; prefs.goveeBrightness100 = true; govee.resendState() }
        }
        VSpacer(10.dp)
        T(
            "Reagiert die Farbe nicht, probiere einen anderen Farb-Befehl. Ist die Helligkeit nur ganz hell/dunkel, wechsle die Skala.",
            13.sp, color = TextTertiary,
        )
    }
}

// ------------------------------------------------------------------ AirPods einrichten

@Composable
fun AirPodsSetupSheet(airpods: AirPodsController, prefs: Prefs, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(airpods.pairedDevices()) }
    var selected by remember { mutableStateOf(prefs.airpodsAddress) }

    GlassSheet("AirPods einrichten", onDismiss) {
        T(
            "AirPods zuerst einmalig in den Android-Bluetooth-Einstellungen koppeln (Case öffnen, Knopf hinten gedrückt halten). Danach hier auswählen.",
            15.sp, color = TextSecondary,
        )
        VSpacer(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton("Bluetooth-Einstellungen") {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            GlassButton("Liste aktualisieren") { devices = airpods.pairedDevices() }
        }
        VSpacer(18.dp)
        T("Gekoppelte Geräte", 17.sp, FontWeight.SemiBold)
        VSpacer(10.dp)
        if (devices.isEmpty()) T("Keine gekoppelten Geräte gefunden.", 14.sp, color = TextTertiary)
        devices.forEach { d ->
            val isSel = d.address == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .bouncy {
                        selected = d.address
                        airpods.selectDevice(d.address)
                        onDismiss()
                    }
                    .glass(18.dp, if (isSel) 1.6f else 0.8f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    T(d.name, 17.sp, FontWeight.SemiBold)
                    T(d.address, 12.sp, color = TextTertiary)
                }
                if (isSel) T("Ausgewählt", 13.sp, FontWeight.Bold, AccentBlue)
            }
        }
        VSpacer(16.dp)
        T(
            "Akku wird über Bluetooth-LE-Signale gelesen. ANC/Transparenz nutzt Apples AAP-Protokoll (L2CAP) – " +
                "das klappt nicht auf jedem Android-Bluetooth-Stack; Status siehst du in der AirPods-Karte.",
            13.sp, color = TextTertiary,
        )
    }
}

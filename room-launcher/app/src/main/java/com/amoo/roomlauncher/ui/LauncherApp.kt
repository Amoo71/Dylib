package com.amoo.roomlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amoo.roomlauncher.BtPerms
import com.amoo.roomlauncher.MainActivity
import com.amoo.roomlauncher.RoomApp
import com.amoo.roomlauncher.toast
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Overlay { NONE, APPS, EXIT, GOVEE, AIRPODS }

@Composable
fun LauncherApp(app: RoomApp, activity: MainActivity) {
    val context = LocalContext.current
    var permsOk by remember { mutableStateOf(BtPerms.granted(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permsOk = BtPerms.granted(context)
        if (permsOk) activity.startBluetooth()
    }
    LaunchedEffect(Unit) {
        if (permsOk) activity.startBluetooth() else permLauncher.launch(BtPerms.required())
    }

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    val homePresses by app.homePresses.collectAsState()
    LaunchedEffect(homePresses) { overlay = Overlay.NONE }
    BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

    val power by app.govee.power.collectAsState()
    val lightColor by app.govee.color.collectAsState()
    val goveeConn by app.govee.connection.collectAsState()
    val lightOn = power && goveeConn == com.amoo.roomlauncher.govee.GoveeController.Connection.CONNECTED

    Box(Modifier.fillMaxSize()) {
        LiquidBackground(accent = if (lightOn) Color(lightColor) else null)

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 26.dp),
        ) {
            val wide = maxWidth > maxHeight && maxWidth >= 700.dp
            Column(Modifier.fillMaxSize()) {
                ClockHeader()
                VSpacer(18.dp)
                if (wide) {
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        LightCard(app.govee, { overlay = Overlay.GOVEE }, Modifier.weight(1.2f).fillMaxHeight())
                        AirPodsCard(app.airpods, { overlay = Overlay.AIRPODS }, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        LightCard(app.govee, { overlay = Overlay.GOVEE }, Modifier.fillMaxWidth().height(470.dp))
                        AirPodsCard(app.airpods, { overlay = Overlay.AIRPODS }, Modifier.fillMaxWidth().height(470.dp))
                    }
                }
                VSpacer(22.dp)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Dock(app.apps, onOpenAll = { overlay = Overlay.APPS })
                }
            }
        }

        if (!permsOk) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp),
            ) {
                GlassButton("Bluetooth-Berechtigung erteilen", highlighted = true) {
                    permLauncher.launch(BtPerms.required())
                }
            }
        }

        // Exit-Knopf unten rechts: gedrückt halten, damit niemand versehentlich rausfliegt.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .alpha(0.55f)
                .size(46.dp)
                .bouncy(onLongClick = { overlay = Overlay.EXIT }) {
                    context.toast("Zum Verlassen gedrückt halten")
                }
                .glass(23.dp, 0.8f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = TextPrimary, modifier = Modifier.size(20.dp))
        }

        Crossfade(targetState = overlay, label = "overlay") { current ->
            val close = { overlay = Overlay.NONE }
            when (current) {
                Overlay.APPS -> AppsSheet(app.apps, close)
                Overlay.EXIT -> ExitSheet(app.apps, app.prefs, { activity.applyKeepScreenOn(it) }, close)
                Overlay.GOVEE -> GoveeSetupSheet(app.govee, app.prefs, close)
                Overlay.AIRPODS -> AirPodsSetupSheet(app.airpods, app.prefs, close)
                Overlay.NONE -> Unit
            }
        }
    }
}

@Composable
private fun ClockHeader() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.GERMANY).format(now) }
    val date = remember(now) { SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(now) }
    Column {
        T(date, 22.sp, FontWeight.SemiBold, TextSecondary)
        T(time, 92.sp, FontWeight.Light, TextPrimary)
    }
}

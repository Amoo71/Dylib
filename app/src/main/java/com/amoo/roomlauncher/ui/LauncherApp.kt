package com.amoo.roomlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amoo.roomlauncher.BtPerms
import com.amoo.roomlauncher.MainActivity
import com.amoo.roomlauncher.RoomApp
import com.amoo.roomlauncher.board.Board
import com.amoo.roomlauncher.board.BoardItem
import com.amoo.roomlauncher.theme.Backdrop
import com.amoo.roomlauncher.theme.BgImage
import com.amoo.roomlauncher.theme.BgType
import com.amoo.roomlauncher.theme.LocalBackdrop
import com.amoo.roomlauncher.theme.ThemeBackground
import com.amoo.roomlauncher.theme.rememberThemeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Overlay { NONE, APPS, SETTINGS, GOVEE, AIRPODS }

private val DEFAULT_PACKAGES = listOf(
    "com.spotify.music", "com.google.android.youtube", "com.google.android.apps.youtube.music",
    "com.netflix.mediaclient", "com.android.chrome", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus",
)

@Composable
fun LauncherApp(app: RoomApp, activity: MainActivity) {
    val context = LocalContext.current

    // ---- Berechtigungen
    var permsOk by remember { mutableStateOf(BtPerms.granted(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permsOk = BtPerms.granted(context)
        if (permsOk) activity.startBluetooth()
    }
    LaunchedEffect(Unit) {
        if (permsOk) activity.startBluetooth() else permLauncher.launch(BtPerms.required())
    }

    // ---- Zustand
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var settingsTab by remember { mutableStateOf(SettingsTab.BACKGROUND) }
    var editMode by remember { mutableStateOf(false) }
    val homePresses by app.homePresses.collectAsState()
    LaunchedEffect(homePresses) {
        overlay = Overlay.NONE
        editMode = false
    }
    BackHandler(enabled = overlay != Overlay.NONE || editMode) {
        if (overlay != Overlay.NONE) overlay = Overlay.NONE else editMode = false
    }

    val items by app.board.items.collectAsState()
    val dock by app.board.dock.collectAsState()
    val dockEnabled by app.board.dockEnabled.collectAsState()
    val apps by app.apps.apps.collectAsState()
    val appMap = remember(apps) { apps.associateBy { it.component.flattenToString() } }
    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) app.board.initDefaultApps(apps.map { it.component.flattenToString() }, DEFAULT_PACKAGES)
    }

    // ---- Hintergrund
    val theme by app.theme.settings.collectAsState()
    val imageVersion by app.theme.imageVersion.collectAsState()
    val bgImage by produceState<BgImage?>(null, theme.type, theme.ownImage, imageVersion) {
        value = if (theme.type == BgType.OWN && theme.ownImage != null) {
            withContext(Dispatchers.IO) { BgImage.load(context) }
        } else {
            null
        }
    }
    val clock = rememberThemeClock()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val backdrop = remember(theme, bgImage, screen) { Backdrop(theme, clock, bgImage, screen) }

        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            ThemeBackground(backdrop)

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 30.dp, end = 30.dp, top = 30.dp, bottom = 16.dp),
            ) {
                Board(
                    items = items,
                    editMode = editMode,
                    onEnterEdit = { editMode = true },
                    onMove = { id, to -> app.board.move(id, to) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    editBadges = { item ->
                        RemoveBadge { app.board.remove(item.id) }
                        if (item is BoardItem.Widget && item.kind.sizes.size > 1) {
                            ResizeBadge(item.size.label) { app.board.cycleSize(item.id) }
                        }
                    },
                ) { item, _ ->
                    when (item) {
                        is BoardItem.Widget -> WidgetHost(
                            item = item,
                            govee = app.govee,
                            airpods = app.airpods,
                            onSetupLight = { overlay = Overlay.GOVEE },
                            onSetupAirPods = { overlay = Overlay.AIRPODS },
                        )
                        is BoardItem.App -> {
                            val entry = appMap[item.component]
                            AppIcon(entry = entry, onLaunch = { if (!editMode && entry != null) app.apps.launch(entry) }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                if (dockEnabled) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Dock(
                            components = dock,
                            appMap = appMap,
                            editMode = editMode,
                            onEnterEdit = { editMode = true },
                            onLaunch = { app.apps.launch(it) },
                            onRemove = { app.board.removeFromDock(it) },
                            onMove = { c, i -> app.board.moveInDock(c, i) },
                            onOpenAll = { overlay = Overlay.APPS },
                        )
                    }
                }
            }

            // Bearbeiten-Leiste oben
            AnimatedVisibility(
                visible = editMode,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton("+ Widget") {
                        settingsTab = SettingsTab.WIDGETS
                        overlay = Overlay.SETTINGS
                    }
                    GlassButton("+ App") {
                        settingsTab = SettingsTab.APPS
                        overlay = Overlay.SETTINGS
                    }
                    GlassButton("Fertig", highlighted = true) { editMode = false }
                }
            }

            if (!permsOk) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                ) {
                    GlassButton("Bluetooth-Berechtigung erteilen", highlighted = true) {
                        permLauncher.launch(BtPerms.required())
                    }
                }
            }

            // Einstellungen unten rechts
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .alpha(0.7f)
                    .size(46.dp)
                    .bouncy {
                        settingsTab = SettingsTab.BACKGROUND
                        overlay = Overlay.SETTINGS
                    }
                    .glass(23.dp, 0.9f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Settings, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
            }

            Crossfade(targetState = overlay, label = "overlay") { current ->
                val close = { overlay = Overlay.NONE }
                when (current) {
                    Overlay.APPS -> AppsSheet(app.apps, app.board, close)
                    Overlay.SETTINGS -> SettingsSheet(
                        app = app,
                        activity = activity,
                        initialTab = settingsTab,
                        onOpenGovee = { overlay = Overlay.GOVEE },
                        onOpenAirPods = { overlay = Overlay.AIRPODS },
                        onDismiss = close,
                    )
                    Overlay.GOVEE -> GoveeSetupSheet(app.govee, app.prefs, close)
                    Overlay.AIRPODS -> AirPodsSetupSheet(app.airpods, app.prefs, close)
                    Overlay.NONE -> Unit
                }
            }
        }
    }
}

@Suppress("unused")
@Composable
private fun EditHint() {
    T("Ziehen zum Verschieben", 13.sp, FontWeight.Medium, TextSecondary)
}

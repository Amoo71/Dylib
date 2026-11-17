package com.amoo.roomlauncher.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.amoo.roomlauncher.BtPerms
import com.amoo.roomlauncher.CrashLog
import com.amoo.roomlauncher.MainActivity
import com.amoo.roomlauncher.RoomApp
import com.amoo.roomlauncher.apps.AppEntry
import com.amoo.roomlauncher.ble.BleHub
import com.amoo.roomlauncher.board.BoardItem
import com.amoo.roomlauncher.board.BoardLayout
import com.amoo.roomlauncher.board.Cell
import com.amoo.roomlauncher.theme.BackgroundView
import com.amoo.roomlauncher.theme.BgImage
import com.amoo.roomlauncher.theme.BgType
import com.amoo.roomlauncher.theme.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DEFAULT_PACKAGES = listOf(
    "com.spotify.music", "com.google.android.youtube", "com.google.android.apps.youtube.music",
    "com.netflix.mediaclient", "com.android.chrome", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus",
)

/** Der komplette Launcher-Bildschirm: Hintergrund, Board, Dock, Einstellungen und Overlays. */
@SuppressLint("ViewConstructor")
class LauncherRoot(private val activity: MainActivity, private val app: RoomApp) : FrameLayout(activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val bgView = BackgroundView(activity)
    private val board = BoardLayout(activity, columns = 8, rows = 4)
    private val boardScroll = object : ScrollView(activity) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            post { board.viewportHeight = h }
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            Backdrop.invalidateAll()
        }
    }
    private val dock = BoardLayout(activity, columns = 1, rows = 1, fixedCellDp = 60).apply { gap = dp(18) }
    private val dockBar = LinearLayout(activity)
    private val dockDivider = View(activity)
    private val dockGlass = GlassDrawable(activity, 34f, 1.15f)
    private val editBar = LinearLayout(activity)
    private val banner = LinearLayout(activity)
    private val overlayHost = FrameLayout(activity)

    private var appMap: Map<String, AppEntry> = emptyMap()
    private var appsVersion = 0
    private var boardAppsVersion = -1
    private var dockAppsVersion = -1
    private var lastTheme: ThemeSettings? = null

    var editMode = false
        private set

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(bgView, LayoutParams(MATCH, MATCH))

        // ---- Board + Dock
        val content = context.column().apply { setPadding(dp(30), dp(28), dp(30), dp(14)) }
        boardScroll.isVerticalScrollBarEnabled = false
        boardScroll.overScrollMode = View.OVER_SCROLL_NEVER
        boardScroll.isFillViewport = true
        boardScroll.clipToPadding = false
        boardScroll.addView(board, LayoutParams(MATCH, WRAP))
        content.addView(boardScroll, lp(MATCH, 0, 1f))

        dockBar.orientation = LinearLayout.HORIZONTAL
        dockBar.gravity = Gravity.CENTER_VERTICAL
        dockBar.addView(dock, lp(WRAP, WRAP))
        dockDivider.setBackgroundColor(C.white(0.22f))
        dockBar.addView(dockDivider, lp(dp(1), dp(44)).margins(l = dp(18), r = dp(18)))
        val allApps = FrameLayout(activity).apply {
            background = GlassDrawable(activity, 18f, 1.3f)
            addView(IconView(activity, Glyph.GRID), LayoutParams(dp(24), dp(24), Gravity.CENTER))
            pressable { showOverlay(context.appsSheet(app) { closeOverlay() }) }
        }
        dockBar.addView(allApps, lp(dp(60), dp(60)))
        content.addView(dockBar, lp(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })
        addView(content, LayoutParams(MATCH, MATCH))

        // ---- Bearbeiten-Leiste
        editBar.orientation = LinearLayout.HORIZONTAL
        editBar.addView(context.glassButton("+ Widget") { openSettings(SettingsTab.WIDGETS) })
        editBar.addView(context.glassButton("+ App") { openSettings(SettingsTab.APPS) }, lp(WRAP, WRAP).margins(l = dp(12)))
        editBar.addView(context.glassButton("Fertig", true) { setEdit(false) }, lp(WRAP, WRAP).margins(l = dp(12)))
        editBar.visibility = View.GONE
        addView(editBar, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })

        // ---- Hinweise (Berechtigung / Standort / Bluetooth)
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        banner.background = GlassDrawable(activity, 22f, 1.2f, 0x80201508.toInt())
        banner.setPadding(dp(18), dp(8), dp(8), dp(8))
        banner.visibility = View.GONE
        addView(banner, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })

        // ---- Einstellungen unten rechts
        val settings = context.glassIconButton(Glyph.GEAR, 46, frosted = true) { openSettings(SettingsTab.BACKGROUND) }
        addView(settings, LayoutParams(dp(46), dp(46), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(14), dp(14)) })

        addView(overlayHost, LayoutParams(MATCH, MATCH))

        // ---- Listener
        board.listener = object : BoardLayout.Listener {
            override fun onEnterEdit() = setEdit(true)
            override fun onMove(id: String, toIndex: Int) = app.board.move(id, toIndex)
            override fun onRemove(id: String) = app.board.remove(id)
            override fun onResize(id: String) = app.board.cycleSize(id)
            override fun onEmptyTap() = setEdit(false)
        }
        dock.listener = object : BoardLayout.Listener {
            override fun onEnterEdit() = setEdit(true)
            override fun onMove(id: String, toIndex: Int) = app.board.moveInDock(id, toIndex)
            override fun onRemove(id: String) = app.board.removeFromDock(id)
            override fun onEmptyTap() = setEdit(false)
        }

        // ---- Daten beobachten
        scope.launch { app.board.items.collect { renderBoard() } }
        scope.launch { app.board.dock.collect { renderDock() } }
        scope.launch {
            app.board.dockEnabled.collect { dockBar.visibility = if (it) View.VISIBLE else View.GONE }
        }
        scope.launch {
            app.apps.apps.collect { list ->
                appMap = list.associateBy { it.key }
                appsVersion++
                if (list.isNotEmpty()) app.board.initDefaultApps(list.map { it.key }, DEFAULT_PACKAGES)
                renderBoard()
                renderDock()
            }
        }
        scope.launch {
            app.iconStyle.collect {
                appsVersion++
                renderBoard()
                renderDock()
            }
        }
        scope.launch { app.smooth.collect { bgView.smooth = it } }
        scope.launch {
            app.theme.settings.collect { t ->
                Backdrop.enabled = t.glassBlur
                Backdrop.opacity = t.glassOpacity
                bgView.theme = t
                val prev = lastTheme
                lastTheme = t
                if (prev == null || prev.type != t.type || prev.ownImage != t.ownImage) loadImage(t)
            }
        }
        scope.launch { app.theme.imageVersion.collect { loadImage(app.theme.settings.value) } }
    }

    private fun loadImage(t: ThemeSettings) {
        if (t.type != BgType.OWN || t.ownImage == null) {
            bgView.image = null
            return
        }
        scope.launch {
            val img = withContext(Dispatchers.IO) { BgImage.load(context) }
            bgView.image = img
        }
    }

    // ---------------------------------------------------------------- Rendern

    private fun renderBoard() {
        val items = app.board.items.value
        val cells = items.map { item ->
            when (item) {
                is BoardItem.Widget -> Cell(item.id, item.w, item.h, if (item.kind.sizes.size > 1) item.size.label else null)
                is BoardItem.App -> Cell(item.id, 1, 1)
            }
        }
        val recreateApps = boardAppsVersion != appsVersion
        boardAppsVersion = appsVersion
        val style = app.iconStyle.value
        board.setCells(
            cells,
            factory = { cell ->
                // Ein fehlerhaftes Widget darf nie den ganzen Launcher abstürzen lassen
                try {
                    when (val item = items.first { it.id == cell.id }) {
                        is BoardItem.Widget -> context.widgetView(
                            item.kind, item.size, app.govee, app.airpods,
                            onOpenLight = { if (!editMode) showOverlay(context.goveeSheet(app.govee, app.prefs) { closeOverlay() }) },
                            onOpenAirPods = { if (!editMode) showOverlay(context.airPodsSheet(app.airpods, app.prefs) { closeOverlay() }) },
                        )
                        is BoardItem.App -> {
                            val entry = appMap[item.component]
                            context.appIcon(entry, style) { if (!editMode && entry != null) app.apps.launch(entry) }
                        }
                    }
                } catch (e: Throwable) {
                    CrashLog.write(context, e)
                    context.glassCard(16).apply {
                        addView(context.label("Widget-Fehler", 15f, C.ORANGE, bold = true))
                        addView(context.label(e.javaClass.simpleName, 12f, C.TEXT3))
                    }
                }
            },
            recreate = { cell -> recreateApps && items.firstOrNull { it.id == cell.id } is BoardItem.App },
        )
    }

    private fun renderDock() {
        val recreate = dockAppsVersion != appsVersion
        dockAppsVersion = appsVersion
        val style = app.iconStyle.value
        val comps = app.board.dock.value
        dock.setCells(
            comps.map { Cell(it, 1, 1) },
            factory = { cell ->
                val entry = appMap[cell.id]
                AppIconTile(context, entry?.icon, style).apply {
                    pressable { if (!editMode && entry != null) app.apps.launch(entry) }
                }
            },
            recreate = { recreate },
        )
        // Nur der Alle-Apps-Knopf → kein Kasten. Mit Apps: [App App App | Alle Apps] in der Glasleiste.
        if (comps.isEmpty()) {
            dock.visibility = View.GONE
            dockDivider.visibility = View.GONE
            dockBar.background = null
            dockBar.setPadding(0, 0, 0, 0)
        } else {
            dock.visibility = View.VISIBLE
            dockDivider.visibility = View.VISIBLE
            if (dockBar.background == null) dockBar.background = dockGlass
            dockBar.setPadding(dp(18), dp(12), dp(18), dp(12))
        }
    }

    // ---------------------------------------------------------------- Hinweise

    /** Prüft Berechtigung, Bluetooth und Standort und zeigt bei Bedarf einen Hinweis mit Knopf. */
    fun refreshBanner() {
        val ctx: Context = context
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val (text, action, onAction) = when {
            !BtPerms.granted(ctx) -> Triple("Bluetooth-Berechtigung fehlt", "Erlauben") { activity.requestBtPermissions() }
            adapter != null && !adapter.isEnabled -> Triple("Bluetooth ist aus", "Einschalten") {
                runCatching { activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                Unit
            }
            !BleHub.locationEnabled(ctx) -> Triple("Standort ist aus – Licht & AirPods-Akku brauchen ihn", "Einschalten") {
                activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            else -> Triple(null, null, null)
        }
        banner.removeAllViews()
        if (text == null || editMode) {
            banner.visibility = View.GONE
            return
        }
        banner.addView(ctx.label(text, 14f, C.ORANGE, bold = true))
        banner.addView(ctx.glassButton(action!!, true) { onAction?.invoke() }, lp(WRAP, WRAP).margins(l = dp(14)))
        banner.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- Zustand

    fun setEdit(on: Boolean) {
        if (editMode == on) return
        editMode = on
        board.editMode = on
        dock.editMode = on
        if (on) {
            banner.visibility = View.GONE
            editBar.visibility = View.VISIBLE
            editBar.alpha = 0f
            editBar.translationY = -dp(20).toFloat()
            editBar.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(SPRING).start()
        } else {
            editBar.animate().alpha(0f).translationY(-dp(20).toFloat()).setDuration(160).withEndAction {
                editBar.visibility = View.GONE
                refreshBanner()
            }.start()
        }
    }

    private fun openSettings(tab: SettingsTab) {
        showOverlay(
            activity.settingsSheet(
                app, tab,
                onOpenGovee = { showOverlay(context.goveeSheet(app.govee, app.prefs) { closeOverlay() }) },
                onOpenAirPods = { showOverlay(context.airPodsSheet(app.airpods, app.prefs) { closeOverlay() }) },
                onDismiss = { closeOverlay() },
            ),
        )
    }

    private fun showOverlay(v: Sheet) {
        overlayHost.removeAllViews()
        overlayHost.addView(v, LayoutParams(MATCH, MATCH))
        v.animateIn()
    }

    fun closeOverlay(): Boolean {
        val current = overlayHost.getChildAt(overlayHost.childCount - 1) as? Sheet ?: return false
        current.animateOut { overlayHost.removeView(current) }
        refreshBanner()
        return true
    }

    /** Zurück-Taste: erst Overlay, dann Bearbeiten beenden. Verlässt den Launcher nie. */
    fun onBack() {
        if (!closeOverlay()) setEdit(false)
    }

    /** Home-Taste, während der Launcher vorne ist. */
    fun onHome() {
        closeOverlay()
        setEdit(false)
    }

    fun dispose() = scope.cancel()
}

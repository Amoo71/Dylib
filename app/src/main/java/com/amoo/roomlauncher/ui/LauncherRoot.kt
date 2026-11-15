package com.amoo.roomlauncher.ui

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.amoo.roomlauncher.MainActivity
import com.amoo.roomlauncher.RoomApp
import com.amoo.roomlauncher.apps.AppEntry
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
            board.viewportHeight = h
        }
    }
    private val dock = BoardLayout(activity, columns = 1, rows = 1, fixedCellDp = 58).apply { gap = dp(18) }
    private val dockBar = LinearLayout(activity)
    private val editBar = LinearLayout(activity)
    private val overlayHost = FrameLayout(activity)

    private var appMap: Map<String, AppEntry> = emptyMap()
    private var lastAppsVersion = 0
    private var appsVersion = 0
    private var lastTheme: ThemeSettings? = null

    var editMode = false
        private set

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(bgView, LayoutParams(MATCH, MATCH))

        // ---- Board + Dock
        val content = context.column().apply { setPadding(dp(30), dp(30), dp(30), dp(14)) }
        boardScroll.isVerticalScrollBarEnabled = false
        boardScroll.overScrollMode = View.OVER_SCROLL_NEVER
        boardScroll.isFillViewport = true
        boardScroll.addView(board, LayoutParams(MATCH, WRAP))
        content.addView(boardScroll, lp(MATCH, 0, 1f))

        dockBar.orientation = LinearLayout.HORIZONTAL
        dockBar.gravity = Gravity.CENTER_VERTICAL
        dockBar.background = GlassDrawable(activity, 32f, 1.1f)
        dockBar.setPadding(dp(18), dp(12), dp(18), dp(12))
        dockBar.addView(dock, lp(WRAP, WRAP))
        val divider = View(activity).apply { setBackgroundColor(C.white(0.22f)) }
        dockBar.addView(divider, lp(dp(1), dp(42)).margins(l = dp(18), r = dp(18)))
        val allApps = FrameLayout(activity).apply {
            background = GlassDrawable(activity, 18f, 1.3f)
            addView(IconView(activity, Glyph.GRID), LayoutParams(dp(24), dp(24), Gravity.CENTER))
            pressable { showOverlay(context.appsSheet(app) { closeOverlay() }) }
        }
        dockBar.addView(allApps, lp(dp(58), dp(58)))
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
        addView(editBar, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })

        // ---- Einstellungen unten rechts
        val settings = context.glassIconButton(Glyph.GEAR, 46) { openSettings(SettingsTab.BACKGROUND) }
        settings.alpha = 0.75f
        addView(settings, LayoutParams(dp(46), dp(46), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(12)) })

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
            app.theme.settings.collect { t ->
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
        val recreateApps = lastAppsVersion != appsVersion
        lastAppsVersion = appsVersion
        board.setCells(
            cells,
            factory = { cell ->
                when (val item = items.first { it.id == cell.id }) {
                    is BoardItem.Widget -> context.widgetView(
                        item.kind, item.size, app.govee, app.airpods,
                        onSetupLight = { showOverlay(context.goveeSheet(app.govee, app.prefs) { closeOverlay() }) },
                        onSetupAirPods = { showOverlay(context.airPodsSheet(app.airpods, app.prefs) { closeOverlay() }) },
                    )
                    is BoardItem.App -> {
                        val entry = appMap[item.component]
                        context.appIcon(entry) { if (!editMode && entry != null) app.apps.launch(entry) }
                    }
                }
            },
            recreate = { cell -> recreateApps && items.firstOrNull { it.id == cell.id } is BoardItem.App },
        )
    }

    private var lastDockAppsVersion = 0

    private fun renderDock() {
        val recreate = lastDockAppsVersion != appsVersion
        lastDockAppsVersion = appsVersion
        dock.setCells(
            app.board.dock.value.map { Cell(it, 1, 1) },
            factory = { cell ->
                val entry = appMap[cell.id]
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (entry != null) setImageBitmap(entry.icon) else background = GlassDrawable(context, 16f)
                    pressable { if (!editMode && entry != null) app.apps.launch(entry) }
                }
            },
            recreate = { recreate },
        )
        // Trenner nur zeigen, wenn Apps im Dock sind
        dockBar.getChildAt(1)?.visibility = if (app.board.dock.value.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- Zustand

    fun setEdit(on: Boolean) {
        if (editMode == on) return
        editMode = on
        board.editMode = on
        dock.editMode = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
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

    private fun showOverlay(v: View) {
        overlayHost.removeAllViews()
        v.alpha = 0f
        overlayHost.addView(v, LayoutParams(MATCH, MATCH))
        v.animate().alpha(1f).setDuration(160).start()
    }

    fun closeOverlay(): Boolean {
        if (overlayHost.childCount == 0) return false
        app.govee.stopScan()
        overlayHost.removeAllViews()
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

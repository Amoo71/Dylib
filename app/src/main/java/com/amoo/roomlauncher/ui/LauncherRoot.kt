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
    private val board = BoardLayout(activity, columns = com.amoo.roomlauncher.board.BoardStore.COLUMNS, rows = com.amoo.roomlauncher.board.BoardStore.ROWS)
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
    private val widgetGlass = HashMap<String, Boolean>()

    var editMode = false
        private set

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(bgView, LayoutParams(MATCH, MATCH))

        // ---- Oben: AirPods-Abzeichen (rechts) · Board · Dock
        val content = context.column().apply { setPadding(dp(30), dp(10), dp(30), dp(14)) }
        val topBar = FrameLayout(activity)
        // Mini-Widgets: AirPods und AirPlay links, Licht rechts
        topBar.addView(
            context.row(airPodsBadge(), airPlayBadge(), spacingDp = 10),
            LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL),
        )
        topBar.addView(lightBadge(), LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        content.addView(topBar, lp(MATCH, dp(38)).margins(b = dp(10)))
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
        // Board, Dock und Abzeichen als eine fertige GPU-Ebene: Bewegt sich nur der Hintergrund,
        // wird diese Ebene nur noch eingeblendet statt jedes Bild komplett neu gezeichnet (spart viel CPU).
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
            override fun onDrop(id: String, col: Int, row: Int) = app.board.place(id, col, row)
            override fun onToggleGlass(id: String) {
                val w = app.board.items.value.firstOrNull { it.id == id } as? BoardItem.Widget ?: return
                app.board.setGlass(id, !w.glass)
            }
            override fun onRemove(id: String) = app.board.remove(id)
            override fun onResize(id: String) = app.board.cycleSize(id)
            override fun onEmptyTap() = setEdit(false)
            override fun onItemLongPress(id: String, view: View): Boolean = openPreview(id, view)
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
        scope.launch {
            app.smooth.collect {
                bgView.smooth = it
                LumenRing.smooth = it
            }
        }
        scope.launch { app.eco.collect { bgView.eco = it } }
        // Bluetooth an/aus → Hinweisleiste sofort aktualisieren (auch über die Schnelleinstellungen)
        scope.launch { app.bluetoothOn.collect { refreshBanner() } }
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
                is BoardItem.Widget -> Cell(item.id, item.w, item.h, if (item.kind.sizes.size > 1) item.size.label else null, item.col, item.row, item.glass)
                is BoardItem.App -> Cell(item.id, 1, 1, null, item.col, item.row)
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
                            item.kind, item.size, app.govee, app.airpods, app.airplay, item.glass,
                            onOpenLight = { if (!editMode) showOverlay(context.goveeSheet(app.govee, app.prefs) { closeOverlay() }) },
                            onOpenAirPods = { if (!editMode) showOverlay(context.airPodsSheet(app.airpods, app.prefs) { closeOverlay() }) },
                            onOpenAirPlay = { if (!editMode) showOverlay(context.airPlaySheet(app.airplay) { closeOverlay() }) },
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
            recreate = { cell ->
                when (val item = items.firstOrNull { it.id == cell.id }) {
                    is BoardItem.App -> recreateApps
                    // Glas-Container umgeschaltet → Widget neu bauen
                    is BoardItem.Widget -> (widgetGlass[item.id] ?: item.glass) != item.glass
                    null -> false
                }
            },
        )
        widgetGlass.clear()
        items.filterIsInstance<BoardItem.Widget>().forEach { widgetGlass[it.id] = it.glass }
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
            !BtPerms.granted(ctx) -> Triple(BtPerms.missingText(), "Erlauben") { activity.requestBtPermissions() }
            adapter != null && !app.bluetoothOn.value && !BleHub.bluetoothOn(ctx) -> Triple("Bluetooth ist aus", "Einschalten") {
                runCatching { activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                Unit
            }
            !BleHub.locationEnabled(ctx) -> Triple("Standort ist aus – die Licht-Suche braucht ihn", "Einschalten") {
                activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            else -> Triple(null, null, null)
        }
        com.amoo.roomlauncher.L.i(
            "Prüfung: Rechte=${BtPerms.granted(ctx)}, Bluetooth=${BleHub.bluetoothOn(ctx)}, Standort=${BleHub.locationEnabled(ctx)} → Hinweis: ${text ?: "keiner"}",
        )
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

    private fun showOverlay(v: Sheet, origin: View? = null) {
        overlayHost.removeAllViews()
        overlayHost.addView(v, LayoutParams(MATCH, MATCH))
        v.animateIn(origin)
        LumenRing.boardPaused = true // Board-Animationen ruhen, solange ein Panel offen ist
    }

    /** Widget gedrückt halten → große Ansicht, die aus dem Widget herauswächst. Apps → Bearbeiten. */
    private fun openPreview(id: String, view: View): Boolean {
        if (editMode) return false
        val item = app.board.items.value.firstOrNull { it.id == id } as? BoardItem.Widget ?: return false
        val sheet = when (item.kind) {
            com.amoo.roomlauncher.board.WidgetKind.LIGHT -> context.goveeSheet(app.govee, app.prefs) { closeOverlay() }
            com.amoo.roomlauncher.board.WidgetKind.AIRPODS -> context.airPodsSheet(app.airpods, app.prefs) { closeOverlay() }
            com.amoo.roomlauncher.board.WidgetKind.AIRPLAY -> context.airPlaySheet(app.airplay) { closeOverlay() }
            com.amoo.roomlauncher.board.WidgetKind.CLOCK -> context.clockSheet(
                onEdit = {
                    closeOverlay()
                    setEdit(true)
                },
                onDismiss = { closeOverlay() },
            )
        }
        showOverlay(sheet, view)
        return true
    }

    /**
     * Mini-Widget Licht (oben rechts): leuchtender Punkt in der Lichtfarbe, Status mit Helligkeit,
     * Mond + Restzeit beim Nacht-Timer und ein kleiner Power-Knopf. Tippen = große Licht-Ansicht.
     */
    private fun lightBadge(): View {
        val ctx: Context = context
        val g = app.govee
        val glow = MiniGlow(ctx)
        val title = ctx.label("Licht", 13.5f, bold = true)
        val state = ctx.label("", 12.5f, C.TEXT2)
        val moon = IconView(ctx, Glyph.MOON, 0xFFB9A8FF.toInt())
        val moonText = ctx.label("", 12f, 0xFFB9A8FF.toInt(), bold = true)
        val moonBox = ctx.row(moon.also { it.layoutParams = lp(dp(12), dp(12)) }, moonText, spacingDp = 4)
        val powerIcon = IconView(ctx, Glyph.POWER)
        val powerBg = RoundDrawable(ctx, 14f, C.white(0.12f), C.white(0.2f))
        val power = FrameLayout(ctx).apply {
            background = powerBg
            addView(powerIcon, LayoutParams(dp(13), dp(13), Gravity.CENTER))
            pressable { g.togglePower() }
        }
        val badge = ctx.row(
            glow.also { it.layoutParams = lp(dp(18), dp(18)) },
            title,
            state,
            moonBox,
            power.also { it.layoutParams = lp(dp(28), dp(28)).margins(l = dp(4)) },
            spacingDp = 8,
        ).apply {
            background = GlassDrawable(ctx, 19f, 1.1f)
            setPadding(dp(12), dp(5), dp(5), dp(5))
            pressable { showOverlay(ctx.goveeSheet(app.govee, app.prefs) { closeOverlay() }, this) }
        }
        fun refresh() {
            val on = g.power.value
            val c = g.connection.value
            val newState = when (c) {
                com.amoo.roomlauncher.govee.GoveeController.Connection.NOT_CONFIGURED -> com.amoo.roomlauncher.Lang.auto("nicht eingerichtet")
                com.amoo.roomlauncher.govee.GoveeController.Connection.CONNECTED ->
                    if (on) com.amoo.roomlauncher.tr("an", "on") + " · ${(g.brightness.value * 100).toInt().coerceAtLeast(1)}%" else com.amoo.roomlauncher.tr("aus", "off")
                else -> com.amoo.roomlauncher.Lang.auto("sucht…")
            }
            // Nur bei Änderung setzen – sonst wird die ganze Board-Ebene unnötig neu gezeichnet
            if (state.text.toString() != newState) state.text = newState
            state.setTextColor(if (on && c == com.amoo.roomlauncher.govee.GoveeController.Connection.CONNECTED) C.TEXT else C.TEXT2)
            val col = if (g.segmented.value) LightColors.average(g.segments.value) ?: g.color.value else g.color.value
            glow.set(col, on)
            val bgCol = if (on) C.white(0.92f) else C.white(0.12f)
            if (powerBg.color != bgCol) {
                powerBg.color = bgCol
                powerIcon.color = if (on) 0xFF101012.toInt() else C.TEXT
                power.invalidate()
                powerIcon.invalidate()
            }
            val at = g.sleepAt.value
            val vis = if (at == 0L) View.GONE else View.VISIBLE
            if (moonBox.visibility != vis) moonBox.visibility = vis
            if (at != 0L) sleepLeft(at).let { if (moonText.text.toString() != it) moonText.text = it }
        }
        badge.bind(g.power) { refresh() }
        badge.bind(g.brightness) { refresh() }
        badge.bind(g.connection) { refresh() }
        badge.bind(g.color) { refresh() }
        badge.bind(g.segments) { refresh() }
        badge.bind(g.sleepAt) { refresh() }
        badge.everyWhileAttached(30_000) { refresh() }
        return badge
    }

    /** Mini-Widget AirPlay: Symbol, Status (bereit/aus/spielt) bzw. der laufende Titel. Tippen = große Ansicht. */
    private fun airPlayBadge(): View {
        val ctx: Context = context
        val a = app.airplay
        val glyph = AirPlayGlyph(ctx)
        val title = ctx.label("AirPlay", 13.5f, bold = true)
        val state = ctx.label("", 12.5f, C.TEXT2).apply { maxWidth = dp(190) }
        val dot = ctx.dot(C.TEXT3, 7)
        val badge = ctx.row(
            glyph.also { it.layoutParams = lp(dp(18), dp(18)) },
            title,
            dot.also { it.layoutParams = lp(dp(7), dp(7)).margins(l = dp(4)) },
            state,
            spacingDp = 7,
        ).apply {
            background = GlassDrawable(ctx, 19f, 1.1f)
            setPadding(dp(12), dp(7), dp(16), dp(7))
            pressable { showOverlay(ctx.airPlaySheet(a) { closeOverlay() }, this) }
        }
        fun refresh() {
            val np = a.nowPlaying.value
            val text = when {
                !a.installed.value -> com.amoo.roomlauncher.Lang.auto("nicht installiert")
                np?.title != null && np.playing -> "♪ " + np.title
                a.running.value -> com.amoo.roomlauncher.Lang.auto("bereit")
                else -> com.amoo.roomlauncher.Lang.auto("aus")
            }
            if (state.text.toString() != text) state.text = text
            val c = if (a.running.value) C.GREEN else C.TEXT3
            if ((dot.background as? RoundDrawable)?.color != c) {
                (dot.background as? RoundDrawable)?.color = c
                dot.invalidate()
            }
            badge.alpha = if (a.running.value) 1f else 0.8f
        }
        badge.bind(a.installed) { refresh() }
        badge.bind(a.running) { refresh() }
        badge.bind(a.nowPlaying) { refresh() }
        return badge
    }

    /** Mini-Abzeichen oben links: AirPods-Symbol, Name, verbunden oder nicht. Tippen = kleines AirPods-Panel. */
    private fun airPodsBadge(): View {
        val ctx: Context = context
        val name = ctx.label("AirPods", 13.5f, bold = true)
        val state = ctx.label("", 12.5f, C.TEXT2)
        val dot = ctx.dot(C.TEXT3, 7)
        val badge = ctx.row(
            AirPodsGlyph(ctx).apply { layoutParams = lp(dp(22), dp(22)) },
            name,
            dot.also { it.layoutParams = lp(dp(7), dp(7)).margins(l = dp(4)) },
            state,
            spacingDp = 7,
        ).apply {
            background = GlassDrawable(ctx, 19f, 1.1f)
            setPadding(dp(12), dp(7), dp(16), dp(7))
            pressable { showOverlay(ctx.airPodsSheet(app.airpods, app.prefs) { closeOverlay() }, this) }
        }
        fun refresh() {
            val n = app.airpods.deviceName.value
            val c = app.airpods.audioConnected.value
            name.text = n ?: "AirPods"
            state.text = com.amoo.roomlauncher.Lang.auto(when {
                n == null -> "nicht gekoppelt"
                c -> "verbunden"
                else -> "nicht verbunden"
            })
            state.setTextColor(if (c) C.TEXT else C.TEXT2)
            (dot.background as? RoundDrawable)?.color = if (c) C.GREEN else C.TEXT3
            dot.invalidate()
            badge.alpha = if (c) 1f else 0.8f
        }
        badge.bind(app.airpods.deviceName) { refresh() }
        badge.bind(app.airpods.audioConnected) { refresh() }
        return badge
    }

    fun closeOverlay(): Boolean {
        val current = overlayHost.getChildAt(overlayHost.childCount - 1) as? Sheet ?: return false
        current.animateOut {
            overlayHost.removeView(current)
            if (overlayHost.childCount == 0) LumenRing.boardPaused = false
        }
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

    /** Jede Berührung weckt die (im Leerlauf pausierte) Hintergrund-Animation. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) bgView.wake()
        return super.dispatchTouchEvent(ev)
    }

    fun dispose() = scope.cancel()
}

/** Kleiner leuchtender Punkt in der Lichtfarbe (aus = dunkel). */
private class MiniGlow(context: Context) : View(context) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var color = android.graphics.Color.WHITE
    private var on = false

    fun set(c: Int, isOn: Boolean) {
        if (c == color && isOn == on) return
        color = c
        on = isOn
        p.shader = null
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = minOf(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val col = if (on) color or 0xFF000000.toInt() else 0xFF3A3A3C.toInt()
        if (p.shader == null) {
            p.shader = android.graphics.RadialGradient(
                cx, cy, r,
                intArrayOf(col, LightColors.withAlpha(col, if (on) 110 else 0), 0),
                floatArrayOf(0.35f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, r, p)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        p.shader = null
    }
}

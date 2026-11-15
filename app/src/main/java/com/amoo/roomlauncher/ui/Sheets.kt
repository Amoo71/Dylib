package com.amoo.roomlauncher.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.amoo.roomlauncher.MainActivity
import com.amoo.roomlauncher.Prefs
import com.amoo.roomlauncher.RoomApp
import com.amoo.roomlauncher.airpods.AirPodsController
import com.amoo.roomlauncher.apps.AppEntry
import com.amoo.roomlauncher.board.BoardItem
import com.amoo.roomlauncher.board.WidgetKind
import com.amoo.roomlauncher.govee.GoveeController
import com.amoo.roomlauncher.theme.BgEffect
import com.amoo.roomlauncher.theme.BgType
import com.amoo.roomlauncher.theme.BubbleColor
import com.amoo.roomlauncher.theme.ThemeSettings
import com.amoo.roomlauncher.toast
import kotlin.math.roundToInt

// ------------------------------------------------------------------ Grundgerüst

/** Modales Glas-Panel über abgedunkeltem Hintergrund. */
class Sheet(context: Context, title: String, fillHeight: Boolean, val onDismiss: () -> Unit) : FrameLayout(context) {
    val body: LinearLayout

    init {
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        setOnClickListener { onDismiss() }
        val panel = context.column().apply {
            background = GlassDrawable(context, 34f, 1.2f, 0xE010121A.toInt())
            setPadding(dp(26), dp(24), dp(26), dp(22))
            isClickable = true
        }
        val header = context.row(
            context.label(title, 26f, bold = true).also { it.layoutParams = lp(0, WRAP, 1f) },
            context.glassIconButton(Glyph.CLOSE, 40) { onDismiss() },
        )
        panel.addView(header)
        body = context.column()
        panel.addView(body, lp(MATCH, if (fillHeight) 0 else WRAP, if (fillHeight) 1f else 0f).margins(t = dp(16)))
        val dm = resources.displayMetrics
        val w = minOf(dp(860), (dm.widthPixels * 0.94f).toInt())
        val h = if (fillHeight) (dm.heightPixels * 0.9f).toInt() else WRAP
        addView(panel, LayoutParams(w, h, Gravity.CENTER))
        if (!fillHeight) panel.layoutParams = (panel.layoutParams as LayoutParams).apply { height = WRAP }
    }
}

private fun Context.sectionTitle(text: String): TextView = label(text, 15f, C.TEXT2, bold = true)

private fun LinearLayout.section(text: String) {
    addView(context.sectionTitle(text), lp().margins(t = context.dp(18), b = context.dp(10)))
}

private fun Context.searchField(hint: String, onChange: (String) -> Unit): EditText = EditText(this).apply {
    this.hint = hint
    setHintTextColor(C.TEXT3)
    setTextColor(C.TEXT)
    textSize = 16f
    isSingleLine = true
    background = GlassDrawable(context, 20f, 0.8f)
    setPadding(dp(18), dp(12), dp(18), dp(12))
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
    })
}

/** Slider-Zeile mit Beschriftung und Wertanzeige. */
private fun Context.sliderRow(title: String, value: Float, format: (Float) -> String, onChange: (Float) -> Unit): LinearLayout {
    val name = label(title, 15f).apply { layoutParams = lp(dp(170), WRAP) }
    val shown = label(format(value), 14f, C.TEXT2).apply { layoutParams = lp(dp(64), WRAP).margins(l = dp(12)) }
    val slider = GlassSlider(this, 34) {
        shown.text = format(it)
        onChange(it)
    }.apply {
        this.value = value
        layoutParams = lp(0, WRAP, 1f)
    }
    return row(name, slider, shown).apply { setPadding(0, dp(5), 0, dp(5)) }
}

private fun Context.switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val t = label(title, 16f).apply { layoutParams = lp(0, WRAP, 1f) }
    return row(t, GlassSwitch(this, checked, onChange)).apply { setPadding(0, dp(6), 0, dp(6)) }
}

private fun Context.chipGroup(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
    val chips = ArrayList<Chip>()
    labels.forEachIndexed { i, l ->
        chips += Chip(this, l, i == selected) {
            chips.forEachIndexed { j, c -> c.chipSelected = j == i }
            onSelect(i)
        }
    }
    return hScroll(*chips.toTypedArray(), spacingDp = 10)
}

// ------------------------------------------------------------------ Alle Apps

fun Context.appsSheet(app: RoomApp, onDismiss: () -> Unit): Sheet {
    val sheet = Sheet(this, "Apps", true, onDismiss)
    var all: List<AppEntry> = app.apps.apps.value
    var shown = all
    val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = shown[position]
            val v = (convertView as? LinearLayout) ?: column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(ImageView(context), lp(dp(60), dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(label("", 13f).apply { gravity = Gravity.CENTER }, lp().margins(t = dp(6)))
            }
            (v.getChildAt(0) as ImageView).setImageBitmap(e.icon)
            (v.getChildAt(1) as TextView).text = e.label
            return v
        }
    }
    val grid = GridView(this).apply {
        numColumns = GridView.AUTO_FIT
        columnWidth = dp(110)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        verticalSpacing = dp(6)
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        this.adapter = adapter
        setOnItemClickListener { _, _, pos, _ ->
            onDismiss()
            app.apps.launch(shown[pos])
        }
        setOnItemLongClickListener { _, _, pos, _ ->
            val e = shown[pos]
            app.board.addApp(e.key)
            toast("${e.label} liegt jetzt auf dem Board")
            true
        }
    }
    sheet.body.addView(searchField("Suchen") { q ->
        shown = if (q.isBlank()) all else all.filter { it.label.contains(q.trim(), true) }
        adapter.notifyDataSetChanged()
    })
    sheet.body.addView(label("Tippen = öffnen · gedrückt halten = aufs Board legen", 12f, C.TEXT3), lp().margins(t = dp(8), b = dp(8)))
    sheet.body.addView(grid, lp(MATCH, 0, 1f))
    grid.bind(app.apps.apps) {
        all = it
        shown = it
        adapter.notifyDataSetChanged()
    }
    return sheet
}

// ------------------------------------------------------------------ Einstellungen

enum class SettingsTab(val label: String) {
    BACKGROUND("Hintergrund"),
    WIDGETS("Widgets"),
    APPS("Apps"),
    DOCK("Dock"),
    DEVICES("Geräte"),
    GENERAL("Allgemein"),
}

private val SWATCHES = longArrayOf(
    0xFF0A0A0F, 0xFF1C1C2E, 0xFF2B1D8F, 0xFF3B2DFF, 0xFF0A84FF, 0xFF00B4D8, 0xFF0F766E, 0xFF34C759,
    0xFFA3E635, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A, 0xFFFF2D87, 0xFF8A1D5C, 0xFFBF5AF2, 0xFF5E5CE6,
).map { it.toInt() }

fun MainActivity.settingsSheet(
    app: RoomApp,
    initial: SettingsTab,
    onOpenGovee: () -> Unit,
    onOpenAirPods: () -> Unit,
    onDismiss: () -> Unit,
): Sheet {
    val sheet = Sheet(this, "Einstellungen", true, onDismiss)
    val content = FrameLayout(this)
    fun show(tab: SettingsTab) {
        content.removeAllViews()
        val v: View = when (tab) {
            SettingsTab.BACKGROUND -> scroll(backgroundTab(app))
            SettingsTab.WIDGETS -> scroll(widgetsTab(app))
            SettingsTab.APPS -> appsTab(app)
            SettingsTab.DOCK -> scroll(dockTab(app))
            SettingsTab.DEVICES -> scroll(devicesTab(onOpenGovee, onOpenAirPods))
            SettingsTab.GENERAL -> scroll(generalTab(app, onDismiss))
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }
    val tabs = SettingsTab.entries
    sheet.body.addView(chipGroup(tabs.map { it.label }, tabs.indexOf(initial)) { show(tabs[it]) })
    sheet.body.addView(content, lp(MATCH, 0, 1f).margins(t = dp(14)))
    show(initial)
    return sheet
}

// ---- Hintergrund

private fun MainActivity.backgroundTab(app: RoomApp): View {
    val root = column()
    val dynamic = column()
    fun theme() = app.theme.settings.value
    fun update(f: (ThemeSettings) -> ThemeSettings) = app.theme.update(f)

    lateinit var rebuild: () -> Unit
    rebuild = {
        dynamic.removeAllViews()
        val t = theme()
        when (t.type) {
            BgType.RAIN -> rainControls(dynamic, app)
            BgType.BLACK -> bubbleControls(dynamic, app, allowThemeColor = false)
            BgType.COLORED -> {
                dynamic.section("Farben (1 = einfarbig, mehrere = Verlauf, max. 4)")
                val preview = View(this).apply { layoutParams = lp(MATCH, dp(26)).margins(t = dp(12)) }
                fun paintPreview() {
                    val cols = theme().colors
                    preview.background = if (cols.size > 1) {
                        android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, cols.toIntArray(),
                        ).apply { cornerRadius = dpf(13) }
                    } else {
                        RoundDrawable(this, 13f, cols.firstOrNull() ?: Color.BLACK)
                    }
                }
                val swatchViews = ArrayList<TextView>()
                fun paintSwatches() {
                    val cols = theme().colors
                    swatchViews.forEachIndexed { i, v ->
                        val idx = cols.indexOf(SWATCHES[i])
                        v.background = RoundDrawable(this, 20f, SWATCHES[i], if (idx >= 0) Color.WHITE else C.white(0.3f))
                        v.text = if (idx >= 0) "${idx + 1}" else ""
                    }
                }
                SWATCHES.forEach { c ->
                    swatchViews += label("", 13f, bold = true).apply {
                        gravity = Gravity.CENTER
                        layoutParams = lp(dp(40), dp(40))
                        pressable {
                            update {
                                val list = it.colors.toMutableList()
                                if (c in list) {
                                    if (list.size > 1) list.remove(c)
                                } else if (list.size < 4) {
                                    list.add(c)
                                }
                                it.copy(colors = list)
                            }
                            paintSwatches()
                            paintPreview()
                        }
                    }
                }
                paintSwatches()
                paintPreview()
                dynamic.addView(hScroll(*swatchViews.toTypedArray(), spacingDp = 10))
                dynamic.addView(preview)
                dynamic.addView(
                    sliderRow("Verlaufswinkel", t.gradientAngle / 360f, { "${(it * 360).roundToInt()}°" }) { v ->
                        update { it.copy(gradientAngle = v * 360f) }
                    },
                    lp().margins(t = dp(12)),
                )
                bubbleControls(dynamic, app, allowThemeColor = true)
            }
            BgType.OWN -> {
                dynamic.section("Eigenes Bild oder GIF")
                dynamic.addView(
                    glassButton(if (t.ownImage == null) "Bild/GIF auswählen" else "Anderes Bild wählen", true) {
                        pickImage { ok ->
                            if (ok) {
                                app.theme.bumpImageVersion()
                                update { it.copy(type = BgType.OWN, ownImage = "file") }
                                rebuild()
                            } else {
                                toast("Bild konnte nicht geladen werden")
                            }
                        }
                    },
                    lp(WRAP, WRAP),
                )
                dynamic.addView(
                    sliderRow("Abdunkeln", t.ownDim / 0.9f, { "${(it * 90).roundToInt()} %" }) { v -> update { it.copy(ownDim = v * 0.9f) } },
                    lp().margins(t = dp(12)),
                )
                dynamic.section("Effekt darüber")
                val effects = BgEffect.entries
                dynamic.addView(chipGroup(effects.map { it.label }, effects.indexOf(t.ownEffect)) { i ->
                    update { it.copy(ownEffect = effects[i]) }
                    rebuild()
                })
                when (t.ownEffect) {
                    BgEffect.RAIN -> rainControls(dynamic, app)
                    BgEffect.BUBBLES -> bubbleControls(dynamic, app, allowThemeColor = false)
                    BgEffect.NONE -> Unit
                }
            }
        }
    }

    root.addView(sectionTitle("Thema"), lp().margins(b = dp(10)))
    val types = BgType.entries
    root.addView(chipGroup(types.map { it.label }, types.indexOf(theme().type)) { i ->
        update { it.copy(type = types[i]) }
        rebuild()
    })
    root.addView(dynamic)
    rebuild()
    root.section("Liquid Glass")
    root.addView(
        label(
            "Das Glas ist durchsichtig: Regen, Blasen und Farben schimmern durch alle Widgets und den Dock.",
            13f, C.TEXT3, lines = 3,
        ),
    )
    root.addView(spacer(20))
    return root
}

private fun Context.rainControls(parent: LinearLayout, app: RoomApp) {
    val t = app.theme.settings.value
    parent.section("Regen")
    parent.addView(sliderRow("Stärke", t.rainIntensity / 3f, { "${(it * 300).roundToInt()} %" }) { v ->
        app.theme.update { it.copy(rainIntensity = (v * 3f).coerceAtLeast(0.05f)) }
    })
    parent.addView(sliderRow("Wind", (t.rainWind + 1f) / 2f, { "${((it * 2 - 1) * 100).roundToInt()}" }) { v ->
        app.theme.update { it.copy(rainWind = v * 2f - 1f) }
    })
    parent.addView(sliderRow("Geschwindigkeit", t.rainSpeed / 2f, { "${(it * 200).roundToInt()} %" }) { v ->
        app.theme.update { it.copy(rainSpeed = (v * 2f).coerceAtLeast(0.1f)) }
    })
}

private fun Context.bubbleControls(parent: LinearLayout, app: RoomApp, allowThemeColor: Boolean) {
    val t = app.theme.settings.value
    parent.section("Blasen")
    parent.addView(sliderRow("Anzahl", t.bubbleCount / 40f, { "${(it * 40).roundToInt()}" }) { v ->
        app.theme.update { it.copy(bubbleCount = (v * 40).roundToInt()) }
    })
    parent.addView(sliderRow("Größe", t.bubbleSize / 2.5f, { "${(it * 250).roundToInt()} %" }) { v ->
        app.theme.update { it.copy(bubbleSize = (v * 2.5f).coerceAtLeast(0.2f)) }
    })
    parent.addView(sliderRow("Tempo", t.bubbleSpeed / 4f, { "${(it * 400).roundToInt()} %" }) { v ->
        app.theme.update { it.copy(bubbleSpeed = (v * 4f).coerceAtLeast(0.1f)) }
    })
    parent.addView(sliderRow("Deckkraft", t.bubbleOpacity, { "${(it * 100).roundToInt()} %" }) { v ->
        app.theme.update { it.copy(bubbleOpacity = v) }
    })
    val colors = BubbleColor.entries.filter { allowThemeColor || it != BubbleColor.THEME }
    parent.addView(
        row(
            label("Farbe", 15f).apply { layoutParams = lp(dp(170), WRAP) },
            chipGroup(colors.map { it.label }, colors.indexOf(t.bubbleColor)) { i ->
                app.theme.update { it.copy(bubbleColor = colors[i]) }
            },
        ).apply { setPadding(0, dp(6), 0, dp(6)) },
    )
}

// ---- Widgets

private fun Context.widgetsTab(app: RoomApp): View {
    val root = column()
    root.addView(label("Widget-Größe antippen, um es zum Board hinzuzufügen.", 14f, C.TEXT2, lines = 2))
    WidgetKind.entries.forEach { kind ->
        val info = column().apply {
            addView(label(kind.title, 18f, bold = true))
            addView(
                label(
                    when (kind) {
                        WidgetKind.CLOCK -> "Uhrzeit und Datum"
                        WidgetKind.LIGHT -> "Govee-LED: an/aus, Helligkeit, Farbe, Szenen"
                        WidgetKind.AIRPODS -> "Akku, Verbinden, ANC / Transparenz / Adaptiv"
                    },
                    13f, C.TEXT3,
                ),
                lp().margins(t = dp(3)),
            )
            layoutParams = lp(0, WRAP, 1f)
        }
        val chips = kind.sizes.map { size ->
            Chip(this, "+ ${size.label}") {
                app.board.addWidget(kind, size)
                toast("${kind.title} (${size.label}) hinzugefügt")
            }
        }
        val r = row(info, *chips.toTypedArray(), spacingDp = 8).apply {
            background = GlassDrawable(context, 20f, 0.8f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(r, lp().margins(t = dp(12)))
    }
    root.addView(
        label(
            "Tipp: Auf dem Board ein Widget gedrückt halten → ziehen zum Verschieben, „Größe“ antippen zum Ändern, „–“ zum Entfernen.",
            13f, C.TEXT3, lines = 3,
        ),
        lp().margins(t = dp(14)),
    )
    return root
}

// ---- Apps

private fun Context.appsTab(app: RoomApp): View {
    val ctx = this
    val root = column()
    var query = ""
    var list: List<AppEntry> = emptyList()
    val adapter = object : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(position: Int) = list[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = list[position]
            val onBoard = app.board.items.value.any { it is BoardItem.App && it.component == e.key }
            val inDock = e.key in app.board.dock.value
            val icon = ImageView(ctx).apply {
                setImageBitmap(e.icon)
                layoutParams = lp(dp(40), dp(40))
            }
            val name = label(e.label, 16f).apply { layoutParams = lp(0, WRAP, 1f).margins(l = dp(14)) }
            val boardChip = Chip(ctx, if (onBoard) "✓ Board" else "+ Board", onBoard) {
                if (onBoard) {
                    app.board.items.value.firstOrNull { it is BoardItem.App && it.component == e.key }?.let { app.board.remove(it.id) }
                } else {
                    app.board.addApp(e.key)
                }
                notifyDataSetChanged()
            }
            val dockChip = Chip(ctx, if (inDock) "✓ Dock" else "+ Dock", inDock) {
                if (inDock) app.board.removeFromDock(e.key) else app.board.addToDock(e.key)
                notifyDataSetChanged()
            }
            return row(icon, name, boardChip, dockChip, spacingDp = 0).apply {
                (dockChip.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
                setPadding(0, dp(6), 0, dp(6))
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }
    }
    fun refresh() {
        val all = app.apps.apps.value
        list = if (query.isBlank()) all else all.filter { it.label.contains(query.trim(), true) }
        adapter.notifyDataSetChanged()
    }
    root.addView(searchField("App suchen") {
        query = it
        refresh()
    })
    val lv = ListView(this).apply {
        divider = null
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        this.adapter = adapter
    }
    root.addView(lv, lp(MATCH, 0, 1f).margins(t = dp(10)))
    lv.bind(app.apps.apps) { refresh() }
    return root
}

// ---- Dock

private fun Context.dockTab(app: RoomApp): View {
    val root = column()
    root.addView(switchRow("Dock anzeigen", app.board.dockEnabled.value) { app.board.setDockEnabled(it) })
    val listBox = column()
    root.section("Apps im Dock (max. 10)")
    root.addView(listBox)
    root.addView(
        label("Tipp: Dock gedrückt halten → Icons ziehen zum Sortieren, „–“ zum Entfernen. Neue Apps im Tab „Apps“ mit „+ Dock“.", 13f, C.TEXT3, lines = 3),
        lp().margins(t = dp(12)),
    )
    listBox.bind(app.board.dock) { dock ->
        listBox.removeAllViews()
        if (dock.isEmpty()) listBox.addView(label("Noch leer.", 14f, C.TEXT3))
        val apps = app.apps.apps.value
        dock.forEachIndexed { i, comp ->
            val entry = apps.firstOrNull { it.key == comp }
            val icon = ImageView(this).apply {
                entry?.let { setImageBitmap(it.icon) }
                layoutParams = lp(dp(40), dp(40))
            }
            val name = label(entry?.label ?: comp.substringBefore('/'), 16f).apply { layoutParams = lp(0, WRAP, 1f).margins(l = dp(14)) }
            val views = mutableListOf<View>(icon, name)
            if (i > 0) views += Chip(this, "←") { app.board.moveInDock(comp, i - 1) }
            if (i < dock.size - 1) views += Chip(this, "→") { app.board.moveInDock(comp, i + 1) }
            views += Chip(this, "Entfernen") { app.board.removeFromDock(comp) }
            listBox.addView(row(*views.toTypedArray(), spacingDp = 6).apply { setPadding(0, dp(5), 0, dp(5)) })
        }
    }
    return root
}

// ---- Geräte

private fun Context.devicesTab(onOpenGovee: () -> Unit, onOpenAirPods: () -> Unit): View = column().apply {
    addView(label("Bluetooth-Geräte einrichten", 14f, C.TEXT2))
    addView(
        row(glassButton("Govee-Licht einrichten", true, onOpenGovee), glassButton("AirPods einrichten", true, onOpenAirPods), spacingDp = 12),
        lp().margins(t = dp(14)),
    )
}

// ---- Allgemein

private fun MainActivity.generalTab(app: RoomApp, onDismiss: () -> Unit): View {
    val root = column()
    root.addView(switchRow("Bildschirm immer an", app.prefs.keepScreenOn) {
        app.prefs.keepScreenOn = it
        applyKeepScreenOn(it)
    })
    root.section("Launcher verlassen")
    root.addView(
        label("Solange Room Launcher der Standard-Startbildschirm ist, bringt die Home-Taste dich immer hierher zurück.", 14f, C.TEXT2, lines = 3),
    )
    app.apps.otherLaunchers().forEach { l ->
        val r = row(
            ImageView(this).apply {
                setImageBitmap(l.icon)
                layoutParams = lp(dp(40), dp(40))
            },
            label("${l.label} öffnen", 17f, bold = true),
            spacingDp = 14,
        ).apply {
            background = GlassDrawable(context, 20f, 0.8f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            pressable {
                onDismiss()
                app.apps.launchHome(l)
            }
        }
        root.addView(r, lp().margins(t = dp(10)))
    }
    root.addView(
        row(
            glassButton("Standard-Startbildschirm ändern") {
                val ok = runCatching {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!ok) startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            glassButton("Android-Einstellungen") {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            spacingDp = 12,
        ),
        lp().margins(t = dp(14)),
    )
    val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
    root.addView(label("Room Launcher $version", 12f, C.TEXT3), lp().margins(t = dp(20)))
    return root
}

// ------------------------------------------------------------------ Govee einrichten

fun Context.goveeSheet(govee: GoveeController, prefs: Prefs, onDismiss: () -> Unit): Sheet {
    val sheet = Sheet(this, "Govee-Licht einrichten", true, {
        govee.stopScan()
        onDismiss()
    })
    val root = column()
    val current = label("", 15f, C.TEXT2)
    current.bind(govee.deviceName) { current.text = "Aktuell: ${it ?: "keins"}" }
    root.addView(current)
    root.addView(
        label("Wichtig: Govee-App auf dem Handy schließen – das Licht erlaubt nur eine Bluetooth-Verbindung gleichzeitig.", 13f, C.TEXT3, lines = 2),
        lp().margins(t = dp(4)),
    )
    val head = label("Suche Geräte…", 17f, bold = true).apply { layoutParams = lp(0, WRAP, 1f) }
    head.bind(govee.scanning) { head.text = if (it) "Suche Geräte…" else "Gefundene Geräte" }
    root.addView(row(head, glassButton("Neu suchen") { govee.startScan() }), lp().margins(t = dp(14)))
    val err = label("", 13f, C.RED, lines = 2)
    err.bind(govee.lastError) {
        err.text = it.orEmpty()
        err.visibility = if (it == null) View.GONE else View.VISIBLE
    }
    root.addView(err)
    val list = column()
    root.addView(list, lp().margins(t = dp(10)))
    list.bind(govee.found) { found ->
        list.removeAllViews()
        if (found.isEmpty()) list.addView(label("Noch nichts gefunden. Ist das Licht an und in der Nähe?", 14f, C.TEXT3, lines = 2))
        found.take(25).forEach { f ->
            val info = column().apply {
                addView(label(f.name, 17f, bold = f.likelyGovee))
                addView(label("${f.address} · ${f.rssi} dBm", 12f, C.TEXT3))
                layoutParams = lp(0, WRAP, 1f)
            }
            val r = row(info).apply {
                if (f.likelyGovee) addView(label("Govee", 13f, C.GREEN, bold = true))
                background = GlassDrawable(context, 18f, if (f.likelyGovee) 1.4f else 0.7f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                pressable {
                    govee.select(f)
                    sheet.onDismiss()
                }
            }
            list.addView(r, lp().margins(b = dp(8)))
        }
    }
    root.section("Farb-Befehl (modellabhängig)")
    val modes = listOf(0x02 to "Standard (02)", 0x0D to "Neuere H6xxx (0D)", 0x0B to "0B", 0x15 to "Segmente (15)")
    root.addView(chipGroup(modes.map { it.second }, modes.indexOfFirst { it.first == prefs.goveeColorMode }) { i ->
        prefs.goveeColorMode = modes[i].first
        govee.resendState()
    })
    root.section("Helligkeitsskala")
    root.addView(chipGroup(listOf("0–255", "0–100"), if (prefs.goveeBrightness100) 1 else 0) { i ->
        prefs.goveeBrightness100 = i == 1
        govee.resendState()
    })
    root.addView(
        label("Reagiert die Farbe nicht, anderen Farb-Befehl probieren. Ist die Helligkeit nur ganz hell/dunkel, Skala wechseln.", 13f, C.TEXT3, lines = 2),
        lp().margins(t = dp(12)),
    )
    sheet.body.addView(scroll(root), lp(MATCH, 0, 1f))
    govee.startScan()
    return sheet
}

// ------------------------------------------------------------------ AirPods einrichten

fun Context.airPodsSheet(airpods: AirPodsController, prefs: Prefs, onDismiss: () -> Unit): Sheet {
    val sheet = Sheet(this, "AirPods einrichten", true, onDismiss)
    val root = column()
    root.addView(
        label(
            "AirPods zuerst einmalig in den Android-Bluetooth-Einstellungen koppeln (Case öffnen, Knopf hinten gedrückt halten). Danach hier auswählen.",
            15f, C.TEXT2, lines = 3,
        ),
    )
    val list = column()
    fun fill() {
        list.removeAllViews()
        val devices = airpods.pairedDevices()
        if (devices.isEmpty()) list.addView(label("Keine gekoppelten Geräte gefunden.", 14f, C.TEXT3))
        devices.forEach { d ->
            val sel = d.address == prefs.airpodsAddress
            val info = column().apply {
                addView(label(d.name, 17f, bold = true))
                addView(label(d.address, 12f, C.TEXT3))
                layoutParams = lp(0, WRAP, 1f)
            }
            val r = row(info).apply {
                if (sel) addView(label("Ausgewählt", 13f, C.BLUE, bold = true))
                background = GlassDrawable(context, 18f, if (sel) 1.6f else 0.8f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                pressable {
                    airpods.selectDevice(d.address)
                    onDismiss()
                }
            }
            list.addView(r, lp().margins(b = dp(8)))
        }
    }
    root.addView(
        row(
            glassButton("Bluetooth-Einstellungen") {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            glassButton("Liste aktualisieren") { fill() },
            spacingDp = 12,
        ),
        lp().margins(t = dp(14)),
    )
    root.section("Gekoppelte Geräte")
    root.addView(list)
    root.addView(
        label(
            "Akku und „im Ohr“ kommen aus Bluetooth-LE-Signalen. ANC/Transparenz nutzt Apples AAP-Protokoll – " +
                "auf Android 4.4 lässt der Bluetooth-Stack diesen Kanal meist nicht zu; der Status steht im großen AirPods-Widget.",
            13f, C.TEXT3, lines = 4,
        ),
        lp().margins(t = dp(14)),
    )
    fill()
    sheet.body.addView(scroll(root), lp(MATCH, 0, 1f))
    return sheet
}

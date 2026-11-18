package com.amoo.roomlauncher.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import com.amoo.roomlauncher.ble.BleHub
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

/** Modales Milchglas-Panel über abgedunkeltem Hintergrund, mit Ein-/Ausblend-Animation. */
class Sheet(context: Context, title: String, fillHeight: Boolean, val onDismiss: () -> Unit) : FrameLayout(context) {
    val body: LinearLayout
    private val panel: LinearLayout
    private var closing = false

    init {
        setBackgroundColor(0x8C000000.toInt())
        isClickable = true
        setOnClickListener { onDismiss() }
        panel = context.column().apply {
            background = GlassDrawable(context, 34f, 1.2f, 0xB80E1016.toInt(), adaptive = false)
            setPadding(dp(26), dp(24), dp(26), dp(22))
            isClickable = true
        }
        val header = context.row(
            context.label(title, 26f, display = true).also { it.layoutParams = lp(0, WRAP, 1f) },
            context.glassIconButton(Glyph.CLOSE, 40) { onDismiss() },
        )
        panel.addView(header)
        body = context.column()
        panel.addView(body, lp(MATCH, if (fillHeight) 0 else WRAP, if (fillHeight) 1f else 0f).margins(t = dp(16)))
        val dm = resources.displayMetrics
        val w = minOf(dp(880), (dm.widthPixels * 0.94f).toInt())
        val h = if (fillHeight) (dm.heightPixels * 0.9f).toInt() else WRAP
        addView(panel, LayoutParams(w, h, Gravity.CENTER))
    }

    fun animateIn() {
        alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.translationY = dp(24).toFloat()
        animate().alpha(1f).setDuration(180).setInterpolator(EASE_OUT).start()
        panel.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(320).setInterpolator(SPRING)
            .withEndAction { Backdrop.invalidateAll() }.start()
    }

    fun animateOut(then: () -> Unit) {
        if (closing) return
        closing = true
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
        panel.animate().scaleX(0.96f).scaleY(0.96f).translationY(dp(12).toFloat()).setDuration(160).setInterpolator(EASE_OUT).start()
        animate().alpha(0f).setDuration(160).withEndAction(then).start()
    }
}

private fun Context.sectionTitle(text: String): TextView = label(text.uppercase(), 12.5f, C.TEXT3, bold = true)

private fun LinearLayout.section(text: String) {
    addView(context.sectionTitle(text), lp().margins(t = context.dp(22), b = context.dp(10)))
}

private fun Context.searchField(hint: String, onChange: (String) -> Unit): EditText = EditText(this).apply {
    this.hint = hint
    typeface = Fonts.regular
    setHintTextColor(C.TEXT3)
    setTextColor(C.TEXT)
    textSize = 16f
    isSingleLine = true
    background = GlassDrawable(context, 20f, 0.8f, frosted = false)
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
    val shown = label(format(value), 14f, C.TEXT2).apply {
        gravity = Gravity.END
        layoutParams = lp(dp(64), WRAP).margins(l = dp(12))
    }
    val slider = GlassSlider(this, 32) {
        shown.text = format(it)
        onChange(it)
    }.apply {
        this.value = value
        layoutParams = lp(0, WRAP, 1f)
    }
    return row(name, slider, shown).apply { setPadding(0, dp(6), 0, dp(6)) }
}

private fun Context.switchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit): LinearLayout {
    val text = column().apply {
        addView(label(title, 16f))
        if (subtitle != null) addView(label(subtitle, 12.5f, C.TEXT3, lines = 2), lp().margins(t = dp(3)))
        layoutParams = lp(0, WRAP, 1f).margins(r = dp(12))
    }
    return row(text, GlassSwitch(this, checked, onChange)).apply { setPadding(0, dp(8), 0, dp(8)) }
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

/** Listeneintrag auf Glas (für Geräte-Listen). */
private fun Context.listItem(title: String, subtitle: String, badge: String?, badgeColor: Int, strong: Boolean, onClick: () -> Unit): View {
    val info = column().apply {
        addView(label(title, 17f, bold = strong))
        addView(label(subtitle, 12f, C.TEXT3), lp().margins(t = dp(3)))
        layoutParams = lp(0, WRAP, 1f)
    }
    return row(info).apply {
        if (badge != null) addView(label(badge, 13f, badgeColor, bold = true))
        background = GlassDrawable(context, 18f, if (strong) 1.4f else 0.8f, frosted = false)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        pressable(onClick = onClick)
    }
}

/** Hinweis, wenn der Standort aus ist – ohne ihn findet Android keine BLE-Geräte. */
private fun Context.locationHint(): View? {
    if (BleHub.locationEnabled(this)) return null
    return row(
        label("Standort ist aus – ohne ihn findet Android keine Bluetooth-Geräte.", 13.5f, C.ORANGE, lines = 2).apply {
            layoutParams = lp(0, WRAP, 1f)
        },
        glassButton("Einschalten") {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        spacingDp = 12,
    ).apply {
        background = RoundDrawable(context, 18f, 0x26FF9F0A, 0x55FF9F0A)
        setPadding(dp(16), dp(10), dp(10), dp(10))
    }
}

// ------------------------------------------------------------------ Alle Apps

fun Context.appsSheet(app: RoomApp, onDismiss: () -> Unit): Sheet {
    val sheet = Sheet(this, "Apps", true, onDismiss)
    val style = app.iconStyle.value
    var all: List<AppEntry> = app.apps.apps.value
    var shown = all
    val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = shown[position]
            val v = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(AppIconTile(context, e.icon, style), lp(dp(62), dp(62)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(label(e.label, 13f).apply { gravity = Gravity.CENTER }, lp().margins(t = dp(7)))
            }
            return v
        }
    }
    val grid = GridView(this).apply {
        numColumns = GridView.AUTO_FIT
        columnWidth = dp(112)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        verticalSpacing = dp(6)
        selector = ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
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
    sheet.body.addView(label("Tippen = öffnen · gedrückt halten = aufs Board legen", 12f, C.TEXT3), lp().margins(t = dp(10), b = dp(6)))
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
    EXIT("Verlassen"),
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
            SettingsTab.DEVICES -> scroll(devicesTab(app, onOpenGovee, onOpenAirPods))
            SettingsTab.GENERAL -> scroll(generalTab(app))
            SettingsTab.EXIT -> scroll(exitTab(app, onDismiss))
        }
        v.alpha = 0f
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.animate().alpha(1f).setDuration(160).start()
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
                dynamic.section("Farben – eine = einfarbig, mehrere = Verlauf (max. 4)")
                val preview = View(this).apply { layoutParams = lp(MATCH, dp(26)).margins(t = dp(12)) }
                fun paintPreview() {
                    val cols = theme().colors
                    preview.background = if (cols.size > 1) {
                        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, cols.toIntArray()).apply { cornerRadius = dpf(13) }
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
        switchRow(
            "Milchglas", theme().glassBlur,
            "Widgets, Dock und Panels zeigen den Hintergrund verschwommen – wie bei iOS. Aus = klares Glas, spart Leistung.",
        ) { on -> update { it.copy(glassBlur = on) } },
    )
    root.addView(
        sliderRow("Glas-Deckkraft", theme().glassOpacity, { "${(it * 100).roundToInt()} %" }) { v ->
            update { it.copy(glassOpacity = v) }
        },
    )
    root.addView(label("Niedrig = klares, sehr durchsichtiges Glas · hoch = stärker getönt.", 12.5f, C.TEXT3, lines = 2))
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
    root.addView(label("Größe antippen, um das Widget aufs Board zu legen.", 14f, C.TEXT2, lines = 2))
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
            background = GlassDrawable(context, 20f, 0.8f, frosted = false)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(r, lp().margins(t = dp(12)))
    }
    root.addView(
        label(
            "Tipp: Auf dem Board etwas gedrückt halten → ziehen zum Verschieben, „Größe“ zum Ändern, „–“ zum Entfernen.",
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
            return row(icon, name, boardChip, dockChip).apply {
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
        selector = ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        this.adapter = adapter
    }
    root.addView(lv, lp(MATCH, 0, 1f).margins(t = dp(10)))
    lv.bind(app.apps.apps) { refresh() }
    return root
}

// ---- Dock

private fun Context.dockTab(app: RoomApp): View {
    val root = column()
    root.addView(switchRow("Dock anzeigen", app.board.dockEnabled.value, "Die Glas-Leiste unten mit deinen Lieblings-Apps.") { app.board.setDockEnabled(it) })
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

private fun Context.devicesTab(app: RoomApp, onOpenGovee: () -> Unit, onOpenAirPods: () -> Unit): View = column().apply {
    locationHint()?.let { addView(it, lp().margins(b = dp(14))) }
    val light = app.govee.deviceName.value
    addView(listItem("Govee-Licht", light ?: "nicht eingerichtet", "Öffnen ›", C.BLUE, light != null, onOpenGovee))
    addView(spacer(10))
    val pods = app.airpods.deviceName.value
    addView(listItem("AirPods", pods ?: "nicht ausgewählt", "Öffnen ›", C.BLUE, pods != null, onOpenAirPods))

    // ---- Diagnose: zeigt live, ob Bluetooth, Licht und AirPods laufen
    section("Diagnose")
    val diag = label("", 13.5f, C.TEXT2, lines = 12).apply { setLineSpacing(dpf(4), 1f) }
    addView(diag)
    val update = object : Runnable {
        override fun run() {
            diag.text = diagnosis(app)
            diag.postDelayed(this, 1000)
        }
    }
    diag.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = update.run()
        override fun onViewDetachedFromWindow(v: View) {
            v.removeCallbacks(update)
        }
    })
    addView(
        row(
            glassButton("Alles neu verbinden") {
                app.ble.restart()
                app.govee.reconnectNow()
                app.airpods.resync()
                toast("Verbindungen werden neu aufgebaut")
            },
            glassButton("Bluetooth neu starten") {
                if (app.ble.resetBluetooth()) toast("Bluetooth startet neu …") else toast("Bitte Bluetooth manuell aus- und einschalten")
            },
            spacingDp = 12,
        ),
        lp().margins(t = dp(14)),
    )
    addView(
        label(
            "Die App repariert sich selbst: hängender Scan → Neustart, Licht antwortet nicht → neu verbinden, " +
                "wiederholte Bluetooth-Fehler → Bluetooth-Neustart (höchstens alle 30 min).",
            12.5f, C.TEXT3, lines = 4,
        ),
        lp().margins(t = dp(12)),
    )
}

private fun Context.diagnosis(app: RoomApp): String {
    val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    fun ok(b: Boolean) = if (b) "✓" else "✗"
    val now = System.currentTimeMillis()
    val last = app.ble.lastResultAt
    val age = if (last == 0L) "noch keine" else "vor ${((now - last) / 1000).coerceAtLeast(0)} s"
    val g = app.govee.connection.value
    val a = app.airpods
    return buildString {
        val btOn = BleHub.bluetoothOn(this@diagnosis)
        appendLine("${ok(btOn)}  Bluetooth ${if (btOn) "an" else "aus"}")
        appendLine("${ok(BleHub.locationEnabled(this@diagnosis))}  Standort (nötig für Bluetooth-Suche)")
        appendLine("${ok(app.ble.scanning)}  Bluetooth-Suche läuft · letztes Signal $age")
        app.ble.lastError?.let { appendLine("    ↳ $it") }
        appendLine("${ok(g == GoveeController.Connection.CONNECTED)}  Licht: ${g.name.lowercase()}${app.govee.deviceName.value?.let { " ($it)" } ?: ""}")
        app.govee.lastError.value?.let { appendLine("    ↳ $it") }
        if (app.prefs.goveeAddress != null) appendLine("    ↳ Signal: ${app.govee.signalInfo()}")
        appendLine("${ok(a.audioConnected.value)}  AirPods Audio ${if (a.audioConnected.value) "verbunden" else "nicht verbunden"}")
        val learned = app.prefs.airpodsModelId != null
        appendLine(
            "${ok(a.battery.value != null)}  AirPods Akku-Signal ${if (a.battery.value != null) "empfangen" else "keins"}" +
                (a.lastRssi?.let { " · $it dBm" } ?: ""),
        )
        appendLine(
            "${ok(learned)}  Modell: ${a.podModel.value?.label ?: "noch nicht erkannt"}" +
                (if (learned) " (gelernt)" else " – wird beim Verbinden gelernt") +
                (if (a.ignoredForeign > 0) " · fremde ignoriert: ${a.ignoredForeign}" else ""),
        )
        append("${ok(a.controlReady.value)}  AirPods Steuerkanal: ${a.controlStatus.value}")
        com.amoo.roomlauncher.CrashLog.read(this@diagnosis)?.let { append("\n⚠  Letzter Fehler: $it") }
    }
}

// ---- Allgemein

private fun MainActivity.generalTab(app: RoomApp): View {
    val root = column()
    root.addView(sectionTitle("Darstellung"), lp().margins(b = dp(6)))
    root.addView(
        row(
            label("App-Icons", 16f).apply { layoutParams = lp(0, WRAP, 1f) },
            chipGroup(IconStyle.labels, app.iconStyle.value) { i ->
                app.prefs.iconStyle = i
                app.iconStyle.value = i
            },
        ).apply { setPadding(0, dp(8), 0, dp(8)) },
    )
    root.addView(
        switchRow("Flüssige Animationen (60 fps)", app.smooth.value, "Aus = 30 fps, schont ein älteres Tablet.") {
            app.prefs.smooth = it
            app.smooth.value = it
        },
    )
    root.addView(switchRow("Bildschirm immer an", app.prefs.keepScreenOn, "Das Tablet geht nicht in den Standby, solange der Launcher offen ist.") {
        app.prefs.keepScreenOn = it
        applyKeepScreenOn(it)
    })
    root.section("System")
    root.addView(
        row(
            glassButton("Android-Einstellungen") {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            glassButton("Bluetooth") {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            glassButton("WLAN") {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            spacingDp = 12,
        ),
    )
    val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
    root.addView(label("Room Launcher $version", 12f, C.TEXT3), lp().margins(t = dp(22)))
    return root
}

// ---- Verlassen: zurück zum normalen Launcher

private fun MainActivity.exitTab(app: RoomApp, onDismiss: () -> Unit): View {
    val root = column()
    root.addView(
        label(
            "Solange Room Launcher der Standard-Startbildschirm ist, bringt dich die Home-Taste immer hierher zurück. " +
                "Hier kommst du zum normalen Launcher.",
            15f, C.TEXT2, lines = 3,
        ),
    )

    root.section("Einmal kurz wechseln")
    val others = app.apps.otherLaunchers()
    if (others.isEmpty()) root.addView(label("Kein anderer Launcher installiert.", 14f, C.TEXT3))
    others.forEach { l ->
        val r = row(
            ImageView(this).apply {
                setImageBitmap(l.icon)
                layoutParams = lp(dp(44), dp(44))
            },
            column().apply {
                addView(label("${l.label} öffnen", 17f, bold = true))
                addView(label("Home-Taste führt danach wieder hierher", 12.5f, C.TEXT3), lp().margins(t = dp(3)))
                layoutParams = lp(0, WRAP, 1f).margins(l = dp(14))
            },
        ).apply {
            background = GlassDrawable(context, 20f, 0.9f, frosted = false)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            pressable {
                onDismiss()
                app.apps.launchHome(l)
            }
        }
        root.addView(r, lp().margins(b = dp(10)))
    }

    root.section("Dauerhaft zurück zum normalen Launcher")
    root.addView(
        label(
            "Hebt Room Launcher als Standard auf. Beim nächsten Druck auf Home fragt Android, welcher Startbildschirm " +
                "verwendet werden soll – dort den alten Launcher mit „Immer“ wählen.",
            14f, C.TEXT2, lines = 4,
        ),
    )
    root.addView(
        row(
            glassButton("Als Standard aufheben", true) {
                @Suppress("DEPRECATION")
                runCatching { packageManager.clearPackagePreferredActivities(packageName) }
                toast("Erledigt – jetzt Home drücken und den Launcher wählen")
                onDismiss()
                // Home-Auswahl direkt anzeigen
                runCatching {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            glassButton("Startbildschirm-Einstellungen") {
                val ok = runCatching {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!ok) startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            spacingDp = 12,
        ),
        lp().margins(t = dp(12)),
    )
    root.addView(spacer(16))
    return root
}

// ------------------------------------------------------------------ Licht-Panel (Steuerung + Einrichtung)

fun Context.goveeSheet(govee: GoveeController, prefs: Prefs, onDismiss: () -> Unit): Sheet {
    val sheet = Sheet(this, "Licht", true, onDismiss)
    val root = column()

    // ---- Status (eine Zeile) + Fehlermeldung, falls es eine gibt
    val status = label("", 14f, C.TEXT2)
    fun refreshStatus() {
        val name = govee.model.value ?: govee.deviceName.value ?: "Licht"
        status.text = "$name · " + when (govee.connection.value) {
            GoveeController.Connection.CONNECTED -> "verbunden"
            GoveeController.Connection.CONNECTING -> "verbinde…"
            GoveeController.Connection.DISCONNECTED -> "getrennt"
            GoveeController.Connection.NOT_CONFIGURED -> "nicht eingerichtet"
        }
    }
    status.bind(govee.deviceName) { refreshStatus() }
    status.bind(govee.model) { refreshStatus() }
    status.bind(govee.connection) { refreshStatus() }
    root.addView(status)
    val err = label("", 13f, C.ORANGE, lines = 2)
    err.bind(govee.lastError) {
        err.text = it.orEmpty()
        err.visibility = if (it == null) View.GONE else View.VISIBLE
    }
    root.addView(err, lp().margins(t = dp(4)))
    // Funkt die Leiste? – sekündlich aktualisiert, solange sie nicht verbunden ist
    val signal = label("", 12.5f, C.TEXT3, lines = 2)
    val signalTick = object : Runnable {
        override fun run() {
            val connected = govee.connection.value == GoveeController.Connection.CONNECTED
            val configured = govee.connection.value != GoveeController.Connection.NOT_CONFIGURED
            signal.visibility = if (!connected && configured) View.VISIBLE else View.GONE
            signal.text = "Signal: " + govee.signalInfo()
            signal.postDelayed(this, 1000)
        }
    }
    signal.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = signalTick.run()
        override fun onViewDetachedFromWindow(v: View) {
            v.removeCallbacks(signalTick)
        }
    })
    root.addView(signal, lp().margins(t = dp(4)))

    // ---- Steuerung: nur das Nötige
    val control = column()
    val pct = label("", 15f, C.TEXT2, display = true)
    pct.bind(govee.brightness) { pct.text = "${(it * 100).roundToInt()}%" }
    val bright = column().apply {
        addView(pct)
        addView(brightnessSlider(govee, 48), lp().margins(t = dp(8)))
    }
    control.addView(row(powerButton(govee, 76), bright.also { it.layoutParams = lp(0, WRAP, 1f) }, spacingDp = 18), lp().margins(t = dp(16)))
    val segInfo = label("", 12.5f, C.TEXT3)
    fun refreshSegInfo() {
        segInfo.text = if (govee.segMode.value == GoveeController.SegMode.ALL) {
            "${govee.count.value}/15 Segmente"
        } else {
            "Tippen = malen · Halten = löschen"
        }
    }
    segInfo.bind(govee.count) { refreshSegInfo() }
    segInfo.bind(govee.segMode) { refreshSegInfo() }
    val segBox = column().apply {
        addView(row(segInfo.also { it.layoutParams = lp(0, WRAP, 1f) }, segModeSwitch(govee, 38, 13.5f).also { it.layoutParams = lp(dp(220), WRAP) }))
        addView(segmentBar(govee, 40), lp(MATCH, dp(40)).margins(t = dp(10)))
    }
    segBox.bind(govee.segmented) { segBox.visibility = if (it) View.VISIBLE else View.GONE }
    control.addView(segBox, lp().margins(t = dp(20)))
    control.addView(colorPicker(govee, 40), lp().margins(t = dp(16)))
    control.addView(presetRow(govee, 40, null), lp().margins(t = dp(12)))

    // Szenen & Musik: eingeklappt
    val more = column().apply { visibility = View.GONE }
    more.section("Szenen")
    more.addView(hScroll(*GoveeController.SCENES.map { (id, l) -> Chip(this, l) { govee.setScene(id) } }.toTypedArray()))
    more.section("Musik (Mikrofon am Controller)")
    more.addView(hScroll(*GoveeController.MUSIC_MODES.map { (id, l) -> Chip(this, l) { govee.setMusicMode(id) } }.toTypedArray()))
    val moreToggle = Chip(this, "Szenen & Musik ▾") { c ->
        val open = more.visibility != View.VISIBLE
        more.visibility = if (open) View.VISIBLE else View.GONE
        c.text = if (open) "Szenen & Musik ▴" else "Szenen & Musik ▾"
    }
    control.addView(moreToggle, lp(WRAP, WRAP).margins(t = dp(18)))
    control.addView(more)
    root.addView(control)

    // ---- Einrichtung / anderes Licht (nur bei Bedarf sichtbar)
    val setup = column()
    locationHint()?.let { setup.addView(it, lp().margins(t = dp(14))) }
    setup.addView(
        label("Govee-App auf dem Handy schließen – das Licht erlaubt nur eine Verbindung gleichzeitig.", 13f, C.TEXT3, lines = 2),
        lp().margins(t = dp(14)),
    )
    val head = label("", 16f, bold = true)
    head.bind(govee.scanning) { head.text = if (it) "Suche Lichter…" else if (govee.found.value.isEmpty()) "" else "Gefundene Lichter" }
    setup.addView(head, lp().margins(t = dp(12)))
    val list = column()
    setup.addView(list, lp().margins(t = dp(8)))
    list.bind(govee.found) { found ->
        list.removeAllViews()
        if (found.isEmpty() && govee.scanning.value) list.addView(label("Ist das Licht an und in der Nähe?", 14f, C.TEXT3, lines = 2))
        found.take(25).forEach { f ->
            val item = listItem(f.name, "${f.address} · ${f.rssi} dBm", if (f.likelyGovee) "Govee" else null, C.GREEN, f.likelyGovee) {
                govee.select(f)
                toast("${f.name} wird verbunden")
            }
            list.addView(item, lp().margins(b = dp(8)))
        }
    }
    setup.addView(glassButton("Erneut suchen") { govee.startScan() }, lp(WRAP, WRAP).margins(t = dp(4)))

    // Technik-Optionen nur, wenn das Modell NICHT automatisch erkannt wurde
    val manual = column()
    manual.section("Farb-Befehl")
    val modes = listOf(0x02 to "Standard (02)", 0x0D to "Neuere H6xxx (0D)", 0x0B to "0B", 0x15 to "Segmente (15)")
    manual.addView(chipGroup(modes.map { it.second }, modes.indexOfFirst { it.first == prefs.goveeColorMode }) { i ->
        govee.setColorMode(modes[i].first)
    })
    manual.section("Helligkeitsskala")
    manual.addView(chipGroup(listOf("0–255", "0–100"), if (prefs.goveeBrightness100) 1 else 0) { i ->
        prefs.goveeBrightness100 = i == 1
        govee.resendState()
    })
    manual.addView(label("Modell unbekannt: Reagiert die Farbe nicht, anderen Farb-Befehl probieren.", 12.5f, C.TEXT3, lines = 2), lp().margins(t = dp(8)))
    manual.bind(govee.model) { m -> manual.visibility = if (m == null) View.VISIBLE else View.GONE }
    setup.addView(manual)
    root.addView(setup)

    // ---- Unten: Neu verbinden / Anderes Licht
    var setupOpen = false
    fun refreshSetup() {
        val none = govee.connection.value == GoveeController.Connection.NOT_CONFIGURED
        control.visibility = if (none) View.GONE else View.VISIBLE
        setup.visibility = if (none || setupOpen) View.VISIBLE else View.GONE
    }
    val footer = row(
        glassButton("Neu verbinden") { govee.reconnectNow() },
        glassButton("Anderes Licht") {
            setupOpen = !setupOpen
            if (setupOpen) govee.startScan() else govee.stopScan()
            refreshSetup()
        },
        spacingDp = 12,
    )
    footer.bind(govee.connection) { c -> footer.visibility = if (c == GoveeController.Connection.NOT_CONFIGURED) View.GONE else View.VISIBLE }
    root.addView(footer, lp().margins(t = dp(22)))
    root.bind(govee.connection) { refreshSetup() }
    root.addView(spacer(16))

    sheet.body.addView(scroll(root), lp(MATCH, 0, 1f))
    sheet.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        // Noch kein Licht eingerichtet → gleich suchen
        override fun onViewAttachedToWindow(v: View) {
            if (govee.connection.value == GoveeController.Connection.NOT_CONFIGURED) govee.startScan()
        }
        override fun onViewDetachedFromWindow(v: View) = govee.stopScan()
    })
    return sheet
}

// ------------------------------------------------------------------ AirPods-Panel (Steuerung + Einrichtung)

fun Context.airPodsSheet(airpods: AirPodsController, prefs: Prefs, onDismiss: () -> Unit): Sheet {
    val sheet = Sheet(this, "AirPods", true, onDismiss)
    val root = column()
    locationHint()?.let { root.addView(it, lp().margins(b = dp(14))) }

    root.addView(ringRow(airpods, 80))
    val ctrl = glassButton("Steuerung verbinden") { airpods.resync() }
    ctrl.bind(airpods.controlReady) { ctrl.visibility = if (it) View.GONE else View.VISIBLE }
    root.addView(row(connectButton(airpods, highlighted = true), ctrl, spacingDp = 12), lp().margins(t = dp(18)))

    root.section("Geräuschkontrolle")
    val noModes = label("Dieses Modell hat keine Geräuschkontrolle (ANC/Transparenz).", 14f, C.TEXT3, lines = 2)
    val modes = noiseSelector(airpods, short = false)
    modes.bind(airpods.podModel) { m ->
        val has = m?.noiseControl != false
        modes.visibility = if (has) View.VISIBLE else View.GONE
        noModes.visibility = if (has) View.GONE else View.VISIBLE
    }
    root.addView(noModes)
    root.addView(modes)
    val strengthLabel = label("", 13f, C.TEXT2)
    val strength = GlassSlider(this, 34) { airpods.setAdaptiveStrength((it * 100).roundToInt()) }
    val strengthBox = column().apply {
        addView(strengthLabel)
        addView(strength, lp().margins(t = dp(6)))
    }
    strengthBox.bind(airpods.adaptiveStrength) {
        strength.value = (it ?: 50) / 100f
        strengthLabel.text = "Adaptives Audio: ${it ?: 50} %  (weniger ↔ mehr Geräuschreduzierung)"
    }
    strengthBox.bind(airpods.noise) {
        strengthBox.visibility = if (it == AirPodsController.Noise.ADAPTIVE) View.VISIBLE else View.GONE
    }
    root.addView(strengthBox, lp().margins(t = dp(14)))
    root.addView(toggleRow("Gesprächserkennung", airpods.conversationAwareness, airpods.controlReady) { airpods.setConversationAwareness(it) }, lp().margins(t = dp(8)))
    root.addView(toggleRow("Automatische Ohrerkennung", airpods.earDetection, airpods.controlReady) { airpods.setEarDetection(it) })
    root.addView(toggleRow("ANC mit einem AirPod", airpods.oneBudAnc, airpods.controlReady) { airpods.setOneBudAnc(it) })
    val status = label("", 12.5f, C.TEXT3, lines = 3)
    status.bind(airpods.controlStatus) { status.text = "Steuerkanal: $it" }
    root.addView(status, lp().margins(t = dp(8)))

    root.addView(
        glassButton("Modell neu erkennen") {
            airpods.forgetModel()
            toast("Wird beim nächsten Signal der verbundenen AirPods neu erkannt")
        },
        lp(WRAP, WRAP).margins(t = dp(12)),
    )
    root.section("Gerät auswählen")
    root.addView(
        label("Einmalig in den Android-Bluetooth-Einstellungen koppeln (Case öffnen, Knopf hinten halten), dann hier auswählen.", 14f, C.TEXT2, lines = 2),
    )
    val list = column()
    fun fill() {
        list.removeAllViews()
        val devices = airpods.pairedDevices()
        if (devices.isEmpty()) list.addView(label("Keine gekoppelten Geräte gefunden.", 14f, C.TEXT3))
        devices.forEach { d ->
            val sel = d.address == prefs.airpodsAddress
            val item = listItem(d.name, d.address, if (sel) "Ausgewählt" else null, C.BLUE, sel) {
                airpods.selectDevice(d.address)
                fill()
            }
            list.addView(item, lp().margins(b = dp(8)))
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
        lp().margins(t = dp(12)),
    )
    root.addView(list, lp().margins(t = dp(12)))
    fill()
    root.addView(spacer(16))
    sheet.body.addView(scroll(root), lp(MATCH, 0, 1f))
    return sheet
}

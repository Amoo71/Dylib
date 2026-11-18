package com.amoo.roomlauncher.board

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WidgetSize(val w: Int, val h: Int, val label: String) {
    S(2, 2, "Klein"),
    M(4, 2, "Mittel"),
}

/** [defaultGlass] = ob das Widget anfangs im Glas-Container sitzt (Uhr: frei schwebend). */
enum class WidgetKind(val title: String, val sizes: List<WidgetSize>, val defaultGlass: Boolean) {
    CLOCK("Uhr", listOf(WidgetSize.S, WidgetSize.M), false),
    LIGHT("Licht", listOf(WidgetSize.S, WidgetSize.M), true),
    AIRPODS("AirPods", listOf(WidgetSize.S, WidgetSize.M), true),
}

/** Ein Element auf dem Board mit festem Platz ([col]/[row] = Zelle oben links). */
sealed class BoardItem {
    abstract val id: String
    abstract val w: Int
    abstract val h: Int
    abstract val col: Int
    abstract val row: Int

    fun at(col: Int, row: Int): BoardItem = when (this) {
        is Widget -> copy(col = col, row = row)
        is App -> copy(col = col, row = row)
    }

    fun slot() = Slot(id, col, row, w, h)

    data class Widget(
        override val id: String,
        val kind: WidgetKind,
        val size: WidgetSize,
        override val col: Int = 0,
        override val row: Int = 0,
        /** false = ohne Glas-Container, komplett durchsichtig. */
        val glass: Boolean = kind.defaultGlass,
    ) : BoardItem() {
        override val w get() = size.w
        override val h get() = size.h
    }

    /** component = ComponentName.flattenToString() */
    data class App(override val id: String, val component: String, override val col: Int = 0, override val row: Int = 0) : BoardItem() {
        override val w get() = 1
        override val h get() = 1
    }
}

class BoardStore(context: Context) {
    private val sp = context.getSharedPreferences("room_board", Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<BoardItem>> = _items.asStateFlow()

    private val _dock = MutableStateFlow(sp.getString("dock", null)?.split("|")?.filter { it.isNotBlank() } ?: emptyList())
    val dock: StateFlow<List<String>> = _dock.asStateFlow()

    private val _dockEnabled = MutableStateFlow(sp.getBoolean("dock_enabled", true))
    val dockEnabled: StateFlow<Boolean> = _dockEnabled.asStateFlow()

    val appsInitialized: Boolean get() = sp.getBoolean("apps_initialized", false)

    // ---------------------------------------------------------------- Board (freies Raster)

    private fun slots() = _items.value.map { it.slot() }

    /**
     * Element dort ablegen, wo es losgelassen wurde – Lücken bleiben Lücken.
     * Ist der Platz belegt, landet es im nächsten freien Platz.
     */
    fun place(id: String, col: Int, row: Int) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        val (c, r) = Grid.nearestFree(slots(), id, col, row, item.w, item.h, COLUMNS, ROWS)
        if (c == item.col && r == item.row) return
        set(_items.value.map { if (it.id == id) it.at(c, r) else it })
    }

    fun remove(id: String) = set(_items.value.filterNot { it.id == id })

    /** Glas-Container eines Widgets an/aus (aus = durchsichtig wie die Uhr). */
    fun setGlass(id: String, on: Boolean) =
        set(_items.value.map { if (it is BoardItem.Widget && it.id == id) it.copy(glass = on) else it })

    fun addWidget(kind: WidgetKind, size: WidgetSize) {
        val (c, r) = Grid.firstFree(slots(), size.w, size.h, COLUMNS)
        set(_items.value + BoardItem.Widget(newId(), kind, size, c, r))
    }

    fun addApp(component: String) {
        if (_items.value.any { it is BoardItem.App && it.component == component }) return
        val (c, r) = Grid.firstFree(slots(), 1, 1, COLUMNS)
        set(_items.value + BoardItem.App(newId(), component, c, r))
    }

    /** Größe wechseln. Passt die neue Größe nicht an den Platz, rückt nur dieses Widget zum nächsten freien Platz. */
    fun cycleSize(id: String) {
        val it = _items.value.firstOrNull { it.id == id } as? BoardItem.Widget ?: return
        val sizes = it.kind.sizes
        val next = sizes[(sizes.indexOf(it.size) + 1) % sizes.size]
        val (c, r) = Grid.nearestFree(slots(), id, it.col, it.row, next.w, next.h, COLUMNS, ROWS)
        set(_items.value.map { x -> if (x.id == id) it.copy(size = next, col = c, row = r) else x })
    }

    /** Beim ersten Start ein paar vorhandene Medien-Apps aufs Board und in den Dock legen. */
    fun initDefaultApps(installed: List<String>, preferredPackages: List<String>) {
        if (appsInitialized) return
        val picks = preferredPackages.mapNotNull { p -> installed.firstOrNull { it.startsWith("$p/") } }
        picks.take(4).forEach { addApp(it) }
        if (_dock.value.isEmpty()) setDock(picks.take(6))
        sp.edit().putBoolean("apps_initialized", true).apply()
    }

    // ---------------------------------------------------------------- Dock

    fun setDockEnabled(on: Boolean) {
        _dockEnabled.value = on
        sp.edit().putBoolean("dock_enabled", on).apply()
    }

    fun addToDock(component: String) {
        if (component in _dock.value || _dock.value.size >= 10) return
        setDock(_dock.value + component)
    }

    fun removeFromDock(component: String) = setDock(_dock.value - component)

    fun moveInDock(component: String, toIndex: Int) {
        val list = _dock.value.toMutableList()
        if (list.indexOf(component) == toIndex.coerceIn(0, list.size - 1)) return
        if (!list.remove(component)) return
        list.add(toIndex.coerceIn(0, list.size), component)
        setDock(list)
    }

    private fun setDock(list: List<String>) {
        _dock.value = list
        sp.edit().putString("dock", list.joinToString("|")).apply()
    }

    // ---------------------------------------------------------------- Speichern

    private fun set(list: List<BoardItem>) {
        _items.value = list
        save(list)
    }

    private fun save(list: List<BoardItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    when (item) {
                        is BoardItem.Widget -> {
                            put("t", "w")
                            put("kind", item.kind.name)
                            put("size", item.size.name)
                            put("g", item.glass)
                        }
                        is BoardItem.App -> {
                            put("t", "a")
                            put("c", item.component)
                        }
                    }
                    put("x", item.col)
                    put("y", item.row)
                },
            )
        }
        sp.edit().putString("items", arr.toString()).apply()
    }

    private fun load(): List<BoardItem> {
        val s = sp.getString("items", null) ?: return defaults()
        return runCatching {
            val arr = JSONArray(s)
            var needsPositions = false
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                if (!o.has("x") || !o.has("y")) needsPositions = true
                val x = o.optInt("x", 0)
                val y = o.optInt("y", 0)
                when (o.optString("t")) {
                    "w" -> {
                        val kind = runCatching { WidgetKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                        val size = runCatching { WidgetSize.valueOf(o.getString("size")) }.getOrNull()
                            ?.takeIf { it in kind.sizes } ?: kind.sizes.first()
                        BoardItem.Widget(o.getString("id"), kind, size, x, y, o.optBoolean("g", kind.defaultGlass))
                    }
                    "a" -> BoardItem.App(o.getString("id"), o.getString("c"), x, y)
                    else -> null
                }
            }
            // Ältere Versionen speicherten nur die Reihenfolge → einmalig in feste Plätze übersetzen
            if (needsPositions) {
                val pos = Grid.pack(list.map { it.w to it.h }, COLUMNS)
                list.mapIndexed { i, it -> it.at(pos[i].first, pos[i].second) }.also { save(it) }
            } else {
                list
            }
        }.getOrElse { defaults() }
    }

    private fun defaults(): List<BoardItem> = listOf(
        BoardItem.Widget(newId(), WidgetKind.CLOCK, WidgetSize.M, 0, 0),
        BoardItem.Widget(newId(), WidgetKind.LIGHT, WidgetSize.S, 4, 0),
    )

    private fun newId() = UUID.randomUUID().toString().take(8)

    companion object {
        /** Raster des Boards: 8 Spalten, 4 sichtbare Zeilen. */
        const val COLUMNS = 8
        const val ROWS = 4
    }
}

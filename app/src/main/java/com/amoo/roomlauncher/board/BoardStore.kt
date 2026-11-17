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

enum class WidgetKind(val title: String, val sizes: List<WidgetSize>) {
    CLOCK("Uhr", listOf(WidgetSize.S, WidgetSize.M)),
    LIGHT("Licht", listOf(WidgetSize.S, WidgetSize.M)),
    AIRPODS("AirPods", listOf(WidgetSize.S, WidgetSize.M)),
}

sealed class BoardItem {
    abstract val id: String
    abstract val w: Int
    abstract val h: Int

    data class Widget(override val id: String, val kind: WidgetKind, val size: WidgetSize) : BoardItem() {
        override val w get() = size.w
        override val h get() = size.h
    }

    /** component = ComponentName.flattenToString() */
    data class App(override val id: String, val component: String) : BoardItem() {
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

    // ---------------------------------------------------------------- Board

    fun move(id: String, toIndex: Int) {
        val list = _items.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0 || from == toIndex.coerceIn(0, list.size - 1)) return
        val item = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        set(list)
    }

    fun remove(id: String) = set(_items.value.filterNot { it.id == id })

    fun addWidget(kind: WidgetKind, size: WidgetSize) =
        set(_items.value + BoardItem.Widget(newId(), kind, size))

    fun addApp(component: String) {
        if (_items.value.any { it is BoardItem.App && it.component == component }) return
        set(_items.value + BoardItem.App(newId(), component))
    }

    fun cycleSize(id: String) = set(
        _items.value.map {
            if (it is BoardItem.Widget && it.id == id) {
                val sizes = it.kind.sizes
                it.copy(size = sizes[(sizes.indexOf(it.size) + 1) % sizes.size])
            } else {
                it
            }
        },
    )

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
                        }
                        is BoardItem.App -> {
                            put("t", "a")
                            put("c", item.component)
                        }
                    }
                },
            )
        }
        sp.edit().putString("items", arr.toString()).apply()
    }

    private fun load(): List<BoardItem> {
        val s = sp.getString("items", null) ?: return defaults()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                when (o.optString("t")) {
                    "w" -> {
                        val kind = runCatching { WidgetKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                        val size = runCatching { WidgetSize.valueOf(o.getString("size")) }.getOrNull()
                            ?.takeIf { it in kind.sizes } ?: kind.sizes.first()
                        BoardItem.Widget(o.getString("id"), kind, size)
                    }
                    "a" -> BoardItem.App(o.getString("id"), o.getString("c"))
                    else -> null
                }
            }
        }.getOrElse { defaults() }
    }

    private fun defaults(): List<BoardItem> = listOf(
        BoardItem.Widget(newId(), WidgetKind.CLOCK, WidgetSize.M),
        BoardItem.Widget(newId(), WidgetKind.LIGHT, WidgetSize.S),
        BoardItem.Widget(newId(), WidgetKind.AIRPODS, WidgetSize.S),
    )

    private fun newId() = UUID.randomUUID().toString().take(8)
}

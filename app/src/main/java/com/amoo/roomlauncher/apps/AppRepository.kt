package com.amoo.roomlauncher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.amoo.roomlauncher.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: ImageBitmap,
)

class AppRepository(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _favorites = MutableStateFlow(prefs.favorites ?: emptyList())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private var loadJob: Job? = null
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    fun start() {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = refresh(force = true)
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        refresh(force = true)
    }

    fun refresh(force: Boolean = false) {
        if (!force && _apps.value.isNotEmpty()) return
        loadJob?.cancel()
        loadJob = scope.launch {
            val list = withContext(Dispatchers.Default) {
                if (force) iconCache.clear()
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != context.packageName }
                    .map { toEntry(it) }
                    .distinctBy { it.component }
                    .sortedBy { it.label.lowercase() }
            }
            _apps.value = list
            if (prefs.favorites == null) {
                // Erste Dock-Belegung: bekannte Medien-Apps, falls installiert.
                val defaults = listOf(
                    "com.spotify.music", "com.google.android.youtube", "com.google.android.apps.youtube.music",
                    "com.netflix.mediaclient", "com.android.chrome", "com.amazon.avod.thirdpartyclient",
                ).filter { p -> list.any { it.packageName == p } }
                setFavorites(defaults)
            }
        }
    }

    private fun toEntry(ri: ResolveInfo): AppEntry {
        val pm = context.packageManager
        val comp = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
        val key = comp.flattenToString()
        val icon = iconCache.getOrPut(key) {
            runCatching { ri.loadIcon(pm).toBitmap(144, 144).asImageBitmap() }
                .getOrElse { android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }
        }
        return AppEntry(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, comp, icon)
    }

    fun toggleFavorite(pkg: String) {
        val cur = _favorites.value
        setFavorites(if (pkg in cur) cur - pkg else cur + pkg)
    }

    private fun setFavorites(list: List<String>) {
        _favorites.value = list
        prefs.favorites = list
    }

    fun launch(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
    }

    /** Andere installierte Home-Launcher (für den Exit-Dialog). */
    fun otherLaunchers(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { toEntry(it) }
    }

    fun launchHome(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

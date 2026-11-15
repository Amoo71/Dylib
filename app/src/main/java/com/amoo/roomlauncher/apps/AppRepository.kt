package com.amoo.roomlauncher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.amoo.roomlauncher.registerSystemReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AppEntry(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: Bitmap,
) {
    val key: String = component.flattenToString()
}

class AppRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private var loadJob: Job? = null
    private val iconCache = ConcurrentHashMap<String, Bitmap>()

    fun start() {
        context.registerSystemReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = refresh(force = true)
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
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
                    .distinctBy { it.key }
                    .sortedBy { it.label.lowercase() }
            }
            _apps.value = list
        }
    }

    private fun toEntry(ri: ResolveInfo): AppEntry {
        val pm = context.packageManager
        val comp = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
        val key = comp.flattenToString()
        val icon = iconCache.getOrPut(key) {
            runCatching { drawableToBitmap(ri.loadIcon(pm), 128) }
                .getOrElse { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        }
        return AppEntry(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, comp, icon)
    }

    fun launch(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
    }

    /** Andere installierte Home-Launcher (zum Verlassen). */
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

    companion object {
        fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
            if (d is BitmapDrawable && d.bitmap != null) {
                return Bitmap.createScaledBitmap(d.bitmap, size, size, true)
            }
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            return bmp
        }
    }
}

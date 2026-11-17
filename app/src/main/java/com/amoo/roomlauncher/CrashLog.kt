package com.amoo.roomlauncher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Merkt sich den letzten Fehler/Absturz, damit er in der Diagnose sichtbar ist. */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write(app, e)
            previous?.uncaughtException(t, e)
        }
    }

    fun write(context: Context, e: Throwable) {
        runCatching {
            val time = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
            val where = e.stackTrace.firstOrNull { it.className.startsWith("com.amoo") }?.let { " @ ${it.fileName}:${it.lineNumber}" } ?: ""
            file(context).writeText("$time  ${e.javaClass.simpleName}: ${e.message ?: ""}$where")
        }
    }

    fun read(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}

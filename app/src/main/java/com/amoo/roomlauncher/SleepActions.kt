package com.amoo.roomlauncher

import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/** Empfänger für das Geräteadministrator-Recht "Bildschirm sperren" (nur das, nichts anderes). */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Was am Ende des Nacht-Timers passiert (zusätzlich zum Ausschalten des Lichts):
 *  1. laufende Musik/Videos pausieren,
 *  2. Launcher nach vorn holen, damit alle anderen Apps im Hintergrund sind,
 *  3. diese Apps beenden (ohne Root: killBackgroundProcesses),
 *  4. Bildschirm ausschalten (Geräteadministrator-Recht "Bildschirm sperren", einmalig erlauben).
 */
object SleepActions {
    private val handler = Handler(Looper.getMainLooper())

    fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    private fun dpm(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    /** Darf die App den Bildschirm ausschalten? */
    fun canLock(context: Context): Boolean = runCatching { dpm(context)?.isAdminActive(admin(context)) == true }.getOrDefault(false)

    /** Öffnet Androids Dialog "Geräteadministrator aktivieren" (nur Bildschirm sperren). */
    fun requestLock(context: Context) {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Damit der Nacht-Timer am Ende den Bildschirm ausschalten kann. Room Launcher nutzt nur „Bildschirm sperren“.",
            )
        if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { context.toast("Bitte unter Einstellungen → Sicherheit → Geräteadministratoren erlauben") }
    }

    /** Recht wieder entfernen (z. B. vor dem Deinstallieren). */
    fun removeLock(context: Context) {
        runCatching { dpm(context)?.removeActiveAdmin(admin(context)) }
    }

    fun run(app: RoomApp) {
        val prefs = app.prefs
        val closeApps = prefs.sleepCloseApps
        val screenOff = prefs.sleepScreenOff
        L.i("Nacht-Timer Ende: Apps schließen=$closeApps, Bildschirm aus=$screenOff")
        if (closeApps) {
            pauseMedia(app)
            // Launcher nach vorn → alle anderen Apps landen im Hintergrund
            runCatching {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
        handler.postDelayed({
            if (closeApps) killApps(app)
            if (screenOff) lock(app)
        }, if (closeApps) 1500L else 0L)
    }

    private fun pauseMedia(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val t = SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        }
    }

    private fun killApps(app: RoomApp) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val keyboard = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        val keep = setOfNotNull(app.packageName, keyboard)
        val packages = app.apps.apps.value.map { it.key.substringBefore('/') }.toSet() - keep
        var n = 0
        for (p in packages) if (runCatching { am.killBackgroundProcesses(p) }.isSuccess) n++
        L.i("Nacht-Timer: $n Apps beendet")
    }

    private fun lock(context: Context) {
        if (!canLock(context)) {
            L.w("Nacht-Timer: Bildschirm-aus nicht erlaubt (Geräteadministrator fehlt)")
            return
        }
        runCatching { dpm(context)?.lockNow() }.onFailure { L.w("lockNow fehlgeschlagen", it) }
    }
}

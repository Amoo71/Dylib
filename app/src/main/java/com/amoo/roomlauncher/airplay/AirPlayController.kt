package com.amoo.roomlauncher.airplay

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.amoo.roomlauncher.L
import com.amoo.roomlauncher.registerSystemReceiver
import com.amoo.roomlauncher.toast
import com.amoo.roomlauncher.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Leerer Mitteilungs-Dienst: Android erlaubt das Lesen fremder Medien-Sitzungen (Titel, Cover, Steuerung)
 * nur Apps mit "Mitteilungszugriff". Mitteilungen selbst werden nicht gelesen.
 */
class MediaListener : NotificationListenerService()

/**
 * Anbindung an "AirPlay Server" von jqssun (io.github.jqssun.airplay).
 *
 * Die App hat keine öffentliche Schnittstelle zum Starten/Stoppen oder für den Status. Deshalb:
 *  - installiert?           → PackageManager (+ Broadcasts bei Installation/Entfernen)
 *  - Server läuft?          → laufende Dienste (unter Android 7 noch für alle Apps sichtbar), alle 30 s
 *                             und nur bei eingeschaltetem Bildschirm
 *  - Läuft gerade (Titel, Cover, Pause/Weiter) → die Medien-Sitzung der App (braucht Mitteilungszugriff),
 *                             rein ereignisgesteuert, ohne Abfrage-Schleife
 *  - Starten = App öffnen, Stoppen = App-Info ("Beenden erzwingen"), Einstellungen = in der App.
 */
class AirPlayController(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        const val PKG = "io.github.jqssun.airplay"
        private const val SERVICE = "io.github.jqssun.airplay.service.AirPlayService"
        const val FDROID = "https://f-droid.org/packages/io.github.jqssun.airplay/"
        const val RELEASES = "https://github.com/jqssun/android-airplay-server/releases/latest"
    }

    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val album: String?,
        val art: Bitmap?,
        val playing: Boolean,
    )

    private val _installed = MutableStateFlow(false)
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _running = MutableStateFlow(false)
    /** Der AirPlay-Dienst läuft (Server ist im Netz sichtbar). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    /** Was gerade per AirPlay läuft (null = nichts oder kein Mitteilungszugriff). */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _access = MutableStateFlow(false)
    /** Mitteilungszugriff erteilt (nötig für Titel/Cover/Steuerung). */
    val access: StateFlow<Boolean> = _access.asStateFlow()

    private var controller: MediaController? = null
    private var sessionsListening = false
    private val listenerComponent = ComponentName(context, MediaListener::class.java)
    private var started = false

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            bindController(null)
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bindController(list?.firstOrNull { it.packageName == PKG })
    }

    fun start() {
        if (started) return
        started = true
        refresh()
        // Installiert/entfernt/aktualisiert → sofort neu prüfen
        context.registerSystemReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.data?.schemeSpecificPart == PKG) refresh()
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )
        // Server-Status: alle 30 s, aber nur bei eingeschaltetem Bildschirm und installierter App
        scope.launch {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            while (isActive) {
                delay(30_000)
                if (_installed.value && pm?.isInteractive != false) checkRunning()
            }
        }
    }

    /** Alles neu prüfen (z. B. wenn der Launcher wieder sichtbar wird). */
    fun refresh() {
        _installed.value = runCatching { context.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)
        checkRunning()
        checkAccess()
    }

    @Suppress("DEPRECATION")
    private fun checkRunning() {
        if (!_installed.value) {
            _running.value = false
            return
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val services = runCatching { am?.getRunningServices(200) }.getOrNull().orEmpty()
        val mine = services.filter { it.service.packageName == PKG }
        val running = mine.any { it.service.className == SERVICE && (it.started || it.foreground) } ||
            mine.any { it.foreground } // Fallback, falls der Dienst umbenannt wurde
        if (running != _running.value) L.i("AirPlay: Server ${if (running) "läuft" else "aus"}")
        _running.value = running
    }

    private fun checkAccess() {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val ok = enabled.split(':').any { ComponentName.unflattenFromString(it) == listenerComponent }
        _access.value = ok
        if (ok && !sessionsListening) listenSessions()
        if (!ok) bindController(null)
    }

    private fun listenSessions() {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent)
            sessionsListening = true
            bindController(msm.getActiveSessions(listenerComponent).firstOrNull { it.packageName == PKG })
        }.onFailure { L.w("AirPlay: Medien-Sitzungen nicht lesbar", it) }
    }

    private fun bindController(c: MediaController?) {
        if (c?.sessionToken == controller?.sessionToken && c != null) return
        controller?.unregisterCallback(mediaCallback)
        controller = c
        c?.registerCallback(mediaCallback)
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            _nowPlaying.value = null
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val raw = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val old = _nowPlaying.value
        // Cover nur verkleinert behalten (spart Speicher) und nur neu skalieren, wenn es sich geändert hat
        val art = when {
            raw == null -> null
            old?.art != null && old.title == title && old.album == album -> old.art
            raw.width > 320 -> Bitmap.createScaledBitmap(raw, 320, (320f * raw.height / raw.width).toInt().coerceAtLeast(1), true)
            else -> raw
        }
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING || c.playbackState?.state == PlaybackState.STATE_BUFFERING
        _nowPlaying.value = if (title == null && artist == null && art == null) null else NowPlaying(title, artist, album, art, playing)
    }

    // ---------------------------------------------------------------- Aktionen

    /** App öffnen – startet den Server (die App hat keinen anderen Weg von außen). */
    fun open() {
        val i = context.packageManager.getLaunchIntentForPackage(PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i == null) {
            install()
            return
        }
        runCatching { context.startActivity(i) }
        scope.launch {
            delay(4_000)
            checkRunning()
        }
    }

    /** Stoppen geht nur über "Beenden erzwingen" in der App-Info. */
    fun openAppInfo() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        context.toast(tr("„Beenden erzwingen“ stoppt den AirPlay-Server", "“Force stop” stops the AirPlay server"))
    }

    fun install() {
        val ok = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FDROID)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) context.toast(tr("Kein Browser gefunden – AirPlay Server bei F-Droid laden", "No browser found – get AirPlay Server from F-Droid"))
    }

    /** Einstellung "Mitteilungszugriff" öffnen (für Titel, Cover und Steuerung). */
    fun requestAccess() {
        runCatching {
            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        context.toast(tr("„Room Launcher“ einschalten", "Turn on “Room Launcher”"))
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }
}

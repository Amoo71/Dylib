package com.amoo.roomlauncher.airplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.IBinder
import com.amoo.roomlauncher.L
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.get
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Eingebauter AirPlay-Empfänger (UxPlay, übernommen aus jqssun/android-airplay-server, GPL-3.0).
 *
 * Der Server läuft als Dienst im Launcher. Diese Klasse bindet ihn an, spiegelt seinen Zustand als
 * Flows für Widgets/Mini-Widget/Panel und bietet Start/Stopp, Steuerung und Einstellungen.
 * Im Leerlauf kostet der Server praktisch nichts: seine Threads warten auf Netzwerk-Verbindungen.
 */
class AirPlayController(private val context: Context, private val scope: CoroutineScope) {

    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val album: String?,
        val art: Bitmap?,
        val playing: Boolean,
    )

    private val _running = MutableStateFlow(false)
    /** Server läuft und ist im WLAN als AirPlay-Ziel sichtbar. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error.asStateFlow()

    private val _connections = MutableStateFlow(0)
    val connections: StateFlow<Int> = _connections.asStateFlow()

    private val _mirroring = MutableStateFlow(false)
    /** Bildschirm wird gerade gespiegelt. */
    val mirroring: StateFlow<Boolean> = _mirroring.asStateFlow()

    private val _video = MutableStateFlow(false)
    /** AirPlay-Video (z. B. YouTube, HLS) läuft. */
    val video: StateFlow<Boolean> = _video.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    /** Musik, die gerade per AirPlay läuft (Titel, Interpret, Cover). */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _pin = MutableStateFlow<String?>(null)
    /** PIN, die am iPhone/Mac eingegeben werden muss (nur mit "PIN verlangen"). */
    val pin: StateFlow<String?> = _pin.asStateFlow()

    private val _service = MutableStateFlow<AirPlayService?>(null)
    /** Der laufende Dienst (für die Vollbild-Ansicht), null solange nicht angebunden. */
    val service: StateFlow<AirPlayService?> = _service.asStateFlow()

    /** Einstellungen des Empfängers (gleiche Schlüssel wie im Original). */
    val prefs: SharedPreferences = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private var collectJob: Job? = null
    private var started = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            _service.value = svc
            observe(svc)
            // Automatisch starten (Einstellung "Beim Start einschalten")
            if (prefs[Prefs.AUTO_START] && svc.serverState.value == AirPlayService.ServerState.STOPPED) svc.startServer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
            collectJob?.cancel()
            _running.value = false
            _connections.value = 0
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            context.bindService(Intent(context, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { L.w("AirPlay: Dienst nicht anbindbar", it) }
    }

    private fun observe(svc: AirPlayService) {
        collectJob?.cancel()
        collectJob = scope.launch {
            launch {
                svc.serverState.collect {
                    _running.value = it == AirPlayService.ServerState.RUNNING
                    _error.value = it == AirPlayService.ServerState.ERROR
                }
            }
            launch { svc.connectionCount.collect { _connections.value = it } }
            launch { svc.mirroringActive.collect { _mirroring.value = it } }
            launch { svc.videoPlaybackActive.collect { _video.value = it } }
            launch { svc.audioOnly.collect { _audioOnly.value = it } }
            launch {
                combine(svc.trackInfo, svc.playing, svc.connectionCount) { t, p, n -> Triple(t, p, n) }.collect { (t, p, n) ->
                    val has = n > 0 && (t.title.isNotBlank() || t.artist.isNotBlank() || t.coverArt != null)
                    _nowPlaying.value = if (!has) null else NowPlaying(
                        t.title.ifBlank { null }, t.artist.ifBlank { null }, t.album.ifBlank { null }, t.coverArt, p,
                    )
                }
            }
        }
        svc.pinCallback = { _pin.value = it }
    }

    // ---------------------------------------------------------------- Steuerung

    fun startServer() {
        val svc = _service.value ?: return start()
        svc.startServer()
        prefs.edit().putBoolean(Prefs.AUTO_START.key, true).apply() // bleibt nach einem Neustart an
    }

    fun stopServer() {
        _service.value?.stopServer()
        prefs.edit().putBoolean(Prefs.AUTO_START.key, false).apply()
    }

    fun toggle() = if (_running.value) stopServer() else startServer()

    /** Vollbild-Ansicht öffnen (Spiegelung, Video oder Musik mit Cover). */
    fun openViewer() {
        runCatching {
            context.startActivity(Intent(context, AirPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun playPause() {
        _service.value?.togglePlayPause()
    }

    fun next() = context.sendBroadcast(Intent(AirPlayService.ACTION_NEXT).setPackage(context.packageName))

    fun previous() = context.sendBroadcast(Intent(AirPlayService.ACTION_PREV).setPackage(context.packageName))

    // ---------------------------------------------------------------- Einstellungen

    val serverName: String get() = prefs[Prefs.SERVER_NAME]

    /** Name im WLAN. Der Server startet dafür kurz neu. */
    fun setServerName(name: String) {
        val n = name.trim().take(40)
        if (n.isEmpty() || n == serverName) return
        Prefs.SERVER_NAME.write(prefs, n)
    }

    fun <T : Any> get(p: io.github.jqssun.airplay.Pref<T>): T = prefs[p]

    fun <T : Any> set(p: io.github.jqssun.airplay.Pref<T>, v: T) {
        p.write(prefs, v)
    }
}

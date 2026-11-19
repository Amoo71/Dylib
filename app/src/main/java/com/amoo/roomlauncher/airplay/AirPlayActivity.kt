package com.amoo.roomlauncher.airplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.amoo.roomlauncher.RoomApp
import com.amoo.roomlauncher.tr
import com.amoo.roomlauncher.ui.AirPlayGlyph
import com.amoo.roomlauncher.ui.ArtView
import com.amoo.roomlauncher.ui.C
import com.amoo.roomlauncher.ui.Glyph
import com.amoo.roomlauncher.ui.MATCH
import com.amoo.roomlauncher.ui.MediaButton
import com.amoo.roomlauncher.ui.WRAP
import com.amoo.roomlauncher.ui.column
import com.amoo.roomlauncher.ui.dp
import com.amoo.roomlauncher.ui.glassButton
import com.amoo.roomlauncher.ui.glassIconButton
import com.amoo.roomlauncher.ui.label
import com.amoo.roomlauncher.ui.lp
import com.amoo.roomlauncher.ui.margins
import com.amoo.roomlauncher.ui.row
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Vollbild-Ansicht des eingebauten AirPlay-Empfängers:
 *  - Bildschirmspiegelung (iPhone/iPad/Mac),
 *  - AirPlay-Video (z. B. YouTube) mit Pause/±10 s,
 *  - Musik mit großem Cover und Steuerung.
 * Öffnet sich bei einer neuen Verbindung von selbst (auch aus dem Standby) und schließt sich, wenn die
 * Verbindung endet. Tippen blendet die Leiste ein; Zurück schließt nur die Ansicht – der Server läuft weiter.
 */
class AirPlayActivity : Activity() {
    private val app get() = application as RoomApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var svc: AirPlayService? = null

    private lateinit var mirror: SurfaceView
    private lateinit var hls: SurfaceView
    private lateinit var mirrorBox: AspectFrame
    private lateinit var hlsBox: AspectFrame
    private lateinit var music: LinearLayout
    private lateinit var waiting: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var videoBar: LinearLayout
    private var hideJob: Job? = null
    private var closeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or // Tablet wacht bei einer Verbindung auf
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ---- Spiegelung
        mirror = SurfaceView(this)
        mirror.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                svc?.setVideoSurface(h.surface)
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                svc?.setVideoSurface(h.surface)
            }

            override fun surfaceDestroyed(h: SurfaceHolder) {
                svc?.clearVideoSurface(h.surface)
            }
        })
        mirrorBox = AspectFrame(this).apply { addView(mirror, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(mirrorBox, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- AirPlay-Video (HLS)
        hls = SurfaceView(this)
        hls.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                svc?.setVideoPlaybackSurface(h.surface)
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                svc?.setVideoPlaybackSurface(h.surface)
            }

            override fun surfaceDestroyed(h: SurfaceHolder) {
                svc?.clearVideoPlaybackSurface(h.surface)
            }
        })
        hlsBox = AspectFrame(this).apply {
            addView(hls, FrameLayout.LayoutParams(MATCH, MATCH))
            visibility = View.GONE
        }
        root.addView(hlsBox, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- Musik: großes Cover + Titel + Steuerung
        val art = ArtView(this, 28f, shade = false)
        val title = label("", 30f, bold = true, lines = 2).apply { gravity = Gravity.CENTER }
        val artist = label("", 19f, C.TEXT2).apply { gravity = Gravity.CENTER }
        val play = MediaButton(this, MediaButton.Kind.PLAY, 76) { app.airplay.playPause() }
        music = column().apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF07070A.toInt())
            addView(art, lp(dp(300), dp(300)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(title, lp(MATCH, WRAP).margins(t = dp(26)))
            addView(artist, lp(MATCH, WRAP).margins(t = dp(6)))
            addView(
                row(
                    MediaButton(this@AirPlayActivity, MediaButton.Kind.PREV, 58) { app.airplay.previous() },
                    play,
                    MediaButton(this@AirPlayActivity, MediaButton.Kind.NEXT, 58) { app.airplay.next() },
                    spacingDp = 24,
                ),
                lp(WRAP, WRAP).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(26)
                },
            )
            visibility = View.GONE
        }
        root.addView(music, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- Warten auf eine Verbindung
        val waitText = label("", 18f, C.TEXT2, lines = 3).apply { gravity = Gravity.CENTER }
        waiting = column().apply {
            gravity = Gravity.CENTER
            addView(AirPlayGlyph(this@AirPlayActivity, C.white(0.5f)), lp(dp(110), dp(110)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(waitText, lp(MATCH, WRAP).margins(t = dp(20)))
        }
        root.addView(waiting, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- Leiste oben (Status + Schließen) und Video-Steuerung unten
        val status = label("", 15f, C.TEXT)
        bar = row(
            AirPlayGlyph(this).apply { layoutParams = lp(dp(22), dp(22)) },
            status.also { it.layoutParams = lp(0, WRAP, 1f).margins(l = dp(10)) },
            glassButton(tr("Stoppen", "Stop")) { svc?.stopVideoPlayback() }.also { it.visibility = View.GONE; it.tag = "stopVideo" },
            glassIconButton(Glyph.CLOSE, 44) { finish() },
            spacingDp = 10,
        ).apply {
            setPadding(dp(20), dp(14), dp(16), dp(14))
            setBackgroundColor(0x99000000.toInt())
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        val vPlay = MediaButton(this, MediaButton.Kind.PLAY, 60) { svc?.let { it.setVideoPlaying(!it.videoPlaybackInfo.value.playing) } }
        val pos = label("", 15f, C.TEXT2)
        videoBar = row(
            glassButton("−10 s") { svc?.seekVideoBy(-AirPlayService.VIDEO_SEEK_STEP_MS) },
            vPlay,
            glassButton("+10 s") { svc?.seekVideoBy(AirPlayService.VIDEO_SEEK_STEP_MS) },
            pos,
            spacingDp = 18,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(18))
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
        }
        root.addView(videoBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
        hideSystemBars()

        // ---- Zustand des Servers
        val a = app.airplay
        scope.launch {
            a.service.collect { s ->
                svc = s
                if (s != null) {
                    // vorhandene Flächen sofort übergeben
                    if (mirror.holder.surface?.isValid == true) s.setVideoSurface(mirror.holder.surface)
                    if (hls.holder.surface?.isValid == true) s.setVideoPlaybackSurface(hls.holder.surface)
                    launch { s.videoAspect.collect { mirrorBox.aspect = it } }
                    launch { s.videoPlaybackAspect.collect { hlsBox.aspect = it } }
                    launch {
                        s.videoPlaybackInfo.collect { info ->
                            vPlay.playing = info.playing
                            pos.text = if (info.durationMs > 0) "${time(info.positionMs)} / ${time(info.durationMs)}" else ""
                        }
                    }
                }
            }
        }
        scope.launch {
            combine(a.connections, a.mirroring, a.video, a.audioOnly, a.nowPlaying) { n, m, v, ao, np ->
                arrayOf(n, m, v, ao, np)
            }.collect { arr ->
                val n = arr[0] as Int
                val video = arr[2] as Boolean
                val audioOnly = arr[3] as Boolean
                val np = arr[4] as AirPlayController.NowPlaying?
                hlsBox.visibility = if (video) View.VISIBLE else View.GONE
                mirrorBox.visibility = if (video) View.GONE else View.VISIBLE
                music.visibility = if (!video && audioOnly && n > 0) View.VISIBLE else View.GONE
                waiting.visibility = if (n == 0 && !video) View.VISIBLE else View.GONE
                videoBar.visibility = if (video) View.VISIBLE else View.GONE
                bar.findViewWithTag<View>("stopVideo")?.visibility = if (video) View.VISIBLE else View.GONE
                art.art = np?.art
                title.text = np?.title ?: tr("AirPlay-Musik", "AirPlay music")
                artist.text = np?.artist ?: ""
                play.playing = np?.playing == true
                status.text = when {
                    video -> tr("AirPlay-Video", "AirPlay video")
                    n > 0 && audioOnly -> tr("Musik über AirPlay", "Music via AirPlay")
                    n > 0 -> tr("Bildschirm wird gespiegelt", "Screen mirroring")
                    else -> tr("Warte auf Verbindung", "Waiting for a connection")
                }
                waitText.text = if (a.running.value) {
                    tr(
                        "Bereit als „${a.serverName}“\nAm iPhone/Mac: Kontrollzentrum → Bildschirmsynchronisierung",
                        "Ready as “${a.serverName}”\nOn iPhone/Mac: Control Center → Screen Mirroring",
                    )
                } else {
                    tr("AirPlay ist aus – im Widget einschalten.", "AirPlay is off – turn it on in the widget.")
                }
                // Verbindung beendet → nach kurzer Zeit zurück zum Launcher
                closeJob?.cancel()
                if (n == 0 && !video) {
                    closeJob = launch {
                        delay(4_000)
                        finish()
                    }
                }
                showBar()
            }
        }
        scope.launch { a.pin.collect { p -> if (p != null) status.text = "PIN: $p" } }
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Leiste kurz zeigen, dann ausblenden (Video/Spiegelung bleiben frei sichtbar). */
    private fun showBar() {
        bar.animate().alpha(1f).setDuration(150).start()
        if (videoBar.visibility == View.VISIBLE) videoBar.animate().alpha(1f).setDuration(150).start()
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(3_500)
            if (waiting.visibility != View.VISIBLE) {
                bar.animate().alpha(0f).setDuration(300).start()
                videoBar.animate().alpha(0f).setDuration(300).start()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showBar()
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onDestroy() {
        svc?.let { s ->
            runCatching { s.clearVideoSurface(mirror.holder.surface) }
            runCatching { s.clearVideoPlaybackSurface(hls.holder.surface) }
        }
        scope.cancel()
        super.onDestroy()
    }
}

/** Hält das Kind im Seitenverhältnis [aspect], mittig und so groß wie möglich (schwarze Ränder). */
class AspectFrame(context: android.content.Context) : FrameLayout(context) {
    var aspect = 16f / 9f
        set(v) {
            if (v > 0f && v != field) {
                field = v
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        var cw = w
        var ch = (w / aspect).toInt()
        if (ch > h) {
            ch = h
            cw = (h * aspect).toInt()
        }
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val left = (width - c.measuredWidth) / 2
            val top = (height - c.measuredHeight) / 2
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }
}

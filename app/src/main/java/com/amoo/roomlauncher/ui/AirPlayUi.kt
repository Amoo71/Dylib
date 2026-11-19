package com.amoo.roomlauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.amoo.roomlauncher.Lang
import com.amoo.roomlauncher.airplay.AirPlayController
import com.amoo.roomlauncher.toast
import com.amoo.roomlauncher.board.WidgetSize
import com.amoo.roomlauncher.tr
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ Bausteine

/** AirPlay-Symbol: Bildschirm mit Dreieck darunter. */
class AirPlayGlyph(context: Context, var color: Int = C.TEXT) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val tri = Path()

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = s * 0.08f
        // Bildschirm mit Lücke unten für das Dreieck
        r.set(cx - s * 0.42f, cy - s * 0.36f, cx + s * 0.42f, cy + s * 0.18f)
        val rr = s * 0.07f
        tri.reset()
        tri.moveTo(cx - s * 0.16f, r.bottom)
        tri.lineTo(r.left + rr, r.bottom)
        tri.quadTo(r.left, r.bottom, r.left, r.bottom - rr)
        tri.lineTo(r.left, r.top + rr)
        tri.quadTo(r.left, r.top, r.left + rr, r.top)
        tri.lineTo(r.right - rr, r.top)
        tri.quadTo(r.right, r.top, r.right, r.top + rr)
        tri.lineTo(r.right, r.bottom - rr)
        tri.quadTo(r.right, r.bottom, r.right - rr, r.bottom)
        tri.lineTo(cx + s * 0.16f, r.bottom)
        canvas.drawPath(tri, stroke)
        tri.reset()
        tri.moveTo(cx, cy + s * 0.04f)
        tri.lineTo(cx + s * 0.22f, cy + s * 0.42f)
        tri.lineTo(cx - s * 0.22f, cy + s * 0.42f)
        tri.close()
        canvas.drawPath(tri, fill)
    }
}

/** Runde Medien-Taste: zurück, abspielen/pausieren, weiter. */
class MediaButton(context: Context, private val kind: Kind, sizeDp: Int, onClick: () -> Unit) : View(context) {
    enum class Kind { PREV, PLAY, NEXT }

    var playing = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111114.toInt() }
    private val path = Path()

    init {
        layoutParams = lp(dp(sizeDp), dp(sizeDp))
        pressable(onClick = onClick)
        contentDescription = kind.name
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val main = kind == Kind.PLAY
        bg.color = if (main) C.white(0.92f) else C.white(0.12f)
        canvas.drawCircle(cx, cy, s / 2, bg)
        p.color = if (main) 0xFF111114.toInt() else C.TEXT
        val g = s * 0.3f
        path.reset()
        when (kind) {
            Kind.PLAY -> if (playing) {
                val w = g * 0.32f
                canvas.drawRoundRect(cx - g * 0.55f, cy - g * 0.6f, cx - g * 0.55f + w, cy + g * 0.6f, w / 3, w / 3, p)
                canvas.drawRoundRect(cx + g * 0.55f - w, cy - g * 0.6f, cx + g * 0.55f, cy + g * 0.6f, w / 3, w / 3, p)
            } else {
                path.moveTo(cx - g * 0.4f, cy - g * 0.65f)
                path.lineTo(cx + g * 0.7f, cy)
                path.lineTo(cx - g * 0.4f, cy + g * 0.65f)
                path.close()
                canvas.drawPath(path, p)
            }
            Kind.NEXT, Kind.PREV -> {
                canvas.save()
                if (kind == Kind.PREV) canvas.scale(-1f, 1f, cx, cy)
                for (o in listOf(-g * 0.55f, g * 0.05f)) {
                    path.reset()
                    path.moveTo(cx + o, cy - g * 0.5f)
                    path.lineTo(cx + o + g * 0.6f, cy)
                    path.lineTo(cx + o, cy + g * 0.5f)
                    path.close()
                    canvas.drawPath(path, p)
                }
                canvas.drawRect(cx + g * 0.62f, cy - g * 0.5f, cx + g * 0.74f, cy + g * 0.5f, p)
                canvas.restore()
            }
        }
    }
}

/** Cover mit runden Ecken; [shade] = dunkler Verlauf nach unten (für Text auf dem Cover). */
class ArtView(context: Context, private val radiusDp: Float, private val shade: Boolean) : View(context) {
    var art: Bitmap? = null
        set(v) {
            if (field !== v) {
                field = v
                shader = null
                invalidate()
            }
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: BitmapShader? = null
    private val m = Matrix()
    private val r = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shader = null
        shadeP.shader = LinearGradient(0f, h * 0.35f, 0f, h.toFloat(), 0x00000000, 0xD9000000.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val b = art ?: return
        val rad = context.dpf(radiusDp)
        if (shader == null) {
            // "centerCrop"
            val sc = max(width / b.width.toFloat(), height / b.height.toFloat())
            m.setScale(sc, sc)
            m.postTranslate((width - b.width * sc) / 2f, (height - b.height * sc) / 2f)
            shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            p.shader = shader
        }
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(r, rad, rad, p)
        if (shade) canvas.drawRoundRect(r, rad, rad, shadeP)
    }
}

/** Status-Text des Servers. */
private fun statusText(a: AirPlayController): Pair<String, Int> = when {
    a.error.value -> Lang.auto("Fehler") to C.RED
    a.video.value -> Lang.auto("Video") to C.GREEN
    a.connections.value > 0 && !a.audioOnly.value -> Lang.auto("spiegelt") to C.GREEN
    a.nowPlaying.value?.playing == true -> Lang.auto("spielt") to C.GREEN
    a.running.value -> Lang.auto("bereit") to C.GREEN
    else -> Lang.auto("aus") to C.TEXT3
}

internal fun View.bindAirPlay(a: AirPlayController, block: () -> Unit) {
    bind(a.running) { block() }
    bind(a.error) { block() }
    bind(a.connections) { block() }
    bind(a.video) { block() }
    bind(a.audioOnly) { block() }
    bind(a.nowPlaying) { block() }
}

/** Ein/Aus-Knopf des Empfängers. */
private fun Context.airPlayToggle(a: AirPlayController, highlighted: Boolean): TextView {
    val b = glassButton("Einschalten", highlighted) { a.toggle() }
    b.bindAirPlay(a) { b.text = Lang.auto(if (a.running.value) "Ausschalten" else "Einschalten") }
    return b
}

/** "Ansehen" – nur während einer Verbindung (Spiegelung/Video/Musik). */
private fun Context.airPlayWatch(a: AirPlayController): TextView {
    val b = glassButton("Ansehen", true) { a.openViewer() }
    b.bindAirPlay(a) { b.visibility = if (a.connections.value > 0 || a.video.value) View.VISIBLE else View.GONE }
    return b
}

/** Steuerleiste: zurück · abspielen/pausieren · weiter. */
private fun Context.mediaControls(a: AirPlayController, sizeDp: Int, withSkip: Boolean): LinearLayout {
    val play = MediaButton(this, MediaButton.Kind.PLAY, sizeDp) { a.playPause() }
    play.bind(a.nowPlaying) { play.playing = it?.playing == true }
    val views = mutableListOf<View>()
    if (withSkip) views += MediaButton(this, MediaButton.Kind.PREV, (sizeDp * 0.8f).toInt()) { a.previous() }
    views += play
    if (withSkip) views += MediaButton(this, MediaButton.Kind.NEXT, (sizeDp * 0.8f).toInt()) { a.next() }
    return row(*views.toTypedArray(), spacingDp = 12)
}

private fun hintFor(a: AirPlayController): String = when {
    a.error.value -> tr("Start fehlgeschlagen – WLAN verbunden?", "Start failed – is Wi-Fi connected?")
    a.video.value -> tr("AirPlay-Video läuft", "AirPlay video is playing")
    a.connections.value > 0 && !a.audioOnly.value -> tr("Bildschirm wird gespiegelt", "Screen is being mirrored")
    a.running.value -> tr("Bereit als „${a.serverName}“ – am iPhone/Mac AirPlay wählen", "Ready as “${a.serverName}” – pick AirPlay on iPhone/Mac")
    else -> tr("AirPlay ist aus", "AirPlay is off")
}

// ------------------------------------------------------------------ Widget

/**
 * AirPlay-Widget (eingebauter Empfänger).
 *  - Klein: Cover (oder Symbol) mit Titel/Interpret, Abspielen/Pause; sonst Status + Ein/Aus bzw. Ansehen.
 *  - Mittel: Cover links; rechts Status, Titel/Interpret, Steuerleiste, Ein/Aus und Ansehen.
 * Gedrückt halten öffnet die große Ansicht (mit Einstellungen).
 */
fun Context.airPlayWidget(size: WidgetSize, a: AirPlayController, glass: Boolean, onOpen: () -> Unit): View {
    val small = size == WidgetSize.S
    val card = FrameLayout(this).apply {
        if (glass) background = GlassDrawable(context, 30f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    val dot = StatusDot(this)
    val status = label("", 12.5f, C.TEXT2)
    val head = row(
        AirPlayGlyph(this).apply { layoutParams = lp(dp(18), dp(18)) },
        label("AirPlay", 15f, bold = true),
        dot.also { it.layoutParams = lp(dp(14), dp(14)).margins(l = dp(2)) },
        status,
        spacingDp = 6,
    ).apply { pressable(onClick = onOpen) }
    val title = label("", if (small) 15f else 19f, bold = true)
    val artist = label("", if (small) 12.5f else 14f, C.TEXT2)
    val hint = label("", 12.5f, C.TEXT3, lines = 2)

    if (small) {
        val art = ArtView(this, 22f, shade = true)
        val glyph = AirPlayGlyph(this, C.white(0.85f))
        val play = mediaControls(a, 46, withSkip = false)
        val toggle = airPlayToggle(a, true)
        val watch = airPlayWatch(a)
        card.addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(glyph, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER).apply { bottomMargin = dp(18) })
        card.addView(head, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        val bottom = column().apply {
            addView(title)
            addView(artist, lp().margins(t = dp(2)))
            addView(hint)
            addView(row(play, watch, toggle, spacingDp = 8), lp(WRAP, WRAP).margins(t = dp(8)))
        }
        card.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        card.bindAirPlay(a) {
            val np = a.nowPlaying.value
            val (st, col) = statusText(a)
            status.text = st
            dot.color = col
            art.art = np?.art
            art.visibility = if (np?.art != null) View.VISIBLE else View.GONE
            glyph.visibility = if (np?.art == null) View.VISIBLE else View.GONE
            glyph.color = if (a.running.value) C.white(0.9f) else C.white(0.35f)
            glyph.invalidate()
            title.text = np?.title ?: ""
            artist.text = np?.artist ?: ""
            title.visibility = if (np?.title != null) View.VISIBLE else View.GONE
            artist.visibility = if (np?.artist != null) View.VISIBLE else View.GONE
            hint.text = hintFor(a)
            hint.visibility = if (np == null) View.VISIBLE else View.GONE
            play.visibility = if (np != null) View.VISIBLE else View.GONE
            // Während einer Sitzung "Ansehen" statt Ein/Aus
            toggle.visibility = if (np == null && a.connections.value == 0 && !a.video.value) View.VISIBLE else View.GONE
        }
    } else {
        val art = ArtView(this, 22f, shade = false)
        val artBox = FrameLayout(this).apply {
            background = RoundDrawable(context, 22f, C.white(0.06f), C.white(0.12f))
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(AirPlayGlyph(this@airPlayWidget, C.white(0.5f)), FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        }
        val controls = mediaControls(a, 50, withSkip = true)
        val buttons = row(airPlayWatch(a), airPlayToggle(a, false), spacingDp = 10)
        val right = column().apply {
            addView(head)
            addView(flexSpace())
            addView(title)
            addView(artist, lp().margins(t = dp(3)))
            addView(hint)
            addView(flexSpace())
            addView(controls, lp(WRAP, WRAP).margins(b = dp(10)))
            addView(buttons, lp(WRAP, WRAP))
        }
        card.addView(
            row(
                artBox.also { it.layoutParams = lp(0, MATCH, 0.85f) },
                right.also { it.layoutParams = lp(0, MATCH, 1.15f).margins(l = dp(16)) },
                center = false,
            ),
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        card.bindAirPlay(a) {
            val np = a.nowPlaying.value
            val (st, col) = statusText(a)
            status.text = st
            dot.color = col
            art.art = np?.art
            title.text = np?.title ?: Lang.auto("AirPlay-Empfänger")
            artist.text = np?.artist ?: ""
            artist.visibility = if (np?.artist != null) View.VISIBLE else View.GONE
            hint.text = if (np != null) "" else hintFor(a)
            hint.visibility = if (np == null) View.VISIBLE else View.GONE
            controls.visibility = if (np != null) View.VISIBLE else View.GONE
        }
    }
    return card
}

// ------------------------------------------------------------------ Große Ansicht

fun Context.airPlaySheet(a: AirPlayController, onDismiss: () -> Unit): Sheet {
    val sheet = Sheet(this, "AirPlay", true, onDismiss)
    val root = column()

    // Läuft gerade
    val art = ArtView(this, 26f, shade = false)
    val artBox = FrameLayout(this).apply {
        background = RoundDrawable(context, 26f, C.white(0.06f), C.white(0.12f))
        addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(AirPlayGlyph(this@airPlaySheet, C.white(0.45f)), FrameLayout.LayoutParams(dp(90), dp(90), Gravity.CENTER))
    }
    val title = label("", 22f, bold = true, lines = 2)
    val artist = label("", 15f, C.TEXT2, lines = 2)
    val album = label("", 13.5f, C.TEXT3)
    val controls = mediaControls(a, 58, withSkip = true)
    val info = column().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(title)
        addView(artist, lp().margins(t = dp(4)))
        addView(album, lp().margins(t = dp(2)))
        addView(controls, lp(WRAP, WRAP).margins(t = dp(18)))
        addView(row(airPlayWatch(a), airPlayToggle(a, false), spacingDp = 10), lp(WRAP, WRAP).margins(t = dp(14)))
    }
    root.addView(row(artBox.also { it.layoutParams = lp(dp(200), dp(200)) }, info.also { it.layoutParams = lp(0, WRAP, 1f).margins(l = dp(22)) }))

    val stat = label("", 14f, C.TEXT2, lines = 4).apply { setLineSpacing(dpf(5), 1f) }
    root.addView(stat, lp().margins(t = dp(18)))

    // ---- Einstellungen
    root.addView(lumenTitle("Einstellungen"), lp().margins(t = dp(22), b = dp(8)))
    val name = android.widget.EditText(this).apply {
        setText(a.serverName)
        hint = Lang.auto("Name im WLAN")
        typeface = Fonts.regular
        setHintTextColor(C.TEXT3)
        setTextColor(C.TEXT)
        textSize = 16f
        isSingleLine = true
        background = GlassDrawable(context, 20f, 0.8f, frosted = false)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        layoutParams = lp(0, WRAP, 1f)
    }
    root.addView(
        row(
            name,
            glassButton("Speichern", true) {
                a.setServerName(name.text.toString())
                toast(tr("Name gespeichert – AirPlay startet kurz neu", "Name saved – AirPlay restarts briefly"))
            },
            spacingDp = 12,
        ),
    )
    root.addView(switchRow(Lang.auto("Immer eingeschaltet"), a.get(io.github.jqssun.airplay.Prefs.AUTO_START), Lang.auto("Startet mit dem Launcher und läuft im Hintergrund")) {
        if (it) a.startServer() else a.stopServer()
    }, lp().margins(t = dp(10)))
    root.addView(switchRow(Lang.auto("Bei Verbindung Vollbild öffnen"), a.get(io.github.jqssun.airplay.Prefs.LAUNCH_ON_CONNECT), Lang.auto("Spiegelung/Video erscheint sofort – auch aus dem Standby")) {
        a.set(io.github.jqssun.airplay.Prefs.LAUNCH_ON_CONNECT, it)
    })
    root.addView(switchRow(Lang.auto("PIN verlangen"), a.get(io.github.jqssun.airplay.Prefs.REQUIRE_PIN), Lang.auto("Neue Geräte müssen eine PIN eingeben")) {
        a.set(io.github.jqssun.airplay.Prefs.REQUIRE_PIN, it)
    })
    val resOpts = listOf("auto" to "Auto", "1280x720" to "720p", "1920x1080" to "1080p")
    root.addView(lumenTitle("Auflösung"), lp().margins(t = dp(14), b = dp(8)))
    root.addView(chipGroup(resOpts.map { it.second }, resOpts.indexOfFirst { it.first == a.get(io.github.jqssun.airplay.Prefs.RESOLUTION) }.coerceAtLeast(0)) { i ->
        a.set(io.github.jqssun.airplay.Prefs.RESOLUTION, resOpts[i].first)
    })
    val fpsOpts = listOf(30, 60)
    root.addView(lumenTitle("Bildrate"), lp().margins(t = dp(14), b = dp(8)))
    root.addView(chipGroup(fpsOpts.map { "$it fps" }, fpsOpts.indexOf(a.get(io.github.jqssun.airplay.Prefs.MAX_FPS)).coerceAtLeast(1)) { i ->
        a.set(io.github.jqssun.airplay.Prefs.MAX_FPS, fpsOpts[i])
    })
    val hevcOpts = listOf("auto" to "Auto", "on" to "An", "off" to "Aus")
    root.addView(lumenTitle("H.265 (HEVC)"), lp().margins(t = dp(14), b = dp(8)))
    root.addView(chipGroup(hevcOpts.map { it.second }, hevcOpts.indexOfFirst { it.first == a.get(io.github.jqssun.airplay.Prefs.H265) }.coerceAtLeast(0)) { i ->
        a.set(io.github.jqssun.airplay.Prefs.H265, hevcOpts[i].first)
    })
    root.addView(
        label(
            tr(
                "Tablet und iPhone/Mac müssen im selben WLAN sein. Geschützte Inhalte (z. B. Apple TV+) gehen nicht. " +
                    "AirPlay-Empfänger: UxPlay / jqssun (GPL-3.0).",
                "Tablet and iPhone/Mac must be on the same Wi-Fi. Protected content (e.g. Apple TV+) doesn't work. " +
                    "AirPlay receiver: UxPlay / jqssun (GPL-3.0).",
            ),
            12.5f, C.TEXT3, lines = 4,
        ),
        lp().margins(t = dp(18)),
    )
    root.addView(spacer(12))

    root.bindAirPlay(a) {
        val np = a.nowPlaying.value
        art.art = np?.art
        title.text = np?.title ?: Lang.auto(if (a.running.value) "Bereit für AirPlay" else "AirPlay ist aus")
        artist.text = np?.artist ?: tr("Am iPhone/Mac: Kontrollzentrum → Bildschirmsynchronisierung oder AirPlay", "On iPhone/Mac: Control Center → Screen Mirroring or AirPlay")
        album.text = np?.album ?: ""
        controls.visibility = if (np != null) View.VISIBLE else View.GONE
        fun ok(b: Boolean) = if (b) "✓" else "✗"
        stat.text = buildString {
            appendLine("${ok(a.running.value)}  " + tr("Empfänger läuft – im WLAN sichtbar als „${a.serverName}“", "Receiver running – visible on Wi-Fi as “${a.serverName}”"))
            append("${ok(a.connections.value > 0)}  " + tr("Verbundene Geräte: ${a.connections.value}", "Connected devices: ${a.connections.value}"))
            if (a.error.value) append("\n✗  " + tr("Start fehlgeschlagen – WLAN verbunden?", "Start failed – is Wi-Fi connected?"))
        }
    }
    sheet.body.addView(scroll(root), lp(MATCH, 0, 1f))
    return sheet
}

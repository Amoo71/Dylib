package com.amoo.roomlauncher.govee

/**
 * Govee-BLE-Protokoll (reines Kotlin, ohne Android – dadurch per Unit-Test prüfbar).
 *
 * Quellen: egold555/Govee-Reverse-Engineering (H6127-Mitschnitte), Beshelmek/govee_ble_lights
 * (Home-Assistant-Integration, Segment-Befehl), chvolkmann/govee_btled.
 *
 *  - 20-Byte-Pakete, letztes Byte = XOR über die ersten 19 Bytes
 *  - 33 01 xx                 Power (01 an / 00 aus)
 *  - 33 04 xx                 Helligkeit (0x00–0xFE bei 0–255-Modellen, 0–100 bei neueren)
 *  - 33 05 02 r g b           Farbe, klassische Modelle
 *  - 33 05 0D/0B r g b        Farbe, neuere H6xxx
 *  - 33 05 15 01 r g b 00 00 00 00 00 LL HH
 *                             Farbe für Segmente (RGBIC); LL/HH = Bitmaske der Segmente (15 Segmente = FF 7F)
 *  - 33 05 04 id              Szene
 *  - 33 05 01 sub [00 r g b]  Musikmodus (Farbe nur bei Spektrum/Rollen)
 *  - AA 01                    Keep-Alive / Status abfragen (Antwort AA 01 xx)
 *  - AA 04                    Helligkeit abfragen (Antwort AA 04 vv)
 *
 * H617E (geprüft mit server.py / "Lumen Remote"): Write Without Response, Helligkeit 1–100,
 * RGB auf 8er-Stufen abgerundet, Segment-Befehl 0x15 mit Maske (alle 15 = 0x7FFF).
 */
object GoveeProtocol {
    const val MODE_CLASSIC = 0x02
    const val MODE_NEW = 0x0D
    const val MODE_ALT = 0x0B
    const val MODE_SEGMENTS = 0x15

    const val SEGMENTS = 15
    const val ALL_SEGMENTS = (1 shl SEGMENTS) - 1 // 0x7FFF

    fun packet(vararg data: Int): ByteArray {
        val b = ByteArray(20)
        data.forEachIndexed { i, v -> if (i < 19) b[i] = v.toByte() }
        var x = 0
        for (i in 0 until 19) x = x xor (b[i].toInt() and 0xFF)
        b[19] = x.toByte()
        return b
    }

    fun power(on: Boolean) = packet(0x33, 0x01, if (on) 0x01 else 0x00)

    /** [level] 0..1. Klassische Modelle: 0x01–0xFE, neuere: 1–100. */
    fun brightness(level: Float, scale100: Boolean): ByteArray {
        val v = level.coerceIn(0f, 1f)
        val raw = if (scale100) Math.round(v * 100).coerceIn(1, 100) else Math.round(v * 254).coerceIn(1, 0xFE)
        return packet(0x33, 0x04, raw)
    }

    /** Jeden Kanal auf 8er-Stufen abrunden – so macht es auch Govees eigene Steuerung. */
    fun quantize(c: Int): Int = (c / 8) * 8

    fun color(argb: Int, mode: Int, segmentMask: Int = ALL_SEGMENTS): ByteArray {
        val r = quantize((argb shr 16) and 0xFF)
        val g = quantize((argb shr 8) and 0xFF)
        val b = quantize(argb and 0xFF)
        return if (mode == MODE_SEGMENTS) {
            val m = segmentMask and ALL_SEGMENTS
            packet(0x33, 0x05, 0x15, 0x01, r, g, b, 0, 0, 0, 0, 0, m and 0xFF, (m shr 8) and 0xFF)
        } else {
            packet(0x33, 0x05, mode, r, g, b)
        }
    }

    fun scene(id: Int) = packet(0x33, 0x05, 0x04, id)

    /** 0 Energisch, 1 Spektrum, 2 Rollen, 3 Rhythmus – nur Spektrum/Rollen nutzen eine Farbe. */
    fun music(sub: Int, argb: Int): ByteArray =
        if (sub == 1 || sub == 2) {
            packet(0x33, 0x05, 0x01, sub, 0x00, (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        } else {
            packet(0x33, 0x05, 0x01, sub)
        }

    val KEEP_ALIVE: ByteArray get() = packet(0xAA, 0x01)
    val QUERY_BRIGHTNESS: ByteArray get() = packet(0xAA, 0x04)

    /** Segmentpaket: [rgb] (ohne Alpha, 0 = aus) für alle Segmente in [mask]. */
    fun segment(rgb: Int, mask: Int): ByteArray = color(rgb, MODE_SEGMENTS, mask)

    /**
     * Plant die Segmentpakete: ein Paket pro Farbe, mit allen Segmenten dieser Farbe in der Maske.
     * [synced] = false → kompletter Stand (nach dem Verbinden – sonst ignoriert die Leiste Einzelsegmente),
     * [synced] = true → nur Segmente, die sich gegenüber [sent] geändert haben.
     */
    fun planSegments(model: IntArray, sent: IntArray, synced: Boolean): List<Pair<Int, Int>> {
        val groups = LinkedHashMap<Int, Int>()
        for (i in 0 until SEGMENTS) {
            val c = model[i] and 0xFFFFFF
            if (synced && (sent[i] and 0xFFFFFF) == c) continue
            groups[c] = (groups[c] ?: 0) or (1 shl i)
        }
        return groups.map { (rgb, mask) -> rgb to mask }
    }

    // ---------------------------------------------------------------- Modell-Erkennung

    /** Govee-Modellnummer aus dem BLE-Namen, z. B. "ihoment_H6199_ABCD" → "H6199". */
    fun modelOf(name: String?): String? =
        name?.let { Regex("H[5-7][0-9]{2}[0-9A-Z]", RegexOption.IGNORE_CASE).find(it)?.value?.uppercase() }

    /**
     * RGBIC-Streifen mit einzeln steuerbaren Segmenten (Segment-Befehl 0x15).
     * Liste aus der Home-Assistant-Integration plus verbreitete RGBIC-Streifen der gleichen Generation.
     */
    private val SEGMENTED = setOf(
        "H617A", "H617C", "H617E", "H617F",
        "H6053", "H6072", "H6102", "H6199",
        "H619A", "H619B", "H619C", "H619D", "H619E", "H619Z",
        "H6172", "H6173", "H6175", "H6176",
        "H618A", "H618C", "H618E", "H618F",
    )

    /** Neuere Modelle, die Farbe über 0x0D erwarten. */
    private val NEW_COLOR = setOf("H6008", "H6046", "H6047", "H6051", "H6056", "H6059", "H6061", "H6062", "H6065", "H6076")

    fun isSegmented(model: String?) = model != null && model in SEGMENTED

    /** Modelle, deren Helligkeit 1–100 statt 0–254 läuft (H617E laut Mitschnitt). */
    private val BRIGHTNESS_100 = setOf("H617A", "H617C", "H617E", "H617F", "H6008", "H6046", "H6047", "H6076")

    fun brightness100(model: String?) = model != null && model in BRIGHTNESS_100

    /** Passender Farb-Befehl für ein Modell (unbekannt → klassisch). */
    fun colorModeFor(model: String?): Int = when {
        model == null -> MODE_CLASSIC
        model in SEGMENTED -> MODE_SEGMENTS
        model in NEW_COLOR -> MODE_NEW
        else -> MODE_CLASSIC
    }

    // ---------------------------------------------------------------- Statusmeldungen

    sealed class Status {
        data class Power(val on: Boolean) : Status()
        data class Brightness(val raw: Int) : Status()
        data class Color(val argb: Int) : Status()
    }

    /** Antworten des Lichts: AA 01 xx (Power), AA 04 xx (Helligkeit), AA 05 mm r g b (Farbe). */
    fun parseStatus(v: ByteArray): Status? {
        if (v.size < 3 || v[0] != 0xAA.toByte()) return null
        val b2 = v[2].toInt() and 0xFF
        return when (v[1].toInt() and 0xFF) {
            0x01 -> Status.Power(b2 == 0x01)
            0x04 -> if (b2 > 0) Status.Brightness(b2) else null
            0x05 -> if (v.size >= 6 && (b2 == MODE_CLASSIC || b2 == MODE_NEW || b2 == MODE_ALT)) {
                Status.Color(
                    (0xFF shl 24) or ((v[3].toInt() and 0xFF) shl 16) or ((v[4].toInt() and 0xFF) shl 8) or (v[5].toInt() and 0xFF),
                )
            } else {
                null
            }
            else -> null
        }
    }
}

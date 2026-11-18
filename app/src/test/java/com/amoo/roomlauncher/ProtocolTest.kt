package com.amoo.roomlauncher

import com.amoo.roomlauncher.govee.GoveeProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft die Bluetooth-Pakete Byte für Byte gegen veröffentlichte Mitschnitte:
 *  - Govee: egold555/Govee-Reverse-Engineering (H6127), Beshelmek/govee_ble_lights
 */
class ProtocolTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertHex(expected: String, actual: ByteArray) =
        assertEquals(expected.replace(" ", "").lowercase(), actual.joinToString("") { "%02x".format(it) })

    // ---------------------------------------------------------------- Govee

    @Test fun goveePowerMatchesCapture() {
        assertHex("3301010000000000000000000000000000000033", GoveeProtocol.power(true))
        assertHex("3301000000000000000000000000000000000032", GoveeProtocol.power(false))
    }

    @Test fun goveeKeepAliveMatchesCapture() {
        assertHex("aa010000000000000000000000000000000000ab", GoveeProtocol.KEEP_ALIVE)
    }

    @Test fun goveeScenesMatchCapture() {
        assertHex("3305040000000000000000000000000000000032", GoveeProtocol.scene(0x00)) // Sonnenaufgang
        assertHex("3305040100000000000000000000000000000033", GoveeProtocol.scene(0x01)) // Sonnenuntergang
        assertHex("3305040400000000000000000000000000000036", GoveeProtocol.scene(0x04)) // Film
        assertHex("330504090000000000000000000000000000003b", GoveeProtocol.scene(0x09)) // Kerzenlicht
        assertHex("3305040f0000000000000000000000000000003d", GoveeProtocol.scene(0x0F)) // Schneeflocke
    }

    @Test fun goveeMusicModesMatchCapture() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        assertHex("3305010000000000000000000000000000000037", GoveeProtocol.music(0, red)) // Energisch
        assertHex("3305010100ff00000000000000000000000000c9", GoveeProtocol.music(1, red)) // Spektrum rot
        assertHex("33050101000000ff0000000000000000000000c9", GoveeProtocol.music(1, blue)) // Spektrum blau
        assertHex("3305010200ff00000000000000000000000000ca", GoveeProtocol.music(2, red)) // Rollen rot
        assertHex("3305010300000000000000000000000000000034", GoveeProtocol.music(3, red)) // Rhythmus
    }

    @Test fun goveeBrightnessFullIsFE() {
        assertHex("3304fe00000000000000000000000000000000c9", GoveeProtocol.brightness(1f, scale100 = false))
        assertEquals(100, GoveeProtocol.brightness(1f, scale100 = true)[2].toInt())
        assertEquals(1, GoveeProtocol.brightness(0f, scale100 = false)[2].toInt()) // nie ganz 0 (= aus)
    }

    @Test fun goveeColorClassicAndSegments() {
        val red = 0xFFFF0000.toInt()
        // RGB wird auf 8er-Stufen abgerundet: FF → F8
        assertHex("330502f8000000000000000000000000000000cc", GoveeProtocol.color(red, GoveeProtocol.MODE_CLASSIC))
        // Alle 15 Segmente = Maske FF 7F (wie Home Assistant)
        val all = GoveeProtocol.color(red, GoveeProtocol.MODE_SEGMENTS)
        assertArrayEquals(hex("33 05 15 01 f8 00 00 00 00 00 00 00 ff 7f"), all.copyOfRange(0, 14))
        // Nur Segment 1 und 15
        val two = GoveeProtocol.color(red, GoveeProtocol.MODE_SEGMENTS, 0b100000000000001)
        assertEquals(0x01, two[12].toInt() and 0xFF)
        assertEquals(0x40, two[13].toInt() and 0xFF)
    }

    // ---- H617E: geprüfte Pakete aus dem Bauplan (server.py / Lumen Remote erzeugen exakt diese Bytes)

    @Test fun h617eSegmentOneInF82858() {
        assertHex("33051501f82858000000000001000000000000ab", GoveeProtocol.segment(0xF82858, 0x0001))
    }

    @Test fun h617eAllSegmentsBlue() {
        assertHex("330515010000f80000000000ff7f00000000005a", GoveeProtocol.color(0xFF0000FF.toInt(), GoveeProtocol.MODE_SEGMENTS))
    }

    @Test fun h617eBrightnessIsOneToHundred() {
        assertEquals(100, GoveeProtocol.brightness(1f, scale100 = true)[2].toInt())
        assertEquals(50, GoveeProtocol.brightness(0.5f, scale100 = true)[2].toInt())
        assertEquals(1, GoveeProtocol.brightness(0f, scale100 = true)[2].toInt()) // 0 nimmt die Leiste nicht an
    }

    @Test fun h617eIsDetectedFromName() {
        val m = GoveeProtocol.modelOf("Govee_H617E_774E")
        assertEquals("H617E", m)
        assertTrue(GoveeProtocol.isSegmented(m))
        assertTrue(GoveeProtocol.brightness100(m))
        assertEquals(GoveeProtocol.MODE_SEGMENTS, GoveeProtocol.colorModeFor(m))
    }

    @Test fun segmentPlannerSendsFullStateThenOnlyChanges() {
        val red = 0xF80000
        val model = IntArray(15) { if (it < 8) red else 0 }
        val sent = IntArray(15) { -1 }
        // Nach dem Verbinden: kompletter Stand, ein Paket pro Farbe
        val full = GoveeProtocol.planSegments(model, sent, synced = false)
        assertEquals(listOf(red to 0x00FF, 0 to 0x7F00), full)
        for ((rgb, mask) in full) for (i in 0 until 15) if (mask and (1 shl i) != 0) sent[i] = rgb
        // Danach nur Änderungen
        model[3] = 0x0000F8
        model[12] = 0x0000F8
        assertEquals(listOf(0x0000F8 to ((1 shl 3) or (1 shl 12))), GoveeProtocol.planSegments(model, sent, synced = true))
        // Nichts geändert → nichts senden
        sent[3] = 0x0000F8
        sent[12] = 0x0000F8
        assertTrue(GoveeProtocol.planSegments(model, sent, synced = true).isEmpty())
    }

    @Test fun goveeChecksumIsXorOfAllBytes() {
        listOf(
            GoveeProtocol.power(true), GoveeProtocol.color(0xFF12AB34.toInt(), 0x0D),
            GoveeProtocol.color(0xFF00FF00.toInt(), GoveeProtocol.MODE_SEGMENTS, 0x1234), GoveeProtocol.brightness(0.37f, false),
        ).forEach { p ->
            assertEquals(20, p.size)
            val xor: Int = p.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
            assertEquals(0, xor)
        }
    }

    @Test fun goveeStatusRepliesParse() {
        assertEquals(GoveeProtocol.Status.Brightness(0xFE), GoveeProtocol.parseStatus(hex("aa04fe0000000000000000000000000000000050")))
        assertEquals(GoveeProtocol.Status.Color(0xFFFF0000.toInt()), GoveeProtocol.parseStatus(hex("aa050dff0000000000000000000000000000005d")))
        assertEquals(GoveeProtocol.Status.Power(true), GoveeProtocol.parseStatus(hex("aa010100000000000000000000000000000000aa")))
        assertNull(GoveeProtocol.parseStatus(hex("3301010000000000000000000000000000000033")))
    }

    @Test fun goveeModelDetection() {
        assertEquals("H6199", GoveeProtocol.modelOf("ihoment_H6199_1A2B"))
        assertEquals("H619A", GoveeProtocol.modelOf("Govee_H619A_77F0"))
        assertEquals("H6008", GoveeProtocol.modelOf("Govee_H6008_ABCD"))
        assertNull(GoveeProtocol.modelOf("JBL Flip 5"))
        assertEquals(GoveeProtocol.MODE_SEGMENTS, GoveeProtocol.colorModeFor("H6199"))
        assertEquals(GoveeProtocol.MODE_NEW, GoveeProtocol.colorModeFor("H6008"))
        assertEquals(GoveeProtocol.MODE_CLASSIC, GoveeProtocol.colorModeFor("H6127"))
        assertEquals(GoveeProtocol.MODE_CLASSIC, GoveeProtocol.colorModeFor(null))
    }
}

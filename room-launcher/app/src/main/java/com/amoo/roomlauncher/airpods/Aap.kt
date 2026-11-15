package com.amoo.roomlauncher.airpods

/**
 * Apple Accessory Protocol (AAP) – Pakete wie in den Open-Source-Projekten
 * LibrePods, MagicPods und AirPodsDesktop dokumentiert.
 */
object Aap {
    const val PSM = 0x1001

    val HANDSHAKE = hex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")
    val SET_FEATURES = hex("04 00 04 00 4D 00 FF 00 00 00 00 00 00 00")
    val REQUEST_NOTIFICATIONS = hex("04 00 04 00 0F 00 FF FF FF FF")

    private val HEADER = hex("04 00 04 00")
    private const val OP_BATTERY = 0x04
    private const val OP_CONTROL = 0x09
    private const val CONTROL_NOISE = 0x0D

    /** mode: 01 Aus, 02 ANC, 03 Transparenz, 04 Adaptiv */
    fun noiseControl(mode: Int): ByteArray =
        hex("04 00 04 00 09 00 0D") + byteArrayOf(mode.toByte(), 0x00, 0x00, 0x00)

    sealed class Message {
        data class NoiseMode(val code: Int) : Message()
        data class BatteryInfo(
            val left: Int?, val right: Int?, val case: Int?,
            val chargingLeft: Boolean, val chargingRight: Boolean, val chargingCase: Boolean,
        ) : Message()
    }

    fun parse(p: ByteArray): Message? {
        if (p.size < 7) return null
        for (i in HEADER.indices) if (p[i] != HEADER[i]) return null
        val op = p[4].toInt() and 0xFF
        return when {
            op == OP_CONTROL && p.size >= 8 && (p[6].toInt() and 0xFF) == CONTROL_NOISE ->
                Message.NoiseMode(p[7].toInt() and 0xFF)
            op == OP_BATTERY -> parseBattery(p)
            else -> null
        }
    }

    // 04 00 04 00 04 00 [count] ([component] 01 [level] [status] 01) * count
    // component: 01 Einzelgerät, 02 rechts, 04 links, 08 Case
    // status: 01 lädt, 02 entlädt, 04 nicht verbunden
    private fun parseBattery(p: ByteArray): Message? {
        val count = p[6].toInt() and 0xFF
        var left: Int? = null
        var right: Int? = null
        var case: Int? = null
        var cl = false
        var cr = false
        var cc = false
        for (i in 0 until count) {
            val off = 7 + i * 5
            if (off + 3 >= p.size) break
            val comp = p[off].toInt() and 0xFF
            val level = (p[off + 2].toInt() and 0xFF).takeIf { it in 0..100 }
            val status = p[off + 3].toInt() and 0xFF
            if (status == 0x04) continue
            val charging = status == 0x01
            when (comp) {
                0x04, 0x01 -> { left = level; cl = charging }
                0x02 -> { right = level; cr = charging }
                0x08 -> { case = level; cc = charging }
            }
        }
        return Message.BatteryInfo(left, right, case, cl, cr, cc)
    }

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}

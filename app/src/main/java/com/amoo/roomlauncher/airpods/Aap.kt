package com.amoo.roomlauncher.airpods

/**
 * Apple Accessory Protocol (AAP). Paketdefinitionen nach GreenPods
 * (github.com/andrewkomkov/GreenPods, core/bluetooth/aap/AapProtocol.kt) und LibrePods.
 *
 * Paket: 04 00 04 00 | Opcode (16 bit LE) | Payload
 * Läuft über einen sicheren L2CAP-Kanal auf PSM 0x1001 mit Apples Service-UUID.
 */
object Aap {
    const val PSM = 0x1001

    /** Apples AAP-Service-UUID – ohne sie kommt der Kanal nicht hoch. */
    const val SERVICE_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"

    val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    /** Muss als Erstes gesendet werden, sonst ignorieren die AirPods alles. */
    val HANDSHAKE = hex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")

    /** Host-Fähigkeiten – schaltet Gesprächserkennung / adaptive Transparenz frei. */
    val SET_HOST_CAPABILITIES = hex("04 00 04 00 4D 00 FF 00 00 00 00 00 00 00")

    /** Abonniert Akku-, Ohrerkennungs-, Geräuschkontroll- und CA-Meldungen. */
    val REQUEST_NOTIFICATIONS = hex("04 00 04 00 0F 00 FF FF FE FF")

    const val OP_BATTERY = 0x04
    const val OP_EAR_DETECTION = 0x06
    const val OP_CONTROL = 0x09
    const val OP_CONVERSATIONAL_AWARENESS = 0x4B

    // Steuerbefehle (Opcode 0x09)
    const val CMD_EAR_DETECTION = 0x0A          // 01 an, 02 aus
    const val CMD_LISTENING_MODE = 0x0D         // 01 aus, 02 ANC, 03 Transparenz, 04 Adaptiv
    const val CMD_ONE_BUD_ANC = 0x1B            // 01 an, 02 aus
    const val CMD_CONVERSATION_DETECT = 0x28    // 01 an, 02 aus
    const val CMD_ADAPTIVE_STRENGTH = 0x2E      // 0..100
    const val CMD_ALLOW_OFF_MODE = 0x34         // 01 an, 02 aus

    fun control(command: Int, data1: Int, data2: Int = 0): ByteArray =
        HEADER + byteArrayOf(
            OP_CONTROL.toByte(), 0x00,
            command.toByte(), (data1 and 0xFF).toByte(), (data2 and 0xFF).toByte(), 0x00, 0x00,
        )

    fun toggle(on: Boolean) = if (on) 0x01 else 0x02

    sealed class Message {
        data class Control(val command: Int, val value: Int) : Message()
        data class EarDetection(val primaryInEar: Boolean, val secondaryInEar: Boolean) : Message()
        data class BatteryInfo(
            val left: Int?, val right: Int?, val case: Int?,
            val chargingLeft: Boolean, val chargingRight: Boolean, val chargingCase: Boolean,
        ) : Message()
        data class SpeechLevel(val level: Int) : Message()
    }

    /** Ein Lesevorgang kann mehrere Pakete enthalten – wir trennen an jedem Header. */
    fun split(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i <= data.size - 4) {
            if (data[i] == HEADER[0] && data[i + 1] == HEADER[1] && data[i + 2] == HEADER[2] && data[i + 3] == HEADER[3]) {
                starts += i
                i += 6
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return listOf(data)
        return starts.mapIndexed { idx, s -> data.copyOfRange(s, if (idx + 1 < starts.size) starts[idx + 1] else data.size) }
    }

    fun parse(p: ByteArray): Message? {
        if (p.size < 7) return null
        for (i in HEADER.indices) if (p[i] != HEADER[i]) return null
        val op = (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8)
        return when (op) {
            OP_CONTROL -> if (p.size >= 8) Message.Control(p[6].toInt() and 0xFF, p[7].toInt() and 0xFF) else null
            OP_EAR_DETECTION -> if (p.size >= 8) {
                Message.EarDetection(p[6].toInt() == 0x00, p[7].toInt() == 0x00)
            } else null
            OP_BATTERY -> parseBattery(p)
            OP_CONVERSATIONAL_AWARENESS -> if (p.size >= 10) Message.SpeechLevel(p[9].toInt() and 0xFF) else null
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

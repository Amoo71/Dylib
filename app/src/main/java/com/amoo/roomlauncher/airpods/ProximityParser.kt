package com.amoo.roomlauncher.airpods

/**
 * Wertet Apples BLE-Werbepaket "Proximity Pairing" aus (Firmen-ID 0x004C, Typ 0x07, 27 Byte),
 * das AirPods ständig aussenden. Aufbau nach GreenPods' AppleBeaconDecoder (gegen echte Mitschnitte
 * getestet, baut auf CAPod auf):
 *
 *  Byte 0      0x07 (Typ)          Byte 3–4  Modell-ID (z. B. 0x0E20 = AirPods Pro)
 *  Byte 5      Status-Flags        Byte 6    Akku: oberes Nibble = primärer, unteres = sekundärer AirPod
 *  Byte 7      oberes Nibble = Lade-Flags, unteres = Case-Akku (0xF = unbekannt)
 *  Byte 8      Deckel-Zähler       Byte 9    Farbe
 *
 *  Status: 0x20 primärer AirPod ist der linke · 0x02 primärer im Ohr · 0x08 sekundärer im Ohr
 *          0x10 ein AirPod im Case · 0x04 beide im Case
 *  Laden:  0x1 rechts · 0x2 links · 0x4 Case
 */
object ProximityParser {
    enum class Wear { IN_EAR, OUT, IN_CASE }

    data class Info(
        val model: PodModel,
        val rawModelId: Int,
        val left: Int?,
        val right: Int?,
        val case: Int?,
        val chargingLeft: Boolean,
        val chargingRight: Boolean,
        val chargingCase: Boolean,
        val leftWear: Wear,
        val rightWear: Wear,
        val lidCounter: Int,
    ) {
        val leftInEar get() = leftWear == Wear.IN_EAR
        val rightInEar get() = rightWear == Wear.IN_EAR
    }

    private const val LENGTH = 27
    private const val TYPE = 0x07

    fun parse(d: ByteArray): Info? {
        if (d.size != LENGTH || (d[0].toInt() and 0xFF) != TYPE) return null
        val modelId = ((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF)
        val status = d[5].toInt() and 0xFF
        val battery = d[6].toInt() and 0xFF
        val caseByte = d[7].toInt() and 0xFF

        val primaryIsLeft = status and 0x20 != 0
        val primary = battery shr 4
        val secondary = battery and 0x0F
        val chargingFlags = caseByte shr 4

        fun pct(n: Int): Int? = if (n == 0x0F) null else n.coerceAtMost(10) * 10

        val bothInCase = status and 0x04 != 0
        val oneInCase = status and 0x10 != 0
        val primaryWear = when {
            bothInCase -> Wear.IN_CASE
            status and 0x02 != 0 -> Wear.IN_EAR
            oneInCase -> Wear.IN_CASE
            else -> Wear.OUT
        }
        val secondaryWear = when {
            bothInCase -> Wear.IN_CASE
            status and 0x08 != 0 -> Wear.IN_EAR
            else -> Wear.OUT
        }
        return Info(
            model = PodModel.fromId(modelId),
            rawModelId = modelId,
            left = pct(if (primaryIsLeft) primary else secondary),
            right = pct(if (primaryIsLeft) secondary else primary),
            case = pct(caseByte and 0x0F),
            chargingLeft = chargingFlags and 0x2 != 0,
            chargingRight = chargingFlags and 0x1 != 0,
            chargingCase = chargingFlags and 0x4 != 0,
            leftWear = if (primaryIsLeft) primaryWear else secondaryWear,
            rightWear = if (primaryIsLeft) secondaryWear else primaryWear,
            lidCounter = d[8].toInt() and 0xFF,
        )
    }
}

/** Apple-Kopfhörer-Modelle (Modell-ID aus dem Werbepaket, wie gesendet – AirPods Pro = 0x0E20). */
enum class PodModel(val id: Int, val label: String, val noiseControl: Boolean, val splitBuds: Boolean = true) {
    AIRPODS_1(0x0220, "AirPods (1. Gen.)", false),
    AIRPODS_2(0x0F20, "AirPods (2. Gen.)", false),
    AIRPODS_3(0x1320, "AirPods (3. Gen.)", false),
    AIRPODS_4(0x1920, "AirPods 4", false),
    AIRPODS_4_ANC(0x1B20, "AirPods 4 (ANC)", true),
    AIRPODS_PRO(0x0E20, "AirPods Pro", true),
    AIRPODS_PRO_2(0x1420, "AirPods Pro 2", true),
    AIRPODS_PRO_2_USBC(0x2420, "AirPods Pro 2 (USB-C)", true),
    AIRPODS_PRO_3(0x2720, "AirPods Pro 3", true),
    AIRPODS_MAX(0x0A20, "AirPods Max", true, splitBuds = false),
    AIRPODS_MAX_USBC(0x1F20, "AirPods Max (USB-C)", true, splitBuds = false),
    AIRPODS_MAX_2(0x2D20, "AirPods Max (2. Gen.)", true, splitBuds = false),
    POWERBEATS_3(0x0320, "Powerbeats 3", false),
    POWERBEATS_4(0x0D20, "Powerbeats 4", false),
    POWERBEATS_PRO(0x0B20, "Powerbeats Pro", false),
    POWERBEATS_PRO_2(0x1D20, "Powerbeats Pro 2", true),
    BEATS_FIT_PRO(0x1220, "Beats Fit Pro", true),
    BEATS_FLEX(0x1020, "Beats Flex", false),
    BEATS_SOLO_3(0x0620, "Beats Solo 3", false, splitBuds = false),
    BEATS_SOLO_4(0x2520, "Beats Solo 4", false, splitBuds = false),
    BEATS_SOLO_BUDS(0x2620, "Beats Solo Buds", false),
    BEATS_SOLO_PRO(0x0C20, "Beats Solo Pro", true, splitBuds = false),
    BEATS_STUDIO_BUDS(0x1120, "Beats Studio Buds", true),
    BEATS_STUDIO_BUDS_PLUS(0x1620, "Beats Studio Buds +", true),
    BEATS_STUDIO_PRO(0x1720, "Beats Studio Pro", true, splitBuds = false),
    BEATS_X(0x0520, "BeatsX", false),
    UNKNOWN(-1, "AirPods", true),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: Int): PodModel = byId[id] ?: UNKNOWN
    }
}

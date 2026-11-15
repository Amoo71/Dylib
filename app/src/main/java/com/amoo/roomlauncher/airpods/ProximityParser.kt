package com.amoo.roomlauncher.airpods

/**
 * Wertet Apples BLE-Werbepaket "Proximity Pairing" (Typ 0x07, 27 Byte) aus,
 * das AirPods ständig aussenden. Logik nach OpenPods / AirPodsDesktop.
 */
object ProximityParser {
    data class Info(
        val model: String,
        val left: Int?, val right: Int?, val case: Int?,
        val chargingLeft: Boolean, val chargingRight: Boolean, val chargingCase: Boolean,
        val leftInEar: Boolean, val rightInEar: Boolean,
    )

    fun parse(d: ByteArray): Info? {
        if (d.size != 27 || d[0] != 0x07.toByte()) return null
        fun nib(i: Int): Int {
            val b = d[i / 2].toInt() and 0xFF
            return if (i % 2 == 0) b shr 4 else b and 0x0F
        }
        // Welcher Ohrhörer "primär" ist, bestimmt die Reihenfolge der Werte.
        val flip = (nib(10) and 0x02) == 0
        val l = nib(if (flip) 12 else 13)
        val r = nib(if (flip) 13 else 12)
        val c = nib(15)
        val chg = nib(14)
        fun pct(v: Int): Int? = if (v in 0..10) v * 10 else null
        // Byte 5: Status – 0x02 primärer AirPod im Ohr, 0x08 sekundärer im Ohr.
        val status = d[5].toInt() and 0xFF
        val primaryIn = status and 0x02 != 0
        val secondaryIn = status and 0x08 != 0
        return Info(
            model = modelName(d[3].toInt() and 0xFF),
            left = pct(l), right = pct(r), case = pct(c),
            chargingLeft = chg and (if (flip) 0x02 else 0x01) != 0,
            chargingRight = chg and (if (flip) 0x01 else 0x02) != 0,
            chargingCase = chg and 0x04 != 0,
            leftInEar = if (flip) primaryIn else secondaryIn,
            rightInEar = if (flip) secondaryIn else primaryIn,
        )
    }

    private fun modelName(id: Int): String = when (id) {
        0x02 -> "AirPods"
        0x0F -> "AirPods (2. Gen.)"
        0x13 -> "AirPods (3. Gen.)"
        0x19 -> "AirPods 4"
        0x1B -> "AirPods 4 (ANC)"
        0x0E -> "AirPods Pro"
        0x14, 0x24 -> "AirPods Pro 2"
        0x27 -> "AirPods Pro 3"
        0x0A, 0x1F -> "AirPods Max"
        else -> "AirPods"
    }
}

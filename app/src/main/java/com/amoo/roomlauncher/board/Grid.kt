package com.amoo.roomlauncher.board

/** Ein belegter Bereich im Raster: Spalte/Zeile oben links, Breite/Höhe in Zellen. */
data class Slot(val id: String, val col: Int, val row: Int, val w: Int, val h: Int) {
    fun overlaps(c: Int, r: Int, ww: Int, hh: Int): Boolean =
        c < col + w && col < c + ww && r < row + h && row < r + hh
}

/**
 * Freies Raster wie auf dem iPhone, aber ohne Nachrutschen: Elemente bleiben genau dort,
 * wo sie abgelegt werden – Lücken bleiben Lücken. Reine Logik (ohne Android), damit sie getestet werden kann.
 */
object Grid {
    /** Passt ein [w]×[h]-Element an [col]/[row], ohne etwas zu überdecken ([ignoreId] = das Element selbst)? */
    fun fits(slots: List<Slot>, ignoreId: String?, col: Int, row: Int, w: Int, h: Int, columns: Int): Boolean {
        if (col < 0 || row < 0 || col + w > columns) return false
        return slots.none { it.id != ignoreId && it.overlaps(col, row, w, h) }
    }

    /** Anzahl belegter Zeilen. */
    fun usedRows(slots: List<Slot>, ignoreId: String? = null): Int =
        slots.filter { it.id != ignoreId }.maxOfOrNull { it.row + it.h } ?: 0

    /** Erster freier Platz (zeilenweise von oben links) – für neu hinzugefügte Elemente. */
    fun firstFree(slots: List<Slot>, w: Int, h: Int, columns: Int): Pair<Int, Int> {
        val ww = w.coerceIn(1, columns)
        var row = 0
        while (true) {
            for (col in 0..columns - ww) if (fits(slots, null, col, row, ww, h, columns)) return col to row
            row++
        }
    }

    /**
     * Nächster freier Platz zum Wunschort (Ablegen beim Ziehen). Gesucht wird in den vorhandenen Zeilen
     * und einer Zeile darunter (so kann das Board nach unten wachsen); bei Gleichstand gewinnt die gleiche Zeile.
     */
    fun nearestFree(
        slots: List<Slot>,
        id: String,
        wantCol: Int,
        wantRow: Int,
        w: Int,
        h: Int,
        columns: Int,
        minRows: Int,
    ): Pair<Int, Int> {
        val ww = w.coerceIn(1, columns)
        val maxRow = maxOf(minRows - h, usedRows(slots, id))
        val c0 = wantCol.coerceIn(0, columns - ww)
        val r0 = wantRow.coerceIn(0, maxRow)
        if (fits(slots, id, c0, r0, ww, h, columns)) return c0 to r0
        var best: Pair<Int, Int>? = null
        var bestD = Int.MAX_VALUE
        for (r in 0..maxRow) {
            for (c in 0..columns - ww) {
                if (!fits(slots, id, c, r, ww, h, columns)) continue
                val dc = c - c0
                val dr = r - r0
                val d = dc * dc * 4 + dr * dr * 5 // Zeilen etwas "teurer": lieber in derselben Zeile bleiben
                if (d < bestD) {
                    bestD = d
                    best = c to r
                }
            }
        }
        return best ?: firstFree(slots.filter { it.id != id }, ww, h, columns)
    }

    /** Alte Reihenfolge-Listen in feste Plätze übersetzen (dichtes Packen, nur einmal beim Umstieg). */
    fun pack(sizes: List<Pair<Int, Int>>, columns: Int): List<Pair<Int, Int>> {
        val placed = ArrayList<Slot>()
        return sizes.mapIndexed { i, (w, h) ->
            val ww = w.coerceIn(1, columns)
            val pos = firstFree(placed, ww, h, columns)
            placed += Slot("#$i", pos.first, pos.second, ww, h)
            pos
        }
    }
}

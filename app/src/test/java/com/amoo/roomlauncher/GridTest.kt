package com.amoo.roomlauncher

import com.amoo.roomlauncher.board.Grid
import com.amoo.roomlauncher.board.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Freies Raster: Elemente bleiben, wo sie abgelegt werden – Lücken bleiben. */
class GridTest {
    private val clock = Slot("clock", 0, 0, 4, 2)
    private val light = Slot("light", 4, 0, 2, 2)
    private val app = Slot("app", 7, 3, 1, 1)
    private val board = listOf(clock, light, app)

    @Test
    fun dropIntoGapStaysThere() {
        // App mitten in eine Lücke ganz unten links ziehen → genau dort
        assertEquals(0 to 3, Grid.nearestFree(board, "app", 0, 3, 1, 1, 8, 4))
        // Lücke rechts oben neben dem Licht
        assertEquals(6 to 0, Grid.nearestFree(board, "app", 6, 0, 1, 1, 8, 4))
    }

    @Test
    fun dropOnOccupiedGoesToNearestFree() {
        // App auf die Uhr fallen lassen (2/1) → nächster freier Platz, nicht ans Ende gepackt
        val (c, r) = Grid.nearestFree(board, "app", 2, 1, 1, 1, 8, 4)
        assertTrue(Grid.fits(board, "app", c, r, 1, 1, 8))
        assertEquals(2, r) // direkt unter der Uhr
    }

    @Test
    fun widgetCannotLeaveGridOrOverlap() {
        // Licht (2×2) ganz nach rechts außen → bleibt im Raster
        assertEquals(6 to 0, Grid.nearestFree(board, "light", 9, 0, 2, 2, 8, 4))
        assertFalse(Grid.fits(board, "light", 3, 0, 2, 2, 8))
        assertTrue(Grid.fits(board, "light", 0, 2, 2, 2, 8))
    }

    @Test
    fun firstFreeFillsFromTopLeft() {
        assertEquals(6 to 0, Grid.firstFree(board, 1, 1, 8))
        assertEquals(6 to 0, Grid.firstFree(board, 2, 2, 8))
        assertEquals(0 to 2, Grid.firstFree(board, 4, 2, 8))
    }

    @Test
    fun boardCanGrowOneRow() {
        // Volles Raster (4 Zeilen) → Ablegen in Zeile 4 erlaubt (Board wächst nach unten)
        val full = (0 until 4).flatMap { r -> (0 until 8).map { c -> Slot("a$c$r", c, r, 1, 1) } } + Slot("x", 0, 9, 1, 1)
        assertEquals(3 to 4, Grid.nearestFree(full, "x", 3, 6, 1, 1, 8, 4))
    }

    @Test
    fun packMigratesOldOrder() {
        val pos = Grid.pack(listOf(4 to 2, 2 to 2, 1 to 1, 1 to 1, 1 to 1), 8)
        assertEquals(listOf(0 to 0, 4 to 0, 6 to 0, 7 to 0, 6 to 1), pos)
    }
}

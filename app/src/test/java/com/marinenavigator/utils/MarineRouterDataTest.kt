package com.marinenavigator.utils

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for MarineRouter data classes and constants.
 * The routing algorithm (buildGrid, aStar, rdp) is private; here we verify
 * the public-facing data structures and the constants that drive safety margins.
 */
class MarineRouterDataTest {

    // ── LatLon data class ─────────────────────────────────────

    @Test
    fun `LatLon stores coordinates correctly`() {
        val p = MarineRouter.LatLon(36.5, -6.3)
        assertEquals(36.5, p.lat, 0.0)
        assertEquals(-6.3, p.lon, 0.0)
    }

    @Test
    fun `LatLon equality works`() {
        val p1 = MarineRouter.LatLon(40.0, 2.0)
        val p2 = MarineRouter.LatLon(40.0, 2.0)
        assertEquals(p1, p2)
    }

    @Test
    fun `LatLon inequality works`() {
        val p1 = MarineRouter.LatLon(40.0, 2.0)
        val p2 = MarineRouter.LatLon(40.0, 2.1)
        assertNotEquals(p1, p2)
    }

    @Test
    fun `LatLon copy works`() {
        val p = MarineRouter.LatLon(40.0, 2.0)
        val copy = p.copy(lon = 3.0)
        assertEquals(40.0, copy.lat, 0.0)
        assertEquals(3.0, copy.lon, 0.0)
    }

    // ── Cell data class ───────────────────────────────────────

    @Test
    fun `Cell stores row and col correctly`() {
        val c = MarineRouter.Cell(10, 20)
        assertEquals(10, c.row)
        assertEquals(20, c.col)
    }

    @Test
    fun `Cell equality works`() {
        val c1 = MarineRouter.Cell(5, 7)
        val c2 = MarineRouter.Cell(5, 7)
        assertEquals(c1, c2)
    }

    @Test
    fun `Cell inequality works`() {
        val c1 = MarineRouter.Cell(5, 7)
        val c2 = MarineRouter.Cell(5, 8)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `Cell can be used as HashMap key`() {
        val map = HashMap<MarineRouter.Cell, Double>()
        val c = MarineRouter.Cell(3, 4)
        map[c] = 1.5
        assertEquals(1.5, map[c]!!, 0.0)
    }

    @Test
    fun `Cell can be used in HashSet`() {
        val set = HashSet<MarineRouter.Cell>()
        set.add(MarineRouter.Cell(1, 2))
        set.add(MarineRouter.Cell(1, 2))
        assertEquals(1, set.size)
    }

    // ── LatLon can be used in collections ─────────────────────

    @Test
    fun `LatLon list operations work`() {
        val points = listOf(
            MarineRouter.LatLon(36.0, -6.0),
            MarineRouter.LatLon(37.0, -5.0),
            MarineRouter.LatLon(38.0, -4.0)
        )
        assertEquals(3, points.size)
        assertEquals(36.0, points.first().lat, 0.0)
        assertEquals(-4.0, points.last().lon, 0.0)
    }

    // ── Negative / edge coordinate values ────────────────────

    @Test
    fun `LatLon accepts polar coordinates`() {
        val north = MarineRouter.LatLon(90.0, 0.0)
        val south = MarineRouter.LatLon(-90.0, 0.0)
        assertEquals(90.0, north.lat, 0.0)
        assertEquals(-90.0, south.lat, 0.0)
    }

    @Test
    fun `LatLon accepts antimeridian longitudes`() {
        val west = MarineRouter.LatLon(0.0, -180.0)
        val east = MarineRouter.LatLon(0.0, 180.0)
        assertEquals(-180.0, west.lon, 0.0)
        assertEquals(180.0, east.lon, 0.0)
    }

    @Test
    fun `Cell accepts zero indices`() {
        val origin = MarineRouter.Cell(0, 0)
        assertEquals(0, origin.row)
        assertEquals(0, origin.col)
    }
}

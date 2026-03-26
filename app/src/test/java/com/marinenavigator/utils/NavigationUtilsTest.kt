package com.marinenavigator.utils

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NavigationUtilsTest {

    // ── distanceMeters ────────────────────────────────────────

    @Test
    fun `distanceMeters same point returns zero`() {
        val d = NavigationUtils.distanceMeters(40.0, -3.0, 40.0, -3.0)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun `distanceMeters known distance equator 1 degree longitude ~111km`() {
        // 1 grado de longitud en el ecuador ≈ 111.319 km
        val d = NavigationUtils.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_319.0, d, 500.0)
    }

    @Test
    fun `distanceMeters known distance 1 degree latitude ~111km`() {
        // Haversine con radio medio de la Tierra 6.371.000 m → 1° lat ≈ 111.195 m
        val d = NavigationUtils.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 500.0)
    }

    @Test
    fun `distanceMeters is symmetric`() {
        val d1 = NavigationUtils.distanceMeters(36.5, -6.3, 43.4, 3.7)
        val d2 = NavigationUtils.distanceMeters(43.4, 3.7, 36.5, -6.3)
        assertEquals(d1, d2, 0.001)
    }

    @Test
    fun `distanceMeters Barcelona to Palma approximately 180nm`() {
        // Barcelona (41.38, 2.17) → Palma (39.57, 2.65) ≈ 200 km
        val d = NavigationUtils.distanceMeters(41.38, 2.17, 39.57, 2.65)
        assertTrue("Expected ~200km, got ${d/1000} km", d in 190_000.0..210_000.0)
    }

    // ── bearingDegrees ────────────────────────────────────────

    @Test
    fun `bearingDegrees due north returns 0`() {
        val b = NavigationUtils.bearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0f, b, 0.5f)
    }

    @Test
    fun `bearingDegrees due south returns 180`() {
        val b = NavigationUtils.bearingDegrees(1.0, 0.0, 0.0, 0.0)
        assertEquals(180.0f, b, 0.5f)
    }

    @Test
    fun `bearingDegrees due east returns 90`() {
        val b = NavigationUtils.bearingDegrees(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0f, b, 0.5f)
    }

    @Test
    fun `bearingDegrees due west returns 270`() {
        val b = NavigationUtils.bearingDegrees(0.0, 1.0, 0.0, 0.0)
        assertEquals(270.0f, b, 0.5f)
    }

    @Test
    fun `bearingDegrees result always in range 0 to 360`() {
        val coords = listOf(
            Pair(Pair(10.0, 20.0), Pair(5.0, 15.0)),
            Pair(Pair(-10.0, -20.0), Pair(10.0, 20.0)),
            Pair(Pair(0.0, 0.0), Pair(-1.0, -1.0))
        )
        for ((from, to) in coords) {
            val b = NavigationUtils.bearingDegrees(from.first, from.second, to.first, to.second)
            assertTrue("Bearing $b out of [0,360)", b >= 0f && b < 360f)
        }
    }

    // ── etaSeconds ────────────────────────────────────────────

    @Test
    fun `etaSeconds with zero speed returns MAX_VALUE`() {
        assertEquals(Long.MAX_VALUE, NavigationUtils.etaSeconds(1000.0, 0.0f))
    }

    @Test
    fun `etaSeconds with very low speed returns MAX_VALUE`() {
        assertEquals(Long.MAX_VALUE, NavigationUtils.etaSeconds(1000.0, 0.4f))
    }

    @Test
    fun `etaSeconds 1852m at 1 knot returns 3600s`() {
        // 1 milla náutica a 1 nudo = 1 hora = 3600 s
        val eta = NavigationUtils.etaSeconds(1852.0, 1.0f)
        assertTrue("Expected ~3600s, got $eta", eta in 3595L..3605L)
    }

    @Test
    fun `etaSeconds 3704m at 2 knots returns 3600s`() {
        val eta = NavigationUtils.etaSeconds(3704.0, 2.0f)
        assertTrue("Expected ~3600s, got $eta", eta in 3590L..3610L)
    }

    // ── formatDuration ────────────────────────────────────────

    @Test
    fun `formatDuration MAX_VALUE returns double dash`() {
        assertEquals("--", NavigationUtils.formatDuration(Long.MAX_VALUE))
    }

    @Test
    fun `formatDuration negative returns double dash`() {
        assertEquals("--", NavigationUtils.formatDuration(-1L))
    }

    @Test
    fun `formatDuration 45 seconds`() {
        assertEquals("45s", NavigationUtils.formatDuration(45L))
    }

    @Test
    fun `formatDuration 90 seconds shows minutes and seconds`() {
        assertEquals("1m 30s", NavigationUtils.formatDuration(90L))
    }

    @Test
    fun `formatDuration 3600 seconds shows 1 hour`() {
        assertEquals("1h 0m", NavigationUtils.formatDuration(3600L))
    }

    @Test
    fun `formatDuration 3754 seconds shows hours and minutes`() {
        assertEquals("1h 2m", NavigationUtils.formatDuration(3754L))
    }

    @Test
    fun `formatDuration zero seconds`() {
        assertEquals("0s", NavigationUtils.formatDuration(0L))
    }

    // ── formatDistance ────────────────────────────────────────

    @Test
    fun `formatDistance below 1nm shows meters`() {
        val s = NavigationUtils.formatDistance(500.0)
        assertEquals("500 m", s)
    }

    @Test
    fun `formatDistance exactly 1852m shows 1_0 mn`() {
        val s = NavigationUtils.formatDistance(1852.0)
        assertEquals("1.0 mn", s)
    }

    @Test
    fun `formatDistance 5556m shows 3_0 mn`() {
        val s = NavigationUtils.formatDistance(5556.0)
        assertEquals("3.0 mn", s)
    }

    @Test
    fun `formatDistance 1000m shows meters`() {
        val s = NavigationUtils.formatDistance(1000.0)
        assertEquals("1000 m", s)
    }

    // ── msToKnots ─────────────────────────────────────────────

    @Test
    fun `msToKnots 0 returns 0`() {
        assertEquals(0.0f, NavigationUtils.msToKnots(0.0f), 0.0001f)
    }

    @Test
    fun `msToKnots 0_514444 returns approximately 1 knot`() {
        assertEquals(1.0f, NavigationUtils.msToKnots(NavigationUtils.KNOTS_TO_MS), 0.001f)
    }

    @Test
    fun `msToKnots and KNOTS_TO_MS are inverse constants`() {
        val roundTrip = NavigationUtils.msToKnots(NavigationUtils.KNOTS_TO_MS)
        assertEquals(1.0f, roundTrip, 0.001f)
    }

    // ── totalDistanceMeters ───────────────────────────────────

    @Test
    fun `totalDistanceMeters empty list returns 0`() {
        assertEquals(0.0, NavigationUtils.totalDistanceMeters(emptyList()), 0.001)
    }

    @Test
    fun `totalDistanceMeters single point returns 0`() {
        assertEquals(0.0, NavigationUtils.totalDistanceMeters(listOf(Pair(0.0, 0.0))), 0.001)
    }

    @Test
    fun `totalDistanceMeters two points matches distanceMeters`() {
        val p1 = Pair(36.5, -6.3)
        val p2 = Pair(36.7, -6.1)
        val expected = NavigationUtils.distanceMeters(p1.first, p1.second, p2.first, p2.second)
        val total = NavigationUtils.totalDistanceMeters(listOf(p1, p2))
        assertEquals(expected, total, 0.001)
    }

    @Test
    fun `totalDistanceMeters three collinear points sums correctly`() {
        val p1 = Pair(0.0, 0.0)
        val p2 = Pair(0.0, 1.0)
        val p3 = Pair(0.0, 2.0)
        val d12 = NavigationUtils.distanceMeters(0.0, 0.0, 0.0, 1.0)
        val d23 = NavigationUtils.distanceMeters(0.0, 1.0, 0.0, 2.0)
        val total = NavigationUtils.totalDistanceMeters(listOf(p1, p2, p3))
        assertEquals(d12 + d23, total, 0.001)
    }

    // ── formatLatitude ────────────────────────────────────────

    @Test
    fun `formatLatitude positive returns N`() {
        val s = NavigationUtils.formatLatitude(36.5)
        assertTrue("Should contain N: $s", s.endsWith("N"))
    }

    @Test
    fun `formatLatitude negative returns S`() {
        val s = NavigationUtils.formatLatitude(-36.5)
        assertTrue("Should contain S: $s", s.endsWith("S"))
    }

    @Test
    fun `formatLatitude zero degrees`() {
        val s = NavigationUtils.formatLatitude(0.0)
        assertTrue(s.startsWith("00°"))
        assertTrue(s.endsWith("N"))
    }

    @Test
    fun `formatLatitude 36_5 degrees correct format`() {
        val s = NavigationUtils.formatLatitude(36.5)
        // 36° 30.000' N
        assertTrue("Got: $s", s.startsWith("36°"))
        assertTrue("Got: $s", s.endsWith("N"))
    }

    // ── formatLongitude ───────────────────────────────────────

    @Test
    fun `formatLongitude positive returns E`() {
        val s = NavigationUtils.formatLongitude(2.5)
        assertTrue("Should contain E: $s", s.endsWith("E"))
    }

    @Test
    fun `formatLongitude negative returns O`() {
        val s = NavigationUtils.formatLongitude(-6.3)
        assertTrue("Should contain O: $s", s.endsWith("O"))
    }

    @Test
    fun `formatLongitude uses 3 digit degrees`() {
        val s = NavigationUtils.formatLongitude(2.5)
        assertTrue("Should start with 3-digit degrees: $s", s.startsWith("002°"))
    }

    // ── midpoint ──────────────────────────────────────────────

    @Test
    fun `midpoint same point returns same point`() {
        val (lat, lon) = NavigationUtils.midpoint(40.0, -3.0, 40.0, -3.0)
        assertEquals(40.0, lat, 0.001)
        assertEquals(-3.0, lon, 0.001)
    }

    @Test
    fun `midpoint symmetric on equator`() {
        val (lat, lon) = NavigationUtils.midpoint(0.0, -10.0, 0.0, 10.0)
        assertEquals(0.0, lat, 0.001)
        assertEquals(0.0, lon, 0.001)
    }

    @Test
    fun `midpoint distance to each endpoint is equal`() {
        val lat1 = 36.5; val lon1 = -6.3
        val lat2 = 43.4; val lon2 = 3.7
        val (midLat, midLon) = NavigationUtils.midpoint(lat1, lon1, lat2, lon2)
        val d1 = NavigationUtils.distanceMeters(lat1, lon1, midLat, midLon)
        val d2 = NavigationUtils.distanceMeters(midLat, midLon, lat2, lon2)
        assertEquals(d1, d2, 100.0)
    }

    // ── bearingName ───────────────────────────────────────────

    @Test
    fun `bearingName 0 returns N`() {
        assertEquals("N", NavigationUtils.bearingName(0f))
    }

    @Test
    fun `bearingName 90 returns E`() {
        assertEquals("E", NavigationUtils.bearingName(90f))
    }

    @Test
    fun `bearingName 180 returns S`() {
        assertEquals("S", NavigationUtils.bearingName(180f))
    }

    @Test
    fun `bearingName 270 returns O`() {
        assertEquals("O", NavigationUtils.bearingName(270f))
    }

    @Test
    fun `bearingName 45 returns NE`() {
        assertEquals("NE", NavigationUtils.bearingName(45f))
    }

    @Test
    fun `bearingName 315 returns NO`() {
        assertEquals("NO", NavigationUtils.bearingName(315f))
    }

    @Test
    fun `bearingName 359_9 returns N`() {
        assertEquals("N", NavigationUtils.bearingName(359.9f))
    }

    @Test
    fun `bearingName 135 returns SE`() {
        assertEquals("SE", NavigationUtils.bearingName(135f))
    }

    @Test
    fun `bearingName 225 returns SO`() {
        assertEquals("SO", NavigationUtils.bearingName(225f))
    }
}

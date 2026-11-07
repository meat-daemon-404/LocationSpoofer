package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TerrainElevationModelTest {

    @Test
    fun `the same coordinate always gives the same baseline`() {
        val first = TerrainElevationModel.baselineMeters(30.274085, 120.155070)
        val second = TerrainElevationModel.baselineMeters(30.274085, 120.155070)
        assertEquals(first, second, 0.0)
    }

    @Test
    fun `baseline stays inside the configured range`() {
        var lat = -85.0
        while (lat <= 85.0) {
            var lng = -180.0
            while (lng <= 180.0) {
                val value = TerrainElevationModel.baselineMeters(lat, lng)
                assertTrue("lat=$lat lng=$lng value=$value", value >= TerrainElevationModel.MIN_M)
                assertTrue("lat=$lat lng=$lng value=$value", value <= TerrainElevationModel.MAX_M)
                lat += 0.0
                lng += 12.0
            }
            lat += 12.0
        }
    }

    @Test
    fun `baseline is not a global constant`() {
        val samples = ArrayList<Double>()
        var i = 0
        while (i < 400) {
            val lat = -60.0 + i * 0.3
            val lng = -170.0 + i * 0.85
            samples += TerrainElevationModel.baselineMeters(lat, lng)
            i++
        }
        assertTrue("distinct=${samples.toSet().size}", samples.toSet().size >= 300)
    }

    @Test
    fun `most places are low and only a few are high`() {
        val samples = ArrayList<Double>()
        var i = 0
        while (i < 2_000) {
            val lat = -55.0 + (i % 61) * 1.7
            val lng = -175.0 + (i / 61) * 3.1
            samples += TerrainElevationModel.baselineMeters(lat, lng)
            i++
        }
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size * 9) / 10]
        assertTrue("median=$median", median in 40.0..700.0)
        assertTrue("p90=$p90", p90 <= 1_800.0)
        assertTrue("max=${sorted.last()}", sorted.last() > 600.0)
    }

    @Test
    fun `nearby points do not jump by hundreds of meters`() {
        val base = TerrainElevationModel.baselineMeters(30.0, 120.0)
        assertTrue(abs(TerrainElevationModel.baselineMeters(30.01, 120.01) - base) < 120.0)
    }

    @Test
    fun `different cities usually differ`() {
        val a = TerrainElevationModel.baselineMeters(30.274085, 120.155070)
        val b = TerrainElevationModel.baselineMeters(39.9042, 116.4074)
        val c = TerrainElevationModel.baselineMeters(22.5431, 114.0579)
        assertTrue(a != b || b != c)
    }

    @Test
    fun `baseline is quantised to the resolution`() {
        var i = 0
        while (i < 50) {
            val value = TerrainElevationModel.baselineMeters(-30.0 + i * 1.3, 100.0 + i * 2.7)
            val steps = value / TerrainElevationModel.RESOLUTION_M
            assertEquals(Math.round(steps).toDouble(), steps, 1e-6)
            i++
        }
    }

    @Test
    fun `blank setting means auto while an explicit number does not`() {
        assertTrue(TerrainElevationModel.isAuto(null))
        assertTrue(TerrainElevationModel.isAuto(""))
        assertTrue(TerrainElevationModel.isAuto("   "))
        assertFalse(TerrainElevationModel.isAuto("0.0"))
        assertFalse(TerrainElevationModel.isAuto("120"))
    }

    @Test
    fun `non finite coordinates fall back to the lower bound`() {
        assertEquals(TerrainElevationModel.MIN_M, TerrainElevationModel.baselineMeters(Double.NaN, 120.0), 0.0)
        assertEquals(TerrainElevationModel.MIN_M, TerrainElevationModel.baselineMeters(30.0, Double.POSITIVE_INFINITY), 0.0)
    }
}

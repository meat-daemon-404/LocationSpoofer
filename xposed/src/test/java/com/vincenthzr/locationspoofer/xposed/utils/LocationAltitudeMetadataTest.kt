package com.vincenthzr.locationspoofer.xposed.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模拟 API 34 的 `Location`：带两个 remove 方法，并记录是否被调用。 */
class FakeLocationWithMsl {
    var mslCleared = false
    var mslAccuracyCleared = false

    fun removeMslAltitude() {
        mslCleared = true
    }

    fun removeMslAltitudeAccuracy() {
        mslAccuracyCleared = true
    }
}

/** 模拟 API 34 以下（或第三方 Location 子类）：没有 MSL 相关方法。 */
class FakeLocationWithoutMsl {
    var touched = false

    fun getLatitude(): Double {
        touched = true
        return 0.0
    }
}

class LocationAltitudeMetadataTest {

    @Test
    fun `clears both altitude fields when the platform supports them`() {
        val location = FakeLocationWithMsl()

        LocationAltitudeMetadata.clear(location)

        assertTrue(location.mslCleared)
        assertTrue(location.mslAccuracyCleared)
    }

    @Test
    fun `is a no-op on platforms without msl support`() {
        val location = FakeLocationWithoutMsl()

        // 不抛异常即为通过：API 34 以下没有这两个方法属预期情况
        LocationAltitudeMetadata.clear(location)

        assertFalse("不应触碰其它方法", location.touched)
    }

    @Test
    fun `tolerates null and partially supported objects`() {
        LocationAltitudeMetadata.clear(null)
        LocationAltitudeMetadata.clear(Any())

        val onlyMsl = object : Any() {
            var cleared = false
            fun removeMslAltitude() {
                cleared = true
            }
        }
        LocationAltitudeMetadata.clear(onlyMsl)
        assertTrue("缺 removeMslAltitudeAccuracy 时仍应清掉能清的那个", onlyMsl.cleared)
    }
}

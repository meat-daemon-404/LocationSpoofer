package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class NmeaRewriteTest {

    private fun sky(
        slots: IntArray,
        elevations: FloatArray = FloatArray(slots.size) { 30f + it },
        azimuths: FloatArray = FloatArray(slots.size) { (it * 37).toFloat() },
        usedInFix: BooleanArray = BooleanArray(slots.size) { it % 2 == 0 }
    ) = SatelliteModel.Sky(slots, elevations, azimuths, usedInFix)

    private fun body(sentence: String): String =
        sentence.removePrefix("$").substringBefore("*")

    private fun fields(sentence: String): List<String> = body(sentence).split(",")

    @Test
    fun `per constellation counts add up to the whole sky so gga never exceeds gsv`() {
        // GGA 的「参与解算颗数」是四个星座合起来算的。只有四个前缀的 GSV 加起来恰好等于整份天空，
        // 「用的比看到的多」才不可能出现。这里用真实模板 + 多组种子/时刻验证这个划分性质。
        assertEquals(4, NmeaRewrite.ALL_TALKERS.size)
        val seeds = listOf(0L, 42L, 1_000_003L, 12_345L, 987_654_321L)
        val instants = listOf(1_700_000_000_000L, 1_700_003_600_000L, 1_700_007_200_000L)
        for (seed in seeds) {
            for (atMs in instants) {
                val counts = SatelliteModel.counts(20, seed, atMs)
                val sky = SatelliteModel.sky(
                    counts, seed, atMs,
                    SatelliteCatalog.BASE_ELEVATIONS, SatelliteCatalog.BASE_AZIMUTHS
                )
                val all = NmeaRewrite.satellites(sky)

                val perTalker = NmeaRewrite.ALL_TALKERS.map { NmeaRewrite.ofTalker(all, it) }
                assertEquals(all.size, perTalker.sumOf { it.size })

                val inViewSum = perTalker.sumOf { it.size }
                val usedSum = perTalker.sumOf { group -> group.count { it.usedInFix } }
                assertEquals(counts.usedInFix, usedSum)
                // 这就是那个硬约束：参与解算的颗数永远不可能超过能数到的在视野颗数
                assertTrue(counts.usedInFix <= inViewSum)
                // 四个星座交错排列，所以任何窗口里四家都该有星：某家整组缺席会让
                // 「GGA 说 15 颗、GSV 里只有 GPS 一家的 1 颗」这种分布显得不可信
                assertTrue(perTalker.all { it.isNotEmpty() })
            }
        }
    }

    @Test
    fun `missing gsv pages are only produced on the real last page`() {
        // 真机只发 2 页、我们需要 3 页：只有第 2 页（真的最后一页）之后才补第 3 页
        assertEquals(listOf(3), NmeaRewrite.missingPages(2, 2, 3))
        assertEquals(emptyList<Int>(), NmeaRewrite.missingPages(2, 1, 3))
        // 页数够用 / 真机页更多时不补
        assertEquals(emptyList<Int>(), NmeaRewrite.missingPages(3, 3, 3))
        assertEquals(emptyList<Int>(), NmeaRewrite.missingPages(4, 4, 3))
        // 差得多时一次补足
        assertEquals(listOf(2, 3), NmeaRewrite.missingPages(1, 1, 3))
    }

    @Test
    fun `supplementary pages finish the constellation without repeating a satellite`() {
        // 取整份模板再按 GPS 过滤（模板里的星座是交错的，直接取前 12 个槽位会混进别的星座）
        val allSlots = IntArray(SatelliteCatalog.SIZE) { it }
        val all = NmeaRewrite.satellites(sky(allSlots))
        val gps = NmeaRewrite.ofTalker(all, "GP")
        assertEquals(12, gps.size)
        val pageCount = NmeaRewrite.gsvPageCount(gps.size)
        assertEquals(3, pageCount)

        // 真机只念了第 1 页（realTotal=1），补发第 2、3 页
        val missing = NmeaRewrite.missingPages(1, 1, pageCount)
        assertEquals(listOf(2, 3), missing)

        val prns = ArrayList<Int>()
        prns.addAll(gps.take(NmeaRewrite.GSV_PAGE_SIZE).map { it.prn })
        for (page in missing) {
            val chunk = gps.drop((page - 1) * NmeaRewrite.GSV_PAGE_SIZE)
                .take(NmeaRewrite.GSV_PAGE_SIZE)
            val parts = fields(NmeaRewrite.gsvSentence("GP", page, pageCount, gps.size, chunk))
            for (index in chunk.indices) {
                prns.add(parts[4 + index * 4].toInt())
            }
        }
        // 三页拼起来正好是整组，且没有重复
        assertEquals(gps.map { it.prn }, prns)
        assertEquals(prns.size, prns.toSet().size)
    }

    @Test
    fun `gsv pages cover every satellite exactly once`() {
        // 北斗 9 颗 -> 3 页（4+4+1）
        val slots = IntArray(9) { it }
        val all = NmeaRewrite.satellites(sky(slots))
        assertEquals(9, all.size)

        val pageCount = NmeaRewrite.gsvPageCount(all.size)
        assertEquals(3, pageCount)

        val seen = ArrayList<NmeaRewrite.Satellite>()
        for (page in 1..pageCount) {
            val chunk = all.drop((page - 1) * NmeaRewrite.GSV_PAGE_SIZE)
                .take(NmeaRewrite.GSV_PAGE_SIZE)
            val sentence = NmeaRewrite.gsvSentence("BD", page, pageCount, all.size, chunk)
            val parts = fields(sentence)
            assertEquals("BDGSV", parts[0])
            assertEquals(pageCount.toString(), parts[1])
            assertEquals(page.toString(), parts[2])
            // 「在视野内总数」每页都要重复，两位补零
            assertEquals("09", parts[3])
            // 4 个字段一颗卫星
            assertEquals(4 + chunk.size * 4, parts.size)
            for (index in chunk.indices) {
                val base = 4 + index * 4
                assertEquals(chunk[index].prn.toString(), parts[base].trimStart('0').ifEmpty { "0" })
                seen.add(chunk[index])
            }
        }
        assertEquals(all.map { it.prn }, seen.map { it.prn })
    }

    @Test
    fun `gsv keeps zero satellites out of the stream`() {
        // 一颗都没有时不该有报文（页面数 0，调用方据此丢弃整组）
        assertEquals(0, NmeaRewrite.gsvPageCount(0))
        assertEquals(1, NmeaRewrite.gsvPageCount(1))
        assertEquals(1, NmeaRewrite.gsvPageCount(4))
        assertEquals(2, NmeaRewrite.gsvPageCount(5))
    }

    @Test
    fun `sentences carry a correct checksum and crlf`() {
        val all = NmeaRewrite.satellites(sky(IntArray(4) { it }))
        val sentence = NmeaRewrite.gsvSentence("GP", 1, 1, all.size, all)
        assertTrue(sentence.startsWith("$"))
        assertTrue(sentence.endsWith("\r\n"))

        // 独立算一遍异或，确保校验和不是随手编的
        val payload = sentence.removePrefix("$").substringBefore("*")
        var xor = 0
        for (character in payload) xor = xor xor character.code
        val expected = String.format(java.util.Locale.US, "%02X", xor and 0xFF)
        assertEquals(expected, sentence.substringAfter("*").substringBefore("\r\n"))
        assertEquals(NmeaRewrite.checksum(payload), expected)
    }

    @Test
    fun `glonass prns live in the sixty five to ninety six band`() {
        // NMEA 里 GLONASS 用的是 65-96 这一段，把 1-24 的编号直接发出去会被判成不存在的卫星
        assertEquals(65, SatelliteCatalog.nmeaPrn(SatelliteCatalog.CONSTELLATION_GLONASS, 1))
        assertEquals(88, SatelliteCatalog.nmeaPrn(SatelliteCatalog.CONSTELLATION_GLONASS, 24))
        // 其它星座直接用星座内编号
        assertEquals(7, SatelliteCatalog.nmeaPrn(SatelliteCatalog.CONSTELLATION_GPS, 7))
        assertEquals(7, SatelliteCatalog.nmeaPrn(SatelliteCatalog.CONSTELLATION_BDS, 7))
        assertEquals(7, SatelliteCatalog.nmeaPrn(SatelliteCatalog.CONSTELLATION_GALILEO, 7))

        // 用真实模板跑一遍：GLONASS 的那些槽位出来的 PRN 必须都在 65..96
        val glonassSlots = (0 until SatelliteCatalog.SIZE)
            .filter { SatelliteCatalog.CONSTELLATION_TYPES[it] == SatelliteCatalog.CONSTELLATION_GLONASS }
            .toIntArray()
        assertTrue(glonassSlots.isNotEmpty())
        val satellites = NmeaRewrite.satellites(sky(glonassSlots))
        assertEquals(glonassSlots.size, satellites.size)
        assertTrue(satellites.all { it.prn in 65..96 })
        assertTrue(satellites.all { it.constellation == SatelliteCatalog.CONSTELLATION_GLONASS })
    }

    @Test
    fun `of talker keeps only that constellation and never invents one`() {
        val slots = IntArray(SatelliteCatalog.SIZE) { it }
        val all = NmeaRewrite.satellites(sky(slots))

        val bds = NmeaRewrite.ofTalker(all, "BD")
        val gps = NmeaRewrite.ofTalker(all, "GP")
        assertEquals(12, bds.size)
        assertEquals(12, gps.size)
        assertEquals(4, NmeaRewrite.ofTalker(all, "GL").size)
        assertEquals(4, NmeaRewrite.ofTalker(all, "GA").size)
        // 未知前缀按「全部」处理，而不是返回空（否则会把整组报文丢空）
        assertEquals(all.size, NmeaRewrite.ofTalker(all, "GN").size)
        assertEquals(all.size, NmeaRewrite.ofTalker(all, "XX").size)
        assertTrue(bds.all { it.constellation == SatelliteCatalog.CONSTELLATION_BDS })
    }

    @Test
    fun `gsa lists at most twelve used satellites and dop stays pythagorean`() {
        val slots = IntArray(SatelliteCatalog.SIZE) { it }
        val all = NmeaRewrite.satellites(sky(slots, usedInFix = BooleanArray(SatelliteCatalog.SIZE) { true }))
        val used = all.filter { it.usedInFix }
        assertEquals(32, used.size)

        val (pdop, hdop, vdop) = NmeaRewrite.dopFromAccuracy(4.6f)
        val sentence = NmeaRewrite.gsaSentence("BD", used, pdop, hdop, vdop)
        val parts = fields(sentence)

        assertEquals("BDGSA", parts[0])
        assertEquals("A", parts[1])
        assertEquals("3", parts[2])
        // A,3 + 12 个 PRN 槽 + 3 个 DOP
        assertEquals(18, parts.size)
        // 12 个槽必须都被填满（参与解算的卫星比 12 多）
        assertTrue((3..14).all { parts[it].isNotBlank() })
        // 多出来的卫星不能挤进来
        assertEquals(used.take(12).map { it.prn.toString() }, (3..14).map { parts[it].toInt().toString() })

        val parsed = parts.takeLast(3).map { it.toDouble() }
        assertTrue(abs(parsed[0] - sqrt(parsed[1] * parsed[1] + parsed[2] * parsed[2])) < 0.11)
    }

    @Test
    fun `gsa leaves unused satellite slots empty instead of filling them`() {
        val all = NmeaRewrite.satellites(sky(IntArray(3) { it }, usedInFix = BooleanArray(3) { true }))
        val (pdop, hdop, vdop) = NmeaRewrite.dopFromAccuracy(5.0f)
        val parts = fields(NmeaRewrite.gsaSentence("GP", all, pdop, hdop, vdop))
        // 前 3 个槽有值，第 4 个槽起必须是空字段
        assertTrue((3..5).all { parts[it].isNotBlank() })
        assertTrue((6..14).all { parts[it].isBlank() })
    }

    @Test
    fun `dop grows with the reported accuracy and stays physically ordered`() {
        val tight = NmeaRewrite.dopFromAccuracy(3.2f)
        val loose = NmeaRewrite.dopFromAccuracy(6.4f)
        assertTrue(loose.second > tight.second)
        // 垂直方向的几何永远比水平差：VDOP 必须大于 HDOP
        assertTrue(loose.third > loose.second)
        assertTrue(tight.third > tight.second)
        // 三个 DOP 都有上下界，精度再差也不会报出天文数字
        (0..40).forEach { index ->
            val (pdop, hdop, vdop) = NmeaRewrite.dopFromAccuracy(index * 0.5f)
            assertTrue(hdop in 0.6..6.0)
            assertTrue(vdop in 1.0..10.0)
            assertTrue(pdop >= vdop)
        }
    }

    @Test
    fun `satellite fields stay inside the integer ranges nmea allows`() {
        val slots = IntArray(SatelliteCatalog.SIZE) { it }
        val elevations = FloatArray(SatelliteCatalog.SIZE) { (it * 3).toFloat() }
        val azimuths = FloatArray(SatelliteCatalog.SIZE) { (it * 11).toFloat() }
        val all = NmeaRewrite.satellites(sky(slots, elevations, azimuths))
        all.forEach { satellite ->
            assertTrue(satellite.prn in 1..99)
            assertTrue(satellite.elevationDeg in 0..90)
            assertTrue(satellite.azimuthDeg in 0..359)
            assertTrue(satellite.cn0DbHz in 30..43)
        }
    }
}

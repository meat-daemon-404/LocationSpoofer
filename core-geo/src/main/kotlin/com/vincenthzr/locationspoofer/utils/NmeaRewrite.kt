package com.vincenthzr.locationspoofer.utils

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * NMEA-0183 的 GSA / GSV 报文重建。
 *
 * 这两类是卫星相关报文：GSV 逐颗列出「在视野内」的卫星号、仰角、方位和信噪比，GSA 列出
 * 「参与解算」的卫星号和 DOP 值。原来的实现只改写 GGA/RMC/GLL 三条，GSV/GSA 原样透传，
 * 于是应用解析 NMEA 看到的是**真实星座**，而同一时刻的 `GnssStatus` 与 `Location.extras`
 * 报的是我们伪造的那一套——同一个物理量在两处对不上，且真卫星号/几何完全暴露。
 *
 * 这里只做纯计算：输入是一份 `SatelliteModel.Sky` 快照，输出是可直接下发的完整句子
 * （含 `$` 前缀、`*XX` 校验和、`\r\n` 结尾）。
 */
object NmeaRewrite {

    /** GSV 每条报文最多携带 4 颗卫星（NMEA-0183 规定）。 */
    const val GSV_PAGE_SIZE = 4

    /** GSA 报文最多列 12 颗参与解算的卫星。 */
    const val GSA_MAX_SATELLITES = 12

    /** 一颗卫星在 NMEA 报文里需要的字段。 */
    data class Satellite(
        val constellation: Int,
        val prn: Int,
        val elevationDeg: Int,
        val azimuthDeg: Int,
        val cn0DbHz: Int,
        val usedInFix: Boolean
    )

    /**
     * 把天空快照摊平成 NMEA 需要的字段。
     *
     * 仰角/方位取整到度、信噪比取整到 dB-Hz：NMEA 里这三项本来就是整数场，
     * 发小数会被严格解析器判为非法。
     */
    fun satellites(sky: SatelliteModel.Sky): List<Satellite> {
        val list = ArrayList<Satellite>(sky.size)
        for (i in 0 until sky.size) {
            val slot = sky.slot[i]
            val constellation = SatelliteCatalog.CONSTELLATION_TYPES[slot]
            val elevation = sky.elevations[i].toInt().coerceIn(0, 90)
            val azimuth = sky.azimuths[i].toInt().coerceIn(0, 359)
            // 与 GnssStatus 通道同一条算式：34 + 仰角/90*8，落在 30~43.5 dB-Hz
            val cn0 = (34f + (sky.elevations[i] / 90f) * 8f).toInt().coerceIn(30, 43)
            list.add(
                Satellite(
                    constellation = constellation,
                    prn = SatelliteCatalog.nmeaPrn(constellation, SatelliteCatalog.SVIDS[slot]),
                    elevationDeg = elevation,
                    azimuthDeg = azimuth,
                    cn0DbHz = cn0,
                    usedInFix = sky.usedInFix[i]
                )
            )
        }
        return list
    }

    /**
     * GSV/GSA 会出现的四个会话前缀。
     *
     * `GL` 是 GLONASS，`BD`/`GB` 都是北斗（老固件用 GB），`GA` 是 Galileo。
     */
    val ALL_TALKERS: List<String> = listOf("GP", "GL", "BD", "GA")

    /** 取某个 NMEA 会话前缀对应的卫星（`GP`/`GL`/`BD`/`GA`；`GN` 视为全部）。 */
    fun ofTalker(all: List<Satellite>, talker: String): List<Satellite> {
        val constellation = when (talker.uppercase()) {
            "GP" -> SatelliteCatalog.CONSTELLATION_GPS
            "GL" -> SatelliteCatalog.CONSTELLATION_GLONASS
            "BD", "GB" -> SatelliteCatalog.CONSTELLATION_BDS
            "GA" -> SatelliteCatalog.CONSTELLATION_GALILEO
            else -> 0
        }
        if (constellation == 0) return all
        return all.filter { it.constellation == constellation }
    }

    /** 给定卫星数需要发几条 GSV 报文（0 颗时返回 0，调用方据此丢弃整组）。 */
    fun gsvPageCount(inViewCount: Int): Int =
        if (inViewCount <= 0) 0 else ceil(inViewCount.toDouble() / GSV_PAGE_SIZE).toInt()

    /**
     * 真机发出的页数可能比我们这份卫星表需要的页数少。
     *
     * 这时光把头部页数改大是不行的：头部写着「共 3 页」而第 3 页永远不出现，任何按页聚合的
     * 解析器都会停在半个周期上（真机自己永远不会这样）。返回真机最后一页之后还需要补发的页号，
     * 由调用方在真机的最后一页之后再补几条。
     */
    fun missingPages(realTotalPages: Int, realPageIndex: Int, ourPageCount: Int): List<Int> {
        if (realPageIndex != realTotalPages) return emptyList()
        if (ourPageCount <= realTotalPages) return emptyList()
        return (realTotalPages + 1..ourPageCount).toList()
    }

    /**
     * 重建一条 GSV。
     *
     * @param pageIndex 1 基页号
     * @param pageCount 该组总页数
     * @param inViewCount 该星座在视野内的总数（每条报文都要重复，解析器用它做完整性校验）
     * @param page 本页携带的卫星（最多 4 颗）
     */
    fun gsvSentence(
        talker: String,
        pageIndex: Int,
        pageCount: Int,
        inViewCount: Int,
        page: List<Satellite>
    ): String {
        val body = StringBuilder()
        body.append(talker.uppercase()).append("GSV,")
        body.append(pageCount).append(',')
        body.append(pageIndex).append(',')
        body.append(String.format(java.util.Locale.US, "%02d", inViewCount))
        for (satellite in page.take(GSV_PAGE_SIZE)) {
            body.append(',')
            body.append(String.format(java.util.Locale.US, "%02d", satellite.prn))
            body.append(',').append(String.format(java.util.Locale.US, "%03d", satellite.elevationDeg))
            body.append(',').append(String.format(java.util.Locale.US, "%03d", satellite.azimuthDeg))
            body.append(',').append(String.format(java.util.Locale.US, "%02d", satellite.cn0DbHz))
        }
        return wrap(body.toString())
    }

    /**
     * 重建一条 GSA。
     *
     * 定位模式固定 `A`（自动），定位类型固定 `3`（3D fix）—— 我们给的是一条有海拔的 3D 解，
     * 报 2D 会和 `Location.hasAltitude()` 自相矛盾。
     */
    fun gsaSentence(
        talker: String,
        usedInFix: List<Satellite>,
        pdop: Double,
        hdop: Double,
        vdop: Double
    ): String {
        val body = StringBuilder()
        body.append(talker.uppercase()).append("GSA,A,3")
        val prns = usedInFix.take(GSA_MAX_SATELLITES)
        for (index in 0 until GSA_MAX_SATELLITES) {
            body.append(',')
            val satellite = prns.getOrNull(index)
            if (satellite != null) {
                body.append(String.format(java.util.Locale.US, "%02d", satellite.prn))
            }
        }
        body.append(',').append(String.format(java.util.Locale.US, "%.1f", pdop))
        body.append(',').append(String.format(java.util.Locale.US, "%.1f", hdop))
        body.append(',').append(String.format(java.util.Locale.US, "%.1f", vdop))
        return wrap(body.toString())
    }

    /**
     * 由水平精度反推 DOP 三兄弟。
     *
     * 真机上 HDOP ≈ 水平精度 / 5 m（URE 约 5 m），VDOP 约为水平方向的 1.7 倍（垂直方向几何更差），
     * PDOP 按 `PDOP² = HDOP² + VDOP²` 合成。这样 NMEA 里的 DOP 与 `Location.getAccuracy()`
     * 是同一个物理量的两种表达，不会互相打架。
     */
    fun dopFromAccuracy(accuracyM: Float): Triple<Double, Double, Double> {
        val hdop = (accuracyM / 5.0).toDouble().coerceIn(0.6, 6.0)
        val vdop = (hdop * 1.7).coerceIn(1.0, 10.0)
        val pdop = sqrt(hdop * hdop + vdop * vdop)
        return Triple(pdop, hdop, vdop)
    }

    /** NMEA 校验和：`$` 与 `*` 之间所有字符的异或，两位大写十六进制。 */
    fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return String.format(java.util.Locale.US, "%02X", value and 0xFF)
    }

    /** 补齐 `$` 前缀、`*XX` 校验和与 CRLF。 */
    fun wrap(body: String): String = "\$$body*${checksum(body)}\r\n"
}

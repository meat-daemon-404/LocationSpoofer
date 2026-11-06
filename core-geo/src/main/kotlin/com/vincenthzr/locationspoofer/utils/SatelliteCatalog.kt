package com.vincenthzr.locationspoofer.utils

/**
 * 多星座卫星模板目录。
 *
 * 这是 `SatelliteModel.sky()` 的输入模板：每个槽位固定一个「星座 + 该星座内的编号」，
 * 几何量（仰角/方位）的基值也在这里。槽位与编号的配对必须合法（GPS 1-32、GLONASS 1-24、
 * 北斗 1-35、Galileo 1-36），否则 GNSS 通道与 NMEA 报文会给出系统里根本不存在的卫星号。
 *
 * 之所以放在 core-geo：它同时被 GnssStatus 通道（`GnssFastMockEngine`）和 NMEA 通道
 * （`NmeaRewrite`）使用，只有一处定义才能保证两条通道的卫星号一致。
 */
object SatelliteCatalog {

    /** 与 `android.location.GnssStatus.CONSTELLATION_*` 取值一致。 */
    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_BDS = 5
    const val CONSTELLATION_GALILEO = 6

    const val SIZE = 32

    /**
     * 槽位 → 星座。
     *
     * **顺序是特意打散的**：`SatelliteModel.sky()` 按会话种子在模板上取一段连续窗口，
     * 如果把同一星座的槽位挨在一起，某些会话的窗口就会整段落在某个星座里 —— 出现
     * 「GPS 只剩 1 颗可见、北斗 14 颗」这种在亚太也说不通的分布，而且 GGA 的颗数
     * 与 GSV 的分星座颗数会明显失衡。四个星座交错排列后，任何窗口都能取到全部四家。
     */
    val CONSTELLATION_TYPES: IntArray = intArrayOf(
        // 轮转：GPS / 北斗 / GLONASS / Galileo ...
        1, 5, 3, 6,
        1, 5, 3, 6,
        1, 5, 3, 6,
        1, 5, 3, 6,
        // 剩下的 GPS 与北斗交替
        1, 5, 1, 5, 1, 5, 1, 5, 1, 5, 1, 5, 1, 5, 1, 5
    )

    /** 槽位 → 该星座内的编号（SVID）。 */
    val SVIDS: IntArray = intArrayOf(
        // 轮转段
        1, 1, 1, 1,
        3, 2, 2, 2,
        6, 3, 3, 3,
        7, 5, 4, 5,
        // 交替段
        8, 6, 9, 7, 11, 8, 14, 9, 17, 10, 19, 11, 21, 13, 22, 14
    )

    /** 槽位 → 基准仰角（度）。 */
    val BASE_ELEVATIONS: FloatArray = floatArrayOf(
        82f, 78f, 55f, 60f,
        69f, 65f, 43f, 48f,
        51f, 54f, 35f, 39f,
        44f, 42f, 28f, 22f,
        36f, 38f, 75f, 72f,
        63f, 58f, 47f, 49f,
        31f, 33f, 59f, 61f,
        41f, 45f, 25f, 29f
    )

    /** 槽位 → 基准方位角（度）。 */
    val BASE_AZIMUTHS: FloatArray = floatArrayOf(
        35f, 12f, 85f, 45f,
        68f, 45f, 175f, 135f,
        95f, 78f, 265f, 225f,
        132f, 112f, 345f, 315f,
        165f, 145f, 198f, 178f,
        232f, 212f, 265f, 245f,
        298f, 278f, 325f, 312f,
        15f, 335f, 52f, 25f
    )

    /**
     * NMEA 会话前缀里表示星座的两个字母（`$GPGSV` 的 `GP`）。
     * 真机按星座各发一组 GSA/GSV，前缀错了比不伪装更显眼。
     */
    fun nmeaTalker(constellation: Int): String = when (constellation) {
        CONSTELLATION_GPS -> "GP"
        CONSTELLATION_GLONASS -> "GL"
        CONSTELLATION_BDS -> "BD"
        CONSTELLATION_GALILEO -> "GA"
        else -> "GN"
    }

    /**
     * 星座内编号 → NMEA 报文里使用的卫星号（PRN）。
     *
     * GLONASS 在 NMEA-0183 里用的是 65-96 这一段（1-24 的编号要加 64），这是最容易写错的一处：
     * 直接把 SVID 当 PRN 发出去，解码端会认为收到了根本不存在的卫星。
     */
    fun nmeaPrn(constellation: Int, svid: Int): Int =
        if (constellation == CONSTELLATION_GLONASS) svid + 64 else svid

    /** 星座的中文名，只用于日志与文档，不参与计算。 */
    fun displayName(constellation: Int): String = when (constellation) {
        CONSTELLATION_GPS -> "GPS"
        CONSTELLATION_GLONASS -> "GLONASS"
        CONSTELLATION_BDS -> "北斗"
        CONSTELLATION_GALILEO -> "Galileo"
        else -> "UNKNOWN"
    }
}

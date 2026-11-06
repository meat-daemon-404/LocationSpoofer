package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.utils.SatelliteCatalog
import com.vincenthzr.locationspoofer.utils.SatelliteModel
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 高性能零分配卫星模拟引擎 (High-Performance Zero-Allocation GNSS/GPS Simulation Engine)
 *
 * 核心优化特性:
 * 1. 单例反射预热与句柄缓存: 仅在首次使用时初始化 Method / Field 引用，彻底消除高频回调中的反射查找。
 * 2. 零 GC 预计算轨道矩阵: 预分配多星座结构体，基于时间戳步进计算平滑轨道与可信 C/N0，不再每秒创建数百个 Random 实例。
 * 3. 1Hz 原子快照缓存: 无论系统 GNSS 回调触发频率多高 (10Hz~20Hz)，均以纳秒级极速直接复用 1 秒内的预构建快照对象。
 * 4. 全版本覆盖: 深度兼容 Android 11+ (GnssStatus.Builder), Android 7~10 (GnssStatus 内部结构), 以及 Legacy GpsStatus。
 */
object GnssFastMockEngine {

    private const val MAX_SATELLITES = 32

    // 预置平滑微扰动表 (循环索引，避免 Math.random() 开销)
    private val JITTER_TABLE = floatArrayOf(
        -0.8f, 0.5f, 1.2f, -0.3f, 0.9f, -1.1f, 0.4f, -0.6f,
        1.1f, -0.9f, 0.7f, -0.4f, 1.3f, -1.2f, 0.3f, -0.2f
    )

    // 缓存区 (零 GC 复用)
    @Volatile
    private var cachedGnssStatusObj: Any? = null
    @Volatile
    private var lastGnssStatusTimeMs: Long = 0L
    @Volatile
    private var cachedGnssCount: Int = 0
    @Volatile
    private var cachedGnssSeed: Long = Long.MIN_VALUE

    @Volatile
    private var cachedGpsSatelliteList: List<Any>? = null
    @Volatile
    private var lastGpsSatellitesTimeMs: Long = 0L
    @Volatile
    private var cachedGpsSatCount: Int = 0
    @Volatile
    private var cachedGpsSeed: Long = Long.MIN_VALUE

    // 反射句柄缓存
    private class ClassReflectionHolder(val classLoader: ClassLoader) {
        var isInitialized = false

        // Android 11+ GnssStatus.Builder
        var builderClass: Class<*>? = null
        var builderConstructor: Constructor<*>? = null
        var addSatelliteMethod: Method? = null
        var buildMethod: Method? = null

        // Android 7-10 GnssStatus Direct Fields
        var gnssStatusClass: Class<*>? = null
        var countField: Field? = null
        var svidWithFlagsField: Field? = null
        var cn0DbHzField: Field? = null
        var elevationsField: Field? = null
        var azimuthsField: Field? = null
        var carrierFrequenciesField: Field? = null
        var basebandCn0DbHzsField: Field? = null

        // GpsSatellite
        var gpsSatelliteClass: Class<*>? = null
        var gpsSatelliteConstructor: Constructor<*>? = null
        var satValidField: Field? = null
        var satHasEphemerisField: Field? = null
        var satHasAlmanacField: Field? = null
        var satUsedInFixField: Field? = null
        var satSnrField: Field? = null
        var satElevationField: Field? = null
        var satAzimuthField: Field? = null

        fun init() {
            if (isInitialized) return
            synchronized(this) {
                if (isInitialized) return

                // 1. GnssStatus.Builder (Android 11+)
                try {
                    builderClass = XposedHelpers.findClassIfExists(
                        "android.location.GnssStatus\$Builder",
                        classLoader
                    )
                    if (builderClass != null) {
                        builderConstructor = builderClass!!.getDeclaredConstructor().apply { isAccessible = true }
                        addSatelliteMethod = builderClass!!.getDeclaredMethod(
                            "addSatellite",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType
                        ).apply { isAccessible = true }
                        buildMethod = builderClass!!.getDeclaredMethod("build").apply { isAccessible = true }
                    }
                } catch (e: Throwable) {
                    // Ignored on older Android
                }

                // 2. GnssStatus Fields (Android 7-10)
                try {
                    gnssStatusClass = XposedHelpers.findClassIfExists("android.location.GnssStatus", classLoader)
                    if (gnssStatusClass != null) {
                        countField = gnssStatusClass!!.getDeclaredField("mSvCount").apply { isAccessible = true }
                        svidWithFlagsField = gnssStatusClass!!.getDeclaredField("mSvidWithFlags").apply { isAccessible = true }
                        cn0DbHzField = gnssStatusClass!!.getDeclaredField("mCn0DbHz").apply { isAccessible = true }
                        elevationsField = gnssStatusClass!!.getDeclaredField("mElevations").apply { isAccessible = true }
                        azimuthsField = gnssStatusClass!!.getDeclaredField("mAzimuths").apply { isAccessible = true }
                        try { carrierFrequenciesField = gnssStatusClass!!.getDeclaredField("mCarrierFrequencies").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { basebandCn0DbHzsField = gnssStatusClass!!.getDeclaredField("mBasebandCn0DbHzs").apply { isAccessible = true } } catch (_: Throwable) {}
                    }
                } catch (e: Throwable) {
                }

                // 3. GpsSatellite
                try {
                    gpsSatelliteClass = XposedHelpers.findClassIfExists("android.location.GpsSatellite", classLoader)
                    if (gpsSatelliteClass != null) {
                        gpsSatelliteConstructor = gpsSatelliteClass!!.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
                        try { satValidField = gpsSatelliteClass!!.getDeclaredField("mValid").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satHasEphemerisField = gpsSatelliteClass!!.getDeclaredField("mHasEphemeris").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satHasAlmanacField = gpsSatelliteClass!!.getDeclaredField("mHasAlmanac").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satUsedInFixField = gpsSatelliteClass!!.getDeclaredField("mUsedInFix").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satSnrField = gpsSatelliteClass!!.getDeclaredField("mSnr").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satElevationField = gpsSatelliteClass!!.getDeclaredField("mElevation").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satAzimuthField = gpsSatelliteClass!!.getDeclaredField("mAzimuth").apply { isAccessible = true } } catch (_: Throwable) {}
                    }
                } catch (e: Throwable) {
                }

                isInitialized = true
            }
        }
    }

    private val reflectionHolders = ConcurrentHashMap<ClassLoader, ClassReflectionHolder>()

    private fun getReflectionHolder(classLoader: ClassLoader): ClassReflectionHolder {
        val holder = reflectionHolders.getOrPut(classLoader) { ClassReflectionHolder(classLoader) }
        if (!holder.isInitialized) {
            holder.init()
        }
        return holder
    }

    /**
     * Android 7~10 的 onSvStatusChanged 六数组签名所需的原始数组。
     * 与 GnssStatus / GpsStatus 路径共用同一份 [SatelliteModel.sky]，保证三条通道的数量、仰角、
     * 方位、参与解算标志完全一致。
     */
    class SvArrays(
        val size: Int,
        val svidWithFlags: IntArray,
        val cn0DbHz: FloatArray,
        val elevations: FloatArray,
        val azimuths: FloatArray,
        val carrierFrequencies: FloatArray
    )

    /** 由卫星天空模型构造 Android 7~10 六数组派发参数 */
    fun buildSvArrays(counts: SatelliteModel.Counts, seed: Long, enableJitter: Boolean = true): SvArrays {
        val now = System.currentTimeMillis()
        val timeStep = (now / 1000L).toInt()
        val sky = SatelliteModel.sky(counts, seed, now, SatelliteCatalog.BASE_ELEVATIONS, SatelliteCatalog.BASE_AZIMUTHS)
        val n = sky.size
        val svidWithFlags = IntArray(n)
        val cn0DbHz = FloatArray(n)
        val elevations = FloatArray(n)
        val azimuths = FloatArray(n)
        val carrierFrequencies = FloatArray(n)
        for (i in 0 until n) {
            val slot = sky.slot[i]
            val type = SatelliteCatalog.CONSTELLATION_TYPES[slot]
            val elevation = sky.elevations[i]
            val jitter = if (enableJitter) JITTER_TABLE[(timeStep + i) and 15] else 0f
            val cn0 = (34f + (elevation / 90f) * 8f + jitter).coerceIn(30f, 43.5f)
            // 低 8 位标志: 0x1 有历书 / 0x2 有星历 / 0x4 参与定位解算
            svidWithFlags[i] = (SatelliteCatalog.SVIDS[slot] shl 4) or 0x03 or (if (sky.usedInFix[i]) 0x04 else 0)
            cn0DbHz[i] = cn0
            elevations[i] = elevation
            azimuths[i] = sky.azimuths[i]
            carrierFrequencies[i] = when (type) {
                5 -> 1561098000f
                3 -> 1602000000f
                else -> 1575420000f
            }
        }
        return SvArrays(n, svidWithFlags, cn0DbHz, elevations, azimuths, carrierFrequencies)
    }

    /**
     * 极速获取或创建 GnssStatus 快照对象
     * 有效期内直接返回原子缓存，耗时 < 0.01ms (纳秒级)
     */
    fun getOrCreateSpoofedGnssStatus(
        classLoader: ClassLoader,
        counts: SatelliteModel.Counts,
        seed: Long,
        enableJitter: Boolean,
        fallbackOriginalObj: Any?
    ): Any? {
        val now = System.currentTimeMillis()
        val count = counts.inView.coerceIn(4, MAX_SATELLITES)

        // 1 秒内命中快照直接返回
        val cached = cachedGnssStatusObj
        if (cached != null && cachedGnssCount == count && cachedGnssSeed == seed && now - lastGnssStatusTimeMs < 1000L) {
            return cached
        }

        synchronized(this) {
            // Double check
            if (cachedGnssStatusObj != null && cachedGnssCount == count && cachedGnssSeed == seed && now - lastGnssStatusTimeMs < 1000L) {
                return cachedGnssStatusObj
            }

            val holder = getReflectionHolder(classLoader)
            val timeStep = (now / 1000L)
            // 星座天顶分布与卫星计数共用同一份模型：数量、仰角、是否参与解算互相自洽
            val sky = SatelliteModel.sky(counts, seed, now, SatelliteCatalog.BASE_ELEVATIONS, SatelliteCatalog.BASE_AZIMUTHS)

            // 方案 A: Android 11+ Builder 极速构建 (全反射句柄已缓存)
            if (holder.builderConstructor != null && holder.addSatelliteMethod != null && holder.buildMethod != null) {
                try {
                    val builder = holder.builderConstructor!!.newInstance()
                    val addMethod = holder.addSatelliteMethod!!

                    for (i in 0 until sky.size) {
                        val slot = sky.slot[i]
                        val type = SatelliteCatalog.CONSTELLATION_TYPES[slot]
                        val svid = SatelliteCatalog.SVIDS[slot]
                        val elevation = sky.elevations[i]
                        val azimuth = sky.azimuths[i]

                        val jitter = if (enableJitter) JITTER_TABLE[(timeStep.toInt() + i) and 15] else 0f
                        // 真实满格强信号典型值: 34~43 dB-Hz
                        val cn0 = (34f + (elevation / 90f) * 8f + jitter).coerceIn(30f, 43.5f)
                        val usedInFix = sky.usedInFix[i]

                        // 真实载波频率: GPS L1 (1575.42 MHz), BDS B1I (1561.098 MHz), GLONASS (1602.0 MHz)
                        val carrierFreq = when (type) {
                            5 -> 1561098000f
                            3 -> 1602000000f
                            6 -> 1575420000f
                            else -> 1575420000f
                        }
                        val basebandCn0 = (cn0 - 2.5f).coerceAtLeast(26f)

                        addMethod.invoke(
                            builder,
                            type,
                            svid,
                            cn0,
                            elevation,
                            azimuth,
                            true,             // hasEphemeris
                            true,             // hasAlmanac
                            usedInFix,        // usedInFix
                            true,             // hasCarrierFrequency
                            carrierFreq,      // carrierFrequencyHz
                            true,             // hasBasebandCn0
                            basebandCn0       // basebandCn0DbHz
                        )
                    }

                    val newStatus = holder.buildMethod!!.invoke(builder)
                    cachedGnssStatusObj = newStatus
                    lastGnssStatusTimeMs = now
                    cachedGnssCount = count
                    cachedGnssSeed = seed
                    return newStatus
                } catch (e: Throwable) {
                    XposedBridge.log("[GnssFastMockEngine] Builder build failed: $e")
                }
            }

            // 方案 B: Android 7~10 字段直接写入 (零额外实例化)
            if (fallbackOriginalObj != null && holder.countField != null && holder.svidWithFlagsField != null) {
                try {
                    holder.countField!!.setInt(fallbackOriginalObj, count)

                    val cn0DbHzs = FloatArray(count)
                    val elevations = FloatArray(count)
                    val azimuths = FloatArray(count)
                    val svidWithFlags = IntArray(count)
                    val carrierFreqs = FloatArray(count)
                    val basebandCn0s = FloatArray(count)

                    for (i in 0 until sky.size) {
                        val slot = sky.slot[i]
                        val type = SatelliteCatalog.CONSTELLATION_TYPES[slot]
                        val svid = SatelliteCatalog.SVIDS[slot]
                        val elevation = sky.elevations[i]
                        val azimuth = sky.azimuths[i]
                        val jitter = if (enableJitter) JITTER_TABLE[(timeStep.toInt() + i) and 15] else 0f
                        val cn0 = (34f + (elevation / 90f) * 8f + jitter).coerceIn(30f, 43.5f)
                        val usedInFix = sky.usedInFix[i]

                        val carrierFreq = when (type) {
                            5 -> 1561098000f
                            3 -> 1602000000f
                            6 -> 1575420000f
                            else -> 1575420000f
                        }

                        cn0DbHzs[i] = cn0
                        elevations[i] = elevation
                        azimuths[i] = azimuth
                        carrierFreqs[i] = carrierFreq
                        basebandCn0s[i] = cn0 - 2.5f

                        var flags = 1 or 2 // hasEphemeris | hasAlmanac
                        if (usedInFix) flags = flags or 4
                        svidWithFlags[i] = (svid shl 8) or (type and 0xF) or (flags shl 4)
                    }

                    holder.svidWithFlagsField!!.set(fallbackOriginalObj, svidWithFlags)
                    holder.cn0DbHzField?.set(fallbackOriginalObj, cn0DbHzs)
                    holder.elevationsField?.set(fallbackOriginalObj, elevations)
                    holder.azimuthsField?.set(fallbackOriginalObj, azimuths)
                    holder.carrierFrequenciesField?.set(fallbackOriginalObj, carrierFreqs)
                    holder.basebandCn0DbHzsField?.set(fallbackOriginalObj, basebandCn0s)

                    cachedGnssStatusObj = fallbackOriginalObj
                    lastGnssStatusTimeMs = now
                    cachedGnssCount = count
                    cachedGnssSeed = seed
                    return fallbackOriginalObj
                } catch (e: Throwable) {
                    XposedBridge.log("[GnssFastMockEngine] Fallback field injection failed: $e")
                }
            }
        }

        return fallbackOriginalObj
    }

    /**
     * 极速获取或创建 GpsSatellite 列表 (适配旧版 GpsStatus.getSatellites() / DevCheck)
     */
    fun getOrCreateSpoofedGpsSatellites(classLoader: ClassLoader, counts: SatelliteModel.Counts, seed: Long): Iterable<Any> {
        val now = System.currentTimeMillis()
        val count = counts.inView.coerceIn(4, MAX_SATELLITES)

        val cached = cachedGpsSatelliteList
        if (cached != null && cachedGpsSatCount == count && cachedGpsSeed == seed && now - lastGpsSatellitesTimeMs < 1000L) {
            return cached
        }

        synchronized(this) {
            if (cachedGpsSatelliteList != null && cachedGpsSatCount == count && cachedGpsSeed == seed && now - lastGpsSatellitesTimeMs < 1000L) {
                return cachedGpsSatelliteList!!
            }

            val holder = getReflectionHolder(classLoader)
            if (holder.gpsSatelliteConstructor == null) {
                return emptyList()
            }

            val timeStep = (now / 1000L)
            val sky = SatelliteModel.sky(counts, seed, now, SatelliteCatalog.BASE_ELEVATIONS, SatelliteCatalog.BASE_AZIMUTHS)
            val list = ArrayList<Any>(sky.size)

            try {
                for (i in 0 until sky.size) {
                    val slot = sky.slot[i]
                    val type = SatelliteCatalog.CONSTELLATION_TYPES[slot]
                    val rawSvid = SatelliteCatalog.SVIDS[slot]
                    val prn = if (type == 3) rawSvid + 64 else rawSvid

                    val sat = holder.gpsSatelliteConstructor!!.newInstance(prn)
                    val elevation = sky.elevations[i]
                    val azimuth = sky.azimuths[i]
                    val jitter = JITTER_TABLE[(timeStep.toInt() + i) and 15]
                    val snr = (34f + (elevation / 90f) * 8f + jitter).coerceIn(30f, 43.5f)
                    val usedInFix = sky.usedInFix[i]

                    holder.satValidField?.setBoolean(sat, true)
                    holder.satHasEphemerisField?.setBoolean(sat, true)
                    holder.satHasAlmanacField?.setBoolean(sat, true)
                    holder.satUsedInFixField?.setBoolean(sat, usedInFix)
                    holder.satSnrField?.set(sat, snr)
                    holder.satElevationField?.set(sat, elevation)
                    holder.satAzimuthField?.set(sat, azimuth)

                    list.add(sat)
                }

                cachedGpsSatelliteList = list
                lastGpsSatellitesTimeMs = now
                cachedGpsSatCount = count
                cachedGpsSeed = seed
                return list
            } catch (e: Throwable) {
                XposedBridge.log("[GnssFastMockEngine] createSpoofedGpsSatellites failed: $e")
                return emptyList()
            }
        }
    }
}

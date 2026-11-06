@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName",
    "OPT_IN_USAGE",
    "unused",
    "UnusedImport"
)

package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.*
import com.vincenthzr.locationspoofer.xposed.hooks.*
import com.vincenthzr.locationspoofer.utils.NmeaRewrite
import com.vincenthzr.locationspoofer.utils.SatelliteCatalog
import com.vincenthzr.locationspoofer.utils.SatelliteModel
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.*
import kotlin.math.*
import io.github.libxposed.api.*

/**
 * 一条 NMEA 会话里「这一秒哪些星座的几何报文已经发过了」的记账。
 *
 * 存在的理由：GGA 里的「参与解算颗数」是四个星座合起来算的，而 GSV 是按星座分页发的。
 * 如果目标应用只收到 GP 的 GSV（真机此时可能根本没发 BD/GL 的 GSV），它把这些 GSV 加起来
 * 只有 12 颗，却看到 GGA 说 15 颗参与解算 —— 「用的比看到的多」，这是任何解析器都能一眼
 * 挑出来的硬矛盾。所以在 GGA 这一刻把还没出现过的星座补齐。
 */
private object NmeaCycle {
    private val lock = Any()
    private var second: Long = Long.MIN_VALUE
    private val emitted = java.util.HashSet<String>()
    private val synthesized = java.util.HashSet<String>()

    /** 进入某一秒；跨秒时清账。 */
    fun enterSecond(nowMs: Long): Long {
        val current = nowMs / 1000L
        synchronized(lock) {
            if (current != second) {
                second = current
                emitted.clear()
                synthesized.clear()
            }
        }
        return current
    }

    fun markEmitted(talker: String) {
        synchronized(lock) { emitted.add(talker.uppercase()) }
    }

    fun markSynthesized(talker: String) {
        synchronized(lock) {
            emitted.add(talker.uppercase())
            synthesized.add(talker.uppercase())
        }
    }

    /** 这一秒还没发过几何报文的星座前缀。 */
    fun pending(talkers: List<String>): List<String> {
        synchronized(lock) { return talkers.filter { it.uppercase() !in emitted } }
    }

    /** 这一秒的报文是我们自己合成出来的：真机后续再发同一星座就该丢掉，否则一页会来两次。 */
    fun isSynthesized(talker: String): Boolean {
        synchronized(lock) { return talker.uppercase() in synthesized }
    }
}

/**
 * 同一秒内的天空快照缓存。
 *
 * GSV 一个星座要发好几页，几页之间必须看到**同一份**卫星表，否则每页的「在视野内总数」
 * 逐页抖动、同一个卫星号在两页里出现不同仰角，任何解析器都能一眼看出来。
 */
private object NmeaSnapshot {
    @Volatile
    private var cachedKey: Long = Long.MIN_VALUE

    @Volatile
    private var cachedSatellites: List<NmeaRewrite.Satellite> = emptyList()

    fun get(config: JSONObject): List<NmeaRewrite.Satellite> {
        val now = System.currentTimeMillis()
        val seed = config.optLong("start_timestamp", 0L)
        // 秒级分桶 + 会话种子一起做键
        val key = (now / 1000L) xor (seed * 31)
        val cached = cachedSatellites
        if (key == cachedKey && cached.isNotEmpty()) return cached
        synchronized(this) {
            if (key == cachedKey && cachedSatellites.isNotEmpty()) return cachedSatellites
            val counts = RouteEngine.realisticSatelliteCounts(config, now)
            val sky = SatelliteModel.sky(
                counts,
                seed,
                now,
                SatelliteCatalog.BASE_ELEVATIONS,
                SatelliteCatalog.BASE_AZIMUTHS
            )
            val built = NmeaRewrite.satellites(sky)
            cachedSatellites = built
            cachedKey = key
            return built
        }
    }
}

/**
 * NMEA 报文改写：GGA/RMC/GLL 就地修补字段，GSA/GSV 整条重建（卫星号/几何/DOP 全部来自
 * 与 GnssStatus 通道同一份 `SatelliteModel.Sky` 快照）。
 */
internal fun LocationHooker.createOnNmeaMessageListenerProxy(
    original: Any,
    classLoader: ClassLoader
): Any {
    val interfaceClass = classLoader.loadClass("android.location.OnNmeaMessageListener")
    val proxy = Proxy.newProxyInstance(
        classLoader,
        arrayOf(interfaceClass),
        object : InvocationHandler {
            override fun invoke(
                proxy: Any,
                method: Method,
                args: Array<out Any>?
            ): Any? {
                if (method.name == "onNmeaMessage" && args != null && args.size >= 1) {
                    val originalMsg = args[0] as? String
                    if (originalMsg != null) {
                        val spoofedMsg = spoofNmeaMessage(originalMsg)
                        if (spoofedMsg == null) return null // 允许丢弃消息
                        val newArgs = arrayOfNulls<Any>(args.size)
                        for (i in args.indices) {
                            newArgs[i] = if (i == 0) spoofedMsg else args[i]
                        }
                        val result = method.invoke(original, *newArgs)
                        // 这一秒还没发过几何报文的星座补齐：GGA 的颗数与 GSV 的颗数必须能对上
                        emitPendingConstellationSentences(original, method, args, 0)
                        // 真机页数不够时补齐我们这份卫星表剩下的页，否则头部页数永远等不到
                        emitMissingGsvPages(original, method, args, originalMsg, 0)
                        return result
                    }
                }
                val methodArgs =
                    if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                return method.invoke(original, *methodArgs)
            }
        }
    )
    // startNmeaGsvInjector(original, "onNmeaMessage", classLoader)
    return proxy
}

internal fun LocationHooker.createGpsStatusNmeaListenerProxy(
    original: Any,
    classLoader: ClassLoader
): Any {
    val interfaceClass = classLoader.loadClass("android.location.GpsStatus\$NmeaListener")
    val proxy = Proxy.newProxyInstance(
        classLoader,
        arrayOf(interfaceClass),
        object : InvocationHandler {
            override fun invoke(
                proxy: Any,
                method: Method,
                args: Array<out Any>?
            ): Any? {
                if (method.name == "onNmeaReceived" && args != null && args.size >= 2) {
                    val originalMsg = args[1] as? String
                    if (originalMsg != null) {
                        val spoofedMsg = spoofNmeaMessage(originalMsg)
                        if (spoofedMsg == null) return null // 允许丢弃消息
                        val newArgs = arrayOfNulls<Any>(args.size)
                        for (i in args.indices) {
                            newArgs[i] = if (i == 1) spoofedMsg else args[i]
                        }
                        val result = method.invoke(original, *newArgs)
                        emitPendingConstellationSentences(original, method, args, 1)
                        emitMissingGsvPages(original, method, args, originalMsg, 1)
                        return result
                    }
                }
                val methodArgs =
                    if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                return method.invoke(original, *methodArgs)
            }
        }
    )
    // startNmeaGsvInjector(original, "onNmeaReceived", classLoader)
    return proxy
}

internal fun LocationHooker.spoofNmeaMessage(sentence: String): String? {
    try {
        val config = readConfig() ?: return sentence
        if (!config.optBoolean("active", false)) return sentence

        val motion = RouteEngine.calculateCurrentPosition(config)
        val targetCoords = getAppTargetCoordinate(motion.lat, motion.lng, config, "WGS-84")
        val targetLat = targetCoords.first
        val targetLng = targetCoords.second

        val parts = sentence.split("*")
        val mainPart = parts[0]
        val fields = mainPart.split(",").toMutableList()
        if (fields.isEmpty()) return sentence

        val type = fields[0]
        var modified = false

        if (type.endsWith("RMC") && fields.size >= 7) {
            val (latStr, latDir) = convertToNmeaLatitude(targetLat)
            val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
            fields[2] = "A" // Status Active / Valid
            fields[3] = latStr
            fields[4] = latDir
            fields[5] = lngStr
            fields[6] = lngDir
            if (fields.size > 7) {
                val knots = motion.speed * 1.943844f
                fields[7] = String.format(java.util.Locale.US, "%.2f", knots)
            }
            if (fields.size > 8) {
                fields[8] = String.format(java.util.Locale.US, "%.1f", motion.bearing)
            }
            modified = true
        } else if (type.endsWith("GGA") && fields.size >= 6) {
            val (latStr, latDir) = convertToNmeaLatitude(targetLat)
            val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
            fields[2] = latStr
            fields[3] = latDir
            fields[4] = lngStr
            fields[5] = lngDir
            if (fields.size > 6) fields[6] = "1" // GPS Fix (Quality indicator)
            if (fields.size > 7) fields[7] = RouteEngine.realisticSatelliteCounts(config).usedInFix.toString() // Number of satellites
            if (fields.size > 8) {
                // HDOP 不能是常量：它与 Location.getAccuracy() 是同一个物理量的两种表达
                val hdop = NmeaRewrite.dopFromAccuracy(getJitteredAccuracy()).second
                fields[8] = String.format(java.util.Locale.US, "%.1f", hdop)
            }
            if (fields.size > 9) fields[9] = String.format(java.util.Locale.US, "%.1f", RouteEngine.realisticAltitude(config))
            modified = true
        } else if (type.endsWith("GLL") && fields.size >= 5) {
            val (latStr, latDir) = convertToNmeaLatitude(targetLat)
            val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
            fields[1] = latStr
            fields[2] = latDir
            fields[3] = lngStr
            fields[4] = lngDir
            if (fields.size > 6) fields[6] = "A"
            modified = true
        } else if (type.endsWith("GSV") && fields.size >= 4) {
            // 整条重建：卫星号/仰角/方位/信噪比全部换成我们这份快照里对应星座的那一颗
            val talker = type.take(2)
            NmeaCycle.enterSecond(System.currentTimeMillis())
            // 这个星座的报文这一秒已经由我们补齐过，真机的再来一份就是重复页
            if (NmeaCycle.isSynthesized(talker)) return null
            NmeaCycle.markEmitted(talker)
            val pageIndex = fields[2].toIntOrNull() ?: return sentence
            val group = NmeaRewrite.ofTalker(NmeaSnapshot.get(config), talker)
            val pageCount = NmeaRewrite.gsvPageCount(group.size)
            // 我们的总数比真机少时，多出来的页要整条丢弃：留着就是零卫星的非法报文
            if (pageIndex < 1 || pageCount == 0 || pageIndex > pageCount) return null
            val page = group.drop((pageIndex - 1) * NmeaRewrite.GSV_PAGE_SIZE)
                .take(NmeaRewrite.GSV_PAGE_SIZE)
            return NmeaRewrite.gsvSentence(talker, pageIndex, pageCount, group.size, page)
        } else if (type.endsWith("GSA") && fields.size >= 3) {
            // 整条重建：PRN 列表与 DOP 三兄弟都跟着我们的卫星表与精度走
            val talker = type.take(2)
            NmeaCycle.enterSecond(System.currentTimeMillis())
            if (NmeaCycle.isSynthesized(talker)) return null
            NmeaCycle.markEmitted(talker)
            val usedInFix = NmeaRewrite.ofTalker(NmeaSnapshot.get(config), talker)
                .filter { it.usedInFix }
            val (pdop, hdop, vdop) = NmeaRewrite.dopFromAccuracy(getJitteredAccuracy())
            return NmeaRewrite.gsaSentence(talker, usedInFix, pdop, hdop, vdop)
        }

        if (!modified) return sentence

        val newMainPart = fields.joinToString(",")
        val newChecksum = calculateNmeaChecksum(newMainPart)

        val tail = if (parts.size > 1) {
            val rawTail = parts[1]
            val lineEnding = rawTail.substring(Math.min(2, rawTail.length))
            "*$newChecksum$lineEnding"
        } else {
            "*$newChecksum"
        }
        return newMainPart + tail
    } catch (e: Exception) {
        XposedBridge.log(e)
        return sentence
    }
}

/**
 * GSV 分页补齐：真机一个星座的页数可能比我们这份卫星表需要的少，
 * 光把头部页数改大是不够的（解析器会等一个永远不来的页）。规则：
 *   - 本页不是真机的最后一页：什么都不做，等下一页
 *   - 本页是真机最后一页、且我们这份表需要更多页：把剩下的页补发出去
 */
internal fun LocationHooker.emitMissingGsvPages(
    original: Any,
    method: Method,
    args: Array<out Any>,
    originalMsg: String,
    messageArgIndex: Int
) {
    val pages = syntheticGsvPages(originalMsg) ?: return
    for (sentence in pages) {
        val extraArgs = arrayOfNulls<Any>(args.size)
        for (i in args.indices) {
            extraArgs[i] = if (i == messageArgIndex) sentence else args[i]
        }
        try {
            method.invoke(original, *extraArgs)
        } catch (_: Throwable) {
            // 目标监听器抛异常与我们无关，别把真机自己的回调也带崩
        }
    }
}

/**
 * GGA 之后的补齐：把这一秒还没发送过几何报文的星座补上（GSA + 全部分页 GSV）。
 *
 * 这是让「GGA 的参与解算颗数」与「GSV 里数出来的在视野颗数」对得上的关键一步 ——
 * 真机可能只发 GP 的 GSV，而我们这份卫星表横跨四个星座，不补齐就会露出「用的比看到的多」。
 */
internal fun LocationHooker.emitPendingConstellationSentences(
    original: Any,
    method: Method,
    args: Array<out Any>,
    messageArgIndex: Int
) {
    val config = readConfig() ?: return
    if (!config.optBoolean("active", false)) return
    val now = System.currentTimeMillis()
    NmeaCycle.enterSecond(now)
    val all = NmeaSnapshot.get(config)
    val accuracy = getJitteredAccuracy()
    val (pdop, hdop, vdop) = NmeaRewrite.dopFromAccuracy(accuracy)
    for (talker in NmeaRewrite.ALL_TALKERS) {
        if (talker !in NmeaCycle.pending(NmeaRewrite.ALL_TALKERS.toList())) continue
        val group = NmeaRewrite.ofTalker(all, talker)
        if (group.isEmpty()) continue
        val used = group.filter { it.usedInFix }
        val sentences = ArrayList<String>(4)
        if (used.isNotEmpty()) {
            sentences.add(NmeaRewrite.gsaSentence(talker, used, pdop, hdop, vdop))
        }
        val pages = NmeaRewrite.gsvPageCount(group.size)
        for (page in 1..pages) {
            val chunk = group.drop((page - 1) * NmeaRewrite.GSV_PAGE_SIZE)
                .take(NmeaRewrite.GSV_PAGE_SIZE)
            sentences.add(NmeaRewrite.gsvSentence(talker, page, pages, group.size, chunk))
        }
        NmeaCycle.markSynthesized(talker)
        for (sentence in sentences) {
            val extraArgs = arrayOfNulls<Any>(args.size)
            for (i in args.indices) {
                extraArgs[i] = if (i == messageArgIndex) sentence else args[i]
            }
            try {
                if (method.parameterTypes.isEmpty()) {
                    method.invoke(original)
                } else {
                    method.invoke(original, *extraArgs)
                }
            } catch (_: Throwable) {
                // 目标监听器自己的问题，别把真机的回调链带崩
            }
        }
    }
}

/** 算出需要补发的 GSV 报文；不需要补发时返回 null。 */
internal fun LocationHooker.syntheticGsvPages(originalMsg: String): List<String>? {
    try {
        val config = readConfig() ?: return null
        if (!config.optBoolean("active", false)) return null
        val realFields = originalMsg.split("*")[0].split(",")
        if (realFields.size < 4) return null
        val type = realFields[0]
        if (!type.endsWith("GSV")) return null
        val realTotal = realFields[1].toIntOrNull() ?: return null
        val realPage = realFields[2].toIntOrNull() ?: return null
        val talker = type.take(2)
        val group = NmeaRewrite.ofTalker(NmeaSnapshot.get(config), talker)
        val ourPages = NmeaRewrite.gsvPageCount(group.size)
        val missing = NmeaRewrite.missingPages(realTotal, realPage, ourPages)
        if (missing.isEmpty()) return null
        return missing.map { page ->
            NmeaRewrite.gsvSentence(
                talker,
                page,
                ourPages,
                group.size,
                group.drop((page - 1) * NmeaRewrite.GSV_PAGE_SIZE)
                    .take(NmeaRewrite.GSV_PAGE_SIZE)
            )
        }
    } catch (e: Exception) {
        XposedBridge.log(e)
        return null
    }
}

internal fun LocationHooker.convertToNmeaLatitude(lat: Double): Pair<String, String> {
    val absLat = Math.abs(lat)
    val degrees = absLat.toInt()
    val minutes = (absLat - degrees) * 60.0
    val latStr = String.format(java.util.Locale.US, "%02d%08.5f", degrees, minutes)
    val dir = if (lat >= 0) "N" else "S"
    return Pair(latStr, dir)
}

internal fun LocationHooker.convertToNmeaLongitude(lng: Double): Pair<String, String> {
    val absLng = Math.abs(lng)
    val degrees = absLng.toInt()
    val minutes = (absLng - degrees) * 60.0
    val lngStr = String.format(java.util.Locale.US, "%03d%08.5f", degrees, minutes)
    val dir = if (lng >= 0) "E" else "W"
    return Pair(lngStr, dir)
}

internal fun LocationHooker.calculateNmeaChecksum(sentence: String): String {
    var checksum = 0
    val startIndex = if (sentence.startsWith("$")) 1 else 0
    val endIndex = sentence.indexOf('*')
    val limit = if (endIndex != -1) endIndex else sentence.length
    for (i in startIndex until limit) {
        checksum = checksum xor sentence[i].code
    }
    return String.format(java.util.Locale.US, "%02X", checksum)
}

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

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.vincenthzr.locationspoofer.utils.GaitTemplate
import com.vincenthzr.locationspoofer.utils.AttitudeModel
import com.vincenthzr.locationspoofer.utils.GeomagneticModel
import com.vincenthzr.locationspoofer.utils.SensorBiasModel
import com.vincenthzr.locationspoofer.utils.GyroscopeModel
import com.vincenthzr.locationspoofer.utils.StepEventTimeline
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.RouteEngine
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SensorStepHooker {

    data class CapturedSensorListener(
        val listener: Any,
        val sensor: Sensor?,
        val handler: Handler?
    )

    val capturedListeners = CopyOnWriteArrayList<CapturedSensorListener>()
    private val hookedListenerClasses = ConcurrentHashMap<Class<*>, Boolean>()

    private val noiseRng = java.util.Random()

    // 真实计步器自开机以来单调递增：换会话 / 摇杆每次写配置导致 start_timestamp 变化时，
    // 把已累计的步数接续为新的起点，而不是回到初始值。
    // 起始值带随机量：所有用户都从同一个常量起步，本身就是可跨账号聚合的指纹。
    private val bootStepsBase: Double = 2350.0 + noiseRng.nextInt(2400).toDouble()
    @Volatile private var stepBase: Double = bootStepsBase
    @Volatile private var lastStepsFloat: Double = bootStepsBase
    @Volatile private var lastStepInitTime: Long = 0L

    // STEP_DETECTOR 需要按真实步频"每步一个事件"派发；每个事件的时刻由连续步数的整数穿越点插值得出，
    // 步数积分则锚定单调时钟，墙钟被 NTP / 用户改动时不会让步数倒退或瞬跳
    @Volatile private var stepAnchorElapsedMs: Long = 0L
    @Volatile private var stepAnchorOffsetSec: Double = 0.0
    private val stepTimeline = StepEventTimeline()
    @Volatile private var cachedTemplateSource: String? = null
    @Volatile private var cachedTemplate: GaitTemplate? = null

    // 缓存虚拟 Sensor 实例
    private var mockStepCounterSensor: Sensor? = null
    private var mockStepDetectorSensor: Sensor? = null
    private var mockAccelerometerSensor: Sensor? = null
    private var mockPressureSensor: Sensor? = null
    private var mockGravitySensor: Sensor? = null
    private var mockLinearAccelSensor: Sensor? = null
    private var mockMagneticSensor: Sensor? = null
    private var mockRotationVectorSensor: Sensor? = null
    private var mockGyroUncalibratedSensor: Sensor? = null
    private var mockAccelUncalibratedSensor: Sensor? = null
    private var mockMagneticUncalibratedSensor: Sensor? = null
    private var mockGameRotationSensor: Sensor? = null

    // 线性加速度要与同一时刻的加速度计同源；跨派发节拍的旧快照不如不要
    private const val ACCEL_SNAPSHOT_TTL_MS = 200L

    @Volatile
    private var lastAccelSnapshot: FloatArray? = null

    @Volatile
    private var lastAccelSnapshotAtMs: Long = 0L

    // 跟踪 handle 到 sensor type 的映射
    private val handleToTypeMap = ConcurrentHashMap<Int, Int>()

    fun hookSensorStepSimulation(classLoader: ClassLoader) {
        val targetClasses = listOf(
            "android.hardware.SensorManager",
            "android.hardware.SystemSensorManager"
        )

        for (className in targetClasses) {
            val managerClass = XposedHelpers.findClassIfExists(className, classLoader) ?: continue

            // 1. Hook getDefaultSensor: 当设备无物理计步传感器时注入虚拟 Sensor
            try {
                XposedHelpers.hookAllMethods(managerClass, "getDefaultSensor") { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val type = (chain.args.firstOrNull() as? Int) ?: return@hookAllMethods result
                    if (result == null && isSimulatedSensorType(type)) {
                        return@hookAllMethods getOrCreateMockSensor(type, classLoader)
                    }
                    return@hookAllMethods result
                }
            } catch (_: Throwable) {}

            // 2. Hook getSensorList
            try {
                XposedHelpers.hookAllMethods(managerClass, "getSensorList") { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val type = (chain.args.firstOrNull() as? Int) ?: return@hookAllMethods result
                    if (isSimulatedSensorType(type)) {
                        if (result is List<*> && result.isEmpty()) {
                            val mock = getOrCreateMockSensor(type, classLoader)
                            if (mock != null) return@hookAllMethods listOf(mock)
                        }
                    }
                    return@hookAllMethods result
                }
            } catch (_: Throwable) {}

            // 3. Hook registerListener / registerListenerImpl
            val regMethods = listOf("registerListener", "registerListenerImpl")
            for (methodName in regMethods) {
                try {
                    XposedHelpers.hookAllMethods(managerClass, methodName) { chain, _ ->
                        val args = chain.args
                        var listener: Any? = null
                        var sensor: Sensor? = null
                        var handler: Handler? = null

                        for (arg in args) {
                            if (arg != null) {
                                if (arg is SensorEventListener || LocationHooker.hasTypeByName(arg.javaClass, "android.hardware.SensorEventListener")) {
                                    listener = arg
                                } else if (arg is Sensor) {
                                    sensor = arg
                                } else if (arg is Handler) {
                                    handler = arg
                                }
                            }
                        }

                        if (listener != null) {
                            val targetSensor = sensor ?: getOrCreateMockSensor(Sensor.TYPE_STEP_COUNTER, classLoader)
                            if (targetSensor != null && isCapturedSensorType(targetSensor.type)) {
                                val entry = CapturedSensorListener(listener, targetSensor, handler)
                                if (!capturedListeners.any { it.listener === listener && it.sensor?.type == targetSensor.type }) {
                                    capturedListeners.add(entry)
                                }

                                // 虚拟 Sensor 的 mHandle 是 0，映射进去会和 handle=0 的真实传感器撞车，
                                // 所以只为真实传感器登记 handle（气压计走"改写真实事件"而非"补发事件"路径）
                                val handle = getSensorHandle(targetSensor)
                                if (handle != null && targetSensor !== mockPressureSensor) {
                                    handleToTypeMap[handle] = targetSensor.type
                                }

                                // 动态 Hook 该 Listener 的具体实现类中的 onSensorChanged 方法
                                hookConcreteListenerClass(listener.javaClass, classLoader)
                            }
                        }

                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }

            // 4. Hook unregisterListener / unregisterListenerImpl
            val unregMethods = listOf("unregisterListener", "unregisterListenerImpl")
            for (methodName in unregMethods) {
                try {
                    XposedHelpers.hookAllMethods(managerClass, methodName) { chain, _ ->
                        val args = chain.args
                        val listener = args.firstOrNull { it != null && (it is SensorEventListener || LocationHooker.hasTypeByName(it.javaClass, "android.hardware.SensorEventListener")) }
                        if (listener != null) {
                            capturedListeners.removeAll { it.listener === listener }
                        }
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }
        }

        // 5. Hook SystemSensorManager$SensorEventQueue.dispatchSensorEvent 底层原生分发接口
        hookSensorEventQueue(classLoader)
    }

    private fun hookSensorEventQueue(classLoader: ClassLoader) {
        try {
            val eventQueueClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
                ?: XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$BaseEventQueue", classLoader)
                ?: return

            XposedHelpers.hookAllMethods(eventQueueClass, "dispatchSensorEvent") { chain, _ ->
                val config = (XposedHelpers.module as? LocationHooker)?.readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val handle = chain.args.firstOrNull() as? Int
                    val values = chain.args.getOrNull(1) as? FloatArray

                    if (handle != null && values != null) {
                        val sensorType = handleToTypeMap[handle]
                        val speed = config.optDouble("speed_m_s", 0.0)

                        if (sensorType == Sensor.TYPE_PRESSURE) {
                            // 气压计与步频模拟无关：静止时也照样按海拔换算，只看气压模拟开关
                            if (barometerEnabled(config)) {
                                applySyntheticPressure(values, config)
                            }
                        } else if (sensorType == Sensor.TYPE_GYROSCOPE && values.size >= 3) {
                            // 陀螺仪：真实值恒为"设备静止"，与 GPS 给出的速度直接矛盾；只在运动时改写
                            if (gyroscopeEnabled(config) && speed > 0.1) {
                                applySyntheticRotation(values, config, speed)
                            }
                        } else if (
                            (sensorType == Sensor.TYPE_GRAVITY || sensorType == Sensor.TYPE_LINEAR_ACCELERATION) &&
                            values.size >= 3
                        ) {
                            // 重力 / 线性加速度是加速度计的派生通道：加速度计改了而它们不改，
                            // 应用把两路一相减就能看出「原始读数在动、派生通道还是静止的」
                            if (config.optBoolean("enable_step_simulation", true) && speed > 0.1) {
                                if (sensorType == Sensor.TYPE_GRAVITY) {
                                    applySyntheticGravity(values, config)
                                } else {
                                    applySyntheticLinearAcceleration(values, config, speed)
                                }
                            }
                        } else if (isMagneticSensorType(sensorType) && values.size >= 3) {
                            // 磁力计与旋转矢量：真机读的是设备真实姿态，与"机头朝行进方向"矛盾
                            if (magneticSimulationEnabled(config) && speed > 0.1) {
                                if (sensorType == Sensor.TYPE_MAGNETIC_FIELD) {
                                    applySyntheticMagneticField(values, config)
                                } else {
                                    applySyntheticRotationVector(values, config)
                                }
                            }
                        } else if (isUncalibratedSensorType(sensorType) && values.size >= 6) {
                            // 未校准通道：系统保证"校准值 = 原始值 − 零偏"，只改校准侧会露馅
                            if (uncalibratedEnabled(config, sensorType!!) && speed > 0.1) {
                                applySyntheticUncalibrated(values, sensorType, config, speed)
                            }
                        } else if (config.optBoolean("enable_step_simulation", true)) {
                            if (sensorType == Sensor.TYPE_STEP_COUNTER) {
                                // 仅对明确判定的计步器传感器进行步数计算，严禁误判光感/距离传感器
                                val curSteps = calculateCurrentSteps(config)
                                values[0] = curSteps.toFloat()
                            } else if (sensorType == Sensor.TYPE_STEP_DETECTOR) {
                                if (speed > 0.1) {
                                    values[0] = 1.0f
                                }
                            } else if (sensorType == Sensor.TYPE_ACCELEROMETER && values.size >= 3) {
                                // 加速度计传感器：仅在运动中(speed > 0.1)叠加生理跑步/步行震动特征，静态时绝不篡改
                                if (speed > 0.1) {
                                    applySyntheticVibration(values, config, speed)
                                }
                            }
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    internal fun saveReloadState(): Array<Any?> = arrayOf(
        capturedListeners.map { arrayOf<Any?>(it.listener, it.sensor, it.handler) }.toTypedArray(),
        stepBase, lastStepsFloat, lastStepInitTime,
        mockStepCounterSensor, mockStepDetectorSensor, mockAccelerometerSensor,
        handleToTypeMap.map { arrayOf(it.key, it.value) }.toTypedArray(),
        // 追加到末尾而不是插在中间：热重载状态是只在本进程内传递的数组，改下标会让旧状态解析错位
        mockPressureSensor,
        mockGravitySensor,
        mockLinearAccelSensor,
        mockMagneticSensor,
        mockRotationVectorSensor,
        mockGameRotationSensor,
        mockGyroUncalibratedSensor,
        mockAccelUncalibratedSensor,
        mockMagneticUncalibratedSensor
    )

    internal fun clearReloadState() { capturedListeners.clear(); hookedListenerClasses.clear() }

    internal fun restoreReloadState(value: Any?) {
        val state = value as? Array<*> ?: return
        (state.getOrNull(0) as? Array<*>)?.filterIsInstance<Array<*>>()?.forEach {
            capturedListeners.add(CapturedSensorListener(it[0]!!, it[1] as? Sensor, it[2] as? Handler))
            hookConcreteListenerClass(it[0]!!.javaClass, it[0]!!.javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
        }
        stepBase = state[1] as Double
        lastStepsFloat = state[2] as Double
        lastStepInitTime = state[3] as Long
        mockStepCounterSensor = state[4] as? Sensor
        mockStepDetectorSensor = state[5] as? Sensor
        mockAccelerometerSensor = state[6] as? Sensor
        (state[7] as? Array<*>)?.filterIsInstance<Array<*>>()?.forEach {
            handleToTypeMap[it[0] as Int] = it[1] as Int
        }
        mockPressureSensor = state.getOrNull(8) as? Sensor
        mockGravitySensor = state.getOrNull(9) as? Sensor
        mockLinearAccelSensor = state.getOrNull(10) as? Sensor
        mockMagneticSensor = state.getOrNull(11) as? Sensor
        mockRotationVectorSensor = state.getOrNull(12) as? Sensor
        mockGameRotationSensor = state.getOrNull(13) as? Sensor
        mockGyroUncalibratedSensor = state.getOrNull(14) as? Sensor
        mockAccelUncalibratedSensor = state.getOrNull(15) as? Sensor
        mockMagneticUncalibratedSensor = state.getOrNull(16) as? Sensor
    }

    /**
     * 动态 Hook 具体的 Listener 实现类（如 Keep 的 StepListener）
     */
    private fun hookConcreteListenerClass(clazz: Class<*>, classLoader: ClassLoader) {
        if (hookedListenerClasses.putIfAbsent(clazz, true) == null) {
            try {
                XposedHelpers.hookAllMethods(clazz, "onSensorChanged") { chain, _ ->
                    val event = chain.args.firstOrNull() as? SensorEvent
                    if (event != null && event.sensor != null) {
                        val config = (XposedHelpers.module as? LocationHooker)?.readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val speed = config.optDouble("speed_m_s", 0.0)
                            if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                                // 气压计：静止时也要换算，只看气压模拟开关
                                if (barometerEnabled(config)) {
                                    applySyntheticPressure(event.values, config)
                                    event.timestamp = SystemClock.elapsedRealtimeNanos()
                                }
                            } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE && event.values.size >= 3) {
                                // 陀螺仪：静止时真实读数本来就是"没动"，不需要改；运动时必须与速度/航向自洽
                                if (gyroscopeEnabled(config) && speed > 0.1) {
                                    applySyntheticRotation(event.values, config, speed)
                                    event.timestamp = SystemClock.elapsedRealtimeNanos()
                                }
                            } else if (
                                (event.sensor.type == Sensor.TYPE_GRAVITY ||
                                    event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) &&
                                event.values.size >= 3
                            ) {
                                // 同上：派生通道必须与加速度计同源，且只在运动中改写
                                if (config.optBoolean("enable_step_simulation", true) && speed > 0.1) {
                                    if (event.sensor.type == Sensor.TYPE_GRAVITY) {
                                        applySyntheticGravity(event.values, config)
                                    } else {
                                        applySyntheticLinearAcceleration(event.values, config, speed)
                                    }
                                    event.timestamp = SystemClock.elapsedRealtimeNanos()
                                }
                            } else if (isUncalibratedSensorType(event.sensor.type) && event.values.size >= 6) {
                    // 未校准通道与校准通道必须减得出同一个值
                    if (uncalibratedEnabled(config, event.sensor.type) && speed > 0.1) {
                        applySyntheticUncalibrated(event.values, event.sensor.type, config, speed)
                        event.timestamp = SystemClock.elapsedRealtimeNanos()
                    }
                } else if (isMagneticSensorType(event.sensor.type) && event.values.size >= 3) {
                                // 同上：姿态类通道只在运动中改写，静止时真机读数本来就是对的
                                if (magneticSimulationEnabled(config) && speed > 0.1) {
                                    if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                                        applySyntheticMagneticField(event.values, config)
                                    } else {
                                        applySyntheticRotationVector(event.values, config)
                                    }
                                    event.timestamp = SystemClock.elapsedRealtimeNanos()
                                }
                            } else if (config.optBoolean("enable_step_simulation", true)) {
                                if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
                                    val curSteps = calculateCurrentSteps(config)
                                    event.values[0] = curSteps.toFloat()
                                    event.timestamp = SystemClock.elapsedRealtimeNanos()
                                } else if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR && event.values.isNotEmpty()) {
                                    if (speed > 0.1) {
                                        event.values[0] = 1.0f
                                        event.timestamp = SystemClock.elapsedRealtimeNanos()
                                    }
                                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && event.values.size >= 3 && speed > 0.1) {
                                    applySyntheticVibration(event.values, config, speed)
                                    event.timestamp = SystemClock.elapsedRealtimeNanos()
                                }
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }
    }

    private fun applySyntheticVibration(values: FloatArray, config: JSONObject, speed: Double) {
        val now = System.currentTimeMillis()
        val synthetic = syntheticAcceleration(config, speed, now)
        values[0] = synthetic[0]
        values[1] = synthetic[1]
        values[2] = synthetic[2]
        // 留一份给线性加速度通道复用：两个通道各自采样一次的话，噪声不同就对不上了
        lastAccelSnapshot = synthetic
        lastAccelSnapshotAtMs = now
    }

    /** 取"当前这一刻"的加速度计读数：优先用刚生成的快照，过期就现算，保证跨通道同源 */
    private fun currentAccelSnapshot(config: JSONObject, speed: Double, now: Long): FloatArray {
        val snapshot = lastAccelSnapshot
        return if (snapshot != null && now - lastAccelSnapshotAtMs in 0..ACCEL_SNAPSHOT_TTL_MS) {
            snapshot
        } else {
            syntheticAcceleration(config, speed, now)
        }
    }

    /** 生成一份加速度计读数（含重力），坐标约定 X 左右、Y 前后、Z 竖直 */
    private fun syntheticAcceleration(config: JSONObject, speed: Double, now: Long): FloatArray {
        val stepsFloat = calculateCurrentStepsFloat(config, now)
        val motionSpeed = RouteEngine.calculateCurrentPosition(config, now).speed.toDouble().takeIf { it > 0.0 } ?: speed
        return RouteEngine.realismSession(config).accelerometer(stepsFloat, motionSpeed, gaitTemplate(config), noiseRng)
    }

    /** 重力通道：竖直轴恒为 g，另两轴为 0，与 [syntheticAcceleration] 的坐标约定一致 */
    private fun applySyntheticGravity(values: FloatArray, config: JSONObject) {
        val gravity = RouteEngine.realismSession(config).gravity()
        values[0] = gravity[0]
        values[1] = gravity[1]
        values[2] = gravity[2]
    }

    /**
     * 线性加速度通道：加速度计减去重力。
     * 优先复用刚生成的加速度计快照，保证应用看到的 `linear == accel - gravity` 成立；
     * 快照太旧（超过 [ACCEL_SNAPSHOT_TTL_MS]）时按同一套参数现算一份。
     */
    private fun applySyntheticLinearAcceleration(values: FloatArray, config: JSONObject, speed: Double) {
        val now = System.currentTimeMillis()
        val accel = currentAccelSnapshot(config, speed, now)
        val linear = RouteEngine.realismSession(config).linearAcceleration(accel)
        values[0] = linear[0]
        values[1] = linear[1]
        values[2] = linear[2]
    }

    /** 磁力计 / 旋转矢量这一组姿态通道的开关：默认开启，`magnetic_simulation = false` 时完全不动 */
    private fun magneticSimulationEnabled(config: JSONObject?): Boolean =
        config != null && config.optBoolean("active", false) && config.optBoolean("magnetic_simulation", true)

    /** 磁力计与两种旋转矢量（含地磁的、不含地磁的）共用一套姿态模型 */
    private fun isMagneticSensorType(type: Int?): Boolean = when (type) {
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> true
        else -> false
    }

    /**
     * 磁力计：由当前纬度与航向推出来（偶极模型），水平分量顺着机头、竖直分量朝下。
     *
     * 真机在没有罗盘干扰时就是这样：应用把磁力计与 GPS 航向一对比，能看出"在跑却朝着固定方向"。
     */
    private fun applySyntheticMagneticField(values: FloatArray, config: JSONObject) {
        val field = syntheticMagneticField(config)
        values[0] = field[0]
        values[1] = field[1]
        values[2] = field[2]
    }

    /** 磁力计三轴：由当前纬度与航向按偶极模型推出来 */
    private fun syntheticMagneticField(config: JSONObject): FloatArray {
        val now = System.currentTimeMillis()
        val position = RouteEngine.calculateCurrentPosition(config, now)
        val startTime = config.optLong("start_timestamp", now)
        val elapsedSec = (now - startTime).coerceAtLeast(0L) / 1000.0
        return GeomagneticModel.sample(
            latitudeDeg = position.lat,
            headingDeg = position.bearing.toDouble(),
            seed = startTime,
            elapsedSec = elapsedSec
        )
    }

    /**
     * 旋转矢量：假设平放、机头朝行进方向，把那个姿态写成四元数。
     *
     * 老 API 上旋转矢量可能只有 3 个值（第四个 w 由应用自己按单位长度反解），所以按长度分别写。
     */
    private fun applySyntheticRotationVector(values: FloatArray, config: JSONObject) {
        val now = System.currentTimeMillis()
        val heading = RouteEngine.calculateCurrentPosition(config, now).bearing.toDouble()
        val q = AttitudeModel.rotationVector(heading)
        values[0] = q[0]
        values[1] = q[1]
        values[2] = q[2]
        if (values.size >= 4) values[3] = q[3]
    }

    /** 气压模拟是否开启：默认开启，用户显式关掉（`barometer_simulation = false`）时完全不动这一路 */
    private fun barometerEnabled(config: JSONObject?): Boolean =
        config != null && config.optBoolean("active", false) && config.optBoolean("barometer_simulation", true)

    /** 陀螺仪模拟是否开启：默认开启，用户显式关掉（`gyroscope_simulation = false`）时完全不动这一路 */
    private fun gyroscopeEnabled(config: JSONObject?): Boolean =
        config != null && config.optBoolean("active", false) && config.optBoolean("gyroscope_simulation", true)

    /**
     * 由路线当前航向的变化率（rad/s）驱动陀螺仪的 z 轴。
     *
     * 用 250 ms 的窗口做差分：窗口太短会被折线顶点的转向阶跃放大成尖峰，太长会把正常转弯抹平。
     */
    private fun headingRateRadPerSec(config: JSONObject, now: Long): Double {
        val windowMs = 250L
        val head = RouteEngine.calculateCurrentPosition(config, now).bearing.toDouble()
        val tail = RouteEngine.calculateCurrentPosition(config, now - windowMs).bearing.toDouble()
        val deltaDeg = (head - tail + 540.0) % 360.0 - 180.0
        return Math.toRadians(deltaDeg) / (windowMs / 1000.0)
    }

    /**
     * 把真实陀螺仪事件的三轴角速度改写成"由速度、步频、航向变化率推出来的"值。
     *
     * 改写真实事件而不是另发：真机陀螺仪按 50~200 Hz 上报，而派发循环只有 1 Hz；
     * 自己按高频补发既费力，又会和真实事件叠加出两条互不相干的序列。
     */
    private fun applySyntheticRotation(values: FloatArray, config: JSONObject, speed: Double) {
        val synthetic = syntheticRotation(config, speed)
        values[0] = synthetic[0]
        values[1] = synthetic[1]
        values[2] = synthetic[2]
    }

    /** 陀螺仪三轴角速度：速度、步频、航向变化率三者共同决定 */
    private fun syntheticRotation(config: JSONObject, speed: Double): FloatArray {
        val now = System.currentTimeMillis()
        val startTime = config.optLong("start_timestamp", now)
        val elapsedSec = (now - startTime).coerceAtLeast(0L) / 1000.0
        return GyroscopeModel.sample(
            speedMps = speed,
            cadenceSpm = currentCadence(config).toDouble(),
            headingRateRadPerSec = headingRateRadPerSec(config, now),
            seed = startTime,
            elapsedSec = elapsedSec
        )
    }

    /**
     * 未校准通道的三类传感器。它们每次给 6 个值：前三含零偏、后三零偏估计，
     * 系统保证 `校准值 = 原始值 − 零偏`。只改校准通道会留下"减完对不上"的破绽。
     */
    private fun isUncalibratedSensorType(type: Int?): Boolean = when (type) {
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> true
        else -> false
    }

    /** 未校准通道跟着各自校准通道的开关走，不额外加配置项 */
    private fun uncalibratedEnabled(config: JSONObject, sensorType: Int): Boolean = when (sensorType) {
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> gyroscopeEnabled(config)
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> config.optBoolean("enable_step_simulation", true)
        else -> magneticSimulationEnabled(config)
    }

    /** 写未校准读数：`原始 = 校准 + 零偏`，零偏写在后三位，减法回去与校准通道逐位相等 */
    private fun applySyntheticUncalibrated(
        values: FloatArray,
        sensorType: Int,
        config: JSONObject,
        speed: Double
    ) {
        if (values.size < 6) return
        val now = System.currentTimeMillis()
        val startTime = config.optLong("start_timestamp", now)
        val elapsedSec = (now - startTime).coerceAtLeast(0L) / 1000.0
        val kind: SensorBiasModel.Kind
        val calibrated: FloatArray
        when (sensorType) {
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                kind = SensorBiasModel.Kind.GYROSCOPE
                calibrated = syntheticRotation(config, speed)
            }
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                kind = SensorBiasModel.Kind.ACCELEROMETER
                calibrated = currentAccelSnapshot(config, speed, now)
            }
            else -> {
                kind = SensorBiasModel.Kind.MAGNETIC_FIELD
                calibrated = syntheticMagneticField(config)
            }
        }
        val bias = SensorBiasModel.bias(kind, startTime, elapsedSec)
        for (axis in 0 until 3) {
            values[axis] = calibrated[axis] + bias[axis]
            values[axis + 3] = bias[axis]
        }
    }

    /**
     * 需要注入虚拟实例的传感器类型。计步两类恒注入（老行为）；气压计只在开启模拟时注入，
     * 否则设备本来没有气压计时会凭空多出一个传感器，反而是新指纹。
     */
    private fun isSimulatedSensorType(type: Int): Boolean = when (type) {
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> true
        Sensor.TYPE_PRESSURE -> barometerEnabled((XposedHelpers.module as? LocationHooker)?.readConfig())
        Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> {
            val config = (XposedHelpers.module as? LocationHooker)?.readConfig()
            config != null && config.optBoolean("active", false) && config.optBoolean("enable_step_simulation", true)
        }
        Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ->
            magneticSimulationEnabled((XposedHelpers.module as? LocationHooker)?.readConfig())
        // 未校准通道只改写真实事件、不注入虚拟实例：设备本来没有加速度计时凭空多出一个
        // "未校准加速度计" 是新指纹，而且它对应的校准通道我们也没有注入
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> false
        else -> false
    }

    /** 需要在 registerListener 里捕获的传感器类型（捕获后要么改写事件，要么补发事件） */
    private fun isCapturedSensorType(type: Int): Boolean = when (type) {
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_ACCELEROMETER -> true
        Sensor.TYPE_GYROSCOPE -> true
        Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> true
        Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> true
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> true
        Sensor.TYPE_PRESSURE -> mockPressureSensor != null || barometerEnabled((XposedHelpers.module as? LocationHooker)?.readConfig())
        else -> false
    }

    /**
     * 把真实气压计事件的值改写成"由海拔换算出来的气压"。
     *
     * 之所以改写真实事件而不是另发一遍：设备本身有气压计时，系统仍在按自己的频率（常见 5~10 Hz）
     * 送来真实读数，再补发一批只会在同一条通道上出现两个互不相干的序列。
     */
    private fun applySyntheticPressure(values: FloatArray, config: JSONObject) {
        if (values.isEmpty()) return
        values[0] = RouteEngine.realisticPressureHpa(config).toFloat()
    }

    /**
     * 由 ConfigPoller 每秒向"我们注入的虚拟气压计"的监听器补发一个事件。
     *
     * 与 [dispatchStepEvents] 分开的原因：计步派发在静止时整段提前返回（速度 ≤ 0.05 直接 return），
     * 而真机气压计在静止时照样按固定频率上报——挂在那条链路上会让静止阶段的读数断流。
     */
    fun dispatchPressureEvents(config: JSONObject, classLoader: ClassLoader) {
        if (!barometerEnabled(config)) return
        val mock = mockPressureSensor ?: return
        val listeners = capturedListeners.filter { it.sensor === mock }
        if (listeners.isEmpty()) return

        val event = createSensorEvent(mock, floatArrayOf(RouteEngine.realisticPressureHpa(config).toFloat())) ?: return
        for (entry in listeners) {
            val targetHandler = entry.handler ?: Handler(Looper.getMainLooper())
            val listener = entry.listener
            targetHandler.post {
                try {
                    if (listener is SensorEventListener) {
                        listener.onSensorChanged(event)
                    } else {
                        XposedHelpers.callMethod(listener, "onSensorChanged", event)
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    /** 用户录制的步态模板（配置里的编码字符串），按字符串内容缓存解码结果 */
    private fun gaitTemplate(config: JSONObject): GaitTemplate? {
        val source = config.optString("gait_template", "")
        if (source != cachedTemplateSource) {
            cachedTemplate = GaitTemplate.decode(source)
            cachedTemplateSource = source
        }
        return cachedTemplate
    }

    /**
     * 计算当前仿真总步数
     */
    fun calculateCurrentSteps(config: JSONObject, now: Long = System.currentTimeMillis()): Long =
        calculateCurrentStepsFloat(config, now).toLong()

    /** 连续的累计步数：整数部分是步数，小数部分是当前这一步的相位，供加速度波形对齐 */
    private fun calculateCurrentStepsFloat(config: JSONObject, now: Long): Double {
        val startTime = config.optLong("start_timestamp", now)
        if (lastStepInitTime != startTime) {
            lastStepInitTime = startTime
            stepBase = lastStepsFloat
            // 换会话：重新对齐 detector 游标，并把墙钟换算成一次性的偏移量
            stepTimeline.reset()
            stepAnchorElapsedMs = SystemClock.elapsedRealtime()
            stepAnchorOffsetSec = ((now - startTime).coerceAtLeast(0L)) / 1000.0
        }

        val speed = config.optDouble("speed_m_s", 0.0)
        if (speed <= 0.05) return lastStepsFloat

        // 真实 STEP_COUNTER 是自开机单调递增的，不会因为改时间/校时而倒退；
        // 所以积分时基用 elapsedRealtime，墙钟只用于把 start_timestamp 折成初始偏移
        val elapsedSec = stepAnchorOffsetSec +
            (SystemClock.elapsedRealtime() - stepAnchorElapsedMs) / 1000.0
        val baseCadence = currentCadence(config)

        val steps = stepBase + RouteEngine.realismSession(config, startTime).steps(baseCadence.toDouble(), elapsedSec)
        lastStepsFloat = steps
        return steps
    }

    /** 当前生效步频（spm）：自动步频按速度换算，手动模式取配置值 */
    private fun currentCadence(config: JSONObject): Int {
        val speed = config.optDouble("speed_m_s", 0.0)
        return if (config.optBoolean("is_auto_cadence", true)) {
            calculateAutoCadence(speed)
        } else {
            config.optInt("step_cadence_spm", 165).coerceIn(60, 240)
        }
    }

    /**
     * 智能根据速度自动计算匹配的生理步频 (SPM, 步/分钟)
     */
    fun calculateAutoCadence(speedMs: Double): Int {
        return when {
            speedMs <= 0.8 -> 95 // 慢走
            speedMs <= 1.5 -> (100 + (speedMs - 0.8) / 0.7 * 25).toInt() // 快走 100~125 SPM
            speedMs <= 2.8 -> (135 + (speedMs - 1.5) / 1.3 * 25).toInt() // 慢跑 135~160 SPM
            speedMs <= 4.0 -> (160 + (speedMs - 2.8) / 1.2 * 20).toInt() // 匀速跑 160~180 SPM
            speedMs <= 5.5 -> (180 + (speedMs - 4.0) / 1.5 * 15).toInt() // 快跑 180~195 SPM
            else -> 200 // 冲刺 200 SPM
        }
    }

    /**
     * 由 ConfigPoller 每秒主动向监听器推送计步事件（仅在运动路线模拟中生效）
     */
    fun dispatchStepEvents(config: JSONObject, classLoader: ClassLoader) {
        if (!config.optBoolean("active", false)) return
        val enableStep = config.optBoolean("enable_step_simulation", true)
        if (!enableStep) return

        val speed = config.optDouble("speed_m_s", 0.0)
        // 静态定位时绝不主动推送步频和加速度事件，保证主线程和人脸识别传感器纯净
        if (speed <= 0.05) return

        val now = System.currentTimeMillis()
        val stepsFloat = calculateCurrentStepsFloat(config, now)
        val totalSteps = stepsFloat.toLong()

        val listeners = capturedListeners.toList()
        if (listeners.isEmpty()) return

        // 本 tick 需要补发的步事件时刻。必须在监听器循环之前算好：
        // 每个事件对应"这一步真实跨越的时刻"，与监听器数量无关（否则第二个监听器会拿到 delta=0）
        val pendingStepTimestamps: LongArray = if (speed > 0.1) {
            stepTimeline.sample(stepsFloat, SystemClock.elapsedRealtimeNanos())
        } else {
            LongArray(0)
        }

        for (entry in listeners) {
            val listener = entry.listener
            val sensor = entry.sensor ?: continue
            val targetHandler = entry.handler ?: Handler(Looper.getMainLooper())

            when (sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val event = createSensorEvent(sensor, floatArrayOf(totalSteps.toFloat()))
                    if (event != null) {
                        targetHandler.post {
                            try {
                                if (listener is SensorEventListener) {
                                    listener.onSensorChanged(event)
                                } else {
                                    XposedHelpers.callMethod(listener, "onSensorChanged", event)
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    // 真机 TYPE_STEP_DETECTOR 是每检测到一步回调一次，间隔约 60/步频 秒；
                    // 每秒只发一个事件会让下游算出恒定的 60 spm，与同时推送的 STEP_COUNTER
                    // （按步频积分，每秒 +2.7~3.0）差 2.7~3.0 倍。
                    for (stepTimestampNs in pendingStepTimestamps) {
                        val event = createSensorEvent(sensor, floatArrayOf(1.0f), stepTimestampNs) ?: continue
                        targetHandler.post {
                            try {
                                if (listener is SensorEventListener) {
                                    listener.onSensorChanged(event)
                                } else {
                                    XposedHelpers.callMethod(listener, "onSensorChanged", event)
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    if (speed > 0.1) {
                        val rawValues = floatArrayOf(0f, 0f, 9.8f)
                        applySyntheticVibration(rawValues, config, speed)
                        val event = createSensorEvent(sensor, rawValues)
                        if (event != null) {
                            targetHandler.post {
                                try {
                                    if (listener is SensorEventListener) {
                                        listener.onSensorChanged(event)
                                    } else {
                                        XposedHelpers.callMethod(listener, "onSensorChanged", event)
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getSensorHandle(sensor: Sensor): Int? {
        return try {
            val handleField = Sensor::class.java.getDeclaredField("mHandle")
            handleField.isAccessible = true
            handleField.getInt(sensor)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateMockSensor(type: Int, classLoader: ClassLoader): Sensor? {
        if (type == Sensor.TYPE_STEP_COUNTER && mockStepCounterSensor != null) return mockStepCounterSensor
        if (type == Sensor.TYPE_STEP_DETECTOR && mockStepDetectorSensor != null) return mockStepDetectorSensor
        if (type == Sensor.TYPE_ACCELEROMETER && mockAccelerometerSensor != null) return mockAccelerometerSensor
        if (type == Sensor.TYPE_PRESSURE && mockPressureSensor != null) return mockPressureSensor
        if (type == Sensor.TYPE_GRAVITY && mockGravitySensor != null) return mockGravitySensor
        if (type == Sensor.TYPE_LINEAR_ACCELERATION && mockLinearAccelSensor != null) return mockLinearAccelSensor
        if (type == Sensor.TYPE_MAGNETIC_FIELD && mockMagneticSensor != null) return mockMagneticSensor
        if (type == Sensor.TYPE_ROTATION_VECTOR && mockRotationVectorSensor != null) return mockRotationVectorSensor
        if (type == Sensor.TYPE_GAME_ROTATION_VECTOR && mockGameRotationSensor != null) return mockGameRotationSensor
        if (type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR && mockRotationVectorSensor != null) return mockRotationVectorSensor
        if (type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED && mockGyroUncalibratedSensor != null) return mockGyroUncalibratedSensor
        if (type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED && mockAccelUncalibratedSensor != null) return mockAccelUncalibratedSensor
        if (type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED && mockMagneticUncalibratedSensor != null) return mockMagneticUncalibratedSensor

        try {
            val sensorClass = Class.forName("android.hardware.Sensor", false, classLoader)
            val constructor: Constructor<*> = sensorClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val sensor = constructor.newInstance() as Sensor

            setSensorField(sensor, "mType", type)
            setSensorField(sensor, "mName", when(type) {
                Sensor.TYPE_STEP_COUNTER -> "Step Counter Sensor"
                Sensor.TYPE_STEP_DETECTOR -> "Step Detector Sensor"
                Sensor.TYPE_PRESSURE -> "Pressure Sensor"
                Sensor.TYPE_GRAVITY -> "Gravity Sensor"
                Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Acceleration Sensor"
                Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer Sensor"
                Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector Sensor"
                Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game Rotation Vector Sensor"
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic Rotation Vector Sensor"
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyroscope Uncalibrated Sensor"
                Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "Accelerometer Uncalibrated Sensor"
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "Magnetic Field Uncalibrated Sensor"
                else -> "Accelerometer Sensor"
            })
            setSensorField(sensor, "mVendor", "Android")
            setSensorField(sensor, "mVersion", 1)
            // 气压计的量程/分辨率/功耗按常见型号（BMP 系列）取值，单位 hPa / mA
            // 重力 / 线性加速度是加速度计的派生量，分辨率与功耗按同一颗器件取
            setSensorField(
                sensor,
                "mResolution",
                when (type) {
                    Sensor.TYPE_PRESSURE -> 0.01f
                    Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> 0.0012f
                    // 磁力计常见分辨率约 0.15 µT；旋转矢量是单位四元数，分辨率按 1e-5 取
                    Sensor.TYPE_MAGNETIC_FIELD -> 0.15f
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> 0.00001f
                    // 未校准通道是同一颗器件的原始读数，分辨率与对应校准通道一致
                    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> 0.001f
                    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> 0.0024f
                    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> 0.15f
                    else -> 1.0f
                }
            )
            setSensorField(
                sensor,
                "mPower",
                when (type) {
                    Sensor.TYPE_PRESSURE -> 0.007f
                    Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> 0.175f
                    Sensor.TYPE_MAGNETIC_FIELD -> 0.28f
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> 0.175f
                    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> 0.2f
                    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> 0.2f
                    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> 0.28f
                    else -> 0.05f
                }
            )

            when (type) {
                Sensor.TYPE_STEP_COUNTER -> mockStepCounterSensor = sensor
                Sensor.TYPE_STEP_DETECTOR -> mockStepDetectorSensor = sensor
                Sensor.TYPE_ACCELEROMETER -> mockAccelerometerSensor = sensor
                Sensor.TYPE_PRESSURE -> mockPressureSensor = sensor
                Sensor.TYPE_GRAVITY -> mockGravitySensor = sensor
                Sensor.TYPE_LINEAR_ACCELERATION -> mockLinearAccelSensor = sensor
                Sensor.TYPE_MAGNETIC_FIELD -> mockMagneticSensor = sensor
                Sensor.TYPE_ROTATION_VECTOR -> mockRotationVectorSensor = sensor
                Sensor.TYPE_GAME_ROTATION_VECTOR -> mockGameRotationSensor = sensor
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> mockRotationVectorSensor = sensor
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> mockGyroUncalibratedSensor = sensor
                Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> mockAccelUncalibratedSensor = sensor
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> mockMagneticUncalibratedSensor = sensor
            }
            return sensor
        } catch (_: Throwable) {
            return null
        }
    }

    private fun setSensorField(sensor: Sensor, fieldName: String, value: Any) {
        try {
            val field: Field = Sensor::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(sensor, value)
        } catch (_: Throwable) {}
    }

    private fun createSensorEvent(
        sensor: Sensor,
        values: FloatArray,
        timestampNs: Long = SystemClock.elapsedRealtimeNanos()
    ): SensorEvent? {
        try {
            val eventConstructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            eventConstructor.isAccessible = true
            val event = eventConstructor.newInstance(values.size) as SensorEvent
            System.arraycopy(values, 0, event.values, 0, values.size)

            val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
            sensorField.isAccessible = true
            sensorField.set(event, sensor)

            val timestampField = SensorEvent::class.java.getDeclaredField("timestamp")
            timestampField.isAccessible = true
            timestampField.setLong(event, timestampNs)

            val accuracyField = SensorEvent::class.java.getDeclaredField("accuracy")
            accuracyField.isAccessible = true
            accuracyField.setInt(event, 3) // SENSOR_STATUS_ACCURACY_HIGH

            return event
        } catch (_: Throwable) {
            try {
                // Fallback 1: search any constructors
                val constructors = SensorEvent::class.java.declaredConstructors
                for (ctor in constructors) {
                    ctor.isAccessible = true
                    val paramTypes = ctor.parameterTypes
                    val args = arrayOfNulls<Any>(paramTypes.size)
                    for (i in args.indices) {
                        if (paramTypes[i] == Int::class.javaPrimitiveType) args[i] = values.size
                    }
                    val event = ctor.newInstance(*args) as SensorEvent
                    System.arraycopy(values, 0, event.values, 0, values.size)
                    try {
                        val sf = SensorEvent::class.java.getDeclaredField("sensor")
                        sf.isAccessible = true
                        sf.set(event, sensor)
                        val tf = SensorEvent::class.java.getDeclaredField("timestamp")
                        tf.isAccessible = true
                        tf.setLong(event, timestampNs)
                        val af = SensorEvent::class.java.getDeclaredField("accuracy")
                        af.isAccessible = true
                        af.setInt(event, 3)
                    } catch (_: Throwable) {}
                    return event
                }
            } catch (_: Throwable) {}

            try {
                // Fallback 2: sun.misc.Unsafe.allocateInstance
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                theUnsafeField.isAccessible = true
                val unsafe = theUnsafeField.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val event = allocateMethod.invoke(unsafe, SensorEvent::class.java) as SensorEvent

                val valuesField = SensorEvent::class.java.getDeclaredField("values")
                valuesField.isAccessible = true
                val arr = FloatArray(values.size)
                System.arraycopy(values, 0, arr, 0, values.size)
                valuesField.set(event, arr)

                val sf = SensorEvent::class.java.getDeclaredField("sensor")
                sf.isAccessible = true
                sf.set(event, sensor)

                val tf = SensorEvent::class.java.getDeclaredField("timestamp")
                tf.isAccessible = true
                tf.setLong(event, SystemClock.elapsedRealtimeNanos())

                val af = SensorEvent::class.java.getDeclaredField("accuracy")
                af.isAccessible = true
                af.setInt(event, 3)
                return event
            } catch (_: Throwable) {}

            return null
        }
    }
}

package com.vincenthzr.locationspoofer.xposed.utils

/**
 * 椭球高之外的「海拔高（MSL）」元数据统一处理。
 *
 * Android 14（API 34）起，`android.location.Location` 除了椭球高（`getAltitude()`）
 * 还带海拔高（`getMslAltitudeMeters()` / `getMslAltitudeAccuracyMeters()`）。
 * 系统 GNSS 给出的结果两者都有，而海拔高依赖大地水准面模型，与椭球高相差几十米。
 *
 * 本模块有多条路径是**改写既有 Location 对象**（不是重新构造），例如
 * `LocationProviderHooker` 与 `GoogleAndAddressHooker` 里拿到系统/Play 服务的
 * `Location` 后就地覆盖经纬度。这种路径只覆盖椭球高，海拔高仍是**真实设备**的值，
 * 于是同一个位置在两条通道上对不上——这与「卫星颗数三口径打架」是同一类特征。
 *
 * 这里统一清掉，口径与 ColorOS 派发路径原本的处理保持一致。
 * 用反射而不是直接调用，是为了在 API 34 以下的设备上编译期与运行期都不受影响
 * （方法不存在时反射抛异常，被吞掉）。
 */
internal object LocationAltitudeMetadata {

    private val CLEAR_METHODS = arrayOf("removeMslAltitude", "removeMslAltitudeAccuracy")

    /** 清掉海拔高与它的精度，使「只覆盖椭球高」的改写路径不再残留真实值。 */
    fun clear(location: Any?) {
        if (location == null) return
        for (name in CLEAR_METHODS) {
            try {
                location.javaClass.getMethod(name).invoke(location)
            } catch (_: Throwable) {
                // API 34 以下没有这两个方法，属预期情况
            }
        }
    }
}

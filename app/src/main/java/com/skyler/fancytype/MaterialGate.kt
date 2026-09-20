package com.skyler.fancytype

/**
 * 超级材质的放行判定，注入在 `pc.m.L0(Iterable, Object)` 上。
 *
 * 为什么选这个点：
 * `bb.b0.j()` 里材质的总门是一句
 * ```
 * z10 = this.s && pc.m.L0(linkedHashMap.keySet(), this.f3476t);
 * ```
 * `this.s` 是设备能力位（blur 支持 + 用户已开启背景模糊），在小米平板 7 上恒为 true，
 * 所以 `pc.m.L0` 的返回值就是唯一的开关。改这里而不是改 prefs，可以免疫
 * `allowed_packages` / `package_versions` 被云端配置覆写的问题。
 *
 * 为什么敢拦 `pc.m.L0`：
 * 它是全局工具方法，被多处调用。判定条件收得很紧 ——
 *   1. 第一参数必须是 `bb.b0.j()` 内部 `new LinkedHashMap()` 的 keySet（类名可辨）；
 *   2. 第二参数必须是字符串（包名）；
 *   3. 若 `bb.b0.j()` 挂上了，还要求当前正处于该方法调用内（ThreadLocal 深度计数）。
 * 三条同时成立时，只有材质白名单判定会命中，其它调用点一律走原逻辑。
 */
object MaterialGate {

    /**
     * 当前线程是否在 `bb.b0.j()` 内。
     *
     * 显式写 `java.lang.ThreadLocal`：Kotlin 默认导入的 `kotlin.concurrent.ThreadLocal`
     * 其 `get()` 返回可空值，会和这里的计数逻辑打架。
     */
    private val depth = object : java.lang.ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }

    /** `bb.b0.j()` 是否成功挂上。挂上后用它把拦截范围收紧到该方法内部。 */
    @Volatile
    var helperHooked: Boolean = false

    /**
     * 最近一次判定看到的前台应用包名（也就是键盘正服务的宿主）。
     * `xe.b.a` 那个 Hook 拿不到它，但诊断「透过窗口模糊白名单」需要，所以在这里留存。
     */
    @Volatile
    var lastHostPackage: String? = null

    /** `ThreadLocal.get()` 在 Kotlin 映射里是可空的，统一在这里兜底。 */
    private fun current(): Int = depth.get() ?: 0

    fun enterHelper() {
        depth.set(current() + 1)
    }

    fun exitHelper() {
        val d = current() - 1
        depth.set(if (d < 0) 0 else d)
    }

    private fun insideHelper(): Boolean = !helperHooked || current() > 0

    /**
     * @return true/false 表示覆写判定结果；null 表示「不干预，走默认逻辑」。
     */
    fun decide(iterable: Any?, candidate: Any?): Boolean? {
        val pkg = candidate as? String ?: return null
        if (pkg.isEmpty()) return null
        // 只有 bb.b0.j() 里那个 LinkedHashMap 的 keySet 才是材质总门；
        // force_dark / force_light 用的是别的集合，交给默认实现。
        if (iterable == null || iterable.javaClass.name != Target.MATERIAL_GATE_SET_CLASS) return null
        if (!insideHelper()) return null

        val cfg = ConfigLoader.snapshot()
        if (!cfg.materialEnabled) return null

        lastHostPackage = pkg
        val allowed = cfg.materialAllowedFor(pkg)
        L.sampled("material_gate", limit = 24) {
            "event=material_gate pkg=$pkg forceAll=${cfg.materialForceAll} " +
                "picked=${cfg.materialPackages.size} allowed=$allowed"
        }
        // 命中就给 true；没命中返回 null，让默认白名单（如 com.android.quicksearchbox）继续生效
        return if (allowed) true else null
    }
}

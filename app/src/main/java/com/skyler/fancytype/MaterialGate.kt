package com.skyler.fancytype

/**
 * 超级材质的放行判定，注入在集合包含方法 `(Iterable, Object) -> boolean` 上。
 * 宿主类名由 [AppTargets.collectionsUtil] 按输入法版本给出；方法名本身不依赖，
 * 一律按签名定位 —— 它每版都会重命名。
 *
 * 为什么选这个点：
 * 材质判定方法里材质的总门是一句
 * ```
 * z10 = 设备能力位 && <集合包含>(白名单.keySet(), 当前前台包名);
 * ```
 * 设备能力位（blur 支持 + 用户已开启背景模糊）在小米平板 7 上恒为 true，
 * 所以这个集合包含的结果就是唯一的开关。改这里而不是改 prefs，可以免疫
 * `allowed_packages` / `package_versions` 被云端配置覆写的问题。
 *
 * 为什么敢拦它：
 * 它是全局工具方法，被多处调用。判定条件收得很紧 ——
 *   1. 第一参数必须是 `new LinkedHashMap()` 的 keySet（类名可辨）；
 *   2. 第二参数必须是字符串（包名）；
 *   3. 若判定方法的宿主挂上了，还要求当前正处于该方法调用内（ThreadLocal 深度计数）；
 *   4. **必须是本次调用里第一处用到的那个集合**（见下）。
 *
 * ⚠️ 第 4 条是 0.2.1053 起才必须的：
 * 这一版小米**新增了黑名单判定**，而白名单与黑名单都是
 * `new LinkedHashMap().keySet()`，**类名完全一样**，只靠类名分不出来。
 * 给黑名单也返回 true 会让 `!<集合包含>(黑名单, 包名)` 变成 false，
 * 材质会被整个关掉 —— 这就是「输入法更新后材质失效」的真正原因。
 *
 * 判定顺序是稳定的：方法里第一处调用**无条件**使用白名单 keySet，
 * 黑名单在它之后、且是**另一个**集合对象。所以「本次调用里首次遇到的集合 = 白名单」，
 * 之后只覆写与它是同一个对象（`===`）的调用，其余（黑名单、force_dark / force_light）
 * 一律交回 App 自己的逻辑。
 */
object MaterialGate {

    /**
     * 当前线程是否在材质判定方法内。
     *
     * 显式写 `java.lang.ThreadLocal`：Kotlin 默认导入的 `kotlin.concurrent.ThreadLocal`
     * 其 `get()` 返回可空值，会和这里的计数逻辑打架。
     */
    private val depth = object : java.lang.ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }

    /**
     * 本次判定方法调用里**第一次**见到的集合，也就是白名单 keySet。
     * 用于把黑名单（同类名、另一个对象）区分出去。
     */
    private val firstSet = object : java.lang.ThreadLocal<Any?>() {}

    /** 材质判定方法是否成功挂上。挂上后用它把拦截范围收紧到该方法内部。 */
    @Volatile
    var helperHooked: Boolean = false

    /**
     * 最近一次判定看到的前台应用包名（也就是键盘正服务的宿主）。
     * 描述符应用入口那个 Hook 拿不到它，但诊断「透过窗口模糊白名单」需要，所以在这里留存。
     */
    @Volatile
    var lastHostPackage: String? = null

    /** `ThreadLocal.get()` 在 Kotlin 映射里是可空的，统一在这里兜底。 */
    private fun current(): Int = depth.get() ?: 0

    fun enterHelper() {
        val d = current()
        // 只在最外层进入时重置「首个集合」，嵌套调用不能重置
        if (d == 0) firstSet.set(null)
        depth.set(d + 1)
    }

    fun exitHelper() {
        val d = current() - 1
        val next = if (d < 0) 0 else d
        depth.set(next)
        if (next == 0) firstSet.set(null)
    }

    private fun insideHelper(): Boolean = !helperHooked || current() > 0

    /**
     * @return true/false 表示覆写判定结果；null 表示「不干预，走默认逻辑」。
     */
    fun decide(iterable: Any?, candidate: Any?): Boolean? {
        val pkg = candidate as? String ?: return null
        if (pkg.isEmpty()) return null
        // 只有材质白名单/黑名单那种 LinkedHashMap 的 keySet 才考虑；
        // 类名相同时还要靠「是不是本次调用的第一个集合」进一步区分（见下）
        if (iterable == null || iterable.javaClass.name != Target.MATERIAL_GATE_SET_CLASS) return null
        if (!insideHelper()) return null

        val cfg = ConfigLoader.snapshot()
        if (!cfg.materialEnabled) return null

        // 本次调用里首次见到的集合 = 白名单；之后只认同一个对象。
        // 于是黑名单（同类名的另一个 keySet）与 force_dark / force_light 集合
        // 都会走到下面 return null，交回 App 自己的判定 —— 这正是 0.2.1053 需要的。
        val known = firstSet.get()
        if (known == null) {
            firstSet.set(iterable)
        } else if (known !== iterable) {
            L.sampled("material_gate_skip", limit = 8) {
                "event=material_gate_skip pkg=$pkg reason=not_whitelist_set"
            }
            return null
        }

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

package io.github.skyshadowhero.fancypad.hooks

import io.github.skyshadowhero.fancypad.*
import dalvik.system.DexFile
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 按输入法版本分档的混淆目标。
 *
 * ## 为什么需要分档
 *
 * R8 每次发版都会重命名混淆类，所以「硬编码一套名字」必然导致
 * **新版本一更新就整块失效**（按类名定位的功能全挂），而且旧版本也没法兼顾。
 * 这里按输入法版本各记一份，运行时挑出匹配的那一档 —— 每个版本都能用。
 *
 * ## 怎么挑档（关键）
 *
 * **不能靠「类名是否存在」**：实测不同版本里会存在**同名的无关类** ——
 * 0.2.1053 里 `n9.e` / `z7.a` 仍在（只是变成别的东西），
 * 0.2.974 里 `oc.m` / `we.b` 也仍在。按存在性打分会挑错。
 *
 * 所以每档用**结构特征**打分：类能不能加载 + 方法签名/字段类型是否符合该角色的形状。
 * 同一份 APK 里只会有一套混淆名满足这些形状，得分最高的那一档就是当前版本。
 *
 * 全部为 0 说明是没记录过的新版本：返回 null，模块整体降级 ——
 * **资源名解析那一层照常工作**（圆角/间距/宽度等尺寸功能不受影响），
 * 只有「按类名定位」的功能不可用，并在日志里明确提示需要补录新版本。
 */
data class AppTargets(
    /** 版本标签，只用于日志 */
    val label: String,

    // ---- 类名 ----
    /** PadSplitQwertyDims：分离键盘几何（诊断用） */
    val padSplitDims: String,
    /** SharedPreferences 门面 */
    val prefFacade: String,
    /** 夹取工具（Kotlin `coerceIn` 的实现）所在类 */
    val clampUtil: String,

    // ---- 方法名 ----
    /** `static boolean (String, boolean)` */
    val mPrefBool: String,
    /** `static boolean ()` 读取 split_keyboard_enabled */
    val mSplitGetter: String,
    /** 夹取工具里 `(float, float, float) -> float` */
    val mClampFloat: String,
    /** 夹取工具里 `(int, int, int) -> int` */
    val mClampInt: String,

    // ---- 诊断字段名 ----
) {

    /**
     * 用结构特征给本档打分（0..3）。分数最高且大于 0 的档位即为当前输入法版本。
     *
     * 每一项都校验「形状」而不是只看类名，同名但结构不符的无关类不会得分。
     */
    fun score(cl: ClassLoader): Int {
        var s = 0
        if (hasPrefFacadeShape(cl, prefFacade)) s++
        if (has13ParamCtor(cl, padSplitDims)) s++
        if (hasClampShape(cl, clampUtil)) s++
        return s
    }
}

/** 悬浮键盘尺寸解锁只依赖夹取工具，不必与其它混淆目标绑在同一版本档。 */
data class ClampTarget(
    val label: String,
    val className: String,
    val floatMethod: String,
    val intMethod: String,
)

// ---------------------------------------------------------------- 结构校验

private fun loadOrNull(cl: ClassLoader, name: String): Class<*>? =
    if (name.isEmpty()) null else try {
        cl.loadClass(name)
    } catch (t: Throwable) {
        null
    }

/** prefs 门面的形状：既能 `(String, boolean) -> boolean`，又能 `() -> boolean` */
private fun hasPrefFacadeShape(cl: ClassLoader, name: String): Boolean {
    val c = loadOrNull(cl, name) ?: return false
    return try {
        val withKey = c.declaredMethods.any {
            it.parameterCount == 2 &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        val noArg = c.declaredMethods.any {
            it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
        }
        withKey && noArg
    } catch (t: Throwable) {
        false
    }
}

/** 是否有 13 参构造（PadSplitQwertyDims 是 13 个 float） */
private fun has13ParamCtor(cl: ClassLoader, name: String): Boolean {
    val c = loadOrNull(cl, name) ?: return false
    return try {
        c.declaredConstructors.any { it.parameterCount == 13 }
    } catch (t: Throwable) {
        false
    }
}

/**
 * 夹取工具的形状：同时有 `(float, float, float) -> float` 与 `(int, int, int) -> int`。
 *
 * 这两个是 Kotlin `coerceIn` 的实现，成对出现，别的地方不会有这种组合。
 */
private fun hasClampShape(cl: ClassLoader, name: String): Boolean {
    val c = loadOrNull(cl, name) ?: return false
    return try {
        val f = c.declaredMethods.any {
            Modifier.isStatic(it.modifiers) &&
            it.parameterCount == 3 &&
                it.returnType == Float::class.javaPrimitiveType &&
                it.parameterTypes.all { p -> p == Float::class.javaPrimitiveType }
        }
        val i = c.declaredMethods.any {
            Modifier.isStatic(it.modifiers) &&
            it.parameterCount == 3 &&
                it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes.all { p -> p == Int::class.javaPrimitiveType }
        }
        f && i
    } catch (t: Throwable) {
        false
    }
}

/**
 * 已知版本的混淆目标表。
 *
 * **每记录一个新版本就在前面加一档**（新的放最前也无所谓，打分决定结果）。
 * 加档时要填全：类名、方法名、以及诊断用的字段名。
 */
object TargetCatalog {

    /**
     * 0.2.1448：夹取工具与分离键盘数据类再次改名；prefs 门面仍为 `j9.e`。
     * `j9.e` 已不再提供单独的 split getter，但该字段没有参与当前 Hook。
     */
    private val V1448 = AppTargets(
        label = "0.2.1448",
        padSplitDims = "pa.n",
        prefFacade = "j9.e",
        clampUtil = "hi.c",
        mPrefBool = "a",
        mSplitGetter = "",
        mClampFloat = "E",
        mClampInt = "F",
    )

    /** 0.2.910 与 0.2.974 的混淆名相同，合并为一档。 */
    private val V910_974 = AppTargets(
        label = "0.2.910 ~ 0.2.974",
        padSplitDims = "la.n",
        prefFacade = "n9.e",
        clampUtil = "z7.s",
        mPrefBool = "a",
        mSplitGetter = "o",
        mClampFloat = "t",
        mClampInt = "u",
    )

    /** 0.2.1053：包名与类名整体重排。 */
    private val V1053 = AppTargets(
        label = "0.2.1053",
        padSplitDims = "ka.n",
        prefFacade = "m9.e",
        clampUtil = "ed.a",
        mPrefBool = "a",
        mSplitGetter = "q",
        mClampFloat = "j",
        mClampInt = "k",
    )

    /**
     * 0.2.1363（2026-11-11 更新）：R8 又整体重排了一轮（`u1`→`b8`、`o9`→`j9`）。
     *
     * 定位依据（反编译后的结构 + 字符串引用，不是猜的）：
     * - `b8.h`：唯一同时有 `r(F,F,F)->F` 与 `s(I,I,I)->I` 两个 `coerceIn` 实现的类 →
     *   夹取工具。原来挂的 `u1.c` 已不存在，**悬浮键盘尺寸解锁就死在这里**。
     * - `j9.e`：`a(String,boolean)->boolean` + `s()`（`s()` 读 `split_keyboard_enabled`）→ prefs 门面。
     * - `fb.e2`：13 个 float 的数据类 → 分离键盘几何（仅诊断用）。
     */
    private val V1363 = AppTargets(
        label = "0.2.1363",
        padSplitDims = "fb.e2",
        prefFacade = "j9.e",
        clampUtil = "b8.h",
        mPrefBool = "a",
        mSplitGetter = "s",
        mClampFloat = "r",
        mClampInt = "s",
    )

    /**
     * 0.2.1246：整体再重排一轮（`m9` → `o9`）。
     *
     * 定位依据（全部来自 dex 结构 + 字符串引用，不是猜的）：
     * - `o9.e`：唯一同时具备 `static boolean(String,boolean)` 与 `static boolean()`、
     *   且代码里真的引用 `"split_keyboard_enabled"` 的类 → prefs 门面；其 `s()` 就是读该键。
     * - `u1.c`：同时有 `(F,F,F)->F`（`n`）与 `(I,I,I)->I`（`o`）→ 夹取工具。
     * - `jb.z0`：13 个 float 的数据类 → 分离键盘几何（仅诊断用）。
     */
    private val V1246 = AppTargets(
        label = "0.2.1246",
        padSplitDims = "jb.z0",
        prefFacade = "o9.e",
        clampUtil = "u1.c",
        mPrefBool = "a",
        mSplitGetter = "s",
        mClampFloat = "n",
        mClampInt = "o",
    )

    val known: List<AppTargets> = listOf(V1448, V1363, V1246, V1053, V910_974)

    /** 满分 = [AppTargets.score] 里实际校验的项数。 */
    private const val MAX_SCORE = 3

    /**
     * 接受一档所需的最低分。
     *
     * 留 1 分余量：某一版改掉一个类的形状时仍能命中，
     * 但只碰巧对上一处的无关混淆类会被挡在门外。
     */
    private const val MIN_ACCEPT = 2

    @Volatile
    private var resolved: AppTargets? = null

    @Volatile
    private var attempted = false

    /** 当前目标 APK 的 dex 路径，供未知版本自动发现夹取工具。 */
    @Volatile
    private var dexPaths: List<String> = emptyList()

    @Volatile
    private var discoveredClamps: List<ClampTarget>? = null

    @Volatile
    private var clampDiscoveryAttempted = false

    /** 已经挑中的档位；未解析或未知版本时为 null。 */
    val active: AppTargets? get() = resolved

    /** 在 `onPackageReady` 传入，不依赖此时尚未创建的 Application。 */
    fun configureDexPaths(baseApk: String?, splitApks: Array<String>?) {
        val paths = buildList {
            if (!baseApk.isNullOrBlank()) add(baseApk)
            splitApks?.filterTo(this) { it.isNotBlank() }
        }.distinct()
        if (paths == dexPaths) return
        synchronized(this) {
            if (paths != dexPaths) {
                dexPaths = paths
                discoveredClamps = null
                clampDiscoveryAttempted = false
            }
        }
    }

    /**
     * 挑出匹配当前输入法版本的档位并缓存。
     *
     * 只认「最高分足够高，且明显领先第二名」的结果：分数并列说明分不开，
     * 宁可不认，也不能认错 —— 认错就等于把整套混淆名指到无关类上，
     * 表现正是「按类名定位的功能突然全失效」这种最难查的故障。
     *
     * @return 匹配到的档位；null 表示当前版本尚未记录或无法区分（模块降级，
     *         资源类功能照常工作）。
     */
    fun resolve(cl: ClassLoader): AppTargets? {
        resolved?.let { return it }
        synchronized(this) {
            resolved?.let { return it }
            if (attempted) return null
            attempted = true

            val scored = known
                .map { it to it.score(cl) }
                .sortedByDescending { it.second }
            for ((t, s) in scored) {
                L.i("event=target_probe version=${t.label} score=$s/$MAX_SCORE")
            }

            val best = scored.firstOrNull() ?: return null
            val runnerUp = scored.getOrNull(1)?.second ?: 0
            val (target, score) = best

            if (score < MIN_ACCEPT) {
                L.w(
                    "event=target_unknown 未记录当前输入法版本的混淆名" +
                        "（最高分 $score/$MAX_SCORE，低于 $MIN_ACCEPT）；" +
                        "按类名定位的功能不可用，资源类功能不受影响"
                )
                // 允许外层重试：类可能只是还没加载好。真正的新版本重试几次依旧会走到这里，
                // 代价只是多扫一遍候选类，远小于「一次偶发失败就永久降级」。
                attempted = false
                return null
            }
            if (score == runnerUp) {
                L.w(
                    "event=target_ambiguous 最高分并列 $score/$MAX_SCORE" +
                        "（${scored.joinToString { "${it.first.label}=${it.second}" }}）；" +
                        "为避免指错混淆名，按类名定位的功能本次不启用"
                )
                attempted = false
                return null
            }

            L.i("event=target_resolved version=${target.label} score=$score/$MAX_SCORE runner_up=$runnerUp")
            resolved = target
            return target
        }
    }

    /**
     * 单独解析悬浮键盘的夹取工具。
     *
     * 已知版本直接使用表中目标；若以后只有其它类改名，则从所有已知夹取目标中独立匹配。
     * 若连夹取类也改名，则枚举目标 APK 中的短混淆类，并通过方法签名和实际夹取语义发现
     * `(float,float,float)->float` / `(int,int,int)->int` 方法，不再要求每次先补整套版本档。
     */
    fun resolveClampTargets(cl: ClassLoader, selected: AppTargets?): List<ClampTarget> {
        selected?.toClampTarget()?.takeIf { it.matches(cl) }?.let { return listOf(it) }

        val knownMatches = known
            .map { it.toClampTarget() }
            .distinctBy { it.className to it.floatMethod }
            .filter { it.matches(cl) }
        if (knownMatches.isNotEmpty()) {
            L.i("event=clamp_resolved_independently targets=${knownMatches.joinToString { it.className }}")
            return knownMatches
        }

        discoveredClamps?.let { return it }
        synchronized(this) {
            discoveredClamps?.let { return it }
            if (clampDiscoveryAttempted || dexPaths.isEmpty()) return emptyList()
            clampDiscoveryAttempted = true
            val found = discoverClampTargets(cl, dexPaths)
            discoveredClamps = found
            if (found.isEmpty()) {
                L.w("event=clamp_discovery_empty paths=${dexPaths.size}")
            } else {
                L.i(
                    "event=clamp_discovered targets=" +
                        found.joinToString { "${it.className}.${it.floatMethod}/${it.intMethod}" },
                )
            }
            return found
        }
    }

    private fun AppTargets.toClampTarget() = ClampTarget(
        label = label,
        className = clampUtil,
        floatMethod = mClampFloat,
        intMethod = mClampInt,
    )

    private fun ClampTarget.matches(cl: ClassLoader): Boolean {
        val cls = loadOrNull(cl, className) ?: return false
        return try {
            cls.declaredMethods.any { it.name == floatMethod && it.isFloatClampShape() } &&
                cls.declaredMethods.any { it.name == intMethod && it.isIntClampShape() }
        } catch (_: Throwable) {
            false
        }
    }

    /** R8 的短类名过滤；避免未知版本时反射加载整个输入法及第三方 SDK。 */
    private val shortObfuscatedName = Regex("^[a-z][a-z0-9]{0,5}\\.[a-z][a-z0-9]{0,5}$")

    private fun discoverClampTargets(cl: ClassLoader, paths: List<String>): List<ClampTarget> {
        val classNames = linkedSetOf<String>()
        for (path in paths) {
            var dex: DexFile? = null
            try {
                dex = DexFile(path)
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    if (shortObfuscatedName.matches(name)) classNames += name
                }
            } catch (t: Throwable) {
                L.w("event=dex_scan_failed path=$path error=${t.javaClass.simpleName}")
            } finally {
                runCatching { dex?.close() }
            }
        }

        val found = mutableListOf<ClampTarget>()
        for (name in classNames) {
            val cls = loadOrNull(cl, name) ?: continue
            val methods = try {
                cls.declaredMethods
            } catch (_: Throwable) {
                continue
            }
            val floatMethods = methods.filter { it.isFloatClampShape() }
            val intMethods = methods.filter { it.isIntClampShape() }
            val floatClamp = floatMethods.firstOrNull { behavesAsFloatClamp(it) } ?: continue
            val intClamp = intMethods.firstOrNull { behavesAsIntClamp(it) } ?: continue
            found += ClampTarget(
                label = "auto",
                className = name,
                floatMethod = floatClamp.name,
                intMethod = intClamp.name,
            )
        }
        return found.distinctBy { Triple(it.className, it.floatMethod, it.intMethod) }
    }

    private fun Method.isFloatClampShape(): Boolean =
        Modifier.isStatic(modifiers) && parameterCount == 3 &&
            returnType == Float::class.javaPrimitiveType &&
            parameterTypes.all { it == Float::class.javaPrimitiveType }

    private fun Method.isIntClampShape(): Boolean =
        Modifier.isStatic(modifiers) && parameterCount == 3 &&
            returnType == Int::class.javaPrimitiveType &&
            parameterTypes.all { it == Int::class.javaPrimitiveType }

    private fun behavesAsFloatClamp(method: Method): Boolean = runCatching {
        method.isAccessible = true
        val below = method.invoke(null, -1f, 0f, 1f) as Float
        val middle = method.invoke(null, 0.5f, 0f, 1f) as Float
        val above = method.invoke(null, 2f, 0f, 1f) as Float
        below == 0f && middle == 0.5f && above == 1f
    }.getOrDefault(false)

    private fun behavesAsIntClamp(method: Method): Boolean = runCatching {
        method.isAccessible = true
        val below = method.invoke(null, -1, 0, 1) as Int
        val middle = method.invoke(null, 1, 0, 2) as Int
        val above = method.invoke(null, 3, 0, 2) as Int
        below == 0 && middle == 1 && above == 2
    }.getOrDefault(false)
}

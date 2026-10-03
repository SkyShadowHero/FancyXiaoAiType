package com.skyler.fancytype

/**
 * 按输入法版本分档的混淆目标。
 *
 * ## 为什么需要分档
 *
 * R8 每次发版都会重命名混淆类，所以「硬编码一套名字」必然导致
 * **新版本一更新就整块失效**（材质、分离键盘开关等全挂），而且旧版本也没法兼顾。
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
    /** 集合包含判定工具类（材质放行判定的宿主） */
    val collectionsUtil: String,
    /** 材质状态机（拦截范围的宿主） */
    val materialHelper: String,
    /** 材质描述符应用入口 */
    val materialApplier: String,
    /** 离屏填充能力门 */
    val advancedVisualGate: String,
    /** 模糊能力位所在类 */
    val blurGate: String,
    /** 夹取工具（Kotlin `coerceIn` 的实现）所在类 */
    val clampUtil: String,

    // ---- 方法名 ----
    /** `static boolean (String, boolean)` */
    val mPrefBool: String,
    /** `static boolean ()` 读取 split_keyboard_enabled */
    val mSplitGetter: String,
    /** 材质状态机里的 0 参 void 方法（拦截范围锚点） */
    val mMaterialApply: String,
    /** 材质应用入口的方法（`(View, …) -> void`） */
    val mApplyMaterial: String,
    /** 夹取工具里 `(float, float, float) -> float` */
    val mClampFloat: String,
    /** 夹取工具里 `(int, int, int) -> int` */
    val mClampInt: String,

    // ---- 诊断字段名 ----
    val fOffscreenFill: String,
    val fBlurSupported: String,
    val fBlurVersion: String,
    val fBlurStatusDefault: String,
    val fBionicMaterial: String,
) {

    /**
     * 用结构特征给本档打分（0..8）。分数最高且大于 0 的档位即为当前输入法版本。
     *
     * 每一项都校验「形状」而不是「名字」：
     * 例如判定工具类必须真的有一个 `(Iterable, Object) -> boolean` 的静态方法，
     * 同名但结构不符的无关类不会得分。
     */
    fun score(cl: ClassLoader): Int {
        var s = 0
        if (hasIterableContainsShape(cl, collectionsUtil)) s++
        if (hasPrefFacadeShape(cl, prefFacade)) s++
        if (hasNoArgVoidMethod(cl, materialHelper)) s++
        if (has13ParamCtor(cl, padSplitDims)) s++
        if (hasStaticBooleanField(cl, advancedVisualGate)) s++
        if (hasStaticBooleanField(cl, blurGate)) s++
        if (hasViewArgVoidMethod(cl, materialApplier)) s++
        if (hasClampShape(cl, clampUtil)) s++
        return s
    }
}

// ---------------------------------------------------------------- 结构校验

private fun loadOrNull(cl: ClassLoader, name: String): Class<*>? =
    if (name.isEmpty()) null else try {
        cl.loadClass(name)
    } catch (t: Throwable) {
        null
    }

/** 该类的静态方法里是否有 `(Iterable, Object) -> boolean` —— 集合包含判定的形状 */
private fun hasIterableContainsShape(cl: ClassLoader, name: String): Boolean {
    val c = loadOrNull(cl, name) ?: return false
    return try {
        c.declaredMethods.any {
            it.parameterCount == 2 &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[0] == Iterable::class.java &&
                it.parameterTypes[1] == Any::class.java
        }
    } catch (t: Throwable) {
        false
    }
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

/** 是否有 0 参 void 方法（材质状态机的锚点方法就是这种形状） */
private fun hasNoArgVoidMethod(cl: ClassLoader, name: String): Boolean {
    val c = loadOrNull(cl, name) ?: return false
    return try {
        c.declaredMethods.any { it.parameterCount == 0 && it.returnType == Void.TYPE }
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

/** 是否有静态 boolean 字段（能力门类都是这种形状） */
private fun hasStaticBooleanField(cl: ClassLoader, name: String): Boolean {
    val c = loadOrNull(cl, name) ?: return false
    return try {
        c.declaredFields.any {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.type == Boolean::class.javaPrimitiveType
        }
    } catch (t: Throwable) {
        false
    }
}

/** 是否有 `(View, 某类型) -> void` 方法（材质应用入口的形状） */
private fun hasViewArgVoidMethod(cl: ClassLoader, name: String): Boolean {
    val c = loadOrNull(cl, name) ?: return false
    return try {
        c.declaredMethods.any {
            it.parameterCount == 2 &&
                it.returnType == Void.TYPE &&
                it.parameterTypes[0] == android.view.View::class.java
        }
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
            it.parameterCount == 3 &&
                it.returnType == Float::class.javaPrimitiveType &&
                it.parameterTypes.all { p -> p == Float::class.javaPrimitiveType }
        }
        val i = c.declaredMethods.any {
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

    /** 0.2.910 与 0.2.974 的混淆名相同，合并为一档。 */
    private val V910_974 = AppTargets(
        label = "0.2.910 ~ 0.2.974",
        padSplitDims = "la.n",
        prefFacade = "n9.e",
        collectionsUtil = "pc.m",
        materialHelper = "bb.b0",
        materialApplier = "xe.b",
        advancedVisualGate = "z7.a",
        blurGate = "xe.b",
        clampUtil = "z7.s",
        mPrefBool = "a",
        mSplitGetter = "o",
        mMaterialApply = "j",
        mApplyMaterial = "a",
        mClampFloat = "t",
        mClampInt = "u",
        fOffscreenFill = "f18746a",
        fBlurSupported = "f18279a",
        fBlurVersion = "f18281d",
        fBlurStatusDefault = "f18280c",
        fBionicMaterial = "b",
    )

    /** 0.2.1053：包名与类名整体重排，并于本版新增了材质黑名单判定。 */
    private val V1053 = AppTargets(
        label = "0.2.1053",
        padSplitDims = "ka.n",
        prefFacade = "m9.e",
        collectionsUtil = "oc.m",
        materialHelper = "ab.i0",
        materialApplier = "we.h",
        advancedVisualGate = "y7.a",
        blurGate = "we.b",
        clampUtil = "ed.a",
        mPrefBool = "a",
        mSplitGetter = "q",
        mMaterialApply = "k",
        mApplyMaterial = "x",
        mClampFloat = "j",
        mClampInt = "k",
        fOffscreenFill = "f17073a",
        fBlurSupported = "f16477a",
        fBlurVersion = "f16479d",
        fBlurStatusDefault = "f16478c",
        fBionicMaterial = "b",
    )

    val known: List<AppTargets> = listOf(V1053, V910_974)

    private const val MAX_SCORE = 8

    /**
     * 接受一档所需的最低分。
     *
     * 留 2 分的余量：某一版改掉一两个类的形状时仍能命中，
     * 但「只碰巧对上两三处」的无关混淆类会被挡在门外。
     */
    private const val MIN_ACCEPT = 6

    @Volatile
    private var resolved: AppTargets? = null

    @Volatile
    private var attempted = false

    /** 已经挑中的档位；未解析或未知版本时为 null。 */
    val active: AppTargets? get() = resolved

    /**
     * 挑出匹配当前输入法版本的档位并缓存。
     *
     * 只认「最高分足够高，且明显领先第二名」的结果：分数并列说明分不开，
     * 宁可不认，也不能认错 —— 认错就等于把整套混淆名指到无关类上，
     * 表现正是「材质/分离键盘突然全失效」这种最难查的故障。
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
                return null
            }
            if (score == runnerUp) {
                L.w(
                    "event=target_ambiguous 最高分并列 $score/$MAX_SCORE" +
                        "（${scored.joinToString { "${it.first.label}=${it.second}" }}）；" +
                        "为避免指错混淆名，按类名定位的功能本次不启用"
                )
                return null
            }

            L.i("event=target_resolved version=${target.label} score=$score/$MAX_SCORE runner_up=$runnerUp")
            resolved = target
            return target
        }
    }
}

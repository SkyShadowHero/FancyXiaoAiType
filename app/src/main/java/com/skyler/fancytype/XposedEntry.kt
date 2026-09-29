package com.skyler.fancytype

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 超级小爱输入法增强模块。
 *
 * 功能 1：调整分离键盘中心间隙（滑块）；间隙减小后两半更靠近屏幕中部。
 * 功能 2：竖屏强制普通键盘（开关，默认关闭 = 保持默认行为）。
 * 功能 3：按键圆角 / 键高 / 键距（横竖屏分设）。
 * 功能 4：设置页右上角「强制关闭输入法」——写入重启信号，本进程在键盘再次弹出时自杀重启。
 * 功能 5：超级材质——接管「哪些应用能用毛玻璃键盘背景」的判定，支持强制全部 / 手动选择。
 *
 * Hook 策略：改「读取入口」而非改磁盘/资源，避免缓存与落盘副作用。
 */
class XposedEntry : XposedModule() {

    private val installed = AtomicBoolean(false)

    @Volatile
    private var processName: String? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        L.i("event=module_loaded process=${param.processName} api=$apiVersion framework=$frameworkName")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != Target.PACKAGE) return
        L.i("event=package_ready package=${param.packageName} process=$processName")
        ConfigLoader.attach(getRemotePreferences(PrefKeys.GROUP))
        installHooks(param.classLoader)
    }

    // ------------------------------------------------------------------ 安装

    private fun installHooks(cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) {
            L.i("event=install_skipped reason=already_installed")
            return
        }
        var ok = 0
        ok += hookResourcesDimension(cl)
        ok += hookPadSplitDims(cl)
        ok += hookSplitEnabledGetter(cl)
        ok += hookPrefBool(cl)
        ok += hookMaterial(cl)
        // 顺序要紧：先把属性 Hook 装好，再做诊断。
        // 诊断会读 z7.a 的静态字段，那一步会触发它的 <clinit>，
        // 一旦在 Hook 之前触发，离屏填充门就被永久算成 false 了。
        ok += hookSystemPropertiesForMaterial(cl)
        ok += hookMaterialDiagnostics(cl)
        L.i("event=install_done hooks=$ok process=$processName")
        if (ok == 0) installed.set(false)
    }

    /**
     * 尺寸覆写的真正拦截点。
     *
     * 注意：分离键盘的横屏间隙走的是 `bb.h1.h()`：
     *     service.getResources().getDimension(resId) / density
     * 它**不经过** Compose 的尺寸解析器 a.a.t，所以只 Hook `a.a.t` 无效（已实测确认）。
     * 这里直接 Hook Resources 的取尺寸方法，覆盖全部调用路径。
     */
    private fun hookResourcesDimension(cl: ClassLoader): Int {
        var ok = 0
        try {
            val resourcesCls = android.content.res.Resources::class.java

            val getDimension = resourcesCls.getDeclaredMethod("getDimension", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
            hook(getDimension)
                .setId("res_getDimension")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val resId = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                    val original = chain.proceed()
                    overrideDimension(chain.getThisObject() as? android.content.res.Resources, resId, original, false)
                }
            ok++

            val getDimensionPixelSize =
                resourcesCls.getDeclaredMethod("getDimensionPixelSize", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
            hook(getDimensionPixelSize)
                .setId("res_getDimensionPixelSize")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val resId = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                    val original = chain.proceed()
                    overrideDimension(chain.getThisObject() as? android.content.res.Resources, resId, original, true)
                }
            ok++

            // getDimensionPixelOffset 与应用里 28 处调用点有关（截断取整与 getDimensionPixelSize 不同），
            // 悬浮栏/候选词的 Compose 尺寸也可能走这条路，一并接管以保证覆写没有漏网。
            val getDimensionPixelOffset =
                resourcesCls.getDeclaredMethod("getDimensionPixelOffset", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
            hook(getDimensionPixelOffset)
                .setId("res_getDimensionPixelOffset")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val resId = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                    val original = chain.proceed()
                    overrideDimension(chain.getThisObject() as? android.content.res.Resources, resId, original, true)
                }
            ok++
        } catch (t: Throwable) {
            L.e("event=hook_failed target=resources_dimension", t)
        }
        return ok
    }


    /**
     * 定位 `pc.m` 里的「集合包含」判定方法。
     *
     * **不能按名字找**：0.2.910 叫 `L0`，0.2.974 改成了 `x0`（0.2.974 上按 `L0` 找会落空，
     * 表现就是超级材质整个失效）。签名 `(Iterable, Object) -> boolean` 在这些版本里
     * 都是该类中唯一的一个，所以按签名定位，改名也不受影响。
     */
    private fun findIterableContains(cls: Class<*>?): Method? {
        if (cls == null) return null
        val picked = try {
            cls.declaredMethods.firstOrNull {
                it.parameterCount == 2 &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[0] == Iterable::class.java &&
                    it.parameterTypes[1] == Any::class.java
            }
        } catch (t: Throwable) {
            L.w("event=methods_scan_failed class=${cls.name}")
            return null
        }
        if (picked == null) {
            L.w("event=method_missing class=${cls.name} signature=(Iterable,Object)->boolean")
            return null
        }
        picked.isAccessible = true
        L.i(
            "event=method_resolved class=${cls.name} method=${picked.name}" +
                "(java.lang.Iterable,java.lang.Object) -> boolean (按签名匹配)"
        )
        return picked
    }

    /** 资源名 -> ID（运行时解析，缓存）。ID 随版本变化，名字稳定。 */
    private val nameToId = HashMap<String, Int>()
    private var namesResolved = false

    /** 用应用自己的 Resources 把资源名解析成 ID；失败回退硬编码（0.2.910 实测值）。 */
    private fun resolveNames(res: android.content.res.Resources?) {
        if (namesResolved || res == null) return
        namesResolved = true
        var ok = 0
        for ((name, fallback) in Target.RES_NAMES) {
            val id = try {
                val a = res.getIdentifier(name, "dimen", Target.PACKAGE)
                if (a != 0) a else res.getIdentifier(name, "dimen", null)
            } catch (t: Throwable) {
                0
            }
            val finalId = if (id != 0) id else fallback
            nameToId[name] = finalId
            if (id != 0) ok++
            if (id != 0 && id != fallback) {
                L.i("event=resid_remapped name=$name hardcoded=0x${fallback.toString(16)} actual=0x${id.toString(16)}")
            }
        }
        L.i("event=resid_resolved by_name=$ok/${Target.RES_NAMES.size}")

        // 外边距资源名一并登记进 nameToId：解析不到就留 0，覆写判定靠 idIn 比较，
        // 而资源 ID 不可能是 0，所以不会误命中。
        var marginOk = 0
        for (name in Target.MARGIN_NAMES) {
            val id = try {
                res.getIdentifier(name, "dimen", Target.PACKAGE)
            } catch (t: Throwable) {
                0
            }
            nameToId[name] = id
            if (id != 0) marginOk++
        }
        L.i("event=margin_resolved by_name=$marginOk/${Target.MARGIN_NAMES.size}")

        // 悬浮候选词窗口 / 悬浮输入法栏的资源名同样按名登记。
        // 一律不写硬编码兜底：这些 ID 在 0.2.974 上相对 0.2.910 已整体 +22，
        // 兜底值只会误导；解析不到就留 0，而资源 ID 不可能是 0，所以不会误命中。
        var floatingOk = 0
        for (name in Target.FLOATING_NAMES) {
            val id = try {
                res.getIdentifier(name, "dimen", Target.PACKAGE)
            } catch (t: Throwable) {
                0
            }
            nameToId[name] = id
            if (id != 0) floatingOk++
        }
        L.i("event=floating_resolved by_name=$floatingOk/${Target.FLOATING_NAMES.size}")
    }

    /** 该资源 ID 是否属于某个名字集合 */
    private fun idIn(resId: Int, names: Array<String>): Boolean =
        names.any { nameToId[it] == resId }

    /**
     * 工具栏展开态的实际高度（dip）。
     *
     * 外层高度是写死的 `movable_bar_height`，而上下 padding 加在它**内部**，
     * 所以高度必须跟着 padding 一起变，否则内容会被固定高度裁掉
     * （表现：只看到上边距、下面一片空）。
     *
     *     height = 默认 52 + 2 × (上下内边距 − 默认 11)
     *
     * 上下内边距回默认 11dp 时，高度正好回到 52dp。
     */
    private fun toolbarHeightDp(cfg: ConfigLoader.Cfg): Float =
        (
            Target.ORIGINAL_TOOLBAR_HEIGHT_DP +
                2f * (cfg.toolbarVPaddingDp - PrefKeys.TOOLBAR_VPADDING_DEFAULT)
            ).coerceAtLeast(8f)

    /**
     * 左侧拖拽竖条的高度：按工具栏高度等比缩放。
     *
     * 默认比例 20 / 52，工具栏变高时竖条一起变长，观感才协调。
     */
    private fun toolbarHandleHeightDp(cfg: ConfigLoader.Cfg): Float =
        Target.ORIGINAL_TOOLBAR_HANDLE_HEIGHT_DP *
            (toolbarHeightDp(cfg) / Target.ORIGINAL_TOOLBAR_HEIGHT_DP)

    /**
     * 拖拽竖条的顶部偏移：`(工具栏高度 − 竖条高度) / 2`，即垂直居中。
     *
     * 默认值 16dp 正好等于 (52 − 20) / 2，说明原布局就是居中设计的；
     * 这里把这个关系显式写出来，工具栏变高时竖条才不会偏上。
     */
    private fun toolbarHandleOffsetTopDp(cfg: ConfigLoader.Cfg): Float =
        (toolbarHeightDp(cfg) - toolbarHandleHeightDp(cfg)) / 2f

    /**
     * 缩放型覆写：返回一个倍率，由调用方乘到资源的**原始值**上（而不是写死新值）。
     *
     * 目前只有收缩态（mini）用：整套几何按展开态高度等比缩放，
     *     factor = 展开态高度 / 52
     * 这样 App 若在某版本改了这些默认值，缩放依然跟着走，不会失配。
     * 默认设置下 factor = 1，直接返回值就是原样透传。
     *
     * 注意：收缩态**不跟随**「竖条左边距」。曾试过用
     * `movable_bar_mini_h_padding` 去驱动，但那是左右对称的 padding 且收缩态宽度固定，
     * 会连带把内容挤扁、还得为此改宽度，牵动太大，已撤销。
     * 现在「竖条左边距」只作用于展开态。
     */
    private fun resolveScale(cfg: ConfigLoader.Cfg, resId: Int): Float? = when {
        // ---- 悬浮候选窗口的三个行高：跟着各自字号等比走 ----
        // 独立开关，放在最前面，因此不受「启用悬浮键盘调节」影响。
        // 只放大字号而不动行高，字的上下边缘会被行高裁掉；这里按
        // 「用户字号 / 原字号」的比例缩放**任一版本里的原行高**，不写死目标值。
        resId == nameToId[Target.NAME_CAND_WIN_CAND_LINE_HEIGHT] ->
            if (cfg.candFontEnabled) cfg.candWinCandFontDp / Target.ORIGINAL_CAND_FONT_DP else null
        resId == nameToId[Target.NAME_CAND_WIN_NUMBER_LINE_HEIGHT] ->
            if (cfg.candFontEnabled) cfg.candWinNumberFontDp / Target.ORIGINAL_NUMBER_FONT_DP else null
        resId == nameToId[Target.NAME_CAND_WIN_PINYIN_LINE_HEIGHT] ->
            if (cfg.candFontEnabled) cfg.candWinPinyinFontDp / Target.ORIGINAL_PINYIN_FONT_DP else null

        !cfg.floatBarEnabled -> null
        idIn(resId, Target.FLOATBAR_MINI_SCALED_NAMES) ->
            toolbarHeightDp(cfg) / Target.ORIGINAL_TOOLBAR_HEIGHT_DP
        else -> null
    }

    /**
     * 按资源 ID 覆写尺寸（dp -> px），数据驱动。支持五类：
     *  - 中心间隙（横屏 / 竖屏各一套）
     *  - 按键圆角（不分横竖屏）
     *  - 按键间距 / 键高（横竖屏各一套）
     *  - 悬浮候选词窗口（触屏键盘的候选栏：圆角 / 候选间距）
     *  - 悬浮键盘（圆角共用；工具栏阴影与内边距；候选窗口阴影与间距）
     * 返回 null 表示「不改」。
     */
    private fun resolveOverride(cfg: ConfigLoader.Cfg, resId: Int): Float? = when {
        // ---- 悬浮候选窗口字号覆盖：独立开关 ----
        // 放在最前面（when 按顺序求值），所以即使「启用悬浮键盘调节」关着也能单独生效。
        // 这三个资源实测都是悬浮候选窗口专属，虚拟键盘那边不受影响。
        resId == nameToId[Target.NAME_CAND_WIN_CAND_FONT] ->
            if (cfg.candFontEnabled) cfg.candWinCandFontDp else null
        resId == nameToId[Target.NAME_CAND_WIN_NUMBER_FONT] ->
            if (cfg.candFontEnabled) cfg.candWinNumberFontDp else null
        resId == nameToId[Target.NAME_CAND_WIN_PINYIN_FONT] ->
            if (cfg.candFontEnabled) cfg.candWinPinyinFontDp else null

        idIn(resId, Target.GAP_LAND_NAMES) -> if (cfg.gapEnabled) cfg.gapLand else null
        idIn(resId, Target.GAP_PORT_NAMES) -> if (cfg.gapEnabled) cfg.gapPort else null
        idIn(resId, Target.CORNER_NAMES) -> if (cfg.cornerEnabled) cfg.cornerDp else null
        resId == nameToId[Target.NAME_BUBBLE_CORNER] -> if (cfg.cornerEnabled) cfg.bubbleCornerDp else null

        idIn(resId, Target.MARGIN_HORIZONTAL_NAMES) -> if (cfg.marginEnabled) cfg.marginHorizontalDp else null
        idIn(resId, Target.MARGIN_BOTTOM_NAMES) -> if (cfg.marginEnabled) cfg.marginBottomDp else null

        resId == nameToId[Target.NAME_KEY_HEIGHT_LAND] -> if (cfg.spaceEnabled) cfg.spaceKeyHLand else null
        resId == nameToId[Target.NAME_KEY_HEIGHT_PORT] -> if (cfg.spaceEnabled) cfg.spaceKeyHPort else null
        resId == nameToId[Target.NAME_KEY_SPACING_LAND] -> if (cfg.spaceEnabled) cfg.spaceKeyHorizLand else null
        resId == nameToId[Target.NAME_KEY_SPACING_PORT] -> if (cfg.spaceEnabled) cfg.spaceKeyHorizPort else null
        resId == nameToId[Target.NAME_ROW_SPACING_LAND] -> if (cfg.spaceEnabled) cfg.spaceRowLand else null
        resId == nameToId[Target.NAME_ROW_SPACING_PORT] -> if (cfg.spaceEnabled) cfg.spaceRowPort else null

        // ---- 悬浮候选词窗口 ----
        resId == nameToId[Target.NAME_CANDIDATE_CORNER] ->
            if (cfg.candidateEnabled) cfg.candidateCornerDp else null
        resId == nameToId[Target.NAME_CANDIDATE_SPACING] ->
            if (cfg.candidateEnabled) cfg.candidateSpacingDp else null

        // ---- 悬浮键盘：圆角（工具栏与候选窗口共用）----
        idIn(resId, Target.FLOATBAR_CORNER_NAMES) ->
            if (cfg.floatBarEnabled) cfg.floatBarCornerDp else null

        // ---- 悬浮键盘：工具栏 ----
        resId == nameToId[Target.NAME_TOOLBAR_SHADOW] ->
            if (cfg.floatBarEnabled) cfg.toolbarShadowDp else null
        resId == nameToId[Target.NAME_TOOLBAR_BUTTON_SPACING] ->
            if (cfg.floatBarEnabled) cfg.toolbarButtonSpacingDp else null
        resId == nameToId[Target.NAME_TOOLBAR_VPADDING] ->
            if (cfg.floatBarEnabled) cfg.toolbarVPaddingDp else null

        // 工具栏高度必须跟着上下内边距同步，保持「内容可用高度」恒定：
        // 外层高度是固定的 movable_bar_height，而上下 padding 加在它**内部**，
        // 只加大 padding 会被固定高度裁掉 —— 表现就是「只看到上边距，下面一片空」。
        resId == nameToId[Target.NAME_TOOLBAR_HEIGHT] ->
            if (cfg.floatBarEnabled) toolbarHeightDp(cfg) else null

        // 拖拽竖条跟着工具栏高度等比缩放并保持垂直居中，
        // 否则工具栏变高后竖条仍停在固定 16dp 偏移处，看着又短又偏。
        resId == nameToId[Target.NAME_TOOLBAR_HANDLE_HEIGHT] ->
            if (cfg.floatBarEnabled) toolbarHandleHeightDp(cfg) else null
        resId == nameToId[Target.NAME_TOOLBAR_HANDLE_OFFSET_TOP] ->
            if (cfg.floatBarEnabled) toolbarHandleOffsetTopDp(cfg) else null
        // 拖拽竖条的左边距，独立于工具栏内容的左右内边距
        resId == nameToId[Target.NAME_TOOLBAR_HANDLE_OFFSET_START] ->
            if (cfg.floatBarEnabled) cfg.toolbarHandleOffsetStartDp else null

        resId == nameToId[Target.NAME_TOOLBAR_PADDING_START] ->
            if (cfg.floatBarEnabled) cfg.toolbarPaddingStartDp else null
        resId == nameToId[Target.NAME_TOOLBAR_PADDING_END] ->
            if (cfg.floatBarEnabled) cfg.toolbarPaddingEndDp else null
        resId == nameToId[Target.NAME_TOOLBAR_BORDER_WIDTH] ->
            if (cfg.floatBarEnabled) cfg.toolbarBorderWidthDp else null

        // ---- 悬浮键盘：候选窗口 ----
        // 宽度：实际窗口宽 = min(该资源, 可用宽度)，展开按钮另占 29dp
        resId == nameToId[Target.NAME_CAND_WIN_MAX_WIDTH] ->
            if (cfg.floatBarEnabled) cfg.candWinMaxWidthDp else null
        // 左右内边距：拼音行与候选行共用，并参与上面的宽度计算
        resId == nameToId[Target.NAME_CAND_WIN_H_PADDING] ->
            if (cfg.floatBarEnabled) cfg.candWinHPaddingDp else null
        resId == nameToId[Target.NAME_CAND_WIN_PINYIN_TOP] ->
            if (cfg.floatBarEnabled) cfg.candWinPinyinTopDp else null
        resId == nameToId[Target.NAME_CAND_WIN_PINYIN_BOTTOM] ->
            if (cfg.floatBarEnabled) cfg.candWinPinyinBottomDp else null
        resId == nameToId[Target.NAME_CAND_WIN_SHADOW] ->
            if (cfg.floatBarEnabled) cfg.candWinShadowDp else null
        resId == nameToId[Target.NAME_CAND_WIN_SPACING] ->
            if (cfg.floatBarEnabled) cfg.candWinSpacingDp else null
        // 悬浮候选窗口的候选词字号（行高由 resolveScale 按同比例联动，无需单独设）
        // —— 这三个已移到 when 的最前面，见 resolveOverride 开头
        resId == nameToId[Target.NAME_CAND_WIN_BORDER_WIDTH] ->
            if (cfg.floatBarEnabled) cfg.candWinBorderWidthDp else null
        idIn(resId, Target.CAND_WIN_ROW_PADDING_NAMES) ->
            if (cfg.floatBarEnabled) cfg.candWinRowPaddingDp else null

        else -> null
    }

    private fun overrideDimension(
        resources: android.content.res.Resources?,
        resId: Int,
        original: Any?,
        asInt: Boolean,
    ): Any? {
        resolveNames(resources)
        val cfg = ConfigLoader.snapshot()

        // 1) 比例缩放（收缩态几何）：乘在**原始值**上，不写死新值。
        //    默认设置下 factor = 1，直接跳过，等于原样透传。
        val factor = resolveScale(cfg, resId)
        if (factor != null && factor != 1f) {
            val origPx = (original as? Number)?.toFloat()
            if (origPx != null) {
                val scaled = (origPx * factor).coerceAtLeast(0f)
                L.sampled("mini_scale", limit = 12) {
                    "event=mini_scale resId=0x${resId.toString(16)} " +
                        "orig=$original factor=$factor -> $scaled"
                }
                return if (asInt) scaled.toInt() else scaled
            }
        }

        // 2) 绝对值覆写（dp × density）
        val targetDp = resolveOverride(cfg, resId)
        if (targetDp == null) {
            L.sampled("dimcall", limit = 8) {
                "event=dimen_call resId=0x${resId.toString(16)} value=$original (passthrough)"
            }
            return original
        }
        val density = resources?.displayMetrics?.density ?: 1f
        val px = targetDp * density
        L.i(
            "event=dimen_override resId=0x${resId.toString(16)} orig=$original " +
                "newPx=$px (${targetDp}dp @density=$density)"
        )
        return if (asInt) px.toInt() else px
    }

    private fun loadClass(cl: ClassLoader, name: String): Class<*>? = try {
        cl.loadClass(name)
    } catch (t: Throwable) {
        L.w("event=class_missing name=$name (可能是版本不符，已降级)")
        null
    }

    /**
     * 按「方法名 + 参数个数」定位，避免参数类型因 ClassLoader 不同而解析失败。
     * extraCheck 用于在多个同名重载中挑出目标。
     */
    private fun findMethodByArity(
        cls: Class<*>?,
        name: String,
        arity: Int,
        extraCheck: (Method) -> Boolean = { true }
    ): Method? {
        if (cls == null) return null
        val candidates = try {
            cls.declaredMethods.filter { it.name == name && it.parameterCount == arity }
        } catch (t: Throwable) {
            L.w("event=methods_scan_failed class=${cls.name}")
            return null
        }
        val picked = candidates.firstOrNull { extraCheck(it) }
        if (picked == null) {
            L.w(
                "event=method_missing class=${cls.name} name=$name arity=$arity " +
                    "candidates=${candidates.map { c -> c.parameterTypes.joinToString(",") { it.name } }}"
            )
            return null
        }
        picked.isAccessible = true
        L.i(
            "event=method_resolved class=${cls.name} method=$name(" +
                picked.parameterTypes.joinToString(",") { it.name } + ") -> ${picked.returnType.name}"
        )
        return picked
    }

    /** la.n = PadSplitQwertyDims：构造时打印分离键盘几何，用于核对间隙是否真的生效。 */
    private fun hookPadSplitDims(cl: ClassLoader): Int {
        val cls = loadClass(cl, Target.CLS_PAD_SPLIT_DIMS) ?: return 0
        val ctor = try {
            cls.declaredConstructors.firstOrNull { it.parameterCount == 13 }?.also { it.isAccessible = true }
        } catch (t: Throwable) {
            null
        } ?: return 0
        return try {
            hook(ctor)
                .setId("pad_split_dims")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val a = chain.args
                    L.sampled("splitdims") {
                        "event=split_dims keyHeight=${a.getOrNull(0)} letterKeyW=${a.getOrNull(4)} " +
                            "centerGap=${a.getOrNull(3)} leftSpaceW=${a.getOrNull(11)} rightSpaceW=${a.getOrNull(12)}"
                    }
                    chain.proceed()
                }
            1
        } catch (t: Throwable) {
            L.e("event=hook_failed target=pad_split_dims", t); 0
        }
    }

    /** 功能 2 的读取入口：n9.e.o()。 */
    private fun hookSplitEnabledGetter(cl: ClassLoader): Int {
        val cls = loadClass(cl, Target.CLS_PREF_FACADE) ?: return 0
        val m = findMethodByArity(cls, Target.M_SPLIT_ENABLED_GETTER, 0) {
            it.returnType == Boolean::class.javaPrimitiveType
        } ?: return 0
        return try {
            hook(m)
                .setId("split_enabled")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val raw = (chain.proceed() as? Boolean) ?: false
                    val final = PortraitGuard.apply(raw)
                    L.sampled("splitgate") {
                        "event=split_gate raw=$raw final=$final landscape=${PortraitGuard.isLandscape()}"
                    }
                    final
                }
            1
        } catch (t: Throwable) {
            L.e("event=hook_failed target=split_enabled", t); 0
        }
    }

    /** 覆盖走通用入口 n9.e.a(String,boolean) 读取 split_keyboard_enabled 的调用点。 */
    private fun hookPrefBool(cl: ClassLoader): Int {
        val cls = loadClass(cl, Target.CLS_PREF_FACADE) ?: return 0
        val m = findMethodByArity(cls, Target.M_PREF_BOOL, 2) {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return 0
        return try {
            hook(m)
                .setId("pref_bool")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val key = chain.getArg(0) as? String
                    val raw = (chain.proceed() as? Boolean) ?: false
                    if (key == Target.KEY_SPLIT_ENABLED) PortraitGuard.apply(raw) else raw
                }
            1
        } catch (t: Throwable) {
            L.e("event=hook_failed target=pref_bool", t); 0
        }
    }

    /**
     * 超级材质：把「哪些应用能用毛玻璃键盘背景」的判定接管过来。
     *
     * 先挂 `bb.b0.j()`（只为把拦截范围收紧到它内部，不改它的行为），
     * 再挂 `pc.m.L0(Iterable, Object)` 做真正的放行判定。
     * 即使 `bb.b0.j()` 挂不上，判定仍靠集合类名生效，只是范围略宽 —— 会降级但不会失效。
     */
    private fun hookMaterial(cl: ClassLoader): Int {
        var ok = 0

        // 1) 范围锚点：bb.b0.j()
        val helperCls = loadClass(cl, Target.CLS_MATERIAL_HELPER)
        val applyMethod = findMethodByArity(helperCls, Target.M_MATERIAL_APPLY, 0) {
            it.returnType == Void.TYPE
        }
        if (applyMethod != null) {
            try {
                hook(applyMethod)
                    .setId("material_apply")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept { chain ->
                        MaterialGate.enterHelper()
                        try {
                            chain.proceed()
                        } finally {
                            MaterialGate.exitHelper()
                        }
                    }
                MaterialGate.helperHooked = true
                ok++
            } catch (t: Throwable) {
                L.e("event=hook_failed target=material_apply", t)
            }
        }

        // 2) 判定入口：pc.m 里的 (Iterable, Object) -> boolean
        val utilCls = loadClass(cl, Target.CLS_COLLECTIONS_UTIL) ?: return ok
        val contains = findIterableContains(utilCls) ?: return ok
        return try {
            hook(contains)
                .setId("material_contains")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val decision = try {
                        MaterialGate.decide(chain.getArg(0), chain.getArg(1))
                    } catch (t: Throwable) {
                        L.e("event=material_decide_failed", t)
                        null
                    }
                    decision ?: chain.proceed()
                }
            ok + 1
        } catch (t: Throwable) {
            L.e("event=hook_failed target=material_contains", t)
            ok
        }
    }

    /**
     * 材质描述符应用入口 `xe.b.a(View, xe.e)`。
     *
     * 1) 观测：把描述符里的模糊参数 / 混合色打出来（采样限流），便于核对材质是否真的带上模糊。
     * 2) 修正：描述符应用完后，补上离屏填充标记 —— 默认因为
     *    `persist.sys.advanced_visual_release` 只有 5 而跳过了这一步，
     *    导致模糊采不到窗口背后的内容，看着是实色。这里直接补，拨开关即时生效。
     */
    private fun hookMaterialDiagnostics(cl: ClassLoader): Int {
        MaterialDiag.logEnvironment(cl)
        MaterialEnhancer.probe()
        val cls = loadClass(cl, Target.CLS_MATERIAL_APPLIER) ?: return 0
        val apply = findMethodByArity(cls, Target.M_APPLY_MATERIAL, 2) {
            it.returnType == Void.TYPE && it.parameterTypes[0] == android.view.View::class.java
        } ?: return 0
        return try {
            hook(apply)
                .setId("material_apply_effect")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val view = chain.getArg(0) as? android.view.View
                    val result = chain.proceed()
                    L.sampled("material_desc", limit = 12) {
                        "event=material_desc ${MaterialDiag.describe(chain.getArg(1))}"
                    }
                    if (view != null) {
                        val cfg = ConfigLoader.snapshot()
                        if (cfg.materialEnabled) {
                            try {
                                MaterialEnhancer.ensureOffscreenFill(view)
                            } catch (t: Throwable) {
                                L.e("event=offscreen_fill_failed", t)
                            }
                        }
                    }
                    result
                }
            1
        } catch (t: Throwable) {
            L.e("event=hook_failed target=material_apply_effect", t); 0
        }
    }

    /**
     * 修正「背景不透明」：`z7.a` 用 `persist.sys.advanced_visual_release >= 6`
     * 判断设备是否支持离屏填充，为假时不调 `setMiBlurWinType`，模糊就没有内容可采样。
     * 这里在该属性被读取时按配置上报 6。
     *
     * 只在「启用超级材质」时生效；其余情况原样透传，
     * 避免影响系统里其它读这个属性的代码。
     */
    private fun hookSystemPropertiesForMaterial(cl: ClassLoader): Int {
        val cls = try {
            Class.forName("android.os.SystemProperties", false, cl)
        } catch (t: Throwable) {
            L.w("event=sysprop_missing")
            return 0
        }
        val get = try {
            cls.getDeclaredMethod("get", String::class.java, String::class.java)
                .also { it.isAccessible = true }
        } catch (t: Throwable) {
            L.e("event=hook_failed target=sysprop_get", t)
            return 0
        }
        return try {
            hook(get)
                .setId("material_sysprop")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val key = chain.getArg(0) as? String
                    if (key == MaterialDiag.PROP_ADVANCED_VISUAL) {
                        if (ConfigLoader.snapshot().materialEnabled) {
                            L.sampled("sysprop_force", limit = 4) {
                                "event=sysprop_force key=$key -> ${MaterialDiag.FORCED_ADVANCED_VISUAL}"
                            }
                            return@intercept MaterialDiag.FORCED_ADVANCED_VISUAL
                        }
                    }
                    chain.proceed()
                }
            1
        } catch (t: Throwable) {
            L.e("event=hook_failed target=sysprop_get", t); 0
        }
    }
}


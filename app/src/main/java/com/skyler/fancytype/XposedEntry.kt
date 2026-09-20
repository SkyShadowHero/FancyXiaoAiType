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
 * 功能 2：竖屏强制普通键盘（开关，默认关闭 = 保持原厂行为）。
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
        ok += hookRestartSignal(cl)
        ok += hookMaterial(cl)
        // 顺序要紧：先把属性 Hook 装好，再做诊断。
        // 诊断会读 z7.a 的静态字段，那一步会触发它的 <clinit>，
        // 一旦在 Hook 之前触发，离屏填充门就被永久算成 false 了。
        ok += hookSystemPropertiesForMaterial(cl)
        ok += hookMaterialDiagnostics(cl)
        ok += hookGlassLayer(cl)
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
        } catch (t: Throwable) {
            L.e("event=hook_failed target=resources_dimension", t)
        }
        return ok
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
    }

    /** 该资源 ID 是否属于某个名字集合 */
    private fun idIn(resId: Int, names: Array<String>): Boolean =
        names.any { nameToId[it] == resId }

    /**
     * 按资源 ID 覆写尺寸（dp -> px），数据驱动。支持三类：
     *  - 中心间隙（横屏 / 竖屏各一套）
     *  - 按键圆角（不分横竖屏）
     *  - 按键间距 / 键高（横竖屏各一套）
     * 返回 null 表示「不改」。
     */
    private fun resolveOverride(cfg: ConfigLoader.Cfg, resId: Int): Float? = when {
        idIn(resId, Target.GAP_LAND_NAMES) -> if (cfg.gapEnabled) cfg.gapLand else null
        idIn(resId, Target.GAP_PORT_NAMES) -> if (cfg.gapEnabled) cfg.gapPort else null
        idIn(resId, Target.CORNER_NAMES) -> if (cfg.cornerEnabled) cfg.cornerDp else null

        resId == nameToId[Target.NAME_KEY_HEIGHT_LAND] -> if (cfg.spaceEnabled) cfg.spaceKeyHLand else null
        resId == nameToId[Target.NAME_KEY_HEIGHT_PORT] -> if (cfg.spaceEnabled) cfg.spaceKeyHPort else null
        resId == nameToId[Target.NAME_KEY_SPACING_LAND] -> if (cfg.spaceEnabled) cfg.spaceKeyHorizLand else null
        resId == nameToId[Target.NAME_KEY_SPACING_PORT] -> if (cfg.spaceEnabled) cfg.spaceKeyHorizPort else null
        resId == nameToId[Target.NAME_ROW_SPACING_LAND] -> if (cfg.spaceEnabled) cfg.spaceRowLand else null
        resId == nameToId[Target.NAME_ROW_SPACING_PORT] -> if (cfg.spaceEnabled) cfg.spaceRowPort else null

        else -> null
    }

    private fun overrideDimension(
        resources: android.content.res.Resources?,
        resId: Int,
        original: Any?,
        asInt: Boolean,
    ): Any? {
        resolveNames(resources)
        val targetDp = resolveOverride(ConfigLoader.snapshot(), resId)
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
     * 「重启输入法」：在多个输入法生命周期点轮询信号，被请求则自杀，由系统重新拉起。
     *
     * 挂多个点是为了「更直接」：只挂 onWindowShown 时，必须等键盘再次弹出才检查；
     * 多挂 onStartInput / onStartInputView 后，任何一次输入会话开始都会立刻检查。
     * 注意：真正的「杀进程」只能由输入法进程自己执行（模块 App 无权限杀别人，
     * 即便 Process.killProcess(myPid()) 杀的也是自己），所以必须走这条信号+轮询路径。
     */
    private fun hookRestartSignal(cl: ClassLoader): Int {
        val cls = loadClass(cl, Target.CLS_IME_SERVICE) ?: return 0
        // 先把「配置里已有的信号」记为基线，否则残留的旧信号会让新进程启动即自杀
        RestartSignal.initBaseline()
        var ok = 0
        for ((methodName, arity) in Target.IME_POLL_POINTS) {
            val m = findMethodByArity(cls, methodName, arity) ?: continue
            try {
                hook(m)
                    .setId("ime_poll_$methodName")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept { chain ->
                        val r = chain.proceed()
                        RestartSignal.poll()
                        r
                    }
                ok++
            } catch (t: Throwable) {
                L.e("event=hook_failed target=$methodName", t)
            }
        }
        return ok
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

        // 2) 判定入口：pc.m.L0(Iterable, Object)
        val utilCls = loadClass(cl, Target.CLS_COLLECTIONS_UTIL) ?: return ok
        val contains = findMethodByArity(utilCls, Target.M_CONTAINS, 2) {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[1] == Any::class.java
        } ?: return ok
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
     * 2) 修正：描述符应用完后，补上离屏填充标记 —— 原厂因为
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

    /**
     * 自建 SurfaceFlinger 特效层，做真正的键盘背景模糊。
     *
     * MIUI 的「透过窗口模糊」被 system_server 的云端名单卡住（非白名单宿主一律不糊），
     * 所以不再走它的材质通道，而是在输入法窗口的 SurfaceControl 下挂一个 effect layer，
     * 用 `Transaction.setBackgroundBlurRadius()` 直接让合成器糊背后的内容 —— 跨窗口，
     * 不经过那张名单。
     *
     * 挂点选 `bb.b0.b(View)`：这个 View 就是键盘材质层本身，身份确定，不会误伤别处。
     */
    private fun hookGlassLayer(cl: ClassLoader): Int {
        val cls = loadClass(cl, Target.CLS_MATERIAL_HELPER) ?: return 0
        var ok = 0

        val applyView = findMethodByArity(cls, Target.M_MATERIAL_APPLY_VIEW, 1) {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[0] == android.view.View::class.java
        }
        if (applyView != null) {
            try {
                hook(applyView)
                    .setId("glass_layer_apply")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept { chain ->
                        val applied = (chain.proceed() as? Boolean) ?: false
                        val view = chain.getArg(0) as? android.view.View
                        if (applied && view != null) {
                            val cfg = ConfigLoader.snapshot()
                            if (cfg.materialEnabled) {
                                try {
                                    GlassLayer.sync(view, cfg.materialBlurDp, cfg.materialCornerDp)
                                } catch (t: Throwable) {
                                    L.e("event=glass_layer_sync_failed", t)
                                }
                            } else {
                                GlassLayer.detach("material_off")
                            }
                        }
                        applied
                    }
                ok++
            } catch (t: Throwable) {
                L.e("event=hook_failed target=glass_layer_apply", t)
            }
        }

        val teardown = findMethodByArity(cls, Target.M_MATERIAL_TEARDOWN, 0) {
            it.returnType == Void.TYPE
        }
        if (teardown != null) {
            try {
                hook(teardown)
                    .setId("glass_layer_teardown")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            GlassLayer.detach("material_teardown")
                        } catch (t: Throwable) {
                            L.e("event=glass_layer_detach_failed", t)
                        }
                        r
                    }
                ok++
            } catch (t: Throwable) {
                L.e("event=hook_failed target=glass_layer_teardown", t)
            }
        }
        return ok
    }
}


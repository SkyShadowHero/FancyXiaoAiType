package io.github.skyshadowhero.fancypad

import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FancyPad 单一入口：一个模块带五个功能域，按**包名**分发（不是进程名，见 [ProcessNames]）。
 *
 * | 包名（scope） | 功能 |
 * |---|---|
 * | `com.xiaomi.type` | 输入法外观增强（分离键盘 / 按键圆角间距 / 悬浮键盘）+ 随手写的输入法侧 |
 * | `android`（scope: `system`） | 光标主题接管（`PointerIconCache` + 两个只读 aconfig flag）+ 随手写声明 |
 * | `com.android.systemui` | 平行窗口动画 / 小窗控制菜单 / 文本选择工具栏 |
 * | `com.miui.securitycore` | 随手写白名单（让它别把默认输入法改写掉） |
 * | 其它（用户自加） | 右键菜单：目标应用进程内的 `PopupWindow` |
 *
 * 所有域的开关都放在同一个 RemotePreferences 组（[PrefKeys.GROUP]）里，
 * 由 App 侧界面写入、Hook 侧通过 [HookPrefs] / [ConfigLoader] 读取。
 *
 * 每个域装完后由 [installWithRetry] 判定要不要重试（最多 3 次）：
 * 「类还没加载好」这类偶发失败不再等于永久降级。
 *
 * Hook 策略：改「读取入口」而非改磁盘/资源，避免缓存与落盘副作用。
 */
class XposedEntry : XposedModule() {

    private companion object {
        /** 模块自己的包名：设置界面没有右键菜单，不需要装那一域。 */
        const val MODULE_PACKAGE = "io.github.skyshadowhero.fancypad"

        /** 每个功能域的安装最多试几次。 */
        const val INSTALL_MAX_ATTEMPTS = 3

        /** 两次安装尝试之间的间隔（ms）。 */
        const val INSTALL_RETRY_DELAY_MS = 1500L
    }

    /**
     * 悬浮键盘窗口宽度区间的下限至少要有这么多像素，才认它是我们的调用。
     * 键盘自然宽度在真机上是上千像素，`0.65 × 自然宽度` 不可能只有几十像素。
     */
    private val minFloatingWidthPx = 200

    private val installed = AtomicBoolean(false)

    /**
     * 安装失败后的重试调度。
     *
     * 为什么需要：`onPackageReady` 每个进程只来一次，而 `loadClass` 失败、资源尚未就绪
     * 这类原因是**可能自愈的**（类晚一点才加载好）。早先的做法是失败也把幂等位钉死，
     * 等于一次偶发失败就永久降级到进程重启 —— 表现是「功能时好时坏，重启就好」。
     * 现在：每个域最多试 [INSTALL_MAX_ATTEMPTS] 次，间隔 [INSTALL_RETRY_DELAY_MS]。
     *
     * 重试跑在主线程（Handler）：装 Hook 本身是反射 + 注册，不需要额外线程，
     * 也不能在 system_server 里另起线程做重活。
     */
    private val retryHandler by lazy { Handler(Looper.getMainLooper()) }
    private val retryCounts = ConcurrentHashMap<String, Int>()

    /** 光标域（system_server）：懒建，只有真的进了 system 作用域才会用到。 */
    private val cursorHooks by lazy { CursorHooks(this) }

    /** 平行窗口域（com.android.systemui）。 */
    private val embeddingHooks by lazy { EmbeddingHooks(this) }

    /**
     * 小窗控制菜单域（com.android.systemui）。
     *
     * 改的是窗口顶部那三个控制点点开后弹出的按钮条（MiuiCaptionContainerView）里
     * 「关闭」按钮的图标（× → −）。与 [embeddingHooks] 同进程，但动的是 MIUI 的窗口装饰，
     * 互不相关。
     */
    private val captionHooks by lazy { CaptionHooks(this) }

    /**
     * 右键菜单域（**目标应用自身**的进程，本机先只挂 mark.via）。
     *
     * 与 SystemUI 那几域是两条完全不同的路径：长按/选中菜单由 SystemUI 画，
     * 右键菜单是应用自己 `PopupWindow.showAsDropDown()` 弹的，只能逐应用注入。
     */
    private val appMenuHooks by lazy { AppMenuHooks(this) }

    /** 随手写 · system_server 侧两道闸（与光标域同进程，作用域 system）。 */
    private val stylusSystemHooks by lazy { StylusSystemHooks(this) }

    /** 随手写 · 输入法进程侧（补 AOSP 的四个 stylus 回调，作用域 com.xiaomi.type）。 */
    private val stylusImeHooks by lazy { StylusImeHooks(this) }

    @Volatile
    private var processName: String? = null
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        // 本机 logd 不收集常规缓冲区（logcat -b main -d 恒为 0 行），模块日志等于失效；
        // 镜像一份到 LSPosed 自己的日志（不经过 logd，落在 /data/adb/lspd/log/）。
        L.attach { priority, msg -> log(priority, L.TAG, "[${param.processName}] $msg") }
        Diag.attach(param.processName)
        L.i("event=module_loaded process=${param.processName} api=$apiVersion framework=$frameworkName")
        dumpInjectionMarker(param.processName)
    }

    /**
     * 注入痕迹落盘（诊断用）。
     *
     * system_server 是**开机注入**的：更新 APK 之后必须重启一次，新代码才会进去。
     * 而本机 logcat 三个常规缓冲区全空、LSPosed 日志也不一定收得到模块日志 ——
     * 所以「新代码到底有没有注入这个进程」需要一个不依赖日志的取证通道：
     * 在进程的数据目录里写一行标记，root 侧 `cat` 即可。
     *
     * | 进程 | 落盘位置 |
     * |---|---|
     * | system_server | `/data/system/fancypad_module.txt` |
     * | 普通应用进程 | `/data/data/<包名>/files/fancypad_module.txt` |
     *
     * 系统进程的判定统一走 [ProcessNames]（`system` / `system_server` / `android` 三种写法都认），
     * 否则在报成 `system_server` 的机器上会去写 `/data/data/system_server/files` ——
     * 那个目录不存在，写入静默失败，看起来就像「模块没注入」。
     */
    private fun dumpInjectionMarker(processName: String) {
        try {
            val dir = ProcessNames.diagDir(processName)
            if (!dir.isDirectory) dir.mkdirs()
            File(dir, "fancypad_module.txt").writeText(
                "process=$processName\napi=$apiVersion\nframework=$frameworkName\n"
            )
        } catch (t: Throwable) {
            L.e("event=marker_write_failed", t)
        }
    }

    /**
     * system_server 的注入时机：系统的 `android` 包也在场景里会走 [onPackageReady]，
     * 但 system_server 的 ClassLoader 在更早的 `onSystemServerStarting` 就已经可用，
     * 早装可以让开机后第一批光标请求就命中我们的实现。
     */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        HookPrefs.bind(this)
        L.i("event=system_server_starting process=$processName")
        installWithRetry("system_cursor") { cursorHooks.installSystemHooks(param.classLoader) }
        installWithRetry("system_stylus") { stylusSystemHooks.install(param.classLoader) }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        installForPackage(param.packageName, param.classLoader)
    }

    /**
     * 装一次；没装上就隔 [INSTALL_RETRY_DELAY_MS] 再试，最多 [INSTALL_MAX_ATTEMPTS] 次。
     *
     * 每个功能域用自己的 tag 计数：输入法外观装好了、随手写没装好，只重试随手写。
     * 用尽次数后明确落一条 `install_gave_up`，避免「功能没了但日志里什么都没有」。
     */
    private fun installWithRetry(tag: String, block: () -> Boolean) {
        // 单个域内部抛异常不能连累后面的域：这里统一兜住，按「没装上」处理
        val ok = runCatching { block() }
            .onFailure { L.e("event=install_threw tag=$tag", it) }
            .getOrDefault(false)
        if (ok) return
        val attempt = (retryCounts[tag] ?: 0) + 1
        retryCounts[tag] = attempt
        if (attempt >= INSTALL_MAX_ATTEMPTS) {
            L.w("event=install_gave_up tag=$tag attempts=$attempt（该功能本次进程内不可用，重启进程后可重试）")
            return
        }
        L.w("event=install_retry tag=$tag attempt=$attempt/$INSTALL_MAX_ATTEMPTS delay=${INSTALL_RETRY_DELAY_MS}ms")
        retryHandler.postDelayed(
            {
                runCatching { installWithRetry(tag, block) }
                    .onFailure { L.e("event=install_retry_failed tag=$tag", it) }
            },
            INSTALL_RETRY_DELAY_MS,
        )
    }

    /**
     * 按「包名 / 进程」装各自那一域的 Hook。
     *
     * 抽成独立函数是为了**热重载后能原样再装一遍**（见 [onHotReloaded]）：
     * 热重载不会重放生命周期，新代码必须自己重新注册 Hook。
     */
    private fun installForPackage(pkg: String, cl: ClassLoader) {
        when (pkg) {
            Target.PACKAGE -> {
                L.i("event=package_ready package=$pkg process=$processName")
                // 必须在装 hook 之前绑定：随手写那边有若干**不带 module 参数**的快照读取
                // （笔迹颜色/粗细/书写区高度等 `HookPrefs.stylusXxx()`），不 bind 就会拿到
                // 字段默认值 —— 真机踩过：表现为"设置了但落笔时还是默认的样子"。
                HookPrefs.bind(this)
                ConfigLoader.attach(getRemotePreferences(PrefKeys.GROUP))
                installWithRetry("ime_appearance") { installHooks(cl) }
                // 随手写：与输入法外观那批 hook 独立，单独装（installHooks 有自己的幂等守卫）
                installWithRetry("ime_stylus") { stylusImeHooks.install(cl) }
            }

            Target.SYSTEM_PACKAGE -> {
                L.i("event=package_ready package=system process=$processName")
                HookPrefs.bind(this)
                installWithRetry("system_cursor") { cursorHooks.installSystemHooks(cl) }
                installWithRetry("system_stylus") { stylusSystemHooks.install(cl) }
            }

            Target.SYSTEM_UI_PACKAGE -> {
                L.i("event=package_ready package=$pkg process=$processName")
                HookPrefs.bind(this)
                installWithRetry("sysui_embedding") { embeddingHooks.install(cl) }
                installWithRetry("sysui_caption") { captionHooks.install(cl) }
            }

            /**
             * 安全中心：这里就是「打开随手写被切到搜狗」的真实发生地 ——
             * `MiuiHandwritingSettingsFragment.p(true)` 会直接改写
             * `Settings.Secure.default_input_method`。它改写前先问当前输入法
             * `supportsStylusHandwriting()`，所以在这个进程装上声明 hook，它自己就早退了。
             */
            Target.SECURITY_CENTER_PACKAGE -> {
                L.i("event=package_ready package=$pkg process=$processName")
                HookPrefs.bind(this)
                installWithRetry("security_stylus") { stylusSystemHooks.installForSecurityCenter(cl) }
            }

            /**
             * 其余包 = 用户自己加进作用域的「普通应用」，走右键菜单域。
             *
             * 只处理命令行里真的会弹菜单的应用；模块自己的设置界面没有右键菜单，
             * 注入进去只会白装 hook，直接跳过。
             *
             * **系统进程一律不进来**：这条分支会在进程里 hook `AbsListView.setSelector` /
             * `View.setLayoutParams` 这类框架全局方法，装进 system_server 是要出事的。
             * 正常情况下 `packageName` 已经把它分到 [Target.SYSTEM_PACKAGE] 了，
             * 这里再拦一道，防的是「包名/进程名对不上」的边角情况（例如热重载反推错了）。
             */
            else -> {
                if (pkg == MODULE_PACKAGE) return
                if (ProcessNames.isSystemProcess(pkg) || ProcessNames.isSystemProcess(processName)) {
                    L.w("event=install_skipped reason=system_process pkg=$pkg process=$processName")
                    return
                }
                L.i("event=package_ready package=$pkg process=$processName")
                HookPrefs.bind(this)
                installWithRetry("app_menu") { appMenuHooks.install(cl) }
            }
        }
    }

    // ------------------------------------------------------------------ 热重载

    /**
     * 热重载：旧一代被冻结**之前**回调。返回 true 才允许继续。
     *
     * 这里只清**模块自己的运行态**（Handler 队列、缓存），不摘 Hook —— 旧 Handle 由框架
     * 通过 [onHotReloaded] 的 `oldHookHandles` 直接交给我们，在那里摘更准确：不会漏，
     * 也不会像「自己维护一张句柄表」那样因为某处漏记而重复安装。
     *
     * 为什么必须清：旧一代冻结后线程并不会停，不清就会出现**两代同时往输入框写**
     * （skill 案例 7：reload 后命中次数翻倍、状态错乱）。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        L.i("event=hot_reloading process=$processName")
        runCatching { stylusImeHooks.dispose() }
            .onFailure { L.e("event=hot_reload_cleanup_failed target=stylus_ime", it) }
        return true
    }

    /**
     * 热重载完成：新代码接管。
     *
     * 热重载**不重放生命周期**（不会再调 `onPackageReady`），所以新代码必须：
     * 1) 把旧一代的 Hook 全摘掉（`oldHookHandles`）；
     * 2) 自己重新装一遍 —— 目标 `ClassLoader` 从旧句柄的 `Executable` 反推
     *   （`HotReloadedParam` 本身不提供）。
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val old = param.oldHookHandles
        var off = 0
        for (h in old) {
            try {
                h.unhook()
                off++
            } catch (t: Throwable) {
                L.w("event=hot_reload_unhook_failed id=${runCatching { h.id }.getOrNull()}")
            }
        }
        val cl = old.firstNotNullOfOrNull { h ->
            runCatching { h.executable.declaringClass.classLoader }.getOrNull()
        }
        L.i(
            "event=hot_reloaded process=${param.processName} old=$off/${old.size} " +
                "classloader=${cl?.javaClass?.name}",
        )
        if (cl == null) {
            L.w("event=hot_reload_no_classloader（无法自动重装，请重启目标进程）")
            return
        }
        // 放掉幂等守卫，否则 install 会直接早退（表现：reload 后功能全没了）
        installed.set(false)
        stylusImeHooks.resetInstalled()
        stylusSystemHooks.resetInstalled()
        appMenuHooks.resetInstalled()
        retryCounts.clear()
        // ★ 按**包名**分派，不能拿进程名当包名：
        //   system_server 的 packageName 是 android，processName 却是 system / system_server，
        //   早先 `processName.substringBefore(':')` 会让它掉进「普通应用」分支，
        //   在 system_server 里装右键菜单域，同时光标/随手写不会重装。
        val pkg = ProcessNames.packageOf(param.processName)
        if (pkg == null) {
            L.w("event=hot_reload_no_package process=${param.processName}（请重启目标进程）")
            return
        }
        installForPackage(pkg, cl)
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 装输入法外观那一批 Hook。
     *
     * @return true = 已装好（含「本来就装过」）；false = 一个都没装上，调用方可以重试。
     */
    private fun installHooks(cl: ClassLoader): Boolean {
        if (!installed.compareAndSet(false, true)) {
            L.i("event=install_skipped reason=already_installed")
            return true
        }
        var ok = 0

        // 1) 与输入法版本无关的 Hook 先装：资源尺寸覆写按资源名解析，不碰混淆类名。
        ok += hookResourcesDimension(cl)

        // 2) 按混淆类名定位的部分：先从分档表挑出匹配当前输入法版本的一档。
        //    未记录的新版本会返回 null —— 此时只有上面的资源类 Hook 生效，
        //    （圆角 / 间距 / 宽度 / 边距等）照常工作，日志里会明确提示。
        val t = TargetCatalog.resolve(cl)
        if (t == null) {
            L.w("event=hooks_degraded reason=unknown_app_version 按类名定位的功能不可用")
        } else {
            ok += hookPadSplitDims(cl, t)
            ok += hookFloatingSize(cl, t)
        }

        L.i("event=install_done hooks=$ok process=$processName appVersion=${t?.label ?: "unknown"}")
        // 落盘诊断：版本分档到底选中了哪一档、得分多少、各档分别几分。
        // 本机日志不可读，这条是事后核对「适配有没有生效」的唯一取证通道。
        Diag.write(
            "install",
            buildString {
                appendLine("process=$processName hooks=$ok")
                appendLine("target=${t?.label ?: "unknown"} score=${t?.let { runCatching { it.score(cl) }.getOrDefault(-1) }}")
                for (c in TargetCatalog.known) {
                    appendLine("  candidate=${c.label} score=${runCatching { c.score(cl) }.getOrDefault(-1)}")
                }
            },
        )
        if (ok == 0) {
            // 一个都没装上：放掉幂等位，让 installWithRetry 能重来一次
            installed.set(false)
            L.w("event=install_failed target=ime_appearance process=$processName")
            return false
        }
        return true
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


    /** 资源名 -> ID（运行时解析，缓存）。ID 随版本变化，名字稳定。 */
    private val nameToId = HashMap<String, Int>()

    /** `nameToId` 的值集合：用于热路径的快速拒绝（见 [overrideDimension]）。 */
    private val interestingIds = HashSet<Int>()
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
        Diag.write(
            "margin_names",
            "resolved=$marginOk/${Target.MARGIN_NAMES.size}\n" +
                Target.MARGIN_NAMES.joinToString("\n") {
                    "  $it -> " + (nameToId[it]?.takeIf { id -> id != 0 }?.let { id -> "0x${id.toString(16)}" } ?: "MISSING")
                },
        )

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
        rebuildInterestingIds()
    }

    /** 把已解析到的资源 ID 收进快速拒绝集合。 */
    private fun rebuildInterestingIds() {
        interestingIds.clear()
        for (v in nameToId.values) if (v != 0) interestingIds.add(v)
        L.i("event=interesting_ids size=${interestingIds.size}")
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
        // ★ 快速拒绝：这是**逐帧**调用的热点函数，绝大多数 resId 与我们无关，
        // 直接透传。没有这一层时每个尺寸解析都要读一遍完整配置（40+ 个偏好键）
        // 外加拼日志字符串 —— 真机表现就是拖动悬浮键盘工具栏很卡。
        if (resId !in interestingIds) return original
        val cfg = ConfigLoader.snapshot()

        // 1) 比例缩放（收缩态几何）：乘在**原始值**上，不写死新值。
        //    默认设置下 factor = 1，直接跳过，等于原样透传。
        val factor = resolveScale(cfg, resId)
        if (factor != null && factor != 1f) {
            val origPx = (original as? Number)?.toFloat()
            if (origPx != null) {
                val scaled = (origPx * factor).coerceAtLeast(0f)
                return if (asInt) scaled.toInt() else scaled
            }
        }

        // 2) 绝对值覆写（dp × density）
        val targetDp = resolveOverride(cfg, resId) ?: return original
        val density = resources?.displayMetrics?.density ?: 1f
        val px = targetDp * density
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

    /** PadSplitQwertyDims（[AppTargets.padSplitDims]）：构造时打印分离键盘几何，用于核对间隙是否真的生效。 */
    private fun hookPadSplitDims(cl: ClassLoader, target: AppTargets): Int {
        val cls = loadClass(cl, target.padSplitDims) ?: return 0
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

    /**
     * 解锁悬浮键盘最大尺寸。
     *
     * 输入法把悬浮键盘的倍率与窗口宽度都夹在自然尺寸的 0.65 ~ 1.1 倍，
     * 三处夹取（拖动中 / 松手提交 / 显示恢复）走的是**同一个** Kotlin
     * `coerceIn` 实现，所以只接管那一个方法就能全部生效 —— 见 [FloatingSize]。
     *
     * ## 怎么认出「是我们的调用」
     *
     * 这个夹取方法是通用工具，输入法里有 190 多处调用，不能见着就改。
     * 判据是**参数特征**而不是调用方：
     *
     * - float 版认死的 `(0.65f, 1.1f)` —— 这对字面量在输入法里只出现在
     *   悬浮键盘的缩放夹取上。
     * - int 版认「上下限除以各自的系数后应当收敛到同一个自然宽度」。
     *   输入法是拿同一个 `fW` 算出上下限的（`0.65*fW`、`1.1*fW`），
     *   所以把两端分别除回去必然差不多相等（只差取整）；随便别的夹取
     *   凑不出这种关系。另外要求下限够大（像素宽度不会只有几十）。
     *
     * 只抬**上限**，下限原样保留 —— 下限决定键盘最小能缩多小，不该被影响。
     * 未解锁时一律原样透传，等于没装这个 hook。
     */
    private fun hookFloatingSize(cl: ClassLoader, target: AppTargets): Int {
        val util = loadClass(cl, target.clampUtil) ?: return 0
        var ok = 0

        // ---- 缩放倍率：(float, float, float) -> float ----
        val clampFloat = findMethodByArity(util, target.mClampFloat, 3) {
            it.returnType == Float::class.javaPrimitiveType &&
                it.parameterTypes.all { p -> p == Float::class.javaPrimitiveType }
        }
        if (clampFloat != null) {
            try {
                hook(clampFloat)
                    .setId("float_kb_scale")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept { chain ->
                        val min = chain.getArg(1) as? Float
                        val max = chain.getArg(2) as? Float
                        val value = chain.getArg(0) as? Float
                        if (min == PrefKeys.FLOAT_KB_NATIVE_MIN_SCALE &&
                            max == PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE &&
                            value != null
                        ) {
                            val newMax = FloatingSize.maxScale
                            if (newMax > PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE) {
                                L.sampled("fkb_size", limit = 8) {
                                    "event=float_kb_scale value=$value -> max=$newMax"
                                }
                                return@intercept chain.proceed(arrayOf<Any?>(value, min, newMax))
                            }
                        }
                        chain.proceed()
                    }
                ok++
            } catch (t: Throwable) {
                L.e("event=hook_failed target=float_kb_scale", t)
            }
        }

        // ---- 窗口宽度：(int, int, int) -> int ----
        val clampInt = findMethodByArity(util, target.mClampInt, 3) {
            it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes.all { p -> p == Int::class.javaPrimitiveType }
        }
        if (clampInt != null) {
            try {
                hook(clampInt)
                    .setId("float_kb_width")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept { chain ->
                        val lo = chain.getArg(1) as? Int
                        val hi = chain.getArg(2) as? Int
                        val value = chain.getArg(0) as? Int
                        if (lo != null && hi != null && value != null && isFloatingWidthRange(lo, hi)) {
                            val newMax = FloatingSize.maxScale
                            if (newMax > PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE) {
                                // lo = 0.65 * fW，所以 fW = lo / 0.65，新上限 = fW * newMax
                                val newHi = (lo * (newMax / PrefKeys.FLOAT_KB_NATIVE_MIN_SCALE)).toInt()
                                L.sampled("fkb_size", limit = 8) {
                                    "event=float_kb_width value=$value lo=$lo hi=$hi -> hi=$newHi"
                                }
                                return@intercept chain.proceed(arrayOf<Any?>(value, lo, newHi))
                            }
                        }
                        chain.proceed()
                    }
                ok++
            } catch (t: Throwable) {
                L.e("event=hook_failed target=float_kb_width", t)
            }
        }

        L.i(
            "event=float_kb_hooks util=${target.clampUtil} " +
                "scale=${if (clampFloat != null) "ok" else "missing"} " +
                "width=${if (clampInt != null) "ok" else "missing"} " +
                "unlock=${FloatingSize.unlocked} maxScale=${FloatingSize.maxScale}"
        )
        return ok
    }

    /**
     * 这一对 (下限, 上限) 是不是悬浮键盘的窗口宽度区间？
     *
     * 输入法用同一个自然宽度 `fW` 算出两端：`lo = 0.65*fW`、`hi = 1.1*fW`。
     * 所以把两端各自除回系数，应当收敛到同一个 `fW`（只差取整误差）。
     * 别的夹取凑不出这种关系。
     *
     * 另外要求 `lo` 够大：键盘自然宽度在真机上是上千像素，区间下限不可能很小，
     * 加上这一条可以把概率性的巧合挡掉。
     */
    private fun isFloatingWidthRange(lo: Int, hi: Int): Boolean {
        if (lo < minFloatingWidthPx || hi <= lo) return false
        val fwFromLo = lo / PrefKeys.FLOAT_KB_NATIVE_MIN_SCALE
        val fwFromHi = hi / PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE
        return kotlin.math.abs(fwFromLo - fwFromHi) < 1.5f
    }

}

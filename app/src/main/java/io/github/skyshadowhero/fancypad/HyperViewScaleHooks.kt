package io.github.skyshadowhero.fancypad

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * FancyPad · 一镜到底（HyperViewScale）—— **把平板被砍掉的那一刀接回来**。
 *
 * ## 这套动画是什么
 *
 * HyperOS 4 里「列表点开→详情页封面从原位展开、返回再收回去」不是金标联盟的 SharedView Kit，
 * 而是小米自己的 **HyperViewScale**：App 进程在点击时抓一份卡片截图 + 位置 + 圆角，
 * 通过 `ActivityOptions.makeRemoteTransition()` 把一段 `view_scale_remote_transition`
 * 交给 system_server，由 WM 在 SurfaceControl 上做形变。
 *
 * ```
 * App 进程                                                     system_server
 * ─────────                                                    ─────────────
 * View.performClick(v)                       ← 手机 framework 改过
 *   └─ HyperViewScaleRemoteStub.getInstance().performClick(v)
 *        ├─ HyperViewScaleManager.isViewSupport(类名)   ← 逐 App 配置
 *        ├─ setScaleView() → 截图/圆角/bounds
 *        └─ ActivityOptions.makeRemoteTransition(...)  ─────→ TransitionImpl.requestStartTransition
 *                                                              └─ flag HYPER_VIEW_SCALE
 * Activity.startActivityForResult(...)       ← 手机 framework 改过
 *   └─ …startActivity(activity, ctx, options, intent) → 把 options 换成带 RemoteTransition 的
 * ```
 *
 * ## 平板到底缺了什么（真机取证，不是猜）
 *
 * 一、**触发链根本没接**。pad 的 `framework.jar` 六个 dex 里 `HyperViewScale*` 只有类定义：
 * `android.view.View.performClick()` 与 `android.app.ActivityThread` 都不引用
 * `HyperViewScaleRemoteStub`。所以手机上是 framework 主动叫这套 API，平板上是**没人叫**。
 *
 * 二、**懒加载本身是坏的**。`HyperViewScaleFrameworkLoader.init()` 走
 * `MiuiStubRegistry.loadJar(path, internal=true, bootCL)`，而那个函数的 internal 分支是
 * `(PathClassLoader) classloader` 强转 —— BootClassLoader 不是 PathClassLoader，
 * 强转抛异常后**被内部 catch 吞掉**、返回 null。真机实测：`init()` 不抛错，
 * 但 `HyperViewScaleRemoteManager` 依然 load 不到（diag 里 `after init class=null`）。
 *
 * 三、三处 `checkFeatureSupport()` 都写着 `!Build.IS_TABLET`（system_server 两处、App 一处）。
 *
 * 所以这里做三件事，**不伪造动画**，仍然用 ROM 自带的实现（folme 曲线、
 * SurfaceControl 形变、返回落点重算都是现成的）：
 *
 * 1. system_server：放开两处 `checkFeatureSupport()`（配置表才能真正下发 `viewScaleInfo`）；
 * 2. App：**自己**用 `PathClassLoader` 把 `miui-framework.hyperviewscale.jar` 挂上
 *    （父加载器给 null = BootClassLoader，这样 `HyperViewScaleRemoteStub` 解析到 boot 里
 *    那一份，类身份才一致），放开 App 侧的 `checkFeatureSupport()/isFeatureEnable()`；
 * 3. App：把缺的两个触发点接回去 —— `View.performClick()` 和
 *    `Activity.startActivityForResult(Intent,int,Bundle)`，并让
 *    `HyperViewScaleRemoteStub.getInstance()` 返回我们这份实例。
 *
 * 逐 App 的卡片类名表来自 `/system_ext/etc/view_scale/view_scale_config.json`
 * （`tv.danmaku.bili`、`com.xingin.xhs` 都在），系统没收录的包不会有反应 —— 这是故意的：
 * 我们只补触发链，不编造"哪个 View 算卡片"。
 */
class HyperViewScaleHooks(private val module: XposedModule) {

    /** system_server 侧（两个类）的幂等位。 */
    @Volatile
    private var systemInstalled = false

    /** App 进程侧的幂等位。 */
    @Volatile
    private var appInstalled = false

    /** 我们自己的实例（每个 App 进程一个）。 */
    @Volatile
    private var manager: Any? = null

    /** 首次放行时写一行落盘诊断（本机 logd 收不到日志，见 [Diag]）。 */
    @Volatile
    private var loggedFirstEnable = false

    /** 诊断计数器：只在头几次点击/启动时落盘，避免刷爆文件。 */
    private var clickLogged = 0
    private var startLogged = 0

    /** 热重载后重新安装用：放掉幂等守卫。 */
    fun resetInstalled() {
        systemInstalled = false
        appInstalled = false
    }

    // ------------------------------------------------------------ system_server

    /**
     * system_server 侧：把两份 `checkFeatureSupport()` 一起改成 true。
     *
     * `HyperViewScaleConfigManager` 那份是**必须**的：它在开机 `init()` 里就把 `mEnable` 定死，
     * 而且 `preBindApplication()` 只在 `mEnable` 为真时给 `ApplicationInfo.viewScaleInfo`
     * 塞配置 —— 没这个字段，App 侧拿到手也是空的。
     *
     * @return true = 至少装上一个。
     */
    fun installSystemHooks(cl: ClassLoader): Boolean {
        if (systemInstalled) return true
        val transition = hookCheckFeatureSupport(
            cl,
            "com.android.server.wm.HyperViewScaleTransitionImpl",
            "hvs_sys_transition",
        )
        val config = hookCheckFeatureSupport(
            cl,
            "com.android.server.wm.hyperviewscale.HyperViewScaleConfigManager",
            "hvs_sys_config",
        )
        if (transition || config) {
            systemInstalled = true
            L.i("event=hvs_system_installed transition=$transition config=$config")
            Diag.write("hvs", "system installed: transition=$transition config=$config")
        } else {
            L.w("event=hvs_system_install_failed 两类都没找到，可能这一版 ROM 没带 HyperViewScale")
        }
        return systemInstalled
    }

    // ---------------------------------------------------------------- App 进程

    /**
     * 目标 App 进程侧：挂 jar、放开闸、把触发链接回去。
     *
     * @param pkg 目标包名（先看系统配置表里有没有它，没有就别浪费一次 12MB 的 dex 加载）。
     * @return true = 已处理（装上了 / 不需要）。
     */
    fun installAppHooks(pkg: String, cl: ClassLoader): Boolean {
        if (appInstalled) return true
        if (!packageNeedsViewScale(pkg)) {
            appInstalled = true
            L.i("event=hvs_app_skipped reason=not_in_config pkg=$pkg")
            return true
        }
        return try {
            installAppHooksUnsafe(pkg, cl)
        } catch (t: Throwable) {
            appInstalled = true
            L.e("event=hvs_app_install_threw pkg=$pkg", t)
            Diag.write("hvs", "app install threw: $t")
            true
        }
    }

    private fun installAppHooksUnsafe(pkg: String, cl: ClassLoader): Boolean {
        // 1) 自己挂 jar：父加载器传 null → BootClassLoader，stub 类身份才和 framework 里的一致
        val ourLoader = PathClassLoader(JAR_PATH, null as ClassLoader?)
        val managerCls = Class.forName(CLS_REMOTE_MANAGER, false, ourLoader)
        Diag.write("hvs", "jar loaded, manager=${managerCls.name}")

        // 2) 先 hook 再实例化：mIsFeatureSupported 是构造时算的 final 字段
        hookCheckFeatureSupport(ourLoader, CLS_REMOTE_MANAGER, "hvs_app_check")
        hookBooleanMethod(managerCls, "isFeatureEnable", "hvs_app_enable")
        hookAnimHandler(managerCls, backgroundHandler())
        hookStartActivity(managerCls)

        val instance = managerCls.getDeclaredConstructor().newInstance()
        manager = instance
        Diag.write("hvs", "manager instance created")

        // 3) 让 framework 看到我们这份实例（手机上这份来自 MiuiStubRegistry）
        hookStubGetInstance(instance)

        // 4) 接上点击触发
        hookPerformClick(instance, managerCls)

        // 5) 把 viewScaleInfo 喂进去（手机上由 framework 在 bind 时调）。
        //    模块通常在 Application 还没创建时注入，currentApplication() 会返回 null ——
        //    所以这里要重试，不能一次失败就放弃（否则 performClick 会在 mViewScaleManager==null 早退）。
        bindApplicationInfoWithRetry(instance, ourLoader)

        appInstalled = true
        L.i("event=hvs_app_installed pkg=$pkg")
        Diag.write("hvs", "app installed ok pkg=$pkg")
        return true
    }

    /**
     * 反复尝试把 `viewScaleInfo` 喂给实例，直到成功或超时。
     *
     * 为什么必须重试：模块在 `onPackageReady` 就装好了（比 `Application.onCreate` 还早），
     * 那一刻 `ActivityThread.currentApplication()` 还是 null。手机上这段是 framework
     * 在 `handleBindApplication` 尾段做的，我们只能自己等。
     */
    private fun bindApplicationInfoWithRetry(instance: Any, ourLoader: ClassLoader) {
        val thread = HandlerThread("hvs-bind").apply { start() }
        val handler = Handler(thread.looper)
        val attempts = 40
        fun attempt(n: Int) {
            if (bindApplicationInfo(instance, ourLoader)) {
                thread.quitSafely()
                return
            }
            if (n >= attempts) {
                Diag.write("hvs", "bind application info gave up after $n attempts")
                thread.quitSafely()
                return
            }
            handler.postDelayed({ attempt(n + 1) }, 250L)
        }
        handler.post { attempt(0) }
    }

    /**
     * `ApplicationInfo.viewScaleInfo` 由 system_server 的 `preBindApplication()` 塞进来。
     *
     * 这里要手动喂给我们的实例（手机上是 framework 在 bind 时调的）。bundle 里装的是
     * `HyperViewScaleConfig$Pkg`（Parcelable），类只存在于我们自己的加载器里，
     * 所以**必须先把 bundle 的 classloader 指过去**，否则反序列化会 ClassNotFound。
     */
    private fun bindApplicationInfo(instance: Any, ourLoader: ClassLoader): Boolean {
        val info: Any = try {
            // ActivityThread 是隐藏 API：直接反射拿 currentApplication().getApplicationInfo()
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) ?: return false
            app.javaClass.getMethod("getApplicationInfo").invoke(app) ?: return false
        } catch (t: Throwable) {
            Diag.write("hvs", "getApplicationInfo failed: $t")
            return false
        }
        val bundle = try {
            info.javaClass.getField("viewScaleInfo").get(info) as? Bundle
        } catch (t: Throwable) {
            null
        }
        if (bundle == null) {
            Diag.write("hvs", "viewScaleInfo is null (system_server 那半没生效？)")
            return true
        }
        try {
            bundle.setClassLoader(ourLoader)
        } catch (t: Throwable) {
            Diag.write("hvs", "setClassLoader failed: $t")
        }
        try {
            instance.javaClass.getMethod("handleBindApplication", info.javaClass)
                .invoke(instance, info)
            Diag.write("hvs", "handleBindApplication ok")
            return true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Diag.write("hvs", "handleBindApplication failed: $cause")
            return true
        }
    }

    // ---------------------------------------------------------------- hooks

    /**
     * `HyperViewScaleRemoteStub.getInstance()` → 我们的实例。
     *
     * 手机 ROM 里这个 static final 字段由 `MiuiStubUtil.getInstance()` 在类初始化时填好；
     * 平板因为 jar 没挂上，拿到的是基类（`performClick` 是空方法）。这里直接改返回值，
     * `View.performClick` / `ActivityThread` 那些调用点就都通了。
     */
    private fun hookStubGetInstance(instance: Any): Boolean {
        return try {
            val stubCls = Class.forName(CLS_STUB, false, null as ClassLoader?)
            val getter = stubCls.getDeclaredMethod("getInstance").apply { isAccessible = true }
            module.hook(getter)
                .setId("hvs_stub_get_instance")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (HookPrefs.hyperViewScaleEnabled(module)) instance else chain.proceed()
                }
            Diag.write("hvs", "stub.getInstance hooked")
            true
        } catch (t: Throwable) {
            L.e("event=hvs_trace_hook_failed target=$CLS_STUB#getInstance", t)
            Diag.write("hvs", "stub getInstance hook failed: $t")
            false
        }
    }

    /**
     * `View.performClick()` —— 手机 framework 在这里调 `stub.performClick(view)`。
     *
     * 只在目标包里装（[installAppHooks] 已经按配置表筛过），而且 manager 内部第一件事是
     * `isSystemProcess()` + `mViewScaleManager == null` 判断，成本可以忽略。
     */
    private fun hookPerformClick(instance: Any, managerCls: Class<*>): Boolean {
        return try {
            val method = View::class.java.getDeclaredMethod("performClick")
            val handler = managerCls.getMethod("performClick", View::class.java)
            module.hook(method)
                .setId("hvs_view_perform_click")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (HookPrefs.hyperViewScaleEnabled(module)) {
                        try {
                            val view = chain.thisObject as? View
                            if (view != null) {
                                handler.invoke(instance, view)
                                if (clickLogged < 5) {
                                    clickLogged++
                                    Diag.write("hvs", "performClick -> ${view.javaClass.name}")
                                }
                            }
                        } catch (t: Throwable) {
                            Diag.write("hvs", "performClick invoke failed: $t")
                        }
                    }
                    result
                }
            Diag.write("hvs", "View.performClick hooked")
            true
        } catch (t: Throwable) {
            L.e("event=hvs_trace_hook_failed target=View#performClick", t)
            Diag.write("hvs", "View.performClick hook failed: $t")
            false
        }
    }

    /**
     * `Activity.startActivityForResult(Intent, int, Bundle)` —— 手机 framework 在这里
     * 把 options 换成带 `RemoteTransition` 的那份（`HyperViewScaleRemoteManager.startActivity`）。
     *
     * 只挂三参版本：`startActivity(Intent)` / `startActivity(Intent, Bundle)` 最终都会走到它。
     */
    private fun hookStartActivity(managerCls: Class<*>): Boolean {
        return try {
            val method = Activity::class.java.getDeclaredMethod(
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java,
            )
            val handler = managerCls.getMethod(
                "startActivity",
                Activity::class.java,
                android.content.Context::class.java,
                Bundle::class.java,
                Intent::class.java,
            )
            module.hook(method)
                .setId("hvs_activity_start")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val enabled = HookPrefs.hyperViewScaleEnabled(module)
                    val current = manager
                    if (!enabled || current == null) {
                        chain.proceed()
                    } else {
                        val args = chain.args
                        val activity = chain.thisObject as? Activity
                        val intent = args.getOrNull(0) as? Intent
                        val requestCode = args.getOrNull(1) as? Int ?: -1
                        val options = args.getOrNull(2) as? Bundle
                        if (activity == null || intent == null) {
                            chain.proceed()
                        } else {
                            val newOptions = try {
                                handler.invoke(current, activity, activity, options, intent) as? Bundle
                            } catch (t: Throwable) {
                                Diag.write("hvs", "startActivity invoke failed: $t")
                                options
                            }
                            if (startLogged < 5) {
                                startLogged++
                                Diag.write(
                                    "hvs",
                                    "startActivity ${intent.component?.className} options=" +
                                        (newOptions?.keySet()?.joinToString(",") ?: "null"),
                                )
                            }
                            chain.proceed(arrayOf(intent, requestCode, newOptions ?: options))
                        }
                    }
                }
            Diag.write("hvs", "Activity.startActivityForResult hooked")
            true
        } catch (t: Throwable) {
            L.e("event=hvs_trace_hook_failed target=Activity#startActivityForResult", t)
            Diag.write("hvs", "startActivity hook failed: $t")
            false
        }
    }

    /**
     * 挂钩 `private static boolean checkFeatureSupport()`，命中时恒返回 true。
     *
     * 关掉开关时走 `chain.proceed()`，等于模块不存在。
     */
    private fun hookCheckFeatureSupport(cl: ClassLoader, className: String, id: String): Boolean {
        return try {
            val cls = cl.loadClass(className)
            val method = cls.getDeclaredMethod("checkFeatureSupport").apply { isAccessible = true }
            module.hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (!HookPrefs.hyperViewScaleEnabled(module)) chain.proceed() else true
                }
            L.i("event=hvs_hook_installed target=$className#checkFeatureSupport id=$id")
            true
        } catch (t: Throwable) {
            L.e("event=hvs_hook_failed target=$className#checkFeatureSupport id=$id", t)
            false
        }
    }

    /** 挂钩一个无参 boolean 实例方法，命中时恒返回 true。 */
    private fun hookBooleanMethod(cls: Class<*>, name: String, id: String): Boolean {
        return try {
            val method = cls.getDeclaredMethod(name).apply { isAccessible = true }
            module.hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (!HookPrefs.hyperViewScaleEnabled(module)) {
                        chain.proceed()
                    } else {
                        if (!loggedFirstEnable) {
                            loggedFirstEnable = true
                            Diag.write("hvs", "first enable hit: ${cls.name}#$name")
                        }
                        true
                    }
                }
            L.i("event=hvs_hook_installed target=${cls.name}#$name id=$id")
            true
        } catch (t: Throwable) {
            L.e("event=hvs_hook_failed target=${cls.name}#$name id=$id", t)
            false
        }
    }

    /**
     * `private Handler getAnimHandler()`：原实现返回 null（单例里字段是 false）时补一个后台 Handler。
     *
     * 这个 Handler 只用于截图（`PixelCopy`）。手机 ROM 用的是
     * `HyperViewBackgroundThread.getHandler()`，我们那份 jar 里也有，所以优先用它。
     */
    private fun hookAnimHandler(cls: Class<*>, fallback: Handler): Boolean {
        return try {
            val method = cls.getDeclaredMethod("getAnimHandler").apply { isAccessible = true }
            module.hook(method)
                .setId("hvs_app_handler")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val original = runCatching { chain.proceed() }.getOrNull()
                    if (!HookPrefs.hyperViewScaleEnabled(module)) {
                        original
                    } else {
                        original ?: fallback
                    }
                }
            L.i("event=hvs_hook_installed target=${cls.name}#getAnimHandler")
            true
        } catch (t: Throwable) {
            L.e("event=hvs_hook_failed target=${cls.name}#getAnimHandler", t)
            false
        }
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 截图用的后台 Handler。
     *
     * 优先用 jar 里的 `HyperViewBackgroundThread`（和 ROM 同一个线程模型），拿不到就自己起一个
     * 带 Looper 的 HandlerThread —— 这只会让 `PixelCopy` 的回调换个线程，不影响正确性。
     */
    private fun backgroundHandler(): Handler {
        return try {
            val cls = Class.forName(CLS_BG_THREAD, false, null as ClassLoader?)
            cls.getDeclaredMethod("getHandler").apply { isAccessible = true }.invoke(null) as Handler
        } catch (t: Throwable) {
            HandlerThread("hvs-capture").apply { start() }.let { Handler(it.looper) }
        }
    }

    /**
     * 这个包在不在系统那份 `view_scale_config.json` 里。
     *
     * 只在表里的包系统才会下发 `viewScaleInfo`，其余包拉 12MB dex 纯属浪费。
     * 表读不到（空列表）时返回 true：宁可多装一次，也不要因为读不到而误判成"不支持"。
     */
    private fun packageNeedsViewScale(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val supported = ViewScaleTargets.packages()
        return supported.isEmpty() || pkg in supported
    }

    private companion object {
        const val CLS_REMOTE_MANAGER = "android.app.hyperviewscale.HyperViewScaleRemoteManager"
        const val CLS_BG_THREAD = "android.app.hyperviewscale.HyperViewBackgroundThread"
        const val CLS_STUB = "android.app.hyperviewscale.HyperViewScaleRemoteStub"
        const val JAR_PATH = "/system_ext/framework/miui-framework.hyperviewscale.jar"
    }
}

package com.skyler.fancytype

import android.view.View
import java.lang.reflect.Method

/**
 * 把「离屏填充 / 窗口模糊」这几个框架接口直接打到键盘背景上。
 *
 * 背景：目标应用把 `persist.sys.advanced_visual_release >= 6` 当作「支持离屏填充」的门
 * （jadx 里的 `z7.a.f18746a`）。本机该属性为 5，于是 `bb.b0` 从不调用
 * `setMiBlurWinType`，模糊采样不到窗口背后的内容，看起来就是一块实色。
 *
 * 这里不改系统属性（那会污染整个进程，且静态字段每进程只算一次、拨开关不重启就没反应），
 * 而是在每次材质被应用时把缺的那几个调用直接补上 —— 拨开关立刻生效。
 *
 * 所有调用都是反射 + 异常吞掉：这些是 MIUI 私有接口，机型/版本上缺失时应当静默降级，
 * 不能把输入法搞崩。
 */
object MaterialEnhancer {

    /** `setMiBlurWinType` 的取值，取自目标应用自己的调用序列 */
    private const val BLUR_WIN_TYPE_PREPARE = 65536
    private const val BLUR_WIN_TYPE_FINAL = 1

    private const val DELAY_FINAL_MS = 500L

    /** 探测一次就够 */
    @Volatile
    private var probed = false

    private val methods = HashMap<String, Method?>()

    private fun method(name: String, vararg params: Class<*>): Method? = synchronized(methods) {
        if (methods.containsKey(name)) return methods[name]
        val m = try {
            View::class.java.getMethod(name, *params)
        } catch (t: Throwable) {
            null
        }
        methods[name] = m
        m
    }

    /** 启动时打一次能力清单：知道到底缺哪个接口，而不是猜。 */
    fun probe() {
        if (probed) return
        probed = true
        val intCls = Int::class.javaPrimitiveType!!
        val boolCls = Boolean::class.javaPrimitiveType!!
        val listCls = ArrayList::class.java
        val available = listOf(
            "setMiBlurWinType(int)" to method("setMiBlurWinType", intCls),
            "setPassWindowBlurEnabled(boolean)" to method("setPassWindowBlurEnabled", boolCls),
            "setMiBackgroundBlurMode(int)" to method("setMiBackgroundBlurMode", intCls),
            "setMiBackgroundBlurRadius(int)" to method("setMiBackgroundBlurRadius", intCls),
            "setMiBackgroundBlurType(int)" to method("setMiBackgroundBlurType", intCls),
            "setMiViewBlurMode(int)" to method("setMiViewBlurMode", intCls),
            "setMixEffectEnabled(boolean)" to method("setMixEffectEnabled", boolCls),
            "setMiBackgroundBlendColors(ArrayList)" to method("setMiBackgroundBlendColors", listCls),
            "isPassWindowBlurWhitelisted(String)" to method("isPassWindowBlurWhitelisted", String::class.java),
        ).joinToString(" ") { (k, v) -> "$k=${if (v != null) "ok" else "MISS"} " }
        L.i("event=material_caps $available")
    }

    /**
     * 在材质描述符已应用之后补上离屏填充标记。
     * 与 `bb.b0.b()` 在门为真时走的序列一致：先 65536，500ms 后 1。
     */
    fun ensureOffscreenFill(view: View) {
        probeWhitelist(view)
        val prepare = method("setMiBlurWinType", Int::class.javaPrimitiveType!!) ?: return
        try {
            prepare.invoke(view, BLUR_WIN_TYPE_PREPARE)
        } catch (t: Throwable) {
            L.sampled("offscreen_prepare_fail", limit = 2) {
                "event=offscreen_fill_failed step=prepare ${t.javaClass.simpleName}"
            }
            return
        }
        // 同时确保「透过窗口模糊」是开着的（原厂门为假时 bb.b0 也不会走到这一步）
        runCatching {
            method("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType!!)?.invoke(view, true)
        }
        L.sampled("offscreen_ok", limit = 6) { "event=offscreen_fill_applied" }
        view.postDelayed({
            try {
                prepare.invoke(view, BLUR_WIN_TYPE_FINAL)
            } catch (t: Throwable) {
                // 忽略：延迟补刀失败不影响主效果
            }
        }, DELAY_FINAL_MS)
    }

    /**
     * 实测框架的「透过窗口模糊」白名单，以及当前窗口形态。
     *
     * MIUI 自己的毛玻璃路径（`miuix/appcompat/widget/i.java`）在应用材质前会先问
     * `View.isPassWindowBlurWhitelisted(包名)`；目标应用的键盘路径不查，但框架内部
     * 未必放行 —— 这张名单在 system_server 侧，应用资源里查不到，只能运行时实测。
     *
     * 同时记录窗口形态：全屏 / 分屏 / 小窗，用于核对「换窗口形态才糊」的现象。
     */
    private fun probeWhitelist(view: View) {
        val host = MaterialGate.lastHostPackage ?: "-"
        val wl = method("isPassWindowBlurWhitelisted", String::class.java)
        val hostAllowed = try {
            wl?.invoke(view, host)?.toString() ?: "no-api"
        } catch (t: Throwable) {
            "err"
        }
        val readBack = try {
            method("getPassWindowBlurEnabled")?.invoke(view)?.toString() ?: "no-api"
        } catch (t: Throwable) {
            "err"
        }
        val mode = windowingMode(view)
        L.sampled("pass_blur_wl", limit = 4) {
            "event=pass_window_blur host=$host hostWhitelisted=$hostAllowed " +
                "passWindowBlurEnabled=$readBack windowingMode=$mode(${windowingModeName(mode)})"
        }
    }

    /**
     * `Configuration.getWindowConfiguration().getWindowingMode()`：1 全屏 / 2 分屏主 / 3 分屏副 /
     * 4 画中画 / 5 自由窗口。
     *
     * 这两个 API 在公开 SDK 里是 @hide，编译期用不了，运行时反射拿。
     */
    private fun windowingMode(view: View): Int = try {
        val cfg = view.getContext()?.resources?.configuration
        if (cfg == null) {
            -1
        } else {
            val wc = cfg.javaClass.getMethod("getWindowConfiguration").invoke(cfg)
            (wc?.javaClass?.getMethod("getWindowingMode")?.invoke(wc) as? Int) ?: -1
        }
    } catch (t: Throwable) {
        -1
    }

    private fun windowingModeName(mode: Int): String = when (mode) {
        1 -> "fullscreen"
        2 -> "split-primary"
        3 -> "split-secondary"
        4 -> "pip"
        5 -> "freeform"
        6 -> "multi-window"
        else -> "unknown"
    }
}

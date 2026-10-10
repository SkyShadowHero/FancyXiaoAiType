package io.github.skyshadowhero.fancypad

import android.content.Context
import dalvik.system.PathClassLoader
import java.lang.reflect.Method

/**
 * 小米触控笔引擎（xiaomi-pencilengine-pad）访问层 —— **只借用系统自带的那一份，不打包任何引擎代码**。
 *
 * 引擎与模型都是系统资产，本模块只负责把它们挂进当前进程：
 *
 * | 资产 | 路径 |
 * |---|---|
 * | 引擎 dex | `/system_ext/framework/xiaomi-pencilengine-pad.jar` |
 * | OCR 识别核心 | `/system_ext/lib64/librecognize_interface.touch.so` |
 * | OCR 模型 | `/system_ext/etc/ocr_model.tflite`（17MB，`RecognizeFacade` 里硬编码该路径） |
 *
 * 这不是自创玩法：搜狗小米版用的就是同一份，见其
 * `com/xiaomi/handwriting/engine/j/c.java` —— 一样的 `PathClassLoader(绝对路径, 系统 ClassLoader)`。
 * 该 jar 既不在 boot classpath 里，也没有 `<library>` 共享库声明，只能这样按路径挂。
 *
 * 唯一的人为限制在引擎内部：`EnableAuthData.WHITE_LIST` 是一份**硬编码的包名白名单**
 * （搜狗、讯飞、各家笔记 App 在列，`com.xiaomi.type` 不在），而
 * `PencilEngineManager.getAuthResult()` 在 XMSF 未连接时**直接返回这份白名单的结果**，
 * 拿不到 true 时 `recognizeText()` 一律返回 null。所以 [open] 之后要把
 * `PencilEngineManager.whitelistResult` 顶掉 —— 见 [bypassWhitelist]。
 *
 * 好消息是这条路**完全本地**：模型在 `/system_ext/etc`，native 在 `/system_ext/lib64`，
 * 不需要联网、不需要小米账号、不需要 XMSF（那条云端分支反而更难走通）。
 */
class PencilEngine private constructor(private val j: J) {

    /** 一个笔迹点。[action] 用 `MotionEvent` 的语义：0=DOWN、2=MOVE、1=UP。 */
    data class Pt(val x: Float, val y: Float, val action: Int, val time: Long)

    /**
     * 把若干条笔迹交给系统 OCR 引擎，返回识别出的文字；失败返回 null。
     * 一条笔迹 = 一次完整落笔到抬笔（首点 DOWN、中间 MOVE、末点 UP）。
     */
    fun recognize(strokes: List<List<Pt>>): String? = try {
        val inkBuilder = j.inkBuilder.invoke(null)
        var used = 0
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            val strokeBuilder = j.strokeBuilder.invoke(null)
            for (p in stroke) {
                val point = j.pointObtain.invoke(null, p.x, p.y, p.action, p.time)
                j.strokeAddPoint.invoke(strokeBuilder, point)
            }
            j.inkAddStroke.invoke(inkBuilder, j.strokeBuild.invoke(strokeBuilder))
            used++
        }
        if (used == 0) {
            L.w("event=pencil_recognize_skipped reason=empty_strokes")
            null
        } else {
            val ink = j.inkBuild.invoke(inkBuilder)
            val text = j.recognizeText.invoke(j.facade, ink) as? String
            if (text == null) L.w("event=pencil_recognize_null strokes=$used")
            else L.i("event=pencil_recognize_ok strokes=$used text=$text")
            text
        }
    } catch (t: Throwable) {
        L.e("event=pencil_recognize_failed", t)
        null
    }

    /** 释放引擎内部（`RecognizeFacade.onDestroy`）。 */
    fun close() {
        try {
            j.onDestroy?.invoke(j.facade)
            L.i("event=pencil_engine_closed")
        } catch (t: Throwable) {
            L.w("event=pencil_engine_close_failed msg=${t.message}")
        }
    }

    /** 反射句柄，构造时一次性解析好，之后每次识别不再做查找。 */
    private class J(
        val facade: Any,
        val recognizeText: Method,
        val onDestroy: Method?,
        val inkBuilder: Method,
        val inkAddStroke: Method,
        val inkBuild: Method,
        val strokeBuilder: Method,
        val strokeAddPoint: Method,
        val strokeBuild: Method,
        val pointObtain: Method,
    )

    companion object {
        const val JAR_PATH = "/system_ext/framework/xiaomi-pencilengine-pad.jar"

        private const val CLS_FACADE =
            "com.miui.penengine.impl.algorithm.recognizelib.algorithm.RecognizeFacade"
        private const val CLS_INK = "com.miui.penengine.impl.data.Ink"
        private const val CLS_MANAGER = "com.miui.penengine.impl.manager.PencilEngineManager"

        /**
         * 挂载系统引擎并构造识别门面。任何一步失败都返回 null 并留下结构化日志
         * （`event=pencil_engine_open_failed`，带上真实异常，便于定位是 dex 挂载、
         * native 库加载还是构造阶段出的问题）。
         */
        fun open(context: Context): PencilEngine? = try {
            // 父 ClassLoader 用本模块自己的，保证 android.* 与模块类可见；
            // 引擎需要的东西（gson 等）都在它自己那个 dex 里。
            val cl = PathClassLoader(JAR_PATH, PencilEngine::class.java.classLoader)

            val facadeCls = cl.loadClass(CLS_FACADE)
            val inkCls = cl.loadClass(CLS_INK)
            val inkBuilderCls = cl.loadClass("$CLS_INK\$Builder")
            val strokeCls = cl.loadClass("$CLS_INK\$Stroke")
            val strokeBuilderCls = cl.loadClass("$CLS_INK\$Stroke\$Builder")
            val pointCls = cl.loadClass("$CLS_INK\$Point")

            // 构造会连带初始化 MultiLineRecognize —— 也就是在这里加载
            // librecognize_interface.touch.so 并吃下 /system_ext/etc/ocr_model.tflite。
            val facade = facadeCls
                .getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(context)

            // 必须在构造之后：构造函数里的 initWhitelist() 会先算一遍（结果必然是 false），
            // 这里把它覆盖成 true。
            bypassWhitelist(cl)

            PencilEngine(
                J(
                    facade = facade,
                    recognizeText = facadeCls.getDeclaredMethod("recognizeText", inkCls),
                    onDestroy = runCatching { facadeCls.getDeclaredMethod("onDestroy") }.getOrNull(),
                    inkBuilder = inkCls.getDeclaredMethod("builder"),
                    inkAddStroke = inkBuilderCls.getDeclaredMethod("addStroke", strokeCls),
                    inkBuild = inkBuilderCls.getDeclaredMethod("build"),
                    strokeBuilder = strokeCls.getDeclaredMethod("builder"),
                    strokeAddPoint = strokeBuilderCls.getDeclaredMethod("addPoint", pointCls),
                    strokeBuild = strokeBuilderCls.getDeclaredMethod("build"),
                    pointObtain = pointCls.getDeclaredMethod(
                        "obtain",
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                    ),
                )
            ).also { L.i("event=pencil_engine_open_ok") }
        } catch (t: Throwable) {
            L.e("event=pencil_engine_open_failed", t)
            null
        }

        /**
         * 顶掉引擎的包名白名单。
         *
         * `getAuthResult()` 的逻辑是「XMSF 没连上 → 返回 whitelistResult」，而
         * `whitelistResult` 是 `PencilEngineManager` 的私有静态布尔字段，
         * 由 `initWhitelist(context)` 用**调用方包名**查 `EnableAuthData.WHITE_LIST` 得到。
         * 调用方是 `com.xiaomi.type`（不在白名单里）或本模块 App，所以直接改成 true。
         *
         * 这是引擎自己类的普通字段，不在 Android 隐藏 API 名单里，反射不受限。
         *
         * 手势那条路（[StylusGestureEngine]）也在同一个引擎上，而 `GestureFacade` 的构造
         * 会再调一次 `initWhitelist()`，所以它构造完还要再顶一次 —— 因此这里是
         * `internal` 而不是 `private`。
         */
        internal fun applyWhitelistBypass(cl: ClassLoader) = bypassWhitelist(cl)

        private fun bypassWhitelist(cl: ClassLoader) {
            try {
                val field = cl.loadClass(CLS_MANAGER).getDeclaredField("whitelistResult")
                field.isAccessible = true
                field.setBoolean(null, true)
                L.i("event=pencil_whitelist_bypassed")
            } catch (t: Throwable) {
                L.e("event=pencil_whitelist_bypass_failed", t)
            }
        }
    }
}

package io.github.skyshadowhero.fancypad

import android.content.Context
import android.graphics.PointF
import android.view.inputmethod.HandwritingGesture
import dalvik.system.PathClassLoader
import java.lang.reflect.Method

/**
 * 书写手势识别（圈选 / 尖尖插入 / 划掉删除 / 换行 / 合并拆分）。
 *
 * ## 形状识别不自己写
 *
 * 系统笔引擎 `/system_ext/framework/xiaomi-pencilengine-pad.jar` 里本来就有整套手势识别，
 * 而且它的门面**直接产出框架手势对象**（反编译见
 * `~/type/re/work/pe-jadx/.../algorithm/gesture/`）：
 *
 * ```
 * GestureFacade.getGoogleGestureResult(List<PointF>) -> HandwritingGesture
 *   ├── SelectGestureParser      → SelectGesture.Builder().setSelectionArea(rectF)     圈选
 *   ├── InsertModeGestureParser  → InsertModeGesture.Builder().setInsertionPoint(point) 尖尖插入
 *   ├── DeleteGestureParser      → DeleteGesture.Builder().setDeletionArea(rectF)      划掉删除
 *   ├── JoinOrSplitGestureParser → 合并/拆分
 *   └── NewLineGestureParser     → 换行
 * ```
 *
 * 所以我们要做的只是：攒一条笔画的点 → 交它 → 拿到 `HandwritingGesture` →
 * 通过 `InputConnection.performHandwritingGesture()` 让**宿主应用自己去执行**
 * （选区、删字都是宿主编辑器的活，输入法只负责"识别形状 + 报坐标"）。
 *
 * ## 坐标必须是屏幕坐标
 *
 * 引擎把笔画的包围盒直接当成 `selectionArea`/`deletionArea` 交给宿主，
 * 宿主拿它去和自己屏幕上的文本布局比对 —— 所以这里喂的必须是 `rawX/rawY`，
 * **不能**用识别文字时那套"按包围盒归零"的坐标（归零只对文字识别有意义）。
 *
 * ## 为什么要懒建 + 预筛
 *
 * `GestureFacade` 的构造会给全局 `P2PManager` 注册一个 MotionPoint 解析器，
 * 与文字识别共用引擎状态 —— 早先"每次抬笔都调它"，真机表现是识别率越用越差。
 * 所以这里：**只有调用方预筛出"像手势"的笔画**才会真正构造它（[StylusImeHooks] 里做预筛），
 * 正常写字根本不碰这条路径。
 */
internal class StylusGestureEngine {

    private val lock = Any()

    @Volatile private var facade: Any? = null
    @Volatile private var getGesture: Method? = null
    @Volatile private var tried = false

    /**
     * 把一条笔画（屏幕坐标）交给引擎。
     *
     * 返回非空 = 这是一个手势，调用方应当把它交给 `performHandwritingGesture()`；
     * 返回 null = 不是手势（或者引擎不可用），照常当文字处理。
     */
    fun recognize(context: Context, points: List<PointF>): HandwritingGesture? {
        if (points.size < MIN_POINTS) return null
        val m = ensure(context) ?: return null
        val f = facade ?: return null
        return try {
            m.invoke(f, points) as? HandwritingGesture
        } catch (t: Throwable) {
            // 手势识别失败绝不能影响输入法：记一条日志就走
            L.w("event=stylus_gesture_recognize_failed msg=${t.message}")
            null
        }
    }

    /** 懒建。任一步失败都返回 null，并且不再重试（避免每个笔画都白折腾一次）。 */
    private fun ensure(context: Context): Method? {
        getGesture?.let { return it }
        synchronized(lock) {
            getGesture?.let { return it }
            if (tried) return null
            tried = true
            return try {
                // 与文字识别用同一个 jar、同一个挂载方式（它不在 boot classpath 里）
                val cl = PathClassLoader(PencilEngine.JAR_PATH, StylusGestureEngine::class.java.classLoader)
                val cls = cl.loadClass(CLS_GESTURE_FACADE)
                val instance = cls.getDeclaredConstructor(Context::class.java)
                    .apply { isAccessible = true }
                    .newInstance(context)

                // ★ 顺序要紧：GestureFacade 的构造里会 `PencilEngineManager.initWhitelist(context)`
                // 重新算一遍包名白名单（结果必然是 false），必须**在构造之后**再顶掉它，
                // 否则 getGoogleGestureResult() 会因 getAuthResult()==false 直接返回 null。
                PencilEngine.applyWhitelistBypass(cl)

                val method = cls.getMethod("getGoogleGestureResult", List::class.java)
                facade = instance
                getGesture = method
                L.i("event=stylus_gesture_engine_ready")
                method
            } catch (t: Throwable) {
                L.e("event=stylus_gesture_engine_failed", t)
                null
            }
        }
    }

    private companion object {
        const val CLS_GESTURE_FACADE = "com.miui.penengine.impl.algorithm.gesture.GestureFacade"

        /** 点太少不可能构成手势（圆、尖、划都要至少这么多个采样点）。 */
        const val MIN_POINTS = 4
    }
}

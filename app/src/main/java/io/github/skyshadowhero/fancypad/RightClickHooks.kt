package io.github.skyshadowhero.fancypad

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface

/**
 * FancyPad · 把「鼠标右键」换成「长按」。
 *
 * 作用域：**目标应用进程**（不像文本工具栏那样在 SystemUI 里）。默认关闭。
 *
 * ## 为什么要做这个
 *
 * 右键和长按在本机是**两条完全不同的实现**，观感也完全不同：
 *
 * | 手势 | 入口（真机反编译） | 结果 |
 * |---|---|---|
 * | 鼠标右键 | `View.performButtonActionOnTouchDown()` → `showContextMenu(x, y)` | 框架的上下文菜单（一列好几行），在**应用进程**里由 `PhoneWindow` 画，外观跟各自主题走 |
 * | 长按 | `View.performLongClick()` → 选中词 / ActionMode | 选择工具栏，由 SystemUI 的 `RemoteSelectionToolbar` 画（已由 [SelectionToolbarHooks] 换成 Miuix 观感） |
 *
 * 证据（`android.view.View`，HyperOS 4.0 / Android 17 的 framework.jar）：
 *
 * ```java
 * // View.java:4330
 * protected boolean performButtonActionOnTouchDown(MotionEvent event) {
 *     if (event.isFromSource(InputDevice.SOURCE_MOUSE)
 *             && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
 *         return showContextMenu(event.getX(), event.getY());
 *     }
 *     return false;
 * }
 *
 * // View.java:4301（长按路径，鼠标时 isAnchored 为真同样落到 showContextMenu）
 * handled = isAnchored ? showContextMenu(x, y) : showContextMenu();
 * ```
 *
 * 上下文菜单在应用进程里、外观由各应用主题决定，想统一改样式就得把模块注入到每个应用；
 * 而**让右键直接走长按**更省事：长按那条路已经落在 SystemUI 上，样式是统一的。
 *
 * ## 怎么换
 *
 * 不改菜单、也不自己画：**合成一次触摸长按**（TOUCHSCREEN 的 DOWN，
 * 让平台 / Chromium / MIUI 自己的长按检测照常跑），到点再补一个 UP 收尾。
 * 这样长按的既有逻辑（选词、选择手柄、ActionMode、网页长按菜单…）全都不用重写。
 *
 * 合成事件不带 SECONDARY 按键状态，因此不会再触发 [performButtonActionOnTouchDown]，
 * 不存在递归。若视图没接住这次合成按下（`dispatchTouchEvent` 返回 false），
 * 就原样交回系统 —— 原来的右键菜单照常出现，功能不会丢。
 *
 * ⚠ 这个 Hook 在**应用进程**里，所以目标应用必须加进模块作用域（LSPosed 里逐个勾选，
 * 或在本模块的「作用域」页里一键申请）。`scope.list` 只是推荐列表，勾选后需重启该应用。
 */
class RightClickHooks(private val module: XposedInterface) {

    private companion object {
        const val CLS_VIEW = "android.view.View"

        /**
         * 长按触发后再等一小会儿才补 UP：平台的 `CheckForLongPress` 在
         * `ViewConfiguration.getLongPressTimeout()`（通常 500ms）时触发，
         * 太早松开会被判成普通点击。
         */
        const val UP_EXTRA_DELAY_MS = 150L
    }

    @Volatile
    private var installed = false

    /** 由 [XposedEntry] 在目标应用进程里调用。可重复调用。 */
    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val view = classLoader.loadClass(CLS_VIEW)
            val method = view.getDeclaredMethod("performButtonActionOnTouchDown", MotionEvent::class.java)
                .apply { isAccessible = true }
            module.hook(method)
                .setId("view_perform_button_action")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                    val view = chain.getThisObject() as? View
                    if (event == null || view == null || !HookPrefs.rightClickAsLongPress()) {
                        return@intercept chain.proceed()
                    }
                    if (!isSecondaryButtonDown(event)) {
                        return@intercept chain.proceed()
                    }
                    // 交回系统就是弹那个原生的上下文菜单，这里先试长按
                    if (synthLongPress(view, event.x, event.y)) {
                        L.sampled("rc_longpress", limit = 4) {
                            "event=right_click_as_long_press view=${view.javaClass.name}"
                        }
                        return@intercept true
                    }
                    chain.proceed()
                }
            L.i("event=right_click_hooks_installed")
        } catch (t: Throwable) {
            L.w("event=right_click_hooks_failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 只认「鼠标 + 副键按下」，别的都交回系统 */
    private fun isSecondaryButtonDown(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0

    /**
     * 在 (x, y) 合成一次触摸长按。返回是否已被视图接住。
     *
     * 只发 DOWN，不发 UP —— 长按判定由视图自己按时间触发；
     * UP 延后补上只是为了把按压状态收干净。
     */
    private fun synthLongPress(view: View, x: Float, y: Float): Boolean {
        val downOk = runCatching {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            val consumed = view.dispatchTouchEvent(down)
            down.recycle()
            consumed
        }.getOrDefault(false)

        if (!downOk) return false

        val delay = ViewConfiguration.getLongPressTimeout() + UP_EXTRA_DELAY_MS
        runCatching {
            view.postDelayed({
                runCatching {
                    val now = SystemClock.uptimeMillis()
                    val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
                    up.setSource(InputDevice.SOURCE_TOUCHSCREEN)
                    view.dispatchTouchEvent(up)
                    up.recycle()
                }
            }, delay)
        }
        return true
    }
}

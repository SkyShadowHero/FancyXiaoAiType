package io.github.skyshadowhero.fancypad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * FancyPad · 文本选择菜单（右键 / 长按文字弹出的浮动工具栏）改造成 Miuix 观感。
 * 作用域 `com.android.systemui`。
 *
 * ## 为什么 hook 落在 SystemUI，而不是各个应用
 *
 * 反编译证据（HyperOS 4.0 / Android 17 真机 framework.jar）：
 * `android.widget.FloatingToolbar` 的 popup 由 `FloatingToolbarPopup.createInstance()` 按
 * `SelectionToolbarManager.isRemoteSelectionToolbarEnabled()` 二选一，而后者最终落到
 * `com.android.internal.hidden_from_bootclasspath.android.permission.flags.Flags
 * .systemSelectionToolbarEnabled()` —— 本机是**硬编码 `return true`**：
 *
 * ```java
 * public static boolean systemSelectionToolbarEnabled() { return true; }
 * ```
 *
 * 所以 `LocalFloatingToolbarPopup`（框架里自带 `floating_popup_container` 布局的那套）在本机是
 * **死代码**，应用进程只跑 `RemoteFloatingToolbarPopup`：它把菜单项与锚点快照通过
 * `SelectionToolbarManager` 发给 system_server，再由 system_server 转给
 * `com.android.systemui/.selectiontoolbar.app.service.SysUiSelectionToolbarRenderService`
 * 渲染。真正画出来的是 SystemUI 里的
 * [CLS_TOOLBAR]（Kotlin 版 `RemoteSelectionToolbar`），它把视图放进 `SurfaceControlViewHost`。
 *
 * 这条路径对模块非常有利：SystemUI 本来就在作用域里，**不需要把模块注入到每一个应用进程**
 * （LSPosed 没有通配符、官方也不提供「全选」，逐应用勾选代价极高）。
 *
 * ## 改了什么
 *
 * 本机原样式来自 framework-res 的 `floating_popup_container.xml` / `floating_popup_menu_button.xml`
 * 一脉：底色 `@color/materialColorSurfaceContainerHighest`、圆角 `?dialogCornerRadius`、
 * 文字 14sp `@color/materialColorOnSurface`，入场动画只有 150ms 纯 alpha —— 就是「原生小圆角 +
 * MD 配色 + 没动画」的来源。这里换成 Miuix 的 token：
 *
 * | 项 | Miuix 取值 | 出处 |
 * |---|---|---|
 * | 圆角 | 16dp | `miuix-ui/basic/ListPopup.kt` 的 `cornerRadius` |
 * | 底色 | `colorScheme.surfaceContainer`（浅 `#FFFFFF` / 深 `#242424`） | `theme/Colors.kt`，ListPopup 同款 |
 * | 文字色 | `colorScheme.onSurfaceContainer`（浅黑 / 深 `#E6FFFFFF`） | `theme/Colors.kt` |
 * | 字号 | 14sp（`Body2`） | `theme/TextStyles.kt` |
 * | 字体 | 系统默认（HyperOS 上是 MiSans） | 本机 `config_bodyFontFamily = sans-serif` |
 * | 入场 | 缩放 0.92 → 1 + 系统自带的淡入 | 原实现只有 alpha |
 *
 * 总开关 [PrefKeys.TOOLBAR_ENABLED] 关掉即完全不介入（默认关闭）。
 *
 * ## 稳定性
 *
 * 全程 `runCatching` + 反射失败即降级：SystemUI 崩了会连带整个系统界面，任何一步拿不到
 * 都直接放弃本次改造，绝不抛出去。
 */
class SelectionToolbarHooks(private val module: XposedInterface) {

    private companion object {
        const val CLS_TOOLBAR =
            "com.android.systemui.selectiontoolbar.app.ui.RemoteSelectionToolbar"
        const val CLS_SHOW_INFO = "android.view.selectiontoolbar.ShowInfo"

        /** 诊断用的偏好组（不参与功能，只用来把现场数据带出 SystemUI 进程） */
        const val DIAG_GROUP = "fancypad_diag"

        // ---- Miuix 设计 token（值见类注释里的出处表）----
        // 注意：这些不能用 const val —— Kotlin 的常量表达式不接受 .toInt() 转换与
        // Java 常量（Color.BLACK），会在编译期报 "should be a constant value"。
        /** `colorScheme.surfaceContainer`（浅色） */
        val MIUIX_SURFACE_CONTAINER_LIGHT = 0xFFFFFFFF.toInt()
        /** `colorScheme.surfaceContainer`（深色） */
        val MIUIX_SURFACE_CONTAINER_DARK = 0xFF242424.toInt()
        /** `colorScheme.onSurfaceContainer`（浅色） */
        val MIUIX_ON_SURFACE_CONTAINER_LIGHT = Color.BLACK
        /** `colorScheme.onSurfaceContainer`（深色，带 90% 不透明度） */
        val MIUIX_ON_SURFACE_CONTAINER_DARK = 0xE6FFFFFF.toInt()

        const val ENTER_SCALE = 0.92f
        const val ENTER_DURATION_MS = 260L
        const val EXIT_DURATION_MS = 160L

        /**
         * 同一实例两次入场动画的最小间隔。
         * `show()` 在每次布局更新（拖动光标、改窗口大小）时也会被调用，不节流的话
         * 工具栏会反复「弹一下」。
         */
        const val ANIM_THROTTLE_MS = 400L
    }

    @Volatile
    private var installed = false

    /** 反射结果缓存：字段名 → Field（null 表示这个版本没有该字段） */
    private val fields = HashMap<String, Field?>()

    /** 上次播入场动画的时间（按实例，弱引用避免拖住已销毁的工具栏） */
    private val lastAnimated = WeakHashMap<Any, Long>()

    /** 已经给哪些实例挂过「退场缩小」的监听 */
    private val hideWired: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    /** 诊断落点：模块自己的 RemotePreferences 组（本机 logcat 被关掉了，用偏好把现场带出来） */
    private val diagPrefs by lazy {
        runCatching { module.getRemotePreferences(DIAG_GROUP) }.getOrNull()
    }
    private val diagCount = AtomicInteger()

    /** 由 [XposedEntry] 在 com.android.systemui 进程里调用。可重复调用。 */
    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val cls = classLoader.loadClass(CLS_TOOLBAR)
            val showInfo = classLoader.loadClass(CLS_SHOW_INFO)
            val show = cls.getDeclaredMethod("show", showInfo).apply { isAccessible = true }
            module.hook(show)
                .setId("rst_show")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    // 菜单项是在 show() 内部才填进容器的，必须等原实现跑完再改。
                    val self = chain.getThisObject()
                    // 这条是「远程渲染路径」的判定证据：hook 命中说明系统确实走
                    // SystemUI 渲染（而不是应用进程内的 LocalFloatingToolbarPopup）。
                    L.sampled("rst_show_hit", limit = 4) {
                        "event=selection_toolbar_show_hit enabled=${HookPrefs.toolbarEnabled()}"
                    }
                    runCatching { restyle(self) }
                    result
                }
            L.i("event=selection_toolbar_installed class=$CLS_TOOLBAR")
        } catch (t: Throwable) {
            L.w("event=selection_toolbar_install_failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 改造

    /** 每次显示都过一遍：圆角 / 底色 / 文字 / 入场动画。全程不抛。 */
    private fun restyle(self: Any?) {
        if (self == null) return
        val container = field(self, "contentContainer") as? ViewGroup
        val holder = field(self, "contentHolder") as? View

        // 诊断放在开关判定之前：即使开关没开也要能确认 hook 到底有没有命中
        // —— WebView / Via 那两个反例究竟走不走 SystemUI 这条路径，全靠这条。
        diag(
            "self=${self.javaClass.simpleName} enabled=${HookPrefs.toolbarEnabled()} " +
                "container=[${container?.let { describe(it) } ?: "-"}] " +
                "holder=[${holder?.let { describe(it) } ?: "-"}]"
        )
        if (!HookPrefs.toolbarEnabled()) return

        val ctx = field(self, "context") as? Context ?: return
        if (container == null) return
        val dark = isNight(ctx)

        // 1) 底色挂在哪一层：本地版画在内容容器上，远程版可能画在承载 surface 的
        //    contentHolder 上。按现场「谁本来就有不透明底色」选层，并把**另一层的
        //    原底色清掉** —— 只改一层、另一层照旧的话，原版的小圆角会从新底色
        //    边缘露出来（外面一圈小圆角 + 里面一块新底色）。
        val surface = when {
            isOpaque(container) -> container
            holder != null && isOpaque(holder) -> holder
            else -> container
        }
        if (surface !== container && isOpaque(container)) container.background = null
        if (holder != null && surface !== holder && isOpaque(holder)) holder.background = null

        //    clipToOutline 让子视图（菜单项的按压反馈）也被圆角裁掉，
        //    否则方角的高亮会溢出到圆角外面。
        surface.background = roundedRect(
            dp(ctx, HookPrefs.toolbarCornerDp()),
            if (dark) MIUIX_SURFACE_CONTAINER_DARK else MIUIX_SURFACE_CONTAINER_LIGHT,
        )
        surface.clipToOutline = true

        // 2) 溢出面板（ListView）自带一层不透明底色，会方方正正地盖住圆角，
        //    清掉让 surface 底色透出来。
        (field(self, "overflowPanel") as? View)?.background = null

        // 3) 文字：Miuix Body2 字号 + onSurfaceContainer 颜色 + 系统默认字体。
        styleText(
            container,
            if (dark) MIUIX_ON_SURFACE_CONTAINER_DARK else MIUIX_ON_SURFACE_CONTAINER_LIGHT,
            HookPrefs.toolbarTextSp(),
        )

        // 4) 入场动画：叠加缩放（系统自带的只有 alpha）。
        animateEnter(self, container)

        diag(
            "restyled surface=${surface.javaClass.simpleName} dark=$dark " +
                "corner=${HookPrefs.toolbarCornerDp()} textSp=${HookPrefs.toolbarTextSp()} " +
                "container=[${describe(container)}] holder=[${holder?.let { describe(it) } ?: "-"}]"
        )
    }

    /** 诊断用：尺寸 + 当前底色的 Drawable 类型与不透明度（op=-1 表示不透明） */
    private fun describe(v: View): String {
        val bg = v.background
        return "${v.javaClass.simpleName} ${v.width}x${v.height} " +
            "bg=${bg?.javaClass?.simpleName ?: "none"}/op=${bg?.opacity ?: "-"}"
    }

    /** 这一层当前是否由不透明底色承载（= 它才是肉眼看到的那层表面） */
    private fun isOpaque(v: View): Boolean =
        v.background?.let { runCatching { it.opacity == PixelFormat.OPAQUE }.getOrDefault(false) }
            ?: false

    /**
     * 把现场数据写进模块自己的 RemotePreferences 组。
     *
     * 为什么不用 logcat：本机用户出于省电与隐私把日志关了（`log -t` 探针写进去也读不到），
     * 所以 logcat 在这台设备上不作为诊断通道。偏好走的是 binder，与 logd 无关。
     * 读法：
     * ```
     * su -c 'cat /data/data/io.github.skyshadowhero.fancypad/shared_prefs/fancypad_diag.xml'
     * ```
     * 只记前几条，避免反复刷偏好。
     */
    private fun diag(msg: String) {
        val n = diagCount.incrementAndGet()
        if (n > 6) return
        runCatching {
            val p = diagPrefs ?: return
            val e = p.edit()
            if (n == 1) e.putString("first", msg)
            e.putString("last", msg)
            e.putInt("count", n)
            e.apply()
        }
    }

    /** 递归把文字换成 Miuix 观感。溢出按钮是 ImageButton，不受影响。 */
    private fun styleText(root: View, color: Int, sizeSp: Float) {
        fun walk(v: View) {
            if (v is TextView) {
                v.setTextColor(color)
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                // Miuix 用系统默认字体族；本机 config_bodyFontFamily 就是 sans-serif，
                // 在 HyperOS 上解析成 MiSans。
                v.typeface = Typeface.SANS_SERIF
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
    }

    private fun animateEnter(self: Any, container: View) {
        val now = SystemClock.uptimeMillis()
        val last = synchronized(lastAnimated) { lastAnimated[self] } ?: 0L
        if (now - last < ANIM_THROTTLE_MS) {
            // 布局更新引起的重复 show：保持当前状态，别再弹一次
            container.scaleX = 1f
            container.scaleY = 1f
            return
        }
        synchronized(lastAnimated) { lastAnimated[self] = now }

        wireExit(self, container)

        container.scaleX = ENTER_SCALE
        container.scaleY = ENTER_SCALE
        container.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    /**
     * 退场也缩回去，跟入场对称。
     *
     * 不替换两个 AnimatorSet 本身：它们的结束回调负责真正的 dismiss（把 surface 收掉），
     * 重造容易漏掉回调。挂个监听器搭在原动画上更稳。
     */
    private fun wireExit(self: Any, container: View) {
        synchronized(hideWired) { if (!hideWired.add(self)) return }
        for (name in arrayOf("delayedHideAnimation", "immediateHideAnimation")) {
            val set = field(self, name) as? AnimatorSet ?: continue
            set.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    runCatching {
                        container.animate()
                            .scaleX(ENTER_SCALE)
                            .scaleY(ENTER_SCALE)
                            .setDuration(EXIT_DURATION_MS)
                            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
                            .start()
                    }
                }
            })
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            ctx.resources.displayMetrics,
        )

    private fun roundedRect(radiusPx: Float, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
        }

    /**
     * 读目标对象上的字段。
     *
     * SystemUI 没有做名称混淆（字节码里 `contentContainer` / `showAnimation` 都是可读原名），
     * 所以按名字取是可靠的；取不到就返回 null，由调用方降级。
     * 每个字段名只解析一次，后续命中缓存。
     */
    private fun field(obj: Any, name: String): Any? {
        val f = synchronized(fields) {
            if (fields.containsKey(name)) {
                fields[name]
            } else {
                val resolved = runCatching { obj.javaClass.getField(name) }.getOrNull()
                    ?: runCatching {
                        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    }.getOrNull()
                fields[name] = resolved
                resolved
            }
        } ?: return null
        return runCatching { f.get(obj) }.getOrNull()
    }
}

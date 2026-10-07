package io.github.skyshadowhero.fancypad

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ListPopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field

/**
 * FancyPad · 右键弹出的**多行上下文菜单**改成 Miuix 观感。
 *
 * 作用域：**目标应用进程**。这个菜单由应用进程里的框架菜单类画（不像选择工具栏那样在
 * SystemUI），所以目标应用要加进模块作用域；默认关闭。
 *
 * ## 反编译证据（HyperOS 4.0 / Android 17 真机，用真实 class_defs 解析器核实）
 *
 * ```
 * View.performButtonActionOnTouchDown(MotionEvent)      protected  classes4
 *  → View.showContextMenu(float,float)                  public     classes4
 *  → DecorView.showContextMenuForChildInternal(...)     private    classes5
 *  → ContextMenuBuilder.showPopup(...)                  public     classes4
 *      （常量扫描确认用的是 attr/contextPopupMenuStyle = 0x01010501）
 *  → MenuPopupHelper.createPopup()                      private    classes4
 *      屏宽 smallestWidth ≥ dimen/cascading_menus_min_smallest_width(720dp) 时用
 *      CascadingMenuPopup，否则 StandardMenuPopup
 *      ★ 本机 2136×3200px @440dpi → smallestWidth = 776dp ≥ 720dp
 *        ⇒ 真机走的是 CascadingMenuPopup
 *  → CascadingMenuPopup.createPopupWindow()
 *  → new android.widget.MenuPopupWindow(context, null, styleAttr, styleRes)
 *      MenuPopupWindow extends ListPopupWindow（classes5，boot classpath）
 *  → ListPopupWindow.<init> 内部 new PopupWindow(...)
 *  → ListPopupWindow.show() → buildDropDown() → mPopup.showAsDropDown(...)
 * ```
 *
 * 「小圆角 + Material 配色」来自背景 drawable
 * `framework-res/res/drawable/popup_background_material.xml`：
 *
 * ```xml
 * <shape android:shape="rectangle">
 *     <corners android:radius="2.0dp" />                       <!-- 2dp，就是那个"很小很小的圆角" -->
 *     <solid android:color="?^attr-private/colorPopupBackground" />
 * </shape>
 * ```
 *
 * 由 `Widget.Material.ListPopupWindow`（`Widget.Material.PopupMenu` 的父样式）的
 * `<item name="popupBackground">` 指定。行视图是 `MenuAdapter.getView()` 充气的
 * `ListMenuItemView`（本机 cascading 版为 `cascading_menu_item_layout_material`，
 * 固定 48dp 行高），文字 `?textAppearanceLargePopupMenu`（本机 = 16sp / sans-serif）。
 *
 * 另外核实：HyperOS 在**资源层没有改动**这些（`MiuiFrameworkResOverlay` /
 * `FrameworksResCommon_Sys` 只覆盖 `config_*`），所以 AOSP 的这套结论在本机成立。
 *
 * ## 两个 hook
 *
 * | 目标 | 类/方法 | 说明 |
 * |---|---|---|
 * | 背景圆角与底色 | `android.widget.MenuPopupWindow.<init>(Context, AttributeSet, int, int)` | 构造完成后 `setBackgroundDrawable()` 换成 Miuix 圆角矩形；本机 CascadingMenuPopup 一定会走这里 |
 * | 背景兜底 | `android.widget.ListPopupWindow.show()` | 只对 `MenuPopupWindow` 生效，`proceed()` 前再设一次背景、之后补内边距与入场动画 |
 * | 行文字 | `com.android.internal.view.menu.MenuAdapter.getView(int, View, ViewGroup)` | 拿到行视图后改 TextView 颜色/字号/字体 |
 *
 * 前两个都是 boot classpath 上的公开类，名字在任何应用里都一样。
 *
 * ## 覆盖范围（实测结论）
 *
 * - **覆盖**：所有走框架 `View.showContextMenu()` 的菜单 —— 包括 Chromium/WebView
 *   （它的 dex 里只有 `ContextMenu`，没有自建 `MenuBuilder`/`MenuPopupHelper`，
 *   也就是交给框架画）。
 * - **不覆盖**：AndroidX AppCompat / Material Components 的菜单。那些类在宿主 APK 自己的
 *   dex 里，且被 R8 改名 —— 实测 `com.github.android` 的 5 个 dex 里
 *   `androidx/appcompat/view/menu/` 只剩 layout 引用保留下来的 3 个 View 类，
 *   `MenuBuilder`/`MenuPopupHelper`/`MenuAdapter`/`Standard*`/`Cascading*` 全部不存在
 *   （同 dex 里能看到 `Landroidx/appcompat/widget/a;` 这类混淆名），
 *   所以**没法按名字 hook**。自带菜单（Compose DropdownMenu、自绘 PopupWindow）同理。
 *
 * Miuix token 与 [SelectionToolbarHooks] 一致，取值出处见那边的类注释。
 */
class ContextMenuHooks(private val module: XposedInterface) {

    private companion object {
        /**
         * 真正的弹窗类。注意是 `android.widget` 而不是
         * `com.android.internal.view.menu` —— 后者在本机不存在（我一开始就踩了这个坑）。
         */
        const val CLS_MENU_POPUP_WINDOW = "android.widget.MenuPopupWindow"

        /** 行视图适配器（框架类，无混淆） */
        const val CLS_MENU_ADAPTER = "com.android.internal.view.menu.MenuAdapter"

        /** `ListPopupWindow.show()` 是所有下拉的公共路径，靠类名过滤出菜单弹窗 */
        const val MENU_POPUP_CLASS_HINT = "MenuPopupWindow"

        // ---- Miuix 设计 token（与文本选择工具栏一致）----
        val MIUIX_SURFACE_CONTAINER_LIGHT = 0xFFFFFFFF.toInt()
        val MIUIX_SURFACE_CONTAINER_DARK = 0xFF242424.toInt()
        val MIUIX_ON_SURFACE_CONTAINER_LIGHT = Color.BLACK
        val MIUIX_ON_SURFACE_CONTAINER_DARK = 0xE6FFFFFF.toInt()

        /** 圆角矩形没有 9-patch padding，行会贴到圆角上，补一点上下内边距 */
        const val LIST_VERTICAL_PADDING_DP = 6f

        const val ENTER_SCALE = 0.94f
        const val ENTER_DURATION_MS = 180L

        /** 诊断用的偏好组（不参与功能，只用来把现场数据带出应用进程） */
        const val DIAG_GROUP = "fancypad_diag"
    }

    @Volatile
    private var installed = false

    private val fields = HashMap<String, Field?>()

    private var diagCount = 0

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        hookPopupWindowCtor(classLoader)
        hookPopupShow(classLoader)
        hookMenuItemView(classLoader)
    }

    // ------------------------------------------------------------------ 背景

    /**
     * 主 hook：`MenuPopupWindow` 构造器。
     *
     * 选它而不是只 hook `show()` 的原因：本机走 `CascadingMenuPopup`，它显式
     * `new MenuPopupWindow(...)`，构造器**一定**会命中；而 `show()` 要额外做类名过滤。
     * 时机也没问题 —— `buildDropDown()` 只读取背景的 padding 来算宽度，不会重设背景。
     */
    private fun hookPopupWindowCtor(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass(CLS_MENU_POPUP_WINDOW)
            val ctor = cls.getDeclaredConstructor(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            module.hook(ctor)
                .setId("menu_popup_window_init")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    // 构造完成之后字段才可用
                    runCatching { applyBackground(chain.getThisObject(), "ctor") }
                    result
                }
            L.i("event=context_menu_ctor_installed")
        } catch (t: Throwable) {
            L.w("event=context_menu_ctor_failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 兜底 hook：`ListPopupWindow.show()`。
     * 背景必须在 `proceed()` **之前**设置（`show()` 内部按背景 padding 算下拉层尺寸），
     * 内边距与入场动画放在 `proceed()` 之后。
     */
    private fun hookPopupShow(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("android.widget.ListPopupWindow")
            val show = cls.getDeclaredMethod("show").apply { isAccessible = true }
            module.hook(show)
                .setId("lpw_show")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val self = chain.getThisObject()
                    val isMenu = HookPrefs.contextMenuEnabled() && isMenuPopup(self)
                    if (isMenu) {
                        runCatching { applyBackground(self, "show") }
                    }
                    val result = chain.proceed()
                    if (isMenu) {
                        runCatching { afterShow(self) }
                    }
                    result
                }
            L.i("event=context_menu_show_installed")
        } catch (t: Throwable) {
            L.w("event=context_menu_show_failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun isMenuPopup(self: Any?): Boolean =
        self != null && self.javaClass.name.contains(MENU_POPUP_CLASS_HINT)

    private fun applyBackground(self: Any?, from: String) {
        if (!HookPrefs.contextMenuEnabled()) return
        val popup = self as? ListPopupWindow ?: return
        // mContext 是 ListPopupWindow 的私有字段
        val ctx = field(popup, "mContext") as? Context ?: return
        val dark = isNight(ctx)
        popup.setBackgroundDrawable(
            roundedRect(
                dp(ctx, HookPrefs.contextMenuCornerDp()),
                if (dark) MIUIX_SURFACE_CONTAINER_DARK else MIUIX_SURFACE_CONTAINER_LIGHT,
            )
        )
        diag("bg from=$from corner=${HookPrefs.contextMenuCornerDp()} dark=$dark ${popup.javaClass.name}")
    }

    private fun afterShow(self: Any?) {
        val popup = self as? ListPopupWindow ?: return
        val list = popup.listView ?: return
        val ctx = list.context ?: return
        val pad = dp(ctx, LIST_VERTICAL_PADDING_DP).toInt()
        if (list.paddingTop != pad || list.paddingBottom != pad) {
            list.setPadding(list.paddingLeft, pad, list.paddingRight, pad)
        }
        // 入场：缩放 + 淡入（主题原本只有 popupEnterTransition 的位移+淡入）
        list.scaleX = ENTER_SCALE
        list.scaleY = ENTER_SCALE
        list.alpha = 0f
        list.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    // ------------------------------------------------------------------ 文字

    /**
     * 行视图：`MenuAdapter.getView()` 充气出 `ListMenuItemView`，里面是 `@id/title` 与
     * `@id/shortcut` 两个 TextView。这里按类型递归改，不依赖那两个 id
     * （id 常量在不同版本/布局间会变）。
     */
    private fun hookMenuItemView(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass(CLS_MENU_ADAPTER)
            val getView = cls.getDeclaredMethod(
                "getView",
                Int::class.javaPrimitiveType,
                View::class.java,
                ViewGroup::class.java,
            ).apply { isAccessible = true }
            module.hook(getView)
                .setId("menu_adapter_getview")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (HookPrefs.contextMenuEnabled()) {
                        val row = result as? View
                        if (row != null) runCatching { styleRow(row) }
                    }
                    result
                }
            L.i("event=context_menu_row_installed")
        } catch (t: Throwable) {
            L.w("event=context_menu_row_failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun styleRow(row: View) {
        val ctx = row.context ?: return
        val dark = isNight(ctx)
        styleText(
            row,
            if (dark) MIUIX_ON_SURFACE_CONTAINER_DARK else MIUIX_ON_SURFACE_CONTAINER_LIGHT,
            HookPrefs.contextMenuTextSp(),
        )
    }

    /** 递归改文字。行里的分隔条 ImageView、子菜单箭头都不受影响。 */
    private fun styleText(root: View, color: Int, sizeSp: Float) {
        fun walk(v: View) {
            if (v is TextView) {
                v.setTextColor(color)
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                // Miuix 用系统默认字体族；本机 config_bodyFontFamily = sans-serif
                v.typeface = Typeface.SANS_SERIF
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
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

    /** 诊断：写进模块自己的 RemotePreferences 组（本机 logcat 被用户关了，见 SelectionToolbarHooks） */
    private fun diag(msg: String) {
        val n = diagCount++
        if (n > 4) return
        runCatching {
            val p = module.getRemotePreferences(DIAG_GROUP) ?: return
            p.edit().putString("ctxmenu_last", "$msg @${SystemClock.uptimeMillis()}").apply()
        }
    }
}

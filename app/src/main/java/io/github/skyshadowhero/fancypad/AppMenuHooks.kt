package io.github.skyshadowhero.fancypad

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * FancyPad · 「右键菜单」域：把**应用进程内**弹出的菜单换成 Miuix 观感。
 * 作用域 = 目标应用自身（本机先只挂 `mark.via`）。
 *
 * ## 为什么不在 SystemUI 里做
 *
 * 真机实测（HyperOS 4.0 / Android 17，sendevent 合成鼠标右键 + screencap 截图取证）：
 *
 * ```
 * Window #20 Window{3de398b u0 PopupWindow:3c31ce6}:
 *     mOwnerUid=10314   package=mark.via                  ← 应用自己的进程
 *     mAttrs={(1465,1330)(374x440) ty=APPLICATION_PANEL surfaceInsets=Rect(16,16-16,16)}
 *     mParentWindow=Window{e20a4f2 u0 mark.via/mark.via.Shell}  mLayoutAttached=true
 * ```
 *
 * 右键菜单是这个应用自己 `PopupWindow.showAsDropDown()` 弹出来的 —— 而
 * 「长按/选中菜单」（见 [SelectionToolbarHooks]）是 SystemUI 画的。两者是**两条完全不同的路径**：
 * 后者改一处全局生效，前者**只能逐应用注入**（LSPosed 没有通配符作用域）。
 *
 * ## 为什么 hook 在 PopupWindow 这个公共漏斗上
 *
 * 系统 WebView 自带菜单实现（`org.chromium/android_webview/contextmenu/AwContextMenuHelper`，类名未混淆），
 * 框架自己的菜单（`ListPopupWindow` / `MenuPopupWindow`）又长得一样，而且**都会经过 `PopupWindow` 的显示入口**。
 * 所以这里只 hook `PopupWindow.showAsDropDown(View,int,int,int)` 与 `showAtLocation(View,int,int,int)`
 * （前者是所有 showAsDropDown 重载的收口），一个 hook 覆盖两种来源，不依赖任何未混淆私有类名。
 *
 * ## 怎么避免误伤普通弹窗
 *
 * 只处理「内容视图里含一个 **≥2 行**的 `ListView`」的弹窗 —— 菜单都长这样，
 * Tooltip / Toast / 按钮气泡不会。判定不过就直接返回，一个字节都不改。
 *
 * ## 换成什么（数值取自 miuix 源码，不是猜的）
 *
 * | 项 | Miuix 取值 | 出处 |
 * |---|---|---|
 * | 圆角 | 16dp | `miuix-ui/basic/ListPopup.kt` → `ListPopupContent.cornerRadius` |
 * | 底色 | `colorScheme.surfaceContainer` | 同上 → `ListPopupContent.backgroundColor` |
 * | 浅色取值 | `Color.White`（#FFFFFF） | `miuix-ui/theme/Colors.kt` 浅色方案字面值 |
 * | 深色取值 | `#242424` | `miuix-ui/theme/Colors.kt` 深色方案 |
 * | 文字色 | `onSurfaceContainer` | `theme/Colors.kt` |
 * | 字号 / 字重 | 16sp（`Body1`）+ Medium | `basic/Dropdown.kt` → `DropdownImpl` 标题 |
 * | 高亮 | 扁平矩形叠加层：悬停 6% / 按下 10% / 聚焦 8% 的 `onBackground` | `utils/MiuixIndication.kt` |
 * | 每行横向内边距 | 20dp | `basic/Dropdown.kt` → `DropdownDefaults.InsideHorizontalPadding` |
 * | 中间行纵向内边距 | 12dp | `basic/Dropdown.kt` → `DropdownDefaults.MiddleVerticalPadding` |
 * | 首/末行纵向内边距 | 20dp | `basic/Dropdown.kt` → `DropdownDefaults.FirstLastVerticalPadding` |
 * | 入场 | 缩放 0.92 → 1 | 原实现只有窗口自带的淡入 |
 *
 * ## 为什么改造推迟到「布局之后」
 *
 * 两件事都必须在布局完成后做：
 * 1. **菜单项是布局阶段才填进 ListView 的**，之前子视图根本不存在，改不到文字；
 * 2. **选层要靠尺寸**（谁真正在画底色、面积多大），`show()` 那一刻所有视图宽高还是 0。
 *
 * 上一版就是在这里踩坑：`show()` 时用 `background.opacity == OPAQUE` 挑层，
 * 结果真正在画那层灰的**是半透明的**，没被选中也没被清掉 —— 于是「圆角变了（靠 clipToOutline 裁出来），
 * 颜色没变（灰仍然由那一层画）」。现在改成：布局后按「会画的层里面积最大的那个」当选，
 * 其余会画的层全部清掉。
 *
 * ## 诊断通道
 *
 * 本机 logcat 已停（`logcat -d` 只有 4 月的旧记录），框架的 RemotePreferences 在 hook 进程侧写不进去，
 * 所以现场写到**目标应用自己的 cache 目录**：`<app cache>/fancypad-appmenu.log`。
 * 只记前几次，读法：
 * ```
 * su -c 'cat /data/data/mark.via/cache/fancypad-appmenu.log'
 * ```
 *
 * ## 稳定性
 *
 * 全程 `runCatching`：任何一步拿不到就放弃本次改造，绝不抛给宿主应用。
 * 开关 [PrefKeys.APPMENU_ENABLED] 默认关闭，关掉即完全不介入。
 */
class AppMenuHooks(private val module: XposedInterface) {

    private companion object {
        // ---- Miuix 设计 token（值见类注释里的出处表）----
        val MIUIX_SURFACE_CONTAINER_LIGHT = 0xFFFFFFFF.toInt()
        val MIUIX_SURFACE_CONTAINER_DARK = 0xFF242424.toInt()
        val MIUIX_ON_SURFACE_CONTAINER_LIGHT = Color.BLACK
        val MIUIX_ON_SURFACE_CONTAINER_DARK = 0xE6FFFFFF.toInt()
        /** `colorScheme.disabledOnSurface`（浅色，Miuix 源码字面值 0xFFB2B2B2） */
        val MIUIX_DISABLED_LIGHT = 0xFFB2B2B2.toInt()
        /** 深色下的禁用文字（Miuix 深色方案同族的浅灰） */
        val MIUIX_DISABLED_DARK = 0x66FFFFFF.toInt()

        const val ENTER_SCALE = 0.92f
        const val ENTER_DURATION_MS = 260L

        /** 同一实例两次入场动画的最小间隔（菜单可能因内容变化被重复 show） */
        const val ANIM_THROTTLE_MS = 400L

        /** 至少这么多行才认它是菜单，避免误伤单选下拉之类的单行弹窗 */
        const val MIN_MENU_ROWS = 2

        /** 首/末行比中间行多出来的纵向内边距：Miuix 20dp − 12dp */
        const val FIRST_LAST_EXTRA_DP = 8f

        /** Miuix 图标格 `DropdownDefaults.IconMinSize` = 26dp（行高的内容部分） */
        const val ICON_CELL_DP = 26f

        /** Miuix 首/末行额外的纵向留白 `FirstLastVerticalPadding` = 20dp */
        const val FIRST_LAST_VPAD_DP = 20f

        /** 行内边距固定值：左右与上下都用 12dp（已按用户要求去掉滑块） */
        const val PAD_H_DP = 12f
        const val PAD_V_DP = 12f

        /** Miuix `ListPopupDefaults.MinWidth`：弹层最小宽度 200dp */
        const val MIN_WIDTH_DP = 200f

        /** Miuix `ListPopupColumn` 的宽度上限 288dp */
        const val MAX_WIDTH_DP = 288f

        /** Miuix `DropdownDefaults.IconEndPadding`：图标格与标题之间 12dp */
        const val ICON_GAP_DP = 12f

        const val LOG_NAME = "fancypad-appmenu.log"

        /** 最多记录几次现场，避免反复刷文件 */
        const val MAX_DIAG = 24
    }

    @Volatile
    private var installed = false

    /** 上次播入场动画的时间（按弹窗实例，弱引用避免拖住已销毁的弹窗） */
    private val lastAnimated = WeakHashMap<Any, Long>()

    /**
     * 已知的菜单行 → 它该用的高亮 drawable。
     *
     * 用途见 [install] 里对 `View.setBackground` 的 hook：框架那套菜单（`MenuDropDownListView`）
     * 是**在 hover 的时候**才往行上塞高亮背景的 —— 那发生在我们改造之后，
     * 于是它和我们的 selector 叠在一起，表现就是「第二种菜单的遮罩更深、而且切换是闪现」。
     */
    private val rowHighlights = WeakHashMap<View, MiuixHighlightDrawable>()

    /**
     * 每个弹窗实例「高亮原本挂在哪一层」的判定结果。
     *
     * 必须缓存：第一轮 restyle 就会把行背景里的 `RippleDrawable` 清掉，后面几轮重新判会
     * 误判成「框架那套」，于是又给行加一份高亮 —— 背景 + 前景双份、alpha 叠成 0.12
     * （实测到的「遮罩比另一种深」就是这么来的）。
     */
    private val rowBasedCache = WeakHashMap<Any, Boolean>()

    /** 菜单的 ListView → 它该用的高亮基色（0 = 黑、0xFFFFFF = 白）。拦截 setSelector 时用。 */
    private val menuListColors = WeakHashMap<View, Int>()

    /**
     * 需要规范化布局参数的**分隔线视图**。
     *
     * 用途见 install() 里对 `View.setLayoutParams` 的 hook：宿主会反复重设这些视图的
     * LayoutParams（框架的 ListMenuItemView.onMeasure、Chromium 的 adapter 绑定），
     * 直接改一次会被还原（实测「闪一下又回来」）。这里改成在**每次设置时替换**，
     * 于是间距永远是 0、行高压成贴合内容。
     */
    private val dividerParams = WeakHashMap<View, Boolean>()



    private val diagCount = AtomicInteger()

    /** 由 [XposedEntry] 在目标应用进程里调用。可重复调用。 */
    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        var ok = 0
        try {
            val cls = PopupWindow::class.java

            // showAsDropDown(View, int, int, int) 是所有 showAsDropDown 重载的收口
            val showAsDropDown = cls.getDeclaredMethod(
                "showAsDropDown",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            module.hook(showAsDropDown)
                .setId("appmenu_show_dropdown")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    // 宽度必须在 show() 之前定好：之后写必然落在入场动画途中/之后 → 「宽度突变」
                    runCatching { presizeWidth(chain.getThisObject() as? PopupWindow) }
                    val result = chain.proceed()
                    runCatching {
                        // 第 0 个参数就是锚点 View（showAsDropDown 是 anchor，showAtLocation 是 parent），
                        // 用它算出「从哪一角展开」，对应 Miuix 的 localTransformOrigin。
                        onShown(
                            chain.getThisObject() as? PopupWindow,
                            runCatching { chain.getArg(0) as? View }.getOrNull(),
                        )
                    }
                    result
                }
            ok++

            val showAtLocation = cls.getDeclaredMethod(
                "showAtLocation",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            module.hook(showAtLocation)
                .setId("appmenu_show_at_location")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    // 宽度必须在 show() 之前定好：之后写必然落在入场动画途中/之后 → 「宽度突变」
                    runCatching { presizeWidth(chain.getThisObject() as? PopupWindow) }
                    val result = chain.proceed()
                    runCatching {
                        // 第 0 个参数就是锚点 View（showAsDropDown 是 anchor，showAtLocation 是 parent），
                        // 用它算出「从哪一角展开」，对应 Miuix 的 localTransformOrigin。
                        onShown(
                            chain.getThisObject() as? PopupWindow,
                            runCatching { chain.getArg(0) as? View }.getOrNull(),
                        )
                    }
                    result
                }
            ok++

            // 行背景的兜底：框架在 hover 时会给行重新设背景（瞬时高亮），
            // 对我们的菜单行一律替换成自己的动画高亮 —— 同一个槽位，不会叠加。
            val setBackground = View::class.java
                .getDeclaredMethod("setBackground", android.graphics.drawable.Drawable::class.java)
            module.hook(setBackground)
                .setId("appmenu_set_background")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val view = chain.getThisObject() as? View
                    val replacement = if (view != null) {
                        synchronized(rowHighlights) { rowHighlights[view] }
                    } else {
                        null
                    }
                    if (replacement != null) {
                        chain.proceed(arrayOf<Any?>(replacement))
                    } else {
                        chain.proceed()
                    }
                }
            ok++

            // 框架在 hover 时会把自己的 selector 塞回 ListView（实测就是「切换项没有过渡」的原因：
            // 我的 drawable 被换掉了）。这里拦截 setSelector，凡是我们的菜单列表，
            // 一律换成**新的** MiuixHighlightDrawable —— 新实例 alpha 从 0 开始，
            // 于是每一次 hover 切换都会重新播一次淡入。
            val setSelector = android.widget.AbsListView::class.java
                .getDeclaredMethod("setSelector", android.graphics.drawable.Drawable::class.java)
            module.hook(setSelector)
                .setId("appmenu_set_selector")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val list = chain.getThisObject() as? View
                    val color = if (list != null) {
                        synchronized(menuListColors) { menuListColors[list] }
                    } else {
                        null
                    }
                    if (color != null) {
                        chain.proceed(arrayOf<Any?>(MiuixHighlightDrawable(color)))
                    } else {
                        chain.proceed()
                    }
                }
            ok++

            // 分隔线的布局参数拦截：宿主每次重设都替换成「贴合内容 + margin 0」。
            // 这比「改一次」可靠 —— 宿主没有机会把它还原回去。
            val setLp = View::class.java.getDeclaredMethod(
                "setLayoutParams",
                android.view.ViewGroup.LayoutParams::class.java,
            )
            module.hook(setLp)
                .setId("appmenu_set_layout_params")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val v = chain.getThisObject() as? View
                    val lp = runCatching {
                        chain.getArg(0) as? android.view.ViewGroup.LayoutParams
                    }.getOrNull()
                    val tracked = v != null &&
                        synchronized(dividerParams) { dividerParams.containsKey(v) }
                    if (tracked && lp != null) {
                        runCatching {
                            (lp as? android.view.ViewGroup.MarginLayoutParams)?.let { m ->
                                m.leftMargin = 0
                                m.rightMargin = 0
                                m.topMargin = 0
                                m.bottomMargin = 0
                            }
                        }
                    }
                    chain.proceed()
                }
            ok++
        } catch (t: Throwable) {
            L.w("event=app_menu_install_failed ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        L.i("event=app_menu_installed hooks=$ok")
    }

    // ------------------------------------------------------------------ 判定

    /** 每次 `PopupWindow` 显示都会进来；判定不过就直接返回。 */
    private fun onShown(popup: PopupWindow?, anchor: View?) {
        if (popup == null) return
        val content = runCatching { popup.contentView }.getOrNull() ?: return
        if (findMenuList(content) == null) {
            // 不是菜单：只在开关打开时记一条「探针」，用来找出「没被改造到的那些菜单」
            // 到底长什么样（是别种弹窗？还是我们的判定条件太窄？）。
            if (HookPrefs.appMenuEnabled()) diag(content, "probe reason=no_menu_list")
            return
        }

        // 每次改造前从框架重读偏好：不然「在 App 里关掉开关」对已注入的进程不生效
        //（变更通知不一定能到达已注入进程，旧快照会一直用下去）。
        runCatching { HookPrefs.rebind(module) }

        if (!HookPrefs.appMenuEnabled()) {
            diag(content, "skipped reason=disabled")
            return
        }
        val ctx = runCatching { content.context }.getOrNull() ?: return

        // 分类开关 + 应用白名单（白名单为空 = 不限制）。
        // 分类判定与 restyle 用的是同一份缓存，保证「判定的类型」和「实际改造方式」一致。
        val list0 = findMenuList(content) ?: return
        val rows0 = menuRows(list0)
        val rowBased0 = synchronized(rowBasedCache) {
            rowBasedCache.getOrPut(popup) { rows0.any { it.background is RippleDrawable } }
        }
        // 不为 WebView 单独写判断：不管哪一类菜单，来了就按同一套处理
        //（rowBased 只决定高亮挂在哪一层，不决定做不做）。
        if (!HookPrefs.appMenuAppAllowed(ctx.packageName)) {
            diag(content, "skipped reason=app_not_allowed pkg=${ctx.packageName}")
            return
        }

        // 入场动画**不碰**：缩放/pivot 那套会带副作用（实测会被原生窗口动画与后续
        // 布局互相干扰），这里保留宿主/系统自己的出现动画。

        // 改造统一推迟到布局之后（子视图存在、尺寸已知）。
        val once = AtomicInteger(0)
        runCatching {
            content.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (once.incrementAndGet() > 3) {
                            runCatching {
                                content.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            }
                            return
                        }
                        runCatching { restyle(popup, ctx, content) }
                    }
                }
            )
        }
        // 兜底：个别视图不再触发全局布局回调
        runCatching {
            content.postDelayed({ runCatching { restyle(popup, ctx, content) } }, 120L)
        }
    }

    // ------------------------------------------------------------------ 改造

    /** 幂等：重复调用只是再设一遍同样的值。 */
    private fun restyle(popup: PopupWindow, ctx: Context, content: View) {
        val dark = isNight(ctx)
        val surface = pickSurface(content) ?: return

        // 1) 可见表面换成 Miuix 圆角 + surfaceContainer
        surface.background = roundedRect(
            dp(ctx, HookPrefs.appMenuCornerDp()),
            if (dark) MIUIX_SURFACE_CONTAINER_DARK else MIUIX_SURFACE_CONTAINER_LIGHT,
        )
        surface.clipToOutline = true

        // 先判断这本菜单的高亮原本挂在哪一层，**必须在 stripPainters 之前判断**：
        //   行自带 ripple（Chromium 那套）→ 高亮是行级的；
        //   否则（框架那套，行的背景是 none）→ 高亮来自 ListView 的 selector。
        // 两者只能选一个，否则 alpha 会叠加 —— 实测第二种菜单的遮罩正好比第一种深一倍。
        val list = findMenuList(content)
        val rows = if (list != null) menuRows(list) else emptyList()
        val rowBased = synchronized(rowBasedCache) {
            rowBasedCache.getOrPut(popup) { rows.any { it.background is RippleDrawable } }
        }

        // 2) 其余「会画的层」全部清掉 —— 注意不能只看 OPAQUE：
        //    实测真正在画那层灰的是半透明的，漏掉它就会出现「圆角变了、颜色没变」。
        //    RippleDrawable 是按压反馈，保留。
        stripPainters(content, surface)

        // 3) 内边距按 Miuix 规格重排（去掉框架/WebView 自带的上下留白）
        applyPadding(ctx, content, surface, list, rows, rowBased)

        // 4) 文字：Miuix Body1 16sp + Medium + onSurfaceContainer 颜色
        styleText(
            content,
            if (dark) MIUIX_ON_SURFACE_CONTAINER_DARK else MIUIX_ON_SURFACE_CONTAINER_LIGHT,
            if (dark) MIUIX_DISABLED_DARK else MIUIX_DISABLED_LIGHT,
            HookPrefs.appMenuTextSp(),
        )

        // 5) 宽高：按当前内容直接改 decor 的 LayoutParams。
        //
        //    **不要在这里做延迟重写**：窗口的入场动画约 200~300ms，任何落在动画途中/刚结束的
        //    尺寸重应用都会被看见（实测表现：动画卡一下、动画结束瞬间宽度突变）。
        //    改成 decor 的 lp 之后，first pass 就能写成功，不需要靠时间差去盖宿主的写入。
        runCatching { applyWindowSize(popup, ctx, content) }
        // 再晚一次兜底：宿主可能在后续布局里改内容尺寸，这里晚一点按当时的内容重算一次窗口高度。
        // 注意**只校正高度、不动宽度**（宽度已在 show() 前定好，事后改会看到突变）。
        runCatching {
            content.postDelayed({
                runCatching { applyWindowSize(popup, ctx, content) }
            }, 320L)
        }

        diag(
            content,
            "restyled surface=${content.javaClass.simpleName}/${surface.javaClass.simpleName} " +
                "dark=$dark corner=${HookPrefs.appMenuCornerDp()} textSp=${HookPrefs.appMenuTextSp()} " +
                "padH=${PAD_H_DP} padV=${PAD_V_DP}",
        )

        // 5b) 再记一份「稳定态」：框架可能在后续的 measure 里把 minimumHeight / 背景写回去
        //     （实测同一个菜单的行高会在 48dp 与 80dp 之间跳），稳定态才能反映真实结果。
        if (diagCount.get() <= MAX_DIAG) {
            runCatching {
                content.postDelayed({ runCatching { diag(content, "settled") } }, 400L)
            }
        }

        // 6) 出现动画**不动** —— 保留宿主/系统自己的那套。
        //    之前这里给根做过缩放 + 按锚点算 pivot，实测有副作用（与原生窗口动画、
        //    后续布局互相干扰），按要求整体撤掉。
    }


    /**
     * 选「肉眼看到的那层表面」。
     *
     * 规则：树里所有**会画底色的层**（background 非空且不是全透明）里，
     * 取**面积最大**的那个 —— 它就是铺得最开、最可能被看到的那层；
     * 一个都没有就用根（由我们把底色设上去）。
     */
    private fun pickSurface(content: View): View? {
        val root = rootOf(content)
        var best: View? = null
        var bestArea = -1L
        forEachView(root) { v ->
            if (paints(v)) {
                val area = v.width.toLong() * v.height.toLong()
                if (area > bestArea) {
                    bestArea = area
                    best = v
                }
            }
        }
        return best ?: root
    }

    /** 这一层是否在画底色（全透明的不算）。 */
    private fun paints(v: View): Boolean {
        val bg = v.background ?: return false
        return runCatching { bg.opacity != PixelFormat.TRANSPARENT }.getOrDefault(true)
    }

    /** 清掉除 [keep] 之外所有会画底色的层；按压反馈（Ripple）与分隔线保留。 */
    private fun stripPainters(root: View, keep: View) {
        forEachView(root) { v ->
            // 分隔线不放：它本身就是靠一个纯色背景画的，清掉就整条看不见了
            // （实测：`divider_view` / `group_divider` 一被清，用户看到的菜单就完全没有分隔线）。
            if (v !== keep && paints(v) && v.background !is RippleDrawable && !isDivider(v)) {
                runCatching { v.background = null }
            }
        }
    }

    /**
     * 清掉框架菜单项**自带**的横向内缩。
     *
     * 依据是 framework-res 的 `cascading_menu_item_layout_material`（已 aapt2 dump 确认）：
     * `title` 有 `layout_marginStart=18dp`、`icon` 有 `layout_marginStart=8dp`、
     * `content` 有 `layout_marginEnd=12dp` 且**高度写死 48dp**、`group_divider` 上下各 8dp。
     * 这些 margin 用 `setPadding` 清不掉，必须直接改 LayoutParams。
     *
     * 清完之后：左右留白完全由本模块的 padH 决定；图标与标题之间保留 Miuix 的
     * `IconEndPadding` = 12dp；`content` 改成按内容量，行高才由我们钉死的值说了算。
     */
    private fun normalizeFrameworkRow(ctx: Context, row: View) {
        val title = findByIdName(row, "title")
        val icon = findByIdName(row, "icon")
        val content = findByIdName(row, "content")
        val divider = findByIdName(row, "group_divider")
        // 框架的分隔线（1dp 的线，在菜单项内部）：登记它并清掉自带的上/下 8dp margin。
        // 高度不动（wrap 会让线变 0 高），之后宿主每次 setLayoutParams 都会被 hook 换成 margin 0。
        divider?.let { d ->
            synchronized(dividerParams) { dividerParams[d] = true }
            runCatching {
                d.layoutParams?.let { lp ->
                    (lp as? android.view.ViewGroup.MarginLayoutParams)?.let { m ->
                        m.leftMargin = 0
                        m.rightMargin = 0
                        m.topMargin = 0
                        m.bottomMargin = 0
                    }
                }
                d.requestLayout()
            }
        }
        if (title == null && icon == null && content == null) return

        val gap = dp(ctx, ICON_GAP_DP).toInt()
        val iconVisible = icon != null && icon.visibility == View.VISIBLE

        runCatching {
            // 内容层：**用权重填满分隔线以下的全部空间**，并自己在内部垂直居中。
            // 这样分隔线贴着行顶（不再被整块居中顶下去），内容仍然居中。
            content?.layoutParams?.let { lp ->
                if (lp is android.widget.LinearLayout.LayoutParams) {
                    lp.height = 0
                    lp.weight = 1f
                } else {
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                }
                (lp as? android.view.ViewGroup.MarginLayoutParams)?.rightMargin = 0
            }
            // 内容容器自己的对齐：横向 LinearLayout，CENTER_VERTICAL 让它内部图标+标题居中
            runCatching {
                if (content is android.widget.LinearLayout) {
                    content.gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }
            // 行改成顶对齐（否则整块内容会被竖直居中，分隔线看上去"偏低/上方有空"）
            runCatching {
                if (row is android.widget.LinearLayout) {
                    row.gravity = android.view.Gravity.TOP
                }
            }
            icon?.layoutParams?.let { lp ->
                (lp as? android.view.ViewGroup.MarginLayoutParams)?.leftMargin = 0
            }
            title?.layoutParams?.let { lp ->
                (lp as? android.view.ViewGroup.MarginLayoutParams)?.leftMargin =
                    if (iconVisible) gap else 0
            }
            // 分隔线：**不做任何修改**。宿主的 ListMenuItemView.onMeasure / adapter 绑定
            // 会把自己的布局重排回来，任何 margin/高度改动都会被还原（实测表现为「闪一下又回来」或偏移）。

            row.requestLayout()
        }
    }

    /** 按资源名后缀找子视图（`android:id/title` → "title"）。 */
    private fun findByIdName(root: View, name: String): View? {
        var found: View? = null
        forEachView(root) { v ->
            if (found == null) {
                val n = runCatching { v.resources.getResourceName(v.id) }.getOrNull().orEmpty()
                if (n.endsWith("/$name")) found = v
            }
        }
        return found
    }

    /** ListView 里真正的菜单项（跳过分隔线之类的装饰行）。 */
    private fun menuRows(list: ListView): List<View> {
        val items = ArrayList<View>()
        for (i in 0 until list.childCount) {
            val child = runCatching { list.getChildAt(i) }.getOrNull() ?: continue
            if (isMenuItem(child)) items.add(child)
        }
        return items
    }

    /** 这一层是不是分隔线（靠资源名识别，Chromium 与框架两边都带 "divider"）。 */
    private fun isDivider(v: View): Boolean = runCatching {
        v.resources.getResourceName(v.id)?.contains("divider", ignoreCase = true) == true
    }.getOrDefault(false)

    /**
     * 内边距按 Miuix 规格重排：
     * - ListView 自己**不许再有上下留白**（框架样式里那几 dp 就是「多余的间距」的来源）；
     * - 每行横向 [PrefKeys.APPMENU_PADDING_H_DEFAULT]（20dp，Miuix `InsideHorizontalPadding`）；
     * - 中间行纵向 [PrefKeys.APPMENU_PADDING_V_DEFAULT]（12dp，`MiddleVerticalPadding`）；
     * - 首行顶部 / 末行底部再多 8dp（凑成 20dp，`FirstLastVerticalPadding`）。
     */
    private fun applyPadding(
        ctx: Context,
        content: View,
        surface: View,
        list: ListView?,
        items: List<View>,
        rowBased: Boolean,
    ) {
        val h = dp(ctx, PAD_H_DP).toInt()
        val vDp = PAD_V_DP

        // 表面层自己也不许带内边距。
        // 实测（mark.via 的 Chromium 菜单）：真正在画底色的是那个 LinearLayout，
        // 它自带 pad=0,22,0,22 —— 22px = 8dp 的上下留白，就是「上下多余的间距」的来源。
        // 而它同时又是 popup.contentView，上一版只在 content !== surface 时才清，正好漏掉。
        // Miuix 的做法是容器不留白，留白由首/末行承担（FirstLastVerticalPadding）。
        runCatching { surface.setPadding(0, 0, 0, 0) }

        if (list != null) {
            runCatching { list.setPadding(0, 0, 0, 0) }
            runCatching { list.clipToPadding = false }
            // Miuix 的弹层没有滚动条；ListView 默认会画一条（padding 归零后更明显）。
            runCatching { list.isVerticalScrollBarEnabled = false }
            runCatching { list.isHorizontalScrollBarEnabled = false }

            // 菜单项由 restyle 统一收集（要在 stripPainters 之前判断高亮挂在哪一层），这里直接用。
            // Miuix 的弹层宽度是「内容宽，钳在 [200dp, 288dp]」（ListPopup.kt 的 ListPopupColumn）。
            // 框架菜单实测只有 501px = 182dp，比 Miuix 的最小宽还窄，所以把最小宽补上。
            runCatching { list.minimumWidth = dp(ctx, MIN_WIDTH_DP).toInt() }

            val midPx = dp(ctx, ICON_CELL_DP + 2f * vDp).toInt()
            // 首/末行**不再额外加高**：所有行等高。
            // （Miuix 的 Dropdown 给首末行多加 FirstLastVerticalPadding，但实测那样会让
            //   「内容总高」和窗口高度更难对齐，用户看到的就是底部留白 / 能滚一点。）
            val edgePx = midPx

            items.forEachIndexed { idx, child ->
                val target = if (idx == 0 || idx == items.lastIndex) edgePx else midPx
                // 直接钉死行高。
                //
                // 为什么不用「清 minimumHeight 再靠内边距撑」：实测**同一个菜单**的行高会在
                // 48dp 与 80dp 之间跳 —— 框架在后续的 measure 里会把最小高度写回去，谁先谁后决定结果。
                // 把 `lp.height` 定成确定值之后，框架就再也改不动了。
                //
                // 目标值取自 Miuix：中间行 = 图标格 26dp + 上下各 12dp = 50dp；
                // 首/末行 = 26dp + 20dp + 12dp = 58dp。
                runCatching {
                    child.layoutParams?.let { lp -> lp.height = target }
                    forEachView(child) { inner -> inner.minimumHeight = 0 }
                    child.minimumHeight = 0
                    child.setPadding(h, 0, h, 0)
                    child.requestLayout()
                }
                // 行高固定后内容要垂直居中，对应 Miuix 的 Row(verticalAlignment = CenterVertically)
                runCatching {
                    if (child is android.widget.LinearLayout) {
                        child.gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                }
                // 框架菜单项自带的内缩（来自 framework-res 的
                // `cascading_menu_item_layout_material`，已直接 dump 布局确认）：
                //   TextView(id=title)        layout_marginStart = 18dp
                //   ImageView(id=icon)        layout_marginStart = 8dp
                //   LinearLayout(id=content)  layout_marginEnd = 12dp、layout_height = 48dp（写死）
                //   ImageView(id=group_divider) marginTop/Bottom = 8dp
                // 这就是「把左右内边距设 0、第二种菜单仍有左右空白」的原因；
                // 写死的 48dp 也是它行高不听话的原因。全部清掉，横向留白只由 padH 决定。
                runCatching { normalizeFrameworkRow(ctx, child) }
                runCatching {
                    forEachView(child) { inner ->
                        if (inner is android.widget.Space) {
                            inner.layoutParams?.let { lp ->
                                lp.width = 0
                                if (lp is android.widget.LinearLayout.LayoutParams) lp.weight = 0f
                            }
                            inner.requestLayout()
                        }
                    }
                }
            }
            applyMiuixHighlight(list, items, isNight(ctx), rowBased)

        }
        // 内容层与表面层之间若还夹着别的容器，也不许自带内边距（会把整块内容顶偏）
        if (content !== surface) runCatching { content.setPadding(0, 0, 0, 0) }
    }

    /** 这一行是不是菜单项（而不是分隔线之类的装饰行）。 */
    private fun isMenuItem(v: View): Boolean {
        if (v.background is RippleDrawable) return true
        var hasText = false
        forEachView(v) { inner -> if (inner is TextView) hasText = true }
        return hasText
    }

    /**
     * 高亮改成 Miuix 那样：**平铺矩形叠加层，不是水波纹**。
     *
     * Miuix 的 `MiuixIndication`（`miuix-ui/utils/MiuixIndication.kt`）写得很清楚：
     * ```
     * HOVER_ALPHA_DELTA = 0.06f      // 悬停
     * PRESS_ALPHA_DELTA = 0.10f      // 按下
     * FOCUS_ALPHA_DELTA = 0.08f      // 聚焦
     *
     * override fun ContentDrawScope.draw() {
     *     drawContent()
     *     drawRect(color = color, alpha = alpha, size = size)   // 满尺寸矩形，画在内容之上
     * }
     * ```
     * 也就是：**没有圆、没有扩散、没有 mask 内缩**，就是一层 alpha 0.06 / 0.10 的 `onBackground`
     * 矩形铺满整行。颜色取 `colorScheme.onBackground`（浅色是黑、深色是白）。
     *
     * 所以这里：
     * - 行的 `RippleDrawable`（那个「大水波纹」）换成**扁平状态列表**：按下 10%、悬停 6%、聚焦 8%；
     * - `ListView` 的 `selector` 也换成同样的扁平 drawable —— 框架菜单的高亮来自 selector，
     *   而它自带的 `listChoiceBackgroundIndicator` **本身带内缩**（就是遮罩外面漏出来的那圈），
     *   换成 `ColorDrawable` 组成的 `StateListDrawable` 后自然满行平铺。
     */
    private fun applyMiuixHighlight(
        list: ListView,
        items: List<View>,
        dark: Boolean,
        rowBased: Boolean,
    ) {
        val base = if (dark) 0xFFFFFF else 0x000000

        if (rowBased) {
            // 行自带 ripple 的那套（Chromium）：高亮挂在每一行自己的 foreground 上。
            // 挂 foreground 是因为 Miuix 就是 `drawContent()` 之后才 `drawRect`（画在内容之上）。
            //
            // 逐行独立的好处：切换菜单项时每行的状态各自变化，**每一项都会有过渡动画**。
            items.forEach { row ->
                runCatching {
                    row.background = null
                    row.foreground = MiuixHighlightDrawable(base)
                }
            }
            // 这套不能再用 selector：两处都画的话 alpha 会叠加（遮罩变深一倍）
            runCatching { list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) }
        } else {
            // 框架那套（`MenuDropDownListView`）：高亮**只走 ListView 的 selector**
            // （实测把 selector 设透明后 hover 完全消失，说明它不经过行的状态/背景）。
            // 行本身保持无背景，避免和 selector 叠加。
            synchronized(menuListColors) { menuListColors[list] = base }
            runCatching { list.selector = MiuixHighlightDrawable(base) }
            // 分隔线：框架/Chromium 会在后续布局里把它重新显示回来，所以挂一个**持续**的
            // 布局监听，每次布局后再压一次（幂等，不会造成布局循环）。
        }
    }


    /**
     * 在 `PopupWindow.show()` **之前**把宽度定好。
     *
     * 为什么必须前置：`show()` 之后写尺寸必然落在入场动画途中/之后，看起来就是「宽度突变」
     * （实测确认）。而 `show()` 之前设 `popup.width` 会**参与 lp 的计算**，窗口从一开始就是我算的宽度，
     * 全程没有变化。
     *
     * 宽度来源：Miuix 的 `ListPopupColumn` 就是「最宽一项的固有宽度，钳在 [200dp, 288dp]」。
     * 框架菜单的 adapter 项就是 `android.view.MenuItem`，可以直接读到标题来量；
     * Chromium 那套取不到标题就沿用宿主自己的宽度（它本来就是 200dp = Miuix 下限）。
     */
    /**
     * 只校正**窗口高度**：取 ListView 各子项实测高度之和。
     *
     * 宽度**不在这里动** —— 它已在 `show()` 之前由 [presizeWidth] 定好；
     * 事后改宽度会落在入场动画途中/之后，表现为「宽度突变」。
     */
    private fun applyWindowSize(popup: PopupWindow, ctx: Context, content: View) {
        val list = findMenuList(content) ?: return
        var h = 0
        for (i in 0 until list.childCount) {
            h += runCatching { list.getChildAt(i).height }.getOrDefault(0)
        }
        if (h <= 0) return
        runCatching {
            val decor = rootOf(content)
            val lp = decor.layoutParams as? android.view.WindowManager.LayoutParams
                ?: return@runCatching
            if (lp.height == h) return@runCatching
            lp.height = h
            val wm = decor.context.getSystemService(android.content.Context.WINDOW_SERVICE)
                as? android.view.WindowManager ?: return@runCatching
            wm.updateViewLayout(decor, lp)
            popup.height = h
        }
    }

    private fun presizeWidth(popup: PopupWindow?) {
        val p = popup ?: return
        if (!HookPrefs.appMenuEnabled()) return
        val content = runCatching { p.contentView }.getOrNull() ?: return
        val list = findMenuList(content) ?: return
        val count = rowCount(list)
        if (count < MIN_MENU_ROWS) return
        val ctx = runCatching { content.context }.getOrNull() ?: return
        val adapter = runCatching { list.adapter }.getOrNull() ?: return

        var maxText = 0f
        for (i in 0 until count) {
            val title = runCatching {
                (adapter.getItem(i) as? android.view.MenuItem)?.title?.toString()
            }.getOrNull() ?: return
            val paint = android.graphics.Paint().apply {
                textSize = HookPrefs.appMenuTextSp() * ctx.resources.displayMetrics.scaledDensity
                typeface = runCatching { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
                    .getOrDefault(Typeface.SANS_SERIF)
            }
            val w = runCatching { paint.measureText(title) }.getOrDefault(0f)
            if (w > maxText) maxText = w
        }
        if (maxText <= 0f) return
        val chrome = dp(ctx, PAD_H_DP * 2f + ICON_CELL_DP + ICON_GAP_DP)
        val width = (chrome + maxText).toInt().coerceIn(
            dp(ctx, MIN_WIDTH_DP).toInt(),
            dp(ctx, MAX_WIDTH_DP).toInt(),
        )
        runCatching { p.width = width }
    }

    /**
     * 按内容算弹层宽度，钳在 Miuix 的 `[200dp, 288dp]`。
     *
     * Miuix 的 `ListPopupColumn` 就是这么量的：取最宽一项的固有宽度再 `coerceIn`。
     * 不做的话窗口会一直沿用 `show()` 那一刻的宽度（框架菜单实测 501px = 182dp，
     * 比 Miuix 的下限还窄），标题就被挤成省略号或换行。
     *
     * 返回 null 表示量不出来（没有菜单项），调用方就不要动宽度。
     */
    private fun menuWidthPx(ctx: Context, content: View): Int? {
        val list = findMenuList(content) ?: return null
        var textPx = 0f
        var rows = 0
        for (i in 0 until list.childCount) {
            val row = runCatching { list.getChildAt(i) }.getOrNull() ?: continue
            if (!isMenuItem(row)) continue
            rows++
            forEachView(row) { v ->
                if (v is TextView) {
                    val text = v.text?.toString().orEmpty()
                    if (text.isNotEmpty()) {
                        val w = runCatching { v.paint.measureText(text) }.getOrDefault(0f)
                        if (w > textPx) textPx = w
                    }
                }
            }
        }
        if (rows == 0) return null
        // 左右内边距 + 图标格（Miuix: 26dp 图标 + 12dp 间距）+ 最宽文本
        val chrome = dp(ctx, PAD_H_DP * 2f + ICON_CELL_DP + ICON_GAP_DP)
        return (chrome + textPx).toInt().coerceIn(
            dp(ctx, MIN_WIDTH_DP).toInt(),
            dp(ctx, MAX_WIDTH_DP).toInt(),
        )
    }

    /** 计算缩放 pivot，对应 Miuix 的 `localTransformOrigin`：
     * 弹窗在锚点下方 → 从上边缘展开（pivotY = 0）；在上方 → 从下边缘（1）。
     * 弹窗在锚点右侧 → 从左边缘展开（pivotX = 0）；左侧 → 从右边缘（1）。
     */
    private fun pivotFor(root: View, anchor: View?): FloatArray {
        if (anchor == null) return floatArrayOf(0.5f, 0.5f)
        val a = IntArray(2)
        val r = IntArray(2)
        runCatching { anchor.getLocationOnScreen(a) }
        runCatching { root.getLocationOnScreen(r) }
        val aw = anchor.width
        val ah = anchor.height
        val rw = root.width
        val aCenterX = a[0] + aw / 2
        val showBelow = r[1] >= a[1] + ah
        val pivotY = if (showBelow) 0f else 1f
        val pivotX = when {
            r[0] >= aCenterX -> 0f
            rw > 0 && r[0] + rw <= aCenterX -> 1f
            r[0] + rw / 2 >= aCenterX -> 0f
            else -> 1f
        }
        return floatArrayOf(pivotX, pivotY)
    }

    // ------------------------------------------------------------------ 树遍历

    private fun findMenuList(root: View): ListView? {
        var found: ListView? = null
        forEachView(root) { v ->
            if (found == null && v is ListView && rowCount(v) >= MIN_MENU_ROWS) found = v
        }
        return found
    }

    private fun rowCount(list: ListView): Int =
        runCatching { list.adapter?.count ?: 0 }.getOrDefault(0)

    /** 广度优先遍历整棵树（深度优先在很深的菜单里容易先撞到叶子）。 */
    private inline fun forEachView(root: View, action: (View) -> Unit) {
        val queue = ArrayDeque<View>()
        queue.addLast(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 400) {
            val v = queue.removeFirst()
            runCatching { action(v) }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { queue.addLast(v.getChildAt(i)) }
                }
            }
        }
    }

    /** 弹窗视图树的根（PopupWindow 的 decor）。 */
    private fun rootOf(content: View): View {
        var v: View = content
        var parent = content.parent
        var guard = 0
        while (parent is View && guard++ < 32) {
            v = parent
            parent = parent.parent
        }
        return v
    }

    /** 递归把文字换成 Miuix 观感。图标是 ImageView，不受影响。 */
    private fun styleText(root: View, color: Int, disabledColor: Int, sizeSp: Float) {
        // Miuix 菜单项标题用的是 `textStyles.body1` + `FontWeight.Medium`
        // （miuix-ui/basic/Dropdown.kt 的 DropdownImpl），Body1 = 16sp。
        // 安卓这边 Medium 对应 `sans-serif-medium` 字族。
        val medium = runCatching { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            .getOrDefault(Typeface.SANS_SERIF)
        forEachView(root) { v ->
            if (v is TextView) {
                runCatching {
                    // 不可点击（禁用）的项：文字用更浅的 disabled 色区分出来
                    val enabled = v.isEnabled &&
                        ((v.parent as? View)?.isEnabled ?: true)
                    v.setTextColor(if (enabled) color else disabledColor)
                    v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                    v.typeface = medium
                }
            }
        }
    }

    // ------------------------------------------------------------------ 动画与工具

    private fun takeAnimationSlot(popup: PopupWindow): Boolean {
        val now = SystemClock.uptimeMillis()
        val last = synchronized(lastAnimated) { lastAnimated[popup] } ?: 0L
        if (now - last < ANIM_THROTTLE_MS) return false
        synchronized(lastAnimated) { lastAnimated[popup] = now }
        return true
    }

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

    // ------------------------------------------------------------------ 诊断

    /**
     * 现场写到**目标应用自己的 cache 目录**。
     *
     * 为什么不用 logcat / RemotePreferences：本机 logcat 已停（只有几个月前的旧记录），
     * 而 hook 进程侧往框架 RemotePreferences 写实测不落盘。cache 目录是应用自己的，
     * 一定能写成功，读的时候用 root 即可。
     */
    private fun diag(content: View, note: String) {
        val n = diagCount.incrementAndGet()
        if (n > MAX_DIAG) return
        runCatching {
            val ctx = content.context
            val f = File(ctx.cacheDir, LOG_NAME)
            val sb = StringBuilder()
            sb.append("===== hit #").append(n).append(" note=").append(note).append('\n')
            sb.append("pkg=").append(ctx.packageName)
                .append(" enabled=").append(HookPrefs.appMenuEnabled()).append('\n')
            // 窗口 lp 的实际宽高 + ListView 的 dividerHeight：定位「多余的 22px / 左右留白」用
            runCatching {
                val decor = rootOf(content)
                val lp = decor.layoutParams
                val l = findMenuList(content)
                sb.append("window=").append(lp?.width).append('x').append(lp?.height)
                    .append(" decor=").append(decor.width).append('x').append(decor.height)
                    .append(" dividerH=").append(l?.dividerHeight)
                    // selector 的**实际类名**：如果 hover 时它被宿主换成了自己的 drawable，
                    // 那就说明「切换项没有过渡」不是我的动画没跑，而是我的 drawable 被换掉了。
                    .append(" selector=").append(
                        runCatching { l?.selector?.javaClass?.simpleName }.getOrNull() ?: "none",
                    )
                    .append(" rows=").append(l?.childCount)
                    .append('\n')
            }
            sb.append(dumpTree(content)).append('\n')
            f.appendText(sb.toString())
        }
    }

    /** 把整棵树摊平成文本：类名 / id / 底色 / 不透明度 / 内边距 / 尺寸。 */
    private fun dumpTree(content: View): String {
        val sb = StringBuilder("tree(root=").append(rootOf(content).javaClass.name).append("):\n")
        val root = rootOf(content)
        forEachView(root) { v ->
            val bg = v.background
            val res = runCatching { v.resources.getResourceName(v.id) }.getOrNull() ?: "-"
            sb.append("  ").append(short(v.javaClass.name))
                .append(" id=").append(res)
                .append(" bg=").append(bg?.let { short(it.javaClass.name) } ?: "none")
                .append("/op=").append(bg?.let { runCatching { it.opacity }.getOrDefault(-1) } ?: -1)
                .append(" fg=").append(
                    runCatching { v.foreground?.let { short(it.javaClass.name) } }.getOrNull()
                        ?: "none",
                )
                .append(" pad=").append(v.paddingLeft).append(',').append(v.paddingTop)
                .append(',').append(v.paddingRight).append(',').append(v.paddingBottom)
                // 外边距也要记：用户把 padH 设 0 后仍有左右空白，说明来源不在 padding 而在 margin
                .append(" mg=").append(
                    (v.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
                        ?.let { "${it.leftMargin},${it.topMargin},${it.rightMargin},${it.bottomMargin}" }
                        ?: "-",
                )
                .append(" wh=").append(v.width).append('x').append(v.height)
                // 状态位：判断框架的高亮到底靠哪个状态（hover / selected / pressed）
                .append(" st=").append(if (v.isHovered) "H" else "-")
                .append(if (v.isSelected) "S" else "-")
                .append(if (v.isPressed) "P" else "-")
                .append(if (v.isFocused) "F" else "-")
                .append('\n')
        }
        return sb.toString()
    }

    private fun short(name: String): String = name.substringAfterLast('.')
}

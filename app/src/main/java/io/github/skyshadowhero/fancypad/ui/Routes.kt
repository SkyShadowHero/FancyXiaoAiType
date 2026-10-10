package io.github.skyshadowhero.fancypad.ui

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Tune
import io.github.skyshadowhero.fancypad.R
import top.yukonga.miuix.kmp.nav.core.NavKey
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * 路由表。全部走 miuix-nav（`NavDisplay` + `NavKey` + `navBackStackOf`）。
 *
 * 层级只有三层，一级页面按功能名列出，点进去才看到该功能域里的设置：
 * ```
 * 功能（一级）
 * ├── 小爱输入法 ──┬── 虚拟键盘
 * │                ├── 悬浮键盘
 * │                ├── 超级材质
 * │                └── 随手写（触控笔手写）
 * ├── 光标主题 ──┬── 主题预设
 * │              ├── 大小
 * │              ├── 导入
 * │              └── 颜色设置（二级，AOSP / GoogleDot 可改色时进入）
 * ├── 平行窗口动画 ── 平行窗口
 * ├── 小窗控制器 ──┬── 功能
 * │                └── 按钮
 * ├── AOSP长按菜单 ── AOSP长按菜单
 * ├── 右键菜单 ── 右键菜单
 * └── 关于
 * ```
 * 各自对应的 LSPosed 作用域见 [AppScope.pkg]（在「关于」页里列出）。
 */
data object RouteScopes : NavKey

data object RouteImeVirtual : NavKey
data object RouteImeFloating : NavKey
data object RouteImeMaterial : NavKey

/** 随手写（触控笔手写）：AOSP Android 14+ 的 stylus handwriting，不是「手写键盘」。 */
data object RouteImeStylus : NavKey

data object RouteCursorPreset : NavKey
data object RouteCursorSize : NavKey
data object RouteCursorImport : NavKey
data object RouteCursorColors : NavKey

data object RouteParallel : NavKey

/** 小窗控制器 · 功能：总开关 + 各功能开关。 */
data object RouteCaptionFeatures : NavKey

/** 小窗控制器 · 按钮：每个按钮的显隐与顺序。 */
data object RouteCaptionButtons : NavKey

data object RouteTextMenu : NavKey

data object RouteAppMenu : NavKey

data object RouteAbout : NavKey

/**
 * 一级页面里的一项 = 一个功能域。
 *
 * 一级页面只列功能名（[label] + 图标），**不带任何描述** —— 描述都留在各自的页面里。
 * [pkg] 是它对应的 LSPosed 作用域，只在「关于」页里列出来。
 * [pages] 是功能域里的页面：多页时用左侧栏 / 底部菜单切换，单页时不显示多页导航。
 *
 * 图标两种来源：[iconRes] 是模块自带的矢量（从系统 / 输入法 / 设计稿提取，生成脚本在
 * `tools/caption` 与 `tools/scope-icons`），非 0 时优先；否则用 [icon] 那个 Miuix 图标。
 */
enum class AppScope(
    val label: String,
    val pkg: String,
    val pages: List<NavKey>,
    /** 一级列表用的矢量 drawable；0 = 用 [icon]。 */
    val iconRes: Int = 0,
    val icon: ImageVector = MiuixIcons.Info,
) {
    Ime(
        label = "小爱输入法",
        pkg = "com.xiaomi.type",
        pages = listOf(RouteImeVirtual, RouteImeFloating, RouteImeMaterial, RouteImeStylus),
        iconRes = R.drawable.ic_scope_ime,
    ),
    Cursor(
        label = "光标主题",
        pkg = "system",
        pages = listOf(RouteCursorPreset, RouteCursorSize, RouteCursorImport),
        iconRes = R.drawable.ic_scope_cursor,
    ),
    Parallel(
        label = "平行窗口动画",
        pkg = "com.android.systemui",
        pages = listOf(RouteParallel),
        iconRes = R.drawable.ic_scope_embedding,
    ),
    Caption(
        label = "小窗控制器",
        pkg = "com.android.systemui",
        pages = listOf(RouteCaptionFeatures, RouteCaptionButtons),
        iconRes = R.drawable.ic_scope_caption,
    ),
    TextMenu(
        label = "AOSP长按菜单",
        pkg = "com.android.systemui",
        pages = listOf(RouteTextMenu),
        icon = MiuixIcons.SelectAll,
    ),
    AppMenu(
        label = "右键菜单",
        pkg = "mark.via",
        pages = listOf(RouteAppMenu),
        icon = MiuixIcons.ListView,
    ),
}

/** 路由 → 顶栏标题 */
fun NavKey.title(): String = when (this) {
    RouteScopes -> "功能"
    RouteImeVirtual -> "虚拟键盘"
    RouteImeFloating -> "悬浮键盘"
    RouteImeMaterial -> "超级材质"
    RouteImeStylus -> "随手写"
    RouteCursorPreset -> "主题预设"
    RouteCursorSize -> "大小"
    RouteCursorImport -> "导入"
    RouteCursorColors -> "颜色设置"
    RouteParallel -> "平行窗口"
    RouteCaptionFeatures -> "功能"
    RouteCaptionButtons -> "按钮"
    RouteTextMenu -> "AOSP长按菜单"
    RouteAppMenu -> "右键菜单"
    RouteAbout -> "关于"
    else -> ""
}

/** 路由 → 导航项图标（只有多页作用域会用到） */
fun NavKey.icon(): ImageVector = when (this) {
    RouteImeVirtual -> MiuixIcons.Tune
    RouteImeFloating -> MiuixIcons.Layers
    RouteImeMaterial -> MiuixIcons.Background
    RouteImeStylus -> MiuixIcons.Edit
    RouteCursorPreset -> MiuixIcons.GridView
    RouteCursorSize -> MiuixIcons.Tune
    RouteCursorImport -> MiuixIcons.Import
    RouteParallel -> MiuixIcons.HorizontalSplit
    RouteCaptionFeatures -> MiuixIcons.Tune
    RouteCaptionButtons -> MiuixIcons.GridView
    RouteTextMenu -> MiuixIcons.SelectAll
    RouteAppMenu -> MiuixIcons.ListView
    RouteAbout -> MiuixIcons.Info
    else -> MiuixIcons.Info
}

/** 路由所属的作用域；一级页面与「关于」返回 null。 */
fun NavKey.scope(): AppScope? = AppScope.entries.firstOrNull { this in it.pages }

/**
 * 轻量压栈转场：只有进入的页面从右侧滑入（离开时滑回右侧），被覆盖的页面**原地不动**。
 *
 * 不用 [NavTransitions.MiuixDefault] 的原因：它会给被覆盖页加 25% 宽度的视差位移 + alpha 衰减，
 * 再叠上默认的 0.5 暗层（`NavDisplayEffects.dimAmount`），也就是每帧要重绘**两整页**再加一层全屏
 * 暗色。这台平板是 3200×2136，实测转场明显发卡；而且被覆盖页被推开后还能看见，观感也乱。
 *
 * 这里只保留必需的位移，配合 [NavDisplayEffects] 关掉暗层与圆角裁切，每帧只有新页在动。
 */
val PadPush: NavTransition = navGraphicsTransition(opaqueDepth = 1f) { scope ->
    val width = scope.layoutSize.width.toFloat()
    val d = scope.relativeDepth
    val rtl = scope.layoutDirection == LayoutDirection.Rtl
    if (d <= 0f) {
        // 进入 / 离开栈顶：从尾边整屏滑入（RTL 镜像）
        translationX = (if (rtl) -1f else 1f) * (-d).coerceIn(0f, 1f) * width
    }
    // d > 0：被覆盖页保持原位 —— 不位移、不淡出、不重绘
}

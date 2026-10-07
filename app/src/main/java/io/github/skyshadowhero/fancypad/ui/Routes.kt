package io.github.skyshadowhero.fancypad.ui

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Tune
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
 * ├── 键盘外观 ──┬── 虚拟键盘
 * │              ├── 悬浮键盘
 * │              └── 超级材质
 * ├── 光标主题 ──┬── 主题预设
 * │              ├── 大小
 * │              ├── 导入
 * │              └── 颜色设置（二级，AOSP / GoogleDot 可改色时进入）
 * ├── 平行窗口动画 ── 平行窗口
 * └── 关于
 * ```
 * 各自对应的 LSPosed 作用域见 [AppScope.pkg]（在「关于」页里列出）。
 */
data object RouteScopes : NavKey

data object RouteImeVirtual : NavKey
data object RouteImeFloating : NavKey
data object RouteImeMaterial : NavKey

data object RouteCursorPreset : NavKey
data object RouteCursorSize : NavKey
data object RouteCursorImport : NavKey
data object RouteCursorColors : NavKey

data object RouteParallel : NavKey

data object RouteTextMenu : NavKey

data object RouteAbout : NavKey

/**
 * 一级页面里的一项 = 一个功能域。
 *
 * 一级页面只列功能名（[label] + 图标），**不带任何描述** —— 描述都留在各自的页面里。
 * [pkg] 是它对应的 LSPosed 作用域，只在「关于」页里列出来。
 * [pages] 是功能域里的页面：多页时用左侧栏 / 底部菜单切换，单页时不显示多页导航。
 */
enum class AppScope(
    val label: String,
    val pkg: String,
    val pages: List<NavKey>,
    val icon: ImageVector,
) {
    Ime(
        label = "键盘外观",
        pkg = "com.xiaomi.type",
        pages = listOf(RouteImeVirtual, RouteImeFloating, RouteImeMaterial),
        icon = MiuixIcons.Tune,
    ),
    Cursor(
        label = "光标主题",
        pkg = "system",
        pages = listOf(RouteCursorPreset, RouteCursorSize, RouteCursorImport),
        icon = MiuixIcons.GridView,
    ),
    Parallel(
        label = "平行窗口动画",
        pkg = "com.android.systemui",
        pages = listOf(RouteParallel),
        icon = MiuixIcons.HorizontalSplit,
    ),
    TextMenu(
        label = "长按菜单",
        pkg = "com.android.systemui",
        pages = listOf(RouteTextMenu),
        icon = MiuixIcons.SelectAll,
    ),
}

/** 路由 → 顶栏标题 */
fun NavKey.title(): String = when (this) {
    RouteScopes -> "功能"
    RouteImeVirtual -> "虚拟键盘"
    RouteImeFloating -> "悬浮键盘"
    RouteImeMaterial -> "超级材质"
    RouteCursorPreset -> "主题预设"
    RouteCursorSize -> "大小"
    RouteCursorImport -> "导入"
    RouteCursorColors -> "颜色设置"
    RouteParallel -> "平行窗口"
    RouteTextMenu -> "长按菜单"
    RouteAbout -> "关于"
    else -> ""
}

/** 路由 → 导航项图标（只有多页作用域会用到） */
fun NavKey.icon(): ImageVector = when (this) {
    RouteImeVirtual -> MiuixIcons.Tune
    RouteImeFloating -> MiuixIcons.Layers
    RouteImeMaterial -> MiuixIcons.Background
    RouteCursorPreset -> MiuixIcons.GridView
    RouteCursorSize -> MiuixIcons.Tune
    RouteCursorImport -> MiuixIcons.Import
    RouteParallel -> MiuixIcons.HorizontalSplit
    RouteTextMenu -> MiuixIcons.SelectAll
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

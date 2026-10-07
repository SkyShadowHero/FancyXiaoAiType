package io.github.skyshadowhero.fancypad.ui

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 路由表。全部走 miuix-nav（`NavDisplay` + `NavKey` + `navBackStackOf`）。
 *
 * 层级只有三层，和模块的三个作用域一一对应：
 * ```
 * 作用域（一级）
 * ├── 输入法外观 · com.xiaomi.type ──┬── 虚拟键盘
 * │                                 ├── 悬浮键盘
 * │                                 └── 超级材质
 * ├── 系统光标 · system ─────────────┬── 光标
 * │                                 └── 颜色设置（二级，AOSP / GoogleDot 可改色时进入）
 * ├── 平行窗口动画 · com.android.systemui ── 平行窗口
 * └── 关于
 * ```
 */
data object RouteScopes : NavKey

data object RouteImeVirtual : NavKey
data object RouteImeFloating : NavKey
data object RouteImeMaterial : NavKey

data object RouteCursor : NavKey
data object RouteCursorColors : NavKey

data object RouteParallel : NavKey

data object RouteAbout : NavKey

/**
 * 一级页面「作用域」里的一项。
 *
 * [pages] 是该作用域自己的功能页：作用域里有多页时，进入后用左侧栏 / 底部菜单在它们之间切换；
 * 只有一页时（光标、平行窗口）不显示多页导航。
 */
enum class AppScope(
    val label: String,
    val pkg: String,
    val summary: String,
    val pages: List<NavKey>,
    val icon: ImageVector,
) {
    Ime(
        label = "输入法外观",
        pkg = "com.xiaomi.type",
        summary = "分离键盘 · 按键圆角与间距 · 候选词 · 悬浮键盘 · 超级材质",
        pages = listOf(RouteImeVirtual, RouteImeFloating, RouteImeMaterial),
        icon = MiuixIcons.Tune,
    ),
    Cursor(
        label = "系统光标",
        pkg = "system",
        summary = "接管光标渲染：预设 · 大小 · 颜色 · 导入",
        pages = listOf(RouteCursor),
        icon = MiuixIcons.GridView,
    ),
    Parallel(
        label = "平行窗口动画",
        pkg = "com.android.systemui",
        summary = "把平行窗口转场恢复成 AOSP 原生实现",
        pages = listOf(RouteParallel),
        icon = MiuixIcons.HorizontalSplit,
    ),
}

/** 路由 → 顶栏标题 */
fun NavKey.title(): String = when (this) {
    RouteScopes -> "作用域"
    RouteImeVirtual -> "虚拟键盘"
    RouteImeFloating -> "悬浮键盘"
    RouteImeMaterial -> "超级材质"
    RouteCursor -> "光标"
    RouteCursorColors -> "颜色设置"
    RouteParallel -> "平行窗口"
    RouteAbout -> "关于"
    else -> ""
}

/** 路由 → 导航项图标（只有多页作用域会用到） */
fun NavKey.icon(): ImageVector = when (this) {
    RouteImeVirtual -> MiuixIcons.Tune
    RouteImeFloating -> MiuixIcons.Layers
    RouteImeMaterial -> MiuixIcons.Background
    RouteCursor -> MiuixIcons.GridView
    RouteParallel -> MiuixIcons.HorizontalSplit
    RouteAbout -> MiuixIcons.Info
    else -> MiuixIcons.Info
}

/** 路由所属的作用域；一级页面与「关于」返回 null。 */
fun NavKey.scope(): AppScope? = AppScope.entries.firstOrNull { this in it.pages }

package io.github.skyshadowhero.fancypad.ui

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import io.github.skyshadowhero.fancypad.R
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 路由表。
 *
 * 顶层只有**一个**路由 [RouteHome]：它里面是三个标签页（首页 / 功能 / 信息），
 * 由底部导航 + `HorizontalPager` 承载（与 miuix example 的 `Route.Main` 同一个思路）。
 * 功能域页面、主题设置这些是**压栈**的二级页，返回键逐层退回。
 *
 * ```
 * RouteHome（底栏：首页 / 功能 / 信息，可左右滑）
 * ├── 首页 ── 卡片：模块状态 + 各功能域状态
 * ├── 功能 ──┬── 小爱输入法 ──┬── 虚拟键盘
 * │          │                ├── 悬浮键盘
 * │          │                ├── 超级材质
 * │          │                └── 随手写
 * │          ├── 光标主题 ──┬── 主题预设
 * │          │              ├── 大小
 * │          │              └── 导入
 * │          │                  └── 颜色设置（三级）
 * │          ├── 平行窗口动画
 * │          ├── 小窗控制器 ──┬── 功能
 * │          │                └── 按钮
 * │          ├── AOSP长按菜单
 * │          └── 右键菜单
 * └── 信息 ── 主题设置 / 版本 / 作用域 / 仓库
 * ```
 *
 * 二级页各自对应的 LSPosed 作用域见 [AppScope.pkg]；作用域没勾的域在「功能」页里
 * 是置灰不可点的（见 [AppScope.requiredScope]）。
 */
data object RouteHome : NavKey

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

/** 主题设置：界面外观（Miuix / Glasmio）+ 明暗模式。从「信息」页进入。 */
data object RouteTheme : NavKey

/**
 * 一个功能域 = 一个路由：域内兄弟页由 [DomainHost] 用 HorizontalPager 承载。
 *
 * 兄弟页不再是独立路由 —— 否则切 tab 会替换栈顶 key，整页重建：
 * TabRow 的动画状态丢失、新旧两个 entry 同时转场还会闪一下。
 */
data class RouteDomain(val scope: AppScope) : NavKey

/**
 * 一级页面的三个标签（底栏项）。
 *
 * 顺序就是底栏顺序，也是 `HorizontalPager` 的页序；返回键在非首页标签上先回到首页。
 */
enum class RootTab(val label: String, val icon: ImageVector) {
    Home("首页", MiuixIcons.Home),
    Features("功能", MiuixIcons.GridView),
    Info("信息", MiuixIcons.Info),
}

/**
 * 一级页面里的一项 = 一个功能域。
 *
 * [pkg] 是它对应的 LSPosed 作用域（仅用于「关于 / 信息」页展示）；
 * [requiredScope] 是**必须全部在作用域里**才能使用这个域的包名集合 ——
 * 缺任意一个，「功能」页里这一项就置灰、点不动（首页卡片同样标为未启用）。
 *
 * 右键菜单是特例：它没有固定目标包（用户自己往作用域里加应用），
 * 所以 [requiredScope] 为空 = 永远可用，但首页卡片会提示「需自行添加目标应用」。
 *
 * [iconRes] 是模块自带的矢量（从系统 / 输入法 / 设计稿提取，生成脚本在
 * `tools/caption` 与 `tools/scope-icons`），非 0 时优先；否则用 [icon] 那个 Miuix 图标。
 */
enum class AppScope(
    val label: String,
    val pkg: String,
    val pages: List<NavKey>,
    val requiredScope: List<String>,
    /** 一级列表用的矢量 drawable；0 = 用 [icon]。 */
    val iconRes: Int = 0,
    val icon: ImageVector = MiuixIcons.Info,
) {
    Ime(
        label = "小爱输入法",
        pkg = "com.xiaomi.type",
        pages = listOf(RouteImeVirtual, RouteImeFloating, RouteImeMaterial, RouteImeStylus),
        requiredScope = listOf("com.xiaomi.type"),
        iconRes = R.drawable.ic_scope_ime,
    ),
    Cursor(
        label = "光标主题",
        pkg = "system",
        pages = listOf(RouteCursorPreset, RouteCursorSize, RouteCursorImport),
        requiredScope = listOf("system"),
        iconRes = R.drawable.ic_scope_cursor,
    ),
    Parallel(
        label = "平行窗口动画",
        pkg = "com.android.systemui",
        pages = listOf(RouteParallel),
        requiredScope = listOf("com.android.systemui"),
        iconRes = R.drawable.ic_scope_embedding,
    ),
    Caption(
        label = "小窗控制器",
        pkg = "com.android.systemui",
        pages = listOf(RouteCaptionFeatures, RouteCaptionButtons),
        requiredScope = listOf("com.android.systemui"),
        iconRes = R.drawable.ic_scope_caption,
    ),
    TextMenu(
        label = "AOSP长按菜单",
        pkg = "com.android.systemui",
        pages = listOf(RouteTextMenu),
        requiredScope = listOf("com.android.systemui"),
        icon = MiuixIcons.SelectAll,
    ),
    AppMenu(
        label = "右键菜单",
        pkg = "mark.via",
        pages = listOf(RouteAppMenu),
        // 目标应用由用户自己加进作用域，没有固定包名 → 永远可选
        requiredScope = emptyList(),
        icon = MiuixIcons.ListView,
    ),
    ;

    /** 缺哪些作用域包（空 = 齐了）。[present] 来自 LSPosed 的当前作用域。 */
    fun missingScope(present: Set<String>): List<String> = requiredScope.filterNot { it in present }

    /** 这个域现在能不能用（作用域齐了）。 */
    fun inScope(present: Set<String>): Boolean = missingScope(present).isEmpty()

    /** 作用域说明（首页/功能页副标题用）。 */
    fun scopeSummary(present: Set<String>): String {
        if (requiredScope.isEmpty()) return "需自行添加目标应用"
        val missing = missingScope(present)
        return if (missing.isEmpty()) {
            "作用域：" + requiredScope.joinToString("、")
        } else {
            "未加入作用域：" + missing.joinToString("、")
        }
    }
}

/** 路由 → 顶栏标题 */
fun NavKey.title(): String = when (this) {
    RouteHome -> "首页"
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
    RouteTheme -> "主题设置"
    is RouteDomain -> scope.label
    else -> ""
}

/** 路由 → 导航项图标（只有多页作用域会用到） */
fun NavKey.icon(): ImageVector = when (this) {
    RouteImeVirtual -> MiuixIcons.Tune
    RouteImeFloating -> MiuixIcons.Layers
    RouteImeMaterial -> MiuixIcons.Settings
    RouteImeStylus -> MiuixIcons.Edit
    RouteCursorPreset -> MiuixIcons.GridView
    RouteCursorSize -> MiuixIcons.Tune
    RouteCursorImport -> MiuixIcons.Import
    RouteParallel -> MiuixIcons.Layers
    RouteCaptionFeatures -> MiuixIcons.Tune
    RouteCaptionButtons -> MiuixIcons.GridView
    RouteTextMenu -> MiuixIcons.SelectAll
    RouteAppMenu -> MiuixIcons.ListView
    RouteTheme -> MiuixIcons.Settings
    else -> MiuixIcons.Info
}

/** 路由所属的作用域；一级页面与「信息」返回 null。 */
fun NavKey.scope(): AppScope? = AppScope.entries.firstOrNull { this in it.pages }

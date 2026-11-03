package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import io.github.skyshadowhero.fancypad.ui.ds.*
import top.yukonga.miuix.kmp.nav.core.NavKey
import androidx.compose.ui.unit.dp

/**
 * 当前页面的标题 / 返回能力。由 [AppShell] 在 `NavDisplay` 外面统一提供，
 * 页面外壳（[PageScaffold]）读它来画自己的标题栏。
 */
val LocalPageTitle = compositionLocalOf { "" }
val LocalPageCanGoBack = compositionLocalOf { false }
val LocalPageOnBack = compositionLocalOf<() -> Unit> { {} }

/** 页面底栏（首页/功能/信息）。由 AppShell 提供，页面自己画 —— 这样进二级页也不会突然消失。 */
val LocalPageBottomBar = compositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * 页面内的分段控件（作用域内的兄弟页切换）。二级页用它替代底栏 ——
 * Miuix 皮肤走 `TabRow`，Glasmio 皮肤走 `GlasmioSegmentedControl`。
 */
val LocalPageHeader = compositionLocalOf<(@Composable (NavKey) -> Unit)?> { null }

/** The active page's real scaffold insets, consumed by its scrollable content. */
val LocalPageScaffoldPadding = compositionLocalOf { PaddingValues(0.dp) }

/**
 * 页面容器：**不透明底色** + 平板限宽居中。
 *
 * 底色是必须的：miuix-nav 的转场会把上一页继续组合在下面（`opaqueDepth = 1f`），
 * 页面自己不带底色的话，二级页半透明地叠在一级页上，就会出现「还能看到上一页」。
 *
 * 底色用 [DsTheme.colorScheme.surface]（浅色下 0xFFF7F7F7），**不能用 background**：
 * 浅色配色里 `background` 与 `surfaceContainer`（Card 的默认底色）都是纯白，
 * 页面用 background 的话卡片会和背景融成一片。
 */
@Composable
fun PageSurface(
    route: NavKey? = null,
    rootTitle: String? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    scaffoldPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    val title = when {
        route == RouteHome -> rootTitle ?: "首页"
        route != null -> route.title()
        else -> LocalPageTitle.current
    }
    val canGoBack = route?.let { it != RouteHome } ?: LocalPageCanGoBack.current
    val headerFactory = if (route?.scope() != null) LocalPageHeader.current else null
    val resolvedHeader: (@Composable () -> Unit)? = header ?: if (route != null && headerFactory != null) {
        { headerFactory(route) }
    } else {
        null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DsTheme.colorScheme.surface),
    ) {
        PageScaffold(
            title = title,
            canGoBack = canGoBack,
            onBack = LocalPageOnBack.current,
            header = resolvedHeader,
            bottomBar = bottomBar ?: LocalPageBottomBar.current,
            scaffoldPadding = scaffoldPadding,
        ) { _ -> content() }
    }
}

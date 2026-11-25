package io.github.skyshadowhero.fancypad.ui.components

import io.github.skyshadowhero.fancypad.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import io.github.skyshadowhero.fancypad.ui.ds.*
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.nav.core.NavKey
import androidx.compose.ui.unit.dp

/** 页面返回动作由 [AppShell] 在 `NavDisplay` 外统一提供。 */
val LocalPageOnBack = compositionLocalOf<() -> Unit> { {} }

/** The active page's real scaffold insets, consumed by its scrollable content. */
val LocalPageScaffoldPadding = compositionLocalOf { PaddingValues(0.dp) }

/** 页面正文录制层；玻璃顶栏、标签与下拉弹层共用，且不会采样到自身。 */
val LocalPageBackdrop = compositionLocalOf<Backdrop?> { null }

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
    route: NavKey,
    title: String = "",
    titleAlpha: () -> Float = { 0f },
    bottomBar: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    scaffoldPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DsTheme.colorScheme.surface),
    ) {
        PageScaffold(
            title = title,
            titleAlpha = titleAlpha,
            canGoBack = route != RouteHome,
            onBack = LocalPageOnBack.current,
            header = header,
            bottomBar = bottomBar,
            scaffoldPadding = scaffoldPadding,
        ) { _ -> content() }
    }
}

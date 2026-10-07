package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.theme.MiuixTheme


/**
 * 页面容器：**不透明底色** + 平板限宽居中。
 *
 * 底色是必须的：miuix-nav 的转场会把上一页继续组合在下面（`opaqueDepth = 1f`），
 * 页面自己不带底色的话，二级页半透明地叠在一级页上，就会出现「还能看到上一页」。
 *
 * 底色用 [MiuixTheme.colorScheme.surface]（浅色下 0xFFF7F7F7），**不能用 background**：
 * 浅色配色里 `background` 与 `surfaceContainer`（Card 的默认底色）都是纯白，
 * 页面用 background 的话卡片会和背景融成一片。
 *
 * 宽度交给外层的 Row：左栏收起时内容区域自然延伸过去（不再限宽居中）。
 */
@Composable
fun PageSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        content()
    }
}

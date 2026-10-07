package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 平板（宽屏）下内容的宽度上限。
 * 3200×2136 的平板上不限宽的话，一行设置会横跨整个屏幕，读起来很散。
 *
 * 实际上限由外壳通过 [LocalContentMaxWidth] 下发（左栏展开时要在两侧都留得下），
 * 这里的 840dp 只是兜底。
 */
internal val CONTENT_MAX_WIDTH = 840.dp

/**
 * 外壳下发的内容宽度上限。
 *
 * 左栏是**覆盖**在内容之上的（不参与布局，见 AppShell），所以上限要保证左栏完全展开时
 * 也压不到内容：`上限 ≤ 屏宽 - 左栏宽`。
 */
internal val LocalContentMaxWidth = compositionLocalOf { CONTENT_MAX_WIDTH }

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
 * 宽屏时内容限制在 [CONTENT_MAX_WIDTH] 内并水平居中，窄屏（手机 / 竖屏平板）不受影响。
 */
@Composable
fun PageSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = LocalContentMaxWidth.current)
                .fillMaxWidth(),
        ) {
            content()
        }
    }
}

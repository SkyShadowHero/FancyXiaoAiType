package io.github.skyshadowhero.fancypad.ui.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition

/**
 * Glasmio 的采样源。
 *
 * Glasmio 的玻璃不是「半透明白」，而是**实时采样身下的图层**再做模糊 / 折射 / 饱和。
 * 没有采样源时（`emptyBackdrop()`）采样结果是空的 —— 折射出来就是黑的，
 * 开关、滑块、分段控件、顶栏按钮全变成黑玻璃。设置界面也没有壁纸可采，
 * 所以这里自己录一层跟页面同色系的竖向渐变当采样源：
 *
 * - 浅色：白 → #F2F2F7，玻璃呈现 iOS 设置页那种偏白的磨砂；
 * - 深色：#1C1C1E → 黑，玻璃呈现深色磨砂。
 *
 * 用 [rememberLayerBackdrop] 真录一层（而不是给个纯色），玻璃才能取到明暗变化，
 * 高光与折射的层次才出得来；录进去的是**静态**渐变，不会因为页面重绘每帧重录。
 */
@Composable
fun GlasmioBackdropHost(content: @Composable () -> Unit) {
    val colors = GlasmioTheme.colors
    val backdrop = rememberLayerBackdrop()
    val brush = remember(colors) {
        Brush.verticalGradient(
            0f to colors.elevatedBackground,
            0.35f to colors.groupedBackground,
            1f to colors.groupedBackground,
        )
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .trackBackdropPosition(backdrop),
        ) {
            Box(Modifier.fillMaxSize().background(brush))
        }
        CompositionLocalProvider(
            LocalGlassBackdrop provides backdrop,
            LocalBlurBackdrop provides backdrop,
            content = content,
        )
    }
}

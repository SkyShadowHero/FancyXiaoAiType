package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏渐进模糊（progressive blur）所需的 backdrop。
 *
 * 模糊要能采样「顶栏背后的滚动内容」，所以内容必须先画进一层 backdrop：
 * 调用方用 `Modifier.layerBackdrop(backdrop)` 把内容层注册进去，
 * 顶栏再用 `progressiveTextureBlur(backdrop = ...)` 采样它。
 *
 * RuntimeShader 不可用时返回 null，调用方据此退化为「不模糊」——
 * 宁可没有效果，也不能因为效果把界面搞崩。本项目 minSdk 35（> 33），
 * 实际总是支持，这里的判断只是兜底。
 */
@Composable
internal fun rememberTopBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

/**
 * 顶部渐进模糊条：把 [content]（顶栏）叠在模糊层之上。
 *
 * 两个刻意的设计：
 *
 * 1. **只在滚动后出现**（[active]）。内容没滚动时顶栏下面没有东西经过，
 *    模糊只会糊住纯背景色，看着像脏了一层。
 * 2. **淡入系数走 `graphicsLayer` 的 lambda**（[scrollOffsetPx] 是函数而不是值）。
 *    滚动偏移每帧都在变，如果把它当参数读进组合，顶栏会每帧重组；
 *    放进 `graphicsLayer` 的 block 里则只在绘制阶段读取，不触发重组。
 *    这与 Miuix 官方 example 的 `BlurredBar` 做法一致。
 *
 * @param active 是否显示模糊层（通常由「是否已滚动」的 derivedStateOf 提供）
 * @param scrollOffsetPx 已滚动的距离（px，滚下去为正），用于计算淡入系数
 */
@Composable
internal fun BlurredTopBar(
    backdrop: LayerBackdrop?,
    active: Boolean,
    scrollOffsetPx: () -> Float,
    content: @Composable () -> Unit,
) {
    if (backdrop == null) {
        content()
        return
    }
    val density = LocalDensity.current.density
    Box {
        if (active) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        // 48dp 内完成淡入；在绘制阶段读状态，不引起重组
                        this.alpha = (scrollOffsetPx() / (48f * density)).coerceIn(0f, 1f)
                    }
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        // 曲线取 2.2：模糊从顶到底衰减得更快，顶栏文字区域才够实
                        gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                        blurRadius = 10f,
                        colors = barBlurColors(),
                    ),
            )
        }
        content()
    }
}

/**
 * 模糊层的混合色：半透明 surface。
 *
 * 纯模糊会显出发灰的底色，混一层半透明 surface 让它仍带主题底色，
 * 与顶栏配色连贯。系数 0.3 取自 Miuix example 的渐进模糊配置。
 */
@Composable
private fun barBlurColors(): BlurColors = BlurDefaults.blurColors(
    blendColors = listOf(
        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.3f)),
    ),
)

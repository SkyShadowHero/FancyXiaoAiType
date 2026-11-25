package io.github.skyshadowhero.fancypad.ui.components

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
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

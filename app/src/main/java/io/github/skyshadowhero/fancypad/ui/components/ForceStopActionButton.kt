package io.github.skyshadowhero.fancypad.ui.components

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

/** 一级页面右上角的“强制关闭应用”按钮。 */
@Composable
fun ForceStopActionButton(
    onClick: () -> Unit,
    backdrop: Backdrop? = null,
) {
    GlassIconButton(onClick = onClick, backdrop = backdrop) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = "强制关闭应用",
        )
    }
}

package io.github.skyshadowhero.fancypad.ui.components

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * 右上角「操作」按钮：裸图标、无卡片底色与直角边框。
 */
@Composable
fun DropdownActionMenu(
    onClick: () -> Unit,
    backdrop: Backdrop? = null,
) {
    GlassIconButton(onClick = onClick, backdrop = backdrop) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = "操作",
        )
    }
}

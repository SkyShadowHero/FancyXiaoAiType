package com.skyler.fancytype.ui

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * 右上角「操作」按钮：裸图标、无卡片底色与直角边框。
 */
@Composable
fun DropdownActionMenu(
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = "操作",
        )
    }
}

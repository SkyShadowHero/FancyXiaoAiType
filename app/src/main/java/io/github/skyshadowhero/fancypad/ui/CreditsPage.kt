package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ui.ds.ArrowPreference
import io.github.skyshadowhero.fancypad.ui.ds.Card
import io.github.skyshadowhero.fancypad.ui.ds.DsTheme
import io.github.skyshadowhero.fancypad.ui.ds.SmallTitle
import io.github.skyshadowhero.fancypad.ui.ds.Text

/**
 * 「致谢」页：用到的界面库、光标素材与参考实现。
 *
 * 光标素材逐个列出各自的仓库与许可（素材按仓库原许可分发，模块本身不改变其授权）。
 * 纯展示、不联网；点条目只在浏览器打开对应主页。
 */
@Composable
fun CreditsPage(
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current
    fun open(url: String) = runCatching { uriHandler.openUri(url) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = scaffoldPadding.calculateTopPadding() + 8.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.Top),
    ) {
        item { SmallTitle("界面") }
        item {
            Card {
                ArrowPreference(
                    title = "Miuix",
                    summary = "HyperOS 风格组件、模糊与导航",
                    onClick = { open("https://github.com/miuix-kotlin-multiplatform/miuix") },
                )
                ArrowPreference(
                    title = "Glasmio",
                    summary = "iOS 液态玻璃风格组件与图标",
                    onClick = { open("https://github.com/neoruaa/glasmio") },
                )
            }
        }

        item { SmallTitle("框架") }
        item {
            Card {
                ArrowPreference(
                    title = "LSPosed",
                    summary = "模块运行框架",
                    onClick = { open("https://github.com/LSPosed/LSPosed") },
                )
            }
        }

        item { SmallTitle("光标素材") }
        item {
            Card {
                ArrowPreference(
                    title = "AOSP cursors",
                    summary = "AOSP 矢量光标 · Tech-Tac/aosp-cursors · Apache-2.0",
                    onClick = { open("https://github.com/Tech-Tac/aosp-cursors") },
                )
                ArrowPreference(
                    title = "material-cursors",
                    summary = "Material 光标 · varlesh/material-cursors",
                    onClick = { open("https://github.com/varlesh/material-cursors") },
                )
                ArrowPreference(
                    title = "apple_cursor",
                    summary = "MacOS 风格光标 · ful1e5/apple_cursor · GPL-3.0",
                    onClick = { open("https://github.com/ful1e5/apple_cursor") },
                )
                ArrowPreference(
                    title = "Google Cursor",
                    summary = "GoogleDot 光标 · ful1e5/Google_Cursor",
                    onClick = { open("https://github.com/ful1e5/Google_Cursor") },
                )
                ArrowPreference(
                    title = "BreezeX Cursor",
                    summary = "BreezeX 光标 · ful1e5/BreezeX_Cursor",
                    onClick = { open("https://github.com/ful1e5/BreezeX_Cursor") },
                )
            }
        }

        item { SmallTitle("参考实现") }
        item {
            Card {
                ArrowPreference(
                    title = "Miuix example",
                    summary = "页面骨架、转场与返回手势的参考",
                    onClick = { open("https://github.com/miuix-kotlin-multiplatform/miuix") },
                )
            }
        }
    }
}

package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ui.ds.ArrowPreference
import io.github.skyshadowhero.fancypad.ui.ds.Card
import io.github.skyshadowhero.fancypad.ui.ds.SmallTitle

/** 项目仓库地址 */
private const val REPO_URL = "https://github.com/SkyShadowHero/FancyXiaoAiType"

/**
 * 「信息」页：外观（主题设置）与项目（仓库地址）。
 *
 * 原来的「检查更新」已经移除：它要靠联网读 GitHub tag，失败态和提示文案都容易误导，
 * 版本号在系统的应用信息里能看到。
 */
@Composable
fun InfoPage(
    uiState: AppUiState,
    scopePackages: Set<String>,
    onTheme: () -> Unit,
    onCredits: () -> Unit,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current

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
        item { SmallTitle("外观") }
        item {
            Card {
                ArrowPreference(
                    title = "主题设置",
                    summary = if (uiState.uiTheme == 1) "Glasmio（iOS 液态玻璃）" else "Miuix（HyperOS 风格）",
                    onClick = onTheme,
                )
            }
        }

        item { SmallTitle("关于") }
        item {
            Card {
                ArrowPreference(
                    title = "致谢",
                    summary = "用到的开源库、素材与参考实现",
                    onClick = onCredits,
                )
            }
        }

        item { SmallTitle("项目") }
        item {
            Card {
                ArrowPreference(
                    title = "GitHub 仓库",
                    summary = REPO_URL,
                    onClick = { runCatching { uriHandler.openUri(REPO_URL) } },
                )
            }
        }
    }
}

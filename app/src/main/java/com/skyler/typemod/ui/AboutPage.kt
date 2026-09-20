package com.skyler.typemod.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.skyler.typemod.PrefKeys
import com.skyler.typemod.Target
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** 项目仓库地址 */
private const val REPO_URL = "https://github.com/SkyShadowHero/XiaoAiTypeMod"

/**
 * 「关于」页：外观 + 目标应用信息 + 项目仓库。
 */
@Composable
fun AboutPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = scaffoldPadding.calculateTopPadding() + 8.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SmallTitle("外观") }
        item {
            Card {
                OverlayDropdownPreference(
                    title = "主题模式",
                    items = ThemeMode.entries.map { it.label },
                    selectedIndex = uiState.themeMode.coerceIn(0, ThemeMode.entries.size - 1),
                    onSelectedIndexChange = { index ->
                        uiState.themeMode = index
                        // 落盘，跨启动记忆
                        uiState.save { editor -> editor.putInt(PrefKeys.THEME_MODE, index) }
                    },
                )
            }
        }

        item { SmallTitle("目标应用") }
        item {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("应用：超级小爱输入法")
                    Text("包名：${Target.PACKAGE}")
                    Text("适配版本：${Target.VERSION_NAME} (${Target.VERSION_CODE})")
                }
            }
        }

        item { SmallTitle("项目") }
        item {
            Card {
                ArrowPreference(
                    title = "GitHub 仓库",
                    summary = REPO_URL,
                    onClick = {
                        runCatching { uriHandler.openUri(REPO_URL) }
                    },
                )
            }
        }
    }
}

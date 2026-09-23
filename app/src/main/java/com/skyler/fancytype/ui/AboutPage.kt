package com.skyler.fancytype.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.skyler.fancytype.BuildConfig
import com.skyler.fancytype.L
import com.skyler.fancytype.PrefKeys
import com.skyler.fancytype.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 项目仓库地址 */
private const val REPO_URL = "https://github.com/SkyShadowHero/FancyXiaoAiType"

/**
 * 「关于」页：外观 + 版本（检查更新）+ 项目仓库。
 *
 * 检查更新读 GitHub 的 tag：先用 releases/latest，仓库还没建 release 时退回 tags 列表。
 * 查到新版本会弹窗展示更新说明，再点「前往下载」跳浏览器。
 */
@Composable
fun AboutPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val current = BuildConfig.VERSION_NAME
    val update = result as? UpdateChecker.Result.Available

    fun startCheck() {
        if (checking) return
        checking = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { UpdateChecker.check(current) }
            L.i("event=update_check_result result=$r")
            result = r
            checking = false
            if (r is UpdateChecker.Result.Available) showUpdateDialog = true
        }
    }

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

        item { SmallTitle("版本") }
        item {
            Card {
                ArrowPreference(
                    title = "检查更新",
                    summary = when {
                        checking -> "正在检查…"
                        update != null -> "发现新版本 ${update.latest} · 点此查看"
                        result is UpdateChecker.Result.UpToDate -> "已是最新版本 · $current"
                        result is UpdateChecker.Result.Failed ->
                            "检查失败：${(result as UpdateChecker.Result.Failed).reason}"

                        else -> "当前版本 $current"
                    },
                    onClick = {
                        // 已经查到新版本时点一下先看说明，而不是重新检查
                        if (update != null) showUpdateDialog = true else startCheck()
                    },
                )
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

    // 发现新版本：展示更新说明并给出跳转
    WindowDialog(
        show = showUpdateDialog && update != null,
        title = "发现新版本 ${update?.latest ?: ""}",
        onDismissRequest = { showUpdateDialog = false },
        content = {
            Column {
                val notes = update?.notes
                Text(
                    text = if (notes.isNullOrBlank()) {
                        "当前版本 $current，可以更新到 ${update?.latest}。"
                    } else {
                        notes
                    },
                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = "稍后",
                        onClick = { showUpdateDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "前往下载",
                        onClick = {
                            showUpdateDialog = false
                            update?.url?.let { url -> runCatching { uriHandler.openUri(url) } }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

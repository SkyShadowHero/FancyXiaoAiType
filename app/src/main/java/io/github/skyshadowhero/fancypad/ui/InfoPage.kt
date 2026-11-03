package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.skyshadowhero.fancypad.BuildConfig
import io.github.skyshadowhero.fancypad.L
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.UpdateChecker
import io.github.skyshadowhero.fancypad.ui.ds.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 项目仓库地址 */
private const val REPO_URL = "https://github.com/SkyShadowHero/FancyXiaoAiType"

/**
 * 「信息」页：原来散在「关于」和「功能」页底部的设置都收到这里。
 *
 * 结构（和 miuix example 的 Settings 页同一套卡片分组）：
 * 外观 → 主题设置；版本 → 检查更新；作用域 → 每个功能域缺哪个包；项目 → 仓库。
 *
 * 检查更新读 GitHub 的 tag：先用 releases/latest，仓库还没建 release 时退回 tags 列表。
 */
@Composable
fun InfoPage(
    uiState: AppUiState,
    scopePackages: Set<String>,
    onTheme: () -> Unit,
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
                    onClick = { runCatching { uriHandler.openUri(REPO_URL) } },
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
                    color = DsTheme.colorScheme.onSurfaceContainerHigh,
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
                        colors = DsButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

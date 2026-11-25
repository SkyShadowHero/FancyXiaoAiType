package io.github.skyshadowhero.fancypad.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import io.github.skyshadowhero.fancypad.data.REPOSITORY_URL
import io.github.skyshadowhero.fancypad.data.ReleaseInfo
import io.github.skyshadowhero.fancypad.data.UpdateCheckResult
import io.github.skyshadowhero.fancypad.data.UpdateRepository
import io.github.skyshadowhero.fancypad.ui.ds.ArrowPreference
import io.github.skyshadowhero.fancypad.ui.ds.Card
import io.github.skyshadowhero.fancypad.ui.ds.SmallTitle
import kotlinx.coroutines.launch

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Current(val release: ReleaseInfo) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * 「信息」页：外观、版本更新与项目地址。
 */
@Composable
fun InfoPage(
    scopePackages: Set<String>,
    onTheme: () -> Unit,
    onCredits: () -> Unit,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    fun checkForUpdates() {
        if (updateState == UpdateUiState.Checking) return
        updateState = UpdateUiState.Checking
        coroutineScope.launch {
            updateState = runCatching { UpdateRepository.check(BuildConfig.VERSION_NAME) }
                .fold(
                    onSuccess = { result ->
                        when (result) {
                            is UpdateCheckResult.Available -> UpdateUiState.Available(result.release)
                            is UpdateCheckResult.UpToDate -> UpdateUiState.Current(result.latest)
                        }
                    },
                    onFailure = { error ->
                        UpdateUiState.Failed(error.message?.take(80) ?: "网络请求失败")
                    },
                )
        }
    }

    val updateSummary = when (val state = updateState) {
        UpdateUiState.Idle -> "当前版本 ${BuildConfig.VERSION_NAME} · 点击检查"
        UpdateUiState.Checking -> "正在连接 GitHub…"
        is UpdateUiState.Available -> "发现 ${state.release.tag} · 点击前往下载"
        is UpdateUiState.Current -> "已是最新版本 · ${BuildConfig.VERSION_NAME}"
        is UpdateUiState.Failed -> "检查失败：${state.message} · 点击重试"
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
                    summary = "明暗模式与莫奈配色",
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
                    title = "检查更新",
                    summary = updateSummary,
                    enabled = updateState != UpdateUiState.Checking,
                    onClick = {
                        val available = updateState as? UpdateUiState.Available
                        if (available != null) {
                            runCatching { uriHandler.openUri(available.release.downloadUrl) }
                        } else {
                            checkForUpdates()
                        }
                    },
                )
                ArrowPreference(
                    title = "GitHub 仓库",
                    summary = REPOSITORY_URL,
                    onClick = { runCatching { uriHandler.openUri(REPOSITORY_URL) } },
                )
            }
        }
    }
}

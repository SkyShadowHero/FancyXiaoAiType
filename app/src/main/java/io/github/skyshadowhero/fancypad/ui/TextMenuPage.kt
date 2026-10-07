package io.github.skyshadowhero.fancypad.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.PrefKeys
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「文本选择菜单」页：右键 / 长按文字弹出的那个浮动工具栏（复制 / 粘贴 / 全选…）。
 *
 * 系统把它的渲染交给了 SystemUI（framework 里 `system_selection_toolbar_enabled`
 * 硬编码为 true，应用进程只负责把菜单项和锚点快照发过去），所以作用域是
 * `com.android.systemui` —— 和「平行窗口动画」同一个进程，但相互独立。
 *
 * 原样式是一套 AOSP 原生观感：小圆角 + MD 配色 + 只有 150ms 纯 alpha 淡入。
 * 打开开关后换成 Miuix 的 token：弹出层圆角 16dp、底色 `surfaceContainer`
 * （浅色纯白 / 深色 `#242424`）、文字 `onSurfaceContainer` + `Body2` 14sp，
 * 并补上缩放入场。
 *
 * ⚠ 改完即时生效（Hook 侧读的是偏好缓存快照，一变就刷新）；
 * **模块本身的升级 / 作用域变更仍需要重启一次平板**。
 */
@Composable
fun TextMenuPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
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
        item { SmallTitle("文本选择菜单") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.toolbarEnabled,
                    onCheckedChange = { checked ->
                        uiState.toolbarEnabled = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.TOOLBAR_ENABLED, checked) }
                    },
                    title = "Miuix 外观",
                    summary = "右键或长按文字弹出的工具栏改用 Miuix 的圆角、配色与入场动画",
                )
                AnimatedVisibility(visible = uiState.toolbarEnabled) {
                    Column {
                        DpSlider(
                            title = "弹出层圆角",
                            summary = "默认 ${PrefKeys.TOOLBAR_CORNER_DEFAULT.toInt()}dp（Miuix 弹出层圆角）",
                            value = uiState.toolbarCornerDp,
                            range = PrefKeys.TOOLBAR_CORNER_MIN..PrefKeys.TOOLBAR_CORNER_MAX,
                            keyPoint = PrefKeys.TOOLBAR_CORNER_DEFAULT,
                            onValueChange = { v -> uiState.toolbarCornerDp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.TOOLBAR_CORNER_DP, uiState.toolbarCornerDp)
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                        DpSlider(
                            title = "文字大小",
                            summary = "默认 ${PrefKeys.TOOLBAR_TEXT_DEFAULT.toInt()}sp（Miuix Body2）",
                            value = uiState.toolbarTextSp,
                            range = PrefKeys.TOOLBAR_TEXT_MIN..PrefKeys.TOOLBAR_TEXT_MAX,
                            keyPoint = PrefKeys.TOOLBAR_TEXT_DEFAULT,
                            unit = "sp",
                            onValueChange = { v -> uiState.toolbarTextSp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.TOOLBAR_TEXT_SP, uiState.toolbarTextSp)
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                    }
                }
            }
        }
    }
}

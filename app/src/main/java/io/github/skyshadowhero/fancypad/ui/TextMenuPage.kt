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
 * 「文本选择菜单」页：长按 / 选中文字后的选择工具栏。
 *
 * 它由 SystemUI 的 `RemoteSelectionToolbar` 渲染 —— framework 里
 * `Flags.systemSelectionToolbarEnabled()` 在本机是硬编码 `return true`，应用进程只负责把
 * 菜单项与锚点快照发过去，所以这一域的作用域是 `com.android.systemui`，
 * **不需要把模块注入到各个应用**。
 *
 * 原样式是小圆角 + MD 配色 + 只有 150ms 纯 alpha 淡入；打开后换成 Miuix 的 token：
 * 弹出层圆角 16dp、底色 `surfaceContainer`（浅色纯白 / 深色 `#242424`）、
 * 文字 `onSurfaceContainer` + `Body2` 14sp，并补上缩放入场。
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
                    summary = "长按文字的工具栏改用 Miuix 的圆角、配色与入场动画（改完即时生效）",
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
        item {
            Card {
                SwitchPreference(
                    checked = uiState.contextMenuEnabled,
                    onCheckedChange = { checked ->
                        uiState.contextMenuEnabled = checked
                        uiState.save { e ->
                            e.putBoolean(PrefKeys.CONTEXTMENU_ENABLED, checked)
                        }
                    },
                    title = "右键上下文菜单",
                    summary = "右键弹出的多行菜单换成 Miuix 圆角/配色/字号（原版圆角只有 2dp）；" +
                        "作用域是各个应用自己，需在 LSPosed 里把目标应用加进作用域并重启该应用",
                )
                AnimatedVisibility(visible = uiState.contextMenuEnabled) {
                    Column {
                        DpSlider(
                            title = "弹出层圆角",
                            summary = "默认 ${PrefKeys.CONTEXTMENU_CORNER_DEFAULT.toInt()}dp（原版 2dp）",
                            value = uiState.contextMenuCornerDp,
                            range = PrefKeys.TOOLBAR_CORNER_MIN..PrefKeys.TOOLBAR_CORNER_MAX,
                            keyPoint = PrefKeys.CONTEXTMENU_CORNER_DEFAULT,
                            onValueChange = { v -> uiState.contextMenuCornerDp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(
                                        PrefKeys.CONTEXTMENU_CORNER_DP,
                                        uiState.contextMenuCornerDp,
                                    )
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                        DpSlider(
                            title = "菜单文字大小",
                            summary = "默认 ${PrefKeys.CONTEXTMENU_TEXT_DEFAULT.toInt()}sp" +
                                "（原版 16sp，Miuix Body2 = 14sp）",
                            value = uiState.contextMenuTextSp,
                            range = PrefKeys.TOOLBAR_TEXT_MIN..PrefKeys.TOOLBAR_TEXT_MAX,
                            keyPoint = PrefKeys.CONTEXTMENU_TEXT_DEFAULT,
                            unit = "sp",
                            onValueChange = { v -> uiState.contextMenuTextSp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.CONTEXTMENU_TEXT_SP, uiState.contextMenuTextSp)
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

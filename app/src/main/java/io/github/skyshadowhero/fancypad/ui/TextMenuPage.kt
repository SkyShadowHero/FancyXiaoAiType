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
 * 「文本选择菜单」页：右键 / 长按文字弹出的那个菜单。两块功能各自独立。
 *
 * ## 1. Miuix 外观（作用域 `com.android.systemui`）
 *
 * 长按文字走的是选择 ActionMode，它的工具栏由 SystemUI 的 `RemoteSelectionToolbar` 渲染
 * （framework 里 `system_selection_toolbar_enabled` 硬编码为 true，应用进程只负责把菜单项
 * 和锚点快照发过去）。原样式是小圆角 + MD 配色 + 只有 150ms 纯 alpha 淡入；打开后换成
 * Miuix 的 token：弹出层圆角 16dp、底色 `surfaceContainer`、文字 `onSurfaceContainer` +
 * `Body2` 14sp，并补上缩放入场。
 *
 * ## 2. 右键改为长按（作用域 = 目标应用自己）
 *
 * 右键和长按在本机是两条实现：右键走 `View.performButtonActionOnTouchDown()` →
 * `showContextMenu()`，弹的是框架的上下文菜单，在**应用进程**里按各自主题画；
 * 长按走选择 ActionMode，落在 SystemUI 上、样式统一。
 *
 * 打开这一项后，右键会在原地合成一次触摸长按，直接复用系统 / Chromium / MIUI 自己的长按
 * 逻辑（反正上面那一项已经把长按菜单换成 Miuix 观感了）。
 *
 * ⚠ 它跑在应用进程里，所以**目标应用必须先加入模块作用域**（LSPosed → 模块 → 作用域，
 * 逐个勾选；`scope.list` 只是推荐列表，不会自动生效），再重启该应用。
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
                    checked = uiState.rightClickAsLongPress,
                    onCheckedChange = { checked ->
                        uiState.rightClickAsLongPress = checked
                        uiState.save { e ->
                            e.putBoolean(PrefKeys.RIGHTCLICK_AS_LONGPRESS, checked)
                        }
                    },
                    title = "右键改为长按",
                    summary = "右键文字时走系统长按（选词 + 选择工具栏），不再弹原生上下文菜单；" +
                        "需在 LSPosed 里把目标应用加进作用域并重启该应用",
                )
            }
        }
    }
}

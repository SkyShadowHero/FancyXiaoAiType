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
 * 「长按菜单」页：长按或选中文字后弹出的那个工具栏（复制 / 粘贴 / 全选 / 分享…）。
 *
 * **它由 SystemUI 画，不是在应用里画的** —— framework 里
 * `Flags.systemSelectionToolbarEnabled()` 在本机是硬编码 `return true`：应用只把
 * "我有哪些菜单项 + 锚点在哪"打包发给系统，真正的视图由 SystemUI 的
 * `RemoteSelectionToolbar` 建、画在 SystemUI 进程里，再把画面交给应用显示。
 *
 * 所以这一域**只需要 `com.android.systemui` 一个作用域，改一处所有应用一起生效**，
 * 不用把模块注入到各个应用。
 *
 * 原样式是 2dp 小圆角 + MD 配色 + 只有 150ms 纯 alpha 淡入（framework-res 的
 * `floating_popup_background` 一脉）。打开开关后换成 Miuix 的 token：
 * 弹出层圆角 16dp、底色 `surfaceContainer`（浅色纯白 / 深色 `#242424`）、
 * 文字 `onSurfaceContainer` + `Body2` 14sp，并补上缩放入场。
 *
 * ⚠ 改完即时生效（Hook 侧读的是偏好缓存快照，一变就刷新）；
 * **模块本身的升级则需要重启一次平板**（LSPosed 在开机时重新注入）。
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
        item { SmallTitle("长按菜单") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.toolbarEnabled,
                    onCheckedChange = { checked ->
                        uiState.toolbarEnabled = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.TOOLBAR_ENABLED, checked) }
                    },
                    title = "改用 Miuix 样式",
                    summary = "长按或选中文字的工具栏换成 Miuix 的圆角、配色与字号" +
                        "（由 SystemUI 绘制，所有应用一起生效；改完即时生效）",
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

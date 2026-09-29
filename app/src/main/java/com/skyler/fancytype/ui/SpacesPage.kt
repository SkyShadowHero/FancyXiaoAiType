package com.skyler.fancytype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import com.skyler.fancytype.PrefKeys
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「虚拟键盘」页的第二段：间距调节 / 边距调节。
 *
 * 1. 间距调节：按键高度 / 键横间距 / 键行间距，横竖屏各一套。
 *    同一个开关控制，滑块标题用「横屏·」「竖屏·」前缀区分，不再另开卡片。
 * 2. 边距调节：键盘离屏幕左/右/下的距离。
 *
 * 关掉总开关时依赖项直接收起，避免一排不可用控件占位。全部走同一个尺寸覆写通道。
 * 超过「该项上限的 6/10」时由 AppShell 统一弹出尺寸过大警告。
 * 滑块统一走 [DpSlider]，点一下行即可弹出输入框输精确数值。
 */
fun LazyListScope.spacingSection(uiState: AppUiState) {
    item { SmallTitle("间距调节") }
    item {
        Card {
            SwitchPreference(
                checked = uiState.spaceEnabled,
                onCheckedChange = { checked ->
                    uiState.spaceEnabled = checked
                    uiState.save { editor -> editor.putBoolean(PrefKeys.SPACE_ENABLED, checked) }
                },
                title = "启用间距调节",
                summary = "关闭则全部保持默认",
            )
            AnimatedVisibility(visible = uiState.spaceEnabled) {
                Column {
                    // ---- 横屏 ----
                    DpSlider(
                        title = "横屏·按键高度",
                        summary = "默认 ${PrefKeys.SPACE_KEY_H_LAND_DEFAULT.toInt()}dp",
                        value = uiState.spaceKeyHLand,
                        range = PrefKeys.SPACE_MIN..PrefKeys.SPACE_KEY_H_MAX,
                        keyPoint = PrefKeys.SPACE_KEY_H_LAND_DEFAULT,
                        onValueChange = { v -> uiState.spaceKeyHLand = v },
                        onCommit = {
                            uiState.save { e -> e.putFloat(PrefKeys.SPACE_KEY_H_LAND, uiState.spaceKeyHLand) }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "横屏·键横间距",
                        summary = "默认 ${PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT.toInt()}dp",
                        value = uiState.spaceKeyHorizLand,
                        range = PrefKeys.SPACE_MIN..PrefKeys.SPACE_MAX,
                        keyPoint = PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT,
                        onValueChange = { v -> uiState.spaceKeyHorizLand = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.SPACE_KEY_HORIZ_LAND, uiState.spaceKeyHorizLand)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "横屏·键行间距",
                        summary = "默认 ${PrefKeys.SPACE_ROW_LAND_DEFAULT.toInt()}dp",
                        value = uiState.spaceRowLand,
                        range = PrefKeys.SPACE_MIN..PrefKeys.SPACE_MAX,
                        keyPoint = PrefKeys.SPACE_ROW_LAND_DEFAULT,
                        onValueChange = { v -> uiState.spaceRowLand = v },
                        onCommit = {
                            uiState.save { e -> e.putFloat(PrefKeys.SPACE_ROW_LAND, uiState.spaceRowLand) }
                        },
                        commitGuard = { uiState.loaded },
                    )

                    // ---- 竖屏 ----
                    DpSlider(
                        title = "竖屏·按键高度",
                        summary = "默认 ${PrefKeys.SPACE_KEY_H_PORT_DEFAULT.toInt()}dp",
                        value = uiState.spaceKeyHPort,
                        range = PrefKeys.SPACE_MIN..PrefKeys.SPACE_KEY_H_MAX,
                        keyPoint = PrefKeys.SPACE_KEY_H_PORT_DEFAULT,
                        onValueChange = { v -> uiState.spaceKeyHPort = v },
                        onCommit = {
                            uiState.save { e -> e.putFloat(PrefKeys.SPACE_KEY_H_PORT, uiState.spaceKeyHPort) }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "竖屏·键横间距",
                        summary = "默认 ${PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT.toInt()}dp",
                        value = uiState.spaceKeyHorizPort,
                        range = PrefKeys.SPACE_MIN..PrefKeys.SPACE_MAX,
                        keyPoint = PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT,
                        onValueChange = { v -> uiState.spaceKeyHorizPort = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.SPACE_KEY_HORIZ_PORT, uiState.spaceKeyHorizPort)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "竖屏·键行间距",
                        summary = "默认 ${PrefKeys.SPACE_ROW_PORT_DEFAULT.toInt()}dp",
                        value = uiState.spaceRowPort,
                        range = PrefKeys.SPACE_MIN..PrefKeys.SPACE_MAX,
                        keyPoint = PrefKeys.SPACE_ROW_PORT_DEFAULT,
                        onValueChange = { v -> uiState.spaceRowPort = v },
                        onCommit = {
                            uiState.save { e -> e.putFloat(PrefKeys.SPACE_ROW_PORT, uiState.spaceRowPort) }
                        },
                        commitGuard = { uiState.loaded },
                    )
                }
            }
        }
    }

    item { SmallTitle("边距调节") }
    item {
        Card {
            SwitchPreference(
                checked = uiState.marginEnabled,
                onCheckedChange = { checked ->
                    uiState.marginEnabled = checked
                    uiState.save { editor -> editor.putBoolean(PrefKeys.MARGIN_ENABLED, checked) }
                },
                title = "启用边距调节",
                summary = "调整键盘离屏幕左/右/下的距离；关闭则保持默认",
            )
            AnimatedVisibility(visible = uiState.marginEnabled) {
                Column {
                    DpSlider(
                        title = "左右边距",
                        summary = "默认约 ${PrefKeys.MARGIN_HORIZONTAL_DEFAULT.toInt()}dp",
                        value = uiState.marginHorizontalDp,
                        range = PrefKeys.MARGIN_MIN..PrefKeys.MARGIN_MAX,
                        keyPoint = PrefKeys.MARGIN_HORIZONTAL_DEFAULT,
                        onValueChange = { v -> uiState.marginHorizontalDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.MARGIN_HORIZONTAL_DP, uiState.marginHorizontalDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "下边距",
                        summary = "默认约 ${PrefKeys.MARGIN_BOTTOM_DEFAULT.toInt()}dp",
                        value = uiState.marginBottomDp,
                        range = PrefKeys.MARGIN_MIN..PrefKeys.MARGIN_MAX,
                        keyPoint = PrefKeys.MARGIN_BOTTOM_DEFAULT,
                        onValueChange = { v -> uiState.marginBottomDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.MARGIN_BOTTOM_DP, uiState.marginBottomDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                }
            }
        }
    }
}

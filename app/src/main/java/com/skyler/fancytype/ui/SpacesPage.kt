package com.skyler.fancytype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skyler.fancytype.PrefKeys
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 二级页「间距」：两张卡片，各自带总开关。
 *
 * 1. 间距调节：按键高度 / 键横间距 / 键行间距，横竖屏各一套。
 *    同一个开关控制，滑块标题用「横屏·」「竖屏·」前缀区分，不再另开卡片。
 * 2. 边距调节：键盘离屏幕左/右/下的距离。
 *
 * 关掉总开关时依赖项直接**收起**（与「分离键盘」页的圆角卡片一致），
 * 避免一排不可用控件占位。全部走同一个尺寸覆写通道。
 * 超过「该项上限的 6/10」时由 AppShell 统一弹出尺寸过大警告。
 */
@Composable
fun SpacesPage(
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
                        SliderPreference(
                            value = uiState.spaceKeyHLand,
                            onValueChange = { v -> uiState.spaceKeyHLand = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { e -> e.putFloat(PrefKeys.SPACE_KEY_H_LAND, uiState.spaceKeyHLand) }
                                }
                            },
                            valueRange = PrefKeys.SPACE_MIN..PrefKeys.SPACE_KEY_H_MAX,
                            keyPoints = listOf(PrefKeys.SPACE_KEY_H_LAND_DEFAULT),
                            showKeyPoints = true,
                            title = "横屏·按键高度",
                            summary = "默认 ${PrefKeys.SPACE_KEY_H_LAND_DEFAULT.toInt()}dp",
                            valueText = "${uiState.spaceKeyHLand.roundToInt()} dp",
                        )
                        SliderPreference(
                            value = uiState.spaceKeyHorizLand,
                            onValueChange = { v -> uiState.spaceKeyHorizLand = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { e ->
                                        e.putFloat(PrefKeys.SPACE_KEY_HORIZ_LAND, uiState.spaceKeyHorizLand)
                                    }
                                }
                            },
                            valueRange = PrefKeys.SPACE_MIN..PrefKeys.SPACE_MAX,
                            keyPoints = listOf(PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT),
                            showKeyPoints = true,
                            title = "横屏·键横间距",
                            summary = "默认 ${PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT.toInt()}dp",
                            valueText = "${uiState.spaceKeyHorizLand.roundToInt()} dp",
                        )
                        SliderPreference(
                            value = uiState.spaceRowLand,
                            onValueChange = { v -> uiState.spaceRowLand = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { e -> e.putFloat(PrefKeys.SPACE_ROW_LAND, uiState.spaceRowLand) }
                                }
                            },
                            valueRange = PrefKeys.SPACE_MIN..PrefKeys.SPACE_MAX,
                            keyPoints = listOf(PrefKeys.SPACE_ROW_LAND_DEFAULT),
                            showKeyPoints = true,
                            title = "横屏·键行间距",
                            summary = "默认 ${PrefKeys.SPACE_ROW_LAND_DEFAULT.toInt()}dp",
                            valueText = "${uiState.spaceRowLand.roundToInt()} dp",
                        )

                        // ---- 竖屏 ----
                        SliderPreference(
                            value = uiState.spaceKeyHPort,
                            onValueChange = { v -> uiState.spaceKeyHPort = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { e -> e.putFloat(PrefKeys.SPACE_KEY_H_PORT, uiState.spaceKeyHPort) }
                                }
                            },
                            valueRange = PrefKeys.SPACE_MIN..PrefKeys.SPACE_KEY_H_MAX,
                            keyPoints = listOf(PrefKeys.SPACE_KEY_H_PORT_DEFAULT),
                            showKeyPoints = true,
                            title = "竖屏·按键高度",
                            summary = "默认 ${PrefKeys.SPACE_KEY_H_PORT_DEFAULT.toInt()}dp",
                            valueText = "${uiState.spaceKeyHPort.roundToInt()} dp",
                        )
                        SliderPreference(
                            value = uiState.spaceKeyHorizPort,
                            onValueChange = { v -> uiState.spaceKeyHorizPort = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { e ->
                                        e.putFloat(PrefKeys.SPACE_KEY_HORIZ_PORT, uiState.spaceKeyHorizPort)
                                    }
                                }
                            },
                            valueRange = PrefKeys.SPACE_MIN..PrefKeys.SPACE_MAX,
                            keyPoints = listOf(PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT),
                            showKeyPoints = true,
                            title = "竖屏·键横间距",
                            summary = "默认 ${PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT.toInt()}dp",
                            valueText = "${uiState.spaceKeyHorizPort.roundToInt()} dp",
                        )
                        SliderPreference(
                            value = uiState.spaceRowPort,
                            onValueChange = { v -> uiState.spaceRowPort = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { e -> e.putFloat(PrefKeys.SPACE_ROW_PORT, uiState.spaceRowPort) }
                                }
                            },
                            valueRange = PrefKeys.SPACE_MIN..PrefKeys.SPACE_MAX,
                            keyPoints = listOf(PrefKeys.SPACE_ROW_PORT_DEFAULT),
                            showKeyPoints = true,
                            title = "竖屏·键行间距",
                            summary = "默认 ${PrefKeys.SPACE_ROW_PORT_DEFAULT.toInt()}dp",
                            valueText = "${uiState.spaceRowPort.roundToInt()} dp",
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
                        SliderPreference(
                            value = uiState.marginHorizontalDp,
                            onValueChange = { v -> uiState.marginHorizontalDp = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { e ->
                                        e.putFloat(PrefKeys.MARGIN_HORIZONTAL_DP, uiState.marginHorizontalDp)
                                    }
                                }
                            },
                            valueRange = PrefKeys.MARGIN_MIN..PrefKeys.MARGIN_MAX,
                            keyPoints = listOf(PrefKeys.MARGIN_HORIZONTAL_DEFAULT),
                            showKeyPoints = true,
                            title = "左右边距",
                            summary = "离屏幕左、右边缘的距离；默认约 ${PrefKeys.MARGIN_HORIZONTAL_DEFAULT.toInt()}dp",
                            valueText = "${uiState.marginHorizontalDp.roundToInt()} dp",
                        )
                        SliderPreference(
                            value = uiState.marginBottomDp,
                            onValueChange = { v -> uiState.marginBottomDp = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { e -> e.putFloat(PrefKeys.MARGIN_BOTTOM_DP, uiState.marginBottomDp) }
                                }
                            },
                            valueRange = PrefKeys.MARGIN_MIN..PrefKeys.MARGIN_MAX,
                            keyPoints = listOf(PrefKeys.MARGIN_BOTTOM_DEFAULT),
                            showKeyPoints = true,
                            title = "下边距",
                            summary = "离屏幕底部的距离；默认约 ${PrefKeys.MARGIN_BOTTOM_DEFAULT.toInt()}dp",
                            valueText = "${uiState.marginBottomDp.roundToInt()} dp",
                        )
                    }
                }
            }
        }
    }
}

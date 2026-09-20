package com.skyler.typemod.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skyler.typemod.PrefKeys
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 二级页「间距」：按键高度 / 键横向间距 / 行间距，横竖屏各一套。
 *
 * 关掉总开关时依赖项**变灰**（保持布局稳定）。全部走同一个尺寸覆写通道，改动实时生效。
 * 超过「该项上限的 6/10」时由 AppShell 统一弹出尺寸过大警告（与键盘间隙同一套判定与弹窗）。
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
        item {
            Card {
                SwitchPreference(
                    checked = uiState.spaceEnabled,
                    onCheckedChange = { checked ->
                        uiState.spaceEnabled = checked
                        uiState.save { editor -> editor.putBoolean(PrefKeys.SPACE_ENABLED, checked) }
                    },
                    title = "启用间距调节",
                    summary = "关闭则全部保持原厂",
                )
            }
        }

        item { SmallTitle("横屏") }
        item {
            Card {
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
                    title = "按键高度",
                    summary = "默认 ${PrefKeys.SPACE_KEY_H_LAND_DEFAULT.toInt()}dp",
                    valueText = "${uiState.spaceKeyHLand.toInt()} dp",
                    enabled = uiState.spaceEnabled,
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
                    title = "键横向间距",
                    summary = "默认 ${PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT.toInt()}dp",
                    valueText = "${uiState.spaceKeyHorizLand.toInt()} dp",
                    enabled = uiState.spaceEnabled,
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
                    title = "行间距",
                    summary = "默认 ${PrefKeys.SPACE_ROW_LAND_DEFAULT.toInt()}dp",
                    valueText = "${uiState.spaceRowLand.toInt()} dp",
                    enabled = uiState.spaceEnabled,
                )
            }
        }

        item { SmallTitle("竖屏") }
        item {
            Card {
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
                    title = "按键高度",
                    summary = "默认 ${PrefKeys.SPACE_KEY_H_PORT_DEFAULT.toInt()}dp",
                    valueText = "${uiState.spaceKeyHPort.toInt()} dp",
                    enabled = uiState.spaceEnabled,
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
                    title = "键横向间距",
                    summary = "默认 ${PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT.toInt()}dp",
                    valueText = "${uiState.spaceKeyHorizPort.toInt()} dp",
                    enabled = uiState.spaceEnabled,
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
                    title = "行间距",
                    summary = "默认 ${PrefKeys.SPACE_ROW_PORT_DEFAULT.toInt()}dp",
                    valueText = "${uiState.spaceRowPort.toInt()} dp",
                    enabled = uiState.spaceEnabled,
                )
            }
        }
    }
}

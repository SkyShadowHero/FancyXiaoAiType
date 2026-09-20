package com.skyler.fancytype.ui

import android.content.SharedPreferences
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
import com.skyler.fancytype.RemoteConfig
import com.skyler.fancytype.Target
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「分离键盘」设置页（Scaffold 由 AppShell 提供）。
 *
 * 关掉开关时，下面的滑块直接收起（不是变灰），避免一排不可用控件占位。
 * 间隙与间距在布局期读取，改动实时生效；按键圆角是构建期参数，需重开键盘。
 */
@Composable
fun SettingsPage(
    uiState: AppUiState,
    gapMaxLand: Float,
    gapMaxPort: Float,
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
        item { SmallTitle("分离键盘宽度") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.gapEnabled,
                    onCheckedChange = { checked ->
                        uiState.gapEnabled = checked
                        uiState.save { editor -> editor.putBoolean(PrefKeys.GAP_ENABLED, checked) }
                    },
                    title = "启用宽度调节",
                    summary = "关闭后完全恢复默认间隙",
                )
                AnimatedVisibility(visible = uiState.gapEnabled) {
                    Column {
                        SliderPreference(
                            value = uiState.gapLand,
                            onValueChange = { v -> uiState.gapLand = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { editor -> editor.putFloat(PrefKeys.GAP_LAND, uiState.gapLand) }
                                }
                            },
                            valueRange = PrefKeys.GAP_MIN..gapMaxLand,
                            keyPoints = listOf(Target.ORIGINAL_GAP_LAND_DP),
                            showKeyPoints = true,
                            title = "横屏中心间隙",
                            summary = "默认 ${Target.ORIGINAL_GAP_LAND_DP.toInt()}dp",
                            valueText = "${uiState.gapLand.toInt()} dp",
                        )
                        SliderPreference(
                            value = uiState.gapPort,
                            onValueChange = { v -> uiState.gapPort = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { editor -> editor.putFloat(PrefKeys.GAP_PORT, uiState.gapPort) }
                                }
                            },
                            valueRange = PrefKeys.GAP_MIN..gapMaxPort,
                            keyPoints = listOf(Target.ORIGINAL_GAP_PORT_DP),
                            showKeyPoints = true,
                            title = "竖屏中心间隙",
                            summary = "默认 ${Target.ORIGINAL_GAP_PORT_DP.toInt()}dp",
                            valueText = "${uiState.gapPort.toInt()} dp",
                        )
                    }
                }
            }
        }

        item { SmallTitle("按键圆角") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.cornerEnabled,
                    onCheckedChange = { checked ->
                        uiState.cornerEnabled = checked
                        uiState.save { editor -> editor.putBoolean(PrefKeys.CORNER_ENABLED, checked) }
                    },
                    title = "启用圆角调节",
                    summary = "统一调整按键与按键气泡的圆角；关闭则保持默认",
                )
                AnimatedVisibility(visible = uiState.cornerEnabled) {
                    Column {
                        SliderPreference(
                            value = uiState.cornerDp,
                            onValueChange = { v -> uiState.cornerDp = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { editor -> editor.putFloat(PrefKeys.CORNER_DP, uiState.cornerDp) }
                                }
                            },
                            valueRange = PrefKeys.CORNER_MIN..PrefKeys.CORNER_MAX,
                            keyPoints = listOf(Target.ORIGINAL_KEY_CORNER_DP),
                            showKeyPoints = true,
                            title = "按键圆角",
                            summary = "默认 ${Target.ORIGINAL_KEY_CORNER_DP.toInt()}dp",
                            valueText = "${uiState.cornerDp.toInt()} dp",
                        )
                        SliderPreference(
                            value = uiState.bubbleCornerDp,
                            onValueChange = { v -> uiState.bubbleCornerDp = v },
                            onValueChangeFinished = {
                                if (uiState.loaded) {
                                    uiState.save { editor ->
                                        editor.putFloat(PrefKeys.BUBBLE_CORNER_DP, uiState.bubbleCornerDp)
                                    }
                                }
                            },
                            valueRange = PrefKeys.CORNER_MIN..PrefKeys.CORNER_MAX,
                            keyPoints = listOf(Target.ORIGINAL_BUBBLE_CORNER_DP),
                            showKeyPoints = true,
                            title = "按键气泡圆角",
                            summary = "点击按键弹出的放大气泡；默认 ${Target.ORIGINAL_BUBBLE_CORNER_DP.toInt()}dp",
                            valueText = "${uiState.bubbleCornerDp.toInt()} dp",
                        )
                    }
                }
            }
        }

        item { SmallTitle("键盘形态") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.portraitForceNormal,
                    onCheckedChange = { checked ->
                        uiState.portraitForceNormal = checked
                        uiState.save { editor ->
                            editor.putBoolean(PrefKeys.PORTRAIT_FORCE_NORMAL, checked)
                        }
                    },
                    title = "竖屏强制普通键盘",
                    summary = "开启后：横屏保持分离，竖屏强制变为整块普通键盘",
                )
            }
        }
    }
}

/**
 * 统一写入口。装载完成前直接丢弃 —— 否则界面先渲染的默认值会覆盖已保存的设置，
 * 表现就是「设置没有记忆」。写入成功后回写 loaded 状态由调用方负责。
 */

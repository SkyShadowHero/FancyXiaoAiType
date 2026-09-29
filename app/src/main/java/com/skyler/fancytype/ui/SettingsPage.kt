package com.skyler.fancytype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.unit.dp
import com.skyler.fancytype.PrefKeys
import com.skyler.fancytype.Target
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「虚拟键盘」页的第一段：分离键盘宽度 / 按键圆角 / 键盘形态。
 *
 * 关掉开关时，下面的滑块直接收起（不是变灰），避免一排不可用控件占位。
 * 间隙与间距在布局期读取，改动实时生效；按键圆角是构建期参数，需重开键盘。
 * 滑块统一走 [DpSlider]，因此**点一下行就能弹出输入框输精确数值**。
 */
fun LazyListScope.appearanceSection(
    uiState: AppUiState,
    gapMaxLand: Float,
    gapMaxPort: Float,
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
                    DpSlider(
                        title = "横屏中心间隙",
                        summary = "默认 ${Target.ORIGINAL_GAP_LAND_DP.toInt()}dp",
                        value = uiState.gapLand,
                        range = PrefKeys.GAP_MIN..gapMaxLand,
                        keyPoint = Target.ORIGINAL_GAP_LAND_DP,
                        onValueChange = { v -> uiState.gapLand = v },
                        onCommit = {
                            uiState.save { editor -> editor.putFloat(PrefKeys.GAP_LAND, uiState.gapLand) }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "竖屏中心间隙",
                        summary = "默认 ${Target.ORIGINAL_GAP_PORT_DP.toInt()}dp",
                        value = uiState.gapPort,
                        range = PrefKeys.GAP_MIN..gapMaxPort,
                        keyPoint = Target.ORIGINAL_GAP_PORT_DP,
                        onValueChange = { v -> uiState.gapPort = v },
                        onCommit = {
                            uiState.save { editor -> editor.putFloat(PrefKeys.GAP_PORT, uiState.gapPort) }
                        },
                        commitGuard = { uiState.loaded },
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
                    DpSlider(
                        title = "按键圆角",
                        summary = "默认 ${Target.ORIGINAL_KEY_CORNER_DP.toInt()}dp",
                        value = uiState.cornerDp,
                        range = PrefKeys.CORNER_MIN..PrefKeys.CORNER_MAX,
                        keyPoint = Target.ORIGINAL_KEY_CORNER_DP,
                        onValueChange = { v -> uiState.cornerDp = v },
                        onCommit = {
                            uiState.save { editor -> editor.putFloat(PrefKeys.CORNER_DP, uiState.cornerDp) }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "按键气泡圆角",
                        summary = "默认 ${Target.ORIGINAL_BUBBLE_CORNER_DP.toInt()}dp",
                        value = uiState.bubbleCornerDp,
                        range = PrefKeys.CORNER_MIN..PrefKeys.CORNER_MAX,
                        keyPoint = Target.ORIGINAL_BUBBLE_CORNER_DP,
                        onValueChange = { v -> uiState.bubbleCornerDp = v },
                        onCommit = {
                            uiState.save { editor ->
                                editor.putFloat(PrefKeys.BUBBLE_CORNER_DP, uiState.bubbleCornerDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
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

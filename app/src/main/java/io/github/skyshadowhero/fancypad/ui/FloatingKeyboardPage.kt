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
 * 「悬浮键盘」页：可拖动输入法栏的圆角、阴影与间距。
 *
 * 术语按界面叫法分两组 —— App 内部对这两块的原名是混淆前的英文类名，
 * 界面上统一成：
 *   movable_bar_*  -> 「工具栏」    可拖动的输入法栏本体
 *   floating_bar_* -> 「候选窗口」  悬浮键盘上的候选词窗口
 *
 * **圆角是两者共用的**，所以单独成一组放在最上面，不归到任何一侧。
 * 与「虚拟键盘」页是两套互相独立的 UI，分开两页。
 */
@Composable
fun FloatingKeyboardPage(
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
        item { SmallTitle("悬浮键盘") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.floatBarEnabled,
                    onCheckedChange = { checked ->
                        uiState.floatBarEnabled = checked
                        uiState.save { editor -> editor.putBoolean(PrefKeys.FLOATBAR_ENABLED, checked) }
                    },
                    title = "启用悬浮键盘调节",
                    summary = "关闭则全部保持默认",
                )
                // 字号覆盖是**独立开关**（放在总开关后面）：输入法自带的「候选词大小」
                // 是虚拟键盘与悬浮候选窗口共用的，这里覆盖的是悬浮窗口专属的资源，
                // 所以只想改字号时不必打开上面的总开关。
                SwitchPreference(
                    checked = uiState.candFontEnabled,
                    onCheckedChange = { checked ->
                        uiState.candFontEnabled = checked
                        uiState.save { editor -> editor.putBoolean(PrefKeys.CAND_FONT_ENABLED, checked) }
                    },
                    title = "覆盖候选窗口字体大小",
                    summary = "只影响悬浮候选窗口",
                )
                AnimatedVisibility(visible = uiState.candFontEnabled) {
                    Column {
                        DpSlider(
                            title = "候选词字号",
                            summary = "默认 ${PrefKeys.CAND_WIN_CAND_FONT_DEFAULT.toInt()}dp",
                            value = uiState.candWinCandFontDp,
                            range = PrefKeys.CAND_WIN_CAND_FONT_MIN..PrefKeys.CAND_WIN_CAND_FONT_MAX,
                            keyPoint = PrefKeys.CAND_WIN_CAND_FONT_DEFAULT,
                            onValueChange = { v -> uiState.candWinCandFontDp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.CAND_WIN_CAND_FONT_DP, uiState.candWinCandFontDp)
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                        DpSlider(
                            title = "序号字号",
                            summary = "默认 ${PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT.toInt()}dp",
                            value = uiState.candWinNumberFontDp,
                            range = PrefKeys.CAND_WIN_NUMBER_FONT_MIN..PrefKeys.CAND_WIN_NUMBER_FONT_MAX,
                            keyPoint = PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT,
                            onValueChange = { v -> uiState.candWinNumberFontDp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.CAND_WIN_NUMBER_FONT_DP, uiState.candWinNumberFontDp)
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                        DpSlider(
                            title = "拼音字号",
                            summary = "默认 ${PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT.toInt()}dp",
                            value = uiState.candWinPinyinFontDp,
                            range = PrefKeys.CAND_WIN_PINYIN_FONT_MIN..PrefKeys.CAND_WIN_PINYIN_FONT_MAX,
                            keyPoint = PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT,
                            onValueChange = { v -> uiState.candWinPinyinFontDp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.CAND_WIN_PINYIN_FONT_DP, uiState.candWinPinyinFontDp)
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                    }
                }
            }
        }

        // 关掉总开关时下面三组直接不渲染，避免一排不可用控件占位
        if (uiState.floatBarEnabled) {
            item { SmallTitle("圆角") }
            item {
                Card {
                    DpSlider(
                        title = "圆角",
                        summary = "工具栏与候选窗口共用，默认 ${PrefKeys.FLOATBAR_CORNER_DEFAULT.toInt()}dp",
                        value = uiState.floatBarCornerDp,
                        range = PrefKeys.FLOATBAR_CORNER_MIN..PrefKeys.FLOATBAR_CORNER_MAX,
                        keyPoint = PrefKeys.FLOATBAR_CORNER_DEFAULT,
                        onValueChange = { v -> uiState.floatBarCornerDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.FLOATBAR_CORNER_DP, uiState.floatBarCornerDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                }
            }

            item { SmallTitle("工具栏") }
            item {
                Card {
                    DpSlider(
                        title = "阴影",
                        summary = "默认 ${PrefKeys.TOOLBAR_SHADOW_DEFAULT.toInt()}dp",
                        value = uiState.toolbarShadowDp,
                        range = PrefKeys.TOOLBAR_SHADOW_MIN..PrefKeys.TOOLBAR_SHADOW_MAX,
                        keyPoint = PrefKeys.TOOLBAR_SHADOW_DEFAULT,
                        onValueChange = { v -> uiState.toolbarShadowDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.TOOLBAR_SHADOW_DP, uiState.toolbarShadowDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "按钮间距",
                        summary = "默认 ${PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT.toInt()}dp",
                        value = uiState.toolbarButtonSpacingDp,
                        range = PrefKeys.TOOLBAR_BUTTON_SPACING_MIN..PrefKeys.TOOLBAR_BUTTON_SPACING_MAX,
                        keyPoint = PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT,
                        onValueChange = { v -> uiState.toolbarButtonSpacingDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(
                                    PrefKeys.TOOLBAR_BUTTON_SPACING_DP,
                                    uiState.toolbarButtonSpacingDp,
                                )
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "行上下内边距",
                        summary = "默认 ${PrefKeys.TOOLBAR_VPADDING_DEFAULT.toInt()}dp",
                        value = uiState.toolbarVPaddingDp,
                        range = PrefKeys.TOOLBAR_VPADDING_MIN..PrefKeys.TOOLBAR_VPADDING_MAX,
                        keyPoint = PrefKeys.TOOLBAR_VPADDING_DEFAULT,
                        onValueChange = { v -> uiState.toolbarVPaddingDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.TOOLBAR_VPADDING_DP, uiState.toolbarVPaddingDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "左内边距",
                        summary = "默认 ${PrefKeys.TOOLBAR_PADDING_START_DEFAULT.toInt()}dp",
                        value = uiState.toolbarPaddingStartDp,
                        range = PrefKeys.TOOLBAR_SIDE_PADDING_MIN..PrefKeys.TOOLBAR_SIDE_PADDING_MAX,
                        keyPoint = PrefKeys.TOOLBAR_PADDING_START_DEFAULT,
                        onValueChange = { v -> uiState.toolbarPaddingStartDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(
                                    PrefKeys.TOOLBAR_PADDING_START_DP,
                                    uiState.toolbarPaddingStartDp,
                                )
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "右内边距",
                        summary = "默认 ${PrefKeys.TOOLBAR_PADDING_END_DEFAULT.toInt()}dp",
                        value = uiState.toolbarPaddingEndDp,
                        range = PrefKeys.TOOLBAR_SIDE_PADDING_MIN..PrefKeys.TOOLBAR_SIDE_PADDING_MAX,
                        keyPoint = PrefKeys.TOOLBAR_PADDING_END_DEFAULT,
                        onValueChange = { v -> uiState.toolbarPaddingEndDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(
                                    PrefKeys.TOOLBAR_PADDING_END_DP,
                                    uiState.toolbarPaddingEndDp,
                                )
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "竖条左边距",
                        summary = "默认 ${PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT.toInt()}dp",
                        value = uiState.toolbarHandleOffsetStartDp,
                        range = PrefKeys.TOOLBAR_HANDLE_OFFSET_START_MIN..PrefKeys.TOOLBAR_HANDLE_OFFSET_START_MAX,
                        keyPoint = PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT,
                        onValueChange = { v -> uiState.toolbarHandleOffsetStartDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(
                                    PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DP,
                                    uiState.toolbarHandleOffsetStartDp,
                                )
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "描边宽度",
                        summary = "默认 ${PrefKeys.BORDER_WIDTH_DEFAULT}dp",
                        value = uiState.toolbarBorderWidthDp,
                        range = PrefKeys.BORDER_WIDTH_MIN..PrefKeys.BORDER_WIDTH_MAX,
                        keyPoint = PrefKeys.BORDER_WIDTH_DEFAULT,
                        stepDp = 0.5f,
                        onValueChange = { v -> uiState.toolbarBorderWidthDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.TOOLBAR_BORDER_WIDTH_DP, uiState.toolbarBorderWidthDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                }
            }

            item { SmallTitle("候选窗口") }
            item {
                Card {
                    DpSlider(
                        title = "宽度",
                        summary = "默认 ${PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT.toInt()}dp，超出屏幕时按屏幕宽",
                        value = uiState.candWinMaxWidthDp,
                        range = PrefKeys.CAND_WIN_MAX_WIDTH_MIN..PrefKeys.CAND_WIN_MAX_WIDTH_MAX,
                        keyPoint = PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT,
                        onValueChange = { v -> uiState.candWinMaxWidthDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CAND_WIN_MAX_WIDTH_DP, uiState.candWinMaxWidthDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "左右边距",
                        summary = "默认 ${PrefKeys.CAND_WIN_H_PADDING_DEFAULT.toInt()}dp",
                        value = uiState.candWinHPaddingDp,
                        range = PrefKeys.CAND_WIN_H_PADDING_MIN..PrefKeys.CAND_WIN_H_PADDING_MAX,
                        keyPoint = PrefKeys.CAND_WIN_H_PADDING_DEFAULT,
                        onValueChange = { v -> uiState.candWinHPaddingDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CAND_WIN_H_PADDING_DP, uiState.candWinHPaddingDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "阴影",
                        summary = "默认 ${PrefKeys.CAND_WIN_SHADOW_DEFAULT.toInt()}dp",
                        value = uiState.candWinShadowDp,
                        range = PrefKeys.TOOLBAR_SHADOW_MIN..PrefKeys.TOOLBAR_SHADOW_MAX,
                        keyPoint = PrefKeys.CAND_WIN_SHADOW_DEFAULT,
                        onValueChange = { v -> uiState.candWinShadowDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CAND_WIN_SHADOW_DP, uiState.candWinShadowDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "候选横间距",
                        summary = "默认 ${PrefKeys.CAND_WIN_SPACING_DEFAULT.toInt()}dp",
                        value = uiState.candWinSpacingDp,
                        range = PrefKeys.CAND_WIN_SPACING_MIN..PrefKeys.CAND_WIN_SPACING_MAX,
                        keyPoint = PrefKeys.CAND_WIN_SPACING_DEFAULT,
                        onValueChange = { v -> uiState.candWinSpacingDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CAND_WIN_SPACING_DP, uiState.candWinSpacingDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "候选行边距",
                        summary = "默认 ${PrefKeys.CAND_WIN_ROW_PADDING_DEFAULT.toInt()}dp",
                        value = uiState.candWinRowPaddingDp,
                        range = PrefKeys.TOOLBAR_VPADDING_MIN..PrefKeys.TOOLBAR_VPADDING_MAX,
                        keyPoint = PrefKeys.CAND_WIN_ROW_PADDING_DEFAULT,
                        onValueChange = { v -> uiState.candWinRowPaddingDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CAND_WIN_ROW_PADDING_DP, uiState.candWinRowPaddingDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "拼音上边距",
                        summary = "默认 ${PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT.toInt()}dp",
                        value = uiState.candWinPinyinTopDp,
                        range = PrefKeys.CAND_WIN_PINYIN_MIN..PrefKeys.CAND_WIN_PINYIN_MAX,
                        keyPoint = PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT,
                        onValueChange = { v -> uiState.candWinPinyinTopDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CAND_WIN_PINYIN_TOP_DP, uiState.candWinPinyinTopDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "拼音下边距",
                        summary = "默认 ${PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT.toInt()}dp",
                        value = uiState.candWinPinyinBottomDp,
                        range = PrefKeys.CAND_WIN_PINYIN_MIN..PrefKeys.CAND_WIN_PINYIN_MAX,
                        keyPoint = PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT,
                        onValueChange = { v -> uiState.candWinPinyinBottomDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CAND_WIN_PINYIN_BOTTOM_DP, uiState.candWinPinyinBottomDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "描边宽度",
                        summary = "默认 ${PrefKeys.BORDER_WIDTH_DEFAULT}dp",
                        value = uiState.candWinBorderWidthDp,
                        range = PrefKeys.BORDER_WIDTH_MIN..PrefKeys.BORDER_WIDTH_MAX,
                        keyPoint = PrefKeys.BORDER_WIDTH_DEFAULT,
                        stepDp = 0.5f,
                        onValueChange = { v -> uiState.candWinBorderWidthDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CAND_WIN_BORDER_WIDTH_DP, uiState.candWinBorderWidthDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                }
            }
        }

    }
}

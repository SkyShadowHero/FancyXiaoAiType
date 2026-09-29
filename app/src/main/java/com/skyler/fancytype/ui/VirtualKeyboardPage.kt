package com.skyler.fancytype.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skyler.fancytype.PrefKeys
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「虚拟键盘」页：触屏虚拟键盘的全部样式调节，三段合成一页。
 *
 * 原先的「外观」「间距」两页，加上新增的「候选词」段合并到这里 ——
 * 三者调的是同一套 UI：触屏虚拟键盘本体、它的按键，以及它上面的候选词窗口。
 *
 * 悬浮键盘（可拖动的输入法栏）是另一套独立 UI，放在「悬浮键盘」页。
 */
@Composable
fun VirtualKeyboardPage(
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
        appearanceSection(uiState, gapMaxLand, gapMaxPort)
        spacingSection(uiState)
        candidateSection(uiState)
    }
}

/** 「虚拟键盘」页的第三段：候选词窗口。 */
private fun LazyListScope.candidateSection(uiState: AppUiState) {
    item { SmallTitle("候选词") }
    item {
        Card {
            SwitchPreference(
                checked = uiState.candidateEnabled,
                onCheckedChange = { checked ->
                    uiState.candidateEnabled = checked
                    uiState.save { editor -> editor.putBoolean(PrefKeys.CANDIDATE_ENABLED, checked) }
                },
                title = "启用候选词调节",
                summary = "关闭则全部保持默认",
            )
            AnimatedVisibility(visible = uiState.candidateEnabled) {
                Column {
                    DpSlider(
                        title = "候选项圆角",
                        summary = "默认 ${PrefKeys.CANDIDATE_CORNER_DEFAULT.toInt()}dp",
                        value = uiState.candidateCornerDp,
                        range = PrefKeys.CANDIDATE_CORNER_MIN..PrefKeys.CANDIDATE_CORNER_MAX,
                        keyPoint = PrefKeys.CANDIDATE_CORNER_DEFAULT,
                        onValueChange = { v -> uiState.candidateCornerDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CANDIDATE_CORNER_DP, uiState.candidateCornerDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                    DpSlider(
                        title = "候选间距",
                        summary = "默认 ${PrefKeys.CANDIDATE_SPACING_DEFAULT.toInt()}dp",
                        value = uiState.candidateSpacingDp,
                        range = PrefKeys.CANDIDATE_SPACING_MIN..PrefKeys.CANDIDATE_SPACING_MAX,
                        keyPoint = PrefKeys.CANDIDATE_SPACING_DEFAULT,
                        onValueChange = { v -> uiState.candidateSpacingDp = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.CANDIDATE_SPACING_DP, uiState.candidateSpacingDp)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                }
            }
        }
    }
}

package io.github.skyshadowhero.fancypad.ui

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
import io.github.skyshadowhero.fancypad.PrefKeys
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「虚拟键盘」页：触屏虚拟键盘的全部样式调节，四段合成一页。
 *
 * 原先的「外观」「间距」两页，加上新增的「候选词」段合并到这里 ——
 * 三者调的是同一套 UI：触屏虚拟键盘本体、它的按键，以及它上面的候选词窗口。
 * 末段「悬浮键盘大小」也归在这里：它调的同样是触屏键盘，只是切换到了
 * 平板上那种可以拖动、缩放的悬浮形态。
 *
 * 悬浮键盘（可拖动的输入法栏）的样式是另一套独立 UI，放在「悬浮键盘」页。
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
        floatingSizeSection(uiState)
    }
}

/**
 * 「虚拟键盘」页的第四段：悬浮键盘最大尺寸。
 *
 * 输入法自己把悬浮键盘的尺寸钉死在「自然宽度的 0.65 ~ 1.1 倍」，
 * 平板上放到最大也还是很小。这一段把上限交回给用户。
 */
private fun LazyListScope.floatingSizeSection(uiState: AppUiState) {
    item { SmallTitle("悬浮键盘大小") }
    item {
        Card {
            SwitchPreference(
                checked = uiState.floatKbUnlock,
                onCheckedChange = { checked ->
                    uiState.floatKbUnlock = checked
                    uiState.save { editor -> editor.putBoolean(PrefKeys.FLOAT_KB_UNLOCK, checked) }
                },
                title = "解锁最大尺寸",
                summary = "放开悬浮键盘 ${PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE} 倍的放大上限",
            )
            AnimatedVisibility(visible = uiState.floatKbUnlock) {
                Column {
                    DpSlider(
                        title = "最大放大",
                        summary = "默认 ${PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE} 倍",
                        value = uiState.floatKbMaxScale,
                        range = PrefKeys.FLOAT_KB_MAX_SCALE_MIN..PrefKeys.FLOAT_KB_MAX_SCALE_MAX,
                        keyPoint = PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE,
                        unit = "倍",
                        stepDp = 0.1f,
                        onValueChange = { v -> uiState.floatKbMaxScale = v },
                        onCommit = {
                            uiState.save { e ->
                                e.putFloat(PrefKeys.FLOAT_KB_MAX_SCALE, uiState.floatKbMaxScale)
                            }
                        },
                        commitGuard = { uiState.loaded },
                    )
                }
            }
        }
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

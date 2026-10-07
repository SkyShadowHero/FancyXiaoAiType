package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 宽屏（平板横屏）阈值，与外壳的 rail 断点一致 */
private const val WIDE_BREAKPOINT_DP = 840

/**
 * 二级页「颜色设置」——只对可改色的预设（AOSP / GoogleDot）开放，
 * 由「光标」页里该预设行下方的「颜色设置 ›」进入。
 *
 * 与原 os4光标主题模块一致：宽屏两个取色器并排，窄屏上下排列，各自带「恢复默认」
 * （默认值取当前主题自己的原始配色）。取色器强制不透明 —— 光标必须是实心色。
 */
@Composable
fun CursorColorsPage(
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val state = cursorState
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_BREAKPOINT_DP

    // 取色器改动 → 防抖落盘（本页与光标页任一可见时都能落盘）
    LaunchedEffect(state.preset, state.scale, state.fill, state.stroke, state.bound) {
        state.persistDebounced()
    }

    if (wide) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { colorPickerCard(state, true) }
                    Box(Modifier.weight(1f)) { colorPickerCard(state, false) }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = scaffoldPadding.calculateTopPadding() + 8.dp,
                    bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            colorPickerCard(state, true)
            colorPickerCard(state, false)
        }
    }
}

/** 顶栏副标题：改动即时生效 + 当前主题（由外壳调用） */
internal fun cursorColorsSubtitle(): String =
    "改动即时生效 · ${themeLabelOf(cursorState.preset)}"

@Composable
private fun colorPickerCard(state: CursorUiState, isFill: Boolean) {
    val color = if (isFill) state.fill else state.stroke
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(BasicComponentDefaults.InsideMargin)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            1.dp,
                            MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.35f),
                            CircleShape,
                        )
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    (if (isFill) "填充色 " else "描边色 ") + hex(color),
                    style = MiuixTheme.textStyles.body1,
                )
            }
            Spacer(Modifier.height(12.dp))
            ColorPicker(
                color = color,
                onColorChanged = { picked ->
                    val opaque = picked.copy(alpha = 1f)      // 光标必须不透明
                    if (isFill) state.fill = opaque else state.stroke = opaque
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val d = defaultColorsOf(state.preset)
                if (isFill) state.fill = Color(d.first) else state.stroke = Color(d.second)
            }) { Text("恢复默认") }
        }
    }
}

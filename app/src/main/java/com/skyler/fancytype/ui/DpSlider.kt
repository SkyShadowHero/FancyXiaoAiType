package com.skyler.fancytype.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 全模块统一的 dp 滑块。三种交互：
 *
 * 1. **拖拽**：拖动中只改内存，松手才落盘（`onValueChangeFinished` → `onCommit`）。
 * 2. **点击整行**：弹出输入框直接键入精确数值，超范围会夹到区间内。
 *    点的是行本身（标题 / 摘要 / 数值那一行），拖动条在 `bottomAction` 里，
 *    两者互不冲突 —— 见 Miuix `SliderPreference` 的布局实现。
 *    Miuix 在设置 `onClick` 后会自动在行尾加一个箭头图标，提示该行可点。
 * 3. **关键值吸附**：`keyPoints` + `showKeyPoints`，拖到默认值附近会吸住。
 *
 * 装载未完成（`commitGuard` 为 false）时丢弃写入，避免界面先渲染的默认值
 * 覆盖已保存的设置 —— 这是本模块「设置没有记忆」的历史根因。
 *
 * @param stepDp 拖拽的最小步进（dp）。默认 1dp；描边宽度这类默认值就是 0.5dp 的
 *   设置传 0.5f，否则拖拽只能落到整数、够不到自己的默认值。
 * @param unit 数值单位，同时用于行尾显示与输入框标签。默认 dp；悬浮键盘最大尺寸
 *   这类**倍率**设置传「倍」，否则会显示成 «2.0 dp» 这种不对的单位。
 */
@Composable
internal fun DpSlider(
    title: String,
    summary: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    keyPoint: Float,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    commitGuard: () -> Boolean,
    stepDp: Float = 1f,
    unit: String = "dp",
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    // 先把区间端点吸附到步进的整数倍，再据此算 steps。
    //
    // 为什么不能直接 (跨度 / 步进) 取整当 steps：横屏中心间隙的上限是按屏幕宽度
    // 算出来的动态值（如 510.33dp），端点不是整数；直接取整会让每一格变成
    // 1.0008dp 这种非整数，拖出来的值永远带小数，正是「不是 1dp」的原因。
    // 这里把端点向下裁到整数格点，保证每一格正好等于 stepDp。
    val intervals = ((range.endInclusive - range.start) / stepDp).toInt().coerceAtLeast(1)
    val snappedEnd = range.start + intervals * stepDp
    // steps = 区间内可取的**中间值**个数（不含两端）= 格数 - 1
    val steps = intervals - 1

    SliderPreference(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = { if (commitGuard()) onCommit() },
        valueRange = range.start..snappedEnd,
        steps = steps,
        keyPoints = listOf(keyPoint),
        showKeyPoints = true,
        title = title,
        summary = summary,
        valueText = "${numText(value)} $unit",
        onClick = {
            // 用当前值预填，用户多半是在它基础上微调
            input = numText(value)
            dialogOpen = true
        },
    )

    // show = false 时 Miuix 不会构建窗口内容（WindowDialog 内部有 visible 判断），
    // 所以每个滑块各带一个对话框实例的开销可以忽略。
    WindowDialog(
        show = dialogOpen,
        title = title,
        summary = summary,
        onDismissRequest = { dialogOpen = false },
        content = {
            Column {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "数值（$unit）",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = "取消",
                        onClick = { dialogOpen = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "确定",
                        onClick = {
                            val parsed = input.trim().toFloatOrNull()
                            if (parsed != null) {
                                // 输入可以超范围，落盘前夹回滑块区间，保持状态自洽
                                onValueChange(parsed.coerceIn(range.start, range.endInclusive))
                                if (commitGuard()) onCommit()
                            }
                            dialogOpen = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

/** 非整数（如描边宽度 0.5dp）保留一位小数，整数不显示 `.0` */
private fun numText(v: Float): String =
    if (v == v.toInt().toFloat()) "${v.toInt()}" else "%.1f".format(v)

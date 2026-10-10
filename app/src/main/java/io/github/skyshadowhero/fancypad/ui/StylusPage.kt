package io.github.skyshadowhero.fancypad.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.PrefKeys
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「随手写」页：给超级小爱输入法（`com.xiaomi.type`）补上**触控笔手写**。
 *
 * 这里的「随手写」是 AOSP Android 14+ 的 stylus handwriting —— 用笔直接在输入框上写字、
 * 笔迹转文字上屏，**不是**输入法里那个「手写键盘」。
 *
 * 页面三组：
 *
 * **一、随手写本身**（输入法进程，作用域 `com.xiaomi.type`）
 * - [PrefKeys.STYLUS_ENABLED]：笔迹要不要真的处理（补 AOSP 的四个 stylus 回调）
 * - [PrefKeys.STYLUS_DELAY_MS]：停笔多久后送识别（系统笔引擎那条路用）
 *
 * **二、笔迹显示**（同一进程，纯附加能力：关掉只影响看得见与否，不影响识别）
 * - [PrefKeys.STYLUS_INK_ENABLED]：画不画笔迹
 * - [PrefKeys.STYLUS_INK_COLOR] / [PrefKeys.STYLUS_INK_WIDTH_PX]：颜色与粗细（px）
 *
 * 书写区就是**整屏**（屏幕宽 × 高，不给用户调）：写哪儿都有笔迹。
 * 早先那版是满宽一条横带、跟着落笔点上下移动，已废弃 —— 带子会在书写中途跳一下。
 *
 * **三、系统侧配合**（system_server 进程，作用域 `system`）
 * - [PrefKeys.STYLUS_WHITELIST]：让系统认定小爱支持随手写（默认开）
 *
 * 总开关 [PrefKeys.STYLUS_ENABLED] 关掉时，**除它以外的全部内容都不显示**。
 *
 * ⚠ 生效条件：模块作用域要勾上 `com.xiaomi.type`、`system`、`com.miui.securitycore`；
 * `system` 侧是**开机注入**的，改完这个开关需要重启一次平板（输入法侧只需重启输入法进程）。
 * 笔迹的颜色/粗细在**每次起笔时**读取，改完下一次落笔生效。
 */
@Composable
fun StylusPage(
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
        item { SmallTitle("随手写") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.stylusEnabled,
                    onCheckedChange = { checked ->
                        uiState.stylusEnabled = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.STYLUS_ENABLED, checked) }
                    },
                    title = "随手写（触控笔手写）",
                    summary = "笔直接在输入框上写字，笔迹转文字上屏",
                )
                // 依赖项跟随总开关显隐：总开关关掉时这些参数没有作用对象，
                // 留在界面上只会让人误以为改了有用（与「平行窗口」页同一处理）。
                AnimatedVisibility(visible = uiState.stylusEnabled) {
                    Column {
                        DpSlider(
                            title = "停笔识别延迟",
                            summary = "停笔多久后出字，默认 " +
                                "${PrefKeys.STYLUS_DELAY_DEFAULT.toInt()} 毫秒",
                            value = uiState.stylusDelayMs,
                            range = PrefKeys.STYLUS_DELAY_MIN..PrefKeys.STYLUS_DELAY_MAX,
                            keyPoint = PrefKeys.STYLUS_DELAY_DEFAULT,
                            unit = "毫秒",
                            stepDp = 100f,
                            onValueChange = { v -> uiState.stylusDelayMs = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.STYLUS_DELAY_MS, uiState.stylusDelayMs)
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                    }
                }
            }
        }

        // 总开关关掉时，这一页其余内容**全部隐藏**：没有作用对象时留着它们只会
        // 让人误以为改了有用（与「平行菜单」页同一处理）。
        if (uiState.stylusEnabled) {
            item { SmallTitle("笔迹显示") }
            item {
                Card {
                    SwitchPreference(
                        checked = uiState.stylusInkEnabled,
                        onCheckedChange = { checked ->
                            uiState.stylusInkEnabled = checked
                            uiState.save { e -> e.putBoolean(PrefKeys.STYLUS_INK_ENABLED, checked) }
                        },
                        title = "显示笔迹",
                        summary = "实时画线；关闭则只看得到识别结果",
                    )
                    AnimatedVisibility(visible = uiState.stylusInkEnabled) {
                        Column {
                            DpSlider(
                                title = "笔迹粗细",
                                summary = "默认 ${PrefKeys.STYLUS_INK_WIDTH_DEFAULT.toInt()} 像素",
                                value = uiState.stylusInkWidthPx,
                                range = PrefKeys.STYLUS_INK_WIDTH_MIN..PrefKeys.STYLUS_INK_WIDTH_MAX,
                                keyPoint = PrefKeys.STYLUS_INK_WIDTH_DEFAULT,
                                unit = "px",
                                onValueChange = { v -> uiState.stylusInkWidthPx = v },
                                onCommit = {
                                    uiState.save { e ->
                                        e.putFloat(
                                            PrefKeys.STYLUS_INK_WIDTH_PX,
                                            uiState.stylusInkWidthPx,
                                        )
                                    }
                                },
                                commitGuard = { uiState.loaded },
                                stepDp = 1f,
                            )
                            InkColorSection(uiState)
                        }
                    }
                }
            }

            item { SmallTitle("书写手势") }
            item {
                Card {
                    SwitchPreference(
                        checked = uiState.stylusGestureEnabled,
                        onCheckedChange = { checked ->
                            uiState.stylusGestureEnabled = checked
                            uiState.save { e -> e.putBoolean(PrefKeys.STYLUS_GESTURE_ENABLED, checked) }
                        },
                        title = "书写手势",
                        summary = "圈选＝选中，划掉＝删除，画尖尖（^）＝在光标处插入",
                    )
                }
            }

            item { SmallTitle("识别引擎") }
            item {
                Card {
                    SwitchPreference(
                        checked = uiState.stylusIflytek,
                        onCheckedChange = { checked ->
                            uiState.stylusIflytek = checked
                            uiState.save { e -> e.putBoolean(PrefKeys.STYLUS_IFLYTEK, checked) }
                        },
                        title = "用讯飞引擎识别",
                        summary = "用小爱自带的讯飞引擎识别\n"
                            + "关闭则退回系统笔引擎，识别率会明显下降",
                    )
                }
            }

            item { SmallTitle("系统侧配合") }
            item {
                Card {
                    SwitchPreference(
                        checked = uiState.stylusWhitelist,
                        onCheckedChange = { checked ->
                            uiState.stylusWhitelist = checked
                            uiState.save { e -> e.putBoolean(PrefKeys.STYLUS_WHITELIST, checked) }
                        },
                        title = "让小爱进入随手写白名单",
                        summary = if (uiState.stylusWhitelist) {
                            "系统已把小爱当作支持随手写的输入法"
                        } else {
                            "⚠ 关闭后系统设置里的随手写会打不开"
                        },
                    )
                }
            }
    }
    }
}

/**
 * 笔迹颜色：色块 + 十六进制 + 取色器 + 恢复默认。
 *
 * 默认色是 HyperOS 蓝而不是黑/白 —— 笔迹叠在**宿主应用**的内容上，
 * 深浅背景都可能遇到，纯黑在深色底上看不见、纯白在浅色底上看不见。
 */
@Composable
private fun InkColorSection(uiState: AppUiState) {
    Column(modifier = Modifier.padding(BasicComponentDefaults.InsideMargin)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(uiState.stylusInkColor))
                    .border(
                        1.dp,
                        MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.35f),
                        CircleShape,
                    )
            )
            Spacer(Modifier.width(12.dp))
            Text("笔迹颜色 ${hex(Color(uiState.stylusInkColor))}", style = MiuixTheme.textStyles.body1)
        }
        Spacer(Modifier.height(12.dp))
        ColorPicker(
            color = Color(uiState.stylusInkColor),
            onColorChanged = { picked ->
                // 笔迹必须不透明：半透明的线叠在正文上会糊成一片
                uiState.stylusInkColor = picked.copy(alpha = 1f).toArgb()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { uiState.stylusInkColor = PrefKeys.STYLUS_INK_COLOR_DEFAULT }) {
            Text("恢复默认颜色")
        }

        // 拖动取色器会**连续**回调，不能每次回调都落盘（一次拖拽几十次 binder 写）。
        // 用 LaunchedEffect 的 key 做天然防抖：值一变就取消上一次、重新计时，
        // 只有在 400ms 内不再变化时才真正写一次。
        LaunchedEffect(uiState.stylusInkColor) {
            if (!uiState.loaded) return@LaunchedEffect
            delay(COLOR_PERSIST_DEBOUNCE_MS)
            uiState.save { e -> e.putInt(PrefKeys.STYLUS_INK_COLOR, uiState.stylusInkColor) }
        }
    }
}

/** 取色器落盘防抖（毫秒）。 */
private const val COLOR_PERSIST_DEBOUNCE_MS = 400L

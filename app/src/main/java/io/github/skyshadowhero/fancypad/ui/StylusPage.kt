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
 * - 依赖项：`_area_half/_area_full` → [PrefKeys.STYLUS_FULLSCREEN]；
 *   `handwriting_recognition_delay`（50~1000，默认 500）→ [PrefKeys.STYLUS_DELAY_MS]
 *
 * **二、墨迹**（同样在输入法进程）
 * - [PrefKeys.STYLUS_INK_COLOR]：墨迹颜色。墨迹是模块**自绘**的 —— 框架只提供手写窗口，
 *   `InkWindow.mInkView` 必须由输入法自己塞进去，没人塞就什么都不显示（见 `StylusInkView`）。
 *
 * **三、系统侧配合**（system_server 进程，作用域 `system`）
 * - [PrefKeys.STYLUS_WHITELIST]：让系统认定小爱支持随手写（默认开）
 *
 * ⚠ 生效条件：模块作用域要勾上 `com.xiaomi.type`、`system`、`com.miui.securitycore`；
 * `system` 侧是**开机注入**的，改完这个开关需要重启一次平板（输入法侧只需重启输入法进程）。
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
                    summary = "笔直接在输入框上写字、笔迹转文字上屏；关闭则落笔不作任何处理",
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
                        "系统已把小爱当作支持随手写的输入法（不这么做设置里的随手写会打不开）"
                    } else {
                        "⚠ 关闭后系统设置里的随手写开关会打不开，并会把你切到搜狗"
                    },
                )
            }
        }
    }
}


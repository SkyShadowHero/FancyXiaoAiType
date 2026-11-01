package io.github.skyshadowhero.fancypad.ui.ds

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.preference.GlasmioListPreference
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.glass.NavigationGlassMaterial
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle
import io.github.neoruaa.glasmio.ui.GlasmioSlider
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.ColorPicker as MiuixColorPicker
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.SmallTitle as MiuixSmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.preference.ArrowPreference as MiuixArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference as MiuixCheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference as MiuixOverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference as MiuixRadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference as MiuixSliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference as MiuixSwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog as MiuixWindowDialog

// ======================================================================================
// 设计系统适配层
//
// 页面里**只 import 这一个包**：同一套调用在 Miuix 与 Glasmio 两种外观下各渲染一套，
// 页面代码因此不用写 if。函数名与参数名刻意跟 Miuix 保持一致（cp 自 top.yukonga.miuix.kmp），
// 迁移时只换 import 就行。
//
// 约定：Glasmio 那边一律用它的公开组件；只有它没有对应物时才用 Compose 基础组件拼
//（例如勾选标记、分隔线、取色网格），并把颜色全部取自 GlasmioTheme.colors。
// ======================================================================================

/** 记录「这是卡片里的第几行」，Glasmio 的分隔线要靠它决定画不画（第一行不画）。 */
private val LocalDsRowCounter = staticCompositionLocalOf<IntArray?> { null }

@Composable
private fun isNotFirstRow(): Boolean {
    val counter = LocalDsRowCounter.current ?: return true
    return remember { counter[0]++ > 0 }
}

// ---------------------------------------------------------------------------- 容器

@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (isGlasmio) {
        val counter = remember { IntArray(1) }
        counter[0] = 0
        CompositionLocalProvider(LocalDsRowCounter provides counter) {
            GlasmioGroupedList(modifier = modifier) { content() }
        }
    } else {
        MiuixCard(modifier = modifier) { content() }
    }
}

@Composable
fun SmallTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (isGlasmio) {
        GlasmioSectionTitle(text, modifier.padding(top = 6.dp))
    } else {
        MiuixSmallTitle(text, modifier)
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    if (isGlasmio) {
        Box(
            modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DsTheme.colorScheme.separator),
        )
    } else {
        MiuixHorizontalDivider(modifier = modifier)
    }
}

// ----------------------------------------------------------------------- 设置行（偏好）

/**
 * Glasmio 列表行的统一外壳。
 *
 * 两点跟直接用 `GlasmioListRow` 不同：
 * 1. 点击反馈走 [dsLiquidClick]（按下整行微缩 + 一层淡淡的内容色），不是 Material 水波纹；
 * 2. 行本身不再吃点击（`onClick` 传 null），避免和外层点击抢事件。
 *
 * 默认**不显示 chevron**：iOS 的设置里只有「会进入下一页」的行才带箭头，
 * 单选/复选/开关行带的是勾或开关本身。
 */
@Composable
private fun DsListRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    showTopSeparator: Boolean = true,
    showDisclosure: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    // 列表行按 iOS 的做法：只盖一层整行的淡灰（不缩放、不带圆角，
                    // 圆角由分组容器自己裁），缩放只留给按钮那类小块可点区域。
                    Modifier.dsLiquidClick(
                        cornerRadius = 0.dp,
                        pressScale = 1f,
                        fillAlpha = 0.07f,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        GlasmioListRow(
            title = title,
            subtitle = subtitle,
            leading = leading,
            trailing = trailing,
            enabled = enabled,
            showTopSeparator = showTopSeparator,
            showDisclosure = showDisclosure,
        )
    }
}

@Composable
fun SwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
) {
    if (isGlasmio) {
        DsListRow(
            title = title,
            subtitle = summary,
            enabled = enabled,
            showTopSeparator = isNotFirstRow(),
            onClick = { if (enabled) onCheckedChange(!checked) },
            trailing = {
                GlasmioSwitch(
                    checked = checked,
                    onCheckedChange = { if (enabled) onCheckedChange(it) },
                    enabled = enabled,
                )
            },
        )
    } else {
        MiuixSwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            summary = summary,
            enabled = enabled,
        )
    }
}

@Composable
fun ArrowPreference(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    if (isGlasmio) {
        DsListRow(
            title = title,
            subtitle = summary,
            leading = startAction,
            enabled = enabled,
            showTopSeparator = isNotFirstRow(),
            showDisclosure = true,
            onClick = if (enabled) onClick else null,
        )
    } else {
        MiuixArrowPreference(
            title = title,
            summary = summary,
            onClick = onClick,
            enabled = enabled,
            startAction = startAction,
        )
    }
}

@Composable
fun RadioButtonPreference(
    title: String,
    summary: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    startAction: (@Composable () -> Unit)? = null,
) {
    if (isGlasmio) {
        DsListRow(
            title = title,
            subtitle = summary,
            leading = startAction,
            showTopSeparator = isNotFirstRow(),
            onClick = onClick,
            trailing = if (selected) ({ CheckMark() }) else null,
        )
    } else {
        MiuixRadioButtonPreference(
            title = title,
            summary = summary,
            selected = selected,
            onClick = onClick,
            startAction = startAction,
        )
    }
}

@Composable
fun CheckboxPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    if (isGlasmio) {
        val colors = DsTheme.colorScheme
        DsListRow(
            title = title,
            subtitle = summary,
            enabled = enabled,
            showTopSeparator = isNotFirstRow(),
            onClick = { if (enabled) onCheckedChange(!checked) },
            trailing = {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            if (checked) colors.accent else Color.Transparent,
                            RoundedCornerShape(50),
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (checked) colors.accent else colors.separator,
                            shape = RoundedCornerShape(50),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) {
                        Text("✓", color = Color.White, style = TextStyle(fontSize = 13.sp))
                    }
                }
            },
        )
    } else {
        MiuixCheckboxPreference(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
fun OverlayDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
) {
    if (isGlasmio) {
        var expanded by remember { mutableStateOf(false) }
        val secondary = DsTheme.colorScheme.onBackgroundVariant
        // iOS 26 的选择器：菜单用 glass 的衔接动画从**右边当前值**那里长出来。
        //
        // 关键点：锚点只包住右侧的值，不包整行 —— 左边标题留在锚点外面，
        // 所以形变（morph）只发生在值那一小块上，标题不会跟着消失/被拉伸。
        // 整行的点击交给外层 clickable（设置 expanded），不再依赖 anchor 的 onClick。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .dsLiquidClick(
                    cornerRadius = 0.dp,
                    pressScale = 1f,
                    fillAlpha = 0.07f,
                    onClick = { expanded = true },
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = DsTheme.textStyles.body1,
                color = DsTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GlasmioMenu(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                itemCount = items.size,
                morphFromAnchor = true,
                expandFromAnchor = Alignment.End,
                anchor = {
                    // 锚点给一小块玻璃：Glasmio 的 morph 是从「有玻璃的源」展开成菜单的，
                    // 源是纯文字的话它只能退化成普通的位移/缩放，看起来就不像玻璃动画。
                    GlassSurface(
                        shape = RoundedCornerShape(8.dp),
                        style = GlassSurfaceStyle.Navigation,
                        material = NavigationGlassMaterial.Clear,
                        contentAlignment = Alignment.Center,
                    ) {
                    AnimatedContent(
                        targetState = items.getOrNull(selectedIndex).orEmpty(),
                        transitionSpec = {
                            (
                                slideInHorizontally(animationSpec = tween(200)) { it } +
                                    fadeIn(animationSpec = tween(160))
                                )
                                .togetherWith(
                                    slideOutHorizontally(animationSpec = tween(160)) { -it } +
                                        fadeOut(animationSpec = tween(120))
                                )
                        },
                        label = "dropdownValue",
                    ) { value ->
                        Text(
                            text = value,
                            style = DsTheme.textStyles.body1,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    }
                },
            ) { _, close ->
            items.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onSelectedIndexChange(index)
                            close()
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = DsTheme.textStyles.body1,
                        color = DsTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isSelected) {
                        CheckMark()
                    }
                }
            }
            }
        }
    } else {
        MiuixOverlayDropdownPreference(
            title = title,
            items = items,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
        )
    }
}

@Composable
fun SliderPreference(
    value: Float,
    onValueChange: (Float) -> Unit,
    title: String,
    summary: String? = null,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    keyPoints: List<Float> = emptyList(),
    showKeyPoints: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    if (isGlasmio) {
        val colors = DsTheme.colorScheme
        val showTop = isNotFirstRow()
        // 吸附到与 Miuix 完全相同的步进：Miuix 的 steps 是「两端之间可取的中间值个数」，
        // 也就是区间被切成 steps + 1 格。Glasmio 的 Slider 本身是连续的，
        // 所以在回调里量化一次 —— 否则会拖出 0.1dp 这种 Miuix 永远不会出现的值。
        val span = valueRange.endInclusive - valueRange.start
        val stepSize = if (steps > 0 && span > 0f) span / (steps + 1) else 0f
        val snap: (Float) -> Float = { raw ->
            if (stepSize <= 0f) {
                raw.coerceIn(valueRange.start, valueRange.endInclusive)
            } else {
                val k = ((raw - valueRange.start) / stepSize).roundToInt()
                (valueRange.start + k * stepSize).coerceIn(valueRange.start, valueRange.endInclusive)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glasmioGroupedListSeparator(colors.separator, visible = showTop)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (onClick != null) {
                                Modifier.dsLiquidClick(onClick = onClick)
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    Text(title, style = DsTheme.textStyles.body1)
                    summary?.let {
                        Text(
                            it,
                            style = DsTheme.textStyles.subheadline,
                            color = colors.onBackgroundVariant,
                        )
                    }
                }
                Text(
                    valueText,
                    style = DsTheme.textStyles.body1,
                    color = colors.onBackgroundVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            GlasmioSlider(
                value = value,
                onValueChange = { onValueChange(snap(it)) },
                valueRange = valueRange,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
            )
        }
    } else {
        MiuixSliderPreference(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            keyPoints = keyPoints,
            showKeyPoints = showKeyPoints,
            title = title,
            summary = summary,
            valueText = valueText,
            onClick = onClick,
            enabled = enabled,
        )
    }
}

// ------------------------------------------------------------------------- 控件 / 文本

/** [DsButtonDefaults.textButtonColorsPrimary] 的返回值；只用来表达「这是主按钮」。 */
enum class DsTextButtonColors { Regular, Primary }

object DsButtonDefaults {
    fun textButtonColorsPrimary(): DsTextButtonColors = DsTextButtonColors.Primary
}

@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: DsTextButtonColors = DsTextButtonColors.Regular,
) {
    if (isGlasmio) {
        val primary = colors == DsTextButtonColors.Primary
        GlasmioButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            emphasis = if (primary) GlassEmphasis.Prominent else GlassEmphasis.Regular,
        ) {
            Text(
                text = text,
                style = DsTheme.textStyles.body1,
                color = if (primary) Color.White else DsTheme.colorScheme.accent,
                maxLines = 1,
            )
        }
    } else {
        if (colors == DsTextButtonColors.Primary) {
            MiuixTextButton(
                text = text,
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
            )
        } else {
            MiuixTextButton(
                text = text,
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
            )
        }
    }
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (isGlasmio) {
        GlasmioButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            emphasis = GlassEmphasis.Regular,
            content = content,
        )
    } else {
        MiuixButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            content = content,
        )
    }
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (isGlasmio) {
        M3Text(
            text = text,
            modifier = modifier,
            color = if (color == Color.Unspecified) LocalContentColor.current else color,
            style = style ?: MaterialTheme.typography.bodyLarge,
            maxLines = maxLines,
            overflow = overflow,
        )
    } else {
        if (style == null) {
            MiuixText(
                text = text,
                modifier = modifier,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
            )
        } else {
            MiuixText(
                text = text,
                modifier = modifier,
                color = color,
                style = style,
                maxLines = maxLines,
                overflow = overflow,
            )
        }
    }
}

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    if (isGlasmio) {
        GlasmioTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            placeholder = label,
            keyboardOptions = keyboardOptions,
        )
    } else {
        MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = modifier,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
        )
    }
}

@Composable
fun TextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    if (isGlasmio) {
        GlasmioTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            placeholder = label,
            keyboardOptions = keyboardOptions,
        )
    } else {
        MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = modifier,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
        )
    }
}

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (isGlasmio) {
        M3Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
        )
    } else {
        MiuixIcon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}

@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (isGlasmio) {
        M3Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
        )
    } else {
        MiuixIcon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (isGlasmio) {
        Box(
            modifier = modifier
                .size(44.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    } else {
        MiuixIconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    }
}

// ------------------------------------------------------------------------- 对话框

@Composable
fun WindowDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    summary: String? = null,
    /**
     * 触发这个弹窗的那个控件在窗口里的位置/尺寸。给了就**从它的位置连续长出来**
     * （缩放到屏幕中间），不给则从中间淡入。
     */
    anchor: Rect? = null,
    content: @Composable () -> Unit,
) {
    if (isGlasmio) {
        GlasmioAlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            message = summary,
            visible = show,
            content = { content() },
        )
    } else {
        MiuixWindowDialog(
            show = show,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
            content = content,
        )
    }
}


// ------------------------------------------------------------------------- 取色器

/** iOS 风格取色：一排排色板，点一下即选中（光标与笔迹都要求不透明，因此不带透明度）。 */
private val DsPalette: List<Color> = listOf(
    0xFF000000, 0xFF3A3A3C, 0xFF8E8E93, 0xFFFFFFFF,
    0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759,
    0xFF00C7BE, 0xFF30B0C7, 0xFF007AFF, 0xFF5856D6,
    0xFFAF52DE, 0xFFFF2D55, 0xFFAC7F5E, 0xFF3482FF,
).map { Color(it) }

@Composable
fun ColorPicker(
    color: Color,
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isGlasmio) {
        val colors = DsTheme.colorScheme
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(color, CircleShape)
                        .border(1.dp, colors.separator, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text("当前颜色", style = DsTheme.textStyles.body2, color = colors.onBackgroundVariant)
            }
            DsPalette.chunked(8).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { swatch ->
                        val selected = swatch == color
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(swatch, CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) colors.accent else colors.separator,
                                    shape = CircleShape,
                                )
                                .dsLiquidClick { onColorChanged(swatch) },
                        )
                    }
                }
            }
        }
    } else {
        MiuixColorPicker(
            color = color,
            onColorChanged = onColorChanged,
            modifier = modifier,
        )
    }
}

/** iOS 的勾选标记（对号），只用于单选行尾。 */
@Composable
private fun CheckMark() {
    Text(
        text = "✓",
        style = TextStyle(fontSize = 17.sp),
        color = DsTheme.colorScheme.accent,
    )
}

/**
 * 液态按压反馈：按下时整块轻微收缩（0.985）+ 一层很淡的内容色圆角填充，没有水波纹。
 *
 * iOS 26/27 的可点区域按下不是「盖一层灰」，而是**整块轻微回弹 + 内容色微亮**；
 * 这里用同一个 interactionSource 同时驱动缩放与填充，填充画在内容下面（drawBehind），
 * 所以文字和图标不会被蒙上一层膜。
 */
@Composable
fun Modifier.dsLiquidClick(
    enabled: Boolean = true,
    cornerRadius: Dp = 12.dp,
    pressScale: Float = 1f,
    fillAlpha: Float = 0.08f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        label = "dsLiquidClick",
    )
    val fill = DsTheme.colorScheme.onBackground
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawBehind {
            if (pressed && enabled && fillAlpha > 0f) {
                drawRoundRect(
                    color = fill.copy(alpha = fillAlpha),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                )
            }
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

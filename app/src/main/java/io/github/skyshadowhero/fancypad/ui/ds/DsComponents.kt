package io.github.skyshadowhero.fancypad.ui.ds

import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
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
// 页面只 import 这一个包；函数名与参数名刻意跟 Miuix 保持一致
//（cp 自 top.yukonga.miuix.kmp），迁移时只换 import 就行。
// ======================================================================================

// ---------------------------------------------------------------------------- 容器

@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MiuixCard(modifier = modifier) { content() }
}

@Composable
fun SmallTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    MiuixSmallTitle(text, modifier)
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    MiuixHorizontalDivider(modifier = modifier)
}

// ----------------------------------------------------------------------- 设置行（偏好）

@Composable
fun SwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
) {
    MiuixSwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
        enabled = enabled,
    )
}

@Composable
fun ArrowPreference(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    MiuixArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
        enabled = enabled,
        startAction = startAction,
    )
}

@Composable
fun RadioButtonPreference(
    title: String,
    summary: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    startAction: (@Composable () -> Unit)? = null,
) {
    MiuixRadioButtonPreference(
        title = title,
        summary = summary,
        selected = selected,
        onClick = onClick,
        startAction = startAction,
    )
}

@Composable
fun CheckboxPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    MiuixCheckboxPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

@Composable
fun OverlayDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
) {
    MiuixOverlayDropdownPreference(
        title = title,
        items = items,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = onSelectedIndexChange,
    )
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

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
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

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    MiuixTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
    )
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
    MiuixTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
    )
}

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    MiuixIcon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    MiuixIcon(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    MiuixIconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
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
    MiuixWindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}


// ------------------------------------------------------------------------- 取色器

@Composable
fun ColorPicker(
    color: Color,
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixColorPicker(
        color = color,
        onColorChanged = onColorChanged,
        modifier = modifier,
    )
}

package io.github.skyshadowhero.fancypad.ui.ds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 界面外观（设计语言）。模块自己的设置界面有两套皮：
 *
 * - [Miuix]：HyperOS / Miuix，模块原本的界面，默认值；
 * - [Glasmio]：iOS 27 Liquid Glass（https://github.com/NEORUAA/Glasmio）。
 *
 * 选中的值存在 `PrefKeys.UI_THEME` 里，切换后整界面即时重绘（见 `ui/Theme.kt` 的 AppTheme）。
 */
enum class UiStyle(val label: String) {
    Miuix("Miuix"),
    Glasmio("Glasmio"),
    ;

    companion object {
        fun of(index: Int): UiStyle = entries.getOrNull(index) ?: Miuix
    }
}

/** 当前外观；所有页面读 [isGlasmio] 决定用哪套组件。 */
val LocalUiStyle = compositionLocalOf { UiStyle.Miuix }

val isGlasmio: Boolean
    @Composable @ReadOnlyComposable get() = LocalUiStyle.current == UiStyle.Glasmio

/**
 * 页面里直接读的语义色。两套皮各给一份（Miuix 取自 MiuixTheme，Glasmio 取自 GlassColors），
 * 这样页面代码不用关心当前是哪套皮。
 */
@Immutable
data class DsColors(
    val surface: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val surfaceContainerHigh: Color,
    val onSurfaceContainerHigh: Color,
    val accent: Color,
    val separator: Color,
    val isDark: Boolean,
)

/** 排版 token。只放了页面真正用到的几档。 */
@Immutable
data class DsTextStyles(
    val body1: TextStyle,
    val body2: TextStyle,
    val footnote1: TextStyle,
    val headline: TextStyle,
    val subheadline: TextStyle,
)

val LocalDsColors = staticCompositionLocalOf<DsColors> {
    error("DsColors 未提供：页面必须在 AppTheme 之内")
}
val LocalDsTextStyles = staticCompositionLocalOf<DsTextStyles> {
    error("DsTextStyles 未提供：页面必须在 AppTheme 之内")
}

object DsTheme {
    val colorScheme: DsColors
        @Composable @ReadOnlyComposable get() = LocalDsColors.current

    val textStyles: DsTextStyles
        @Composable @ReadOnlyComposable get() = LocalDsTextStyles.current
}

/** 与 Miuix `BasicComponentDefaults.InsideMargin` 对齐的内边距（裸内容放进卡片时用）。 */
object DsDimens {
    val insideMargin = androidx.compose.ui.unit.Dp(16f)
}

package io.github.skyshadowhero.fancypad.ui.ds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 页面里直接读的语义色（取自 MiuixTheme）：页面代码不用关心主题实现。
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

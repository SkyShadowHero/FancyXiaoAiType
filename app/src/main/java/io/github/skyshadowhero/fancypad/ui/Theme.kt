package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.skyshadowhero.fancypad.ui.ds.GlasmioBackdropHost
import io.github.skyshadowhero.fancypad.ui.ds.rememberDsIndication
import io.github.skyshadowhero.fancypad.ui.ds.DsColors
import io.github.skyshadowhero.fancypad.ui.ds.DsTextStyles
import io.github.skyshadowhero.fancypad.ui.ds.LocalDsColors
import io.github.skyshadowhero.fancypad.ui.ds.LocalDsTextStyles
import io.github.skyshadowhero.fancypad.ui.ds.LocalUiStyle
import io.github.skyshadowhero.fancypad.ui.ds.UiStyle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题层：对齐 miuix 官方 example 的 `example/.../ui/Theme.kt` 结构 ——
 * 由调用方持有 ThemeController 的配置输入，这里负责构造 Controller 并包一层 MiuixTheme。
 *
 * 现在有两套外观（[UiStyle]）：Miuix 走 MiuixTheme，Glasmio 走 GlasmioTheme；
 * 两边都顺手把页面用的语义 token（[DsColors] / [DsTextStyles]）提供下去，
 * 页面因此只写一套代码。
 */
val LocalColorMode = compositionLocalOf { 0 }

enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
    MonetSystem("莫奈·跟随系统"),
    MonetLight("莫奈·浅色"),
    MonetDark("莫奈·深色"),
}

@Composable
fun AppTheme(
    uiStyle: UiStyle = UiStyle.Miuix,
    colorMode: Int = 0,
    keyColor: Color? = null,
    colorSpec: Int = 0,
    paletteStyle: Int = 0,
    content: @Composable () -> Unit,
) {
    if (uiStyle == UiStyle.Glasmio) {
        GlasmioAppearance(colorMode = colorMode, content = content)
    } else {
        MiuixAppearance(
            colorMode = colorMode,
            keyColor = keyColor,
            colorSpec = colorSpec,
            paletteStyle = paletteStyle,
            content = content,
        )
    }
}

/** 深色与否：显式选过就按选择，否则跟随系统。两套皮共用同一套判断。 */
@Composable
private fun resolveDark(colorMode: Int): Boolean = when (colorMode) {
    1, 4 -> false
    2, 5 -> true
    else -> isSystemInDarkTheme()
}

@Composable
private fun GlasmioAppearance(colorMode: Int, content: @Composable () -> Unit) {
    val dark = resolveDark(colorMode)
    GlasmioTheme(isDark = dark) {
        // Glasmio 的玻璃控件必须由一个 backdrop 宿主提供采样源，否则
        // `LocalBlurBackdrop` / `LocalGlassBackdrop` 的默认值会直接抛
        // 「Blur glass must be hosted by the app backdrop provider」；
        // 而给空 backdrop 又会让折射采到黑色。见 [GlasmioBackdropHost]。
        GlasmioBackdropHost {
            val colors = GlasmioTheme.colors
            val text = GlasmioTheme.textStyles
            // 干掉 Material 的水波纹：Glasmio 的行与控件都用这一层淡淡的按压遮罩。
            val indication = rememberDsIndication(colors.content)
            CompositionLocalProvider(
                LocalUiStyle provides UiStyle.Glasmio,
                LocalColorMode provides colorMode,
                LocalIndication provides indication,
                LocalDsColors provides DsColors(
                    surface = colors.groupedBackground,
                    onBackground = colors.content,
                    onBackgroundVariant = colors.secondaryContent,
                    surfaceContainerHigh = colors.elevatedBackground,
                    onSurfaceContainerHigh = colors.content,
                    accent = colors.accent,
                    separator = colors.separator,
                    isDark = colors.isDark,
                ),
                LocalDsTextStyles provides DsTextStyles(
                    body1 = text.body,
                    body2 = text.subheadline,
                    footnote1 = text.footnote,
                    headline = text.headline,
                    subheadline = text.subheadline,
                ),
                content = content,
            )
        }
    }
}

@Composable
private fun MiuixAppearance(
    colorMode: Int,
    keyColor: Color?,
    colorSpec: Int,
    paletteStyle: Int,
    content: @Composable () -> Unit,
) {
    val spec = ThemeColorSpec.entries.getOrNull(colorSpec) ?: ThemeColorSpec.Spec2021
    val style = ThemePaletteStyle.entries.getOrNull(paletteStyle) ?: ThemePaletteStyle.Content
    val controller = remember(colorMode, keyColor, spec, style) {
        when (colorMode) {
            1 -> ThemeController(ColorSchemeMode.Light)
            2 -> ThemeController(ColorSchemeMode.Dark)
            3 -> ThemeController(
                ColorSchemeMode.MonetSystem,
                keyColor = keyColor,
                colorSpec = spec,
                paletteStyle = style
            )
            4 -> ThemeController(
                ColorSchemeMode.MonetLight,
                keyColor = keyColor,
                colorSpec = spec,
                paletteStyle = style
            )
            5 -> ThemeController(
                ColorSchemeMode.MonetDark,
                keyColor = keyColor,
                colorSpec = spec,
                paletteStyle = style
            )
            else -> ThemeController(ColorSchemeMode.System)
        }
    }
    MiuixTheme(controller = controller) {
        val colors = MiuixTheme.colorScheme
        val text = MiuixTheme.textStyles
        CompositionLocalProvider(
            LocalUiStyle provides UiStyle.Miuix,
            LocalColorMode provides colorMode,
            LocalDsColors provides DsColors(
                surface = colors.surface,
                onBackground = colors.onBackground,
                onBackgroundVariant = colors.onBackgroundVariant,
                surfaceContainerHigh = colors.surfaceContainerHigh,
                onSurfaceContainerHigh = colors.onSurfaceContainerHigh,
                accent = colors.primary,
                separator = colors.dividerLine,
                isDark = isInDarkTheme(),
            ),
            LocalDsTextStyles provides DsTextStyles(
                body1 = text.body1,
                body2 = text.body2,
                footnote1 = text.footnote1,
                headline = text.headline1,
                subheadline = text.body2,
            ),
            content = content,
        )
    }
}

@Composable
fun isInDarkTheme(): Boolean = when (LocalColorMode.current) {
    1, 4 -> false
    2, 5 -> true
    else -> isSystemInDarkTheme()
}

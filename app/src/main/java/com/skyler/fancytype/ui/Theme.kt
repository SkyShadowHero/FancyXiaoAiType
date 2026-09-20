package com.skyler.fancytype.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题层：对齐 miuix 官方 example 的 `example/.../ui/Theme.kt` 结构 ——
 * 由调用方持有 ThemeController 的配置输入，这里负责构造 Controller 并包一层 MiuixTheme。
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
    colorMode: Int = 0,
    keyColor: Color? = null,
    colorSpec: Int = 0,
    paletteStyle: Int = 0,
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
    CompositionLocalProvider(LocalColorMode provides colorMode) {
        MiuixTheme(controller = controller, content = content)
    }
}

@Composable
fun isInDarkTheme(): Boolean = when (LocalColorMode.current) {
    1, 4 -> false
    2, 5 -> true
    else -> isSystemInDarkTheme()
}

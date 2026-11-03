package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.ui.ds.ArrowPreference
import io.github.skyshadowhero.fancypad.ui.ds.Card
import io.github.skyshadowhero.fancypad.ui.ds.DsTheme
import io.github.skyshadowhero.fancypad.ui.ds.OverlayDropdownPreference
import io.github.skyshadowhero.fancypad.ui.ds.RadioButtonPreference
import io.github.skyshadowhero.fancypad.ui.ds.SmallTitle
import io.github.skyshadowhero.fancypad.ui.ds.Text
import io.github.skyshadowhero.fancypad.ui.ds.UiStyle
import io.github.skyshadowhero.fancypad.ui.ds.isGlasmio

/** Glasmio 项目主页：主题说明里给个入口，点一下能在浏览器里看它到底是什么。 */


/**
 * 「主题设置」：模块自己的设置界面用哪套外观。
 *
 * 只有两件事：
 * 1. **界面主题** —— Miuix（HyperOS 风，默认）⇄ Glasmio（iOS 27 液态玻璃风）。
 *    选完立刻生效并落盘：整棵界面树在 AppTheme 里换壳，页面上的组件跟着换一套渲染。
 * 2. **明暗模式** —— 从「关于」页挪过来，两套外观共用这套明暗选择（含莫奈取色）。
 *
 * 注意作用范围：这一页只改**本模块的设置界面**，不碰 Hook 侧任何行为，
 * 也不会影响被 Hook 的应用（输入法 / SystemUI / 系统）长什么样。
 */
@Composable
fun ThemeSettingsPage(
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
        item { SmallTitle("界面主题") }
        item {
            Card {
                UiStyle.entries.forEach { style ->
                    RadioButtonPreference(
                        title = style.label,
                        selected = uiState.uiTheme == style.ordinal,
                        onClick = {
                            if (uiState.uiTheme != style.ordinal) {
                                uiState.uiTheme = style.ordinal
                                uiState.save { editor ->
                                    editor.putInt(PrefKeys.UI_THEME, style.ordinal)
                                }
                            }
                        },
                    )
                }
            }
        }

        item { SmallTitle("外观模式") }
        item {
            Card {
                // 莫奈（Monet）是 Miuix 取色体系的说法：它靠 Miuix ThemeController 从壁纸里
                // 提取主题色。Glasmio 那边没有这套东西，所以 iOS 外观下只留系统/浅色/深色
                // 三个真正有意义的选项，免得选了「莫奈」却没有任何变化。
                val glasmio = isGlasmio
                val modes = if (glasmio) {
                    listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)
                } else {
                    ThemeMode.entries.toList()
                }
                val effective = when (uiState.themeMode) {
                    3 -> ThemeMode.System
                    4 -> ThemeMode.Light
                    5 -> ThemeMode.Dark
                    else -> ThemeMode.entries.getOrElse(uiState.themeMode) { ThemeMode.System }
                }
                OverlayDropdownPreference(
                    title = "主题模式",
                    items = modes.map { it.label },
                    selectedIndex = modes.indexOf(effective).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val mode = modes.getOrElse(index) { ThemeMode.System }
                        uiState.themeMode = mode.ordinal
                        uiState.save { editor -> editor.putInt(PrefKeys.THEME_MODE, mode.ordinal) }
                    },
                )
            }
        }

    }
}

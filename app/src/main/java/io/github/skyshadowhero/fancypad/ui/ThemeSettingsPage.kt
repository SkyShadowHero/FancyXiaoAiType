package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.ui.ds.Card
import io.github.skyshadowhero.fancypad.ui.ds.OverlayDropdownPreference
import io.github.skyshadowhero.fancypad.ui.ds.SmallTitle

/**
 * 「主题设置」：模块自己的设置界面的明暗模式。
 *
 * 只有一件事：明暗 / 莫奈取色模式（见 [ThemeMode]）。界面外观固定为 Miuix，
 * 不再提供皮肤切换。
 *
 * 作用范围：只改本模块的设置界面，不影响 Hook 侧，也不会影响被 Hook 的应用
 *（输入法 / SystemUI / 系统）长什么样。
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
        item { SmallTitle("外观模式") }
        item {
            Card {
                val modes = ThemeMode.entries.toList()
                OverlayDropdownPreference(
                    title = "主题模式",
                    items = modes.map { it.label },
                    selectedIndex = uiState.themeMode.coerceIn(0, modes.lastIndex),
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

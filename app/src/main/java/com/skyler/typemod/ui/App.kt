package com.skyler.typemod.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.skyler.typemod.PrefKeys
import com.skyler.typemod.RemoteConfig
import kotlinx.coroutines.delay

/**
 * 顶层 UI 状态：跨页面共享（配置值 + 当前页）。
 */
class AppUiState {
    var page by mutableIntStateOf(0)
    var themeMode by mutableIntStateOf(0)

    var gapEnabled by mutableStateOf(true)
    var gapLand by mutableStateOf(284f)
    var gapPort by mutableStateOf(113f)
    var portraitForceNormal by mutableStateOf(false)

    var cornerEnabled by mutableStateOf(false)
    var cornerDp by mutableStateOf(8f)

    var spaceEnabled by mutableStateOf(false)
    var spaceKeyHLand by mutableStateOf(51.5f)
    var spaceKeyHPort by mutableStateOf(51.5f)
    var spaceKeyHorizLand by mutableStateOf(8f)
    var spaceKeyHorizPort by mutableStateOf(8f)
    var spaceRowLand by mutableStateOf(10f)
    var spaceRowPort by mutableStateOf(10f)

    /**
     * 是否已从远端把配置读进来。
     * 未装载完成前所有写入都会被 [writePrefs] 丢弃 —— 这是「设置没有记忆」的根因：
     * 之前页面先渲染、配置后装载，界面把默认值写回，覆盖了已保存的设置。
     */
    var loaded by mutableStateOf(false)

    /**
     * 统一保存入口：装载完成前直接丢弃写入。
     * 这样即使界面先渲染（显示默认值），也不会把默认值写回、覆盖已保存的设置。
     * @return 是否真的写入
     */
    fun save(block: (android.content.SharedPreferences.Editor) -> Unit): Boolean =
        if (loaded) RemoteConfig.edit(block) else false
}

/**
 * 应用根：主题 + 导航事件宿主 + 后台装载配置 + 页面外壳。
 *
 * 页面立即渲染（不插加载页），配置在后台读入；装载前禁止写入，因此不会覆盖已保存设置。
 * 必须提供 `LocalNavigationEventDispatcherOwner`：Miuix 弹层内部注册 NavigationBackHandler，
 * 缺少宿主会抛异常。作为根组件需显式传 parent = null。
 */
@Composable
fun App(padding: PaddingValues = PaddingValues(0.dp)) {
    val uiState = remember { AppUiState() }
    val navigationEventOwner = rememberNavigationEventDispatcherOwner(parent = null)

    LaunchedEffect(Unit) {
        // 等框架完成 Binder 绑定（通常很快），再读配置
        var waited = 0
        while (!RemoteConfig.isReady && waited < 5000) {
            delay(100)
            waited += 100
        }
        loadConfigInto(uiState)
        // 即便服务没就绪也要置位：否则用户永远改不了设置
        uiState.loaded = true
    }

    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides navigationEventOwner,
    ) {
        AppTheme(colorMode = uiState.themeMode) {
            AppShell(uiState = uiState, padding = padding)
        }
    }
}

/** 一次性把远端配置读进 UI 状态。逐键 runCatching，单键异常不影响整体。 */
private fun loadConfigInto(uiState: AppUiState) {
    val p = RemoteConfig.prefs() ?: return
    uiState.gapEnabled = p.runCatching { getBoolean(PrefKeys.GAP_ENABLED, true) }.getOrDefault(true)
    uiState.gapLand = p.runCatching { getFloat(PrefKeys.GAP_LAND, 0f) }.getOrDefault(0f)
        .takeIf { it > 0f } ?: PrefKeys.GAP_DEFAULT_LAND
    uiState.gapPort = p.runCatching { getFloat(PrefKeys.GAP_PORT, 0f) }.getOrDefault(0f)
        .takeIf { it > 0f } ?: PrefKeys.GAP_DEFAULT_PORT
    uiState.portraitForceNormal = p.runCatching { getBoolean(PrefKeys.PORTRAIT_FORCE_NORMAL, false) }
        .getOrDefault(false)

    uiState.cornerEnabled = p.runCatching { getBoolean(PrefKeys.CORNER_ENABLED, false) }.getOrDefault(false)
    uiState.cornerDp = p.runCatching { getFloat(PrefKeys.CORNER_DP, 0f) }.getOrDefault(0f)
        .takeIf { it > 0f } ?: PrefKeys.CORNER_DEFAULT

    uiState.spaceEnabled = p.runCatching { getBoolean(PrefKeys.SPACE_ENABLED, false) }.getOrDefault(false)
    uiState.spaceKeyHLand = p.runCatching { getFloat(PrefKeys.SPACE_KEY_H_LAND, PrefKeys.SPACE_KEY_H_LAND_DEFAULT) }
        .getOrDefault(PrefKeys.SPACE_KEY_H_LAND_DEFAULT)
    uiState.spaceKeyHPort = p.runCatching { getFloat(PrefKeys.SPACE_KEY_H_PORT, PrefKeys.SPACE_KEY_H_PORT_DEFAULT) }
        .getOrDefault(PrefKeys.SPACE_KEY_H_PORT_DEFAULT)
    uiState.spaceKeyHorizLand = p.runCatching {
        getFloat(PrefKeys.SPACE_KEY_HORIZ_LAND, PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT)
    }.getOrDefault(PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT)
    uiState.spaceKeyHorizPort = p.runCatching {
        getFloat(PrefKeys.SPACE_KEY_HORIZ_PORT, PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT)
    }.getOrDefault(PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT)
    uiState.spaceRowLand = p.runCatching { getFloat(PrefKeys.SPACE_ROW_LAND, PrefKeys.SPACE_ROW_LAND_DEFAULT) }
        .getOrDefault(PrefKeys.SPACE_ROW_LAND_DEFAULT)
    uiState.spaceRowPort = p.runCatching { getFloat(PrefKeys.SPACE_ROW_PORT, PrefKeys.SPACE_ROW_PORT_DEFAULT) }
        .getOrDefault(PrefKeys.SPACE_ROW_PORT_DEFAULT)

    uiState.themeMode = p.runCatching { getInt(PrefKeys.THEME_MODE, 0) }.getOrDefault(0)
        .coerceIn(0, ThemeMode.entries.size - 1)
}

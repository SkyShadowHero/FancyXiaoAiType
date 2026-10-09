package io.github.skyshadowhero.fancypad.ui

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
import io.github.skyshadowhero.fancypad.L
import io.github.skyshadowhero.fancypad.MaterialPackages
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.RemoteConfig
import kotlinx.coroutines.delay

/**
 * 顶层 UI 状态：跨页面共享（配置值；导航状态由 miuix-nav 的路由栈持有）。
 */
class AppUiState {
    var themeMode by mutableIntStateOf(0)

    var gapEnabled by mutableStateOf(false)
    var gapLand by mutableStateOf(284f)
    var gapPort by mutableStateOf(113f)
    var portraitForceNormal by mutableStateOf(false)

    var cornerEnabled by mutableStateOf(false)
    var cornerDp by mutableStateOf(8f)
    var bubbleCornerDp by mutableStateOf(PrefKeys.BUBBLE_CORNER_DEFAULT)

    // ---- 键盘外边距 ----
    var marginEnabled by mutableStateOf(false)
    var marginHorizontalDp by mutableStateOf(PrefKeys.MARGIN_HORIZONTAL_DEFAULT)
    var marginBottomDp by mutableStateOf(PrefKeys.MARGIN_BOTTOM_DEFAULT)

    var spaceEnabled by mutableStateOf(false)
    var spaceKeyHLand by mutableStateOf(51.5f)
    var spaceKeyHPort by mutableStateOf(51.5f)
    var spaceKeyHorizLand by mutableStateOf(8f)
    var spaceKeyHorizPort by mutableStateOf(8f)
    var spaceRowLand by mutableStateOf(10f)
    var spaceRowPort by mutableStateOf(10f)

    // ---- 超级材质 ----
    var materialEnabled by mutableStateOf(false)
    var materialForceAll by mutableStateOf(false)
    var materialPackages by mutableStateOf<Set<String>>(emptySet())

    // ---- 悬浮候选词窗口 ----
    var candidateEnabled by mutableStateOf(false)
    var candidateCornerDp by mutableStateOf(PrefKeys.CANDIDATE_CORNER_DEFAULT)
    var candidateSpacingDp by mutableStateOf(PrefKeys.CANDIDATE_SPACING_DEFAULT)

    // ---- 悬浮键盘：圆角（工具栏与候选窗口共用）----
    var floatBarEnabled by mutableStateOf(false)
    var floatBarCornerDp by mutableStateOf(PrefKeys.FLOATBAR_CORNER_DEFAULT)

    // ---- 悬浮键盘：工具栏 ----
    var toolbarShadowDp by mutableStateOf(PrefKeys.TOOLBAR_SHADOW_DEFAULT)
    var toolbarButtonSpacingDp by mutableStateOf(PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT)
    var toolbarVPaddingDp by mutableStateOf(PrefKeys.TOOLBAR_VPADDING_DEFAULT)
    var toolbarPaddingStartDp by mutableStateOf(PrefKeys.TOOLBAR_PADDING_START_DEFAULT)
    var toolbarPaddingEndDp by mutableStateOf(PrefKeys.TOOLBAR_PADDING_END_DEFAULT)
    var toolbarHandleOffsetStartDp by mutableStateOf(PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT)

    // ---- 悬浮键盘：候选窗口 ----
    var candWinMaxWidthDp by mutableStateOf(PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT)
    var candWinHPaddingDp by mutableStateOf(PrefKeys.CAND_WIN_H_PADDING_DEFAULT)
    var candWinPinyinTopDp by mutableStateOf(PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT)
    var candWinPinyinBottomDp by mutableStateOf(PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT)
    var candWinShadowDp by mutableStateOf(PrefKeys.CAND_WIN_SHADOW_DEFAULT)
    var candWinSpacingDp by mutableStateOf(PrefKeys.CAND_WIN_SPACING_DEFAULT)
    var candWinRowPaddingDp by mutableStateOf(PrefKeys.CAND_WIN_ROW_PADDING_DEFAULT)
    var candWinCandFontDp by mutableStateOf(PrefKeys.CAND_WIN_CAND_FONT_DEFAULT)

    // ---- 悬浮候选窗口：字号覆盖（独立开关）----
    var candFontEnabled by mutableStateOf(false)
    var candWinNumberFontDp by mutableStateOf(PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT)
    var candWinPinyinFontDp by mutableStateOf(PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT)

    // ---- 悬浮键盘：描边宽度 ----
    var toolbarBorderWidthDp by mutableStateOf(PrefKeys.BORDER_WIDTH_DEFAULT)
    var candWinBorderWidthDp by mutableStateOf(PrefKeys.BORDER_WIDTH_DEFAULT)

    // ---- 悬浮键盘：解锁最大尺寸 ----
    var floatKbUnlock by mutableStateOf(false)
    var floatKbMaxScale by mutableStateOf(PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT)

    // ---- 光标主题（原 os4光标主题模块，作用域 system）----
    /** 接管系统光标总开关；关掉则 Hook 侧完全走系统原实现。 */
    var cursorEnabled by mutableStateOf(false)

    // ---- 平行窗口动画（原 os4平行窗口动画fix模块，作用域 com.android.systemui）----
    var embeddingEnabled by mutableStateOf(false)
    var embeddingFolmeDisable by mutableStateOf(true)
    var embeddingMergeDisable by mutableStateOf(true)
    var embeddingJumpCutDisable by mutableStateOf(true)

    // ---- 小窗控制器（作用域 com.android.systemui）----
    /** 小窗控制器总开关（默认开）：关掉后这一域所有设置全部失效、回到系统原样。 */
    var captionEnabled by mutableStateOf(true)
    /** 把控制菜单里「关闭」按钮的 × 图标换成 −（只换图标，点击行为不变）。 */
    var captionCloseAsMinus by mutableStateOf(false)

    /** 在控制菜单里追加一个红色「彻底关闭」按钮（forceStop，进程不留在后台）。 */
    var captionForceClose by mutableStateOf(false)

    /** 控制菜单里隐藏的按钮（逗号分隔的 key）；空串 = 全部显示。 */
    var captionButtonHidden by mutableStateOf("")

    /** 控制菜单的按钮顺序（逗号分隔的 key）。 */
    var captionButtonOrder by mutableStateOf(PrefKeys.CAPTION_BUTTON_DEFAULT_ORDER)

    /** 隐藏「当前状态对应的按钮」：全屏藏「全屏」、小窗藏「小窗」。 */
    var captionHideCurrentState by mutableStateOf(false)

    /** 始终显示「新窗口」按钮（框架只在支持多实例时才加）。 */
    var captionAlwaysNewWindow by mutableStateOf(false)

    /** 隐藏三个控制点（不画，点击区域还在）。 */
    var captionHideDots by mutableStateOf(false)


    // ---- AOSP长按菜单（作用域 com.android.systemui，由 SystemUI 绘制）----
    var toolbarEnabled by mutableStateOf(false)
    var toolbarCornerDp by mutableStateOf(PrefKeys.TOOLBAR_CORNER_DEFAULT)
    var toolbarTextSp by mutableStateOf(PrefKeys.TOOLBAR_TEXT_DEFAULT)

    // ---- 右键菜单（作用域 = 目标应用自身；菜单由应用自己 PopupWindow 弹出）----
    var appMenuEnabled by mutableStateOf(false)
    var appMenuWebviewEnabled by mutableStateOf(true)
    var appMenuApps by mutableStateOf("")
    var appMenuCornerDp by mutableStateOf(PrefKeys.APPMENU_CORNER_DEFAULT)
    var appMenuTextSp by mutableStateOf(PrefKeys.APPMENU_TEXT_DEFAULT)
    var appMenuPaddingHDp by mutableStateOf(PrefKeys.APPMENU_PADDING_H_DEFAULT)
    var appMenuPaddingVDp by mutableStateOf(PrefKeys.APPMENU_PADDING_V_DEFAULT)

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
 * 服务绑定的缓冲时间。绑定在 Application.onCreate 里发起，通常进入 UI 时已经完成；
 * 这点缓冲只用于兜住偶发的慢绑定，超过就按「未连接」提示，之后一旦绑上会自动收起。
 */
private const val BIND_GRACE_MS = 1200L

/**
 * 应用根：主题 + 导航事件宿主 + 后台装载配置 + 页面外壳。
 *
 * 页面立即渲染（不插加载页），配置在后台读入；装载前禁止写入，因此不会覆盖已保存设置。
 * 未连上 LSPosed 时弹出警告窗口并禁用写入（否则改动的设置无处保存）。
 * 必须提供 `LocalNavigationEventDispatcherOwner`：Miuix 弹层内部注册 NavigationBackHandler，
 * 缺少宿主会抛异常。作为根组件需显式传 parent = null。
 */
@Composable
fun App(padding: PaddingValues = PaddingValues(0.dp)) {
    val uiState = remember { AppUiState() }
    val navigationEventOwner = rememberNavigationEventDispatcherOwner(parent = null)
    // 进入应用时先直接取当前状态：RemoteConfig.init() 在 Application.onCreate 里就已经
    // 发起绑定，通常到这一帧已经绑好了，那就不必等。
    var serviceReady by remember { mutableStateOf<Boolean?>(RemoteConfig.isReady) }
    // 供「重试」按钮触发重新绑定检测
    var retryTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryTick) {
        // 还没绑上时给一小段缓冲（绑定是异步的），但不再像以前那样盲等 15 秒才出结论
        if (!RemoteConfig.isReady) {
            var waited = 0
            while (!RemoteConfig.isReady && waited < BIND_GRACE_MS) {
                delay(100)
                waited += 100
            }
        }
        serviceReady = RemoteConfig.isReady
        if (RemoteConfig.isReady) {
            loadConfigInto(uiState)
            uiState.loaded = true
        }

        // 持续跟随：绑定可能稍后才完成，也可能中途断开（onServiceDied）。
        // 只读一个 volatile 标志，不走 binder，开销可忽略。
        while (true) {
            delay(400)
            val ready = RemoteConfig.isReady
            if (ready != serviceReady) {
                serviceReady = ready
                L.i("event=service_state_changed ready=$ready")
                if (ready) {
                    loadConfigInto(uiState)
                    uiState.loaded = true
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides navigationEventOwner,
    ) {
        AppTheme(colorMode = uiState.themeMode) {
            AppShell(
                uiState = uiState,
                padding = padding,
                serviceMissing = serviceReady == false,
                onRetryService = {
                    RemoteConfig.reset()
                    retryTick++
                },
            )
        }
    }
}

/** 一次性把远端配置读进 UI 状态。逐键 runCatching，单键异常不影响整体。 */
private fun loadConfigInto(uiState: AppUiState) {
    val p = RemoteConfig.prefs() ?: return
    uiState.gapEnabled = p.runCatching { getBoolean(PrefKeys.GAP_ENABLED, false) }.getOrDefault(false)
    uiState.gapLand = p.runCatching { getFloat(PrefKeys.GAP_LAND, 0f) }.getOrDefault(0f)
        .takeIf { it > 0f } ?: PrefKeys.GAP_DEFAULT_LAND
    uiState.gapPort = p.runCatching { getFloat(PrefKeys.GAP_PORT, 0f) }.getOrDefault(0f)
        .takeIf { it > 0f } ?: PrefKeys.GAP_DEFAULT_PORT
    uiState.portraitForceNormal = p.runCatching { getBoolean(PrefKeys.PORTRAIT_FORCE_NORMAL, false) }
        .getOrDefault(false)

    uiState.cornerEnabled = p.runCatching { getBoolean(PrefKeys.CORNER_ENABLED, false) }.getOrDefault(false)
    uiState.cornerDp = p.runCatching { getFloat(PrefKeys.CORNER_DP, 0f) }.getOrDefault(0f)
        .takeIf { it > 0f } ?: PrefKeys.CORNER_DEFAULT
    uiState.bubbleCornerDp = p.runCatching {
        getFloat(PrefKeys.BUBBLE_CORNER_DP, PrefKeys.BUBBLE_CORNER_DEFAULT)
    }.getOrDefault(PrefKeys.BUBBLE_CORNER_DEFAULT)
    uiState.marginEnabled = p.runCatching { getBoolean(PrefKeys.MARGIN_ENABLED, false) }.getOrDefault(false)
    uiState.marginHorizontalDp = p.runCatching {
        getFloat(PrefKeys.MARGIN_HORIZONTAL_DP, PrefKeys.MARGIN_HORIZONTAL_DEFAULT)
    }.getOrDefault(PrefKeys.MARGIN_HORIZONTAL_DEFAULT)
    uiState.marginBottomDp = p.runCatching {
        getFloat(PrefKeys.MARGIN_BOTTOM_DP, PrefKeys.MARGIN_BOTTOM_DEFAULT)
    }.getOrDefault(PrefKeys.MARGIN_BOTTOM_DEFAULT)

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

    uiState.materialEnabled = p.runCatching { getBoolean(PrefKeys.MATERIAL_ENABLED, false) }
        .getOrDefault(false)
    uiState.materialForceAll = p.runCatching { getBoolean(PrefKeys.MATERIAL_FORCE_ALL, false) }
        .getOrDefault(false)
    uiState.materialPackages = MaterialPackages.decode(
        p.runCatching { getString(PrefKeys.MATERIAL_PACKAGES, "") }.getOrDefault("")
    )

    // ---- 悬浮候选词窗口 ----
    uiState.candidateEnabled = p.runCatching { getBoolean(PrefKeys.CANDIDATE_ENABLED, false) }
        .getOrDefault(false)
    uiState.candidateCornerDp = p.runCatching {
        getFloat(PrefKeys.CANDIDATE_CORNER_DP, PrefKeys.CANDIDATE_CORNER_DEFAULT)
    }.getOrDefault(PrefKeys.CANDIDATE_CORNER_DEFAULT)
    uiState.candidateSpacingDp = p.runCatching {
        getFloat(PrefKeys.CANDIDATE_SPACING_DP, PrefKeys.CANDIDATE_SPACING_DEFAULT)
    }.getOrDefault(PrefKeys.CANDIDATE_SPACING_DEFAULT)

    // ---- 悬浮键盘：圆角（工具栏与候选窗口共用）----
    uiState.floatBarEnabled = p.runCatching { getBoolean(PrefKeys.FLOATBAR_ENABLED, false) }
        .getOrDefault(false)
    uiState.floatBarCornerDp = p.runCatching {
        getFloat(PrefKeys.FLOATBAR_CORNER_DP, PrefKeys.FLOATBAR_CORNER_DEFAULT)
    }.getOrDefault(PrefKeys.FLOATBAR_CORNER_DEFAULT)

    // ---- 悬浮键盘：工具栏 ----
    uiState.toolbarShadowDp = p.runCatching {
        getFloat(PrefKeys.TOOLBAR_SHADOW_DP, PrefKeys.TOOLBAR_SHADOW_DEFAULT)
    }.getOrDefault(PrefKeys.TOOLBAR_SHADOW_DEFAULT)
    uiState.toolbarButtonSpacingDp = p.runCatching {
        getFloat(PrefKeys.TOOLBAR_BUTTON_SPACING_DP, PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT)
    }.getOrDefault(PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT)
    uiState.toolbarVPaddingDp = p.runCatching {
        getFloat(PrefKeys.TOOLBAR_VPADDING_DP, PrefKeys.TOOLBAR_VPADDING_DEFAULT)
    }.getOrDefault(PrefKeys.TOOLBAR_VPADDING_DEFAULT)
    uiState.toolbarPaddingStartDp = p.runCatching {
        getFloat(PrefKeys.TOOLBAR_PADDING_START_DP, PrefKeys.TOOLBAR_PADDING_START_DEFAULT)
    }.getOrDefault(PrefKeys.TOOLBAR_PADDING_START_DEFAULT)
    uiState.toolbarPaddingEndDp = p.runCatching {
        getFloat(PrefKeys.TOOLBAR_PADDING_END_DP, PrefKeys.TOOLBAR_PADDING_END_DEFAULT)
    }.getOrDefault(PrefKeys.TOOLBAR_PADDING_END_DEFAULT)
    uiState.toolbarHandleOffsetStartDp = p.runCatching {
        getFloat(
            PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DP,
            PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT,
        )
    }.getOrDefault(PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT)

    // ---- 悬浮键盘：候选窗口 ----
    uiState.candWinMaxWidthDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_MAX_WIDTH_DP, PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT)
    uiState.candWinHPaddingDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_H_PADDING_DP, PrefKeys.CAND_WIN_H_PADDING_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_H_PADDING_DEFAULT)
    uiState.candWinPinyinTopDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_PINYIN_TOP_DP, PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT)
    uiState.candWinPinyinBottomDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_PINYIN_BOTTOM_DP, PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT)
    uiState.candWinShadowDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_SHADOW_DP, PrefKeys.CAND_WIN_SHADOW_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_SHADOW_DEFAULT)
    uiState.candWinSpacingDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_SPACING_DP, PrefKeys.CAND_WIN_SPACING_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_SPACING_DEFAULT)
    uiState.candWinRowPaddingDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_ROW_PADDING_DP, PrefKeys.CAND_WIN_ROW_PADDING_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_ROW_PADDING_DEFAULT)
    uiState.candWinCandFontDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_CAND_FONT_DP, PrefKeys.CAND_WIN_CAND_FONT_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_CAND_FONT_DEFAULT)

    // ---- 悬浮候选窗口：字号覆盖（独立开关）----
    uiState.candFontEnabled = p.runCatching { getBoolean(PrefKeys.CAND_FONT_ENABLED, false) }
        .getOrDefault(false)
    uiState.candWinNumberFontDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_NUMBER_FONT_DP, PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT)
    uiState.candWinPinyinFontDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_PINYIN_FONT_DP, PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT)
    }.getOrDefault(PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT)

    // ---- 悬浮键盘：描边宽度 ----
    uiState.toolbarBorderWidthDp = p.runCatching {
        getFloat(PrefKeys.TOOLBAR_BORDER_WIDTH_DP, PrefKeys.BORDER_WIDTH_DEFAULT)
    }.getOrDefault(PrefKeys.BORDER_WIDTH_DEFAULT)
    uiState.candWinBorderWidthDp = p.runCatching {
        getFloat(PrefKeys.CAND_WIN_BORDER_WIDTH_DP, PrefKeys.BORDER_WIDTH_DEFAULT)
    }.getOrDefault(PrefKeys.BORDER_WIDTH_DEFAULT)

    // ---- 悬浮键盘：解锁最大尺寸 ----
    uiState.floatKbUnlock = p.runCatching { getBoolean(PrefKeys.FLOAT_KB_UNLOCK, false) }
        .getOrDefault(false)
    uiState.floatKbMaxScale = p.runCatching {
        getFloat(PrefKeys.FLOAT_KB_MAX_SCALE, PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT)
    }.getOrDefault(PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT)

    // ---- 光标主题（默认关闭：所有功能都要用户显式打开）----
    uiState.cursorEnabled = p.runCatching { getBoolean(PrefKeys.CURSOR_ENABLED, false) }
        .getOrDefault(false)

    // ---- 平行窗口动画（总开关默认关闭；子项保持开启，打开总开关即生效）----
    uiState.embeddingEnabled = p.runCatching { getBoolean(PrefKeys.EMBEDDING_ENABLED, false) }
        .getOrDefault(false)
    uiState.embeddingFolmeDisable = p.runCatching {
        getBoolean(PrefKeys.EMBEDDING_FOLME_DISABLE, true)
    }.getOrDefault(true)
    uiState.embeddingMergeDisable = p.runCatching {
        getBoolean(PrefKeys.EMBEDDING_MERGE_DISABLE, true)
    }.getOrDefault(true)
    uiState.embeddingJumpCutDisable = p.runCatching {
        getBoolean(PrefKeys.EMBEDDING_JUMPCUT_DISABLE, true)
    }.getOrDefault(true)

    // ---- 小窗控制器（默认：总开关开、各功能关）----
    uiState.captionEnabled = p.runCatching {
        getBoolean(PrefKeys.CAPTION_ENABLED, true)
    }.getOrDefault(true)
    uiState.captionCloseAsMinus = p.runCatching {
        getBoolean(PrefKeys.CAPTION_CLOSE_AS_MINUS, false)
    }.getOrDefault(false)
    uiState.captionForceClose = p.runCatching {
        getBoolean(PrefKeys.CAPTION_FORCE_CLOSE, false)
    }.getOrDefault(false)
    uiState.captionButtonHidden = p.runCatching {
        getString(PrefKeys.CAPTION_BUTTON_HIDDEN, "")
    }.getOrNull().orEmpty()
    uiState.captionButtonOrder = p.runCatching {
        getString(PrefKeys.CAPTION_BUTTON_ORDER, PrefKeys.CAPTION_BUTTON_DEFAULT_ORDER)
    }.getOrNull().orEmpty().ifBlank { PrefKeys.CAPTION_BUTTON_DEFAULT_ORDER }
    uiState.captionHideCurrentState = p.runCatching {
        getBoolean(PrefKeys.CAPTION_HIDE_CURRENT_STATE, false)
    }.getOrDefault(false)
    uiState.captionAlwaysNewWindow = p.runCatching {
        getBoolean(PrefKeys.CAPTION_ALWAYS_NEW_WINDOW, false)
    }.getOrDefault(false)
    uiState.captionHideDots = p.runCatching {
        getBoolean(PrefKeys.CAPTION_HIDE_DOTS, false)
    }.getOrDefault(false)

    // ---- 文本选择菜单（默认关闭）----
    uiState.toolbarEnabled = p.runCatching { getBoolean(PrefKeys.TOOLBAR_ENABLED, false) }
        .getOrDefault(false)
    uiState.toolbarCornerDp = p.runCatching {
        getFloat(PrefKeys.TOOLBAR_CORNER_DP, PrefKeys.TOOLBAR_CORNER_DEFAULT)
    }.getOrDefault(PrefKeys.TOOLBAR_CORNER_DEFAULT)
    uiState.toolbarTextSp = p.runCatching {
        getFloat(PrefKeys.TOOLBAR_TEXT_SP, PrefKeys.TOOLBAR_TEXT_DEFAULT)
    }.getOrDefault(PrefKeys.TOOLBAR_TEXT_DEFAULT)

    // ---- 右键菜单（默认关闭）----
    uiState.appMenuEnabled = p.runCatching { getBoolean(PrefKeys.APPMENU_ENABLED, false) }
        .getOrDefault(false)
    uiState.appMenuWebviewEnabled = p.runCatching {
        getBoolean(PrefKeys.APPMENU_WEBVIEW_ENABLED, true)
    }.getOrDefault(true)
    uiState.appMenuApps = p.runCatching { getString(PrefKeys.APPMENU_APPS, "") }
        .getOrNull().orEmpty()
    uiState.appMenuCornerDp = p.runCatching {
        getFloat(PrefKeys.APPMENU_CORNER_DP, PrefKeys.APPMENU_CORNER_DEFAULT)
    }.getOrDefault(PrefKeys.APPMENU_CORNER_DEFAULT)
    uiState.appMenuTextSp = p.runCatching {
        getFloat(PrefKeys.APPMENU_TEXT_SP, PrefKeys.APPMENU_TEXT_DEFAULT)
    }.getOrDefault(PrefKeys.APPMENU_TEXT_DEFAULT)
    uiState.appMenuPaddingHDp = p.runCatching {
        getFloat(PrefKeys.APPMENU_PADDING_H_DP, PrefKeys.APPMENU_PADDING_H_DEFAULT)
    }.getOrDefault(PrefKeys.APPMENU_PADDING_H_DEFAULT)
    uiState.appMenuPaddingVDp = p.runCatching {
        getFloat(PrefKeys.APPMENU_PADDING_V_DP, PrefKeys.APPMENU_PADDING_V_DEFAULT)
    }.getOrDefault(PrefKeys.APPMENU_PADDING_V_DEFAULT)
}

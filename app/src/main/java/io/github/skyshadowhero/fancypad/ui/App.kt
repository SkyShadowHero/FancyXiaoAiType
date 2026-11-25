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
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.RemoteConfig
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import io.github.skyshadowhero.fancypad.ui.navigation.BottomBarStyle
import io.github.skyshadowhero.fancypad.ui.navigation.AppShell
import io.github.skyshadowhero.fancypad.ui.navigation.RouteHome
import io.github.skyshadowhero.fancypad.ui.theme.AppTheme
import io.github.skyshadowhero.fancypad.ui.theme.ThemeMode

/**
 * 顶层 UI 状态：跨页面共享（配置值；导航状态由 miuix-nav 的路由栈持有）。
 */
class AppUiState {
    var themeMode by mutableIntStateOf(0)
    var bottomBarStyle by mutableIntStateOf(PrefKeys.BOTTOM_BAR_STYLE_DEFAULT)

    var gapEnabled by mutableStateOf(false)
    var gapLand by mutableStateOf(284f)
    var gapPort by mutableStateOf(113f)

    var cornerEnabled by mutableStateOf(false)
    var cornerDp by mutableStateOf(8f)
    var bubbleCornerDp by mutableStateOf(PrefKeys.BUBBLE_CORNER_DEFAULT)

    // ---- 键盘外边距 ----
    var marginEnabled by mutableStateOf(false)
    var marginHorizontalDp by mutableStateOf(PrefKeys.MARGIN_HORIZONTAL_DEFAULT)

    var spaceEnabled by mutableStateOf(false)
    var spaceKeyHLand by mutableStateOf(51.5f)
    var spaceKeyHPort by mutableStateOf(51.5f)
    var spaceKeyHorizLand by mutableStateOf(8f)
    var spaceKeyHorizPort by mutableStateOf(8f)
    var spaceRowLand by mutableStateOf(10f)
    var spaceRowPort by mutableStateOf(10f)

    // ---- 悬浮候选词窗口 ----
    var candidateEnabled by mutableStateOf(false)
    var candidateCornerDp by mutableStateOf(PrefKeys.CANDIDATE_CORNER_DEFAULT)
    var candidateSpacingDp by mutableStateOf(PrefKeys.CANDIDATE_SPACING_DEFAULT)

    // ---- 悬浮键盘：圆角（工具栏与候选窗口共用）----
    var floatBarEnabled by mutableStateOf(false)
    var floatBarCornerDp by mutableStateOf(PrefKeys.FLOATBAR_CORNER_DEFAULT)
    var floatBarUnifiedShadow by mutableStateOf(false)

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
    var embeddingFolmeDisable by mutableStateOf(false)
    var embeddingMergeDisable by mutableStateOf(false)
    var embeddingJumpCutDisable by mutableStateOf(false)

    // ---- 平行视界分栏无级（作用域各目标 App，与右键菜单一致）----
    var embeddingRatioEnabled by mutableStateOf(false)
    var embeddingRatioApps by mutableStateOf("")
    var embeddingRatioMin by mutableStateOf(0.35f)
    var embeddingRatioMax by mutableStateOf(0.65f)

    // ---- 一镜到底（HyperViewScale，作用域 system + 各目标 App）----
    var hyperViewScaleEnabled by mutableStateOf(false)

    // ---- 小窗控制器（作用域 com.android.systemui）----
    /** 小窗控制器总开关；关掉后这一域所有设置全部失效、回到系统原样。 */
    var captionEnabled by mutableStateOf(false)
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


    // ---- 右键菜单（作用域 = 目标应用自身；菜单由应用自己 PopupWindow 弹出）----
    var appMenuEnabled by mutableStateOf(false)
    var appMenuWebviewEnabled by mutableStateOf(false)
    var appMenuApps by mutableStateOf("")
    var appMenuCornerDp by mutableStateOf(PrefKeys.APPMENU_CORNER_DEFAULT)
    var appMenuTextSp by mutableStateOf(PrefKeys.APPMENU_TEXT_DEFAULT)
    var appMenuPaddingHDp by mutableStateOf(PrefKeys.APPMENU_PADDING_H_DEFAULT)
    var appMenuPaddingVDp by mutableStateOf(PrefKeys.APPMENU_PADDING_V_DEFAULT)

    // ---- 随手写（触控笔手写，作用域 com.xiaomi.type）----
    // 「随手写」= AOSP Android 14+ 的触控笔手写：笔直接在输入框上写字、笔迹转文字上屏，
    // 跟输入法里那个「手写键盘」不是一件事。小爱缺这条线的原因很具体：它的
    // res/xml/method.xml 里没有 android:supportsStylusHandwriting="true"，
    // 系统因此从不把手写会话交给它。
    var stylusEnabled by mutableStateOf(false)
    /** 应用侧 WebView / 自绘编辑器兼容兜底白名单。 */
    var stylusApps by mutableStateOf(PrefKeys.STYLUS_APPS_DEFAULT)
    var stylusToolbarEnabled by mutableStateOf(false)
    var stylusDelayMs by mutableStateOf(PrefKeys.STYLUS_DELAY_DEFAULT)

    // ---- 笔迹显示（画布挂在框架的手写窗口里，满宽一条横带、跟着落笔点走）----
    var stylusInkEnabled by mutableStateOf(false)
    var stylusInkColor by mutableIntStateOf(PrefKeys.STYLUS_INK_COLOR_DEFAULT)
    var stylusInkWidthPx by mutableStateOf(PrefKeys.STYLUS_INK_WIDTH_DEFAULT)
    var stylusGestureEnabled by mutableStateOf(false)

    // 系统侧配合（system_server 进程，作用域 system）。
    var stylusWhitelist by mutableStateOf(false)



    /**
     * 是否已从远端把配置读进来。
     * 未装载完成前所有写入都会被 [writePrefs] 丢弃 —— 这是「设置没有记忆」的根因：
     * 之前页面先渲染、配置后装载，界面把默认值写回，覆盖了已保存的设置。
     */
    var loaded by mutableStateOf(false)

    /**
     * LSPosed 服务绑定状态：null = 还在等（启动头一两百毫秒），true/false = 已有结论。
     * 首页卡片靠它区分「正在连接」和「没激活」，避免每次进入都红一下再变绿。
     */
    var serviceReady by mutableStateOf<Boolean?>(null)

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
    // ★ 必须用**宿主自己的**返回分发器（ComponentActivity 在 onCreate 里把它挂到 decorView 的
    //   ViewTree 上，`LocalNavigationEventDispatcherOwner` 会自动解析到它）。
    //   前提：androidx.activity ≥ 1.13.0 —— 只有它实现了 NavigationEventDispatcherOwner
    //   并用 DirectNavigationEventInput 把系统返回事件喂进 dispatcher。1.11.0 上 ambient 恒为
    //   null，会掉到下面的 fallback root，于是「按返回直接退出」（见 build.gradle.kts）。
    //
    //   早先这里是 `rememberNavigationEventDispatcherOwner(parent = null)`：按 libxposed 无关的
    //   navigationevent 文档，parent 显式为 null 会建一条**游离的 root dispatcher**，
    //   "you must use a NavigationEventInput to send it events. Otherwise, the dispatcher will be
    //   detached and will not receive events." —— 结果就是 NavDisplay 的返回处理永远收不到系统返回，
    //   事件落到 Activity 上直接把应用关掉（「无论在哪里返回都会退出」就是这么来的）。
    //
    //   只有宿主确实没提供时（非 ComponentActivity、或旧版 activity）才退回自建 root，
    //   此时返回手势不可用，但不至于崩。
    val ambientNavOwner = LocalNavigationEventDispatcherOwner.current
    val fallbackNavOwner = if (ambientNavOwner == null) {
        rememberNavigationEventDispatcherOwner(parent = null)
    } else {
        null
    }
    // 进入应用时先直接取当前状态：RemoteConfig.init() 在 Application.onCreate 里就已经
    // 发起绑定，通常到这一帧已经绑好了，那就不必等。
    // 三态：null = 还在判定（服务可能下一帧才绑上），true/false = 已确定。
    // 早先这里直接塞 RemoteConfig.isReady（false），于是首帧就是"未激活红"，
    // 等绑定完成再变绿 —— 每次进首页都闪一下红。
    var serviceReady by remember {
        mutableStateOf<Boolean?>(if (RemoteConfig.isReady) true else null)
    }
    LaunchedEffect(Unit) {
        // 还没绑上时给一小段缓冲（绑定是异步的），但不再像以前那样盲等 15 秒才出结论
        if (!RemoteConfig.isReady) {
            var waited = 0
            while (!RemoteConfig.isReady && waited < BIND_GRACE_MS) {
                delay(100)
                waited += 100
            }
        }
        serviceReady = RemoteConfig.isReady
        uiState.serviceReady = serviceReady
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
                uiState.serviceReady = ready
                L.i("event=service_state_changed ready=$ready")
                if (ready) {
                    loadConfigInto(uiState)
                    uiState.loaded = true
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides (ambientNavOwner ?: fallbackNavOwner!!),
    ) {
        // 根路由是 RouteHome（首页/功能/信息三个标签在它内部），不再是功能列表。
        val backStack = remember { navBackStackOf(RouteHome) }
        AppTheme(colorMode = uiState.themeMode) {
            AppShell(
                uiState = uiState,
                backStack = backStack,
                padding = padding,
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
    uiState.bottomBarStyle = p.runCatching {
        getInt(PrefKeys.BOTTOM_BAR_STYLE, PrefKeys.BOTTOM_BAR_STYLE_DEFAULT)
    }.getOrDefault(PrefKeys.BOTTOM_BAR_STYLE_DEFAULT)
        .coerceIn(0, BottomBarStyle.entries.lastIndex)

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
    uiState.floatBarUnifiedShadow = p.runCatching {
        getBoolean(PrefKeys.FLOATBAR_UNIFIED_SHADOW, false)
    }.getOrDefault(false)

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

    // ---- 平行窗口动画（所有开关默认关闭）----
    uiState.embeddingEnabled = p.runCatching { getBoolean(PrefKeys.EMBEDDING_ENABLED, false) }
        .getOrDefault(false)
    uiState.embeddingFolmeDisable = p.runCatching {
        getBoolean(PrefKeys.EMBEDDING_FOLME_DISABLE, false)
    }.getOrDefault(false)
    uiState.embeddingMergeDisable = p.runCatching {
        getBoolean(PrefKeys.EMBEDDING_MERGE_DISABLE, false)
    }.getOrDefault(false)
    uiState.embeddingJumpCutDisable = p.runCatching {
        getBoolean(PrefKeys.EMBEDDING_JUMPCUT_DISABLE, false)
    }.getOrDefault(false)
    uiState.embeddingRatioEnabled = p.runCatching {
        getBoolean(PrefKeys.EMBEDDING_RATIO_ENABLED, false)
    }.getOrDefault(false)
    uiState.embeddingRatioApps = p.runCatching {
        getString(PrefKeys.EMBEDDING_RATIO_APPS, "")
    }.getOrNull().orEmpty()
    uiState.embeddingRatioMin = p.runCatching {
        getFloat(PrefKeys.EMBEDDING_RATIO_MIN, 0.35f)
    }.getOrDefault(0.35f)
    uiState.embeddingRatioMax = p.runCatching {
        getFloat(PrefKeys.EMBEDDING_RATIO_MAX, 0.65f)
    }.getOrDefault(0.65f)

    // ---- 一镜到底（默认关闭）----
    uiState.hyperViewScaleEnabled = p.runCatching {
        getBoolean(PrefKeys.HYPER_VIEW_SCALE_ENABLED, false)
    }.getOrDefault(false)

    // ---- 小窗控制器（所有功能开关默认关闭）----
    uiState.captionEnabled = p.runCatching {
        getBoolean(PrefKeys.CAPTION_ENABLED, false)
    }.getOrDefault(false)
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

    // ---- 右键菜单（默认关闭）----
    uiState.appMenuEnabled = p.runCatching { getBoolean(PrefKeys.APPMENU_ENABLED, false) }
        .getOrDefault(false)
    uiState.appMenuWebviewEnabled = p.runCatching {
        getBoolean(PrefKeys.APPMENU_WEBVIEW_ENABLED, false)
    }.getOrDefault(false)
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

    // ---- 随手写（默认关闭：实验功能，不改变输入法默认行为）----
    uiState.stylusEnabled = p.runCatching { getBoolean(PrefKeys.STYLUS_ENABLED, false) }
        .getOrDefault(false)
    uiState.stylusApps = p.runCatching {
        getString(PrefKeys.STYLUS_APPS, PrefKeys.STYLUS_APPS_DEFAULT)
    }.getOrNull() ?: PrefKeys.STYLUS_APPS_DEFAULT
    uiState.stylusToolbarEnabled = p.runCatching {
        getBoolean(PrefKeys.STYLUS_TOOLBAR_ENABLED, false)
    }.getOrDefault(false)
    uiState.stylusDelayMs = p.runCatching {
        getFloat(PrefKeys.STYLUS_DELAY_MS, PrefKeys.STYLUS_DELAY_DEFAULT)
    }.getOrDefault(PrefKeys.STYLUS_DELAY_DEFAULT)
    uiState.stylusWhitelist = p.runCatching { getBoolean(PrefKeys.STYLUS_WHITELIST, false) }
        .getOrDefault(false)

    // ---- 笔迹显示 ----
    uiState.stylusInkEnabled = p.runCatching { getBoolean(PrefKeys.STYLUS_INK_ENABLED, false) }
        .getOrDefault(false)
    uiState.stylusInkColor = p.runCatching {
        getInt(PrefKeys.STYLUS_INK_COLOR, PrefKeys.STYLUS_INK_COLOR_DEFAULT)
    }.getOrDefault(PrefKeys.STYLUS_INK_COLOR_DEFAULT)
    uiState.stylusInkWidthPx = p.runCatching {
        getFloat(PrefKeys.STYLUS_INK_WIDTH_PX, PrefKeys.STYLUS_INK_WIDTH_DEFAULT)
    }.getOrDefault(PrefKeys.STYLUS_INK_WIDTH_DEFAULT)
    uiState.stylusGestureEnabled = p.runCatching {
        getBoolean(PrefKeys.STYLUS_GESTURE_ENABLED, false)
    }.getOrDefault(false)
}

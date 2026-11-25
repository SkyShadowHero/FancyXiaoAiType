package io.github.skyshadowhero.fancypad.ui.navigation

import io.github.skyshadowhero.fancypad.ui.*
import io.github.skyshadowhero.fancypad.ui.components.*
import io.github.skyshadowhero.fancypad.ui.pages.*
import io.github.skyshadowhero.fancypad.ui.utils.*

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import io.github.skyshadowhero.fancypad.RemoteConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.skyshadowhero.fancypad.ui.utils.ImeRestarter
import io.github.skyshadowhero.fancypad.ui.state.CursorConnectionEffect
import io.github.skyshadowhero.fancypad.L
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.ui.ds.DsButtonDefaults
import io.github.skyshadowhero.fancypad.ui.ds.DsTheme
import io.github.skyshadowhero.fancypad.ui.ds.TextButton
import io.github.skyshadowhero.fancypad.ui.ds.WindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * 自适应外壳。全部导航走 miuix-nav（[NavDisplay] + [NavKey]）：作用域是路由栈的第一层，
 * 作用域里的功能页是第二层，光标域的颜色设置是第三层。
 *
 * 界面只有 Miuix 一套外观：顶栏渐进模糊 + 横屏左栏 / 竖屏底部菜单。
 *
 * - 一级页面（RouteHome 的三个标签）不显示多页导航；
 * - 进入多页作用域（输入法外观）后，在这几页之间切换 —— 同级切换只**替换**栈顶，
 *   不加深返回栈，返回键一次就回到作用域列表；
 * - 配置装载已在 App() 完成，这里只负责渲染与交互。
 */
@Composable
fun AppShell(
    uiState: AppUiState,
    backStack: SnapshotStateList<NavKey>,
    padding: PaddingValues,
) {
    val configuration = LocalConfiguration.current
    val useTabletNavigation = configuration.usesTabletAdaptiveNavigation()

    // 上限按「物理屏幕」算：LocalConfiguration 与 displayMetrics 在自由窗口下都是窗口尺寸
    val context = LocalContext.current
    val maxMetrics = remember(context) { maxWindowMetricsOrNull(context) }
    val fallbackMetrics = context.resources.displayMetrics
    val widthPx = (maxMetrics?.width ?: fallbackMetrics.widthPixels).toFloat()
    val heightPx = (maxMetrics?.height ?: fallbackMetrics.heightPixels).toFloat()
    val density = maxMetrics?.density ?: fallbackMetrics.density.let { if (it > 0f) it else 1f }
    val screenLongDp = maxOf(widthPx, heightPx) / density
    val screenShortDp = minOf(widthPx, heightPx) / density
    val gapMaxLand = (screenLongDp * PrefKeys.GAP_MAX_RATIO).coerceAtLeast(PrefKeys.GAP_MAX_FALLBACK)
    val gapMaxPort = (screenShortDp * PrefKeys.GAP_MAX_RATIO).coerceAtLeast(PrefKeys.GAP_MAX_FALLBACK)

    // ---------------------------------------------------------------- 路由

    fun push(key: NavKey) {
        if (key !in backStack) backStack.add(key)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    // 返回交给 NavDisplay 自己的预测返回（它注册的 onBack 就是 pop）。
    // 这里**不能再挂一个 BackHandler**：两个处理器同时存在时，一次返回会被分发两遍，
    // 实测表现为「先 pop 一层、紧接着 Activity 被 finish」——就是闪退回桌面的根因。


    // ---------------------------------------------------------------- 作用域状态
    // 3 秒轮询一次 getScope()（轻量 binder），首页卡片与「功能」页置灰都读它。
    var scopePackages by remember { mutableStateOf(RemoteConfig.scope()) }
    LaunchedEffect(Unit) {
        while (true) {
            scopePackages = RemoteConfig.scope()
            delay(3000)
        }
    }

    // ---------------------------------------------------------------- 标签页（miuix example 的用法）
    //
    // 顶层只有一个路由 RouteHome，三个标签是它内部的 HorizontalPager：
    //   · 底栏 NavigationBar 切换页，页可以左右滑
    //   · 返回：先回「首页」标签；已经在首页才交给系统
    //     （NavDisplay 在栈深 > 1 时自己 pop，栈深 == 1 时才轮到下面这个 handler）
    val pagerState = rememberPagerState(pageCount = { RootTab.entries.size })
    // 域内 Pager 提到路由宿主：平板 GlassNavigationRail 与页面内容直接共享同一状态，
    // 不再维护第二套“侧栏当前项”。固定枚举保证 Map 的键和 Pager 生命周期稳定。
    val domainPagerStates = remember {
        AppScope.entries.associateWith { appScope ->
            PagerState(pageCount = { appScope.pages.size })
        }
    }
    val tabScope = rememberCoroutineScope()
    fun selectRootTab(index: Int) {
        if (index !in RootTab.entries.indices) return
        while (backStack.size > 1) backStack.removeLastOrNull()
        if (index != pagerState.currentPage) {
            tabScope.launch { pagerState.animateScrollToPage(index, animationSpec = TabSwitchSpec) }
        }
    }
    val rootBackEnabled by remember {
        derivedStateOf { backStack.size == 1 && pagerState.currentPage != RootTab.Home.ordinal }
    }
    val rootNavState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = rootNavState,
        isBackEnabled = rootBackEnabled,
        onBackCompleted = {
            tabScope.launch { pagerState.animateScrollToPage(RootTab.Home.ordinal, animationSpec = TabSwitchSpec) }
        },
    )

    val current = backStack.lastOrNull() ?: RouteHome

    // ---- 尺寸过大风险提示（间隙 + 按键间距统一处理）----
    // 判定规则两者完全一致：任一项超过「该项上限的 6/10」即告警。
    // 间隙的上限来自屏幕宽度（越大越可能顶出屏幕），按键间距的上限来自各自滑块的量程。
    val oversized = buildList {
        if (uiState.gapEnabled) {
            add(SpaceWarning.Item("横屏中心间隙", uiState.gapLand, gapMaxLand))
            add(SpaceWarning.Item("竖屏中心间隙", uiState.gapPort, gapMaxPort))
        }
        if (uiState.spaceEnabled) {
            add(SpaceWarning.Item("横屏按键高度", uiState.spaceKeyHLand, PrefKeys.SPACE_KEY_H_MAX))
            add(SpaceWarning.Item("横屏键横向间距", uiState.spaceKeyHorizLand, PrefKeys.SPACE_MAX))
            add(SpaceWarning.Item("横屏行间距", uiState.spaceRowLand, PrefKeys.SPACE_MAX))
            add(SpaceWarning.Item("竖屏按键高度", uiState.spaceKeyHPort, PrefKeys.SPACE_KEY_H_MAX))
            add(SpaceWarning.Item("竖屏键横向间距", uiState.spaceKeyHorizPort, PrefKeys.SPACE_MAX))
            add(SpaceWarning.Item("竖屏行间距", uiState.spaceRowPort, PrefKeys.SPACE_MAX))
        }
    }.let { SpaceWarning.exceeded(it) }

    var sizeWarningDismissed by remember { mutableStateOf(false) }
    // 回到安全范围后复位，这样下次再调大还会提醒
    LaunchedEffect(oversized.isEmpty()) {
        if (oversized.isEmpty()) sizeWarningDismissed = false
    }

    // 尺寸过大：弹说明性对话框（不是一闪而过的提示），逐项列出超限值与安全阈值
    WindowDialog(
        show = oversized.isNotEmpty() && !sizeWarningDismissed,
        title = "⚠ 尺寸设置过大",
        summary = buildString {
            append("以下设置已超过安全范围（各自上限的 6/10）：\n\n")
            oversized.forEach {
                append("• ${it.label}：${it.value.toInt()}dp，建议 ≤ ${it.limit.toInt()}dp\n")
            }
            append("\n继续调大可能出现以下问题：\n")
            append("• 按键被挤出屏幕，部分按键无法点到\n")
            append("• 按键相互重叠，或按键文字被裁切\n")
            append("• 键盘高度异常，遮挡输入区域\n\n")
            append("建议回调到安全范围内。若布局已异常，可关闭对应的总开关立即还原。")
        },
        onDismissRequest = { },
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = "我知道了",
                    onClick = { sizeWarningDismissed = true },
                    modifier = Modifier.weight(1f),
                    colors = DsButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )

    // 光标域的框架连接跟随：拆成三个页面后只在外壳里跑一份轮询
    CursorConnectionEffect()

    // ---------------------------------------------------------- 重启输入法 + Snackbar

    // 重启提示与触发点只有一份，统一交给 Miuix 的 Snackbar 宿主。
    val miuixSnackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showRestartDialog by remember { mutableStateOf(false) }
    // 重启确认框从重启按钮的位置长出来：记录按钮在窗口里的矩形。
    var restartAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    fun startRestart() {
        L.i("event=restart_clicked")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ImeRestarter.forceStop() }
                    .getOrElse { ImeRestarter.Result.Failed(it.message ?: "未知错误") }
            }
            L.i("event=restart_result result=$result")
            when (result) {
                is ImeRestarter.Result.Killed -> {
                    val message = "已关闭输入法，下次弹出键盘时自动重启"
                    miuixSnackbar.showSnackbar(message, "重启成功")
                }

                is ImeRestarter.Result.Failed -> {
                    val message = "重启失败：需要 root 授权（KernelSU）"
                    miuixSnackbar.showSnackbar(message, "失败")
                }
            }
        }
    }

    // 重启确认弹窗
    WindowDialog(
        show = showRestartDialog,
        title = "重启超级小爱输入法？",
        onDismissRequest = { showRestartDialog = false },
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = "取消",
                    onClick = { showRestartDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "重启",
                    onClick = {
                        showRestartDialog = false
                        startRestart()
                    },
                    modifier = Modifier.weight(1f),
                    colors = DsButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )

    // 悬浮底栏要采样背后的内容 → 内容层单独注册进 backdrop，底栏作为**兄弟节点**叠在上面
    // （不能放进被注册的那一层，否则会采样到自己）。
    val barBackdrop = rememberTopBlurBackdrop()
    Box(modifier = Modifier.fillMaxSize()) {
        // 只有内容层进 backdrop；底栏是它的兄弟节点，采样内容、不会采样到自己
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!useTabletNavigation && barBackdrop != null) {
                        Modifier.layerBackdrop(barBackdrop)
                    } else {
                        Modifier
                    },
                ),
        ) {
            MiuixShell(
                uiState = uiState,
                backStack = backStack,
                pagerState = pagerState,
                domainPagerStates = domainPagerStates,
                scopePackages = scopePackages,
                showRootBottomBar = !useTabletNavigation,
                padding = padding,
                gapMaxLand = gapMaxLand,
                gapMaxPort = gapMaxPort,
                miuixSnackbar = miuixSnackbar,
                showRestartDialog = { showRestartDialog = true },
                onBack = { pop() },
                onPush = { push(it) },
            )
        }
    // 悬浮底栏：只在一级标签页出现，浮在内容之上一层（内容会从它下面滚过）。
    // 进出二级页面时跟着页面的转场一起滑出/滑入，而不是直接消失。
    androidx.compose.animation.AnimatedVisibility(
        visible = current == RouteHome && !useTabletNavigation,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = androidx.compose.animation.slideInVertically(
            animationSpec = androidx.compose.animation.core.tween(300),
            initialOffsetY = { it / 2 },
        ) + androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(220),
        ),
        exit = androidx.compose.animation.slideOutVertically(
            animationSpec = androidx.compose.animation.core.tween(260),
            targetOffsetY = { it },
        ) + androidx.compose.animation.fadeOut(
            animationSpec = androidx.compose.animation.core.tween(200),
        ),
    ) {
        // 用 targetPage：点击瞬间就指向目标页，胶囊立刻开始动；settledPage 会慢半拍。
        when (BottomBarStyle.entries.getOrElse(uiState.bottomBarStyle) { BottomBarStyle.Glass }) {
            BottomBarStyle.Glass -> GlassRootBottomBar(
                selectedIndex = pagerState.targetPage,
                onSelect = ::selectRootTab,
                backdrop = barBackdrop,
            )
            BottomBarStyle.Classic -> AppBottomBar(
                selectedIndex = pagerState.targetPage,
                onSelect = ::selectRootTab,
                modifier = Modifier,
                backdrop = barBackdrop,
            )
        }
    }
    }
}

/** Miuix 页面宿主；响应式导航由各自的 `NavDisplay` 路由条目持有。 */
@Composable
private fun MiuixShell(
    uiState: AppUiState,
    backStack: SnapshotStateList<NavKey>,
    pagerState: PagerState,
    domainPagerStates: Map<AppScope, PagerState>,
    scopePackages: Set<String>,
    showRootBottomBar: Boolean,
    padding: PaddingValues,
    gapMaxLand: Float,
    gapMaxPort: Float,
    miuixSnackbar: SnackbarHostState,
    showRestartDialog: () -> Unit,
    onBack: () -> Unit,
    onPush: (NavKey) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DsTheme.colorScheme.surface)
    ) {
        MiuixPageHost(
            uiState = uiState,
            backStack = backStack,
            pagerState = pagerState,
            domainPagerStates = domainPagerStates,
            scopePackages = scopePackages,
            gapMaxLand = gapMaxLand,
            gapMaxPort = gapMaxPort,
            padding = padding,
            miuixSnackbar = miuixSnackbar,
            showRestartDialog = showRestartDialog,
            onBack = onBack,
            onPush = onPush,
            showRootBottomBar = showRootBottomBar,
        )
    }
}

@Composable
private fun MiuixPageHost(
    uiState: AppUiState,
    backStack: SnapshotStateList<NavKey>,
    pagerState: PagerState,
    domainPagerStates: Map<AppScope, PagerState>,
    scopePackages: Set<String>,
    gapMaxLand: Float,
    gapMaxPort: Float,
    padding: PaddingValues,
    miuixSnackbar: SnackbarHostState,
    showRestartDialog: () -> Unit,
    onBack: () -> Unit,
    onPush: (NavKey) -> Unit,
    showRootBottomBar: Boolean,
) {
    Scaffold(
        // 顶栏已移进页面自身（PageScaffold）：标题/返回随页面一起转场，
        // 内容也只吃一次 inset（不再出现「顶部一大块空白」）。
        topBar = {},
        snackbarHost = { SnackbarHost(miuixSnackbar) },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding(),
        )
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            NavPages(
                uiState = uiState,
                backStack = backStack,
                pagerState = pagerState,
                domainPagerStates = domainPagerStates,
                scopePackages = scopePackages,
                padding = padding,
                contentPadding = contentPadding,
                gapMaxLand = gapMaxLand,
                gapMaxPort = gapMaxPort,
                onBack = onBack,
                onPush = onPush,
                showRootBottomBar = showRootBottomBar,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 路由栈 → NavDisplay；页面与对应的响应式导航在同一条目内转场。 */
@Composable
private fun NavPages(
    uiState: AppUiState,
    backStack: SnapshotStateList<NavKey>,
    pagerState: PagerState,
    domainPagerStates: Map<AppScope, PagerState>,
    scopePackages: Set<String>,
    padding: PaddingValues,
    contentPadding: PaddingValues,
    gapMaxLand: Float,
    gapMaxPort: Float,
    onBack: () -> Unit,
    onPush: (NavKey) -> Unit,
    showRootBottomBar: Boolean,
    modifier: Modifier = Modifier,
) {
    val rootTabScope = rememberCoroutineScope()
    val useTabletNavigation = LocalConfiguration.current.usesTabletAdaptiveNavigation()
    Box(modifier = modifier) {
        // 与 miuix example 完全一致的默认观感：
        //   · 转场用库自带的 NavTransitions.MiuixDefault（新页整宽滑入 + 被覆盖页 1/4 视差）
        //   · 遮罩与圆角是正交的 effects 层：dimAmount 0.5（example 的 slide 风格取值），
        //     圆角跟随设备屏幕圆角（平板为 0 时自然不裁）
        // 之前这两项被手动关成 false / 0f，所以「没有遮罩、没有圆角」。
        val cornerRadius = rememberNavSystemCornerRadius()
        // 页面内容不再自己让位：顶栏由 PageScaffold 在页面内部占位，
        // 这里只保留底部 inset（底栏是应用级的那一条）。
        // 之前这里又加了状态栏高度，和 PageScaffold 的 inset 叠加，内容就被推到半屏以下。
        val currentRoute = backStack.lastOrNull() ?: RouteHome
        val pagePadding = PaddingValues(
            top = 0.dp,
            // 悬浮底栏不占布局高度，靠底部 padding 让内容能滚到它下面而不被永久遮住
            bottom = contentPadding.calculateBottomPadding() +
                if (currentRoute == RouteHome && showRootBottomBar) BottomBarReservedHeight else 0.dp,
        )
        CompositionLocalProvider(
            LocalPageOnBack provides onBack,
        ) {
        NavDisplay(
            backStack = backStack,
            onBack = onBack,
            transition = NavTransitions.MiuixDefault,
            effects = NavDisplayEffects(
                enableCornerClip = true,
                cornerClipRadius = cornerRadius,
                dimAmount = 0.5f,
                backdropColor = DsTheme.colorScheme.surface,
            ),
        ) {
            entry<RouteHome>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                val navigationItems = remember { rootNavigationItems() }
                TabletAdaptivePage(
                    enabled = useTabletNavigation,
                    items = navigationItems,
                    selectedIndex = pagerState.targetPage,
                    onSelect = { index ->
                        if (index in RootTab.entries.indices && index != pagerState.targetPage) {
                            rootTabScope.launch {
                                pagerState.animateScrollToPage(index, animationSpec = TabSwitchSpec)
                            }
                        }
                    },
                ) { railState ->
                    val title = navigationItems
                        .getOrNull(pagerState.targetPage)
                        ?.label
                        .orEmpty()
                    PageSurface(
                        route = RouteHome,
                        title = title.takeIf { railState != null }.orEmpty(),
                        titleAlpha = { railState?.progress ?: 0f },
                        scaffoldPadding = pagePadding,
                    ) {
                        RootPager(
                            uiState = uiState,
                            pagerState = pagerState,
                            scopePackages = scopePackages,
                            padding = padding,
                            scaffoldPadding = LocalPageScaffoldPadding.current,
                            onOpenScope = { scope -> onPush(RouteDomain(scope)) },
                            onOpenTheme = { onPush(RouteTheme) },
                            onOpenCredits = { onPush(RouteCredits) },
                        )
                    }
                }
            }
            entry<RouteDomain>(swipeDismiss = NavSwipeDirection.LeftToRight) { domain ->
                val domainPager = domainPagerStates.getValue(domain.scope)
                val navigationItems = remember(domain.scope) {
                    pageNavigationItems(domain.scope.pages)
                }
                TabletAdaptivePage(
                    enabled = useTabletNavigation && domain.scope.pages.size > 1,
                    items = navigationItems,
                    selectedIndex = domainPager.targetPage,
                    onSelect = { index ->
                        if (index in domain.scope.pages.indices && index != domainPager.targetPage) {
                            rootTabScope.launch {
                                domainPager.animateScrollToPage(index, animationSpec = TabSwitchSpec)
                            }
                        }
                    },
                ) { railState ->
                    val title = navigationItems
                        .getOrNull(domainPager.targetPage)
                        ?.label
                        .orEmpty()
                    PageSurface(
                        route = domain,
                        title = title.takeIf { railState != null }.orEmpty(),
                        titleAlpha = { railState?.progress ?: 0f },
                        scaffoldPadding = pagePadding,
                        header = {
                            if (!useTabletNavigation) DomainTabs(domain.scope, domainPager)
                        },
                    ) {
                        DomainHost(
                            scope = domain.scope,
                            pagerState = domainPager,
                            uiState = uiState,
                            padding = padding,
                            scaffoldPadding = LocalPageScaffoldPadding.current,
                            gapMaxLand = gapMaxLand,
                            gapMaxPort = gapMaxPort,
                            onPush = { onPush(it) },
                        )
                    }
                }
            }
            entry<RouteTheme>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteTheme, scaffoldPadding = pagePadding) {
                    ThemeSettingsPage(
                        uiState = uiState,
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteCredits>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteCredits, scaffoldPadding = pagePadding) {
                    CreditsPage(
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteImeVirtual>(contentKey = { "ime" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteImeVirtual, scaffoldPadding = pagePadding) {
                    VirtualKeyboardPage(
                        uiState = uiState,
                        gapMaxLand = gapMaxLand,
                        gapMaxPort = gapMaxPort,
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteImeFloating>(contentKey = { "ime" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteImeFloating, scaffoldPadding = pagePadding) {
                    FloatingKeyboardPage(
                        uiState = uiState,
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteImeStylus>(contentKey = { "ime" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteImeStylus, scaffoldPadding = pagePadding) {
                    StylusPage(
                        uiState = uiState,
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteCursorPreset>(contentKey = { "cursor" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteCursorPreset, scaffoldPadding = pagePadding) {
                    CursorPresetPage(
                        cursorEnabled = uiState.cursorEnabled,
                        onCursorEnabledChange = { checked ->
                            uiState.cursorEnabled = checked
                            uiState.save { e -> e.putBoolean(PrefKeys.CURSOR_ENABLED, checked) }
                        },
                        onOpenColors = { onPush(RouteCursorColors) },
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteCursorSize>(contentKey = { "cursor" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteCursorSize, scaffoldPadding = pagePadding) {
                    CursorSizePage(
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteCursorImport>(contentKey = { "cursor" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteCursorImport, scaffoldPadding = pagePadding) {
                    CursorImportPage(
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteCursorColors>(contentKey = { "cursor" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteCursorColors, scaffoldPadding = pagePadding) {
                    CursorColorsPage(
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteParallel>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteParallel, scaffoldPadding = pagePadding) {
                    ParallelPage(
                        uiState = uiState,
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteCaptionFeatures>(contentKey = { "caption" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteCaptionFeatures, scaffoldPadding = pagePadding) {
                    CaptionFeaturesPage(
                        uiState = uiState,
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteCaptionButtons>(contentKey = { "caption" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteCaptionButtons, scaffoldPadding = pagePadding) {
                    CaptionButtonsPage(
                        uiState = uiState,
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
            entry<RouteAppMenu>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteAppMenu, scaffoldPadding = pagePadding) {
                    AppMenuPage(
                        uiState = uiState,
                        padding = padding,
                        scaffoldPadding = LocalPageScaffoldPadding.current,
                    )
                }
            }
        }
        }
    }
}

/**
 * 三个标签页的容器（miuix example 的 `AppPager` 同款做法：`HorizontalPager` 承载一级页面，
 * 底栏切页、页面可左右滑）。
 */
@Composable
private fun RootPager(
    uiState: AppUiState,
    pagerState: PagerState,
    scopePackages: Set<String>,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
    onOpenScope: (AppScope) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenCredits: () -> Unit,
) {
    val tabScope = rememberCoroutineScope()
    val openFeatures: () -> Unit = {
        tabScope.launch { pagerState.animateScrollToPage(RootTab.Features.ordinal, animationSpec = TabSwitchSpec) }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        // 同 DomainHost：每页必须撑满 pager 高度，否则内容少的页会被垂直居中
        Box(modifier = Modifier.fillMaxSize()) {
        when (RootTab.entries[page]) {
            RootTab.Home -> HomePage(
                uiState = uiState,
                scopePackages = scopePackages,
                onOpenScope = onOpenScope,
                onOpenFeatures = openFeatures,
                padding = padding,
                scaffoldPadding = scaffoldPadding,
            )

            RootTab.Features -> ScopeListPage(
                scopePackages = scopePackages,
                onEnter = onOpenScope,
                padding = padding,
                scaffoldPadding = scaffoldPadding,
            )

            RootTab.Info -> InfoPage(
                scopePackages = scopePackages,
                onTheme = onOpenTheme,
                onCredits = onOpenCredits,
                padding = padding,
                scaffoldPadding = scaffoldPadding,
            )
        }
        }
    }
}

/** 物理屏幕尺寸（最大窗口边界）。取不到返回 null，由调用方退回 displayMetrics。 */
private data class ScreenSize(val width: Int, val height: Int, val density: Float)

private fun maxWindowMetricsOrNull(context: android.content.Context): ScreenSize? = try {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE)
            as? android.view.WindowManager
        val bounds = wm?.maximumWindowMetrics?.bounds
        val d = context.resources.displayMetrics.density.let { if (it > 0f) it else 1f }
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            ScreenSize(bounds.width(), bounds.height(), d)
        } else {
            null
        }
    } else {
        null
    }
} catch (t: Throwable) {
    L.e("event=max_window_metrics_failed", t)
    null
}

/**
 * 底栏点标签切页：`animateScrollToPage` 的默认 spring 要 400ms 以上才结算，
 * 点击后明显「慢半拍」。改成 200ms 定值曲线，和底栏胶囊的 spring 同步收尾。
 */
private val TabSwitchSpec = tween<Float>(
    durationMillis = 200,
    easing = androidx.compose.animation.core.FastOutSlowInEasing,
)

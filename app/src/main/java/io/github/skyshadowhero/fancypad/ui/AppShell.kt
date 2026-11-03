package io.github.skyshadowhero.fancypad.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.github.skyshadowhero.fancypad.RemoteConfig
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.skyshadowhero.fancypad.ImeRestarter
import io.github.skyshadowhero.fancypad.L
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.ui.ds.DsButtonDefaults
import io.github.skyshadowhero.fancypad.ui.ds.DsSegmentedRow
import io.github.skyshadowhero.fancypad.ui.ds.DsSidebar
import io.github.skyshadowhero.fancypad.ui.ds.DsTheme
import io.github.skyshadowhero.fancypad.ui.ds.GlasmioSnackbarHost
import io.github.skyshadowhero.fancypad.ui.ds.TextButton
import io.github.skyshadowhero.fancypad.ui.ds.WindowDialog
import io.github.skyshadowhero.fancypad.ui.ds.isGlasmio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.basic.DividerDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailDefaults
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * 自适应外壳。全部导航走 miuix-nav（[NavDisplay] + [NavKey]）：作用域是路由栈的第一层，
 * 作用域里的功能页是第二层，光标域的颜色设置是第三层。
 *
 * 两种外观（[io.github.skyshadowhero.fancypad.ui.ds.UiStyle]）共用一个路由栈与同一套页面，
 * 差别只在**外壳**：
 * - Miuix：顶栏渐进模糊 + 横屏左栏 / 竖屏底部菜单（原有行为）；
 * - Glasmio：iOS 风格居中顶栏 + 分段控件切换同作用域的页面（没有左栏与底部菜单）。
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
    serviceMissing: Boolean = false,
    onRetryService: () -> Unit = {},
) {
    val glasmio = isGlasmio

    // 未连上 LSPosed：启动即弹窗警告（不阻断界面，但明确告知改动不会保存/生效）
    var serviceWarningDismissed by remember { mutableStateOf(false) }
    WindowDialog(
        show = serviceMissing && !serviceWarningDismissed,
        title = "未连接到 LSPosed",
        summary = "本模块依赖 LSPosed 框架才能生效，当前未能连接到框架服务。\n\n" +
            "请检查：\n" +
            "1. 已在 LSPosed 管理器中启用本模块\n" +
            "2. 作用域已勾选 com.xiaomi.type、system、com.android.systemui、com.miui.securitycore\n" +
            "   （只在用「右键菜单」功能时才需要再加 mark.via）\n" +
            "3. 启用后已重启输入法进程（system / SystemUI / 安全中心的作用域变更需重启平板）\n\n" +
            "在框架就绪前，此处的改动不会被保存，也不会生效。",
        onDismissRequest = { },
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = "重试连接",
                    onClick = { onRetryService() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "我知道了",
                    onClick = { serviceWarningDismissed = true },
                    modifier = Modifier.weight(1f),
                    colors = DsButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )

    val configuration = LocalConfiguration.current
    val useRail = configuration.screenWidthDp >= RAIL_BREAKPOINT_DP

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

    /** 同一作用域内的功能页之间切换：只替换栈顶，返回键一次回到作用域列表。 */
    fun openSibling(key: NavKey) {
        val last = backStack.lastOrNull() ?: return
        if (last == key) return
        if (last.scope() != null && last.scope() == key.scope()) {
            backStack[backStack.lastIndex] = key
        } else {
            push(key)
        }
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
    val tabScope = rememberCoroutineScope()
    val rootBackEnabled by remember {
        derivedStateOf { backStack.size == 1 && pagerState.currentPage != RootTab.Home.ordinal }
    }
    val rootNavState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = rootNavState,
        isBackEnabled = rootBackEnabled,
        onBackCompleted = {
            tabScope.launch { pagerState.animateScrollToPage(RootTab.Home.ordinal) }
        },
    )

    val current = backStack.lastOrNull() ?: RouteHome
    // 顶栏标题：在 RouteHome 里跟着标签走，二级页用自己的标题
    val pageTitle = if (current == RouteHome) {
        RootTab.entries[pagerState.currentPage.coerceIn(0, RootTab.entries.lastIndex)].label
    } else {
        current.title()
    }
    val activeScope = current.scope()

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

    val pages = activeScope?.pages.orEmpty()
    val showScopeNav = pages.size > 1

    // 退出多页作用域时 pages 立刻变空，但左栏收起动画还要播完，所以记住最后一组页面。
    var lastScopePages by remember { mutableStateOf(emptyList<NavKey>()) }
    LaunchedEffect(pages) { if (pages.isNotEmpty()) lastScopePages = pages }
    // 左栏标题同理：收起动画期间 activeScope 已经是 null，标题不能跟着变空。
    var lastScope by remember { mutableStateOf<AppScope?>(null) }
    LaunchedEffect(activeScope) { if (activeScope != null) lastScope = activeScope }

    // 光标域的框架连接跟随：两套外观都要跑（拆成三个页面后只在外壳里跑一份轮询）
    CursorConnectionEffect()

    // ---------------------------------------------------------- 重启输入法 + Snackbar

    // 两套外观各自有自己的 Snackbar 宿主（Miuix 用 Miuix 的，Glasmio 用自己画的胶囊），
    // 但提示内容与触发点只有一份，所以两边状态都持有，按当前外观选一个显示。
    val miuixSnackbar = remember { SnackbarHostState() }
    val glassSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
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
                    if (glasmio) glassSnackbar.showSnackbar(message)
                    else miuixSnackbar.showSnackbar(message, "重启成功")
                }

                is ImeRestarter.Result.Failed -> {
                    val message = "重启失败：需要 root 授权（KernelSU）"
                    if (glasmio) glassSnackbar.showSnackbar(message)
                    else miuixSnackbar.showSnackbar(message, "失败")
                }
            }
        }
    }

    // 重启确认弹窗（两套外观共用）
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

    // 底栏只在三个一级标签页出现（与 miuix example 一致）；
    // 二级页里原本用底栏切兄弟页的地方改成页内分段控件（Miuix TabRow / Glasmio 分段控件）。
    // Header is derived from each NavDisplay entry's own route, not the current stack top.
    // NavDisplay keeps the outgoing entry composed during back navigation; this keeps its
    // TabRow visible until that entry's exit animation has finished.
    val siblingTabScrollStates = remember { mutableMapOf<AppScope, SiblingTabScrollState>() }
    val glassTabScope = rememberCoroutineScope()
    val glassTheme = isGlasmio
    val pageHeader: @Composable (NavKey) -> Unit = { route ->
        val routePages = route.scope()?.pages.orEmpty()
        if (routePages.size > 1) {
            // Keep one shared selected value while the sibling destinations transition.
            // On a pop to a route outside this scope, retain the outgoing entry's selection
            // until that entry has left the display.
            val selectedRoute = current.takeIf { it in routePages } ?: route
            val tabScrollState = siblingTabScrollStates.getOrPut(route.scope()!!) {
                SiblingTabScrollState()
            }
            SiblingTabs(
                pages = routePages,
                current = selectedRoute,
                miuixScrollState = tabScrollState.miuix,
                glassPositionAnimation = tabScrollState.glassPosition,
                onSelect = { selected ->
                    if (selected != selectedRoute && !glassTheme) {
                        openSibling(selected)
                    } else if (selected != selectedRoute) {
                            val targetIndex = routePages.indexOf(selected)
                            glassTabScope.launch {
                                try {
                                    if (targetIndex >= 0) {
                                        tabScrollState.glassPosition.animateTo(
                                            targetIndex.toFloat(),
                                            tween(durationMillis = 260),
                                        )
                                    }
                                } finally {
                                    // Drag release also animates the shared indicator; if that
                                    // cancels this animation, still commit the chosen destination.
                                    openSibling(selected)
                                }
                            }
                        }
                },
            )
        }
    }
    CompositionLocalProvider(LocalPageHeader provides pageHeader) {
    // 悬浮底栏要采样背后的内容 → 内容层单独注册进 backdrop，底栏作为**兄弟节点**叠在上面
    // （不能放进被注册的那一层，否则会采样到自己）。
    val barBackdrop = if (glasmio) null else rememberTopBlurBackdrop(DsTheme.colorScheme.surface)
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 只有内容层进 backdrop；底栏是它的兄弟节点，采样内容、不会采样到自己
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (barBackdrop != null) Modifier.layerBackdrop(barBackdrop) else Modifier),
        ) {
            if (glasmio) {
        // ------------------------------------------------ Glasmio 外壳（iOS 风）
        //
        // 布局结构**固定**：Row[ 左栏 | 详情列 ]，左栏那一列永远存在、宽度在 0 ↔ 300dp 之间动画。
        // 这是 Miuix 外壳里踩过的同一个坑（见 MiuixShell 的注释）：按「有没有侧栏」增删 Row
        // 的子节点，NavDisplay 会被换到新的组合位置重建，上一层的列表会瞬间按变窄后的宽度
        // 重排 —— 视觉上就是「闪一下跑到新位置」。所以结构不动、只让宽度动。
        //
        // 顶栏属于**详情列**而不是整屏：有左栏时它是详情页的标题栏（像 iPadOS 那样），
        // 没左栏时它自然铺满整宽。左栏自己有表头（作用域名），两边互不重叠。
        // 侧栏宽度可拖（右边缘把手），松手用 spring 收尾 —— macOS 的 sidebar 就是这个交互。
        var sidebarUserWidth by remember { mutableStateOf(SIDEBAR_WIDTH) }
        // 拖拽时面板做一点点横向拉伸（以左边缘为原点），松手弹回 —— 液态的手感。
        var sidebarDragging by remember { mutableStateOf(false) }
        val sidebarStretch by animateFloatAsState(
            targetValue = if (sidebarDragging) 1.02f else 1f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
            label = "sidebarStretch",
        )
        val sidebarTargetWidth = if (showScopeNav && useRail) sidebarUserWidth else 0.dp
        val sidebarWidth by animateDpAsState(
            targetValue = sidebarTargetWidth,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "sidebarWidth",
        )
        val sidebarScope = activeScope ?: lastScope
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(modifier = Modifier.fillMaxSize().background(DsTheme.colorScheme.surface)) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 左栏：槽位宽度动画，但**不裁切内容** —— 内容按最终宽度排版、先画出来，
                // 再让槽位让位；否则展开/收起过程中内容会被槽位齐边切掉。
                Box(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxHeight()
                        // 拖拽时以左边缘为原点做轻微横向拉伸，松手弹回：液态手感。
                        .graphicsLayer {
                            scaleX = sidebarStretch
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                        .zIndex(1f)
                        // 收起时按宽度比例淡出：裁切保住不越界，但看不到被切的那条边
                        //（之前去掉裁切后，收起过程中内容溢出到内容区，就是那个残影）。
                        .graphicsLayer {
                            val target = if (sidebarUserWidth.value > 0f) sidebarUserWidth.value else 1f
                            alpha = (sidebarWidth.value / target).coerceIn(0f, 1f)
                        }
                        .clipToBounds(),
                ) {
                    if (sidebarWidth > 0.dp) {
                        // 侧栏上也能左右滑动切换选项（和内容区、底栏一致的手势）。
                        var sidebarSwipe by remember { mutableStateOf(0f) }
                        Box(
                            modifier = Modifier
                                .requiredWidth(sidebarUserWidth)
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .pointerInput(pages, current) {
                                    val list = if (pages.isNotEmpty()) pages else lastScopePages
                                    detectHorizontalDragGestures(
                                        onDragStart = { sidebarSwipe = 0f },
                                        onHorizontalDrag = { _, amount -> sidebarSwipe += amount },
                                        onDragEnd = {
                                            val threshold = 60.dp.toPx()
                                            val idx = list.indexOf(current)
                                            if (idx >= 0 && kotlin.math.abs(sidebarSwipe) > threshold) {
                                                val target = if (sidebarSwipe < 0f) idx + 1 else idx - 1
                                                list.getOrNull(target)?.let { openSibling(it) }
                                            }
                                            sidebarSwipe = 0f
                                        },
                                        onDragCancel = { sidebarSwipe = 0f },
                                    )
                                },
                        ) {
                            DsSidebar(
                                title = sidebarScope?.label.orEmpty(),
                                items = (if (pages.isNotEmpty()) pages else lastScopePages)
                                    .map { it to it.title() },
                                selected = current,
                                onSelected = { openSibling(it) },
                                modifier = Modifier.requiredWidth(sidebarUserWidth),
                            )
                        }
                        // 右边缘 10dp 的把手：拖动改宽度（200..360dp），松手由 spring 收尾。
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(10.dp)
                                .draggable(
                                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                                    onDragStarted = { sidebarDragging = true },
                                    onDragStopped = { sidebarDragging = false },
                                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                                        sidebarUserWidth = (sidebarUserWidth + with(density) { delta.toDp() })
                                            .coerceIn(200.dp, 360.dp)
                                    },
                                ),
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // 顶栏**浮**在内容之上：内容始终铺满整列，只把顶栏实测高度作为
                    // 列表的 contentPadding。这样「有没有分段控件」不会再改变内容布局，
                    // 进二级页时不会出现「标题消失、内容先闪一下再上移」。
                    var barHeightPx by remember { mutableIntStateOf(0) }
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val barHeight = with(density) { barHeightPx.toDp() }
                    val contentTop by animateDpAsState(
                        targetValue = barHeight,
                        animationSpec = tween(durationMillis = 200),
                        label = "contentTop",
                    )

                    // 同作用域内的页面支持左右滑动切换（像底栏那样）：横向拖动超过 80dp 切一页。
                    var swipeAccum by remember { mutableStateOf(0f) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(pages, current) {
                                if (!showScopeNav) return@pointerInput
                                detectHorizontalDragGestures(
                                    onDragStart = { swipeAccum = 0f },
                                    onHorizontalDrag = { _, amount -> swipeAccum += amount },
                                    onDragEnd = {
                                        val threshold = 80.dp.toPx()
                                        val idx = pages.indexOf(current)
                                        if (idx >= 0 && kotlin.math.abs(swipeAccum) > threshold) {
                                            if (swipeAccum > 0f && idx == 0) {
                                                pop()
                                            } else {
                                                val target = if (swipeAccum < 0f) idx + 1 else idx - 1
                                                pages.getOrNull(target)?.let { openSibling(it) }
                                            }
                                        }
                                        swipeAccum = 0f
                                    },
                                    onDragCancel = { swipeAccum = 0f },
                                )
                            },
                    ) {
                        NavPages(
                            uiState = uiState,
                            backStack = backStack,
                            pagerState = pagerState,
                            scopePackages = scopePackages,
                            padding = padding,
                            contentPadding = PaddingValues(bottom = bottomInset),
                            gapMaxLand = gapMaxLand,
                            gapMaxPort = gapMaxPort,
                            onBack = {
                                pop()
                            },
                            onPush = { push(it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // 顶栏已移进页面自身（PageScaffold）：不再作为外壳的固定层，
                    // 这样标题/返回/内容属于同一个 NavDisplay entry，转场时一起动。
                }
            }
            // Snackbar 浮在最外层底部，跨左右两栏都能看见。
            Box(Modifier.align(Alignment.BottomCenter)) {
                GlasmioSnackbarHost(glassSnackbar)
            }
        }
    } else {
        MiuixShell(
            uiState = uiState,
            backStack = backStack,
            pagerState = pagerState,
            scopePackages = scopePackages,
            pageTitle = pageTitle,
            current = current,
            pages = pages,
            showScopeNav = showScopeNav,
            useRail = useRail,
            padding = padding,
            gapMaxLand = gapMaxLand,
            gapMaxPort = gapMaxPort,
            miuixSnackbar = miuixSnackbar,
            showRestartDialog = { showRestartDialog = true },
            onBack = {
                        pop()
                    },
            onPush = { push(it) },
            onOpenSibling = { openSibling(it) },
        )
            }
        }
    }
    // 宽屏分栏的分隔线：画在内容层（layerBackdrop）之外，所以不会被顶栏的
    // progressive blur 采样；从顶栏下沿开始，不盖住标题栏。
    // 悬浮底栏：只在一级标签页出现，浮在内容之上一层（内容会从它下面滚过）。
    // 进出二级页面时跟着页面的转场一起滑出/滑入，而不是直接消失。
    androidx.compose.animation.AnimatedVisibility(
        visible = current == RouteHome,
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
        AppBottomBar(
            selectedIndex = pagerState.currentPage,
            onSelect = { index ->
                if (index != pagerState.currentPage) {
                    glassTabScope.launch { pagerState.animateScrollToPage(index) }
                }
            },
            modifier = Modifier,
            backdrop = barBackdrop,
        )
    }
    }
    }
}

/**
 * 作用域内兄弟页切换：Miuix 用 `TabRowWithContour`，Glasmio 用分段控件（[DsSegmentedRow]）。
 * 二级页里用它替代底栏，所以它属于页面 header，跟着页面一起转场。
 */
private data class SiblingTabScrollState(
    val miuix: LazyListState = LazyListState(),
    val glassPosition: Animatable<Float, AnimationVector1D> = Animatable(0f),
)

@Composable
private fun SiblingTabs(
    pages: List<NavKey>,
    current: NavKey,
    miuixScrollState: LazyListState,
    glassPositionAnimation: Animatable<Float, AnimationVector1D>,
    onSelect: (NavKey) -> Unit,
) {
    if (isGlasmio) {
        DsSegmentedRow(
            items = pages.map { it to it.title() },
            selected = current,
            onSelected = onSelect,
            positionAnimation = glassPositionAnimation,
        )
    } else {
        val routeIndex = pages.indexOf(current).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val targetIndex = routeIndex
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(targetIndex, pages.size, miuixScrollState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        var endX = down.position.x
                        var released = false
                        while (!released) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            endX = change.position.x
                            released = !change.pressed
                        }

                        val selectedItem = miuixScrollState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == targetIndex }
                        val delta = endX - down.position.x
                        val itemWidth = selectedItem?.size?.toFloat()
                        val itemStart = selectedItem?.let { with(density) { 17.dp.toPx() } + it.offset }
                        val startedOnSelectedTab = itemStart != null && itemWidth != null &&
                            down.position.x in itemStart..(itemStart + itemWidth)
                        val threshold = maxOf(with(density) { 36.dp.toPx() }, (itemWidth ?: 72f) * 0.5f)
                        if (startedOnSelectedTab && kotlin.math.abs(delta) >= threshold) {
                            val stride = (itemWidth ?: 72f) + with(density) { 5.dp.toPx() }
                            val steps = (kotlin.math.abs(delta) / stride).roundToInt().coerceAtLeast(1)
                            val destination = (targetIndex + if (delta < 0f) steps else -steps)
                                .coerceIn(0, pages.lastIndex)
                            if (destination != targetIndex) onSelect(pages[destination])
                        }
                    }
                },
        ) {
            // 用 TabRowWithContour（轮廓胶囊）。
            // 注意：它的指示器动画靠 `lastSettledSelectedTabIndex` 的 remember 状态驱动，
            // 而兄弟页切换会**整页重建**（openSibling 换掉了栈顶 key）→ 每次都是新组合，
            // 于是永远走 snapTo 分支，看起来"没有动画"。根治要把它挪出被重建的层级
            // （见下一条建议：作用域内用 HorizontalPager 承载兄弟页）。
            TabRowWithContour(
                tabs = pages.map { it.title() },
                selectedTabIndex = targetIndex,
                onTabSelected = { index -> pages.getOrNull(index)?.let(onSelect) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                minWidth = 0.dp,
                maxWidth = 1000.dp,
                listState = miuixScrollState,
            )
        }
    }
}

/**
 * Miuix 外壳：唯一的 Scaffold（顶栏 + Snackbar），内容交给 [NavDisplay] 按路由渲染。
 * 右上角放「重启输入法」入口；宽屏走左栏、窄屏走底部菜单。
 */
@Composable
private fun MiuixShell(
    uiState: AppUiState,
    backStack: SnapshotStateList<NavKey>,
    pagerState: PagerState,
    scopePackages: Set<String>,
    current: NavKey,
    pageTitle: String,
    pages: List<NavKey>,
    showScopeNav: Boolean,
    useRail: Boolean,
    padding: PaddingValues,
    gapMaxLand: Float,
    gapMaxPort: Float,
    miuixSnackbar: SnackbarHostState,
    showRestartDialog: () -> Unit,
    onBack: () -> Unit,
    onPush: (NavKey) -> Unit,
    onOpenSibling: (NavKey) -> Unit,
) {

    /** 宽屏 + 当前功能域有多页 → 显示左栏 */
    val railVisible = showScopeNav && useRail

    // 左栏宽度动画。宽度有三个档：
    //   0（没有菜单）↔ MinWidth（菜单收起态）↔ ExpandedWidth（菜单展开态）
    // 关键：菜单自带的「展开/收起」按钮改的是 railState，容器宽度必须跟着它走 ——
    // 写死展开宽度的话，用户把菜单收起来后容器还是那么宽，右侧内容就不会延伸过去。
    val railState = rememberNavigationRailState(initialValue = NavigationRailValue.Expanded)
    val railTargetWidth = when {
        !railVisible -> 0.dp
        railState.isExpanded -> RAIL_WIDTH
        else -> RAIL_COLLAPSED_WIDTH
    }
    val railWidth by animateDpAsState(
        targetValue = railTargetWidth,
        animationSpec = tween(durationMillis = 240),
        label = "railWidth",
    )

    // 收起过程中 pages 已经空了（当前路由不再属于任何功能域），但菜单还要播完收回动画，
    // 所以记住最后一组菜单项。
    var lastRailPages by remember { mutableStateOf(emptyList<NavKey>()) }
    LaunchedEffect(pages) { if (pages.isNotEmpty()) lastRailPages = pages }
    val railPages = pages.ifEmpty { lastRailPages }

    // 左栏内容：交给 PageHost，放进它内容区里的 Row（在顶栏之下）：
    // 左栏占位、右侧内容铺满剩余宽度 —— 左栏收起时内容自然会延伸过去。
    // 之前把左栏做成整屏覆盖层，结果顶栏压住了它的展开/收起按钮（截图确认）。
    val railContent: @Composable () -> Unit = {
        if (railWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    // 左栏是整列全高：自己避开状态栏与导航栏（顶栏不再替它让位）
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Bottom
                        )
                    )
                    .clipToBounds()
            ) {
                // 菜单本体按当前（动画中的）宽度排版并由容器裁切：
                // 出现时是「从左边推出来」，收起/展开时与容器宽度同步。
                NavigationRail(
                    state = railState,
                    modifier = Modifier
                        .requiredWidth(railWidth)
                        .fillMaxHeight(),
                ) {
                    railPages.forEach { page ->
                        NavigationRailItem(
                            selected = current == page,
                            onClick = { onOpenSibling(page) },
                            icon = page.icon(),
                            label = page.title(),
                        )
                    }
                }
            }
        }
    }

    // 结构固定为 Box → Column → Row[左栏 | 页面]：PageHost / NavDisplay 的树位置不随菜单出现而变化，
    // 从一级页面进入多页功能域时不会被重建，转场动画才播得出来。
    //
    // 左栏是 Row 的一列、整列全高，顶栏在它右边 —— 所以顶栏永远不会压到左栏的展开/收起按钮
    //（之前把左栏放进 Scaffold 内容区，顶栏正好压在它头上；截图确认过）。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DsTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                railContent()
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    MiuixPageHost(
                        uiState = uiState,
                        backStack = backStack,
                        pagerState = pagerState,
                        scopePackages = scopePackages,
                        current = current,
                        pageTitle = pageTitle,
                        pages = pages,
                        gapMaxLand = gapMaxLand,
                        gapMaxPort = gapMaxPort,
                        padding = padding,
                        miuixSnackbar = miuixSnackbar,
                        showRestartDialog = showRestartDialog,
                        onBack = onBack,
                        onPush = onPush,
                    )
                }
            }
            // 窄屏的兄弟页切换已改为页面内的 TabRow（见 SiblingTabs），
            // 这里不再放底部菜单 —— 底栏只属于一级标签页。
        }
    }
}

@Composable
private fun MiuixPageHost(
    uiState: AppUiState,
    backStack: SnapshotStateList<NavKey>,
    pagerState: PagerState,
    scopePackages: Set<String>,
    current: NavKey,
    pageTitle: String,
    pages: List<NavKey>,
    gapMaxLand: Float,
    gapMaxPort: Float,
    padding: PaddingValues,
    miuixSnackbar: SnackbarHostState,
    showRestartDialog: () -> Unit,
    onBack: () -> Unit,
    onPush: (NavKey) -> Unit,
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
                scopePackages = scopePackages,
                padding = padding,
                contentPadding = contentPadding,
                gapMaxLand = gapMaxLand,
                gapMaxPort = gapMaxPort,
                onBack = onBack,
                onPush = onPush,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 顶栏副标题（目前只有颜色设置页用） */
private fun NavKey.subtitle(): String = when (this) {
    RouteCursorColors -> cursorColorsSubtitle()
    else -> ""
}

/** 路由栈 → NavDisplay。两种外壳共用这一份，页面本身跟外观无关。 */
@Composable
private fun NavPages(
    uiState: AppUiState,
    backStack: SnapshotStateList<NavKey>,
    pagerState: PagerState,
    scopePackages: Set<String>,
    padding: PaddingValues,
    contentPadding: PaddingValues,
    gapMaxLand: Float,
    gapMaxPort: Float,
    onBack: () -> Unit,
    onPush: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootTabScope = rememberCoroutineScope()
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
                if (currentRoute == RouteHome) BottomBarReservedHeight else 0.dp,
        )
        val titleForPage = if (currentRoute == RouteHome) {
            RootTab.entries[pagerState.currentPage.coerceIn(0, RootTab.entries.lastIndex)].label
        } else {
            currentRoute.title()
        }
        CompositionLocalProvider(
            LocalPageTitle provides titleForPage,
            LocalPageCanGoBack provides (backStack.size > 1),
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
                PageSurface(
                    route = RouteHome,
                    rootTitle = RootTab.entries[pagerState.currentPage.coerceIn(0, RootTab.entries.lastIndex)].label,
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
                    )
                }
            }
            entry<RouteDomain>(swipeDismiss = NavSwipeDirection.LeftToRight) { domain ->
                // pager 状态提到这里创建：顶栏（header）与内容区要共用同一个实例，
                // header 里画 TabRow 才能既在模糊之上、又和 pager 同步。
                val domainPager = rememberPagerState(pageCount = { domain.scope.pages.size })
                PageSurface(
                    route = domain,
                    scaffoldPadding = pagePadding,
                    header = { if (!isWideLayout()) DomainTabs(domain.scope, domainPager) },
                    // 分栏分隔线交给页面自己画：随页面转场/预测性返回一起进出
                    sidebarDivider = isWideLayout() && domain.scope.pages.size > 1,
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
            entry<RouteTheme>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteTheme, scaffoldPadding = pagePadding) {
                    ThemeSettingsPage(
                        uiState = uiState,
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
            entry<RouteImeMaterial>(contentKey = { "ime" }, swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteImeMaterial, scaffoldPadding = pagePadding) {
                    MaterialPage(
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
            entry<RouteTextMenu>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                PageSurface(route = RouteTextMenu, scaffoldPadding = pagePadding) {
                    TextMenuPage(
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
) {
    val tabScope = rememberCoroutineScope()
    val openFeatures: () -> Unit = {
        tabScope.launch { pagerState.animateScrollToPage(RootTab.Features.ordinal) }
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
                uiState = uiState,
                scopePackages = scopePackages,
                onTheme = onOpenTheme,
                padding = padding,
                scaffoldPadding = scaffoldPadding,
            )
        }
        }
    }
}

/**
 * 底栏：首页 / 功能 / 信息。
 *
 * Miuix 皮肤直接用库里的 `NavigationBar`；Glasmio 皮肤没有对应的底栏组件，
 * 这里用同一套语义 token 画一条轻量底栏（图标 + 文字 + 强调色），保证两套皮都能用。
 */
@Composable
private fun RootBottomBar(
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
) {
    val tabs = RootTab.entries
    if (isGlasmio) {
        val colors = DsTheme.colorScheme
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = pagerState.currentPage == index
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 6.dp),
                ) {
                    top.yukonga.miuix.kmp.basic.Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) colors.accent else colors.onBackgroundVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    androidx.compose.material3.Text(
                        text = tab.label,
                        color = if (selected) colors.accent else colors.onBackgroundVariant,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    } else {
        NavigationBar {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = pagerState.currentPage == index,
                    onClick = { onSelect(index) },
                    icon = tab.icon,
                    label = tab.label,
                )
            }
        }
    }
}

/** 平板布局断点：横屏平板走左侧栏；竖屏平板 / 手机走底部菜单。 */
private const val RAIL_BREAKPOINT_DP = 840

/** 页面自带顶栏的高度（PageScaffold 的 TopAppBar），页面内容按它让位。 */
private val PAGE_BAR_HEIGHT = 56.dp

/** Glasmio 外观下左栏的展开宽度（Miuix 用的是它自己的 NavigationRailDefaults，这里自己给）。 */
private val SIDEBAR_WIDTH = 250.dp

/**
 * 展开态左栏**总**宽 = Miuix 的展开宽度 + 它自带的那条右侧分隔线。
 * 少算这 0.75dp 就会让分隔线落在裁切范围之外，界面上那条竖细线就看不见了。
 */
private val RAIL_WIDTH = NavigationRailDefaults.ExpandedWidth + DividerDefaults.Thickness

/** 左栏收起态总宽（同上，加分隔线宽度）。 */
private val RAIL_COLLAPSED_WIDTH = NavigationRailDefaults.MinWidth + DividerDefaults.Thickness

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

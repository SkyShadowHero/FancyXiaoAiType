package io.github.skyshadowhero.fancypad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ImeRestarter
import io.github.skyshadowhero.fancypad.L
import io.github.skyshadowhero.fancypad.PrefKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.DividerDefaults
import top.yukonga.miuix.kmp.basic.NavigationRailDefaults
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 自适应外壳。全部导航走 miuix-nav（[NavDisplay] + [NavKey]）：作用域是路由栈的第一层，
 * 作用域里的功能页是第二层，光标域的颜色设置是第三层。
 *
 * - 一级页面（[RouteScopes]）不显示多页导航；
 * - 进入多页作用域（输入法外观）后，横屏走左侧栏、竖屏走底部菜单，在这几页之间切换
 *   —— 同级切换只**替换**栈顶，不加深返回栈，返回键一次就回到作用域列表；
 * - 配置装载已在 App() 完成，这里只负责渲染与交互。
 */
@Composable
fun AppShell(
    uiState: AppUiState,
    padding: PaddingValues,
    serviceMissing: Boolean = false,
    onRetryService: () -> Unit = {},
) {
    // 未连上 LSPosed：启动即弹窗警告（不阻断界面，但明确告知改动不会保存/生效）
    var serviceWarningDismissed by remember { mutableStateOf(false) }
    WindowDialog(
        show = serviceMissing && !serviceWarningDismissed,
        title = "未连接到 LSPosed",
        summary = "本模块依赖 LSPosed 框架才能生效，当前未能连接到框架服务。\n\n" +
            "请检查：\n" +
            "1. 已在 LSPosed 管理器中启用本模块\n" +
            "2. 作用域已勾选 com.xiaomi.type、system、com.android.systemui\n" +
            "3. 启用后已重启输入法进程（system / SystemUI 的作用域变更需重启平板）\n\n" +
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
                    colors = ButtonDefaults.textButtonColorsPrimary(),
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

    // 只做内存栈（navBackStackOf）：路由都是 data object，不需要序列化插件，
    // 代价是不做进程重建后的路由恢复 —— 对模块设置页够用。
    val backStack = remember { navBackStackOf(RouteScopes) }
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

    // 返回兜底：NavDisplay 的返回桥在部分宿主下不生效（会直接退到桌面），这里补一层；
    // pop() 有 size 守卫，重复触发也不会退出
    BackHandler(enabled = backStack.size > 1) { pop() }

    val current = backStack.lastOrNull() ?: RouteScopes
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
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )

    val pages = activeScope?.pages.orEmpty()
    val showScopeNav = pages.size > 1

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
                            onClick = { openSibling(page) },
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
            .background(MiuixTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                railContent()
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PageHost(
                        uiState = uiState,
                        backStack = backStack,
                        current = current,
                        gapMaxLand = gapMaxLand,
                        gapMaxPort = gapMaxPort,
                        padding = padding,
                        railSettled = railWidth == railTargetWidth,
                        onBack = { pop() },
                        onPush = { push(it) },
                    )
                }
            }
                // 窄屏用底部菜单，从底部滑入 / 滑出
                AnimatedVisibility(
                    visible = showScopeNav && !useRail,
                    enter = slideInVertically(animationSpec = tween(260)) { it } + fadeIn(tween(180)),
                    exit = slideOutVertically(animationSpec = tween(220)) { it } + fadeOut(tween(140)),
                ) {
                    NavigationBar {
                        pages.forEach { page ->
                            NavigationBarItem(
                                selected = current == page,
                                onClick = { openSibling(page) },
                                icon = page.icon(),
                                label = page.title(),
                            )
                        }
                    }
                }
        }
    }
}

/**
 * 页面宿主：唯一的 Scaffold（顶栏 + Snackbar），内容交给 [NavDisplay] 按路由渲染。
 * 右上角放「重启输入法」入口。
 */
@Composable
private fun PageHost(
    uiState: AppUiState,
    backStack: androidx.compose.runtime.snapshots.SnapshotStateList<NavKey>,
    current: NavKey,
    gapMaxLand: Float,
    gapMaxPort: Float,
    padding: PaddingValues,
    railSettled: Boolean,
    onBack: () -> Unit,
    onPush: (NavKey) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()

    // 光标域的框架连接跟随（拆成三个页面后只在外壳里跑一份轮询）
    CursorConnectionEffect()

    // 顶部渐进模糊。backdrop 需要内容层注册进去（见下面的 layerBackdrop），
    // 顶栏的模糊层才能采样到滚动经过的内容。
    val backdrop = rememberTopBlurBackdrop()
    // 只在滚动后才让模糊层出现。这里用 derivedStateOf 让这个布尔量只在
    // 「有/无偏移」翻转时触发重组，而不是每帧重组顶栏 —— 滚动偏移本身
    // 在 BlurredTopBar 的 graphicsLayer 里按帧读取，不进组合。
    val blurActive by remember(backdrop) {
        derivedStateOf { backdrop != null && scrollBehavior.state.contentOffset < 0f }
    }

    var showRestartDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // 用 composition 作用域而不是 LaunchedEffect(key)：
    // 之前把「进行中」标记当 key，在里面把它置回 false 会让 Compose 直接取消当前协程，
    // withContext 在挂起点被中断，结果上报与 Snackbar 都走不到（实测重启生效了但没有任何提示）。
    val scope = rememberCoroutineScope()

    fun startRestart() {
        L.i("event=restart_clicked")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ImeRestarter.forceStop() }
                    .getOrElse { ImeRestarter.Result.Failed(it.message ?: "未知错误") }
            }
            L.i("event=restart_result result=$result")
            when (result) {
                is ImeRestarter.Result.Killed ->
                    snackbarHostState.showSnackbar("已关闭输入法，下次弹出键盘时自动重启", "重启成功")

                is ImeRestarter.Result.Failed ->
                    snackbarHostState.showSnackbar("重启失败：需要 root 授权（KernelSU）", "失败")
            }
        }
    }

    Scaffold(
        topBar = {
            BlurredTopBar(
                backdrop = backdrop,
                active = blurActive,
                scrollOffsetPx = { -scrollBehavior.state.contentOffset },
            ) {
                TopAppBar(
                    title = current.title(),
                    subtitle = current.subtitle(),
                    scrollBehavior = scrollBehavior,
                    // 模糊层在顶栏下面；顶栏自己必须透明，否则会盖住模糊。
                    // 没滚动时不需要模糊，用回主题色，避免透出下面的内容。
                    color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        if (backStack.size > 1) {
                            IconButton(onClick = onBack) {
                                Icon(MiuixIcons.Back, "返回")
                            }
                        }
                    },
                    actions = {
                        // 「重启小爱输入法」只跟输入法域有关：只在键盘外观的页面里出现，
                        // 功能列表 / 光标 / 平行窗口 / 关于都不显示它。
                        if (current.scope() == AppScope.Ime) {
                            DropdownActionMenu { showRestartDialog = true }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding(),
        )
        // 页面内容注册进 backdrop，供顶栏的模糊层采样；
        // 同时把顶栏的 nestedScrollConnection 挂到这一层。
        //
        // 这个 nestedScroll 是必须的：Miuix 文档写明 nestedScrollConnection
        // 「should be attached to a Modifier.nestedScroll in order to keep track of
        // the scroll events」。之前全模块一处都没挂，scrollBehavior.state.contentOffset
        // 恒为 0 —— 顶栏既不收起，渐进模糊也永远不出现（实测「完全没效果」就是这个原因）。
        // 页面内容（顶栏在它自己内部）。
        //
        // 顶栏模糊的 backdrop 是全屏 offscreen 图层：左栏动画期间内容会每帧重新排版，
        // 那一层就会整层重录 —— 实测就是这个卡顿。动画结束（railSettled）再挂回去。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (backdrop != null && railSettled) Modifier.layerBackdrop(backdrop)
                    else Modifier
                )
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
                NavDisplay(
                    backStack = backStack,
                    onBack = onBack,
                    transition = PadPush,
                    // 关掉默认的 0.5 暗层与圆角裁切：这台平板 3200×2136，全屏暗层 + 裁切每帧都要重绘，
                    // 实测发卡；backdropColor 给页面底色，转场期间露出的区域不会是黑的。
                    effects = NavDisplayEffects(
                        enableCornerClip = false,
                        dimAmount = 0f,
                        backdropColor = MiuixTheme.colorScheme.surface,
                    ),
                ) {
                    entry<RouteScopes> {
                        PageSurface {
                            ScopeListPage(
                                onEnter = { scope -> onPush(scope.pages.first()) },
                                onAbout = { onPush(RouteAbout) },
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteImeVirtual> {
                        PageSurface {
                            VirtualKeyboardPage(
                                uiState = uiState,
                                gapMaxLand = gapMaxLand,
                                gapMaxPort = gapMaxPort,
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteImeFloating> {
                        PageSurface {
                            FloatingKeyboardPage(
                                uiState = uiState,
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteImeMaterial> {
                        PageSurface {
                            MaterialPage(
                                uiState = uiState,
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteCursorPreset> {
                        PageSurface {
                            CursorPresetPage(
                                cursorEnabled = uiState.cursorEnabled,
                                onCursorEnabledChange = { checked ->
                                    uiState.cursorEnabled = checked
                                    uiState.save { e -> e.putBoolean(PrefKeys.CURSOR_ENABLED, checked) }
                                },
                                onOpenColors = { onPush(RouteCursorColors) },
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteCursorSize> {
                        PageSurface {
                            CursorSizePage(
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteCursorImport> {
                        PageSurface {
                            CursorImportPage(
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteCursorColors> {
                        PageSurface {
                            CursorColorsPage(
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteParallel> {
                        PageSurface {
                            ParallelPage(
                                uiState = uiState,
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteTextMenu> {
                        PageSurface {
                            TextMenuPage(
                                uiState = uiState,
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                    entry<RouteAbout> {
                        PageSurface {
                            AboutPage(
                                uiState = uiState,
                                padding = padding,
                                scaffoldPadding = contentPadding,
                            )
                        }
                    }
                }
            }
        }

    // Miuix 二次确认弹窗。
    // 用 WindowDialog（独立窗口层）而不是 OverlayDialog：
    // OverlayDialog 依赖 Scaffold 的 MiuixPopupHost，在本应用的多 Scaffold 结构下渲染不出来
    // （实测弹窗完全不出现）。WindowDialog 自带窗口层，跨页面可用，符合 Miuix 对全局弹窗的推荐。
    WindowDialog(
        show = showRestartDialog,
        title = "重启超级小爱输入法？",
        summary = null,
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
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}

/** 顶栏副标题（目前只有颜色设置页用） */
private fun NavKey.subtitle(): String = when (this) {
    RouteCursorColors -> cursorColorsSubtitle()
    else -> ""
}

/** 平板布局断点：横屏平板走左侧栏；竖屏平板 / 手机走底部菜单。 */
private const val RAIL_BREAKPOINT_DP = 840

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

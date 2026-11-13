package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import io.github.skyshadowhero.fancypad.ui.ds.Card
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.ui.ds.DsTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.nav.core.NavKey


/**
 * 一个功能域 = **一个路由**，域内的兄弟页用 `HorizontalPager` 承载。
 *
 * 这是 miuix example 的做法（它的一级标签就是 `Route.Main` + pager）。之前每个兄弟页是
 * 独立路由、切换时替换栈顶，导致：
 * - 整页重建 → `TabRowWithContour` 的 remember 状态丢失 → 指示器永远 snap、没有动画；
 * - 新旧两个 entry 同时参与转场 → tab 行闪现一下。
 *
 * 现在切 tab 只是翻页：路由不变、组合不重建，动画与手势都连续。
 */
/** 宽屏（平板/横屏）判断：和 AppShell 的侧栏断点保持一致。 */
@Composable
fun isWideLayout(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840

@Composable
fun DomainHost(
    scope: AppScope,
    pagerState: androidx.compose.foundation.pager.PagerState,
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
    gapMaxLand: Float,
    gapMaxPort: Float,
    onPush: (NavKey) -> Unit,
) {
    val pages = scope.pages
    val pageContent: @Composable () -> Unit = {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            // 每页必须撑满 pager 高度，否则内容少的页会被交叉轴居中
            Box(modifier = Modifier.fillMaxSize()) {
                DomainPage(
                    page = pages[index],
                    uiState = uiState,
                    padding = padding,
                    scaffoldPadding = scaffoldPadding,
                    gapMaxLand = gapMaxLand,
                    gapMaxPort = gapMaxPort,
                    onPush = onPush,
                )
            }
        }
    }
    if (pages.size > 1 && isWideLayout()) {
        // 平板：兄弟页切换从顶栏 TabRow 改成左侧栏（左列表 + 右详情）
        Row(modifier = Modifier.fillMaxSize()) {
            DomainSidebar(scope = scope, pagerState = pagerState)
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { pageContent() }
        }
    } else {
        pageContent()
    }
}

/** 宽屏侧栏宽度：外壳画分隔线时要对齐这条边。 */
val DomainSidebarWidth = 220.dp

/** 宽屏下的域内切换侧栏（窄屏仍用顶栏 TabRow）。 */
@Composable
private fun DomainSidebar(
    scope: AppScope,
    pagerState: androidx.compose.foundation.pager.PagerState,
) {
    val pages = scope.pages
    val tabScope = rememberCoroutineScope()
    // 直接复用 Miuix 的卡片与列表行（HyperOS 列表的间距/圆角/按压遮罩）。
    Box(
        modifier = Modifier
            .width(DomainSidebarWidth)
            .fillMaxHeight()
            .padding(
                start = 12.dp,
                end = 8.dp,
                top = LocalPageScaffoldBottomlessPadding() + 16.dp,
                bottom = 12.dp,
            ),
    ) {
        Card {
            pages.forEachIndexed { index, page ->
                val selected = pagerState.currentPage == index
                val onClick: () -> Unit = {
                    tabScope.launch { pagerState.animateScrollToPage(index) }
                }
                DsSidebarItem(
                    title = page.title(),
                    selected = selected,
                    onClick = onClick,
                )
            }
        }
    }
}

/** 侧栏条目（左对齐 + 选中灰色遮罩，无勾选标记）。 */
@Composable
private fun DsSidebarItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.onBackground.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        androidx.compose.material3.Text(
            text = title,
            color = colors.onBackground,
            fontSize = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 侧栏顶部让位：与页面内容一致，避开顶栏。 */
@Composable
private fun LocalPageScaffoldBottomlessPadding(): androidx.compose.ui.unit.Dp =
    LocalPageScaffoldPadding.current.calculateTopPadding()

/**
 * 域内兄弟页切换条 —— 放在顶栏的 `header` 槽里。
 *
 * `PageScaffold` 会把它画在 progressive blur **之上**（模糊层在顶栏内部先画，header 后画），
 * 所以文字始终是清晰的，模糊只作用于它背后的滚动内容。
 * 单页域不渲染（一个胶囊没意义）。
 */
@Composable
fun DomainTabs(
    scope: AppScope,
    pagerState: androidx.compose.foundation.pager.PagerState,
) {
    val pages = scope.pages
    if (pages.size <= 1) return
    val tabScope = rememberCoroutineScope()
    TabRowWithContour(
        tabs = pages.map { it.title() },
        selectedTabIndex = pagerState.currentPage,
        onTabSelected = { index ->
            if (index in pages.indices) tabScope.launch { pagerState.animateScrollToPage(index) }
        },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        minWidth = 0.dp,
        maxWidth = 1000.dp,
    )
}

/** 兄弟页内容分发（原来在 NavDisplay 的 entry 里，现在由 pager 承载）。 */
@Composable
private fun DomainPage(
    page: NavKey,
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
    gapMaxLand: Float,
    gapMaxPort: Float,
    onPush: (NavKey) -> Unit,
) {
    when (page) {
        RouteImeVirtual -> VirtualKeyboardPage(
            uiState = uiState,
            gapMaxLand = gapMaxLand,
            gapMaxPort = gapMaxPort,
            padding = padding,
            scaffoldPadding = scaffoldPadding,
        )

        RouteImeFloating -> FloatingKeyboardPage(
            uiState = uiState,
            padding = padding,
            scaffoldPadding = scaffoldPadding,
        )

        RouteImeStylus -> StylusPage(
            uiState = uiState,
            padding = padding,
            scaffoldPadding = scaffoldPadding,
        )

        RouteCursorPreset -> CursorPresetPage(
            cursorEnabled = uiState.cursorEnabled,
            onCursorEnabledChange = { checked ->
                uiState.cursorEnabled = checked
                uiState.save { e -> e.putBoolean(PrefKeys.CURSOR_ENABLED, checked) }
            },
            onOpenColors = { onPush(RouteCursorColors) },
            padding = padding,
            scaffoldPadding = scaffoldPadding,
        )

        RouteCursorSize -> CursorSizePage(padding = padding, scaffoldPadding = scaffoldPadding)
        RouteCursorImport -> CursorImportPage(padding = padding, scaffoldPadding = scaffoldPadding)
        RouteCursorColors -> CursorColorsPage(padding = padding, scaffoldPadding = scaffoldPadding)

        RouteParallel -> ParallelPage(
            uiState = uiState,
            padding = padding,
            scaffoldPadding = scaffoldPadding,
        )

        RouteCaptionFeatures -> CaptionFeaturesPage(
            uiState = uiState,
            padding = padding,
            scaffoldPadding = scaffoldPadding,
        )

        RouteCaptionButtons -> CaptionButtonsPage(
            uiState = uiState,
            padding = padding,
            scaffoldPadding = scaffoldPadding,
        )

        RouteAppMenu -> AppMenuPage(
            uiState = uiState,
            padding = padding,
            scaffoldPadding = scaffoldPadding,
        )
    }
}
/** Miuix 侧栏行：选中项是一层比按压遮罩更深的灰色底。 */
@Composable
private fun MiuixSidebarRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) colors.onBackground.copy(alpha = 0.14f) else Color.Transparent,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        androidx.compose.material3.Text(
            text = title,
            color = if (selected) colors.accent else colors.onBackground,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}

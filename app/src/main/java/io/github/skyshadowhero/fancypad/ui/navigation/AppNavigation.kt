package io.github.skyshadowhero.fancypad.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ui.components.rememberTopBlurBackdrop
import io.github.skyshadowhero.fancypad.ui.ds.DsTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassNavigationRail
import top.yukonga.miuix.kmp.glass.GlassNavigationRailDefaults
import top.yukonga.miuix.kmp.glass.GlassNavigationRailState
import top.yukonga.miuix.kmp.glass.rememberGlassNavigationRailState

/** 竖屏/窄屏使用的底栏外观。序号会持久化，已有值不能重排。 */
enum class BottomBarStyle(
    val label: String,
    val summary: String,
) {
    Glass("OS4 柔光玻璃", "Miuix 0.9.5 官方玻璃导航栏"),
    Classic("经典底栏", "保留原有的悬浮胶囊样式"),
}

/** 仅平板横屏启用官方可变形导航：左侧栏可收成顶部菜单。 */
fun Configuration.usesTabletAdaptiveNavigation(): Boolean =
    smallestScreenWidthDp >= 600 && orientation == Configuration.ORIENTATION_LANDSCAPE

internal fun rootNavigationItems(): List<GlassNavigationItem> = RootTab.entries.map { tab ->
    GlassNavigationItem(
        icon = tab.icon,
        label = tab.label,
        contentDescription = tab.label,
    )
}

internal fun pageNavigationItems(pages: List<top.yukonga.miuix.kmp.nav.core.NavKey>): List<GlassNavigationItem> =
    pages.map { page ->
        GlassNavigationItem(
            icon = page.icon(),
            label = page.title(),
            contentDescription = page.title(),
        )
    }

/** Miuix OS4 官方柔光玻璃底栏。 */
@Composable
fun GlassRootBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val items = remember { rootNavigationItems() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 68.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassNavigationBar(
            items = items,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            backdrop = backdrop,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
        )
    }
}

/**
 * Miuix 0.9.5 的平板导航：展开态是左侧栏，点击内置按钮后连续变形成顶部胶囊菜单。
 */
@Composable
fun TabletAdaptiveNavigation(
    items: List<GlassNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    state: GlassNavigationRailState,
    modifier: Modifier = Modifier,
) {
    GlassNavigationRail(
        items = items,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        backdrop = backdrop,
        state = state,
        expandContentDescription = "展开为左侧菜单",
        collapseContentDescription = "收起为顶部菜单",
        modifier = modifier,
    )
}

/**
 * 一个路由条目自己的平板导航场景。
 *
 * 不透明背景、页面正文和 Rail 都属于同一个 `NavDisplay` entry；因此 push / pop 与预测返回
 * 会整体移动和加遮罩。正文单独录入 backdrop，Rail 作为兄弟覆盖层，不会采样到自身。
 */
@Composable
fun TabletAdaptivePage(
    enabled: Boolean,
    items: List<GlassNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (GlassNavigationRailState?) -> Unit,
) {
    if (!enabled || items.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(DsTheme.colorScheme.surface),
        ) {
            content(null)
        }
        return
    }

    // 每个路由条目持有自己的状态与交互节点。根菜单和功能域菜单在转场期间可以同时
    // 存在，但不会热替换同一个 Rail 的 items / 点击回调。
    val state = rememberGlassNavigationRailState(initialExpanded = true)
    val backdrop = rememberTopBlurBackdrop()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DsTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .tabletNavigationContent(state),
            ) {
                content(state)
            }
        }
        TabletAdaptiveNavigation(
            items = items,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            backdrop = backdrop,
            state = state,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 展开左栏时让页面同步让位；动画进度只在布局阶段读取，不逐帧重组页面。 */
fun Modifier.tabletNavigationContent(state: GlassNavigationRailState): Modifier =
    layout { measurable, constraints ->
        val inset = (
            (GlassNavigationRailDefaults.ExpandedWidth + GlassNavigationRailDefaults.Margin) *
                state.progress.coerceIn(0f, 1f)
            ).roundToPx().coerceIn(0, constraints.maxWidth)
        val childWidth = (constraints.maxWidth - inset).coerceAtLeast(0)
        val placeable = measurable.measure(
            constraints.copy(minWidth = childWidth, maxWidth = childWidth),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeRelative(inset, 0)
        }
    }

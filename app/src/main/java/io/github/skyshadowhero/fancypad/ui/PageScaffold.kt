package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ui.ds.DsTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面外壳：**标题栏与底栏都属于页面本身**，而不是外层壳的固定层。
 *
 * 这样三个要求同时成立：
 * 1. 返回 / 转场时，标题、底栏和内容一起滑走（它们都在 NavDisplay 的 entry 里）；
 * 2. 内容只吃一次 inset：`content` 拿到的是本 Scaffold 的 `innerPadding`，
 *    不再叠加外层顶栏高度 → 从顶部开始排，不会有那块空白；
 * 3. 次级页面也保留底栏，不会「一进二级页底栏突然消失」。
 *
 * Miuix 用 `Scaffold + TopAppBar`（标题随滚动收起）。
 */
@Composable
fun PageScaffold(
    title: String,
    subtitle: String = "",
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    scaffoldPadding: PaddingValues = PaddingValues(0.dp),
    /** 宽屏分栏分隔线：画在模糊层之上、并随页面转场一起动（进/出与预测性返回同步）。 */
    sidebarDivider: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberTopBlurBackdrop()
    // 外层 Box 只为了把「分栏分隔线」画在内容（以及 progressive blur 的 backdrop）之上：
    // 这样它既不会被顶栏模糊糊掉，又会跟随页面转场/预测性返回一起进出。
    Box(modifier = Modifier.fillMaxSize()) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurActive by remember(backdrop) {
        derivedStateOf { backdrop != null && scrollBehavior.state.contentOffset < 0f }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BlurredTopBar(
                backdrop = backdrop,
                active = blurActive,
                scrollOffsetPx = { -scrollBehavior.state.contentOffset },
            ) {
                PageTopAppBar(
                    title = title,
                    subtitle = subtitle,
                    scrollBehavior = scrollBehavior,
                    header = header,
                    canGoBack = canGoBack,
                    onBack = onBack,
                    barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface,
                )
            }
        },
        bottomBar = { bottomBar?.invoke() },
    ) { innerPadding ->
        // Miuix Scaffold lays body content out with a finite viewport. Keep the scrollable
        // page directly in that slot and give it Scaffold's actual insets as list padding.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            CompositionLocalProvider(LocalPageScaffoldPadding provides innerPadding) {
                content(innerPadding)
            }
        }
    }

        if (sidebarDivider) {
            Box(
                modifier = Modifier
                    .padding(start = DomainSidebarWidth, top = 56.dp)
                    .fillMaxHeight()
                    .width(0.5.dp)
                    .background(DsTheme.colorScheme.separator),
            )
        }
    }
}

@Composable
private fun PageTopAppBar(
    title: String,
    subtitle: String,
    scrollBehavior: ScrollBehavior,
    header: (@Composable () -> Unit)?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    barColor: Color,
) {
    val navigationIcon: @Composable () -> Unit = {
        if (canGoBack) {
            IconButton(onClick = onBack, modifier = Modifier.zIndex(2f)) {
                Icon(MiuixIcons.Back, contentDescription = "返回")
            }
        }
    }
    if (header == null) {
        SmallTopAppBar(
            title = title,
            subtitle = subtitle,
            color = barColor,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
        )
    } else {
        // header（TabRow）放在 AppBar **外面**、但仍在 BlurredTopBar 的内容里：
        // 这样整块顶栏（标题 + TabRow）的高度会被模糊层 matchParentSize 覆盖，
        // 模糊带高度才和视觉上的顶栏一致。
        Column(modifier = Modifier.fillMaxWidth()) {
            SmallTopAppBar(
                title = title,
                subtitle = subtitle,
                color = barColor,
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
            )
            header()
        }
    }
}

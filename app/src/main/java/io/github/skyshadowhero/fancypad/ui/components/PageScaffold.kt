package io.github.skyshadowhero.fancypad.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
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
 * 平板 Rail 展开时显示当前页标题；Rail 收成顶部菜单时，标题按同一进度淡出。
 * 窄屏仍不显示标题，只保留返回按钮和域内标签。
 */
@Composable
fun PageScaffold(
    modifier: Modifier = Modifier,
    title: String = "",
    titleAlpha: () -> Float = { 0f },
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    actions: (@Composable RowScope.() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    scaffoldPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberTopBlurBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val showTopBar = canGoBack || actions != null || header != null || title.isNotEmpty()
    CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (showTopBar) GlassTopAppBar(
                title = title,
                largeTitle = title,
                expandable = false,
                // 0.9.5-beta04 的组件内置路径会调用 progressiveTextureBlur，
                // 仅在有效 backdrop 存在时启用，避免退回旧的普通模糊实现。
                progressiveBlur = backdrop != null,
                backdrop = backdrop,
                scrollBehavior = scrollBehavior,
                titleAlpha = titleAlpha,
                navigationIcon = {
                    if (canGoBack) {
                        GlassIconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                actions = { actions?.invoke(this) },
                bottomContent = { header?.invoke() },
            )
        },
        bottomBar = { bottomBar?.invoke() },
    ) { innerPadding ->
        val resolvedPadding = PaddingValues(
            start = maxOf(
                innerPadding.calculateStartPadding(layoutDirection),
                scaffoldPadding.calculateStartPadding(layoutDirection),
            ),
            top = maxOf(innerPadding.calculateTopPadding(), scaffoldPadding.calculateTopPadding()),
            end = maxOf(
                innerPadding.calculateEndPadding(layoutDirection),
                scaffoldPadding.calculateEndPadding(layoutDirection),
            ),
            bottom = maxOf(innerPadding.calculateBottomPadding(), scaffoldPadding.calculateBottomPadding()),
        )
        // Miuix Scaffold lays body content out with a finite viewport. Keep the scrollable
        // page directly in that slot and give it Scaffold's actual insets as list padding.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            CompositionLocalProvider(LocalPageScaffoldPadding provides resolvedPadding) {
                content(resolvedPadding)
            }
        }
    }
    }
}

package com.skyler.typemod.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.skyler.typemod.L
import com.skyler.typemod.PrefKeys
import com.skyler.typemod.RemoteConfig
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.window.WindowDialog

/** 导航项 */
enum class AppPage(val label: String) {
    Settings("分离键盘"),
    Spaces("间距"),
    About("关于"),
}

/**
 * 自适应外壳：横屏平板走左侧栏，竖屏 / 手机走底部菜单。
 * 配置装载已在 App() 完成，这里只负责渲染与交互。
 */
@Composable
fun AppShell(
    uiState: AppUiState,
    padding: PaddingValues,
) {
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

    val pages = AppPage.entries

    if (useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            val railState = rememberNavigationRailState(initialValue = NavigationRailValue.Expanded)
            NavigationRail(state = railState) {
                pages.forEachIndexed { index, page ->
                    NavigationRailItem(
                        selected = uiState.page == index,
                        onClick = { uiState.page = index },
                        icon = page.icon(),
                        label = page.label,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                PageHost(uiState, pages, gapMaxLand, gapMaxPort, padding)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                PageHost(uiState, pages, gapMaxLand, gapMaxPort, padding)
            }
            NavigationBar {
                pages.forEachIndexed { index, page ->
                    NavigationBarItem(
                        selected = uiState.page == index,
                        onClick = { uiState.page = index },
                        icon = page.icon(),
                        label = page.label,
                    )
                }
            }
        }
    }
}

/** 页面宿主：持有唯一的 Scaffold，右上角放「重启输入法」入口。 */
@Composable
private fun PageHost(
    uiState: AppUiState,
    pages: List<AppPage>,
    gapMaxLand: Float,
    gapMaxPort: Float,
    padding: PaddingValues,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var showRestartDialog by remember { mutableStateOf(false) }
    var restartPending by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(restartPending) {
        if (!restartPending) return@LaunchedEffect
        restartPending = false
        L.i("event=restart_clicked")
        val ok = RemoteConfig.requestImeRestart()
        L.i("event=restart_write_result ok=$ok")
        if (ok) {
            snackbarHostState.showSnackbar("已发送重启请求：下次弹出键盘时生效", "请求成功")
        } else {
            snackbarHostState.showSnackbar("未连接到 LSPosed 框架，无法发送重启请求", "请求失败")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = pages[uiState.page.coerceIn(0, pages.size - 1)].title(),
                scrollBehavior = scrollBehavior,
                actions = {
                    DropdownActionMenu { showRestartDialog = true }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding(),
        )
        when (pages[uiState.page.coerceIn(0, pages.size - 1)]) {
            AppPage.Settings -> SettingsPage(
                uiState = uiState,
                gapMaxLand = gapMaxLand,
                gapMaxPort = gapMaxPort,
                padding = padding,
                scaffoldPadding = contentPadding,
            )

            AppPage.Spaces -> SpacesPage(
                uiState = uiState,
                padding = padding,
                scaffoldPadding = contentPadding,
            )

            AppPage.About -> AboutPage(
                uiState = uiState,
                padding = padding,
                scaffoldPadding = contentPadding,
            )
        }
    }

    // Miuix 二次确认弹窗。
    // 用 WindowDialog（独立窗口层）而不是 OverlayDialog：
    // OverlayDialog 依赖 Scaffold 的 MiuixPopupHost，在本应用的多 Scaffold 结构下渲染不出来
    // （实测弹窗完全不出现）。WindowDialog 自带窗口层，跨页面可用，符合 Miuix 对全局弹窗的推荐。
    WindowDialog(
        show = showRestartDialog,
        title = "重启输入法？",
        summary = "即将强制关闭并重启「超级小爱输入法」进程。" +
            "下次弹出键盘时生效，用于让改动（尤其是按键圆角这类构建期参数）完全应用。",
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
                        restartPending = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}

private fun AppPage.title(): String = when (this) {
    AppPage.Settings -> "分离键盘调节"
    AppPage.Spaces -> "间距"
    AppPage.About -> "关于"
}

private fun AppPage.icon() = when (this) {
    AppPage.Settings -> MiuixIcons.Tune
    AppPage.Spaces -> MiuixIcons.More
    AppPage.About -> MiuixIcons.Info
}

/** 平板布局断点：横屏平板走左侧栏；竖屏平板 / 手机走底部菜单。 */
private const val RAIL_BREAKPOINT_DP = 840

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
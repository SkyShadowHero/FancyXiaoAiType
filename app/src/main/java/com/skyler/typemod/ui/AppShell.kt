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
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.window.WindowDialog

/** 导航项 */
enum class AppPage(val label: String) {
    Settings("分离键盘"),
    Spaces("间距"),
    Material("超级材质"),
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
            "2. 作用域已勾选 com.xiaomi.type\n" +
            "3. 启用后已重启输入法进程\n\n" +
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

    val pages = AppPage.entries

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

            AppPage.Material -> MaterialPage(
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
    AppPage.Material -> "超级材质"
    AppPage.About -> "关于"
}

private fun AppPage.icon() = when (this) {
    AppPage.Settings -> MiuixIcons.Tune
    AppPage.Spaces -> MiuixIcons.More
    AppPage.Material -> MiuixIcons.Background
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
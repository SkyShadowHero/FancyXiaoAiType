package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.skyshadowhero.fancypad.BuildConfig
import io.github.skyshadowhero.fancypad.RemoteConfig
import io.github.skyshadowhero.fancypad.ui.ds.*
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.NavigationGlassMaterial

/**
 * 首页：KernelSU 风格的卡片，一眼看清「这个模块现在到底有没有在生效」。
 *
 * 状态判定分三层，缺一层都会「开了没反应」：
 * 1. **作用域**——目标包没勾进 LSPosed 作用域，代码根本不会被注入；
 * 2. **开关**——域内的总开关没打开（每个域都有各自的总闸）；
 * 3. **进程**——作用域有了、开关也开了，但目标进程还没重启（system_server / SystemUI
 *    是开机注入的），会显示「等待进程启动」；正在跑旧代码的进程会显示「需重启」。
 *
 * 点未加入作用域的卡片不会进设置页，只会跳到「功能」页看缺哪个包。
 */
@Composable
fun HomePage(
    uiState: AppUiState,
    scopePackages: Set<String>,
    onOpenScope: (AppScope) -> Unit,
    onOpenFeatures: () -> Unit,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val running = RemoteConfig.runningTargets()
    val api = RemoteConfig.apiVersion()
    val framework = RemoteConfig.frameworkName()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            // Tilt rotates around the touch point; a little outer room keeps the first card
            // from being clipped by LazyColumn's viewport without adding space inside it.
            top = scaffoldPadding.calculateTopPadding() + if (isGlasmio) 24.dp else 16.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
    ) {
        // ---------------- 模块总体状态 ----------------
        item {
            val healthy = framework != null && scopePackages.isNotEmpty()
            HomeStatusCard(
                healthy = healthy,
                framework = framework,
                api = api,
                scopeCount = scopePackages.size,
                runningCount = running.size,
                staleCount = running.count {
                    it.state == io.github.libxposed.service.HookedTarget.State.STALE
                },
            )
        }

        // 功能域列表只在「功能」页出现，首页不再重复一份（这里只留一句引导）
        item {
            Card {
                ArrowPreference(
                    title = "功能设置",
                    summary = "各功能域的作用域状态与开关都在「功能」页",
                    onClick = onOpenFeatures,
                )
            }
        }
    }
}

@Composable
private fun HomeStatusCard(
    healthy: Boolean,
    framework: String?,
    api: Int?,
    scopeCount: Int,
    runningCount: Int,
    staleCount: Int,
) {
    val statusSurface = if (healthy) Color(0xFFDDF8E5) else Color(0xFFFFE1DF)
    val statusAccent = if (healthy) Color(0xFF2ECC71) else Color(0xFFF04438)
    val primaryText = Color(0xFF111111)
    val secondaryText = Color(0xFF626262)
    val statusText = if (framework == null) {
        "未连接到 LSPosed 框架，所有功能都不会生效。"
    } else {
        "$framework（API $api）· 作用域 $scopeCount 个包 · 已注入 $runningCount 个进程"
    }

    if (isGlasmio) {
        Box(modifier = Modifier.fillMaxWidth()) {
            GlassSurface(
                // 与下方选项卡片一致：GlasmioGroupedList 用的是 26dp 连续圆角，
                // 这里给 16dp 会显得比列表「方」一圈。
                shape = RoundedCornerShape(26.dp),
                style = GlassSurfaceStyle.Navigation,
                material = NavigationGlassMaterial.Clear,
                onClick = {},
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(2.dp)
                        .background(statusSurface.copy(alpha = 0.22f), RoundedCornerShape(24.dp)),
                ) {
                    HomeStatusCardContent(
                        healthy, statusAccent, primaryText, secondaryText,
                        statusText, staleCount,
                    )
                }
            }
        }
    } else {
        MiuixCard(
            modifier = Modifier.fillMaxWidth(),
            colors = MiuixCardDefaults.defaultColors(color = statusSurface, contentColor = primaryText),
            insideMargin = PaddingValues(0.dp),
            pressFeedbackType = PressFeedbackType.Tilt,
            onClick = {},
        ) {
            HomeStatusCardContent(
                healthy, statusAccent, primaryText, secondaryText,
                statusText, staleCount,
            )
        }
    }
}

@Composable
private fun HomeStatusCardContent(
    healthy: Boolean,
    accent: Color,
    primaryText: Color,
    secondaryText: Color,
    statusText: String,
    staleCount: Int,
) {
    if (!healthy) {
        // 「模块未激活」：标题 + 一句指引 + 右侧大图标（与参考设计一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 128.dp)
                .padding(start = 18.dp, top = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("模块未激活", style = DsTheme.textStyles.headline, color = primaryText)
                Spacer(Modifier.height(8.dp))
                Text(
                    "请在 LSPosed 中启用并重启系统界面",
                    style = DsTheme.textStyles.body1,
                    color = primaryText,
                )
            }
            Box(
                modifier = Modifier.width(96.dp).height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusMark(
                    healthy = false,
                    color = accent,
                    modifier = Modifier.offset(x = 18.dp),
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .padding(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(if (healthy) "工作中" else "未工作", style = DsTheme.textStyles.headline, color = primaryText)
            Spacer(Modifier.height(4.dp))
            Text("版本：${BuildConfig.VERSION_NAME}", style = DsTheme.textStyles.body1, color = primaryText)
            Spacer(Modifier.height(16.dp))
            Text(if (healthy) "LSPosed" else "LSPosed 未连接", style = DsTheme.textStyles.body1, color = primaryText)
            Text(statusText, style = DsTheme.textStyles.footnote1, color = secondaryText)
            if (staleCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "$staleCount 个进程仍在运行旧代码，重启后生效。",
                    style = DsTheme.textStyles.footnote1,
                    color = secondaryText,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier.width(104.dp).height(150.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            StatusMark(
                healthy = healthy,
                color = accent,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 44.dp, y = 44.dp),
            )
        }
    }
}

@Composable
private fun StatusMark(healthy: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(104.dp)) {
        val strokeWidth = size.minDimension * 0.105f
        drawCircle(color = color, style = Stroke(width = strokeWidth))
        val mark = Path().apply {
            if (healthy) {
                moveTo(size.width * 0.25f, size.height * 0.52f)
                lineTo(size.width * 0.43f, size.height * 0.69f)
                lineTo(size.width * 0.76f, size.height * 0.34f)
            } else {
                moveTo(size.width * 0.34f, size.height * 0.34f)
                lineTo(size.width * 0.66f, size.height * 0.66f)
                moveTo(size.width * 0.66f, size.height * 0.34f)
                lineTo(size.width * 0.34f, size.height * 0.66f)
            }
        }
        drawPath(
            path = mark,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

/** 功能域图标：自带矢量优先，否则 Miuix 图标。 */
@Composable
internal fun ScopeIcon(scope: AppScope, alpha: Float = 1f) {
    if (scope.iconRes != 0) {
        Icon(
            painter = painterResource(scope.iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp).alpha(alpha),
            tint = DsTheme.colorScheme.onBackgroundVariant,
        )
    } else {
        Icon(
            imageVector = scope.icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp).alpha(alpha),
            tint = DsTheme.colorScheme.onBackgroundVariant,
        )
    }
}

/** 这个域里是否有任意一个总开关打开（用来区分「没启用」与「启用了但没生效」）。 */
internal fun AppScope.enabledIn(s: AppUiState): Boolean = when (this) {
    AppScope.Ime -> s.gapEnabled || s.cornerEnabled || s.marginEnabled || s.spaceEnabled ||
        s.candidateEnabled || s.floatBarEnabled || s.materialEnabled || s.stylusEnabled

    AppScope.Cursor -> s.cursorEnabled
    AppScope.Parallel -> s.embeddingEnabled
    AppScope.Caption -> s.captionEnabled || s.captionCloseAsMinus || s.captionForceClose ||
        s.captionHideCurrentState || s.captionAlwaysNewWindow || s.captionHideDots

    AppScope.TextMenu -> s.toolbarEnabled
    AppScope.AppMenu -> s.appMenuEnabled
}

/** 这个域对应的进程里，正在跑当前代码的个数。 */
internal fun AppScope.runningCount(
    targets: List<io.github.libxposed.service.HookedTarget>,
): Int = targets.count {
    it.state == io.github.libxposed.service.HookedTarget.State.UP_TO_DATE && matchesProcess(it.processName)
}

/** 还跑着旧代码的进程数。 */
internal fun AppScope.staleCount(
    targets: List<io.github.libxposed.service.HookedTarget>,
): Int = targets.count {
    it.state == io.github.libxposed.service.HookedTarget.State.STALE && matchesProcess(it.processName)
}

private fun AppScope.matchesProcess(name: String): Boolean {
    val host = name.substringBefore(':')
    return when (this) {
        AppScope.Ime -> host == "com.xiaomi.type"
        AppScope.Cursor -> host == "system" || host == "system_server" || host == "android"
        AppScope.Parallel, AppScope.Caption, AppScope.TextMenu -> host == "com.android.systemui"
        // 右键菜单：注入的是用户自己加的目标应用，这里按「非系统、非输入法」粗判
        AppScope.AppMenu -> host != "com.xiaomi.type" && host != "system" &&
            host != "system_server" && host != "android" && host != "com.android.systemui"
    }
}

/** 卡片副标题：把「缺作用域 / 未启用 / 已启用但没生效 / 生效中」说成人话。 */
private fun AppScope.statusText(
    available: Boolean,
    missing: List<String>,
    enabled: Boolean,
    injected: Int,
    stale: Int,
): String = when {
    !available -> "未加入作用域：" + missing.joinToString("、")
    this == AppScope.AppMenu && !enabled -> "需把目标应用加入作用域并在此开启"
    !enabled -> "已就绪，未启用"
    stale > 0 -> "需重启：$stale 个进程仍运行旧代码"
    injected > 0 -> "生效中"
    else -> "已启用，等待进程启动"
}

package io.github.skyshadowhero.fancypad.ui.pages

import io.github.skyshadowhero.fancypad.ui.*
import io.github.skyshadowhero.fancypad.ui.components.*
import io.github.skyshadowhero.fancypad.ui.utils.*

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.PackageListCodec
import io.github.skyshadowhero.fancypad.PrefKeys
import io.github.skyshadowhero.fancypad.RemoteConfig
import io.github.skyshadowhero.fancypad.ViewScaleTargets
import io.github.skyshadowhero.fancypad.ui.ds.*
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.RangeSlider
import kotlinx.coroutines.withContext

/**
 * 「平板动画修复」页：两件事，都是"平板被砍掉的动画"。
 *
 * ## 一、平行窗口动画（原 os4平行窗口动画fix模块）：作用域 `com.android.systemui`。
 *
 * 问题：HyperOS 的平行窗口（Activity Embedding）左右切换 / 进二级页时卡顿、丢动画。
 * 真机实测只有「跳切」这一层有效：`createAnimationAdapters()` 返回空 →
 * `shouldUseJumpCut*` 为 true → 这次转场根本不播。把判定钉成 false 即恢复动画。
 * （早先还 hook 过 Folme 引擎与转场合并，真机对比无可见效果，已删除。）
 *
 * 总开关关掉即完全还原成系统原行为。
 *
 * ## 一·五、分栏无级调节：作用域 = **各目标 App 自身**（与右键菜单一致）
 *
 * 拖中间分隔线时比例本来是连续的，但松手会被吸附、且两侧比例被限死在 0.35~0.65。
 * 打开后松手停在拖到的位置，两侧的可拖范围用双滑块调（默认系统值 0.35 / 0.65）。
 * 生效应用要**先在 LSPosed 作用域里加上它，再强停一次**（和右键菜单同一套注入路径）。
 *
 * 小窗控制点的按钮条（× → − / 红色强制关闭）已拆到独立的「小窗控制器」页 ——
 * 虽然同属 `com.android.systemui`，但和这里没有技术关联。
 *
 * ⚠ 这一域是 SystemUI 启动时注入的：开关改完即时生效（Hook 侧读的是缓存快照，
 * 偏好一变就刷新），但**模块本身的升级/作用域变更需要重启一次**。
 *
 * ## 二、一镜到底（HyperViewScale）：作用域 `system` + **各目标 App 自身**
 *
 * 列表点开→详情页封面从原位展开、返回再收回。OS4 手机有、平板没有，原因是
 * `HyperViewScaleTransitionImpl` / `HyperViewScaleConfigManager` /
 * `HyperViewScaleRemoteManager` 三处闸都写着 `!Build.IS_TABLET`。
 * 动画本体和逐 App 配置（`/system_ext/etc/view_scale/view_scale_config.json`，
 * 小红书和 B 站都已收录）都是现成的，这里只把闸放开。
 *
 * 想让某个 App 也吃上：LSPosed 作用域里加上它自己的包名，然后重启那个 App。
 * 系统没收录的包不会有反应（配置表是小米云端下发的，模块不伪造）。
 */
@Composable
fun ParallelPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
): Unit {
    val context = LocalContext.current
    var requesting by remember { mutableStateOf(false) }
    var scopeSummary by remember { mutableStateOf("把系统支持一镜到底的应用加进作用域") }

    // 平行窗口无级调节：生效应用选择器（只列作用域内应用，和右键菜单一致）
    var showRatioPicker by remember { mutableStateOf(false) }
    var ratioApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    LaunchedEffect(showRatioPicker) {
        if (!showRatioPicker || ratioApps.isNotEmpty()) return@LaunchedEffect
        val scopedPkgs = runCatching { RemoteConfig.service()?.scope }.getOrNull()
        ratioApps = withContext(Dispatchers.IO) { AppCatalog.load(context) }
            .filter { scopedPkgs.isNullOrEmpty() || scopedPkgs.contains(it.packageName) }
    }

    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = scaffoldPadding.calculateTopPadding() + 8.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SmallTitle("平行窗口动画修复") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.embeddingEnabled,
                    onCheckedChange = { checked ->
                        uiState.embeddingEnabled = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.EMBEDDING_ENABLED, checked) }
                    },
                    title = "恢复 AOSP 原生动画",
                    summary = "只控制本卡动画；分栏无级由下方独立开关控制",
                )
                AnimatedVisibility(visible = uiState.embeddingEnabled) {
                    Column {
                        SwitchPreference(
                            checked = uiState.embeddingFolmeDisable,
                            onCheckedChange = { checked ->
                                uiState.embeddingFolmeDisable = checked
                                uiState.save { e -> e.putBoolean(PrefKeys.EMBEDDING_FOLME_DISABLE, checked) }
                            },
                            title = "禁用 Folme 引擎",
                            summary = "改用 AOSP 的 DefaultAnimationProvider",
                        )
                        SwitchPreference(
                            checked = uiState.embeddingMergeDisable,
                            onCheckedChange = { checked ->
                                uiState.embeddingMergeDisable = checked
                                uiState.save { e -> e.putBoolean(PrefKeys.EMBEDDING_MERGE_DISABLE, checked) }
                            },
                            title = "禁止转场合并",
                            summary = "合并失败会把正在播放的动画取消掉",
                        )
                        SwitchPreference(
                            checked = uiState.embeddingJumpCutDisable,
                            onCheckedChange = { checked ->
                                uiState.embeddingJumpCutDisable = checked
                                uiState.save { e -> e.putBoolean(PrefKeys.EMBEDDING_JUMPCUT_DISABLE, checked) }
                            },
                            title = "禁止跳切",
                            summary = "跳切等于这次转场不播动画",
                        )
                    }
                }
            }
        }

        item { SmallTitle("平行窗口分栏无级调节") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.embeddingRatioEnabled,
                    onCheckedChange = { checked ->
                        uiState.embeddingRatioEnabled = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.EMBEDDING_RATIO_ENABLED, checked) }
                    },
                    title = "分栏无级调节",
                    summary = "关闭后下一次拖动恢复 35% / 50% / 65% 系统停靠；在 LSPosed 停用整个模块后还需重启目标应用",
                )
                AnimatedVisibility(visible = uiState.embeddingRatioEnabled) {
                    Column {
                        RatioRangeSlider(
                            lower = uiState.embeddingRatioMin,
                            upper = uiState.embeddingRatioMax,
                            onValueChange = { lower, upper ->
                                uiState.embeddingRatioMin = lower
                                uiState.embeddingRatioMax = upper
                            },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.EMBEDDING_RATIO_MIN, uiState.embeddingRatioMin)
                                    e.putFloat(PrefKeys.EMBEDDING_RATIO_MAX, uiState.embeddingRatioMax)
                                }
                            },
                        )
                        ArrowPreference(
                            title = "生效的应用",
                            summary = runCatching {
                                val n = PackageListCodec.decode(uiState.embeddingRatioApps).size
                                if (n == 0) "未选择（不生效）" else "已选择 $n 个"
                            }.getOrDefault("未选择（不生效）"),
                            onClick = { showRatioPicker = true },
                        )
                    }
                }
            }
        }

        item { SmallTitle("一镜到底") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.hyperViewScaleEnabled,
                    onCheckedChange = { checked ->
                        uiState.hyperViewScaleEnabled = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.HYPER_VIEW_SCALE_ENABLED, checked) }
                    },
                    title = "一镜到底动画（HyperViewScale）",
                )
                ArrowPreference(
                    title = "一键申请作用域",
                    summary = scopeSummary,
                    enabled = !requesting,
                    onClick = {
                        requesting = true
                        val pkgs = ViewScaleTargets.packages()
                        RemoteConfig.requestScope(pkgs) { ok, message ->
                            // 回调可能来自 binder 线程，UI 状态要回主线程改
                            Handler(Looper.getMainLooper()).post {
                                requesting = false
                                scopeSummary = if (ok) "已加入 ${pkgs.size} 个应用" else "申请失败：$message"
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        }
    }

    val selectedRatioApps = runCatching { PackageListCodec.decode(uiState.embeddingRatioApps) }
        .getOrDefault(emptySet())
    ScopeAppPickerDialog(
        show = showRatioPicker,
        apps = ratioApps,
        selected = selectedRatioApps,
        summary = "只列作用域内的应用；已选择 ${selectedRatioApps.size} 个（不选 = 不生效）",
        onToggle = { pkg ->
            val next = runCatching { PackageListCodec.decode(uiState.embeddingRatioApps) }
                .getOrDefault(emptySet())
                .toMutableSet()
                .apply { if (!add(pkg)) remove(pkg) }
            uiState.embeddingRatioApps = PackageListCodec.encode(next)
            uiState.save { e -> e.putString(PrefKeys.EMBEDDING_RATIO_APPS, uiState.embeddingRatioApps) }
        },
        onDismissRequest = { showRatioPicker = false },
    )
}

/**
 * 分栏范围双滑块（Miuix [RangeSlider]）：左边是下限、右边是上限。
 *
 * 拖动中只改内存（标题行实时显示百分比），松手才落盘；步进 1%。
 * 两端都允许落在系统默认值 0.35 / 0.65 上，所以「不改」就完全是系统原行为。
 */
@Composable
private fun RatioRangeSlider(
    lower: Float,
    upper: Float,
    onValueChange: (Float, Float) -> Unit,
    onCommit: () -> Unit,
) {
    val span = PrefKeys.EMBEDDING_RATIO_SLIDER_MAX - PrefKeys.EMBEDDING_RATIO_SLIDER_MIN
    val steps = (span / PrefKeys.EMBEDDING_RATIO_STEP).toInt() - 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("分栏范围", style = DsTheme.textStyles.body1)
            Text(
                "${ratioText(lower)} ~ ${ratioText(upper)}",
                style = DsTheme.textStyles.body2,
                color = DsTheme.colorScheme.onBackgroundVariant,
            )
        }
        RangeSlider(
            value = lower..upper,
            onValueChange = { range ->
                onValueChange(quantizeRatio(range.start), quantizeRatio(range.endInclusive))
            },
            valueRange = PrefKeys.EMBEDDING_RATIO_SLIDER_MIN..PrefKeys.EMBEDDING_RATIO_SLIDER_MAX,
            steps = steps,
            onValueChangeFinished = onCommit,
            showKeyPoints = true,
            keyPoints = listOf(
                PrefKeys.EMBEDDING_RATIO_DEFAULT_MIN,
                PrefKeys.EMBEDDING_RATIO_DEFAULT_MAX,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 吸附到 1% 步进并夹回量程。 */
private fun quantizeRatio(value: Float): Float {
    val snapped = (value / PrefKeys.EMBEDDING_RATIO_STEP).roundToInt() * PrefKeys.EMBEDDING_RATIO_STEP
    return snapped.coerceIn(PrefKeys.EMBEDDING_RATIO_SLIDER_MIN, PrefKeys.EMBEDDING_RATIO_SLIDER_MAX)
}

private fun ratioText(value: Float): String = "${(value * 100).roundToInt()}%"

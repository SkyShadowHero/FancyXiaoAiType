package io.github.skyshadowhero.fancypad.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.PrefKeys
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「平行窗口」页（原 os4平行窗口动画fix模块）：作用域 `com.android.systemui`。
 *
 * 问题：HyperOS 的平行窗口（Activity Embedding）左右切换 / 进二级页时卡顿、丢动画。
 * 根因是平行窗口的转场走了小米的 Folme 动画引擎，而且转场允许「合并」与「跳切」——
 * 合并失败会把正在播的动画取消，跳切等于这次转场不播。
 *
 * 四个开关默认全开（= 旧模块的行为）；总开关关掉即完全还原成系统原行为。
 * 实测真正影响观感的是「禁止转场合并」+「禁止跳切」，另外两项保留为对照开关。
 *
 * 小窗控制点的按钮条（× → − / 红色强制关闭）已拆到独立的「小窗控制器」页 ——
 * 虽然同属 `com.android.systemui`，但和这里没有技术关联。
 *
 * ⚠ 这一域是 SystemUI 启动时注入的：开关改完即时生效（Hook 侧读的是缓存快照，
 * 偏好一变就刷新），但**模块本身的升级/作用域变更需要重启一次**。
 */
@Composable
fun ParallelPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
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
        item { SmallTitle("平行窗口动画") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.embeddingEnabled,
                    onCheckedChange = { checked ->
                        uiState.embeddingEnabled = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.EMBEDDING_ENABLED, checked) }
                    },
                    title = "恢复 AOSP 原生动画",
                    summary = "关闭则完全保持 HyperOS 原行为",
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
                            summary = "改用 AOSP 的 DefaultAnimationProvider（517/80/30ms）",
                        )
                        SwitchPreference(
                            checked = uiState.embeddingMergeDisable,
                            onCheckedChange = { checked ->
                                uiState.embeddingMergeDisable = checked
                                uiState.save { e -> e.putBoolean(PrefKeys.EMBEDDING_MERGE_DISABLE, checked) }
                            },
                            title = "禁止转场合并",
                            summary = "合并失败会把正在播放的动画取消掉（实测最有效项）",
                        )
                        SwitchPreference(
                            checked = uiState.embeddingJumpCutDisable,
                            onCheckedChange = { checked ->
                                uiState.embeddingJumpCutDisable = checked
                                uiState.save { e -> e.putBoolean(PrefKeys.EMBEDDING_JUMPCUT_DISABLE, checked) }
                            },
                            title = "禁止跳切",
                            summary = "跳切等于这次转场不播动画（实测最有效项）",
                        )
                    }
                }
            }
        }
    }
}

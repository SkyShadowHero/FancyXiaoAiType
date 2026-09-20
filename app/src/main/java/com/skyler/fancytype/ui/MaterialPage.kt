package com.skyler.fancytype.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.skyler.fancytype.MaterialPackages
import com.skyler.fancytype.PrefKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「超级材质」页：接管默认「哪些应用能用毛玻璃键盘背景」的白名单判定。
 *
 * 默认只有 `com.android.quicksearchbox` 在白名单里；本页可以把范围放开到
 * 「强制所有应用」或「手动勾选的一批应用」。
 *
 * 关掉总开关 / 打开强制全部时，下方依赖项**变灰**（保持布局稳定），
 * 与「间距」页的处理保持一致。
 */
@Composable
fun MaterialPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    var showPicker by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 只在真正需要时才扫描应用列表（打开页面后点「选择应用」）
    LaunchedEffect(showPicker) {
        if (!showPicker || apps.isNotEmpty()) return@LaunchedEffect
        loading = true
        apps = withContext(Dispatchers.IO) { AppCatalog.load(context) }
        loading = false
    }

    val pickedCount = uiState.materialPackages.size
    val manualEnabled = uiState.materialEnabled && !uiState.materialForceAll

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
        item { SmallTitle("超级材质") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.materialEnabled,
                    onCheckedChange = { checked ->
                        uiState.materialEnabled = checked
                        uiState.save { editor -> editor.putBoolean(PrefKeys.MATERIAL_ENABLED, checked) }
                    },
                    title = "启用超级材质",
                    summary = "让键盘背景使用系统毛玻璃材质（默认仅系统搜索可用）",
                )
            }
        }

        item { SmallTitle("生效范围") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.materialForceAll,
                    onCheckedChange = { checked ->
                        uiState.materialForceAll = checked
                        uiState.save { editor -> editor.putBoolean(PrefKeys.MATERIAL_FORCE_ALL, checked) }
                    },
                    title = "强制所有应用",
                    summary = "任何应用弹出键盘都启用超级材质，忽略下面的手动选择",
                    enabled = uiState.materialEnabled,
                )
                ArrowPreference(
                    title = "手动选择应用",
                    summary = when {
                        !uiState.materialEnabled -> "需先启用超级材质"
                        uiState.materialForceAll -> "已开启强制全部，此项不生效"
                        pickedCount == 0 -> "未选择（保持默认白名单）"
                        else -> "已选择 $pickedCount 个应用"
                    },
                    onClick = { showPicker = true },
                    enabled = manualEnabled,
                )
            }
        }
    }

    AppPickerDialog(
        show = showPicker,
        apps = apps,
        loading = loading,
        selected = uiState.materialPackages,
        query = query,
        onQueryChange = { query = it },
        maxListHeight = (configuration.screenHeightDp * 0.45f).dp,
        onToggle = { pkg ->
            val next = if (uiState.materialPackages.contains(pkg)) {
                uiState.materialPackages - pkg
            } else {
                uiState.materialPackages + pkg
            }
            uiState.materialPackages = next
            uiState.save { editor ->
                editor.putString(PrefKeys.MATERIAL_PACKAGES, MaterialPackages.encode(next))
            }
        },
        onDismiss = { showPicker = false },
    )
}

/**
 * 应用选择弹窗。
 *
 * 用 `WindowDialog`（独立窗口层）而不是 `Overlay*`：本应用是多 Scaffold 结构，
 * Overlay 组件依赖当前 Scaffold 的 popup host，实测渲染不出来。
 * 搜索框用 Miuix 的 `TextField`，列表用 `CheckboxPreference`，都是 0.9.4 的公开 API。
 */
@Composable
private fun AppPickerDialog(
    show: Boolean,
    apps: List<InstalledApp>,
    loading: Boolean,
    selected: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    maxListHeight: androidx.compose.ui.unit.Dp,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyword = query.trim()
    val filtered = remember(apps, keyword) {
        if (keyword.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.contains(keyword, ignoreCase = true) ||
                    it.packageName.contains(keyword, ignoreCase = true)
            }
        }
    }

    WindowDialog(
        show = show,
        title = "选择应用",
        summary = "已选择 ${selected.size} 个",
        onDismissRequest = onDismiss,
        content = {
            Column {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜索应用名或包名",
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))

                when {
                    loading -> PickerHint("正在读取应用列表…")
                    filtered.isEmpty() -> PickerHint(
                        if (apps.isEmpty()) "没有读取到可启动的应用" else "没有匹配的应用"
                    )
                    else -> Card {
                        LazyColumn(modifier = Modifier.heightIn(max = maxListHeight)) {
                            items(filtered, key = { it.packageName }) { app ->
                                CheckboxPreference(
                                    title = app.label,
                                    summary = app.packageName,
                                    checked = selected.contains(app.packageName),
                                    onCheckedChange = { onToggle(app.packageName) },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PickerHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = MiuixTheme.colorScheme.onSurfaceContainerHigh)
    }
}

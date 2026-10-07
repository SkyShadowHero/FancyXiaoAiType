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
import io.github.skyshadowhero.fancypad.RemoteConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.skyshadowhero.fancypad.MaterialPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「右键菜单」页：鼠标右键在文字上弹出的那个菜单。
 *
 * **它由应用自己画**，跟上面「AOSP长按菜单」不是一条路：真机实测（合成鼠标右键 + 截图取证）
 * 那个菜单就是一个挂在当前 Activity 上的 `PopupWindow`：
 *
 * ```
 * Window{u0 PopupWindow:...}: mOwnerUid=<应用 UID> ty=APPLICATION_PANEL
 *     mParentWindow=Window{u0 mark.via/mark.via.Shell}
 * ```
 *
 * 所以这一域**必须把目标应用逐个加进 LSPosed 作用域**（本机先只挂 `mark.via`），
 * 加完要**强停该应用**让它重新被注入。
 *
 * 原样式是框架/WebView 自带的小圆角 + MD 配色 + 只有窗口自带的淡入。打开开关后换成
 * Miuix 的 token：圆角 16dp、底色 `surfaceContainer`（浅色纯白 / 深色 `#242424`）、
 * 文字 `onSurfaceContainer` + `Body2` 14sp，并补上缩放淡入。
 *
 * 只处理「内容里含一个 ≥2 行 ListView」的弹窗 —— 也就是菜单；普通 Tooltip / 气泡不受影响。
 */
@Composable
fun AppMenuPage(
    uiState: AppUiState,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    LaunchedEffect(showPicker) {
        if (!showPicker || apps.isNotEmpty()) return@LaunchedEffect
        // 只列**作用域内**的应用：作用域决定模块能不能注入，不在作用域里的应用勾了也没用。
        // 作用域从 LSPosed 服务直接读（XposedService.getScope），不另存一份清单。
        val scopedPkgs = runCatching { RemoteConfig.service()?.scope }.getOrNull()
        apps = withContext(Dispatchers.IO) { AppCatalog.load(context) }
            .filter { app ->
                scopedPkgs.isNullOrEmpty() || scopedPkgs.contains(app.packageName)
            }
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
        item { SmallTitle("右键菜单") }
        item {
            Card {
                SwitchPreference(
                    checked = uiState.appMenuEnabled,
                    onCheckedChange = { checked ->
                        uiState.appMenuEnabled = checked
                        uiState.save { e -> e.putBoolean(PrefKeys.APPMENU_ENABLED, checked) }
                    },
                    title = "改用 Miuix 样式",
                    summary = "把右键弹出的菜单换成 Miuix 的圆角、配色、字号与入场动画" +
                        "（由应用自己绘制，需把该应用加入作用域并强停一次）",
                )
                if (uiState.appMenuEnabled) {
                    ArrowPreference(
                        title = "生效的应用",
                        summary = runCatching {
                            val n = MaterialPackages.decode(uiState.appMenuApps).size
                            if (n == 0) "未选择（不生效）" else "已选择 $n 个"
                        }.getOrDefault("未选择（不生效）"),
                        onClick = { showPicker = true },
                    )
                }
                AnimatedVisibility(visible = uiState.appMenuEnabled) {
                    Column {
                        DpSlider(
                            title = "弹出层圆角",
                            summary = "默认 ${PrefKeys.APPMENU_CORNER_DEFAULT.toInt()}dp（Miuix 弹出层圆角）",
                            value = uiState.appMenuCornerDp,
                            range = PrefKeys.APPMENU_CORNER_MIN..PrefKeys.APPMENU_CORNER_MAX,
                            keyPoint = PrefKeys.APPMENU_CORNER_DEFAULT,
                            onValueChange = { v -> uiState.appMenuCornerDp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.APPMENU_CORNER_DP, uiState.appMenuCornerDp)
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                        DpSlider(
                            title = "文字大小",
                            summary = "默认 ${PrefKeys.APPMENU_TEXT_DEFAULT.toInt()}sp" +
                                "（Miuix Body1，菜单项标题用的就是它，且为 Medium 字重）",
                            value = uiState.appMenuTextSp,
                            range = PrefKeys.APPMENU_TEXT_MIN..PrefKeys.APPMENU_TEXT_MAX,
                            keyPoint = PrefKeys.APPMENU_TEXT_DEFAULT,
                            unit = "sp",
                            onValueChange = { v -> uiState.appMenuTextSp = v },
                            onCommit = {
                                uiState.save { e ->
                                    e.putFloat(PrefKeys.APPMENU_TEXT_SP, uiState.appMenuTextSp)
                                }
                            },
                            commitGuard = { uiState.loaded },
                        )
                    }
                }
            }
        }
    }

    AppMenuAppPickerDialog(
        show = showPicker,
        apps = apps,
        selected = runCatching { MaterialPackages.decode(uiState.appMenuApps) }.getOrDefault(emptySet()),
        onToggle = { pkg ->
            val next = runCatching { MaterialPackages.decode(uiState.appMenuApps) }.getOrDefault(emptySet())
                .toMutableSet()
                .apply { if (!add(pkg)) remove(pkg) }
            uiState.appMenuApps = MaterialPackages.encode(next)
            uiState.save { e -> e.putString(PrefKeys.APPMENU_APPS, uiState.appMenuApps) }
        },
        onDismiss = { showPicker = false },
    )
}

/** 生效应用的多选列表（复用「超级材质」那套 AppCatalog + MaterialPackages 编解码）。 */
@Composable
private fun AppMenuAppPickerDialog(
    show: Boolean,
    apps: List<InstalledApp>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = "选择生效的应用",
        summary = "只列作用域内的应用；已选择 ${selected.size} 个（不选 = 不生效）",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(apps) { app ->
                SwitchPreference(
                    checked = selected.contains(app.packageName),
                    onCheckedChange = { onToggle(app.packageName) },
                    title = app.label,
                    summary = app.packageName,
                )
            }
        }
    }
}

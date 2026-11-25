package io.github.skyshadowhero.fancypad.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ui.ds.Card
import io.github.skyshadowhero.fancypad.ui.ds.SwitchPreference
import io.github.skyshadowhero.fancypad.ui.utils.AppCatalog
import io.github.skyshadowhero.fancypad.ui.utils.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 所有页面右上角的批量强制关闭（重启目标进程）入口。 */
@Composable
fun ForceStopAppsDialogAction(
    packages: Set<String>,
    onForceStop: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val backdrop = LocalPageBackdrop.current
    var showDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var apps by remember(packages) {
        mutableStateOf(forceStopApps(packages, emptyMap()))
    }

    LaunchedEffect(packages) {
        val labels = withContext(Dispatchers.IO) {
            AppCatalog.load(context.applicationContext).associate { it.packageName to it.label }
        }
        apps = forceStopApps(packages, labels)
        val available = apps.mapTo(hashSetOf(), InstalledApp::packageName)
        selected = selected.intersect(available)
    }

    ForceStopActionButton(
        onClick = {
            selected = emptySet()
            showDialog = true
        },
        backdrop = backdrop,
    )

    GlassDialog(
        show = showDialog,
        backdrop = backdrop,
        title = "选择要强制关闭的应用",
        summary = "可多选，需要 Root 授权。普通应用关闭后不会自动重新打开。",
        onDismissRequest = {
            showDialog = false
            selected = emptySet()
        },
    ) {
        // 整个内容区限制高度，按钮先占位，列表只使用剩余空间并在需要时滚动。
        // weight(fill = false) 让短列表按内容收缩，不会把 Dialog 强制拉到满高。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(
                        items = apps,
                        key = InstalledApp::packageName,
                    ) { app ->
                        SwitchPreference(
                            checked = app.packageName in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) {
                                    selected + app.packageName
                                } else {
                                    selected - app.packageName
                                }
                            },
                            title = app.label,
                            summary = app.packageName,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassDialogButton(
                    onClick = {
                        showDialog = false
                        selected = emptySet()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                GlassDialogButton(
                    onClick = {
                        val targets = selected
                        showDialog = false
                        selected = emptySet()
                        onForceStop(targets)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = selected.isNotEmpty(),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "强制关闭", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

private fun forceStopApps(
    packages: Set<String>,
    labels: Map<String, String>,
): List<InstalledApp> {
    val knownOrder = knownLabels.keys.withIndex().associate { (index, pkg) -> pkg to index }
    return packages.map { pkg ->
        InstalledApp(
            packageName = pkg,
            label = knownLabels[pkg] ?: labels[pkg] ?: pkg,
        )
    }.sortedWith(
        compareBy<InstalledApp> { knownOrder[it.packageName] ?: Int.MAX_VALUE }
            .thenBy { it.label },
    )
}

private val knownLabels = linkedMapOf(
    "com.xiaomi.type" to "超级小爱输入法",
    "com.android.systemui" to "系统界面",
    "com.miui.securitycore" to "手机(平板)管家",
)

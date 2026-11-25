package io.github.skyshadowhero.fancypad.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ui.ds.Card
import io.github.skyshadowhero.fancypad.ui.ds.SwitchPreference
import io.github.skyshadowhero.fancypad.ui.utils.InstalledApp
import top.yukonga.miuix.kmp.glass.GlassDialog

/** Shared glass multi-select dialog for app-scoped features. */
@Composable
internal fun ScopeAppPickerDialog(
    show: Boolean,
    apps: List<InstalledApp>,
    selected: Set<String>,
    summary: String,
    onToggle: (String) -> Unit,
    onDismissRequest: () -> Unit,
    title: String = "选择生效的应用",
) {
    GlassDialog(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(
                    items = apps,
                    key = { it.packageName },
                ) { app ->
                    SwitchPreference(
                        checked = app.packageName in selected,
                        onCheckedChange = { onToggle(app.packageName) },
                        title = app.label,
                        summary = app.packageName,
                    )
                }
            }
        }
    }
}

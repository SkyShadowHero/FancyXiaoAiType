package io.github.skyshadowhero.fancypad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.skyshadowhero.fancypad.ui.ds.*

/**
 * 「功能」页：一级功能域列表。
 *
 * **没有加入 LSPosed 作用域的功能域会置灰、点不动**（KernelSU 那种「没装/没启用」的观感）：
 * 作用域里没有目标包，注入根本不会发生，点进去改任何开关都不会生效。
 * 缺哪个包直接写在副标题里（例如「未加入作用域：com.xiaomi.type」）。
 *
 * 主题设置与关于已移到「信息」页，这里只留功能域。
 */
@Composable
fun ScopeListPage(
    scopePackages: Set<String>,
    onEnter: (AppScope) -> Unit,
    padding: PaddingValues,
    scaffoldPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            // 顶栏高度已经由 scaffoldPadding 给足了，这里不再额外叠加间距，
            // 否则「功能」页顶部会空出一大块（就是反馈里的「padding 太大」）。
            top = scaffoldPadding.calculateTopPadding(),
            bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.Top),
    ) {
        item {
            Card {
                AppScope.entries.forEach { scope ->
                    val available = scope.inScope(scopePackages)
                    ArrowPreference(
                        title = scope.label,
                        summary = scope.scopeSummary(scopePackages),
                        enabled = available,
                        startAction = { ScopeIcon(scope, alpha = if (available) 1f else 0.35f) },
                        onClick = { if (available) onEnter(scope) },
                    )
                }
            }
        }
    }
}

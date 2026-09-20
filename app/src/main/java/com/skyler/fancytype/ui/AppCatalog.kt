package com.skyler.fancytype.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.text.Collator
import java.util.Locale

/** 可选应用：包名 + 显示名 */
data class InstalledApp(val packageName: String, val label: String)

/**
 * 「超级材质」手动选择用的应用清单。
 *
 * 只列**可启动的应用**（MAIN/LAUNCHER），而不是 `getInstalledApplications()`：
 * 前者恰好是「用户真的会打开、真的会弹键盘」的那批，且只需在 Manifest 里声明
 * `<queries>` 意图，不必申请 `QUERY_ALL_PACKAGES` 这种范围过大的权限。
 */
object AppCatalog {

    private const val CACHE_TTL_MS = 60_000L

    @Volatile
    private var cache: List<InstalledApp>? = null

    @Volatile
    private var cachedAt = 0L

    /** 读取（带 60 秒缓存，避免每次打开选择器都全量扫描）。 */
    fun load(context: Context): List<InstalledApp> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cachedAt < CACHE_TTL_MS) return it }

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            emptyList()
        }

        val collator = Collator.getInstance(Locale.CHINESE)
        val list = resolved.asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                InstalledApp(pkg, labelOf(pm, info, pkg))
            }
            // 同一个应用可能有多个启动入口，去重保留第一个
            .distinctBy { it.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .toList()

        cache = list
        cachedAt = now
        return list
    }

    private fun labelOf(pm: PackageManager, info: ResolveInfo, pkg: String): String {
        val fromActivity = runCatching { info.loadLabel(pm).toString() }.getOrNull()
        if (!fromActivity.isNullOrBlank()) return fromActivity
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}

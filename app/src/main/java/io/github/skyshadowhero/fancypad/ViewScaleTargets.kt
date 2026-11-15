package io.github.skyshadowhero.fancypad

import org.json.JSONObject
import java.io.File

/**
 * 一镜到底：**系统配置表里收录了哪些 App**。
 *
 * 小米在 `/system_ext/etc/view_scale/view_scale_config.json` 里逐 App 声明「哪些 Activity 的
 * 哪些 View 算卡片、圆角多少、底色取哪」。只有在这个表里的包，system_server 才会在
 * `preBindApplication()` 时给 `ApplicationInfo.viewScaleInfo` 塞配置 —— 没进表的包
 * 即使把模块作用域加上也不会有一镜到底（这是刻意的边界，模块不编造"哪个 View 算卡片"）。
 *
 * 设置页用它来：
 * - 显示"支持哪些 App"；
 * - 「一键申请作用域」按钮一次把这些包提交给 LSPosed。
 *
 * 两份来源：出厂（`/system_ext/etc/`）与云端下发（`/data/system/view_scale/`）。
 * 后者存在且更新时系统会优先用云端的，这里两份都读、取并集。
 */
object ViewScaleTargets {

    private val CONFIG_PATHS = arrayOf(
        "/system_ext/etc/view_scale/view_scale_config.json",
        "/data/system/view_scale/cloud_view_scale_config.json",
    )

    /** 配置表里的包名（去重、保序）。读不到时返回空列表。 */
    fun packages(): List<String> {
        val result = LinkedHashSet<String>()
        for (path in CONFIG_PATHS) {
            val text = try {
                val f = File(path)
                if (!f.isFile) continue
                f.readText()
            } catch (t: Throwable) {
                continue
            }
            try {
                val root = JSONObject(text)
                val pkgArray = root.optJSONArray("pkg") ?: continue
                for (i in 0 until pkgArray.length()) {
                    val pkg = pkgArray.optJSONObject(i)?.optString("attr").orEmpty()
                    if (pkg.isNotEmpty()) result.add(pkg)
                }
            } catch (t: Throwable) {
                // 单个文件坏掉不影响另一份
            }
        }
        return result.toList()
    }
}

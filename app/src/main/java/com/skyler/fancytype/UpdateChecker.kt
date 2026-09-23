package com.skyler.fancytype

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：读 GitHub 上的发布标签，与本机版本比对。
 *
 * 仓库的 tag 就是纯版本号（`1.2.0` / `1.1.0`），没有 `v` 前缀，但比对时两种都兼容。
 * 先用 `releases/latest`（能顺带拿到更新说明与详情页地址），仓库还没建 release 时
 * 退回 `tags` 列表取最大版本。
 *
 * 不引入 okhttp：就用 HttpURLConnection，少一个依赖。
 * 注意 GitHub API 要求带 User-Agent，不带会直接 403。
 */
object UpdateChecker {

    private const val OWNER_REPO = "SkyShadowHero/FancyXiaoAiType"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    private const val API_TAGS = "https://api.github.com/repos/$OWNER_REPO/tags"
    private const val RELEASE_TAG_PAGE = "https://github.com/$OWNER_REPO/releases/tag/"

    private const val TIMEOUT_MS = 12_000

    sealed interface Result {
        data class UpToDate(val current: String) : Result

        data class Available(
            val current: String,
            val latest: String,
            val url: String,
            val notes: String?,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    fun check(current: String): Result = try {
        val latest = fetchLatestRelease() ?: fetchLatestTag()
        when {
            latest == null -> Result.Failed("仓库里还没有发布标签")
            compare(latest.tag, current) > 0 ->
                Result.Available(current, latest.tag, latest.url, latest.notes)

            else -> Result.UpToDate(current)
        }
    } catch (t: Throwable) {
        L.w("event=update_check_failed ${t.javaClass.simpleName}: ${t.message}")
        Result.Failed(t.message ?: t.javaClass.simpleName)
    }

    private data class Latest(val tag: String, val url: String, val notes: String?)

    private fun fetchLatestRelease(): Latest? {
        val json = getJson(API_LATEST) ?: return null
        val tag = json.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val url = json.optString("html_url").trim().ifEmpty { RELEASE_TAG_PAGE + tag }
        val notes = json.optString("body").trim().ifEmpty { null }
        L.i("event=update_latest source=release tag=$tag")
        return Latest(tag, url, notes)
    }

    private fun fetchLatestTag(): Latest? {
        val arr = getArray(API_TAGS) ?: return null
        var best: String? = null
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
            if (name.isEmpty()) continue
            if (best == null || compare(name, best) > 0) best = name
        }
        val tag = best ?: return null
        L.i("event=update_latest source=tags tag=$tag")
        return Latest(tag, RELEASE_TAG_PAGE + tag, null)
    }

    // ---------------------------------------------------------------- 网络

    private fun rawGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub API 强制要求 User-Agent，缺了会返回 403
            setRequestProperty("User-Agent", "FancyType-UpdateChecker")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 失败一律返回 null，交给上层决定是回退还是报错。 */
    private fun getJson(url: String): JSONObject? = try {
        JSONObject(rawGet(url))
    } catch (t: Throwable) {
        L.i("event=update_get_json_miss url=$url reason=${t.javaClass.simpleName}")
        null
    }

    private fun getArray(url: String): JSONArray? = try {
        JSONArray(rawGet(url))
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------- 版本比对

    /** 语义化比较：1.10.0 > 1.9.0；非数字段按 0 处理。 */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    private fun parse(v: String): List<Int> = v.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '+', '_')
        .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}

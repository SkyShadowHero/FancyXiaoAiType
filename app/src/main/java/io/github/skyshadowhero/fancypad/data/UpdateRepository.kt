package io.github.skyshadowhero.fancypad.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val REPOSITORY_URL = "https://github.com/SkyShadowHero/FancyXiaoAiType"
const val RELEASES_URL = "$REPOSITORY_URL/releases"
private const val LATEST_RELEASE_API =
    "https://api.github.com/repos/SkyShadowHero/FancyXiaoAiType/releases/latest"

data class ReleaseInfo(
    val tag: String,
    val version: SemanticVersion,
    val downloadUrl: String,
)

sealed interface UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data class UpToDate(val latest: ReleaseInfo) : UpdateCheckResult
}

object UpdateRepository {
    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val current = SemanticVersion.parse(currentVersion)
            ?: error("无法识别当前版本：$currentVersion")
        val latest = fetchLatestRelease()
        if (latest.version > current) {
            UpdateCheckResult.Available(latest)
        } else {
            UpdateCheckResult.UpToDate(latest)
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "FancyXiaoAiType-update-checker")

            val code = connection.responseCode
            if (code !in 200..299) error("GitHub 返回 HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            val version = SemanticVersion.parse(tag) ?: error("无法识别版本标签：$tag")
            val releasePage = json.optString("html_url").trim()
                .takeIf { it.startsWith("$RELEASES_URL/") }
                ?: RELEASES_URL
            ReleaseInfo(tag = tag, version = version, downloadUrl = releasePage)
        } finally {
            connection.disconnect()
        }
    }
}

/** 支持 `Pad-1.1.5`、`v1.2.0-beta.1` 等标签的 SemVer 比较。 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
        for (index in 0 until maxOf(preRelease.size, other.preRelease.size)) {
            val left = preRelease.getOrNull(index) ?: return -1
            val right = other.preRelease.getOrNull(index) ?: return 1
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val compared = when {
                leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (compared != 0) return compared
        }
        return 0
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        if (preRelease.isNotEmpty()) append('-').append(preRelease.joinToString("."))
    }

    companion object {
        private val PATTERN = Regex(
            "(?:^|[^0-9])(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$",
        )

        fun parse(value: String): SemanticVersion? {
            val match = PATTERN.find(value.trim()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                preRelease = match.groupValues[4]
                    .takeIf { it.isNotEmpty() }
                    ?.split('.')
                    .orEmpty(),
            )
        }
    }
}

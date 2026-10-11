package dev.kaorios.patcher.storage

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

/**
 * Checks the GitHub repository for app updates across beta and stable channels.
 *
 * The app's own releases live at `nothingnesscore/KaoriOS-RePatcher`. Beta releases are
 * published as prereleases with tags like `v1.2.0-beta.7`; stable releases are normal
 * releases with tags like `v1.2.0`. A beta supersedes a stable when its base version is
 * greater than or equal to the stable's version.
 *
 * Never fails the check: network errors return `null` and the UI stays silent.
 */
class UpdateChecker {

    /** What a check resolved; `null` means no update is available or the check failed. */
    data class Result(
        val tag: String,
        val versionName: String,
        val isBeta: Boolean,
        val isPrerelease: Boolean,
        val apkUrl: String?,
        val apkSize: Long?,
        val publishedAt: String,
        val body: String,
        val requiresRepatch: Boolean,
        val channelSwitch: Boolean,
    )

    /**
     * Checks for an update on [preferredChannel] ("beta" or "stable").
     *
     * Queries both the latest stable release and the latest prerelease, then picks the one
     * that supersedes the current version. A channel switch is flagged when the preferred
     * channel differs from the current build's channel.
     */
    fun check(
        currentVersion: String,
        currentIsBeta: Boolean,
        preferredChannel: String,
    ): Result? = try {
        val stable = fetchLatestRelease(prerelease = false)
        val beta = fetchLatestRelease(prerelease = true)

        val candidates = listOfNotNull(stable, beta).filter { release ->
            versionGreaterThan(release.versionName, currentVersion)
        }
        if (candidates.isEmpty()) return null

        // Prefer the requested channel, but fall back to any newer release.
        val preferred = candidates.filter {
            if (preferredChannel == "stable") !it.isBeta else it.isBeta
        }
        val best = (preferred + candidates).maxByOrNull { versionSortKey(it.versionName) }
            ?: return null

        // A cross-channel update (beta→stable or stable→beta) needs a caution flag.
        val channelSwitch = best.isBeta != currentIsBeta

        Result(
            tag = best.tag,
            versionName = best.versionName,
            isBeta = best.isBeta,
            isPrerelease = best.isPrerelease,
            apkUrl = best.apkUrl,
            apkSize = best.apkSize,
            publishedAt = best.publishedAt,
            body = best.body,
            requiresRepatch = detectRepatch(best.body),
            channelSwitch = channelSwitch,
        )
    } catch (_: Exception) {
        null
    }

    private data class Release(
        val tag: String,
        val versionName: String,
        val isBeta: Boolean,
        val isPrerelease: Boolean,
        val apkUrl: String?,
        val apkSize: Long?,
        val publishedAt: String,
        val body: String,
    )

    private fun fetchLatestRelease(prerelease: Boolean): Release? {
        // GitHub's "latest" endpoint excludes prereleases; list and filter instead.
        val url = if (prerelease) {
            "$RELEASES_API?per_page=10"
        } else {
            "$RELEASES_API/latest"
        }
        val conn = open(url, ACCEPT_API)
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()

        val releases = if (prerelease) {
            val array = org.json.JSONArray(body)
            (0 until array.length()).map { array.getJSONObject(it) }
        } else {
            listOf(JSONObject(body))
        }

        val json = releases.firstOrNull { obj ->
            val pre = obj.optBoolean("prerelease", false)
            val draft = obj.optBoolean("draft", false)
            !draft && pre == prerelease
        } ?: return null

        val tag = json.getString("tag_name")
        val versionName = tag.removePrefix("v")
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        var apkSize: Long? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }

        return Release(
            tag = tag,
            versionName = versionName,
            isBeta = versionName.contains("-beta"),
            isPrerelease = json.optBoolean("prerelease", false),
            apkUrl = apkUrl,
            apkSize = apkSize,
            publishedAt = json.optString("published_at", ""),
            body = json.optString("body", ""),
        )
    }

    /**
     * Parses the changelog body for patch-related keywords.
     *
     * A release that touches smali, hooks, framework/services, keystore, or the engine
     * requires the user to uninstall the old module and re-patch. Cosmetic changes (UI,
     * theme, strings) do not.
     */
    private fun detectRepatch(body: String): Boolean {
        val lower = body.lowercase()
        return REPATCH_KEYWORDS.any { lower.contains(it) }
    }

    /** Semantic-ish version comparison: `1.2.0-beta.7` > `1.2.0-beta.6` > `1.2.0`. */
    private fun versionGreaterThan(a: String, b: String): Boolean =
        versionSortKey(a) > versionSortKey(b)

    /**
     * Numeric sort key so `1.2.0-beta.10` sorts above `1.2.0-beta.9`.
     *
     * Stable versions get the highest patch (9999) so a stable always outranks its own betas.
     * Returned as a single Long so `maxByOrNull` can compare directly.
     */
    private fun versionSortKey(version: String): Long {
        val base = version.substringBefore("-")
        val parts = base.split(".").map { it.toLongOrNull() ?: 0L }
        val stable = parts.getOrElse(0) { 0L } * 10_000_000L +
            parts.getOrElse(1) { 0L } * 10_000L +
            parts.getOrElse(2) { 0L } * 100L +
            9_999L
        val beta = version.substringAfter("-beta.", "").toLongOrNull()
            ?: version.substringAfter("-rc.", "").toLongOrNull()
            ?: 0L
        return stable * 100_000L + beta
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", accept)
            when (conn.responseCode) {
                in 200..299 -> return conn
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    checkNotNull(location) { "redirect without Location from $current" }
                    current = URI(current).resolve(location).toString()
                }
                else -> {
                    val code = conn.responseCode
                    conn.disconnect()
                    error("HTTP $code for $current")
                }
            }
        }
        error("too many redirects for $url")
    }

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/nothingnesscore/KaoriOS-RePatcher/releases"
        private const val ACCEPT_API = "application/vnd.github+json"
        private const val USER_AGENT = "KaoriosPatcher"
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /** Keywords that mean the update touches patch logic and warrants a re-patch. */
        private val REPATCH_KEYWORDS = listOf(
            "smali", "hook", "patch", "framework.jar", "services.jar",
            "keystore", "keybox", "attestation", "engine", "dex",
            "relocat", "overflow", "register", "method-id",
        )
    }
}

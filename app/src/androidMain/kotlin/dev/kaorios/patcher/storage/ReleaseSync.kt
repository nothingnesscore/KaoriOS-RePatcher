package dev.kaorios.patcher.storage

import dev.kaorios.patcher.pipeline.PatchLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Pulls the latest Kaorios-Toolbox release assets into the app workspace before a patch run.
 *
 * Both shippable inputs — the KaoriOS runtime dex (`classes.dex`, merged into `framework.jar`)
 * and the manager APK (`KaoriosToolbox*.apk`, overlaid as a priv-app) — are published on the
 * GitHub release and can be replaced upstream between app builds (the dex especially: release
 * notes routinely announce rebuilt payloads). A run therefore re-syncs them into
 * `<workspace>/assets/` and ships those bytes, so patching always uses what the repo currently
 * publishes instead of copies frozen at app-build time.
 *
 * Never fails the run: the copy from the last successful sync stays in the workspace as the
 * offline cache, and a cold cache leaves the caller to fall back on its bundled/pushed files.
 *
 * The release listing itself can be the part that fails — `api.github.com` shares 60
 * unauthenticated requests an hour per IP and is unreachable from some regions entirely —
 * while the downloads it points at (`github.com/releases/download/...`) still work. When
 * the API path throws, [fetchViaFeed] resolves the same newest tag from the public atom
 * feed instead, so a blocked API degrades to one direct download rather than to a stale
 * cache that ships an old dex forever.
 */
class ReleaseSync(private val assetsDir: File) {

    /** What a sync resolved; `null` means the caller falls back to its own copy. */
    data class Result(
        val tag: String,
        val runtimeDex: File?,
        val toolboxApk: File?,
        val detail: String,
    )

    private enum class Kind { DEX, APK }

    fun sync(): Result {
        assetsDir.mkdirs()
        val cachedDex = assetsDir.resolve(DEX_NAME)
        val cachedApk = assetsDir.resolve(APK_NAME)
        return try {
            val tag = fetchRelease()
            val detail = detailFor(tag)
            PatchLog.info(detail)
            Result(tag, cachedDex.takeIf { it.isFile }, cachedApk.takeIf { it.isFile }, detail)
        } catch (apiError: Exception) {
            try {
                val tag = fetchViaFeed()
                PatchLog.warn(
                    "release sync api unreachable (${apiError.message}); " +
                        "resolved $tag from the releases feed"
                )
                val detail = detailFor(tag)
                PatchLog.info(detail)
                Result(tag, cachedDex.takeIf { it.isFile }, cachedApk.takeIf { it.isFile }, detail)
            } catch (feedError: Exception) {
                val have = listOfNotNull(
                    cachedDex.takeIf { it.isFile }?.let { "cached ${it.name}" },
                    cachedApk.takeIf { it.isFile }?.let { "cached ${it.name}" },
                )
                val detail = "release sync failed (api: ${apiError.message}; " +
                    "feed: ${feedError.message}); using " +
                    (have.ifEmpty { listOf("bundled/pushed fallbacks") }.joinToString(" + "))
                PatchLog.warn(detail)
                Result(
                    tag = assetsDir.resolve(TAG_FILE).takeIf { it.isFile }?.readText().orEmpty(),
                    runtimeDex = cachedDex.takeIf { it.isFile },
                    toolboxApk = cachedApk.takeIf { it.isFile },
                    detail = detail,
                )
            }
        }
    }

    /** The one sync summary line the log and the report carry: `release sync <tag>: …`. */
    private fun detailFor(tag: String): String =
        "release sync $tag: " +
            listOfNotNull(
                assetsDir.resolve(DEX_NAME).takeIf { it.isFile }?.let { "${it.name}=${it.length()}B" },
                assetsDir.resolve(APK_NAME).takeIf { it.isFile }?.let { "${it.name}=${it.length()}B" },
            ).joinToString(" ")

    /** Resolves the newest release per asset type and downloads what the workspace lacks. */
    private fun fetchRelease(): String {
        val array = getJsonArray("$RELEASES_API?per_page=30", ACCEPT_API)
        val releases = (0 until array.length()).map { array.getJSONObject(it) }
            .filter { !it.optBoolean("draft", false) }
        // GitHub's "latest" endpoint orders by created date, which misleads when a newer
        // version's release was created earlier (v2.0.6.1 shipped after a v2.0.6.0 rebuild
        // and lost "latest" to it), so the dex release is chosen by tag version instead.
        val dexRelease = releases
            .filter { rel -> asset(rel, DEX_ASSET) != null }
            .maxByOrNull { versionKey(it.getString("tag_name")) }
            ?: error("no release publishes a $DEX_ASSET asset")
        val tag = dexRelease.getString("tag_name")
        val dexAsset = asset(dexRelease, DEX_ASSET)
            ?: error("release $tag publishes no $DEX_ASSET asset")
        // The APK only ships when it comes from the very release the dex came from: a
        // mismatched pair means upstream replaced the runtime without republishing the
        // manager, and the app-shipped bundled APK is the intended fallback — a stale
        // workspace copy would shadow it and silently downgrade the priv-app overlay.
        val apkAsset = asset(dexRelease, null)
        fetch(
            dexAsset.getString("browser_download_url"),
            assetsDir.resolve(DEX_NAME),
            dexAsset.getLong("size"),
            digest(dexAsset),
            Kind.DEX,
        )
        val cachedApk = assetsDir.resolve(APK_NAME)
        val apkTagFile = assetsDir.resolve(APK_TAG_FILE)
        if (apkAsset == null) {
            // Drop any workspace APK so it cannot win over the bundled one: without a
            // same-release APK asset the cached copy's provenance is unknown (a pre-beta.10
            // workspace has no tag file), and a stale shadow would silently downgrade the
            // priv-app overlay. The tag file is removed with it so the next sync starts clean.
            cachedApk.delete()
            apkTagFile.delete()
        } else {
            fetch(
                apkAsset.getString("browser_download_url"),
                cachedApk,
                apkAsset.getLong("size"),
                digest(apkAsset),
                Kind.APK,
            )
            apkTagFile.writeText(tag)
        }
        assetsDir.resolve(TAG_FILE).writeText(tag)
        return tag
    }

    /**
     * The non-API half of [sync]: newest tag from the releases atom feed, dex from the
     * plain `github.com/releases/download` URLs the feed's tags address.
     *
     * Candidates come back newest-first by [versionKey], never by feed order — editing an
     * old release bumps its `<updated>` timestamp and can reorder the feed. A candidate
     * older than the workspace's recorded tag is skipped outright (no downgrades), and a
     * cache already holding the chosen tag is reused without touching the network. The APK
     * has no guessable asset name without the API, so only a workspace APK tagged to the
     * chosen release survives; anything else is deleted so the bundled signed copy wins,
     * mirroring the same-release rule [fetchRelease] enforces.
     */
    private fun fetchViaFeed(): String {
        val tags = TAG_HREF.findAll(get(FEED_URL, ACCEPT_FEED))
            .map { it.groupValues[1] }
            .filter { VERSION_TAG.matches(it) }
            .distinct()
            .sortedByDescending { versionKey(it) }
            .take(FALLBACK_TAG_LIMIT)
            .toList()
        if (tags.isEmpty()) error("releases feed lists no versioned tags")
        val tagFile = assetsDir.resolve(TAG_FILE)
        val cacheTag = tagFile.takeIf { it.isFile }?.readText().orEmpty()
        val cachedDex = assetsDir.resolve(DEX_NAME)
        var lastFailure: Exception? = null
        var attempted = false
        for (tag in tags) {
            if (cacheTag.isNotEmpty() && versionKey(tag) < versionKey(cacheTag)) continue
            if (tag == cacheTag && cachedDex.isFile && valid(Kind.DEX, cachedDex)) return tag
            attempted = true
            try {
                fetch(
                    "$RELEASES_URL/download/$tag/$DEX_ASSET",
                    cachedDex,
                    expectedSize = null,
                    expectedSha = null,
                    Kind.DEX,
                    force = true,
                )
                val apkTagFile = assetsDir.resolve(APK_TAG_FILE)
                if (apkTagFile.takeIf { it.isFile }?.readText() != tag) {
                    assetsDir.resolve(APK_NAME).delete()
                    apkTagFile.delete()
                }
                tagFile.writeText(tag)
                return tag
            } catch (e: Exception) {
                // A 404 means this release publishes no classes.dex; an older one may.
                lastFailure = e
            }
        }
        if (!attempted && cacheTag.isNotEmpty() && cachedDex.isFile && valid(Kind.DEX, cachedDex)) {
            // Every feed candidate is older than the workspace: the cache is the newest dex.
            return cacheTag
        }
        throw lastFailure ?: error("no feed candidate publishes a $DEX_ASSET")
    }

    private fun asset(release: JSONObject, requiredName: String?): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
        if (requiredName != null) return entries.firstOrNull { it.getString("name") == requiredName }
        return entries.firstOrNull {
            it.getString("name").endsWith(".apk", ignoreCase = true) &&
                it.getString("name").contains("toolbox", ignoreCase = true)
        } ?: entries.firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
    }

    private fun digest(asset: JSONObject): String? =
        asset.optString("digest")
            .takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")
            ?.takeIf { it.length == 64 }

    /** Numeric sort key for release tags (`v2.0.6.1` → 2_000_006_001); junk segments sort as 0. */
    private fun versionKey(tag: String): Long {
        val parts = tag.removePrefix("v").substringBefore("-").split(".")
        fun seg(i: Int) = parts.getOrElse(i) { "0" }.toLongOrNull() ?: 0L
        // All four segments count: a three-segment key made v2.0.6.1 and v2.0.6.0 tie,
        // and the tie fell back to feed/list order — which is how a rebuilt v2.0.6.0 once
        // won over the newer release.
        return seg(0) * 1_000_000_000L + seg(1) * 1_000_000L + seg(2) * 1_000L + seg(3)
    }

    /**
     * Downloads [url] unless the cached file already matches the release's size and digest.
     *
     * The size/digest pair is what makes a redownload skippable: an 11 MB APK is only fetched
     * when the release actually replaced it, and a partial or corrupt file is caught before it
     * can be renamed over a good cache. [force] bypasses the skip for callers that carry no
     * size or digest to compare (the feed fallback knows the tag, not the asset metadata) —
     * without it a stale-but-valid cache would shadow the download forever.
     */
    private fun fetch(
        url: String,
        target: File,
        expectedSize: Long?,
        expectedSha: String?,
        kind: Kind,
        force: Boolean = false,
    ): File {
        if (!force && target.isFile && matches(target, expectedSize, expectedSha) && valid(kind, target)) {
            return target
        }
        val tmp = File(assetsDir, target.name + ".part")
        val conn = open(url, ACCEPT_BINARY)
        conn.inputStream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()
        if (!matches(tmp, expectedSize, expectedSha)) {
            tmp.delete()
            error("${target.name}: download does not match the release's size/digest")
        }
        if (!valid(kind, tmp)) {
            tmp.delete()
            error("${target.name}: downloaded file is not a valid ${kind.name.lowercase()}")
        }
        target.delete()
        check(tmp.renameTo(target)) { "${target.name}: cannot move into place" }
        return target
    }

    private fun matches(file: File, expectedSize: Long?, expectedSha: String?): Boolean {
        if (expectedSize != null && file.length() != expectedSize) return false
        if (expectedSha != null && !sha256(file).equals(expectedSha, ignoreCase = true)) return false
        return true
    }

    /** The properties ART and `PackageManager` respectively refuse to load without. */
    private fun valid(kind: Kind, file: File): Boolean = when (kind) {
        Kind.DEX -> file.readHead(DEX_MAGIC.size).contentEquals(DEX_MAGIC)
        Kind.APK -> file.readHead(ZIP_MAGIC.size).contentEquals(ZIP_MAGIC) &&
            String(file.readBytes(), Charsets.ISO_8859_1).contains(APK_SIG_BLOCK_MAGIC)
    }

    private fun get(url: String, accept: String): String {
        val conn = open(url, accept)
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        return body
    }

    private fun getJsonArray(url: String, accept: String): org.json.JSONArray =
        org.json.JSONArray(get(url, accept))

    /**
     * GET with explicit redirect handling: release downloads 302 to the storage CDN, and
     * `HttpURLConnection`'s follower is not to be trusted across hosts.
     */
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

    private fun File.readHead(n: Int): ByteArray {
        inputStream().use { input ->
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) break
                read += r
            }
            return if (read == n) buf else buf.copyOf(read)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/hzzmonetvn/Kaorios-Toolbox/releases"
        private const val ACCEPT_API = "application/vnd.github+json"
        private const val ACCEPT_BINARY = "*/*"
        private const val USER_AGENT = "KaoriosPatcher"
        private const val DEX_ASSET = "classes.dex"
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000

        /** The API-free half: feed for tags, `releases/download` for bytes — both on github.com. */
        private const val FEED_URL = "https://github.com/hzzmonetvn/Kaorios-Toolbox/releases.atom"
        private const val ACCEPT_FEED = "application/atom+xml"
        private const val RELEASES_URL = "https://github.com/hzzmonetvn/Kaorios-Toolbox/releases"
        private val TAG_HREF = Regex("releases/tag/([^\"?#<\\s]+)")
        private val VERSION_TAG = Regex("v\\d+(?:\\.\\d+){1,3}[^\\s]*")
        private const val FALLBACK_TAG_LIMIT = 5

        /** Stable workspace names, whatever the release asset is called upstream. */
        const val DEX_NAME = "kaorios.dex"
        const val APK_NAME = "KaoriosToolbox.apk"
        private const val TAG_FILE = "release.tag"
        private const val APK_TAG_FILE = "KaoriosToolbox.apk.tag"

        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a) // "dex\n"
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

        /** Identifies an APK Signing Block, i.e. the v2 signature a re-zip would destroy. */
        private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"
    }
}

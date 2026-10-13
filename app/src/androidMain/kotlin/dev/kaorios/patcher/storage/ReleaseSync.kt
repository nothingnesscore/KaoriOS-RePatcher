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
            val detail = "release sync $tag: " +
                listOfNotNull(
                    cachedDex.takeIf { it.isFile }?.let { "${it.name}=${it.length()}B" },
                    cachedApk.takeIf { it.isFile }?.let { "${it.name}=${it.length()}B" },
                ).joinToString(" ")
            PatchLog.info(detail)
            Result(tag, cachedDex.takeIf { it.isFile }, cachedApk.takeIf { it.isFile }, detail)
        } catch (e: Exception) {
            val have = listOfNotNull(
                cachedDex.takeIf { it.isFile }?.let { "cached ${it.name}" },
                cachedApk.takeIf { it.isFile }?.let { "cached ${it.name}" },
            )
            val detail = "release sync failed (${e.message}); using " +
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

    /** Numeric sort key for release tags (`v2.0.6.1` → 2_006_001); junk segments sort as 0. */
    private fun versionKey(tag: String): Long {
        val parts = tag.removePrefix("v").substringBefore("-").split(".")
        fun seg(i: Int) = parts.getOrElse(i) { "0" }.toLongOrNull() ?: 0L
        return seg(0) * 1_000_000L + seg(1) * 1_000L + seg(2)
    }

    /**
     * Downloads [url] unless the cached file already matches the release's size and digest.
     *
     * The size/digest pair is what makes a redownload skippable: an 11 MB APK is only fetched
     * when the release actually replaced it, and a partial or corrupt file is caught before it
     * can be renamed over a good cache.
     */
    private fun fetch(
        url: String,
        target: File,
        expectedSize: Long?,
        expectedSha: String?,
        kind: Kind,
    ): File {
        if (target.isFile && matches(target, expectedSize, expectedSha) && valid(kind, target)) {
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

    private fun getJsonArray(url: String, accept: String): org.json.JSONArray {
        val conn = open(url, accept)
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        return org.json.JSONArray(body)
    }

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

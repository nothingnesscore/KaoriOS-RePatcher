package dev.kaorios.patcher.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Access to the shared `Download` folder, used for two things: reading the user-supplied KaoriOS
 * runtime dex, and dropping the finished module where the file manager can reach it so it can be
 * flashed from SukiSU.
 *
 * This needs `MANAGE_EXTERNAL_STORAGE`. Scoped storage makes plain [File] paths into shared
 * storage fail from API 30, and `requestLegacyExternalStorage` is ignored on those versions, so
 * "All files access" is the only route that keeps the rest of the app on `java.io.File`.
 */
class DownloadsStore(context: Context) {

    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    val dir: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** Human-readable location, shown in the UI. */
    val path: String get() = dir.absolutePath

    /** Whether "All files access" has been granted. */
    val granted: Boolean get() = Environment.isExternalStorageManager()

    /** Settings screen for this app's "All files access" toggle. */
    fun grantIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .apply { data = Uri.parse("package:${appContext.packageName}") }

    /** First of [names] that exists in Downloads, or null. */
    fun findFirst(names: List<String>): File? =
        names.asSequence()
            .map { dir.resolve(it) }
            .firstOrNull { it.isFile && it.length() > 0 }

    /**
     * Copies [source] to Downloads as [name].
     *
     * @return the written file, or null with [lastError] set.
     */
    fun export(source: File, name: String): File? = runCatching {
        dir.mkdirs()
        val destination = dir.resolve(name)
        source.copyTo(destination, overwrite = true)
        destination
    }.onFailure { lastError = it }.getOrNull()

    /** Why the last [export] or [findFirst] failed, for surfacing in the UI. */
    var lastError: Throwable? = null
        private set

    /** Explains a failed shared-storage operation in terms the user can act on. */
    fun explain(error: Throwable?): String = when {
        !granted -> "Grant All files access to read Download/kaorios.dex and write the module."
        error == null -> "Could not access ${dir.absolutePath}."
        else -> error.message ?: error::class.simpleName.orEmpty()
    }
}
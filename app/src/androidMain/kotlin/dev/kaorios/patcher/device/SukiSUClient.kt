package dev.kaorios.patcher.device

import java.io.File

/**
 * Talks to the device's root daemon.
 *
 * Every flavour exposes a world-executable `/system/bin/su`, but `/data/adb` is root-only, so
 * the app process cannot stat the manager's own CLI. Identity therefore comes from `su -v` and
 * the SELinux context `su -c id` reports, never from probing manager paths.
 */
class SukiSUClient {

    private val suBin = File("/system/bin/su")

    private var cachedStatus: RootStatus? = null

    fun rootStatus(refresh: Boolean = false): RootStatus {
        cachedStatus?.takeIf { !refresh }?.let { return it }
        val status = probe()
        cachedStatus = status
        return status
    }

    private fun probe(): RootStatus {
        if (!suBin.canExecute()) return RootStatus.NONE

        val id = run(listOf(suBin.absolutePath, "-c", "id")).getOrNull()?.trim().orEmpty()
        if (!id.contains("uid=0")) return RootStatus.NONE

        val suVersion = run(listOf(suBin.absolutePath, "-v")).getOrNull()?.trim().orEmpty()
        val flavour = detectFlavour(suVersion, id)
        val version = suVersion.ifEmpty { id.substringAfter("context=", "").trim() }
        return RootStatus(
            available = true,
            flavour = flavour,
            version = version,
            binDir = if (flavour == RootFlavour.MAGISK) null else KSU_BIN_DIR
        )
    }

    /**
     * `su -v` names the implementation on every supported manager, so it is authoritative.
     * The SELinux context is the fallback: KernelSU family runs in the `ksu` domain, Magisk
     * in `magisk`.
     */
    private fun detectFlavour(suVersion: String, idOutput: String): RootFlavour = when {
        suVersion.contains("SukiSU", ignoreCase = true) -> RootFlavour.SUKISU
        suVersion.contains("KernelSU", ignoreCase = true) -> RootFlavour.KERNELSU
        suVersion.contains("Magisk", ignoreCase = true) -> RootFlavour.MAGISK
        idOutput.contains("magisk", ignoreCase = true) -> RootFlavour.MAGISK
        else -> RootFlavour.KERNELSU
    }

    /** Reads a system property through the root shell, falling back to [android.os.Build]. */
    fun getProperty(key: String, fallback: String = ""): String {
        val fromRoot = run(listOf("sh", "-c", "getprop $key")).getOrNull()?.trim()
        if (!fromRoot.isNullOrEmpty()) return fromRoot
        return fallback
    }

    fun kernelRelease(): String = getProperty("ro.kernel.version", System.getProperty("os.version").orEmpty())

    /**
     * Whether [path] is a regular file on the device.
     *
     * The artifact paths sit on read-only system mounts a plain shell can stat, so root is only
     * the fallback for a ROM that hides them from the app uid. This is what distinguishes a ROM
     * that never carried the file (AOSP ships no `miui-services.jar`) from a failed pull.
     */
    fun fileExists(path: String): Boolean {
        val probe = "if [ -f '$path' ]; then echo yes; else echo no; fi"
        run(listOf("sh", "-c", probe)).getOrNull()?.trim()?.let { output ->
            if (output == "yes" || output == "no") return output == "yes"
        }
        return runAsRoot(probe).map { it.trim() == "yes" }.getOrDefault(false)
    }

    fun collectDeviceSpec(): DeviceSpec {
        val status = rootStatus(refresh = true)
        // HOS carries `ro.mi.os.version.name` (HyperOS) and/or `ro.miui.ui.version.*` (MIUI);
        // an AOSP-derived ROM carries none of them, which is what separates the two families.
        // The properties only label the ROM — whether `miui-services.jar` exists is probed with
        // [fileExists] at pull time, because a debloated HOS can lose the file without them.
        val hosVersion = getProperty("ro.mi.os.version.name")
            .ifEmpty { getProperty("ro.miui.ui.version.name") }
        val isHos = hosVersion.isNotEmpty() || getProperty("ro.miui.ui.version.code").isNotEmpty()
        return DeviceSpec(
            arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            abi = getProperty("ro.product.cpu.abi", android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
            sdkInt = android.os.Build.VERSION.SDK_INT,
            androidVersion = android.os.Build.VERSION.RELEASE ?: "unknown",
            fingerprint = android.os.Build.FINGERPRINT ?: "unknown",
            kernelRelease = kernelRelease(),
            rootFlavour = status.flavour,
            rootVersion = status.version,
            romType = if (isHos) RomType.HOS else RomType.AOSP,
            hosVersion = hosVersion,
        )
    }

/**
 * Copies [remotePath] into the app workspace as root.
 *
 * The bytes are streamed out of `su`'s stdout and written by this process rather than by a
 * shell redirect: a redirect would run in the root context, and SELinux denies root writes
 * into another UID's `app_data_file`, so the workspace directory is unreachable that way.
 * Writing from the app process keeps the file owned by the app's own uid.
 */
fun pull(remotePath: String, destination: File): Result<File> = runCatching {
    val stderr = File.createTempFile("kp-pull-err", ".txt")
    try {
        val process = ProcessBuilder(suBin.absolutePath, "-c", "cat '$remotePath'")
            .redirectError(stderr)
            .start()
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, destination.name + ".part")
        staging.outputStream().buffered(STREAM_BUFFER).use { out ->
            process.inputStream.buffered(STREAM_BUFFER).use { input -> input.copyTo(out) }
        }
        val stderrText = stderr.readText().trim()
        if (!process.waitFor(PULL_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroy()
            error("pull timed out after ${PULL_TIMEOUT_MINUTES}m: $remotePath")
        }
        if (process.exitValue() != 0) {
            error("cat $remotePath failed (exit ${process.exitValue()}): $stderrText")
        }
        if (!staging.isFile || staging.length() == 0L) {
            error("pull produced an empty file for $remotePath${if (stderrText.isEmpty()) "" else ": $stderrText"}")
        }
        if (!staging.renameTo(destination)) {
            staging.copyTo(destination, overwrite = true)
            staging.delete()
        }
        destination
    } finally {
        stderr.delete()
    }
}

    /** Writes [content] to [remotePath] as root, creating parent directories. */
    fun writeRemote(remotePath: String, content: ByteArray): Result<Unit> {
        val parent = remotePath.substringBeforeLast('/', "")
        val tmp = File(System.getProperty("java.io.tmpdir"), "kp-upload-${content.size}-${remotePath.hashCode()}")
        return try {
            tmp.writeBytes(content)
            runAsRoot(
                "mkdir -p '$parent' && cat '${tmp.absolutePath}' > '$remotePath' && rm -f '${tmp.absolutePath}'"
            ).map { }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Flashes [zip] through whichever manager is installed.
     *
     * The KernelSU family exposes `ksud module install`; that path only exists once `su` has
     * already elevated, because `/data/adb` is unreadable to the app process.
     */
    fun installModule(zip: File): Result<String> {
        val status = rootStatus()
        if (!status.available) return Result.failure(IllegalStateException("No root manager available"))
        val zipPath = zip.absolutePath
        return when (status.flavour) {
            RootFlavour.MAGISK -> runAsRoot("magisk --install-module '$zipPath'")
            else -> runAsRoot("$KSU_BIN_DIR/ksud module install '$zipPath'")
        }
    }

    fun runAsRoot(command: String): Result<String> {
        val status = rootStatus()
        if (!status.available) return Result.failure(IllegalStateException("No root manager available"))
        return run(listOf(suBin.absolutePath, "-c", command))
    }

    private fun run(command: List<String>): Result<String> = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy()
            error("command timed out: ${command.joinToString(" ")}")
        }
        val code = process.exitValue()
        if (code != 0) error("exit $code: ${output.trim()}")
        output
    }

    private companion object {
        /** KernelSU/SukiSU userspace CLI directory. Reachable only after `su` elevates. */
        const val KSU_BIN_DIR = "/data/adb/ksu/bin"

        const val STREAM_BUFFER = 64 * 1024

        /** `framework.jar`, `services.jar` and `miui-services.jar` are tens of MB; per pull. */
        const val PULL_TIMEOUT_MINUTES = 5L
    }
}
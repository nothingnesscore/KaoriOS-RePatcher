package dev.kaorios.patcher.workspace

import java.io.File

/**
 * On-disk layout for one patching session.
 *
 * Pulled jars are kept beside their extracted DEX trees so a failed run can be inspected
 * without re-pulling, and every intermediate is app-private.
 */
class Workspace(private val root: File) {

    init {
        require(root.mkdirs() || root.isDirectory) { "cannot create workspace at ${root.absolutePath}" }
    }

    val pulledDir: File get() = root.resolve("pulled").ensure()
    val dexDir: File get() = root.resolve("dex").ensure()
    val smaliDir: File get() = root.resolve("smali").ensure()
    val outDir: File get() = root.resolve("out").ensure()
    val moduleDir: File get() = root.resolve("module").ensure()
    val logDir: File get() = root.resolve("logs").ensure()

    /**
     * Latest release assets fetched by [dev.kaorios.patcher.storage.ReleaseSync].
     *
     * Deliberately not cleared by [clear] and not counted by [totalBytes]: it is the offline
     * cache for the runtime dex and Toolbox APK, not a per-run intermediate.
     */
    val assetsDir: File get() = root.resolve("assets").ensure()

    /** Disassembled tree for [jarName]'s `classesN.dex` splits. */
    fun smaliTreeFor(jarName: String, dexName: String): File = smaliDir.resolve("$jarName/$dexName")

    fun dexFileFor(jarName: String, dexName: String): File = dexDir.resolve(jarName).resolve(dexName)

    fun pulledFile(name: String): File = pulledDir.resolve(name)

    /** Removes every intermediate, keeping nothing. */
    fun clear() {
        listOf(pulledDir, dexDir, smaliDir, outDir, moduleDir, logDir).forEach { dir ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    fun totalBytes(): Long = listOf(pulledDir, dexDir, smaliDir, outDir, moduleDir)
        .sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    private fun File.ensure(): File = apply { mkdirs() }
}
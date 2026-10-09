package dev.kaorios.engine.cli

import dev.kaorios.engine.dex.DexArchiveRoundTrip
import dev.kaorios.engine.dex.KaoriosRuntime
import dev.kaorios.engine.module.KsuModuleBuilder
import dev.kaorios.engine.module.MountMode
import dev.kaorios.engine.patch.PatchEngine
import dev.kaorios.engine.patch.PatchMode
import dev.kaorios.engine.patch.PatchSelection
import dev.kaorios.engine.patch.PatchStatus
import dev.kaorios.engine.patch.PatchTarget
import java.io.File

/**
 * Desktop entry point for the same round-trip the app runs on device.
 *
 * Point it at a directory of pulled jars; it disassembles only the target classes, applies the
 * engine, rebuilds the archives that changed, and packages a module. Exists so a real ROM can
 * be validated in seconds without flashing anything.
 *
 * Usage: `PatchCli <pulled-dir> <work-dir> [mode] [mount] [--disassemble-only]
 * [--selection=…] [kaorios.dex]`
 */
object PatchCli {

    /**
     * Where each pulled file belongs inside the module's overlay.
     *
     * `SettingsProvider.apk` is absent because upstream v2.0.6.1 retired provider patching
     * altogether: the stock APK is never rebuilt, never re-signed and never shipped — re-zipping
     * it destroys the APK Signing Scheme v2 block, `Package Manager` drops the package, and
     * `system_server` deadlocks waiting for it. The guide patches `framework.jar` and
     * `services.jar` only.
     */
    private val MODULE_PATHS = mapOf(
        "framework.jar" to "system/framework/framework.jar",
        "services.jar" to "system/framework/services.jar",
        "miui-services.jar" to "system/system_ext/framework/miui-services.jar",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { usage() }
        val pulled = File(args[0])
        val work = File(args[1])
        val mode = PatchMode.valueOf(args.getOrNull(2)?.uppercase() ?: "ALL_IN_ONE")
        val mount = MountMode.valueOf(args.getOrNull(3)?.uppercase() ?: "MAGIC_MOUNT")

        // Everything after the mount mode is free-form: parsed here so the selection drives
        // disassembly too, not just the patch pass.
        val options = args.drop(4)
        val selectionArg = options.firstOrNull { it.startsWith("--selection=") }
        val selection = selectionArg?.let { parseSelection(it.removePrefix("--selection=")) }
            ?: PatchSelection.of(mode)

        require(pulled.isDirectory) { "not a directory: $pulled" }

        val smaliRoot = work.resolve("smali").also { it.deleteRecursively(); it.mkdirs() }
        val outDir = work.resolve("out").also { it.deleteRecursively(); it.mkdirs() }
        val moduleDir = work.resolve("module").also { it.deleteRecursively(); it.mkdirs() }

        val artifacts = MODULE_PATHS.keys.map { pulled.resolve(it) }.filter { it.isFile }
        require(artifacts.isNotEmpty()) { "no known artifacts under $pulled" }

        val targetNames = PatchEngine.disassemblyFiles(selection)
        println(
            "mode=${mode.name} selection=$selection mount=${mount.name} " +
                "targets=${targetNames.size} patched=${PatchEngine.targets(selection).keys.size}"
        )

        val roundTrip = DexArchiveRoundTrip()

        val disassemblies = artifacts.associate { artifact ->
            artifact.name to roundTrip.disassemble(
                source = artifact,
                artifactName = artifact.name,
                smaliRoot = smaliRoot,
                targetFileNames = targetNames,
            )
        }

        val found = disassemblies.values.sumOf { d -> d.slices.sumOf { s -> s.descriptors.size } }
        println("disassembled $found target class(es) from ${artifacts.size} artifact(s)")
        if (found == 0) {
            println("!! no target class present in these artifacts; nothing to do")
            return
        }

        // Stops here so a disassembly can be inspected before the engine ever touches it.
        // The stop flag and the runtime dex travel in either order, with the dex optional. It
        // used to be positional, so a desktop run that wanted a runtime had to pass a placeholder
        // for the flag's slot or its dex was silently dropped and the patchers failed to link
        // against the hook.
        val pullOnly = "--disassemble-only" in options
        val runtimePath = options.firstOrNull { it != "--disassemble-only" && !it.startsWith("--selection=") }
        val runtime = runtimePath?.let { File(it) }?.takeIf { it.isFile }

        if (pullOnly) {
            println("disassembly only; skipping patch and rebuild")
            return
        }
        // A pure Build spoof rewrites Build/Build$VERSION and injects no call site, so it is the
        // one selection that legitimately runs without a runtime dex. Every other selection that
        // injects a call site links against android.security.kaorios.KaoriosHook and would fail
        // ART verification at boot without it.
        val needsRuntime = selection.needsRuntime
        if (runtime != null) {
            val classes = KaoriosRuntime.validate(runtime)
            println("kaorios runtime: ${runtime.name} defines ${classes.size}/${KaoriosRuntime.REQUIRED_DESCRIPTORS.size} required class(es)")
            if (classes.size < KaoriosRuntime.REQUIRED_DESCRIPTORS.size) {
                println("!! runtime dex is missing required class(es); the hooks expecting them will not resolve")
            }
        } else if (needsRuntime) {
            println("!! no KaoriOS runtime dex supplied; patched call sites would not resolve")
            println("!! refusing to build a module — pass kaorios.dex as the last argument")
            return
        } else {
            println("no call sites injected, no KaoriOS runtime required")
        }

        val outcomes = patchTree(smaliRoot, selection)
        workspaceSmali = smaliRoot
        val patched = outcomes.filter { it.status == PatchStatus.PATCHED }
        println("patched=${patched.size} ok=${outcomes.count { it.ok }} failed=${outcomes.count { !it.ok }}")
        for (outcome in outcomes) {
            println("  ${outcome.status.name.padEnd(16)} ${outcome.className}")
            outcome.detail?.let { println("      $it") }
            // A layout rejection is expected on an unfamiliar ROM. Anything else is an engine
            // bug, and the one-line detail hides where — so re-run and print the trace.
            if (!outcome.ok && outcome.detail?.startsWith("UNSUPPORTED_LAYOUT") != true) {
                diagnose(artifacts, selection, outcome)
            }
        }

        val failures = outcomes.filter { !it.ok }
        if (failures.isNotEmpty()) {
            println("!! ${failures.size} target(s) failed and were left byte-identical:")
            failures.forEach {
                println("  ${it.className}${it.detail?.let { d -> " ($d)" } ?: ""}")
            }
            println("!! refusing to rebuild: a half-applied patch set would ship silently broken hooks")
            return
        }

        val rebuilt = artifacts.mapNotNull { artifact ->
            val disassembly = disassemblies.getValue(artifact.name)
            val runtimeDex = if (artifact.name == "framework.jar" && runtime != null) {
                runtime.readBytes()
            } else {
                null
            }
            val result = roundTrip.rebuild(disassembly, outDir.resolve(artifact.name), runtimeDex)
            if (result.changedDexes.isEmpty()) {
                println("${artifact.name}: unchanged, copied through")
                null
            } else {
                val host = result.runtimeHost?.let { "; runtime folded into $it" } ?: ""
                println("${artifact.name}: rebuilt ${result.changedDexes.joinToString()}$host")
                artifact.name to result.patchedClasses
            }
        }.toMap()

        if (rebuilt.isEmpty()) {
            println("!! nothing changed; no module written")
            return
        }
        if (needsRuntime && !rebuilt.containsKey("framework.jar")) {
            println("!! KaoriOS runtime was not merged into framework.jar; no module written")
            println("   (framework.jar missing from $pulled, or it did not change)")
            return
        }

        // Digests of the pulled originals, so the installer can refuse a ROM this module was not
        // built for. A module overwrites framework.jar with no rollback path; a mismatch would
        // boot-loop.
        val bundle = KsuModuleBuilder.Bundle(
            versionName = "1.0.0",
            mountMode = mount,
            artifacts = artifacts
                .filter { rebuilt.containsKey(it.name) }
                .map { KsuModuleBuilder.Artifact(outDir.resolve(it.name), MODULE_PATHS.getValue(it.name)) },
            stockDigests = artifacts
                .filter { rebuilt.containsKey(it.name) }
                .associate { artifact ->
                    val relative = MODULE_PATHS.getValue(artifact.name).removePrefix("system/")
                    "/system/$relative" to sha256(artifact)
                },
        )
        val zip = KsuModuleBuilder().build(bundle, moduleDir.resolve("kaorios_patcher.zip"))
        println("module: ${zip.absolutePath} (${zip.length() / 1024} KB)")
    }

    /** Re-runs a failed target's patcher directly so an engine bug surfaces with a trace. */
    private fun diagnose(artifacts: List<File>, selection: PatchSelection, outcome: TargetOutcome) {
        val file = File(workspaceSmali, outcome.className)
        if (!file.isFile) return
        val target = PatchEngine.targets(selection)[file.name] ?: return
        val failure = runCatching { target.patch(file.readText()) }.exceptionOrNull()
        if (failure == null) {
            println("      (patcher itself succeeded; the failure is in verify())")
        } else {
            println("      --- stack trace ---")
            failure.stackTrace.take(12).forEach { println("      $it") }
        }
    }

    private var workspaceSmali: File = File(".")

    /** Applies the engine to every target file in [smaliRoot], rewriting only what changed. */
    private fun patchTree(smaliRoot: File, selection: PatchSelection): List<TargetOutcome> {
        val targets = PatchEngine.targets(selection)
        val outcomes = mutableListOf<TargetOutcome>()
        for (target in targets.keys) {
            smaliRoot.walkTopDown()
                .filter { it.isFile && it.name == target }
                .forEach { file ->
                    val relative = file.relativeTo(smaliRoot).invariantSeparatorsPath
                    val original = file.readText()
                    val result = PatchEngine.applyTargetPatch(target, original, targets)
                    if (result.changed) file.writeText(result.content)
                    outcomes += TargetOutcome(
                        artifact = relative.substringBefore("/"),
                        className = relative,
                        status = result.status,
                        detail = result.error,
                    )
                }
        }
        return outcomes
    }

    private data class TargetOutcome(
        val artifact: String,
        val className: String,
        val status: PatchStatus,
        val detail: String?,
    ) {
        /** Mirrors `PatchEngine.run` — a guide-skippable absence is not a half-applied patch. */
        val ok: Boolean get() = status == PatchStatus.PATCHED ||
            status == PatchStatus.ALREADY_PATCHED ||
            status == PatchStatus.NOT_TARGET
    }

    /** Streams the digest so an 88 MB jar is never held in memory. */
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Parses `--selection=<flag>[,<flag>…]` into a [PatchSelection].
     *
     * Named flags turn on, everything else is off — the spec is the whole selection, not a
     * delta — so `--selection=hooks,corePatch,flagSecure,hideDev` is exactly what the app's
     * default toggles send an Android 13-16 device through (`buildSpoof` is A17-only).
     */
    private fun parseSelection(spec: String): PatchSelection {
        val flags = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val unknown = flags.filterNot { it in SELECTION_FLAGS }
        require(unknown.isEmpty()) {
            "unknown selection flag(s): ${unknown.joinToString()} — expected one of " +
                SELECTION_FLAGS.joinToString()
        }
        return PatchSelection(
            hooks = "hooks" in flags,
            buildSpoof = "build" in flags,
            corePatch = "corePatch" in flags,
            flagSecure = "flagSecure" in flags,
            hideDevStatus = "hideDev" in flags,
        )
    }

    private val SELECTION_FLAGS = setOf("hooks", "build", "corePatch", "flagSecure", "hideDev")

    private fun usage(): String =
        "usage: PatchCli <pulled-dir> <work-dir> [ALL_IN_ONE|HOOKS|BUILD_SPOOF|FULL] " +
            "[MAGIC_MOUNT|HYBRIDMOUNT|DIRECT_OVERLAY] [--disassemble-only] " +
            "[--selection=hooks,build,corePatch,flagSecure,hideDev] [kaorios.dex]"
}
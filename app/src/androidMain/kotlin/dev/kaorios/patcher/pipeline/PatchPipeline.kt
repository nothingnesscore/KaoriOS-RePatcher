package dev.kaorios.patcher.pipeline

import android.util.Log
import dev.kaorios.engine.dex.DexArchiveRoundTrip
import dev.kaorios.engine.dex.KaoriosRuntime
import dev.kaorios.engine.patch.PatchEngine
import dev.kaorios.engine.patch.PatchSelection
import dev.kaorios.engine.patch.PatchStatus
import dev.kaorios.patcher.workspace.Workspace
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every line this pipeline emits goes to logcat under one tag, so a run can be watched live. */
object PatchLog {
    const val TAG = "KaoriosPatcher"

    /**
     * Cap on the in-memory tail the Patch tab renders. Older lines are dropped from memory but
     * stay in the file, so nothing is lost by watching a long run on screen.
     */
    private const val MAX_TAIL_LINES = 600

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private val tail = ArrayDeque<String>()
    private var sink: FileWriter? = null
    private var listener: ((List<String>) -> Unit)? = null

    /**
     * Mirrors every subsequent line into [file], appending, so one file covers the whole session
     * and survives the process. This is what makes a run inspectable after the fact and pullable
     * off the device.
     */
    fun attach(file: File) {
        synchronized(lock) {
            closeSink()
            runCatching {
                file.parentFile?.mkdirs()
                sink = FileWriter(file, true)
            }.onFailure { sink = null }
        }
    }

    /** Writes a run separator, so several runs in one file stay readable. */
    fun marker(message: String) = emit("=", message)

    fun step(step: PatchStep) = emit("I", "step -> ${step::class.simpleName}")

    /** One target, exactly as guide §5 defines the vocabulary. [path] is relative to the tree. */
    fun target(status: PatchStatus, path: String, detail: String?) =
        emit("I", "$status $path" + (detail?.let { " ($it)" } ?: ""))

    fun info(message: String) = emit("I", message)

    fun warn(message: String) = emit("W", message)

    fun fail(message: String, error: Throwable? = null) = emit("E", message, error)

    /** Drops the tail and tells the listener, for when the workspace that backs it is cleared. */
    fun reset() {
        synchronized(lock) {
            tail.clear()
            listener?.invoke(emptyList())
        }
    }

    /** Receives a copy of the tail on every line; `null` unsubscribes. */
    fun setListener(listener: ((List<String>) -> Unit)?) {
        synchronized(lock) { this.listener = listener }
    }

    fun close() {
        synchronized(lock) { closeSink() }
    }

    private fun closeSink() {
        runCatching { sink?.close() }
        sink = null
    }

    private fun emit(level: String, message: String, error: Throwable? = null) {
        when (level) {
            "I" -> Log.i(TAG, message)
            "W" -> Log.w(TAG, message)
            "=" -> Log.i(TAG, "== $message")
            else -> Log.e(TAG, message, error)
        }
        synchronized(lock) {
            val line = "${stamp.format(Date())} $level $message" +
                (error?.let { " — $it" } ?: "")
            if (tail.size >= MAX_TAIL_LINES) tail.removeFirst()
            tail.addLast(line)
            runCatching {
                sink?.write(line)
                sink?.write("\n")
                sink?.flush()
            }
            listener?.invoke(tail.toList())
        }
    }
}

/** Progress reported as the pipeline advances; the UI renders this directly. */
sealed interface PatchStep {
    data object Idle : PatchStep
    data object Pulling : PatchStep
    data object Syncing : PatchStep
    data object Disassembling : PatchStep
    data object Patching : PatchStep
    data object Reassembling : PatchStep
    data object BuildingModule : PatchStep
    data class Done(val moduleZip: File) : PatchStep
    data class Failed(val message: String) : PatchStep
}

/**
 * Per-target outcome, retained so the UI can explain a partial failure.
 *
 * [ok] mirrors `PatchEngine.run`: a target the guide marks skippable and this ROM does not carry
 * comes back `NOT_TARGET`, which leaves its file byte-identical *by design* and must not count as
 * a half-applied patch set. `UNSUPPORTED_LAYOUT` and `FAILED` still do.
 */
data class TargetOutcome(
    val artifact: String,
    val className: String,
    val status: PatchStatus,
    val detail: String?
) {
    val ok: Boolean get() = status == PatchStatus.PATCHED ||
        status == PatchStatus.ALREADY_PATCHED ||
        status == PatchStatus.NOT_TARGET
}

/**
 * Applies the engine to a smali tree on disk.
 *
 * Only files the engine reports as changed are written back, so a rejected layout cannot
 * leave a half-patched tree behind.
 */
class PatchPipeline(private val workspace: Workspace) {

    data class TreeResult(
        val outcomes: List<TargetOutcome>,
        val failures: List<TargetOutcome>
    ) {
        val ok: Boolean get() = failures.isEmpty()
    }

    /** Patches every target class inside [smaliRoot], rewriting files in place. */
    fun patchTree(smaliRoot: File, selection: PatchSelection): TreeResult {
        val targets = PatchEngine.targets(selection)
        val outcomes = mutableListOf<TargetOutcome>()

        for (target in targets.keys) {
            val matches = smaliRoot.walkTopDown()
                .filter { it.isFile && it.name == target }
                .toList()
            if (matches.isEmpty()) continue

            for (file in matches) {
                val className = file.relativeTo(smaliRoot).invariantSeparatorsPath
                val artifact = className.substringBefore("/")
                val original = file.readText()
                val result = PatchEngine.applyTargetPatch(target, original, targets)

                if (result.changed) {
                    file.writeText(result.content)
                }
                PatchLog.target(result.status, className, result.error)
                outcomes += TargetOutcome(artifact, className, result.status, result.error)
            }
        }

        return TreeResult(outcomes, outcomes.filterNot { it.ok })
    }

    /** Patches a whole disassembled tree and reports the aggregate verdict. */
    fun runAll(smaliRoots: Map<File, String>, selection: PatchSelection): TreeResult {
        val all = mutableListOf<TargetOutcome>()
        var failure = false
        for ((root, label) in smaliRoots) {
            val result = patchTree(root, selection)
            all += result.outcomes.map { it.copy(artifact = label) }
            if (!result.ok) failure = true
        }
        return TreeResult(all, if (failure) all.filterNot { it.ok } else emptyList())
    }

    data class RoundTrip(
        val outcomes: List<TargetOutcome>,
        val failures: List<TargetOutcome>,
        /** Rebuilt artifacts in [Workspace.outDir]; empty when nothing changed. */
        val artifacts: List<File>,
        /** Classes the supplied KaoriOS runtime dex contributed, when one was merged. */
        val runtimeClasses: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = failures.isEmpty() && artifacts.isNotEmpty()
    }

    /**
     * Full round trip over the pulled jars: disassemble the target classes, patch them, then
     * rebuild only the dex files that actually changed.
     *
     * A target the engine rejects leaves its smali untouched, so its dex is never rewritten and
     * the corresponding archive is copied through byte-for-byte.
     *
     * [kaoriosRuntime] is the framework dex carrying `android.security.kaorios.KaoriosHook;`.
     * It is merged into `framework.jar` because every injected call site links against it; when
     * it is absent the run still succeeds, but [RoundTrip.runtimeClasses] stays empty and the
     * caller is expected to refuse to ship the result.
     */
    fun patchAndRepackage(
        inputs: List<Pair<String, File>>,
        selection: PatchSelection,
        kaoriosRuntime: File? = null,
        onProgress: (PatchStep) -> Unit = {},
    ): RoundTrip {
        val roundTrip = DexArchiveRoundTrip()
        // One place every phase transition passes through, so logcat and the UI never disagree.
        val step: (PatchStep) -> Unit = { PatchLog.step(it); onProgress(it) }
        val disassemblies = inputs.map { (artifactName, file) ->
            step(PatchStep.Disassembling)
            PatchLog.info("disassembling $artifactName (${file.length()} bytes)")
            artifactName to roundTrip.disassemble(
                source = file,
                artifactName = artifactName,
                smaliRoot = workspace.smaliDir,
                targetFileNames = PatchEngine.disassemblyFiles(selection),
            )
        }

        step(PatchStep.Patching)
        PatchLog.info(
            "selection=${selection.describe()} targets=${PatchEngine.targets(selection).keys.size}"
        )
        val treeResult = patchTree(workspace.smaliDir, selection)

        step(PatchStep.Reassembling)
        var runtimeClasses: List<String> = emptyList()
        val artifacts = mutableListOf<File>()
        for ((artifactName, disassembly) in disassemblies) {
            val isFramework = artifactName == FRAMEWORK
            if (disassembly.slices.isEmpty() && !(isFramework && kaoriosRuntime != null)) {
                PatchLog.info("$artifactName: no target class present, skipped")
                continue
            }
            var runtimeDex: ByteArray? = null
            if (isFramework && kaoriosRuntime != null) {
                runtimeClasses = KaoriosRuntime.validate(kaoriosRuntime)
                runtimeDex = kaoriosRuntime.readBytes()
                PatchLog.info("runtime dex defines ${runtimeClasses.size} class(es)")
            }
            val target = workspace.outDir.resolve(artifactName)
            val rebuilt = roundTrip.rebuild(disassembly, target, runtimeDex)
            if (rebuilt.changedDexes.isNotEmpty()) {
                PatchLog.info(
                    "$artifactName: rebuilt ${rebuilt.changedDexes.joinToString()}" +
                        (rebuilt.runtimeHost?.let { ", runtime folded into $it" } ?: "")
                )
                artifacts += target
            } else {
                PatchLog.info("$artifactName: unchanged, copied through")
            }
        }
        return RoundTrip(
            outcomes = treeResult.outcomes,
            failures = treeResult.failures,
            artifacts = artifacts,
            runtimeClasses = runtimeClasses,
        )
    }

    private companion object {
        const val FRAMEWORK = "framework.jar"
    }
}

/** Compact, loggable form of a selection: only what differs from the default shows up. */
fun PatchSelection.describe(): String = listOfNotNull(
    "hooks=$hooks",
    "build=$buildSpoof",
    "corePatch=$corePatch",
    "flagSecure=$flagSecure",
    "hideDevStatus=$hideDevStatus",
).joinToString(" ")
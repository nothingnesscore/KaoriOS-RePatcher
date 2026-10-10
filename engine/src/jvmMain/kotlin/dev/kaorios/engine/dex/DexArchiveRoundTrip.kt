package dev.kaorios.engine.dex

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** One dex inside an archive, plus the target classes that were extracted from it. */
data class DexSlice(
    val entryName: String,
    val descriptors: List<String>,
)

/**
 * Bookkeeping for a single artifact so the patch step can run between the two halves.
 *
 * [baseline] fingerprints each emitted smali file so [rebuild] can tell which dexes actually
 * changed and leave the rest byte-identical. [dexEntries] records what [disassemble] already
 * learned about each dex — its size and whether it defines the hook — so the runtime merge does
 * not have to reopen the archive.
 */
data class Disassembly(
    val artifactName: String,
    val source: File,
    val smaliRoot: File,
    val slices: List<DexSlice>,
    val baseline: Map<String, Int>,
    val dexEntries: List<KaoriosRuntime.DexEntry> = emptyList(),
)

data class RebuildResult(
    val changedDexes: List<String>,
    val patchedClasses: List<String>,
    /** Entry the KaoriOS runtime was folded into, when one was supplied. */
    val runtimeHost: String? = null,
)

class DexArchiveRoundTrip(private val apiLevel: Int = DexRoundTrip.DEFAULT_API) {

    /**
     * Extracts every target class from [source] into `[smaliRoot]/<artifact>/<dexEntry>/`.
     *
     * Nesting under the artifact name keeps `PatchPipeline`'s artifact labelling intact, and the
     * entry name keeps multi-dex jars unambiguous.
     */
    fun disassemble(
        source: File,
        artifactName: String,
        smaliRoot: File,
        targetFileNames: Set<String>,
    ): Disassembly {
        val slices = mutableListOf<DexSlice>()
        val baseline = mutableMapOf<String, Int>()
        val dexEntries = mutableListOf<KaoriosRuntime.DexEntry>()

        ZipFile(source).use { zip ->
            val staging = File(smaliRoot.parentFile, "$artifactName.dex-staging").also { it.mkdirs() }
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".dex")) continue

                val staged = File(staging, entry.name)
                staged.writeBytes(zip.getInputStream(entry).readBytes())
                val dexFile = try {
                    DexFileFactory.loadDexFile(staged, Opcodes.forApi(apiLevel))
                } catch (e: Exception) {
                    throw IllegalStateException("cannot read ${artifactName}!${entry.name}: ${e.message}", e)
                }
                if (KaoriosRuntime.DEX_ENTRY_NAME.matches(entry.name)) {
                    dexEntries += KaoriosRuntime.DexEntry(
                        name = entry.name,
                        size = entry.size,
                        definesHook = dexFile.classes.any { it.type == KaoriosRuntime.HOOK_DESCRIPTOR },
                    )
                }

                val outDir = File(smaliRoot, "$artifactName/${entry.name}")
                val descriptors = DexRoundTrip.disassembleTargets(dexFile, outDir, targetFileNames, apiLevel)
                for (descriptor in descriptors) {
                    val file = File(outDir, DexRoundTrip.smaliRelativePathFor(descriptor))
                    if (file.isFile) baseline[file.absolutePath] = file.readText().hashCode()
                }
                if (descriptors.isNotEmpty()) slices += DexSlice(entry.name, descriptors)
                staged.delete()
            }
        }
        if (dexEntries.isEmpty()) error("${source.name} holds no classes*.dex")
        return Disassembly(artifactName, source, smaliRoot, slices, baseline, dexEntries)
    }

    /**
     * Reassembles the changed classes and writes a new archive to [output].
     *
     * A dex is only rebuilt when at least one of its smali files differs from the baseline, so
     * untouched dexes are copied through verbatim. The archive is rewritten entry for entry — no
     * entry is ever added or removed, so the dex count `boot-framework.oat` was built against
     * stays exactly as it was.
     *
     * [runtimeDex] is the KaoriOS runtime. It is folded into one of the dexes that are already
     * there ([KaoriosRuntime.hostSlots] picks which) rather than appended as `classes7.dex`:
     * `OatFile::Open` compares the dex count of the jar against the one recorded in the boot
     * oat and rejects the image outright on a mismatch, which is what boots a patched ROM into
     * an interpreter-only framework and from there into a watchdog loop.
     */
    @JvmOverloads
    fun rebuild(
        disassembly: Disassembly,
        output: File,
        runtimeDex: ByteArray? = null,
    ): RebuildResult {
        val changedDexes = mutableMapOf<String, ByteArray>()
        val patchedClasses = mutableListOf<String>()
        val scratch = File(disassembly.smaliRoot.parentFile, "${disassembly.artifactName}.dex-out")

        val deferred = mutableListOf<DeferredSlice>()
        ZipFile(disassembly.source).use { zip ->
            for (slice in disassembly.slices) {
                val outDir = File(disassembly.smaliRoot, "${disassembly.artifactName}/${slice.entryName}")
                val files = slice.descriptors
                    .map { File(outDir, DexRoundTrip.smaliRelativePathFor(it)) }
                    .filter { it.isFile }
                if (files.isEmpty()) continue

                val dirty = files.any { it.readText().hashCode() != disassembly.baseline[it.absolutePath] }
                if (!dirty) continue

                scratch.mkdirs()
                val assembled = File(scratch, slice.entryName)
                val replacements = try {
                    DexRoundTrip.assemble(files, assembled, apiLevel)
                        .classes
                        .associateBy { it.type }
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "${disassembly.artifactName}!${slice.entryName}: ${e.message}",
                        e,
                    )
                }

                val staged = File(scratch, "orig-${slice.entryName}")
                val originalEntry = zip.getEntry(slice.entryName)
                    ?: error("${disassembly.artifactName} lost ${slice.entryName} between passes")
                staged.writeBytes(zip.getInputStream(originalEntry).readBytes())
                val original = DexFileFactory.loadDexFile(staged, Opcodes.forApi(apiLevel))

                val merged = File(scratch, "merged-${slice.entryName}")
                try {
                    DexRoundTrip.writeMergedDex(original, replacements, merged)
                    changedDexes[slice.entryName] = merged.readBytes()
                    patchedClasses += replacements.keys.sorted()
                } catch (e: DexRoundTrip.MethodIdOverflow) {
                    // This dex is already at the 65536 method-id ceiling. Defer the
                    // replacements; they will be relocated to a dex that has headroom.
                    deferred += DeferredSlice(slice.entryName, staged, replacements)
                }
            }

            relocateOverfullSlices(zip, scratch, disassembly, deferred, changedDexes, patchedClasses)
        }

        val runtimeHost = runtimeDex?.let { mergeRuntime(disassembly, changedDexes, it) }

        output.parentFile?.mkdirs()
        val expected = LinkedHashSet<String>()
        ZipFile(disassembly.source).use { zip ->
            AlignedZipWriter(output.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    val bytes = changedDexes[entry.name] ?: zip.getInputStream(entry).readBytes()
                    out.put(
                        name = entry.name,
                        data = bytes,
                        stored = entry.method == ZipEntry.STORED,
                        time = entry.time,
                    )
                    expected += entry.name
                }
            }
        }
        verify(disassembly, output, expected)

        return RebuildResult(changedDexes.keys.sorted(), patchedClasses, runtimeHost)
    }

    /**
     * Folds [runtimeDex] into the first candidate dex that will accept it.
     *
     * The candidates come from [KaoriosRuntime.hostSlots]; each is tried in turn because a merge
     * can be rejected when it would push that dex past dex's 65536-entry id limits, in which case
     * the next one is attempted. Nothing is written until one succeeds, so a rejected attempt
     * cannot leave a half-merged dex behind.
     *
     * @return the entry name that ended up holding the runtime.
     */
    private fun mergeRuntime(
        disassembly: Disassembly,
        changedDexes: MutableMap<String, ByteArray>,
        runtimeDex: ByteArray,
    ): String {
        val scratch = File(disassembly.smaliRoot.parentFile, "${disassembly.artifactName}.runtime-merge")
        var failure: Exception? = null
        for (candidate in KaoriosRuntime.hostSlots(disassembly.dexEntries)) {
            scratch.deleteRecursively()
            scratch.mkdirs()
            try {
                val base = changedDexes[candidate] ?: readEntry(disassembly.source, candidate)
                val baseFile = File(scratch, "base.dex").also { it.writeBytes(base) }
                val runtimeFile = File(scratch, "runtime.dex").also { it.writeBytes(runtimeDex) }
                val merged = File(scratch, "merged.dex")

                val original = DexFileFactory.loadDexFile(baseFile, Opcodes.forApi(apiLevel))
                val additions = DexFileFactory.loadDexFile(runtimeFile, Opcodes.forApi(apiLevel)).classes
                DexRoundTrip.writeAugmentedDex(original, additions, merged)

                val bytes = merged.readBytes()
                val written = DexFileFactory.loadDexFile(merged, Opcodes.forApi(apiLevel)).classes
                check(written.any { it.type == KaoriosRuntime.HOOK_DESCRIPTOR }) {
                    "${disassembly.artifactName}!$candidate did not keep ${KaoriosRuntime.HOOK_DESCRIPTOR}"
                }
                changedDexes[candidate] = bytes
                scratch.deleteRecursively()
                return candidate
            } catch (e: Exception) {
                failure = e
            }
        }
        scratch.deleteRecursively()
        throw IllegalStateException(
            "${disassembly.artifactName}: the KaoriOS runtime could not be merged into any of its " +
                "${disassembly.dexEntries.size} dex files. Shipping without it would leave every " +
                "injected call site unresolvable at boot.",
            failure,
        )
    }

    private fun readEntry(source: File, name: String): ByteArray = ZipFile(source).use { zip ->
        val entry = zip.getEntry(name) ?: error("${source.name} lost $name")
        zip.getInputStream(entry).readBytes()
    }

    private data class DeferredSlice(
        val entryName: String,
        val stagedOriginal: File,
        val replacements: Map<String, ClassDef>,
    )

    /**
     * Handles slices whose merge would push their method table past the 65536 invoke-index
     * ceiling. For each deferred slice: strip the patched classes out of the full dex (writing
     * the remaining classes normally), then add the patched classes to a different dex in the
     * same jar that has room. The classloader loads every dex in the jar, so relocating a class
     * between dexes within one archive is transparent at runtime.
     */
    private fun relocateOverfullSlices(
        zip: ZipFile,
        scratch: File,
        disassembly: Disassembly,
        deferred: List<DeferredSlice>,
        changedDexes: MutableMap<String, ByteArray>,
        patchedClasses: MutableList<String>,
    ) {
        if (deferred.isEmpty()) return

        // Candidate host dexes: every slice that was NOT itself deferred, tried in order.
        // Hosts are re-checked per deferred slice because a successful relocation adds methods.
        val nonDeferred = disassembly.slices.filter { s ->
            deferred.none { it.entryName == s.entryName }
        }

        for (d in deferred) {
            val original = DexFileFactory.loadDexFile(d.stagedOriginal, Opcodes.forApi(apiLevel))
            val moved = d.replacements.keys

            // 1. Write the full dex WITHOUT the relocated classes.
            //    Removing classes only shrinks the method table, so this cannot overflow.
            val stripped = File(scratch, "stripped-${d.entryName}")
            val remaining = original.classes
                .filter { it.type !in moved }
            DexPool.writeTo(stripped.absolutePath, object : DexFile {
                override fun getClasses(): Set<ClassDef> = remaining.toSet()
                override fun getOpcodes(): Opcodes = original.opcodes
            })
            changedDexes[d.entryName] = stripped.readBytes()
            patchedClasses += moved.sorted()

            // 2. Pick a host with room and add the relocated classes there.
            var relocated = false
            for (host in nonDeferred) {
                if (host.entryName == d.entryName) continue
                val hostStaged = File(scratch, "host-${host.entryName}")
                if (!hostStaged.isFile) {
                    val he = zip.getEntry(host.entryName) ?: continue
                    hostStaged.writeBytes(zip.getInputStream(he).readBytes())
                }
                val hostFile = try {
                    // If this host was already rewritten (e.g. a prior relocation), load from
                    // the rewritten bytes instead of the original zip entry.
                    val base = changedDexes[host.entryName] ?: hostStaged.readBytes()
                    val tmp = File(scratch, "host2-${host.entryName}")
                    tmp.writeBytes(base)
                    val hostDex = DexFileFactory.loadDexFile(tmp, Opcodes.forApi(apiLevel))

                    val out = File(scratch, "relocated-${d.entryName}-in-${host.entryName}")
                    DexRoundTrip.writeAugmentedDex(hostDex, d.replacements.values.toList(), out)
                    out
                } catch (e: Exception) {
                    // Host rejected the merge (no room / collision); try the next candidate.
                    continue
                }
                changedDexes[host.entryName] = hostFile.readBytes()
                relocated = true
                break
            }

            if (!relocated) {
                throw IllegalStateException(
                    "${disassembly.artifactName}: ${d.entryName} is at the dex method-id " +
                        "ceiling and no other dex in the jar has room to host " +
                        "${moved.joinToString(", ")}. Cannot produce a bootable image.",
                )
            }
        }
    }

    /**
     * Confirms the rebuilt archive is what ART will accept: same entries, all dexes aligned.
     *
     * A missing or duplicated entry would change the dex count the boot oat was built for, and
     * misaligned dex data would make `dex2oat` fall back to copying instead of mapping — both are
     * cheap to check here and expensive to discover at boot.
     */
    private fun verify(disassembly: Disassembly, output: File, expected: Set<String>) {
        val actual = LinkedHashSet<String>()
        ZipFile(output).use { zip ->
            for (entry in zip.entries()) actual += entry.name
        }
        check(actual == expected) {
            "${disassembly.artifactName}: rebuilt archive holds ${actual.size} of ${expected.size} entries"
        }
        val misaligned = AlignedZipWriter.misalignedEntries(output)
        check(misaligned.isEmpty()) {
            "${disassembly.artifactName}: dex data not ${AlignedZipWriter.DEFAULT_ALIGNMENT}-byte aligned: " +
                misaligned.joinToString()
        }
    }
}

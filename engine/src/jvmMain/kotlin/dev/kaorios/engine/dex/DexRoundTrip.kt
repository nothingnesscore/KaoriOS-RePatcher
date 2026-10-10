package dev.kaorios.engine.dex

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File

/**
 * Disassembles and reassembles only the classes the engine actually targets.
 *
 * A full baksmali run over `framework.jar` would emit hundreds of megabytes of text and take
 * minutes on a phone, so the round-trip is deliberately narrow: [disassembleTargets] keeps a
 * class name filter, and [writeMergedDex] swaps the patched classes back into an untouched
 * copy of the original dex rather than rebuilding it from smali.
 */
object DexRoundTrip {

    /**
     * Dex format level to parse and emit.
     *
     * Dex version 041 has been stable since API 30, so parsing newer platform jars at this
     * level is correct rather than merely tolerated.
     */
    const val DEFAULT_API = 34

    /** Maps a type descriptor to the smali file baksmali writes for it. */
    fun smaliFileNameFor(descriptor: String): String =
        descriptor.substringAfterLast('/').removeSuffix(";") + ".smali"

    /** Inverse of [smaliFileNameFor]: `Landroid/os/Build$VERSION;` -> `android/os/Build$VERSION.smali`. */
    fun smaliRelativePathFor(descriptor: String): String =
        descriptor.removePrefix("L").removeSuffix(";") + ".smali"

    /**
     * Disassembles every class in [dexFile] whose smali filename is in [targetFileNames].
     *
     * Returns the descriptors actually written. Emits `.registers` with `p`-style parameters,
     * matching the text layout the engine's patchers are written against; emitting `.locals`
     * instead would silently change the anchors they search for.
     */
    fun disassembleTargets(
        dexFile: DexFile,
        outputDir: File,
        targetFileNames: Set<String>,
        apiLevel: Int = DEFAULT_API,
    ): List<String> {
        val descriptors = dexFile.classes
            .map { it.type }
            .filter { smaliFileNameFor(it) in targetFileNames }
            .sorted()
        if (descriptors.isEmpty()) return emptyList()

        val options = BaksmaliOptions().apply {
            this.apiLevel = apiLevel
            localsDirective = false
            parameterRegisters = true
            debugInfo = true
            sequentialLabels = true
            registerInfo = 0
            // Must stay 0. Any non-zero registerInfo makes baksmali run dex analysis, which
            // dereferences a ClassPath we never build for a filtered dex and NPEs on every
            // class. The engine does not need the `.param` name annotations it would emit;
            // Smali.canonicalizeParamAliases derives parameter aliases positionally.
        }
        outputDir.mkdirs()
        val ok = Baksmali.disassembleDexFile(dexFile, outputDir, apiLevel, options, descriptors)
        check(ok) { "baksmali failed for ${outputDir.name}" }
        normalizeToLf(outputDir)
        return descriptors
    }

    /**
     * Rewrites emitted smali with LF endings.
     *
     * baksmali writes the host separator, which is CRLF on Windows. Every line-anchored pattern
     * in the engine is ported from the Python reference and assumes LF, so a CRLF file defeats
     * them silently — `KeyStoreSpiPatch` reports "leaf-array return not found" on a layout that
     * actually matches. Normalising here keeps the desktop and on-device runs identical, and the
     * assembler is indifferent to line endings.
     *
     * Done on bytes so the text is never re-encoded.
     */
    private fun normalizeToLf(directory: File) {
        directory.walkTopDown().filter { it.isFile && it.name.endsWith(".smali") }.forEach { file ->
            val bytes = file.readBytes()
            var crlf = false
            for (i in 0 until bytes.size - 1) {
                if (bytes[i] == CR && bytes[i + 1] == LF) {
                    crlf = true
                    break
                }
            }
            if (!crlf) return@forEach
            val out = java.io.ByteArrayOutputStream(bytes.size)
            for (i in bytes.indices) {
                if (bytes[i] == CR && i + 1 < bytes.size && bytes[i + 1] == LF) continue
                out.write(bytes[i].toInt())
            }
            file.writeBytes(out.toByteArray())
        }
    }

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A

    /** Assembles [smaliFiles] into a fresh dex at [output]. */
    fun assemble(
        smaliFiles: List<File>,
        output: File,
        apiLevel: Int = DEFAULT_API,
    ): DexFile {
        output.parentFile?.mkdirs()
        val options = SmaliOptions().apply {
            this.apiLevel = apiLevel
            outputDexFile = output.absolutePath
            jobs = 1
            verboseErrors = true
        }
        val ok = Smali.assemble(options, smaliFiles.map { it.absolutePath })
        check(ok) { "smali could not assemble ${smaliFiles.size} file(s) into ${output.name}" }
        return DexFileFactory.loadDexFile(output, Opcodes.forApi(apiLevel))
    }

    /**
     * Writes a copy of [original] with [replacements] substituted in.
     *
     * Classes the patch did not produce are carried over untouched. [DexPool] re-interns and
     * renumbers every reference, so replacement `ClassDef`s are free to carry indices from the
     * small dex they were assembled into.
     *
     * @throws MethodIdOverflow when the merge would push the method table past 65536 entries.
     *   Invoke instructions encode a 16-bit method index, so any method that is *called* must
     *   live below that ceiling; a dex already at the limit cannot absorb new call targets.
     */
    fun writeMergedDex(
        original: DexFile,
        replacements: Map<String, ClassDef>,
        output: File,
    ) {
        output.parentFile?.mkdirs()
        try {
            DexPool.writeTo(output.absolutePath, SubstitutedDexFile(original, replacements))
        } catch (e: Exception) {
            if (isMethodIdOverflow(e)) {
                throw MethodIdOverflow(
                    "merge would exceed the dex method-id ceiling: ${e.message}",
                    e,
                )
            }
            throw e
        }
    }

    /** True when [e] (or its causes) is the dexlib2 16-bit method-index overflow. */
    fun isMethodIdOverflow(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = cur.message ?: ""
            if (msg.contains("Unsigned short value out of range") ||
                msg.contains("method_idx") && msg.contains("out of range")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    /** Raised when a merge would produce a dex ART cannot invoke from. */
    class MethodIdOverflow(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    /**
     * Descriptors the runtime dex is allowed to displace when it moves into a host dex.
     *
     * Anything outside these namespaces colliding with a runtime class means the host is not
     * what the caller thought it was; overwriting a real framework class to make the merge
     * succeed would corrupt the dex silently.
     */
    private val RUNTIME_NAMESPACES = listOf("Landroid/security/kaorios/", "Lcom/kousei/")

    /**
     * Writes a copy of [original] with every class of [additions] present too.
     *
     * Unlike [writeMergedDex] this *adds* classes rather than substituting them, which is how
     * the KaoriOS runtime is folded into an existing dex instead of claiming a slot of its own.
     * A class already defined by [original] is replaced only when it belongs to the runtime's
     * namespaces, so a rerun swaps the runtime in place instead of duplicating it.
     *
     * [DexPool] re-interns and renumbers every id on the way out, so it is also what rejects a
     * merge that would push the host past dex's 65536-entry limits; callers are expected to try
     * the next candidate dex on that failure.
     */
    fun writeAugmentedDex(
        original: DexFile,
        additions: Collection<ClassDef>,
        output: File,
    ) {
        output.parentFile?.mkdirs()
        val originalTypes = original.classes.mapTo(HashSet()) { it.type }
        val additionsByType = LinkedHashMap<String, ClassDef>()
        for (added in additions) {
            if (added.type in originalTypes && RUNTIME_NAMESPACES.none { added.type.startsWith(it) }) {
                throw IllegalStateException(
                    "refusing to displace unrelated class ${added.type} while merging the runtime",
                )
            }
            additionsByType[added.type] = added
        }
        val merged = LinkedHashSet<ClassDef>(original.classes.size + additionsByType.size)
        for (existing in original.classes) merged += additionsByType[existing.type] ?: existing
        for (added in additions) if (added.type !in originalTypes) merged += added
        DexPool.writeTo(output.absolutePath, FixedDexFile(original, merged))
    }

    private class SubstitutedDexFile(
        private val original: DexFile,
        private val replacements: Map<String, ClassDef>,
    ) : DexFile {
        override fun getClasses(): Set<ClassDef> =
            original.classes.mapTo(LinkedHashSet()) { replacements[it.type] ?: it }

        override fun getOpcodes(): Opcodes = original.opcodes
    }

    private class FixedDexFile(
        private val original: DexFile,
        private val classes: Set<ClassDef>,
    ) : DexFile {
        override fun getClasses(): Set<ClassDef> = classes

        override fun getOpcodes(): Opcodes = original.opcodes
    }
}
package dev.kaorios.engine.dex

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import java.io.File

/**
 * Handling for the KaoriOS runtime dex that the patched call sites link against.
 *
 * Every hook injects an `invoke` into `Landroid/security/kaorios/KaoriosHook;`. That class has
 * to sit on the boot classpath, so the reference pipeline (`patch-framework-a17-artifact.sh`)
 * takes it as a required `--kaorios-dex` and merges it into `framework.jar`. Without it the
 * patched framework references a class that does not exist and boot-critical methods such as
 * `ActivityThread.initActivityThread` fail verification, so the check here fails closed rather
 * than shipping a module that would soft-brick the device.
 */
object KaoriosRuntime {

    const val HOOK_DESCRIPTOR = "Landroid/security/kaorios/KaoriosHook;"

    /** Names that make up a multi-dex archive's dex set, in the order ART counts them. */
    val DEX_ENTRY_NAME = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")

    /** Descriptors the reference validates before merging; kept in step with the toolbox. */
    val REQUIRED_DESCRIPTORS = listOf(
        HOOK_DESCRIPTOR,
        "Landroid/security/kaorios/settings/IAdvancedPolicyService;",
        "Landroid/security/kaorios/settings/AdvancedPolicyRuntimeStatus;",
    )

    /**
     * Descriptors [dex] defines.
     *
     * Reading the class list rather than searching the string pool is deliberate: a patched
     * archive *references* the hook without defining it.
     */
    fun descriptorsIn(dex: File, apiLevel: Int = DexRoundTrip.DEFAULT_API): Set<String> =
        runCatching {
            val defined = LinkedHashSet<String>()
            for (classDef in DexFileFactory.loadDexFile(dex, Opcodes.forApi(apiLevel)).classes) {
                defined.add(classDef.type)
            }
            defined
        }.getOrDefault(emptySet())

    /**
     * Checks [dex] can back the hooks.
     *
     * @return the classes it defines that the hooks expect.
     * @throws IllegalStateException when the hook itself is missing.
     */
    fun validate(dex: File, apiLevel: Int = DexRoundTrip.DEFAULT_API): List<String> {
        require(dex.isFile && dex.length() > 0) { "KaoriOS runtime dex not found at ${dex.absolutePath}" }
        val defined = descriptorsIn(dex, apiLevel)
        if (HOOK_DESCRIPTOR !in defined) {
            throw IllegalStateException(
                "${dex.name} does not define $HOOK_DESCRIPTOR. The patched framework calls it, " +
                    "so a module built without it would fail verification at boot.",
            )
        }
        return REQUIRED_DESCRIPTORS.filter { it in defined }
    }

    /**
     * One dex entry of an archive, plus what was learned while it was already open.
     *
     * `DexArchiveRoundTrip.disassemble` reads every dex anyway, so recording the size and the
     * presence of the hook here costs nothing and saves re-opening the archive later.
     */
    data class DexEntry(val name: String, val size: Long, val definesHook: Boolean)

    /**
     * Orders the dexes of an archive by how suitable they are as the runtime's host.
     *
     * **The runtime must never get a slot of its own.** `boot-framework.oat` was built for the
     * stock jar, and `OatFile::Open` refuses it outright when the jar holds a different number of
     * dex files: "expected 6 uncompressed dex files, but found 7". The boot image then cannot be
     * used, framework and `system_server` fall back to the interpreter, and the resulting slow
     * boot trips the MIUI watchdog, which kills zygote and loops. Appending `classes7.dex` is
     * therefore not an option no matter how convenient it looks — this function only ever
     * reorders dexes that are already there.
     *
     * Preference, most to least:
     *  1. the slot that already defines the hook, so reflashing an already-patched ROM replaces
     *     the runtime in place instead of leaving a second copy behind;
     *  2. the smallest dex, which has the most room left under dex's 65536-entry id limits —
     *     `framework.jar` ships a ~10 MB dex that may be close to them, while `classes6.dex` is 3 MB.
     *
     * Callers try the list in order and move on if a merge is rejected for exceeding those limits.
     */
    fun hostSlots(entries: List<DexEntry>): List<String> {
        require(entries.isNotEmpty()) { "archive holds no classes*.dex" }
        val hookSlot = entries.firstOrNull { it.definesHook }
        val rest = entries.filterNot { it == hookSlot }.sortedBy { it.size }.map { it.name }
        return listOfNotNull(hookSlot?.name) + rest
    }
}
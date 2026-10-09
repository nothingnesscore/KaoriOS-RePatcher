package dev.kaorios.engine.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A selection decides what is patched *and* what is disassembled, and the two must agree:
 * a class the patcher will touch has to exist in the tree first, and a class the patcher
 * will never touch must not be pulled in for its own sake.
 *
 * The retired SettingsProvider is the load-bearing case — upstream `8c752fd` removed it
 * from every mode, so no selection may target or even disassemble it.
 */
class PatchSelectionTest {

    /** Every selection, so a new one cannot slip in without being covered here. */
    private val selections = listOf(
        PatchSelection(),
        PatchSelection(hooks = true, buildSpoof = true, corePatch = false, flagSecure = false),
        PatchSelection(corePatch = true),
        PatchSelection(flagSecure = true),
        PatchSelection(hideDevStatus = true),
        PatchSelection(hooks = false, buildSpoof = true),
        PatchSelection.of(PatchMode.HOOKS),
        PatchSelection.of(PatchMode.BUILD_SPOOF),
        PatchSelection.of(PatchMode.ALL_IN_ONE),
        PatchSelection.of(PatchMode.FULL),
        PatchSelection.forAndroid(13),
        PatchSelection.forAndroid(16, corePatch = true, flagSecure = true),
        PatchSelection.forAndroid(17, corePatch = true, flagSecure = true),
        PatchSelection.forAndroid(17, hideDevStatus = true),
    )

    @Test
    fun `hooks select exactly the hook targets and disassemble them`() {
        for (selection in selections.filter { it.hooks }) {
            val targets = PatchEngine.targets(selection)
            val files = PatchEngine.disassemblyFiles(selection)
            for (name in PatchEngine.HOOK_TARGETS.keys) {
                assertContains(targets.keys, name, "selection $selection skips $name")
                assertContains(files, name, "selection $selection does not disassemble $name")
            }
        }
    }

    /** The provider dropped out of mode 1 upstream; nothing may bring it back. */
    @Test
    fun `the retired settings provider is never targeted or disassembled`() {
        for (selection in selections) {
            assertFalse(
                "SettingsProvider.smali" in PatchEngine.targets(selection).keys,
                "selection $selection patches the retired provider",
            )
            assertFalse(
                "SettingsProvider.smali" in PatchEngine.disassemblyFiles(selection),
                "selection $selection disassembles the retired provider",
            )
        }
    }

    @Test
    fun `a build spoof run never asks for the hook classes`() {
        val files = PatchEngine.disassemblyFiles(PatchSelection(hooks = false, buildSpoof = true))
        for (target in PatchEngine.HOOK_TARGETS.keys) {
            assertFalse(target in files, "$target disassembled without hooks selected")
        }
    }

    /** The CLI's mode vocabulary has to survive the trip through the selection type. */
    @Test
    fun `every mode round trips through a selection`() {
        for (mode in PatchMode.entries) {
            val selection = PatchSelection.of(mode)
            assertEquals(mode, selection.toMode(), "$mode came back as ${selection.toMode()}")
        }
    }

    /**
     * The guide's version tables: mode 1 (hooks) covers Android 13-17, while modes 2/3 (Build)
     * are A17-only — upstream's `kaorios_patcher.py` rejects the Build patch on anything older.
     * A selection built for a 13-16 device must therefore never carry it, and the targets it
     * selects must agree: no `Build.smali` to patch means no `Build.smali` disassembled.
     */
    @Test
    fun `the build spoof is gated to Android 17 and up`() {
        for (major in 13..16) {
            val selection = PatchSelection.forAndroid(major, corePatch = true, flagSecure = true)
            assertFalse(selection.buildSpoof, "Android $major was offered the A17-only Build spoof")
            assertTrue(selection.hooks, "Android $major lost the hook set")
            assertFalse(
                "Build.smali" in PatchEngine.targets(selection).keys,
                "Android $major would patch Build.smali",
            )
            assertFalse(
                "Build\$VERSION.smali" in PatchEngine.disassemblyFiles(selection),
                "Android $major would disassemble Build\$VERSION.smali",
            )
        }
        val a17 = PatchSelection.forAndroid(17, corePatch = true, flagSecure = true)
        assertTrue(a17.buildSpoof, "Android 17 must get the Build spoof")
        assertTrue(a17.hooks, "Android 17 must keep the hook set")
        assertContains(PatchEngine.targets(a17).keys, "Build.smali")
    }

    /**
     * Optional patches §1 is opt-in: without the switch neither targeted nor disassembled —
     * no reason to pull `Settings$NameValueCache.smali` into a run that will not touch it —
     * and with it, both, in agreement.
     */
    @Test
    fun `hide developer and ADB status is gated by its own switch`() {
        val file = "Settings\$NameValueCache.smali"

        assertFalse(file in PatchEngine.targets(PatchSelection()).keys)
        assertFalse(file in PatchEngine.disassemblyFiles(PatchSelection()))

        val on = PatchSelection(hideDevStatus = true)
        assertContains(PatchEngine.targets(on).keys, file)
        assertContains(PatchEngine.disassemblyFiles(on), file)
    }

    /** §1 injects a KaoriosHook call site, so it needs the runtime dex even without hooks. */
    @Test
    fun `the hide ADB patch alone still requires a runtime dex`() {
        assertTrue(PatchSelection(hooks = false, hideDevStatus = true).needsRuntime)
        assertFalse(PatchSelection(hooks = false, buildSpoof = true).needsRuntime)
    }

    /** The CLI's mode tables predate §1, so no mode may silently switch the patch on. */
    @Test
    fun `no mode enables the hide ADB patch`() {
        for (mode in PatchMode.entries) {
            assertFalse(
                PatchSelection.of(mode).hideDevStatus,
                "$mode turned on a patch the mode tables do not carry",
            )
        }
    }

    /**
     * App Hide travels with the hook set — `ComputerEngine`'s ForCaller insert plus the
     * `AppsFilterBase` template — and ADB Hide behind its own switch, and neither is version
     * gated: every Android the guide covers (13-17) must select and disassemble all three
     * classes. The ROM family never reaches [PatchSelection] at all, so HOS and AOSP read the
     * same targets; a shape the ROM does not carry degrades to NOT_TARGET inside the patcher,
     * never to a silently dropped selection.
     */
    @Test
    fun `App Hide and ADB Hide are selected on every supported Android`() {
        for (major in 13..17) {
            val selection = PatchSelection.forAndroid(major, hideDevStatus = true)
            val targets = PatchEngine.targets(selection).keys
            val files = PatchEngine.disassemblyFiles(selection)
            for (name in listOf("ComputerEngine.smali", "AppsFilterBase.smali", "Settings\$NameValueCache.smali")) {
                assertContains(targets, name, "Android $major lost the $name target")
                assertContains(files, name, "Android $major does not disassemble $name")
            }
            assertTrue(
                selection.needsRuntime,
                "Android $major injects KaoriosHook call sites without requiring the runtime",
            )
        }
    }

    /** Without the switch the ADB-hide class never enters a run, on any version. */
    @Test
    fun `the ADB-hide class stays out of every selection without its switch`() {
        for (major in 13..17) {
            val selection = PatchSelection.forAndroid(major)
            assertFalse(
                "Settings\$NameValueCache.smali" in PatchEngine.targets(selection).keys,
                "Android $major patches the ADB-hide class without the switch",
            )
            assertFalse(
                "Settings\$NameValueCache.smali" in PatchEngine.disassemblyFiles(selection),
                "Android $major disassembles the ADB-hide class without the switch",
            )
        }
    }
}

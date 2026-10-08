package dev.kaorios.engine

import dev.kaorios.engine.patch.PatchEngine
import dev.kaorios.engine.patch.PatchMode
import dev.kaorios.engine.patch.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Differential test of the Kotlin engine against the Kaorios Python reference.
 *
 * `tools/generate_oracle.py` invokes the reference patchers over the upstream fixtures
 * and records the status plus the exact expected output text. Every case here asserts
 * the Kotlin port produces the same status and, for successful patches, byte-identical
 * output - so the port is checked against the behavioural specification rather than
 * against my own reading of it.
 */
class OracleParityTest {

    private data class Case(
        val name: String,
        val target: String,
        val status: PatchStatus,
        val input: String,
        val expected: String?,
        val error: String?
    )

    private fun loadCases(): List<Case> {
        val url = javaClass.getResource("/oracle") ?: fail("oracle resources missing; run tools/generate_oracle.py")
        val root = java.io.File(url.toURI())
        return root.listFiles()!!.sortedBy { it.name }.map { dir ->
            fun read(name: String) = dir.resolve(name).takeIf { it.isFile }?.readText()
            Case(
                name = dir.name,
                target = read("target.txt")!!.trim(),
                status = PatchStatus.valueOf(read("status.txt")!!.trim()),
                input = read("input.smali")!!,
                expected = read("expected.smali"),
                error = read("error.txt")
            )
        }
    }

    private val cases = loadCases()

    /** All nine patchable files, so Build-spoof fixtures resolve alongside the hook targets. */
    private val allTargets = PatchEngine.targets(PatchMode.ALL_IN_ONE)

    @Test
    fun `oracle is present and covers every core target`() {
        assertTrue(cases.size >= 25, "expected a broad oracle, found ${cases.size} cases")
        val targets = cases.map { it.target }.toSet()
        for (expected in listOf(
            "ActivityThread.smali",
            "ComputerEngine.smali",
            "SystemServer.smali",
            "AndroidKeyStoreKeyPairGeneratorSpi.smali",
            "AndroidKeyStoreSpi.smali",
            "Instrumentation.smali",
            "ApplicationPackageManager.smali",
            "Build.smali",
            "Build\$VERSION.smali"
        )) {
            assertTrue(expected in targets, "oracle missing coverage for $expected")
        }
    }

    @Test
    fun `kotlin engine matches the python reference on every case`() {
        val mismatches = mutableListOf<String>()
        for (case in cases) {
            val outcome = PatchEngine.applyTargetPatch(case.target, case.input, allTargets)
            if (outcome.status != case.status) {
                mismatches += "${case.name}: status ${outcome.status} != ${case.status} (${outcome.error})"
                continue
            }
            when (case.status) {
                PatchStatus.PATCHED -> if (outcome.content != case.expected) {
                    mismatches += "${case.name}: patched output differs from reference"
                }
                PatchStatus.ALREADY_PATCHED -> if (outcome.content != case.input) {
                    mismatches += "${case.name}: already-patched input was modified"
                }
                PatchStatus.UNSUPPORTED_LAYOUT, PatchStatus.FAILED -> {
                    if (outcome.content != case.input) {
                        mismatches += "${case.name}: rejected input was modified"
                    }
                    if (case.error != null && outcome.error == null) {
                        mismatches += "${case.name}: rejection lost its diagnostic"
                    }
                }
                PatchStatus.NOT_TARGET -> mismatches += "${case.name}: fixture reported as NOT_TARGET"
            }
        }
        if (mismatches.isNotEmpty()) {
            fail("engine diverged from the python reference:\n" + mismatches.joinToString("\n"))
        }
    }

    @Test
    fun `no rejected case ever yields modified content`() {
        for (case in cases.filter { it.status == PatchStatus.UNSUPPORTED_LAYOUT || it.status == PatchStatus.FAILED }) {
            val outcome = PatchEngine.applyTargetPatch(case.target, case.input, allTargets)
            assertEquals(case.input, outcome.content, "${case.name} must fail closed")
        }
    }

    @Test
    fun `directory run is unsuccessful when any target is unsupported`() {
        val stock = cases.first { it.name == "stock_system_server" }
        val corrupted = cases.first { it.name == "corrupted_computer_engine" }
        val report = PatchEngine.run(
            PatchMode.HOOKS,
            mapOf(
                stock.target to stock.input,
                corrupted.target to corrupted.input
            )
        )
        assertEquals(false, report.ok, "an unsupported layout must fail the whole run")
        assertEquals(listOf(corrupted.target), report.unsupported)
        assertEquals(listOf(stock.target), report.patched)
    }

    @Test
    fun `directory run succeeds when every target patches or is already patched`() {
        val stock = cases.first { it.name == "stock_computer_engine" }
        val already = cases.first { it.name == "already_patched_system_server" }
        val report = PatchEngine.run(
            PatchMode.HOOKS,
            mapOf(stock.target to stock.input, already.target to already.input)
        )
        assertTrue(report.ok, "expected a clean run, got ${report.outcomes}")
        assertEquals(listOf(stock.target), report.patched)
        assertEquals(listOf(already.target), report.alreadyPatched)
    }

    @Test
    fun `patching is idempotent`() {
        for (case in cases.filter { it.status == PatchStatus.PATCHED }) {
            val first = PatchEngine.applyTargetPatch(case.target, case.input, allTargets)
            assertEquals(PatchStatus.PATCHED, first.status, case.name)
            val second = PatchEngine.applyTargetPatch(case.target, first.content, allTargets)
            assertEquals(PatchStatus.ALREADY_PATCHED, second.status, "${case.name} was not idempotent")
            assertEquals(first.content, second.content, "${case.name} changed on second pass")
        }
    }

    @Test
    fun `mode selection isolates build spoof from hooks`() {
        assertEquals(
            setOf("Build.smali", "Build\$VERSION.smali"),
            PatchEngine.targets(PatchMode.BUILD_SPOOF).keys
        )
        assertEquals(
            PatchEngine.HOOK_TARGETS.size + 2,
            PatchEngine.targets(PatchMode.ALL_IN_ONE).size
        )
        assertTrue("Build.smali" !in PatchEngine.targets(PatchMode.HOOKS).keys)
    }
}
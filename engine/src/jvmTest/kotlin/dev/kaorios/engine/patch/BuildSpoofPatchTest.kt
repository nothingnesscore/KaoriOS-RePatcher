package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Port of upstream `script/test_patcher_register_encoding.py::BuildFieldTest` (2bdca9a):
 * a stock field that already carries an initializer must be rewritten to a single
 * `= null` — the earlier emit appended ` = null` after the stock ` = "OEM"` and produced
 * `= null = "OEM"`, which no assembler accepts.
 */
class BuildSpoofPatchTest {

    private val original = """
        .class public Landroid/os/Build;
        .field public static final BRAND:Ljava/lang/String; = "OEM"
        .field public static final TIME:J = 0x1L
    """.trimIndent()

    @Test
    fun `an initialized field becomes a single null initializer`() {
        // The fixture is deliberately minimal (BRAND + TIME only, like upstream's) — it
        // mirrors patch output, not a full Build.smali, so verify is exercised below.
        val out = BuildSpoofPatch.patchBuild(original)
        assertEquals(PatchStatus.PATCHED, out.status)
        assertContains(out.content, ".field public static  BRAND:Ljava/lang/String; = null")
        assertFalse(out.content.contains("= null ="))
    }

    @Test
    fun `patching the already-patched output is a no-op`() {
        val patched = BuildSpoofPatch.patchBuild(original).content
        val again = BuildSpoofPatch.patchBuild(patched)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status)
        assertEquals(patched, again.content)
    }

    @Test
    fun `a malformed double initializer does not pass as already patched`() {
        // Upstream's regression on its minimal fixture: the second substitution now
        // *repairs* the damaged line, so the patch no longer reports ALREADY_PATCHED —
        // verification is what refuses the result (the minimal fixture never declared
        // DEVICE, and the direct check names the repair the initializer rule requires).
        val patched = BuildSpoofPatch.patchBuild(original).content
        val bad = patched.replace("= null", "= null = \"OEM\"")
        val target = PatchTarget("Build.smali", BuildSpoofPatch::patchBuild, BuildSpoofPatch::verifyBuild)
        val outcome = PatchEngine.applyTargetPatch(target.fileName, bad, mapOf(target.fileName to target))
        assertNotEquals(PatchStatus.ALREADY_PATCHED, outcome.status)
        assertNotNull(outcome.error)
        val e = assertFailsWith<PatchVerificationException> { BuildSpoofPatch.verifyBuild(bad) }
        assertContains(e.message.orEmpty(), "must have a null initializer")
    }

    private fun buildFixture(classLine: String, style: (String) -> String): String = buildString {
        appendLine(classLine)
        listOf("BRAND", "DEVICE", "FINGERPRINT", "HARDWARE", "ID", "MANUFACTURER", "MODEL", "PRODUCT", "TAGS", "TYPE", "USER")
            .forEach { appendLine(".field public static $it:Ljava/lang/String; ${style(it)}") }
        appendLine(".field public static TIME:J")
    }

    @Test
    fun `a matching filename with the wrong class is rejected`() {
        val fixture = buildFixture(".class public Lexample/NotBuild;") { " = \"OEM\"" }
        val target = PatchTarget("Build.smali", BuildSpoofPatch::patchBuild, BuildSpoofPatch::verifyBuild)
        val outcome = PatchEngine.applyTargetPatch(target.fileName, fixture, mapOf(target.fileName to target))
        assertEquals(PatchStatus.UNSUPPORTED_LAYOUT, outcome.status, outcome.error ?: "")
        assertEquals(fixture, outcome.content, "input must stay byte-identical")
        assertContains(outcome.error.orEmpty(), "class descriptor")
    }

    @Test
    fun `a non-final initialized field is not already patched`() {
        val fixture = buildFixture(".class public Landroid/os/Build;") { " = \"OEM\"" }
        val target = PatchTarget("Build.smali", BuildSpoofPatch::patchBuild, BuildSpoofPatch::verifyBuild)
        val outcome = PatchEngine.applyTargetPatch(target.fileName, fixture, mapOf(target.fileName to target))
        assertEquals(PatchStatus.PATCHED, outcome.status, outcome.error ?: "")
        assertContains(outcome.content, ".field public static BRAND:Ljava/lang/String; = null")
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            BuildSpoofPatch.patchBuild(outcome.content).status
        )
        BuildSpoofPatch.verifyBuild(outcome.content) // must not throw
    }
}

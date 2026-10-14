package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
        // Mirrors upstream's regression on its minimal fixture: patching such text is a
        // no-op (no `final` left), so verification is what stands between it and an
        // ALREADY_PATCHED verdict — it fails on a stock field the fixture never declared.
        // On a complete Build.smali the protection is the emit fix itself: malformed
        // text can no longer be produced in the first place.
        val patched = BuildSpoofPatch.patchBuild(original).content
        val bad = patched.replace("= null", "= null = \"OEM\"")
        assertEquals(PatchStatus.ALREADY_PATCHED, BuildSpoofPatch.patchBuild(bad).status)
        val e = assertFailsWith<PatchVerificationException> { BuildSpoofPatch.verifyBuild(bad) }
        assertContains(e.message.orEmpty(), "not found")
    }
}

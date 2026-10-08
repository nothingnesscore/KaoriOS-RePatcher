package dev.kaorios.engine.smali

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The register arithmetic both guide-driven patch sets share.
 *
 * Every case here is a layout taken from the production ROM: `.registers` with `p`-aliased
 * parameters, `.param` debug lines hanging off the directive, and parameter slots that also
 * appear written the old way as `vN`. Getting any of it wrong does not fail loudly — the patch
 * lands, the assembler accepts it, and the method quietly reads a different register at boot.
 */
class MethodRewriteTest {

    private val twoParams = """
        .method public isScreenCaptureAllowed(I)Z
            .registers 5
            .param p1, "userHandle"    # I

            return v1
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `paramCount counts this and every descriptor entry`() {
        assertEquals(2, MethodRewrite.paramCount(".method public isScreenCaptureAllowed(I)Z"))
        assertEquals(1, MethodRewrite.paramCount(".method isSecureLocked()Z"))
        assertEquals(4, MethodRewrite.paramCount(".method public captureDisplay(" +
            "ILandroid/window/ScreenCaptureInternal\$CaptureArgs;" +
            "Landroid/window/ScreenCaptureInternal\$ScreenCaptureListener;)V"))
        assertEquals(
            3,
            MethodRewrite.paramCount(".method public static f([I[[Ljava/lang/String;Landroid/view/View;)V"),
        )
    }

    @Test
    fun `findMethod stops at the end of the method it matched`() {
        val file = """
            .class public Lfoo/Bar;
            .super Ljava/lang/Object;

            .method public a()V
                .registers 1
                return-void
            .end method

            .method public b()V
                .registers 1
                return-void
            .end method
        """.trimIndent() + "\n"

        val found = MethodRewrite.findMethod(file, "a()V", "Bar")
        val body = found.bodyIn(file)

        assertEquals(".method public a()V", found.header)
        assertTrue(body.contains("return-void"), "the matched method lost its body")
        assertFalse(body.contains(".method public b()V"), "the span swallowed the next method")
    }

    @Test
    fun `findMethodOrNull reports an absent method without swallowing ambiguity`() {
        val file = twoParams
        assertNull(MethodRewrite.findMethodOrNull(file, "setSecureLocked(Z)V", "Bar"))
        assertFailsWith<UnsupportedLayoutException> { MethodRewrite.findMethod(file, "setSecureLocked(Z)V", "Bar") }
    }

    @Test
    fun `growing registers frees the first parameter slot and keeps the indentation`() {
        // Parameters written the old way: `v3`/`v4` are `p0`/`p1` under `.registers 5`.
        val method = """
            .method public f(I)I
                .registers 5
                move v3, v4
                return v4
            .end method
        """.trimIndent() + "\n"

        val grown = MethodRewrite.growRegisters(method, ".method public f(I)I", "Bar.f")

        assertEquals("v3", grown.scratch)
        assertEquals(3, grown.scratchNum)
        assertTrue(grown.method.contains("    .registers 6\n"), "register directive was not grown")
        assertTrue(grown.method.contains("    move p0, p1\n"), "raw parameter registers were left behind")
        assertFalse(
            Regex("(?<![A-Za-z0-9_])v4(?![0-9])").containsMatchIn(grown.method),
            "a raw parameter register survived canonicalisation",
        )
    }

    @Test
    fun `a register above fifteen uses the wide const form`() {
        assertEquals("const/4 v5, 0x1", MethodRewrite.constInstruction("v5", 1))
        assertEquals("const v20, 0x0", MethodRewrite.constInstruction("v20", 0))
    }

    @Test
    fun `injecting at the entry places the block between the directive and the param lines`() {
        val out = MethodRewrite.injectAtEntry(twoParams, ".method public isScreenCaptureAllowed(I)Z", "Bar") { v ->
            listOf("nop", "return $v").joinToString("\n") { "    $it" }
        }.first

        assertTrue(
            out.contains(".registers 6\n    nop\n    return v3\n    .param p1"),
            "injection did not land directly under the register directive:\n$out",
        )
    }
}

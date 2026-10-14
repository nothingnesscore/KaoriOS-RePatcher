package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Foreign-shape acceptance for the App Hide visibility hook. A device already patched by
 * the upstream Toolbox app or a manual guide insert carries a hook our exact emitters did
 * not write; re-running must come back ALREADY_PATCHED instead of failing the build
 * closed. Structurally broken inserts must still fail — and quote their own lines, because
 * the tester's single log row is the only channel that can reveal a shape we have never
 * seen (the A15 report failed as `incorrect high-register caller/package/user or return
 * sequence` with no hint of what the device actually held).
 */
class ComputerEngineVisibilityShapeTest {

    private val stock = """
        .class public Lcom/android/server/pm/ComputerEngine;
        .super Ljava/lang/Object;

        .method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
            .registers 10
            const/4 v0, 0x0
            return v0
        .end method
    """.trimIndent()

    /** Guide shape: staged package local, foreign label, no `{p1}` fast path to match. */
    private val foreignStaged = """
        .class public Lcom/android/server/pm/ComputerEngine;
        .super Ljava/lang/Object;

        .method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
            .registers 10
            move-object v0, p1
            if-eqz v0, :vis_stock
            invoke-interface {v0}, Lcom/android/server/pm/pkg/PackageStateInternal;->getPackageName()Ljava/lang/String;
            move-result-object v1
            if-eqz v1, :vis_stock
            invoke-static {p2, v1, p5}, ${ComputerEnginePatch.HOOK_TARGET}
            move-result v1
            if-eqz v1, :vis_stock
            const/4 v1, 0x1
            return v1
            :vis_stock
            const/4 v0, 0x0
            return v0
        .end method
    """.trimIndent()

    @Test
    fun `stock text still fails verification`() {
        assertFailsWith<PatchVerificationException> { ComputerEnginePatch.verify(stock) }
    }

    @Test
    fun `a foreign staged insert is accepted as already patched`() {
        val out = ComputerEnginePatch.patch(foreignStaged)
        assertEquals(PatchStatus.ALREADY_PATCHED, out.status)
        assertEquals(foreignStaged, out.content)
        ComputerEnginePatch.verify(out.content)
    }

    @Test
    fun `a foreign insert missing the return-1 fails and quotes its shape`() {
        val broken = foreignStaged.replace("    const/4 v1, 0x1\n", "")
        val e = assertFailsWith<PatchVerificationException> { ComputerEnginePatch.verify(broken) }
        assertContains(e.message.orEmpty(), "const-1")
        assertContains(e.message.orEmpty(), "shape:")
    }

    @Test
    fun `a two-operand hook call fails`() {
        val broken = foreignStaged.replace("{p2, v1, p5}", "{p2, v1}")
        assertFailsWith<PatchVerificationException> { ComputerEnginePatch.verify(broken) }
    }

    @Test
    fun `a hook without a guarded package name fails`() {
        val broken = foreignStaged.replaceFirst("    if-eqz v1, :vis_stock\n", "")
        assertFailsWith<PatchVerificationException> { ComputerEnginePatch.verify(broken) }
    }

    @Test
    fun `a hook after an unconditional return is rejected as unreachable`() {
        // Port of upstream test_prepatched_hook_after_unconditional_return_is_rejected (2bdca9a):
        // a pre-existing hook below an early return is dead code, never ALREADY_PATCHED.
        val unreachable = foreignStaged.replaceFirst(
            "    move-object v0, p1",
            "    const/4 v9, 0x0\n    return v9\n    move-object v0, p1",
        )
        val e = assertFailsWith<PatchVerificationException> { ComputerEnginePatch.verify(unreachable) }
        assertContains(e.message.orEmpty(), "hook must precede a terminating instruction")
    }
}

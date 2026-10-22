package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Instrumentation hooks must be executable, not comment text — Kotlin port of upstream
 * `script/test_patcher_instrumentation_hooks.py` (Kaorios-Toolbox `9a50d37`).
 *
 * A commented-out hook can neither suppress a patch (idempotency runs through the
 * line-anchored `HOOK_INVOKE`) nor satisfy the verifier's counts, and every `return-object`
 * must still be directly dominated by the initContext call feeding it the Context register.
 */
class InstrumentationHookTest {

    private fun instrumentation(
        firstBody: String = "    const/4 v0, 0x0\n    return-object v0\n",
        secondBody: String = "    const/4 v0, 0x0\n    return-object v0\n",
    ): String = buildString {
        append(".class public Landroid/app/Instrumentation;\n")
        append(".super Ljava/lang/Object;\n")
        append(".method public newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;\n")
        append("    .locals 2\n")
        append(firstBody)
        append(".end method\n")
        append(".method public newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;\n")
        append("    .locals 2\n")
        append(secondBody)
        append(".end method\n")
    }

    @Test
    fun commentOnlyHookIsNotAlreadyPatched() {
        val original = instrumentation(
            firstBody = "    # invoke-static {p2}, " +
                "Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V\n" +
                "    const/4 v0, 0x0\n" +
                "    return-object v0\n",
        )
        val outcome = InstrumentationPatch.patch(original)
        assertEquals(PatchStatus.PATCHED, outcome.status, outcome.error ?: "")
        InstrumentationPatch.verify(outcome.content)
        assertEquals(
            2,
            InstrumentationPatch.HOOK_INVOKE.findAll(outcome.content).count(),
            "both methods must carry an executable hook",
        )
    }

    @Test
    fun commentOnlyHookCannotSatisfyVerifier() {
        val patched = InstrumentationPatch.patch(instrumentation())
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")
        val realHook =
            "    invoke-static {p2}, Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V"
        assertContains(patched.content, realHook)
        val commented = patched.content.replaceFirst(realHook, "    # " + realHook.trim())
        val e = assertFailsWith<PatchVerificationException> {
            InstrumentationPatch.verify(commented)
        }
        assertContains(e.message.orEmpty(), "initContext hooks")
    }

    @Test
    fun missingHookBeforeOneReturnFailsVerification() {
        val patched = InstrumentationPatch.patch(instrumentation())
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")
        val hook =
            "    invoke-static {p3}, Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V"
        assertContains(patched.content, hook)
        val marker = ".method public newApplication(Ljava/lang/ClassLoader;"
        val splitAt = patched.content.indexOf(marker)
        assertTrue(splitAt >= 0, "second method must exist")
        val head = patched.content.substring(0, splitAt)
        var tail = patched.content.substring(splitAt)
        val hooked = "$hook\n\n    return-object v0"
        assertTrue(tail.contains(hooked), "fixture must carry the hook-above-return shape")
        // Move the hook from directly above the return to the method entry: still one
        // hook and one return, but no longer in dominance of each other.
        tail = tail.replaceFirst(hooked, "    return-object v0")
        tail = tail.replaceFirst("    .locals 2\n", "    .locals 2\n$hook\n")
        val e = assertFailsWith<PatchVerificationException> {
            InstrumentationPatch.verify(head + tail)
        }
        assertContains(e.message.orEmpty(), "directly dominate")
    }
}

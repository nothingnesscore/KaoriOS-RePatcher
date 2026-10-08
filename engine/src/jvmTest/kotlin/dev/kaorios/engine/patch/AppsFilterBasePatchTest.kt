package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.UnsupportedLayoutException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Conformance for the optional root-template insert: `Template_V2060/service/AppsFilterBase.smali`
 * has no Python patcher upstream (the guide's mode 1 never touches this class), so these
 * fixtures - not a differential oracle - pin the behaviour. The stock fixture keeps this ROM's
 * real header (`.registers 17`, the five `.param` lines) because recognising that layout is what
 * makes a re-run come back `ALREADY_PATCHED` instead of being rejected.
 */
class AppsFilterBasePatchTest {

    private val stock = """
        .class public abstract Lcom/android/server/pm/AppsFilterBase;
        .super Ljava/lang/Object;

        .method public shouldFilterApplication(Lcom/android/server/pm/snapshot/PackageDataSnapshot;ILjava/lang/Object;Lcom/android/server/pm/pkg/PackageStateInternal;I)Z
            .registers 17
            .param p1, "snapshot"    # Lcom/android/server/pm/snapshot/PackageDataSnapshot;
            .param p2, "callingUid"    # I
            .param p3, "callingSetting"    # Ljava/lang/Object;
            .param p4, "targetPkgSetting"    # Lcom/android/server/pm/pkg/PackageStateInternal;
            .param p5, "userId"    # I

            move/from16 v6, p5
            sget-boolean v0, Lcom/android/server/pm/AppsFilterBase;->DEBUG_TRACING:Z
            if-eqz v0, :cond_f
            const-string v0, "shouldFilterApplication"
            invoke-static {p2}, Landroid/os/UserHandle;->getAppId(I)I
            move-result v0
            return v0
            :cond_f
            const/4 v0, 0x1
            return v0
        .end method
    """.trimIndent()

    private val insert = listOf(
        "move-object v0, p4",
        "if-eqz v0, ${AppsFilterBasePatch.LABEL_STOCK}",
        AppsFilterBasePatch.TRY_START,
        AppsFilterBasePatch.GET_PACKAGE_NAME_CALL,
        "move-result-object v1",
        "if-eqz v1, ${AppsFilterBasePatch.LABEL_STOCK}",
        "const/4 v2, 0x0",
        "invoke-static {v2, v1}, ${AppsFilterBasePatch.HOOK_TARGET}",
        "move-result v2",
        "if-eqz v2, ${AppsFilterBasePatch.LABEL_STOCK}",
        "const/4 v2, 0x1",
        "return v2",
        AppsFilterBasePatch.TRY_END,
        ".catch Ljava/lang/Throwable; {${AppsFilterBasePatch.TRY_START} .. " +
            "${AppsFilterBasePatch.TRY_END}} ${AppsFilterBasePatch.CATCH}",
        AppsFilterBasePatch.CATCH,
        AppsFilterBasePatch.LABEL_STOCK,
    ).joinToString("\n") { "    $it" } + "\n"

    private fun patchOk(input: String = stock): String {
        val out = AppsFilterBasePatch.patch(input)
        assertEquals(PatchStatus.PATCHED, out.status)
        AppsFilterBasePatch.verify(out.content)
        return out.content
    }

    @Test
    fun `patch inserts the template shape after the headers`() {
        val patched = patchOk()
        assertContains(patched, insert)
        val lastParam = patched.indexOf(".param p5")
        val paramEnd = patched.indexOf('\n', lastParam) + 1
        assertEquals(paramEnd, patched.indexOf("    move-object v0, p4"), "insert is not after headers")
        assertContains(patched, "    move/from16 v6, p5")
        assertContains(patched, "const-string v0, \"shouldFilterApplication\"")
    }

    @Test
    fun `patch is idempotent`() {
        val once = patchOk()
        val second = AppsFilterBasePatch.patch(once)
        assertEquals(PatchStatus.ALREADY_PATCHED, second.status)
        assertEquals(once, second.content)
        AppsFilterBasePatch.verify(second.content)
    }

    @Test
    fun `a method the ROM does not carry is NOT_TARGET and byte-identical`() {
        val other = """
            .class public abstract Lcom/android/server/pm/AppsFilterBase;
            .super Ljava/lang/Object;

            .method public shouldFilterApplicationUsingCache(III)Z
                .registers 4
                const/4 v0, 0x1
                return v0
            .end method
        """.trimIndent()
        val out = AppsFilterBasePatch.patch(other)
        assertEquals(PatchStatus.NOT_TARGET, out.status)
        assertEquals(other, out.content)
    }

    @Test
    fun `a pre-existing reserved label is rejected`() {
        val colliding = stock.replace(
            "    move/from16 v6, p5",
            "    ${AppsFilterBasePatch.LABEL_STOCK}\n    move/from16 v6, p5"
        )
        assertFailsWith<UnsupportedLayoutException> { AppsFilterBasePatch.patch(colliding) }
    }

    @Test
    fun `verify rejects stock text`() {
        assertFailsWith<PatchVerificationException> { AppsFilterBasePatch.verify(stock) }
    }

    @Test
    fun `verify rejects a duplicated hook`() {
        val patched = patchOk()
        val dup = patched.replace(
            "    invoke-static {v2, v1}, ${AppsFilterBasePatch.HOOK_TARGET}",
            "    invoke-static {v2, v1}, ${AppsFilterBasePatch.HOOK_TARGET}\n" +
                "    move-result v2\n" +
                "    invoke-static {v2, v1}, ${AppsFilterBasePatch.HOOK_TARGET}"
        )
        assertFailsWith<PatchVerificationException> { AppsFilterBasePatch.verify(dup) }
    }

    @Test
    fun `verify rejects a missing catch-all`() {
        val patched = patchOk()
        val noCatch = patched.lines()
            .filterNot { it.trim().startsWith(".catch Ljava/lang/Throwable") }
            .joinToString("\n")
        assertFailsWith<PatchVerificationException> { AppsFilterBasePatch.verify(noCatch) }
    }

    @Test
    fun `verify rejects an insert that is not at the method head`() {
        val patched = patchOk()
        val insertPos = patched.indexOf("    move-object v0, p4")
        val stockPos = patched.indexOf("    move/from16 v6, p5")
        val stockLineEnd = patched.indexOf('\n', stockPos) + 1
        val headers = patched.substring(0, insertPos)
        val insertBlock = patched.substring(insertPos, stockPos)
        val stockFirst = patched.substring(stockPos, stockLineEnd)
        val stockRest = patched.substring(stockLineEnd)
        val rebuilt = headers + stockFirst + insertBlock + stockRest
        assertFailsWith<PatchVerificationException> { AppsFilterBasePatch.verify(rebuilt) }
    }

    @Test
    fun `locals directive form with a high p4 uses move-object from16`() {
        val localsForm = """
            .class public abstract Lcom/android/server/pm/AppsFilterBase;
            .super Ljava/lang/Object;

            .method public shouldFilterApplication(Lcom/android/server/pm/snapshot/PackageDataSnapshot;ILjava/lang/Object;Lcom/android/server/pm/pkg/PackageStateInternal;I)Z
                .locals 13

                move-object/from16 v6, p5
                const/4 v0, 0x1
                return v0
            .end method
        """.trimIndent()
        val out = AppsFilterBasePatch.patch(localsForm)
        assertEquals(PatchStatus.PATCHED, out.status)
        assertContains(out.content, "    move-object/from16 v0, p4\n")
        assertContains(out.content, insert.replace("    move-object v0, p4\n", "    move-object/from16 v0, p4\n"))
        AppsFilterBasePatch.verify(out.content)
    }

    @Test
    fun `too few locals is an unsupported layout`() {
        val cramped = """
            .class public abstract Lcom/android/server/pm/AppsFilterBase;
            .super Ljava/lang/Object;

            .method public shouldFilterApplication(Lcom/android/server/pm/snapshot/PackageDataSnapshot;ILjava/lang/Object;Lcom/android/server/pm/pkg/PackageStateInternal;I)Z
                .registers 8

                const/4 v0, 0x1
                return v0
            .end method
        """.trimIndent()
        assertFailsWith<UnsupportedLayoutException> { AppsFilterBasePatch.patch(cramped) }
    }
}

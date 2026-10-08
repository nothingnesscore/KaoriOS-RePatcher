package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.UnsupportedLayoutException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Conformance for the one guide patch with no Python counterpart: `Optional patches` §1 of
 * `Patch_Guide_2.0.6.1.md` is ported from the guide text and its linked example, so these
 * fixtures — not a differential oracle — are what pins the behaviour.
 *
 * The example half is derived from `Template_V2060/a17/framework/Settings$NameValueCache.smali`,
 * the file the guide links as "Example smali (Android 17)": its header, register count, `.param`
 * block and injected sequence are kept verbatim (label `:cond_kaorios_dev_stock`, blank line
 * before the label) with the stock body abbreviated, because recognising that exact layout is
 * what makes an already-patched ROM come back `ALREADY_PATCHED` instead of being rejected.
 */
class NameValueCachePatchTest {

    /** The guide's block, exactly as §1 writes it, label included, indented for a body. */
    private val guideBlock = listOf(
        "if-eqz p2, :kaorios_dev_stock",
        "invoke-static/range {p1 .. p3}, " +
            "Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatusFromNameValueCache" +
            "(Landroid/content/ContentResolver;Ljava/lang/String;I)Z",
        "move-result v0",
        "if-eqz v0, :kaorios_dev_stock",
        "const-string v0, \"0\"",
        "return-object v0",
        ":kaorios_dev_stock",
    ).joinToString("\n") { "    $it" }

    private val stock = """
        .class public greylist-max-o Landroid/provider/Settings${'$'}NameValueCache;
        .super Ljava/lang/Object;

        .method public greylist-max-o getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;
            .registers 6
            .param p1, "cr"    # Landroid/content/ContentResolver;
            .param p2, "name"    # Ljava/lang/String;
            .param p3, "userId"    # I

            .line 3841
            move-object v1, p0
            const/4 v0, 0x0
            return-object v0
        .end method
    """.trimIndent()

    /** The linked example's method, verbatim through the label, stock body abbreviated. */
    private val templatePatched = """
        .class public greylist-max-o Landroid/provider/Settings${'$'}NameValueCache;
        .super Ljava/lang/Object;

        .method public greylist-max-o getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;
            .registers 25
            .param p1, "cr"    # Landroid/content/ContentResolver;
            .param p2, "name"    # Ljava/lang/String;
            .param p3, "userId"    # I

            .line 3841
            if-eqz p2, :cond_kaorios_dev_stock
            invoke-static/range {p1 .. p3}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatusFromNameValueCache(Landroid/content/ContentResolver;Ljava/lang/String;I)Z
            move-result v0
            if-eqz v0, :cond_kaorios_dev_stock
            const-string v0, "0"
            return-object v0

            :cond_kaorios_dev_stock
            move-object/from16 v1, p0
            move-object/from16 v6, p2
            invoke-static {}, Landroid/os/UserHandle;->myUserId()I
            move-result v0
            return-object v0
        .end method
    """.trimIndent()

    @Test
    fun `patches the stock method with the guide's block`() {
        val result = NameValueCachePatch.patch(stock)

        assertEquals(PatchStatus.PATCHED, result.status)
        // The guide says "insert after .registers": the block lands directly beneath it, above
        // the `.param` headers and the stock body.
        assertContains(result.content, ".registers 6\n$guideBlock")
        assertContains(result.content, ".param p1, \"cr\"")
        assertContains(result.content, "move-object v1, p0")
    }

    @Test
    fun `a second pass recognises its own patch`() {
        val first = NameValueCachePatch.patch(stock)
        assertEquals(PatchStatus.PATCHED, first.status)

        val second = NameValueCachePatch.patch(first.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, second.status)
        assertEquals(first.content, second.content)
    }

    @Test
    fun `recognises the guide template's layout as already patched`() {
        val result = NameValueCachePatch.patch(templatePatched)

        assertEquals(PatchStatus.ALREADY_PATCHED, result.status)
        assertEquals(templatePatched, result.content)
    }

    @Test
    fun `a ROM without the method reports NOT_TARGET and changes nothing`() {
        val noMethod = stock.replace(
            "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;",
            "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/util/List;)Ljava/util/Map;",
        )

        // Through the engine, not the patcher directly: NOT_TARGET is the one status whose
        // verifier is skipped, and this pins that a missing method never reaches one.
        val outcome = PatchEngine.applyTargetPatch(
            "Settings\$NameValueCache.smali",
            noMethod,
            PatchEngine.targets(PatchSelection(hideDevStatus = true)),
        )
        assertEquals(PatchStatus.NOT_TARGET, outcome.status)
        assertEquals(noMethod, outcome.content)
    }

    @Test
    fun `a method with no local register is rejected`() {
        val noLocal = stock.replace(".registers 6", ".registers 4")
        assertFailsWith<UnsupportedLayoutException> { NameValueCachePatch.patch(noLocal) }
    }

    @Test
    fun `a file for another class is rejected`() {
        val otherClass = stock.replace("Settings${'$'}NameValueCache", "Settings${'$'}System")
        assertFailsWith<UnsupportedLayoutException> { NameValueCachePatch.patch(otherClass) }
    }

    @Test
    fun `verifier rejects a hook below stock logic`() {
        val belowStock = stock.replace(".end method", "$guideBlock\n\n.end method")
        assertFailsWith<PatchVerificationException> { NameValueCachePatch.verify(belowStock) }
    }
}

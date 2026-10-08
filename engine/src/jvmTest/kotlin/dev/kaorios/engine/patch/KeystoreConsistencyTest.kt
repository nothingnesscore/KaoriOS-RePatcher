package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.Smali
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Mirrors `script/test_keystore_consistency.py` (upstream v2.0.6.1): the leaf must delegate
 * to the chained `engineGetCertificateChain`, a historical build that discarded the hook's
 * result must be repaired rather than rejected, and a leaf without two locals must fail
 * closed.
 */
class KeystoreConsistencyTest {

    private val stock = """
        .class public Landroid/security/keystore2/AndroidKeyStoreSpi;
        .super Ljava/security/KeyStoreSpi;
        .method public engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;
            .locals 2
            const/4 v0, 0x0
            return-object v0
        .end method
        .method public engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
            .locals 3
            const/4 v0, 0x1
            new-array v1, v0, [Ljava/security/cert/Certificate;
            const/4 v0, 0x0
            const/4 v2, 0x0
            aput-object v2, v1, v0
            return-object v1
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `leaf delegates and retains the stock body`() {
        val patched = KeyStoreSpiPatch.patch(stock)
        assertEquals(PatchStatus.PATCHED, patched.status)
        assertContains(patched.content, ":kaorios_certificate_stock\n\n    const/4 v0, 0x0")
        KeyStoreSpiPatch.verify(patched.content)

        val again = KeyStoreSpiPatch.patch(patched.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status)
        assertEquals(patched.content, again.content, "second pass must be byte-identical")
    }

    @Test
    fun `old discarded chain result is repaired`() {
        val result = KeyStoreSpiPatch.patch(stock).content
        val old = result.replaceFirst(
            "move-result-object v1",
            "move-result-object v2\n    .line 215",
        )
        assertFailsWith<PatchVerificationException> { KeyStoreSpiPatch.verify(old) }

        val repaired = KeyStoreSpiPatch.patch(old)
        assertEquals(PatchStatus.PATCHED, repaired.status, "the repair must count as a change")
        KeyStoreSpiPatch.verify(repaired.content)
    }

    @Test
    fun `insufficient locals fail closed`() {
        val error = assertFailsWith<IllegalStateException> {
            KeyStoreSpiPatch.patch(stock.replaceFirst(".locals 2", ".locals 1"))
        }
        assertContains(error.message!!, "two existing local")
    }

    /** `_extract_method_body` must anchor on the declaration, not the leaf's invoke of it. */
    @Test
    fun `method lookup ignores invoke references`() {
        val result = KeyStoreSpiPatch.patch(stock).content
        val span = Smali.methodSpanByAnchor(
            result,
            "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
            "SPI",
        )
        val body = result.substring(span.start, span.endExclusive)
        assertTrue(body.startsWith(".method"), body)
        assertContains(body, "aput-object")
    }

    @Test
    fun `leaf dataflow tampering is rejected`() {
        val result = KeyStoreSpiPatch.patch(stock).content
        val tampered = result.replace("aget-object v0, v0, v1", "aget-object v0, v0, v0")
        assertFailsWith<PatchVerificationException> { KeyStoreSpiPatch.verify(tampered) }
    }

    @Test
    fun `roundtrip labels and debug lines`() {
        var result = KeyStoreSpiPatch.patch(stock).content
        result = result.replace(":kaorios_certificate_stock", ":cond_d")
        result = result.replace("    :cond_d\n", "    .line 220\n    :cond_d\n")
        KeyStoreSpiPatch.verify(result)

        val again = KeyStoreSpiPatch.patch(result)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status)
        assertEquals(result, again.content)
    }

    @Test
    fun `register directive retained`() {
        val patched = KeyStoreSpiPatch.patch(stock.replaceFirst(".locals 2", ".registers 4"))
        assertContains(patched.content, ".registers 4")
        KeyStoreSpiPatch.verify(patched.content)
    }
}

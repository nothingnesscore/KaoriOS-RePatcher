package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.Smali
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * TrickyStore's `hackCertificateChainIfNeeded` sits between the leaf `aput-object`
 * and the `move-result` of the provider invoke, so the stock pairing regexes cannot
 * see the return's producer: the Jasur A17 report came back `UNSUPPORTED_LAYOUT`
 * for exactly this shape. The backward scan now follows the `move-result` back to
 * the provider invoke's input register and pairs the `aput` that fed it; the
 * verifier mirrors the same span rule (a span never crosses another `aput-object`
 * or `return-object`, so the loop's `aput` cannot re-pair into the same hook).
 */
class TrickyStoreKeystoreTest {

    private val fixture: String = javaClass
        .getResource("/fixtures/trickystore_androidkeystorespi.smali")!!
        .readText()

    private fun chainSpan(content: String) = Smali.methodSpanByAnchor(
        content,
        "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
        "AndroidKeyStoreSpi",
    )

    @Test
    fun `trickystore chain with provider hook between aput and return patches and verifies`() {
        val patched = KeyStoreSpiPatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")

        val span = chainSpan(patched.content)
        val chain = patched.content.substring(span.start, span.endExclusive)
        assertEquals(
            1,
            Regex(Regex.escape(KeyStoreSpiPatch.HOOK_SIGNATURE)).findAll(chain).count(),
            "exactly the populated-array return must be hooked",
        )
        assertEquals(
            3,
            Regex("\\breturn-object\\s+").findAll(chain).count(),
            "all stock returns stay",
        )
        assertContains(
            chain, "hackCertificateChainIfNeeded",
            message = "the TrickyStore provider hook is untouched",
        )
        KeyStoreSpiPatch.verify(patched.content)

        val again = KeyStoreSpiPatch.patch(patched.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status, again.error ?: "")
        assertEquals(patched.content, again.content, "second pass must be byte-identical")
    }

    @Test
    fun `the leaf still delegates to the chain`() {
        val patched = KeyStoreSpiPatch.patch(fixture)
        val span = Smali.methodSpanByAnchor(
            patched.content,
            "engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;",
            "AndroidKeyStoreSpi",
        )
        val leaf = patched.content.substring(span.start, span.endExclusive)
        assertContains(leaf, "engineGetCertificateChain")
        assertContains(leaf, ":kaorios_certificate_stock")
    }

    @Test
    fun `breaking the hook's dataflow tie fails verification`() {
        val patched = KeyStoreSpiPatch.patch(fixture).content
        // The pair regex ties the invoke input, the move-result and the return together;
        // feeding the hook from a different register breaks that tie and the pair no
        // longer exists, so the hook count and the pair count disagree.
        val tampered = patched.replaceFirst(
            "invoke-static {p1}, Landroid/security/kaorios/KaoriosHook;",
            "invoke-static {p2}, Landroid/security/kaorios/KaoriosHook;",
        )
        val error = assertFailsWith<PatchVerificationException> { KeyStoreSpiPatch.verify(tampered) }
        assertContains(error.message.orEmpty(), "dataflow mismatch")
    }
}

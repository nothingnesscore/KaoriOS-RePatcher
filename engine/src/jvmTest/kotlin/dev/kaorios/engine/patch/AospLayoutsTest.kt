package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AOSP Android 15 shapes that the reference patchers (HyperOS-tuned) refuse:
 *
 * - `AndroidKeyStoreSpi.engineGetCertificateChain` carries four return-objects against a
 *   single aput-adjacent populated-array return (the extra bare return is the catch path).
 * - `ComputerEngine.getInstallSourceInfo` rewrites the installer name to a literal on one
 *   path (aurora store → Play Store), merging a `const-string` with the installer
 *   provenance at the join.
 * - `ReconcilePackageUtils.<clinit>` has no `Flags.restrictNonpreloadsSystemShareduids` guard
 *   at all: it reads `Build.IS_DEBUGGABLE` straight into
 *   `ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS`, so the guide's anchor is missing and the value
 *   feeding the field write is what has to be forced.
 *
 * The fixtures are verbatim slices of a real Android 15 disassembly. Both must patch and
 * verify, and the fail-closed negatives must still throw.
 */
class AospLayoutsTest {

    private val keystore: String = javaClass
        .getResource("/fixtures/aosp15_androidkeystorespi.smali")!!
        .readText()

    private val computerEngine: String = javaClass
        .getResource("/fixtures/aosp15_computer_engine.smali")!!
        .readText()

    private val reconcile: String = javaClass
        .getResource("/fixtures/aosp15_reconcile_package_utils.smali")!!
        .readText()

    private fun chainSpan(content: String) = Smali.methodSpanByAnchor(
        content,
        "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
        "AndroidKeyStoreSpi",
    )

    @Test
    fun `aosp15 chain with four returns patches and verifies`() {
        val patched = KeyStoreSpiPatch.patch(keystore)
        assertEquals(PatchStatus.PATCHED, patched.status)

        val span = chainSpan(patched.content)
        val chain = patched.content.substring(span.start, span.endExclusive)
        assertEquals(
            1,
            Regex(Regex.escape(KeyStoreSpiPatch.HOOK_SIGNATURE)).findAll(chain).count(),
            "exactly the populated-array return must be hooked",
        )
        assertEquals(
            4,
            Regex("\\breturn-object\\s+").findAll(chain).count(),
            "the three null returns must be left alone",
        )
        KeyStoreSpiPatch.verify(patched.content)

        val again = KeyStoreSpiPatch.patch(patched.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status)
        assertEquals(patched.content, again.content, "second pass must be byte-identical")
    }

    @Test
    fun `aosp15 chain with unprovable bare returns fails closed`() {
        val span = chainSpan(keystore)
        val chain = keystore.substring(span.start, span.endExclusive)
        // The null const now initializes a register no bare return uses, so the extra
        // returns are no longer provably null.
        val broken = chain.replaceFirst("const/4 v0, 0x0", "const/4 v6, 0x0")
        val fixture = keystore.substring(0, span.start) + broken + keystore.substring(span.endExclusive)

        val error = assertFailsWith<UnsupportedLayoutException> { KeyStoreSpiPatch.patch(fixture) }
        assertContains(error.message!!, "unsupported return layout")
    }

    @Test
    fun `aosp15 installer source with literal rewrite patches and verifies`() {
        val patched = InstallerSourcePatch.patch(computerEngine)
        assertEquals(PatchStatus.PATCHED, patched.status)

        assertEquals(
            2,
            Regex(Regex.escape(InstallerSourcePatch.HOOK)).findAll(patched.content).count(),
            "both installer read APIs must be hooked",
        )
        assertContains(patched.content, "com.android.vending")
        InstallerSourcePatch.verify(patched.content)

        val again = InstallerSourcePatch.patch(patched.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status)
        assertEquals(patched.content, again.content, "second pass must be byte-identical")
    }

    @Test
    fun `installer slot without installer provenance still fails closed`() {
        // Rename the first installer-field read (inside getInstallSourceInfo) so no path
        // to the constructor argument is installer-proven.
        val broken = computerEngine.replaceFirst("mInstallerPackageName", "mUpdateOwnerPackageName")
        val error = assertFailsWith<UnsupportedLayoutException> { InstallerSourcePatch.patch(broken) }
        assertContains(error.message!!, "installing argument is not stock installer")
    }

    @Test
    fun `aosp15 clinit without the guard forces the value feeding the field write`() {
        val patched = CorePatchPatch.patchReconcilePackageUtils(reconcile)
        assertEquals(PatchStatus.PATCHED, patched.status)

        val clinit = patched.content.substringBefore(".method public static isCompatSignatureUpdateNeeded")
        assertContains(clinit, "const/4 v0, 0x1")
        assertFalse(
            "IS_DEBUGGABLE" in clinit,
            "the stock read feeding ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS must be gone",
        )
        CorePatchPatch.verifyReconcilePackageUtils(patched.content)

        assertTrue(patched.content.contains("const/4 v0, 0x2"), "the rewrite escaped <clinit>")
        assertTrue(patched.content.contains("const/4 p0, 0x0"), "the rewrite escaped <clinit>")

        val again = CorePatchPatch.patchReconcilePackageUtils(patched.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status)
        assertEquals(patched.content, again.content, "second pass must be byte-identical")
    }

    @Test
    fun `aosp15 stock clinit fails verification rather than passing on it`() {
        assertFailsWith<PatchVerificationException> { CorePatchPatch.verifyReconcilePackageUtils(reconcile) }
    }

    @Test
    fun `aosp15 clinit feed the guide does not cover fails closed`() {
        // A literal other than 0/1 sitting in the feed is a shape neither the guide nor this
        // port has seen — it must be refused with the file byte-identical.
        val unknown = reconcile.replaceFirst(
            "sget-boolean v0, Landroid/os/Build;->IS_DEBUGGABLE:Z",
            "const/4 v0, 0x2",
        )
        val error = assertFailsWith<UnsupportedLayoutException> {
            CorePatchPatch.patchReconcilePackageUtils(unknown)
        }
        assertContains(error.message!!, "does not cover")
    }

    @Test
    fun `aosp15 clinit feed whose register the constructor still reads fails closed`() {
        // Forcing the definition is only safe while it feeds the field write and nothing else.
        val readElsewhere = reconcile.replaceFirst(
            "    return-void\n",
            "    if-nez v0, :cond_9\n\n    return-void\n",
        )
        val error = assertFailsWith<UnsupportedLayoutException> {
            CorePatchPatch.patchReconcilePackageUtils(readElsewhere)
        }
        assertContains(error.message!!, "also reads v0")
    }

    // ── Android 16 shapes ─────────────────────────────────────────────────────

    private val a16Keystore: String = javaClass
        .getResource("/fixtures/a16_androidkeystorespi.smali")!!
        .readText()

    @Test
    fun `a16 chain with label between aput and return patches and verifies`() {
        // baksmali on AOSP 16 can emit `:cond_…` between the final aput and its return.
        val span = chainSpan(a16Keystore)
        val chain = a16Keystore.substring(span.start, span.endExclusive)
        val labelled = chain.replaceFirst(
            "    aput-object v2, v3, v4\n\n    .line 212\n    return-object v3",
            "    aput-object v2, v3, v4\n    :cond_50\n\n    .line 212\n    return-object v3",
        )
        val fixture = a16Keystore.substring(0, span.start) + labelled + a16Keystore.substring(span.endExclusive)

        val patched = KeyStoreSpiPatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status)
        val s = chainSpan(patched.content)
        val patchedChain = patched.content.substring(s.start, s.endExclusive)
        assertEquals(
            1,
            Regex(Regex.escape(KeyStoreSpiPatch.HOOK_SIGNATURE)).findAll(patchedChain).count(),
        )
        KeyStoreSpiPatch.verify(patched.content)
    }

    @Test
    fun `a16 chain with move-object between aput and return patches and verifies`() {
        // Some AOSP 16 builds route the final array through a move-object before returning.
        val span = chainSpan(a16Keystore)
        val chain = a16Keystore.substring(span.start, span.endExclusive)
        val moved = chain.replaceFirst(
            "    aput-object v2, v3, v4\n\n    .line 212\n    return-object v3",
            "    aput-object v2, v3, v4\n    move-object v5, v3\n\n    .line 212\n    return-object v5",
        )
        val fixture = a16Keystore.substring(0, span.start) + moved + a16Keystore.substring(span.endExclusive)

        val patched = KeyStoreSpiPatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status)
        val s = chainSpan(patched.content)
        val patchedChain = patched.content.substring(s.start, s.endExclusive)
        assertEquals(
            1,
            Regex(Regex.escape(KeyStoreSpiPatch.HOOK_SIGNATURE)).findAll(patchedChain).count(),
        )
        // The hook must write back to the moved register that the method returns.
        assertContains(patchedChain, "move-result-object v5")
        assertContains(patchedChain, "return-object v5")
        KeyStoreSpiPatch.verify(patched.content)
    }

    @Test
    fun `a16 chain with arbitrary instruction between aput and return patches via lenient scan`() {
        // R8/D8 on some A16 builds inserts instructions the exact regexes cannot express.
        // The lenient line scan must find the aput by scanning backward from the return.
        val span = chainSpan(a16Keystore)
        val chain = a16Keystore.substring(span.start, span.endExclusive)
        val withInstruction = chain.replaceFirst(
            "    aput-object v2, v3, v4\n\n    .line 212\n    return-object v3",
            "    aput-object v2, v3, v4\n    const/4 v6, 0x1\n\n    .line 212\n    return-object v3",
        )
        val fixture = a16Keystore.substring(0, span.start) + withInstruction + a16Keystore.substring(span.endExclusive)

        val patched = KeyStoreSpiPatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status)
        val s = chainSpan(patched.content)
        val patchedChain = patched.content.substring(s.start, s.endExclusive)
        assertEquals(
            1,
            Regex(Regex.escape(KeyStoreSpiPatch.HOOK_SIGNATURE)).findAll(patchedChain).count(),
        )
        KeyStoreSpiPatch.verify(patched.content)
    }

    @Test
    fun `a16 chain with a wide debug-heavy gap patches via the lenient scan`() {
        // baksmali debug directives run ~3 lines per instruction, so a loop-exit tail can
        // push the final aput past a small backward window (the shape HOS A16 reported).
        // The strict patterns refuse the real instructions in the gap, so only the widened
        // lenient window can pair the aput with its return.
        val span = chainSpan(a16Keystore)
        val chain = a16Keystore.substring(span.start, span.endExclusive)
        val gap = buildString { repeat(20) { append("    const/4 v6, 0x1\n\n    .line 99\n") } }
        val wide = chain.replaceFirst(
            "    aput-object v2, v3, v4\n\n    .line 212\n    return-object v3",
            "    aput-object v2, v3, v4\n$gap\n    .line 212\n    return-object v3",
        )
        val fixture = a16Keystore.substring(0, span.start) + wide + a16Keystore.substring(span.endExclusive)

        val patched = KeyStoreSpiPatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status)
        val s = chainSpan(patched.content)
        val patchedChain = patched.content.substring(s.start, s.endExclusive)
        assertEquals(
            1,
            Regex(Regex.escape(KeyStoreSpiPatch.HOOK_SIGNATURE)).findAll(patchedChain).count(),
        )
        assertContains(patchedChain, "move-result-object v3")
        KeyStoreSpiPatch.verify(patched.content)

        val again = KeyStoreSpiPatch.patch(patched.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status)
        assertEquals(patched.content, again.content, "second pass must be byte-identical")
    }

    @Test
    fun `unmatched chain reports the neighbourhood feeding each return`() {
        // A ROM whose middle return does not pair with the built array must fail closed
        // *and* carry enough context in the message to see what its returns look like —
        // the count alone cost a blind round trip on HOS V816.
        val span = chainSpan(a16Keystore)
        val chain = a16Keystore.substring(span.start, span.endExclusive)
        val broken = chain.replaceFirst("    return-object v3\n", "    return-object p1\n")
        val fixture = a16Keystore.substring(0, span.start) + broken + a16Keystore.substring(span.endExclusive)

        val error = assertFailsWith<UnsupportedLayoutException> { KeyStoreSpiPatch.patch(fixture) }
        val message = error.message!!
        assertContains(message, "aput=2(v3,v3)")
        assertContains(message, "ret=3(v1,p1,v1)")
        // The neighbourhood markers: what fed the unpairable middle return, and what the
        // array writes are followed by.
        assertContains(message, "ret#1@")
        assertContains(message, "<-")
        assertContains(message, "aput-object v2, v3, v4")
        assertContains(message, "aput#1@")
    }

    @Test
    fun `a16 generator with registers 16 reuses v14 without growing the frame`() {
        // The A16 template parks the hook result in v14 of a `.registers 16` frame.
        // Growing to 17 would push p0 to v16 and trip the register-encoding gate.
        val body = """
            .method public whitelist test-api generateKeyPair()Ljava/security/KeyPair;
                .registers 16
                invoke-direct {p0}, Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;->ensureKeystoreProvider()V

                move-result-object p0
                return-object p0
            .end method
        """.trimIndent()
        val patched = KeyStoreGeneratorPatch.patch(body)
        assertEquals(PatchStatus.PATCHED, patched.status)
        assertTrue(patched.content.contains(".registers 16"), "frame must not grow")
        assertContains(patched.content, "move-result-object v14")
        KeyStoreGeneratorPatch.verify(patched.content)
    }

    @Test
    fun `a16 generator with locals 1 reuses v0 without growing the frame`() {
        val body = """
            .method public whitelist test-api generateKeyPair()Ljava/security/KeyPair;
                .locals 1
                invoke-direct {p0}, Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;->ensureKeystoreProvider()V

                move-result-object v0
                return-object v0
            .end method
        """.trimIndent()
        val patched = KeyStoreGeneratorPatch.patch(body)
        assertEquals(PatchStatus.PATCHED, patched.status)
        assertTrue(patched.content.contains(".locals 1"), "frame must not grow")
        assertContains(patched.content, "move-result-object v0")
        KeyStoreGeneratorPatch.verify(patched.content)
    }

    @Test
    fun `a16 generator with zero locals grows by one`() {
        val body = """
            .method public whitelist test-api generateKeyPair()Ljava/security/KeyPair;
                .registers 1
                invoke-direct {p0}, Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;->ensureKeystoreProvider()V

                move-result-object p0
                return-object p0
            .end method
        """.trimIndent()
        val patched = KeyStoreGeneratorPatch.patch(body)
        assertEquals(PatchStatus.PATCHED, patched.status)
        assertTrue(patched.content.contains(".registers 2"), "zero-local frame must grow by one")
        // The scratch is v(newCount-2) = v0: v1 is p0 after the growth, so parking the
        // hook result there would clobber the very register the hook is invoked with.
        assertContains(patched.content, "move-result-object v0")
        KeyStoreGeneratorPatch.verify(patched.content)
    }
}

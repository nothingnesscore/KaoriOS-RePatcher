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
}

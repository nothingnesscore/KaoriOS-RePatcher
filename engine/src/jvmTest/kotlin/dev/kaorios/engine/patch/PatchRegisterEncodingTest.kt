package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Register-encoding safety: growing a directive must never leave stock instructions
 * addressing operands their opcode cannot encode, and canonicalisation must never touch
 * text that is not a register.
 *
 * Kotlin port of upstream `script/test_patcher_register_encoding.py` (Kaorios-Toolbox
 * `aab122b`): the reference refuses such a target with `UNSUPPORTED_LAYOUT` and the input
 * unchanged rather than emitting smali that fails at reassembly — which is exactly what
 * the Android 16 tester's run did before this gate existed.
 */
class PatchRegisterEncodingTest {

    private fun generator(body: String, registers: Int = 16): String = buildString {
        append(".class public Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;\n")
        append(".super Ljava/security/KeyPairGeneratorSpi;\n")
        append(".method public generateKeyPair()Ljava/security/KeyPair;\n")
        append("    .registers $registers\n")
        append(body)
        append("    const/4 v0, 0x0\n")
        append("    return-object v0\n")
        append(".end method\n")
    }

    private fun applyKeystore(fixture: String): PatchOutcome {
        val target = PatchTarget(
            "AndroidKeyStoreKeyPairGeneratorSpi.smali",
            KeyStoreGeneratorPatch::patch,
            KeyStoreGeneratorPatch::verify
        )
        return PatchEngine.applyTargetPatch(
            target.fileName, fixture, mapOf(target.fileName to target)
        )
    }

    @Test
    fun narrowParameterOperandAfterGrowthIsRefusedWithOriginalInput() {
        val bodies = listOf(
            "    invoke-virtual {p0}, Ljava/lang/Object;->toString()Ljava/lang/String;\n",
            "    invoke-virtual {v15}, Ljava/lang/Object;->toString()Ljava/lang/String;\n",
            "    iget-object v0, p0, Ljava/lang/Object;->field:Ljava/lang/Object;\n",
        )
        for (body in bodies) {
            val fixture = generator(body)
            val outcome = applyKeystore(fixture)
            assertEquals(PatchStatus.UNSUPPORTED_LAYOUT, outcome.status, "body: $body")
            assertEquals(fixture, outcome.content, "input must stay byte-identical: $body")
            assertContains(outcome.error ?: "", "encoding limit v15")
        }
    }

    @Test
    fun rangeInvokeAndWideMovesRemainEncodable() {
        val fixture = generator(
            "    move-object/from16 v1, p0\n" +
                "    invoke-virtual/range {p0 .. p0}, Ljava/lang/Object;->toString()Ljava/lang/String;\n"
        )
        val outcome = applyKeystore(fixture)
        assertEquals(PatchStatus.PATCHED, outcome.status, outcome.error ?: "")
        KeyStoreGeneratorPatch.verify(outcome.content)
        Smali.verifyRegisterEncoding(outcome.content, "AndroidKeyStoreKeyPairGeneratorSpi.smali")
    }

    @Test
    fun literalsAndCommentsSurviveCanonicalization() {
        val out = Smali.canonicalizeParamAliases(
            "    const-string v0, \"v15 # p0\"\n" +
                "    # v15 must remain in this comment\n",
            16, 1
        )
        assertContains(out, "\"v15 # p0\"")
        assertContains(out, "# v15 must remain in this comment")
    }

    @Test
    fun descriptorsAndFieldNamesSurviveCanonicalization() {
        val out = Smali.canonicalizeParamAliases(
            "    const-class v0, Lorg/v15;\n" +
                "    iget v0, v1, Lorg/Holder;->v15:I\n",
            16, 1
        )
        assertContains(out, "Lorg/v15;")
        assertContains(out, "Lorg/Holder;->v15:I")
    }

    @Test
    fun hasSystemFeatureShiftedStockParameterIsRefusedByTheGate() {
        val fixture = buildString {
            append(".class public Landroid/app/ApplicationPackageManager;\n")
            append(".super Ljava/lang/Object;\n")
            append(".method public hasSystemFeature(Ljava/lang/String;I)Z\n")
            append("    .locals 16\n")
            append("    invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\n")
            append("    const/4 v0, 0x0\n")
            append("    return v0\n")
            append(".end method\n")
        }
        val error = assertFailsWith<UnsupportedLayoutException> {
            Smali.verifyRegisterEncoding(fixture, "ApplicationPackageManager.smali")
        }
        assertContains(error.message ?: "", "encoding limit v15")
    }

    @Test
    fun prepatchedInvalidEncodingCannotPassTheGate() {
        val safe = generator(
            "    invoke-virtual/range {p0 .. p0}, Ljava/lang/Object;->toString()Ljava/lang/String;\n"
        )
        Smali.verifyRegisterEncoding(safe, "ok") // the safe form assembles

        val broken = safe
            .replace(".registers 16", ".registers 17") // the grown directive shifts p0 onto v16
            .replace(
                "invoke-virtual/range {p0 .. p0}",
                "invoke-virtual {p0}"
            )
        val error = assertFailsWith<UnsupportedLayoutException> {
            Smali.verifyRegisterEncoding(broken, "broken")
        }
        assertContains(error.message ?: "", "encoding limit v15")
    }

    private fun computerEngine(body: String, directive: String, full: Boolean = true): String {
        val signature = if (full) {
            "shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;IL" +
                "android/content/ComponentName;IIZZ)Z"
        } else {
            "shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;II)Z"
        }
        return buildString {
            append(".class public Lcom/android/server/pm/ComputerEngine;\n")
            append(".super Ljava/lang/Object;\n")
            append(".method public $signature\n")
            append("    $directive\n")
            append(body)
            append("    const/4 v0, 0x0\n")
            append("    return v0\n")
            append(".end method\n")
        }
    }

    @Test
    fun highPathPreservesDescriptorParameterNames() {
        val fixture = computerEngine(
            "    const-class v0, Lorg/p7;\n" +
                "    iget v0, v1, Lorg/Holder;->p7:I\n",
            ".locals 8"
        )
        val outcome = ComputerEnginePatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, outcome.status, outcome.error ?: "")
        assertContains(outcome.content, "Lorg/p7;")
        assertContains(outcome.content, "Lorg/Holder;->p7:I")
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            ComputerEnginePatch.patch(outcome.content).status,
            "re-patching must be idempotent"
        )
    }

    @Test
    fun highPathRewritesNarrowStockParameterOperandsIntoTheirHomes() {
        val fixture = computerEngine(
            "    invoke-static {p7}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;\n",
            ".locals 8"
        )
        val outcome = ComputerEnginePatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, outcome.status, outcome.error ?: "")
        assertContains(outcome.content, "move/16 v15, p7")
        assertContains(outcome.content, "invoke-static {v15}, Ljava/lang/Boolean;->valueOf")
        Smali.verifyRegisterEncoding(outcome.content, "ComputerEngine.smali")
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            ComputerEnginePatch.patch(outcome.content).status
        )
    }

    @Test
    fun lowPathKeepsLiteralsCommentsAndDescriptors() {
        for (directive in listOf(".locals 2", ".registers 6")) {
            val fixture = computerEngine(
                "    const-string v0, \"v3 # p1\"\n" +
                    "    # v3 p1 untouched\n" +
                    "    const-class v0, Lorg/v3;\n" +
                    "    iget v0, v1, Lorg/Holder;->v3:I\n",
                directive, full = false
            )
            val outcome = ComputerEnginePatch.patch(fixture)
            assertEquals(PatchStatus.PATCHED, outcome.status, "$directive: ${outcome.error}")
            assertContains(outcome.content, "\"v3 # p1\"")
            assertContains(outcome.content, "# v3 p1 untouched")
            assertContains(outcome.content, "Lorg/v3;")
            assertContains(outcome.content, "Lorg/Holder;->v3:I")
            Smali.verifyRegisterEncoding(outcome.content, "ComputerEngine.smali")
        }
    }

    @Test
    fun lowPathCanonicalizesPhysicalParameterAliases() {
        val fixture = computerEngine(
            "    invoke-static {v4}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\n",
            ".locals 2", full = false
        )
        val outcome = ComputerEnginePatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, outcome.status, outcome.error ?: "")
        assertContains(outcome.content, "invoke-static {p2}, Ljava/lang/Integer;->valueOf")
        Smali.verifyRegisterEncoding(outcome.content, "ComputerEngine.smali")
    }

    @Test
    fun shiftedApplicationPackageManagerTargetIsRefusedThroughApplyTargetPatch() {
        val fixture = buildString {
            append(".class public Landroid/app/ApplicationPackageManager;\n")
            append(".super Ljava/lang/Object;\n")
            append(".method public hasSystemFeature(Ljava/lang/String;I)Z\n")
            append("    .locals 13\n")
            append("    invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;\n")
            append("    const/4 v0, 0x0\n")
            append("    return v0\n")
            append(".end method\n")
        }
        val target = PatchTarget(
            "ApplicationPackageManager.smali",
            PackageManagerPatch::patch,
            PackageManagerPatch::verify
        )
        val outcome = PatchEngine.applyTargetPatch(
            target.fileName, fixture, mapOf(target.fileName to target)
        )
        assertEquals(PatchStatus.UNSUPPORTED_LAYOUT, outcome.status, outcome.error ?: "")
        assertEquals(fixture, outcome.content, "input must stay byte-identical")
        assertContains(outcome.error ?: "", "encoding limit v15")
    }
}

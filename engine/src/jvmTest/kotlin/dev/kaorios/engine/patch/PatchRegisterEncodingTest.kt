package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun narrowParameterOperandSurvivesBecauseTheGeneratorDoesNotGrow() {
        // The A16 tester's failure: the reference grows `.registers 16` to 17, which
        // pushes p0 to v16; a stock narrow `invoke-virtual {p0}` cannot encode v16, so
        // the gate refuses and no module is produced. The engine's no-growth path reuses
        // the highest local instead, so these bodies now patch and stay encodable.
        val bodies = listOf(
            "    invoke-virtual {p0}, Ljava/lang/Object;->toString()Ljava/lang/String;\n",
            "    invoke-virtual {v15}, Ljava/lang/Object;->toString()Ljava/lang/String;\n",
            "    iget-object v0, p0, Ljava/lang/Object;->field:Ljava/lang/Object;\n",
        )
        for (body in bodies) {
            val fixture = generator(body)
            val outcome = applyKeystore(fixture)
            assertEquals(PatchStatus.PATCHED, outcome.status, "body: $body ${outcome.error}")
            assertTrue(outcome.content.contains(".registers 16"), "frame must not grow: $body")
            Smali.verifyRegisterEncoding(outcome.content, "AndroidKeyStoreKeyPairGeneratorSpi.smali")
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

    @Test
    fun crossingLocalParameterRangeIsRefusedByCanonicalization() {
        for (end in listOf("v3", "p0")) {
            val body = "    invoke-static/range {v2 .. $end}, Ljava/lang/Object;->wait(J)V\n"
            val error = assertFailsWith<UnsupportedLayoutException> {
                Smali.canonicalizeParamAliases(body, 4, 1)
            }
            assertContains(error.message ?: "", "crosses")
        }
        // Upstream refuses this body through its canonicalize-with-bump path; the engine's
        // no-growth generator never shifts the frame, so the same layout still patches.
        val stillPatches = applyKeystore(
            generator("    invoke-static/range {v2 .. v3}, Ljava/lang/Object;->wait(J)V\n", registers = 4)
        )
        assertEquals(PatchStatus.PATCHED, stillPatches.status, stillPatches.error ?: "")
    }

    @Test
    fun shiftedLit16OperandIsRefusedByTheGate() {
        // Upstream's case runs the grown frame (16 → 17 pushes p0 onto v16); the no-growth
        // generator never creates that shift, so the gate is pinned directly on a frame
        // where the stock operand already addresses past v15.
        val fixture = generator("    add-int/lit16 v0, p0, 0x1\n", registers = 17)
        val error = assertFailsWith<UnsupportedLayoutException> {
            Smali.verifyRegisterEncoding(fixture, "AndroidKeyStoreKeyPairGeneratorSpi.smali")
        }
        assertContains(error.message ?: "", "encoding limit v15")
    }

    @Test
    fun branchLabelNamedLikeRegisterIsNotAnOperand() {
        val fixture = generator("    if-eq v0, v1, :v255\n    :v255\n")
        val outcome = applyKeystore(fixture)
        assertEquals(PatchStatus.PATCHED, outcome.status, outcome.error ?: "")
    }

    @Test
    fun preexistingGeneratorHookMustBeAtEntryWithStockFallback() {
        val original = generator(
            "    invoke-virtual/range {p0 .. p0}, Ljava/lang/Object;->toString()Ljava/lang/String;\n"
        )
        val patched = KeyStoreGeneratorPatch.patch(original)
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")
        // The no-growth frame keeps p0 at v15, so the hook is the narrow form and its
        // scratch is the top local v14 (upstream's grown frame pairs range + v15).
        val invoke = "invoke-static {p0}"
        val cases = listOf(
            patched.content.replaceFirst(
                "    $invoke", "    return-object v0\n    $invoke"
            ),
            patched.content.replaceFirst(
                "    if-eqz v14, :cond_kaorios_gen_stock",
                "    if-nez v14, :cond_kaorios_gen_stock"
            ),
            patched.content.replaceFirst(
                "    :cond_kaorios_gen_stock", "    :missing_stock"
            ),
        )
        for ((n, bad) in cases.withIndex()) {
            assertNotEquals(patched.content, bad, "case $n must mutate the patch")
            val outcome = applyKeystore(bad)
            assertNotEquals(PatchStatus.PATCHED, outcome.status, "case $n")
            assertNotEquals(PatchStatus.ALREADY_PATCHED, outcome.status, "case $n")
            assertEquals(bad, outcome.content, "case $n must fail closed")
            assertNotNull(outcome.error, "case $n must carry a diagnostic")
        }
    }

    @Test
    fun generatorEntryLabelFailsClosed() {
        val fixture = generator("    :stock_entry\n    goto :stock_entry\n")
        val outcome = applyKeystore(fixture)
        assertEquals(PatchStatus.UNSUPPORTED_LAYOUT, outcome.status, outcome.error ?: "")
        assertEquals(fixture, outcome.content, "input must stay byte-identical")
        assertContains(outcome.error ?: "", "entry label")
    }

    @Test
    fun generatorCommentIsNotAStockFallbackExit() {
        val fixture = generator("")
        val patched = KeyStoreGeneratorPatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")
        val bad = patched.content.replace(
            "    return-object v0\n.end method",
            "    # return-object v0\n.end method"
        )
        assertNotEquals(patched.content, bad, "the stock exit must actually be commented out")
        val outcome = applyKeystore(bad)
        assertEquals(PatchStatus.UNSUPPORTED_LAYOUT, outcome.status, outcome.error ?: "")
        assertEquals(bad, outcome.content, "input must stay byte-identical")
        assertContains(outcome.error ?: "", "fallback")
    }

    @Test
    fun generatorInjectionFollowsParameterMetadata() {
        val fixture = generator(
            "    .param p0, \"this\"    # Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;\n" +
                "        .annotation runtime Ljava/lang/Deprecated;\n" +
                "        .end annotation\n" +
                "    .end param\n" +
                "    .line 42\n"
        )
        val patched = KeyStoreGeneratorPatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")
        assertTrue(
            patched.content.indexOf("    .end param") <
                patched.content.indexOf(KeyStoreGeneratorPatch.HOOK_SIGNATURE),
            "the hook must be injected after the parameter metadata, not between it and the directive"
        )
        val again = KeyStoreGeneratorPatch.patch(patched.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, again.status, again.error ?: "")
        assertEquals(patched.content, again.content, "second pass must be byte-identical")
        KeyStoreGeneratorPatch.verify(patched.content)
    }

    @Test
    fun debugDirectiveBetweenHookReturnAndStockFallbackIsAccepted() {
        val original = generator(
            "    invoke-virtual/range {p0 .. p0}, Ljava/lang/Object;->toString()Ljava/lang/String;\n"
        )
        val patched = KeyStoreGeneratorPatch.patch(original)
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")
        // Port of upstream test_debug_directive_between_hook_return_and_stock_fallback_is_accepted
        // (9a50d37): baksmali floats `.line` above the stock label on re-disassembly. The
        // no-growth frame parks the hook's scratch at v14 (v15 is p0), where the reference's
        // grown frame pairs range invoke + v15.
        val withDebug = patched.content.replaceFirst(
            "    return-object v14\n\n    :cond_kaorios_gen_stock",
            "    return-object v14\n    .line 783\n\n    :cond_kaorios_gen_stock",
        )
        assertNotEquals(patched.content, withDebug, "the fixture must actually gain the directive")
        KeyStoreGeneratorPatch.verify(withDebug)
        val outcome = applyKeystore(withDebug)
        assertEquals(PatchStatus.ALREADY_PATCHED, outcome.status, outcome.error ?: "")
        assertEquals(withDebug, outcome.content, "an accepted input must stay byte-identical")
    }

    @Test
    fun prepatchedHookBypassedByGotoIsRejected() {
        // Port of upstream ServicesParameterEncodingTest.test_prepatched_hook_bypassed_by_goto_is_rejected
        // (9a50d37): a `goto` ahead of the hook routes every caller past the null checks.
        val fixture = computerEngine("", ".locals 8", full = false)
        val patched = ComputerEnginePatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")
        val label = ComputerEnginePatch.RESERVED_LABEL
        val bypassed = patched.content.replaceFirst(
            "    if-eqz p1, $label",
            "    goto $label\n    if-eqz p1, $label",
        )
        assertNotEquals(patched.content, bypassed, "the fixture must actually gain the branch")
        val e = assertFailsWith<PatchVerificationException> { ComputerEnginePatch.verify(bypassed) }
        assertContains(e.message.orEmpty(), "bypassed by a branch")
    }

    @Test
    fun prepatchedHookBypassedByConditionalBranchIsRejected() {
        // The conditional twin: `if-nez` skips the hook for one caller shape and still
        // leaves two `if-eqz` guards in place, so only the branch identity check catches it.
        val fixture = computerEngine("", ".locals 8", full = false)
        val patched = ComputerEnginePatch.patch(fixture)
        assertEquals(PatchStatus.PATCHED, patched.status, patched.error ?: "")
        val label = ComputerEnginePatch.RESERVED_LABEL
        val bypassed = patched.content.replaceFirst(
            "    if-eqz p1, $label",
            "    if-nez p2, $label\n    if-eqz p1, $label",
        )
        assertNotEquals(patched.content, bypassed, "the fixture must actually gain the branch")
        val e = assertFailsWith<PatchVerificationException> { ComputerEnginePatch.verify(bypassed) }
        assertContains(e.message.orEmpty(), "bypassed by a branch")
    }

    @Test
    fun commentOnlyVisibilityHookIsNotAlreadyPatched() {
        // Port of upstream ServicesParameterEncodingTest.test_comment_only_hook_is_not_already_patched
        // (9a50d37): comment text suppresses neither the patch nor the verifier.
        val fixture = computerEngine("", ".locals 8", full = false)
        val commented = fixture.replaceFirst(
            "    const/4 v0, 0x0\n",
            "    # invoke-static {p2, v1, p3}, ${ComputerEnginePatch.HOOK_TARGET}\n    const/4 v0, 0x0\n",
        )
        assertNotEquals(fixture, commented, "the fixture must actually gain the comment")
        val outcome = ComputerEnginePatch.patch(commented)
        assertEquals(PatchStatus.PATCHED, outcome.status, outcome.error ?: "")
        ComputerEnginePatch.verify(outcome.content)
    }
}

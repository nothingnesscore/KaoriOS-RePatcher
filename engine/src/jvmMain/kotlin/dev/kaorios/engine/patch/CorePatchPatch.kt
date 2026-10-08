package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.MethodRewrite
import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * `Toolbox-docs/V2.0.3+/CorePatch.md` — the static signature, downgrade and shared-user bypass.
 *
 * §1 rewrites `framework.jar`, §2 `services.jar`. Both are pure smali: nothing here calls
 * `KaoriosHook`, so a CorePatch target never depends on the runtime dex and a `FULL` run can
 * rebuild it without one.
 *
 * The guide quotes registers from its own reference ROM and says outright that `v14`, `v12` and
 * `v0` are examples, so every register is resolved from the anchor it guards — the third argument
 * of the `invoke` it forces, the operand of the branch it redirects, `p0` of the method it
 * short-circuits — and [constFor] chooses `const/4` versus `const` from the register's *physical*
 * index, which `const/4`'s four-bit register field cannot exceed.
 *
 * `Template_V2060`'s smali is a mixed bag: `PackageParser`, `PackageParserException`,
 * `ApkSignatureSchemeV2Verifier`, `ApkSigningBlockUtils`, `ApkSignatureVerifier`, `StrictJarFile`
 * and `ReconcilePackageUtils` arrive patched, while `StrictJarVerifier`,
 * `ApkSignatureSchemeV3Verifier`, `ParsingPackageUtils` and `InstallPackageHelper` are stock
 * references. Where the two disagree the guide text wins. Two divergences are worth naming:
 * `PackageParserException`'s template insert lands *after* the `iput` it is meant to overwrite,
 * which is a dead store — the guide says "insert above" — and `InstallPackageHelper`'s template
 * carries no forced branch at all.
 *
 * Absence of a named method is [UnsupportedLayoutException], not [PatchStatus.NOT_TARGET]: §1 and
 * §2 are unconditional in the guide, and a silent skip would ship a module that looks patched and
 * is not.
 */
object CorePatchPatch {

    private const val INDENT = "    "

    private val CERTS_DESCRIPTOR =
        "Landroid/util/apk/ApkSignatureVerifier;->unsafeGetCertsWithoutVerification("
    private val V1_DESCRIPTOR = "Landroid/util/apk/ApkSignatureVerifier;->verifyV1Signature("
    private val FIND_ENTRY = Regex(
        """invoke-virtual\s+\{[^}]*\},\s*Landroid/util/jar/StrictJarFile;->findEntry\("""
    )
    private val BAD_SHARED_USER_ID = Regex(
        """const-string\s+\S+,\s*"<manifest> specifies bad sharedUserId name"""
    )
    private val IS_EQUAL = Regex(
        """invoke-static\s+\{[^}]*\},\s*Ljava/security/MessageDigest;->isEqual\(\[B\[B\)Z"""
    )
    private val LEAVING_SHARED_USER = Regex(
        """invoke-interface\s+\{[^}]*\},\s*Lcom/android/server/pm/pkg/AndroidPackage;->isLeavingSharedUser\(\)Z"""
    )
    private val ERROR_FIELD =
        "iput p1, p0, Landroid/content/pm/PackageParser\$PackageParserException;->error:I"

    private val MOVE_RESULT = Regex("""^move-result\s+([vp]\d+)$""")
    private val MOVE_RESULT_OBJECT = Regex("""^move-result-object\s+([vp]\d+)$""")
    private val TEST_BRANCH = Regex("""^(?:if-eqz|if-nez)\s+([vp]\d+),\s*(:\w+)$""")
    private val CONST =
        Regex("""^(?:const/4|const/16|const/high16|const)\s+([vp]\d+),\s*(0[xX][0-9a-fA-F]+)$""")
    private val ZERO_CONST = Regex("""^const/4\s+v\d+,\s*0x0$""")
    private val DIRECTIVE = Regex("""^\.(registers|locals)\s+(\d+)$""")
    private val REGISTER_LIST = Regex("""\{([^}]*)\}""")
    private val SINGLE_REGISTER = Regex("""^[vp]\d+$""")

    // ---- §1 framework.jar ----------------------------------------------------

    /** `unsafeGetCertsWithoutVerification` gets version 1, and the bad sharedUserId name passes. */
    fun patchPackageParser(content: String): PatchOutcome {
        val label = "PackageParser"
        val doc = Doc(content)
        var changed = false

        val certs = doc.exactlyOne(label, doc.hits { CERTS_DESCRIPTOR in it }, "unsafeGetCertsWithoutVerification")
        val version = doc.registerArg(certs, 2)
            ?: throw UnsupportedLayoutException("$label: unsafeGetCertsWithoutVerification has no register list")
        changed = doc.constBefore(certs, version, 1, doc.localsAt(certs, label), label) || changed

        val badName = doc.exactlyOne(label, doc.hits { BAD_SHARED_USER_ID.containsMatchIn(it) }, "bad sharedUserId message")
        changed = doc.forceBranchTaken(branchBefore(doc, badName, label), label) || changed

        return outcome(changed, content, doc)
    }

    /** Both constructors store `error`, so both are forced to `0`. */
    fun patchPackageParserException(content: String): PatchOutcome {
        val label = "PackageParserException"
        val doc = Doc(content)
        val sites = doc.hits { it == ERROR_FIELD }
        if (sites.isEmpty()) throw UnsupportedLayoutException("$label: $ERROR_FIELD not found")
        var changed = false
        for (at in sites.asReversed()) {
            changed = doc.constBefore(at, "p1", 0, doc.localsAt(at, label), label) || changed
        }
        return outcome(changed, content, doc)
    }

    /** The three `checkCapability` methods answer `true`. */
    fun patchPackageParserSigningDetails(content: String): PatchOutcome {
        val label = "PackageParser\$SigningDetails"
        val doc = Doc(content)
        val changed = forceCheckCapability(doc, label)
        return outcome(changed, content, doc)
    }

    /** The three `checkCapability` methods plus `hasAncestorOrSelf` answer `true`. */
    fun patchSigningDetails(content: String): PatchOutcome {
        val label = "SigningDetails"
        val doc = Doc(content)
        var changed = forceCheckCapability(doc, label)
        val at = doc.methodStart(label, "hasAncestorOrSelf", "Z")
        changed = forceEntryReturn(doc, doc.scope(at, label), 1, "$label.hasAncestorOrSelf") || changed
        return outcome(changed, content, doc)
    }

    /** `MessageDigest.isEqual`'s verdict is replaced by `const vN, 0x1`. */
    fun patchApkSignatureSchemeV2Verifier(content: String): PatchOutcome =
        forceDigestMatch(content, "ApkSignatureSchemeV2Verifier")

    fun patchApkSignatureSchemeV3Verifier(content: String): PatchOutcome =
        forceDigestMatch(content, "ApkSignatureSchemeV3Verifier")

    fun patchApkSigningBlockUtils(content: String): PatchOutcome =
        forceDigestMatch(content, "ApkSigningBlockUtils")

    /** `getMinimumSignatureSchemeVersionForTargetSdk` answers 0, `verifyV1Signature` skips `verifyFull`. */
    fun patchApkSignatureVerifier(content: String): PatchOutcome {
        val label = "ApkSignatureVerifier"
        val doc = Doc(content)
        var changed = false

        val minimum = doc.methodStart(label, "getMinimumSignatureSchemeVersionForTargetSdk", "I")
        changed = forceEntryReturn(doc, doc.scope(minimum, label), 0, "$label.getMinimum...") || changed

        val verify = doc.exactlyOne(label, doc.hits { V1_DESCRIPTOR in it }, "verifyV1Signature call")
        val guard = doc.registerArg(verify, 2)
            ?: throw UnsupportedLayoutException("$label: verifyV1Signature call has no register list")
        changed = doc.constBefore(verify, guard, 0, doc.localsAt(verify, label), label) || changed

        return outcome(changed, content, doc)
    }

    /** `verifyMessageDigest` answers `true`. */
    fun patchStrictJarVerifier(content: String): PatchOutcome {
        val label = "StrictJarVerifier"
        val doc = Doc(content)
        val at = doc.methodStart(label, "verifyMessageDigest", "Z")
        val changed = forceEntryReturn(doc, doc.scope(at, label), 1, label)
        return outcome(changed, content, doc)
    }

    /**
     * The manifest-entry loop no longer throws when a declared entry is missing: the `if-eqz` on
     * `findEntry`'s result goes, and with it the label that only ever branched to the
     * `SecurityException` it guarded.
     *
     * The label is removed only when nothing else in the method targets it, because a second
     * reference would leave the assembler with a branch into thin air.
     */
    fun patchStrictJarFile(content: String): PatchOutcome {
        val label = "StrictJarFile"
        val doc = Doc(content)
        val at = doc.exactlyOne(label, doc.hits { FIND_ENTRY.containsMatchIn(it) }, "StrictJarFile.findEntry")
        val scope = doc.scope(at, label)
        val captured = nextReal(doc, at + 1)
            ?: throw UnsupportedLayoutException("$label: findEntry is not followed by a result")
        val move = MOVE_RESULT_OBJECT.matchEntire(doc.lines[captured].trim())
            ?: throw UnsupportedLayoutException("$label: findEntry result is not captured")
        val tested = nextReal(doc, captured + 1)
            ?: throw UnsupportedLayoutException("$label: findEntry result is never tested")
        val branch = TEST_BRANCH.matchEntire(doc.lines[tested].trim())
            ?: return PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        if (branch.groupValues[1] != move.groupValues[1]) {
            throw UnsupportedLayoutException("$label: findEntry result is tested by an unrelated branch")
        }
        if (tested !in scope.start..scope.end) {
            throw UnsupportedLayoutException("$label: findEntry's branch lies outside its method")
        }

        val target = branch.groupValues[2]
        val definitions = (scope.start..scope.end).filter { doc.lines[it].trim() == target }
        if (definitions.size != 1) {
            throw UnsupportedLayoutException("$label: $target is defined ${definitions.size} times in its method")
        }
        val references = (scope.start..scope.end).filter {
            it != definitions[0] && doc.lines[it].contains(target)
        }
        if (references.singleOrNull() != tested) {
            throw UnsupportedLayoutException("$label: $target has ${references.size} users in its method")
        }

        doc.lines.removeAt(maxOf(tested, definitions[0]))
        doc.lines.removeAt(minOf(tested, definitions[0]))
        return outcome(true, content, doc)
    }

    /** The sharedUserId name check takes its "no complaint" branch. */
    fun patchParsingPackageUtils(content: String): PatchOutcome {
        val label = "ParsingPackageUtils"
        val doc = Doc(content)
        val badName = doc.exactlyOne(label, doc.hits { BAD_SHARED_USER_ID.containsMatchIn(it) }, "bad sharedUserId message")
        val changed = doc.forceBranchTaken(branchBefore(doc, badName, label), label)
        return outcome(changed, content, doc)
    }

    // ---- §2 services.jar -----------------------------------------------------

    /** Every `checkDowngrade` overload returns immediately; the signature checks answer 0/1/0. */
    fun patchPackageManagerServiceUtils(content: String): PatchOutcome {
        val label = "PackageManagerServiceUtils"
        val doc = Doc(content)
        var changed = false

        val downgrades = doc.methodStarts { name, ret -> name == "checkDowngrade" && ret == "V" }
        if (downgrades.isEmpty()) throw UnsupportedLayoutException("$label: no checkDowngrade overload")
        for (at in downgrades.asReversed()) {
            changed = forceEntryReturn(doc, doc.scope(at, label), null, "$label.checkDowngrade") || changed
        }
        for ((name, ret, value) in SIGNATURE_CHECKS) {
            val at = doc.methodStart(label, name, ret)
            changed = forceEntryReturn(doc, doc.scope(at, label), value, "$label.$name") || changed
        }
        return outcome(changed, content, doc)
    }

    /** `shouldCheckUpgradeKeySetLocked` answers `false`. */
    fun patchKeySetManagerService(content: String): PatchOutcome {
        val label = "KeySetManagerService"
        val doc = Doc(content)
        val at = doc.methodStart(label, "shouldCheckUpgradeKeySetLocked", "Z")
        val changed = forceEntryReturn(doc, doc.scope(at, label), 0, label)
        return outcome(changed, content, doc)
    }

    /**
     * The `isLeavingSharedUser` branch is forced, so a package is never held back for it.
     *
     * This ROM calls the predicate on `AndroidPackage` twice and tests both results, so the
     * guide's own anchor — the call on the `p1` package parameter — is what selects between them;
     * the other sits in a different method and the guide never asked about it.
     */
    fun patchInstallPackageHelper(content: String): PatchOutcome {
        val label = "InstallPackageHelper"
        val doc = Doc(content)
        val at = guideSite(doc, label)
        val branch = branchOnResult(doc, at)
            ?: throw UnsupportedLayoutException("$label: isLeavingSharedUser result is never tested")
        val changed = doc.forceBranchTaken(branch, label)
        return outcome(changed, content, doc)
    }

    /**
     * `<clinit>`'s `ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS` becomes `true`.
     *
     * The file carries eleven other `const/4 vN, 0x0` instructions, so the rewrite is scoped to the
     * constructor and requires exactly one candidate there — a second rewrite would change
     * behaviour the guide never asked about.
     */
    fun patchReconcilePackageUtils(content: String): PatchOutcome {
        val label = "ReconcilePackageUtils.<clinit>"
        val doc = Doc(content)
        val at = doc.methodStart("ReconcilePackageUtils", "<clinit>", "V")
        val scope = doc.scope(at, label)
        if ((scope.start..scope.end).none { "restrictNonpreloadsSystemShareduids" in doc.lines[it] }) {
            throw UnsupportedLayoutException("$label: restrictNonpreloadsSystemShareduids guard not found")
        }
        val zeros = (scope.start..scope.end).filter { ZERO_CONST.matches(doc.lines[it].trim()) }
        if (zeros.size > 1) throw UnsupportedLayoutException("$label: ${zeros.size} candidate constants in <clinit>")
        if (zeros.isEmpty()) return PatchOutcome(PatchStatus.ALREADY_PATCHED, content)

        val register = CONST.matchEntire(doc.lines[zeros[0]].trim())!!.groupValues[1]
        doc.lines[zeros[0]] = INDENT + doc.constFor(register, doc.localsAt(zeros[0], label), 1) + doc.nl
        return outcome(true, content, doc)
    }

    // ---- §3 miui-services.jar ------------------------------------------------

    /**
     * The two methods that decide whether a system app may be replaced by a third-party APK.
     *
     * §3 is the one section the guide marks conditional — "if present in the ROM" — so a class,
     * or a method inside it, that this ROM does not carry is [PatchStatus.NOT_TARGET] rather than
     * a rejection: unlike §1 and §2 there is nothing unconditional to fall back on. Both are `V`
     * on this ROM, so `return-void` is type-correct as the guide specifies.
     */
    private val MIUI_UPDATE_METHODS = listOf("verifyIsolationViolation", "canBeUpdate")

    fun patchPackageManagerServiceImpl(content: String): PatchOutcome {
        val label = "PackageManagerServiceImpl"
        val doc = Doc(content)
        val sites = guideSite3(doc)
        if (sites.isEmpty()) return PatchOutcome(PatchStatus.NOT_TARGET, content)
        var changed = false
        for ((at, name) in sites) {
            changed = forceEntryReturn(doc, doc.scope(at, label), null, "$label.$name") || changed
        }
        return outcome(changed, content, doc)
    }

    /**
     * Every §3 method body with its name, ordered so a rewrite never invalidates a later index:
     * forcing a return inserts lines, and the two methods sit thousands of lines apart.
     */
    private fun guideSite3(doc: Doc): List<Pair<Int, String>> =
        MIUI_UPDATE_METHODS.flatMap { name ->
            doc.methodStarts { n, _ -> n == name }.map { it to name }
        }.sortedByDescending { it.first }

    // ---- verification --------------------------------------------------------

    fun verifyPackageParser(content: String) = verifying("PackageParser") {
        val doc = Doc(content)
        val certs = doc.exactlyOneOrFail("PackageParser", doc.hits { CERTS_DESCRIPTOR in it }, "unsafeGetCertsWithoutVerification")
        val version = doc.registerArg(certs, 2) ?: failVerification("PackageParser: no register list")
        doc.expectConstBefore(certs, version, 1, "PackageParser: unsafeGetCertsWithoutVerification version")

        val badName = doc.exactlyOneOrFail("PackageParser", doc.hits { BAD_SHARED_USER_ID.containsMatchIn(it) }, "bad sharedUserId message")
        val branch = branchBefore(doc, badName, "PackageParser")
        doc.expectConstBefore(branch, branchOperand(doc, branch, "PackageParser"), forcedTaken(doc, branch), "PackageParser: sharedUserId branch")
    }

    fun verifyPackageParserException(content: String) = verifying("PackageParserException") {
        val doc = Doc(content)
        val sites = doc.hits { it == ERROR_FIELD }
        if (sites.isEmpty()) failVerification("PackageParserException: no error field store left to check")
        for (at in sites) doc.expectConstBefore(at, "p1", 0, "PackageParserException: error field")
    }

    fun verifyPackageParserSigningDetails(content: String) = verifying("PackageParser\$SigningDetails") {
        expectCheckCapability(Doc(content), "PackageParser\$SigningDetails")
    }

    fun verifySigningDetails(content: String) = verifying("SigningDetails") {
        val doc = Doc(content)
        expectCheckCapability(doc, "SigningDetails")
        expectEntryForced(doc, doc.methodStartOrFail("SigningDetails", "hasAncestorOrSelf", "Z"), 1, "SigningDetails.hasAncestorOrSelf")
    }

    fun verifyApkSignatureSchemeV2Verifier(content: String) = expectDigestMatch(content, "ApkSignatureSchemeV2Verifier")
    fun verifyApkSignatureSchemeV3Verifier(content: String) = expectDigestMatch(content, "ApkSignatureSchemeV3Verifier")
    fun verifyApkSigningBlockUtils(content: String) = expectDigestMatch(content, "ApkSigningBlockUtils")

    fun verifyApkSignatureVerifier(content: String) = verifying("ApkSignatureVerifier") {
        val doc = Doc(content)
        expectEntryForced(doc, doc.methodStartOrFail("ApkSignatureVerifier", "getMinimumSignatureSchemeVersionForTargetSdk", "I"), 0, "ApkSignatureVerifier.getMinimum...")
        val verify = doc.exactlyOneOrFail("ApkSignatureVerifier", doc.hits { V1_DESCRIPTOR in it }, "verifyV1Signature call")
        val guard = doc.registerArg(verify, 2) ?: failVerification("ApkSignatureVerifier: no register list")
        doc.expectConstBefore(verify, guard, 0, "ApkSignatureVerifier: verifyFull guard")
    }

    fun verifyStrictJarVerifier(content: String) = verifying("StrictJarVerifier") {
        val doc = Doc(content)
        expectEntryForced(doc, doc.methodStartOrFail("StrictJarVerifier", "verifyMessageDigest", "Z"), 1, "StrictJarVerifier.verifyMessageDigest")
    }

    fun verifyStrictJarFile(content: String) = verifying("StrictJarFile") {
        val doc = Doc(content)
        val at = doc.exactlyOneOrFail("StrictJarFile", doc.hits { FIND_ENTRY.containsMatchIn(it) }, "StrictJarFile.findEntry")
        val captured = nextReal(doc, at + 1) ?: failVerification("StrictJarFile: findEntry is not followed by a result")
        val move = MOVE_RESULT_OBJECT.matchEntire(doc.lines[captured].trim())
            ?: failVerification("StrictJarFile: findEntry result is not captured")
        val tested = nextReal(doc, captured + 1) ?: failVerification("StrictJarFile: findEntry result is never tested")
        val branch = TEST_BRANCH.matchEntire(doc.lines[tested].trim())
        if (branch != null && branch.groupValues[1] == move.groupValues[1]) {
            failVerification("StrictJarFile: findEntry still discards a missing entry")
        }
    }

    fun verifyParsingPackageUtils(content: String) = verifying("ParsingPackageUtils") {
        val doc = Doc(content)
        val badName = doc.exactlyOneOrFail("ParsingPackageUtils", doc.hits { BAD_SHARED_USER_ID.containsMatchIn(it) }, "bad sharedUserId message")
        val branch = branchBefore(doc, badName, "ParsingPackageUtils")
        doc.expectConstBefore(branch, branchOperand(doc, branch, "ParsingPackageUtils"), forcedTaken(doc, branch), "ParsingPackageUtils: sharedUserId branch")
    }

    fun verifyPackageManagerServiceUtils(content: String) = verifying("PackageManagerServiceUtils") {
        val doc = Doc(content)
        val downgrades = doc.methodStarts { name, ret -> name == "checkDowngrade" && ret == "V" }
        if (downgrades.isEmpty()) failVerification("PackageManagerServiceUtils: no checkDowngrade overload left")
        for (at in downgrades) expectEntryForced(doc, at, null, "PackageManagerServiceUtils.checkDowngrade")
        for ((name, ret, value) in SIGNATURE_CHECKS) {
            expectEntryForced(doc, doc.methodStartOrFail("PackageManagerServiceUtils", name, ret), value, "PackageManagerServiceUtils.$name")
        }
    }

    fun verifyKeySetManagerService(content: String) = verifying("KeySetManagerService") {
        val doc = Doc(content)
        expectEntryForced(
            doc,
            doc.methodStartOrFail("KeySetManagerService", "shouldCheckUpgradeKeySetLocked", "Z"),
            0,
            "KeySetManagerService.shouldCheckUpgradeKeySetLocked",
        )
    }

    fun verifyInstallPackageHelper(content: String) = verifying("InstallPackageHelper") {
        val doc = Doc(content)
        val at = guideSite(doc, "InstallPackageHelper")
        val branch = branchOnResult(doc, at) ?: failVerification("InstallPackageHelper: isLeavingSharedUser is no longer tested")
        doc.expectConstBefore(branch, branchOperand(doc, branch, "InstallPackageHelper"), forcedTaken(doc, branch), "InstallPackageHelper: leaving shared user")
    }

    fun verifyPackageManagerServiceImpl(content: String) = verifying("PackageManagerServiceImpl") {
        val doc = Doc(content)
        val sites = guideSite3(doc)
        if (sites.isEmpty()) failVerification("PackageManagerServiceImpl: neither §3 method is present")
        for ((at, name) in sites) expectEntryForced(doc, at, null, "PackageManagerServiceImpl.$name")
    }

    fun verifyReconcilePackageUtils(content: String) = verifying("ReconcilePackageUtils") {
        val doc = Doc(content)
        val at = doc.methodStartOrFail("ReconcilePackageUtils", "<clinit>", "V")
        val scope = doc.scope(at, "ReconcilePackageUtils.<clinit>")
        if ((scope.start..scope.end).none { "restrictNonpreloadsSystemShareduids" in doc.lines[it] }) {
            failVerification("ReconcilePackageUtils: <clinit> lost its guard")
        }
        if ((scope.start..scope.end).any { ZERO_CONST.matches(doc.lines[it].trim()) }) {
            failVerification("ReconcilePackageUtils: <clinit> still allows the stock default")
        }
    }

    // ---- shared machinery ----------------------------------------------------

    /** Guide §2's signature table: method name, return descriptor, forced value. */
    private val SIGNATURE_CHECKS = listOf(
        Triple("compareSignatures", "I", 0),
        Triple("matchSignaturesCompat", "Z", 1),
        Triple("verifySignatures", "Z", 0),
    )

    private fun outcome(changed: Boolean, original: String, doc: Doc): PatchOutcome =
        if (changed) PatchOutcome(PatchStatus.PATCHED, doc.text())
        else PatchOutcome(PatchStatus.ALREADY_PATCHED, original)

    /** Runs [block], promoting a layout the patcher could not parse into a failed verification. */
    private inline fun verifying(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: UnsupportedLayoutException) {
            failVerification("$label: ${e.message}")
        }
    }

    /** The first non-blank, non-directive line at or after [from], stopping at `.end method`. */
    private fun nextReal(doc: Doc, from: Int): Int? {
        var i = from
        while (i < doc.lines.size) {
            val text = doc.lines[i].trim()
            if (text.startsWith(".end method")) return null
            if (text.isEmpty() || text.startsWith(".")) {
                i++
                continue
            }
            return i
        }
        return null
    }

    /**
     * The conditional guarding [anchorAt], searched *backwards* and stopping at a label so a
     * branch belonging to an earlier block can never be picked by mistake.
     */
    private fun branchBefore(doc: Doc, anchorAt: Int, label: String): Int {
        val first = doc.scope(anchorAt, label).start
        var i = anchorAt - 1
        var scanned = 0
        while (i >= first && scanned < 40) {
            val text = doc.lines[i].trim()
            if (text.isEmpty() || text.startsWith(".")) {
                i--
                scanned++
                continue
            }
            if (text.startsWith(":")) break
            if (TEST_BRANCH.matches(text)) return i
            i--
            scanned++
        }
        throw UnsupportedLayoutException("$label: no conditional branch ahead of the anchor")
    }

    /**
     * The guide's `isLeavingSharedUser` anchor, of which there are two in this ROM.
     *
     * `InstallPackageHelper.canBeUpdated` calls it on `p1` and tests the result against `:cond_1`
     * — that is the guide's site. A second call, on `p5` with `v3` and `:cond_2`, is a different
     * branch entirely and must stay untouched, so candidates are first narrowed to the ones whose
     * `move-result` is actually tested and then to the one reading `p1`. The `ifEmpty` keeps the
     * filter honest: a ROM that names its parameters differently still resolves rather than
     * rejecting, and only an ambiguous residual is refused.
     */
    private fun guideSite(doc: Doc, label: String): Int {
        val tested = doc.hits { LEAVING_SHARED_USER.containsMatchIn(it) }
            .filter { branchOnResult(doc, it) != null }
        val site = tested.filter { doc.registerArg(it, 0) == "p1" }.ifEmpty { tested }
        return doc.exactlyOne(label, site, "isLeavingSharedUser whose result is tested")
    }

    /**
     * The branch testing [invokeAt]'s `move-result`, when the guide's shape is present.
     *
     * A forced constant may already stand between the verdict and the branch — that is where
     * [Doc.forceBranchTaken] puts it, because `move-result` would overwrite a constant placed
     * above it — so the scan steps over one writing the same register rather than giving up on
     * the patcher's own output.
     */
    private fun branchOnResult(doc: Doc, invokeAt: Int): Int? {
        val moveAt = nextReal(doc, invokeAt + 1) ?: return null
        val move = MOVE_RESULT.matchEntire(doc.lines[moveAt].trim()) ?: return null
        var at = moveAt + 1
        while (true) {
            val next = nextReal(doc, at) ?: return null
            val branch = TEST_BRANCH.matchEntire(doc.lines[next].trim())
            if (branch != null) return next.takeIf { branch.groupValues[1] == move.groupValues[1] }
            if (!doc.constAssigns(doc.lines[next], move.groupValues[1])) return null
            at = next + 1
        }
    }

    private fun branchOperand(doc: Doc, at: Int, label: String): String =
        TEST_BRANCH.matchEntire(doc.lines[at].trim())?.groupValues?.get(1)
            ?: throw UnsupportedLayoutException("$label: not a simple conditional branch")

    /** The value that makes the branch at [at] fall through the way the guide wants. */
    private fun forcedTaken(doc: Doc, at: Int): Int =
        if (doc.lines[at].trim().startsWith("if-eqz")) 0 else 1

    private fun forcedReturn(doc: Doc, scope: Scope, value: Int?, label: String): List<String> =
        if (value == null) listOf("return-void")
        else {
            if (MethodRewrite.paramCount(doc.lines[scope.start].trim()) == 0) {
                throw UnsupportedLayoutException("$label: no parameter register to force")
            }
            listOf(doc.constFor("p0", scope.locals, value), "return p0")
        }

    /** Prepends a forced return to a method, directly below its register directive. */
    private fun forceEntryReturn(doc: Doc, scope: Scope, value: Int?, label: String): Boolean {
        val expected = forcedReturn(doc, scope, value, label)
        var i = scope.directive + 1
        while (i < scope.end && doc.lines[i].trim().isEmpty()) i++
        val present = (i until minOf(i + expected.size, scope.end)).map { doc.lines[it].trim() }
        if (present == expected) return false
        doc.lines.addAll(scope.directive + 1, expected.map { INDENT + it + doc.nl })
        return true
    }

    private fun expectEntryForced(doc: Doc, at: Int, value: Int?, label: String) {
        val scope = doc.scope(at, label)
        val expected = forcedReturn(doc, scope, value, label)
        var i = scope.directive + 1
        while (i < scope.end && doc.lines[i].trim().isEmpty()) i++
        val present = (i until minOf(i + expected.size, scope.end)).map { doc.lines[it].trim() }
        if (present != expected) {
            failVerification("$label: method does not start with ${expected.joinToString(" / ")}")
        }
    }

    /** Both classes declare `checkCapability`, `checkCapability(String, int)` and `…Recover`. */
    private fun checkCapabilityStarts(doc: Doc, label: String): List<Int> =
        doc.methodStarts { name, ret -> name.startsWith("checkCapability") && ret == "Z" }
            .also { if (it.size < 2) throw UnsupportedLayoutException("$label: found ${it.size} checkCapability methods") }

    private fun forceCheckCapability(doc: Doc, label: String): Boolean {
        var changed = false
        for (at in checkCapabilityStarts(doc, label).asReversed()) {
            changed = forceEntryReturn(doc, doc.scope(at, label), 1, "$label.checkCapability") || changed
        }
        return changed
    }

    private fun expectCheckCapability(doc: Doc, label: String) {
        val starts = doc.methodStarts { name, ret -> name.startsWith("checkCapability") && ret == "Z" }
        if (starts.size < 2) failVerification("$label: found ${starts.size} checkCapability methods")
        for (at in starts) expectEntryForced(doc, at, 1, "$label.checkCapability")
    }

    private fun forceDigestMatch(content: String, label: String): PatchOutcome {
        val doc = Doc(content)
        val sites = doc.hits { IS_EQUAL.containsMatchIn(it) }.filter { readsDigestVerdict(doc, it) }
        val at = doc.exactlyOne(label, sites, "MessageDigest.isEqual whose result is the verdict")
        val verdict = nextReal(doc, at + 1)
            ?: throw UnsupportedLayoutException("$label: MessageDigest.isEqual is not followed by a result")
        val line = doc.lines[verdict].trim()
        val move = MOVE_RESULT.matchEntire(line)
        if (move != null) {
            doc.lines[verdict] = INDENT + doc.constFor(move.groupValues[1], doc.localsAt(at, label), 1) + doc.nl
            return PatchOutcome(PatchStatus.PATCHED, doc.text())
        }
        if (CONST.matchEntire(line)?.groupValues?.get(2)?.equals("0x1", ignoreCase = true) == true) {
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }
        throw UnsupportedLayoutException("$label: MessageDigest.isEqual result is not a verdict")
    }

    private fun expectDigestMatch(content: String, label: String) = verifying(label) {
        val doc = Doc(content)
        val sites = doc.hits { IS_EQUAL.containsMatchIn(it) }.filter { readsDigestVerdict(doc, it) }
        val at = doc.exactlyOneOrFail(label, sites, "MessageDigest.isEqual whose result is the verdict")
        val next = nextReal(doc, at + 1) ?: failVerification("$label: isEqual has no result")
        val line = doc.lines[next].trim()
        if (MOVE_RESULT.matches(line)) failVerification("$label: isEqual verdict is still the stock move-result")
        val const = CONST.matchEntire(line) ?: failVerification("$label: isEqual result was not forced")
        if (!const.groupValues[2].equals("0x1", ignoreCase = true)) {
            failVerification("$label: isEqual verdict forced to ${const.groupValues[2]}")
        }
    }

    private fun readsDigestVerdict(doc: Doc, at: Int): Boolean {
        val next = nextReal(doc, at + 1) ?: return false
        val line = doc.lines[next].trim()
        if (MOVE_RESULT.matches(line)) return true
        val const = CONST.matchEntire(line) ?: return false
        return const.groupValues[2].equals("0x1", ignoreCase = true)
    }

    private data class Scope(val start: Int, val end: Int, val directive: Int, val locals: Int)

    /** A smali file as editable lines that keep their own terminators. */
    private class Doc(content: String) {
        val lines: MutableList<String> = Smali.splitKeepEnds(content).toMutableList()
        val nl: String = Smali.newlineOf(content)

        fun text(): String = lines.joinToString("")

        fun hits(predicate: (String) -> Boolean): List<Int> =
            lines.indices.filter { predicate(lines[it].trim()) }

        fun exactlyOne(label: String, hits: List<Int>, what: String): Int = when (hits.size) {
            1 -> hits[0]
            0 -> throw UnsupportedLayoutException("$label: $what not found")
            else -> throw UnsupportedLayoutException("$label: ambiguous $what (${hits.size} matches)")
        }

        fun exactlyOneOrFail(label: String, hits: List<Int>, what: String): Int = when (hits.size) {
            1 -> hits[0]
            0 -> failVerification("$label: $what not found")
            else -> failVerification("$label: ambiguous $what (${hits.size} matches)")
        }

        fun methodStarts(predicate: (name: String, returnType: String) -> Boolean): List<Int> =
            lines.indices.filter { i ->
                val header = lines[i].trim()
                header.startsWith(".method") && predicate(methodName(header), returnType(header))
            }

        fun methodStart(label: String, name: String, returnType: String): Int =
            exactlyOne(label, methodStarts { n, r -> n == name && r == returnType }, name)

        fun methodStartOrFail(label: String, name: String, returnType: String): Int =
            exactlyOneOrFail(label, methodStarts { n, r -> n == name && r == returnType }, name)

        fun localsAt(at: Int, label: String): Int = scope(at, label).locals

        /** The method enclosing [at], its register directive, and how many registers it owns. */
        fun scope(at: Int, label: String): Scope {
            var start = at
            while (start > 0 && !lines[start].trimStart().startsWith(".method")) start--
            if (!lines[start].trimStart().startsWith(".method")) {
                throw UnsupportedLayoutException("$label: no enclosing .method")
            }
            var end = start + 1
            while (end < lines.size && !lines[end].trimStart().startsWith(".end method")) end++
            if (end >= lines.size) throw UnsupportedLayoutException("$label: unterminated method")

            var directive = start + 1
            while (directive < end && !DIRECTIVE.matches(lines[directive].trim())) directive++
            if (directive >= end) throw UnsupportedLayoutException("$label: no .registers/.locals directive")

            val match = DIRECTIVE.matchEntire(lines[directive].trim())!!
            val count = match.groupValues[2].toInt()
            val params = MethodRewrite.paramCount(lines[start].trim())
            val locals = if (match.groupValues[1] == "registers") count - params else count
            return Scope(start, end, directive, locals)
        }

        /** The [index]th register of an `invoke`'s `{…}` list, when it names a single register. */
        fun registerArg(at: Int, index: Int): String? {
            val list = REGISTER_LIST.find(lines[at])?.groupValues?.get(1) ?: return null
            return list.split(',').map { it.trim() }.getOrNull(index)
                ?.takeIf { SINGLE_REGISTER.matches(it) }
        }

        fun constFor(register: String, localsCount: Int, value: Int): String {
            if (value !in 0..1) throw UnsupportedLayoutException("the guides only force 0 or 1, got $value")
            return if (Smali.physical(register, localsCount) <= 15) "const/4 $register, 0x$value"
            else "const $register, 0x$value"
        }

        /** Places a constant immediately above [at]; reports whether anything was written. */
        fun constBefore(at: Int, register: String, value: Int, localsCount: Int, label: String): Boolean {
            if (at <= 0) throw UnsupportedLayoutException("$label: nothing precedes the anchor")
            if (isConst(lines[at - 1], register, value)) return false
            if (lines[at - 1].trim().startsWith("const")) {
                throw UnsupportedLayoutException("$label: an unrelated constant precedes the anchor")
            }
            lines.add(at, INDENT + constFor(register, localsCount, value) + nl)
            return true
        }

        /** Forces the branch at [at] to be taken, which is what all three guide sites mean. */
        fun forceBranchTaken(at: Int, label: String): Boolean {
            val text = lines[at].trim()
            val branch = TEST_BRANCH.matchEntire(text)
                ?: throw UnsupportedLayoutException("$label: not a simple conditional branch: $text")
            val value = if (text.startsWith("if-eqz")) 0 else 1
            return constBefore(at, branch.groupValues[1], value, localsCount = localsAt(at, label), label = label)
        }

        /** Whether [line] is a constant assignment to [register], of any width. */
        fun constAssigns(line: String, register: String): Boolean =
            CONST.matchEntire(line.trim())?.groupValues?.get(1) == register

        fun expectConstBefore(at: Int, register: String, value: Int, what: String) {
            if (at <= 0 || !isConst(lines[at - 1], register, value)) failVerification(what)
        }

        private fun isConst(line: String, register: String, value: Int): Boolean {
            val match = CONST.matchEntire(line.trim()) ?: return false
            return match.groupValues[1] == register &&
                match.groupValues[2].equals("0x$value", ignoreCase = true)
        }

        private fun methodName(header: String): String =
            header.removePrefix(".method").trim().substringBefore('(')
                .split(Regex("\\s+")).last()

        private fun returnType(header: String): String = header.substringAfter(')', "")
    }
}

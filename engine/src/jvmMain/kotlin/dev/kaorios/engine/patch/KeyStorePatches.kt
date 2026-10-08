package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException

/** AndroidKeyStoreKeyPairGeneratorSpi.generateKeyPair() - software keypair hook. */
object KeyStoreGeneratorPatch {

    const val HOOK_SIGNATURE =
        "Landroid/security/kaorios/KaoriosHook;->initGenerateSoftwareKeyPair(Ljava/lang/Object;)Ljava/security/KeyPair;"
    private const val ANCHOR = "generateKeyPair()Ljava/security/KeyPair;"
    private val DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)")

    fun patch(content: String): PatchOutcome {
        val start = content.indexOf(ANCHOR)
        if (start < 0) {
            throw UnsupportedLayoutException("generateKeyPair() anchor method not found in KeyPairGeneratorSpi")
        }
        val end = content.indexOf(".end method", start)
        if (end < 0) {
            throw UnsupportedLayoutException("unterminated generateKeyPair method in KeyPairGeneratorSpi")
        }

        var methodBody = content.substring(start, end)
        if (methodBody.contains(HOOK_SIGNATURE)) {
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }

        val match = DIRECTIVE.find(methodBody)
            ?: throw UnsupportedLayoutException(".registers or .locals directive not found in generateKeyPair")
        val directive = match.groupValues[1]
        val oldReg = match.groupValues[2].toInt()
        val newReg = oldReg + 1

        val vTarget: String
        val p0Num: Int
        if (directive == "registers") {
            methodBody = Smali.canonicalizeParamAliases(methodBody, oldReg, 1)
            vTarget = "v${newReg - 2}"
            p0Num = newReg - 1
        } else {
            methodBody = Smali.canonicalizeParamAliases(methodBody, oldReg + 1, 1)
            vTarget = "v$oldReg"
            p0Num = oldReg + 1
        }

        val label = Smali.uniqueLabel(":cond_kaorios_gen_stock", methodBody)
        val invoke = if (p0Num > 15) {
            "invoke-static/range {p0 .. p0}, $HOOK_SIGNATURE"
        } else {
            "invoke-static {p0}, $HOOK_SIGNATURE"
        }

        val inject = """
    $invoke
    move-result-object $vTarget

    if-eqz $vTarget, $label
    return-object $vTarget

    $label
"""
        val finalMatch = DIRECTIVE.find(methodBody)
            ?: throw UnsupportedLayoutException("register directive vanished during canonicalization")
        val newBody = methodBody.substring(0, finalMatch.range.first) +
            ".$directive $newReg" + inject + methodBody.substring(finalMatch.range.last + 1)

        return PatchOutcome(
            PatchStatus.PATCHED,
            content.substring(0, start) + newBody + content.substring(end)
        )
    }

    fun verify(content: String) {
        val body = Smali.methodSpanByAnchor(content, ANCHOR, "AndroidKeyStoreKeyPairGeneratorSpi")
            .let { content.substring(it.start, it.endExclusive) }
        val count = Regex(Regex.escape(HOOK_SIGNATURE)).findAll(body).count()
        if (count != 1) {
            failVerification(
                "AndroidKeyStoreKeyPairGeneratorSpi: expected exactly 1 initGenerateSoftwareKeyPair hook in generateKeyPair, found $count"
            )
        }
    }
}

/**
 * AndroidKeyStoreSpi - certificate chain hook plus single-leaf delegation (upstream v2.0.6.1).
 *
 * Ported from `_patch_certificate_chain` + `patch_keystore_spi` in `kaorios_patcher.py`:
 * the chain's complete-array return is wrapped in `CertificateChainIfNeeded`, and
 * `engineGetCertificate` delegates to the chain so the single certificate is the first chain
 * element, with the stock body kept below `:kaorios_certificate_stock` as the fallback. A
 * historical build injected the hook but discarded its `move-result` into the wrong register;
 * that layout is repaired in place rather than rejected.
 */
object KeyStoreSpiPatch {

    const val HOOK_SIGNATURE =
        "Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;"
    private const val CHAIN_ANCHOR = "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;"
    private const val LEAF_ANCHOR = "engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;"

    /** The delegation call as it appears inside a method body, used as the idempotency marker. */
    private const val CHAIN_CALL =
        "->engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;"

    private const val LEAF_DELEGATE =
        "invoke-virtual/range {p0 .. p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;" +
            CHAIN_CALL

    private const val LEAF_LABEL = ":kaorios_certificate_stock"
    private val DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)")
    private val APUT_RETURN = Regex(
        "(?<aput>aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+)" +
            "(?<gap>(?:[ \\t]*\\.(?:line|local|end local|restart local)[^\\n]*\\n|[ \\t]*\\n)*)" +
            "[ \\t]*(?<ret>return-object\\s+\\k<array>)"
    )
    private val RETURN_OBJECT = Regex("\\breturn-object\\s+([vp]\\d+)")

    /** The hook signature escaped for embedding in a pattern. */
    private const val HOOK_PATTERN =
        "Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded" +
            "\\(\\[Ljava/security/cert/Certificate;\\)\\[Ljava/security/cert/Certificate;"

    /**
     * Debug directives or blank lines the reference tolerates between instructions,
     * verbatim from upstream (`debug_gap` / the `pairs` gaps in `verify_target_content`).
     */
    private const val DEBUG_GAP =
        "(?:\\s*\\.(?:line|local|end local|restart local)[^\\n]*\\n|\\s*\\n)*\\s*"

    /**
     * The historical hook whose result register does not match the array it was called with.
     *
     * Repair rewrites only the `move-result-object` operand to the array, exactly as
     * upstream's `_patch_certificate_chain` does when the hook is already present.
     */
    private val DISCARDED_HOOK = Regex(
        "(?<prefix>aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+" +
            DEBUG_GAP +
            "invoke-static(?:/range)?\\s*\\{\\k<array>(?:\\s*\\.\\.\\s*\\k<array>)?\\},\\s*" +
            HOOK_PATTERN +
            DEBUG_GAP +
            "move-result-object\\s+)" +
            "(?<result>[vp]\\d+)" +
            "(?<tail>" + DEBUG_GAP + "return-object\\s+\\k<array>)"
    )

    fun patch(content: String): PatchOutcome {
        val (chained, chainChanged) = patchCertificateChain(content)
        return patchLeafDelegation(chained, chainChanged)
    }

    /** `_patch_certificate_chain`: strict anchor, repair branch, then the fresh chain hook. */
    private fun patchCertificateChain(content: String): Pair<String, Boolean> {
        val span = Smali.methodSpanByAnchor(content, CHAIN_ANCHOR, "AndroidKeyStoreSpi")
        val methodBody = content.substring(span.start, span.endExclusive)

        if (methodBody.contains(HOOK_SIGNATURE)) {
            val repaired = DISCARDED_HOOK.replace(methodBody) { match ->
                match.groups["prefix"]!!.value + match.groups["array"]!!.value +
                    match.groups["tail"]!!.value
            }
            val body = content.substring(0, span.start) + repaired +
                content.substring(span.endExclusive)
            return body to (repaired != methodBody)
        }

        // The actual A13-A17 methods have two null returns and one populated
        // certificate array return. Match the leaf insertion immediately before
        // that return; a loop's earlier aput-object is not a safe hook point.
        // AOSP Android 15 carries a fourth bare null return (the catch path), so
        // beyond the (3,1) shape every non-matched return must be provably null.
        val matches = APUT_RETURN.findAll(methodBody).toList()
        if (matches.isEmpty()) {
            throw UnsupportedLayoutException("engineGetCertificateChain: leaf-array return not found")
        }
        val returns = RETURN_OBJECT.findAll(methodBody).map { it.groupValues[1] }.toList()
        if (returns.size == 3 && matches.size == 1) {
            val nullReg = returns[0]
            val array = matches[0].groups["array"]!!.value
            if (returns != listOf(nullReg, array, nullReg) ||
                !Regex("const/4\\s+${Regex.escape(nullReg)},\\s*0x0\\b")
                    .containsMatchIn(methodBody.substring(0, matches[0].range.first))
            ) {
                throw UnsupportedLayoutException("engineGetCertificateChain: unsupported null-return layout")
            }
        } else {
            // AOSP-shaped methods (e.g. Android 15) carry extra bare null returns alongside
            // the single populated-array return — four returns against one aput-adjacent
            // hook point. Every return that is not a matched hook point must be provably
            // null: its register was const/4'd to 0x0 before the first populated return.
            // The equal-count case falls in here too and is vacuously satisfied, since
            // every return is then matched.
            val nullConsts = Regex("const/4\\s+([vp]\\d+),\\s*0x0\\b")
                .findAll(methodBody.substring(0, matches.first().range.first))
                .map { it.groupValues[1] }.toSet()
            val hooked = matches.map { it.groups["ret"]!!.range.first }.toSet()
            for (ret in RETURN_OBJECT.findAll(methodBody)) {
                if (ret.range.first !in hooked && ret.groupValues[1] !in nullConsts) {
                    throw UnsupportedLayoutException("engineGetCertificateChain: unsupported return layout")
                }
            }
        }

        val regMatch = DIRECTIVE.find(methodBody)
            ?: throw UnsupportedLayoutException("engineGetCertificateChain register directive not found")
        val regCount = regMatch.groupValues[2].toInt()
        val paramBase = if (regMatch.groupValues[1] == "registers") regCount - 2 else regCount

        var newBody = methodBody
        for (match in matches.asReversed()) {
            val array = match.groups["array"]!!.value
            val physical = if (array.startsWith("p")) paramBase + array.substring(1).toInt() else array.substring(1).toInt()
            val invoke = if (physical > 15) {
                "invoke-static/range {$array .. $array}, $HOOK_SIGNATURE"
            } else {
                "invoke-static {$array}, $HOOK_SIGNATURE"
            }
            val inject = "$invoke\n    move-result-object $array\n    "
            val retStart = match.groups["ret"]!!.range.first
            newBody = newBody.substring(0, retStart) + inject + newBody.substring(retStart)
        }
        return (content.substring(0, span.start) + newBody + content.substring(span.endExclusive)) to true
    }

    /** The `engineGetCertificate` half of `patch_keystore_spi`: delegate to the chain. */
    private fun patchLeafDelegation(content: String, chainChanged: Boolean): PatchOutcome {
        val span = Smali.methodSpanByAnchor(content, LEAF_ANCHOR, "AndroidKeyStoreSpi")
        val body = content.substring(span.start, span.endExclusive)

        if (body.contains(CHAIN_CALL)) {
            verify(content)
            return PatchOutcome(
                if (chainChanged) PatchStatus.PATCHED else PatchStatus.ALREADY_PATCHED,
                content
            )
        }

        val directive = DIRECTIVE.find(body)
            ?: throw UnsupportedLayoutException("engineGetCertificate register directive not found")
        val locals = directive.groupValues[2].toInt() -
            (if (directive.groupValues[1] == "registers") 2 else 0)
        if (locals < 2) {
            // The reference raises a plain ValueError here, and its status machine maps this
            // message to FAILED rather than UNSUPPORTED_LAYOUT - keep the same classification.
            throw IllegalStateException("engineGetCertificate requires two existing local registers")
        }
        val label = Smali.uniqueLabel(LEAF_LABEL, body)
        val inject = "\n" +
            "    $LEAF_DELEGATE\n" +
            "    move-result-object v0\n" +
            "    if-eqz v0, $label\n" +
            "    array-length v1, v0\n" +
            "    if-eqz v1, $label\n" +
            "    const/4 v1, 0x0\n" +
            "    aget-object v0, v0, v1\n" +
            "    return-object v0\n" +
            "    $label\n"
        val insertAt = directive.range.last + 1
        val newBody = body.substring(0, insertAt) + inject + body.substring(insertAt)
        return PatchOutcome(
            PatchStatus.PATCHED,
            content.substring(0, span.start) + newBody + content.substring(span.endExclusive)
        )
    }

    fun verify(content: String) {
        verifyChain(content)
        verifyLeafDelegation(content)
    }

    private fun verifyChain(content: String) {
        val span = Smali.methodSpanByAnchor(content, CHAIN_ANCHOR, "AndroidKeyStoreSpi")
        val body = content.substring(span.start, span.endExclusive)
        val returns = RETURN_OBJECT.findAll(body).map { it.groupValues[1] }.toList()
        val returnCount = returns.size
        val hookCount = Regex(Regex.escape(HOOK_SIGNATURE)).findAll(body).count()
        if (returnCount == 0) {
            failVerification("AndroidKeyStoreSpi: no return-object found in engineGetCertificateChain")
        }
        val pairs = PAIRED_HOOK.findAll(body).toList()
        if (returnCount == 3) {
            // The upstream shape: one populated return between two null returns.
            if (hookCount != 1) {
                failVerification("AndroidKeyStoreSpi: expected 1 CertificateChainIfNeeded hooks, found $hookCount")
            }
            if (pairs.size != hookCount) {
                failVerification("AndroidKeyStoreSpi: dataflow mismatch; hook must directly dominate its return")
            }
            val nullReg = returns[0]
            val array = pairs[0].groups["array"]!!.value
            if (returns != listOf(nullReg, array, nullReg) ||
                !Regex("const/4\\s+${Regex.escape(nullReg)},\\s*0x0\\b")
                    .containsMatchIn(body.substring(0, pairs[0].range.first))
            ) {
                failVerification("AndroidKeyStoreSpi: unsupported null-return layout")
            }
            return
        }
        if (hookCount == 0) {
            failVerification("AndroidKeyStoreSpi: expected CertificateChainIfNeeded hooks, found 0")
        }
        if (pairs.size != hookCount) {
            failVerification("AndroidKeyStoreSpi: dataflow mismatch; hook must directly dominate its return")
        }
        // Mirror of patchCertificateChain's acceptance for AOSP-shaped methods: any return
        // not covered by a paired hook must be provably null.
        val nullConsts = Regex("const/4\\s+([vp]\\d+),\\s*0x0\\b")
            .findAll(body.substring(0, pairs.first().range.first))
            .map { it.groupValues[1] }.toSet()
        val pairRanges = pairs.map { it.range }
        for (ret in RETURN_OBJECT.findAll(body)) {
            val inPair = pairRanges.any { it.first <= ret.range.first && ret.range.last <= it.last }
            if (!inPair && ret.groupValues[1] !in nullConsts) {
                failVerification("AndroidKeyStoreSpi: unsupported return layout")
            }
        }
    }

    /**
     * The leaf must carry the exact delegation block: chain call, null/empty checks routed to
     * a single shared stock label, and `aget-object` of the first element. Debug directives
     * are stripped first, exactly as upstream's `verify_target_content` does.
     */
    private fun verifyLeafDelegation(content: String) {
        val span = Smali.methodSpanByAnchor(content, LEAF_ANCHOR, "AndroidKeyStoreSpi")
        val body = LEAF_DEBUG_STRIP.replace(content.substring(span.start, span.endExclusive), "")
        if (!LEAF_SEQUENCE.containsMatchIn(body)) {
            failVerification(
                "AndroidKeyStoreSpi: single certificate must delegate to the chain with stock fallback"
            )
        }
    }

    private val LEAF_DEBUG_STRIP =
        Regex("(?m)^[ \\t]*\\.(?:line|local|end local|restart local|param)[^\\n]*\\n")

    private val LEAF_SEQUENCE = Regex(
        "invoke-virtual/range \\{p0 \\.\\. p1\\}, Landroid/security/keystore2/AndroidKeyStoreSpi;->engineGetCertificateChain" +
            "\\(Ljava/lang/String;\\)\\[Ljava/security/cert/Certificate;\\s+" +
            "move-result-object v0\\s+if-eqz v0, (?<label>:[\\w]+)\\s+" +
            "array-length v1, v0\\s+if-eqz v1, \\k<label>\\s+" +
            "const/4 v1, 0x0\\s+aget-object v0, v0, v1\\s+return-object v0\\s+" +
            "\\k<label>"
    )

    private val PAIRED_HOOK = Regex(
        "aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+" +
            DEBUG_GAP +
            "invoke-static(?:/range)?\\s*\\{\\k<array>(?:\\s*\\.\\.\\s*\\k<array>)?\\},\\s*" +
            HOOK_PATTERN +
            DEBUG_GAP +
            "move-result-object\\s+\\k<array>" +
            DEBUG_GAP +
            "return-object\\s+\\k<array>"
    )
}

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
        val oldCount = match.groupValues[2].toInt()

        // No-growth: reuse the highest existing local as the hook's scratch. At method entry
        // every local is dead, and the template itself parks the result in v14 of a
        // `.registers 16` frame (v15 = p0). Growing the frame shifts every parameter slot
        // and can push p0 past v15, which the register-encoding gate then refuses.
        val vTarget: String
        val p0Num: Int
        var newCount = oldCount
        if (directive == "registers") {
            // Locals are v0 … v(oldCount-2); p0 sits at physical v(oldCount-1).
            val existingLocals = oldCount - 1
            if (existingLocals < 1) {
                // Zero locals: grow by one so there is a scratch slot. Params still fit
                // (p0 moves to v1, well under v15), so the encoding gate stays green.
                newCount = oldCount + 1
                vTarget = "v$oldCount"
                p0Num = newCount - 1
            } else {
                vTarget = "v${oldCount - 2}"
                p0Num = oldCount - 1
            }
        } else {
            // Locals are v0 … v(oldCount-1); p0 sits at physical v(oldCount).
            if (oldCount < 1) {
                newCount = oldCount + 1
                vTarget = "v$oldCount"
                p0Num = newCount
            } else {
                vTarget = "v${oldCount - 1}"
                p0Num = oldCount
            }
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
            ?: throw UnsupportedLayoutException("register directive vanished")
        val directiveLine =
            if (newCount == oldCount) ".$directive $oldCount" else ".$directive $newCount"
        val newBody = methodBody.substring(0, finalMatch.range.first) +
            directiveLine + inject + methodBody.substring(finalMatch.range.last + 1)

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

    /**
     * Debug directives, blank lines and label lines the reference tolerates between
     * instructions. Labels matter on AOSP 16: baksmali can emit `:cond_…` between the
     * final `aput-object` and its `return-object`.
     */
    private val GAP =
        "(?:[ \\t]*\\.(?:line|local|end local|restart local)[^\\n]*\\n|[ \\t]*\\n|[ \\t]*:[^\\n]*\\n)*"

    /** `aput-object arr…, arr, …` directly followed by `return-object arr`. */
    private val APUT_RETURN = Regex(
        "(?<aput>aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+)" +
            "(?<gap>$GAP)" +
            "[ \\t]*(?<ret>return-object\\s+\\k<array>)"
    )

    /**
     * The same shape with a `move-object moved, arr` between the aput and the return.
     * The hook still targets the array before the copy; the return register is the moved
     * one, so the injection lands on the value the method actually returns.
     */
    private val APUT_MOVE_RETURN = Regex(
        "(?<aput>aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+)" +
            "(?<gap1>$GAP)" +
            "[ \\t]*move-object\\s+(?<moved>[vp]\\d+),\\s*\\k<array>" +
            "(?<gap2>$GAP)" +
            "[ \\t]*(?<ret>return-object\\s+\\k<moved>)"
    )

    private val RETURN_OBJECT = Regex("\\breturn-object\\s+([vp]\\d+)")

    /** The hook signature escaped for embedding in a pattern. */
    private const val HOOK_PATTERN =
        "Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded" +
            "\\(\\[Ljava/security/cert/Certificate;\\)\\[Ljava/security/cert/Certificate;"

    /**
     * Debug directives, blank lines and label lines the reference tolerates between
     * instructions, verbatim from upstream (`debug_gap` / the `pairs` gaps in
     * `verify_target_content`), plus labels so AOSP 16's `:cond_…` hops are accepted.
     */
    private const val DEBUG_GAP =
        "(?:\\s*\\.(?:line|local|end local|restart local)[^\\n]*\\n|\\s*\\n|\\s*:[^\\n]*\\n)*\\s*"

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
        // AOSP 16 can route the final array through a `move-object` before the
        // return, so both the direct and the moved shapes are accepted.
        var matches: List<HookPoint> =
            (APUT_RETURN.findAll(methodBody).toList() + APUT_MOVE_RETURN.findAll(methodBody).toList())
                .sortedBy { it.range.first }
                .map { m ->
                    HookPoint(
                        aputStart = m.range.first,
                        retText = m.groups["ret"]!!.value,
                        retStart = m.groups["ret"]!!.range.first,
                    )
                }
        if (matches.isEmpty()) {
            // R8/D8 codegen on some A16 builds inserts instructions the exact regexes
            // cannot express; the line scan tolerates any content between aput and return.
            matches = lenientFind(methodBody)
        }
        if (matches.isEmpty()) {
            throw UnsupportedLayoutException(
                "engineGetCertificateChain: leaf-array return not found (${shapeDiagnostics(methodBody)})"
            )
        }
        val returns = RETURN_OBJECT.findAll(methodBody).map { it.groupValues[1] }.toList()
        if (returns.size == 3 && matches.size == 1) {
            val nullReg = returns[0]
            // The middle return is what the method actually returns: the array itself on
            // the direct shape, or the `move-object` destination on the A16 moved shape.
            val retReg = matches[0].retReg
            if (returns != listOf(nullReg, retReg, nullReg) ||
                !Regex("const/4\\s+${Regex.escape(nullReg)},\\s*0x0\\b")
                    .containsMatchIn(methodBody.substring(0, matches[0].aputStart))
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
                .findAll(methodBody.substring(0, matches.first().aputStart))
                .map { it.groupValues[1] }.toSet()
            val hooked = matches.map { it.retStart }.toSet()
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
            // The hook targets the register the method returns: the array itself on the
            // direct shape, or the `move-object` destination on the A16 moved shape.
            val retReg = match.retReg
            val physical = if (retReg.startsWith("p")) {
                paramBase + retReg.substring(1).toInt()
            } else {
                retReg.substring(1).toInt()
            }
            val invoke = if (physical > 15) {
                "invoke-static/range {$retReg .. $retReg}, $HOOK_SIGNATURE"
            } else {
                "invoke-static {$retReg}, $HOOK_SIGNATURE"
            }
            val inject = "$invoke\n    move-result-object $retReg\n    "
            val retStart = match.retStart
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
            .ifEmpty { PAIRED_HOOK_LENIENT.findAll(body).toList() }
        if (returnCount == 3) {
            // The upstream shape: one populated return between two null returns.
            if (hookCount != 1) {
                failVerification("AndroidKeyStoreSpi: expected 1 CertificateChainIfNeeded hooks, found $hookCount")
            }
            if (pairs.size != hookCount) {
                failVerification("AndroidKeyStoreSpi: dataflow mismatch; hook must directly dominate its return")
            }
            val nullReg = returns[0]
            // The hooked return register, not the aput array: on the A16 moved shape the
            // method returns the `move-object` destination, which differs from the array.
            val retReg = pairs[0].groups["target"]!!.value
            if (returns != listOf(nullReg, retReg, nullReg) ||
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

    /**
     * The hook paired with its aput and return, tolerating the A16 `move-object` hop.
     * The hook may target the array itself or the moved copy — either way the register
     * it writes back is the one the method returns.
     */
    private val PAIRED_HOOK = Regex(
        "aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+" +
            DEBUG_GAP +
            "(?:move-object\\s+(?<moved>[vp]\\d+),\\s*\\k<array>" + DEBUG_GAP + ")?" +
            "invoke-static(?:/range)?\\s*\\{(?<target>[vp]\\d+)(?:\\s*\\.\\.\\s*\\k<target>)?\\},\\s*" +
            HOOK_PATTERN +
            DEBUG_GAP +
            "move-result-object\\s+\\k<target>" +
            DEBUG_GAP +
            "return-object\\s+\\k<target>"
    )

    /**
     * Lenient variant used only when the strict pattern finds no pairs: allows arbitrary
     * instructions between the aput and the invoke (R8/D8 codegen variations). The
     * invoke → move-result → return triplet stays strict so a hook that does not
     * directly dominate its return is still rejected.
     */
    private val PAIRED_HOOK_LENIENT = Regex(
        "aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+" +
            "(?:[^\\n]*\\n){0,15}?[ \\t]*" +
            "invoke-static(?:/range)?\\s*\\{(?<target>[vp]\\d+)(?:\\s*\\.\\.\\s*\\k<target>)?\\},\\s*" +
            HOOK_PATTERN +
            DEBUG_GAP +
            "move-result-object\\s+\\k<target>" +
            DEBUG_GAP +
            "return-object\\s+\\k<target>"
    )

    /**
     * A hook injection point located by either the exact regex or the lenient line scan:
     * the position of the `aput-object` and the `return-object` it precedes.
     */
    private data class HookPoint(val aputStart: Int, val retText: String, val retStart: Int) {
        val retReg: String get() = retText.substringAfterLast(' ').trim()
    }

    /**
     * Line-based fallback for R8/D8 codegen variations the exact regexes cannot express:
     * scan backward from each `return-object` to the nearest `aput-object` into the same
     * register (following `move-object` hops), tolerating any instructions in between.
     * Stops at `.end method` and at another `return-object` so a match never spans a
     * control-flow boundary.
     */
    private fun lenientFind(methodBody: String): List<HookPoint> {
        val lines = Smali.splitKeepEnds(methodBody)
        val offsets = IntArray(lines.size)
        var off = 0
        for (i in lines.indices) {
            offsets[i] = off
            off += lines[i].length
        }
        val aputRe = Regex("aput-object\\s+[vp]\\d+,\\s*([vp]\\d+),\\s*[vp]\\d+")
        val moveRe = Regex("move-object(?:/from16)?\\s+([vp]\\d+),\\s*([vp]\\d+)")
        val points = mutableListOf<HookPoint>()
        for (i in lines.indices) {
            val retMatch = RETURN_OBJECT.find(lines[i]) ?: continue
            val retReg = retMatch.groupValues[1]
            var target = retReg
            for (j in i - 1 downTo maxOf(0, i - 30)) {
                val line = lines[j]
                if (line.contains(".end method")) break
                if (RETURN_OBJECT.containsMatchIn(line)) break
                val aput = aputRe.find(line)
                if (aput != null && aput.groupValues[1] == target) {
                    points.add(
                        HookPoint(
                            aputStart = offsets[j] + aput.range.first,
                            retText = retMatch.value,
                            retStart = offsets[i] + retMatch.range.first,
                        )
                    )
                    break
                }
                val move = moveRe.find(line)
                if (move != null && move.groupValues[1] == target) {
                    target = move.groupValues[2]
                }
            }
        }
        return points
    }

    /**
     * Concise shape summary for the failure message: how many aput / return instructions
     * the method carries and where they sit, so an unmatched ROM reports what it actually
     * looks like instead of just "not found".
     */
    private fun shapeDiagnostics(methodBody: String): String {
        val aputs = Regex("aput-object\\s+[vp]\\d+,\\s*([vp]\\d+),\\s*[vp]\\d+")
            .findAll(methodBody).map { it.groupValues[1] }.toList()
        val returns = RETURN_OBJECT.findAll(methodBody)
            .map { it.groupValues[1] }.toList()
        return "aput=${aputs.size}(${aputs.joinToString(",")}) ret=${returns.size}(${returns.joinToString(",")})"
    }
}

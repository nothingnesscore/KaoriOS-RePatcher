package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.Smali.RegisterDirective
import dev.kaorios.engine.smali.Span
import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * ComputerEngine.shouldFilterApplication - hidden-app list hook.
 *
 * Ported from `patch-services-a17.py`. Supports the Android 17 7-parameter overload
 * and the older 3-parameter one, with a high-register fallback when the 4-byte
 * invoke format cannot address the hook operands.
 */
object ComputerEnginePatch {

    const val HOOK_TARGET =
        "Landroid/security/kaorios/KaoriosHook;->shouldHideAppListForCaller(ILjava/lang/String;I)Z"
    const val GET_PACKAGE_NAME_CALL =
        "invoke-interface {p1}, Lcom/android/server/pm/pkg/PackageStateInternal;->getPackageName()Ljava/lang/String;"
    const val RESERVED_LABEL = ":cond_kaorios_ps_null"

    private val METHOD_7_PARAM = Regex(
        "(?m)^\\.method[^\\r\\n]*[ \\t]shouldFilterApplication" +
            "\\(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ\\)Z" +
            "[ \\t]*(?:\\r?\\n|$)"
    )
    private val METHOD_3_PARAM = Regex(
        "(?m)^\\.method[^\\r\\n]*[ \\t]shouldFilterApplication" +
            "\\(Lcom/android/server/pm/pkg/PackageStateInternal;II\\)Z" +
            "[ \\t]*(?:\\r?\\n|$)"
    )

    private data class MethodInfo(val span: Span, val paramCount: Int)

    private fun methodSpan(text: String): MethodInfo {
        val m7 = METHOD_7_PARAM.findAll(text).toList()
        if (m7.size == 1) {
            val end = Smali.END_METHOD.find(text, m7[0].range.last + 1)
                ?: throw UnsupportedLayoutException("unterminated ComputerEngine.shouldFilterApplication (7-param)")
            return MethodInfo(Span(m7[0].range.first, end.range.last + 1), 7)
        }
        val m3 = METHOD_3_PARAM.findAll(text).toList()
        if (m3.size == 1) {
            val end = Smali.END_METHOD.find(text, m3[0].range.last + 1)
                ?: throw UnsupportedLayoutException("unterminated ComputerEngine.shouldFilterApplication (3-param)")
            return MethodInfo(Span(m3[0].range.first, end.range.last + 1), 3)
        }
        if (m7.size > 1 || m3.size > 1) {
            throw UnsupportedLayoutException(
                "ambiguous shouldFilterApplication: found ${m7.size} (7-param) and ${m3.size} (3-param)"
            )
        }
        throw UnsupportedLayoutException("target ComputerEngine.shouldFilterApplication method not found")
    }

    private fun hookCount(body: String): Int = Smali.countOccurrences(body, HOOK_TARGET)

    private fun highHook(paramCount: Int, oldBase: Int, scratch: Int, label: String, newline: String): String {
        val width = if (paramCount == 7) 8 else 4
        val user = if (paramCount == 7) 5 else 3
        val objects = if (paramCount == 7) setOf(0, 1, 3) else setOf(0, 1)
        val lines = (0 until width).map { i ->
            val op = if (i in objects) "move-object/16" else "move/16"
            "$op v${oldBase + i}, p$i"
        }.toMutableList()
        lines += listOf(
            "if-eqz v${oldBase + 1}, $label",
            "invoke-interface/range {v${oldBase + 1} .. v${oldBase + 1}}, Lcom/android/server/pm/pkg/PackageStateInternal;->getPackageName()Ljava/lang/String;",
            "move-result-object v${scratch + 1}",
            "if-eqz v${scratch + 1}, $label",
            "move/16 v${scratch}, v${oldBase + 2}",
            "move/16 v${scratch + 2}, v${oldBase + user}",
            "invoke-static/range {v$scratch .. v${scratch + 2}}, $HOOK_TARGET",
            "move-result v$scratch",
            "if-eqz v$scratch, $label",
            "const/16 v$scratch, 0x1",
            "return v$scratch",
            label
        )
        return lines.joinToString("") { "    $it$newline" }
    }

    /** Offset just past the `.registers`/`.locals`, `.param` and `.annotation` headers. */
    private fun findInjectionPoint(body: String): Int {
        val lines = Smali.splitKeepEnds(body)
        var injectedIdx = 0
        var inAnnotation = false
        for (i in lines.indices) {
            val stripped = lines[i].trim()
            if (stripped.isEmpty()) continue
            when {
                stripped.startsWith(".method") -> {}
                stripped.startsWith(".registers") || stripped.startsWith(".locals") -> injectedIdx = i + 1
                stripped.startsWith(".param") -> injectedIdx = i + 1
                stripped.startsWith(".annotation") -> {
                    inAnnotation = true
                    injectedIdx = i + 1
                }
                inAnnotation -> {
                    injectedIdx = i + 1
                    if (stripped.startsWith(".end annotation")) inAnnotation = false
                }
                else -> break
            }
        }
        return lines.take(injectedIdx).sumOf { it.length }
    }

    private fun verifyHighRegister(body: String, paramCount: Int) {
        val directive = Smali.findRegisterDirective(body)
            ?: throw UnsupportedLayoutException("missing registers for high-register hook")
        val width = if (paramCount == 7) 8 else 4
        val localsCount = directive.count - (if (directive.kind == RegisterDirective.Kind.REGISTERS) width else 0)
        val scratch = localsCount - 3
        val oldBase = scratch - width
        val labelMatch = Regex("if-eqz\\s+v${oldBase + 1},\\s*(:\\S+)").find(body)
        if (labelMatch == null || oldBase < 0 || scratch + 2 > 255) {
            throw UnsupportedLayoutException("invalid high-register hook frame")
        }
        val expected = highHook(paramCount, oldBase, scratch, labelMatch.groupValues[1], "\n")
        fun compact(value: String) =
            value.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() }
        if (!compact(body).contains(compact(expected))) {
            throw UnsupportedLayoutException("incorrect high-register caller/package/user or return sequence")
        }
    }

    fun verify(text: String) {
        visibilityVerify(text)
        if (INSTALLER_API.containsMatchIn(text)) {
            InstallerSourcePatch.verify(text)
        }
    }

    private fun visibilityVerify(text: String) {
        val info = methodSpan(text)
        val body = text.substring(info.span.start, info.span.endExclusive)
        val stripped = DIRECTIVE_STRIP.replace(body, "")
        val count = hookCount(stripped)
        if (count != 1) {
            failVerification("expected exactly one shouldHideAppListForCaller hook; found $count")
        }
        if (!body.contains(GET_PACKAGE_NAME_CALL)) {
            verifyHighRegister(body, info.paramCount)
            return
        }
        val userParam = if (info.paramCount == 7) "p5" else "p3"
        val pattern = Regex(
            "if-eqz\\s+p1,\\s*(:\\S+)\\s*(?:\\r?\\n)+\\s*" + Regex.escape(GET_PACKAGE_NAME_CALL) +
                "\\s*(?:\\r?\\n)+\\s*move-result-object\\s+(v\\d+)\\s*(?:\\r?\\n)+" +
                "\\s*if-eqz\\s+\\2,\\s*\\1\\s*(?:\\r?\\n)+\\s*" +
                "invoke-static\\s*\\{p2,\\s*\\2,\\s*$userParam\\},\\s*" + Regex.escape(HOOK_TARGET) +
                "\\s*(?:\\r?\\n)+\\s*move-result\\s+(v\\d+)\\s*(?:\\r?\\n)+" +
                "\\s*if-eqz\\s+\\3,\\s*\\1\\s*(?:\\r?\\n)+\\s*const/4\\s+\\3,\\s*0x1\\s*(?:\\r?\\n)+" +
                "\\s*return\\s+\\3\\s*(?:\\r?\\n)+\\s*\\1"
        )
        if (!pattern.containsMatchIn(body)) {
            failVerification("hook sequence does not match exact fail-closed return structure or register order")
        }
    }

    private val DIRECTIVE_STRIP =
        Regex("(?m)^[ \\t]*\\.(?:line|local|end local|restart local|prologue|epilogue)\\b[^\\n]*\\n")

    internal fun patchVisibility(text: String): PatchOutcome {
        val info = methodSpan(text)
        val body = text.substring(info.span.start, info.span.endExclusive)
        val count = hookCount(body)
        if (count == 1) {
            visibilityVerify(text)
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, text)
        }
        if (count > 1) {
            throw UnsupportedLayoutException("multiple shouldHideAppListForCaller hooks already present")
        }
        if (body.contains(RESERVED_LABEL)) {
            throw UnsupportedLayoutException("reserved Kaorios label already exists in target method")
        }

        val newline = Smali.newlineOf(text)
        val userParam = if (info.paramCount == 7) "p5" else "p3"
        val paramWidth = if (info.paramCount == 7) 8 else 4

        val original = Smali.findRegisterDirective(body)
        val hookReg: String
        var updatedBody: String
        when {
            original != null && original.kind == RegisterDirective.Kind.LOCALS -> {
                val currentLocs = original.count
                // Parameters sit at v(currentLocs…); growing the locals shifts them, so their
                // numeric aliases become pN first — otherwise a stock `const/4 v0` reads the
                // freshly added local instead of the parameter it meant.
                val canonicalized = Smali.canonicalizeParamAliases(
                    body, currentLocs + paramWidth, paramWidth
                )
                hookReg = "v$currentLocs"
                val directive = Smali.findRegisterDirective(canonicalized)
                    ?: throw UnsupportedLayoutException(".locals directive vanished during canonicalization")
                updatedBody = canonicalized.replaceRange(
                    directive.span.start, directive.span.endExclusive,
                    "${directive.indent}.locals ${currentLocs + 1}$newline"
                )
            }
            original != null -> {
                val currentRegs = original.count
                val existingLocals = currentRegs - paramWidth
                if (existingLocals < 0) {
                    throw UnsupportedLayoutException(".registers $currentRegs is less than parameter count $paramWidth")
                }
                val rewritten = Smali.canonicalizeParamAliases(body, currentRegs, paramWidth)
                hookReg = "v$existingLocals"
                val directive = Smali.findRegisterDirective(rewritten)
                    ?: throw UnsupportedLayoutException(".registers directive vanished during canonicalization")
                updatedBody = rewritten.replaceRange(
                    directive.span.start, directive.span.endExclusive,
                    "${directive.indent}.locals ${existingLocals + 1}$newline"
                )
            }
            else -> {
                hookReg = "v0"
                val lineEnd = body.indexOf('\n').let { if (it < 0) body.length else it + 1 }
                updatedBody = body.substring(0, lineEnd) + "    .locals 1$newline" + body.substring(lineEnd)
            }
        }

        // The threshold counts the *highest* parameter slot, not the hook's own operand: after
        // canonicalisation the stock body addresses every parameter as pN, and each of those
        // physical registers must still fit a 4-bit operand — `new_locals + param_width - 1`
        // is exactly p(param_width-1). The reference uses the same bound.
        val newLocals = Smali.findRegisterDirective(updatedBody)?.count ?: 1
        if (maxOf(newLocals + paramWidth - 1, hookReg.substring(1).toInt()) > 15) {
            return highRegisterPatch(text, info, body, original, paramWidth, newline)
        }

        val injOffset = findInjectionPoint(updatedBody)
        val hookCode = "    if-eqz p1, $RESERVED_LABEL$newline" +
            "    $GET_PACKAGE_NAME_CALL$newline" +
            "    move-result-object $hookReg$newline" +
            "    if-eqz $hookReg, $RESERVED_LABEL$newline" +
            "    invoke-static {p2, $hookReg, $userParam}, $HOOK_TARGET$newline" +
            "    move-result $hookReg$newline" +
            "    if-eqz $hookReg, $RESERVED_LABEL$newline" +
            "    const/4 $hookReg, 0x1$newline" +
            "    return $hookReg$newline" +
            "    $RESERVED_LABEL$newline"

        val patchedBody = updatedBody.substring(0, injOffset) + hookCode + updatedBody.substring(injOffset)
        val patched = text.substring(0, info.span.start) + patchedBody + text.substring(info.span.endExclusive)
        visibilityVerify(patched)
        return PatchOutcome(PatchStatus.PATCHED, patched)
    }

    private fun highRegisterPatch(
        text: String,
        info: MethodInfo,
        body: String,
        original: RegisterDirective?,
        paramWidth: Int,
        newline: String
    ): PatchOutcome {
        val oldCount = original?.count
            ?: throw UnsupportedLayoutException("missing registers for high-register hook")
        val oldBase = if (original.kind == RegisterDirective.Kind.LOCALS) oldCount else oldCount - paramWidth
        val scratch = oldBase + paramWidth
        if (scratch + 2 > 255) {
            throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: high-register scratch exceeds 8-bit limits")
        }

        var updatedBody = buildString {
            Smali.splitKeepEnds(body).forEach { line ->
                if (line.trim().startsWith(".param")) {
                    append(line)
                } else {
                    append(
                        Smali.transformOutside(line, SPLIT_SAFE) { outside ->
                            PARAM_ALIAS.replace(outside) { m -> "v${oldBase + m.groupValues[1].toInt()}" }
                        }
                    )
                }
            }
        }

        val directive = Smali.findRegisterDirective(updatedBody)
            ?: throw UnsupportedLayoutException("missing registers for high-register hook")
        updatedBody = updatedBody.replaceRange(
            directive.span.start, directive.span.endExclusive,
            "    .locals ${scratch + 3}$newline"
        )
        val injOffset = findInjectionPoint(updatedBody)
        val hookCode = highHook(info.paramCount, oldBase, scratch, RESERVED_LABEL, newline)
        val patchedBody = updatedBody.substring(0, injOffset) + hookCode + updatedBody.substring(injOffset)
        val patched = text.substring(0, info.span.start) + patchedBody + text.substring(info.span.endExclusive)
        visibilityVerify(patched)
        return PatchOutcome(PatchStatus.PATCHED, patched)
    }

    private val SPLIT_SAFE = Regex("\"(?:\\\\.|[^\"\\\\])*\"|#[^\\n]*")
    private val PARAM_ALIAS = Regex("(?<![\\w/\$;>:])p(\\d+)(?![\\w/\$;])")

    private val INSTALLER_API = Regex(
        "(?m)^\\.method[^\\n]*\\b(?:getInstallerPackageName|getInstallSourceInfo)\\("
    )

    /** The reference delegates to the installer-source patcher when those read APIs exist. */
    fun patch(text: String): PatchOutcome {
        val visibility = patchVisibility(text)
        if (!INSTALLER_API.containsMatchIn(text)) return visibility
        return InstallerSourcePatch.patch(visibility.content)
    }
}
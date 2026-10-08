package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.MethodRewrite
import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * `Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.1.md` — Optional patches §1, Hide developer/ADB status.
 *
 * The guide's one optional patch without a Python counterpart: upstream ships no `patch-*.py` for
 * it, so this port is written against the guide text and its linked example
 * (`Template_V2060/a17/framework/Settings$NameValueCache.smali`) rather than differentially
 * tested, and the conformance fixtures live in [NameValueCachePatchTest].
 *
 * `Settings$NameValueCache.getStringForUser` is the choke point every `Settings.Secure` /
 * `Settings.Global` read goes through, so the guide injects at the method entry: when the name is
 * non-null, ask the runtime whether this read should be hidden, and answer the stock value for
 * `adb_enabled` / `development_settings_enabled` (`"0"`) when it says so; otherwise fall through
 * untouched. No local is live at method entry, so the six-line block can use `v0` outright — but
 * only while the method actually *has* a local: under `.registers N` with four parameter words
 * that means `N >= 5`, and a method with zero locals is rejected instead of aliasing `v0` onto
 * `p0`. The runtime dex defines the hook with exactly this descriptor (probe-verified against the
 * release payload), so the injected call site resolves at boot.
 *
 * The whole section is optional: the class carrying no `getStringForUser` reports
 * [PatchStatus.NOT_TARGET] and leaves the file byte-identical, the same skippable-site
 * vocabulary CorePatch §3 and DSV's third site use.
 */
object NameValueCachePatch {

    const val HOOK_TARGET =
        "Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatusFromNameValueCache" +
            "(Landroid/content/ContentResolver;Ljava/lang/String;I)Z"

    /** The guide's target method, exactly as §1 spells it. */
    const val SIGNATURE =
        "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;"

    private const val LABEL = "Settings\$NameValueCache"

    /** The guide's label, uniquified per method body the way the reference's labels are. */
    private const val LABEL_BASE = ":kaorios_dev_stock"

    private val CLASS_RE = Regex(
        "(?m)^\\.class .*Landroid/provider/Settings\\\$NameValueCache;[ \\t]*\\r?$"
    )

    /**
     * Lines that may sit between the method declaration and the injected block, stripped before
     * the sequence is matched: the block is written directly under the register directive, while
     * a re-disassembly of the patched dex floats `.param` (and any `.line`) back above it, so
     * both shapes have to read as "at entry".
     */
    private val HEADER_LINE = Regex(
        "(?m)^[ \\t]*\\.(?:method|registers|locals|param|line|local|end local|restart local|" +
            "prologue|epilogue)\\b[^\\n]*\\r?\\n?"
    )

    private val ANNOTATION_BLOCK = Regex(
        "(?m)^[ \\t]*\\.annotation\\b[^\\n]*\\r?\\n(?:.*\\r?\\n)*?[ \\t]*\\.end annotation[ \\t]*\\r?\\n?"
    )

    /** The guide's six instructions plus its label, with the verdict register captured. */
    private val BLOCK_SEQUENCE = Regex(
        "if-eqz\\s+p2,\\s*(?<label>:[\\w$]+)\\s+" +
            "invoke-static/range\\s+\\{p1 \\.\\. p3\\},\\s*" + Regex.escape(HOOK_TARGET) + "\\s+" +
            "move-result\\s+(?<scratch>v\\d+)\\s+" +
            "if-eqz\\s+\\k<scratch>,\\s*\\k<label>\\s+" +
            "const-string\\s+\\k<scratch>,\\s*\"0\"\\s+" +
            "return-object\\s+\\k<scratch>\\s+" +
            "\\k<label>\\b"
    )

    private fun requireClass(content: String) {
        if (!CLASS_RE.containsMatchIn(content)) {
            throw UnsupportedLayoutException(
                "$LABEL: expected .class Landroid/provider/Settings\$NameValueCache;"
            )
        }
    }

    /** Local registers the method owns — the slots `v0..` address, parameters excluded. */
    private fun localCount(body: String, paramWords: Int): Int {
        val directive = Smali.findRegisterDirective(body)
            ?: throw UnsupportedLayoutException("$LABEL: no .registers/.locals directive")
        return when (directive.kind) {
            Smali.RegisterDirective.Kind.REGISTERS -> directive.count - paramWords
            Smali.RegisterDirective.Kind.LOCALS -> directive.count
        }
    }

    fun patch(content: String): PatchOutcome {
        requireClass(content)
        val found = MethodRewrite.findMethodOrNull(content, SIGNATURE, LABEL)
            ?: return PatchOutcome(PatchStatus.NOT_TARGET, content)
        val body = found.bodyIn(content)

        val count = Smali.countOccurrences(body, HOOK_TARGET)
        if (count == 1) {
            verify(content)
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }
        if (count > 1) {
            throw UnsupportedLayoutException("$LABEL: multiple shouldHideDevStatus hooks already present")
        }

        val directive = Smali.findRegisterDirective(body)
            ?: throw UnsupportedLayoutException("$LABEL: no .registers/.locals directive")
        if (localCount(body, MethodRewrite.paramCount(found.header)) < 1) {
            throw UnsupportedLayoutException(
                "$LABEL: getStringForUser has no free local register for the hook verdict"
            )
        }

        val label = Smali.uniqueLabel(LABEL_BASE, body)
        val newline = Smali.newlineOf(content)
        val block = block(label).replace("\n", newline) + newline

        val patchedMethod = body.substring(0, directive.span.endExclusive) + block +
            body.substring(directive.span.endExclusive)
        val patched = content.replaceRange(found.span.start, found.span.endExclusive, patchedMethod)

        verify(patched)
        return PatchOutcome(PatchStatus.PATCHED, patched)
    }

    /** The guide's seven lines, indented for a method body. */
    private fun block(label: String): String = listOf(
        "if-eqz p2, $label",
        "invoke-static/range {p1 .. p3}, $HOOK_TARGET",
        "move-result v0",
        "if-eqz v0, $label",
        "const-string v0, \"0\"",
        "return-object v0",
        label,
    ).joinToString("\n") { "    $it" }

    fun verify(text: String) {
        requireClass(text)
        val found = MethodRewrite.findMethod(text, SIGNATURE, LABEL)
        val body = found.bodyIn(text)

        val count = Smali.countOccurrences(body, HOOK_TARGET)
        if (count != 1) {
            failVerification("$LABEL: expected exactly one shouldHideDevStatus hook; found $count")
        }
        if (Smali.countOccurrences(text, HOOK_TARGET) != 1) {
            failVerification("$LABEL: shouldHideDevStatus hook appears outside getStringForUser")
        }

        val locals = localCount(body, MethodRewrite.paramCount(found.header))
        val stripped = ANNOTATION_BLOCK.replace(HEADER_LINE.replace(body, ""), "")
        val match = BLOCK_SEQUENCE.find(stripped)
            ?: failVerification("$LABEL: hook block is not the guide's entry sequence")

        // `v0` is only a local while the method has one; a verdict register at or above the
        // local count lands in a parameter slot and would be read back as live data on the
        // fall-through path.
        val scratch = match.groups["scratch"]!!.value.removePrefix("v").toInt()
        if (scratch >= locals) {
            failVerification(
                "$LABEL: verdict register v$scratch aliases a parameter (only $locals local(s))"
            )
        }
        val prefix = stripped.substring(0, match.range.first)
        if (prefix.isNotBlank()) {
            failVerification("$LABEL: hook sits below stock logic in getStringForUser")
        }

        val label = match.groups["label"]!!.value
        val definitions = Regex("(?m)^[ \\t]*" + Regex.escape(label) + "[ \\t]*\\r?$")
            .findAll(stripped).count()
        if (definitions != 1) {
            failVerification("$LABEL: branch target $label appears $definitions times")
        }
    }
}

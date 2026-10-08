package dev.kaorios.engine.smali

/**
 * Text primitives shared by every patch target.
 *
 * All offsets are half-open `[start, endExclusive)` to match the Python reference
 * implementation, which is the behavioural specification this engine ports.
 */
object Smali {

    fun newlineOf(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

    fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    /**
     * Applies [transform] to the stretches of [line] that lie outside every match of [protected].
     *
     * Exists because the obvious spelling is wrong. Python's `re.split` interleaves capture
     * groups, so the reference implementation can alternate `if (i % 2 == 1) keep else
     * rewrite`; Kotlin's `Regex.split` drops them, which deletes the literal contents and
     * leaves a line such as `const-string v0, ` that no assembler will accept. Match and
     * rebuild instead of splitting.
     */
    fun transformOutside(line: String, protected: Regex, transform: (String) -> String): String {
        if (!protected.containsMatchIn(line)) return transform(line)
        val out = StringBuilder(line.length)
        var last = 0
        for (match in protected.findAll(line)) {
            if (match.range.first > last) {
                out.append(transform(line.substring(last, match.range.first)))
            }
            out.append(match.value)
            last = match.range.last + 1
        }
        if (last < line.length) {
            out.append(transform(line.substring(last)))
        }
        return out.toString()
    }

    /**
     * Splits into lines without terminators and without a trailing empty entry, matching
     * Python's `str.splitlines()`. Kotlin's `lines()` and `split("\n")` both keep a
     * trailing empty element, which silently adds a newline when the result is rejoined.
     */
    fun splitLines(text: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] != '\n' && text[j] != '\r') j++
            result.add(text.substring(i, j))
            if (j >= text.length) break
            i = if (text[j] == '\r' && j + 1 < text.length && text[j + 1] == '\n') j + 2 else j + 1
        }
        return result
    }

    /**
     * Splits into lines while keeping each line's terminator, so summing the first N
     * entries yields the character offset of line N. Mirrors Python's
     * `str.splitlines(keepends=True)`, which the reference uses to compute insert points.
     */
    fun splitKeepEnds(text: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] != '\n' && text[j] != '\r') j++
            if (j < text.length) {
                if (text[j] == '\r' && j + 1 < text.length && text[j + 1] == '\n') j += 2 else j++
            }
            result.add(text.substring(i, j))
            i = j
        }
        return result
    }

    /** Returns [base] unless it is already a bare label in [methodBody], else `base_<hash>`. */
    fun uniqueLabel(base: String, methodBody: String): String {
        var candidate = base
        var suffix = sha256Hex4(methodBody)
        // Re-tested per candidate, exactly like the reference's `_unique_label`: caching the
        // pattern for `base` would loop forever once the candidate gains a suffix.
        while (Regex("(?m)^\\s*" + Regex.escape(candidate) + "\\s*$").containsMatchIn(methodBody)) {
            candidate = "${base}_$suffix"
            suffix = sha256Hex4(methodBody + suffix)
        }
        return candidate
    }

    /** First four hex characters of SHA-256 — the suffix the reference's `_unique_label` uses. */
    private fun sha256Hex4(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(4)

    /**
     * Rewrites `v(R-P+N)` parameter aliases to `pN` so that growing `.registers`
     * does not silently move the physical parameter slots.
     */
    fun canonicalizeParamAliases(methodBody: String, registers: Int, paramCount: Int): String {
        var body = methodBody
        val firstParamV = registers - paramCount
        for (n in 0 until paramCount) {
            val vName = "v${firstParamV + n}"
            val pName = "p$n"
            body = Regex("(?<![A-Za-z0-9_])${Regex.escape(vName)}(?![0-9])").replace(body, pName)
        }
        return body
    }

    /**
     * Span of the method whose declaration line ends with [methodAnchor], exclusive of `.end method`.
     *
     * Anchored at `^\.method` with the signature at end of line, mirroring upstream's
     * `_extract_method_body`: a bare `indexOf` also matches invoke *references* to the
     * signature, which can resolve to a different method (or none) than the one meant.
     */
    fun methodSpanByAnchor(text: String, methodAnchor: String, label: String): Span {
        val anchor = Regex(
            "(?m)^\\.method[^\\n]* " + Regex.escape(methodAnchor) + "[ \\t]*$"
        ).find(text)
            ?: throw UnsupportedLayoutException("$label: method anchor '$methodAnchor' not found")
        val end = text.indexOf(".end method", anchor.range.first)
        if (end < 0) throw UnsupportedLayoutException("$label: unterminated method at '$methodAnchor'")
        return Span(anchor.range.first, end)
    }

    /** Span of the single method matching [regex], inclusive of its `.end method` line. */
    fun methodSpanByRegex(text: String, regex: Regex, label: String): Span {
        val matches = regex.findAll(text).toList()
        if (matches.size != 1) {
            throw UnsupportedLayoutException(
                if (matches.isEmpty()) "$label: target method not found"
                else "$label: ambiguous target, found ${matches.size} matches"
            )
        }
        // Search forward from the *start* of the match. Resuming after the match's own
        // `.end method` lands between that line's `d` and its terminator, where `^` cannot
        // match, so the scan picks up the *next* method's terminator and the span silently
        // grows by one whole method.
        val end = END_METHOD.find(text, matches[0].range.first)
            ?: throw UnsupportedLayoutException("$label: unterminated $label method")
        return Span(matches[0].range.first, end.range.last + 1)
    }

    internal val END_METHOD = Regex("(?m)^[ \\t]*\\.end method[ \\t]*(?:\\r?\\n|$)")
    internal val REGISTERS = Regex("(?m)^(?<indent>[ \\t]*)\\.registers[ \\t]+(?<num>\\d+)[ \\t]*(?:\\r?\\n|$)")
    internal val LOCALS = Regex("(?m)^(?<indent>[ \\t]*)\\.locals[ \\t]+(?<num>\\d+)[ \\t]*(?:\\r?\\n|$)")

    /** A method's `.registers`/`.locals` directive, if it has one. */
    data class RegisterDirective(val kind: Kind, val count: Int, val indent: String, val span: Span) {
        enum class Kind { REGISTERS, LOCALS }
    }

    fun findRegisterDirective(body: String): RegisterDirective? {
        val loc = LOCALS.find(body)
        val reg = REGISTERS.find(body)
        return when {
            loc != null -> RegisterDirective(
                RegisterDirective.Kind.LOCALS,
                loc.groups["num"]!!.value.toInt(),
                loc.groups["indent"]!!.value,
                Span(loc.range.first, loc.range.last + 1)
            )
            reg != null -> RegisterDirective(
                RegisterDirective.Kind.REGISTERS,
                reg.groups["num"]!!.value.toInt(),
                reg.groups["indent"]!!.value,
                Span(reg.range.first, reg.range.last + 1)
            )
            else -> null
        }
    }

    /** Physical register number for `vN`/`pN` given the method's local count. */
    fun physical(register: String, localsCount: Int): Int {
        val n = register.substring(1).toInt()
        return if (register.startsWith("p")) n + localsCount else n
    }
}
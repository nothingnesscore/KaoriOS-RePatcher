package dev.kaorios.engine.smali

/**
 * Locating a method by signature, and giving a patch a scratch register.
 *
 * Shared by the two doc-driven patch sets — `Disable_Secure_Flag.md` and `CorePatch.md` — which
 * rewrite a method's entry point and therefore need the same two non-obvious steps.
 *
 * Growing `.registers` only yields a *free* register if every reference to the parameter slots
 * already uses the `p` aliases, because the assembler re-derives where `p0` lives from the
 * register count. [Smali.canonicalizeParamAliases] rewrites those references first, which is why
 * the new scratch register is always the slot that used to be `p0`. Both halves of that argument
 * are asserted rather than assumed: a surviving raw reference means the rewrite missed one, and a
 * changed string literal means it rewrote the wrong thing entirely.
 */
object MethodRewrite {

    /** A method located by signature: its declaration line plus its full span. */
    data class Found(val header: String, val span: Span) {
        fun bodyIn(content: String): String = span.substringIn(content)
    }

    /** Matches the whole of a method whose declaration mentions [signature]. */
    fun regexFor(signature: String): Regex = Regex(
        "\\.method[^\\n]*?" + Regex.escape(signature) + ".*?\\.end method",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * The single method declaring [signature].
     *
     * Ambiguity and absence are both [UnsupportedLayoutException]: a patch that guessed which of
     * two overloads was meant would rewrite the wrong one, and both guides describe exactly one
     * target method.
     */
    fun findMethod(content: String, signature: String, label: String): Found =
        findMethodOrNull(content, signature, label)
            ?: throw UnsupportedLayoutException("$label: $signature method not found")

    /**
     * Like [findMethod], but `null` when the class declares no such method.
     *
     * For sites the guide marks skippable, so "this ROM does not carry the method" can be a
     * reported absence rather than a rejected layout. Ambiguity still throws — a class with two
     * equally-matching declarations is never an absence.
     */
    fun findMethodOrNull(content: String, signature: String, label: String): Found? {
        val matches = regexFor(signature).findAll(content).toList()
        if (matches.isEmpty()) return null
        val span = Smali.methodSpanByRegex(content, regexFor(signature), label)
        return Found(span.substringIn(content).substringBefore('\n'), span)
    }

    /** [findMethodOrNull] over alternative spellings of one method, e.g. two names for a type. */
    fun findFirstMethodOrNull(content: String, signatures: List<String>, label: String): Found? {
        for (signature in signatures) {
            findMethodOrNull(content, signature, label)?.let { return it }
        }
        return null
    }

    /**
     * Physical parameter slots: `this` plus each entry of the parameter descriptor, unless the
     * method is static.
     *
     * The descriptor is walked rather than split on separators because an array of `L…;` or `[I`
     * contains no separator of its own.
     */
    fun paramCount(header: String): Int {
        val tokens = header.trim().removePrefix(".method").trim().split(Regex("\\s+"))
        val nameIndex = tokens.indexOfFirst { it.contains('(') }
        val modifiers = if (nameIndex >= 0) tokens.subList(0, nameIndex) else emptyList()
        val starts = if ("static" in modifiers) 0 else 1

        val descriptor = header.substringAfter('(').substringBefore(')')
        var i = 0
        var total = starts
        while (i < descriptor.length) {
            while (i < descriptor.length && descriptor[i] == '[') i++
            if (i >= descriptor.length) throw UnsupportedLayoutException("malformed descriptor: $header")
            total++
            if (descriptor[i] == 'L') {
                val end = descriptor.indexOf(';', i)
                if (end < 0) throw UnsupportedLayoutException("unterminated type descriptor: $header")
                i = end + 1
            } else {
                i++
            }
        }
        return total
    }

    /** Result of [growRegisters]: the rewritten method and the register that was freed. */
    data class Grown(val method: String, val scratch: String, val scratchNum: Int)

    /**
     * Grows the method's register directive by one and canonicalises its parameter aliases.
     *
     * Works for both directive kinds: under `.registers N` the freed slot is the first parameter,
     * under `.locals N` it is `vN`, which is the first parameter and not a local in either count —
     * locals never move, so anything annotated with `.local` keeps meaning what it meant.
     */
    fun growRegisters(method: String, header: String, label: String): Grown {
        val directive = Smali.findRegisterDirective(method)
            ?: throw UnsupportedLayoutException("$label: no .registers/.locals directive")
        val params = paramCount(header)

        val canonicalRegisters = when (directive.kind) {
            Smali.RegisterDirective.Kind.REGISTERS -> directive.count
            Smali.RegisterDirective.Kind.LOCALS -> directive.count + params
        }
        val literalsBefore = STRING_LITERAL.findAll(method).map { it.value }.toList()
        val canonicalized = Smali.canonicalizeParamAliases(method, canonicalRegisters, params)
        val literalsAfter = STRING_LITERAL.findAll(canonicalized).map { it.value }.toList()
        if (literalsBefore != literalsAfter) {
            throw UnsupportedLayoutException("$label: canonicalising parameters altered a string literal")
        }

        val paramFirst = canonicalRegisters - params
        for (n in 0 until params) {
            val raw = "v${paramFirst + n}"
            if (RAW_REGISTER(raw).containsMatchIn(canonicalized)) {
                throw UnsupportedLayoutException("$label: $raw is still addressed as a raw register")
            }
        }

        val fresh = Smali.findRegisterDirective(canonicalized)
            ?: throw UnsupportedLayoutException("$label: register directive vanished while canonicalising")
        val scratchNum = when (fresh.kind) {
            Smali.RegisterDirective.Kind.REGISTERS -> fresh.count - params
            Smali.RegisterDirective.Kind.LOCALS -> fresh.count
        }
        if (scratchNum !in 0..255) {
            throw UnsupportedLayoutException("$label: no usable scratch register (v$scratchNum)")
        }

        val matched = fresh.span.substringIn(canonicalized)
        val terminator = when {
            matched.endsWith("\r\n") -> "\r\n"
            matched.endsWith("\n") -> "\n"
            else -> ""
        }
        val kindName = if (fresh.kind == Smali.RegisterDirective.Kind.REGISTERS) "registers" else "locals"
        val replacement = fresh.indent + ".$kindName ${fresh.count + 1}" + terminator
        val grown = canonicalized.substring(0, fresh.span.start) + replacement +
            canonicalized.substring(fresh.span.endExclusive)
        return Grown(grown, "v$scratchNum", scratchNum)
    }

    /**
     * Splices [inject] directly after the register directive.
     *
     * `PackageManagerPatch` already ships this shape — instructions before `.param` assemble,
     * because `DexArchiveRoundTrip.rebuild` is the assembler and a build that rejected them threw
     * on device — and both guides specify "below `.registers X`" for the same reason: no local is
     * live yet at method entry.
     */
    fun injectAtEntry(
        method: String,
        header: String,
        label: String,
        inject: (scratch: String) -> String,
    ): Pair<String, String> {
        val grown = growRegisters(method, header, label)
        val directive = Smali.findRegisterDirective(grown.method)
            ?: throw UnsupportedLayoutException("$label: register directive vanished while growing")
        val text = inject(grown.scratch).trimEnd('\n')
        val rewritten = grown.method.substring(0, directive.span.endExclusive) + text + "\n" +
            grown.method.substring(directive.span.endExclusive)
        return rewritten to grown.scratch
    }

    /**
     * Splices [inject] immediately after the line holding [anchor] inside the method.
     *
     * Used where the guide's insertion point is mid-body rather than at the entry: the register
     * directive still grows, but the code lands where the stock value it overrides was produced.
     */
    fun injectAfterAnchor(
        method: String,
        header: String,
        label: String,
        anchor: Regex,
        inject: (scratch: String) -> String,
    ): Pair<String, String> {
        val grown = growRegisters(method, header, label)
        val match = anchor.find(grown.method)
            ?: throw UnsupportedLayoutException("$label: insertion anchor not found")
        val endOfLine = grown.method.indexOf('\n', match.range.last)
        if (endOfLine < 0) throw UnsupportedLayoutException("$label: anchor is not followed by a line break")
        val text = inject(grown.scratch).trimEnd('\n')
        val rewritten = grown.method.substring(0, endOfLine + 1) + text + "\n" +
            grown.method.substring(endOfLine + 1)
        return rewritten to grown.scratch
    }

    /**
     * `const/4` reaches only `v0`–`v15`, so a scratch register above that needs the 32-bit form.
     * Both guides only ever force `0` or `1`, which neither form fails to represent.
     */
    fun constInstruction(register: String, value: Int): String {
        val num = register.substring(1).toInt()
        require(value in 0..1) { "the guides only force 0 or 1, got $value" }
        return if (num <= 15) "const/4 $register, 0x$value" else "const $register, 0x$value"
    }

    private val STRING_LITERAL = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
    private fun RAW_REGISTER(name: String) =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![0-9])")
}

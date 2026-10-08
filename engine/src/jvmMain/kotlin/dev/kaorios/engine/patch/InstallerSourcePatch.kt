package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * Installer-source filtering inside ComputerEngine's installer read APIs.
 *
 * Ported from `patch-installer-source.py`. The patcher only accepts methods whose stock
 * instructions provably derive the installer name from the AOSP source read: it runs a small
 * forward dataflow analysis over the method's control-flow graph, tracking provenance tags
 * for each register, and refuses any layout where the installer value could come from
 * anywhere other than `getInstallSource`.
 *
 * One extension beyond the reference: a `const-string` literal keeps a `string` tag instead
 * of collapsing to `unknown`, and `string` is allowed alongside `installer`/`null` at the
 * InstallSourceInfo constructor's installing argument (and at non-modern installer returns).
 * AOSP-15 ROMs rewrite the installer name to a literal on one path (aurora store → Play
 * Store); the join merges that literal with the installer provenance, which would otherwise
 * poison the slot. The check still requires `installer` to be present on some path, so a
 * slot that is only ever a literal — or only ever unknown — is refused as before.
 */
object InstallerSourcePatch {

    const val HOOK =
        "Landroid/security/kaorios/KaoriosHook;->filterInstallerPackageName(Landroid/content/ContentResolver;IILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
    private const val INIT = "Landroid/content/pm/InstallSourceInfo;-><init>"

    private val SIGNATURES = setOf(
        "getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;",
        "getInstallerPackageName(Ljava/lang/String;I)Ljava/lang/String;",
        "getInstallSourceInfo(Ljava/lang/String;)Landroid/content/pm/InstallSourceInfo;",
        "getInstallSourceInfo(Ljava/lang/String;I)Landroid/content/pm/InstallSourceInfo;"
    )

    private val METHOD_RE =
        Regex("(?m)^\\.method (?<header>[^\\n]+)\\n(?<body>.*?)^\\.end method", setOf(RegexOption.DOT_MATCHES_ALL))
    private val CLASS_RE = Regex("(?m)^\\s*\\.class [^\\n]*Lcom/android/server/pm/ComputerEngine;\\s*$")
    private val REGISTER_INVOCATION = Regex("\\b[vp]\\d+\\b")
    private val INSTALLER_FIELD =
        Regex("Lcom/android/server/pm/InstallSource;->(?:mInstallerPackageName|installerPackageName):Ljava/lang/String;")
    private val NULL_CONST = Regex(",\\s*0x0$")
    private val SKIPPED_DIRECTIVE = Regex("(?m)^\\s*\\.(?:line|local|end local|restart local|param|prologue|epilogue)\\b")

    private val UNKNOWN = setOf("unknown")
    private val RECEIVER = setOf("receiver")
    private val TARGET = setOf("target")
    private val USER = setOf("user")
    private val UID = setOf("uid")
    private val SOURCE = setOf("source")
    private val INSTALLER = setOf("installer")
    private val NULL = setOf("null")
    private val INFO = setOf("info")
    private val STRING = setOf("string")

    private val SUPPORTED_CONSTRUCTORS = mapOf(
        "(Ljava/lang/String;Landroid/content/pm/SigningInfo;Ljava/lang/String;Ljava/lang/String;I)V" to 6,
        "(Ljava/lang/String;Landroid/content/pm/SigningInfo;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V" to 7
    )

    private val NON_WRITE_PREFIXES = listOf(
        "if-", "return", "throw", "iput", "sput", "aput", "monitor-", ":", "goto", "check-cast"
    )

    private data class Line(val index: Int, val instruction: String)

    private data class Method(val header: String, val body: String, val start: Int, val endExclusive: Int) {
        val modern: Boolean get() = header.split(" ").last().startsWith("getInstallSourceInfo(")
        val width: Int get() = if (header.contains("Ljava/lang/String;I)")) 3 else 2
    }

    private fun methodOf(match: MatchResult): Method = Method(
        match.groups["header"]!!.value,
        match.groups["body"]!!.value,
        match.range.first,
        match.range.last + 1
    )

    /** Executable instructions with their line numbers, excluding directives and comments. */
    private fun executable(body: String): List<Line> =
        Smali.splitLines(body).mapIndexedNotNull { index, raw ->
            val instruction = raw.substringBefore('#').trim()
            if (instruction.isEmpty() || instruction.startsWith(".")) null else Line(index, instruction)
        }

    /**
     * Character offset where the first executable line begins.
     *
     * `Line.index` is a line number and cannot be used with `substring`. Assumes LF, which
     * [dev.kaorios.engine.dex.DexRoundTrip] guarantees by normalising disassembly output.
     */
    private fun firstExecutableOffset(body: String): Int? {
        var offset = 0
        for (raw in Smali.splitLines(body)) {
            val instruction = raw.substringBefore('#').trim()
            if (instruction.isNotEmpty() && !instruction.startsWith(".")) return offset
            offset += raw.length + 1
        }
        return null
    }

    /** Expands an invoke argument list, including `a..b` ranges. */
    private fun registers(invocation: String): List<String> {
        val open = invocation.indexOf('{')
        val close = invocation.indexOf('}', open + 1)
        if (open < 0 || close < 0) return emptyList()
        val raw = invocation.substring(open + 1, close).trim()
        if (raw.isEmpty()) return emptyList()
        if (raw.contains("..")) {
            val ends = raw.split("..").map { it.trim() }
            if (ends[0][0] != ends[1][0]) {
                throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: mixed register range")
            }
            val prefix = ends[0][0]
            val from = ends[0].drop(1).toInt()
            val to = ends[1].drop(1).toInt()
            return (from..to).map { "$prefix$it" }
        }
        return raw.split(",").map { it.trim() }
    }

    /**
     * Forward dataflow proving where the installer value comes from. Returns the code
     * indices that hand the installer name (or install-source info) back to the caller.
     */
    private fun stockSites(body: String, base: Int, width: Int, modern: Boolean): Map<Int, String> {
        val code = executable(body)
        // Worklist entries index `code`, so a label must resolve to its position in the
        // filtered list. `Line.index` is the *body* line number used to splice the hook back
        // in, which is a different coordinate system and can exceed code.size.
        val labels = code.mapIndexedNotNull { position, line ->
            if (line.instruction.startsWith(":")) line.instruction to position else null
        }.toMap()
        fun labelAt(reference: String): Int = labels[reference]
            ?: throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: branch to unknown label $reference")
        if (code.any { it.instruction.contains("switch") || it.instruction.startsWith("fill-array") || it.instruction.startsWith(".catch") } ||
            body.contains(".catch")
        ) {
            throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: exceptional/switch control flow")
        }

        val initial = mutableMapOf<String, Set<String>>("v$base" to RECEIVER, "v${base + 1}" to TARGET)
        if (width == 3) initial["v${base + 2}"] = USER

        val states = mutableMapOf<Int, Map<String, Set<String>>>(0 to initial)
        val queue = ArrayDeque(listOf(0))
        val sites = mutableMapOf<Int, String>()
        val cleared = mutableSetOf<Int>()

        while (queue.isNotEmpty()) {
            val n = queue.removeLast()
            val env = states[n]!!.toMutableMap()
            val line = code[n]
            val instruction = line.instruction
            val regs = REGISTER_INVOCATION.findAll(instruction.substringBefore(", L")).map { it.value }.toList()
            fun value(reg: String): Set<String> = env[reg] ?: UNKNOWN

            when {
                instruction.startsWith("invoke-") -> {
                    val args = registers(instruction)
                    val close = instruction.indexOf("},")
                    val target = if (close < 0) "" else instruction.substring(close + 2).trim()
                    env["\$result"] = UNKNOWN
                    when {
                        target == "Landroid/os/Binder;->getCallingUid()I" -> {
                            if (cleared.contains(n)) {
                                throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: UID read after identity clear")
                            }
                            env["\$result"] = UID
                        }
                        target == "Landroid/os/Binder;->clearCallingIdentity()J" -> cleared.add(n)
                        target.startsWith("Lcom/android/server/pm/ComputerEngine;->getInstallSource(") -> {
                            val expected = listOf(RECEIVER, TARGET, UID) + if (width == 3) listOf(USER) else emptyList()
                            if (args.size != expected.size ||
                                args.zip(expected).any { (r, tag) -> value(r) != tag }
                            ) {
                                throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: stock source caller/target/user provenance")
                            }
                            env["\$result"] = SOURCE
                        }
                        target.startsWith(INIT) -> {
                            val descriptor = target.substring(INIT.length)
                            if (!modern || SUPPORTED_CONSTRUCTORS[descriptor] != args.size) {
                                throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: InstallSourceInfo constructor")
                            }
                            val tag = value(args[4])
                            if (!tag.contains("installer") ||
                                !tag.all { it == "installer" || it == "null" || it == "string" }
                            ) {
                                throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: installing argument is not stock installer")
                            }
                            sites[line.index] = args[4]
                            val receiver = value(args[0])
                            if (receiver.size != 1 || !receiver.first().startsWith("new:")) {
                                throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: constructor receiver")
                            }
                            for ((register, tags) in env.toList()) {
                                if (tags == receiver) env[register] = INFO
                            }
                        }
                    }
                }
                instruction.startsWith("move-result") -> env[regs[0]] = env.remove("\$result") ?: UNKNOWN
                instruction.startsWith("move") -> env[regs[0]] = value(regs[1])
                instruction.startsWith("iget-object") && INSTALLER_FIELD.containsMatchIn(instruction) -> {
                    if (value(regs[1]) != SOURCE) {
                        throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: installer field source")
                    }
                    env[regs[0]] = INSTALLER
                }
                instruction.startsWith("new-instance") -> env[regs[0]] = setOf("new:${line.index}")
                instruction.startsWith("const") -> env[regs[0]] = when {
                    NULL_CONST.containsMatchIn(instruction) -> NULL
                    // String literals keep their own tag: ROMs that rewrite the installer
                    // name to a literal on some path (AOSP 15's aurora-store → vending
                    // block) merge it back with the installer provenance at the join.
                    instruction.startsWith("const-string") -> STRING
                    else -> UNKNOWN
                }
                instruction.startsWith("return-object") -> {
                    val tags = value(regs[0])
                    if (modern) {
                        if (!tags.all { it == "null" || it == "info" }) {
                            throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: unverified InstallSourceInfo return")
                        }
                    } else if (tags.all { it == "installer" || it == "null" || it == "string" } &&
                        tags.contains("installer")
                    ) {
                        sites[line.index] = regs[0]
                    } else if (tags != NULL) {
                        throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: unverified installer return")
                    }
                }
                regs.isNotEmpty() && NON_WRITE_PREFIXES.none { instruction.startsWith(it) } -> env[regs[0]] = UNKNOWN
            }

            val successors = mutableListOf<Int>()
            when {
                instruction.startsWith("return") || instruction.startsWith("throw") -> {}
                instruction.startsWith("goto") ->
                    successors += labelAt(instruction.split(" ").last())
                else -> {
                    if (n + 1 < code.size) successors += n + 1
                    if (instruction.startsWith("if-")) {
                        successors += labelAt(instruction.split(",").last().trim())
                    }
                }
            }

            for (successor in successors) {
                val previous = states[successor]
                val merged: Map<String, Set<String>> = if (previous == null) {
                    env
                } else {
                    val keys = previous.keys + env.keys
                    keys.associateWith { key ->
                        (previous[key] ?: UNKNOWN) + (env[key] ?: UNKNOWN)
                    }
                }
                if (previous != merged) {
                    states[successor] = merged
                    queue.addLast(successor)
                }
            }
        }

        if (sites.isEmpty() || (modern && sites.size != 1)) {
            throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: expected proven installer return/constructor")
        }
        return sites
    }

    /**
     * Rewrites `pN` to its physical `v{base+N}` slot so the hook can use fresh locals
     * without shifting any stock operand.
     */
    private fun normalize(method: Method): Triple<String, Int, Int> {
        if (method.header.split(" ").contains("static")) {
            throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: static API")
        }
        val width = method.width
        val matches = DIRECTIVE_RE.findAll(method.body).toList()
        if (matches.size != 1) throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: register directive")
        val count = matches[0].groupValues[2].toInt()
        val base = if (matches[0].groupValues[1] == "locals") count else count - width
        val total = base + width
        if (base < 0 || total + 4 > 255) {
            throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: register limits")
        }

        val rewritten = Smali.splitLines(method.body).joinToString("\n") { line ->
            if (line.trim().startsWith(".param")) {
                line
            } else {
                val instruction = line.substringBefore("#")
                val comment = line.substringAfter("#", "")
                val rebuilt = Smali.transformOutside(instruction, QUOTED) { outside ->
                    PARAM_ALIAS.replace(outside) { m -> "v${base + m.groupValues[1].toInt()}" }
                }
                rebuilt + (if (comment.isEmpty()) "" else "#$comment")
            }
        } + "\n"
        return Triple(rewritten, base, width)
    }

    private val DIRECTIVE_RE = Regex("(?m)^\\s*\\.(locals|registers)\\s+(\\d+)\\s*$")
    private val QUOTED = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
    private val PARAM_ALIAS = Regex("\\bp(\\d+)\\b")
    private val LOCALS_DIRECTIVE = Regex("(?m)^\\s*\\.locals\\s+\\d+\\s*$")

    private fun hookBlock(scratch: Int, real: String): String =
        "    move-object/16 v${scratch + 4}, $real\n" +
            "    invoke-static/range {v$scratch .. v${scratch + 4}}, $HOOK\n" +
            "    move-result-object $real\n"

    private fun prologue(base: Int, width: Int, scratch: Int): String {
        var text = (0 until width).joinToString("") { i ->
            val op = if (i < 2) "move-object/16" else "move/16"
            "    $op v${base + i}, p$i\n"
        }
        text += "    const/16 v$scratch, 0x0\n"
        text += "    invoke-static {}, Landroid/os/Binder;->getCallingUid()I\n"
        text += "    move-result v${scratch + 1}\n"
        text += "    move-object/16 v${scratch + 3}, v${base + 1}\n"
        text += if (width == 3) {
            "    move/16 v${scratch + 2}, v${base + 2}\n"
        } else {
            "    invoke-static/range {v${scratch + 1} .. v${scratch + 1}}, Landroid/os/UserHandle;->getUserId(I)I\n" +
                "    move-result v${scratch + 2}\n"
        }
        return text
    }

    private fun patchMethod(method: Method): String {
        val (normalized, base, width) = normalize(method)
        val scratch = base + width
        val sites = stockSites(normalized, base, width, method.modern)

        val lines = Smali.splitKeepEnds(normalized).toMutableList()
        for ((lineNo, real) in sites.entries.sortedByDescending { it.key }) {
            lines.add(lineNo, hookBlock(scratch, real))
        }
        var body = lines.joinToString("")

        val directive = DIRECTIVE_RE.find(body)
            ?: throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: register directive")
        body = body.substring(0, directive.range.first) + "    .locals ${scratch + 5}\n" +
            body.substring(directive.range.last + 1)

        val first = executable(body).firstOrNull()?.index
            ?: throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: no executable instructions")
        val entryLines = Smali.splitKeepEnds(body).toMutableList()
        entryLines.add(first, prologue(base, width, scratch))
        body = entryLines.joinToString("")

        return ".method ${method.header}\n$body.end method"
    }

    private fun targetMethods(text: String): List<Method> {
        if (!CLASS_RE.containsMatchIn(text)) {
            throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: expected ComputerEngine class")
        }
        val matches = METHOD_RE.findAll(text)
            .filter { it.groups["header"]!!.value.split(" ").last() in SIGNATURES }
            .toList()
        val names = matches.map { it.groups["header"]!!.value.split(" ").last().substringBefore("(") }.toSet()
        if (matches.size != 2 || names.size != 2) {
            throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: require both installer APIs exactly once")
        }
        return matches.map { methodOf(it) }
    }

    /** Normalizes formatting so two texts compare on structure rather than whitespace. */
    private fun canonical(text: String): String {
        var normalized = Smali.splitLines(text).mapNotNull { raw ->
            val value = raw.substringBefore('#').trim()
            if (value.isEmpty() || SKIPPED_DIRECTIVE.containsMatchIn(value)) {
                null
            } else if (value.startsWith(".method ") || value == ".end method") {
                value
            } else {
                "    $value"
            }
        }.joinToString("\n") + "\n"

        for (method in METHOD_RE.findAll(normalized).toList().asReversed()) {
            val width = if (method.groups["header"]!!.value.contains("Ljava/lang/String;I)")) 3 else 2
            val updated = Regex("(?m)^    \\.registers (\\d+)$").replace(method.value) { m ->
                "    .locals ${m.groupValues[1].toInt() - width}"
            }
            normalized = normalized.substring(0, method.range.first) + updated +
                normalized.substring(method.range.last + 1)
        }
        return normalized
    }

    fun verify(text: String) {
        val canonicalText = canonical(text)
        val targets = targetMethods(canonicalText)
        if (Smali.countOccurrences(canonicalText, HOOK) != targets.sumOf { Smali.countOccurrences(it.body, HOOK) }) {
            throw UnsupportedLayoutException("installer hook outside supported read APIs")
        }
        for (method in targets) {
            val width = method.width
            val directive = Regex("(?m)^\\s*\\.locals\\s+(\\d+)\\s*$").find(method.body)
                ?: throw UnsupportedLayoutException("missing allocated locals")
            val scratch = directive.groupValues[1].toInt() - 5
            val base = scratch - width
            val entry = prologue(base, width, scratch)
            if (Smali.countOccurrences(method.body, entry) != 1) {
                throw UnsupportedLayoutException("missing/altered original caller, target or user capture")
            }
            // `firstExecutableOffset` yields a character offset; the entry must open the executable
            // region, not merely exist somewhere in the method.
            val first = firstExecutableOffset(method.body)
                ?: throw UnsupportedLayoutException("UNSUPPORTED_LAYOUT: no executable instructions")
            if (!method.body.substring(first).startsWith(entry)) {
                throw UnsupportedLayoutException("caller capture must precede stock instructions and identity clear")
            }
            var stock = method.body.replaceFirst(entry, "")
            val pattern = Regex(
                "    move-object/16 v${scratch + 4}, ([vp]\\d+)\\n" +
                    "    invoke-static/range \\{v$scratch \\.\\. v${scratch + 4}\\}, " + Regex.escape(HOOK) + "\\n" +
                    "    move-result-object \\1\\n"
            )
            val blocks = pattern.findAll(stock).toList()
            if (blocks.isEmpty() || blocks.size != Smali.countOccurrences(stock, HOOK)) {
                throw UnsupportedLayoutException("partial or duplicate installer hook")
            }
            stock = pattern.replace(stock, "")
            // The reference replaces the first occurrence only; replacing every `.locals` line
            // would rewrite unrelated directives further down the method.
            stock = LOCALS_DIRECTIVE.replaceFirst(stock, "    .registers $scratch\n")
            val candidate = METHOD_RE.matchEntire(".method ${method.header}\n$stock.end method")
                ?: throw UnsupportedLayoutException("hook coverage/position/value differs from proven stock return paths")
            val expected = canonical(patchMethod(methodOf(candidate)))
            // Compared against the *original* method, not `candidate`: the check is
            // "strip the hook, re-patch, get the original text back". Comparing the
            // re-patched candidate with the stripped candidate can never agree, because
            // re-patching re-allocates the scratch locals.
            val actual = canonical(canonicalText.substring(method.start, method.endExclusive))
            if (expected != actual) {
                // The check is "strip the hook, re-patch, get the same text back". When it
                // fails the useful information is the first differing line, not the verdict.
                val a = Smali.splitLines(expected)
                val b = Smali.splitLines(actual)
                val at = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) } ?: -1
                System.err.println("[InstallerSourcePatch] fixed-point mismatch at line $at")
                System.err.println("  expected: ${a.getOrNull(at)}")
                System.err.println("  actual  : ${b.getOrNull(at)}")
                throw UnsupportedLayoutException("hook coverage/position/value differs from proven stock return paths")
            }
        }
    }

    fun patch(text: String): PatchOutcome {
        if (text.contains(HOOK)) {
            verify(text)
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, text)
        }
        var patched = text
        for (method in targetMethods(patched).asReversed()) {
            patched = patched.substring(0, method.start) + patchMethod(method) +
                patched.substring(method.endExclusive)
        }
        verify(patched)
        return PatchOutcome(PatchStatus.PATCHED, patched)
    }
}
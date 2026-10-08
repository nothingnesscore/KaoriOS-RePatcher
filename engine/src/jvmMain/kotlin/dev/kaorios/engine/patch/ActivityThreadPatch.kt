package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.Span
import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * ActivityThread.handleBindApplication(AppBindData)V - Kaorios process init hook.
 *
 * Ported from `patch-activitythread-a17.py`. The hook must dominate the
 * `mBoundApplication` assignment, and the operands must be provable entry-parameter
 * aliases, otherwise the layout is rejected.
 */
object ActivityThreadPatch {

    const val HOOK_TARGET =
        "Landroid/security/kaorios/KaoriosHook;->initActivityThread(Ljava/lang/Object;)V"

    private val METHOD_RE =
        Regex("(?m)^\\.method[^\\r\\n]*[ \\t]handleBindApplication\\(Landroid/app/ActivityThread\\\$AppBindData;\\)V[ \\t]*(?:\\r?\\n|$)")
    private val ASSIGNMENT_RE = Regex(
        "(?m)^(?<indent>[ \\t]*)iput-object[ \\t]+(?<data>[vp]\\d+),[ \\t]*(?<owner>[vp]\\d+),[ \\t]*" +
            "Landroid/app/ActivityThread;->mBoundApplication:Landroid/app/ActivityThread\\\$AppBindData;[ \\t]*(?:\\r?\\n|$)"
    )
    private val LABEL_DEF = Regex("^[ \\t]*(:[\\w$]+)[ \\t]*$")
    private val LABEL_DEF_EXACT = Regex(":[\\w$]+")
    private val REGISTER_DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)")
    private val MOVE_OBJECT =
        Regex("(?:move-object(?:/from16|/16)?)\\s+([vp]\\d+),\\s*([vp]\\d+)")

    private data class Anchor(
        val assignmentSpan: Span,
        val hookCall: String
    )

    private fun methodSpan(text: String): Span {
        val matches = METHOD_RE.findAll(text).toList()
        if (matches.size != 1) {
            throw UnsupportedLayoutException(
                "expected exactly one ActivityThread.handleBindApplication(AppBindData); found ${matches.size}"
            )
        }
        val end = Smali.END_METHOD.find(text, matches[0].range.last + 1)
            ?: throw UnsupportedLayoutException("unterminated ActivityThread.handleBindApplication")
        return Span(matches[0].range.first, end.range.last + 1)
    }

    private fun hookCount(body: String): Int = Smali.countOccurrences(body, HOOK_TARGET)

    private fun anchor(body: String): Anchor {
        val assignments = ASSIGNMENT_RE.findAll(body).toList()
        if (assignments.size != 1) {
            throw UnsupportedLayoutException(
                "expected exactly one mBoundApplication assignment; found ${assignments.size}"
            )
        }
        val assignment = assignments[0]
        if (Regex("(?m)^\\.method[^\\n]*\\bstatic\\b").containsMatchIn(body)) {
            throw UnsupportedLayoutException("unsupported static handleBindApplication")
        }
        val registers = REGISTER_DIRECTIVE.find(body)
            ?: throw UnsupportedLayoutException("unsupported ActivityThread register layout")
        val count = registers.groupValues[2].toInt()
        val base = if (registers.groupValues[1] == "registers") count - 2 else count
        if (base < 0) throw UnsupportedLayoutException("unsupported ActivityThread parameter count")

        fun phys(reg: String): Int {
            val n = reg.substring(1).toInt()
            return if (reg.startsWith("p")) n + base else n
        }

        val aliases = mutableMapOf(base to 0, base + 1 to 1)
        val prefix = body.substring(0, assignment.range.first)
        var initialMoves = true
        for (line in Smali.splitLines(prefix)) {
            val instruction = line.trim().substringBefore('#').trim()
            if (instruction.isEmpty() || instruction.startsWith(".")) continue
            val move = MOVE_OBJECT.matchEntire(instruction)
            if (initialMoves && move != null && phys(move.groupValues[2]) in aliases) {
                aliases[phys(move.groupValues[1])] = aliases.getValue(phys(move.groupValues[2]))
                continue
            }
            initialMoves = false
            if (WRITE_EXEMPT_PREFIXES.any { instruction.startsWith(it) }) continue
            val destination = DESTINATION.find(instruction)
            if (destination != null) {
                val written = phys(destination.groupValues[1])
                val wide = instruction.substringBefore(' ').let { opcode ->
                    opcode.contains("wide") || opcode.contains("long") || opcode.contains("double")
                }
                if (written in aliases || (wide && written + 1 in aliases)) {
                    throw UnsupportedLayoutException("unsupported ActivityThread alias overwritten before assignment")
                }
            }
        }
        if (aliases[phys(assignment.groups["data"]!!.value)] != 1 ||
            aliases[phys(assignment.groups["owner"]!!.value)] != 0
        ) {
            throw UnsupportedLayoutException("unsupported mBoundApplication operands are not proven entry parameter aliases")
        }

        val labels = Smali.splitLines(prefix)
            .mapNotNull { LABEL_DEF.matchEntire(it)?.groupValues?.get(1) }
            .toSet()
        val suffix = body.substring(assignment.range.last + 1)
        for (line in Smali.splitLines(suffix)) {
            val instruction = line.trim()
            if ((instruction.startsWith("if-") || instruction.startsWith("goto")) &&
                LABEL_REF.findAll(instruction).any { it.value in labels }
            ) {
                throw UnsupportedLayoutException("unsupported ActivityThread back edge into anchor prefix")
            }
            if (LABEL_DEF_EXACT.matches(instruction) && instruction in labels) {
                throw UnsupportedLayoutException("unsupported ActivityThread switch/back-edge label")
            }
        }

        val reg = assignment.groups["data"]!!.value
        val needsRange = phys(reg) > 15
        val opcode = if (needsRange) "invoke-static/range" else "invoke-static"
        val args = if (needsRange) "{$reg .. $reg}" else "{$reg}"
        val span = Span(assignment.range.first, assignment.range.last + 1)
        return Anchor(span, "$opcode $args, $HOOK_TARGET")
    }

    fun verify(text: String) {
        val span = methodSpan(text)
        val body = text.substring(span.start, span.endExclusive)
        if (hookCount(body) != 1) {
            failVerification("expected exactly one Object initActivityThread hook")
        }
        val a = anchor(body)
        val following = body.substring(a.assignmentSpan.endExclusive).trimStart()
        if (!following.startsWith(a.hookCall) ||
            following.substring(a.hookCall.length).lineSequence().first().trim().isNotEmpty()
        ) {
            failVerification("Object initActivityThread hook must immediately follow proven assignment")
        }
    }

    fun patch(text: String): PatchOutcome {
        val span = methodSpan(text)
        val body = text.substring(span.start, span.endExclusive)
        val count = hookCount(body)
        if (count == 1) {
            verify(text)
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, text)
        }
        if (count > 1) {
            throw UnsupportedLayoutException("multiple Object initActivityThread hooks already present")
        }
        val a = anchor(body)
        val newline = Smali.newlineOf(text)
        val indent = ASSIGNMENT_RE.find(body)!!.groups["indent"]!!.value
        val hook = "$indent${a.hookCall}$newline"
        val patchedBody = body.substring(0, a.assignmentSpan.endExclusive) + hook +
            body.substring(a.assignmentSpan.endExclusive)
        val patched = text.substring(0, span.start) + patchedBody + text.substring(span.endExclusive)
        verify(patched)
        return PatchOutcome(PatchStatus.PATCHED, patched)
    }
}

private val WRITE_EXEMPT_PREFIXES = listOf(
    ":", "invoke-", "iput", "sput", "aput", "if-", "goto", "return",
    "throw", "monitor-", "check-cast", "packed-switch", "sparse-switch", "fill-array-data"
)
private val DESTINATION = Regex("^\\S+\\s+([vp]\\d+)")
private val LABEL_REF = Regex(":[\\w$]+")
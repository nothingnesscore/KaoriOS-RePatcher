package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.Span
import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * SystemServer.run()V - Kaorios initSystemServer hook.
 *
 * Ported from `patch-systemserver-a17.py`. The hook must precede Looper.loop().
 */
object SystemServerPatch {

    const val HOOK_TARGET = "Landroid/security/kaorios/KaoriosHook;->initSystemServer()V"
    const val HOOK_CALL = "invoke-static {}, $HOOK_TARGET"
    const val ANCHOR_CALL = "invoke-static {}, Landroid/os/Looper;->loop()V"

    private val CLASS_RE = Regex("(?m)^\\.class\\s+.*Lcom/android/server/SystemServer;\\s*$")
    private val METHOD_RUN_RE =
        Regex("(?m)^\\.method\\s+(?:public\\s+|private\\s+|protected\\s+)?(?:final\\s+)?run\\(\\)V\\s*$")
    private val ANCHOR_LINE =
        Regex("(?m)^(?<indent>[ \\t]*)" + Regex.escape(ANCHOR_CALL) + "[ \\t]*(?:\\r?\\n|$)")

    private fun methodSpan(text: String): Span {
        if (!CLASS_RE.containsMatchIn(text)) {
            throw UnsupportedLayoutException("expected .class Lcom/android/server/SystemServer;")
        }
        val matches = METHOD_RUN_RE.findAll(text).toList()
        if (matches.isEmpty()) throw UnsupportedLayoutException("target SystemServer.run()V method not found")
        if (matches.size > 1) {
            throw UnsupportedLayoutException("ambiguous SystemServer.run()V: found ${matches.size} matches")
        }
        val end = Smali.END_METHOD.find(text, matches[0].range.last + 1)
            ?: throw UnsupportedLayoutException("unterminated SystemServer.run()V method")
        return Span(matches[0].range.first, end.range.last + 1)
    }

    fun verify(text: String) {
        val span = methodSpan(text)
        val body = text.substring(span.start, span.endExclusive)

        if (Smali.countOccurrences(text, HOOK_TARGET) != 1) {
            failVerification("expected exactly one initSystemServer hook in class")
        }
        if (!body.contains(HOOK_CALL)) {
            failVerification("expected exact $HOOK_CALL in run()V")
        }
        if (!body.contains(ANCHOR_CALL)) {
            failVerification("expected Looper.loop() in SystemServer.run()V")
        }
        val pattern = Regex(
            "\\s*" + Regex.escape(HOOK_CALL) +
                "\\s*(?:\\r?\\n)+" +
                "(?:(?!\\.end method).)*?" +
                "\\s*" + Regex.escape(ANCHOR_CALL),
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        if (!pattern.containsMatchIn(body)) {
            failVerification("hook must precede Looper.loop() in SystemServer.run()V")
        }
    }

    fun patch(text: String): PatchOutcome {
        val span = methodSpan(text)
        val body = text.substring(span.start, span.endExclusive)

        if (body.contains(HOOK_TARGET)) {
            verify(text)
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, text)
        }

        val anchors = ANCHOR_LINE.findAll(body).toList()
        if (anchors.isEmpty()) {
            throw UnsupportedLayoutException("anchor '$ANCHOR_CALL' not found in SystemServer.run()V")
        }
        if (anchors.size > 1) {
            throw UnsupportedLayoutException("ambiguous anchor: found ${anchors.size} Looper.loop() in SystemServer.run()V")
        }

        val match = anchors[0]
        val indent = match.groups["indent"]!!.value
        val injection = "$indent$HOOK_CALL\n\n"
        val newBody = body.substring(0, match.range.first) + injection + body.substring(match.range.first)
        val patched = text.substring(0, span.start) + newBody + text.substring(span.endExclusive)

        verify(patched)
        return PatchOutcome(PatchStatus.PATCHED, patched)
    }
}
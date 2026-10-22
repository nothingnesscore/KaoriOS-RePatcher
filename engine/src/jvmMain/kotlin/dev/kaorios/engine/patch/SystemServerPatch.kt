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

    /** Executable hook / anchor lines — line-anchored, comment text never counts (upstream `EXECUTABLE_*_RE`). */
    private val EXECUTABLE_HOOK =
        Regex("(?m)^[ \\t]*" + Regex.escape(HOOK_CALL) + "[ \\t]*(?:#.*)?$")
    private val EXECUTABLE_ANCHOR =
        Regex("(?m)^[ \\t]*" + Regex.escape(ANCHOR_CALL) + "[ \\t]*(?:#.*)?$")

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

        val totalHookCount = EXECUTABLE_HOOK.findAll(text).count()
        if (totalHookCount != 1) {
            failVerification("expected exactly one executable initSystemServer hook in class; found $totalHookCount")
        }
        val hooks = EXECUTABLE_HOOK.findAll(body).toList()
        if (hooks.isEmpty()) {
            failVerification("expected exact $HOOK_CALL in run()V")
        }
        val anchors = EXECUTABLE_ANCHOR.findAll(body).toList()
        if (anchors.isEmpty()) {
            failVerification("expected Looper.loop() in SystemServer.run()V")
        }
        if (hooks[0].range.first > anchors[0].range.first) {
            failVerification("hook must precede Looper.loop() in SystemServer.run()V")
        }
    }

    fun patch(text: String): PatchOutcome {
        val span = methodSpan(text)
        val body = text.substring(span.start, span.endExclusive)

        if (EXECUTABLE_HOOK.containsMatchIn(body)) {
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
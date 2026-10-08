package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.UnsupportedLayoutException

enum class PatchStatus {
    PATCHED,
    ALREADY_PATCHED,
    UNSUPPORTED_LAYOUT,
    FAILED,
    NOT_TARGET
}

/** Which set of targets a run should apply. */
enum class PatchMode(val id: String) {
    HOOKS("1"),
    BUILD_SPOOF("2"),
    ALL_IN_ONE("3"),
    /**
     * Every guide ported into this engine: the upstream mode tables plus
     * `Disable_Secure_Flag.md` and `CorePatch.md`.
     *
     * Kept separate from [ALL_IN_ONE] so the upstream modes stay byte-identical to the reference
     * they are differentially tested against, and so a misbehaving extra patch can be bisected
     * out by rebuilding with `ALL_IN_ONE`.
     */
    FULL("4");

    companion object {
        fun fromId(id: String): PatchMode? = entries.firstOrNull { it.id == id }
    }
}

/** Outcome of patching one file. [content] is the original text whenever nothing changed. */
data class PatchOutcome(
    val status: PatchStatus,
    val content: String,
    val error: String? = null
) {
    val changed: Boolean get() = status == PatchStatus.PATCHED
}

/** A patch function operating on a single smali file's text. */
typealias PatchFn = (String) -> PatchOutcome

/**
 * Applies [patchFn] to [original], mapping engine failures onto the status vocabulary.
 *
 * Each patch target verifies its own candidate output before returning
 * [PatchStatus.PATCHED], so any exception here means the original text stands.
 */
fun applyPatch(patchFn: PatchFn, original: String): PatchOutcome = try {
    patchFn(original)
} catch (e: UnsupportedLayoutException) {
    PatchOutcome(PatchStatus.UNSUPPORTED_LAYOUT, original, e.message)
} catch (e: PatchVerificationException) {
    PatchOutcome(PatchStatus.FAILED, original, e.message)
} catch (e: Exception) {
    PatchOutcome(PatchStatus.FAILED, original, e.message)
}

/** Signal that a verifier rejected candidate output. */
fun failVerification(message: String): Nothing = throw PatchVerificationException(message)
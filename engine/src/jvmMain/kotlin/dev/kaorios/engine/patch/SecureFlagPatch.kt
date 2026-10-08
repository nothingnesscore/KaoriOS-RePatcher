package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.MethodRewrite
import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * `Toolbox-docs/V2.0.3+/Disable_Secure_Flag.md` — the Secure Flag set, four sites in
 * `services.jar`.
 *
 * Every entry site gets the same prologue: ask the runtime whether the toggle is on, and when it
 * is, force the stock result to the value the guide names; otherwise fall through untouched.
 *
 * The guide writes `v0` throughout because that is the register its reference ROM used. The
 * register is derived from the method instead — [MethodRewrite] grows the register directive by
 * one and hands back the slot the parameters used to occupy — so the same instructions apply to a
 * method whose parameters sit anywhere. `captureDisplay`'s forced register comes from the
 * `move-result` that follows `notAllowCaptureDisplay`, for the same reason: on the reference ROM
 * that was `v0`, here it is `v5`, and forcing anything else would overwrite a live local.
 *
 * Sites 1 and 2 are required. Site 3 is the one the guide explicitly permits a ROM to lack
 * ("In some ROMs, this method may not find the blocking code, so it can be skipped"), so its
 * absence reports as [PatchStatus.NOT_TARGET] rather than rejecting the run.
 */
object SecureFlagPatch {

    const val HOOK_SIGNATURE = "Landroid/security/kaorios/KaoriosHook;->isSecureFlag()Z"
    private const val LABEL_BASE = ":cond_kaorios"

    private const val ALLOW = "isScreenCaptureAllowed(I)Z"
    private const val IS_SECURE = "isSecureLocked()Z"
    private const val SET_SECURE = "setSecureLocked(Z)V"

    /**
     * The guide spells both types `ScreenCapture`; this ROM's dex uses `ScreenCaptureInternal`.
     * Both are accepted so the site is found whichever spelling the ROM carries.
     */
    private val CAPTURE_DISPLAY = listOf(
        "captureDisplay(ILandroid/window/ScreenCaptureInternal\$CaptureArgs;" +
            "Landroid/window/ScreenCaptureInternal\$ScreenCaptureListener;)V",
        "captureDisplay(ILandroid/window/ScreenCapture\$CaptureArgs;" +
            "Landroid/window/ScreenCapture\$ScreenCaptureListener;)V",
    )

    private val NOT_ALLOW = Regex(
        "notAllowCaptureDisplay\\([^\\n]*\\)Z[ \\t]*\\r?\\n[ \\t\\r\\n]*" +
            "move-result[ \\t]+(?<verdict>v\\d+)"
    )

    private val METHOD_OPEN = Regex("(?m)^\\s*\\.(?:method|registers|locals|param|line)\\b[^\\n]*$")

    // ---- sites 1 and 2 -------------------------------------------------------

    /** Site 1 — `DevicePolicyCacheImpl.isScreenCaptureAllowed(I)Z` answers `true`. */
    fun patchDevicePolicyCache(content: String): PatchOutcome = single(
        content,
        label = "DevicePolicyCacheImpl",
        signature = ALLOW,
        force = { v -> listOf(MethodRewrite.constInstruction(v, 1), "return $v") },
    )

    /** Sites 2a/2b — `WindowState.isSecureLocked()Z` answers `false`, `setSecureLocked` no-ops. */
    fun patchWindowState(content: String): PatchOutcome {
        var text = content
        var changed = false

        val getter = rewrite(text, "WindowState", IS_SECURE) { v ->
            listOf(MethodRewrite.constInstruction(v, 0), "return $v")
        }
        if (getter is Site.Absent) throw UnsupportedLayoutException("WindowState: $IS_SECURE method not found")
        if (getter is Site.Rewritten) {
            text = getter.content
            changed = true
        }

        val setter = rewrite(text, "WindowState", SET_SECURE) { listOf("return-void") }
        if (setter is Site.Absent) throw UnsupportedLayoutException("WindowState: $SET_SECURE method not found")
        if (setter is Site.Rewritten) {
            text = setter.content
            changed = true
        }

        return PatchOutcome(if (changed) PatchStatus.PATCHED else PatchStatus.ALREADY_PATCHED, text)
    }

    /**
     * Site 3 — clear `notAllowCaptureDisplay`'s verdict before `captureDisplay` branches on it.
     *
     * The guide allows this site to be skipped when a ROM carries no blocking code to find, which
     * is the one absence here that does not reject the run.
     */
    fun patchCaptureDisplay(content: String): PatchOutcome {
        val label = "WindowManagerService.captureDisplay"
        val found = MethodRewrite.findFirstMethodOrNull(content, CAPTURE_DISPLAY, label)
            ?: return PatchOutcome(PatchStatus.NOT_TARGET, content)
        val method = found.bodyIn(content)
        if (method.contains(HOOK_SIGNATURE)) {
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }
        val verdict = NOT_ALLOW.find(method)?.groups?.get("verdict")?.value
            ?: return PatchOutcome(PatchStatus.NOT_TARGET, content)

        val branch = Smali.uniqueLabel(LABEL_BASE, method)
        val (patched, _) = MethodRewrite.injectAfterAnchor(method, found.header, label, NOT_ALLOW) { v ->
            block(v, branch, listOf(MethodRewrite.constInstruction(verdict, 0)))
        }
        return PatchOutcome(
            PatchStatus.PATCHED,
            content.replaceRange(found.span.start, found.span.endExclusive, patched),
        )
    }

    // ---- verification --------------------------------------------------------

    fun verifyDevicePolicyCache(content: String) = verifyEntry(
        content,
        "DevicePolicyCacheImpl",
        ALLOW,
        force = "const(?:/4)?\\s+\\k<scratch>,\\s*0x1\\s+return\\s+\\k<scratch>",
    )

    fun verifyWindowState(content: String) {
        verifyEntry(
            content,
            "WindowState",
            IS_SECURE,
            force = "const(?:/4)?\\s+\\k<scratch>,\\s*0x0\\s+return\\s+\\k<scratch>",
        )
        verifyEntry(content, "WindowState", SET_SECURE, force = "return-void")
    }

    fun verifyCaptureDisplay(content: String) {
        val label = "WindowManagerService.captureDisplay"
        val found = MethodRewrite.findFirstMethodOrNull(content, CAPTURE_DISPLAY, label)
            ?: failVerification("$label: method not found")
        val body = found.bodyIn(content)
        if (Smali.countOccurrences(body, HOOK_SIGNATURE) != 1) {
            failVerification("$label: expected exactly one isSecureFlag call")
        }
        val match = CAPTURE_SEQUENCE.find(body)
            ?: failVerification("$label: isSecureFlag is not injected between the verdict and its branch")
        verifyUniqueBranch(body, match.groups["branch"]!!.value, label)
    }

    /**
     * The guide's entry sequence — hook, force, then the stock body — with [force] supplying the
     * middle. The hook must be the first thing after the register directive: no local is live yet
     * there, so the forced return cannot have overwritten anything.
     */
    private fun verifyEntry(content: String, label: String, signature: String, force: String) {
        val found = MethodRewrite.findMethod(content, signature, label)
        val body = found.bodyIn(content)
        if (Smali.countOccurrences(body, HOOK_SIGNATURE) != 1) {
            failVerification("$label: expected exactly one isSecureFlag call in $signature")
        }
        val match = Regex(
            "invoke-static\\s*\\{\\},\\s*" + Regex.escape(HOOK_SIGNATURE) + "\\s+" +
                "move-result\\s+(?<scratch>v\\d+)\\s+" +
                "if-eqz\\s+\\k<scratch>,\\s*(?<branch>:[\\w$]+)\\s+" +
                force + "\\s+" +
                "(?:\\.line\\s+\\d+\\s+)*" +
                "\\k<branch>\\b"
        ).find(body) ?: failVerification("$label: isSecureFlag control flow is not the guide's")
        verifyUniqueBranch(body, match.groups["branch"]!!.value, label)
        val prefix = METHOD_OPEN.replace(body.substring(0, match.range.first), "")
        if (prefix.isNotBlank()) {
            failVerification("$label: isSecureFlag is below stock logic in $signature")
        }
    }

    /**
     * The site 3 block, which sits mid-body rather than at the entry, followed by the stock
     * `if-eqz` it was written to neutralise — checking that too proves the insertion did not
     * land somewhere it would have eaten the control flow it is meant to influence.
     */
    private val CAPTURE_SEQUENCE = Regex(
        "notAllowCaptureDisplay\\([^\\n]*\\)Z\\s+" +
            "move-result\\s+(?<verdict>v\\d+)\\s+" +
            "invoke-static\\s*\\{\\},\\s*" + Regex.escape(HOOK_SIGNATURE) + "\\s+" +
            "move-result\\s+(?<scratch>v\\d+)\\s+" +
            "if-eqz\\s+\\k<scratch>,\\s*(?<branch>:[\\w$]+)\\s+" +
            "const(?:/4)?\\s+\\k<verdict>,\\s*0x0\\s+" +
            "\\k<branch>\\s+" +
            "if-eqz\\s+\\k<verdict>\\b"
    )

    private fun verifyUniqueBranch(body: String, branch: String, label: String) {
        val occurrences = Regex("(?m)^\\s*" + Regex.escape(branch) + "\\s*$").findAll(body).count()
        if (occurrences != 1) {
            failVerification("$label: branch target $branch appears $occurrences times")
        }
    }

    // ---- shared machinery ----------------------------------------------------

    /** Outcome of looking for one guide method in one class. */
    private sealed interface Site {
        /** The class declares no such method. */
        object Absent : Site

        /** The method already carried the hook. */
        object Already : Site

        /** The hook was injected. */
        data class Rewritten(val content: String) : Site
    }

    /** [single] over one class that must declare [signature]. */
    private fun rewrite(
        content: String,
        label: String,
        signature: String,
        force: (scratch: String) -> List<String>,
    ): Site {
        val found = MethodRewrite.findMethodOrNull(content, signature, label)
            ?: return Site.Absent
        val method = found.bodyIn(content)
        if (method.contains(HOOK_SIGNATURE)) return Site.Already

        val branch = Smali.uniqueLabel(LABEL_BASE, method)
        val (patched, _) = MethodRewrite.injectAtEntry(method, found.header, label) { v ->
            block(v, branch, force(v))
        }
        return Site.Rewritten(content.replaceRange(found.span.start, found.span.endExclusive, patched))
    }

    private fun single(
        content: String,
        label: String,
        signature: String,
        force: (scratch: String) -> List<String>,
    ): PatchOutcome = when (val site = rewrite(content, label, signature, force)) {
        Site.Absent -> throw UnsupportedLayoutException("$label: $signature method not found")
        Site.Already -> PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        is Site.Rewritten -> PatchOutcome(PatchStatus.PATCHED, site.content)
    }

    /** The guide's six-line block, indented for a method body. */
    private fun block(scratch: String, branch: String, force: List<String>): String =
        (listOf(
            "invoke-static {}, $HOOK_SIGNATURE",
            "move-result $scratch",
            "if-eqz $scratch, $branch",
        ) + force + branch)
            .joinToString("\n") { "    $it" }
}

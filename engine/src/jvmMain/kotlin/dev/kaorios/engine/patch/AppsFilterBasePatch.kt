package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * AppsFilterBase.shouldFilterApplication(snapshot overload) - hidden-app list stamping hook.
 *
 * Ports the insert from the Toolbox template `Template_V2060/service/AppsFilterBase.smali`:
 * `KaoriosHook.shouldHideAppList(null, targetPkg)` is the only call site whose return value
 * reaches `kaorios_hide_app_work`, the Settings.Global key the Toolbox app reads to decide
 * whether "Hide App List" is available ("Framework hook not detected" otherwise). The guide's
 * own mode-1 set (`shouldHideAppListForCaller` in ComputerEngine) never writes that key, so
 * without this insert the feature stays permanently disabled no matter what the UI shows.
 *
 * `ComputerEngine.shouldFilterApplication` delegates to this method in the same binder frame,
 * so the call runs with the original caller's identity — the `Binder.getCallingUid` gate
 * inside the runtime's `d5.a` needs exactly that. The template wraps the hook in
 * `catch Throwable` and falls through to the stock method head on any failure; that shape is
 * reproduced here and pinned byte-for-byte by [verify].
 *
 * An absent method returns [PatchStatus.NOT_TARGET]: the snapshot overload is an AOSP layout
 * not every ROM carries, and the template file is an optional root template upstream never
 * wired into its Python patchers. Absence leaves the file byte-identical.
 */
object AppsFilterBasePatch {

    const val HOOK_TARGET =
        "Landroid/security/kaorios/KaoriosHook;->shouldHideAppList(Landroid/content/ContentResolver;Ljava/lang/String;)Z"
    const val GET_PACKAGE_NAME_CALL =
        "invoke-interface {v0}, Lcom/android/server/pm/pkg/PackageStateInternal;->getPackageName()Ljava/lang/String;"

    const val LABEL_STOCK = ":cond_kaorios_hideapp_stock"
    const val TRY_START = ":try_start_kaorios_hideapp"
    const val TRY_END = ":try_end_kaorios_hideapp"
    const val CATCH = ":catch_kaorios_hideapp"

    const val HOOK_PREFIX = "KaoriosHook;->shouldHideAppList(Landroid/content/ContentResolver;"

    private const val PARAM_COUNT = 6

    private val METHOD_RE = Regex(
        "(?m)^\\.method[^\\r\\n]*[ \\t]shouldFilterApplication" +
            "\\(Lcom/android/server/pm/snapshot/PackageDataSnapshot;ILjava/lang/Object;" +
            "Lcom/android/server/pm/pkg/PackageStateInternal;I\\)Z[ \\t]*(?:\\r?\\n|$)"
    )

    private val DIRECTIVE_STRIP =
        Regex("(?m)^[ \\t]*\\.(?:line|local|end local|restart local|prologue|epilogue)\\b[^\\n]*\\n")
    private val REGISTER_OR_LOCALS = Regex("(?m)^\\s*\\.(registers|locals)\\s+(\\d+)")

    private data class MethodInfo(val start: Int, val endExclusive: Int)

    private fun methodSpan(text: String): MethodInfo? {
        val matches = METHOD_RE.findAll(text).toList()
        if (matches.isEmpty()) return null
        if (matches.size > 1) {
            throw UnsupportedLayoutException(
                "ambiguous shouldFilterApplication(snapshot): found ${matches.size}"
            )
        }
        val head = matches[0]
        val end = Smali.END_METHOD.find(text, head.range.last + 1)
            ?: throw UnsupportedLayoutException("unterminated AppsFilterBase.shouldFilterApplication")
        return MethodInfo(head.range.first, end.range.last + 1)
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

    /** Locals available for scratch, derived from the method's register directive. */
    private fun localsOf(body: String): Pair<Int, Int> {
        val directive = REGISTER_OR_LOCALS.find(body)
            ?: throw UnsupportedLayoutException("missing registers directive for hide-app hook")
        val count = directive.groupValues[2].toInt()
        val locals = if (directive.groupValues[1] == "locals") count else count - PARAM_COUNT
        // Numeric alias of p4 (targetPkgSetting) for the move width decision.
        val p4 = if (directive.groupValues[1] == "locals") count + 4 else count - 2
        return locals to p4
    }

    /**
     * The insert, exactly as it lands in the file: null-guard on `targetPkgSetting` outside the
     * try, then getPackageName + `shouldHideAppList(null, pkg)` inside a `catch Throwable` that
     * falls through to the stock method head.
     */
    private fun emit(body: String, newline: String): String {
        val (locals, p4) = localsOf(body)
        if (locals < 3) {
            throw UnsupportedLayoutException("method has $locals locals; hide-app hook needs 3")
        }
        val move = if (p4 > 15) "move-object/from16 v0, p4" else "move-object v0, p4"
        return listOf(
            move,
            "if-eqz v0, $LABEL_STOCK",
            TRY_START,
            GET_PACKAGE_NAME_CALL,
            "move-result-object v1",
            "if-eqz v1, $LABEL_STOCK",
            "const/4 v2, 0x0",
            "invoke-static {v2, v1}, $HOOK_TARGET",
            "move-result v2",
            "if-eqz v2, $LABEL_STOCK",
            "const/4 v2, 0x1",
            "return v2",
            TRY_END,
            ".catch Ljava/lang/Throwable; {$TRY_START .. $TRY_END} $CATCH",
            CATCH,
            LABEL_STOCK,
        ).joinToString(newline) { "    $it" } + newline
    }

    fun patch(content: String): PatchOutcome {
        val info = methodSpan(content)
            ?: return PatchOutcome(PatchStatus.NOT_TARGET, content)
        val body = content.substring(info.start, info.endExclusive)
        if (Smali.countOccurrences(body, HOOK_PREFIX) > 0) {
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }
        if (body.contains(LABEL_STOCK)) {
            throw UnsupportedLayoutException("reserved Kaorios label already exists in target method")
        }

        val newline = Smali.newlineOf(content)
        val injOffset = findInjectionPoint(body)
        val insert = emit(body, newline)
        val patchedBody = body.substring(0, injOffset) + insert + body.substring(injOffset)
        val patched = content.substring(0, info.start) + patchedBody + content.substring(info.endExclusive)
        verify(patched)
        return PatchOutcome(PatchStatus.PATCHED, patched)
    }

    fun verify(content: String) {
        val info = methodSpan(content)
            ?: throw PatchVerificationException("AppsFilterBase.shouldFilterApplication(snapshot) not found")
        val body = content.substring(info.start, info.endExclusive)
        val stripped = DIRECTIVE_STRIP.replace(body, "")
        val count = Smali.countOccurrences(stripped, HOOK_PREFIX)
        if (count != 1) {
            throw PatchVerificationException("expected exactly one shouldHideAppList hook; found $count")
        }
        val newline = Smali.newlineOf(content)
        val expected = emit(stripped, newline)
        val injOffset = findInjectionPoint(stripped)
        val tail = stripped.substring(injOffset)
        if (!tail.startsWith(expected)) {
            throw PatchVerificationException(
                "hook sequence does not match the exact fail-closed template shape"
            )
        }
        for (label in listOf(LABEL_STOCK, CATCH)) {
            val defined = Regex("(?m)^\\s*" + Regex.escape(label) + "\\s*$").findAll(stripped).count()
            if (defined != 1) {
                throw PatchVerificationException("$label must be defined exactly once; found $defined")
            }
        }
        val caught = Regex(
            "\\.catch Ljava/lang/Throwable; \\{" + Regex.escape(TRY_START) +
                " \\.\\. " + Regex.escape(TRY_END) + "\\} " + Regex.escape(CATCH)
        ).findAll(stripped).count()
        if (caught != 1) {
            throw PatchVerificationException("expected exactly one catch-all for the hook; found $caught")
        }
    }
}

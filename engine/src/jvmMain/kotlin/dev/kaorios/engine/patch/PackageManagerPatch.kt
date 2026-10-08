package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.Smali
import dev.kaorios.engine.smali.UnsupportedLayoutException

/** ApplicationPackageManager.hasSystemFeature(String,int)Z - feature spoof hook. */
object PackageManagerPatch {

    const val HOOK_SIGNATURE =
        "Landroid/security/kaorios/KaoriosHook;->hasSystemFeature(Ljava/lang/String;I)Ljava/lang/Boolean;"
    private val METHOD_RE = Regex(
        "\\.method[^\\n]*?hasSystemFeature\\(Ljava/lang/String;I\\)Z.*?\\.end method",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)([^\\n]*)")
    private val METHOD_OPEN = Regex("(?m)^\\s*\\.(?:method|registers|locals|param|line)\\b[^\\n]*$")

    fun patch(content: String): PatchOutcome {
        val match = METHOD_RE.find(content)
            ?: throw UnsupportedLayoutException("hasSystemFeature(Ljava/lang/String;I)Z method not found in ApplicationPackageManager")

        var methodBody = match.value
        if (methodBody.contains(HOOK_SIGNATURE)) {
            return PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }

        val regMatch = DIRECTIVE.find(methodBody)
            ?: throw UnsupportedLayoutException(".registers or .locals directive not found in hasSystemFeature")
        val directive = regMatch.groupValues[1]
        val oldCount = regMatch.groupValues[2].toInt()
        val newCount = oldCount + 1

        val scratch: String
        val scratchNum: Int
        val p1Num: Int
        val p2Num: Int
        if (directive == "locals") {
            scratch = "v$oldCount"
            methodBody = Smali.canonicalizeParamAliases(methodBody, oldCount + 3, 3)
            scratchNum = oldCount
            p1Num = oldCount + 2
            p2Num = oldCount + 3
        } else {
            val paramCount = 3
            methodBody = Smali.canonicalizeParamAliases(methodBody, oldCount, paramCount)
            scratch = "v${newCount - paramCount - 1}"
            scratchNum = newCount - paramCount - 1
            p1Num = newCount - paramCount + 1
            p2Num = newCount - paramCount + 2
        }

        val finalMatch = DIRECTIVE.find(methodBody)
            ?: throw UnsupportedLayoutException("register directive vanished during canonicalization")
        val newDirective = ".${directive} $newCount${finalMatch.groupValues[3]}"
        val label = Smali.uniqueLabel(":cond_kaorios_feature_stock", methodBody)

        val invokeStatic = if (p1Num > 15 || p2Num > 15) {
            "invoke-static/range {p1 .. p2}, $HOOK_SIGNATURE"
        } else {
            "invoke-static {p1, p2}, $HOOK_SIGNATURE"
        }
        val invokeVirtual = if (scratchNum > 15) {
            "invoke-virtual/range {$scratch .. $scratch}, Ljava/lang/Boolean;->booleanValue()Z"
        } else {
            "invoke-virtual {$scratch}, Ljava/lang/Boolean;->booleanValue()Z"
        }

        val inject = """
    $invokeStatic
    move-result-object $scratch

    if-eqz $scratch, $label
    $invokeVirtual
    move-result $scratch
    return $scratch

    $label"""

        val newMethod = methodBody.substring(0, finalMatch.range.first) + newDirective + inject +
            methodBody.substring(finalMatch.range.last + 1)
        return PatchOutcome(
            PatchStatus.PATCHED,
            content.substring(0, match.range.first) + newMethod + content.substring(match.range.last + 1)
        )
    }

    fun verify(content: String) {
        val match = METHOD_RE.find(content)
            ?: failVerification("ApplicationPackageManager: hasSystemFeature(Ljava/lang/String;I)Z method not found")
        val body = match.value
        val sequence = Regex(
            "invoke-static(?:/range)?\\s*\\{p1(?:,\\s*p2|\\s*\\.\\.\\s*p2)\\},\\s*" +
                Regex.escape(HOOK_SIGNATURE) + "\\s+" +
                "move-result-object\\s+(?<scratch>v\\d+)\\s+" +
                "if-eqz\\s+\\k<scratch>,\\s*(?<label>:[\\w$]+)\\s+" +
                "invoke-virtual(?:/range)?\\s*\\{\\k<scratch>(?:\\s*\\.\\.\\s*\\k<scratch>)?\\},\\s*" +
                "Ljava/lang/Boolean;->booleanValue\\(\\)Z\\s+" +
                "move-result\\s+\\k<scratch>\\s+" +
                "return\\s+\\k<scratch>\\s+" +
                "(?:\\.line\\s+\\d+\\s+)*" +
                "\\k<label>\\b"
        ).find(body)
        if (sequence == null || Smali.countOccurrences(body, "KaoriosHook;->hasSystemFeature") != 1) {
            failVerification("ApplicationPackageManager: invalid hasSystemFeature hook control flow")
        }
        val label = sequence.groups["label"]!!.value
        if (Regex("(?m)^\\s*" + Regex.escape(label) + "\\s*$").findAll(body).count() != 1) {
            failVerification("ApplicationPackageManager: stock branch target is not unique")
        }
        val prefix = METHOD_OPEN.replace(body.substring(0, sequence.range.first), "")
        if (prefix.isNotBlank()) {
            failVerification("ApplicationPackageManager: hook is after stock logic")
        }
    }
}
package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * Instrumentation.newApplication overloads - context hook.
 *
 * Ported from `patch_instrumentation` in `kaorios_patcher_a17.py`. Every
 * `return-object` in the method must be dominated by the initContext hook so no
 * application can be created with an un-hooked context.
 */
object InstrumentationPatch {

    const val HOOK_SIGNATURE =
        "Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V"
    const val ANCHOR_CLASS =
        "newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;"
    const val ANCHOR_LOADER =
        "newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;"

    private val DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)")
    private val RETURN_OBJECT = Regex("(return-object\\s+[vp]\\d+\\s*)")
    private val STATIC_HEADER = Regex("\\bstatic\\b")

    private fun patchMethod(text: String, methodName: String, initialParam: String): Pair<String, Boolean> {
        val start = text.indexOf(methodName)
        if (start < 0) throw UnsupportedLayoutException("$methodName anchor method not found in Instrumentation")
        val end = text.indexOf(".end method", start)
        if (end < 0) throw UnsupportedLayoutException("unterminated $methodName method in Instrumentation")

        var param = initialParam
        val methodBody = text.substring(start, end)
        val headerStart = text.lastIndexOf(".method", start)
        val header = text.substring(headerStart, start)
        if (methodName.contains("Class;") && STATIC_HEADER.containsMatchIn(header)) {
            param = "p1"
        }
        if (methodBody.contains(HOOK_SIGNATURE)) return text to false

        val matches = RETURN_OBJECT.findAll(methodBody).toList()
        if (matches.isEmpty()) throw UnsupportedLayoutException("return-object not found in $methodName")

        val regMatch = DIRECTIVE.find(methodBody)
        var realRegNum = 0
        if (regMatch != null) {
            val directive = regMatch.groupValues[1]
            val count = regMatch.groupValues[2].toInt()
            val paramNum = param.substring(1).toInt()
            realRegNum = if (directive == "registers") {
                val pCount = if (methodName.contains("Class;")) {
                    if (param == "p1") 2 else 3
                } else {
                    4
                }
                count - pCount + paramNum
            } else {
                count + paramNum
            }
        }

        val invoke = if (realRegNum > 15) {
            "invoke-static/range {$param .. $param}, $HOOK_SIGNATURE"
        } else {
            "invoke-static {$param}, $HOOK_SIGNATURE"
        }

        var newBody = methodBody
        for (m in matches.asReversed()) {
            val inject = "$invoke\n\n    ${m.groupValues[1]}"
            newBody = newBody.substring(0, m.range.first) + inject + newBody.substring(m.range.last + 1)
        }
        return (text.substring(0, start) + newBody + text.substring(end)) to true
    }

    fun patch(content: String): PatchOutcome {
        val (afterFirst, c1) = patchMethod(content, ANCHOR_CLASS, "p2")
        val (afterSecond, c2) = patchMethod(afterFirst, ANCHOR_LOADER, "p3")
        val changed = c1 || c2
        return if (changed) {
            PatchOutcome(PatchStatus.PATCHED, afterSecond)
        } else {
            PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }
    }

    fun verify(content: String) {
        val body1 = bodyOf(content, ANCHOR_CLASS)
        val body2 = bodyOf(content, ANCHOR_LOADER)
        val returns1 = RETURN_OBJECT.findAll(body1).count()
        val returns2 = RETURN_OBJECT.findAll(body2).count()
        val hooks1 = Regex(Regex.escape(HOOK_SIGNATURE)).findAll(body1).count()
        val hooks2 = Regex(Regex.escape(HOOK_SIGNATURE)).findAll(body2).count()

        if (returns1 > 0 && hooks1 != returns1) {
            failVerification("Instrumentation: newApplication(Class,Context) expected $returns1 initContext hooks, found $hooks1")
        }
        if (returns2 > 0 && hooks2 != returns2) {
            failVerification("Instrumentation: newApplication(ClassLoader,String,Context) expected $returns2 initContext hooks, found $hooks2")
        }
        if (hooks1 == 0 && hooks2 == 0) {
            failVerification("Instrumentation: KaoriosHook initContext hook not found in either newApplication method")
        }

        val headerStart = content.lastIndexOf(".method", content.indexOf(ANCHOR_CLASS))
        val classHeader = content.substring(headerStart, content.indexOf(ANCHOR_CLASS))
        val classContext = if (STATIC_HEADER.containsMatchIn(classHeader)) "p1" else "p2"
        for ((body, contextReg) in listOf(body1 to classContext, body2 to "p3")) {
            val dominated = Regex(
                "invoke-static(?:/range)?\\s*\\{" + Regex.escape(contextReg) +
                    "(?:\\s*\\.\\.\\s*" + Regex.escape(contextReg) + ")?\\},\\s*" +
                    Regex.escape(HOOK_SIGNATURE) + "\\s*$"
            )
            for (ret in RETURN_OBJECT.findAll(body)) {
                val preceding = body.substring(0, ret.range.first)
                if (!dominated.containsMatchIn(preceding)) {
                    failVerification("Instrumentation: initContext must directly dominate each return with the Context parameter")
                }
            }
        }
    }

    private fun bodyOf(content: String, anchor: String): String {
        val start = content.indexOf(anchor)
        if (start < 0) {
            throw UnsupportedLayoutException("Instrumentation: method anchor '$anchor' not found")
        }
        val end = content.indexOf(".end method", start)
        if (end < 0) {
            throw UnsupportedLayoutException("Instrumentation: unterminated method at '$anchor'")
        }
        return content.substring(start, end)
    }
}
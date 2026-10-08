package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.UnsupportedLayoutException

/**
 * Build / Build$VERSION static-initializer spoofing (Android 17).
 *
 * Ported from `kaorios_patcher_a17.py`. Drops `final` from a fixed field list so
 * the framework's own static blocks can assign spoofed values at boot, and rewrites
 * the String constants to `= null` so they can be filled in later.
 */
object BuildSpoofPatch {

    val NULL_STRING_FIELDS = listOf(
        "BRAND", "BRAND_FOR_ATTESTATION", "DEVICE", "DEVICE_FOR_ATTESTATION",
        "FINGERPRINT", "HARDWARE", "ID", "MANUFACTURER", "MANUFACTURER_FOR_ATTESTATION",
        "MODEL", "MODEL_FOR_ATTESTATION", "PRODUCT", "PRODUCT_FOR_ATTESTATION",
        "TAGS", "TYPE", "USER"
    )

    val VERSION_FIELDS = listOf(
        "RELEASE", "RELEASE_OR_CODENAME", "RELEASE_OR_PREVIEW_DISPLAY",
        "SECURITY_PATCH", "DEVICE_INITIAL_SDK_INT"
    )

    private fun fieldPattern(name: String, type: String) =
        Regex("\\.field public static[^\\n]*? ${Regex.escape(name)}:$type")

    private fun dropFinal(text: String, field: String, type: String): String =
        Regex("(\\.field public static[^\\n]*?)final([^\\n]*? ${Regex.escape(field)}:$type)")
            .replace(text) { m -> m.groups[1]!!.value + m.groups[2]!!.value }

    private fun nullifyString(text: String, field: String): String =
        Regex("(\\.field public static[^\\n]*?)final([^\\n]*? ${Regex.escape(field)}:Ljava/lang/String;)")
            .replace(text) { m -> m.groups[1]!!.value + m.groups[2]!!.value + " = null" }

    fun patchBuild(content: String): PatchOutcome {
        val anchor = NULL_STRING_FIELDS.first()
        if (!fieldPattern(anchor, "Ljava/lang/String;").containsMatchIn(content)) {
            throw UnsupportedLayoutException("Build.smali: expected field $anchor not found - unsupported layout")
        }
        if (!Regex("\\.field public static[^\\n]*? TIME:J").containsMatchIn(content)) {
            throw UnsupportedLayoutException("Build.smali: expected field TIME:J not found - unsupported layout")
        }
        var patched = content
        for (field in NULL_STRING_FIELDS) patched = nullifyString(patched, field)
        patched = dropFinal(patched, "TIME", "J")
        return if (patched != content) {
            PatchOutcome(PatchStatus.PATCHED, patched)
        } else {
            PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }
    }

    fun patchBuildVersion(content: String): PatchOutcome {
        val anchor = VERSION_FIELDS.first()
        if (!Regex("\\.field public static[^\\n]*? ${Regex.escape(anchor)}:[^\\s]+").containsMatchIn(content)) {
            throw UnsupportedLayoutException("Build\$VERSION.smali: expected field $anchor not found - unsupported layout")
        }
        var patched = content
        for (field in VERSION_FIELDS) patched = dropFinal(patched, field, "[^\\s]+")
        return if (patched != content) {
            PatchOutcome(PatchStatus.PATCHED, patched)
        } else {
            PatchOutcome(PatchStatus.ALREADY_PATCHED, content)
        }
    }

    fun verifyBuild(content: String) {
        for (field in NULL_STRING_FIELDS) {
            val match = fieldPattern(field, "Ljava/lang/String;").find(content)
            if (match == null) {
                if (field.endsWith("_FOR_ATTESTATION") &&
                    !Regex("\\.field[^\\n]* ${Regex.escape(field)}:").containsMatchIn(content)
                ) {
                    continue
                }
                failVerification("Build.smali post-patch: field $field not found")
            }
            if (match.value.contains("final")) {
                failVerification("Build.smali post-patch: field $field still has 'final' modifier - patch did not apply")
            }
        }
        val time = Regex("\\.field public static[^\\n]* TIME:J").find(content)
            ?: failVerification("Build.smali post-patch: field TIME:J not found")
        if (time.value.contains("final")) {
            failVerification("Build.smali post-patch: field TIME:J still has 'final' modifier - patch did not apply")
        }
    }

    fun verifyBuildVersion(content: String) {
        for (field in VERSION_FIELDS) {
            val match = Regex("\\.field public static[^\\n]* ${Regex.escape(field)}:[^\\s]+").find(content)
                ?: failVerification("Build\$VERSION.smali post-patch: field $field not found")
            if (match.value.contains("final")) {
                failVerification("Build\$VERSION.smali post-patch: field $field still has 'final' modifier - patch did not apply")
            }
        }
    }
}
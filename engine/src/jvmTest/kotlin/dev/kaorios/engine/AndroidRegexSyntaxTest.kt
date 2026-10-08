package dev.kaorios.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android's `java.util.regex.Pattern` is not the JDK's: it compiles through
 * `com.android.icu.util.regex.PatternNative`, and ICU is stricter than the desktop engine about
 * braces. A `}` with no matching `{` outside a character class is a literal on the JVM and a
 * `PatternSyntaxException` on the device, so a pattern can pass every test here and still blow up
 * the moment its `object` is first initialised on the phone.
 *
 * `CorePatchPatch` shipped exactly that shape (`invoke-virtual\s+\{[^}]*},`) and took the app down
 * with an `ExceptionInInitializerError` the first time a `FULL` run reached CorePatch — the very
 * feature the run existed to exercise. The desktop suite cannot see this, so the guard reads the
 * engine's own sources instead: every string literal handed to `Regex(...)` is extracted,
 * interpolated segments are dropped (they are gone by the time the pattern exists) and escapes are
 * collapsed, and the result must contain no brace outside a character class that ICU would refuse
 * to quote.
 *
 * A real `{n,m}` interval is fine and is skipped as such.
 */
class AndroidRegexSyntaxTest {

    @Test
    fun `every Regex literal the engine declares is legal for the Android ICU engine`() {
        val sources = sourceFiles()
        assertTrue(sources.isNotEmpty(), "no sources found from ${File(".").absolutePath}")

        val offending = sources.flatMap { file ->
            regexLiterals(file.readText()).mapNotNull { pattern ->
                firstUnescapedBrace(pattern)?.let { "$file: $it" }
            }
        }

        assertTrue(offending.isEmpty(), "patterns the Android regex engine rejects:\n" + offending.joinToString("\n"))
    }

    // ---- sources ---------------------------------------------------------

    private fun sourceFiles(): List<File> {
        val roots = listOf("src/jvmMain", "engine/src/jvmMain", "../engine/src/jvmMain")
            .map(::File)
            .filter(File::isDirectory) +
            listOf("src/androidMain", "../app/src/androidMain", "app/src/androidMain")
                .map(::File)
                .filter(File::isDirectory)
        return roots.distinctBy { it.canonicalPath }.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" } }
    }

    /** Every string literal that appears directly inside a `Regex(...)` call, decoded to its runtime form. */
    private fun regexLiterals(source: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = source.indexOf("Regex(", from)
            if (at < 0) return out
            var open = at + "Regex(".length
            while (open < source.length && source[open].isWhitespace()) open++
            when {
                source.startsWith("\"\"\"", open) -> {
                    val start = open + 3
                    val end = source.indexOf("\"\"\"", start)
                    if (end < 0) return out
                    out += dropInterpolation(source.substring(start, end))
                    from = end + 3
                }
                open < source.length && source[open] == '"' -> {
                    val start = open + 1
                    var end = start
                    while (end < source.length && source[end] != '"') {
                        if (source[end] == '\\') end++
                        end++
                    }
                    if (end >= source.length) return out
                    out += dropInterpolation(source.substring(start, end)).collapseBackslashes()
                    from = end + 1
                }
                else -> from = at + "Regex(".length
            }
        }
    }

    /** `${…}` and `$name` are replaced before the pattern is ever compiled, so they are not pattern text. */
    private fun dropInterpolation(literal: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < literal.length) {
            if (literal[i] != '$') {
                out.append(literal[i]); i++
            } else if (i + 1 < literal.length && literal[i + 1] == '{') {
                var depth = 0
                var j = i + 1
                while (j < literal.length) {
                    if (literal[j] == '{') depth++
                    if (literal[j] == '}' && --depth == 0) break
                    j++
                }
                i = j + 1
            } else if (i + 1 < literal.length && (literal[i + 1].isLetterOrDigit() || literal[i + 1] == '_')) {
                var j = i + 1
                while (j < literal.length && (literal[j].isLetterOrDigit() || literal[j] == '_')) j++
                out.append('x')
                i = j
            } else {
                out.append(literal[i]); i++
            }
        }
        return out.toString()
    }

    /** `"\\{"` is a quoted Kotlin string whose runtime pattern is the escaped `\{`. */
    private fun String.collapseBackslashes(): String {
        val out = StringBuilder()
        var i = 0
        while (i < length) {
            if (this[i] == '\\' && i + 1 < length && this[i + 1] == '\\') {
                out.append('\\'); i += 2
            } else {
                out.append(this[i]); i++
            }
        }
        return out.toString()
    }

    // ---- the check -------------------------------------------------------

    private fun firstUnescapedBrace(pattern: String): String? {
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '\\' -> i += 2
                '[' -> { inClass = true; i++ }
                ']' -> { inClass = false; i++ }
                '{' -> {
                    if (inClass) { i++; continue }
                    val close = intervalEnd(pattern, i)
                    if (close < 0) return "unescaped '{' at $i in `$pattern`"
                    i = close + 1
                }
                '}' -> {
                    if (inClass) { i++; continue }
                    return "unescaped '}' at $i in `$pattern`"
                }
                else -> i++
            }
        }
        return null
    }

    /** Index of the `}` closing a `{n}` / `{n,}` / `{n,m}` interval starting at [open], or -1. */
    private fun intervalEnd(pattern: String, open: Int): Int {
        var i = open + 1
        val digits = StringBuilder()
        while (i < pattern.length && pattern[i].isDigit()) { digits.append(pattern[i]); i++ }
        if (digits.isEmpty()) return -1
        if (i < pattern.length && pattern[i] == ',') {
            i++
            val upper = StringBuilder()
            while (i < pattern.length && pattern[i].isDigit()) { upper.append(pattern[i]); i++ }
            if (upper.isNotEmpty() && upper.toString().toInt() < digits.toString().toInt()) return -1
        }
        return if (i < pattern.length && pattern[i] == '}') i else -1
    }
}

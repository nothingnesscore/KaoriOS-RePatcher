package dev.kaorios.engine.smali

/** Half-open offset range, mirroring the `[start, end)` spans used by the Python reference. */
data class Span(val start: Int, val endExclusive: Int) {
    val length: Int get() = endExclusive - start

    fun substringIn(source: String): String = source.substring(start, endExclusive)
}

/** The layout of a method is not one of the supported AOSP shapes; the file is left untouched. */
class UnsupportedLayoutException(message: String) : Exception(message)

/** A patch produced output that failed its verifier. Never written to disk. */
class PatchVerificationException(message: String) : Exception(message)
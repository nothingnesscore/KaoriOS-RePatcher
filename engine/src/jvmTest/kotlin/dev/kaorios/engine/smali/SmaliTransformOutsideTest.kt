package dev.kaorios.engine.smali

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards against the class of bug that only appears on a real ROM.
 *
 * The differential oracle fixtures contain no string literals inside the patched method
 * bodies, so the shared literal-splitting path was never exercised by them. A real A17 class
 * is full of `const-string` instructions, and the first port deleted every one of them,
 * producing a file no assembler would accept.
 *
 * The mechanism is [Smali.transformOutside]: Kotlin's `Regex.split` drops capture groups
 * while Python's `re.split` keeps them, so the reference can alternate keep/rewrite over
 * split parts where the naive Kotlin port silently deletes the literal contents.
 */
class SmaliTransformOutsideTest {

    private val stringLiteral = Regex("\"[^\"]*\"")

    @Test
    fun `literal contents survive a rewrite of the segments around them`() {
        val line = """    const-string v0, "settings.provider.value"    # v0 comment"""
        val out = Smali.transformOutside(line, stringLiteral) { segment ->
            segment.replace("v0", "v1")
        }
        assertEquals("""    const-string v1, "settings.provider.value"    # v1 comment""", out)
    }

    @Test
    fun `a line carrying no literal is rewritten whole`() {
        val line = "    invoke-static {v0}, Landroid/os/Build;->something()V"
        val out = Smali.transformOutside(line, stringLiteral) { segment ->
            segment.replace("v0", "v1")
        }
        assertEquals("    invoke-static {v1}, Landroid/os/Build;->something()V", out)
    }

    @Test
    fun `two literals on one line are both preserved verbatim`() {
        val line = """    invoke-static {v0, v1}, Lfoo;->of(Ljava/lang/String;Ljava/lang/String;)V    # "a" and "b""""
        val out = Smali.transformOutside(line, stringLiteral) { segment ->
            segment.replace("v1", "v2")
        }
        assertTrue(out.contains("\"a\" and \"b\""), out)
        assertEquals(
            """    invoke-static {v0, v2}, Lfoo;->of(Ljava/lang/String;Ljava/lang/String;)V    # "a" and "b"""",
            out,
        )
    }

    @Test
    fun `the split a naive port performs would truncate the literal`() {
        val line = """    const-string v0, "system""""
        // What Regex.split would rebuild from the parts it keeps: the literal is gone.
        val naive = stringLiteral.split(line).joinToString("") { it }
        assertTrue(naive.trimEnd().endsWith(","), "sanity: the split really does drop it — '$naive'")

        val out = Smali.transformOutside(line, stringLiteral) { segment ->
            segment.replace("const-string", "const-string/16")
        }
        assertEquals("""    const-string/16 v0, "system"""", out)
    }
}

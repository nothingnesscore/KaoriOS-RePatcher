package dev.kaorios.engine.cli

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import java.util.zip.ZipFile

/**
 * Prints the raw string literals a class references, escaping anything unprintable.
 *
 * baksmali can render a `const-string` operand as empty on certain ROMs, which leaves the file
 * unassemblable. This is how the offending literals get identified.
 *
 * Usage: `StringProbe <archive> <dex-entry> <class-descriptor> [method-name]`
 */
object StringProbe {

    private const val API = 34

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 3) { "usage: StringProbe <archive> <dex-entry> <class-descriptor> [method]" }
        val methodFilter = args.getOrNull(3)

        val staged = File.createTempFile("probe", ".dex")
        ZipFile(File(args[0])).use { zip ->
            val entry = zip.getEntry(args[1]) ?: error("${args[1]} not in ${args[0]}")
            staged.writeBytes(zip.getInputStream(entry).readBytes())
        }
        try {
            val dex = DexFileFactory.loadDexFile(staged, Opcodes.forApi(API))
            val classDef = dex.classes.firstOrNull { it.type == args[2] }
                ?: error("${args[2]} not in ${args[1]}")
            for (method in classDef.methods) {
                if (methodFilter != null && !method.name.contains(methodFilter)) continue
                println("== ${method.name} ==")
                val implementation = method.implementation ?: continue
                for (instruction in implementation.instructions) {
                    val literal = literalOf(instruction)
                    if (literal != null) println("  $literal")
                }
            }
        } finally {
            staged.delete()
        }
    }

    /** The literal of a `const-string` instruction, escaped so nothing hides in the output. */
    private fun literalOf(instruction: Instruction): String? {
        if (!instruction.opcode.name.startsWith("const-string")) return null
        val value = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: return null
        return "${instruction.opcode.name} ${render(value.string)}"
    }

    private fun render(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code in 32..126 -> append(c)
                else -> append("\\u%04x".format(c.code))
            }
        }
        append('"')
    }
}
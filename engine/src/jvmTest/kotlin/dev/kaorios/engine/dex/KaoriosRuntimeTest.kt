package dev.kaorios.engine.dex

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the KaoriOS runtime merge, which is what stands between a patched module and a boot loop.
 *
 * Every injected call site `invoke`s `Landroid/security/kaorios/KaoriosHook;`. That class only
 * resolves if the runtime dex lands on the boot classpath, so the dex has to be validated rather
 * than trusted — and it has to land in a dex the archive *already* had, because `OatFile::Open`
 * compares the jar's dex count against the one baked into `boot-framework.oat` and refuses the
 * boot image on a mismatch. The resulting interpreter-only framework boots so slowly that the
 * MIUI watchdog kills zygote, which is exactly the loop this suite exists to prevent.
 *
 * Dexes are synthesised with [DexPool] rather than checked in as fixtures: the descriptor list is
 * all that matters here, and a binary blob in the repo would go stale without anyone noticing.
 */
class KaoriosRuntimeTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "kaorios-runtime-test-${System.nanoTime()}")

    /**
     * A dex defining [descriptors], each as an empty public class.
     *
     * Built through dexlib2 rather than checked in as a binary fixture: only the descriptor list
     * matters here, and a blob in the repo would drift from the engine without anyone noticing.
     */
    private fun writeDex(vararg descriptors: String): File {
        tmp.mkdirs()
        val file = File(tmp, "runtime-${descriptors.size}-${System.nanoTime()}.dex")
        val classes = descriptors.map { descriptor ->
            ImmutableClassDef(
                descriptor,
                AccessFlags.PUBLIC.getValue(),
                "Ljava/lang/Object;",
                null,
                null,
                listOf<Annotation>(),
                listOf<Field>(),
                listOf<Method>(),
            )
        }
        DexPool.writeTo(file.path, ImmutableDexFile(Opcodes.forApi(DexRoundTrip.DEFAULT_API), classes))
        return file
    }

    /** A framework.jar containing [dexNames] in order, each holding [descriptors]. */
    private fun frameworkJar(
        dexNames: List<String>,
        descriptors: List<List<String>> = dexNames.map { emptyList() },
        stored: Boolean = true,
    ): File {
        tmp.mkdirs()
        val jar = File(tmp, "framework-${System.nanoTime()}.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            dexNames.forEachIndexed { index, name ->
                val bytes = writeDex(*descriptors[index].toTypedArray()).readBytes()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun runtimeDex(vararg extra: String) =
        writeDex(KaoriosRuntime.HOOK_DESCRIPTOR, *extra).readBytes()

    @Test
    fun `accepts a dex defining the hook`() {
        val dex = writeDex(KaoriosRuntime.HOOK_DESCRIPTOR)
        assertTrue(KaoriosRuntime.HOOK_DESCRIPTOR in KaoriosRuntime.validate(dex))
    }

    @Test
    fun `rejects a dex without the hook`() {
        val dex = writeDex("Ljava/lang/Object;")
        val error = assertFailsWith<IllegalStateException> { KaoriosRuntime.validate(dex) }
        assertTrue(
            KaoriosRuntime.HOOK_DESCRIPTOR in error.message.orEmpty(),
            "message should name the missing descriptor, was: ${error.message}",
        )
    }

    @Test
    fun `rejects a missing file`() {
        assertFailsWith<IllegalArgumentException> {
            KaoriosRuntime.validate(File(tmp, "absent.dex"))
        }
    }

    @Test
    fun `reports only the required classes it found`() {
        val dex = writeDex(KaoriosRuntime.HOOK_DESCRIPTOR, "Ljava/lang/Object;")
        assertEquals(listOf(KaoriosRuntime.HOOK_DESCRIPTOR), KaoriosRuntime.validate(dex))
    }

    @Test
    fun `never offers a slot the archive does not already have`() {
        val entries = listOf(
            KaoriosRuntime.DexEntry("classes.dex", 9_806_172, false),
            KaoriosRuntime.DexEntry("classes3.dex", 9_673_816, false),
            KaoriosRuntime.DexEntry("classes6.dex", 3_018_140, false),
        )
        val hosts = KaoriosRuntime.hostSlots(entries)
        assertTrue(
            hosts.toSet() == entries.map { it.name }.toSet(),
            "must only reorder what is present, was $hosts",
        )
        assertFalse(
            hosts.any { KaoriosRuntime.DEX_ENTRY_NAME.matches(it).not() },
            "host must be a dex entry, was $hosts",
        )
    }

    @Test
    fun `orders candidate hosts smallest first so the merge has id headroom`() {
        val entries = listOf(
            KaoriosRuntime.DexEntry("classes.dex", 9_806_172, false),
            KaoriosRuntime.DexEntry("classes3.dex", 9_673_816, false),
            KaoriosRuntime.DexEntry("classes6.dex", 3_018_140, false),
        )
        assertEquals(
            listOf("classes6.dex", "classes3.dex", "classes.dex"),
            KaoriosRuntime.hostSlots(entries),
        )
    }

    @Test
    fun `puts a slot that already holds the hook first`() {
        val entries = listOf(
            KaoriosRuntime.DexEntry("classes.dex", 9_806_172, false),
            KaoriosRuntime.DexEntry("classes6.dex", 3_018_140, false),
            KaoriosRuntime.DexEntry("classes3.dex", 9_673_816, true),
        )
        assertEquals(
            listOf("classes3.dex", "classes6.dex", "classes.dex"),
            KaoriosRuntime.hostSlots(entries),
        )
    }

    @Test
    fun `rejects an archive with no dexes`() {
        assertFailsWith<IllegalArgumentException> { KaoriosRuntime.hostSlots(emptyList()) }
    }

    @Test
    fun `merging the runtime leaves the dex count of the archive untouched`() {
        val stock = listOf("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex", "classes5.dex", "classes6.dex")
        val jar = frameworkJar(stock)
        val out = File(tmp, "merged-${System.nanoTime()}.jar")

        val roundTrip = DexArchiveRoundTrip()
        val disassembly = roundTrip.disassemble(jar, jar.name, File(tmp, "smali"), emptySet())
        val result = roundTrip.rebuild(disassembly, out, runtimeDex())

        val names = ZipFile(out).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertEquals(stock, names, "the archive must keep exactly the dex set the boot oat expects")
        assertNull(
            names.firstOrNull { it == "classes7.dex" },
            "a new dex would be rejected by OatFile::Open",
        )
        assertTrue(result.runtimeHost in stock, "the runtime must land in a dex that was already there")
        assertEquals(listOf(result.runtimeHost), result.changedDexes, "only the host dex changes")
    }

    @Test
    fun `the runtime survives the merge and the other dexes stay byte-identical`() {
        val stock = listOf("classes.dex", "classes2.dex", "classes3.dex")
        val jar = frameworkJar(stock)
        val out = File(tmp, "merged-${System.nanoTime()}.jar")

        val roundTrip = DexArchiveRoundTrip()
        val disassembly = roundTrip.disassemble(jar, jar.name, File(tmp, "smali"), emptySet())
        val host = roundTrip.rebuild(disassembly, out, runtimeDex()).runtimeHost!!

        val before = ZipFile(jar).use { z -> stock.associateWith { z.getInputStream(z.getEntry(it)).readBytes() } }
        val after = ZipFile(out).use { z -> stock.associateWith { z.getInputStream(z.getEntry(it)).readBytes() } }

        for (name in stock) {
            if (name == host) continue
            assertTrue(
                before.getValue(name).contentEquals(after.getValue(name)),
                "$name should be a verbatim copy",
            )
        }

        val hostDir = File(tmp, "host-${System.nanoTime()}")
        hostDir.mkdirs()
        val hostDex = File(hostDir, host).also { it.writeBytes(after.getValue(host)) }
        assertTrue(
            KaoriosRuntime.HOOK_DESCRIPTOR in KaoriosRuntime.descriptorsIn(hostDex),
            "$host must define the hook the patched call sites link against",
        )
    }

    @Test
    fun `the rebuilt archive is aligned the way dex2oat wants it`() {
        val jar = frameworkJar(listOf("classes.dex", "classes2.dex"))
        val out = File(tmp, "aligned-${System.nanoTime()}.jar")

        val roundTrip = DexArchiveRoundTrip()
        val disassembly = roundTrip.disassemble(jar, jar.name, File(tmp, "smali"), emptySet())
        roundTrip.rebuild(disassembly, out, runtimeDex())

        assertEquals(
            emptyList<String>(),
            AlignedZipWriter.misalignedEntries(out),
            "unaligned dex data makes dex2oat fall back to copying instead of mapping",
        )
    }

    @Test
    fun `a merge that would collide with an unrelated class is refused`() {
        val host = writeDex("Lcom/android/server/Foo;")
        val additions = writeDex("Lcom/android/server/Foo;", "Landroid/security/kaorios/KaoriosHook;")
        val out = File(tmp, "collision-${System.nanoTime()}.dex")

        val original = com.android.tools.smali.dexlib2.DexFileFactory.loadDexFile(
            host, Opcodes.forApi(DexRoundTrip.DEFAULT_API),
        )
        val extra = com.android.tools.smali.dexlib2.DexFileFactory.loadDexFile(
            additions, Opcodes.forApi(DexRoundTrip.DEFAULT_API),
        )
        val error = assertFailsWith<IllegalStateException> {
            DexRoundTrip.writeAugmentedDex(original, extra.classes, out)
        }
        assertTrue("Lcom/android/server/Foo;" in error.message.orEmpty())
    }
}

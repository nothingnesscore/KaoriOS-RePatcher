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
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the dex method-id overflow detection that lets the rebuild loop relocate patched
 * classes out of a saturated dex.
 *
 * On A17 Global, `framework.jar/classes3.dex` ships with `method_ids_size = 65535` — the
 * dex 16-bit invoke-index ceiling. Substituting the keystore classes (which inject new
 * `KaoriosHook` method references) pushes the table past 65536, and DexPool refuses to emit
 * any `invoke-*` whose method index would exceed 16 bits. `DexRoundTrip.writeMergedDex`
 * wraps that failure in [DexRoundTrip.MethodIdOverflow], which
 * `DexArchiveRoundTrip.rebuild` catches to strip the classes from the full dex and
 * relocate them to a dex with headroom.
 */
class MethodIdOverflowRelocationTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "kaorios-overflow-test-${System.nanoTime()}")

    @Test
    fun `isMethodIdOverflow detects dexlib2 short-range message`() {
        assertTrue(DexRoundTrip.isMethodIdOverflow(IllegalStateException("Unsigned short value out of range: 65537")))
    }

    @Test
    fun `isMethodIdOverflow detects nested cause`() {
        val inner = IllegalStateException("Unsigned short value out of range: 65540")
        assertTrue(DexRoundTrip.isMethodIdOverflow(RuntimeException("merge failed", inner)))
    }

    @Test
    fun `isMethodIdOverflow rejects unrelated errors`() {
        assertTrue(!DexRoundTrip.isMethodIdOverflow(IllegalStateException("smali could not assemble 1 file(s)")))
    }

    @Test
    fun `MethodIdOverflow is catchable as IllegalStateException`() {
        assertFailsWith<DexRoundTrip.MethodIdOverflow> {
            throw DexRoundTrip.MethodIdOverflow("test overflow")
        }
        assertFailsWith<IllegalStateException> {
            throw DexRoundTrip.MethodIdOverflow("test overflow")
        }
    }

    @Test
    fun `writeMergedDex succeeds on a normal dex`() {
        tmp.mkdirs()
        val originalFile = File(tmp, "orig-${System.nanoTime()}.dex")
        val replacement = ImmutableClassDef(
            "Lcom/test/Replaced;",
            AccessFlags.PUBLIC.getValue(),
            "Ljava/lang/Object;",
            null,
            null,
            listOf<Annotation>(),
            listOf<Field>(),
            listOf<Method>(),
        )
        DexPool.writeTo(
            originalFile.path,
            ImmutableDexFile(
                Opcodes.forApi(DexRoundTrip.DEFAULT_API),
                listOf(replacement),
            ),
        )
        val original = com.android.tools.smali.dexlib2.DexFileFactory.loadDexFile(
            originalFile,
            Opcodes.forApi(DexRoundTrip.DEFAULT_API),
        )
        val out = File(tmp, "merged-${System.nanoTime()}.dex")
        DexRoundTrip.writeMergedDex(original, mapOf("Lcom/test/Replaced;" to replacement), out)
        assertTrue(out.isFile)
    }
}

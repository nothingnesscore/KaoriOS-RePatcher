package dev.kaorios.engine.module

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The module may only overlay archives whose dex the round trip rebuilt.
 *
 * Re-zipping an APK destroys its APK Signing Scheme v2 block. Package Manager then logs
 * "No APK Signature Scheme v2 signature", drops the package, and on this ROM the missing
 * `SettingsProvider` left `system_server` blocked in `DeviceConfig.getLong` until the
 * watchdog killed the boot. Nothing short of the ROM's platform certificate can produce a
 * deployable APK on its own, so a rebuilt APK is refused outright — upstream v2.0.6.1
 * retired the only one we ever shipped, and there is no escape hatch any more.
 */
class KsuModuleBuilderTest {

    private fun file(name: String): File {
        val suffix = name.substringAfterLast('.', "")
        val f = File.createTempFile("kpu", if (suffix.isEmpty()) null else ".$suffix")
        f.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        f.deleteOnExit()
        return f
    }

    private fun bundle(
        vararg artifacts: KsuModuleBuilder.Artifact,
        includeToolbox: Boolean = true,
        stockDigests: Map<String, String> = emptyMap(),
        toolboxApk: File? = null,
        mountMode: MountMode = MountMode.MAGIC_MOUNT,
    ) = KsuModuleBuilder.Bundle(
        versionName = "1.0.0",
        artifacts = artifacts.toList(),
        includeToolbox = includeToolbox,
        stockDigests = stockDigests,
        toolboxApk = toolboxApk,
        mountMode = mountMode,
    )

    /** Builds the module and returns every entry's bytes, keyed by path. */
    private fun pack(bundle: KsuModuleBuilder.Bundle): Map<String, ByteArray> {
        val dest = File.createTempFile("kp-module", ".zip").apply { delete() }
        dest.deleteOnExit()
        KsuModuleBuilder().build(bundle, dest)
        return ZipFile(dest).use { zip ->
            zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }
        }
    }

    private fun jars() = listOf(
        KsuModuleBuilder.Artifact(file("framework.jar"), "system/framework/framework.jar"),
        KsuModuleBuilder.Artifact(file("services.jar"), "system/framework/services.jar"),
    )

    private fun text(entries: Map<String, ByteArray>, path: String): String =
        String(entries.getValue(path))

    @Test
    fun `an apk artifact is refused`() {
        val apk = file("SettingsProvider.apk")
        val bundle = bundle(
            *jars().toTypedArray(),
            KsuModuleBuilder.Artifact(apk, "system/priv-app/SettingsProvider/SettingsProvider.apk"),
        )
        val dest = File.createTempFile("kp-module", ".zip").apply { delete() }
        dest.deleteOnExit()

        val error = assertFailsWith<IllegalArgumentException> {
            KsuModuleBuilder().build(bundle, dest)
        }
        assertTrue(error.message!!.contains("v2 signature"), error.message!!)
        assertTrue(!dest.exists(), "nothing may be written when an apk is rejected")
    }

    @Test
    fun `jars are packaged`() {
        val dest = File.createTempFile("kp-module", ".zip").apply { delete() }
        dest.deleteOnExit()

        KsuModuleBuilder().build(bundle(*jars().toTypedArray()), dest)

        assertTrue(dest.isFile && dest.length() > 0)
    }

    /** A zip without these is not a module; managers refuse it before running a single script. */
    @Test
    fun `the kernelSU scaffolding is complete`() {
        val entries = pack(bundle(*jars().toTypedArray()))

        val expected = listOf(
            "module.prop",
            "customize.sh",
            "service.sh",
            "props.sh",
            "action.sh",
            "post-fs-data.sh",
            "uninstall.sh",
            "system.prop",
            "META-INF/com/google/android/update-binary",
            "META-INF/com/google/android/updater-script",
        )
        for (path in expected) {
            assertContains(entries.keys, path)
        }
        assertEquals("#MAGISK\n", text(entries, "META-INF/com/google/android/updater-script"))

        val prop = text(entries, "module.prop")
        for (line in listOf(
            "id=kaorios_patcher",
            "ksu=1",
            "minKsu=10904",
            "sufs=1",
            "minSufs=10000",
            "minApi=31",
            "maxApi=37",
            "requireReboot=true",
        )) {
            assertContains(prop, line)
        }
    }

    @Test
    fun `the toolbox apk is shipped byte for byte`() {
        val entries = pack(bundle(*jars().toTypedArray()))

        val packaged = entries.getValue(KsuModuleBuilder.TOOLBOX_APK_PATH)
        val resource = KsuModuleBuilder::class.java
            .getResourceAsStream(KsuModuleBuilder.TOOLBOX_APK_RESOURCE)!!
            .use { it.readBytes() }

        assertContains(entries.keys, KsuModuleBuilder.TOOLBOX_PERMISSIONS_PATH)
        assertEquals(resource.size, packaged.size, "toolbox apk was re-encoded")
        assertTrue(resource.contentEquals(packaged), "toolbox apk bytes changed in transit")

        // The marker that identifies an APK Signing Block. A v2 signature is exactly what a
        // re-zip destroys, so its presence is the property the flashability of this file rests on.
        assertTrue(
            String(packaged, Charsets.ISO_8859_1).contains(APK_SIG_BLOCK_MAGIC),
            "toolbox apk has no APK Signing Block",
        )
        val whitelist = text(entries, KsuModuleBuilder.TOOLBOX_PERMISSIONS_PATH)
        for (permission in listOf(
            "android.permission.INSTALL_PACKAGES",
            "android.permission.DELETE_PACKAGES",
            "android.permission.DUMP",
            "android.permission.READ_LOGS",
            "android.permission.WRITE_SECURE_SETTINGS",
        )) {
            assertContains(whitelist, permission)
        }
    }

    @Test
    fun `the toolbox can be omitted`() {
        val entries = pack(bundle(*jars().toTypedArray(), includeToolbox = false))

        assertTrue(entries.keys.none { it.contains("KaoriosToolbox") })
        assertTrue(entries.keys.none { it.contains("com.kousei.kaorios.xml") })
        assertContains(entries.keys, "module.prop")
    }

    /** The app passes the APK it just synced from the release; it must land verbatim. */
    @Test
    fun `a release toolbox apk is shipped byte for byte`() {
        val release = File.createTempFile("release", ".apk").apply {
            deleteOnExit()
            writeBytes(
                KsuModuleBuilder::class.java
                    .getResourceAsStream(KsuModuleBuilder.TOOLBOX_APK_RESOURCE)!!
                    .readBytes(),
            )
        }
        val entries = pack(bundle(*jars().toTypedArray(), toolboxApk = release))

        val packaged = entries.getValue(KsuModuleBuilder.TOOLBOX_APK_PATH)
        assertContains(
            String(packaged, Charsets.ISO_8859_1),
            APK_SIG_BLOCK_MAGIC,
            message = "toolbox apk has no APK Signing Block",
        )
        assertTrue(release.readBytes().contentEquals(packaged), "release apk bytes changed in transit")
    }

    /** A corrupt download fails closed instead of shipping an APK PackageManager would drop. */
    @Test
    fun `a toolbox apk without a signing block is refused`() {
        val unsigned = File.createTempFile("unsigned", ".apk").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "not a real apk".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            pack(bundle(*jars().toTypedArray(), toolboxApk = unsigned))
        }
    }

    /**
     * An abort after a mount action has already overwritten the stock jar has no rollback path,
     * so the digest check must textually precede every mount step.
     */
    @Test
    fun `the rom guard runs before the mount action`() {
        val guard = pack(
            bundle(
                *jars().toTypedArray(),
                stockDigests = mapOf("/system/framework/framework.jar" to "deadbeef"),
            ),
        )
        val customize = text(guard, "customize.sh")

        val guardAt = customize.indexOf("sha256sum")
        val mountAt = customize.indexOf("Magic mount active")
        assertTrue(guardAt in 0 until mountAt, "guard at $guardAt, mount at $mountAt")
        assertContains(customize, "abort \"! Patched jar was built against a different system image\"")
    }

    @Test
    fun `every boot script has a shebang`() {
        val entries = pack(bundle(*jars().toTypedArray()))
        for (path in listOf("service.sh", "props.sh", "action.sh", "post-fs-data.sh", "uninstall.sh")) {
            assertTrue(text(entries, path).startsWith("#!/system/bin/sh"), path)
        }
    }

    /**
     * The boot-loop protector is an arm/disarm pair: `post-fs-data.sh` arms `boot_pending`
     * before the framework loads and `service.sh` disarms it only after `boot_completed`.
     * A marker still present on the next boot means the previous boot never finished, so the
     * module disables itself and reboots — without that pair a patched jar that fails ART
     * verification would leave the device in a boot loop with no way out but a manager.
     */
    @Test
    fun `a boot that never completes disables the module on the next boot`() {
        val entries = pack(bundle(*jars().toTypedArray()))
        val postfs = text(entries, "post-fs-data.sh")
        val service = text(entries, "service.sh")

        val checkAt = postfs.indexOf("if [ -f \"\$MODDIR/boot_pending\" ]")
        val armAt = postfs.indexOf("touch \"\$MODDIR/boot_pending\"")
        assertTrue(checkAt in 0 until armAt, "pending must be checked before it is re-armed")
        assertContains(postfs, "touch \"\$MODDIR/disable\"")
        assertContains(postfs, "reboot")

        assertContains(service, "getprop sys.boot_completed")
        val disarmAt = service.indexOf("rm -f \"\$MODDIR/boot_pending\"")
        val waitAt = service.indexOf("getprop sys.boot_completed")
        assertTrue(disarmAt > waitAt, "the marker may only be cleared after boot_completed")
        assertContains(service, "SKIP_BOOT_WAIT")
    }

    /**
     * [MountMode.DIRECT_OVERLAY] writes straight into `/system`, which `disable` alone cannot
     * undo, so the installer must keep the originals for `post-fs-data.sh` to copy back.
     */
    @Test
    fun `direct overlay keeps the originals for a boot-failure restore`() {
        val entries = pack(bundle(*jars().toTypedArray(), mountMode = MountMode.DIRECT_OVERLAY))
        val customize = text(entries, "customize.sh")

        val backupAt = customize.indexOf("stock_backup/framework/framework.jar")
        val copyAt = customize.indexOf("cp -f \"\$MODPATH/system/framework/framework.jar\" \"/system/framework/framework.jar\"")
        assertTrue(backupAt in 0 until copyAt, "the original must be saved before it is overwritten (backupAt=$backupAt copyAt=$copyAt)")
        assertContains(text(entries, "post-fs-data.sh"), "stock_backup")
    }

    @Test
    fun `magic mount needs no system copies`() {
        val customize = text(pack(bundle(*jars().toTypedArray())), "customize.sh")
        assertFalse(customize.contains("stock_backup"), customize)
    }

    /**
     * `/data/adb/kaorios` is a configuration bundle this module never creates — it belongs to the
     * reference installer. Removing it on uninstall would destroy another module's state as a
     * side effect of removing ours.
     */
    @Test
    fun `uninstall only clears our own residue`() {
        val script = text(pack(bundle(*jars().toTypedArray())), "uninstall.sh")

        assertFalse(script.contains("/data/adb/kaorios"), script)
        assertContains(script, "package_cache")
    }

    /**
     * The spoofed property set lives in exactly one place, and both operations read it.
     *
     * A reset and a clear driven by two copies of the list would drift, and the failure mode is
     * silent: a key the module set but no longer knows to delete stays overridden until reboot.
     */
    @Test
    fun `the property layer offers reset and clear over one list`() {
        val entries = pack(bundle(*jars().toTypedArray()))
        val props = text(entries, "props.sh")

        assertContains(props, "kaorios_props_reset()")
        assertContains(props, "kaorios_props_clear()")
        for (prop in KsuModuleBuilder.SPOOF_PROPS) {
            assertContains(props, prop)
        }

        val leaked = entries.keys
            .filter { it.endsWith(".sh") && it != "props.sh" }
            .filter { text(entries, it).contains("verifiedbootstate") }
        assertTrue(leaked.isEmpty(), "spoofed property list leaked into $leaked")

        val service = text(entries, "service.sh")
        assertContains(service, "props.sh")
        assertContains(service, "kaorios_props_reset")
    }

    /**
     * The action button is the module's only on-demand entry point, and there is no manager app
     * on the target device to press it — the app calls it with an explicit argument instead.
     */
    @Test
    fun `the action button dispatches reset and clear and purges the caches`() {
        val action = text(pack(bundle(*jars().toTypedArray())), "action.sh")

        assertContains(action, "MODE=")
        assertContains(action, "\"clear\"")
        assertContains(action, "kaorios_props_clear")
        assertContains(action, "service.sh")
        assertContains(action, "SKIP_BOOT_WAIT=1")
        // GMS caches its Play Integrity verdict here; a stale entry survives a prop change.
        assertContains(action, "pif.prop")
        assertContains(action, "pif.json")
        assertContains(action, "package_cache")
    }

    /**
     * `init` publishes `ro.*` before any module runs, so they are runtime overrides rather than
     * `system.prop` entries; the installer only ever stages files and must stay out of it.
     */
    @Test
    fun `the installer and system prop never touch runtime properties`() {
        val entries = pack(bundle(*jars().toTypedArray()))
        for (path in listOf("customize.sh", "system.prop", "post-fs-data.sh", "uninstall.sh")) {
            val script = text(entries, path)
            assertFalse(script.contains("resetprop"), path)
            assertFalse(script.contains("verifiedbootstate"), path)
        }
    }

    private companion object {
        /** Literal bytes of the APK Signing Block magic, `"APK Sig Block 42"`. */
        const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"
    }
}

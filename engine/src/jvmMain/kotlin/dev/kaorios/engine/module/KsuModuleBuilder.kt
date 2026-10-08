package dev.kaorios.engine.module

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * How the patched files reach `/system`.
 *
 * [MAGIC_MOUNT] is the default because KernelSU mounts a module's `system/` tree over the real
 * partition itself, with no installer-time copy and no external mount binary. The copies made
 * by [DIRECT_OVERLAY] land permanently, which breaks on read-only or verified partitions, and
 * [HYBRIDMOUNT] depends on a separate project being present.
 */
enum class MountMode { MAGIC_MOUNT, HYBRIDMOUNT, DIRECT_OVERLAY }

/**
 * Module id written into `module.prop`, and therefore the directory the manager installs into
 * (`/data/adb/modules/<id>`).
 *
 * Shared with the app, which invokes `action.sh` at that path directly — there is no manager
 * app on the target device to press the action button for it.
 */
const val MODULE_ID = "kaorios_patcher"

/**
 * Builds a flashable KernelSU/SukiSU module zip from patched artifacts.
 *
 * Layout follows the standard module format: `module.prop`, the `META-INF` pair every manager
 * expects, `customize.sh` for install-time work, the boot scripts (`service.sh`, `props.sh`,
 * `action.sh`, `post-fs-data.sh`, `uninstall.sh`), `system.prop`, and the patched files under
 * `system/` so the boot-time overlay picks them up. On top of the patches it ships the KaoriOS
 * Toolbox as a `priv-app`, with the matching `privapp-permissions` whitelist.
 *
 * `props.sh` is the module's property layer: it holds the spoofed key/value set plus the reset
 * and clear operations, `service.sh` runs the reset at every late start, and `action.sh` exposes
 * both on demand. There is no manager app on the target device to press the action button, so
 * the app invokes `action.sh` itself.
 */
class KsuModuleBuilder(private val moduleId: String = MODULE_ID) {

    data class Artifact(val source: File, val modulePath: String)

    data class Bundle(
        val versionName: String,
        val artifacts: List<Artifact>,
        val mountMode: MountMode = MountMode.MAGIC_MOUNT,
        /**
         * SHA-256 of each artifact *before* patching, keyed by its on-device path.
         *
         * The installer uses these to confirm it is running on the ROM the module was built for.
         * A module overwrites `framework.jar` at boot with no way to roll back, so applying one
         * built for a different build to a device whose stock file differs would boot-loop.
         */
        val stockDigests: Map<String, String> = emptyMap(),
        /** Build fingerprint the module was produced against, checked alongside the digests. */
        val buildFingerprint: String? = null,
        /**
         * Ships the KaoriOS Toolbox APK as `system/priv-app/` with its permission whitelist.
         *
         * The APK is a verbatim copy of the signed release artifact, never rebuilt, so it keeps
         * its APK Signing Scheme v2 block and `Package Manager` will publish it.
         */
        val includeToolbox: Boolean = true,
        /**
         * Streams this file to [TOOLBOX_APK_PATH] instead of the bundled resource.
         *
         * The app refreshes it from the GitHub release on every run so the module always ships
         * the APK the repo currently publishes; [build] re-checks the APK Signing Block marker,
         * so a bad download fails closed rather than producing an APK PackageManager drops.
         * `null` keeps the bundled copy (desktop CLI, tests, offline-with-no-cache).
         */
        val toolboxApk: File? = null,
    )

    fun build(bundle: Bundle, destination: File): File {
        rejectApks(bundle)
        requireMissingSources(bundle)
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putEntryText("module.prop", moduleProp(bundle))
            zip.putEntryText("customize.sh", customizeScript(bundle))
            zip.putEntryText("service.sh", serviceScript(bundle))
            zip.putEntryText("props.sh", propsScript())
            zip.putEntryText("action.sh", actionScript())
            zip.putEntryText("post-fs-data.sh", postFsData())
            zip.putEntryText("uninstall.sh", uninstallScript())
            zip.putEntryText("system.prop", SYSTEM_PROP)
            zip.putEntryText("META-INF/com/google/android/update-binary", UPDATE_BINARY)
            zip.putEntryText("META-INF/com/google/android/updater-script", UPDATER_SCRIPT)
            for (artifact in bundle.artifacts) {
                zip.putFile(artifact.source, artifact.modulePath)
            }
            if (bundle.includeToolbox) {
                val toolbox = bundle.toolboxApk
                if (toolbox != null) {
                    require(toolbox.isFile) { "toolbox apk not found: $toolbox" }
                    val bytes = toolbox.readBytes()
                    require(String(bytes, Charsets.ISO_8859_1).contains(APK_SIG_BLOCK_MAGIC)) {
                        "toolbox apk $toolbox carries no APK Signing Block — Package Manager " +
                            "would drop it with 'No APK Signature Scheme v2 signature', so it " +
                            "is not shipped"
                    }
                    zip.putNextEntry(ZipEntry(TOOLBOX_APK_PATH))
                    zip.write(bytes)
                    zip.closeEntry()
                } else {
                    zip.putResource(TOOLBOX_APK_PATH, TOOLBOX_APK_RESOURCE)
                }
                zip.putResource(TOOLBOX_PERMISSIONS_PATH, PERMISSIONS_RESOURCE)
            }
        }
        return destination
    }

    /**
     * Every path the module overlays under `/system`, in package order.
     *
     * [installCopies] and [hybridmountScript] both derive from this so neither can name a file the
     * zip does not actually carry.
     */
    private fun overlayPaths(bundle: Bundle): List<String> = buildList {
        addAll(bundle.artifacts.map { it.modulePath })
        if (bundle.includeToolbox) {
            add(TOOLBOX_APK_PATH)
            add(TOOLBOX_PERMISSIONS_PATH)
        }
    }

    private fun requireMissingSources(bundle: Bundle) {
        val missing = bundle.artifacts.filterNot { it.source.isFile }
        require(missing.isEmpty()) {
            "artifact source not found: " + missing.joinToString { "${it.modulePath} -> ${it.source}" }
        }
    }

    /**
     * Refuses to overlay *any* rebuilt APK.
     *
     * Re-zipping strips the APK Signing Scheme v2 block, `Package Manager` rejects the file
     * with "No APK Signature Scheme v2 signature", the package never publishes, and on this ROM
     * that left `system_server` blocked in `DeviceConfig.getLong` until the watchdog killed the
     * boot. Upstream v2.0.6.1 retired the one rebuilt APK we ever shipped (SettingsProvider)
     * entirely, so there is no escape hatch any more: a `.apk` handed in as a patched artifact
     * fails closed, no matter its path.
     *
     * The KaoriOS Toolbox is unaffected: [build] copies it straight from the signed release
     * artifact, so the v2 block survives untouched.
     */
    private fun rejectApks(bundle: Bundle) {
        val apks = bundle.artifacts.filter { it.modulePath.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return
        require(false) {
            "refusing to package " + apks.joinToString { it.modulePath } +
                ": a rebuilt APK loses its v2 signature and Package Manager drops it, which " +
                "blanked SettingsProvider and deadlocked system_server. SettingsProvider patching " +
                "was retired upstream at v2.0.6.1 — ship the stock APK, never a rebuilt one."
        }
    }

    private fun moduleProp(bundle: Bundle): String {
        val code = bundle.versionName.replace(Regex("[^0-9]"), "").ifEmpty { "1" }
        return listOf(
            "id=$moduleId",
            "name=Kaorios Patcher",
            "version=v${bundle.versionName}",
            "versionCode=$code",
            "author=Kaorios",
            "description=Patched framework and services via ${bundle.mountMode.label}.",
            "minMagisk=20400",
            "ksu=1",
            "minKsu=10904",
            "sufs=1",
            "minSufs=10000",
            "minApi=31",
            "maxApi=37",
            "requireReboot=true",
        ).joinToString("\n", postfix = "\n")
    }

    /**
     * Shell fragment for [MountMode.DIRECT_OVERLAY], where the installer itself must copy.
     *
     * Kept separate from the templating above so the `$MODPATH` and `$(...)` forms stay
     * readable; Kotlin would otherwise treat them as string templates. Each parent directory is
     * created first because `/system/priv-app/KaoriosToolbox` does not exist on a stock ROM.
     */
    private fun installCopies(bundle: Bundle): String = buildString {
        appendLine("ui_print \"- Copying patched files into /system\"")
        appendLine("set_perm_recursive \$MODPATH/system 0 0 0755 0644")
        appendLine("# Keep the originals: post-fs-data.sh copies them back if a boot never completes.")
        appendLine("mkdir -p \"\$MODPATH/stock_backup\"")
        for (path in overlayPaths(bundle)) {
            val relative = path.removePrefix("system/")
            val parent = relative.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                appendLine("mkdir -p \"/system/$parent\"")
            }
            appendLine("mkdir -p \"\$MODPATH/stock_backup/$parent\"")
            appendLine("if [ -f \"/system/$relative\" ]; then cp -f \"/system/$relative\" \"\$MODPATH/stock_backup/$relative\"; fi")
            appendLine("cp -f \"\$MODPATH/system/$relative\" \"/system/$relative\"")
        }
    }.trimEnd()

    private fun customizeScript(bundle: Bundle): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# Kaorios Patcher module installer")
        appendLine()
        appendLine("ui_print \"- Arch: \$(getprop ro.product.cpu.abi)\"")
        appendLine("ui_print \"- Android: \$(getprop ro.build.version.release)\"")
        appendLine("ui_print \"- Mount: ${bundle.mountMode.label}\"")
        appendLine()
        appendLine("if [ \"\$(getprop ro.product.cpu.abi)\" != \"arm64-v8a\" ]; then")
        appendLine("  abort \"! This module targets arm64-v8a devices only\"")
        appendLine("fi")
        appendLine()
        // The guard has to run before any mount action. DIRECT_OVERLAY copies straight into
        // /system and HYBRIDMOUNT registers live VFS rules, so an abort issued afterwards would
        // find the stock jar already overwritten with no rollback path.
        appendLine(romGuard(bundle))
        appendLine()
        appendLine("# Files only; the boot scripts are sourced and executed, not read.")
        appendLine("ui_print \"- Setting permissions...\"")
        appendLine("set_perm_recursive \$MODPATH 0 0 0755 0644")
        for (script in BOOT_SCRIPTS) {
            appendLine("[ -f \"\$MODPATH/$script\" ] && chmod 0755 \"\$MODPATH/$script\"")
        }
        appendLine()
        when (bundle.mountMode) {
            MountMode.MAGIC_MOUNT -> {
                appendLine("# KernelSU mounts \$MODPATH/system over /system at boot.")
                appendLine("set_perm_recursive \$MODPATH/system 0 0 0755 0644")
                appendLine("ui_print \"- Magic mount active; no installer copy needed\"")
            }
            MountMode.HYBRIDMOUNT -> appendLine(hybridmountScript(bundle))
            MountMode.DIRECT_OVERLAY -> appendLine(installCopies(bundle))
        }
        appendLine()
        if (bundle.includeToolbox) {
            appendLine("# The Toolbox is an ordinary priv-app; 0644 is what Package Manager expects.")
            appendLine("if [ -d \"\$MODPATH/system/priv-app\" ]; then")
            appendLine("  set_perm_recursive \"\$MODPATH/system/priv-app\" 0 0 0755 0644")
            appendLine("fi")
            appendLine("if [ -d \"\$MODPATH/system/etc/permissions\" ]; then")
            appendLine("  set_perm_recursive \"\$MODPATH/system/etc/permissions\" 0 0 0755 0644")
            appendLine("fi")
            appendLine("ui_print \"- KaoriOS Toolbox staged as a priv-app\"")
            appendLine()
        }
        // framework.jar and services.jar changed underneath it, so the cached package metadata
        // has to go; keeping it leaves PackageManager reading dexes that no longer exist.
        appendLine("rm -rf /data/system/package_cache/*")
        appendLine()
        // Runs the Toolbox install fallback now, so the app is present before the first boot
        // that actually sees the overlay. In recovery `pm` is missing, hence the guard.
        appendLine("if [ -f \"\$MODPATH/service.sh\" ]; then")
        appendLine("  SKIP_BOOT_WAIT=1 . \"\$MODPATH/service.sh\" 2>/dev/null || true")
        appendLine("fi")
        appendLine()
        appendLine("ui_print \"- Patched classes: ${bundle.artifacts.size} artifact(s)\"")
        appendLine("ui_print \"- Done. Reboot to apply.\"")
    }

    /**
     * Shell fragment refusing to install unless the device matches what the module was built for.
     *
     * Only the two system jars are checked, and only when digests were supplied:
     * hashing ~88 MB inside the installer takes a few seconds, which is the price of not
     * boot-looping a device whose stock `framework.jar` differs. `sha256sum` is part of toybox and
     * therefore always present; the fallback keeps the check from hard-failing on a ROM where it
     * somehow is not.
     */
    private fun romGuard(bundle: Bundle): String {
        if (bundle.stockDigests.isEmpty()) {
            return "# No stock digests recorded; skipping ROM compatibility check."
        }
        return buildString {
            appendLine("# Refuse to apply to a ROM this module was not built for.")
            appendLine("# A mismatched framework.jar cannot be verified by ART and will not boot.")
            if (bundle.buildFingerprint != null) {
                appendLine("EXPECT_FINGERPRINT='${bundle.buildFingerprint}'")
                appendLine("if [ -n \"\$EXPECT_FINGERPRINT\" ]; then")
                appendLine("  ACTUAL_FINGERPRINT=\$(getprop ro.build.fingerprint)")
                appendLine("  if [ \"\$ACTUAL_FINGERPRINT\" != \"\$EXPECT_FINGERPRINT\" ]; then")
                appendLine("    ui_print \"! ROM mismatch\"")
                appendLine("    ui_print \"  expected: \$EXPECT_FINGERPRINT\"")
                appendLine("    ui_print \"  actual:   \$ACTUAL_FINGERPRINT\"")
                appendLine("    abort \"! This module was built for a different ROM\"")
                appendLine("  fi")
                appendLine("fi")
            }
            for ((devicePath, digest) in bundle.stockDigests) {
                appendLine("EXPECTED='$digest'")
                appendLine("if command -v sha256sum >/dev/null 2>&1; then")
                appendLine("  ACTUAL=\$(sha256sum '$devicePath' 2>/dev/null | cut -d' ' -f1)")
                appendLine("  if [ -n \"\$ACTUAL\" ] && [ \"\$ACTUAL\" != \"\$EXPECTED\" ]; then")
                appendLine("    ui_print \"! $devicePath does not match this build\"")
                appendLine("    ui_print \"  expected sha256: \$EXPECTED\"")
                appendLine("    ui_print \"  actual sha256:   \$ACTUAL\"")
                appendLine("    abort \"! Patched jar was built against a different system image\"")
                appendLine("  fi")
                appendLine("else")
                appendLine("  ui_print \"! sha256sum unavailable; cannot verify $devicePath\"")
                appendLine("fi")
            }
        }.trimEnd()
    }

    /**
     * VFS rules for [HYBRIDMOUNT], derived from [bundle] rather than hardcoded.
     *
     * Only files the zip actually carries get a rule, so the path list comes from
     * [overlayPaths] — naming a path the module does not ship would register a rule against a
     * missing file. Same rule set the [installCopies] branch applies for [DIRECT_OVERLAY].
     */
    private fun hybridmountScript(bundle: Bundle): String = buildString {
        appendLine("ui_print \"- Loading hybridmount and installing VFS rules\"")
        appendLine("for ko in /data/adb/kaorios/hybridmount/*.ko; do")
        appendLine("  [ -f \"\$ko\" ] || continue")
        appendLine("  insmod \"\$ko\" 2>/dev/null || true")
        appendLine("done")
        appendLine("if command -v hybridmount >/dev/null 2>&1; then")
        for (path in overlayPaths(bundle)) {
            val relative = path.removePrefix("system/")
            appendLine("  hybridmount vfs rule add --path /system/$relative --target \$MODPATH/$path")
        }
        appendLine("else")
        appendLine("  ui_print \"! hybridmount unavailable; falling back to magic mount\"")
        appendLine("fi")
    }.trimEnd()

    /**
     * Applies the spoofed property set once boot completes, then makes sure the Toolbox is
     * registered.
     *
     * Also sourced from `customize.sh` with `SKIP_BOOT_WAIT=1`, so both the property reset and
     * the fallback run during the flash itself. `$0` resolves to `customize.sh` while sourced
     * there, which puts `MODDIR` at `$MODPATH` — the same tree the APK and `props.sh` sit in.
     *
     * The property work lives in [propsScript] so the key/value list has exactly one home; a
     * reset and a clear driven by different lists would silently disagree about which properties
     * this module owns.
     */
    private fun serviceScript(bundle: Bundle): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# Kaorios Patcher late-start service")
        appendLine("MODDIR=\${0%/*}")
        appendLine()
        // Recovery and the action button both skip the wait; only boot needs it.
        appendLine("# Recovery and the action button both skip the wait; only boot needs it.")
        appendLine("if [ -z \"\$SKIP_BOOT_WAIT\" ]; then")
        appendLine("  until [ \"\$(getprop sys.boot_completed)\" = \"1\" ]; do")
        appendLine("    sleep 1")
        appendLine("  done")
        appendLine("  sleep 1")
        appendLine("  # Boot reached userspace: the pending marker set by post-fs-data.sh can go.")
        appendLine("  rm -f \"\$MODDIR/boot_pending\"")
        appendLine("fi")
        appendLine()
        appendLine("# Verified-boot / lock-state / tamper-flag props, re-applied every late start.")
        appendLine("# KernelSU resets ro.* to the ROM's own values on each boot, so this is not a one-off.")
        appendLine("if [ -f \"\$MODDIR/props.sh\" ]; then")
        appendLine("  . \"\$MODDIR/props.sh\"")
        appendLine("  kaorios_props_reset")
        appendLine("fi")
        if (bundle.includeToolbox) {
            appendLine()
            appendLine("# The priv-app overlay normally registers the Toolbox; install it as a user app")
            appendLine("# only when that did not happen.")
            appendLine("if ! pm path $TOOLBOX_PACKAGE >/dev/null 2>&1; then")
            appendLine("  [ -f \"\$MODDIR/$TOOLBOX_APK_PATH\" ] && \\")
            appendLine("    pm install -r \"\$MODDIR/$TOOLBOX_APK_PATH\" >/dev/null 2>&1")
            appendLine("fi")
        }
    }.trimEnd()

    /**
     * The spoofed property set and the two operations over it, emitted verbatim as `props.sh`.
     *
     * `reset` writes every key through `resetprop` — the only tool that can rewrite an `ro.*`
     * property after init has published it — and `clear` deletes the same keys again, which
     * hands them back to the ROM. Both are driven by [SPOOF_PROPS], so a property is only ever
     * cleared if it was also set, and vice versa.
     *
     * KernelSU ships `resetprop` beside `ksud`; Magisk and APatch keep it in their own binary
     * directories, and none of them guarantee it is on `PATH` for a module script, so the lookup
     * tries `PATH`, then the known locations, then searches as a last resort.
     */
    private fun propsScript(): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# Spoofed system properties: the list, plus the reset and clear operations.")
        appendLine("# Sourced by service.sh (every boot) and action.sh (on demand); not run directly.")
        appendLine()
        appendLine("KAORIOS_SPOOF_PROPS='")
        for (prop in SPOOF_PROPS) appendLine(prop)
        appendLine("'")
        appendLine()
        appendLine("kaorios_resetprop() {")
        appendLine("  if command -v resetprop >/dev/null 2>&1; then")
        appendLine("    echo resetprop")
        appendLine("    return 0")
        appendLine("  fi")
        appendLine("  for candidate in /data/adb/ksu/bin/resetprop \\")
        appendLine("                   /data/adb/ap/bin/resetprop \\")
        appendLine("                   /data/adb/magisk/resetprop; do")
        appendLine("    if [ -x \"\$candidate\" ]; then")
        appendLine("      echo \"\$candidate\"")
        appendLine("      return 0")
        appendLine("    fi")
        appendLine("  done")
        appendLine("  find /data/adb -name resetprop -type f 2>/dev/null | head -n 1")
        appendLine("}")
        appendLine()
        appendLine("kaorios_props_reset() {")
        appendLine("  tool=\"\$(kaorios_resetprop)\"")
        appendLine("  if [ -z \"\$tool\" ]; then")
        appendLine("    echo \"resetprop not found; properties left as the ROM set them\"")
        appendLine("    return 1")
        appendLine("  fi")
        appendLine("  for item in \$KAORIOS_SPOOF_PROPS; do")
        appendLine("    \"\$tool\" \"\${item%%=*}\" \"\${item#*=}\" 2>/dev/null")
        appendLine("  done")
        appendLine("  return 0")
        appendLine("}")
        appendLine()
        appendLine("kaorios_props_clear() {")
        appendLine("  tool=\"\$(kaorios_resetprop)\"")
        appendLine("  if [ -z \"\$tool\" ]; then")
        appendLine("    echo \"resetprop not found; nothing to clear\"")
        appendLine("    return 1")
        appendLine("  fi")
        appendLine("  for item in \$KAORIOS_SPOOF_PROPS; do")
        appendLine("    \"\$tool\" -d \"\${item%%=*}\" 2>/dev/null")
        appendLine("  done")
        appendLine("  return 0")
        appendLine("}")
    }.trimEnd()

    /**
     * Action button: the module's on-demand functions, plus the caches that still describe the
     * stock framework so a toggle takes effect without a reboot.
     *
     * The device has no KernelSU manager app, so nothing else can press this button — the app
     * and `su -c` invoke it directly. The manager would call it with no arguments, which is the
     * default branch; `action.sh clear` deletes the spoofed properties instead of applying them.
     */
    private fun actionScript(): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# KernelSU/APatch/Magisk action button. Optional argument: reset (default) | clear.")
        appendLine("MODDIR=\"\${0%/*}\"")
        appendLine("[ ! -f \"\$MODDIR/service.sh\" ] && MODDIR=\"/data/adb/modules/$moduleId\"")
        appendLine()
        appendLine("MODE=\"\${1:-reset}\"")
        appendLine()
        appendLine("if [ \"\$MODE\" = \"clear\" ]; then")
        appendLine("  echo \"Kaorios Patcher: clearing spoofed properties\"")
        appendLine("  if [ -f \"\$MODDIR/props.sh\" ]; then")
        appendLine("    . \"\$MODDIR/props.sh\"")
        appendLine("    kaorios_props_clear")
        appendLine("  else")
        appendLine("    echo \"props.sh not found in \$MODDIR\"")
        appendLine("  fi")
        appendLine("else")
        appendLine("  echo \"Kaorios Patcher: resetting spoofed properties\"")
        appendLine("  if [ -f \"\$MODDIR/service.sh\" ]; then")
        appendLine("    SKIP_BOOT_WAIT=1 . \"\$MODDIR/service.sh\"")
        appendLine("  else")
        appendLine("    echo \"service.sh not found in \$MODDIR\"")
        appendLine("  fi")
        appendLine("fi")
        appendLine()
        appendLine("# GMS caches its Play Integrity verdict here; stale entries survive a prop change.")
        appendLine("rm -f /data/data/com.google.android.gms/cache/pif.prop \\")
        appendLine("      /data/data/com.google.android.gms/pif.prop \\")
        appendLine("      /data/data/com.google.android.gms/cache/pif.json \\")
        appendLine("      /data/data/com.google.android.gms/pif.json")
        appendLine()
        appendLine("rm -rf /data/system/package_cache/*")
        appendLine("echo \"Kaorios Patcher: caches cleared\"")
    }.trimEnd()

    /**
     * Boot-loop protector: `post-fs-data.sh` arms `boot_pending` on every boot and
     * `service.sh` disarms it once `sys.boot_completed` is reached. A marker that survives
     * into the next boot means the previous one never finished, so the module disables
     * itself, restores any `/system` copies this module made, and reboots into stock.
     */
    private fun postFsData(): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# Boot-loop protector: armed here every boot, disarmed by service.sh at boot_completed.")
        appendLine("MODDIR=\${0%/*}")
        appendLine()
        appendLine("if [ -f \"\$MODDIR/boot_pending\" ]; then")
        appendLine("  echo \"kaorios: a boot never reached boot_completed; restoring stock\" >> \"\$MODDIR/boot_restore.log\"")
        appendLine("  rm -f \"\$MODDIR/boot_pending\"")
        appendLine("  touch \"\$MODDIR/disable\"")
        appendLine("  touch \"\$MODDIR/boot_restored\"")
        appendLine("  if [ -d \"\$MODDIR/stock_backup\" ]; then")
        appendLine("    mount -o remount,rw /system 2>/dev/null || true")
        appendLine("    cp -af \"\$MODDIR/stock_backup/.\" /system/ 2>/dev/null || true")
        appendLine("  fi")
        appendLine("  sync")
        appendLine("  reboot")
        appendLine("  exit 0")
        appendLine("fi")
        appendLine("touch \"\$MODDIR/boot_pending\"")
    }.trimEnd()

    /**
     * Drops the caches that still describe the patched jars, so the stock framework takes effect
     * again on the next boot.
     *
     * `/data/adb/kaorios` is deliberately left alone: this module never creates it, and deleting
     * it would wipe a configuration bundle belonging to a different module's install.
     */
    private fun uninstallScript(): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("rm -rf /data/system/package_cache/*")
    }

    private val MountMode.label: String
        get() = when (this) {
            MountMode.MAGIC_MOUNT -> "magic mount"
            MountMode.HYBRIDMOUNT -> "hybridmount"
            MountMode.DIRECT_OVERLAY -> "/system overlay"
        }

    private fun ZipOutputStream.putEntryText(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }

    /**
     * Streams a classpath resource straight into the zip.
     *
     * Used instead of [putEntryText] for anything whose bytes must survive exactly as stored —
     * the Toolbox APK in particular, since a single re-encoded byte would invalidate its
     * APK Signing Scheme v2 block.
     */
    private fun ZipOutputStream.putResource(path: String, resource: String) {
        val stream = KsuModuleBuilder::class.java.getResourceAsStream(resource)
            ?: error("module resource missing from classpath: $resource")
        stream.use { input ->
            putNextEntry(ZipEntry(path))
            input.copyTo(this)
            closeEntry()
        }
    }

    private fun ZipOutputStream.putFile(source: File, path: String) {
        putNextEntry(ZipEntry(path))
        source.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    companion object {
        /** Package the shipped Toolbox APK resolves to; `service.sh` probes it with `pm path`. */
        const val TOOLBOX_PACKAGE = "com.kousei.kaorios"

        /** Where the overlay must place the APK for `PackageManager` to publish it as a priv-app. */
        const val TOOLBOX_APK_PATH = "system/priv-app/KaoriosToolbox/KaoriosToolbox.apk"

        /** `privapp-permissions` whitelist required for the APK's privileged requests. */
        const val TOOLBOX_PERMISSIONS_PATH = "system/etc/permissions/com.kousei.kaorios.xml"

        const val TOOLBOX_APK_RESOURCE = "/module/KaoriosToolbox.apk"
        private const val PERMISSIONS_RESOURCE = "/module/com.kousei.kaorios.xml"

        /** Marker of an APK Signing Block; its presence is what keeps the APK publishable. */
        private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"

        /**
         * Verified-boot, lock-state and tamper-flag properties the module overrides through
         * `resetprop`, and the complete set `props.sh` knows how to undo.
         *
         * Same list the reference module applies, kept verbatim so a diff against it stays
         * meaningful. These are runtime overrides rather than `system.prop` entries: `ro.*` is
         * published by `init` before any module runs, and `system.prop` cannot replace a value
         * that is already set. Clearing a key hands it back to the ROM.
         */
        val SPOOF_PROPS = listOf(
            "ro.boot.verifiedbootstate=green",
            "ro.boot.veritymode=enforcing",
            "vendor.boot.vbmeta.device_state=locked",
            "ro.secureboot.lockstate=locked",
            "ro.boot.flash.locked=1",
            "ro.boot.vbmeta.device_state=locked",
            "ro.boot.selinux=enforcing",
            "sys.oem_unlock_allowed=0",
            "ro.boot.veritymode.managed=yes",
            "ro.debuggable=0",
            "ro.force.debuggable=0",
            "ro.secure=1",
            "ro.boot.realmebootstate=green",
            "ro.boot.warranty_bit=0",
            "ro.vendor.boot.warranty_bit=0",
            "ro.vendor.warranty_bit=0",
            "ro.warranty_bit=0",
            "ro.boot.realme.lockstate=1",
            "vendor.boot.verifiedbootstate=green",
        )

        /** Scripts that must end up executable; `set_perm_recursive` writes them 0644. */
        private val BOOT_SCRIPTS =
            listOf("service.sh", "props.sh", "action.sh", "post-fs-data.sh", "uninstall.sh")
    }
}

/**
 * Read by the package manager before `service.sh` ever runs, so it is a plain file rather than
 * something the installer writes.
 *
 * `ro.control_privapp_permissions=` (empty) turns off privapp permission enforcement, which is
 * what the reference module ships and what guarantees the Toolbox acquires its privileged
 * requests on a ROM whose own whitelist does not cover them.
 */
private val SYSTEM_PROP: String
    get() = listOf(
        "# Applied by the module installer at flash time.",
        "ro.control_privapp_permissions=",
        "persist.sys.kaorios=kousei",
    ).joinToString("\n", postfix = "\n")

/** Magisk's module stub: a manager invokes it with `<update-binary> <api> <zip>`. */
private val UPDATE_BINARY: String
    get() = listOf(
        "#!/sbin/sh",
        "umask 022",
        "ui_print() { echo \"\$1\"; }",
        "",
        "require_new_magisk() {",
        "  ui_print \"*******************************\"",
        "  ui_print \" Please install Magisk v20.4+! \"",
        "  ui_print \"*******************************\"",
        "  exit 1",
        "}",
        "",
        "OUTFD=\$2",
        "ZIPFILE=\$3",
        "",
        "mount /data 2>/dev/null",
        "",
        "[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk",
        ". /data/adb/magisk/util_functions.sh",
        "[ \$MAGISK_VER_CODE -lt 20400 ] && require_new_magisk",
        "",
        "install_module",
        "exit 0",
    ).joinToString("\n", postfix = "\n")

/** Presence of this entry is what makes an archive look like a Magisk-format module. */
private val UPDATER_SCRIPT: String
    get() = "#MAGISK\n"

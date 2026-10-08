package dev.kaorios.patcher.pipeline

import dev.kaorios.engine.patch.PatchSelection
import dev.kaorios.engine.patch.PatchStatus

/**
 * Reports a completed run against the guides `:engine` is ported from:
 * `Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.1.md` (`framework.jar` §2-§6, `services.jar` §1-§3,
 * `Optional patches` §1-§2), `Disable_Secure_Flag.md` (DSV) and `CorePatch.md` (§1-§3) in
 * `hzzmonetvn/Kaorios-Toolbox`. All three were read at [REVISION]; each additional table adds
 * its own source, it does not move the revision.
 *
 * Reporting only. `PatchEngine` decides what gets patched and this never touches the patch set;
 * it exists so a `adb logcat -s KaoriosPatcher` capture can be checked against the guides without
 * opening them, and so a guide entry the ROM does not actually contain is loud instead of
 * silently missing from a list of successes.
 *
 * Which entries are expected follows the [PatchSelection]: the optional guides only when they
 * were selected. `SettingsProvider.smali` is never expected — upstream v2.0.6.1 retired provider
 * patching ("Keep the stock SettingsProvider. Fake Settings has been removed").
 */
object GuideCheck {

    /** Upstream revision this table was read from; bump it whenever the guide is re-read. */
    const val REVISION = "8c752fd"

    /** Guide `framework.jar` §2-§6, in guide order. §1 is the payload import, not a smali target. */
    private val FRAMEWORK_HOOKS = listOf(
        "Instrumentation.smali",
        "ActivityThread.smali",
        "ApplicationPackageManager.smali",
        "AndroidKeyStoreKeyPairGeneratorSpi.smali",
        "AndroidKeyStoreSpi.smali",
    )

    /** Guide `services.jar` §1-§3, in guide order; §2 and §3 both live in ComputerEngine. */
    private val SERVICES_HOOKS = listOf(
        "SystemServer.smali",
        "ComputerEngine.smali",
    )

    /** `Optional patches` §1, Hide developer/ADB status; only selected runs apply it. */
    private val HIDE_DEV = listOf("Settings\$NameValueCache.smali")

    /** `Optional patches` §2, the Android 17 Build patch; only mode 2/3 applies it. */
    private val BUILD_SPOOF = listOf("Build.smali", "Build\$VERSION.smali")

    /** `Disable_Secure_Flag.md`, the three DSV sites in guide order. */
    private val DSV = listOf(
        "DevicePolicyCacheImpl.smali",
        "WindowState.smali",
        "WindowManagerService.smali",
    )

    /**
     * CorePatch §3's one target — the only guide entry living in `miui-services.jar`, which
     * AOSP-derived ROMs do not ship at all, so it is expected only when that jar was pulled.
     */
    private const val MIUI_SERVICES_IMPL = "PackageManagerServiceImpl.smali"

    /** `CorePatch.md` §1 (`framework.jar`), §2 (`services.jar`), §3 (`miui-services.jar`). */
    private val COREPATCH = listOf(
        "PackageParser.smali",
        "PackageParser\$PackageParserException.smali",
        "PackageParser\$SigningDetails.smali",
        "SigningDetails.smali",
        "ApkSignatureSchemeV2Verifier.smali",
        "ApkSignatureSchemeV3Verifier.smali",
        "ApkSignatureVerifier.smali",
        "ApkSigningBlockUtils.smali",
        "StrictJarVerifier.smali",
        "StrictJarFile.smali",
        "ParsingPackageUtils.smali",
        "PackageManagerServiceUtils.smali",
        "KeySetManagerService.smali",
        "InstallPackageHelper.smali",
        "ReconcilePackageUtils.smali",
        MIUI_SERVICES_IMPL,
    )

    /**
     * Sites the guide itself lets a ROM skip, and therefore the only ones where `NOT_TARGET`
     * reports as `absent` instead of as a miss: DSV's third site ("the guide allows this site
     * to be skipped when a ROM carries no blocking code to find"), CorePatch §3 ("if present
     * in the ROM") and optional §1's `getStringForUser`, which the whole optional section lets
     * a ROM go without. Everything else that comes back `NOT_TARGET` is a contradiction between
     * the table and the patch set and is reported as such.
     */
    private val OPTIONAL = setOf("WindowManagerService.smali", MIUI_SERVICES_IMPL, HIDE_DEV.first())

    /** Guide entries deliberately not shipped here, with the reason. */
    private val EXCLUDED = mapOf(
        "SettingsProvider.smali" to
            "retired upstream at 8c752fd — the guide says keep the stock SettingsProvider, so " +
                "the APK is never patched, rebuilt or shipped",
        "WindowStateAnimator.smali" to
            "the DSV guide offers it only as an alternative to WindowState; this ROM's copy " +
                "declares neither isSecureLocked nor setSecureLocked (it is disassembled " +
                "anyway, so the absence is readable rather than assumed)",
    )

    private val OK = setOf(PatchStatus.PATCHED, PatchStatus.ALREADY_PATCHED)

    /**
     * Logs one line per guide entry and returns whether every shippable entry was patched.
     *
     * [selection] decides which guides are expected at all, and therefore which of their entries
     * count as a miss: a run that never asked for CorePatch is not failing to report CorePatch.
     *
     * @param outcomes everything the run produced, successes included, so an entry that was
     *   found but rejected (`UNSUPPORTED_LAYOUT` / `FAILED`) is reported as such rather than
     *   as absent.
     * @param miuiServices whether the ROM actually carries `miui-services.jar`. CorePatch §3 is
     *   the guide's one "if present in the ROM" section, and an AOSP ROM has no such jar, so
     *   its entry is not expected there instead of being counted missing.
     */
    fun report(
        selection: PatchSelection,
        outcomes: List<TargetOutcome>,
        miuiServices: Boolean = true,
    ): Boolean {
        val byFile = outcomes.groupBy { it.className.substringAfterLast('/') }
        val expected = buildList {
            if (selection.hooks) {
                addAll(FRAMEWORK_HOOKS)
                addAll(SERVICES_HOOKS)
            }
            if (selection.hideDevStatus) addAll(HIDE_DEV)
            if (selection.buildSpoof) addAll(BUILD_SPOOF)
            if (selection.flagSecure) addAll(DSV)
            if (selection.corePatch) {
                addAll(if (miuiServices) COREPATCH else COREPATCH - MIUI_SERVICES_IMPL)
            }
        }
        val sections = buildList {
            if (selection.hooks) add("Patch_Guide framework/services")
            if (selection.hideDevStatus) add("Patch_Guide optional §1 (Hide developer/ADB)")
            if (selection.buildSpoof) add("Patch_Guide optional §2 (Build)")
            if (selection.flagSecure) add("Disable_Secure_Flag")
            if (selection.corePatch) {
                add(if (miuiServices) "CorePatch §1-§3" else "CorePatch §1-§2 (no miui-services.jar)")
            }
        }.joinToString(", ")

        PatchLog.info("guide $REVISION conformance (${selection.describe()}; $sections)")
        if (selection.corePatch && !miuiServices) {
            PatchLog.info(
                "  skipped $MIUI_SERVICES_IMPL — the ROM carries no miui-services.jar, " +
                    "which is what CorePatch §3 is conditional on"
            )
        }
        var shippedOk = 0
        var absentOptional = 0
        val misses = mutableListOf<String>()

        for (file in expected) {
            val outcome = byFile[file]?.firstOrNull()
            val guideOptional = OPTIONAL.contains(file)
            when {
                outcome == null -> {
                    misses += file
                    PatchLog.warn("  MISSING  $file — not present in any pulled artifact")
                }
                OK.contains(outcome.status) -> {
                    shippedOk++
                    PatchLog.info("  ok       $file ${outcome.status} (${outcome.className})")
                }
                outcome.status == PatchStatus.NOT_TARGET && guideOptional -> {
                    absentOptional++
                    PatchLog.info("  absent   $file — the guide marks this site optional for this ROM")
                }
                outcome.status == PatchStatus.NOT_TARGET -> {
                    misses += file
                    PatchLog.warn("  ABSENT   $file — reported NOT_TARGET but this entry is not optional")
                }
                else -> {
                    misses += file
                    PatchLog.warn(
                        "  REJECTED $file ${outcome.status}" +
                            (outcome.detail?.let { " ($it)" } ?: "")
                    )
                }
            }
        }

        // A run only lists its exclusions when it would otherwise be expected to ship them.
        if (selection.hooks) {
            for ((file, reason) in EXCLUDED) {
                PatchLog.info("  excluded $file — $reason")
            }
        }

        val summary = buildString {
            append("guide $REVISION result: $shippedOk/${expected.size} shippable entries patched")
            if (absentOptional > 0) append(", $absentOptional optional site absent")
            if (misses.isNotEmpty()) append(", missing: ${misses.joinToString()}")
        }
        PatchLog.info(summary)
        return misses.isEmpty()
    }
}

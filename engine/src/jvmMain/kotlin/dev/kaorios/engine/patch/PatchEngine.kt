package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.UnsupportedLayoutException

/** One patchable file plus the functions that patch and verify it. */
data class PatchTarget(
    val fileName: String,
    val patch: PatchFn,
    val verify: (String) -> Unit
)

/** Aggregate result of a directory or single-file run. */
data class PatchReport(
    val outcomes: Map<String, PatchOutcome>,
    val ok: Boolean
) {
    val patched: List<String> get() = outcomes.filterValues { it.status == PatchStatus.PATCHED }.keys.sorted()
    val alreadyPatched: List<String> get() = outcomes.filterValues { it.status == PatchStatus.ALREADY_PATCHED }.keys.sorted()
    val unsupported: List<String> get() = outcomes.filterValues { it.status == PatchStatus.UNSUPPORTED_LAYOUT }.keys.sorted()
    val failed: List<String> get() = outcomes.filterValues { it.status == PatchStatus.FAILED }.keys.sorted()
}

/**
 * The seven core A17 hook targets, keyed by smali file name.
 *
 * Ported from the mode tables in `kaorios_patcher.py`: mode 1 is the hook set,
 * mode 2 the Build/Build$VERSION spoof, mode 3 both. SettingsProvider.smali dropped
 * out of mode 1 upstream at v2.0.6.1 along with the rest of provider patching.
 */
object PatchEngine {

    val HOOK_TARGETS: Map<String, PatchTarget> = linkedMapOf(
        "ActivityThread.smali" to PatchTarget(
            "ActivityThread.smali",
            ActivityThreadPatch::patch,
            ActivityThreadPatch::verify
        ),
        "ComputerEngine.smali" to PatchTarget(
            "ComputerEngine.smali",
            ComputerEnginePatch::patch,
            ComputerEnginePatch::verify
        ),
        "AppsFilterBase.smali" to PatchTarget(
            "AppsFilterBase.smali",
            AppsFilterBasePatch::patch,
            AppsFilterBasePatch::verify
        ),
        "SystemServer.smali" to PatchTarget(
            "SystemServer.smali",
            SystemServerPatch::patch,
            SystemServerPatch::verify
        ),
        "AndroidKeyStoreKeyPairGeneratorSpi.smali" to PatchTarget(
            "AndroidKeyStoreKeyPairGeneratorSpi.smali",
            KeyStoreGeneratorPatch::patch,
            KeyStoreGeneratorPatch::verify
        ),
        "AndroidKeyStoreSpi.smali" to PatchTarget(
            "AndroidKeyStoreSpi.smali",
            KeyStoreSpiPatch::patch,
            KeyStoreSpiPatch::verify
        ),
        "Instrumentation.smali" to PatchTarget(
            "Instrumentation.smali",
            InstrumentationPatch::patch,
            InstrumentationPatch::verify
        ),
        "ApplicationPackageManager.smali" to PatchTarget(
            "ApplicationPackageManager.smali",
            PackageManagerPatch::patch,
            PackageManagerPatch::verify
        )
    )

    /**
     * `Optional patches` §1 of `Patch_Guide_2.0.6.1.md` — Hide developer/ADB status, one class in
     * `framework.jar`. The guide's only optional section without an upstream Python patcher, so
     * it travels behind [PatchSelection.hideDevStatus] rather than a mode and is disassembled
     * only when selected.
     */
    val HIDE_DEV_TARGETS: Map<String, PatchTarget> = linkedMapOf(
        "Settings\$NameValueCache.smali" to PatchTarget(
            "Settings\$NameValueCache.smali",
            NameValueCachePatch::patch,
            NameValueCachePatch::verify,
        ),
    )

    val BUILD_TARGETS: Map<String, PatchTarget> = linkedMapOf(
        "Build.smali" to PatchTarget("Build.smali", BuildSpoofPatch::patchBuild, BuildSpoofPatch::verifyBuild),
        "Build\$VERSION.smali" to PatchTarget(
            "Build\$VERSION.smali",
            BuildSpoofPatch::patchBuildVersion,
            BuildSpoofPatch::verifyBuildVersion
        )
    )

    /**
     * Classes named by `Toolbox-docs/V2.0.3+/Disable_Secure_Flag.md`, in guide order.
     *
     * The guide offers `WindowStateAnimator` as an alternative to `WindowState`; this ROM's copy
     * declares neither `isSecureLocked` nor `setSecureLocked`, so only `WindowState` ships as a
     * target. `WindowStateAnimator` stays in the list above purely so a run disassembles it and
     * the absence can be read in a report rather than assumed.
     */
    val DSV_FILES = listOf(
        "DevicePolicyCacheImpl.smali",
        "WindowState.smali",
        "WindowStateAnimator.smali",
        "WindowManagerService.smali",
    )

    /**
     * Classes named by `Toolbox-docs/V2.0.3+/CorePatch.md`, in guide order — §1 `framework.jar`,
     * §2 `services.jar`, §3 `miui-services.jar`.
     */
    val COREPATCH_FILES = listOf(
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
        "PackageManagerServiceImpl.smali",
    )

    /** The subset of [DSV_FILES] this build knows how to patch. */
    val DSV_TARGETS: Map<String, PatchTarget> = linkedMapOf(
        "DevicePolicyCacheImpl.smali" to PatchTarget(
            "DevicePolicyCacheImpl.smali",
            SecureFlagPatch::patchDevicePolicyCache,
            SecureFlagPatch::verifyDevicePolicyCache,
        ),
        "WindowState.smali" to PatchTarget(
            "WindowState.smali",
            SecureFlagPatch::patchWindowState,
            SecureFlagPatch::verifyWindowState,
        ),
        "WindowManagerService.smali" to PatchTarget(
            "WindowManagerService.smali",
            SecureFlagPatch::patchCaptureDisplay,
            SecureFlagPatch::verifyCaptureDisplay,
        ),
    )

    /** The subset of [COREPATCH_FILES] this build knows how to patch: §1, §2 and §3. */
    val COREPATCH_TARGETS: Map<String, PatchTarget> = linkedMapOf(
        "PackageParser.smali" to PatchTarget(
            "PackageParser.smali",
            CorePatchPatch::patchPackageParser,
            CorePatchPatch::verifyPackageParser,
        ),
        "PackageParser\$PackageParserException.smali" to PatchTarget(
            "PackageParser\$PackageParserException.smali",
            CorePatchPatch::patchPackageParserException,
            CorePatchPatch::verifyPackageParserException,
        ),
        "PackageParser\$SigningDetails.smali" to PatchTarget(
            "PackageParser\$SigningDetails.smali",
            CorePatchPatch::patchPackageParserSigningDetails,
            CorePatchPatch::verifyPackageParserSigningDetails,
        ),
        "SigningDetails.smali" to PatchTarget(
            "SigningDetails.smali",
            CorePatchPatch::patchSigningDetails,
            CorePatchPatch::verifySigningDetails,
        ),
        "ApkSignatureSchemeV2Verifier.smali" to PatchTarget(
            "ApkSignatureSchemeV2Verifier.smali",
            CorePatchPatch::patchApkSignatureSchemeV2Verifier,
            CorePatchPatch::verifyApkSignatureSchemeV2Verifier,
        ),
        "ApkSignatureSchemeV3Verifier.smali" to PatchTarget(
            "ApkSignatureSchemeV3Verifier.smali",
            CorePatchPatch::patchApkSignatureSchemeV3Verifier,
            CorePatchPatch::verifyApkSignatureSchemeV3Verifier,
        ),
        "ApkSignatureVerifier.smali" to PatchTarget(
            "ApkSignatureVerifier.smali",
            CorePatchPatch::patchApkSignatureVerifier,
            CorePatchPatch::verifyApkSignatureVerifier,
        ),
        "ApkSigningBlockUtils.smali" to PatchTarget(
            "ApkSigningBlockUtils.smali",
            CorePatchPatch::patchApkSigningBlockUtils,
            CorePatchPatch::verifyApkSigningBlockUtils,
        ),
        "StrictJarVerifier.smali" to PatchTarget(
            "StrictJarVerifier.smali",
            CorePatchPatch::patchStrictJarVerifier,
            CorePatchPatch::verifyStrictJarVerifier,
        ),
        "StrictJarFile.smali" to PatchTarget(
            "StrictJarFile.smali",
            CorePatchPatch::patchStrictJarFile,
            CorePatchPatch::verifyStrictJarFile,
        ),
        "ParsingPackageUtils.smali" to PatchTarget(
            "ParsingPackageUtils.smali",
            CorePatchPatch::patchParsingPackageUtils,
            CorePatchPatch::verifyParsingPackageUtils,
        ),
        "PackageManagerServiceUtils.smali" to PatchTarget(
            "PackageManagerServiceUtils.smali",
            CorePatchPatch::patchPackageManagerServiceUtils,
            CorePatchPatch::verifyPackageManagerServiceUtils,
        ),
        "KeySetManagerService.smali" to PatchTarget(
            "KeySetManagerService.smali",
            CorePatchPatch::patchKeySetManagerService,
            CorePatchPatch::verifyKeySetManagerService,
        ),
        "InstallPackageHelper.smali" to PatchTarget(
            "InstallPackageHelper.smali",
            CorePatchPatch::patchInstallPackageHelper,
            CorePatchPatch::verifyInstallPackageHelper,
        ),
        "ReconcilePackageUtils.smali" to PatchTarget(
            "ReconcilePackageUtils.smali",
            CorePatchPatch::patchReconcilePackageUtils,
            CorePatchPatch::verifyReconcilePackageUtils,
        ),
        "PackageManagerServiceImpl.smali" to PatchTarget(
            "PackageManagerServiceImpl.smali",
            CorePatchPatch::patchPackageManagerServiceImpl,
            CorePatchPatch::verifyPackageManagerServiceImpl,
        ),
    )

    fun targets(selection: PatchSelection): Map<String, PatchTarget> = buildMap {
        if (selection.hooks) putAll(HOOK_TARGETS)
        if (selection.buildSpoof) putAll(BUILD_TARGETS)
        if (selection.corePatch) putAll(COREPATCH_TARGETS)
        if (selection.flagSecure) putAll(DSV_TARGETS)
        if (selection.hideDevStatus) putAll(HIDE_DEV_TARGETS)
    }

    fun targets(mode: PatchMode): Map<String, PatchTarget> = targets(PatchSelection.of(mode))

    /**
     * Every smali file the disassembler should produce for [selection].
     *
     * [targets] only names what this run *patches*, which is the whole class list once a guide is
     * fully ported but a strict subset while one is not. Enabling a guide also asks for the classes
     * it names so a run can be inspected — and reported on — before its patcher exists.
     */
    fun disassemblyFiles(selection: PatchSelection): Set<String> = buildSet {
        if (selection.hooks) addAll(HOOK_TARGETS.keys)
        if (selection.buildSpoof) addAll(BUILD_TARGETS.keys)
        if (selection.corePatch) addAll(COREPATCH_FILES)
        if (selection.flagSecure) addAll(DSV_FILES)
        if (selection.hideDevStatus) addAll(HIDE_DEV_TARGETS.keys)
    }

    fun disassemblyFiles(mode: PatchMode): Set<String> = disassemblyFiles(PatchSelection.of(mode))

    /**
     * Applies [mode] to [fileName]'s [content].
     *
     * Fails closed: the returned text is only modified when the target's own
     * verifier accepted the candidate output.
     */
    fun applyTargetPatch(fileName: String, content: String, targets: Map<String, PatchTarget>): PatchOutcome {
        val target = targets[fileName] ?: return PatchOutcome(PatchStatus.NOT_TARGET, content)
        return try {
            val result = target.patch(content)
            // A guide-skippable site that the ROM simply does not carry comes back byte-identical.
            // There is nothing to verify in text the patcher never touched — and its verifier is
            // written to recognise the injected block, which by definition is not there.
            if (result.status != PatchStatus.NOT_TARGET) target.verify(result.content)
            result
        } catch (e: UnsupportedLayoutException) {
            PatchOutcome(PatchStatus.UNSUPPORTED_LAYOUT, content, e.message)
        } catch (e: PatchVerificationException) {
            PatchOutcome(PatchStatus.FAILED, content, e.message)
        } catch (e: Exception) {
            PatchOutcome(PatchStatus.FAILED, content, e.message)
        }
    }

    /**
     * Runs every target found in [files] (a filename-to-text map, typically produced by
     * walking a disassembled smali tree). Returns an unsuccessful report if any target
     * errored or hit an unsupported layout.
     */
    fun run(mode: PatchMode, files: Map<String, String>): PatchReport {
        val targets = targets(mode)
        val outcomes = linkedMapOf<String, PatchOutcome>()
        for ((name, content) in files) {
            if (name !in targets) continue
            outcomes[name] = applyTargetPatch(name, content, targets)
        }
        val ok = outcomes.isNotEmpty() &&
            outcomes.values.none { it.status == PatchStatus.UNSUPPORTED_LAYOUT || it.status == PatchStatus.FAILED }
        return PatchReport(outcomes, ok)
    }
}
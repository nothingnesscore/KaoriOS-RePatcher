package dev.kaorios.engine.patch

/**
 * What a run is actually asked to patch.
 *
 * The guide's mode numbers are a poor fit for the app's UI: mode 1 is the hook set, mode 2 the
 * Build spoof and mode 3 both, but the shipped UI offers one fixed "KaoriOS" entry (hooks *and*
 * Build spoof, always together on A17) plus three independent optional patches. A selection of
 * booleans expresses that directly, while [PatchMode] stays as the CLI's and the tests' vocabulary.
 *
 * [hideDevStatus] has no mode: `Optional patches` §1 is a guide-level option upstream's mode
 * tables never carried, so `of(mode)` never sets it and `toMode()` is unaffected by it.
 *
 * There is no SettingsProvider switch: upstream v2.0.6.1 removed provider patching entirely
 * ("Fake Settings has been removed. Use stock SettingsProvider.apk; patch framework.jar and
 * services.jar only."), so the stock APK is never patched, rebuilt, signed or shipped.
 */
data class PatchSelection(
    val hooks: Boolean = true,
    val buildSpoof: Boolean = true,
    val corePatch: Boolean = false,
    val flagSecure: Boolean = false,
    val hideDevStatus: Boolean = false,
) {
    /** False only for a run that injects no call site — a pure Build spoof needs no runtime dex. */
    val needsRuntime: Boolean get() = hooks || hideDevStatus

    fun toMode(): PatchMode = when {
        corePatch && flagSecure && hooks && buildSpoof -> PatchMode.FULL
        !hooks && buildSpoof -> PatchMode.BUILD_SPOOF
        hooks && !buildSpoof && !corePatch && !flagSecure -> PatchMode.HOOKS
        else -> PatchMode.ALL_IN_ONE
    }

    companion object {
        /** The CLI's mode tables. */
        fun of(mode: PatchMode): PatchSelection = when (mode) {
            PatchMode.HOOKS -> PatchSelection(buildSpoof = false)
            PatchMode.BUILD_SPOOF -> PatchSelection(hooks = false)
            PatchMode.ALL_IN_ONE -> PatchSelection()
            PatchMode.FULL -> PatchSelection(corePatch = true, flagSecure = true)
        }

        /**
         * The selection for a detected device, following the guide's version tables: mode 1
         * (hooks) covers Android 13-17, while mode 2/3 (Build spoof) is A17-only — upstream's
         * `kaorios_patcher.py` refuses it on anything older, and so does this. A pre-17 device
         * therefore runs hooks alone and never has `Build` / `Build$VERSION` rewritten; the
         * engine still fails closed on any layout it does not recognise.
         */
        fun forAndroid(
            androidMajor: Int,
            corePatch: Boolean = false,
            flagSecure: Boolean = false,
            hideDevStatus: Boolean = false,
        ): PatchSelection = PatchSelection(
            hooks = true,
            buildSpoof = androidMajor >= 17,
            corePatch = corePatch,
            flagSecure = flagSecure,
            hideDevStatus = hideDevStatus,
        )
    }
}

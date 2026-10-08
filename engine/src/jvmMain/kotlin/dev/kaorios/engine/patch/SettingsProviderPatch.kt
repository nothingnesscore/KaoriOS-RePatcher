package dev.kaorios.engine.patch

/**
 * The SettingsProvider patch is retired as of upstream v2.0.6.1.
 *
 * The reference removed `patch_settings_provider` from `kaorios_patcher.py` in `8c752fd`
 * ("Remove SettingsProvider patching"): fake Settings are gone, the stock `SettingsProvider.apk`
 * must stay byte-identical, and patching happens in `framework.jar` / `services.jar` only. This
 * stub keeps the old entry point's contract — a plain exception whose message the status
 * classifier maps to FAILED — so any stray caller fails closed instead of silently patching.
 */
object SettingsProviderPatch {

    private const val RETIRED =
        "Fake Settings has been removed. Use stock SettingsProvider.apk; patch framework.jar and services.jar only."

    fun patch(@Suppress("UNUSED_PARAMETER") content: String): PatchOutcome {
        throw IllegalStateException(RETIRED)
    }

    fun verify(@Suppress("UNUSED_PARAMETER") content: String) {
        throw IllegalStateException(RETIRED)
    }
}

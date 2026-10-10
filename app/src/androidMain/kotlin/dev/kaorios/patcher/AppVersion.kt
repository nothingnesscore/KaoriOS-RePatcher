package dev.kaorios.patcher

import dev.kaorios.patcher.BuildConfig

/**
 * Short, human-readable version label derived from [BuildConfig.VERSION_NAME].
 *
 * A beta build (`1.2.0-beta.5`) renders as `beta.5`; a stable build (`1.2.0`) renders as
 * `stable.<versionCode>` so the number keeps incrementing across stable releases without
 * the user having to track the semver base.
 */
object AppVersion {

    /** `beta.5` from `1.2.0-beta.5`, `stable.3` from `1.2.0` + versionCode 3. */
    val shortLabel: String
        get() = parseShortLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    /** True when this APK was built from a `-beta.N` version name. */
    val isBeta: Boolean
        get() = BuildConfig.VERSION_NAME.contains("-beta.")

    /** Pure function so tests can pin the parsing without a device. */
    fun parseShortLabel(versionName: String, versionCode: Int): String {
        val beta = Regex("""-beta\.(\d+)""").find(versionName)
        return if (beta != null) "beta.${beta.groupValues[1]}" else "stable.$versionCode"
    }
}

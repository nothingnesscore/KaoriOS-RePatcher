package dev.kaorios.patcher.device

/** Snapshot of the host device, gathered once per refresh. */
data class DeviceSpec(
    val arch: String,
    val abi: String,
    val sdkInt: Int,
    val androidVersion: String,
    val fingerprint: String,
    val kernelRelease: String,
    val rootFlavour: RootFlavour,
    val rootVersion: String,
    /** Which ROM family the device runs: Xiaomi's stack (`HOS`) or stock Android (`AOSP`). */
    val romType: RomType,
    /** HyperOS/MIUI version property (`OS4.0`, `V816`); empty when the ROM publishes none. */
    val hosVersion: String,
) {
    /** Marketing major derived from the API level: SDK 33 → Android 13 … SDK 37 → Android 17. */
    val androidMajor: Int get() = sdkInt - 20

    /** Android 17 / SDK 37 is the Build-spoof target; the hooks also cover 13-16. */
    val isAndroid17: Boolean get() = sdkInt >= 37

    /** The patch guides cover Android 13 (SDK 33) through 17 (SDK 37); anything else is out. */
    val isSupported: Boolean get() = sdkInt in 33..37

    /**
     * The ROM line the Device row shows, e.g. `HOS OS4.0`, `HOS V816` or `AOSP`.
     *
     * The labels arrive from resources so the acronyms stay translatable, while the version
     * itself is the raw property the ROM published.
     */
    fun romLabel(hosLabel: String, aospLabel: String): String = when (romType) {
        RomType.AOSP -> aospLabel
        RomType.HOS -> if (hosVersion.isEmpty()) hosLabel else "$hosLabel $hosVersion"
    }
}

/**
 * ROM families the guides are written against.
 *
 * `HOS` is MIUI/HyperOS — it publishes `ro.mi.*` / `ro.miui.*` properties and ships
 * `miui-services.jar`, the jar CorePatch §3 patches ("if present in the ROM"). `AOSP` is a
 * plain build (Lineage and friends): no such properties, no such jar, and §3 does not apply.
 */
enum class RomType { HOS, AOSP }

enum class RootFlavour { NONE, KERNELSU, SUKISU, MAGISK }

data class RootStatus(
    val available: Boolean,
    val flavour: RootFlavour,
    val version: String,
    val binDir: String?
) {
    companion object {
        val NONE = RootStatus(false, RootFlavour.NONE, "", null)
    }
}

package dev.kaorios.patcher.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kaorios.engine.module.KsuModuleBuilder
import dev.kaorios.engine.module.MountMode
import dev.kaorios.engine.patch.PatchSelection
import dev.kaorios.patcher.device.DeviceSpec
import dev.kaorios.patcher.device.RootStatus
import dev.kaorios.patcher.device.SukiSUClient
import dev.kaorios.patcher.pipeline.GuideCheck
import dev.kaorios.patcher.pipeline.PatchLog
import dev.kaorios.patcher.pipeline.PatchPipeline
import dev.kaorios.patcher.pipeline.PatchStep
import dev.kaorios.patcher.pipeline.TargetOutcome
import dev.kaorios.patcher.pipeline.describe
import dev.kaorios.patcher.storage.DownloadsStore
import dev.kaorios.patcher.storage.ReleaseSync
import dev.kaorios.patcher.workspace.Workspace
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of handing the finished zip to the root manager. */
sealed interface FlashState {
    data object Idle : FlashState
    data object Running : FlashState
    /** `ksud module install` output; truncated to its last line, which is the verdict. */
    data class Done(val detail: String) : FlashState
    data class Failed(val detail: String) : FlashState
}

/** Everything the UI renders. */
data class PatchUiState(
    val device: DeviceSpec? = null,
    val root: RootStatus = RootStatus.NONE,
    /** Whether the workspace holds `miui-services.jar`; AOSP ROMs carry no such file. */
    val hasMiuiJar: Boolean = false,
    /**
     * The three optional patches from the guide's `Optional patches` section, offered as switches.
     *
     * The main set is fixed: hooks always travel, and the Build spoof joins them on A17 —
     * modes 2/3 are A17-only, which is why there is no row for either (see [selection]).
     * [hideDevStatus] is `Optional patches` §1 (Hide developer/ADB status), the one optional
     * guide section upstream never shipped a mode table for.
     */
    val corePatch: Boolean = true,
    val flagSecure: Boolean = true,
    val hideDevStatus: Boolean = true,
    val enableVfs: Boolean = false,
    val step: PatchStep = PatchStep.Idle,
    val outcomes: List<TargetOutcome> = emptyList(),
    val workspaceBytes: Long = 0L,
    /** Classes the merged KaoriOS runtime dex provides; empty until a run completes. */
    val runtimeClasses: List<String> = emptyList(),
    /** Whether "All files access" is granted, i.e. `Download/` is reachable. */
    val downloadsGranted: Boolean = false,
    val flash: FlashState = FlashState.Idle,
) {
    /**
     * What the engine will be asked to patch, derived from the detected device.
     *
     * Hooks (guide mode 1) cover Android 13-17; the Build spoof (mode 2/3) is A17-only, so a
     * 13-16 device — or one whose properties have not been read yet — runs hooks alone.
     */
    val selection: PatchSelection
        get() = device?.let {
            PatchSelection.forAndroid(it.androidMajor, corePatch, flagSecure, hideDevStatus)
        } ?: PatchSelection(
                hooks = true,
                buildSpoof = false,
                corePatch = corePatch,
                flagSecure = flagSecure,
                hideDevStatus = hideDevStatus,
            )

    val busy: Boolean get() = step !is PatchStep.Idle && step !is PatchStep.Done && step !is PatchStep.Failed
    val moduleReady: Boolean get() = step is PatchStep.Done
    val flashBusy: Boolean get() = flash is FlashState.Running
}

class PatchViewModel(
    private val client: SukiSUClient = SukiSUClient(),
    private val workspace: Workspace,
    private val downloads: DownloadsStore,
) : ViewModel() {

    private val pipeline = PatchPipeline(workspace)

    /**
     * Refreshes the runtime dex and Toolbox APK from the GitHub release on every patch run.
     *
     * Both are release assets that upstream replaces between app builds, so the workspace copy
     * is re-synced instead of shipping a snapshot frozen at app-build time; the last good sync
     * stays in the workspace as the offline fallback.
     */
    private val releaseSync = ReleaseSync(workspace.assetsDir)

    private val _state = MutableStateFlow(PatchUiState())
    val state: StateFlow<PatchUiState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** A copy of [PatchLog]'s tail, so a run can be read back on screen without logcat. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    init {
        PatchLog.attach(workspace.logDir.resolve(LOG_FILE))
        PatchLog.setListener { _log.value = it }
    }

    override fun onCleared() {
        PatchLog.setListener(null)
        PatchLog.close()
    }

    fun refresh() {
        viewModelScope.launch {
            val spec = withContext(Dispatchers.IO) { client.collectDeviceSpec() }
            val root = withContext(Dispatchers.IO) { client.rootStatus(refresh = true) }
            _state.update {
                it.copy(
                    device = spec,
                    root = root,
                    workspaceBytes = workspace.totalBytes(),
                    downloadsGranted = downloads.granted,
                    hasMiuiJar = workspace.pulledFile(MIUI_SERVICES_JAR).isFile,
                )
            }
        }
    }

    /** Refreshes only the shared-storage grant, for when the user returns from Settings. */
    fun refreshStorageAccess() {
        viewModelScope.launch {
            _state.update { it.copy(downloadsGranted = downloads.granted) }
        }
    }

    /**
     * Reports a failure from a launched action without leaving the UI stuck on a spinner.
     */
    private fun fail(throwable: Throwable) {
        PatchLog.fail("step failed: ${throwable.message ?: throwable::class.simpleName}", throwable)
        _state.update {
            it.copy(
                step = PatchStep.Failed(throwable.message ?: throwable::class.simpleName.orEmpty()),
                workspaceBytes = workspace.totalBytes()
            )
        }
    }

    fun setCorePatch(enabled: Boolean) = _state.update { it.copy(corePatch = enabled) }

    fun setFlagSecure(enabled: Boolean) = _state.update { it.copy(flagSecure = enabled) }

    fun setHideDevStatus(enabled: Boolean) = _state.update { it.copy(hideDevStatus = enabled) }

    fun setVfs(enabled: Boolean) = _state.update { it.copy(enableVfs = enabled) }

    /**
     * Copies the session log out of app-private storage into `Download/`.
     *
     * The workspace file is only readable as root, so this is what makes a run pullable with a
     * plain `adb pull`; called automatically whenever a run, a flash or an action finishes.
     */
    fun exportLog() {
        val file = workspace.logDir.resolve(LOG_FILE)
        if (!file.isFile || file.length() == 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val exported = runCatching { downloads.export(file, LOG_EXPORT) }
            when {
                exported.isFailure ->
                    PatchLog.warn("log: export failed — ${exported.exceptionOrNull()?.message}")
                exported.getOrNull() == null ->
                    PatchLog.warn("log: export failed — ${downloads.explain(downloads.lastError)}")
                else -> PatchLog.info("log: exported to ${downloads.path}/$LOG_EXPORT")
            }
        }
    }

    /** Pulls the artifacts the engine needs, as root. */
    fun pullArtifacts() {
        viewModelScope.launch {
            _state.update { it.copy(step = PatchStep.Pulling(0, ARTIFACT_PATHS.size)) }
            try {
                withContext(Dispatchers.IO) {
                    val root = client.rootStatus()
                    PatchLog.info("pull: root=${root.flavour} available=${root.available}")
                    if (!root.available) {
                        error("No root manager available; install SukiSU first")
                    }
                    val artifacts = ARTIFACT_PATHS.entries.map { it.key to it.value }
                    for ((index, artifact) in artifacts.withIndex()) {
                        val (name, path) = artifact
                        _state.update { it.copy(step = PatchStep.Pulling(index, artifacts.size)) }
                        val target = workspace.pulledFile(name)
                        if (!client.fileExists(path)) {
                            if (name != MIUI_SERVICES_JAR) {
                                error("pull: $path does not exist on this device")
                            }
                            // CorePatch §3 is the guide's one "if present in the ROM" section and
                            // AOSP ships no miui-services.jar at all: drop a stale copy from an
                            // earlier pull rather than leave it to masquerade as this ROM's file.
                            target.delete()
                            PatchLog.info("pull: $path absent on this ROM — $name skipped")
                            continue
                        }
                        client.pull(path, target).getOrThrow()
                        PatchLog.info("pull: $path -> ${target.length()} bytes")
                    }
                }
            } catch (e: Exception) {
                fail(e)
                return@launch
            }
            _state.update {
                it.copy(
                    workspaceBytes = workspace.totalBytes(),
                    step = PatchStep.Idle,
                    hasMiuiJar = workspace.pulledFile(MIUI_SERVICES_JAR).isFile,
                )
            }
        }
    }

    /**
     * Artifact name to the on-device path it is pulled from.
     *
     * Also the key the module's compatibility guard hashes, so it is the single place a third
     * jar has to be added to.
     */
    private val ARTIFACT_PATHS = mapOf(
        "framework.jar" to "/system/framework/framework.jar",
        "services.jar" to "/system/framework/services.jar",
        MIUI_SERVICES_JAR to "/system/system_ext/framework/miui-services.jar",
    )

    /** Artifact file name to its path inside the module overlay. */
    private val modulePaths = mapOf(
        "framework.jar" to "system/framework/framework.jar",
        "services.jar" to "system/framework/services.jar",
        MIUI_SERVICES_JAR to "system/system_ext/framework/miui-services.jar",
    )

    /**
     * On-device path each artifact is pulled from, used by the module's compatibility guard.
     *
     * The digest is taken from the pulled copy, which is the stock file on the device being
     * patched, so the installer can refuse to apply the module to any other ROM.
     *
     * `miui-services.jar` lives on the `system_ext` partition; `/system/system_ext` is a symlink
     * to it, so both spell the same file and either resolves for the installer's `sha256sum`.
     */
    private val devicePaths = ARTIFACT_PATHS

    /**
     * Where the KaoriOS framework dex is looked for.
     *
     * The patched call sites link against `android.security.kaorios.KaoriosHook;`, which has to
     * be on the boot classpath, so the dex must be merged into `framework.jar`. It is built from a
     * private source repo and published as the `classes.dex` asset of the Kaorios-Toolbox GitHub
     * release, so it cannot be derived here and is supplied: push it with
     * `adb push kaorios.dex /sdcard/Download/` and it is picked up on the next run.
     */
    private val runtimeDexCandidates = listOf(
        "kaorios.dex",
        "kaorios-framework.dex",
        "framework-kaorios.dex",
    )

    private fun findRuntimeDex(): File? =
        downloads.findFirst(runtimeDexCandidates)?.also { it.setReadable(true) }

    /** SHA-256 of the pulled stock artifacts, keyed by on-device path. */
    private suspend fun stockDigests(names: Collection<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            names.mapNotNull { name ->
                val file = workspace.pulledFile(name)
                if (file.isFile) devicePaths[name]?.let { it to file.sha256() } else null
            }.toMap()
        }

    /**
     * Disassembles the pulled jars, patches the target classes, reassembles the dex files that
     * changed, and packages a flashable module into `Download/`.
     *
     * Fails closed: a target the engine rejects is left untouched, and a run that produced no
     * KaoriOS runtime is reported as a failure instead of emitting a module whose injected call
     * sites cannot resolve.
 */
    fun patchAndBuild() {
        viewModelScope.launch {
            PatchLog.marker("patch run")
            _state.update { it.copy(step = PatchStep.Syncing, flash = FlashState.Idle) }
            val present = modulePaths.keys
                .map { it to workspace.pulledFile(it) }
                .filter { it.second.isFile }
            _state.update { it.copy(hasMiuiJar = present.any { p -> p.first == MIUI_SERVICES_JAR }) }
            if (present.isEmpty()) {
                fail(IllegalStateException("Pull the system jars first"))
                return@launch
            }
            if (!downloads.granted) {
                fail(IllegalStateException(downloads.explain(null)))
                return@launch
            }
            // The selection is derived from the device, so an unknown one must not default into
            // a run — and a version the guides do not cover (outside SDK 33..37) is refused here
            // instead of being handed to the engine to fail halfway through the round trip.
            val device = _state.value.device
            if (device == null) {
                fail(IllegalStateException("Device properties not read yet — tap Refresh first"))
                return@launch
            }
            if (!device.isSupported) {
                fail(
                    IllegalStateException(
                        "Android ${device.androidVersion} (SDK ${device.sdkInt}) is outside the " +
                            "guide's 13-17 range; refusing to patch an unverified layout."
                    )
                )
                return@launch
            }
            PatchLog.info(
                "device: Android ${device.androidVersion} (SDK ${device.sdkInt}) " +
                    "${device.romType} ${device.hosVersion}".trimEnd() +
                    " supported=${device.isSupported}"
            )

            // Release assets first: the workspace copy is what ships unless the network says
            // otherwise. The pushed Download/ dex remains the last resort when both fail.
            val assets = withContext(Dispatchers.IO) { releaseSync.sync() }
            val runtime = assets.runtimeDex ?: withContext(Dispatchers.IO) { findRuntimeDex() }
            _state.update { it.copy(step = PatchStep.Disassembling()) }
            // Snapshotted once: the run is the selection the user pressed, even if a switch were
            // to move underneath it while the round trip is running.
            val selection = _state.value.selection
            PatchLog.info(
                "build: selection=${selection.describe()} mount=" +
                    (if (_state.value.enableVfs) "HYBRIDMOUNT" else "MAGIC_MOUNT") +
                    " inputs=${present.joinToString { it.first + ":" + it.second.length() }} " +
                    "runtime=" + (runtime?.let {
                        "${it.parentFile?.name}/${it.name}:${it.length()}"
                    } ?: "MISSING")
            )
            val roundTrip = try {
                withContext(Dispatchers.IO) {
                    pipeline.patchAndRepackage(present, selection, runtime) { step ->
                        _state.update { it.copy(step = step) }
                    }
                }
            } catch (e: Exception) {
                fail(e)
                return@launch
            }

            // Report before the early returns so a run that refuses to package still says which
            // guide entries it did and did not reach. CorePatch §3's entry is only expected when
            // the ROM actually carries the jar it lives in.
            val guideOk = GuideCheck.report(
                selection,
                roundTrip.outcomes,
                miuiServices = present.any { it.first == MIUI_SERVICES_JAR },
            )

            if (roundTrip.failures.isNotEmpty()) {
                _state.update {
                    it.copy(
                        outcomes = roundTrip.outcomes,
                        step = PatchStep.Failed(
                            "Refusing to package: ${roundTrip.failures.size} target(s) were left " +
                                "byte-identical — " +
                                roundTrip.failures.joinToString { f ->
                                    f.className + (f.detail?.let { d -> " ($d)" } ?: "")
                                } +
                                ". Shipping a half-applied patch set would silently drop hooks " +
                                "the runtime expects, so nothing was written."
                        ),
                    )
                }
                return@launch
            }

            if (roundTrip.artifacts.isEmpty()) {
                _state.update {
                    it.copy(
                        outcomes = roundTrip.outcomes,
                        step = PatchStep.Failed("No target could be patched; artifacts were left untouched."),
                    )
                }
                return@launch
            }

            // A pure Build spoof rewrites Build/Build$VERSION only and injects no call sites, so
            // it is the one selection that legitimately runs without a runtime dex. The shipped
            // UI never offers one — hooks and Build spoof travel together — but `PatchSelection`
            // still models it, and the check follows that rather than a mode the user cannot pick.
            val needsRuntime = selection.needsRuntime

            if (needsRuntime && runtime == null) {
                _state.update {
                    it.copy(
                        outcomes = roundTrip.outcomes,
                        step = PatchStep.Failed(
                            "KaoriOS runtime dex missing. The patched framework calls " +
                                "android.security.kaorios.KaoriosHook, which must be merged into " +
                                "framework.jar — reconnect to sync it from the release, or push " +
                                "it to ${downloads.path}/kaorios.dex, and run again."
                        ),
                    )
                }
                return@launch
            }

            if (needsRuntime && roundTrip.runtimeClasses.isEmpty()) {
                _state.update {
                    it.copy(
                        outcomes = roundTrip.outcomes,
                        step = PatchStep.Failed(
                            "KaoriOS runtime was supplied but nothing was merged: framework.jar " +
                                "was not among the rebuilt artifacts. The injected call sites would " +
                                "not resolve at boot, so no module was written."
                        ),
                    )
                }
                return@launch
            }

            val digests = stockDigests(roundTrip.artifacts.map { it.name })
            if (!guideOk) {
                PatchLog.warn("guide $GuideCheck.REVISION conformance failed — packaging anyway")
            }
            PatchLog.info(
                "stock digests: " + digests.entries.joinToString { "${it.key}=${it.value.take(16)}…" }
            )
            _state.update { it.copy(step = PatchStep.BuildingModule) }
            val zip = try {
                withContext(Dispatchers.IO) {
                    val bundle = KsuModuleBuilder.Bundle(
                        versionName = "1.0.0",
                        mountMode = if (_state.value.enableVfs) MountMode.HYBRIDMOUNT else MountMode.MAGIC_MOUNT,
                        artifacts = roundTrip.artifacts.map {
                            KsuModuleBuilder.Artifact(it, modulePaths.getValue(it.name))
                        },
                        stockDigests = digests,
                        buildFingerprint = _state.value.device?.fingerprint,
                        toolboxApk = assets.toolboxApk,
                    )
                    KsuModuleBuilder().build(bundle, workspace.moduleDir.resolve(MODULE_ZIP))
                }
            } catch (e: Exception) {
                fail(e)
                return@launch
            }

            val exported = withContext(Dispatchers.IO) {
                downloads.export(zip, MODULE_ZIP)
            }
            withContext(Dispatchers.IO) {
                PatchLog.info(
                    "module: ${zip.absolutePath} size=${zip.length()} " +
                        "sha256=${zip.sha256()} exported=${exported ?: "FAILED"}"
                )
            }
            PatchLog.info("runtime classes merged: ${roundTrip.runtimeClasses.size}")
            _state.update {
                it.copy(
                    outcomes = roundTrip.outcomes,
                    workspaceBytes = workspace.totalBytes(),
                    runtimeClasses = roundTrip.runtimeClasses,
                    downloadsGranted = true,
                    step = if (exported != null) {
                        PatchStep.Done(exported)
                    } else {
                        PatchStep.Failed(
                            "Module built at ${zip.absolutePath} but could not be copied to " +
                                "${downloads.path}: ${downloads.explain(downloads.lastError)}"
                        )
                    },
                )
            }
        }.invokeOnCompletion { exportLog() }
    }

    fun clearWorkspace() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { workspace.clear() }
            PatchLog.attach(workspace.logDir.resolve(LOG_FILE))
            PatchLog.reset()
            _state.update {
                it.copy(
                    outcomes = emptyList(),
                    step = PatchStep.Idle,
                    workspaceBytes = 0L,
                    hasMiuiJar = false,
                    flash = FlashState.Idle,
                )
            }
        }
    }

    /**
     * Hands the finished zip to the root manager, which installs it as a module.
     *
     * The exported copy in `Download/` is preferred because `ksud` reads it as root from a
     * world-readable mount, where the app-private workspace is only reachable if `su` may read
     * another uid's `app_data_file`. The workspace copy is the fallback for the case where the
     * export never happened.
     *
     * This is deliberately not automatic: overwriting `framework.jar` at boot has no rollback
     * path, so installing is always a separate, explicit action the user takes after the
     * offline gate has passed.
     */
    fun flashModule() {
        viewModelScope.launch {
            val done = _state.value.step as? PatchStep.Done
            val zip = listOfNotNull(done?.moduleZip, workspace.moduleDir.resolve(MODULE_ZIP))
                .firstOrNull { it.isFile }
            if (zip == null) {
                PatchLog.warn("flash: no module zip found")
                _state.update {
                    it.copy(flash = FlashState.Failed("No module zip found; build one first."))
                }
                return@launch
            }
            if (!_state.value.root.available) {
                PatchLog.warn("flash: root unavailable")
                _state.update { it.copy(flash = FlashState.Failed("Root is required to install a module.")) }
                return@launch
            }
            _state.update { it.copy(flash = FlashState.Running) }
            PatchLog.info("flash: ksud module install ${zip.absolutePath} (${zip.length()} bytes)")
            val result = withContext(Dispatchers.IO) { client.installModule(zip) }
            result.onSuccess { output ->
                PatchLog.info("flash ok: ${output.lineSequence().lastOrNull()?.trim()}")
            }.onFailure { error ->
                PatchLog.fail("flash failed: ${error.message}", error)
            }
            _state.update { state ->
                state.copy(
                    flash = result.fold(
                        onSuccess = { output ->
                            FlashState.Done(output.lineSequence().lastOrNull { it.isNotBlank() }
                                ?.trim().orEmpty().ifEmpty { "Module installed." })
                        },
                        onFailure = { error ->
                            FlashState.Failed(error.message ?: "ksud module install failed")
                        },
                    ),
                )
            }
        }.invokeOnCompletion { exportLog() }
    }
}

/**
 * The one artifact a ROM may or may not carry: CorePatch §3's jar lives in Xiaomi's
 * `miui-services`, which AOSP-derived ROMs do not ship at all.
 */
private const val MIUI_SERVICES_JAR = "miui-services.jar"

/** File name of the built module, both inside the workspace and in `Download/`. */
private const val MODULE_ZIP = "kaorios_patcher.zip"

/** Session log, kept in the workspace and mirrored to `Download/` under this name. */
private const val LOG_FILE = "patch.log"
private const val LOG_EXPORT = "kaorios_patcher.log"

/** Streams the digest so an 88 MB jar is never held in memory. */
private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
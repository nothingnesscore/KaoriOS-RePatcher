package dev.kaorios.patcher.ui.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kaorios.engine.patch.PatchStatus
import dev.kaorios.patcher.R
import dev.kaorios.patcher.device.DeviceSpec
import dev.kaorios.patcher.device.RomType
import dev.kaorios.patcher.device.RootFlavour
import dev.kaorios.patcher.device.RootStatus
import dev.kaorios.patcher.pipeline.PatchStep
import dev.kaorios.patcher.pipeline.TargetOutcome
import dev.kaorios.patcher.ui.PatchUiState
import dev.kaorios.patcher.ui.component.InfoRow
import dev.kaorios.patcher.ui.component.MonoText
import dev.kaorios.patcher.ui.component.PageColumn
import dev.kaorios.patcher.ui.component.SectionCard
import dev.kaorios.patcher.ui.component.SettingSwitch
import dev.kaorios.patcher.ui.component.StatusBadge
import dev.kaorios.patcher.ui.component.StatusTone
import dev.kaorios.patcher.ui.component.resolve
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PatchPage(
    state: PatchUiState,
    contentPadding: PaddingValues,
    scrollState: ScrollState,
    onCorePatchChange: (Boolean) -> Unit,
    onFlagSecureChange: (Boolean) -> Unit,
    onHideDevStatusChange: (Boolean) -> Unit,
    onVfsChange: (Boolean) -> Unit,
    onPull: () -> Unit,
    onPatch: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onGrantStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageColumn(
        contentPadding = contentPadding,
        scrollState = scrollState,
        modifier = modifier,
    ) {
        DeviceSection(state.device, state.root)
        if (!state.downloadsGranted) {
            StorageAccessSection(onGrantStorage)
        }
        InstructionsSection()
        MainPatchSection(state.selection.buildSpoof)
        // Whether CorePatch §3 has a jar to work on is a ROM capability first: HOS always
        // carries `miui-services.jar` (a pull confirms it), AOSP never does, and before the
        // device has been read neither has been ruled out. The workspace copy only *confirms*
        // it — an empty workspace must not claim the ROM lacks the file.
        ExtraPatchSection(
            state.corePatch,
            state.flagSecure,
            state.hideDevStatus,
            state.hasMiuiJar || state.device?.romType != RomType.AOSP,
            onCorePatchChange,
            onFlagSecureChange,
            onHideDevStatusChange,
        )
        VfsSection(state.enableVfs, state.device, onVfsChange)
        ActionSection(state, onPull, onPatch, onClear, onRefresh)
        StatusSection(state.step, state.workspaceBytes)
        if (state.outcomes.isNotEmpty()) {
            TargetSection(state.outcomes)
        }
    }
}

@Composable
private fun DeviceSection(device: DeviceSpec?, root: RootStatus) {
    SectionCard(
        title = stringResource(R.string.section_device),
        icon = MiuixIcons.SearchDevice,
    ) {
        if (device == null) {
            InfoRow(
                label = stringResource(R.string.label_device),
                value = stringResource(R.string.value_reading),
            )
        } else {
            // Four rows: arch, Android/support, ROM family and root. The kernel release
            // only matters to the VFS warning, which keeps it where it is used.
            InfoRow(label = stringResource(R.string.label_arch), value = device.arch)
            InfoRow(
                label = stringResource(R.string.label_android),
                value = "${device.androidVersion} (SDK ${device.sdkInt}) · " + stringResource(
                    if (device.isSupported) R.string.value_supported_yes else R.string.value_supported_no
                ),
            )
            InfoRow(
                label = stringResource(R.string.label_rom),
                value = device.romLabel(
                    hosLabel = stringResource(R.string.rom_hos),
                    aospLabel = stringResource(R.string.rom_aosp),
                ),
            )
            InfoRow(label = stringResource(R.string.label_root), value = root.displayValue())
        }
    }
}

/**
 * Shown only while "All files access" is missing.
 *
 * The runtime dex has to be read from `Download/` and the module has to be written back there, and
 * scoped storage blocks both, so this gate is not dismissible — the run fails without it.
 */
@Composable
private fun StorageAccessSection(onGrantStorage: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.section_storage_access),
        icon = MiuixIcons.Lock,
    ) {
        Text(
            text = stringResource(R.string.storage_access_summary),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body2,
        )
        Button(
            onClick = onGrantStorage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.storage_access_action))
        }
    }
}

/**
 * The part that is not a choice: hooks always ship, and the Build spoof joins them whenever the
 * device is A17 — mode 1 covers Android 13-17 while modes 2/3 are A17-only
 * (`PatchSelection.forAndroid`), so a pre-17 device is told it runs hooks alone.
 *
 * Shown as a fixed row rather than a dead switch so the absence of a control reads as intent.
 */
@Composable
private fun InstructionsSection() {
    SectionCard(
        title = stringResource(R.string.section_howto),
        icon = MiuixIcons.Info,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (step in listOf(
                R.string.howto_step_1,
                R.string.howto_step_2,
                R.string.howto_step_3,
                R.string.howto_step_4,
                R.string.howto_step_5,
            )) {
                Text(
                    text = stringResource(step),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
    }
}

@Composable
private fun MainPatchSection(buildSpoof: Boolean) {
    SectionCard(
        title = stringResource(R.string.section_patches_main),
        icon = MiuixIcons.Replace,
    ) {
        InfoRow(
            label = stringResource(R.string.patch_main_title),
            value = stringResource(
                if (buildSpoof) R.string.patch_main_summary else R.string.patch_main_summary_hooks
            ),
        )
    }
}

/** `Patch_Guide_2.0.6.1.md` `Optional patches`, each independently switchable. */
@Composable
private fun ExtraPatchSection(
    corePatch: Boolean,
    flagSecure: Boolean,
    hideDevStatus: Boolean,
    miuiJarExpected: Boolean,
    onCorePatchChange: (Boolean) -> Unit,
    onFlagSecureChange: (Boolean) -> Unit,
    onHideDevStatusChange: (Boolean) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.section_patches_extra),
        icon = MiuixIcons.Tune,
    ) {
        SettingSwitch(
            title = stringResource(R.string.patch_corepatch_title),
            summary = stringResource(
                if (miuiJarExpected) R.string.patch_corepatch_summary else R.string.patch_corepatch_summary_nojar
            ),
            checked = corePatch,
            onCheckedChange = onCorePatchChange,
            icon = MiuixIcons.Lock,
        )
        SettingSwitch(
            title = stringResource(R.string.patch_flagsecure_title),
            summary = stringResource(R.string.patch_flagsecure_summary),
            checked = flagSecure,
            onCheckedChange = onFlagSecureChange,
            icon = MiuixIcons.Report,
        )
        SettingSwitch(
            title = stringResource(R.string.patch_hideadb_title),
            summary = stringResource(R.string.patch_hideadb_summary),
            checked = hideDevStatus,
            onCheckedChange = onHideDevStatusChange,
            icon = MiuixIcons.Hide,
        )
    }
}

@Composable
private fun VfsSection(
    enabled: Boolean,
    device: DeviceSpec?,
    onChange: (Boolean) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.section_vfs),
        icon = MiuixIcons.Merge,
    ) {
        SettingSwitch(
            title = stringResource(R.string.vfs_title),
            summary = stringResource(R.string.vfs_summary),
            checked = enabled,
            onCheckedChange = onChange,
            icon = MiuixIcons.Merge,
        )
        val kernel = device?.kernelRelease
        if (enabled && kernel != null && !kernel.contains("android")) {
            Text(
                text = stringResource(R.string.vfs_kernel_warning, kernel),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote2,
            )
        }
    }
}

@Composable
private fun ActionSection(
    state: PatchUiState,
    onPull: () -> Unit,
    onPatch: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.section_actions),
        icon = MiuixIcons.Tasks,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRefresh,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_refresh))
                }
                Button(
                    onClick = onClear,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_clear))
                }
            }
            Button(
                onClick = onPull,
                enabled = !state.busy && state.root.available,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = MiuixIcons.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_pull))
            }
            Button(
                onClick = onPatch,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = MiuixIcons.Replace,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_patch))
            }
            if (!state.root.available) {
                Text(
                    text = stringResource(R.string.root_required),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
    }
}

@Composable
private fun StatusSection(step: PatchStep, workspaceBytes: Long) {
    SectionCard(
        title = stringResource(R.string.section_status),
        icon = MiuixIcons.Info,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(step.labelRes()),
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.body1,
            )
            Text(
                text = "%.1f MB".format(workspaceBytes / 1024.0 / 1024.0),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        if (step.isRunning()) {
            InfiniteProgressIndicator(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        when (step) {
            is PatchStep.Done -> MonoText(
                text = step.moduleZip.absolutePath,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MiuixTheme.colorScheme.primary,
            )
            is PatchStep.Failed -> Text(
                text = step.message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote2,
            )
            else -> Unit
        }
    }
}

@Composable
private fun TargetSection(outcomes: List<TargetOutcome>) {
    SectionCard(
        title = stringResource(R.string.section_targets),
        icon = MiuixIcons.Report,
    ) {
        outcomes.forEach { outcome ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MonoText(
                    text = outcome.className.substringAfterLast('/'),
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text = stringResource(outcome.status.labelRes()),
                    tone = outcome.status.tone(),
                )
            }
            outcome.detail?.let { detail ->
                Text(
                    text = detail,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
    }
}

private fun PatchStep.isRunning(): Boolean =
    this !is PatchStep.Idle && this !is PatchStep.Done && this !is PatchStep.Failed

private fun PatchStep.labelRes(): Int = when (this) {
    is PatchStep.Idle -> R.string.status_idle
    is PatchStep.Pulling -> R.string.status_pulling
    is PatchStep.Syncing -> R.string.status_syncing
    is PatchStep.Disassembling -> R.string.status_disassembling
    is PatchStep.Patching -> R.string.status_patching
    is PatchStep.Reassembling -> R.string.status_reassembling
    is PatchStep.BuildingModule -> R.string.status_building_module
    is PatchStep.Done -> R.string.status_done
    is PatchStep.Failed -> R.string.status_failed
}

private fun PatchStatus.labelRes(): Int = when (this) {
    PatchStatus.PATCHED -> R.string.target_patched
    PatchStatus.ALREADY_PATCHED -> R.string.target_already_patched
    else -> R.string.target_failed
}

private fun PatchStatus.tone(): StatusTone = if (
    this == PatchStatus.PATCHED || this == PatchStatus.ALREADY_PATCHED
) StatusTone.Primary else StatusTone.Error

private fun RootStatus.displayValue(): String = when {
    !available -> RootFlavour.NONE.name.lowercase().replaceFirstChar { it.uppercase() }
    else -> "${flavour.name.lowercase().replaceFirstChar { it.uppercase() }} ${version.take(24)}"
}
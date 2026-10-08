package dev.kaorios.patcher.ui.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kaorios.patcher.R
import dev.kaorios.patcher.pipeline.PatchStep
import dev.kaorios.patcher.ui.FlashState
import dev.kaorios.patcher.ui.PatchUiState
import dev.kaorios.patcher.ui.component.MonoText
import dev.kaorios.patcher.ui.component.PageColumn
import dev.kaorios.patcher.ui.component.SectionCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ModulePage(
    state: PatchUiState,
    log: List<String>,
    contentPadding: PaddingValues,
    scrollState: ScrollState,
    onFlash: () -> Unit,
    onExportLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageColumn(
        contentPadding = contentPadding,
        scrollState = scrollState,
        modifier = modifier,
    ) {
        ModuleOutputSection(state)
        FlashSection(state, onFlash)
        RuntimeSection(state)
        ContentsSection(state)
        FailedTargetsSection(state)
        LogSection(log, onExportLog)
    }
}

/**
 * Whether the KaoriOS runtime that the injected call sites link against is actually bundled.
 *
 * Every hook `invoke`s `Landroid/security/kaorios/KaoriosHook;`, which only exists if the runtime
 * dex was merged into `framework.jar`. Without it the module would fail ART verification at boot,
 * so this is stated plainly rather than inferred from the fact that a run succeeded.
 */
@Composable
private fun RuntimeSection(state: PatchUiState) {
    val bundled = state.runtimeClasses
    SectionCard(title = stringResource(R.string.module_runtime_title), icon = MiuixIcons.Layers) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (bundled.isEmpty()) {
                Text(
                    text = stringResource(R.string.module_runtime_missing_title),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body1,
                )
                Text(
                    text = stringResource(R.string.module_runtime_missing_summary),
                    modifier = Modifier.padding(top = 4.dp),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            } else {
                Text(
                    text = stringResource(R.string.module_runtime_bundled_title, bundled.size),
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.body1,
                )
                bundled.forEach { MonoText(text = it) }
            }
        }
    }
}

@Composable
private fun ContentsSection(state: PatchUiState) {
    SectionCard(title = stringResource(R.string.module_contents_title), icon = MiuixIcons.GridView) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            moduleContents(state).forEach { MonoText(text = it) }
        }
    }
}

@Composable
private fun ModuleOutputSection(state: PatchUiState) {
    val zip = (state.step as? PatchStep.Done)?.moduleZip
    SectionCard(title = stringResource(R.string.module_output_title), icon = MiuixIcons.FileDownloads) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (zip == null) {
                Text(
                    text = stringResource(R.string.module_none_title),
                    style = MiuixTheme.textStyles.body1,
                )
                Text(
                    text = stringResource(R.string.module_none_summary),
                    modifier = Modifier.padding(top = 4.dp),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            } else {
                Text(
                    text = stringResource(R.string.module_ready_title),
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.body1,
                )
                MonoText(
                    text = zip.absolutePath,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.module_ready_summary),
                    modifier = Modifier.padding(top = 4.dp),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
    }
}

/**
 * Installation is a separate, explicit step.
 *
 * `framework.jar` is overwritten at boot with no rollback path, so nothing here runs on its own:
 * the user picks when to hand the zip to the manager, after the offline gate has been read.
 * The route is `ksud module install`, because the KernelSU family ships that CLI and there is no
 * manager activity to launch — a clean device has the kernel side without any app installed.
 */
@Composable
private fun FlashSection(state: PatchUiState, onFlash: () -> Unit) {
    val zip = (state.step as? PatchStep.Done)?.moduleZip
    SectionCard(title = stringResource(R.string.module_flash_title), icon = MiuixIcons.Import) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (val flash = state.flash) {
                FlashState.Idle -> Text(
                    text = stringResource(R.string.module_flash_idle),
                    style = MiuixTheme.textStyles.body1,
                )
                FlashState.Running -> Text(
                    text = stringResource(R.string.module_flash_running),
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.body1,
                )
                is FlashState.Done -> {
                    Text(
                        text = stringResource(R.string.module_flash_done),
                        color = MiuixTheme.colorScheme.primary,
                        style = MiuixTheme.textStyles.body1,
                    )
                    MonoText(text = flash.detail, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        text = stringResource(R.string.module_flash_reboot),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
                is FlashState.Failed -> Text(
                    text = stringResource(R.string.module_flash_failed, flash.detail),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body1,
                )
            }
            if (zip != null) {
                MonoText(text = zip.absolutePath, modifier = Modifier.padding(top = 4.dp))
            }
            Button(
                onClick = onFlash,
                enabled = zip != null && !state.busy && !state.flashBusy && state.root.available,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Import,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (state.flashBusy) R.string.module_flash_running else R.string.module_flash_action
                    ),
                )
            }
            if (!state.root.available) {
                Text(
                    text = stringResource(R.string.module_flash_root_missing),
                    modifier = Modifier.padding(top = 6.dp),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
    }
}

@Composable
private fun FailedTargetsSection(state: PatchUiState) {
    val failed = state.outcomes.filterNot { it.ok }
    if (failed.isEmpty()) return

    SectionCard(title = stringResource(R.string.module_failed_title), icon = MiuixIcons.Report) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.module_failed_summary),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                style = MiuixTheme.textStyles.footnote2,
            )
            failed.forEach { outcome ->
                MonoText(
                    text = "${outcome.className} — ${outcome.status.name}",
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The pipeline's own log, tail-rendered so a run can be checked without logcat.
 *
 * The file backing it lives in the workspace, which only root can read, so [onExportLog] copies
 * it to `Download/` where a plain `adb pull` reaches it.
 */
@Composable
private fun LogSection(log: List<String>, onExportLog: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.section_patch_log),
        icon = MiuixIcons.File,
    ) {
        if (log.isEmpty()) {
            Text(
                text = stringResource(R.string.patch_log_empty),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                style = MiuixTheme.textStyles.footnote2,
            )
        } else {
            val scroll = rememberScrollState()
            val visible = log.takeLast(LogVisibleLines)
            LaunchedEffect(log) {
                withFrameNanos { }
                scroll.scrollTo(scroll.maxValue)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LogMaxHeight)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                visible.forEach { line ->
                    MonoText(text = line, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
            Button(
                onClick = onExportLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.FileDownloads,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.patch_log_export))
            }
        }
    }
}

private const val LogVisibleLines = 200
private val LogMaxHeight = 240.dp

/**
 * What the zip carries for *this* configuration.
 *
 * Derived from the selection rather than hard-coded so a run that shut CorePatch cannot list a
 * jar that was never packaged — `miui-services.jar` only appears when CorePatch §3 is being
 * applied to it *and* the ROM actually carries the jar (an AOSP build has none, so the file was
 * never even pulled). The scripts and the Toolbox entries are unconditional: the builder always
 * emits them.
 */
private fun moduleContents(state: PatchUiState): List<String> = buildList {
    add("system/framework/framework.jar  (+ Kaorios runtime dex)")
    add("system/framework/services.jar")
    if (state.corePatch && state.hasMiuiJar) {
        add("system/system_ext/framework/miui-services.jar  (CorePatch §3)")
    }
    add("system/priv-app/KaoriosToolbox/KaoriosToolbox.apk")
    add("system/etc/permissions/com.kousei.kaorios.xml")
    add("module.prop")
    add("customize.sh   (ROM digest guard, runs before the mount)")
    add("service.sh     (late-start Toolbox registration, applies props)")
    add("props.sh       (spoofed property set, reset and clear)")
    add("action.sh      (action button: reset/clear props, purge caches)")
    add("post-fs-data.sh")
    add("uninstall.sh")
    add("system.prop")
    add("META-INF/com/google/android/update-binary")
    add("META-INF/com/google/android/updater-script")
}

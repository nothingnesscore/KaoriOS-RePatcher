package dev.kaorios.patcher.ui.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kaorios.patcher.R
import dev.kaorios.patcher.ui.component.PageColumn
import dev.kaorios.patcher.ui.component.SectionCard
import dev.kaorios.patcher.ui.component.SettingRow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Update

/**
 * The "Manual Patching" tab: a view-only preview of the future manual flow.
 *
 * Every row is inert (`enabled = false`, no `onClick`) so nothing can be selected or run —
 * the tab exists to show what is coming (file-picker rows for the three jars by their exact
 * names, an OS type and an Android version target) while the automatic pipeline stays the
 * only path that patches anything. The "Coming soon" chip lives in the shell's title bar.
 */
@Composable
fun ManualPatchPage(
    contentPadding: PaddingValues,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    PageColumn(
        contentPadding = contentPadding,
        scrollState = scrollState,
        modifier = modifier,
    ) {
        SectionCard(
            title = stringResource(R.string.manual_jars_section),
            icon = MiuixIcons.File,
        ) {
            SettingRow(
                title = stringResource(R.string.manual_jar_framework),
                summary = stringResource(R.string.manual_jar_summary),
                icon = MiuixIcons.File,
                enabled = false,
            )
            SettingRow(
                title = stringResource(R.string.manual_jar_services),
                summary = stringResource(R.string.manual_jar_summary),
                icon = MiuixIcons.File,
                enabled = false,
            )
            SettingRow(
                title = stringResource(R.string.manual_jar_miui_services),
                summary = stringResource(R.string.manual_jar_summary),
                icon = MiuixIcons.File,
                enabled = false,
            )
        }
        SectionCard(
            title = stringResource(R.string.manual_target_section),
            icon = MiuixIcons.Tasks,
        ) {
            SettingRow(
                title = stringResource(R.string.manual_os_type),
                summary = stringResource(R.string.manual_os_value),
                icon = MiuixIcons.Info,
                enabled = false,
            )
            SettingRow(
                title = stringResource(R.string.manual_android_version),
                summary = stringResource(R.string.manual_android_value),
                icon = MiuixIcons.Update,
                enabled = false,
            )
        }
    }
}

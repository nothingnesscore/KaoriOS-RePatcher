package dev.kaorios.patcher.ui.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kaorios.patcher.AppVersion
import dev.kaorios.patcher.R
import dev.kaorios.patcher.device.RootFlavour
import dev.kaorios.patcher.ui.PatchUiState
import dev.kaorios.patcher.ui.component.LabelledRow
import dev.kaorios.patcher.ui.component.PageColumn
import dev.kaorios.patcher.ui.component.SectionCard
import dev.kaorios.patcher.ui.component.SettingDropdown
import dev.kaorios.patcher.ui.component.SettingRow
import dev.kaorios.patcher.ui.component.SettingSwitch
import dev.kaorios.patcher.ui.service.PrefsRepository
import dev.kaorios.patcher.ui.service.rememberBooleanPreference
import dev.kaorios.patcher.ui.service.rememberStringPreference
import dev.kaorios.patcher.ui.theme.PREF_AMOLED_BLACK
import dev.kaorios.patcher.ui.theme.PREF_DYNAMIC_COLOR
import dev.kaorios.patcher.ui.theme.PREF_EDGE_BLUR
import dev.kaorios.patcher.ui.theme.PREF_LIQUID_GLASS
import dev.kaorios.patcher.ui.theme.PREF_THEME_MODE
import dev.kaorios.patcher.ui.theme.PREF_UPDATE_CHANNEL
import dev.kaorios.patcher.ui.theme.THEME_MODE_DARK
import dev.kaorios.patcher.ui.theme.THEME_MODE_LIGHT
import dev.kaorios.patcher.ui.theme.THEME_MODE_SYSTEM
import dev.kaorios.patcher.ui.theme.CHANNEL_BETA
import dev.kaorios.patcher.ui.theme.CHANNEL_STABLE
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Unlock

private val ThemeModes = listOf(THEME_MODE_SYSTEM, THEME_MODE_LIGHT, THEME_MODE_DARK)
private val Channels = listOf(CHANNEL_BETA, CHANNEL_STABLE)

@Composable
fun SettingsPage(
    state: PatchUiState,
    prefs: PrefsRepository,
    contentPadding: PaddingValues,
    scrollState: ScrollState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PageColumn(
        contentPadding = contentPadding,
        scrollState = scrollState,
        modifier = modifier,
    ) {
        AppearanceSection(prefs)
        UpdatesSection(prefs)
        WorkspaceSection(state, onClear)
        EnvironmentSection(state)
    }
}

/**
 * One row per group, not one row per choice: the theme is a single value, so its row carries the
 * list popup that opens next to it instead of spending three card rows on the choices.
 */
@Composable
private fun AppearanceSection(prefs: PrefsRepository) {
    val themeMode = rememberStringPreference(prefs, PREF_THEME_MODE, THEME_MODE_SYSTEM)
    val dynamicColor = rememberBooleanPreference(prefs, PREF_DYNAMIC_COLOR, true)
    val amoledBlack = rememberBooleanPreference(prefs, PREF_AMOLED_BLACK, false)
    val liquidGlass = rememberBooleanPreference(prefs, PREF_LIQUID_GLASS, true)
    val edgeBlur = rememberBooleanPreference(prefs, PREF_EDGE_BLUR, true)
    val themeLabels = ThemeModes.map {
        stringResource(
            when (it) {
                THEME_MODE_LIGHT -> R.string.settings_theme_light
                THEME_MODE_DARK -> R.string.settings_theme_dark
                else -> R.string.settings_theme_system
            },
        )
    }

    SectionCard(title = stringResource(R.string.settings_appearance_title), icon = MiuixIcons.Theme) {
        SettingDropdown(
            title = stringResource(R.string.settings_theme_title),
            summary = stringResource(
                when (themeMode) {
                    THEME_MODE_LIGHT -> R.string.settings_theme_light_summary
                    THEME_MODE_DARK -> R.string.settings_theme_dark_summary
                    else -> R.string.settings_theme_system_summary
                },
            ),
            items = themeLabels,
            selectedIndex = ThemeModes.indexOf(themeMode).coerceIn(0, ThemeModes.lastIndex),
            onSelectedIndexChange = { index ->
                prefs.putString(PREF_THEME_MODE, ThemeModes[index])
            },
            icon = MiuixIcons.Theme,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_dynamic_color),
            summary = stringResource(R.string.settings_dynamic_color_summary),
            checked = dynamicColor,
            onCheckedChange = { prefs.putBoolean(PREF_DYNAMIC_COLOR, it) },
            icon = MiuixIcons.Image,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_amoled_black),
            summary = stringResource(R.string.settings_amoled_black_summary),
            checked = amoledBlack,
            onCheckedChange = { prefs.putBoolean(PREF_AMOLED_BLACK, it) },
            icon = MiuixIcons.Background,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_liquid_glass),
            summary = stringResource(R.string.settings_liquid_glass_summary),
            checked = liquidGlass,
            onCheckedChange = { prefs.putBoolean(PREF_LIQUID_GLASS, it) },
            icon = MiuixIcons.Layers,
        )
        SettingSwitch(
            title = stringResource(R.string.settings_edge_blur),
            summary = stringResource(R.string.settings_edge_blur_summary),
            checked = edgeBlur,
            onCheckedChange = { prefs.putBoolean(PREF_EDGE_BLUR, it) },
            icon = MiuixIcons.Tune,
        )
    }
}

@Composable
private fun UpdatesSection(prefs: PrefsRepository) {
    val channel = rememberStringPreference(prefs, PREF_UPDATE_CHANNEL, CHANNEL_BETA)
    val channelLabels = Channels.map {
        stringResource(
            when (it) {
                CHANNEL_STABLE -> R.string.channel_stable
                else -> R.string.channel_beta
            },
        )
    }

    SectionCard(title = stringResource(R.string.settings_updates_title), icon = MiuixIcons.Info) {
        SettingDropdown(
            title = stringResource(R.string.settings_channel_title),
            summary = stringResource(
                when (channel) {
                    CHANNEL_STABLE -> R.string.settings_channel_stable_summary
                    else -> R.string.settings_channel_beta_summary
                },
            ),
            items = channelLabels,
            selectedIndex = Channels.indexOf(channel).coerceIn(0, Channels.lastIndex),
            onSelectedIndexChange = { index ->
                prefs.putString(PREF_UPDATE_CHANNEL, Channels[index])
            },
            icon = MiuixIcons.Info,
        )
        LabelledRow(
            label = stringResource(R.string.label_version),
            value = AppVersion.shortLabel,
            icon = MiuixIcons.Info,
        )
    }
}

@Composable
private fun WorkspaceSection(state: PatchUiState, onClear: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_workspace_title), icon = MiuixIcons.Folder) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_workspace_size),
                summary = stringResource(
                    R.string.settings_workspace_summary,
                    "%.1f".format(state.workspaceBytes / 1024.0 / 1024.0),
                ),
                icon = MiuixIcons.Delete,
                onClick = onClear,
            )
        }
    }
}

@Composable
private fun EnvironmentSection(state: PatchUiState) {
    SectionCard(title = stringResource(R.string.settings_environment_title), icon = MiuixIcons.Info) {
        LabelledRow(
            label = stringResource(R.string.label_root),
            value = stringResource(state.root.flavour.labelRes()),
            icon = MiuixIcons.Unlock,
        )
        LabelledRow(
            label = stringResource(R.string.label_patcher),
            value = stringResource(R.string.engine_version),
            icon = MiuixIcons.Info,
        )
    }
}

private fun RootFlavour.labelRes(): Int = when (this) {
    RootFlavour.NONE -> R.string.root_none
    RootFlavour.KERNELSU -> R.string.root_kernelsu
    RootFlavour.SUKISU -> R.string.root_sukisu
    RootFlavour.MAGISK -> R.string.root_magisk
}
package dev.kaorios.patcher.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Semantic colour roles. Components resolve these instead of hardcoding colours. */
enum class StatusTone { Primary, Neutral, Error }

@Composable
fun StatusTone.resolve(): Color = when (this) {
    StatusTone.Primary -> MiuixTheme.colorScheme.primary
    StatusTone.Neutral -> MiuixTheme.colorScheme.onSurfaceSecondary
    StatusTone.Error -> MiuixTheme.colorScheme.error
}

private val BadgeShape = RoundedCornerShape(10.dp)

/**
 * Leading glyph for a row. A `null` icon renders nothing, so the slot keeps the row's rhythm
 * without leaving a gap.
 */
@Composable
private fun StartIcon(icon: ImageVector?) {
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
    }
}

/** A titled group of rows. Section headers are the only place a small title is used. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title = title, icon = icon)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

/**
 * The small-caps group title, optionally led by a glyph.
 *
 * With an icon the row supplies the padding `SmallTitle` would otherwise apply, so the title
 * lands on exactly the same baseline whether or not a glyph is present.
 */
@Composable
private fun SectionTitle(title: String, icon: ImageVector?) {
    if (icon == null) {
        SmallTitle(text = title, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        SmallTitle(text = title, insideMargin = PaddingValues(0.dp))
    }
}

/** Read-only label/value pair inside a [SectionCard]. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    BasicComponent(
        modifier = modifier,
        title = label,
        summary = value,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Tappable row with an explanatory summary. [enabled] renders Miuix's disabled colours. */
@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        startAction = { StartIcon(icon) },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        onClick = onClick,
        enabled = enabled,
    )
}

/** Row whose trailing control is a switch; tapping the row toggles it too. */
@Composable
fun SettingSwitch(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        startAction = { StartIcon(icon) },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        onClick = { onCheckedChange(!checked) },
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.padding(end = 16.dp),
            )
        },
    )
}

/**
 * Row whose trailing control is the selected value plus a chevron; tapping it opens a list
 * popup anchored to the row's own end slot.
 *
 * This is Miuix's `WindowDropdownPreference` under our standard row margins and leading icon,
 * so a dropdown choice looks exactly like the switch rows around it while the menu itself stays
 * library-owned (positioning, dim, back handling, haptics). The *window* variant is required,
 * not the overlay one: settings renders outside the shell's `Scaffold`, and `OverlayDropdownPreference`
 * parks its popup state in `LocalPopupStates` — a local the overlay never reads — so a tap there
 * could never open anything. The window variant renders through its own platform `Dialog`.
 */
@Composable
fun SettingDropdown(
    title: String,
    summary: String?,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    WindowDropdownPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        summary = summary,
        modifier = modifier,
        startAction = { StartIcon(icon) },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

/** Compact status pill, small enough to sit inline next to a label. */
@Composable
fun StatusBadge(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val accent = tone.resolve()
    Surface(
        modifier = modifier,
        shape = BadgeShape,
        color = accent.copy(alpha = 0.14f),
        contentColor = accent,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = accent,
            style = MiuixTheme.textStyles.footnote2,
        )
    }
}

@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MiuixTheme.colorScheme.dividerLine)
}

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MiuixTheme.textStyles.footnote1,
    )
}

@Composable
fun LabelledRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            style = MiuixTheme.textStyles.footnote1,
        )
        MonoText(text = value)
    }
}
package dev.kaorios.patcher.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kaorios.patcher.R
import dev.kaorios.patcher.storage.UpdateChecker
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * An inline banner shown when an update is available.
 *
 * Displays the new version, a channel badge (Beta/Stable), whether a re-patch is needed,
 * and a cross-channel caution when applicable. The user can install or dismiss.
 */
@Composable
fun UpdateBanner(
    update: UpdateChecker.Result,
    currentVersion: String,
    currentIsBeta: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.update_title),
        icon = MiuixIcons.Update,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Version line with channel badge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.update_version, update.versionName),
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.body1,
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(
                    text = stringResource(
                        if (update.isBeta) R.string.update_beta_badge else R.string.update_stable_badge
                    ),
                    tone = if (update.isBeta) StatusTone.Error else StatusTone.Primary,
                )
            }
            Text(
                text = stringResource(R.string.update_current, currentVersion),
                modifier = Modifier.padding(top = 2.dp),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                style = MiuixTheme.textStyles.footnote2,
            )

            // Re-patch indicator.
            if (update.requiresRepatch) {
                Text(
                    text = stringResource(R.string.update_repatch_title),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                )
                Text(
                    text = stringResource(R.string.update_repatch_body),
                    modifier = Modifier.padding(top = 2.dp),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            } else {
                Text(
                    text = stringResource(R.string.update_cosmetic_title),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.body2,
                )
                Text(
                    text = stringResource(R.string.update_cosmetic_body),
                    modifier = Modifier.padding(top = 2.dp),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }

            // Cross-channel caution.
            if (update.channelSwitch) {
                Text(
                    text = stringResource(
                        R.string.update_channel_caution,
                        if (update.isBeta) "beta" else "stable",
                        if (currentIsBeta) "beta" else "stable",
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }

            // Buttons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.update_action_install))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.update_action_later),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

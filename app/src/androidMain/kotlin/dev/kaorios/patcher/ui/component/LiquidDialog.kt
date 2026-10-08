package dev.kaorios.patcher.ui.component

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.kaorios.patcher.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The spring that flies a confirmation dialog in (and back out).
 *
 * Slightly under-damped on purpose: the panel overshoots its final scale by a few percent and
 * settles — the same "pop" the navbar's bubble has, so the fly-out reads as one material rather
 * than a fade. The motion itself is the HOS4 dialog fly-out: the panel drops from under the
 * title bar into place (negative `translationY` at `progress = 0`) instead of spreading from a
 * capsule at the centre.
 */
internal val DialogSpreadSpring =
    spring<Float>(dampingRatio = 0.72f, stiffness = 320f, visibilityThreshold = 0.001f)

/** How dark the recorded scrim under the dialog gets at full spread. */
internal const val DialogScrimAlpha = 0.42f

/** How far above its resting slot the panel starts its fly-out. */
private val DialogFlyOutDistance = 220.dp

/** What a confirmation dialog is asking about. Strings resolve inside the dialog. */
data class ConfirmSpec(
    @StringRes val title: Int,
    @StringRes val message: Int,
    @StringRes val confirmLabel: Int,
    /** Runs once the user confirms; the caller dismisses first so the exit never blocks it. */
    val action: () -> Unit,
)

/**
 * A confirmation dialog in the app's liquid-glass language: the panel is backdrop glass that
 * *flies out* from under the title bar into its resting slot — the HOS4 dialog motion — driven
 * by [progress], and gathers back the same way on dismiss.
 *
 * Layer rules, exactly like the navbar: [progress] is only ever read inside draw/layer scopes,
 * never composition, so the spring animates without recomposing a single node. The panel must be
 * composed **outside** the recorded region (the caller's job) — it samples [backdrop], which is
 * what makes the dimmed page show through its glass. Taps inside the panel are swallowed so the
 * caller's outside-tap layer below never fires from the dialog's own body; only [onDismiss]
 * (that layer, or the back key) and [onConfirm] (the buttons) close it.
 *
 * A null [backdrop] falls back to a plain surface fill with the same alpha spread — degraded,
 * never half-glass.
 */
@Composable
fun LiquidConfirmDialog(
    spec: ConfirmSpec,
    progress: Animatable<Float, AnimationVector1D>,
    backdrop: LayerBackdrop?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(spec.title)
    val message = stringResource(spec.message)
    val confirmLabel = stringResource(spec.confirmLabel)
    val cancelLabel = stringResource(R.string.dialog_cancel)
    val surface = MiuixTheme.colorScheme.surface
    val onSurface = MiuixTheme.colorScheme.onSurface
    val onSecondary = MiuixTheme.colorScheme.onSurfaceSecondary
    val density = LocalDensity.current
    val blurPx = with(density) { LiquidBlurRadius.toPx() }
    val flyOutPx = with(density) { DialogFlyOutDistance.toPx() }
    val refractionHeightPx = with(density) { DialogRefractionHeight.toPx() }
    val refractionAmountPx = with(density) { DialogRefractionAmount.toPx() }

    Column(
        modifier = modifier
            .width(300.dp)
            // Fly-out + dismiss fade, both outside the glass element so its clip moves with it.
            .graphicsLayer {
                val p = progress.value.coerceIn(0f, 1f)
                val scale = lerp(0.94f, 1f, p)
                scaleX = scale
                scaleY = scale
                alpha = p
                translationY = -(1f - p) * flyOutPx
            }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = {
                            // Constant 16% card corners: the motion is the fly-out, not a
                            // capsule that relaxes — the panel keeps one silhouette throughout.
                            RoundedCornerShape(percent = 16)
                        },
                        effects = {
                            val p = progress.value.coerceIn(0f, 1f)
                            vibrancy()
                            blur(blurPx)
                            lens(
                                refractionHeight = refractionHeightPx * p,
                                refractionAmount = refractionAmountPx * p,
                                chromaticAberration = true,
                            )
                        },
                        highlight = { Highlight(alpha = 0.9f * progress.value.coerceIn(0f, 1f)) },
                        shadow = { Shadow(alpha = 0.8f * progress.value.coerceIn(0f, 1f)) },
                        innerShadow = {
                            val p = progress.value.coerceIn(0f, 1f)
                            InnerShadow(radius = DialogInnerRadius * p, alpha = 0.45f * p)
                        },
                        onDrawSurface = {
                            drawRect(
                                surface.copy(
                                    alpha = DialogSurfaceAlpha *
                                        progress.value.coerceIn(0f, 1f),
                                ),
                            )
                        },
                    )
                } else {
                    Modifier.drawBehind {
                        val p = progress.value.coerceIn(0f, 1f)
                        if (p <= 0f) return@drawBehind
                        // Same constant 16% corner as the glass panel, scaled to the box.
                        val radius = (minOf(size.width, size.height) / 2f) * 0.32f
                        drawRoundRect(
                            color = surface.copy(alpha = 0.92f * p),
                            cornerRadius = CornerRadius(radius),
                        )
                    }
                },
            )
            // Swallow touches on the panel's own body so they never reach the dismiss layer.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        Text(
            text = title,
            color = onSurface,
            style = MiuixTheme.textStyles.title4,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            color = onSecondary,
            style = MiuixTheme.textStyles.body2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = cancelLabel,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = confirmLabel)
            }
        }
    }
}

/** Refraction the dialog's glass gains as it spreads — the material "forming". */
private val DialogRefractionHeight = 14.dp
private val DialogRefractionAmount = 16.dp
private val DialogInnerRadius = 14.dp
private const val DialogSurfaceAlpha = 0.5f

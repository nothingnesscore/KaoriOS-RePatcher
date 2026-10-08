package dev.kaorios.patcher.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.SnackbarData
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SnackbarShape = RoundedCornerShape(percent = 50)

/**
 * The snackbar restyled into the app's glass language: a capsule of backdrop glass that spreads
 * in on a spring rather than sliding in flat — the same material as the navbar and the confirm
 * dialogs, at snackbar scale.
 *
 * Composed through Miuix's `SnackbarHost` `snackbar` slot, so this replaces the default chip's
 * *visuals* only; the host still owns placement and dismissal timing. It must sit outside the
 * recorded region (its caller's job) because it samples [backdrop]. A null [backdrop] falls
 * back to a plain surface chip.
 */
@Composable
fun GlassSnackbar(
    data: SnackbarData,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(data) {
        progress.animateTo(
            1f,
            spring(dampingRatio = 0.7f, stiffness = 380f, visibilityThreshold = 0.001f),
        )
    }
    val surface = MiuixTheme.colorScheme.surface
    val onSurface = MiuixTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val blurPx = with(density) { LiquidBlurRadius.toPx() }
    val refractionHeightPx = with(density) { SnackbarRefractionHeight.toPx() }
    val refractionAmountPx = with(density) { SnackbarRefractionAmount.toPx() }

    Box(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .graphicsLayer {
                val p = progress.value
                val scale = lerp(0.82f, 1f, p)
                scaleX = scale
                scaleY = scale
                alpha = p.coerceIn(0f, 1f)
            }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { SnackbarShape },
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
                        highlight = {
                            Highlight(alpha = 0.85f * progress.value.coerceIn(0f, 1f))
                        },
                        shadow = { Shadow(alpha = 0.7f * progress.value.coerceIn(0f, 1f)) },
                        onDrawSurface = {
                            drawRect(
                                surface.copy(
                                    alpha = SnackbarSurfaceAlpha *
                                        progress.value.coerceIn(0f, 1f),
                                ),
                            )
                        },
                    )
                } else {
                    Modifier.background(surface.copy(alpha = 0.94f), SnackbarShape)
                },
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Text(
            text = data.visuals.message,
            color = onSurface,
            style = MiuixTheme.textStyles.body2,
            textAlign = TextAlign.Center,
        )
    }
}

private val SnackbarRefractionHeight = 10.dp
private val SnackbarRefractionAmount = 12.dp
private const val SnackbarSurfaceAlpha = 0.55f

package dev.kaorios.patcher.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop as LiquidBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as recordLiquidLayer
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberLiquidLayer
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Records the page so the floating glass surfaces can sample it.
 *
 * `null` when the feature is off or the device lacks runtime shader support, which is the signal
 * for callers to fall back to a plain surface colour rather than drawing a broken effect.
 */
@Composable
fun rememberLiquidBackdrop(enabled: Boolean): LiquidBackdrop? {
    val surfaceColor = MiuixTheme.colorScheme.surface
    return if (enabled && isRuntimeShaderSupported()) {
        rememberLiquidLayer {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }
}

/** Marks [content] as the region [rememberLiquidBackdrop] recorded. */
fun Modifier.recordLiquidBackdrop(backdrop: LiquidBackdrop?): Modifier =
    if (backdrop != null) recordLiquidLayer(backdrop) else this

/**
 * Liquid-glass fill for the floating navigation bar: a saturated, refracted backdrop with a rim
 * highlight and a soft shadow, tinted just enough to keep the labels legible over page content.
 *
 * [shape] must be a [CornerBasedShape] — the refraction shader is driven by the corner radii and
 * `lens` throws on anything else.
 */
@Composable
fun Modifier.liquidGlassBackground(
    backdrop: LiquidBackdrop,
    shape: CornerBasedShape,
): Modifier {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val density = LocalDensity.current
    val blurPx = with(density) { LiquidBlurRadius.toPx() }
    val refractionHeightPx = with(density) { LiquidRefractionHeight.toPx() }
    val refractionAmountPx = with(density) { LiquidRefractionAmount.toPx() }
    val shapeLambda: () -> Shape = remember(shape) { { shape } }
    val rim: () -> Highlight = remember { { Highlight(LiquidRimWidth, LiquidRimBlur) } }
    val shade: () -> Shadow = remember {
        { Shadow(radius = LiquidShadowRadius, color = LiquidShadowColor) }
    }

    return drawBackdrop(
        backdrop = backdrop,
        shape = shapeLambda,
        effects = {
            vibrancy()
            blur(blurPx)
            lens(refractionHeight = refractionHeightPx, refractionAmount = refractionAmountPx)
        },
        highlight = rim,
        shadow = shade,
        onDrawSurface = { drawRect(surfaceColor.copy(alpha = LiquidSurfaceAlpha)) },
    )
}

/** Which screen edge a [gradientBlurEdge] strip hangs from. */
enum class GradientEdge { Top, Bottom }

/**
 * Blurs a strip of the page along one screen edge and dissolves it into the page.
 *
 * The strip paints **only** blurred content: no tint, no scrim, no wash of the surface colour.
 * A gradient that darkens the edge is a vignette no matter how softly it fades, and it is what
 * made the old bands read as paint. What is left is the page itself going soft, masked by an
 * eased alpha ramp so it holds near the edge and reaches zero before the strip ends instead of
 * thinning evenly across the whole band.
 *
 * [GradientEdgeStrips] stacks three of these at increasing radius, which is what makes the blur
 * itself graduate: the deepest strip is light and long, the one at the screen edge is heavy and
 * short, and content dissolves through them instead of stepping from sharp to blurred at once.
 *
 * The strip must be composed outside the recorded region, exactly like the navigation bar —
 * otherwise it blurs its own pixels.
 */
@Composable
fun Modifier.gradientBlurEdge(
    backdrop: LiquidBackdrop,
    edge: GradientEdge,
    radius: Dp,
): Modifier {
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    val mask = remember(edge) { edgeMask(edge) }
    // Hoisted: the lambdas below run on every record of the backdrop.
    val maskPaint = remember { Paint() }

    return drawBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        effects = { blur(radiusPx) },
        highlight = null,
        shadow = null,
        onDrawBackdrop = { draw ->
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), maskPaint)
            draw()
            drawRect(
                brush = Brush.linearGradient(
                    *mask.toTypedArray(),
                    start = Offset.Zero,
                    end = Offset(0f, size.height),
                ),
                blendMode = BlendMode.DstIn,
            )
            drawContext.canvas.restore()
        },
    )
}

/**
 * Alpha profile of one strip, from the screen edge outwards.
 *
 * Convex rather than straight: a linear ramp spends most of its length at a middling opacity,
 * which is exactly what makes a band look painted on. Holding full strength for the first third
 * and then falling away keeps the deep blur where it belongs and leaves the inner half almost
 * clean.
 */
private fun edgeMask(edge: GradientEdge): List<Pair<Float, Color>> =
    if (edge == GradientEdge.Top) {
        listOf(0f to 1f, 0.30f to 0.68f, 0.55f to 0.34f, 0.78f to 0.11f, 1f to 0f)
    } else {
        listOf(0f to 0f, 0.22f to 0.11f, 0.45f to 0.34f, 0.70f to 0.68f, 1f to 1f)
    }.map { (stop, alpha) -> stop to Color.White.copy(alpha = alpha) }

/** One nested strip: how far it reaches and how much it softens what it covers. */
private data class EdgeLayer(val radius: Dp, val depth: Dp)

/**
 * The top and bottom graduated-blur strips, laid over the page but outside the recorded region.
 *
 * Three layers per edge, lightest and deepest first so the heavy one lands on top of the stack:
 * at the screen edge all three composite into a deep blur, halfway out only the light one is
 * left, and past that the mask has already reached zero. That is the profile iOS and HyperOS
 * use — the content is visibly softening as it approaches the edge, with nothing drawn over it.
 *
 * [bottomBarHeight] is the floating navigation bar's measured height so the lower strip rises
 * out of the bar rather than floating above it.
 *
 * [scrollState] gates *when* each edge draws: a strip only shows once content has scrolled past
 * its bound (top strip fades in over the first [EdgeScrollFade] of scroll-away from the top,
 * bottom strip fades out on approach to the end). The alpha is resolved in composition rather
 * than only inside `graphicsLayer`: a layer-only zero still draws, which leaves a fully faded
 * strip in the tree as a backdrop observer re-blurring every pager frame for pixels nobody
 * sees — the stutter this gate exists to remove. The cost is one small subtree recomposing per
 * scroll frame.
 */
@Composable
fun GradientEdgeStrips(
    backdrop: LiquidBackdrop,
    bottomBarHeight: Dp,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    val fadePx = with(density) { EdgeScrollFade.toPx() }
    Box(modifier = modifier) {
        val topAlpha = (scrollState.value / fadePx).coerceIn(0f, 1f)
        val bottomAlpha = ((scrollState.maxValue - scrollState.value) / fadePx)
            .coerceIn(0f, 1f)
        if (topAlpha > 0f) {
            EdgeLayers.forEach { layer ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(topInset + layer.depth)
                        .graphicsLayer { alpha = topAlpha }
                        .gradientBlurEdge(backdrop, GradientEdge.Top, layer.radius),
                )
            }
        }
        if (bottomAlpha > 0f) {
            EdgeLayers.forEach { layer ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(bottomBarHeight + layer.depth)
                        .graphicsLayer { alpha = bottomAlpha }
                        .gradientBlurEdge(backdrop, GradientEdge.Bottom, layer.radius),
                )
            }
        }
    }
}

/** Scroll distance over which an edge strip fades in or out. */
private val EdgeScrollFade = 48.dp

/** Draw order matters: the light, deep strip is laid down first and the heavy one rides it. */
private val EdgeLayers = listOf(
    EdgeLayer(radius = 7.dp, depth = 120.dp),
    EdgeLayer(radius = 16.dp, depth = 76.dp),
    EdgeLayer(radius = 30.dp, depth = 44.dp),
)

/** Shared blur radius: the bar and the bubble soften the backdrop by the same amount. */
internal val LiquidBlurRadius = 24.dp
private val LiquidRefractionHeight = 14.dp
private val LiquidRefractionAmount = 16.dp
private val LiquidRimWidth = 0.75.dp
private val LiquidRimBlur = 1.dp
private val LiquidShadowRadius = 20.dp
private val LiquidShadowColor = Color.Black.copy(alpha = 0.18f)
private const val LiquidSurfaceAlpha = 0.45f

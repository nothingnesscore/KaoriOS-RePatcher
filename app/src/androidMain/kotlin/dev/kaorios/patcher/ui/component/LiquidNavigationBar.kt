package dev.kaorios.patcher.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asAndroidRuntimeShader
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** One destination in the floating glass bar. */
data class GlassTab(
    val icon: ImageVector,
    val label: String,
)

private val BarShape = RoundedCornerShape(percent = 50)
private val BubbleShape = RoundedCornerShape(percent = 50)

/**
 * Pill height, matched to the reference SukiSU manager bar (measured 191px ≈ 64dp at 480dpi).
 * The old 56dp pill both looked squat next to it and left the bar crowding the gesture handle.
 */
private val BarHeight = 64.dp
private val BarPadding = 4.dp

/**
 * Width of one tab slot. The bar wraps the slots, so it stays a compact centred pill.
 *
 * 77dp matches the reference bar's slot pitch (936px pill / 4 tabs ≈ 231px at 480dpi); the old
 * 64dp slots made every tap target visibly tighter than the reference.
 */
private val TabSlotWidth = 77.dp

/**
 * Extra clearance below the navigation-bar inset.
 *
 * On this device the gesture inset parks the pill 60px above the screen edge, which leaves only
 * 23px to the gesture handle — the finger and the handle ended up fighting for the same strip.
 * The reference bar sits 85px above the edge (47px above the handle), so we add the difference.
 * [AppShell] measures the whole chain ([WindowInsets.navigationBars] + this), so content
 * padding and the snackbar keep tracking the bar automatically.
 */
private val BarBottomClearance = 8.5.dp

private val BubbleHeight = 50.dp
private val TabIconSize = 24.dp
private val TabLabelSpacing = 2.dp

/** Resting fill of the bubble: a soft light pill under the selected tab. */
private const val BubbleRestAlpha = 0.10f

/**
 * How far the bubble inflates while a swipe is held or a tab is tapped.
 *
 * 1.50 puts the 50dp bubble at 75dp against a 64dp bar, so it overhangs the capsule by 5.5dp top
 * and bottom. The overhang only became visible once the bar stopped clipping its children —
 * `drawBackdrop` lays its content out with `clip = true` in the capsule's shape, so the bubble
 * used to be hard-cut at the bar's edge no matter what this number said. The bar glass now
 * lives in its own empty sibling layer ([LiquidNavigationBar]'s first child) precisely so the
 * bubble can escape it.
 */
private const val BubblePressScale = 1.50f

/**
 * What happens to the tab's own content while that same press runs.
 *
 * Split from [BubblePressScale] on purpose: the glass has to escape the pill, the icon and label
 * only have to feel the touch, and scaling them by 1.50 would push the label outside the bubble.
 */
private const val TabIconScale = 1.10f

/**
 * The bubble's own refraction, at rest and at full press.
 *
 * A resting lens is what separates glass from a painted chip: with the effect collapsed to zero
 * the bubble drew nothing but its white fill, which reads as a flat square. The press values are
 * the same numbers the bar itself uses, so inflating reads as *more of the same material* rather
 * than a different effect switching on.
 */
private val BubbleRestRefractionHeight = 6.dp
private val BubbleRestRefractionAmount = 8.dp
private val BubbleRefractionHeight = 16.dp
private val BubbleRefractionAmount = 20.dp

/** Rim, drop shadow and inset shadow — all present at rest, all deepening under the finger. */
private val BubbleRestRimAlpha = 0.45f
private val BubbleRestShadowAlpha = 0.35f
private val BubbleRestInnerAlpha = 0.25f
private val BubbleRestInnerRadius = 5.dp
private val BubblePressShadowAlpha = 0.7f
private val BubblePressInnerAlpha = 0.55f
private val BubbleInnerShadowRadius = 10.dp

/** How much the bar itself leans into a drag before it lets go — the rubber-band feedback. */
private val BarRubberBand = 5.dp
private val BarRubberEase = EaseOut

/** How much of a drag's travel becomes lean: a third of a slot caps out at [BarRubberBand]. */
private const val BarRubberGain = 0.06f

/** The soft glow that rides the bubble across the glass. Shader only — the glow Box is an
 *  unclipped sibling of the pill, so any flat fill here draws a grey *rectangle* over the
 *  capsule's rounded corners; the radial bloom is the whole effect. The bloom's radius must
 *  fade to zero before it reaches the Box's straight top/bottom edges (a radius past half the
 *  Box height saturates those edges and the hard cut reads as a rectangular band), and the Box
 *  is clipped to [BarShape] as a second line of defence. */
private const val BloomShaderAlpha = 0.15f

/**
 * The AGSL smoothstep radial the bloom draws with — always available (`minSdk` 33). One instance
 * per bar; its uniforms are set directly on every draw, the wrapper below holds the same one.
 */
private const val BloomShaderSource = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""

/** Darkening laid over the glass while it is held, opposite to the resting white fill. */
private const val BubblePressShadeAlpha = 0.03f

/**
 * How wide the bubble is inside its 77dp slot.
 *
 * Two dp short of the slot (as in the reference bar, whose 228px bubble sits in a 234px pitch),
 * so the resting pill keeps a hairline gap to the capsule instead of fusing with it.
 */
private val BubbleWidth = TabSlotWidth - 2.dp

/** How much the fling's velocity squashes the bubble along/across the travel direction. */
private const val VelocitySquishScale = 10f
private const val VelocitySquishCap = 0.2f

/**
 * iOS/HyperOS-style floating navigation bar: a true capsule of glass with a bubble that pops out
 * of it and carries the selection across the tabs.
 *
 * **Two layers, never one.** `drawBackdrop` clips its content to the shape it draws, so anything
 * that must outgrow the capsule cannot live inside the element that draws it. The bar's glass is
 * therefore an empty sibling Box (the first child); the accent row, the bubble, the glow and the
 * tab content are siblings above it, free to overhang the pill whenever the bubble inflates.
 *
 * **One gesture owner.** The whole inner area runs a single `inspectDragGestures`, and the tabs
 * are semantics-only (`Role.Tab` + `onClick` for accessibility, no pointer handler). A tap
 * selects the slot the finger landed on; a drag past touch slop selects the slot under the
 * finger at release, using the spring's *target* (the finger's own position) rather than its
 * animated value. Nothing else ever writes the bubble's position, so a tap cannot race a
 * release, and a long swipe cannot strand the bubble mid-bar — [NavBarPhysics] accumulates every
 * delta into one retargeting spring instead of racing `snapTo` calls.
 *
 * Touch owns the whole lifecycle: the press rises on the down event with no touch slop, the bar
 * leans a few pixels into the direction of travel, a soft bloom rides the bubble's own animated
 * position — Kyant's `InteractiveHighlight` anchors the light to the pill rather than the
 * contact point, so a glow chasing the finger around the capsule is deliberately absent — and
 * the blob only deflates as far as its slide carries it.
 *
 * A null [backdrop] — the feature is off, or the device has no runtime shaders — leaves a plain
 * capsule with a sliding indicator, so the bar keeps its shape and its navigation either way.
 */
@Composable
fun LiquidNavigationBar(
    selectedIndex: Int,
    items: List<GlassTab>,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val lastIndex = items.lastIndex
    val accent = MiuixTheme.colorScheme.primary
    val surface = MiuixTheme.colorScheme.surface
    val scope = rememberCoroutineScope()
    val initialIndex = selectedIndex.coerceIn(0, lastIndex)
    val physics = remember(lastIndex) { NavBarPhysics(scope, initialIndex.toFloat(), lastIndex) }
    // The accent copy of the row. Recorded once and sampled by the bubble on every press.
    val tabsBackdrop = rememberLayerBackdrop { drawContent() }
    // The bubble-anchored bloom: one shader instance for the glow layer below, its uniforms
    // set per draw from [NavBarPhysics]'s press and animated slot.
    val bloomShader = remember { RuntimeShader(BloomShaderSource) }
    val bloomAndroidShader = remember { bloomShader.asAndroidRuntimeShader() }

    // Gesture-local scratch state. Written only from the pointer callbacks below (UI thread)
    // and read only there or in draw scopes — none of it is read by composition, so a moving
    // finger never recomposes the bar.
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    // Raw travel, unscaled — [dragAccumPx] is gain-decayed for the lean and can never reach
    // touch slop on its own, so tap-vs-drag is judged on this instead.
    var rawDragPx by remember { mutableFloatStateOf(0f) }
    var hasDragged by remember { mutableStateOf(false) }
    var downSlot by remember { mutableIntStateOf(initialIndex) }
    // Re-read inside the gesture: the handlers outlive the recomposition that followed them,
    // and a tap that has already reported its selection must not be measured against the old one.
    val latestSelected by rememberUpdatedState(selectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)

    /**
     * One release, one owner: a tap commits the slot it landed on, a drag commits the slot the
     * finger was over — read from the spring's *target* (the finger's position), never from its
     * animated value, so releasing mid-slide can never round to a neighbouring tab.
     */
    fun finishGesture() {
        val target = (if (hasDragged) physics.target.roundToInt() else downSlot)
            .coerceIn(0, lastIndex)
        dragAccumPx = 0f
        rawDragPx = 0f
        physics.easeLeanBack()
        physics.release(target.toFloat())
        if (target != latestSelected) latestOnSelect(target)
    }

    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.coerceIn(0, lastIndex).toFloat()
        if (abs(physics.target - target) > 0.01f) physics.slideTo(target)
    }

    val density = LocalDensity.current
    val slotPx = with(density) { TabSlotWidth.toPx() }
    val barPaddingPx = with(density) { BarPadding.toPx() }
    val blurPx = with(density) { LiquidBlurRadius.toPx() }
    val restRefractionHeightPx = with(density) { BubbleRestRefractionHeight.toPx() }
    val restRefractionAmountPx = with(density) { BubbleRestRefractionAmount.toPx() }
    val refractionHeightPx = with(density) { BubbleRefractionHeight.toPx() }
    val refractionAmountPx = with(density) { BubbleRefractionAmount.toPx() }
    val rubberBandPx = with(density) { BarRubberBand.toPx() }

    Box(
        modifier = modifier
            // Insets wrap the pill instead of eating into it, so the capsule keeps its full
            // height and floats above the gesture bar rather than being squeezed by it;
            // [BarBottomClearance] then lifts it clear of the handle to match the reference bar.
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(bottom = BarBottomClearance)
            .width(BarPadding * 2 + TabSlotWidth * items.size)
            .height(BarHeight)
            // The rubber-band lean: the whole capsule shifts a few pixels with the drag and
            // falls back on release, so a swipe feels like it drags the bar rather than a chip.
            .graphicsLayer { translationX = physics.lean }
            .selectableGroup(),
    ) {

        // The bar itself: an empty layer. `drawBackdrop` lays its content out clipped to the
        // capsule, so the glass element must have no children — everything that can outgrow the
        // pill lives in the sibling box below instead of being cut at BarHeight.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (backdrop != null) {
                        Modifier.liquidGlassBackground(backdrop, BarShape)
                    } else {
                        Modifier.background(surface, BarShape)
                    },
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = BarPadding)
                .pointerInput(items.size, slotPx) {
                    inspectDragGestures(
                        onDragStart = { down ->
                            dragAccumPx = 0f
                            rawDragPx = 0f
                            hasDragged = false
                            // Where the finger landed decides a tap's selection, and the bubble
                            // walks there on the down so the press feels answered immediately.
                            val slot = (down.position.x / slotPx).toInt().coerceIn(0, lastIndex)
                            downSlot = slot
                            physics.press(slot.toFloat())
                        },
                        onDrag = { _, dragAmount ->
                            if (dragAmount.x != 0f) {
                                dragAccumPx = (dragAccumPx + dragAmount.x) * BarRubberGain
                                rawDragPx += abs(dragAmount.x)
                                if (!hasDragged && rawDragPx > viewConfiguration.touchSlop) {
                                    hasDragged = true
                                }
                                physics.slideTo(physics.target + dragAmount.x / slotPx)
                                physics.snapLean(
                                    dragAccumPx.coerceIn(-rubberBandPx, rubberBandPx),
                                )
                            }
                        },
                        onDragEnd = {
                            finishGesture()
                        },
                        onDragCancel = {
                            finishGesture()
                        },
                    )
                },
        ) {

            // Accent copy of the row: recorded into tabsBackdrop, never drawn to the screen.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, tab ->
                    GlassTabSlot(
                        tab = tab,
                        index = index,
                        isSelected = index == selectedIndex,
                        press = physics::press,
                        progress = physics::progress,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            // The bubble sits *under* the tab content: it is the glass the tabs rest on, so the
            // icons and labels stay readable while it inflates around the pressed one. It is a
            // sibling of the bar's glass (not a child), so its 8dp overhang is not clipped.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(BubbleWidth)
                    .height(BubbleHeight)
                    .graphicsLayer {
                        translationX = (physics.progress + 0.5f - items.size / 2f) * slotPx
                    }
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                                shape = { BubbleShape },
                                effects = {
                                    val held = physics.press
                                    vibrancy()
                                    // Matches the bar at rest and clears as the bubble lifts,
                                    // so the magnified accent tabs stay crisp under the glass.
                                    blur(blurPx * (1f - held))
                                    // A resting lens, not an absent one — collapsing this to
                                    // zero at rest left the bubble with no material of its own.
                                    lens(
                                        refractionHeight = lerp(
                                            restRefractionHeightPx,
                                            refractionHeightPx,
                                            held,
                                        ),
                                        refractionAmount = lerp(
                                            restRefractionAmountPx,
                                            refractionAmountPx,
                                            held,
                                        ),
                                        chromaticAberration = true,
                                    )
                                },
                                highlight = {
                                    Highlight(alpha = lerp(BubbleRestRimAlpha, 1f, physics.press))
                                },
                                shadow = {
                                    Shadow(
                                        alpha = lerp(
                                            BubbleRestShadowAlpha,
                                            BubblePressShadowAlpha,
                                            physics.press,
                                        ),
                                    )
                                },
                                innerShadow = {
                                    val held = physics.press
                                    InnerShadow(
                                        radius = BubbleRestInnerRadius +
                                            (BubbleInnerShadowRadius - BubbleRestInnerRadius) *
                                            held,
                                        alpha = lerp(
                                            BubbleRestInnerAlpha,
                                            BubblePressInnerAlpha,
                                            held,
                                        ),
                                    )
                                },
                                layerBlock = {
                                    val scale = 1f + (BubblePressScale - 1f) * physics.press
                                    // Fling squish: velocity stretches the bubble along travel
                                    // and pinches it across, clamped so it never inverts.
                                    val v = physics.velocity / VelocitySquishScale
                                    scaleX = scale / (1f - (v * 0.75f).coerceIn(
                                        -VelocitySquishCap,
                                        VelocitySquishCap,
                                    ))
                                    scaleY = scale * (1f - (v * 0.25f).coerceIn(
                                        -VelocitySquishCap,
                                        VelocitySquishCap,
                                    ))
                                },
                                onDrawSurface = {
                                    val held = physics.press
                                    drawRect(
                                        Color.White.copy(alpha = BubbleRestAlpha * (1f - held)),
                                    )
                                    drawRect(
                                        Color.Black.copy(alpha = BubblePressShadeAlpha * held),
                                    )
                                },
                            )
                        } else {
                            Modifier.background(
                                Color.White.copy(alpha = BubbleRestAlpha),
                                BubbleShape,
                            )
                        },
                    ),
            )

            // The bloom under the bubble: additive white drawn *below* the tab content so it
            // reads as the surface catching light rather than a dot pasted over the icons.
            // Clipped to the capsule so the bloom can never mark a straight edge of the Box.
            // The position is the bubble's own animated slot — Kyant's position lambda ignores
            // the contact point — so the light rides the pill instead of chasing the finger.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(BarShape)
                    .drawBehind {
                        val strength = physics.press
                        if (strength <= 0f) return@drawBehind
                        val cx = barPaddingPx + (physics.progress + 0.5f) * slotPx
                        bloomShader.apply {
                            setFloatUniform("size", size.width, size.height)
                            setColorUniform(
                                "color",
                                Color.White.copy(BloomShaderAlpha * strength),
                            )
                            setFloatUniform("radius", size.minDimension * 0.5f)
                            setFloatUniform(
                                "position",
                                cx.coerceIn(0f, size.width),
                                size.height * 0.5f,
                            )
                        }
                        drawRect(
                            ShaderBrush(bloomAndroidShader),
                            blendMode = BlendMode.Plus,
                        )
                    },
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, tab ->
                    GlassTabSlot(
                        tab = tab,
                        index = index,
                        isSelected = index == selectedIndex,
                        press = physics::press,
                        progress = physics::progress,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // Semantics only — no pointer handler. The bar's own gesture owns
                            // every touch, so a tap here cannot race a second selection path;
                            // TalkBack still gets a real, activatable tab.
                            .semantics {
                                role = Role.Tab
                                selected = index == selectedIndex
                                onClick(label = tab.label) {
                                    latestOnSelect(index)
                                    true
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.GlassTabSlot(
    tab: GlassTab,
    index: Int,
    isSelected: Boolean,
    press: () -> Float,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    // Not `LocalContentColor`: the bar sits outside any miuix Surface, so that local would fall
    // back to its black default and paint the tabs invisible against the dark glass.
    val selectedColor = MiuixTheme.colorScheme.onSurface
    val idleColor = MiuixTheme.colorScheme.onSurfaceSecondary
    val contentColor = if (isSelected) selectedColor else idleColor
    Column(
        // The lift follows the bubble, not the selection: the press walks the bubble to the slot
        // under the finger, so scaling the tab that is merely still selected would animate the
        // one sitting in the dark instead of the one being touched.
        modifier = modifier.graphicsLayer {
            val held = press()
            val scale = if (index == progress().roundToInt()) {
                1f + (TabIconScale - 1f) * held
            } else {
                1f
            }
            scaleX = scale
            scaleY = scale
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            modifier = Modifier.size(TabIconSize),
            tint = contentColor,
        )
        Text(
            text = tab.label,
            modifier = Modifier.padding(top = TabLabelSpacing),
            color = contentColor,
            style = MiuixTheme.textStyles.footnote2,
        )
    }
}

/**
 * Every animation the bar's glass runs, behind one owner: which slot the bubble sits on, how hard
 * it is being pressed, its fling velocity (for the squish), and the bar's rubber-band lean.
 *
 * **Input never cancels a job.** Ported from Kyant's `DampedDragAnimation`: every call launches a
 * fresh coroutine and lets `Animatable`'s mutator mutex supersede whatever is already animating
 * *when the new one actually starts*. The previous design cancelled the prior job on each drag
 * event instead — and because `animateTo` first suspends waiting for a frame tick while a real
 * finger delivers one event per frame, every job was killed before it ever advanced the value:
 * the bubble stood still for the whole drag and only snapped home when the finger lifted.
 * Input injection spaces events ~100 ms apart, so every job finished there and the tests
 * could not see the difference.
 *
 * [slideTo] is the only writer of the bubble's position, and the gesture only ever hands it a
 * *target* (the finger's accumulated position), never an animated value — which is what makes
 * release-rounding deterministic.
 *
 * The target is mirrored in [slideTarget], written **synchronously** in [slideTo] before the
 * coroutine that drives `valueAnimation` is even launched. A real finger's input arrives in
 * bursts: ViewRootImpl can drain several MotionEvents inside one frame, so `onDrag` runs N times
 * before any launched `animateTo` has started — had those calls read `Animatable.targetValue`
 * (updated only once the coroutine runs), every one of them would have seen the same stale
 * position, kept only its own delta, and retargeted the press-walk away from the finger. Input
 * injection never batches, which is exactly why the old async read looked fine under test.
 */
private class NavBarPhysics(
    private val scope: CoroutineScope,
    initialIndex: Float,
    private val lastIndex: Int,
) {
    private val valueSpec =
        spring<Float>(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)
    private val pressSpec =
        spring<Float>(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f)
    /**
     * The settle after finger lift. The inflate stays instant ([pressSpec]); the deflate rides
     * down over ~250 ms instead — holding full pressure until the bubble *arrived* and then
     * snapping flat read as the pill sticking under the finger and popping off afterwards.
     * Critically damped so the settle never overshoots back up.
     */
    private val settleSpec =
        spring<Float>(dampingRatio = 1f, stiffness = 350f, visibilityThreshold = 0.001f)
    private val velocitySpec =
        spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.01f)
    private val leanSpec = tween<Float>(260, easing = BarRubberEase)

    private val valueAnimation = Animatable(initialIndex)
    private val pressAnimation = Animatable(0f)
    private val velocityAnimation = Animatable(0f)
    private val leanAnimation = Animatable(0f)
    private val velocityTracker = VelocityTracker()

    /** The bubble's animated position, in slots. Read by draw scopes only. */
    val progress: Float get() = valueAnimation.value

    /**
     * The spring's target — the finger's own position during a drag.
     *
     * Mirrors the value handed to the next `animateTo`, but written synchronously by [slideTo]
     * rather than by the coroutine that runs it, so gesture math inside one input batch always
     * accumulates onto the previous call's delta instead of a pre-batch position.
     */
    var slideTarget: Float = initialIndex
        private set
    val target: Float get() = slideTarget

    /** 0..1 press, driving the bubble's scale/effects and the tab lift. */
    val press: Float get() = pressAnimation.value

    /** Current fling velocity in normalised slot units — feeds the bubble's squish. */
    val velocity: Float get() = velocityAnimation.value

    /** Bar rubber-band lean in pixels. */
    val lean: Float get() = leanAnimation.value

    /**
     * Walks (or retargets) the bubble to [slot]. Never cancels: the freshly launched
     * `animateTo` supersedes the one in flight through the Animatable's own mutator mutex,
     * which is the only thing allowed to end an animation — see the class note.
     */
    fun slideTo(slot: Float) {
        slideTarget = slot.coerceIn(0f, lastIndex.toFloat())
        scope.launch { valueAnimation.animateTo(slideTarget, valueSpec) { trackVelocity() } }
    }

    /** Finger down: inflate now and walk the bubble to the slot under the finger. */
    fun press(downSlot: Float) {
        velocityTracker.resetTracking()
        scope.launch { pressAnimation.animateTo(1f, pressSpec) }
        slideTo(downSlot)
    }

    /**
     * Finger up: commit [selection] and let the press settle home on its own — the deflate
     * starts immediately and coasts down as the bubble slides to its slot, so there is no
     * hold-then-snap after the finger has already left. The fling velocity coasts to zero on
     * its own spring so the squish relaxes with it.
     */
    fun release(selection: Float) {
        slideTo(selection)
        scope.launch {
            launch { velocityAnimation.animateTo(0f, velocitySpec) }
            pressAnimation.animateTo(0f, settleSpec)
        }
    }

    /** Applies the gesture's rubber-band lean; called on every drag delta. */
    fun snapLean(px: Float) {
        scope.launch { leanAnimation.snapTo(px) }
    }

    /** Lets the bar's lean fall back to centre on its own ease. */
    fun easeLeanBack() {
        scope.launch { leanAnimation.animateTo(0f, leanSpec) }
    }

    /** Samples the spring's motion so [velocity] tracks the fling for the squish. */
    private fun trackVelocity() {
        velocityTracker.addPosition(SystemClock.elapsedRealtime(), Offset(valueAnimation.value, 0f))
        val range = lastIndex.coerceAtLeast(1).toFloat()
        val v = velocityTracker.calculateVelocity().x / range
        scope.launch { velocityAnimation.animateTo(v, velocitySpec) }
    }
}

/**
 * The down event, then the whole drag, then the up — with no touch slop on the start.
 *
 * Ported from Kyant's backdrop catalog (`DragGestureInspector`), keeping both guards that the
 * two shipped references rely on: SukiSU's manager reads the whole stream on the `Initial` pass
 * with `positionChangeIgnoreConsumed()`, and HyperChanger's `LiquidInteraction` gates the same
 * treatment behind `ignoreConsumed = followFingerImmediately` with the comment "the Main pass is
 * consumed … this observer must still receive the stream". The drag loop therefore awaits the
 * `Initial` pass, takes raw deltas and never aborts on `isConsumed`: a Main-pass consumer —
 * the HorizontalPager this bar floats over, or a page's own clickables — must not be able to
 * park the bubble mid-gesture, which is exactly what the previous `positionChange()` +
 * consumed-abort combination let happen under a real finger (injection tests, which never
 * batch, could not see it).
 *
 * The helper consumes every event it reads — the down and every moved change. The bar is an
 * overlay that owns its rect: without that, the pager behind it starts scrolling the moment the
 * finger crosses touch slop, so the bubble and the page would both chase the same gesture.
 * Consumption happens on the `Initial` pass, which runs entirely before any `Main`-pass
 * consumer sees the event, and the loop reads `IgnoreConsumed` variants, so the bar's own
 * consumption can never starve itself.
 */
private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)

        val down = awaitFirstDown(false)
        val drag = initialDown

        onDragStart(down)
        onDrag(drag, Offset.Zero)
        drag.consume()
        val upEvent =
            drag(
                pointerId = drag.id,
                onDrag = {
                    onDrag(it, it.positionChangeIgnoreConsumed())
                    it.consume()
                }
            )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}

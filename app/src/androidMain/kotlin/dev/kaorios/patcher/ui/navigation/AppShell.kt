package dev.kaorios.patcher.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kaorios.patcher.R
import dev.kaorios.patcher.pipeline.PatchStep
import dev.kaorios.patcher.ui.PatchUiState
import dev.kaorios.patcher.ui.component.ConfirmSpec
import dev.kaorios.patcher.ui.component.DialogScrimAlpha
import dev.kaorios.patcher.ui.component.DialogSpreadSpring
import dev.kaorios.patcher.ui.component.GlassSnackbar
import dev.kaorios.patcher.ui.component.GradientEdgeStrips
import dev.kaorios.patcher.ui.component.GlassTab
import dev.kaorios.patcher.ui.component.LiquidConfirmDialog
import dev.kaorios.patcher.ui.component.LiquidNavigationBar
import dev.kaorios.patcher.ui.component.StatusBadge
import dev.kaorios.patcher.ui.component.StatusTone
import dev.kaorios.patcher.ui.component.recordLiquidBackdrop
import dev.kaorios.patcher.ui.component.rememberLiquidBackdrop
import dev.kaorios.patcher.ui.page.ManualPatchPage
import dev.kaorios.patcher.ui.page.ModulePage
import dev.kaorios.patcher.ui.page.PatchPage
import dev.kaorios.patcher.ui.page.SettingsPage
import dev.kaorios.patcher.ui.service.PrefsRepository
import dev.kaorios.patcher.ui.service.rememberBooleanPreference
import dev.kaorios.patcher.ui.theme.PREF_EDGE_BLUR
import dev.kaorios.patcher.ui.theme.PREF_LIQUID_GLASS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.icon.extended.Settings

/** Callbacks the shell forwards to the active page. */
data class PatchActions(
    val onCorePatchChange: (Boolean) -> Unit,
    val onFlagSecureChange: (Boolean) -> Unit,
    val onHideDevStatusChange: (Boolean) -> Unit,
    val onVfsChange: (Boolean) -> Unit,
    val onPull: () -> Unit,
    val onPatch: () -> Unit,
    val onClear: () -> Unit,
    val onRefresh: () -> Unit,
    /** Sends the user to the "All files access" toggle; needed before `Download/` is reachable. */
    val onGrantStorage: () -> Unit,
    /** Hands the built zip to the root manager for installation. */
    val onFlash: () -> Unit,
    /** Copies the session log into `Download/` so it can be pulled off the device. */
    val onExportLog: () -> Unit,
)

private data class RootDestination(
    @get:StringRes val title: Int,
    val icon: ImageVector,
)

private val Destinations = listOf(
    RootDestination(R.string.tab_patch, MiuixIcons.Replace),
    RootDestination(R.string.tab_output, MiuixIcons.Layers),
    RootDestination(R.string.tab_manual_short, MiuixIcons.Import),
)

/** Index of the view-only Manual Patching tab (drives the "Coming soon" chip). */
private const val ManualTabIndex = 2

/** Title / subtitle the shared HOS title bar shows for each tab. */
private data class PageChrome(@get:StringRes val title: Int, @get:StringRes val subtitle: Int)

private val PageChromes = listOf(
    PageChrome(R.string.page_patch_title, R.string.page_patch_subtitle),
    PageChrome(R.string.page_module_title, R.string.page_module_subtitle),
    PageChrome(R.string.tab_manual, R.string.page_manual_subtitle),
)

/** Settings push: how fast the page slides in, and how fast back/close slides it out. */
private val SettingsPushEnter = tween<Float>(260)
private val SettingsPushExit = tween<Float>(220)

/**
 * Root navigation: one pager-owned tab set, a floating glass navigation bar, and a snackbar host.
 *
 * The pager keeps adjacent tabs composed, so returning to the patch tab never re-runs its
 * scroll state. A terminal pipeline step is surfaced from any tab so a failure cannot hide
 * behind a page switch.
 *
 * One preference, [PREF_LIQUID_GLASS], drives the whole bar: it turns on the glass capsule *and*
 * the backdrop blur behind it. Turning it off leaves the same pill drawn as a flat surface, so
 * the bar never disappears and never degrades to a half-glass state.
 *
 * The HOS title bar is Miuix's own `TopAppBar`: one bar above the pager, a large title that
 * folds into the collapsed bar through `MiuixScrollBehavior` (one behaviour per tab so each
 * keeps its fold), the settings icon top-right, and the "Coming soon" chip beside the Manual
 * Patching title. Settings itself is a separate page view pushed over the shell.
 */
@Composable
fun KaoriosPatcherApp(
    state: PatchUiState,
    prefs: PrefsRepository,
    log: List<String>,
    actions: PatchActions,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { Destinations.size })
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val liquidGlass = rememberBooleanPreference(prefs, PREF_LIQUID_GLASS, true)
    val edgeBlur = rememberBooleanPreference(prefs, PREF_EDGE_BLUR, true)
    // One recorder for whichever liquid feature is live; a page is never recorded twice.
    val liquidBackdrop = rememberLiquidBackdrop(enabled = liquidGlass || edgeBlur)
    val barBackdrop = if (liquidGlass) liquidBackdrop else null
    val tabs = Destinations.map { GlassTab(it.icon, stringResource(it.title)) }

    // Hoisted scroll state: one per tab, one for the settings page view. The title bar folds
    // and the edge strips fade from the same values, so they can never disagree.
    val scrollStates = remember { List(Destinations.size) { ScrollState(0) } }
    val settingsScrollState = remember { ScrollState(0) }
    // One collapse behaviour per tab: switching tabs restores that tab's own fold position.
    val scrollBehaviors = List(Destinations.size) { MiuixScrollBehavior() }
    val settingsBehavior = MiuixScrollBehavior()
    var settingsOpen by remember { mutableStateOf(false) }
    // One progress (0 = shell, 1 = settings) drives both the overlay's horizontal push and the
    // bar's slide-down so they can never disagree. The gate reads settingsOpen only; the
    // per-frame value is read inside graphicsLayer blocks, which never recompose. Both
    // functions are idempotent so the gesture handler, the legacy back handler, the back icon
    // and the bar's tab selection can all call them safely.
    val settingsProgress = remember { Animatable(0f) }
    var settingsWidthPx by remember { mutableIntStateOf(0) }
    fun openSettings() {
        if (settingsOpen) return
        settingsOpen = true
        scope.launch { settingsProgress.animateTo(1f, SettingsPushEnter) }
    }
    fun closeSettings() {
        if (!settingsOpen) return
        scope.launch {
            settingsProgress.animateTo(0f, SettingsPushExit)
            settingsOpen = false
        }
    }

    // One glass confirmation dialog for the two destructive actions. The request, the spread
    // spring and the dismissal live here so the scrim (inside the recorded region, so the
    // dialog's glass samples the dimmed page) and the panel (outside it) share one progress.
    var confirm by remember { mutableStateOf<ConfirmSpec?>(null) }
    val dialogProgress = remember { Animatable(0f) }
    fun openConfirm(spec: ConfirmSpec) {
        confirm = spec
        scope.launch { dialogProgress.animateTo(1f, DialogSpreadSpring) }
    }
    fun closeConfirm() {
        scope.launch {
            dialogProgress.animateTo(0f, DialogSpreadSpring)
            confirm = null
        }
    }
    BackHandler(enabled = confirm != null) { closeConfirm() }
    // Settings is a page view: system back closes it, but a confirmation dialog always wins.
    // Predictive back drives the push from the gesture itself — each event mirrors the gesture
    // into the progress; a committed back finishes through closeSettings, an outward swipe
    // cancels this handler (its own coroutine dies with it, hence the restore runs on the
    // shell's scope) and the page springs back to fully open. The plain handler keeps back
    // working on paths that deliver no gesture events and is composed first, so the predictive
    // one wins while both are enabled (last composed among the enabled ones is invoked).
    BackHandler(enabled = settingsOpen && confirm == null) { closeSettings() }
    PredictiveBackHandler(enabled = settingsOpen && confirm == null) { events ->
        try {
            events.collect { event -> settingsProgress.snapTo(1f - event.progress) }
            closeSettings()
        } catch (cancellation: CancellationException) {
            scope.launch { settingsProgress.animateTo(1f, SettingsPushEnter) }
            throw cancellation
        }
    }
    // Gated copies: pages keep calling `onFlash`/`onClear` unchanged; the shell decides whether
    // they run straight away or through the dialog.
    val gatedActions = actions.copy(
        onFlash = {
            openConfirm(
                ConfirmSpec(
                    title = R.string.dialog_flash_title,
                    message = R.string.dialog_flash_body,
                    confirmLabel = R.string.dialog_confirm_install,
                    action = actions.onFlash,
                ),
            )
        },
        onClear = {
            openConfirm(
                ConfirmSpec(
                    title = R.string.dialog_clear_title,
                    message = R.string.dialog_clear_body,
                    confirmLabel = R.string.dialog_confirm_clear,
                    action = actions.onClear,
                ),
            )
        },
    )

    val moduleReadyMessage = stringResource(R.string.snackbar_module_ready)
    var reportedStep by remember { mutableStateOf(state.step) }
    LaunchedEffect(state.step) {
        if (reportedStep != state.step) {
            val message = when (val step = state.step) {
                is PatchStep.Done -> moduleReadyMessage
                is PatchStep.Failed -> step.message.lineSequence().firstOrNull().orEmpty()
                else -> null
            }
            if (message != null && reportedStep !is PatchStep.Done) {
                snackbarHostState.showSnackbar(message)
            }
            reportedStep = state.step
        }
    }

    // The settings page view sits above everything in the recorded region while it is open,
    // so the edge strips and the bar keep sampling it; the active scroll drives both.
    val activeScrollState = if (settingsOpen) settingsScrollState else scrollStates[pagerState.currentPage]

    Box(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        var bottomBarHeightPx by remember { mutableIntStateOf(0) }

        // The bar is deliberately outside the recorded region: the recorded layer is what the
        // bar blurs, so recording the bar itself would make it sample its own pixels.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .recordLiquidBackdrop(liquidBackdrop),
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    val page = pagerState.currentPage
                    val chrome = PageChromes[page]
                    TopAppBar(
                        title = stringResource(chrome.title),
                        largeTitle = stringResource(chrome.title),
                        subtitle = stringResource(chrome.subtitle),
                        scrollBehavior = scrollBehaviors[page],
                        actions = {
                            if (page == ManualTabIndex) {
                                StatusBadge(
                                    text = stringResource(R.string.badge_coming_soon),
                                    tone = StatusTone.Primary,
                                )
                            }
                            IconButton(onClick = { openSettings() }) {
                                Icon(
                                    imageVector = MiuixIcons.Settings,
                                    contentDescription = stringResource(R.string.page_settings_title),
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    // Reserve height without composing a second, clickable navigation bar.
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { bottomBarHeightPx.toDp() }),
                    )
                },
            ) { padding ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    PageFor(
                        page = page,
                        state = state,
                        prefs = prefs,
                        log = log,
                        actions = gatedActions,
                        padding = padding,
                        scrollState = scrollStates[page],
                        modifier = Modifier.nestedScroll(scrollBehaviors[page].nestedScrollConnection),
                    )
                }
            }

            // Settings as a separate page view: a slide-in push over the shell, with its own
            // HOS title bar (back icon left) and its own collapse behaviour. The surface is
            // clickable-without-indication so a tap on empty space is swallowed here instead
            // of falling through to the pager underneath. Gated on settingsOpen alone — never
            // the per-frame progress — so the push stays a layer transform over a stable
            // subtree; the alpha keeps the shell from showing through the trailing gap.
            if (settingsOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { settingsWidthPx = it.width }
                        .graphicsLayer {
                            translationX = (1f - settingsProgress.value) * settingsWidthPx
                            alpha = settingsProgress.value
                        }
                        .background(MiuixTheme.colorScheme.surface)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    // The overlay sits outside the Scaffold, i.e. outside its Surface, so
                    // nothing publishes LocalContentColor here — Miuix's default is Color.Black
                    // and the back glyph rendered black-on-black. Publish the same
                    // content colour Surface would, so un-Surfaced icons read onSurface.
                    CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurface) {
                        TopAppBar(
                            title = stringResource(R.string.page_settings_title),
                            largeTitle = stringResource(R.string.page_settings_title),
                            scrollBehavior = settingsBehavior,
                            navigationIcon = {
                                IconButton(onClick = { closeSettings() }) {
                                    Icon(
                                        imageVector = MiuixIcons.Back,
                                        contentDescription = stringResource(R.string.action_back),
                                    )
                                }
                            },
                        )
                        SettingsPage(
                            state = state,
                            prefs = prefs,
                            contentPadding = PaddingValues(0.dp),
                            scrollState = settingsScrollState,
                            onClear = gatedActions.onClear,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(settingsBehavior.nestedScrollConnection),
                        )
                    }
                }
            }

            // The dim is *recorded* (drawn into the backdrop) and sits above the settings page
            // view as well, so a confirmation opened from Settings dims it like any other page.
            // Every glass surface above the dim — navbar, edge strips, the dialog panel —
            // samples the page exactly as the user sees it.
            if (confirm != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(
                                color = Color.Black,
                                alpha = DialogScrimAlpha * dialogProgress.value.coerceIn(0f, 1f),
                            )
                        },
                )
            }
        }

        if (liquidBackdrop != null && edgeBlur) {
            GradientEdgeStrips(
                backdrop = liquidBackdrop,
                bottomBarHeight = with(density) { bottomBarHeightPx.toDp() },
                scrollState = activeScrollState,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bottomBarHeightPx = it.height }
                // Slides below its own footprint as settings pushes in — a layer transform,
                // so the slide never recomposes the shell while it runs.
                .graphicsLayer {
                    translationY = settingsProgress.value * bottomBarHeightPx
                },
        ) {
            LiquidNavigationBar(
                selectedIndex = pagerState.currentPage,
                items = tabs,
                onSelect = { index ->
                    closeSettings()
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                backdrop = barBackdrop,
            )
        }

        // Outside the recorded region on purpose: the chip samples the backdrop, and it rests
        // just above the floating bar instead of inside the content's bottom padding.
        SnackbarHost(
            state = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { bottomBarHeightPx.toDp() } + 14.dp),
            content = { data -> GlassSnackbar(data, backdrop = barBackdrop) },
        )

        if (confirm != null) {
            // Invisible modal layer above every glass surface: it eats the touches the dialog
            // does not want (navbar taps included) and dismisses on anything outside the panel.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { closeConfirm() },
                    ),
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LiquidConfirmDialog(
                    spec = confirm!!,
                    progress = dialogProgress,
                    backdrop = liquidBackdrop,
                    onConfirm = {
                        confirm?.let { spec ->
                            closeConfirm()
                            spec.action()
                        }
                    },
                    onDismiss = { closeConfirm() },
                )
            }
        }
    }
}

@Composable
private fun PageFor(
    page: Int,
    state: PatchUiState,
    prefs: PrefsRepository,
    log: List<String>,
    actions: PatchActions,
    padding: PaddingValues,
    scrollState: ScrollState,
    modifier: Modifier,
) {
    when (page) {
        0 -> PatchPage(
            state = state,
            contentPadding = padding,
            scrollState = scrollState,
            onCorePatchChange = actions.onCorePatchChange,
            onFlagSecureChange = actions.onFlagSecureChange,
            onHideDevStatusChange = actions.onHideDevStatusChange,
            onVfsChange = actions.onVfsChange,
            onPull = actions.onPull,
            onPatch = actions.onPatch,
            onClear = actions.onClear,
            onRefresh = actions.onRefresh,
            onGrantStorage = actions.onGrantStorage,
            modifier = modifier,
        )
        1 -> ModulePage(
            state = state,
            log = log,
            contentPadding = padding,
            scrollState = scrollState,
            onFlash = actions.onFlash,
            onExportLog = actions.onExportLog,
            modifier = modifier,
        )
        else -> ManualPatchPage(
            contentPadding = padding,
            scrollState = scrollState,
            modifier = modifier,
        )
    }
}

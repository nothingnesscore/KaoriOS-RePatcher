package dev.kaorios.patcher.ui.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.kaorios.patcher.ui.service.PrefsRepository
import dev.kaorios.patcher.ui.service.rememberBooleanPreference
import dev.kaorios.patcher.ui.service.rememberLongPreference
import dev.kaorios.patcher.ui.service.rememberStringPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

internal const val PREF_THEME_MODE = "pref_theme_mode"

/**
 * Monet / dynamic colour.
 *
 * The key is deliberately not the original `pref_dynamic_color_enabled`: `registerDefaults` never
 * overwrites a key that already exists, so renaming it is what flips an install that was seeded
 * with `false` over to the new default. Colour is meant to track the framework here — the app is
 * expected to sit in step with whatever a wallpaper-colour tool is doing to the system.
 */
internal const val PREF_DYNAMIC_COLOR = "pref_monet_dynamic_color"
internal const val PREF_THEME_SEED_COLOR = "pref_theme_seed_color"
internal const val PREF_LIQUID_GLASS = "pref_liquid_glass_navbar"
internal const val PREF_EDGE_BLUR = "pref_edge_gradient_blur"
internal const val PREF_AMOLED_BLACK = "pref_amoled_black"

internal const val THEME_MODE_SYSTEM = "system"
internal const val THEME_MODE_LIGHT = "light"
internal const val THEME_MODE_DARK = "dark"

internal const val DEFAULT_THEME_SEED_COLOR = 0xFF3B6FF5L

/** Every preference the shell seeds on first launch. */
internal val PrefDefaults: Map<String, Any> = mapOf(
    PREF_THEME_MODE to THEME_MODE_SYSTEM,
    PREF_DYNAMIC_COLOR to true,
    PREF_THEME_SEED_COLOR to DEFAULT_THEME_SEED_COLOR,
    PREF_LIQUID_GLASS to true,
    PREF_EDGE_BLUR to true,
    PREF_AMOLED_BLACK to false,
)

/**
 * Applies the Miuix theme and keeps the system bars in step with the resolved mode.
 *
 * Dynamic colour is on by default so the palette follows the framework's own Monet colours; the
 * seed only drives the palette when it is switched off, which keeps the app recognisable on
 * devices without a wallpaper-derived palette.
 */
@Composable
fun KaoriosPatcherTheme(
    prefs: PrefsRepository,
    content: @Composable () -> Unit,
) {
    val themeMode = rememberStringPreference(prefs, PREF_THEME_MODE, THEME_MODE_SYSTEM)
    val dynamicColor = rememberBooleanPreference(prefs, PREF_DYNAMIC_COLOR, true)
    val seedColor = rememberLongPreference(prefs, PREF_THEME_SEED_COLOR, DEFAULT_THEME_SEED_COLOR)
    val amoledBlack = rememberBooleanPreference(prefs, PREF_AMOLED_BLACK, false)

    val darkSystemBars = when (themeMode) {
        THEME_MODE_LIGHT -> false
        THEME_MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }

    // Monet modes in every case: a plain Light/Dark/System mode ignores keyColor entirely and
    // returns Miuix's static defaults, so both branches of the dynamic toggle produced the same
    // blue palette. Monet + a null keyColor runs platformDynamicColors(), which reads the
    // wallpaper's own palette (theme_customization_overlay_packages / system accent); Monet +
    // the seed runs colorsFromSeed() — the app's recognisable blue when dynamic colour is off.
    val controller = remember(themeMode, dynamicColor, seedColor) {
        ThemeController(
            colorSchemeMode = when (themeMode) {
                THEME_MODE_LIGHT -> ColorSchemeMode.MonetLight
                THEME_MODE_DARK -> ColorSchemeMode.MonetDark
                else -> ColorSchemeMode.MonetSystem
            },
            keyColor = if (dynamicColor) null else Color(seedColor.toInt()),
        )
    }

    val activity = LocalActivity.current as? ComponentActivity
    SideEffect {
        val style = if (darkSystemBars) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    MiuixTheme(controller = controller) {
        // AMOLED pure black: clone the resolved scheme (Monet included — the accents survive,
        // only the structural surfaces go to #000000) and re-theme on top of it with Miuix's
        // public `colors` overload (`LocalColors` itself is internal). The clone is a `copy`,
        // never a mutation: `MiuixTheme.colorScheme` is shared.
        //
        // Both states must run through ONE composition path: an if/else around `content()`
        // would put it at two different call sites, and flipping the AMOLED branch would then
        // dispose and recreate the whole subtree — wiping every `remember` below (settings
        // page open, selected tab, scroll positions). The colors overload leaves
        // `localColorSchemeMode` to the enclosing controller, and its textStyles /
        // smoothRounding defaults resolve to the ambient controller values, so wrapping the
        // non-AMOLED path in it is behaviour-identical.
        // Never remember the black clone: `base` is one stable instance mutated in place by the
        // controller's updateColorsFrom, so remember(base) would freeze it at the first frame
        // and AMOLED would show that stale palette forever. The fields are snapshot-state
        // backed, so copying per recomposition both subscribes to their changes and stays live.
        val base = MiuixTheme.colorScheme
        val colors = if (amoledBlack && darkSystemBars) {
            base.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color.Black,
                surfaceContainer = Color.Black,
                surfaceContainerHigh = Color.Black,
                surfaceContainerHighest = Color.Black,
            )
        } else {
            base
        }
        MiuixTheme(colors = colors) { content() }
    }
}
# Kaorios Patcher — UI Guidelines

Authoritative UI conventions for `app/`. Derived from
[HyperIsland](https://github.com/1812z/HyperIsland) (the reference app) and
[Miuix](https://github.com/compose-miuix-ui/miuix) upstream guidance. When this file and
existing source disagree, existing source wins — update this file in the same change.

## 1. Stack

| Concern | Choice |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose |
| Component library | **Miuix** (`top.yukonga.miuix.kmp`, Android target) |
| Compose runtime | Compose Multiplatform `1.10.3` |
| Compiler / plugin | Kotlin `2.3.20`, `org.jetbrains.kotlin.plugin.compose` `2.3.20` |
| Android Gradle plugin | `8.13.0`, Gradle `8.13` |
| SDK | `compileSdk` / `targetSdk` 37, `minSdk` 33 |
| JDK | 17 |
| Application id | `dev.kaorios.patcher` |

Rules:

- **No Material components.** Everything visible comes from Miuix (`top.yukonga.miuix.kmp.*`).
  Material3 remains only as a transitive dependency of the Compose plugin.
- **No hardcoded colours or text sizes.** Colours resolve through `MiuixTheme.colorScheme.*`,
  type through `MiuixTheme.textStyles.*`.
- Compose Multiplatform is retained for the runtime, but the app is **Android-only**;
  `:engine` is `jvm()` only. Compose artifacts are declared as direct coordinates; the
  `org.jetbrains.compose` Gradle plugin is not applied. Do not introduce `commonMain` UI code.

## 2. Commands

```bash
.\gradlew.bat :engine:jvmTest            # oracle parity suite — run before any engine change
.\gradlew.bat :app:assembleDebug         # debug APK
.\gradlew.bat :app:assembleReleaseFast   # release without R8 / resource shrinking
.\gradlew.bat build                      # everything
```

Version lives in `gradle.properties` as `appVersionName` / `appVersionCode`; never hardcode it
in `build.gradle.kts`.

## 3. Package layout

```text
app/src/androidMain/kotlin/dev/kaorios/patcher/
  MainActivity.kt          entry point; builds state, hands it to the shell, nothing else
  ui/
    navigation/AppShell.kt root navigation, pager, single Scaffold, snackbar host
    page/                  one file per screen, stateless
    component/             reusable rows, badges, page chrome
    theme/                 MiuixTheme wiring, preference keys, defaults
    service/               persisted state and platform services
    PatchViewModel.kt      state holder for the patch pipeline
  device/  pipeline/  module/  workspace/   (non-UI)
```

Hard rules:

- **`AppShell` owns the only `Scaffold`.** Pages must not nest another one; they receive the
  scaffold `PaddingValues` and apply it themselves.
- **Pages are stateless.** Every input is a parameter; no page constructs a ViewModel or
  touches `SharedPreferences` directly.
- Navigation is a `HorizontalPager` plus a `LiquidNavigationBar` with three tabs: **Patch**,
  **Output** and **Manual Patching** (the third is an inert, greyed preview — `ManualPatchPage`
  renders non-interactive rows only, and its title carries the `Coming soon` chip). Settings is
  *not* a tab: it is a separate page view pushed over the shell from the `TopAppBar`'s
  settings icon. Do not add a `NavHost`/`NavController` unless there are real detail routes.
- **The shell owns one Miuix `TopAppBar`** above the pager (large title + subtitle that fold
  through `MiuixScrollBehavior` — one behaviour per tab so each keeps its own fold). Pages never
  title themselves: they take a hoisted `scrollState` parameter so the bar fold and the edge-strip
  fade read the same value the page scrolls by. Settings gets its own `TopAppBar` (back icon
  left) and its own scroll state inside the page view; a confirmation `BackHandler` always wins
  over the settings one.
- A tab that is not selected stays composed so its scroll state survives a round trip.

## 4. Component conventions

Follow Miuix upstream so future swaps stay mechanical.

### Signature order

```kotlin
@Composable
fun ComponentName(
    onClick: () -> Unit,                        // 1. callbacks
    modifier: Modifier = Modifier,              // 2. modifier
    enabled: Boolean = true,                    // 3. flags
    cornerRadius: Dp = CardDefaults.CornerRadius,
    content: @Composable () -> Unit,            // last: content
)
```

- Trailing lambda is `content` only. Miuix's `BasicComponent` is the exception: its last
  parameter is `interactionSource`, so pass slots by name (`endActions = { … }`).
- A setting with a handful of choices stays on its own row: `SettingDropdown` wraps Miuix's
  `OverlayDropdownPreference`, so the list opens next to the row it belongs to. A dialog over the
  page for a single value (and the old `OptionRow` picker) is retired.
- `@NonRestartableComposable` only on thin wrappers that delegate fully and read no state.
- `@Immutable` on colour/style data classes with no lambda fields; `@Stable` when they hold
  callbacks.

### Shapes

- Filled surfaces: Miuix `Card`, or `Surface` with `RoundedCornerShape(radius)` /
  `CircleShape`. Miuix's smooth-corner shapes live in `top.yukonga.miuix.kmp.shapes`
  (`SmoothRoundedCornerShape`) — use them where a squircle is wanted.
- Radii come from the library's `Defaults` objects; do not invent new dp values per component.

### Icons

`MiuixIcons.<Name>` from `top.yukonga.miuix.kmp.icon.extended.*`. No text glyphs as icons —
the pre-Miuix navigation bar used `◆ ▣ ⚙` and that pattern is retired.

## 5. Theming

- `KaoriosTheme.kt` is the only place that constructs a `ThemeController`. Pages read the
  theme; they never build one.
- Preference keys are `pref_`-prefixed constants declared next to the theme, not inline strings.
- Theme modes are `system | light | dark`. Dynamic colour is on by default and falls back to
  the seed colour when switched off.
- Every mode resolves through Miuix's **Monet** schemes (`MonetSystem` / `MonetLight` /
  `MonetDark`) — never the static ones. A static scheme ignores `keyColor`, which silently
  makes the Dynamic colour toggle a no-op (both branches render the same blue).
- AMOLED pure black re-themes through the `colors` overload on **one** composition path: no
  `if/else` around `content()` (it would dispose the subtree and wipe page state), and never
  `remember` the black clone (the controller mutates `base` in place — a remembered copy
  freezes the palette at the first frame).
- System bars are driven from the resolved mode in a `SideEffect`; do not call
  `enableEdgeToEdge` anywhere else.

## 6. State and preferences

- `PrefsRepository` mirrors `SharedPreferences` into snapshot state so reads are pure and
  composables recompose on write. Compose functions do not mutate the map.
- Never seed a preference from inside composition — defaults go through `PrefDefaults` or the
  `remember*Preference` default argument.
- One `remember*Preference` helper per type; do not add ad-hoc `mutableStateOf` mirrors.

## 7. Strings and localisation

- **All user-visible text lives in `res/values/strings.xml`.** Hardcoded literals in
  composables are a review failure.
- Parameters use positional formatting (`%1$s`), never concatenation.
- Base `values/` first; add `values-{ja,ru,tr}` once a string has stopped changing. Keep
  translated files at full parity — partial translation yields a mixed-language UI.

## 8. Page checklist

Adding or changing a screen means all of:

1. Strings in `strings.xml`.
2. Page file under `ui/page/`, stateless, taking `contentPadding` and a hoisted `scrollState`.
3. Rows via `ui/component/` — do not inline a bespoke row.
4. Colours via `StatusTone.resolve()`, never literal `Color(...)`.
5. Any long-running action routed through `PatchViewModel`, surfaced as a terminal step.
6. `.\gradlew.bat :app:assembleDebug` green.

## 9. Glass and blur

Backdrop blur is **enabled**. `miuix-blur` needs runtime shaders (API 33), which is why
`minSdk` is 33 — the app targets HyperOS 4 / Android 17 only.

| Module | Requirement | Status |
| --- | --- | --- |
| `miuix-ui`, `miuix-core`, `miuix-shapes`, `miuix-icons` | `minSdk` 24 | in use at `0.9.0` |
| `miuix-blur` | `minSdk` 33 (runtime shaders) | in use at `0.9.0` |

Rules:

- All glass goes through `ui/component/GlassBlur.kt`. Never hand-roll a blur, and never
  reintroduce the old translucent-gradient + hairline-border `GlassPanel`.
- `rememberLiquidBackdrop(enabled)` captures the page layer once; `Modifier.recordLiquidBackdrop`
  marks the region that is recorded. **The navigation bar is composed outside the recorded
  region** — a bar inside it samples its own pixels and blurs to nothing. `GradientEdgeStrips`
  then draws the graduated top/bottom fade from the same backdrop: three nested layers per edge
  (`EdgeLayers`), each an eased alpha mask over the blurred content and **nothing else** — no
  tint, no scrim, no wash of the surface colour. A gradient that colours the edge is a vignette
  however softly it fades, and that is what made the old bands read as paint; the strip must
  leave the page's own colour alone and only take it out of focus. The strips are **scroll-gated**:
  each edge's alpha is computed inside `graphicsLayer` from the hoisted `ScrollState` (top fades
  in over the first `EdgeScrollFade` of scroll-away from the top, bottom fades out on approach
  to the end), so an edge at its rest bound draws nothing and a fling never recomposes a strip.
- Never clip the page or the bar: the swipe bubble has to be free to swell past the pill
  (`BubblePressScale` deliberately exceeds `BarHeight`), so anything that looks like it needs a
  clip is a sign the layer order is wrong instead.
- The bubble is glass at **rest**, not only under the finger. Its `lens`, rim, drop shadow and
  inset shadow all have non-zero resting values interpolated up to their press values; collapsing
  them to `held` leaves the bubble drawing nothing but its white fill, which reads as a flat
  square of paint rather than a chip of glass. Chromatic aberration is on throughout, so the
  prism fringe only shows once the refraction grows.
- The bubble belongs to the **finger**, not to the selection: the bar's own gesture walks it to
  the slot under the down event and only reports `onSelect` when the pointer lifts. Inflating
  whichever tab was already selected meant pressing an unselected tab lit up the one you were
  leaving.
- Check `isRuntimeShaderSupported()` before capturing; do not assume API 33+ implies support.
  That check belongs to `GlassBlur`'s backdrop capture only. The bar's own highlight is a plain
  `RuntimeShader` drawn unconditionally: `backdrop.asAndroidRuntimeShader()` (backdrop
  `2.0.0-alpha03` has no `asComposeShader`) into a `ShaderBrush`, which `minSdk` 33 makes safe.
- **No hover path at all** — `Modifier.onPointerEvent` does not exist in Compose UI `1.10.5`
  and `Hover` is not a `PointerEventType`, but the deeper reason is Kyant's: his
  `InteractiveHighlight` anchors the bloom to the bubble's *animated slot* (its position lambda
  ignores the contact point), so the light rides the pill through the physics rather than
  chasing a cursor. The bar therefore draws its glow from `NavBarPhysics.press` at
  `barPaddingPx + (progress + 0.5f) * slotPx` inside one `drawBehind` — no second
  `pointerInput`, no cursor state, nothing that can go sticky.
- The bar draws from an **empty sibling layer**: the first child under
  `rememberLiquidBackdrop` is a placeholder `Box`, and the pill/bubble/glow are unclipped
  siblings composed after it, so the recorded region never contains the bar's own pixels.

Settings → Appearance controls all of it at runtime: *Floating glass bar*
(`pref_liquid_glass_navbar`) for the pill and its swipe bubble, *Edge gradient blur*
(`pref_edge_gradient_blur`) for the page fade, *Dynamic colour* (`pref_monet_dynamic_color`) for
the Monet palette, and *AMOLED pure black* (`pref_amoled_black`) for #000000 structural
surfaces in dark mode — the last is applied in `KaoriosPatcherTheme` by re-theming on top of a
`copy()` of the resolved scheme (Monet accents survive; `LocalColors` itself is internal, so the
public `MiuixTheme(colors = …)` overload is the hook).

Version ceiling: Miuix `0.9.4` needs AGP `>= 9.1` and Compose `1.12.0`, which this toolchain
cannot satisfy. Upgrading Miuix means upgrading AGP, Gradle and `compileSdk` together.

### Confirm dialogs

Flash and Clear confirmations are liquid-glass overlays owned by `AppShell`, not platform
`Dialog`s — a subcomposition would sit outside the recorded backdrop and lose the glass.

- State lives in `AppShell` (`confirm: ConfirmSpec?`), never in a page. Pages stay stateless
  and receive **gated** callbacks built with `actions.copy(...)` — both `onFlash` and `onClear`
  (including the copy inside Settings) route through the dialog.
- `BackHandler(enabled = confirm != null)` — back dismisses, it never confirms, and it always
  wins over the settings page view's own back handler.
- Layer order inside the root `Box`: the record region contains the Scaffold, the settings page
  view and then the scrim (`drawRect(Color.Black, alpha = DialogScrimAlpha × spread)`) — the
  dim sits **above** Settings so a confirmation opened from there dims it like any other page;
  outside the record region come the edge strips, navbar, snackbar, a full-screen dismiss/blocker
  layer (`clickable(indication = null)`) that eats every tap, then the centered
  `LiquidConfirmDialog` panel. The blocker sits **above** the navbar, so a tap that would reach
  a tab dismisses the dialog and leaves the page alone.
- Animation is the HOS4 **dialog fly-out**: the panel drops from above its resting slot
  (`translationY = -(1 - p) × DialogFlyOutDistance` (220.dp), scale lerps 0.94 → 1, alpha with
  `p`) under `DialogSpreadSpring` (0.72 / 320, slightly under-damped for a small pop) while the
  scrim ramps to `DialogScrimAlpha` (0.42). The corner is a **constant 16% card** — there is no
  capsule-that-spreads stage; the motion is the drop, not the shape.
- Every dialog string lives in `strings.xml` (`dialog_*`), including the buttons.

### Snackbar

Terminal steps raise a root snackbar through Miuix's `SnackbarHost`, composed **outside** the
record region (same reason as the bar). Two traps:

- Miuix's slot parameter is `content =`, not `snackbar =`.
- Pass the bar's backdrop through: `content = { GlassSnackbar(it, backdrop = barBackdrop) }`.
  `GlassSnackbar` draws the capsule (refraction lens, rim, highlight) from that backdrop and
  falls back to a translucent surface when there is none. Host alignment is `BottomCenter`
  with `padding(bottom = navbarHeight + 14.dp)` so the capsule floats above the pill.

## 10. Error surfacing

The engine is fail-closed: a rejected layout leaves the file untouched. The UI must make that
visible rather than implying success.

- Failure state → `StatusTone.Error`; partial outcome → keep the tone, keep the detail line.
- Terminal steps raise a root snackbar so the failure is visible from any tab.
- Never suppress an error because the pipeline is still busy.

## 11. Permissions

Shared-storage access goes through `storage/DownloadsStore.kt`, never a hardcoded path. Android 17
blocks direct `File` access to shared storage and ignores `requestLegacyExternalStorage`, so
`MANAGE_EXTERNAL_STORAGE` is the only way to keep the rest of the app on `java.io.File`.

- Ask only when a run actually needs it: the Patch tab shows the grant card *only* while
  `downloadsGranted` is false, and hides it once granted.
- `patchAndBuild` re-checks the grant and fails with `downloads.explain(null)` rather than
  surfacing a bare `FileNotFoundException` from deep in a copy.
- A permission is a gate, not a warning. Never let a run continue in a state where its output
  cannot be written.

## 12. Preconditions

Some steps need an input the app cannot produce. Surface the requirement as a dedicated card, not
as an error after the fact — see ModulePage's *KaoriOS runtime* section, which lists the classes a
merged runtime contributes and states plainly that a module without one is not safe to flash.

The rule generalises: when correctness depends on an external input, show its status on its own
row so absence is visible before the user commits to a run, and again in the result.

## 13. Documentation

Behaviour visible to a user needs a `README.md` note. UI structure changes update this file.
# KaoriOS RePatcher

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/nothingnesscore/KaoriOS-RePatcher?label=release)](https://github.com/nothingnesscore/KaoriOS-RePatcher/releases)

Patch a rooted Android phone into a KaoriOS-style setup **entirely from the phone**.
The app pulls `framework.jar` / `services.jar` (plus `miui-services.jar` when the ROM has
one) over root, rewrites exactly the smali classes the guides name, folds in the KaoriOS
runtime dex, and builds one flashable KernelSU/Magisk module — then installs it for you.
Android 13–17, HyperOS/MIUI and AOSP. No PC, no cable.

- **One pipeline on the device:** pull → patch → build → install, with live logs.
- **Fails closed:** a layout it does not recognise is left byte-identical, and a run with
  any failure never writes a module.
- **Wrong-ROM guard:** the installer re-hashes your still-stock jars and aborts on a
  mismatch before anything is mounted.
- **Boot guard:** a first boot that never finishes disables the module and puts stock back.
- **Proven by comparison:** 110 differential tests replay the reference Python patchers'
  exact output against the Kotlin engine, byte for byte.

## Build it yourself

Prerequisites:

- **JDK 17**
- **Android SDK with platform 37** — for the `:app` module only; the engine tests run on
  a plain JVM with no Android SDK
- **Python 3** — only if you regenerate the differential test fixtures

```bash
git clone https://github.com/nothingnesscore/KaoriOS-RePatcher.git
cd KaoriOS-RePatcher

./gradlew :engine:jvmTest        # 110 tests — must be green (Windows: gradlew.bat)
./gradlew :app:assembleDebug     # needs an Android SDK
```

The `:app` module is included when an SDK is reachable: `local.properties` with
`sdk.dir=...` (Android Studio writes that file for you) **or** the `ANDROID_HOME` /
`ANDROID_SDK_ROOT` environment variable. The APK lands in
`app/build/outputs/apk/debug/`.

Run everything the way the release workflows do:

```bash
./gradlew build :engine:jvmTest
```

### Repository map

| Path | What lives there |
|---|---|
| `engine/src/jvmMain/kotlin/dev/kaorios/engine/patch/` | the smali patchers — one per guide target, all fail-closed |
| `engine/src/jvmMain/kotlin/dev/kaorios/engine/dex/` | disassemble → patch → rebuild round trip (baksmali/DexPool) |
| `engine/src/jvmMain/kotlin/dev/kaorios/engine/module/` | builds the flashable module zip (scripts, overlays, digest guard) |
| `engine/src/jvmMain/kotlin/dev/kaorios/engine/cli/` | desktop entry points (`runCli`, `runStringProbe`) |
| `app/src/androidMain/kotlin/dev/kaorios/patcher/` | the phone app: root client, workspace, pipeline, UI |
| `app/src/androidMain/kotlin/dev/kaorios/patcher/ui/PatchViewModel.kt` | orchestrates pull → patch → build → install |
| `app/src/androidMain/res/values/strings.xml` | every user-visible string |
| `engine/src/jvmTest/resources/oracle/` | committed differential fixtures — generated, never hand-edit |
| `tools/` | fixture generator, pre-flash verification gates |
| `UI_GUIDELINES.md` / `AGENTS.md` | UI rules / architecture, invariants, verification deep-dive |

Check a built module before flashing it (device-free gate):

```bash
python tools/verify_module.py <output-dir>     # -> SAFE TO FLASH / DO NOT FLASH
```

### Release signing

Release builds are signed only when you configure a keystore; otherwise they come out
unsigned (debug builds are always installable):

```bash
keytool -genkeypair -v -keystore keystore/release.jks -alias kaorios \
  -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties    # then fill in path + passwords
./gradlew :app:assembleRelease
```

Keystores are gitignored on purpose — each maintainer signs with their own key, and an
update must carry the same signature or Android treats it as a different app. The release
workflows use the same mechanism through repository secrets (see *Releases* below).

## Use it on the phone

1. Install the APK from the [releases page](https://github.com/nothingnesscore/KaoriOS-RePatcher/releases),
   grant root and *All files access*.
2. Tap **Patch**. The app reads your device, pulls the system jars, fetches the runtime
   assets, patches, and writes `Download/kaorios_patcher.zip` (about five minutes).
3. Open the **Module** tab → **Install module**, then reboot.
4. Optionally watch it live: `adb logcat -s KaoriosPatcher`.

The Build-spoof sections target Android 17 only (the reference implementation refuses
older); on Android 13–16 the hook set still applies. The module's property layer applies
itself at every late start — nothing waits for a button press.

## Safety

Patching a file ART will boot-loop over demands sutures, not scissors:

- **Every patch verifies its own output.** An unrecognised layout raises, the original
  bytes stay, and the run reports failure — no half-patched tree, no module.
- **The installer refuses the wrong ROM.** `customize.sh` re-hashes the still-stock jars
  (a few seconds) and `abort`s on any mismatch — *before* any mount action, because after
  it a direct-overlay copy has already clobbered the partition.
- **A bad flash costs one reboot, not a loop.** Every boot arms a pending marker; if the
  previous boot never completed, the module disables itself, copies the stock jars back
  and reboots.
- **Magic mount by default.** KernelSU overlays the module onto `/system` at boot — the
  installer performs no copy into a read-only partition.
- **Honest caveat:** a `framework.jar` that passes every test can still refuse to boot on
  a ROM nobody has exercised. Flashing is the riskiest step you take — keep a way back.

## Releases

Two GitHub Actions workflows, both triggered by hand from the
**[Actions](https://github.com/nothingnesscore/KaoriOS-RePatcher/actions)** tab
(*Run workflow*), both gated by the same checkpoint: the full Gradle build **and** the
110-test engine suite must pass on that very run before any APK is published.

### Stable release — `stable.yml`

- Refuses to run if `gradle.properties` still names an already-tagged version, so bump
  `appVersionName` / `appVersionCode` first and tag, `versionName` and `versionCode` can
  never drift apart.
- Requires the signing secrets (below), builds the release APK, and publishes a normal
  release titled `KaoriOS RePatcher <version>` — with the version and `versionCode`
  spelled out, the APK's SHA-256, install steps, and a request to report anything that
  breaks.
- The notes point testers at the beta track for the next cycle: this is the "has soaked"
  build.

### Beta prerelease — `beta.yml`

- Publishes a **prerelease** tagged `v<appVersionName>-beta.<run number>`, titled
  `KaoriOS RePatcher <version> (beta)`, whose notes open with a friendly
  **work in progress** warning: rough edges are expected, that is the point.
- Same checkpoint, same version fields in the notes, and the APK itself carries the beta
  suffix in `versionName`, so a beta is always identifiable on the device.
- Signed with the same keystore; when a repository has no signing secrets it falls back
  to a debug-signed APK — fine for testers, not for upgrades over a stable.

### Versioning

`gradle.properties` is the single source of truth both workflows read:

```properties
appVersionName=1.2.0
appVersionCode=3
```

| Track | Tag | Rule |
|---|---|---|
| Stable | `v1.2.0` | must not already exist — bump after every stable release |
| Beta | `v1.2.0-beta.7` | run number keeps it unique; `versionName` carries the suffix |

### For fork maintainers

Four repository secrets enable signed releases: `RELEASE_KEYSTORE_B64` (base64 of your
`.jks`), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
Without them betas still publish (debug-signed) and stables refuse to.

### Want to help?

Install the newest beta — or a stable — use it for real, and open an issue with what you
were doing, your Android/HyperOS version family, and the matching lines from
`adb logcat -s KaoriosPatcher`. Every bug found on a beta is a bug that never reaches a
stable; that feedback loop is what the two tracks exist for.

## How it is verified

```bash
./gradlew build :engine:jvmTest       # 110 differential tests against the reference
python tools/generate_oracle.py       # regenerate fixtures (needs Kaorios-Toolbox next door)
python tools/verify_module.py <dir>   # deep pre-flash gate -> SAFE TO FLASH / DO NOT FLASH
./gradlew :engine:runCli "-PcliArgs=<abs pulled> <abs work> FULL MAGIC_MOUNT <abs dex>"
```

- **Oracle parity.** `tools/generate_oracle.py` runs the reference Python patchers and
  records their exact output under `engine/src/jvmTest/resources/oracle/`;
  `OracleParityTest` replays every case and requires byte-identical results.
- **Pre-flash gate.** `verify_module.py` checks dex signatures and checksums, the runtime
  merge, reference resolution against stock jars, and the module inventory — proof a build
  is flashable without touching a device.
- **Desktop round trip.** `runCli` replays the app's whole pipeline against already-pulled
  jars, so a ROM can be validated end-to-end without flashing.

## The UI

The app should look like it belongs on HyperOS: a floating glass tab bar — **Patch**,
**Output**, **Manual Patching** — whose little bubble slides between tabs as you switch,
big titles that fold away as you scroll, settings pushed in over the shell from the gear,
dialogs that drop in from the top, and accent colours that follow your wallpaper. The blur
is real GPU glass (which is why `minSdk` is 33 — Android 13+). Everything is Jetpack
Compose + Miuix, there are no Material widgets, and every visible string lives in
`strings.xml`.

Written rules for contributors: [`UI_GUIDELINES.md`](UI_GUIDELINES.md).
Architecture, invariants and verification detail: [`AGENTS.md`](AGENTS.md).

## Credits

This repository builds on other people's work — listed here with what was used, where it
comes from and under which license, rather than claimed as original:

| What | Source | License |
|---|---|---|
| The patch semantics this engine ports (the Python patchers and guides), plus the runtime dex and manager APK, which are fetched from its releases | [Kaorios-Toolbox](https://github.com/hzzmonetvn/Kaorios-Toolbox) | upstream ships no license file — its artifacts and terms remain its own |
| The `ksud` module-install flow the app drives, and the manager bottom bar whose proportions the floating bar is measured against | [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) | GPL-3.0 |
| Liquid-glass rendering approach — damped drag, highlight, and the `backdrop` library, which is a direct dependency | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 |
| The Compose component kit (Miuix), a direct dependency of the whole UI | [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 |
| UI references | [ColdP/HyperChanger](https://github.com/ColdP/HyperChanger) | Apache-2.0 |
| UI-stack inspiration for the Miuix composition | [HyperIsland](https://github.com/1812z/HyperIsland) | see upstream |
| The smali/baksmali toolchain the round trip runs on, and the smali dialect the patchers target | Google AOSP smali | Apache-2.0 |

## License

[GNU General Public License v3.0](LICENSE).

GPL-3.0 covers what was written here: the Kotlin engine, the Android app and the
tooling. Third-party components keep their own licenses (the Apache-2.0 dependencies are
GPL-compatible; the GPL-3.0 SukiSU-Ultra work is credited above). The upstream
Kaorios-Toolbox publishes no license file for its Python sources — no ownership over them
is claimed; this license simply covers the Kotlin port, the app and the tooling in this
repository.

# KaoriosPatcherApp — Agent Notes

Kotlin port of the Kaorios-Toolbox A17 smali patcher, packaged as an Android app that pulls
system jars through SukiSU, patches them with `:engine`, and emits a flashable KernelSU module.

`Kaorios-Toolbox/` next door is a **read-only Python reference**. It defines the behaviour
`:engine` must reproduce; never edit it and never make the build depend on it at runtime.

## Stack

Kotlin 2.3.20 · KMP `:engine` (`jvm()` only) · Android `:app` · AGP 8.13.0 · Gradle 8.13 ·
compileSdk/targetSdk 37 · minSdk 33 · JDK 17 · Miuix 0.9.0 (incl. `miuix-blur`) ·
AOSP smali 3.0.10.

Deployment target is HyperOS 4 / Android 17 (SDK 37) on a rooted device. `minSdk` is 33 purely
because `miuix-blur` needs runtime shaders — do not lower it without dropping blur.

## Device detection

The app reads the Android major off the API level (SDK 33 → Android 13 … SDK 37 → Android 17,
`DeviceSpec.androidMajor`) and the ROM family off `ro.mi.os.version.name` / `ro.miui.ui.version.*`
(`DeviceSpec.romType`: `HOS` = MIUI/HyperOS, `AOSP` = a plain build; `hosVersion` is the raw
property, `OS4.0` or `V816`). The selection follows from that through `PatchSelection.forAndroid`:
the hook set (guide mode 1) covers Android 13-17, the Build spoof (mode 2/3) is A17-only —
upstream's `kaorios_patcher.py` refuses it on anything older — so a 13-16 device runs hooks alone
and `Build` / `Build$VERSION` are neither targeted nor disassembled. `patchAndBuild` refuses to
run when the device is unknown or outside SDK 33-37, and `PatchUiState.selection` defaults to
hooks-only before the device has been read.

**`miui-services.jar` is pulled only when the file exists on the device.**
`SukiSUClient.fileExists` stats the path first; an AOSP ROM has no such jar at all (CorePatch §3
is the guide's one "if present in the ROM" section), so a missing file skips the pull, deletes
a stale copy from an earlier pull, and never fails the run — framework/services remain
mandatory. `GuideCheck.report(miuiServices = …)` mirrors the same signal so
`PackageManagerServiceImpl.smali` is *not expected* there instead of being counted missing, and
the section line reports `CorePatch §1-§2 (no miui-services.jar)`. The Device row shows both
readings (`ROM: HOS OS4.0` / `ROM: AOSP`), and `moduleContents` lists the jar only when
CorePatch is on *and* the workspace actually holds it.

## Commands

```bash
python tools\generate_oracle.py    # regenerate differential fixtures from the Python reference
.\gradlew.bat :engine:jvmTest      # 110-test suite — mandatory before engine changes
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleReleaseFast
.\gradlew.bat build
```

Desktop round-trip against already-pulled jars — the same code path the app runs, no device:

```bash
.\gradlew.bat :engine:runCli "-PcliArgs=<pulled-dir> <work-dir> [FULL|ALL_IN_ONE|HOOKS|BUILD_SPOOF] [MAGIC_MOUNT|HYBRIDMOUNT|DIRECT_OVERLAY] [--disassemble-only] [kaorios.dex]"
.\gradlew.bat :engine:runStringProbe "-PprobeArgs=<archive> <dex> <descriptor> [method]"
```

`:app` is included when `local.properties` contains `sdk.dir=` or `ANDROID_HOME` /
`ANDROID_SDK_ROOT` is set (`settings.gradle.kts`).

`runCli` takes **absolute** paths only — a relative `<pulled-dir>` resolves against the Gradle
daemon's working directory and silently pulls nothing. `FULL` is the whole port (hooks + Build +
`Disable_Secure_Flag` + `CorePatch` §1-§3); the other modes select a subset. Long runs must be
launched detached (`Start-Process ... -File run.ps1`) with stdout redirected and a `.done`
sentinel — the MCP shell call returns before Gradle finishes, and re-launching duplicates the
build.

## Releases

Two manual GitHub Actions workflows in `.github/workflows/`, both checkpointed on
`./gradlew build :engine:jvmTest`:

- `stable.yml` — refuses to run if `v<appVersionName>` is already tagged (bump
  `gradle.properties` first), requires the signing secrets, publishes a normal release
  whose notes invite testers to report problems.
- `beta.yml` — publishes `v<appVersionName>-beta.<run>` as a prerelease with a
  work-in-progress warning; debug-signed when the repo has no signing secrets.

`gradle.properties` (`appVersionName` / `appVersionCode`) is the single source of truth
both read; bump it after every stable release. Signing resolves from `-Pks.*` Gradle
properties (CI: secrets `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) with the gitignored `keystore.properties`
as the local fallback. CI finds the SDK through `ANDROID_HOME`/`ANDROID_SDK_ROOT`
(`settings.gradle.kts`) and installs `platforms;android-37.0` + `build-tools;37.0.0`.

## Architecture

```
:engine   jvm-only. patch/* = smali text engine; dex/* = AOSP smali round-trip;
          module/* = KSU zip builder; cli/* = desktop entry points.
:app      Android shell: root access, workspace, pipeline, Miuix UI. All patching and packaging
          lives in :engine; :app has no smali or zip logic of its own.
```

Round trip: `DexArchiveRoundTrip.disassemble` (baksmali, filtered to `PatchEngine.disassemblyFiles(mode)`
— every class the mode patches, plus any the guides name so an absence can be *reported* rather
than assumed) → `PatchEngine` rewrites the smali in place → `rebuild` assembles only the changed
classes and merges them back through `DexPool`. A dex is rewritten only when a target inside it
changed, so untouched dexes stay byte-identical. Measured at 54–79 s for `FULL` — framework,
services and miui-services, 29 patched targets (incl. `AppsFilterBase`) — on a PC against
`local-work/pulled`.

## The KaoriOS runtime dex

Every hook injects an `invoke` into `Landroid/security/kaorios/KaoriosHook;`. That class is not in
AOSP, so it must be merged into `framework.jar` or the patched module fails ART verification at
boot. `KaoriosRuntime` owns this: it validates that the supplied dex *defines* the hook (reading
the class list, not the string pool — a patched archive references the hook without defining it),
then picks which existing dex to fold it into.

The dex is built from a private source repo and published as the `classes.dex` asset of the
`Kaorios-Toolbox` GitHub release; it cannot be derived from this repository. The app fetches it
itself: every Patch tap runs `ReleaseSync` after the pull/permission validations and before
disassembly, downloading the release's `classes.dex` → `workspace/assets/kaorios.dex` and its
signed manager APK → `workspace/assets/KaoriosToolbox.apk`, and logs one line —
`release sync <tag>: kaorios.dex=<n>B KaoriosToolbox.apk=<n>B`. The download fails closed (dex
`dex\n` magic, APK `PK\x03\x04` + `APK Sig Block 42`, `.part` → rename), and a failed sync
leaves the existing offline cache in place, so the run still works from whatever the assets
already hold; a first run with neither network nor cache falls back to a manual drop, so:

```bash
adb push kaorios.dex /sdcard/Download/kaorios.dex   # used only when the assets cache is empty
```

(the assets dex always wins when present — `assets.runtimeDex ?: findRuntimeDex()`). See also
`Bundle.toolboxApk`: the app hands the builder the synced release APK from `workspace/assets/`
in preference to the bundled copy.

**The runtime is folded into a dex that is already there. It never gets a slot of its own.**
`OatFile::Open` compares the jar's dex count against the one baked into `boot-framework.oat` and
refuses the boot image on a mismatch (`expected 6 uncompressed dex files, but found 7`); the
resulting interpreter-only framework boots so slowly that the MIUI watchdog kills zygote, which
is the loop this design exists to prevent. `KaoriosRuntime.hostSlots` therefore only *reorders*
dexes the archive already has: the slot that already holds the hook first (so reflashing
replaces it in place instead of leaving a second copy), then the rest smallest-first, because
the smallest dex has the most room under dex's 65536-entry id limits. `DexArchiveRoundTrip`
tries each candidate and fails closed if none will take it. The rebuilt archive is rewritten
entry for entry — no entry is ever added or removed — and is 4-byte aligned by
`AlignedZipWriter`, which `dex2oat` requires before it will mmap a dex straight out of the jar.
`KaoriosRuntimeTest` pins all of it, including that the dex count is unchanged.

`DownloadsStore` reads that dex and writes the finished module via `MANAGE_EXTERNAL_STORAGE`.
Scoped storage blocks direct `File` paths into shared storage from API 30 and
`requestLegacyExternalStorage` is ignored there, so "All files access" is the only route that
keeps the rest of the app on `java.io.File`.

## Verification

`:engine` is differentially tested against the Python patchers. `tools/generate_oracle.py`
imports them and writes `engine/src/jvmTest/resources/oracle/<case>/…`; expected outputs are
compared byte-for-byte. **Write oracle files with `newline=""`** — the generator reads sources
with universal newlines, so CRLF translation corrupts the fixtures.

The oracle tree is generated, not hand-edited, and is committed as test data so a fresh
clone runs the suite green — regenerate with `tools/generate_oracle.py` and diff before
accepting any change after an upstream move.

Upstream renamed the entry point at commit `d2c3b83`: `script/kaorios_patcher_a17.py` is a
`runpy` shim whose *module* executes `main()` on import, so loading it drops the patcher
functions, and `script/kaorios_patcher_a17_test.py` was deleted outright. `generate_oracle.py`
therefore loads `script/kaorios_patcher.py` and harvests fixtures from the verbatim snapshot in
`tools/upstream_kaorios_patcher_a17_test.py`. Every `patch-*.py` / `verify-*.py` is
byte-identical upstream, so a regeneration against the current tree reproduces the committed
oracle exactly — diff it before accepting any change.

The fixtures are abridged and contain no string literals inside patched method bodies, so they
cannot catch whole classes of porting bug. `SmaliTransformOutsideTest` guards the
literal-splitting gap and `KeystoreConsistencyTest` pins the v2.0.6.1 leaf delegation against
`script/test_keystore_consistency.py`, and `real_a17_*` oracle cases carry verbatim classes from
a production ROM. Add to these whenever a patcher starts rewriting text it does not own.

Real-ROM oracle cases are generated only when `local-work/pristine/smali` holds a disassembly;
`generate_oracle.py` skips them otherwise, so a checkout without a device still runs.

### Offline pre-flash gate

`tools/verify_module.py` is a device-free gate over a built `framework.jar` / `services.jar`
/ `miui-services.jar` set. It reuses the hand-written dex reader in `tools/verify_dex_refs.py`
and checks, in one run: every dex's sha1 signature + adler32 checksum + header, the `u2`-capped id
tables and per-jar duplicate classes, that all 334 runtime classes are actually *merged* (not
merely referenced) and that the hooks reference them, that the runtime dex resolves every one of
its own references against stock, and — the important one — that the set of references the patch
*introduced* is fully resolvable, by diffing the same resolution check over the stock jars.

```bash
python tools\verify_module.py local-work\run19\out    # -> SAFE TO FLASH / DO NOT FLASH
```

The stock side is `local-work/device_orig_fw.jar` + `device_orig_sv.jar` + `device_orig_mis.jar`;
`pair()` prefers a patched name over a stock name per jar, so an output directory reads as patch
output and a jar the run never touched is simply not found.

Point it at stock to confirm the control still fails: with no runtime merged, the "runtime merged
and referenced" section must go red. The sixth section, **module inventory**, runs whenever a
`kaorios_patcher.zip` sits beside the directory (or is passed as a fourth argument): required
entries, `module.prop` keys, the digest guard ahead of every mount marker, the Toolbox APK
byte-identical to `engine/src/jvmMain/resources/module/KaoriosToolbox.apk` and still carrying
`APK Sig Block 42` (that byte-compare pins the *bundled* resource: a module the app built after
a release sync legitimately carries the newer release APK instead — `Bundle.toolboxApk` — so
this one check goes red while the APK is still the signed release; compare the zip's entry sha
against `workspace/assets/KaoriosToolbox.apk` before treating it as a defect, and note the
sig-block check applies either way), the privapp whitelist covering `PRIVAPP_PERMISSIONS`, the
jars inside the
zip byte-identical to the ones the dex sections just checked, **no `SettingsProvider.apk` at
all** (upstream `8c752fd` retired provider patching — the stock APK is never rebuilt, re-signed
or shipped), and the property layer — `props.sh`
must define `kaorios_props_reset` and `kaorios_props_clear`, be the *only* entry carrying the
spoofed list, be sourced and applied by `service.sh`, and be dispatched by `action.sh` alongside
the `pif.prop` / `pif.json` / `package_cache` purge, with no other script rewriting runtime
properties. Prove it fails too: delete `service.sh` from a copy, flip one byte inside its Toolbox
APK, strip `miui-services.jar` from a run that verified it, add a
`system/priv-app/SettingsProvider/SettingsProvider.apk` entry to a zip, or rename
`kaorios_props_clear` in `props.sh`.

**`miui-services.jar` is conditional in both directions.** CorePatch §3 is the one section the
guide marks "if present in the ROM", so a ROM carrying neither method reports `NOT_TARGET`, the jar
is copied through unchanged and never reaches the zip. The inventory therefore *requires* the
`system/system_ext/framework/miui-services.jar` entry when the run verified the jar and
*forbids* it when the run did not — an unverified jar has nothing for the digest guard to compare
against. `/system/system_ext` is a symlink to `/system_ext` on this ROM, which is why the overlay
path is spelled out in `MODULE_ENTRY` rather than derived from the file name.

The dex reader needed two fixes worth remembering — the `direct_methods` and `virtual_methods`
lists each restart their `method_idx_diff` base at 0, and array descriptors must be unwrapped and
whitelist-checked before being treated as missing.

The gate has been run against a module the **app** built on the device (`local-work/run15`, the
same jars it just pulled): 6/6 PASS, `SAFE TO FLASH`, exit 0. Those jars came out byte-identical
to the PC-built `run14` pair, which is the reproducibility check that matters — the platform
(`baksmali`/`DexPool`) agrees on both sides. It is green again over the three-jar `FULL` build,
most recently `local-work/run19` (6/6, exit 0), and the property-layer negative control holds:
renaming `kaorios_props_clear` inside a copy of that zip turns only the inventory section red and
flips the verdict to `DO NOT FLASH`, exit 1.

## Observability

Every on-device phase logs under one tag, `KaoriosPatcher` (`PatchLog` in `:app`): step
transitions, each target as `PATCHED` / `ALREADY_PATCHED` / `UNSUPPORTED_LAYOUT` / `FAILED` /
`NOT_TARGET` (guide §5's vocabulary), the runtime dex class count, the rebuilt dex list plus the
host slot the runtime folded into, the release-sync summary
(`release sync <tag>: kaorios.dex=…B KaoriosToolbox.apk=…B` — sync logs no `step ->` line of
its own), the stock digests, and the module path/size/sha256. Flashing logs its `ksud` verdict. Watch a run live with:

```bash
adb logcat -s KaoriosPatcher
```

`GuideCheck` then prints a conformance table against three guides —
`Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.1.md` (`framework.jar` §2-§6, `services.jar` §1-§3 and
`Optional patches` §1-§2), `Disable_Secure_Flag.md`, and `CorePatch.md` §1-§3 — keyed by
`GuideCheck.REVISION`, which names the upstream commit all three were read at (adding a table
does not move it; re-reading the guides at a new commit does; it is `8c752fd` as of v2.0.6.1). It
reports each expected file `ok` / `MISSING` / `REJECTED`, plus `absent` for the three sites the
guides themselves let a ROM skip, and names the entries deliberately out of scope —
`SettingsProvider.smali` (retired upstream at `8c752fd`; the guide says keep the stock
SettingsProvider) and `WindowStateAnimator.smali`, each with
why. The table is built from `PatchSelection`, not from a mode, so an optional guide
switched off simply is not expected that run. It is **reporting only**: it
never changes the patch set, and a miss is logged as a warning rather than blocking the build,
because `PatchEngine` already fails closed on anything it rejected.

**`NOT_TARGET` is ok — but only where a guide allows it.** Absence of a named method in a
required guide section is
`UnsupportedLayoutException` (fails the run); only CorePatch §3 ("if present in the ROM"), DSV's
third site and Optional §1's `getStringForUser` may come back `NOT_TARGET`, and those are the
entries in `GuideCheck.OPTIONAL`.
`NOT_TARGET` on anything else is logged `ABSENT` and counted as a miss, because it means the table
and the patch set disagree. Either way a skipped file is left byte-identical: `PatchEngine` only
writes what a patch reported as changed, so a skip can never leave a half-patched tree.

The guide's §9 verifiers run against our output too: disassemble the built jars with
`runCli ... --disassemble-only`, then point `verify-services-a17-hooks.py`,
`verify-systemserver-a17-hooks.py` and `verify-framework-a17-hooks.py --caller-only` at the
resulting `smali/` tree. All three pass on `run15`. Without `--caller-only` the framework
verifier fails on `expected exactly one android/security/kaorios/KaoriosHook.smali ... found 0`
— it needs a *full* disassembly of the final artifact, and our round trip filters to the target
classes. That mode is also the one AGENTS records as failing on the AdvancedPolicy revision
mismatch, independent of the tree filter.

## Engine invariants

- Patchers fail closed: an unrecognised layout leaves the file byte-identical.
- `PatchEngine.run(...).ok` is false if any target failed. Never soften this.
- Smali line helpers are deliberate: `Smali.splitLines` drops a trailing empty line (Python
  `splitlines()` semantics) while `Smali.splitKeepEnds` preserves offsets for injection. Use
  them instead of `lines()` / `split("\n")`.
- **`Smali.transformOutside` is not optional.** Kotlin's `Regex.split` drops capture groups;
  Python's `re.split` keeps them. The reference ports therefore alternate
  `if (i % 2 == 1) keep else rewrite`, which in Kotlin silently deletes string-literal contents
  and yields lines like `const-string v0, ` that no assembler accepts.
- Register-directive offsets are re-resolved **after** `canonicalizeParamAliases` renames
  `v*` → `p*`; stale offsets silently corrupt the patch.
- `baksmali` `BaksmaliOptions.registerInfo` must stay `0`. Any non-zero value makes it run dex
  analysis, which dereferences a `ClassPath` we never build for a filtered dex and NPEs on every
  class. `canonicalizeParamAliases` derives parameter aliases positionally, so the `.param`
  annotations `registerInfo` would emit are not needed.
- Disassembly emits `.registers` with `p`-style parameters, matching the text the patchers are
  written against. `.locals` output silently breaks their anchors.
- **Line endings are normalised to LF at the disassembly boundary.** baksmali writes the host
  separator, and on Windows that is CRLF, which defeats every LF-anchored pattern ported from the
  reference. PC and device results diverge silently without this.
- **Never mix coordinate systems inside a patcher.** `InstallerSourcePatch` needs both a body
  line number (to splice text back in) and a position in the filtered instruction list (to walk
  the CFG); it once used the former for the latter and blew past the end of the list.
- Root access goes through `/system/bin/su` only. `/data/adb` is unreadable to the app's uid, so
  the manager's own CLI cannot be probed directly; identity comes from `su -v`. The KernelSU
  family's CLI is `ksud`, not `ksu`.
- **`customize.sh` refuses to install on the wrong ROM.** `Bundle.stockDigests` carries the
  SHA-256 of each pulled original, and the build fingerprint when known; the installer hashes the
  still-stock `/system` files and `abort`s on any mismatch. A module overwrites `framework.jar`
  at boot with no rollback path, so hashing ~88 MB there is cheap insurance against a boot loop.
  Hash the file at install time, never the module's own copy. The guard is emitted **before** the
  mount action — `DIRECT_OVERLAY` copies straight into `/system` and `HYBRIDMOUNT` registers live
  VFS rules, so an `abort` issued afterwards would find the stock jar already overwritten. The
  hybridmount rule list is derived from `Bundle.artifacts` for the same reason: only jars that are
  actually in the zip get a rule. Both mount branches now derive from `overlayPaths`, which is
  `artifacts` plus the Toolbox entries, so a path can never be named for a file the zip lacks.
- **The Toolbox APK reaches the zip only as a byte stream, never as a rebuilt artifact.** Two
  routes, both byte-for-byte: `Bundle.toolboxApk`, when the builder is handed a file, wins — the
  app always hands `workspace/assets/KaoriosToolbox.apk`, i.e. the release-synced signed APK,
  after requiring the file and its `APK Sig Block 42` — and when it is null the builder falls
  back to `putResource` over `engine/src/jvmMain/resources/module/KaoriosToolbox.apk`. Neither
  route stages through `java.io.tmpdir`, which is not reliably writable on Android.
  `rejectApks` refuses **every** `.apk` handed in as a *patched artifact*. Upstream `8c752fd`
  removed the one exception this used to carry (`KsuModuleBuilder.SETTINGS_PROVIDER_APK_PATH`
  behind `Bundle.allowRebuiltSettingsProvider`, re-signed by the now-deleted
  `dev.kaorios.engine.apk.ApkSigner` with the throwaway key from
  `src/jvmMain/resources/apk/signing.p12`), so there is no escape hatch left: a rebuilt APK
  always loses its v2 block and `Package Manager` drops it, and the only `.apk` that ever
  reaches the zip is the Toolbox's own — release-synced bytes or the bundled resource, never
  anything this codebase rebuilt.
- **`SettingsProvider.apk` never reaches the zip, at any path, in any state.** Upstream `8c752fd`
  retired provider patching — the guide says keep the stock APK — so `PatchSelection` has no
  settings-provider target, no mode disassembles `SettingsProvider.smali`, and
  `SettingsProviderPatch` exists only to throw if anything ever calls it. The failure it prevents
  is worth remembering: re-zipping strips the APK's Signing Scheme v2 block, `Package Manager`
  logs "No APK Signature Scheme v2 signature" and drops the package, its `call`/`query` providers
  never publish, and `system_server` blocks in `DeviceConfig.getLong` under the AMS lock until
  the watchdog kills the boot. `verify_module.py`'s inventory fails if a
  `system/priv-app/SettingsProvider/SettingsProvider.apk` entry ever appears.
- **The spoofed property set has exactly one home.** `KsuModuleBuilder.SPOOF_PROPS` is emitted
  into `props.sh` and nothing else in the zip carries it. `service.sh` sources that script and
  runs `kaorios_props_reset` at every late start and again at the end of every flash; `action.sh`
  dispatches `reset` (the default, i.e. what a manager pressing the button runs) or `clear`
  before purging the GMS `pif.prop` / `pif.json` and `/data/system/package_cache` files, because
  a cached verdict describes the props as they were. Two copies of the list would drift silently
  and leave a key the module set but no longer knows to delete overridden until reboot, so both
  `KsuModuleBuilderTest` and the inventory section fail on a leak. The values go through
  `resetprop` rather than `system.prop`: `init` publishes `ro.*` before any module runs, so
  `system.prop` cannot replace a value that is already set, and `-d` hands a key back to the ROM.
- **There is no manager app to press the action button, and the app no longer presses it either.**
  The Reset/Clear buttons and their whole caller chain (`PatchViewModel.runModuleAction`,
  `PropsActionState`, `SukiSUClient.runModuleAction`, the `ModuleAction` enum) were removed;
  `action.sh` still ships both dispatch branches (`reset` default, `clear`) because it is the
  module's action entry point for a future manager — `KsuModuleBuilderTest` and the inventory
  pin them, so neither branch can be dropped silently.
- **A boot that never reaches `boot_completed` disables itself on the next boot.** Every boot
  `post-fs-data.sh` arms `$MODDIR/boot_pending`; `service.sh` clears it only after
  `sys.boot_completed=1` — and not at all when `SKIP_BOOT_WAIT` is set, which is how
  `customize.sh` sources the script at flash time without waiting (and how `action.sh` re-applies
  the props through the same path). A marker that survives into the next boot means the previous
  boot never finished: the script appends to `$MODDIR/boot_restore.log`, `rm`s the marker,
  `touch`es `disable` (KernelSU skips a disabled module's scripts from then on) and
  `boot_restored`, copies `$MODDIR/stock_backup/.` back over `/system` when the run saved one
  (`MountMode.DIRECT_OVERLAY` always does in `installCopies`; magic mount saves none because it
  overlays `$MODPATH/system` without touching `/system`), `sync`s and `reboot`s. Re-flashing
  replaces `$MODPATH` wholesale, so stale markers cannot survive a reinstall either.
  `KsuModuleBuilderTest` pins the arm/disarm timing, the direct-overlay backup and the
  magic-mount case, and the offline gate's inventory requires `boot_pending` in both scripts.
  One known hole: `MountMode.HYBRIDMOUNT`'s VFS rules may live outside
  `$MODPATH`, so `disable` might not unregister them — unverified, `hybridmount` CLI semantics
  unqueried.
- **The `data/adb/kaorios/` config bundle is still dropped.** It carries an identity (Keybox,
  PIF/device props) rather than a boot state, and shipping it would be installing a different
  module's configuration on top of ours.
- **A run that produced no KaoriOS runtime is a failure, not a partial success.** The module looks
  identical either way and the difference only shows up as a boot loop, so `patchAndBuild` bails
  rather than writing a zip whose injected call sites cannot resolve.
- **Both entry points fail closed on a partial patch.** `PatchCli` and `PatchViewModel` refuse to
  write a module when *any* target is left byte-identical — a half-applied patch set silently drops
  hooks the runtime expects — and refuse one where a supplied runtime never reached
  `framework.jar`. `PatchMode.BUILD_SPOOF` is the only exemption: it rewrites `Build` /
  `Build$VERSION` and injects no call site, so it legitimately runs without a runtime dex.

## UI

Follow [`UI_GUIDELINES.md`](UI_GUIDELINES.md). Non-negotiables: Miuix only (no Material
components), one `Scaffold` in `AppShell`, stateless pages, all text in `strings.xml`.

**`LiquidNavigationBar`'s metrics are measured, not chosen.** The bar is standardised on the
SukiSU manager's bar: `BarHeight` 64dp, `TabSlotWidth` 77dp, `BubbleHeight` 50dp,
`BarBottomClearance` 8.5dp (pill bottom sits ~86px above the screen edge and ~48px above the
gesture handle; bubble 75×50dp), all within 1–3px of the reference at 480dpi. Do not shrink
them, and do not strip the glass — `chromaticAberration`, the springs and
`BubblePressScale = 1.50` stay: `dumpsys gfxinfo` puts the jank in the pager's page redraw
(p90 ≈ 30 ms, janky < 1.5 %) with or without the bar, so the glass identity costs nothing
measurable.

**Back binds per window through the view tree — never through one composition-wide owner.**
`WindowDialog` and `WindowListPopup` compose through `androidx.compose.ui.window.Dialog` in a
subcomposition that *inherits* our CompositionLocals, and Miuix's `NavigationBackHandler`
resolves `LocalNavigationEventDispatcherOwner` (composition local, else
`findViewTreeNavigationEventDispatcherOwner()` on the current window's view). A single
`CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides …)` — the fix for
ComponentActivity ≤1.9.3, which published no owner and threw
`IllegalStateException: No NavigationEventDispatcher was provided…` the moment a dialog
opened — silently broke that per-window resolution: with `enableOnBackInvokedCallback`, the
focused window is the dialog (`dumpsys window` `mCurrentFocus`, `CoreBackPreview
currentFocus`), the system back lands on the dialog's own `ComponentDialog` dispatcher
(`DialogWrapper extends ComponentDialog`), while the propagated local had bound Miuix's
handler to the activity-side dispatcher — zero listeners on the dialog side, and no fallback
run in callback-enabled mode, so the completed back (`on_back_invoked` in logcat) did
nothing at all: the Theme flyout neither dismissed nor let the back through. `MainActivity`
therefore provides **no owner**; each window resolves its own: the shell's
`BackHandler`/`PredictiveBackHandler` and `PopupEntry` handlers bind `ComponentActivity` via
the activity decor (`initializeViewTreeOwners`), dialog-side Miuix handlers bind
`ComponentDialog` via the dialog decor. `activity-compose` is pinned to `1.13.0` for exactly
that per-window publishing, and `navigationevent-compose:1.0.2` stays named explicitly
(Miuix declares it `implementation`, so its types are runtime-only otherwise). The manifest
enables `android:enableOnBackInvokedCallback` so back arrives with gesture progress;
completion-only dispatches still work without it. `DialogWrapper` adds its own
dismiss callback through `OnBackPressedDispatcher` gated on `dismissOnBackPress`, which
`platformDialogProperties()` sets to `false` — dismissal is Miuix's handler's job.

**Settings is one `Animatable` push gated on a boolean.** `settingsOpen` is the only
composition gate; the per-frame `settingsProgress` is read inside `graphicsLayer` blocks
(overlay `translationX`/`alpha`, bar `translationY`) so a push never recomposes the shell.
Every entry point — gear, back icon, predictive gesture, legacy `BackHandler`, bar tab
select — goes through the idempotent `openSettings`/`closeSettings`. `PredictiveBackHandler`
is composed after the plain handler (last composed among the enabled ones wins) and, on a
cancelled gesture, restores progress from the shell's scope because the handler's own
coroutine is already cancelled by then.

## Known gaps

Measured against a real HyperOS 4 / Android 17 (SDK 37) ROM on a rooted test device: **every `ALL_IN_ONE` target patches on device**, and `FULL` patches **29 of
29** across `framework.jar` / `services.jar` / `miui-services.jar` on the desktop round trip
(`local-work/run20`) with `failed=0` — including the new `services.jar/classes3.dex` →
`AppsFilterBase.smali`. All three archives rebuild, the KaoriOS runtime is merged
(3/3 required classes), `verify_module.py` reports 6/6 PASS / `SAFE TO FLASH`, and the zip is
emitted with `miui-services.jar` at `system/system_ext/framework/`. Oracle parity against the
Python reference is still pinned by `OracleParityTest`.

**The `FULL` module has never been flashed.** `local-work/run20/module/kaorios_patcher.zip` is
the current SAFE-TO-FLASH artifact (run19 was the previous one).

### Hide App List — root cause and the `AppsFilterBase` target

The upstream feature was dead on stock: `d5.a` (the only writer of
`Settings.Global.kaorios_hide_app_work`) sits behind `KaoriosHook.shouldHideAppList`, which on the
stock ROM has **no call site** — `AppsFilterBase.shouldFilterApplication` never consults it, so no
app query ever stamps the flag. Toolbox's own status line only reads the raw flag via `th3.f`
(unfiltered), hence "Framework hook not detected — feature disabled" plus an `enabled="false"`
"Add app" container: a catch-22 that only the seed flag breaks (see below).

- The chain on a patched ROM is `ComputerEngine.shouldFilterApplication` (7-param, our ForCaller
  insert) → `mAppsFilter.shouldFilterApplication(snapshot…)` → `AppsFilterBase.
  shouldFilterApplication(Lcom/android/server/pm/snapshot/PackageDataSnapshot;ILjava/lang/Object;
  Lcom/android/server/pm/pkg/PackageStateInternal;I)Z`. `Binder.getCallingUid()` inside `d5.a` is
  therefore valid at the AppsFilterBase frame (same binder call).
- `AppsFilterBasePatch.kt` (PatchEngine target `"AppsFilterBase.smali"`) ports the template
  `Template_V2060\service\AppsFilterBase.smali` insert: null-guard `p4`, `getPackageName`,
  `shouldHideAppList(cr, pkg)`, return 1 on hide, `.catch Ljava/lang/Throwable` falling through
  to the stock label. Absent method → `NOT_TARGET` (keeps AOSP runs safe), hook-present →
  `ALREADY_PATCHED`, reserved-label collision / shape mismatch → fail-closed
  (`PatchVerificationException` from `verify()`).
- **Oracle parity is untouched by design**: the 6 ComputerEngine oracle cases pin
  `ComputerEnginePatch` byte-for-byte (ForCaller only) and must not change; no oracle case covers
  AppsFilterBase (the Python reference has no such patcher), so the new class cannot break
  `OracleParityTest` (its count assertion is relative to `HOOK_TARGETS.size`).
- **Bootstrap on a stock-signature run**: `su -c 'settings put global kaorios_hide_app_work 1'`
  (plain shell put is denied `WRITE_SECURE_SETTINGS`) makes the app flip to detected state —
  "Add app" becomes `enabled="true" clickable="true"` — after re-entering the Hidden features
  screen. The seed is safe: `m5.e` short-circuits to false while `kaorios_advanced_features`
  is absent, and `d5.a`'s once-flag check sits *after* the uid + `hideapp` config gates, so the
  seed never mass-hides anything; its only effect is unlocking the UI. Adding an app through the
  now-enabled picker writes `hideapp:true` into `kaorios_hide_devlist` (mode-1 path
  `y31` → `e41(pkg, 0, 1)` → `ex.f` JSON), after which opening that app can re-trigger the stamp.
- 10 new engine tests live in `AppsFilterBasePatchTest` (shape, idempotency, NOT_TARGET
  byte-identity, label collision, verify rejections, `.locals`/`.registers` variants); suite is
  now 110 tests, all green.

Verified on device (previous, 7-dex build): the output `framework.jar` defined `KaoriosHook` (334
classes); `classes2/4/5/6.dex` were SHA-256 identical to stock; the emitted guard digest for
`framework.jar` matched the device's `/system` copy. That build appended `classes7.dex` and is
what caused the boot loop — the dex-count-preserving merge replaces it and is not yet flashed.

- On-device round trip is ~5 min, dominated by `DexPool` rewriting whole dexes. Untouched dexes
  are copied through, so cost scales with how many a patch actually touches.
- **`SettingsProvider` is retired, not broken.** Upstream `8c752fd` dropped provider patching
  wholesale ("Remove SettingsProvider patching"), and the port followed: no target, no
  disassembly, no APK in the zip, and `SettingsProviderPatch` only throws if anything calls it.
  Before that this repo shipped the APK behind a signature-verification gate, re-signed with a
  throwaway key — it was never flashed, and upstream's own reason for dropping it (a rebuilt APK
  cannot carry the ROM's platform certificate, and `patch-settingsprovider-a17-artifact.sh`
  refuses without `platform.pk8`, which cannot be recovered from a device) is why the guide now
  says keep the stock one. If an older module's zip ever carries the APK again, logcat shows
  `No APK Signature Scheme v2 signature` and then `system_server` blocked in
  `DeviceConfig.getLong` — those two lines are the failure this retirement prevents.
- Modules are built with **magic mount** by default (KernelSU overlays `$MODPATH/system` at
  boot, no installer copy). `MountMode.HYBRIDMOUNT` and `DIRECT_OVERLAY` remain available.
- **CorePatch §3's overlay path is the one part of the module that has never been exercised.**
  `system/system_ext/framework/miui-services.jar` depends on magic mount following this ROM's
  `/system/system_ext -> /system_ext` symlink, and that is only observable after a flash. The jar
  itself is sound: `classes.dex` is the only entry that changes, every id table keeps its exact
  size and offset, `dexdump` reads it, and it comes out ~46 KB smaller purely because `DexPool`
  re-dedups `debug_info_item`s (43,115 → 33,638 for ~4.9 bytes each) — do not read that shrink as
  data loss when you next diff stock against output.
- **Flashing is unverified.** The round trip produces the zip and the boot-time overlay is
  untested — in particular whether a patched `framework.jar` survives ART boot on A17. The
  compatibility guard reduces the risk of flashing to the wrong ROM; it does not prove the patched
  framework boots. Watch logcat for `VerifyError` on the first boot.
- **There is no manager app to launch.** The test device runs KernelSU (`su -v` reports the flavour) but `pm list packages` holds no manager — nothing matching
  `me.weishu.kernelsu`, `com.sukisu.ultra`, `com.rifsxd.*` or `magisk`. So the Module tab's
  **Install module** button calls `SukiSUClient.installModule`, i.e.
  `su -c "/data/adb/ksu/bin/ksud module install <zip>"` (`magisk --install-module` for the Magisk
  flavour), never an explicit intent. It is a separate, explicit action — nothing installs on its
  own. The **Reset** and **Clear** buttons that used to press `action.sh` from the Module tab were
  removed (see the Engine invariants bullet): with no manager app nothing invokes `action.sh`
  today, but the script keeps both dispatch branches as the module's manager-button contract.
- **The property layer has never run either.** `resetprop` is present on the device
  (`/data/adb/ksu/bin/resetprop`, confirmed — `ksud module` ships it), and both operations are
  pinned by `KsuModuleBuilderTest` and the inventory section, but no script in this module has
  been executed on it yet. Expect the first `action.sh` run to prove whether `PATH` resolution
  finds the tool or the known-location fallback does; the message `resetprop not found; ...`
  is the failure to watch for.
- **The privapp whitelist needs no addition.** Every privileged permission among the Toolbox's 59
  `uses-permission` entries is already covered: the ones absent from the whitelist
  (`ACCESS_COARSE_LOCATION`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, the Shizuku API permission,
  and the app's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) are normal/dangerous or
  app-defined, and `INTERACT_ACROSS_USERS_FULL` is not in the manifest at all. The reference XML
  is shipped byte-identical — do not "fix" it.
- The reference `verify-framework-a17-hooks.py` **does not pass against our runtime dex and that
  is a revision mismatch, not a defect.** It requires `AdvancedPolicyService.smali` and
  `AdvancedPolicySnapshot.smali`; our build ships R8-renamed `settings/a.smali` (an
  `IAdvancedPolicyService$Stub`) plus `AdvancedPolicyRuntimeStatus`, and a precise scan of the
  whole dex finds **zero** references to either required name. The Toolbox repo publishes no
  `.dex`, so its `REQUIRED` list targets a differently-obfuscated build than the released
  `kaorios.dex`. `verify-services-a17-hooks.py` and `verify-systemserver-a17-hooks.py` both pass.
- `SystemHelper.apk` and `messaging.apk` are **0-byte files on the stock ROM itself**, so the
  "Invalid file" `PackageManager` noise they cause is pre-existing and not something a patched
  build introduces. Do not chase it in logcat.
- This is a patcher and module builder, not a port of the KaoriOS Toolbox UI. Toolbox features
  exist at runtime only because the released runtime dex is injected into the boot classpath; they
  are not reproducible here without the private framework source.

## On-device notes

- **Kotlin/Java `Regex` runs on Android's ICU `Pattern`, not the JDK's.** An unescaped `}` anywhere
  in a pattern throws `PatternSyntaxException: Syntax error … near index N` from
  `com.android.icu.util.regex.PatternNative` at *class-init time*, which surfaces as
  `ExceptionInInitializerError` in `PatchEngine.<clinit>` and kills the run before any target is
  touched. `AndroidRegexSyntaxTest` scans every `Regex("…")` literal in `engine/src/jvmMain` and
  `app/src/androidMain` and fails on any unescaped brace outside a character class — run it after
  touching smali patterns, and remember ICU only accepts `{n,m}`-style intervals (or `\{`/`\}`)
  — a bare closing brace in a character-class-free pattern is an error.
- **`adb shell input tap` alone fails** with `SecurityException: INJECT_EVENTS permission`; always
  use `su -c 'input tap X Y'`. Taps are silently dropped while the display is dozing, so wake with
  `su -c 'input keyevent 224'`, dismiss with `su -c 'wm dismiss-keyguard'`, and keep the screen
  awake via `su -c 'settings put system screen_off_timeout 600000'`.
  `uiautomator dump` bounds are screen coordinates and match screenshot pixels 1:1; Compose
  switches expose no semantics, so switch state must be read from screenshots only.
- Patch logs persist after every run at
  `/data/data/dev.kaorios.patcher/files/kaorios_workspace/logs/patch.log` and are copied to
  `/sdcard/Download/kaorios_patcher.log` on completion (internal file carries one extra trailing
  `I log: exported to …` line). Both survive process death and are pullable with `adb pull`.

Device-specific state (flashed modules, stock-vs-mounted digests, build fingerprint,
session test flags) is kept locally in the gitignored `local-work/device-state.md` — do not commit it.

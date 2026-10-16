# Changelog

Concise, versioned history. Release workflows embed it in the GitHub release notes with
headings demoted one level: a beta's notes carry only the newest section, so prerelease
notes stay short, while a stable's notes carry every section since the previous stable
release. Write a release's section at ship time, in the same step that bumps
`gradle.properties` — the beta workflow refuses to publish when the newest section does not
belong to the version line being released — and leave released sections alone.

## [v1.2.0-beta.14] - 2026-10-16

- **Fix — TrickyStore keystore chains patch again (A17 report):** TrickyStore's
  `hackCertificateChainIfNeeded` sits between the leaf `aput-object` and the `move-result`
  of the provider invoke, so neither the strict pairing nor the previous lenient scan could
  see the return's producer and the run died with `UNSUPPORTED_LAYOUT`. The backward scan
  now follows the `move-result` back to the provider invoke's input register and pairs the
  `aput` that fed it, and the verifier mirrors the same rule — a pairing span never crosses
  another `aput-object` or `return-object`, so the loop's earlier `aput` cannot re-count the
  same hook (which would have failed `pairs == hookCount` on exactly the shape this fixes).
  The TrickyStore provider hook itself is never touched.
- **Fix — zero-local generator frames no longer clobber `p0`:** growing `.registers 1` by
  one parked the hook result in `v1`, which *is* `p0` after the growth — the very register
  the hook is invoked with. The scratch is now `v(newCount-2)` (`v0`), and the verifier
  requires it to stay the top local (`registers - 2`).
- **Port — upstream hardening `2bdca9a` → `f8d87de`:** canonicalise refuses `/range`
  operands that cross the local/parameter boundary instead of silently renaming across it;
  the register-encoding gate learns `rsub-int` and the five `/lit16` two-address forms and
  strips branch labels (a `:v255` label is not an operand); the generator verifier anchors
  the hook at the method's real entry (skipping `.param`/annotation/`.line` metadata —
  a directive-shaped line or a label at entry fails closed) and re-runs the encoding gate;
  Build spoof's second substitution now also rewrites a non-final initialized stock field,
  and its verifier requires the null initializer; every patch target checks the file's
  `.class` descriptor against the expected owner before patching, so a renamed or foreign
  file fails closed instead of matching on content alone.
- **Change — the patch run logs its own identity:** the first `KaoriosPatcher` line after
  `patch run` is now `patcher: <version> [label] code=<n> guide=<revision>`, so a tester
  log always names the build that produced it.

## [v1.2.0-beta.13] - 2026-10-14

- **Fix — release sync can no longer settle on a stale dex (A16 report):** the version key
  ignored the fourth tag segment, so v2.0.6.1 and v2.0.6.0 *tied* and list order decided —
  which is how a rebuilt v2.0.6.0 kept winning. All four segments now count, and when
  `api.github.com` is unreachable (60 unauthenticated requests an hour per IP, blocked
  outright in some regions) the sync falls back to the public `releases.atom` feed for the
  tag and downloads from `github.com/releases/download` — never downgrading the workspace's
  recorded tag — instead of silently shipping the offline cache forever. `/releases/latest`
  is not used as a fallback: it 302s to v2.0.6.0.
- **Fix — Android 16 keystore chain: wider scan and a self-explaining failure.** The
  lenient aput→return scan window grows 30 → 100 lines (baksmali debug directives run ~3
  lines per instruction, so a loop-exit tail could hide the aput past the old bound), with
  the verify-side pairing widened to match. A chain that still cannot pair now reports its
  full neighbourhood — `aput=… ret=…` counts plus `ret#n@<line><- …` / `aput#n@<line>> …`
  context — so the next tester log shows the exact instruction the scan could not follow
  instead of costing another blind round trip. The HOS V816 report's `WindowManagerService`
  absence was benign (guide-optional); only the keystore miss failed the run.

## [v1.2.0-beta.12] - 2026-10-14

- **Fix — devices already patched by another tool no longer fail closed (A15 report):** the
  App Hide verifiers accept a foreign hook shape — the upstream Toolbox template's
  stock-label insert or a manual guide insert — when the structural invariants hold
  (insert at the method head, null-guard, catch-all, hook result gated into `return 1`,
  fall-through label defined once), so a re-run reports `ALREADY_PATCHED` instead of
  `FAILED` / `UNSUPPORTED_LAYOUT` killing the whole module. Our own insert (reserved labels
  present) is still checked byte-for-byte, and broken shapes are still rejected — every
  failure now quotes the offending insert's lines, which is the only channel a remote
  tester has to show a shape we have never seen.
- **Fix — Android 16 keystore chain finds what exact regexes miss:** when the strict
  aput→return pattern finds nothing, a line scan anchors on each `return-object` and walks
  back to its `aput-object` (following `move-object` hops, stopping at control-flow
  boundaries); an unmatched ROM now reports `aput=… ret=…` shape diagnostics instead of a
  bare "leaf-array return not found".
- **Fix — Build spoof strips stock field initializers (upstream `2bdca9a`):** an
  initialized stock field (`BRAND:Ljava/lang/String; = "google"`) previously produced
  `= null = "google"`, which no assembler accepts — the differential oracle had that
  malformed output pinned as expected until now. AOSP-like ROMs patch cleanly.
- **Fix — a pre-existing visibility hook below an unconditional `return`/`throw` is
  rejected as dead code (upstream `2bdca9a`).**
- **Change — upstream reference moved `c645e64` → `2bdca9a`:** guide conformance re-read
  (target inventory unchanged), the differential oracle gains an A16 keystore case and the
  generator stops failing on the fixture added in beta.11.

## [v1.2.0-beta.11] - 2026-10-13

- **Fix — Android 16 / AOSP ROMs no longer fail closed with three `UNSUPPORTED_LAYOUT`
  errors:** the keystore-generator hook reuses the highest local instead of growing the
  frame (the reference's growth pushed `p0` past v15 and tripped the register-encoding
  gate); the certificate-chain hook accepts AOSP 16's `:cond_…` label and `move-object`
  hop between the final `aput-object` and its `return-object`; and the installer-source
  filter now finds the installing argument by dataflow provenance instead of a fixed
  index, so AOSP 16's reordered `InstallSourceInfo` constructor is recognised. An
  unsupported installer layout degrades to visibility-only — the App Hide hook still
  ships — instead of rejecting the whole file.
- **Fix — a stale cached Toolbox APK can no longer survive a release sync:** when the
  selected release publishes no APK asset, the cached copy is deleted unconditionally
  (pre-beta.10 workspaces have no tag file, so provenance was unknown).
- **Change — ROM detection shows the build's display name:** the device row now reads
  `ROM: AOSP Lunaris-3.12` (or `HOS OS4.0 (…)`) from `ro.build.display.id`, so an AOSP
  ROM that retains MIUI props is no longer mislabelled as HyperOS.

## [v1.2.0-beta.10] - 2026-10-12

- **Fix — release sync picks the newest release by version, not by date:** `releases/latest`
  on the fork returns v2.0.6.0 (its rebuild was *created* after v2.0.6.1's release), so the
  module kept shipping the stale 573,604 B dex and the old APK — which is why hide-apps and
  the brand spoof never took effect (both run in the runtime dex). The sync now lists all
  releases and picks the highest tag that publishes a `classes.dex` (currently v2.0.6.1,
  573,444 B).
- **Fix — a stale workspace Toolbox APK can no longer shadow the bundled one:** the manager
  APK is taken only from the same release as the dex; when that release has no APK asset
  (v2.0.6.1 does not) an APK cached under a different tag is deleted, so the bundled signed
  v2.0.6.1 (versionCode 2061) is what the module ships.
- **Fix — the update banner's Install button actually installs and reports failures:**
  downloads follow redirects explicitly with a User-Agent, the APK is staged through
  `/data/local/tmp` before `pm install -r` (an app-private path is not reliably readable),
  and install errors are shown in the banner instead of being logged only.
- **Change — Indian app-props entries carry package keys only:** the 14 packages added in
  beta.9 no longer hardcode a Pixel 6 identity; the runtime applies the working PIF value
  to listed packages, so overriding it with stale values was wrong.
- **Fix — the module now reports the app version:** `module.prop` is stamped with the real
  version name instead of the hardcoded `1.0.0`.
- **Chore — GuideCheck re-read at fork head `c645e64`:** no new patch targets; the upstream
  register-encoding hardening (boundary-safe alias rewriting, `UNSUPPORTED_LAYOUT`) was
  already at parity.

## [v1.2.0-beta.9] - 2026-10-12

- **Change — VFS mode wording and device recommendation:** the mount toggle now reads
  "Serve through VFS mode" instead of naming Hybrid Mount alone, and the patcher probes
  the device for a VFS provider (Hybrid Mount's binary or the NoMount metamodule) when it
  reads the device spec. With a provider present the section recommends enabling VFS mode;
  enabled with none, it points at installing one. The module's flash-time check and
  boot-time rule registration recognise NoMount as well — as a metamodule it converts the
  mount itself, so that branch logs instead of registering rules.
- **Feature — Indian-market apps in the built-in spooflist:** 14 packages (BHIM, PhonePe,
  Google Pay, Paytm, Amazon, Flipkart, HDFC / ICICI / Axis / YONO SBI banking, CRED,
  MobiKwik, Freecharge, Airtel) added to `app-props.json` (65 → 79 entries). They are
  spoofed to the same Pixel 6 identity `Pif-props.json` gives GMS, so the per-app Build
  props and the attestation verdict agree — BHIM and friends compare both.
- **Change — bundled Toolbox APK v2.0.6.0 → v2.0.6.1:** the offline-fallback
  `KaoriosToolbox.apk` resource is now the signed v2.0.6.1 build (versionCode 2061), so
  the framework reports the current version and the flash-time fallback matches what
  release sync downloads. Signature v2 block intact.

## [v1.2.0-beta.8] - 2026-10-11

- **Feature — in-app update checker:** the app now queries the GitHub releases API when it
  enters the foreground (throttled to once per hour) and shows an update banner when a
  newer release is available. The banner states whether a re-patch is needed (cosmetic vs
  patch-related changes), shows a Beta/Stable channel badge, and adds a caution when the
  offer is a cross-channel switch. Install downloads the APK and runs `pm install -r`
  through the root shell; Later dismisses the banner.
- **Feature — cross-channel update offers:** a beta user is offered a newer stable when
  one supersedes their build; a stable user who switches the channel to beta is offered
  the newest beta only when it supersedes, with a bleeding-edge caution message. The
  Settings channel dropdown triggers a force-check on switch.
- **Fix — VFS / Hybrid Mount integration:** `kernelRelease()` now reads `uname -r`
  instead of `ro.kernel.version` (which reports `6.1` without the `android` infix, making
  the GKI warning fire spuriously on every boot). `customize.sh` checks Hybrid Mount
  availability at flash time and warns when the module is absent; `service.sh` re-registers
  VFS rules on every boot (the kernel module does not persist them) by loading the correct
  `.ko` for the device's Android/kernel and adding a `vfs rule add` per overlay path.
  `verify_module.py`'s mount marker updated to match.
- **Tests — suite 133 → 134:** `hybridmount checks availability at flash and registers
  rules every boot` pins the customize.sh availability check and the service.sh rule
  registration.

## [v1.2.0-beta.7] - 2026-10-10

- **Fix — attestation / Play Integrity now work out of the box:** the module ships the
  attestation identity bundle (`data/adb/kaorios/`: Keybox.xml, Pif-props.json,
  device-model.json, app-props.json) that the runtime's software-keypair hook needs to
  sign the attestation chain. Without it, hardware attestation failed and Play Store
  reported the device as uncertified. `customize.sh` deploys the bundle at flash time;
  `uninstall.sh` removes it on uninstall.
- **Feature — `action.sh pif` mode:** redeploys the identity bundle and purges the GMS
  Play Integrity verdict cache, so a Toolbox update to Pif-props.json takes effect
  immediately without a reboot.
- **Tests — suite 132 → 133:** `the attestation identity bundle is shipped and deployed`
  pins the four config entries, the Keybox XML structure, and the customize.sh deploy step.

## [v1.2.0-beta.6] - 2026-10-10

- **Fix — A17 Global reassembly crash at the dex method-id ceiling:** on the A17 Global
  HOS4 ROM, `framework.jar/classes3.dex` ships with `method_ids_size = 65535` — the dex
  16-bit invoke-index limit. Substituting the keystore classes (which inject new
  `KaoriosHook` references) pushed the table past 65536 and reassembly died with
  `Unsigned short value out of range: 65537` while writing `BaseBundle.deepCopyValue`.
  The rebuild loop now catches `MethodIdOverflow`, strips the patched classes out of the
  full dex, and relocates them to a dex in the same jar with headroom — the classloader
  loads every dex, so the move is transparent at boot. End-to-end against the reported
  device's `framework.jar`: patched 19/19, all dexes under the ceiling, module emitted.
- **Feature — version badge and update channel:** the title bar now shows a short version
  chip (`beta.6` on beta builds, `stable.N` on stable) next to the settings gear. Settings
  gains an Updates section with a channel switch (Beta / Stable) so testers can opt into
  either track without reinstalling.
- **Tests — suite 127 → 132:** `MethodIdOverflowRelocationTest` pins the overflow
  detection and the distinct exception type the rebuild loop catches.

## [v1.2.0-beta.5] - 2026-10-10

- **Fix — register-growth reassembly crash (reported on Android 16):** growing a patcher's
  register directive shifted the physical parameter slots while stock narrow instructions
  (`iget`/`iput`, non-`/range` `invoke`, `const/4`, `/2addr`, `if-*`) still addressed the
  old positions, so reassembly died with
  `framework.jar!classes3.dex: smali could not assemble 3 file(s)`. The ports now mirror
  upstream exactly: the growth threshold accounts for *every* stock parameter
  (`new_locals + param_width - 1 > 15`), the high path shadows stock operands into their
  old homes, canonicalisation skips strings/comments and descriptor/field boundaries like
  `Lorg/v15;` / `->v15:I`, and `PatchEngine` runs `Smali.verifyRegisterEncoding` over every
  method of a file it changed — an unencodable result is refused `UNSUPPORTED_LAYOUT` with
  the input byte-identical instead of crashing at reassembly (upstream's register refusal
  from `aab122b`). Desktop round trip over the reported AOSP Android 15 jars patches 25/25
  and emits the module with the gate in place.
- **Fix — wide parameters counted short:** `MethodRewrite.paramCount` now counts `J`/`D`
  as two dex slots (`[J`/`[D` one), so the `p0` of e.g. `getInstalledPackagesBody(JII)`
  resolves to `registers - ins_size`; the slot-unaware count placed it one register high
  and made the new gate refuse stock methods that assemble fine — surfaced by the
  `real_a17_ComputerEngine` oracle case.
- **Tests — suite 116 → 127:** `PatchRegisterEncodingTest` ports upstream
  `script/test_patcher_register_encoding.py` end to end — refusal with byte-identical input
  for narrow parameter operands, range/wide passthrough, literal/comment/descriptor
  preservation, the `ApplicationPackageManager` refusal through `applyTargetPatch`,
  `ComputerEngine` high-path descriptor preservation plus idempotency, and low-path alias
  canonicalisation under both directive kinds.
- **Oracle:** the three `aosp15_*` fixtures leave the differential oracle
  (`SKIP_FIXTURES` — the Python reference is narrower than the engine on A15 and ships no
  CorePatch §2 patcher); they stay pinned by `AospLayoutsTest` and the round trip above.
- **Release notes:** a beta release now embeds only its newest changelog entry; a stable
  release embeds everything since the previous stable.

## [v1.2.0-beta.4] - 2026-10-09

- **Fix — beta.3's on-device failure (AOSP Android 15):** `ReconcilePackageUtils.<clinit>`
  on AOSP carries no `Flags.restrictNonpreloadsSystemShareduids` guard, so the guide's
  `const/4 …, 0x0` anchor never matched and CorePatch §2 refused the ROM. The patcher now
  recognises the unguarded shape and forces the value feeding
  `ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS` (literal flipped in place, `sget-boolean`
  replaced); every other shape still fails closed with the file byte-identical. Desktop
  repro with all three optional toggles on: `patched=26 ok=27 failed=0`, module built.
- **CLI:** `PatchCli --selection=hooks,build,corePatch,flagSecure,hideDev` lets a desktop
  run mirror the app's exact toggles instead of a coarse mode.
- **UI:** the main-screen spinner is now a determinate progress bar with per-phase `(n/m)`
  counts (pull / disassemble / patch / reassemble; release-asset sync stays indeterminate),
  and `patch.log` records step *transitions* only instead of one line per target.
- **Tests — suite 110 → 116:** four new `AospLayoutsTest` cases over a verbatim A15
  `ReconcilePackageUtils` fixture (patch + verify + idempotency + no-escape from `<clinit>`,
  plus three fail-closed negatives: stock must not verify, an uncovered literal feed, a
  register the constructor still reads). Two new `PatchSelectionTest` cases pin App Hide
  (`ComputerEngine` + `AppsFilterBase`) and ADB Hide (`Settings$NameValueCache`) as selected
  and disassembled on **every** Android 13-17, and absent from every selection without the
  ADB-hide switch.
- **Docs:** AGENTS records the audit conclusion — App Hide and ADB Hide are never version-
  or family-gated; a shape the ROM does not carry degrades to `NOT_TARGET` inside the
  patcher, never to a silently dropped selection.

## [v1.2.0-beta.3] - 2026-10-08

- First beta of the 1.2.0 line (same tree as v1.1.0), cut for on-device soak.
- Exercised with all three optional toggles on (CorePatch, Disable Secure Flag, hide dev
  state) — the session that surfaced the AOSP A15 CorePatch anchor miss fixed in beta.4.

## [v1.1.0] - 2026-10-08

- First release: on-device smali patcher that pulls system jars through SukiSU, patches
  them with the differential-tested `:engine`, and emits a flashable KernelSU module —
  hooks for Android 13-17, A17 Build spoof, Disable Secure Flag, CorePatch §1-§3, App Hide,
  ADB Hide; magic-mount default behind a digest-guarded installer.

# Changelog

Concise, versioned history. Release workflows embed it in the GitHub release notes with
headings demoted one level: a beta's notes carry only the newest section, so prerelease
notes stay short, while a stable's notes carry every section since the previous stable
release. Write a release's section at ship time, in the same step that bumps
`gradle.properties` — the beta workflow refuses to publish when the newest section does not
belong to the version line being released — and leave released sections alone.

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

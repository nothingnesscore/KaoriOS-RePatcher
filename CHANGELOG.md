# Changelog

Concise, versioned history. Both release workflows embed this file verbatim in the GitHub
release notes (headings demoted one level), so every release's notes carry the compiled
record — including test-suite changes — of everything since the previous one. Write a
release's section at ship time, in the same step that bumps `gradle.properties`, and leave
released sections alone.

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

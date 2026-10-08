#!/usr/bin/env python3
"""Offline pre-flash gate for a patched framework/services/miui-services triple.

Nothing here touches a device. It answers the only question that matters before
flashing a module that overwrites /system/framework at boot: *did the patch
introduce anything ART cannot resolve, and is every dex still self-consistent?*

  1. every dex keeps a valid header signature (sha1), checksum (adler32),
     file_size and header_size -- ART rejects a stale one at load time
  2. the id tables referenced by u2 fields stay under 65536, and no class is
     defined twice inside a jar (both are hard verify failures)
  3. class counts only grow where the KaoriOS runtime was folded in
  4. every type/method/field the patched dexes reference resolves against
     stock framework + services + miui-services + the runtime dex -- and the
     same check is run on the stock jars, so only references the *patch*
     introduced can fail
   5. the module zip that would actually be flashed carries the KernelSU
      scaffolding, the ROM digest guard ahead of every mount action, and a
      Toolbox APK byte-identical to the signed release artifact (so its APK
      Signing Scheme v2 block is intact); its jars are byte-identical to the
      ones sections 1-4 just checked, and it never carries a
      SettingsProvider.apk (upstream 8c752fd retired provider patching -- the
      guide says keep the stock APK)

miui-services.jar is only in a run when CorePatch §3 patched it -- a ROM that
carries neither §3 method reports NOT_TARGET and the jar is copied through
untouched, so it never reaches the zip. Section 5 therefore treats it as
conditional in both directions: required when it was verified, forbidden when
it was not. SettingsProvider.apk gets only the forbidden half: a rebuilt APK
loses its Signing Scheme v2 block in the re-zip, Package Manager drops it and
system_server deadlocks, so its presence in the zip is always an error no
matter what the run did.

Usage:
    python verify_module.py <patched_dir> [stock_dir] [runtime.dex] [module.zip]

    patched_dir  directory holding framework.jar, services.jar and (when §3
                 applied) miui-services.jar (e.g. run18/out)
    stock_dir    directory holding device_orig_fw.jar, device_orig_sv.jar and
                 device_orig_mis.jar
    runtime      path to kaorios.dex
    module       path to kaorios_patcher.zip; defaults to the sibling
                 <patched_dir>/../module/kaorios_patcher.zip when it exists

Exit status 0 means safe to flash.
"""
from __future__ import annotations

import hashlib
import os
import sys
import zipfile
import zlib

import verify_dex_refs as vr

U2_CAPPED = {"type_ids": 0x40, "proto_ids": 0x48,
             "field_ids": 0x50, "method_ids": 0x58}

JAR_NAME = {"device_orig_fw.jar": "framework.jar",
            "device_orig_sv.jar": "services.jar",
            "device_orig_mis.jar": "miui-services.jar"}

# Where each verified jar lives inside the module. Only framework/services are under
# system/framework: miui-services lives on the /system_ext symlink, so its overlay path
# has to be written from MODULE_PATHS in PatchCli, not from the file name.
MODULE_ENTRY = {"framework.jar": "system/framework/framework.jar",
                "services.jar": "system/framework/services.jar",
                "miui-services.jar": "system/system_ext/framework/miui-services.jar"}

# CorePatch §3's jar: conditional in both directions, see the module docstring.
CONDITIONAL_JAR = "miui-services.jar"


def dexes(path: str) -> list[tuple[str, bytes]]:
    if path.endswith(".dex"):
        return [(os.path.basename(path), open(path, "rb").read())]
    with zipfile.ZipFile(path) as zf:
        return [(n, zf.read(n)) for n in sorted(zf.namelist())
                if n.endswith(".dex")]


def u4(buf: bytes, off: int) -> int:
    return int.from_bytes(buf[off:off + 4], "little")


def norm(label: str) -> str:
    left = label.replace("/", "\\")
    jar_part, _, dex_name = left.rpartition("!")
    jar = JAR_NAME.get(jar_part.split("\\")[-1], jar_part.split("\\")[-1])
    return f"{jar}!{dex_name}"


def pair(directory: str) -> list[str]:
    """The jars this gate tracks, in framework/services/miui-services order.

    A patched output directory names them framework.jar; a device pull names them
    device_orig_fw.jar. The patched name is tried first so a directory holding both
    reads as patch output, and a jar the run never touched is simply not found.
    """
    names = (("framework.jar", "device_orig_fw.jar"),
             ("services.jar", "device_orig_sv.jar"),
             (CONDITIONAL_JAR, "device_orig_mis.jar"))
    found: list[str] = []
    for patched_name, stock_name in names:
        for name in (patched_name, stock_name):
            candidate = os.path.join(directory, name)
            if os.path.exists(candidate):
                found.append(candidate)
                break
    return found


def check_signatures(paths: list[str]) -> list[str]:
    bad: list[str] = []
    for path in paths:
        for name, blob in dexes(path):
            where = f"{os.path.basename(path)}!{name}"
            if u4(blob, 0x08) != zlib.adler32(blob[0x0C:]) & 0xFFFFFFFF:
                bad.append(f"{where}: adler32 checksum mismatch")
            if blob[0x0C:0x20] != hashlib.sha1(blob[0x20:]).digest():
                bad.append(f"{where}: sha1 signature mismatch")
            if u4(blob, 0x20) != len(blob):
                bad.append(f"{where}: file_size {u4(blob, 0x20)} != {len(blob)}")
            if u4(blob, 0x24) != 112:
                bad.append(f"{where}: header_size {u4(blob, 0x24)} != 112")
    return bad


def check_limits(paths: list[str]) -> tuple[list[str], dict[str, int]]:
    bad: list[str] = []
    defined: dict[str, int] = {}
    for path in paths:
        # Duplicate detection is per jar: framework and services are separate
        # class loaders and legitimately carry some of the same AOSP stubs.
        seen: dict[str, int] = {}
        for name, blob in dexes(path):
            where = f"{os.path.basename(path)}!{name}"
            for table, off in U2_CAPPED.items():
                size = u4(blob, off)
                if size >= 65536:
                    bad.append(f"{where}: {table}={size} exceeds the u2 limit")
            classes = vr.parse_dex(blob, where).defined_classes
            defined[where] = len(classes)
            for cls in classes:
                seen[cls] = seen.get(cls, 0) + 1
        for cls, count in sorted(seen.items()):
            if count > 1:
                bad.append(f"{os.path.basename(path)}: {cls} defined {count} times")
    return bad, defined


def check_stock_baseline(stock_paths: list[str], patched_paths: list[str],
                         runtime: str) -> list[str]:
    """Only references the patch introduced are allowed to fail."""
    providers: list[vr.DexInfo] = []
    for path in stock_paths + [runtime]:
        providers.extend(vr.load(path))
    classes, methods, fields, supers, ifaces = vr.merge(providers)

    def scan(paths: list[str]) -> dict[str, tuple[list, list, list]]:
        out: dict[str, tuple[list, list, list]] = {}
        for path in paths:
            for target in vr.load(path):
                out[norm(target.label)] = vr.check(
                    target, classes, methods, fields, supers, ifaces)
        return out

    stock, patched = scan(stock_paths), scan(patched_paths)
    bad: list[str] = []
    for label in sorted(patched):
        if label not in stock:
            bad.append(f"{label}: no stock counterpart to diff against")
            continue
        before, after = stock[label], patched[label]
        for kind, idx in (("type", 0), ("method", 1), ("field", 2)):
            introduced = sorted(set(after[idx]) - set(before[idx]))
            for item in introduced:
                bad.append(f"{label}: new unresolved {kind} {item}")
    return bad


def check_runtime(runtime: str, stock_paths: list[str]) -> list[str]:
    """The runtime dex must be self-sufficient: nothing it references may be
    missing from the stock jars it is about to be merged into."""
    providers: list[vr.DexInfo] = []
    for path in stock_paths + [runtime]:
        providers.extend(vr.load(path))
    classes, methods, fields, supers, ifaces = vr.merge(providers)
    bad: list[str] = []
    for target in vr.load(runtime):
        types, meths, flds = vr.check(
            target, classes, methods, fields, supers, ifaces)
        bad += [f"runtime: unresolved type {t}" for t in types]
        bad += [f"runtime: unresolved method {m}" for m in meths]
        bad += [f"runtime: unresolved field {f}" for f in flds]
    return bad


def check_merged(patched_paths: list[str], runtime: str) -> list[str]:
    """The runtime has to be *defined* in the patched jars, not just called.

    A module that only references KaoriosHook resolves nothing at boot and the
    framework dies during verification -- the failure is silent in every other
    check here, so it gets one of its own."""
    runtime_classes: set[str] = set()
    for info in vr.load(runtime):
        runtime_classes |= info.defined_classes

    merged: set[str] = set()
    referenced: set[str] = set()
    for path in patched_paths:
        for info in vr.load(path):
            merged |= info.defined_classes
            referenced |= {c for c, _, _ in info.used_methods
                           if c.startswith("Landroid/security/kaorios/")}
            referenced |= {c for c, _, _ in info.used_fields
                           if c.startswith("Landroid/security/kaorios/")}

    bad: list[str] = []
    missing = sorted(runtime_classes - merged)
    if missing:
        bad.append(f"{len(missing)}/{len(runtime_classes)} runtime classes not "
                   f"merged into the patched jars (e.g. {missing[0]})")
    if "Landroid/security/kaorios/KaoriosHook;" not in merged:
        bad.append("KaoriosHook is not defined in the patched jars")
    if not referenced:
        bad.append("nothing in the patched jars references the KaoriOS runtime "
                   "(hooks were not injected)")
    return bad


TOOLBOX_APK_ENTRY = "system/priv-app/KaoriosToolbox/KaoriosToolbox.apk"
TOOLBOX_WHITELIST_ENTRY = "system/etc/permissions/com.kousei.kaorios.xml"
MODULE_ZIP_NAME = "kaorios_patcher.zip"

# A zip without these is not a module: managers reject it before running a script.
# system/system_ext/framework/miui-services.jar is deliberately absent -- see CONDITIONAL_JAR.
REQUIRED_ENTRIES = [
    "module.prop",
    "customize.sh",
    "service.sh",
    "props.sh",
    "action.sh",
    "post-fs-data.sh",
    "uninstall.sh",
    "system.prop",
    "META-INF/com/google/android/update-binary",
    "META-INF/com/google/android/updater-script",
    "system/framework/framework.jar",
    "system/framework/services.jar",
    TOOLBOX_WHITELIST_ENTRY,
    TOOLBOX_APK_ENTRY,
]

# Lines the reference module.prop carries for KernelSU / SukiSU / APatch / Magisk.
REQUIRED_PROP = ["id=", "ksu=1", "sufs=1", "minApi=", "maxApi=", "requireReboot=true"]

# Everything in this list is signature|privileged. On a ROM that enforces privapp permissions a
# whitelist miss is a silent deny, so the grant only lands if the shipped XML carries it.
PRIVAPP_PERMISSIONS = [
    "android.permission.INSTALL_PACKAGES",
    "android.permission.DELETE_PACKAGES",
    "android.permission.REQUEST_DELETE_PACKAGES",
    "android.permission.CHANGE_PACKAGE_INSTALLATION_STATE",
    "android.permission.CLEAR_APP_CACHE",
    "android.permission.CLEAR_APP_USER_DATA",
    "android.permission.GET_PACKAGE_SIZE",
    "android.permission.WRITE_PACKAGE_VERIFICATION",
    "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
    "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND",
    "android.permission.READ_LOGS",
    "android.permission.DUMP",
    "android.permission.REBOOT",
    "android.permission.DEVICE_POWER",
    "android.permission.RECOVERY",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.ACCESS_ALL_EXTERNAL_STORAGE",
    "android.permission.WRITE_MEDIA_STORAGE",
    "android.permission.ACCESS_NOTIFICATIONS",
    "android.permission.FORCE_STOP_PACKAGES",
    "android.permission.KILL_BACKGROUND_PROCESSES",
    "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
    "android.permission.WRITE_SETTINGS",
]

# Text that only appears once the installer is about to alter the live system. The digest guard
# has to come first: an abort after these has nothing left to roll back.
MOUNT_MARKERS = [
    "Magic mount active",
    "hybridmount vfs rule add",
    'cp -f "$MODPATH/system/',
]


def find_module(patched_dir: str, explicit: str | None = None) -> str | None:
    if explicit:
        return explicit if os.path.exists(explicit) else None
    run_dir = os.path.dirname(os.path.abspath(patched_dir))
    candidate = os.path.join(run_dir, "module", MODULE_ZIP_NAME)
    return candidate if os.path.exists(candidate) else None


def check_module(module_zip: str, patched_paths: list[str],
                 reference_apk: str, patched_dir: str = "") -> list[str]:
    """Everything about the zip that a dex reader cannot tell you."""
    bad: list[str] = []
    with zipfile.ZipFile(module_zip) as zf:
        names = set(zf.namelist())
        missing = [n for n in REQUIRED_ENTRIES if n not in names]
        if missing:
            bad.append("missing entries: " + ", ".join(missing))
            return bad

        prop = zf.read("module.prop").decode("utf-8", "replace").splitlines()
        for key in REQUIRED_PROP:
            if not any(line.startswith(key) for line in prop):
                bad.append(f"module.prop missing {key}*")

        customize = zf.read("customize.sh").decode("utf-8", "replace")
        guard = customize.find("sha256sum")
        if guard < 0:
            bad.append("customize.sh has no ROM digest guard")
        else:
            mounts = [customize.find(m) for m in MOUNT_MARKERS]
            mounts = [m for m in mounts if m >= 0]
            if not mounts:
                bad.append("customize.sh has no mount action for the guard to precede")
            elif guard > min(mounts):
                bad.append("ROM digest guard runs after the mount action; an abort "
                           "would find the stock jar already overwritten")

        if "abort \"! Patched jar was built against a different system image\"" \
                not in customize:
            bad.append("customize.sh does not abort on a digest mismatch")

        sysprop = zf.read("system.prop").decode("utf-8", "replace")
        if "ro.control_privapp_permissions=" not in sysprop:
            bad.append("system.prop does not disable privapp permission enforcement")

        # The property layer has exactly one home and both entry points read it. A reset and a
        # clear driven by two copies of the list would drift, and the failure mode is silent: a
        # key the module set but no longer knows to delete stays overridden until reboot.
        props = zf.read("props.sh").decode("utf-8", "replace")
        for op in ("kaorios_props_reset", "kaorios_props_clear"):
            if f"{op}()" not in props:
                bad.append(f"props.sh does not define {op}()")
        if "resetprop" not in props:
            bad.append("props.sh never invokes resetprop")
        if "ro.boot.verifiedbootstate" not in props:
            bad.append("props.sh carries no verified-boot override")
        leaked = [e for e in ("service.sh", "action.sh", "system.prop", "customize.sh")
                  if "verifiedbootstate" in zf.read(e).decode("utf-8", "replace")]
        if leaked:
            bad.append("spoofed property list leaked into " + ", ".join(leaked))

        service = zf.read("service.sh").decode("utf-8", "replace")
        if "props.sh" not in service or "kaorios_props_reset" not in service:
            bad.append("service.sh does not apply the properties at late start")

        # Boot-loop guard: post-fs-data.sh arms the marker every boot, service.sh disarms it
        # at boot_completed. Either half missing turns the protector into dead weight.
        postfs = zf.read("post-fs-data.sh").decode("utf-8", "replace")
        if "boot_pending" not in postfs:
            bad.append("post-fs-data.sh does not arm the boot marker")
        if "boot_pending" not in service:
            bad.append("service.sh does not clear the boot marker")

        # No manager app presses the action button and the app no longer calls it either;
        # action.sh keeps both branches as the module's manager-button contract, so the
        # dispatch and the cache purge have to be present for a future caller.
        action = zf.read("action.sh").decode("utf-8", "replace")
        if '"clear"' not in action or "kaorios_props_clear" not in action:
            bad.append("action.sh does not dispatch the clear operation")
        if "pif.prop" not in action or "pif.json" not in action:
            bad.append("action.sh does not purge the GMS pif verdict cache")
        if "package_cache" not in action:
            bad.append("action.sh does not purge package_cache")

        # init publishes ro.* before any module runs, so they are runtime overrides; the
        # installer only stages files and must stay out of it.
        for entry in ("customize.sh", "system.prop", "post-fs-data.sh"):
            if "resetprop" in zf.read(entry).decode("utf-8", "replace"):
                bad.append(f"{entry} rewrites runtime properties; that belongs in props.sh")

        whitelist = zf.read(TOOLBOX_WHITELIST_ENTRY).decode("utf-8", "replace")
        for permission in PRIVAPP_PERMISSIONS:
            if permission not in whitelist:
                bad.append(f"privapp whitelist missing {permission}")

        apk = zf.read(TOOLBOX_APK_ENTRY)
        if b"APK Sig Block 42" not in apk:
            bad.append("toolbox apk has no APK Signing Block; Package Manager "
                       "will drop it with 'No APK Signature Scheme v2 signature'")
        if os.path.exists(reference_apk):
            with open(reference_apk, "rb") as handle:
                reference = handle.read()
            if reference != apk:
                bad.append("toolbox apk differs from the signed release artifact "
                           f"({len(reference)} vs {len(apk)} bytes)")
        else:
            bad.append(f"reference toolbox apk not found: {reference_apk}")

        # SettingsProvider.apk is forbidden outright (upstream 8c752fd retired provider
        # patching; the guide says keep the stock APK). A rebuilt APK loses its Signing Scheme
        # v2 block in the re-zip, Package Manager drops it with "No APK Signature Scheme v2
        # signature", its providers never publish, and system_server deadlocks in
        # DeviceConfig.getLong until the watchdog kills the boot -- so one direction is all
        # this needs, and it must fire whatever the run rebuilt.
        sp_entry = "system/priv-app/SettingsProvider/SettingsProvider.apk"
        if sp_entry in names:
            bad.append(f"{sp_entry} must never be packaged: upstream 8c752fd retired "
                       "SettingsProvider patching, the stock apk is kept")

        # What sections 1-4 checked has to be what boots: same bytes or the gate is meaningless.
        # CorePatch §3's jar is conditional in both directions -- a jar this run did not verify
        # must not be in the zip either, or the digest guard has nothing to compare it against.
        present = {os.path.basename(p) for p in patched_paths}
        mis_entry = MODULE_ENTRY[CONDITIONAL_JAR]
        if CONDITIONAL_JAR in present and mis_entry not in names:
            bad.append(f"{mis_entry} missing: {CONDITIONAL_JAR} was patched but not packaged")
        if CONDITIONAL_JAR not in present and mis_entry in names:
            bad.append(f"{mis_entry} is packaged but {CONDITIONAL_JAR} was not part of this "
                       "verification")

        for path in patched_paths:
            name = os.path.basename(path)
            entry = MODULE_ENTRY.get(name, "system/framework/" + name)
            if entry not in names:
                # The conditional jar's absence is already reported above, with its reason.
                if name != CONDITIONAL_JAR:
                    bad.append(f"missing entries: {entry}")
                continue
            with open(path, "rb") as handle:
                local = handle.read()
            if zf.read(entry) != local:
                bad.append(f"{entry} in the module is not the jar that was verified")
    return bad


def main(argv: list[str]) -> int:
    base = os.path.dirname(os.path.abspath(__file__))
    # Stock jars and the runtime dex live in the (gitignored) local-work/
    # scratch next door; every argument can still override them explicitly.
    local = os.path.join(base, "..", "local-work")
    if len(argv) < 2:
        print(__doc__)
        return 2

    patched = pair(argv[1])
    stock = pair(argv[2] if len(argv) > 2 else local)
    runtime = argv[3] if len(argv) > 3 else os.path.join(local, "kaorios.dex")

    if not patched or not stock or not os.path.exists(runtime):
        print(f"patched={patched}\nstock={stock}\nruntime={runtime}")
        return 2

    print("patched : " + ", ".join(os.path.basename(p) for p in patched))
    print("stock   : " + ", ".join(os.path.basename(p) for p in stock))
    print("runtime : " + os.path.basename(runtime))

    module = find_module(argv[1], argv[4] if len(argv) > 4 else None)
    reference_apk = os.path.join(base, "..", "engine", "src", "jvmMain",
                                 "resources", "module", "KaoriosToolbox.apk")
    print("module  : " + (module if module else "(not found)"))

    sections = [
        ("dex self-signature / checksum", check_signatures(patched)),
        ("dex u2 id limits + duplicate classes", check_limits(patched)[0]),
        ("runtime merged and referenced", check_merged(patched, runtime)),
        ("runtime dex references", check_runtime(runtime, stock)),
        ("references introduced by the patch",
         check_stock_baseline(stock, patched, runtime)),
    ]
    if module:
        sections.append(("module inventory",
                         check_module(module, patched, reference_apk, argv[1])))

    failed = False
    for title, problems in sections:
        status = "PASS" if not problems else "FAIL"
        failed |= bool(problems)
        print(f"[{status}] {title} ({len(problems)} problem(s))")
        for problem in problems[:40]:
            print(f"        {problem}")
        if len(problems) > 40:
            print(f"        ... {len(problems) - 40} more")

    if not module:
        print(f"[SKIP] module inventory (no {MODULE_ZIP_NAME} beside {argv[1]})")

    _, defined = check_limits(patched)
    for label, count in sorted(defined.items()):
        print(f"        {label}: {count} classes")
    print("\nSAFE TO FLASH" if not failed else "\nDO NOT FLASH")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

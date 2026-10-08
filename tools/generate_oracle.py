"""Differential oracle: run the Kaorios Python reference patchers and record their results.

Produces engine/src/jvmTest/resources/oracle.json containing, per fixture, the status the
reference produced and (when it patched successfully) the exact expected output text.
The Kotlin engine's tests are asserted against this file, so the Kotlin port is verified
byte-for-byte against the behavioural specification rather than against my own assumptions.
"""
import importlib
import importlib.util
import json
import pathlib
import sys

_ROOT = pathlib.Path(__file__).resolve().parents[1]
REF = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else _ROOT.parent / "Kaorios-Toolbox" / "script")
RES = pathlib.Path(sys.argv[2] if len(sys.argv) > 2
                   else _ROOT / "engine" / "src" / "jvmTest" / "resources")

sys.path.insert(0, str(REF))


def load(name):
    spec = importlib.util.spec_from_file_location(name + "_ref", REF / (name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name + "_ref"] = mod
    spec.loader.exec_module(mod)
    return mod


# Upstream renamed the entry point: `kaorios_patcher_a17.py` is now a 366-byte `runpy`
# shim that executes `kaorios_patcher.py` under `__main__`, so loading the old name would
# run the interactive CLI and drop the patcher functions this module needs. Load the
# canonical module directly.
kp = load("kaorios_patcher")
ce = load("patch-services-a17")
ss = load("patch-systemserver-a17")
at = load("patch-activitythread-a17")


def classify(fn, filename, text):
    try:
        result = fn(text)
        patched, changed = result if isinstance(result, tuple) else (result, result != text)
        kp.verify_target_content(filename, patched)
        return "PATCHED" if changed else "ALREADY_PATCHED", patched
    except Exception as exc:  # noqa: BLE001 - the reference signals every failure as an exception
        message = str(exc).lower()
        unsupported = any(t in message for t in (
            "anchor", "not found", "ambiguous", "unterminated", "unsupported",
            "missing", "expected exactly one", "found 0",
        ))
        return ("UNSUPPORTED_LAYOUT" if unsupported else "FAILED"), str(exc)


def system_server_patch(text):
    patched = ss.patch(text)
    return patched, patched != text


TARGETS = {
    "ComputerEngine.smali": ce.patch,
    "SystemServer.smali": system_server_patch,
    "ActivityThread.smali": at.patch,
    "AndroidKeyStoreKeyPairGeneratorSpi.smali": kp.patch_keystore_generator,
    "AndroidKeyStoreSpi.smali": kp.patch_keystore_spi,
    "Instrumentation.smali": kp.patch_instrumentation,
    "ApplicationPackageManager.smali": kp.patch_app_pkg_manager,
    "Build.smali": kp.patch_build,
    "Build$VERSION.smali": kp.patch_build_version,
}


def inline_fixtures():
    """Harvest smali literals defined in the reference test module.

    Upstream deleted `script/kaorios_patcher_a17_test.py` in "Clean repository test clutter",
    which orphaned every `inline_*` oracle case. The file is vendored verbatim in this
    directory so the fixtures stay reproducible; the patch algorithms it exercises are
    unchanged upstream, so the snapshot is the same specification the tests encoded.
    """
    spec = importlib.util.spec_from_file_location(
        "kp_test", pathlib.Path(__file__).with_name("upstream_kaorios_patcher_a17_test.py")
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    found = []
    for name, value in vars(mod).items():
        if isinstance(value, str) and ".class " in value and ".method" in value:
            found.append((name, value))
    return found


FIXTURE_TO_TARGET = {
    "computer_engine": "ComputerEngine.smali",
    "system_server": "SystemServer.smali",
}

oracle = {}
INPUTS = {}

for path in sorted((RES / "fixtures").rglob("*.smali")):
    name = path.stem
    text = path.read_text(encoding="utf-8")
    filename = next(
        (target for token, target in FIXTURE_TO_TARGET.items() if token in name), None
    )
    if filename is None:
        raise SystemExit(f"no target mapped for fixture {path.name}")
    status, payload = classify(TARGETS[filename], filename, text)
    oracle[name] = {"file": filename, "status": status, "payload": payload}
    INPUTS[name] = text

INLINE_TO_TARGET = {
    "SAMPLE_ACTIVITY_THREAD": "ActivityThread.smali",
    "SAMPLE_APP_PKG_MANAGER": "ApplicationPackageManager.smali",
    "SAMPLE_KEYSTORE_SPI": "AndroidKeyStoreSpi.smali",
    "SAMPLE_KEYSTORE_GEN": "AndroidKeyStoreKeyPairGeneratorSpi.smali",
    "SAMPLE_INSTRUMENTATION": "Instrumentation.smali",
}

for key, value in inline_fixtures():
    # The snapshot still defines SAMPLE_SETTINGS_PROVIDER_STOCK, but upstream 8c752fd
    # retired provider patching — skip it rather than raising "no target mapped".
    if key.startswith("SAMPLE_SETTINGS_PROVIDER"):
        continue
    filename = next(
        (target for prefix, target in INLINE_TO_TARGET.items() if key.startswith(prefix)), None
    )
    if filename is None:
        raise SystemExit(f"no target mapped for inline fixture {key}")
    status, payload = classify(TARGETS[filename], filename, value)
    oracle["inline_" + key] = {"file": filename, "status": status, "payload": payload}
    INPUTS["inline_" + key] = value

BUILD_SMALI = """.class public final Landroid/os/Build;
.super Ljava/lang/Object;
.source "Build.java"


# static fields
.field public static final BRAND:Ljava/lang/String; = "google"

.field public static final DEVICE:Ljava/lang/String; = "generic"

.field public static final FINGERPRINT:Ljava/lang/String; = "google/generic"

.field public static final HARDWARE:Ljava/lang/String; = "generic"

.field public static final ID:Ljava/lang/String; = "GENERIC"

.field public static final MANUFACTURER:Ljava/lang/String; = "Google"

.field public static final MODEL:Ljava/lang/String; = "sdk"

.field public static final PRODUCT:Ljava/lang/String; = "sdk"

.field public static final TAGS:Ljava/lang/String; = "test-keys"

.field public static final TIME:J = 0x0L

.field public static final TYPE:Ljava/lang/String; = "user"

.field public static final USER:Ljava/lang/String; = "user"

"""

BUILD_VERSION_SMALI = """.class public final Landroid/os/Build$VERSION;
.super Ljava/lang/Object;
.source "Build.java"


# static fields
.field public static final DEVICE_INITIAL_SDK_INT:I = 0x22

.field public static final RELEASE:Ljava/lang/String; = "14"

.field public static final RELEASE_OR_CODENAME:Ljava/lang/String; = "14"

.field public static final RELEASE_OR_PREVIEW_DISPLAY:Ljava/lang/String; = "14"

.field public static final SECURITY_PATCH:Ljava/lang/String; = "2024-01-01"

"""

BUILD_SMALI_PARTIAL = """.class public final Landroid/os/Build;
.super Ljava/lang/Object;

.field public static final BRAND:Ljava/lang/String; = "google"

.field public static final ID:Ljava/lang/String; = "GENERIC"

"""

import re as _re

BUILD_ALREADY_SPOOFED = _re.sub(
    r"\.field public static final (\w+):Ljava/lang/String; = \"[^\"]*\"",
    r".field public static \1:Ljava/lang/String; = null",
    BUILD_SMALI,
)

EXTRA_FIXTURES = {
    "extra_Build_stock": ("Build.smali", BUILD_SMALI),
    "extra_Build_already_spoofed": ("Build.smali", BUILD_ALREADY_SPOOFED),
    "extra_Build_partial_unsupported": ("Build.smali", BUILD_SMALI_PARTIAL),
    "extra_Build_VERSION_stock": ("Build$VERSION.smali", BUILD_VERSION_SMALI),
}

# ComputerEngine fixtures that also exercise the installer-source sub-patch, which only
# engages when the installer read APIs are present.
INSTALLER_MODERN = """.class public Lcom/android/server/pm/ComputerEngine;
.super Ljava/lang/Object;

.method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
    .registers 10
    const/4 v0, 0x0
    return v0
.end method

.method public getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;
    .registers 6
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I
    move-result v2
    const/4 v0, 0x0
    return-object v0
.end method

.method public getInstallSourceInfo(Ljava/lang/String;)Landroid/content/pm/InstallSourceInfo;
    .registers 7
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I
    move-result v2
    const/4 v0, 0x0
    return-object v0
.end method

"""

INSTALLER_VALID = """.class public Lcom/android/server/pm/ComputerEngine;
.super Ljava/lang/Object;

.method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
    .registers 10
    const/4 v0, 0x0
    return v0
.end method

.method public getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;
    .registers 8
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I
    move-result v3
    invoke-virtual {p1, p2, v3}, Lcom/android/server/pm/ComputerEngine;->getInstallSource(Ljava/lang/String;II)Lcom/android/server/pm/InstallSource;
    move-result-object v2
    iget-object v4, v2, Lcom/android/server/pm/InstallSource;->mInstallerPackageName:Ljava/lang/String;
    return-object v4
.end method

.method public getInstallSourceInfo(Ljava/lang/String;)Landroid/content/pm/InstallSourceInfo;
    .registers 12
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I
    move-result v3
    invoke-virtual {p1, p2, v3}, Lcom/android/server/pm/ComputerEngine;->getInstallSource(Ljava/lang/String;II)Lcom/android/server/pm/InstallSource;
    move-result-object v2
    iget-object v5, v2, Lcom/android/server/pm/InstallSource;->mInstallerPackageName:Ljava/lang/String;
    const/4 v6, 0x0
    new-instance v7, Landroid/content/pm/InstallSourceInfo;
    invoke-direct {v7, v5, v6, v6, v5, v6}, Landroid/content/pm/InstallSourceInfo;-><init>(Ljava/lang/String;Landroid/content/pm/SigningInfo;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
    return-object v7
.end method

"""

EXTRA_FIXTURES.update({
    "extra_ComputerEngine_installer_modern": ("ComputerEngine.smali", INSTALLER_MODERN),
    "extra_ComputerEngine_installer_valid": ("ComputerEngine.smali", INSTALLER_VALID),
})

# The high-register path: .registers 25 with seven parameters leaves 17 locals, so the
# low-path hook would address v17/p5 past the 4-bit invoke window. The reference emits
# invoke-interface/range for getPackageName, which is also what keeps verification on the
# high-register structure branch instead of the p-based sequence pattern.
COMPUTER_ENGINE_HIGH_REGISTER = """.class public Lcom/android/server/pm/ComputerEngine;
.super Ljava/lang/Object;

.method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
    .registers 25
    .param p1, "ps"    # Lcom/android/server/pm/pkg/PackageStateInternal;
    .param p2, "callingUid"    # I

    .line 2566
    move-object/from16 v6, p0
    move-object/from16 v7, p1
    const/4 v0, 0x0
    return v0
.end method

"""

EXTRA_FIXTURES.update({
    "extra_ComputerEngine_high_register": ("ComputerEngine.smali", COMPUTER_ENGINE_HIGH_REGISTER),
})

# Keystore leaf/chain fixtures from script/test_keystore_consistency.py (upstream v2.0.6.1):
# the stock class, a build whose chain discarded the hook's result (must be repaired), and a
# leaf with too few locals (must fail closed as FAILED, not UNSUPPORTED_LAYOUT).
KEYSTORE_STOCK = '''.class public Landroid/security/keystore2/AndroidKeyStoreSpi;
.super Ljava/security/KeyStoreSpi;
.method public engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;
    .locals 2
    const/4 v0, 0x0
    return-object v0
.end method
.method public engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
    .locals 3
    const/4 v0, 0x1
    new-array v1, v0, [Ljava/security/cert/Certificate;
    const/4 v0, 0x0
    const/4 v2, 0x0
    aput-object v2, v1, v0
    return-object v1
.end method
'''

KEYSTORE_PATCHED, _ = kp.patch_keystore_spi(KEYSTORE_STOCK)
KEYSTORE_DISCARDED = KEYSTORE_PATCHED.replace(
    "move-result-object v1", "move-result-object v2\n    .line 215", 1)

EXTRA_FIXTURES.update({
    "extra_KeyStoreSpi_stock_leaf": ("AndroidKeyStoreSpi.smali", KEYSTORE_STOCK),
    "extra_KeyStoreSpi_repair_discarded": ("AndroidKeyStoreSpi.smali", KEYSTORE_DISCARDED),
    "extra_KeyStoreSpi_leaf_one_local": (
        "AndroidKeyStoreSpi.smali",
        KEYSTORE_STOCK.replace(".locals 2", ".locals 1", 1),
    ),
})

# Verbatim classes from a real HyperOS 4 / Android 17 (SDK 37) device. The synthetic fixtures
# above are abridged: they carry no string literals in patched bodies and no debug directives,
# which is how three separate porting bugs survived them (split-capture-group semantics,
# CRLF-only line anchors, and a label index taken from the wrong coordinate system). When a
# local-work/pulled directory holds jars from a real ROM, add its classes here so the suite
# keeps proving parity against production input.
REAL_ROM = pathlib.Path(__file__).resolve().parent.parent / "local-work" / "pristine" / "smali"
REAL_ROM_CASES = {
    "real_a17_ComputerEngine": (
        "ComputerEngine.smali",
        "services.jar/classes3.dex/com/android/server/pm/ComputerEngine.smali",
    ),
    "real_a17_AndroidKeyStoreSpi": (
        "AndroidKeyStoreSpi.smali",
        "framework.jar/classes3.dex/android/security/keystore2/AndroidKeyStoreSpi.smali",
    ),
}

for name, (filename, relative) in REAL_ROM_CASES.items():
    source = REAL_ROM / pathlib.Path(relative)
    if not source.is_file():
        print(f"skipping {name}: {source} not present")
        continue
    text = source.read_text(encoding="utf-8")
    status, payload = classify(TARGETS[filename], filename, text)
    oracle[name] = {"file": filename, "status": status, "payload": payload}
    INPUTS[name] = text

for name, (filename, text) in EXTRA_FIXTURES.items():
    status, payload = classify(TARGETS[filename], filename, text)
    oracle[name] = {"file": filename, "status": status, "payload": payload}
    INPUTS[name] = text

out_root = RES / "oracle"
if out_root.exists():
    import shutil

    shutil.rmtree(out_root)
out_root.mkdir(parents=True)

for name, entry in oracle.items():
    case = out_root / name
    case.mkdir(parents=True)

    def write(filename, text):
        # newline="" keeps the reference's exact bytes: Python read the sources with
        # universal newlines (CRLF -> LF), so the stored text must stay LF-only.
        (case / filename).write_text(text, encoding="utf-8", newline="")

    write("target.txt", entry["file"])
    write("status.txt", entry["status"])
    write("input.smali", INPUTS[name])
    if entry["status"] == "PATCHED":
        write("expected.smali", entry["payload"])
    elif entry["status"] != "ALREADY_PATCHED":
        write("error.txt", entry["payload"])

print(f"wrote {out_root} with {len(oracle)} cases")
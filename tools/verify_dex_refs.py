#!/usr/bin/env python3
"""Check every type/method/field a target DEX references resolves in a provider set.

Providers are the stock device jars (framework/services), the KaoriOS runtime DEX
itself, plus a whitelist of packages that live in boot-image/APEX jars we do not
ship. Anything outside that set becomes a NoClassDefFoundError / NoSuchMethodError
at runtime, so it is reported rather than ignored.
"""
from __future__ import annotations

import os
import sys
import zipfile
from dataclasses import dataclass, field

WHITELIST_PREFIXES = (
    "Ljava/", "Ljavax/", "Lsun/", "Ljdk/", "Ldalvik/", "Llibcore/",
    "Lorg/json/", "Lorg/w3c/", "Lorg/xml/", "Lorg/xmlpull/",
    "Lcom/android/org/", "Lcom/android/i18n/", "Landroid/icu/",
    "Lvendor/", "Ljunit/",
)


@dataclass
class DexInfo:
    label: str
    defined_classes: set[str] = field(default_factory=set)
    defined_methods: dict[str, set[str]] = field(default_factory=dict)
    defined_fields: dict[str, set[str]] = field(default_factory=dict)
    supers: dict[str, str | None] = field(default_factory=dict)
    used_types: set[str] = field(default_factory=set)
    used_methods: set[tuple[str, str, str]] = field(default_factory=set)
    used_fields: set[tuple[str, str, str]] = field(default_factory=set)
    method_index: list[tuple[str, str, str]] = field(default_factory=list)
    field_index: list[tuple[str, str, str]] = field(default_factory=list)
    ifaces: dict[str, list[str]] = field(default_factory=dict)


def u4(b: bytes, off: int) -> int:
    return int.from_bytes(b[off:off + 4], "little")


def u2(b: bytes, off: int) -> int:
    return int.from_bytes(b[off:off + 2], "little")


def uleb128(b: bytes, off: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = b[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, off
        shift += 7


def read_string(b: bytes, off: int) -> str:
    _, off = uleb128(b, off)
    end = b.index(0, off)
    return b[off:end].decode("utf-8", errors="replace")


def parse_dex(data: bytes, label: str) -> DexInfo:
    if data[:4] not in (b"dex\n", b"cdex"):
        raise ValueError(f"{label}: not a dex file")
    info = DexInfo(label=label)

    str_size, str_off = u4(data, 0x38), u4(data, 0x3C)
    type_size, type_off = u4(data, 0x40), u4(data, 0x44)
    proto_size, proto_off = u4(data, 0x48), u4(data, 0x4C)
    field_size, field_off = u4(data, 0x50), u4(data, 0x54)
    method_size, method_off = u4(data, 0x58), u4(data, 0x5C)
    class_size, class_off = u4(data, 0x60), u4(data, 0x64)

    strings = [read_string(data, u4(data, str_off + i * 4)) for i in range(str_size)]
    type_desc = [strings[u4(data, type_off + i * 4)] for i in range(type_size)]
    info.used_types.update(type_desc)

    protos: list[str] = []
    for i in range(proto_size):
        base = proto_off + i * 12
        ret = type_desc[u4(data, base + 4)]
        params_off = u4(data, base + 8)
        params: list[str] = []
        if params_off:
            count = u4(data, params_off)
            params = [type_desc[u2(data, params_off + 4 + j * 2)] for j in range(count)]
        protos.append(f"({''.join(params)}){ret}")

    for i in range(field_size):
        base = field_off + i * 8
        entry = (type_desc[u2(data, base)], strings[u4(data, base + 4)],
                 type_desc[u2(data, base + 2)])
        info.used_fields.add(entry)
        info.field_index.append(entry)

    for i in range(method_size):
        base = method_off + i * 8
        entry = (type_desc[u2(data, base)], strings[u4(data, base + 4)],
                 protos[u2(data, base + 2)])
        info.used_methods.add(entry)
        info.method_index.append(entry)

    for i in range(class_size):
        base = class_off + i * 32
        cls = type_desc[u4(data, base)]
        info.defined_classes.add(cls)
        info.defined_methods.setdefault(cls, set())
        info.defined_fields.setdefault(cls, set())
        super_idx = u4(data, base + 8)
        info.supers[cls] = None if super_idx == 0xFFFFFFFF else type_desc[super_idx]
        ifaces_off = u4(data, base + 12)
        impl: list[str] = []
        if ifaces_off:
            count = u4(data, ifaces_off)
            impl = [type_desc[u2(data, ifaces_off + 4 + j * 2)] for j in range(count)]
        info.ifaces[cls] = impl
        data_off = u4(data, base + 24)
        if not data_off:
            continue
        off = data_off
        n_static, off = uleb128(data, off)
        n_instance, off = uleb128(data, off)
        n_direct, off = uleb128(data, off)
        n_virtual, off = uleb128(data, off)

        for count in (n_static, n_instance):
            fidx = 0
            for _ in range(count):
                diff, off = uleb128(data, off)
                _, off = uleb128(data, off)
                fidx += diff
                _, name, typ = info.field_index[fidx]
                info.defined_fields[cls].add(f"{name}:{typ}")

        for count in (n_direct, n_virtual):
            midx = 0
            for _ in range(count):
                diff, off = uleb128(data, off)
                _, off = uleb128(data, off)
                _, off = uleb128(data, off)
                midx += diff
                _, name, proto = info.method_index[midx]
                info.defined_methods[cls].add(f"{name}{proto}")

    return info


def dex_blobs(path: str) -> list[tuple[str, bytes]]:
    if path.endswith(".dex"):
        return [(path, open(path, "rb").read())]
    out = []
    with zipfile.ZipFile(path) as zf:
        for name in sorted(zf.namelist()):
            if name.endswith(".dex"):
                out.append((f"{path}!{name}", zf.read(name)))
    return out


def load(path: str) -> list[DexInfo]:
    return [parse_dex(blob, label) for label, blob in dex_blobs(path)]


def merge(infos: list[DexInfo]):
    classes: set[str] = set()
    methods: dict[str, set[str]] = {}
    fields: dict[str, set[str]] = {}
    supers: dict[str, str | None] = {}
    ifaces: dict[str, list[str]] = {}
    for info in infos:
        classes |= info.defined_classes
        supers.update(info.supers)
        ifaces.update(info.ifaces)
        for cls, sigs in info.defined_methods.items():
            methods.setdefault(cls, set()).update(sigs)
        for cls, sigs in info.defined_fields.items():
            fields.setdefault(cls, set()).update(sigs)
    return classes, methods, fields, supers, ifaces


def whitelisted(descriptor: str) -> bool:
    return descriptor.startswith(WHITELIST_PREFIXES)


PRIMITIVES = set("BCDFIJSVZ")

# java.lang.Object declares these on every reference type, so they resolve
# without any provider holding the class.
OBJECT_METHODS = {
    "equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;",
    "getClass()Ljava/lang/Class;", "clone()Ljava/lang/Object;",
    "notify()V", "notifyAll()V", "wait()V", "wait(J)V", "wait(JI)V",
    "finalize()V",
}


def unwrap(descriptor: str) -> str | None:
    """Strip array prefixes; return None for arrays whose element is primitive."""
    while descriptor.startswith("["):
        descriptor = descriptor[1:]
    return None if descriptor in PRIMITIVES else descriptor


def resolves(sigs: dict[str, set[str]], supers: dict[str, str | None],
             ifaces: dict[str, list[str]], cls: str, member: str) -> bool:
    if member in OBJECT_METHODS:
        return True
    queue: list[str | None] = [cls]
    seen: set[str] = set()
    while queue:
        cur = queue.pop()
        if not cur or cur in seen:
            continue
        seen.add(cur)
        if member in sigs.get(cur, ()):
            return True
        if cur != "Ljava/lang/Object;" and whitelisted(cur):
            # A boot/APEX class we do not ship: cannot disprove the member.
            return True
        queue.append(supers.get(cur))
        queue.extend(ifaces.get(cur, ()))
    return False


def check(target: DexInfo, classes, methods, fields, supers, ifaces):
    bad_types: set[str] = set()
    for desc in target.used_types:
        elem = unwrap(desc)
        if elem is not None and not whitelisted(elem) and elem not in classes:
            bad_types.add(elem)
    bad_methods = sorted(
        f"{c}->{n}{p}" for (c, n, p) in target.used_methods
        if not whitelisted(c) and c in classes
        and not resolves(methods, supers, ifaces, c, f"{n}{p}")
    )
    bad_fields = sorted(
        f"{c}->{n}:{t}" for (c, n, t) in target.used_fields
        if not whitelisted(c) and c in classes
        and not resolves(fields, supers, ifaces, c, f"{n}:{t}")
    )
    return sorted(bad_types), bad_methods, bad_fields


def main() -> int:
    # Stock jars and the runtime dex live in the (gitignored) local-work/
    # scratch next door; targets come from argv as before.
    local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "local-work")
    provider_paths = [
        os.path.join(local, "device_orig_fw.jar"),
        os.path.join(local, "device_orig_sv.jar"),
        os.path.join(local, "kaorios.dex"),
    ]
    target_paths = sys.argv[1:] or [os.path.join(local, "kaorios.dex")]

    print("providers:")
    provider_infos: list[DexInfo] = []
    for path in provider_paths:
        infos = load(path)
        provider_infos.extend(infos)
        print(f"  {path}: {len(infos)} dex, "
              f"{sum(len(i.defined_classes) for i in infos)} classes")
    classes, methods, fields, supers, ifaces = merge(provider_infos)

    failed = False
    for path in target_paths:
        for target in load(path):
            bad_types, bad_methods, bad_fields = check(
                target, classes, methods, fields, supers, ifaces)
            print(f"\ntarget {target.label}")
            print(f"  types used={len(target.used_types)} "
                  f"methods used={len(target.used_methods)} "
                  f"fields used={len(target.used_fields)}")
            for title, items in (
                ("UNRESOLVED TYPE", bad_types),
                ("UNRESOLVED METHOD", bad_methods),
                ("UNRESOLVED FIELD", bad_fields),
            ):
                if not items:
                    print(f"  {title}: none")
                    continue
                failed = True
                print(f"  {title}: {len(items)}")
                for item in items[:60]:
                    print(f"    {item}")
                if len(items) > 60:
                    print(f"    ... {len(items) - 60} more")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

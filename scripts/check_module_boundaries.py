#!/usr/bin/env python3
"""CI 边界守护：firestar-core 不得反向依赖 firestar-biz。

扫描 firestar-core 下所有 .java，若发现 import 了「仅在 firestar-biz 中定义」的类，
打印违规并退出码 1。仅检查显式 import（与 SeedIntegrity 的静态判定互补）。

用法：python3 scripts/check_module_boundaries.py
"""
import os, re, sys

# 脚本位于 <仓库根>/scripts/，仓库根即 Maven reactor 根（含 firestar-core/biz/server）
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MODS = {
    "core": os.path.join(ROOT, "firestar-core", "src", "main", "java"),
    "biz":  os.path.join(ROOT, "firestar-biz",  "src", "main", "java"),
    "server": os.path.join(ROOT, "firestar-server", "src", "main", "java"),
}
PKG = "com/firestar/"
IMP = re.compile(r'^\s*import\s+(?:static\s+)?(com\.firestar\.[\w.]+)\s*;', re.M)


def walk(base):
    out = []
    for dp, _, fns in os.walk(base):
        for fn in fns:
            if fn.endswith(".java"):
                out.append(os.path.join(dp, fn))
    return out


# simple class name -> set of modules that define it
name_mod = {}
for mod, base in MODS.items():
    if mod == "server":  # 只索引 core/biz，避免 server 同名类造成误报
        continue
    for f in walk(base):
        rel = os.path.relpath(f, base).replace(os.sep, "/")
        if not rel.startswith(PKG):
            continue
        sn = os.path.basename(f)[:-5]
        name_mod.setdefault(sn, set()).add(mod)

viol = []
for f in walk(MODS["core"]):
    with open(f, "r", encoding="utf-8", errors="ignore") as fh:
        src = fh.read()
    for m in IMP.finditer(src):
        fq = m.group(1)
        sn = fq.split(".")[-1]
        owners = name_mod.get(sn)
        if owners and "biz" in owners and "core" not in owners:
            viol.append((os.path.relpath(f, MODS["core"]), fq))

if viol:
    print("核心模块反向依赖了业务模块的类（违反依赖方向铁律）：")
    for rel, fq in viol:
        print(f"  {rel}  ->  {fq}")
    sys.exit(1)
print("OK: firestar-core 未反向依赖 firestar-biz。")

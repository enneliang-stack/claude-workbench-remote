#!/usr/bin/env python3
"""下载完整离线安装所需的 Alpine aarch64 包。

用法：python phone/fetch-offline-packages.py
输出：phone/offline-apk/*.apk、phone/alpine-minirootfs-3.24.2-aarch64.tar.gz

这些文件由 build-phone-assets.py 放进 APK，手机首次安装时用
`apk add --no-network --allow-untrusted /opt/workbench/apk/*.apk` 离线安装，
不依赖 Alpine/Node 的网络下载。
"""
import gzip
import io
import re
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "phone" / "offline-apk"
BASE = "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24"
ALPINE_VERSION = "3.24.2"
ROOTS = [
    "bash", "curl", "jq", "libgcc", "libstdc++", "ripgrep", "git",
    "ca-certificates", "procps", "coreutils", "findutils", "nodejs", "npm",
]
UA = {"User-Agent": "ClaudeWorkbench-offline-builder/1.0"}


def fetch(url, timeout=60):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def parse_index(text, repo):
    pkgs = []
    for stanza in text.split("\n\n"):
        d = {}
        for line in stanza.splitlines():
            if len(line) > 2 and line[1] == ":":
                d[line[0]] = line[2:]
        if "P" in d and "V" in d:
            d["_repo"] = repo
            d["_file"] = d.get("F") or f"{d['P']}-{d['V']}.apk"
            pkgs.append(d)
    return pkgs


def version_key(v):
    main, _, rev = v.partition("-r")
    parts = []
    for x in re.split(r"[._-]", main):
        parts.append(int(x) if x.isdigit() else 0)
    return (parts, int(rev) if rev.isdigit() else 0)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    all_pkgs = []
    for repo in ("main", "community"):
        url = f"{BASE}/{repo}/aarch64/APKINDEX.tar.gz"
        print(f"[index] {url}")
        raw = fetch(url)
        with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as t:
            idx = t.extractfile("APKINDEX").read().decode("utf-8")
        got = parse_index(idx, repo)
        print(f"[index] {repo}: {len(got)} packages")
        all_pkgs.extend(got)

    provider = {}
    for p in all_pkgs:
        provider.setdefault(p["P"], []).append(p)
        for item in p.get("p", "").split():
            name = item.split("=", 1)[0]
            if name:
                provider.setdefault(name, []).append(p)

    def best(name):
        cands = provider.get(name) or []
        if not cands:
            return None
        # 优先真实包名，其次任意 provider
        named = [p for p in cands if p["P"] == name]
        pool = named or cands
        return max(pool, key=lambda p: version_key(p["V"]))

    selected = {}
    missing = []

    def add(name, needed_by):
        p = best(name)
        if p is None:
            missing.append((name, needed_by))
            return
        if p["P"] in selected:
            return
        selected[p["P"]] = p
        for dep in p.get("D", "").split():
            dep = dep.lstrip("!")
            dep_name = re.split(r"[<>=~]", dep, 1)[0]
            if dep_name:
                add(dep_name, p["P"])

    for r in ROOTS:
        add(r, "root")

    # yash-binsh 也提供 /bin/sh，会和 busybox 冲突；让 base rootfs 的 busybox 提供 sh。
    selected.pop("yash", None)
    selected.pop("yash-binsh", None)
    # musl 用 rootfs 自带的版本，避免在 proot 运行中替换动态加载器。
    selected.pop("musl", None)

    if missing:
        print("[warn] 未找到依赖：")
        for name, by in missing:
            print(f"  {name} (needed by {by})")

    total = 0
    for p in sorted(selected.values(), key=lambda x: x["P"]):
        url = f"{BASE}/{p['_repo']}/aarch64/{p['_file']}"
        dst = OUT / p["_file"]
        size = int(p.get("S", "0") or 0)
        if dst.is_file() and (size <= 0 or dst.stat().st_size == size):
            total += dst.stat().st_size
            continue
        print(f"[download] {p['P']} {p['V']} ({size / 1024 / 1024:.1f} MB)")
        data = fetch(url, timeout=180)
        dst.write_bytes(data)
        total += len(data)
    print(f"[done] {len(selected)} packages, {total / 1024 / 1024:.1f} MB")

    rootfs = ROOT / "phone" / f"alpine-minirootfs-{ALPINE_VERSION}-aarch64.tar.gz"
    if not rootfs.is_file():
        url = f"{BASE}/releases/aarch64/{rootfs.name}"
        print(f"[download] {url}")
        rootfs.write_bytes(fetch(url, timeout=180))
    print(f"[done] {rootfs} ({rootfs.stat().st_size / 1024 / 1024:.1f} MB)")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"[error] {e}", file=sys.stderr)
        sys.exit(1)

#!/usr/bin/env python3
"""Patch vendored Tailscale netmon to accept TS_FAKE_DEFAULT_IFACE.

Android's policy routing hides the default route from /proc/net/route, so
netmon in proot reports an empty default route. The App passes the active
Android interface name (from ConnectivityManager) in this env var.
"""
from pathlib import Path

root = Path(__file__).resolve().parent
target = root / "vendor" / "tailscale.com" / "net" / "netmon" / "interfaces_linux.go"
if not target.is_file():
    raise SystemExit("找不到 vendor/tailscale.com/net/netmon/interfaces_linux.go；先运行 go mod vendor")

text = target.read_text(encoding="utf-8")
if "TS_FAKE_DEFAULT_IFACE" in text:
    print("netmon already patched")
    raise SystemExit(0)

old = """func defaultRoute() (d DefaultRouteDetails, err error) {
\tv, err := defaultRouteInterfaceProcNet()"""
new = """func defaultRoute() (d DefaultRouteDetails, err error) {
\t// Android proot: policy routing hides the default route from /proc/net/route.
\t// The host App passes the active interface name (ConnectivityManager).
\tif v := os.Getenv("TS_FAKE_DEFAULT_IFACE"); v != "" {
\t\td.InterfaceName = v
\t\treturn d, nil
\t}
\tv, err := defaultRouteInterfaceProcNet()"""
if old not in text:
    raise SystemExit("defaultRoute 模式没找到，Tailscale 版本可能变了")
target.write_text(text.replace(old, new, 1), encoding="utf-8")
print("netmon patched")

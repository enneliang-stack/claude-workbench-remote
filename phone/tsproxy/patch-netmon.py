#!/usr/bin/env python3
"""Patch vendored Tailscale for the Android proot environment.

1) netmon: Android policy routing hides the default route from /proc/net/route,
   so accept TS_FAKE_DEFAULT_IFACE from the App.
2) ipnlocal peerapi: tsnet/netstack can return a nil listener for the 2nd+
   address; the stock code assumes ln is non-nil and panics with
   "net.Addr is nil, not *net.TCPAddr". Reuse the first listener port.
"""
from pathlib import Path

root = Path(__file__).resolve().parent


def patch_netmon():
    target = root / "vendor" / "tailscale.com" / "net" / "netmon" / "interfaces_linux.go"
    if not target.is_file():
        raise SystemExit("找不到 vendor/tailscale.com/net/netmon/interfaces_linux.go；先运行 go mod vendor")
    text = target.read_text(encoding="utf-8")
    if "TS_FAKE_DEFAULT_IFACE" in text:
        print("netmon already patched")
        return
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


def patch_peerapi():
    target = root / "vendor" / "tailscale.com" / "ipn" / "ipnlocal" / "local.go"
    if not target.is_file():
        raise SystemExit("找不到 vendor/tailscale.com/ipn/ipnlocal/local.go；先运行 go mod vendor")
    text = target.read_text(encoding="utf-8")
    if "peerapi listen returned nil listener" in text:
        print("peerapi already patched")
        return
    old = """\t\tif skipListen {
\t\t\tpln.port = b.peerAPIListeners[0].port
\t\t} else {
\t\t\tpln.port = ln.Addr().(*net.TCPAddr).Port
\t\t}"""
    new = """\t\tif skipListen || ln == nil {
\t\t\tif len(b.peerAPIListeners) > 0 {
\t\t\t\tpln.port = b.peerAPIListeners[0].port
\t\t\t} else {
\t\t\t\tb.logf("[unexpected] peerapi listen returned nil listener for %s", a.Addr())
\t\t\t\tcontinue
\t\t\t}
\t\t} else {
\t\t\tpln.port = ln.Addr().(*net.TCPAddr).Port
\t\t}"""
    if old not in text:
        raise SystemExit("peerapi skipListen 模式没找到，Tailscale 版本可能变了")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("peerapi patched")


patch_netmon()
patch_peerapi()

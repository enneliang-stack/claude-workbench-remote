#!/usr/bin/env python3
"""把 PC 版 server.js / 手机页转换成 Android 本机版资源。

只做“本机运行”必需的改动，PC 版源码保持不动：
- 强制只监听 127.0.0.1，关闭远程访问
- 固定 claude 路径、端口、手机工作区
- 余额接口直连 DeepSeek（不再依赖桌宠 sidecar）
- 文件选择器提供 /workspace、/sdcard 快捷入口
"""
import os
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_SERVER = ROOT / "server" / "server-1.1.28-remote.js"
SRC_PUBLIC = ROOT / "overlay" / "claude-gui" / "public"
OUT = ROOT / "android" / "app" / "assets" / "phone"

# 手机页资源版本：和服务端 ASSET_VERSION 同步，用于给 m.css/m.js 加 ?v=，
# 强制 WebView 在 App 升级后重新拉取，避免缓存住了旧的收起按钮等 JS/CSS。
def _asset_version():
    try:
        f = ROOT / "android" / "app" / "src" / "com" / "missyao" / "claudeworkbench" / "LocalEngine.java"
        m = re.search(r"ASSET_VERSION\s*=\s*(\d+)", f.read_text(encoding="utf-8"))
        return m.group(1) if m else "0"
    except Exception:
        return "0"

ASSET_TAG = _asset_version()


def rep(text, old, new, label):
    if old not in text:
        raise SystemExit(f"[patch] 找不到模式：{label}")
    return text.replace(old, new, 1)


def build_server():
    text = SRC_SERVER.read_text(encoding="utf-8")

    text = rep(
        text,
        "const IS_WIN = process.platform === 'win32';",
        "const IS_WIN = process.platform === 'win32';\n"
        "// Android 本机模式：由 App 的 proot 环境启动，只监听 127.0.0.1\n"
        "const IS_PHONE = process.env.WORKBENCH_PHONE === '1';",
        "IS_PHONE",
    )

    text = rep(
        text,
        "本客户端由用户在桌面上操作。",
        "本客户端由用户在手机上操作。",
        "phone ask guide",
    )

    phone_guide_def = """const PHONE_GUIDE = [
  '你可以通过 phone_* MCP 工具操作这台安卓手机：先用 phone_ui 查看当前界面，再用 phone_tap、phone_tap_text、phone_type、phone_swipe、phone_global、phone_open_app 执行。',
  '密码框、支付、转账、验证码等敏感操作必须交还用户，不要尝试输入或点击；工具返回 needsUser 时停下来让用户处理。',
  '每次操作手机前用一句不超过 20 字的中文说明要做什么；操作后可以再用 phone_ui 确认结果。',
  '手机操作要快：目标明确时直接用 phone_tap_text / phone_open_app / phone_global，不必每次都读整屏；只有找不到控件时才用 phone_ui，并传 max_nodes=180 左右。',
].join('\\n');

"""
    text = rep(
        text,
        "const ASK_GUIDE = [",
        phone_guide_def + "const ASK_GUIDE = [",
        "phone guide definition",
    )

    text = rep(
        text,
        "'--append-system-prompt', ASK_GUIDE",
        "'--append-system-prompt', IS_PHONE ? ASK_GUIDE + '\\n' + PHONE_GUIDE : ASK_GUIDE",
        "phone guide append",
    )

    # 手机端 Node 服务只监听 127.0.0.1；某些代理/ROM 会让 socket 看到非 loopback 地址，
    # 从而误返回「远程访问登录页」。手机端直接视为本机，跳过多余的令牌门槛。
    text = rep(
        text,
        "function isLoopback(req) {\n"
        "  const addr = (req.socket && req.socket.remoteAddress) || '';\n"
        "  return addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1';\n"
        "}",
        "function isLoopback(req) {\n"
        "  if (IS_PHONE) return true; // 手机端服务只监听 127.0.0.1\n"
        "  const addr = (req.socket && req.socket.remoteAddress) || '';\n"
        "  return addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1';\n"
        "}",
        "phone loopback",
    )
    # 手机端默认推理档位 low：新建对话直接带 low，设置里也可以改。
    text = rep(
        text,
        "settings: { defaultPermissionMode: 'bypassPermissions', defaultModel: '', defaultEffort: '' },",
        "settings: { defaultPermissionMode: 'bypassPermissions', defaultModel: '', defaultEffort: 'low' },",
        "phone default effort",
    )
    text = rep(
        text,
        "state = loadState();",
        "state = loadState();\n"
        "if (IS_PHONE) {\n"
        "  state.settings.defaultEffort = 'low';\n"
        "  if (!state.phoneEffortMigrated) {\n"
        "    for (const c of Object.values(state.conversations || {})) {\n"
        "      if (!c.effort || c.effort === 'max') c.effort = 'low';\n"
        "    }\n"
        "    state.phoneEffortMigrated = true;\n"
        "  }\n"
        "}",
        "phone default effort state",
    )

    text = rep(
        text,
        "function ensurePlaywrightSkill() {",
        "function ensurePlaywrightSkill() {\n  if (IS_PHONE) return;",
        "skip playwright skill",
    )

    text = rep(
        text,
        "let remoteEnabled = readRemoteEnabled();",
        "let remoteEnabled = IS_PHONE ? false : readRemoteEnabled();",
        "remote off",
    )

    text = rep(
        text,
        "async function listDrives() {\n  if (!IS_WIN) return ['/'];",
        "async function listDrives() {\n"
        "  if (IS_PHONE) return ['/workspace', '/sdcard'].filter(p => fs.existsSync(p));\n"
        "  if (!IS_WIN) return ['/'];",
        "phone drives",
    )

    text = rep(
        text,
        "function quickLinks() {\n  return [",
        "function quickLinks() {\n"
        "  if (IS_PHONE) {\n"
        "    return [\n"
        "      { name: '工作区', path: '/workspace' },\n"
        "      { name: '主目录', path: HOME },\n"
        "      { name: '手机存储', path: '/sdcard' }\n"
        "    ].filter(q => fs.existsSync(q.path));\n"
        "  }\n"
        "  return [",
        "phone quick links",
    )

    text = rep(
        text,
        "async function buildBootstrap() {\n  const folderMap",
        "async function buildBootstrap() {\n"
        "  if (IS_PHONE) {\n"
        "    try { fs.mkdirSync('/workspace', { recursive: true }); } catch { /* ignore */ }\n"
        "    let added = false;\n"
        "    for (const dir of ['/workspace', '/sdcard']) {\n"
        "      if (!fs.existsSync(dir)) continue;\n"
        "      if (state.folders.some(f => normKey(f.path) === normKey(dir))) continue;\n"
        "      state.folders.push({ path: dir, addedAt: new Date().toISOString() });\n"
        "      added = true;\n"
        "    }\n"
        "    if (added) saveState();\n"
        "  }\n"
        "  const folderMap",
        "workspace bootstrap",
    )

    text = rep(
        text,
        "function openBrowser(url) {\n  if (process.env.NO_OPEN) return;",
        "function openBrowser(url) {\n  if (IS_PHONE || process.env.NO_OPEN) return;",
        "no open browser",
    )

    text = rep(
        text,
        "let SERVER_PORT = Number(process.env.PORT || 4711);",
        "let SERVER_PORT = Number(process.env.PORT || (IS_PHONE ? 4712 : 4711));",
        "phone port",
    )

    text = rep(
        text,
        "function applyRemoteBind(enabled) {\n  const host",
        "function applyRemoteBind(enabled) {\n  if (IS_PHONE) enabled = false;\n  const host",
        "bind loopback",
    )

    text = rep(
        text,
        "function resolveClaude() {\n  return new Promise(resolve => {",
        "function resolveClaude() {\n"
        "  if (IS_PHONE) {\n"
        "    const fixed = '/root/.local/bin/claude';\n"
        "    if (fs.existsSync(fixed)) {\n"
        "      CLAUDE_BIN = fixed;\n"
        "      return new Promise(resolve => {\n"
        "        let done = false;\n"
        "        const finish = () => { if (!done) { done = true; resolve(); } };\n"
        "        try {\n"
        "          execFile(CLAUDE_BIN, ['--version'], { windowsHide: true, timeout: 15000 }, (e, out) => {\n"
        "            if (!e) CLAUDE_VERSION = String(out).trim();\n"
        "            finish();\n"
        "          });\n"
        "        } catch { finish(); }\n"
        "        setTimeout(finish, 15000);\n"
        "      });\n"
        "    }\n"
        "  }\n"
        "  return new Promise(resolve => {",
        "resolve claude",
    )

    text = rep(
        text,
        "    const cliMode = uiMode === 'bypassPermissions' ? 'manual' : uiMode;",
        "    const cliMode = uiMode === 'bypassPermissions'\n"
        "      ? ((IS_PHONE && process.env.CLAUDE_LEGACY === '1') ? 'default' : 'manual')\n"
        "      : uiMode;",
        "legacy permission mode",
    )

    text = rep(
        text,
        "    if (conv.model) args.push('--model', conv.model);",
        "    if (IS_PHONE) args.push('--mcp-config', '/opt/workbench/phone-mcp.json');\n"
        "    let modelArg = conv.model;\n"
        "    if (IS_PHONE && process.env.CLAUDE_LEGACY === '1') {\n"
        "      modelArg = String(modelArg || '').replace(/\\[[^\\]]*\\]\\s*$/, '').trim();\n"
        "    }\n"
        "    if (modelArg) args.push('--model', modelArg);",
        "legacy model name + mcp",
    )

    # 余额：手机端直接问 DeepSeek，不再找桌宠 sidecar
    start = text.index("  if (p === '/api/balance' && req.method === 'GET') {")
    end = text.index("  if (p === '/api/remote' && req.method === 'GET') {")
    balance_phone = """  if (p === '/api/balance' && req.method === 'GET') {
    if (IS_PHONE) {
      const s = readClaudeSettings();
      const env = s.env || {};
      const key = String(env.ANTHROPIC_AUTH_TOKEN || env.ANTHROPIC_API_KEY || '').trim();
      const base = String(env.ANTHROPIC_BASE_URL || 'https://api.deepseek.com/anthropic').trim();
      if (!key) return json(res, { ok: false, error: '未配置 API Key' }, 400);
      if (!/deepseek\\.com/i.test(base)) {
        return json(res, { ok: false, error: '当前服务商不支持余额查询' }, 400);
      }
      try {
        const r = await fetch('https://api.deepseek.com/user/balance', {
          headers: { authorization: 'Bearer ' + key },
          signal: AbortSignal.timeout(15000)
        });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) {
          return json(res, { ok: false, error: (data.error && data.error.message) || ('HTTP ' + r.status) }, 502);
        }
        return json(res, {
          ok: true,
          isAvailable: data.is_available !== false,
          balances: (data.balance_infos || []).map(b => ({
            currency: b.currency,
            total_balance: b.total_balance,
            granted_balance: b.granted_balance,
            topped_up_balance: b.topped_up_balance
          }))
        });
      } catch (e) {
        return json(res, { ok: false, error: e.message || '查询余额失败' }, 502);
      }
    }
    const petBase = await findPetSidecar();
    if (!petBase) return json(res, { ok: false, error: '桌宠服务未启动' }, 503);
    try {
      // 页面首屏用缓存（不触发 DeepSeek 请求），只有点余额刷新时才 forced
      const refresh = url.searchParams.get('refresh') === '1' ? '1' : '0';
      const r = await fetch(petBase + '/api/balance?refresh=' + refresh + '&bubble=0', {
        signal: AbortSignal.timeout(15000)
      });
      const data = await r.json().catch(() => ({}));
      return json(res, data, r.ok ? 200 : 502);
    } catch (e) {
      return json(res, { ok: false, error: e.message || '查询余额失败' }, 502);
    }
  }

"""
    text = text[:start] + balance_phone + text[end:]

    # 远程开关接口：手机端直接返回不支持
    text = rep(
        text,
        "  if (p === '/api/remote' && req.method === 'GET') {\n    return json(res, {",
        "  if (p === '/api/remote' && req.method === 'GET') {\n"
        "    if (IS_PHONE) return json(res, { enabled: false, phone: true, port: SERVER_PORT, loopback: true, urls: [] });\n"
        "    return json(res, {",
        "remote get",
    )
    text = rep(
        text,
        "  if (p === '/api/remote' && req.method === 'POST') {\n    let body;",
        "  if (p === '/api/remote' && req.method === 'POST') {\n"
        "    if (IS_PHONE) return json(res, { ok: false, error: '手机端不支持远程访问' }, 400);\n"
        "    let body;",
        "remote post",
    )

    # 浏览器/文件管理器相关接口在手机端不可用
    text = rep(
        text,
        "  const token = requestToken(req, url);\n  if (token !== TOKEN) return json(res, { error: '未授权' }, 401);",
        "  const token = requestToken(req, url);\n"
        "  if (token !== TOKEN) return json(res, { error: '未授权' }, 401);\n\n"
        "  if (IS_PHONE && (p === '/api/browser/open' || p === '/api/browser/chrome-path' || p === '/api/reveal')) {\n"
        "    return json(res, { ok: false, error: '手机端不支持该操作' }, 501);\n"
        "  }\n"
        "  if (IS_PHONE && p === '/api/update/check') {\n"
        "    return json(res, { configured: false, available: false });\n"
        "  }",
        "phone unsupported apis",
    )

    # 手机端服务只监听 127.0.0.1，API 不再强制令牌；避免代理/ROM 导致误判。
    text = rep(
        text,
        "  if (token !== TOKEN) return json(res, { error: '未授权' }, 401);",
        "  if (!IS_PHONE && token !== TOKEN) return json(res, { error: '未授权' }, 401);",
        "phone api token",
    )
    text = rep(
        text,
        "const TOKEN = loadOrCreateToken();",
        "const TOKEN = loadOrCreateToken();\n"
        "if (IS_PHONE) console.log('[phone] server 2.4.6: IS_PHONE loopback bypass active');",
        "phone server version log",
    )

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "server.js").write_bytes(text.encode("utf-8"))
    print(f"[patch] {OUT / 'server.js'} ({len(text)} bytes)")


def build_public():
    dest = OUT / "public"
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(SRC_PUBLIC, dest)
    picker = ROOT / "phone" / "phone-folder-picker.js"
    if not picker.is_file():
        raise SystemExit("[picker] 找不到 phone/phone-folder-picker.js")
    shutil.copy2(picker, dest / "phone-folder-picker.js")

    # 只在手机端页面加「返回模式选择」浮钮；PC 版手机页保持原样。
    inject = """
<style>#httpsTip,#installTip{display:none!important}#phoneFolderPicker[hidden]{display:none!important}#sheet .path-row{display:none!important}</style>
<script>
(function () {
  function addHome() {
    var h = document.querySelector('.hd-left h1');
    if (h && !document.getElementById('phoneModeBadge')) {
      var badge = document.createElement('span');
      badge.id = 'phoneModeBadge';
      badge.textContent = '手机端';
      badge.style.cssText = 'margin-left:7px;padding:1px 6px;border-radius:6px;'
        + 'background:rgba(240,163,94,.15);color:#f0a35e;font-size:10px;font-weight:400;'
        + 'vertical-align:middle;';
      h.appendChild(badge);
    }
    var empty = document.querySelector('#folderList .empty');
    if (empty && empty.textContent.indexOf('输入路径') >= 0) {
      empty.textContent = '点下方「浏览手机文件夹」选择文件夹';
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addHome);
  else addHome();
  setInterval(addHome, 1500);
})();
</script>
<script src="/phone-folder-picker.js"></script>
"""
    for name in ("m.html", "m/index.html"):
        f = dest / name
        if not f.is_file():
            continue
        html = f.read_text(encoding="utf-8")
        if "workbench-phone" not in html:
            html = html.replace("<head>", '<head>\n<meta name="workbench-phone" content="1">', 1)
        if "phoneModeBadge" not in html:
            html = html.replace("</body>", inject + "</body>", 1)
        # 手机端不需要「电脑文件浏览器」入口；上传文件本身就是引用手机上的文件。
        html = re.sub(
            r'<button id="pickPcFile".*?</button>\s*',
            '',
            html,
            count=1,
            flags=re.S,
        )
        html = html.replace(
            "<span>上传文件（任意类型）</span>",
            "<span>引用手机上的文件</span>",
        )
        html = html.replace("引用电脑上的文件", "引用手机上的文件")
        # 资源版本参数：升级后 WebView 不会继续用缓存里的旧 m.js/m.css。
        html = re.sub(r'(href="/m\.css)(?:\?[^"]*)?(")',
                      rf'\1?v={ASSET_TAG}\2', html)
        html = re.sub(r'(src="/m\.js)(?:\?[^"]*)?(")',
                      rf'\1?v={ASSET_TAG}\2', html)
        # 必须用 LF，避免 Windows 上 write_text 把整个 m.js/m.html 变成 CRLF。
        f.write_bytes(html.encode("utf-8"))
    js = dest / "m.js"
    if js.is_file():
        text = js.read_text(encoding="utf-8")
        text = text.replace("引用电脑上的文件", "引用手机上的文件")
        old_handler = ("    $('#pickPcFile').onclick = () => {\n"
                       "      closeAttachSheet();\n"
                       "      openPcFileBrowser();\n"
                       "    };")
        new_handler = ("    if ($('#pickPcFile')) $('#pickPcFile').onclick = () => {\n"
                       "      closeAttachSheet();\n"
                       "      openPcFileBrowser();\n"
                       "    };")
        if old_handler in text:
            text = text.replace(old_handler, new_handler, 1)
        else:
            print("[warn] 找不到 pickPcFile 绑定，跳过")
        js.write_bytes(text.encode("utf-8"))
    print(f"[copy] {dest}")


def build_start():
    (OUT / "start.sh").write_bytes((
        "#!/bin/sh\n"
        "export HOME=/root\n"
        "export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
        "export LANG=C.UTF-8\n"
        "cd /opt/workbench\n"
        "exec node server.js\n"
    ).encode("utf-8"))
    print(f"[write] {OUT / 'start.sh'}")


def build_tsproxy():
    src = ROOT / "phone" / "tsproxy" / "tsproxy-linux-arm64"
    if not src.is_file():
        raise SystemExit("[tsproxy] 找不到 phone/tsproxy/tsproxy-linux-arm64，先运行 build-tsproxy.ps1")
    shutil.copy2(src, OUT / "tsproxy")
    print(f"[copy] {OUT / 'tsproxy'} ({src.stat().st_size} bytes)")


def build_mcp():
    src = ROOT / "phone" / "phone-mcp.js"
    if not src.is_file():
        raise SystemExit("[mcp] 找不到 phone/phone-mcp.js")
    (OUT / "phone-mcp.js").write_bytes(src.read_bytes())
    (OUT / "phone-mcp.json").write_bytes((
        """{
  "mcpServers": {
    "phone": {
      "type": "stdio",
      "command": "/usr/bin/node",
      "args": ["/opt/workbench/phone-mcp.js"]
    }
  }
}
"""
    ).encode("utf-8"))
    print(f"[write] {OUT / 'phone-mcp.js'} / phone-mcp.json")


def build_bundled_claude():
    """把离线安装包放进 APK；缺哪个就跳过哪个，缺失时仍走在线安装。"""
    if os.environ.get("SLIM") == "1":
        for name in ("claude-musl.tgz", "alpine-minirootfs-3.24.2-aarch64.tar.gz"):
            f = OUT / name
            if f.is_file():
                f.unlink()
        apkdir = OUT / "apk"
        if apkdir.exists():
            shutil.rmtree(apkdir)
        print("[slim] 跳过内置环境资源（适合已装过环境的设备升级）")
        return

    src = ROOT / "phone" / "claude-musl.tgz"
    if src.is_file():
        shutil.copy2(src, OUT / "claude-musl.tgz")
        print(f"[bundled] {OUT / 'claude-musl.tgz'} ({src.stat().st_size} bytes)")
    else:
        print("[bundled] 未找到 phone/claude-musl.tgz，跳过内置 Claude Code")

    rootfs = ROOT / "phone" / "alpine-minirootfs-3.24.2-aarch64.tar.gz"
    if rootfs.is_file():
        shutil.copy2(rootfs, OUT / rootfs.name)
        print(f"[bundled] {OUT / rootfs.name} ({rootfs.stat().st_size} bytes)")
    else:
        print("[bundled] 未找到 Alpine 根文件系统，首次安装会联网下载")

    apks = ROOT / "phone" / "offline-apk"
    dest = OUT / "apk"
    if apks.is_dir():
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(apks, dest)
        n = len(list(dest.glob("*.apk")))
        print(f"[bundled] {dest} ({n} 个 Alpine 包)")
    else:
        print("[bundled] 未找到 phone/offline-apk，工具和 Node 会联网安装")


if __name__ == "__main__":
    build_server()
    build_public()
    build_start()
    build_mcp()
    build_tsproxy()
    build_bundled_claude()

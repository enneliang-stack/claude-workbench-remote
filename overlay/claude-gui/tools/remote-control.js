'use strict';

/**
 * 远程访问开关（命令行版）
 *
 * 用法：
 *   node tools/remote-control.js on       开启远程访问并打印手机可打开的地址
 *   node tools/remote-control.js off      关闭远程访问，恢复只允许本机
 *   node tools/remote-control.js status   查看当前状态
 *
 * 实际工作仍在本机的 Claude Code 进程里跑；这里只切换工作台服务的监听地址。
 */

const fs = require('fs');
const path = require('path');
const http = require('http');
const os = require('os');

const ROOT = path.join(__dirname, '..');
const DATA = path.join(ROOT, 'data');

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
}

function readToken() {
  const data = readJson(path.join(DATA, 'gui-token.json'));
  return data && typeof data.token === 'string' ? data.token.trim() : '';
}

function readPort() {
  const runtime = readJson(path.join(DATA, 'runtime.json'));
  const port = runtime && Number(runtime.port);
  return Number.isInteger(port) && port > 0 ? port : Number(process.env.PORT || 4711);
}

function request(method, pathname, body) {
  return new Promise((resolve, reject) => {
    const payload = body == null ? null : Buffer.from(JSON.stringify(body), 'utf8');
    const req = http.request({
      host: '127.0.0.1',
      port: readPort(),
      path: pathname,
      method,
      timeout: 3000,
      headers: {
        'x-gui-token': readToken(),
        ...(payload ? { 'content-type': 'application/json', 'content-length': payload.length } : {})
      }
    }, res => {
      let out = '';
      res.setEncoding('utf8');
      res.on('data', d => { out += d; });
      res.on('end', () => {
        let data = null;
        try { data = JSON.parse(out); } catch { /* 保留原文用于报错 */ }
        resolve({ status: res.statusCode, data, raw: out });
      });
    });
    req.on('timeout', () => req.destroy(new Error('连接工作台超时')));
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function publicIPv6() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv6' || a.internal) continue;
      const addr = String(a.address || '').split('%')[0];
      if (!addr || addr === '::1') continue;
      if (addr.startsWith('fe80') || addr.startsWith('fd')) continue;
      return addr;
    }
  }
  return '';
}

function printIPv6Hint() {
  const ip = publicIPv6();
  if (ip) {
    console.log(`  公网 IPv6：${ip}`);
    console.log('  手机 5G 也有 IPv6 时，内置 Tailscale 会优先直连（比中继快很多）。');
  } else {
    console.log('  电脑没有公网 IPv6：手机 5G 只能走 Tailscale 中继，延迟较高。');
    console.log('  可运行 claude-remote\\pc\\开启IPv6直连.cmd 开启，然后重启远程访问。');
  }
}

function printUrls(data) {
  const urls = data && Array.isArray(data.urls) ? data.urls : [];
  if (!urls.length) {
    console.log('  没有检测到可用的局域网 IPv4 地址。请确认电脑已联网。');
    return;
  }
  for (const u of urls) {
    // 统一指向手机版；这个路径在新旧版本的服务器上都能用
    let link = u.url;
    try {
      const parsed = new URL(u.url);
      parsed.pathname = '/m/index.html';
      link = parsed.toString();
    } catch { /* 保持原样 */ }
    console.log(`  ${u.tailscale ? '[Tailscale] ' : ''}${u.name}: ${link}`);
  }
}

(async () => {
  // 不带参数时默认开启（双击 远程访问.cmd 即可），关闭请双击 关闭远程访问.cmd
  const cmd = String(process.argv[2] || 'on').toLowerCase();

  if (!readToken()) {
    console.error('没有找到 data\\gui-token.json。');
    console.error('请先关闭并重新启动一次工作台客户端，让新代码生成这个文件。');
    process.exitCode = 1;
    return;
  }

  try {
    if (cmd === 'on' || cmd === 'enable' || cmd === 'start') {
      const r = await request('POST', '/api/remote', { enabled: true });
      if (r.status === 401) throw new Error('令牌不匹配，客户端可能还在运行旧代码');
      if (r.status === 404) throw new Error('客户端还在运行旧代码，缺少 /api/remote 接口');
      const data = r.data;
      if (!data || !data.ok) throw new Error((data && data.error) || r.raw || '开启失败');
      console.log('远程访问已开启。手机打开下面任一地址（需与电脑在同一 Wi-Fi 或 Tailscale）：');
      printUrls(data);
      printIPv6Hint();
      console.log('');
      console.log('首次打开会写入登录 Cookie，之后可以收藏 /m 或添加到主屏幕。');
    } else if (cmd === 'off' || cmd === 'disable' || cmd === 'stop') {
      const r = await request('POST', '/api/remote', { enabled: false });
      if (r.status === 401) throw new Error('令牌不匹配，客户端可能还在运行旧代码');
      if (r.status === 404) throw new Error('客户端还在运行旧代码，缺少 /api/remote 接口');
      const data = r.data;
      if (!data || !data.ok) throw new Error((data && data.error) || r.raw || '关闭失败');
      console.log('远程访问已关闭，工作台恢复为只允许本机访问。');
    } else {
      const r = await request('GET', '/api/remote');
      if (r.status === 401) throw new Error('令牌不匹配，客户端可能还在运行旧代码');
      if (r.status === 404) throw new Error('客户端还在运行旧代码，缺少 /api/remote 接口');
      const data = r.data;
      if (!data) throw new Error(r.raw || '无法读取状态');
      console.log(`远程访问：${data.enabled ? '已开启' : '未开启'}`);
      if (data.enabled) {
        printUrls(data);
        printIPv6Hint();
      }
    }
  } catch (e) {
    console.error('操作失败：' + e.message);
    console.error('如果工作台正在运行旧代码，请关闭客户端后重新启动再试。');
    process.exitCode = 1;
  }
})();

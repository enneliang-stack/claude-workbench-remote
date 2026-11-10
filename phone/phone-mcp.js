#!/usr/bin/env node
'use strict';

/**
 * 手机控制 MCP 桥：Claude Code 通过 stdio 调用这里的 phone_* 工具，
 * 这里再转发到 App 的 127.0.0.1 控制接口。
 *
 * 注意：stdout 只能输出 MCP JSON-RPC；日志一律写 stderr。
 */
const http = require('http');
const fs = require('fs');
const readline = require('readline');

const CONFIG_FILE = process.env.PHONE_CONTROL_CONFIG || '/opt/workbench/data/phone-control.json';

function log(...args) {
  try { console.error('[phone-mcp]', ...args); } catch { /* ignore */ }
}

function send(msg) {
  try {
    process.stdout.write(JSON.stringify(msg) + '\n');
  } catch (e) {
    log('write failed', e.message);
  }
}

function loadConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
  } catch {
    return { enabled: false, port: 0, token: '' };
  }
}

function callApp(path, body) {
  const cfg = loadConfig();
  if (!cfg.enabled || !cfg.port || !cfg.token) {
    return Promise.resolve({
      ok: false,
      disabled: true,
      error: '手机控制未开启：请在 Claude 工作台 App →「设置与日志」→「手机控制」开启无障碍服务。'
    });
  }
  const data = Buffer.from(JSON.stringify(body || {}), 'utf8');
  return new Promise(resolve => {
    const req = http.request({
      host: '127.0.0.1',
      port: cfg.port,
      path,
      method: 'POST',
      headers: {
        'content-type': 'application/json; charset=utf-8',
        'content-length': data.length,
        authorization: 'Bearer ' + cfg.token
      }
    }, res => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', d => { text += d; });
      res.on('end', () => {
        try {
          resolve(JSON.parse(text));
        } catch {
          resolve({ ok: false, error: '手机控制返回无法解析：' + text.slice(0, 300) });
        }
      });
    });
    req.on('error', e => resolve({ ok: false, error: '手机控制连接失败：' + e.message }));
    req.setTimeout(20000, () => req.destroy(new Error('手机控制请求超时')));
    req.write(data);
    req.end();
  });
}

function textResult(text, isError) {
  return { content: [{ type: 'text', text: String(text == null ? '' : text) }], isError: !!isError };
}

function summarize(r) {
  if (r && r.ok) {
    if (r.png) return textResult('截图已获取（图片在结果里）', false);
    if (r.nodes) return textResult(JSON.stringify(r), false);
    return textResult(JSON.stringify(r), false);
  }
  const msg = (r && (r.error || r.message)) || '操作失败';
  return textResult(msg, true);
}

const TOOLS = [
  {
    name: 'phone_status',
    description: '检查手机控制是否已连接：返回无障碍状态、屏幕尺寸、当前前台包名。开始操作前可先调用。',
    inputSchema: { type: 'object', properties: {} }
  },
  {
    name: 'phone_ui',
    description: '读取当前安卓界面。返回可见控件的文字/描述/提示、是否可点击/可输入、屏幕坐标 bounds 和中心点 cx/cy。目标明确时不要每次整屏读取；只有找不到控件时再调用。',
    inputSchema: {
      type: 'object',
      properties: {
        max_nodes: { type: 'number', description: '最多返回多少个节点，默认 180，最大 600' }
      }
    }
  },
  {
    name: 'phone_tap',
    description: '按屏幕绝对坐标点按。坐标来自 phone_ui 的 bounds/cx/cy。',
    inputSchema: {
      type: 'object',
      properties: {
        x: { type: 'number' },
        y: { type: 'number' }
      },
      required: ['x', 'y']
    }
  },
  {
    name: 'phone_tap_text',
    description: '按屏幕上可见文字/描述查找并点击控件。优先用这个而不是坐标。密码、支付、转账、验证码等敏感文字会被拒绝并交还用户。',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: '要查找的文字或部分文字' },
        exact: { type: 'boolean', description: '是否要求完全匹配，默认 false' }
      },
      required: ['text']
    }
  },
  {
    name: 'phone_type',
    description: '向当前焦点输入框（或第一个输入框）写入文字。密码框、验证码框、支付相关输入框会被拒绝并交还用户。',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: '要输入的文字' }
      },
      required: ['text']
    }
  },
  {
    name: 'phone_swipe',
    description: '在屏幕上滑动，用于滚动列表或翻页。',
    inputSchema: {
      type: 'object',
      properties: {
        x1: { type: 'number' },
        y1: { type: 'number' },
        x2: { type: 'number' },
        y2: { type: 'number' },
        duration: { type: 'number', description: '毫秒，默认 350' }
      },
      required: ['x1', 'y1', 'x2', 'y2']
    }
  },
  {
    name: 'phone_global',
    description: '执行系统操作：back（返回）、home（桌面）、recents（最近任务）、notifications（通知栏）、quick_settings（快捷设置）。',
    inputSchema: {
      type: 'object',
      properties: {
        action: {
          type: 'string',
          enum: ['back', 'home', 'recents', 'notifications', 'quick_settings']
        }
      },
      required: ['action']
    }
  },
  {
    name: 'phone_open_app',
    description: '按 App 名称或包名打开一个已安装 App。可以用 phone_apps 先查列表。',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'App 名称（如“微信”）或包名（如 com.tencent.mm）' }
      },
      required: ['name']
    }
  },
  {
    name: 'phone_apps',
    description: '列出手机里可启动的 App（名称和包名），最多 200 个。',
    inputSchema: { type: 'object', properties: {} }
  },
  {
    name: 'phone_screenshot',
    description: '截取当前屏幕（Android 11+）。密码/支付等安全界面可能禁止截屏。图片会作为 image content 返回。',
    inputSchema: { type: 'object', properties: {} }
  }
];

const HANDLERS = {
  phone_status: () => callApp('/status'),
  phone_ui: a => callApp('/ui', { max_nodes: Number(a.max_nodes) || 180 }),
  phone_tap: a => callApp('/tap', { x: Number(a.x), y: Number(a.y) }),
  phone_tap_text: a => callApp('/tap_text', { text: String(a.text || ''), exact: !!a.exact }),
  phone_type: a => callApp('/type', { text: String(a.text || '') }),
  phone_swipe: a => callApp('/swipe', {
    x1: Number(a.x1), y1: Number(a.y1), x2: Number(a.x2), y2: Number(a.y2),
    duration: Number(a.duration || 350)
  }),
  phone_global: a => callApp('/global', { action: String(a.action || '') }),
  phone_open_app: a => callApp('/open_app', { name: String(a.name || '') }),
  phone_apps: () => callApp('/apps'),
  phone_screenshot: () => callApp('/screenshot')
};

async function handle(msg) {
  const { id, method, params } = msg || {};
  if (method === 'initialize') {
    const requested = params && params.protocolVersion;
    const known = ['2024-10-07', '2024-11-05', '2025-03-26', '2025-06-18'];
    const version = known.includes(requested) ? requested : '2024-11-05';
    return send({
      jsonrpc: '2.0',
      id,
      result: {
        protocolVersion: version,
        capabilities: { tools: {} },
        serverInfo: { name: 'phone', version: '1.0.0' }
      }
    });
  }
  if (method === 'notifications/initialized' || method === 'notifications/cancelled') return;
  if (method === 'ping') return send({ jsonrpc: '2.0', id, result: {} });
  if (method === 'tools/list') return send({ jsonrpc: '2.0', id, result: { tools: TOOLS } });
  if (method === 'tools/call') {
    const name = params && params.name;
    const args = (params && params.arguments) || {};
    const fn = HANDLERS[name];
    if (!fn) {
      return send({
        jsonrpc: '2.0', id,
        result: textResult('未知工具：' + name, true)
      });
    }
    try {
      const r = await fn(args);
      if (name === 'phone_screenshot' && r && r.ok && r.png) {
        return send({
          jsonrpc: '2.0', id,
          result: {
            content: [
              { type: 'image', data: r.png, mimeType: 'image/png' },
              { type: 'text', text: '截图已获取' }
            ],
            isError: false
          }
        });
      }
      return send({ jsonrpc: '2.0', id, result: summarize(r) });
    } catch (e) {
      return send({ jsonrpc: '2.0', id, result: textResult(String(e.message || e), true) });
    }
  }
  if (id !== undefined && id !== null) {
    return send({
      jsonrpc: '2.0', id,
      error: { code: -32601, message: 'Method not found: ' + method }
    });
  }
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', line => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let msg;
  try {
    msg = JSON.parse(trimmed);
  } catch (e) {
    log('bad json', trimmed.slice(0, 120));
    return;
  }
  handle(msg).catch(e => {
    log('handle failed', e && e.stack ? e.stack : e);
    if (msg && msg.id !== undefined) {
      send({ jsonrpc: '2.0', id: msg.id, error: { code: -32603, message: String(e && e.message || e) } });
    }
  });
});
rl.on('close', () => process.exit(0));

log('started, config=', CONFIG_FILE);

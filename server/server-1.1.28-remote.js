'use strict';

/**
 * Claude Code 工作台 —— 本地服务
 *
 * 直接驱动已安装的 claude CLI（走 stream-json 协议），复用 ~/.claude 里的会话记录，
 * 所以界面里看到的对话和终端里的 `claude --resume` 是同一份数据。
 * 不依赖任何 npm 包。
 */

const http = require('http');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { spawn, execFile } = require('child_process');

const ROOT = __dirname;
const PUBLIC_DIR = path.join(ROOT, 'public');
const DATA_DIR = path.join(ROOT, 'data');
const STATE_FILE = path.join(DATA_DIR, 'state.json');
const TURN_META_FILE = path.join(DATA_DIR, 'turn-meta.json');
const HOME = os.homedir();
const PROJECTS_DIR = path.join(HOME, '.claude', 'projects');
const IS_WIN = process.platform === 'win32';
const CHROME_BROWSER_JS = path.join(ROOT, 'chrome-browser.js');
const CHROME_CONFIG = path.join(DATA_DIR, 'chrome-path.json');
const BUNDLED_CLI_DIR = path.join(ROOT, 'tools', 'playwright-cli');
const PLAYWRIGHT_SKILL_DIR = path.join(ROOT, 'skills', 'playwright-cli');

// 安装包自带的 Playwright CLI 优先于系统里其他版本；Claude 子进程会继承这个 PATH，
// 这样技能里的 playwright-cli 命令和顶栏浏览器按钮用的是同一套 CLI。
(function setupBundledCli() {
  if (!fs.existsSync(path.join(BUNDLED_CLI_DIR, 'playwright-cli.cmd'))) return;
  const current = (process.env.PATH || '').split(path.delimiter).filter(Boolean);
  if (current.some(p => p.toLowerCase() === BUNDLED_CLI_DIR.toLowerCase())) return;
  process.env.PATH = [BUNDLED_CLI_DIR, ...current].join(path.delimiter);
})();

// 更新包把技能放在 claude-gui\skills 下；用户级技能目录没有或来源更新时复制过去，
// 朋友的机器第一次更新后就能在「技能与 MCP」里看到 playwright-cli。
function ensurePlaywrightSkill() {
  const srcSkill = path.join(PLAYWRIGHT_SKILL_DIR, 'SKILL.md');
  if (!fs.existsSync(srcSkill)) return;
  const destDir = path.join(HOME, '.claude', 'skills', 'playwright-cli');
  const destSkill = path.join(destDir, 'SKILL.md');
  try {
    const srcText = fs.readFileSync(srcSkill, 'utf8');
    const destText = fs.existsSync(destSkill) ? fs.readFileSync(destSkill, 'utf8') : '';
    if (srcText === destText) return;
    fs.mkdirSync(path.dirname(destDir), { recursive: true });
    fs.cpSync(PLAYWRIGHT_SKILL_DIR, destDir, { recursive: true, force: true });
    console.log('[skills] 已安装 playwright-cli 技能');
  } catch (e) {
    console.error('[skills] 安装 playwright-cli 技能失败：' + e.message);
  }
}

// 令牌持久化：远程页面/手机书签在客户端重启后仍然有效。
// 本地界面仍然通过 index.html 注入拿到它；非本机请求必须显式带令牌。
function loadOrCreateToken() {
  const file = path.join(DATA_DIR, 'gui-token.json');
  try {
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    const token = typeof data.token === 'string' ? data.token.trim() : '';
    if (token.length >= 16) return token;
  } catch { /* 首次运行或文件损坏时重新生成 */ }
  const token = crypto.randomBytes(16).toString('hex');
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(
      file,
      JSON.stringify({ token, createdAt: new Date().toISOString() }, null, 2) + '\n',
      'utf8'
    );
  } catch (e) { console.error('[token] 保存失败：' + e.message); }
  return token;
}

const TOKEN = loadOrCreateToken();

// 远程访问开关：默认关闭，只监听 127.0.0.1。
// 打开后监听 0.0.0.0，但非本机请求（静态文件也包含）一律要求令牌。
const REMOTE_FILE = path.join(DATA_DIR, 'remote.json');
const RUNTIME_FILE = path.join(DATA_DIR, 'runtime.json');

function readRemoteEnabled() {
  try {
    const data = JSON.parse(fs.readFileSync(REMOTE_FILE, 'utf8'));
    return data.enabled === true;
  } catch {
    return false;
  }
}

function writeRemoteEnabled(enabled) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(REMOTE_FILE, JSON.stringify({ enabled: !!enabled }, null, 2) + '\n', 'utf8');
  } catch (e) { console.error('[remote] 保存配置失败：' + e.message); }
}

let remoteEnabled = readRemoteEnabled();
const PERMISSION_MODES = ['bypassPermissions', 'auto', 'acceptEdits', 'plan'];
// Claude Code 的推理档位（--effort）。空字符串表示不传，跟随 settings.json 的 CLAUDE_CODE_EFFORT_LEVEL
const EFFORT_LEVELS = ['low', 'medium', 'high', 'xhigh', 'max'];

// 提问工具：次要问题在没人回答时多久按推荐项继续；
// 重要问题等用户，但长时间无人应答会安全取消这一轮，避免进程一直挂着
const ASK_AUTO_MS = 40000;
const ASK_MAJOR_MS = 30 * 60 * 1000;
const ASK_GUIDE = [
  '本客户端由用户在桌面上操作。除非会明显影响最终结果、涉及不可逆操作或存在方向性分歧，',
  '否则不要向用户提问，自行选择合理默认值并在结果里说明。',
  '必须使用 AskUserQuestion 时：把推荐选项放在第一个；在 header 开头标注【重要】',
  '（方向性/不可逆决策，必须等用户回答）或【次要】（小问题，用户未及时回答就按第一个选项继续）。',
  '只要本轮需要调用工具，就在第一次调用工具前用正文（用户可见）写一句不超过 20 字的中文说明要做什么；',
  '之后每完成一个关键阶段或准备收尾时再说一句，不要每个工具调用都说，也不要把进度只写进思考；',
  '不需要工具的纯问答不用写。子代理直接返回结果，不用写这类短话。'
].join('');

// 聊天图片（粘贴的截图）限制。Claude API 的 5MB 限制算的是 base64 之后的大小。
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
const MAX_IMAGES = 5;
const MAX_IMAGE_B64 = 5 * 1024 * 1024;
const MAX_TOTAL_IMAGE_B64 = 24 * 1024 * 1024;
const MAX_CHAT_BODY = 32 * 1024 * 1024;
const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;

// ---------------------------------------------------------------- 状态

const DEFAULT_STATE = {
  folders: [],            // [{ path, addedAt }]
  conversations: {},      // id -> { cwd, title, createdAt, updatedAt, model, permissionMode }
  settings: { defaultPermissionMode: 'bypassPermissions', defaultModel: '', defaultEffort: '' },
  cwdCache: {},           // 项目目录名 -> 真实 cwd
  lastConversation: null
};

let state = null;

function loadState() {
  try {
    const raw = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    return {
      ...DEFAULT_STATE,
      ...raw,
      settings: { ...DEFAULT_STATE.settings, ...(raw.settings || {}) },
      conversations: raw.conversations || {},
      folders: raw.folders || [],
      cwdCache: raw.cwdCache || {}
    };
  } catch {
    return JSON.parse(JSON.stringify(DEFAULT_STATE));
  }
}

// 每轮的耗时 / 用量 / 费用，按该轮最后一条 assistant 消息 id 记录，供历史回显
let turnMetaCache = null;
let turnMetaTimer = null;

function loadTurnMeta() {
  if (turnMetaCache) return turnMetaCache;
  try { turnMetaCache = JSON.parse(fs.readFileSync(TURN_META_FILE, 'utf8')) || {}; }
  catch { turnMetaCache = {}; }
  return turnMetaCache;
}

function saveTurnMetaSoon() {
  if (turnMetaTimer) return;
  turnMetaTimer = setTimeout(() => {
    turnMetaTimer = null;
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true });
      const tmp = TURN_META_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(turnMetaCache || {}), 'utf8');
      fs.renameSync(tmp, TURN_META_FILE);
    } catch (e) {
      console.error('[turn-meta] 保存失败', e.message);
    }
  }, 200);
}

function recordTurnMeta(convId, rec) {
  if (!convId || !rec || !rec.assistantId) return;
  const all = loadTurnMeta();
  all[rec.assistantId] = { convId, ...rec };
  const keys = Object.keys(all);
  if (keys.length > 3000) {
    for (const k of keys.slice(0, keys.length - 3000)) delete all[k];
  }
  saveTurnMetaSoon();
}

function dropTurnMeta(convId) {
  const all = loadTurnMeta();
  let changed = false;
  for (const k of Object.keys(all)) {
    if (all[k] && all[k].convId === convId) { delete all[k]; changed = true; }
  }
  if (changed) saveTurnMetaSoon();
}

let saveTimer = null;
function saveState() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true });
      const tmp = STATE_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(state, null, 2), 'utf8');
      fs.renameSync(tmp, STATE_FILE);
    } catch (e) {
      console.error('[state] 保存失败', e.message);
    }
  }, 250);
}

state = loadState();

// ---------------------------------------------------------------- 小工具

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8'
};

// 手机下载生成文件时用的常见类型
const DOWNLOAD_MIME = {
  '.apk': 'application/vnd.android.package-archive',
  '.zip': 'application/zip',
  '.pdf': 'application/pdf',
  '.md': 'text/markdown; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.log': 'text/plain; charset=utf-8',
  '.csv': 'text/csv; charset=utf-8',
  '.doc': 'application/msword',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xls': 'application/vnd.ms-excel',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  '.ppt': 'application/vnd.ms-powerpoint',
  '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  '.mp4': 'video/mp4',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav'
};

function json(res, obj, code = 200) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  });
  res.end(body);
}

function readJson(req, limit = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    let over = false;
    const chunks = [];
    req.on('data', c => {
      if (over) return;
      size += c.length;
      if (size > limit) {
        over = true;
        chunks.length = 0;   // 丢弃已收内容，继续把请求读完以便正常返回错误
      } else chunks.push(c);
    });
    req.on('end', () => {
      if (over) return reject(new Error('请求体过大'));
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {});
      } catch {
        reject(new Error('无效的 JSON'));
      }
    });
    req.on('error', reject);
  });
}

// ---------------------------------------------------------------- 远程访问

function isLoopback(req) {
  const addr = (req.socket && req.socket.remoteAddress) || '';
  return addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1';
}

function cookieValue(req, name) {
  const raw = req.headers.cookie || '';
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    if (part.slice(0, i).trim() !== name) continue;
    try { return decodeURIComponent(part.slice(i + 1).trim()); } catch { return ''; }
  }
  return '';
}

/** API / SSE / 静态资源共用的令牌来源：请求头、查询参数、登录后写入的 Cookie */
function requestToken(req, url) {
  return String(
    req.headers['x-gui-token'] ||
    (url && url.searchParams.get('token')) ||
    cookieValue(req, 'gui_token') ||
    ''
  );
}

/** 本机所有可被手机访问的 IPv4 地址（Tailscale 的 100.x 单独标出来） */
function localAddresses() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      out.push({
        name,
        address: a.address,
        tailscale: a.address.startsWith('100.'),
        url: `http://${a.address}:${SERVER_PORT}/`
      });
    }
  }
  return out;
}

function remoteUrls() {
  if (!remoteEnabled) return [];
  const token = encodeURIComponent(TOKEN);
  return localAddresses().map(a => ({
    ...a,
    // /m/index.html 在新旧版本的服务器上都能直接打开手机版
    url: `${a.url}m/index.html?token=${token}`,
    desktopUrl: `${a.url}?token=${token}`,
    loginUrl: a.url
  }));
}

function serveRemoteLogin(res, pathname, badToken) {
  const action = pathname.startsWith('/m') ? pathname : pathname === '/remote' ? '/remote' : '/';
  const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark">
<title>工作台 · 远程访问</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; min-height:100vh; display:grid; place-items:center; background:#0c0e11;
         color:#e8e6e3; font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; }
  form { width:min(92vw,360px); background:#14171c; border:1px solid #262b33; border-radius:14px;
         padding:24px; box-shadow:0 18px 50px rgba(0,0,0,.45); }
  h1 { font-size:17px; margin:0 0 6px; }
  p { margin:0 0 16px; color:#9aa3ad; font-size:13px; }
  .err { color:#e0766b; margin:0 0 12px; font-size:13px; }
  input { width:100%; box-sizing:border-box; padding:11px 12px; border-radius:9px; border:1px solid #303743;
          background:#0c0e11; color:#e8e6e3; font:inherit; }
  button { width:100%; margin-top:12px; padding:11px 12px; border:0; border-radius:9px; cursor:pointer;
           background:#e08a5f; color:#1b120c; font:600 15px/1 inherit; }
  code { color:#c9d1d9; word-break:break-all; }
</style></head>
<body><form method="get" action="${action}">
  <h1>远程访问工作台</h1>
  <p>输入电脑上 <code>claude-gui\\data\\gui-token.json</code> 里的令牌。</p>
  ${badToken ? '<p class="err">令牌不正确，请重新输入。</p>' : ''}
  <input name="token" type="password" autocomplete="current-password" placeholder="访问令牌" autofocus>
  <button type="submit">进入</button>
</form></body></html>`;
  res.writeHead(200, {
    'content-type': 'text/html; charset=utf-8',
    'cache-control': 'no-store',
    'x-frame-options': 'DENY',
    'referrer-policy': 'no-referrer'
  });
  res.end(html);
}

// 桌宠 sidecar 只监听 127.0.0.1，手机访问不到；由工作台服务代理余额查询
const PET_PORTS = Array.from({ length: 10 }, (_, i) => 4780 + i);

async function findPetSidecar() {
  for (const port of PET_PORTS) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/api/health`, {
        signal: AbortSignal.timeout(400)
      });
      if (!res.ok) continue;
      const data = await res.json();
      if (data && data.app === 'dsh-pet-bridge') return `http://127.0.0.1:${port}`;
    } catch { /* 试下一个端口 */ }
  }
  return '';
}

async function readdirSafe(dir) {
  try {
    return await fsp.readdir(dir, { withFileTypes: true });
  } catch {
    return [];
  }
}

function normKey(p) {
  return path.resolve(p).toLowerCase();
}

/** Claude Code 的项目目录编码：所有非字母数字字符 → "-" */
function encodeProjectDir(cwd) {
  return path.resolve(cwd).replace(/[^A-Za-z0-9]/g, '-');
}

function projectDirFor(cwd) {
  return path.join(PROJECTS_DIR, encodeProjectDir(cwd));
}

/** 累加一轮里每次 API 调用的 usage */
function addUsage(acc, u) {
  if (!u) return acc;
  const out = acc || {
    input_tokens: 0,
    output_tokens: 0,
    cache_creation_input_tokens: 0,
    cache_read_input_tokens: 0
  };
  out.input_tokens += u.input_tokens || 0;
  out.output_tokens += u.output_tokens || 0;
  out.cache_creation_input_tokens += u.cache_creation_input_tokens || 0;
  out.cache_read_input_tokens += u.cache_read_input_tokens || 0;
  return out;
}

function truncate(s, n) {
  s = String(s || '').replace(/\s+/g, ' ').trim();
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

function toolResultText(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  const parts = [];
  for (const b of content) {
    if (!b || typeof b !== 'object') continue;
    if (b.type === 'text') parts.push(b.text || '');
    else if (b.type === 'image') parts.push('[图片]');
  }
  return parts.join('\n');
}

/** 按文件头嗅探图片类型，防止声明的 media_type 与实际内容不符 */
function sniffImageType(buf) {
  if (buf.length >= 8 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4E && buf[3] === 0x47) return 'image/png';
  if (buf.length >= 3 && buf[0] === 0xFF && buf[1] === 0xD8 && buf[2] === 0xFF) return 'image/jpeg';
  if (buf.length >= 6 && buf.slice(0, 3).toString('latin1') === 'GIF') return 'image/gif';
  if (buf.length >= 12 && buf.slice(0, 4).toString('latin1') === 'RIFF' && buf.slice(8, 12).toString('latin1') === 'WEBP') return 'image/webp';
  return null;
}

/** 校验并规范化前端传来的图片数组：[{ media_type, data(base64) }] */
function validateImages(raw) {
  if (raw == null) return [];
  if (!Array.isArray(raw)) throw new Error('图片参数无效');
  if (raw.length > MAX_IMAGES) throw new Error(`最多 ${MAX_IMAGES} 张图片`);

  const out = [];
  let total = 0;
  for (const img of raw) {
    const media_type = String((img && img.media_type) || '');
    if (!IMAGE_TYPES.has(media_type)) throw new Error('不支持的图片类型');

    let data = String((img && img.data) || '');
    if (/^data:/i.test(data)) {
      const m = data.match(/^data:([^;,]+);base64,([\s\S]*)$/);
      if (!m || m[1] !== media_type) throw new Error('图片 data URL 无效');
      data = m[2];
    }
    data = data.replace(/\s+/g, '');
    if (!data || data.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) {
      throw new Error('图片数据无效');
    }
    if (data.length > MAX_IMAGE_B64) throw new Error('单张图片超过 5MB');
    total += data.length;
    if (total > MAX_TOTAL_IMAGE_B64) throw new Error('图片总大小过大');

    const buf = Buffer.from(data, 'base64');
    if (!buf.length || sniffImageType(buf) !== media_type) throw new Error('图片格式与声明不符');
    out.push({ media_type, data });
  }
  return out;
}

/** 组装发给 Claude 的 user 消息内容：图片在前，文字在后 */
function buildUserContent(text, images = []) {
  const content = [];
  for (const img of images) {
    content.push({
      type: 'image',
      source: { type: 'base64', media_type: img.media_type, data: img.data }
    });
  }
  if (text) content.push({ type: 'text', text });
  return content;
}

// ---------------------------------------------------------------- Claude CLI

let CLAUDE_BIN = IS_WIN ? 'claude.exe' : 'claude';
let CLAUDE_VERSION = '';
const sessionFileCache = new Map();

function resolveClaude() {
  return new Promise(resolve => {
    const finder = IS_WIN ? 'where' : 'which';
    execFile(finder, ['claude'], { windowsHide: true }, (err, stdout) => {
      if (!err && stdout.trim()) {
        const first = stdout.split(/\r?\n/).map(s => s.trim()).filter(Boolean)[0];
        if (first) CLAUDE_BIN = first;
      }
      execFile(CLAUDE_BIN, ['--version'], { windowsHide: true, shell: IS_WIN && /\.(cmd|bat)$/i.test(CLAUDE_BIN) }, (e2, out2) => {
        if (!e2) CLAUDE_VERSION = String(out2).trim();
        resolve();
      });
    });
  });
}

function spawnClaude(args, cwd) {
  const needsShell = IS_WIN && /\.(cmd|bat)$/i.test(CLAUDE_BIN);
  return spawn(CLAUDE_BIN, args, {
    cwd,
    windowsHide: true,
    shell: needsShell,
    env: {
      ...process.env,
      // 技能里用这个变量定位安装目录下的 chrome-browser.js，避免写死盘符。
      WORKBENCH_CHROME_BROWSER: CHROME_BROWSER_JS,
      // 让 CLI 在 900K 窗口做自动压缩（主要在轮次边界生效）。之前关掉是因为 1M 边缘
      // 压缩会用满 32k 输出预算，DeepSeek 会返回 400；降到 900K 后留出约 148K 余量。
      // 真正的轮内兜底在客户端：930K 停止本轮并手动压缩。
      CLAUDE_CODE_AUTO_COMPACT_WINDOW: '900000'
    },
    stdio: ['pipe', 'pipe', 'pipe']
  });
}

async function findSessionFile(id) {
  const cached = sessionFileCache.get(id);
  if (cached && fs.existsSync(cached)) return cached;

  // 先在已知文件夹里找
  for (const f of state.folders) {
    const p = path.join(projectDirFor(f.path), id + '.jsonl');
    if (fs.existsSync(p)) {
      sessionFileCache.set(id, p);
      return p;
    }
  }
  // 再全盘扫描项目目录
  for (const d of await readdirSafe(PROJECTS_DIR)) {
    if (!d.isDirectory()) continue;
    const p = path.join(PROJECTS_DIR, d.name, id + '.jsonl');
    if (fs.existsSync(p)) {
      sessionFileCache.set(id, p);
      return p;
    }
  }
  return null;
}

async function cwdOfProjectDir(dirName) {
  if (state.cwdCache[dirName]) return state.cwdCache[dirName];
  const dir = path.join(PROJECTS_DIR, dirName);
  for (const d of await readdirSafe(dir)) {
    if (!d.isFile() || !d.name.endsWith('.jsonl')) continue;
    const file = path.join(dir, d.name);
    let fd;
    try {
      fd = await fsp.open(file, 'r');
      const buf = Buffer.alloc(256 * 1024);
      const { bytesRead } = await fd.read(buf, 0, buf.length, 0);
      const text = buf.subarray(0, bytesRead).toString('utf8');
      for (const line of text.split('\n')) {
        if (!line.includes('"cwd"')) continue;
        try {
          const o = JSON.parse(line);
          if (o.cwd) {
            state.cwdCache[dirName] = o.cwd;
            saveState();
            return o.cwd;
          }
        } catch { /* 截断的行，跳过 */ }
      }
    } catch { /* ignore */ }
    finally { if (fd) await fd.close().catch(() => {}); }
  }
  return null;
}

// ---------------------------------------------------------------- 会话解析

const metaCache = new Map(); // file -> { stamp, size, offset, tail, state, meta, firstUser, hasAiTitle }
const metaJobs = new Map();  // file -> Promise（同一文件的增量解析串行化，避免重复应用追加内容）

// 会话元数据缓存持久化：启动时不用把几十 MB 的历史记录全部重读一遍。
// 文件只会在末尾追加，所以记录 offset/tail 后，后续只需要读新增的字节。
const META_CACHE_FILE = path.join(DATA_DIR, 'session-meta-cache.json');
// 解析规则变更时升版本，旧的磁盘缓存会自动失效并全量重扫
const META_CACHE_V = 2;
const META_TAIL_MAX = 64 * 1024;
let metaDisk = null;
let metaDiskTimer = null;

function loadMetaDisk() {
  if (metaDisk) return metaDisk;
  metaDisk = {};
  try {
    const raw = JSON.parse(fs.readFileSync(META_CACHE_FILE, 'utf8'));
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) metaDisk = raw;
  } catch { /* 首次运行或文件损坏，忽略 */ }
  return metaDisk;
}

function saveMetaDiskSoon() {
  if (metaDiskTimer) return;
  metaDiskTimer = setTimeout(() => {
    metaDiskTimer = null;
    try {
      const entries = Object.entries(metaDisk || {});
      const kept = entries.length > 400
        ? entries.sort((a, b) => (b[1].savedAt || 0) - (a[1].savedAt || 0)).slice(0, 400)
        : entries;
      const tmp = META_CACHE_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(Object.fromEntries(kept)), 'utf8');
      fs.renameSync(tmp, META_CACHE_FILE);
    } catch { /* 缓存写失败不影响主流程 */ }
  }, 1200);
  metaDiskTimer.unref?.();
}

function metaState() {
  return { title: null, preview: null, firstUser: null, userTurns: 0, createdAt: null };
}

function applyMetaLine(state, o) {
  if (!o || typeof o !== 'object') return;
  if (o.type === 'ai-title' && o.aiTitle) {
    state.title = o.aiTitle;
    return;
  }
  if (o.type === 'last-prompt' && o.lastPrompt) {
    state.preview = o.lastPrompt;
    return;
  }
  // 助手正文优先于用户提问作为侧栏预览：任务跑完后显示最终结论
  if (o.type === 'assistant') {
    if (!o.isSidechain) {
      const c = o.message && o.message.content;
      if (Array.isArray(c)) {
        const t = c.filter(b => b && b.type === 'text').map(b => b.text || '')
          .join('').replace(/\s+/g, ' ').trim();
        if (t) state.preview = t;
      }
    }
    return;
  }
  if (o.timestamp && !state.createdAt) state.createdAt = o.timestamp;
  if (o.type !== 'user' || o.isSidechain || o.isMeta) return;

  const c = o.message && o.message.content;
  let t = '';
  let hasImage = false;
  if (typeof c === 'string') t = c;
  else if (Array.isArray(c)) {
    if (c.some(b => b && b.type === 'tool_result')) return;
    t = c.filter(b => b && b.type === 'text').map(b => b.text || '').join('');
    hasImage = c.some(b => b && b.type === 'image');
  }
  if (!t.trim() && !hasImage) return;
  state.userTurns++;
  if (!state.firstUser) state.firstUser = t.trim() || '[图片]';
}

function applyMetaText(state, text) {
  for (const line of text.split('\n')) {
    if (!line) continue;
    let o;
    try { o = JSON.parse(line); } catch { continue; }
    applyMetaLine(state, o);
  }
}

/** 文件末尾可能没有换行；如果这半行本身已经是完整 JSON，就按旧逻辑解析掉 */
function consumeMetaTail(state, tail) {
  if (!tail) return { tail: '', bytes: 0 };
  try {
    applyMetaLine(state, JSON.parse(tail));
    return { tail: '', bytes: Buffer.byteLength(tail, 'utf8') };
  } catch {
    return { tail, bytes: 0 };
  }
}

function metaFromState(file, stat, state) {
  const firstUser = state.firstUser || '';
  return {
    meta: {
      id: path.basename(file, '.jsonl'),
      file,
      title: state.title || (firstUser ? truncate(firstUser, 40) : '新对话'),
      preview: truncate(state.preview || firstUser || '', 90),
      turns: state.userTurns,
      createdAt: state.createdAt || stat.birthtime.toISOString(),
      updatedAt: stat.mtime.toISOString(),
      size: stat.size
    },
    firstUser,
    hasAiTitle: !!state.title
  };
}

/** 只切出完整的行；末尾没写完的半行留给下一次追加时再拼 */
function splitCompleteLines(text) {
  const cut = text.lastIndexOf('\n');
  if (cut < 0) return { complete: '', tail: text };
  return { complete: text.slice(0, cut + 1), tail: text.slice(cut + 1) };
}

async function readRange(file, start, length) {
  if (length <= 0) return '';
  const fd = await fsp.open(file, 'r');
  try {
    const buf = Buffer.allocUnsafe(length);
    let off = 0;
    while (off < length) {
      const { bytesRead } = await fd.read(buf, off, length - off, start + off);
      if (!bytesRead) break;
      off += bytesRead;
    }
    return buf.subarray(0, off).toString('utf8');
  } finally {
    await fd.close().catch(() => {});
  }
}

function readSessionMeta(file, stat) {
  // 同一文件的读取串行化：增量解析依赖上一次的 offset，并发应用会重复计数
  const prev = metaJobs.get(file) || Promise.resolve();
  const job = prev.then(
    () => readSessionMetaNow(file, stat),
    () => readSessionMetaNow(file, stat)
  );
  metaJobs.set(file, job);
  return job.finally(() => {
    if (metaJobs.get(file) === job) metaJobs.delete(file);
  });
}

async function readSessionMetaNow(file, stat) {
  const stamp = `${stat.mtimeMs}:${stat.size}`;
  const cached = metaCache.get(file);
  if (cached && cached.stamp === stamp) return cached.meta;

  const disk = loadMetaDisk()[file];
  // 磁盘缓存带格式版本；以后解析规则变了直接升 v，旧缓存会自动失效
  const diskEntry = disk && disk.v === META_CACHE_V ? disk : null;
  let entry = cached || diskEntry;
  let state = null;
  let offset = 0;
  let tail = '';
  let append = false;

  if (entry && entry.state && typeof entry.offset === 'number' && typeof entry.size === 'number' &&
      (entry.tail || '').length <= META_TAIL_MAX) {
    if (entry.stamp === stamp) {
      const parsed = metaFromState(file, stat, entry.state);
      metaCache.set(file, {
        ...entry, meta: parsed.meta,
        firstUser: parsed.firstUser, hasAiTitle: parsed.hasAiTitle
      });
      return parsed.meta;
    }
    // 只处理“变长”的文件；同尺寸但 mtime 变化（被重写）时退回全量解析
    const tailBytes = Buffer.byteLength(entry.tail || '', 'utf8');
    if (stat.size > entry.size && entry.offset >= 0 && entry.offset + tailBytes <= stat.size) {
      state = entry.state;
      offset = entry.offset;
      tail = entry.tail || '';
      append = true;
    }
  }

  if (append) {
    // offset 指向 tail 的起点，tail 已经读过；只需要读 tail 之后新追加的字节
    const start = offset + Buffer.byteLength(tail, 'utf8');
    const delta = await readRange(file, start, stat.size - start);
    const split = splitCompleteLines(tail + delta);
    applyMetaText(state, split.complete);
    offset += Buffer.byteLength(split.complete, 'utf8');
    tail = split.tail;
  } else {
    const text = await fsp.readFile(file, 'utf8');
    const split = splitCompleteLines(text);
    state = metaState();
    applyMetaText(state, split.complete);
    offset = Buffer.byteLength(split.complete, 'utf8');
    tail = split.tail;
  }

  const consumed = consumeMetaTail(state, tail);
  tail = consumed.tail;
  offset += consumed.bytes;

  // 超长的半行（例如正在写入的大工具结果）不放进缓存；offset 置 -1，下次全量解析，
  // 避免把已经写完整的半行漏掉或重复计数。
  if (tail.length > META_TAIL_MAX) { tail = ''; offset = -1; }

  const parsed = metaFromState(file, stat, state);
  const rec = {
    stamp, size: stat.size, offset, tail, state,
    meta: parsed.meta, firstUser: parsed.firstUser, hasAiTitle: parsed.hasAiTitle,
    savedAt: Date.now()
  };
  metaCache.set(file, rec);
  loadMetaDisk()[file] = {
    v: META_CACHE_V, stamp, size: stat.size, offset, tail, state, savedAt: Date.now()
  };
  saveMetaDiskSoon();
  return parsed.meta;
}

/** 会话的第一条用户消息（用于生成标题），顺带判断 CLI 是否已经写过 ai-title */
async function sessionFirstPrompt(file) {
  try {
    const stat = await fsp.stat(file);
    await readSessionMeta(file, stat);
    const cached = metaCache.get(file) || {};
    return { firstUser: cached.firstUser || '', hasAiTitle: !!cached.hasAiTitle };
  } catch {
    return { firstUser: '', hasAiTitle: false };
  }
}

// ---------------------------------------------------------------- 自动标题
//
// GUI 走的是 `claude -p --input-format stream-json` 无头模式，这种模式下 Claude Code
// 不会生成终端里那种 ai-title（实测只会写 queue-operation）。这里用同一份模型配置
// 单独发一个很小的请求，把第一条用户消息概括成标题，存进 state 并广播给界面。

const titleJobs = new Map();      // convId -> Promise
const titleAttempts = new Map();  // convId -> 次数（失败/无内容时避免无限重试）
const titleSkipped = new Set();   // 已有 ai-title 的会话，本进程内不再检查
const TITLE_MAX_ATTEMPTS = 3;

/** 读取 ~/.claude/settings.json 里配置的模型 API，作为生成标题用的轻量请求通道 */
function titleApiConfig() {
  const s = readClaudeSettings();
  const env = { ...process.env, ...(s.env || {}) };
  const token = String(env.ANTHROPIC_AUTH_TOKEN || '').trim();
  const apiKey = String(env.ANTHROPIC_API_KEY || '').trim();
  const rawModel = String(
    env.ANTHROPIC_DEFAULT_HAIKU_MODEL || env.ANTHROPIC_SMALL_FAST_MODEL ||
    env.ANTHROPIC_MODEL || env.ANTHROPIC_DEFAULT_SONNET_MODEL || ''
  ).trim();
  if ((!token && !apiKey) || !rawModel || typeof fetch !== 'function') return null;

  // `deepseek-flash[1m]` 里的 [1m] 是 Claude Code 自己的上下文标记，真实模型名要剥掉
  const model = rawModel.replace(/\[[^\]]*\]\s*$/, '').trim();
  if (!model) return null;

  let base = String(env.ANTHROPIC_BASE_URL || '').trim().replace(/\/+$/, '');
  if (!base) base = 'https://api.anthropic.com';
  const url = /\/v1$/i.test(base) ? base + '/messages' : base + '/v1/messages';

  const headers = {
    'content-type': 'application/json',
    'anthropic-version': '2023-06-01'
  };
  if (token) headers.authorization = 'Bearer ' + token;
  else headers['x-api-key'] = apiKey;

  return { url, model, headers };
}

function buildTitlePrompt(text) {
  const clean = String(text || '').replace(/\s+/g, ' ').trim().slice(0, 1500);
  return [
    '请根据下面这段对话的第一条用户消息，起一个简短、具体的中文标题。',
    '要求：不超过 20 个字；概括主要任务或主题；不要使用引号、书名号或标点；',
    '不要“标题：”之类的前缀；只输出标题本身。',
    '',
    '第一条消息：',
    clean
  ].join('\n');
}

function sanitizeTitle(raw) {
  let t = String(raw || '').split(/\r?\n/).map(s => s.trim()).find(Boolean) || '';
  t = t.replace(/^好的[，,。.！!\s]*/, '');
  t = t.replace(/^(标题|题目|title)\s*[:：]\s*/i, '');
  t = t.replace(/^(标题|题目|title)\s*(?:是|为)\s*[:：]?\s*/i, '');
  t = t.replace(/^\*\*(.*)\*\*$/, '$1');
  t = t.replace(/^[ \t"'“”‘’《「『]+/, '').replace(/[ \t"'“”‘’》」』]+$/, '');
  t = t.replace(/[。．.!！?？；;，,、\s]+$/, '');
  t = t.replace(/\s+/g, ' ').trim();
  const chars = Array.from(t);
  if (chars.length > 40) t = chars.slice(0, 40).join('').trim();
  return t;
}

async function requestConversationTitle(text) {
  const cfg = titleApiConfig();
  if (!cfg) return '';
  const res = await fetch(cfg.url, {
    method: 'POST',
    headers: cfg.headers,
    body: JSON.stringify({
      model: cfg.model,
      max_tokens: 512,
      temperature: 0.3,
      system: '你负责给对话起标题，只输出标题本身，不要解释。',
      messages: [{ role: 'user', content: buildTitlePrompt(text) }]
    }),
    signal: AbortSignal.timeout(30000)
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    throw new Error(`HTTP ${res.status} ${detail.slice(0, 200)}`);
  }
  const data = await res.json();
  const out = Array.isArray(data.content)
    ? data.content.filter(b => b && b.type === 'text').map(b => b.text || '').join('')
    : '';
  return sanitizeTitle(out);
}

async function generateAutoTitle(convId, fallbackText) {
  let conv = state.conversations[convId];
  if (!conv || conv.title || titleSkipped.has(convId)) return null;
  if ((titleAttempts.get(convId) || 0) >= TITLE_MAX_ATTEMPTS) return null;

  let firstUser = String(fallbackText || '').trim();
  const file = await findSessionFile(convId);
  if (file) {
    const info = await sessionFirstPrompt(file);
    if (info.hasAiTitle) { titleSkipped.add(convId); return null; }
    if (info.firstUser.trim()) firstUser = info.firstUser.trim();
  }
  // 没有可概括的文字（纯图片 / 斜杠命令）时不浪费调用次数
  if (!firstUser || firstUser === '[图片]' || firstUser.startsWith('/')) return null;

  titleAttempts.set(convId, (titleAttempts.get(convId) || 0) + 1);
  const title = await requestConversationTitle(firstUser);
  if (!title) return null;

  conv = state.conversations[convId];
  if (!conv || conv.title) return null;  // 生成期间被重命名了
  conv.title = title;
  conv.titleGenerated = true;
  saveState();
  const idx = convIndex.get(convId);
  if (idx) idx.title = title;
  broadcast({
    t: 'conv_meta', id: convId, title, permissionMode: conv.permissionMode,
    model: conv.model, effort: conv.effort || ''
  });
  console.log('[title]', convId.slice(0, 8), '→', title);
  return title;
}

function scheduleAutoTitle(convId, fallbackText) {
  if (!convId || !state.conversations[convId]) return null;
  const running = titleJobs.get(convId);
  if (running) return running;
  const job = generateAutoTitle(convId, fallbackText)
    .catch(e => { console.error('[title] 生成失败：', e.message); return null; })
    .finally(() => titleJobs.delete(convId));
  titleJobs.set(convId, job);
  return job;
}

/** 启动后给已有的、还没有标题的会话补上标题（只补界面上可见的这些） */
async function backfillTitles() {
  const ids = Object.entries(state.conversations)
    .filter(([, c]) => c && !c.title)
    .map(([id]) => id)
    .slice(0, 20);
  for (const id of ids) {
    try { await scheduleAutoTitle(id, null); } catch { /* ignore */ }
    await new Promise(r => setTimeout(r, 400));
  }
}

/**
 * 真正的压缩摘要只在开头出现；引用其它对话的消息里可能夹带旧摘要，
 * 不能让整条用户消息被误判成摘要隐藏掉。
 */
function looksLikeCompactSummaryText(text) {
  const s = String(text || '');
  if (/<gui-reference-context>/i.test(s)) return false;
  return /^\s*(?:This session is being continued|The summary below covers)/i.test(s);
}

/** 把一个会话文件解析成前端可直接渲染的消息数组 */
function parseTranscript(text) {
  const messages = [];
  const assistantById = new Map();
  const toolBlocks = new Map();
  const steerSeen = new Set();
  let lastAssistant = null;
  // 顺带统计压缩后的上下文占用，避免为了这个数字再完整解析一遍 JSONL
  let ctxTokens = 0;
  let compacted = false;

  for (const line of text.split('\n')) {
    if (!line) continue;
    let o;
    try { o = JSON.parse(line); } catch { continue; }

    if (o.type === 'system' && o.subtype === 'compact_boundary') {
      const cm = o.compactMetadata || o.compact_metadata || {};
      ctxTokens = cm.postTokens || cm.post_tokens || 0;
      compacted = true;
    } else if (compacted && o.type === 'assistant' && o.message && o.message.usage) {
      const u = o.message.usage;
      const t = (u.input_tokens || 0) + (u.cache_creation_input_tokens || 0) + (u.cache_read_input_tokens || 0);
      if (t) ctxTokens = t;
    }

    if (o.isSidechain) continue;

    // 运行中插话：Claude Code 会把它存成 queued_command 附件，而不是普通 user 消息
    if (o.type === 'attachment' && o.attachment && o.attachment.type === 'queued_command') {
      const a = o.attachment;
      // 后台任务完成通知是 CLI 注入的系统消息（[SYSTEM NOTIFICATION - NOT USER INPUT]），
      // 不是用户插话，界面上不要显示。
      if (a.commandMode === 'task-notification' ||
          (a.origin && a.origin.kind === 'task-notification') ||
          (typeof a.prompt === 'string' && /^\s*<task-notification\b/i.test(a.prompt))) {
        continue;
      }

      const key = a.delivery_id || o.uuid;
      if (key && steerSeen.has(key)) continue;
      if (key) steerSeen.add(key);

      const prompt = a.prompt;
      let textPart = '';
      const images = [];
      if (typeof prompt === 'string') {
        textPart = prompt;
      } else if (Array.isArray(prompt)) {
        for (const b of prompt) {
          if (!b) continue;
          if (b.type === 'text') {
            textPart += b.text || '';
          } else if (b.type === 'image' && b.source && b.source.type === 'base64') {
            const mt = b.source.media_type;
            const data = b.source.data;
            if (IMAGE_TYPES.has(mt) && typeof data === 'string' && data.length <= MAX_IMAGE_B64) {
              images.push({ media_type: mt, data });
            }
          }
        }
      }

      if (!textPart.trim() && !images.length) continue;
      lastAssistant = null;
      messages.push({
        id: o.uuid || key,
        role: 'user',
        text: textPart,
        images: images.length ? images : undefined,
        steer: true,
        ts: o.timestamp || o.attachment.timestamp
      });
      continue;
    }

    if (o.type === 'user' && !o.isMeta) {
      const c = o.message && o.message.content;
      let textPart = '';
      let sawToolResult = false;
      const images = [];

      if (typeof c === 'string') {
        textPart = c;
      } else if (Array.isArray(c)) {
        for (const b of c) {
          if (!b) continue;
          if (b.type === 'tool_result') {
            sawToolResult = true;
            const blk = toolBlocks.get(b.tool_use_id);
            if (blk) {
              blk.result = truncate(toolResultText(b.content), 20000);
              blk.isError = !!b.is_error;
            }
          } else if (b.type === 'text') {
            textPart += b.text || '';
          } else if (b.type === 'image' && b.source && b.source.type === 'base64') {
            const mt = b.source.media_type;
            const data = b.source.data;
            if (IMAGE_TYPES.has(mt) && typeof data === 'string' && data.length <= MAX_IMAGE_B64) {
              images.push({ media_type: mt, data });
            }
          }
        }
      }

      if (sawToolResult && !textPart.trim() && !images.length) continue;
      if (!textPart.trim() && !images.length) continue;
      if (/^<(system-reminder|command-name|local-command)/.test(textPart.trim())) continue;
      if (o.origin && o.origin.kind && o.origin.kind !== 'human') continue;

      lastAssistant = null;
      const compactSummary = !/<gui-reference-context>/i.test(textPart) &&
        (!!o.isCompactSummary || looksLikeCompactSummaryText(textPart));
      messages.push({
        id: o.uuid, role: 'user', text: textPart,
        images: images.length ? images : undefined,
        ts: o.timestamp,
        compactSummary: compactSummary || undefined
      });
      continue;
    }

    if (o.type === 'assistant' && o.message) {
      const msg = o.message;
      const id = msg.id || o.uuid;
      let entry = assistantById.get(id);
      if (!entry) {
        entry = { id, role: 'assistant', blocks: [], ts: o.timestamp };
        assistantById.set(id, entry);
        messages.push(entry);
        lastAssistant = entry;
      }
      if (msg.usage) entry.usage = msg.usage;
      const content = Array.isArray(msg.content) ? msg.content : [];
      for (const b of content) {
        if (!b) continue;
        if (b.type === 'text') {
          if (!b.text) continue;
          const existing = entry.blocks.find(x => x.type === 'text' && x.text === b.text);
          if (!existing) entry.blocks.push({ type: 'text', text: b.text });
        } else if (b.type === 'thinking') {
          if (!b.thinking) continue;
          if (entry.blocks.some(x => x.type === 'thinking' && x.thinking === b.thinking)) continue;
          entry.blocks.push({ type: 'thinking', thinking: b.thinking });
        } else if (b.type === 'tool_use') {
          if (entry.blocks.some(x => x.type === 'tool_use' && x.id === b.id)) continue;
          const blk = { type: 'tool_use', id: b.id, name: b.name, input: b.input || {}, result: null, isError: false };
          entry.blocks.push(blk);
          toolBlocks.set(b.id, blk);
        }
      }
    }
  }

  for (const m of messages) {
    if (m.role === 'assistant') {
      m.blocks = m.blocks.filter(b => b.type !== 'text' || b.text.trim());
      if (!m.blocks.length) m.blocks.push({ type: 'text', text: '（无内容）' });
    }
  }
  return {
    messages: messages.filter(m => m.role === 'user' || m.blocks.length),
    contextTokens: ctxTokens,
    compacted
  };
}

/**
 * “引用/交接”用的精简上下文：最近一次压缩摘要 + 最近几十条消息。
 * 只回传文本和工具标签，不带工具结果，避免把几十 MB 长转录整个发给浏览器。
 */
function handoffMessages(messages, maxMessages = 40, maxChars = 20000) {
  const clip = (s, n) => {
    const t = String(s == null ? '' : s);
    return t.length > n ? t.slice(0, Math.max(0, n - 1)) + '…' : t;
  };
  const out = [];
  let total = 0;
  let summary = null;
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role === 'user' && looksLikeCompactSummaryText(m.text)) {
      summary = m;
      break;
    }
  }
  if (summary) {
    const text = clip(summary.text, 8000);
    out.push({ role: 'user', text, ts: summary.ts, compactSummary: true });
    total += text.length;
  }
  const tail = messages.slice(Math.max(0, messages.length - maxMessages));
  for (const m of tail) {
    if (m === summary) continue;
    if (m.role === 'user') {
      const text = clip(m.text, 3000);
      if (!text.trim()) continue;
      if (total + text.length > maxChars) break;
      out.push({ role: 'user', text, ts: m.ts });
      total += text.length;
    } else if (m.role === 'assistant') {
      const blocks = [];
      let text = '';
      for (const b of m.blocks || []) {
        if (!b) continue;
        if (b.type === 'text' && b.text && b.text.trim()) {
          text += (text ? '\n' : '') + b.text.trim();
        } else if (b.type === 'tool_use' && b.name) {
          const inp = b.input || {};
          const pick = inp.file_path || inp.path || inp.notebook_path || inp.pattern ||
            inp.command || inp.url || inp.query || inp.description || '';
          const arg = typeof pick === 'string'
            ? pick.replace(/\s+/g, ' ').trim().slice(0, 120)
            : '';
          blocks.push({ type: 'tool_use', name: b.name, input: { description: arg || b.name } });
        }
      }
      const t = clip(text, 3000);
      if (t) blocks.unshift({ type: 'text', text: t });
      if (!blocks.length) continue;
      if (total + t.length > maxChars) break;
      out.push({ role: 'assistant', blocks, ts: m.ts });
      total += t.length;
    }
  }
  return out;
}

// 解析结果按文件 mtime/size 缓存：手机分页时不用每次重新解析整份长转录
const transcriptCache = new Map();
const TRANSCRIPT_CACHE_MAX = 4;

async function readTranscript(file) {
  let stat = null;
  try { stat = await fsp.stat(file); } catch { /* 文件可能刚被删除 */ }

  let parsed = null;
  if (stat) {
    const cached = transcriptCache.get(file);
    if (cached && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size) {
      parsed = cached.parsed;
      transcriptCache.delete(file);
      transcriptCache.set(file, cached);
    }
  }

  if (!parsed) {
    const text = await fsp.readFile(file, 'utf8');
    parsed = parseTranscript(text);
    if (stat) {
      transcriptCache.set(file, { mtimeMs: stat.mtimeMs, size: stat.size, parsed });
      while (transcriptCache.size > TRANSCRIPT_CACHE_MAX) {
        transcriptCache.delete(transcriptCache.keys().next().value);
      }
    }
  }

  const all = loadTurnMeta();
  for (const m of parsed.messages) {
    if (m.role !== 'assistant') continue;
    const rec = all[m.id];
    if (rec) {
      m.meta = { duration: rec.duration, costUsd: rec.costUsd, usage: rec.usage, ts: rec.ts };
    } else {
      delete m.meta;
    }
  }
  return {
    messages: parsed.messages,
    contextTokens: parsed.contextTokens,
    compacted: parsed.compacted
  };
}

function trimText(v, n) {
  return typeof v === 'string' && v.length > n ? v.slice(0, n) + '\n…' : v;
}

function lightInput(input) {
  if (!input || typeof input !== 'object') return input;
  const out = { ...input };
  for (const k of ['content', 'new_string', 'old_string', 'prompt', 'task', 'text', 'description']) {
    if (typeof out[k] === 'string' && out[k].length > 2000) {
      out[k] = out[k].slice(0, 2000) + '\n…';
    }
  }
  if (Array.isArray(out.edits)) {
    out.edits = out.edits.map(e => e && typeof e === 'object'
      ? { ...e, old_string: trimText(e.old_string, 2000), new_string: trimText(e.new_string, 2000) }
      : e);
  }
  return out;
}

/** 手机端只需要看摘要：截断工具结果/思考/大段输入，减少长对话加载量 */
function lightenMessages(messages) {
  return messages.map(m => {
    if (!m || m.role !== 'assistant' || !Array.isArray(m.blocks)) return m;
    return {
      ...m,
      blocks: m.blocks.map(b => {
        if (!b) return b;
        if (b.type === 'tool_use') {
          return { ...b, input: lightInput(b.input), result: trimText(b.result, 2000) };
        }
        if (b.type === 'thinking') {
          return { ...b, thinking: trimText(b.thinking, 4000), text: trimText(b.text, 4000) };
        }
        return b;
      })
    };
  });
}

// ---------------------------------------------------------------- 技能 / MCP 管理

const CLAUDE_DIR = path.join(HOME, '.claude');
const USER_SKILLS_DIR = path.join(CLAUDE_DIR, 'skills');
const SKILL_TRASH_DIR = path.join(CLAUDE_DIR, 'skills-trash');
const CLAUDE_JSON_FILE = path.join(HOME, '.claude.json');

function readJsonFile(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
}

function readVersion() {
  const v = readJsonFile(path.join(ROOT, 'version.json'));
  return v && typeof v.version === 'string' ? v.version : '';
}

// ---------------------------------------------------------------- 客户端内更新检查

// 客户端自己的 update-config.json 优先；老版本安装目录的 tools\update-config.json 兜底
const UPDATE_CONFIG_FILES = [
  path.join(ROOT, 'update-config.json'),
  path.join(ROOT, '..', 'tools', 'update-config.json')
];
let updateCheckCache = { at: 0, key: '', data: null };

function resolveUpdateManifest(raw, cfgPath) {
  const s = String(raw || '').trim();
  if (!s) return '';
  if (/^(https?|file):\/\//i.test(s)) return s;
  if (path.isAbsolute(s)) return s;
  return path.resolve(cfgPath ? path.dirname(cfgPath) : ROOT, s);
}

function findUpdateManifestConfig() {
  const envUrl = String(process.env.WORKBENCH_MANIFEST_URL || '').trim();
  if (envUrl) return { url: resolveUpdateManifest(envUrl, ''), configPath: '' };
  for (const p of UPDATE_CONFIG_FILES) {
    const cfg = readJsonFile(p);
    const raw = cfg && typeof cfg.manifestUrl === 'string' ? cfg.manifestUrl : '';
    if (String(raw).trim()) return { url: resolveUpdateManifest(raw, p), configPath: p };
  }
  return { url: '', configPath: '' };
}

function compareVersionStrings(a, b) {
  const pa = String(a || '').split('.').map(x => parseInt(x, 10) || 0);
  const pb = String(b || '').split('.').map(x => parseInt(x, 10) || 0);
  const n = Math.max(pa.length, pb.length);
  for (let i = 0; i < n; i++) {
    const x = pa[i] || 0;
    const y = pb[i] || 0;
    if (x !== y) return x - y;
  }
  return 0;
}

async function readUpdateManifest(src) {
  if (/^https?:\/\//i.test(src)) {
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), 8000);
    try {
      const res = await fetch(src, {
        signal: ac.signal,
        redirect: 'follow',
        headers: { 'cache-control': 'no-cache' }
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return await res.text();
    } finally {
      clearTimeout(timer);
    }
  }
  let p = src;
  if (/^file:\/\//i.test(p)) p = require('url').fileURLToPath(p);
  return await fsp.readFile(p, 'utf8');
}

function buildUpdateChanges(manifest, current, remote) {
  const out = [];
  const seen = new Set();
  const add = (version, notes, date) => {
    const v = String(version || '').trim();
    if (!v || seen.has(v)) return;
    if (compareVersionStrings(v, current) <= 0) return;
    if (compareVersionStrings(v, remote) > 0) return;
    seen.add(v);
    out.push({
      version: v,
      date: String(date || '').slice(0, 40),
      notes: String(notes || '').slice(0, 2000)
    });
  };

  add(remote, manifest.notes, manifest.publishedAt);
  if (Array.isArray(manifest.releases)) {
    for (const r of manifest.releases) {
      if (r && typeof r === 'object') add(r.version, r.notes, r.date || r.publishedAt);
    }
  }
  out.sort((a, b) => compareVersionStrings(b.version, a.version));
  return out.slice(0, 20);
}

async function getUpdateStatus(force = false) {
  const current = readVersion();
  const cfg = findUpdateManifestConfig();
  if (!cfg.url) return { configured: false, available: false, current: current };
  if (!force && updateCheckCache.data && updateCheckCache.key === cfg.url &&
      Date.now() - updateCheckCache.at < 60000) {
    return updateCheckCache.data;
  }

  const text = await readUpdateManifest(cfg.url);
  let manifest;
  try { manifest = JSON.parse(text); } catch {
    throw new Error('更新清单不是合法 JSON');
  }
  if (manifest.app && String(manifest.app) !== 'claude-workbench') {
    throw new Error('更新清单的 app 不匹配');
  }
  const remote = String(manifest.version || '').trim();
  if (!remote) throw new Error('更新清单缺少 version');

  const patch = manifest.patch && manifest.patch.url ? manifest.patch : null;
  const full = manifest.full && manifest.full.url ? manifest.full : null;
  const data = {
    configured: true,
    available: compareVersionStrings(current, remote) < 0 && !!(patch || full),
    current: current,
    version: remote,
    notes: String(manifest.notes || '').slice(0, 4000),
    changes: buildUpdateChanges(manifest, current, remote),
    manifestUrl: cfg.url,
    size: Number((patch && patch.size) || (full && full.size) || 0) || 0,
    kind: patch ? 'patch' : (full ? 'full' : '')
  };
  updateCheckCache = { at: Date.now(), key: cfg.url, data: data };
  return data;
}

function projectKeyFor(cfg, cwd) {
  if (!cfg || !cfg.projects || !cwd) return null;
  const want = normKey(cwd);
  for (const k of Object.keys(cfg.projects)) {
    if (normKey(k) === want) return k;
  }
  return null;
}

async function pathSize(p, budget = { n: 0 }) {
  if (budget.n > 4000) return 0;
  try {
    const st = await fsp.lstat(p);
    if (st.isSymbolicLink()) return 0;
    if (st.isFile()) { budget.n++; return st.size; }
    if (!st.isDirectory()) return 0;
    let total = 0;
    for (const name of await fsp.readdir(p)) {
      budget.n++;
      total += await pathSize(path.join(p, name), budget);
      if (budget.n > 4000) break;
    }
    return total;
  } catch { return 0; }
}

function parseSkillFrontmatter(text) {
  const out = {};
  const m = /^---\s*\r?\n([\s\S]*?)\r?\n---/.exec(text || '');
  if (!m) return out;
  const lines = m[1].split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const mm = /^([A-Za-z0-9_-]+)\s*:\s*(.*)$/.exec(lines[i]);
    if (!mm) continue;
    const key = mm[1];
    let val = mm[2].trim();
    if (/^[>|][+-]?$/.test(val)) {
      const parts = [];
      while (i + 1 < lines.length && /^\s+/.test(lines[i + 1])) {
        parts.push(lines[++i].trim());
      }
      val = parts.join(' ');
    }
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    out[key] = val;
  }
  return out;
}

function skillRootsFor(cwdList) {
  const roots = [{ dir: USER_SKILLS_DIR, scope: 'user', project: null }];
  for (const cwd of cwdList || []) {
    roots.push({ dir: path.join(cwd, '.claude', 'skills'), scope: 'project', project: cwd });
  }
  return roots;
}

async function readSkillEntry(p, ent, root) {
  const isDir = ent.isDirectory();
  const isMd = ent.isFile() && /\.md$/i.test(ent.name);
  if (!isDir && !isMd) return null;
  if (ent.name.startsWith('.')) return null;

  let text = '';
  let main = p;
  try {
    if (isDir) {
      main = path.join(p, 'SKILL.md');
      text = await fsp.readFile(main, 'utf8');
    } else {
      text = await fsp.readFile(p, 'utf8');
    }
  } catch { /* 目录里没有 SKILL.md 也列出来，标记为无效 */ }

  // 技能目录里的 SKILL.md 才算；单独的 .md 只有带 name/description frontmatter 才认，避免把 README 当技能
  if (isMd && !/^---\s*\r?\n[\s\S]*?\b(?:name|description)\s*:/i.test(text)) return null;

  const fm = parseSkillFrontmatter(text);
  const body = text.replace(/^---\s*\r?\n[\s\S]*?\r?\n---\s*\r?\n?/, '');
  const fallbackDesc = body.split(/\r?\n/).map(s => s.trim())
    .find(s => s && !s.startsWith('#') && !s.startsWith('<!--')) || '';
  let stat = null;
  try { stat = await fsp.lstat(p); } catch { /* ignore */ }

  return {
    name: String(fm.name || path.basename(p, path.extname(p)) || ent.name),
    description: truncate(String(fm.description || fallbackDesc || ''), 240),
    path: p,
    mainFile: main,
    hasSkillMd: isDir ? !!text : true,
    scope: root.scope,
    project: root.project || null,
    size: await pathSize(p),
    updatedAt: stat && stat.mtime ? stat.mtime.toISOString() : null,
    removable: true
  };
}

async function listSkills(cwdList) {
  const roots = skillRootsFor(cwdList);
  const seen = new Set();
  const skills = [];
  for (const root of roots) {
    if (!fs.existsSync(root.dir)) continue;
    const entries = await readdirSafe(root.dir);
    for (const ent of entries) {
      const p = path.join(root.dir, ent.name);
      const key = normKey(p);
      if (seen.has(key)) continue;
      const item = await readSkillEntry(p, ent, root);
      if (item) { seen.add(key); skills.push(item); }
    }
  }
  skills.sort((a, b) => {
    if (a.scope !== b.scope) return a.scope === 'user' ? -1 : 1;
    return a.name.localeCompare(b.name, 'zh-CN');
  });
  return skills;
}

function mcpTarget(entry) {
  const e = entry && typeof entry === 'object' ? entry : {};
  const type = String(e.type || e.transport || (e.url ? 'http' : 'stdio')).toLowerCase();
  let target = '';
  if (typeof e.url === 'string' && e.url) {
    try {
      const u = new URL(e.url);
      target = u.protocol + '//' + u.host + u.pathname;
    } catch {
      target = '[URL]';
    }
  } else if (typeof e.command === 'string' && e.command) {
    target = path.basename(e.command);
  }
  return { type, target };
}

async function listMcpServers(cwdList) {
  const cfg = readJsonFile(CLAUDE_JSON_FILE);
  const out = [];
  const add = (name, entry, scope, source, cwd, enabled) => {
    const t = mcpTarget(entry);
    out.push({
      name: String(name),
      scope,
      source,
      cwd: cwd || null,
      type: t.type,
      target: t.target,
      enabled
    });
  };

  if (cfg && cfg.mcpServers && typeof cfg.mcpServers === 'object') {
    for (const [name, entry] of Object.entries(cfg.mcpServers)) {
      add(name, entry, 'user', '~/.claude.json', null, true);
    }
  }

  for (const cwd of cwdList || []) {
    const key = projectKeyFor(cfg, cwd);
    const proj = key && cfg.projects ? cfg.projects[key] : null;

    if (proj && proj.mcpServers && typeof proj.mcpServers === 'object') {
      for (const [name, entry] of Object.entries(proj.mcpServers)) {
        add(name, entry, 'local', '~/.claude.json（项目）', cwd, true);
      }
    }

    const mcpJson = path.join(cwd, '.mcp.json');
    if (fs.existsSync(mcpJson)) {
      const m = readJsonFile(mcpJson);
      const servers = m && m.mcpServers && typeof m.mcpServers === 'object' ? m.mcpServers : null;
      if (servers) {
        const disabled = new Set(proj && Array.isArray(proj.disabledMcpjsonServers) ? proj.disabledMcpjsonServers : []);
        const approved = new Set(proj && Array.isArray(proj.enabledMcpjsonServers) ? proj.enabledMcpjsonServers : []);
        for (const [name, entry] of Object.entries(servers)) {
          add(name, entry, 'project', mcpJson, cwd, disabled.has(name) ? false : (approved.has(name) ? true : null));
        }
      }
    }
  }

  const seen = new Set();
  return out.filter(m => {
    const key = `${m.scope}\u0000${m.cwd || ''}\u0000${m.name}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function extensionCwdList(cwdParam) {
  const set = new Set();
  if (cwdParam) set.add(path.resolve(cwdParam));
  for (const f of state.folders || []) { if (f.path) set.add(path.resolve(f.path)); }
  for (const c of Object.values(state.conversations || {})) { if (c.cwd) set.add(path.resolve(c.cwd)); }
  return [...set].slice(0, 60);
}

async function listExtensions(cwdParam) {
  const cwdList = extensionCwdList(cwdParam);
  const [skills, mcp] = await Promise.all([listSkills(cwdList), listMcpServers(cwdList)]);
  return {
    version: readVersion(),
    skills,
    mcp,
    roots: { userSkills: USER_SKILLS_DIR, trash: SKILL_TRASH_DIR, claudeJson: CLAUDE_JSON_FILE }
  };
}

async function removeSkillDir(target, cwdParam) {
  const cwdList = extensionCwdList(cwdParam);
  const roots = skillRootsFor(cwdList);
  const abs = path.resolve(target);
  let root = null;
  for (const r of roots) {
    const rel = path.relative(path.resolve(r.dir), abs);
    if (rel && !rel.startsWith('..') && !path.isAbsolute(rel) && !rel.includes(path.sep)) {
      root = r;
      break;
    }
  }
  if (!root) throw new Error('只能卸载技能目录里的直接子项');
  const st = await fsp.lstat(abs);
  if (!st.isDirectory() && !st.isFile()) throw new Error('不是技能文件或目录');

  fs.mkdirSync(SKILL_TRASH_DIR, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  let dest = path.join(SKILL_TRASH_DIR, `${stamp}-${path.basename(abs)}`);
  let n = 1;
  while (fs.existsSync(dest)) dest = path.join(SKILL_TRASH_DIR, `${stamp}-${path.basename(abs)}-${n++}`);
  await fsp.rename(abs, dest);
  return { trashedTo: dest };
}

function execFileP(file, args, opts = {}) {
  return new Promise(resolve => {
    execFile(file, args, {
      windowsHide: true,
      timeout: 60000,
      maxBuffer: 4 * 1024 * 1024,
      ...opts
    }, (err, stdout, stderr) => {
      resolve({
        ok: !err,
        code: err ? (typeof err.code === 'number' ? err.code : -1) : 0,
        stdout: String(stdout || ''),
        stderr: String(stderr || '')
      });
    });
  });
}

function stripAnsi(s) {
  return String(s || '').replace(/\u001b\[[0-9;]*m/g, '');
}

async function removeMcpServer(name, scope, cwd) {
  if (!/^[^\r\n]{1,160}$/.test(String(name || ''))) throw new Error('MCP 名称无效');
  if (!['user', 'local', 'project'].includes(scope)) throw new Error('作用域无效');
  let workdir = ROOT;
  if (scope !== 'user') {
    if (!cwd) throw new Error('缺少项目目录');
    const st = await fsp.stat(cwd);
    if (!st.isDirectory()) throw new Error('项目目录不存在');
    workdir = cwd;
  }
  const r = await execFileP(CLAUDE_BIN, ['mcp', 'remove', name, '-s', scope], { cwd: workdir });
  if (!r.ok) {
    const msg = stripAnsi(r.stderr || r.stdout).trim();
    throw new Error(msg || 'claude mcp remove 执行失败');
  }
  return { output: stripAnsi(r.stdout || r.stderr).trim() || '已移除' };
}

// ---------------------------------------------------------------- 插件管理

let pluginCache = { at: 0, installed: [], available: [], availableError: '' };

async function listPlugins(force = false) {
  const now = Date.now();
  if (!force && pluginCache.at && now - pluginCache.at < 60000) return pluginCache;

  let installed = [];
  let available = [];
  let availableError = '';

  const r = await execFileP(CLAUDE_BIN, ['plugin', 'list', '--json', '--available'], { timeout: 60000 });
  if (r.ok) {
    try {
      const j = JSON.parse(r.stdout);
      if (Array.isArray(j)) installed = j;
      else {
        installed = Array.isArray(j.installed) ? j.installed : [];
        available = Array.isArray(j.available) ? j.available : [];
      }
    } catch (e) {
      availableError = '插件列表解析失败：' + e.message;
    }
  } else {
    availableError = stripAnsi(r.stderr || r.stdout).trim() || 'claude plugin list 执行失败';
    const r2 = await execFileP(CLAUDE_BIN, ['plugin', 'list', '--json'], { timeout: 30000 });
    if (r2.ok) {
      try {
        const j2 = JSON.parse(r2.stdout);
        installed = Array.isArray(j2) ? j2 : (Array.isArray(j2.installed) ? j2.installed : []);
      } catch { /* ignore */ }
    }
  }

  const installedIds = new Set(installed.map(p => String(p.id || '')));
  const byId = new Map(available.map(p => [String(p.pluginId || ''), p]));

  const inst = installed.map(p => {
    const id = String(p.id || '');
    const at = id.lastIndexOf('@');
    const name = at > 0 ? id.slice(0, at) : id;
    const marketplace = at > 0 ? id.slice(at + 1) : '';
    const meta = byId.get(id) || {};
    let description = String(meta.description || '');
    let version = String(p.version || '');
    // --available 可能不包含已安装的插件，从插件自己的 manifest 补描述
    if ((!description || !version) && p.installPath) {
      const mf = readJsonFile(path.join(String(p.installPath), '.claude-plugin', 'plugin.json')) ||
        readJsonFile(path.join(String(p.installPath), 'plugin.json'));
      if (mf) {
        if (!description && mf.description) description = String(mf.description);
        if (!version && mf.version) version = String(mf.version);
      }
    }
    return {
      id,
      name,
      marketplace,
      version,
      scope: String(p.scope || ''),
      enabled: !!p.enabled,
      projectEnabled: !!p.projectEnabled,
      description: truncate(description, 300),
      installCount: Number(meta.installCount) || 0,
      installPath: String(p.installPath || '')
    };
  });

  const avail = available
    .filter(p => !installedIds.has(String(p.pluginId || '')))
    .map(p => ({
      id: String(p.pluginId || ''),
      name: String(p.name || String(p.pluginId || '').split('@')[0]),
      marketplace: String(p.marketplaceName || ''),
      description: truncate(String(p.description || ''), 300),
      installCount: Number(p.installCount) || 0
    }))
    .sort((a, b) => b.installCount - a.installCount);

  pluginCache = { at: now, installed: inst, available: avail, availableError };
  return pluginCache;
}

async function pluginAction(action, id, scope) {
  const act = String(action || '');
  if (!['install', 'enable', 'disable', 'uninstall'].includes(act)) throw new Error('不支持的插件操作');
  const pid = String(id || '').trim();
  if (!/^[^\r\n]{1,200}$/.test(pid)) throw new Error('插件标识无效');
  if (act === 'install' && !pid.includes('@')) throw new Error('安装时请使用 name@marketplace 格式');

  const args = ['plugin', act, pid];
  if (act === 'install') {
    args.push('-y', '-s', 'user');
  } else {
    if (act === 'uninstall') args.push('-y');
    if (scope && ['user', 'project', 'local'].includes(scope)) args.push('-s', scope);
  }

  const r = await execFileP(CLAUDE_BIN, args, { timeout: 180000 });
  const out = stripAnsi((r.stdout || '') + (r.stderr ? '\n' + r.stderr : '')).trim();
  if (!r.ok) throw new Error(out || `claude plugin ${act} 失败`);
  pluginCache.at = 0;
  return { output: out || '完成' };
}

// ---------------------------------------------------------------- 会话索引

const convIndex = new Map(); // id -> { cwd, title, updatedAt, ... }

async function listFolderSessions(folderPath) {
  const dir = projectDirFor(folderPath);
  const entries = await readdirSafe(dir);
  const files = entries
    .filter(e => e.isFile() && e.name.endsWith('.jsonl'))
    .map(e => path.join(dir, e.name));
  // 并行 stat/解析：冷启动时几十个会话不必排队读
  const out = await Promise.all(files.map(async file => {
    let stat;
    try { stat = await fsp.stat(file); } catch { return null; }
    let meta;
    try { meta = await readSessionMeta(file, stat); } catch { return null; }
    const override = state.conversations[meta.id];
    return {
      ...meta,
      title: (override && override.title) || meta.title,
      cwd: folderPath,
      pinned: !!(override && override.pinned)
    };
  }));
  return out.filter(Boolean);
}

async function addFolderIfNeeded(p) {
  const abs = path.resolve(p);
  if (!state.folders.some(f => normKey(f.path) === normKey(abs))) {
    // 放最前面
    state.folders.unshift({ path: abs, addedAt: new Date().toISOString() });
    saveState();
  }
}

// ---------------------------------------------------------------- 流式运行器

const sseClients = new Set();
let runners = new Map(); // convId -> Runner

function broadcast(ev) {
  const payload = 'data: ' + JSON.stringify(ev) + '\n\n';
  for (const res of sseClients) {
    try { res.write(payload); } catch { sseClients.delete(res); }
  }
}

class Runner {
  constructor(conv) {
    this.id = conv.id;
    this.cwd = conv.cwd;
    this.child = null;
    this.busy = false;
    this.stopping = false;
    this.stderr = '';
    this.msgSeq = 0;
    this.currentMsgId = null;
    this.lastAssistantId = null;
    this.runUsage = null;
    this.lastCostUsd = 0;
    this.toolByIndex = new Map();
    this.pendingPerms = new Map();
    this.turnId = null;
    this.mode = conv.permissionMode;
    this.model = conv.model || '';
    this.effort = conv.effort || '';
    this.idleTimer = null;
    this.seq = 0;
    // 当前这轮的实时快照：切走再切回来时用它恢复正在流式输出的内容
    this.live = null;
    // 插话刚好赶在上一轮收尾时，CLI 会把它当成下一轮；先记着，等新的 run_start 使用
    this.pendingSteers = [];
    // “全部放行”在 CLI 侧用 manual + 宿主自动允许实现，这样 AskUserQuestion/ExitPlanMode 仍然可用
    this.autoAllow = false;
    this.askTimers = new Map();
    this.askMeta = new Map();
    this.permMeta = new Map();
  }

  clearAsks() {
    for (const t of this.askTimers.values()) clearTimeout(t);
    this.askTimers.clear();
    const ids = [...this.askMeta.keys()];
    this.askMeta.clear();
    if (this.live) this.live.ask = null;
    // 让还开着问题面板的客户端知道问题已经作废（停任务、切模型等）
    for (const requestId of ids) {
      this.emit({ t: 'ask_resolved', requestId, answers: null, auto: false, cancelled: true });
    }
  }

  emit(ev) {
    this.seq += 1;
    broadcast({ convId: this.id, seq: this.seq, ...ev });
  }

  liveBlock(key) {
    if (!this.live) return null;
    return this.live.blocks.find(b => b.key === key) || null;
  }

  liveAddText(key, kind, text) {
    if (!this.live) return;
    let b = this.liveBlock(key);
    if (!b) {
      b = { key, type: kind === 'thinking' ? 'thinking' : 'text', text: '' };
      this.live.blocks.push(b);
    }
    b.text += text;
  }

  liveSetToolResult(toolUseId, content, isError) {
    if (!this.live) return;
    const b = this.live.blocks.find(x => x.type === 'tool_use' && x.id === toolUseId);
    if (!b) return;
    b.result = content;
    b.isError = !!isError;
    b.done = true;
  }

  /** 返回给 GET /api/conversation：客户端切回正在运行的对话时据此重建实时视图 */
  liveSnapshot() {
    if (!this.live) return null;
    return {
      turnId: this.live.turnId,
      startedAt: this.live.startedAt,
      seq: this.seq,
      users: this.live.users.map(u => ({ ...u })),
      blocks: this.live.blocks.map(b => ({ ...b })),
      permission: this.live.permission ? { ...this.live.permission } : null,
      ask: this.live.ask ? {
        requestId: this.live.ask.requestId,
        toolUseId: this.live.ask.toolUseId,
        questions: (this.live.ask.questions || []).map(q => ({ ...q })),
        major: !!this.live.ask.major,
        autoAt: this.live.ask.autoAt || null,
        cancelAt: this.live.ask.cancelAt || null
      } : null
    };
  }

  matches(conv) {
    return this.mode === conv.permissionMode && this.model === (conv.model || '') &&
      this.effort === (conv.effort || '');
  }

  async ensure(conv) {
    if (this.child && !this.child.killed) {
      if (!this.matches(conv)) this.kill();
      else return;
    }

    const file = await findSessionFile(this.id);
    const uiMode = conv.permissionMode || state.settings.defaultPermissionMode;
    // bypassPermissions 会让 CLI 跳过 canUseTool，导致 AskUserQuestion/ExitPlanMode 不可用；
    // 改用 manual + 下面自动 allow 非交互类请求，界面上的“全部放行”语义不变。
    const cliMode = uiMode === 'bypassPermissions' ? 'manual' : uiMode;
    const args = [
      '-p',
      '--input-format', 'stream-json',
      '--output-format', 'stream-json',
      '--verbose',
      '--include-partial-messages',
      '--permission-mode', cliMode,
      '--permission-prompt-tool', 'stdio',
      '--append-system-prompt', ASK_GUIDE
    ];
    if (conv.model) args.push('--model', conv.model);
    if (conv.effort) args.push('--effort', conv.effort);
    // 模式由界面控制：不允许模型自己切换模式；非 plan 模式也不让它提交计划
    const disallowed = ['EnterPlanMode'];
    if (uiMode !== 'plan') disallowed.push('ExitPlanMode');
    args.push('--disallowedTools', disallowed.join(','));

    if (file) args.push('--resume', this.id);
    else args.push('--session-id', this.id);

    this.mode = uiMode;
    this.autoAllow = uiMode === 'bypassPermissions';
    this.model = conv.model || '';
    this.effort = conv.effort || '';
    this.stderr = '';
    this.stopping = false;

    const child = spawnClaude(args, this.cwd);
    this.child = child;
    this.lastCostUsd = 0;   // 新进程的累计费用从头算

    child.stdout.setEncoding('utf8');
    let buf = '';
    child.stdout.on('data', chunk => {
      buf += chunk;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        if (!line.trim()) continue;
        let ev;
        try { ev = JSON.parse(line); } catch { continue; }
        try { this.handle(ev); } catch (e) { console.error('[runner] 处理事件出错', e); }
      }
    });

    child.stderr.setEncoding('utf8');
    child.stderr.on('data', d => {
      this.stderr = (this.stderr + d).slice(-4000);
    });

    child.stdin.on('error', () => {});

    child.on('error', err => {
      if (this.child !== child) return;
      this.emit({ t: 'error', message: `无法启动 Claude：${err.message}` });
      this.busy = false;
      this.child = null;
    });

    child.on('exit', (code) => {
      // 已被替换掉的旧进程，忽略它的退出事件
      if (this.child !== child) return;
      const wasBusy = this.busy;
      this.child = null;
      this.busy = false;
      if (wasBusy) {
        const msg = this.stopping
          ? '已停止'
          : (this.stderr.trim() || `进程退出（代码 ${code}）`);
        this.live = null;
        this.emit({
          t: 'run_end',
          stopped: this.stopping,
          error: this.stopping ? null : msg,
          cost: null
        });
      }
      this.live = null;
      this.pendingSteers = [];
      this.permMeta.clear();
      this.clearAsks();
      this.stopping = false;
    });
  }

  handle(ev) {
    const t = ev.type;

    if (t === 'system') {
      if (ev.subtype === 'init') {
        this.emit({
          t: 'init',
          sessionId: ev.session_id,
          model: ev.model,
          tools: ev.tools,
          cwd: ev.cwd
        });
      } else if (ev.subtype === 'status') {
        this.emit({
          t: 'status',
          status: ev.status,
          compactResult: ev.compact_result || ev.compactResult || null,
          compactError: ev.compact_error || ev.compactError || null
        });
      } else if (ev.subtype === 'thinking_tokens') {
        this.emit({ t: 'thinking_tokens', n: ev.estimated_tokens || 0 });
      } else if (ev.subtype === 'compact_boundary') {
        const cm = ev.compact_metadata || ev.compactMetadata || {};
        this.emit({
          t: 'compact_boundary',
          trigger: cm.trigger || '',
          pre: cm.pre_tokens || cm.preTokens || 0,
          post: cm.post_tokens || cm.postTokens || 0
        });
      }
      return;
    }

    if (t === 'stream_event') {
      const e = ev.event || {};
      if (e.type === 'message_start') {
        // 插话刚好赶在上一轮收尾时，CLI 可能把它当成下一轮，这里补一个 run_start
        if (!this.busy && this.child) {
          this.busy = true;
          this.runUsage = null;
          this.lastAssistantId = null;
          this.turnId = 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
          this.clearIdle();
          this.live = {
            turnId: this.turnId,
            startedAt: Date.now(),
            users: this.pendingSteers.splice(0).map(u => ({ ...u, afterBlocks: 0 })),
            blocks: [],
            permission: null,
            ask: null
          };
          this.emit({ t: 'run_start', turnId: this.turnId, ts: Date.now() });
        }
        this.currentMsgId = (e.message && e.message.id) || ('m' + (++this.msgSeq));
        this.toolByIndex.clear();
        this.emit({ t: 'msg_start', msgId: this.currentMsgId, model: e.message && e.message.model });
      } else if (e.type === 'content_block_start') {
        const b = e.content_block || {};
        if (b.type === 'text') {
          const key = this.currentMsgId + ':' + e.index;
          if (this.live) this.live.blocks.push({ key, type: 'text', text: '' });
          this.emit({ t: 'block_start', key, block: { type: 'text', text: '' } });
        } else if (b.type === 'thinking') {
          const key = this.currentMsgId + ':' + e.index;
          if (this.live) this.live.blocks.push({ key, type: 'thinking', text: '' });
          this.emit({ t: 'block_start', key, block: { type: 'thinking', thinking: '' } });
        } else if (b.type === 'tool_use') {
          this.toolByIndex.set(e.index, b.id);
          const key = 'tool:' + b.id;
          if (this.live) {
            this.live.blocks.push({
              key, type: 'tool_use', id: b.id, name: b.name || '',
              input: null, result: null, isError: false, done: false
            });
          }
          this.emit({ t: 'block_start', key, block: { type: 'tool_use', id: b.id, name: b.name, input: null } });
        }
      } else if (e.type === 'message_delta') {
        // 每次 API 调用的最终 usage（含真实的 output_tokens）
        if (e.usage) this.runUsage = addUsage(this.runUsage, e.usage);
      } else if (e.type === 'content_block_delta') {
        const d = e.delta || {};
        if (d.type === 'text_delta') {
          const key = this.currentMsgId + ':' + e.index;
          this.liveAddText(key, 'text', d.text || '');
          this.emit({ t: 'delta', key, kind: 'text', text: d.text || '' });
        } else if (d.type === 'thinking_delta') {
          const key = this.currentMsgId + ':' + e.index;
          this.liveAddText(key, 'thinking', d.thinking || '');
          this.emit({ t: 'delta', key, kind: 'thinking', text: d.thinking || '' });
        } else if (d.type === 'input_json_delta') {
          const toolId = this.toolByIndex.get(e.index);
          if (toolId) this.emit({ t: 'delta', key: 'tool:' + toolId, kind: 'input_json', text: d.partial_json || '' });
        }
      }
      return;
    }

    if (t === 'assistant' && ev.message) {
      this.lastAssistantId = ev.message.id || this.currentMsgId || this.lastAssistantId;
      for (const b of ev.message.content || []) {
        if (b && b.type === 'tool_use') {
          const key = 'tool:' + b.id;
          if (this.live) {
            const lb = this.liveBlock(key);
            if (lb) {
              lb.input = b.input || {};
              if (b.name) lb.name = b.name;
            } else {
              this.live.blocks.push({
                key, type: 'tool_use', id: b.id, name: b.name || '',
                input: b.input || {}, result: null, isError: false, done: false
              });
            }
          }
          this.emit({
            t: 'block_set',
            key,
            block: { type: 'tool_use', id: b.id, name: b.name, input: b.input || {} }
          });
        }
      }
      if (ev.message.usage) {
        // 这里只用于界面提示；assistant 事件可能重复且 output_tokens 为 0，
        // 本轮累计以 message_delta 为准。
        this.emit({ t: 'usage', usage: ev.message.usage, model: ev.message.model });
      }
      return;
    }

    if (t === 'user' && ev.message) {
      const c = ev.message.content;
      if (Array.isArray(c)) {
        for (const b of c) {
          if (b && b.type === 'tool_result') {
            const content = truncate(toolResultText(b.content), 24000);
            this.liveSetToolResult(b.tool_use_id, content, b.is_error);
            this.emit({
              t: 'tool_result',
              toolUseId: b.tool_use_id,
              content,
              isError: !!b.is_error
            });
          }
        }
      }
      return;
    }

    if (t === 'result') {
      this.busy = false;

      // usage 是每次 API 调用的，这里用本轮累加值；total_cost_usd 是进程累计，
      // 相减得到本轮的费用。
      const turnUsage = this.runUsage || ev.usage || null;
      const totalCost = typeof ev.total_cost_usd === 'number' ? ev.total_cost_usd : null;
      let turnCost = null;
      if (totalCost != null) {
        turnCost = Math.max(0, totalCost - (this.lastCostUsd || 0));
        this.lastCostUsd = totalCost;
      }

      if (this.lastAssistantId) {
        recordTurnMeta(this.id, {
          assistantId: this.lastAssistantId,
          duration: typeof ev.duration_ms === 'number' ? ev.duration_ms : null,
          costUsd: turnCost,
          usage: turnUsage,
          ts: new Date().toISOString()
        });
      }
      this.lastAssistantId = null;
      this.runUsage = null;

      this.emit({
        t: 'run_end',
        subtype: ev.subtype,
        isError: !!ev.is_error || ev.subtype !== 'success',
        error: ev.is_error ? (ev.result || ev.subtype) : null,
        cost: turnCost,
        duration: ev.duration_ms,
        usage: turnUsage,
        stopReason: ev.stop_reason
      });
      this.live = null;
      this.permMeta.clear();
      this.clearAsks();
      this.scheduleIdleKill();
      // 之前生成失败（比如网络抖动）时，这一轮结束后再试一次
      scheduleAutoTitle(this.id, null);
      return;
    }

    if (t === 'control_request') {
      const req = ev.request || {};
      const requestId = ev.request_id;
      if (req.subtype === 'can_use_tool') {
        const input = req.input || {};
        const toolName = req.tool_name || '工具';
        const requiresInteraction = !!req.requires_user_interaction ||
          toolName === 'AskUserQuestion' || toolName === 'ExitPlanMode';

        if (toolName === 'AskUserQuestion') {
          this.pendingPerms.set(requestId, input);
          this.startAsk(requestId, req);
          return;
        }

        // 全部放行：普通工具请求直接批准，不打扰用户；需要用户交互的仍然弹 UI
        if (this.autoAllow && !requiresInteraction) {
          this.write({
            type: 'control_response',
            response: {
              subtype: 'success',
              request_id: requestId,
              response: { behavior: 'allow', updatedInput: input }
            }
          });
          return;
        }

        this.pendingPerms.set(requestId, input);
        this.permMeta.set(requestId, {
          toolName,
          requiresUserInteraction: requiresInteraction,
          suggestions: req.permission_suggestions || []
        });
        if (this.live) {
          this.live.permission = {
            requestId,
            toolName,
            input,
            suggestions: req.permission_suggestions || [],
            requiresUserInteraction: requiresInteraction
          };
        }
        this.emit({
          t: 'permission',
          requestId,
          toolName,
          input,
          suggestions: req.permission_suggestions || [],
          requiresUserInteraction: requiresInteraction
        });
        // 普通权限 5 分钟无响应就拒绝；方向性交互（如 ExitPlanMode）等 30 分钟，
        // 仍无人处理就安全取消，避免进程一直挂着。
        setTimeout(() => {
          if (!this.pendingPerms.has(requestId)) return;
          this.respondPermission(
            requestId,
            false,
            requiresInteraction
              ? '用户长时间没有确认，这个操作已自动取消。请先结束这一轮并等用户回来后再继续。'
              : undefined
          );
        }, requiresInteraction ? ASK_MAJOR_MS : 5 * 60 * 1000);
      }
      return;
    }
  }

  /** 判断一组问题是否属于“必须等用户”的方向性问题 */
  askIsMajor(questions) {
    const MAJOR = /【重要】|\[重要\]|重要决策|方向性|不可逆|重大|风险|删除|覆盖|批准|plan approval|approve the plan/i;
    for (const q of questions) {
      if (!q || typeof q !== 'object') continue;
      const text = `${q.header || ''} ${q.question || ''}`;
      if (MAJOR.test(text)) return true;
      if (!Array.isArray(q.options) || q.options.length === 0) return true;
    }
    return false;
  }

  /** 次要问题的自动答案：每题取第一个选项（系统提示要求模型把推荐项放第一个） */
  askAutoAnswers(questions) {
    const answers = {};
    for (const q of questions) {
      if (!q || typeof q.question !== 'string') return null;
      const opt = Array.isArray(q.options) ? q.options.find(o => o && typeof o.label === 'string') : null;
      if (!opt) return null;
      answers[q.question] = opt.label;
    }
    return Object.keys(answers).length ? answers : null;
  }

  startAsk(requestId, req) {
    const input = req.input || {};
    const questions = Array.isArray(input.questions) ? input.questions : [];
    const autoAnswers = this.askAutoAnswers(questions);
    const major = this.askIsMajor(questions) || !autoAnswers;
    const meta = {
      requestId,
      toolUseId: req.tool_use_id || null,
      questions,
      major,
      autoAt: major ? null : Date.now() + ASK_AUTO_MS,
      cancelAt: major ? Date.now() + ASK_MAJOR_MS : null
    };
    this.askMeta.set(requestId, meta);
    if (this.live) this.live.ask = { ...meta };
    this.emit({
      t: 'ask',
      requestId,
      toolUseId: meta.toolUseId,
      questions,
      major,
      autoAt: meta.autoAt,
      cancelAt: meta.cancelAt
    });
    const timer = setTimeout(() => {
      if (!this.pendingPerms.has(requestId)) return;
      if (major) {
        // 大方向问题等很久也没人回答：安全取消并让模型收尾，不永久占用进程
        this.respondAsk(requestId, null, { cancel: true, timeout: true });
      } else {
        const answers = this.askAutoAnswers(questions);
        if (answers) this.respondAsk(requestId, answers, { auto: true });
      }
    }, major ? ASK_MAJOR_MS : ASK_AUTO_MS);
    timer.unref?.();
    this.askTimers.set(requestId, timer);
  }

  respondAsk(requestId, answers, opts = {}) {
    const meta = this.askMeta.get(requestId);
    if (!meta || !this.pendingPerms.has(requestId)) return false;
    this.pendingPerms.delete(requestId);
    this.askMeta.delete(requestId);
    const timer = this.askTimers.get(requestId);
    if (timer) { clearTimeout(timer); this.askTimers.delete(requestId); }
    if (this.live && this.live.ask && this.live.ask.requestId === requestId) this.live.ask = null;

    if (opts.cancel) {
      const message = opts.timeout
        ? '用户长时间没有回答问题，这个问题已自动取消。请先结束这一轮并等用户回来后再继续，不要自行做出方向性决定。'
        : '用户取消了这个问题，请根据已有信息自行判断继续，必要时在结果里说明你的假设。';
      this.write({
        type: 'control_response',
        response: {
          subtype: 'success',
          request_id: requestId,
          response: { behavior: 'deny', message }
        }
      });
      this.emit({
        t: 'ask_resolved', requestId, answers: null,
        auto: false, cancelled: true, timeout: !!opts.timeout
      });
      return true;
    }

    const finalAnswers = {};
    for (const q of meta.questions) {
      const text = String((q && q.question) || '');
      let value = answers && typeof answers[text] === 'string' ? answers[text].trim() : '';
      if (!value) {
        const opt = (q.options || [])[0];
        value = opt && typeof opt.label === 'string' ? opt.label : '按你的推荐继续';
      }
      finalAnswers[text] = value;
    }

    this.write({
      type: 'control_response',
      response: {
        subtype: 'success',
        request_id: requestId,
        response: {
          behavior: 'allow',
          updatedInput: { questions: meta.questions, answers: finalAnswers }
        }
      }
    });
    this.emit({
      t: 'ask_resolved',
      requestId,
      answers: finalAnswers,
      auto: !!opts.auto,
      cancelled: false
    });
    return true;
  }

  respondPermission(requestId, allow, message) {
    if (this.askMeta.has(requestId)) return false;   // 提问必须走 respondAsk
    if (!this.pendingPerms.has(requestId)) return false;
    const input = this.pendingPerms.get(requestId);
    const meta = this.permMeta.get(requestId) || {};
    this.pendingPerms.delete(requestId);
    this.permMeta.delete(requestId);
    if (this.live && this.live.permission && this.live.permission.requestId === requestId) {
      this.live.permission = null;
    }
    const response = {
      subtype: 'success',
      request_id: requestId,
      response: allow
        ? { behavior: 'allow', updatedInput: input }
        : { behavior: 'deny', message: message || '用户在界面中拒绝了该操作' }
    };
    this.write({ type: 'control_response', response });
    this.emit({ t: 'permission_resolved', requestId, allow });

    // 计划批准后退出 plan 模式：切到“自动接受编辑”，下一条消息按新模式重启
    if (allow && meta.toolName === 'ExitPlanMode') {
      const st = ensureConvState(this.id);
      const mode = 'acceptEdits';
      st.permissionMode = mode;
      st.updatedAt = new Date().toISOString();
      saveState();
      broadcast({
        t: 'conv_meta', id: this.id, title: st.title, permissionMode: mode,
        model: st.model, effort: st.effort || ''
      });
    }
    return true;
  }

  write(obj) {
    if (!this.child || !this.child.stdin.writable) return false;
    try {
      this.child.stdin.write(JSON.stringify(obj) + '\n');
      return true;
    } catch {
      return false;
    }
  }

  async start(conv, text, images = []) {
    if (this.busy) throw new Error('上一条消息还在处理中');
    await this.ensure(conv);
    if (!this.child) throw new Error('Claude 进程启动失败');

    this.busy = true;
    this.stopping = false;
    this.runUsage = null;
    this.pendingSteers = [];
    this.turnId = 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

    const ok = this.write({
      type: 'user',
      message: { role: 'user', content: buildUserContent(text, images) },
      parent_tool_use_id: null
    });
    if (!ok) {
      this.busy = false;
      throw new Error('写入失败，Claude 进程可能已退出');
    }

    const userTs = new Date().toISOString();
    const userMsg = {
      id: 'u' + Date.now(),
      role: 'user',
      text,
      images: images.length ? images : undefined,
      ts: userTs
    };
    this.live = {
      turnId: this.turnId,
      startedAt: Date.now(),
      users: [{ text, images: images.length ? images : undefined, steer: false, ts: userTs, afterBlocks: 0 }],
      blocks: [],
      permission: null,
      ask: null
    };
    this.emit({ t: 'user_echo', message: userMsg });
    this.emit({ t: 'run_start', turnId: this.turnId, ts: Date.now() });
    this.clearIdle();

    // 第一条消息发出后就开始起标题，和正文生成并行，不阻塞对话
    scheduleAutoTitle(this.id, text);
  }

  /** 运行中插话：把消息写进正在跑的 Claude 进程，当前工具结束后生效 */
  steer(text, images = []) {
    if (!this.busy) throw new Error('本轮已经结束，请重新发送');
    if (!this.child || this.child.killed) throw new Error('Claude 进程已退出');
    const ok = this.write({
      type: 'user',
      message: { role: 'user', content: buildUserContent(text, images) },
      parent_tool_use_id: null
    });
    if (!ok) throw new Error('插话写入失败，Claude 进程可能已退出');

    const ts = new Date().toISOString();
    const msg = {
      id: 'u' + Date.now(),
      role: 'user',
      text,
      images: images.length ? images : undefined,
      steer: true,
      ts
    };
    const rec = {
      text, images: images.length ? images : undefined, steer: true, ts,
      afterBlocks: this.live ? this.live.blocks.length : 0
    };
    if (this.live) this.live.users.push(rec);
    this.pendingSteers.push(rec);
    this.emit({ t: 'user_echo', message: msg });
  }

  stop() {
    if (!this.child) return;
    this.stopping = true;
    this.pendingPerms.clear();
    this.permMeta.clear();
    this.clearAsks();
    try { this.child.kill(); } catch { /* ignore */ }
  }

  kill() {
    this.stopping = true;
    if (this.child) {
      try { this.child.kill(); } catch { /* ignore */ }
    }
    this.child = null;
    this.busy = false;
    this.live = null;
    this.pendingSteers = [];
    this.permMeta.clear();
    this.clearAsks();
    this.clearIdle();
  }

  clearIdle() {
    if (this.idleTimer) { clearTimeout(this.idleTimer); this.idleTimer = null; }
  }

  scheduleIdleKill() {
    this.clearIdle();
    this.idleTimer = setTimeout(() => {
      if (!this.busy) {
        this.kill();
        runners.delete(this.id);
      }
    }, 20 * 60 * 1000);
  }
}

function runnerFor(conv) {
  let r = runners.get(conv.id);
  if (!r) {
    r = new Runner(conv);
    runners.set(conv.id, r);
  }
  // 注意：不要在这里覆盖 r.mode / r.model，ensure() 会对比后决定是否重启进程
  return r;
}

// ---------------------------------------------------------------- 会话查找

function lookupConv(id) {
  const s = state.conversations[id];
  if (s) {
    return {
      id,
      cwd: s.cwd,
      title: s.title || null,
      model: s.model || '',
      effort: s.effort || '',
      permissionMode: s.permissionMode || state.settings.defaultPermissionMode
    };
  }
  const i = convIndex.get(id);
  if (i) {
    return {
      id,
      cwd: i.cwd,
      title: i.title || null,
      model: '',
      effort: '',
      permissionMode: state.settings.defaultPermissionMode
    };
  }
  return null;
}

function ensureConvState(id, extra = {}) {
  if (!state.conversations[id]) {
    const i = convIndex.get(id);
    state.conversations[id] = {
      cwd: (i && i.cwd) || extra.cwd,
      title: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      model: '',
      effort: '',
      permissionMode: state.settings.defaultPermissionMode
    };
  }
  return state.conversations[id];
}

// ---------------------------------------------------------------- 文件系统浏览

async function listDrives() {
  if (!IS_WIN) return ['/'];
  const drives = [];
  for (const letter of 'CDEFGHIJKLMNOPQRSTUVWXYZ') {
    const p = letter + ':\\';
    try { await fsp.access(p); drives.push(p); } catch { /* 不存在 */ }
  }
  return drives;
}

function quickLinks() {
  return [
    { name: '主目录', path: HOME },
    { name: '桌面', path: path.join(HOME, 'Desktop') },
    { name: '文档', path: path.join(HOME, 'Documents') },
    { name: '下载', path: path.join(HOME, 'Downloads') }
  ].filter(q => fs.existsSync(q.path));
}

async function browseDir(dir, includeFiles = false) {
  const target = dir ? path.resolve(dir) : '';
  const quick = quickLinks();
  if (!target) {
    const drives = await listDrives();
    return {
      path: '',
      parent: null,
      drives,
      quick,
      entries: drives.map(d => ({ name: d, path: d, isDir: true }))
    };
  }

  const stat = await fsp.stat(target);
  if (!stat.isDirectory()) throw new Error('不是文件夹');

  const parent = path.dirname(target);
  const entries = [];
  for (const d of await readdirSafe(target)) {
    if (d.name === '$RECYCLE.BIN' || d.name === 'System Volume Information') continue;
    const full = path.join(target, d.name);
    if (d.isDirectory()) {
      entries.push({ name: d.name, path: full, isDir: true, hidden: d.name.startsWith('.') });
    } else if (includeFiles && d.isFile()) {
      let size = 0;
      try { size = (await fsp.stat(full)).size; } catch { /* ignore */ }
      entries.push({ name: d.name, path: full, isDir: false, size, hidden: d.name.startsWith('.') });
    }
  }
  entries.sort((a, b) => {
    if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
    return a.name.localeCompare(b.name, 'zh-CN');
  });

  return {
    path: target,
    parent: parent === target ? null : parent,
    drives: await listDrives(),
    quick,
    entries
  };
}

// ---------------------------------------------------------------- 启动数据

async function buildBootstrap() {
  const folderMap = new Map(); // norm -> {path, addedAt, auto}

  for (const f of state.folders) {
    folderMap.set(normKey(f.path), { path: f.path, addedAt: f.addedAt, auto: false });
  }

  for (const d of await readdirSafe(PROJECTS_DIR)) {
    if (!d.isDirectory()) continue;
    const cwd = await cwdOfProjectDir(d.name);
    if (!cwd) continue;
    const key = normKey(cwd);
    if (!folderMap.has(key)) folderMap.set(key, { path: cwd, addedAt: null, auto: true });
  }

  const folders = [];
  convIndex.clear();

  for (const info of folderMap.values()) {
    const exists = fs.existsSync(info.path);
    const sessions = exists ? await listFolderSessions(info.path) : [];
    const sessionIds = new Set(sessions.map(s => s.id));
    for (const c of sessions) convIndex.set(c.id, c);

    // 占位会话（还没产生任何消息）
    for (const [id, c] of Object.entries(state.conversations)) {
      if (normKey(c.cwd) !== normKey(info.path)) continue;
      if (sessionIds.has(id)) continue;
      sessionIds.add(id);
      sessions.push({
        id,
        title: c.title || '新对话',
        preview: '还没有消息',
        cwd: info.path,
        createdAt: c.createdAt,
        updatedAt: c.updatedAt,
        turns: 0,
        placeholder: true
      });
      convIndex.set(id, { id, cwd: info.path, title: c.title });
    }

    // 排序规则：未发过消息的新对话永远排在最前面（最新的在前）；
    // 已经发过消息的对话再按最近更新时间排。这样正在运行的任务不会把刚开的新对话挤下去。
    sessions.sort((a, b) => {
      const aNew = !!a.placeholder || a.turns === 0;
      const bNew = !!b.placeholder || b.turns === 0;
      if (aNew !== bNew) return aNew ? -1 : 1;
      const at = new Date(aNew ? (a.createdAt || a.updatedAt) : a.updatedAt).getTime();
      const bt = new Date(bNew ? (b.createdAt || b.updatedAt) : b.updatedAt).getTime();
      return (isFinite(bt) ? bt : 0) - (isFinite(at) ? at : 0);
    });

    if (sessions.length || !info.auto) {
      folders.push({
        path: info.path,
        name: path.basename(info.path) || info.path,
        exists,
        addedAt: info.addedAt,
        conversations: sessions.map(s => {
          const r = runners.get(s.id);
          return {
            ...s,
            running: !!(r && r.busy),
            asking: !!(r && r.live && r.live.ask),
            waiting: !!(r && r.live && (r.live.ask || r.live.permission))
          };
        })
      });
    }
  }

  // 按添加时间倒序，自动发现的排后面
  folders.sort((a, b) => {
    if (!!a.addedAt !== !!b.addedAt) return a.addedAt ? -1 : 1;
    if (a.addedAt && b.addedAt) return new Date(b.addedAt) - new Date(a.addedAt);
    return a.name.localeCompare(b.name, 'zh-CN');
  });

  return {
    folders,
    settings: {
      ...state.settings,
      // defaultModel 是“实际生效”的默认模型；Choice 是用户显式选过的全局默认（可能为空）
      defaultModel: state.settings.defaultModel || envModel(),
      defaultModelChoice: state.settings.defaultModel || '',
      availableModels: localModels(),
      envEffort: envEffort(),
      effortLevels: EFFORT_LEVELS
    },
    lastConversation: state.lastConversation,
    app: {
      version: CLAUDE_VERSION,
      claude: CLAUDE_BIN,
      token: TOKEN,
      home: HOME,
      permissionModes: PERMISSION_MODES
    }
  };
}

function readClaudeSettings() {
  try {
    return JSON.parse(fs.readFileSync(path.join(HOME, '.claude', 'settings.json'), 'utf8'));
  } catch {
    return {};
  }
}

function envModel() {
  const s = readClaudeSettings();
  return (s.env && s.env.ANTHROPIC_MODEL) || s.model || '';
}

/** settings.json / 环境变量里配置的默认推理档位（CLAUDE_CODE_EFFORT_LEVEL） */
function envEffort() {
  const s = readClaudeSettings();
  const raw = ((s.env && s.env.CLAUDE_CODE_EFFORT_LEVEL) || process.env.CLAUDE_CODE_EFFORT_LEVEL || '')
    .trim().toLowerCase();
  return EFFORT_LEVELS.includes(raw) ? raw : '';
}

// 本机 ~/.claude/settings.json 里配置的模型，按「默认」优先去重后提供给下拉框
function localModels() {
  const s = readClaudeSettings();
  const env = s.env || {};
  const entries = [
    [s.model, '默认'],
    [env.ANTHROPIC_MODEL, '默认'],
    [env.ANTHROPIC_DEFAULT_OPUS_MODEL, 'opus'],
    [env.ANTHROPIC_DEFAULT_SONNET_MODEL, 'sonnet'],
    [env.ANTHROPIC_DEFAULT_HAIKU_MODEL, 'haiku'],
    [env.ANTHROPIC_SMALL_FAST_MODEL, 'haiku'],
    [env.CLAUDE_CODE_SUBAGENT_MODEL, '子代理']
  ];
  const map = new Map();
  for (const [raw, role] of entries) {
    const id = String(raw || '').trim();
    if (!id) continue;
    if (!map.has(id)) map.set(id, []);
    if (role && !map.get(id).includes(role)) map.get(id).push(role);
  }
  // 没配置任何模型时退回 Claude Code 自带别名
  if (!map.size) {
    for (const id of ['sonnet', 'opus', 'haiku']) map.set(id, []);
  }
  return [...map.entries()].map(([id, roles]) => ({ id, roles }));
}

// ---------------------------------------------------------------- 路由

async function handleApi(req, res, url) {
  const p = url.pathname;

  if (p === '/api/ping') return json(res, { app: 'claude-gui', port: SERVER_PORT });

  const token = requestToken(req, url);
  if (token !== TOKEN) return json(res, { error: '未授权' }, 401);

  if (p === '/api/upload' && req.method === 'POST') {
    const id = url.searchParams.get('id') || '';
    const conv = lookupConv(id);
    if (!conv) return json(res, { error: '会话不存在' }, 404);
    let cwdStat;
    try { cwdStat = await fsp.stat(conv.cwd); } catch { return json(res, { error: '对话文件夹不存在' }, 400); }
    if (!cwdStat.isDirectory()) return json(res, { error: '对话文件夹不存在' }, 400);

    const len = Number(req.headers['content-length'] || 0);
    if (len > MAX_UPLOAD_BYTES) return json(res, { error: '文件超过 100MB' }, 413);

    let rawName = String(req.headers['x-file-name'] || '');
    try { rawName = decodeURIComponent(rawName); } catch { /* 保持原样 */ }
    const safeName = (path.basename(rawName).replace(/[\\/:*?"<>|\r\n]/g, '_').trim() || 'upload').slice(0, 120);

    const dir = path.join(conv.cwd, '.workbench-uploads');
    try {
      await fsp.mkdir(dir, { recursive: true });
    } catch (e) {
      return json(res, { error: '无法创建上传目录：' + e.message }, 500);
    }

    const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
    const ext = path.extname(safeName);
    const stem = safeName.slice(0, safeName.length - ext.length) || 'upload';
    let target = path.join(dir, `${stamp}-${stem}${ext}`);
    for (let i = 2; fs.existsSync(target); i++) {
      target = path.join(dir, `${stamp}-${stem}-${i}${ext}`);
    }
    const part = target + '.part';

    let size = 0;
    let over = false;
    try {
      await new Promise((resolve, reject) => {
        const ws = fs.createWriteStream(part);
        req.on('data', chunk => {
          size += chunk.length;
          if (size > MAX_UPLOAD_BYTES && !over) {
            over = true;
            ws.destroy();
          }
        });
        req.on('error', reject);
        ws.on('error', reject);
        ws.on('finish', resolve);
        req.pipe(ws);
      });
    } catch (e) {
      try { await fsp.unlink(part); } catch { /* ignore */ }
      if (over) return json(res, { error: '文件超过 100MB' }, 413);
      return json(res, { error: '上传失败：' + e.message }, 500);
    }

    if (over) {
      try { await fsp.unlink(part); } catch { /* ignore */ }
      return json(res, { error: '文件超过 100MB' }, 413);
    }

    try {
      await fsp.rename(part, target);
      const st = await fsp.stat(target);
      return json(res, {
        ok: true,
        path: target,
        name: safeName,
        storedName: path.basename(target),
        size: st.size
      });
    } catch (e) {
      try { await fsp.unlink(part); } catch { /* ignore */ }
      return json(res, { error: '保存失败：' + e.message }, 500);
    }
  }

  if (p === '/api/download' && req.method === 'GET') {
    const id = url.searchParams.get('id') || '';
    const rawPath = url.searchParams.get('path') || '';
    const conv = lookupConv(id);
    if (!conv) return json(res, { error: '会话不存在' }, 404);

    let targetReal;
    let stat;
    try {
      const cwdReal = fs.realpathSync(conv.cwd);
      const target = path.resolve(conv.cwd, rawPath);
      targetReal = fs.realpathSync(target);
      const rel = path.relative(cwdReal, targetReal);
      if (rel.startsWith('..') || path.isAbsolute(rel)) {
        return json(res, { error: '只能下载当前对话目录内的文件' }, 403);
      }
      stat = fs.statSync(targetReal);
      if (!stat.isFile()) return json(res, { error: '不是文件' }, 400);
    } catch {
      return json(res, { error: '文件不存在' }, 404);
    }

    const name = path.basename(targetReal);
    const ext = path.extname(targetReal).toLowerCase();
    const type = MIME[ext] || DOWNLOAD_MIME[ext] || 'application/octet-stream';
    const fallback = name.replace(/[^\x20-\x7E]/g, '_').replace(/["\\]/g, '_');
    res.writeHead(200, {
      'content-type': type,
      'content-length': stat.size,
      'content-disposition':
        `attachment; filename="${fallback}"; filename*=UTF-8''${encodeURIComponent(name)}`,
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff'
    });
    const stream = fs.createReadStream(targetReal);
    stream.on('error', () => { try { res.destroy(); } catch { /* ignore */ } });
    stream.pipe(res);
    return;
  }

  if (p === '/api/balance' && req.method === 'GET') {
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

  if (p === '/api/remote' && req.method === 'GET') {
    return json(res, {
      enabled: remoteEnabled,
      port: SERVER_PORT,
      loopback: isLoopback(req),
      urls: remoteUrls()
    });
  }

  if (p === '/api/remote' && req.method === 'POST') {
    let body;
    try { body = await readJson(req); } catch (e) { return json(res, { error: e.message }, 400); }
    const enabled = !!body.enabled;
    const changed = enabled !== remoteEnabled;
    if (changed) {
      remoteEnabled = enabled;
      writeRemoteEnabled(enabled);
    }
    // 先回包，再重绑端口：closeAllConnections() 会把当前这条请求也断掉。
    const result = { ok: true, enabled: remoteEnabled, changed, urls: remoteUrls() };
    json(res, result);
    if (changed) {
      setTimeout(() => {
        applyRemoteBind(remoteEnabled).catch(err => {
          console.error('[remote] 切换监听地址失败：' + err.message);
          remoteEnabled = !remoteEnabled;
          writeRemoteEnabled(remoteEnabled);
        });
      }, 150);
    }
    return;
  }

  if (p === '/api/browser/open' && req.method === 'POST') {
    try {
      const result = await openChromeBrowser();
      // Chrome 缺失是可恢复状态：客户端会弹原生目录选择框，让用户指定安装目录。
      return json(res, result);
    } catch (e) {
      return json(res, { ok: false, error: e.message }, 500);
    }
  }

  if (p === '/api/browser/chrome-path' && req.method === 'GET') {
    return json(res, {
      ok: true,
      config: readChromeConfig(),
      detected: findChromePath()
    });
  }

  if (p === '/api/browser/chrome-path' && req.method === 'POST') {
    let body;
    try { body = await readJson(req); } catch (e) { return json(res, { error: e.message }, 400); }
    const picked = String(body.path || body.dir || '').trim();
    const chromeExe = resolveChromePath(picked);
    if (!chromeExe) {
      return json(res, { ok: false, error: '所选目录里没有找到 chrome.exe' }, 400);
    }
    try {
      writeChromeConfig({ path: chromeExe, updatedAt: new Date().toISOString() });
    } catch (e) {
      return json(res, { ok: false, error: '保存 Chrome 路径失败：' + e.message }, 500);
    }
    return json(res, { ok: true, path: chromeExe });
  }

  if (p === '/api/update/check' && req.method === 'GET') {
    try {
      return json(res, await getUpdateStatus(url.searchParams.get('refresh') === '1'));
    } catch (e) {
      return json(res, { configured: true, available: false, error: e.message });
    }
  }

  if (p === '/api/bootstrap' && req.method === 'GET') {
    return json(res, await buildBootstrap());
  }

  if (p === '/api/extensions' && req.method === 'GET') {
    try {
      return json(res, await listExtensions(url.searchParams.get('cwd') || ''));
    } catch (e) {
      return json(res, { error: '读取技能/MCP 失败：' + e.message }, 500);
    }
  }

  if (p === '/api/extensions/skill/remove' && req.method === 'POST') {
    let body;
    try { body = await readJson(req); } catch (e) { return json(res, { error: e.message }, 400); }
    try {
      const r = await removeSkillDir(String(body.path || ''), String(body.cwd || ''));
      return json(res, { ok: true, ...r });
    } catch (e) {
      return json(res, { error: e.message }, 400);
    }
  }

  if (p === '/api/extensions/mcp/remove' && req.method === 'POST') {
    let body;
    try { body = await readJson(req); } catch (e) { return json(res, { error: e.message }, 400); }
    try {
      const r = await removeMcpServer(String(body.name || ''), String(body.scope || ''), String(body.cwd || ''));
      return json(res, { ok: true, ...r });
    } catch (e) {
      return json(res, { error: e.message }, 400);
    }
  }

  if (p === '/api/plugins' && req.method === 'GET') {
    try {
      return json(res, await listPlugins(url.searchParams.get('refresh') === '1'));
    } catch (e) {
      return json(res, { error: '读取插件失败：' + e.message }, 500);
    }
  }

  if (p === '/api/plugins/action' && req.method === 'POST') {
    let body;
    try { body = await readJson(req); } catch (e) { return json(res, { error: e.message }, 400); }
    try {
      const r = await pluginAction(String(body.action || ''), String(body.id || ''), String(body.scope || ''));
      return json(res, { ok: true, ...r });
    } catch (e) {
      return json(res, { error: e.message }, 400);
    }
  }

  if (p === '/api/stream' && req.method === 'GET') {
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no'
    });
    res.write('retry: 3000\n\n');
    sseClients.add(res);
    const ping = setInterval(() => {
      try { res.write(': ping\n\n'); } catch { /* ignore */ }
    }, 15000);
    req.on('close', () => {
      clearInterval(ping);
      sseClients.delete(res);
    });
    return;
  }

  if (p === '/api/conversation' && req.method === 'GET') {
    const id = url.searchParams.get('id');
    const conv = lookupConv(id);
    if (!conv) return json(res, { error: '会话不存在' }, 404);
    const handoff = url.searchParams.get('handoff') === '1';
    const file = await findSessionFile(id);
    let messages = [];
    let contextTokens = -1;
    let compacted = false;
    if (file) {
      try {
        const r = await readTranscript(file);
        messages = handoff ? handoffMessages(r.messages) : r.messages;
        contextTokens = r.contextTokens;
        compacted = r.compacted;
      } catch (e) { console.error('[read]', e.message); }
    }

    // 手机端分页：默认不传 limit 保持电脑端行为（返回全部）。
    // 手机传 limit + light，只返回最后一页并截断工具大字段。
    const light = url.searchParams.get('light') === '1';
    const limitParam = url.searchParams.get('limit');
    if (light) messages = lightenMessages(messages);
    const total = messages.length;
    let start = 0;
    let end = total;
    let hasMore = false;
    if (!handoff && limitParam != null) {
      const limit = Math.max(1, Math.min(100, Number(limitParam) || 30));
      const beforeRaw = url.searchParams.get('before');
      if (beforeRaw != null && beforeRaw !== '') {
        end = Math.max(0, Math.min(total, Number(beforeRaw) || 0));
      }
      start = Math.max(0, end - limit);
      hasMore = start > 0;
      messages = messages.slice(start, end);
    }

    const s = state.conversations[id];
    const liveRunner = runners.get(id);
    const running = !!(liveRunner && liveRunner.busy);
    return json(res, {
      id,
      cwd: conv.cwd,
      // state 里的标题是权威值（可能被用户清空），不要再回退到可能过期的索引
      title: s ? (s.title || null) : ((convIndex.get(id) && convIndex.get(id).title) || null),
      model: conv.model,
      effort: conv.effort,
      permissionMode: conv.permissionMode,
      messages,
      start,
      end,
      total,
      hasMore,
      contextTokens,
      compacted,
      running,
      live: running && liveRunner ? liveRunner.liveSnapshot() : null,
      file
    });
  }

  if (p === '/api/conversation' && req.method === 'POST') {
    const body = await readJson(req);
    const cwd = String(body.cwd || '').trim();
    if (!cwd) return json(res, { error: '请先选择文件夹' }, 400);
    let stat;
    try { stat = await fsp.stat(cwd); } catch { return json(res, { error: '文件夹不存在' }, 400); }
    if (!stat.isDirectory()) return json(res, { error: '不是文件夹' }, 400);

    const id = crypto.randomUUID();
    const conv = {
      cwd: path.resolve(cwd),
      title: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      model: state.settings.defaultModel === 'default' ? '' : (state.settings.defaultModel || ''),
      effort: state.settings.defaultEffort || '',
      permissionMode: state.settings.defaultPermissionMode
    };
    state.conversations[id] = conv;
    await addFolderIfNeeded(cwd);
    state.lastConversation = id;
    convIndex.set(id, { id, cwd: conv.cwd, title: '新对话' });
    saveState();
    broadcast({ t: 'conv_created', id, cwd: conv.cwd });
    return json(res, { id, cwd: conv.cwd, title: null, model: conv.model, effort: conv.effort, permissionMode: conv.permissionMode, messages: [], running: false });
  }

  if (p === '/api/conversation' && req.method === 'PATCH') {
    const id = url.searchParams.get('id');
    const body = await readJson(req);
    const s = ensureConvState(id);
    if (typeof body.title === 'string') {
      s.title = body.title.trim() || null;
      const cached = convIndex.get(id);
      if (cached) cached.title = s.title;
    }
    if (typeof body.model === 'string') s.model = body.model;
    if (typeof body.effort === 'string' && (body.effort === '' || EFFORT_LEVELS.includes(body.effort))) {
      s.effort = body.effort;
    }
    if (typeof body.permissionMode === 'string' && PERMISSION_MODES.includes(body.permissionMode)) {
      s.permissionMode = body.permissionMode;
    }
    if (typeof body.pinned === 'boolean') s.pinned = body.pinned;
    s.updatedAt = new Date().toISOString();
    saveState();
    broadcast({
      t: 'conv_meta', id, title: s.title, permissionMode: s.permissionMode,
      model: s.model, effort: s.effort || ''
    });
    // 用户清空了标题 → 恢复自动生成
    if (typeof body.title === 'string' && !s.title) {
      titleAttempts.delete(id);
      titleSkipped.delete(id);
      scheduleAutoTitle(id, null);
    }
    return json(res, { ok: true, conversation: s });
  }

  if (p === '/api/conversation' && req.method === 'DELETE') {
    const id = url.searchParams.get('id');
    const r = runners.get(id);
    if (r) { r.kill(); runners.delete(id); }
    const file = await findSessionFile(id);
    if (file) { try { await fsp.unlink(file); } catch (e) { return json(res, { error: '删除失败：' + e.message }, 500); } }
    delete state.conversations[id];
    convIndex.delete(id);
    if (state.lastConversation === id) state.lastConversation = null;
    sessionFileCache.delete(id);
    dropTurnMeta(id);
    saveState();
    broadcast({ t: 'conv_removed', id });
    return json(res, { ok: true });
  }

  if (p === '/api/chat' && req.method === 'POST') {
    let body;
    try {
      body = await readJson(req, MAX_CHAT_BODY);
    } catch (e) {
      return json(res, { error: e.message }, e.message === '请求体过大' ? 413 : 400);
    }

    const id = String(body.id || '');
    const text = String(body.text || '');
    let images;
    try { images = validateImages(body.images); }
    catch (e) { return json(res, { error: e.message }, 400); }

    if (!text.trim() && !images.length) return json(res, { error: '消息为空' }, 400);

    const conv = lookupConv(id);
    if (!conv) return json(res, { error: '会话不存在' }, 404);
    if (!fs.existsSync(conv.cwd)) return json(res, { error: '文件夹不存在：' + conv.cwd }, 400);

    const r = runnerFor(conv);
    if (r.busy) return json(res, { error: '上一条消息还在处理中' }, 409);

    try {
      await r.start(conv, text, images);
    } catch (e) {
      broadcast({ convId: id, t: 'error', message: e.message });
      return json(res, { error: e.message }, 500);
    }
    return json(res, { ok: true });
  }

  if (p === '/api/steer' && req.method === 'POST') {
    let body;
    try {
      body = await readJson(req, MAX_CHAT_BODY);
    } catch (e) {
      return json(res, { error: e.message }, e.message === '请求体过大' ? 413 : 400);
    }

    const id = String(body.id || '');
    const text = String(body.text || '');
    let images;
    try { images = validateImages(body.images); }
    catch (e) { return json(res, { error: e.message }, 400); }

    if (!text.trim() && !images.length) return json(res, { error: '消息为空' }, 400);

    const conv = lookupConv(id);
    if (!conv) return json(res, { error: '会话不存在' }, 404);
    if (!fs.existsSync(conv.cwd)) return json(res, { error: '文件夹不存在：' + conv.cwd }, 400);

    const r = runnerFor(conv);
    if (!r || !r.busy) {
      return json(res, { error: '本轮刚刚结束，请重新发送', notRunning: true }, 409);
    }

    try {
      r.steer(text, images);
    } catch (e) {
      return json(res, { error: e.message, notRunning: !r.busy }, 409);
    }
    return json(res, { ok: true });
  }

  if (p === '/api/phone/current' && req.method === 'GET') {
    if (!IS_PHONE) return json(res, { error: '仅手机端可用' }, 404);
    // runners 里可能还留着已经结束的 Runner；必须看 busy，不能只看 key 是否存在。
    const active = [];
    for (const [id, r] of runners) {
      if (r && r.busy) active.push(id);
    }
    return json(res, {
      ok: true,
      id: active[0] || '',
      running: active.length > 0,
      count: active.length
    });
  }

  if (p === '/api/stop' && req.method === 'POST') {
    const body = await readJson(req);
    const id = String(body.id || '');
    if (id) {
      const r = runners.get(id);
      if (r) r.stop();
    } else {
      // 悬浮窗「停止」：没有指定会话时停掉当前所有运行中的会话。
      for (const r of runners.values()) r.stop();
    }
    return json(res, { ok: true });
  }

  if (p === '/api/permission' && req.method === 'POST') {
    const body = await readJson(req);
    const r = runners.get(String(body.id || ''));
    if (!r) return json(res, { error: '会话未运行' }, 400);
    const message = typeof body.message === 'string' ? body.message.slice(0, 500) : '';
    const ok = r.respondPermission(String(body.requestId || ''), !!body.allow, message);
    return json(res, { ok });
  }

  if (p === '/api/ask' && req.method === 'POST') {
    const body = await readJson(req);
    const id = String(body.id || '');
    const r = runners.get(id);
    if (!r) return json(res, { error: '会话未运行' }, 400);
    const requestId = String(body.requestId || '');
    if (!r.askMeta.has(requestId)) return json(res, { error: '问题已经结束' }, 409);
    let answers = null;
    if (body.answers != null) {
      if (typeof body.answers !== 'object' || Array.isArray(body.answers)) {
        return json(res, { error: '答案格式无效' }, 400);
      }
      answers = {};
      for (const [k, v] of Object.entries(body.answers)) {
        if (typeof v !== 'string') return json(res, { error: '答案格式无效' }, 400);
        answers[k] = v;
      }
    }
    const ok = r.respondAsk(requestId, answers, { cancel: !!body.cancel });
    return json(res, { ok });
  }

  if (p === '/api/fs' && req.method === 'GET') {
    try {
      const dir = url.searchParams.get('path') || '';
      return json(res, await browseDir(dir, url.searchParams.get('files') === '1'));
    } catch (e) {
      return json(res, { error: e.message }, 400);
    }
  }

  if (p === '/api/fs/mkdir' && req.method === 'POST') {
    const body = await readJson(req);
    const parent = String(body.path || '');
    const name = String(body.name || '').trim();
    if (!parent || !name) return json(res, { error: '参数不完整' }, 400);
    if (/[\\/:*?"<>|]/.test(name)) return json(res, { error: '文件夹名不能包含 \\ / : * ? " < > |' }, 400);
    const target = path.join(parent, name);
    try {
      await fsp.mkdir(target, { recursive: false });
      return json(res, { ok: true, path: target });
    } catch (e) {
      return json(res, { error: e.code === 'EEXIST' ? '文件夹已存在' : e.message }, 400);
    }
  }

  if (p === '/api/fs/delete' && req.method === 'POST') {
    if (!IS_PHONE) return json(res, { error: '当前模式不允许删除文件夹' }, 403);
    const body = await readJson(req);
    const target = path.resolve(String(body.path || ''));
    const roots = ['/workspace', '/sdcard'].map(r => path.resolve(r));
    const root = roots.find(r => target === r || target.startsWith(r + path.sep));
    if (!root) return json(res, { error: '只能删除手机工作区或存储里的文件夹' }, 400);
    if (target === root) return json(res, { error: '不能删除根目录' }, 400);
    const rel = path.relative(root, target);
    if (rel.split(path.sep).includes('Android')) {
      return json(res, { error: '不能删除 Android 系统目录' }, 400);
    }
    let st;
    try { st = await fsp.lstat(target); } catch { return json(res, { error: '文件夹不存在' }, 400); }
    if (!st.isDirectory() && !st.isSymbolicLink()) return json(res, { error: '不是文件夹' }, 400);
    try {
      await fsp.rm(target, { recursive: st.isDirectory(), force: false });
      return json(res, { ok: true, path: target });
    } catch (e) {
      return json(res, { error: '删除失败：' + e.message }, 500);
    }
  }

  if (p === '/api/folder' && req.method === 'POST') {
    const body = await readJson(req);
    const folder = String(body.path || '');
    try {
      const st = await fsp.stat(folder);
      if (!st.isDirectory()) throw new Error('不是文件夹');
    } catch {
      return json(res, { error: '文件夹不存在' }, 400);
    }
    await addFolderIfNeeded(folder);
    return json(res, { ok: true });
  }

  if (p === '/api/folder' && req.method === 'DELETE') {
    const folder = url.searchParams.get('path');
    state.folders = state.folders.filter(f => normKey(f.path) !== normKey(folder));
    saveState();
    return json(res, { ok: true });
  }

  if (p === '/api/reveal' && req.method === 'POST') {
    const body = await readJson(req);
    const target = String(body.path || '');
    if (target && fs.existsSync(target)) {
      try { spawn(IS_WIN ? 'explorer.exe' : 'open', [target], { detached: true, stdio: 'ignore' }).unref(); } catch { /* ignore */ }
    }
    return json(res, { ok: true });
  }

  if (p === '/api/settings' && req.method === 'POST') {
    const body = await readJson(req);
    if (typeof body.defaultPermissionMode === 'string' && PERMISSION_MODES.includes(body.defaultPermissionMode)) {
      state.settings.defaultPermissionMode = body.defaultPermissionMode;
    }
    if (typeof body.defaultModel === 'string') state.settings.defaultModel = body.defaultModel;
    if (typeof body.defaultEffort === 'string' && (body.defaultEffort === '' || EFFORT_LEVELS.includes(body.defaultEffort))) {
      state.settings.defaultEffort = body.defaultEffort;
    }
    saveState();
    return json(res, { ok: true, settings: state.settings });
  }

  return json(res, { error: '未知接口' }, 404);
}

async function serveStatic(req, res, url) {
  const pathname = url.pathname;
  const loopback = isLoopback(req);
  const isPage = pathname === '/' || pathname === '/m' || pathname === '/m/index.html' ||
    pathname === '/remote' || pathname === '/index.html' || pathname === '/m.html';
  // 手机/平板（非本机）默认进移动版；桌面端仍然用完整工作台
  const isMobilePage = pathname === '/m' || pathname === '/m/index.html' || pathname === '/remote' ||
    (!loopback && (pathname === '/' || pathname === '/index.html'));
  const rel = isMobilePage ? '/m.html' : (isPage ? '/index.html' : pathname);

  // 非本机请求：静态资源也要令牌，否则局域网里任何人都能拿到注入了令牌的页面
  if (!loopback && requestToken(req, url) !== TOKEN) {
    if (isPage) return serveRemoteLogin(res, pathname, !!url.searchParams.get('token'));
    res.writeHead(401, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
    res.end('需要访问令牌');
    return;
  }

  const file = path.join(PUBLIC_DIR, decodeURIComponent(rel));
  if (file !== PUBLIC_DIR && !file.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403); res.end('Forbidden'); return;
  }

  let data;
  try { data = await fsp.readFile(file); } catch { res.writeHead(404); res.end('Not Found'); return; }

  // 页面和 PWA manifest 都需要令牌：只有通过鉴权的请求才会走到这里
  const base = path.basename(file);
  if (base === 'index.html' || base === 'm.html' || base === 'manifest.webmanifest') {
    const text = data.toString('utf8').replace(/__TOKEN__/g, TOKEN);
    data = Buffer.from(text, 'utf8');
  }

  const headers = {
    'content-type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
    'cache-control': 'no-cache'
  };
  // 页面用 ?token= 打开一次后写入 Cookie，后续 app.js / styles.css / manifest 由浏览器自动带上
  if (!loopback && url.searchParams.get('token')) {
    headers['set-cookie'] = `gui_token=${encodeURIComponent(TOKEN)}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax`;
  }
  res.writeHead(200, headers);
  res.end(data);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  try {
    if (url.pathname.startsWith('/api/')) return await handleApi(req, res, url);
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); res.end(); return; }
    return await serveStatic(req, res, url);
  } catch (e) {
    console.error('[http]', e);
    if (!res.headersSent) json(res, { error: e.message }, 500);
    else res.end();
  }
});

function writeRuntime() {
  try {
    fs.writeFileSync(RUNTIME_FILE, JSON.stringify({
      pid: process.pid,
      port: SERVER_PORT,
      host: remoteEnabled ? '0.0.0.0' : '127.0.0.1',
      remote: remoteEnabled,
      updatedAt: new Date().toISOString()
    }, null, 2) + '\n', 'utf8');
  } catch { /* 仅用于给脚本读端口，写失败不影响服务 */ }
}

let rebindChain = Promise.resolve();

/** 运行时切换监听地址：远程开 → 0.0.0.0，关 → 127.0.0.1 */
function applyRemoteBind(enabled) {
  const host = enabled ? '0.0.0.0' : '127.0.0.1';
  const run = async () => {
    await new Promise(resolve => {
      try { server.close(() => resolve()); } catch { resolve(); }
      // SSE / keep-alive 连接会拖住 close 回调，必须主动断掉；界面会自动重连
      if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
    });
    await new Promise((resolve, reject) => {
      const onError = err => { server.removeListener('listening', onListen); reject(err); };
      const onListen = () => { server.removeListener('error', onError); resolve(); };
      server.once('error', onError);
      server.once('listening', onListen);
      server.listen(SERVER_PORT, host);
    });
    writeRuntime();
  };
  rebindChain = rebindChain.then(run, run);
  return rebindChain;
}

// ---------------------------------------------------------------- 启动

let SERVER_PORT = Number(process.env.PORT || 4711);
let triedPort = 0;

function openBrowser(url) {
  if (process.env.NO_OPEN) return;
  try {
    // 空标题的 start 在部分 Windows 上会报「Not enough memory resources…」，给个标题。
    if (IS_WIN) spawn('cmd.exe', ['/c', 'start', 'Claude Workbench', url], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
    else if (process.platform === 'darwin') spawn('open', [url], { detached: true, stdio: 'ignore' }).unref();
    else spawn('xdg-open', [url], { detached: true, stdio: 'ignore' }).unref();
  } catch { /* ignore */ }
}

async function isOurInstance(port) {
  return new Promise(resolve => {
    const req = http.get({ host: '127.0.0.1', port, path: '/api/ping', timeout: 800 }, res => {
      let body = '';
      res.on('data', d => { body += d; });
      res.on('end', () => {
        try { resolve(JSON.parse(body).app === 'claude-gui'); } catch { resolve(false); }
      });
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

function readChromeConfig() {
  try {
    const data = JSON.parse(fs.readFileSync(CHROME_CONFIG, 'utf8'));
    if (!data || typeof data !== 'object') return null;
    const pathValue = typeof data.path === 'string' ? data.path.trim() : '';
    const dirValue = typeof data.dir === 'string' ? data.dir.trim() : '';
    return pathValue || dirValue ? { path: pathValue, dir: dirValue } : null;
  } catch {
    return null;
  }
}

function resolveChromePath(input) {
  if (!input) return '';
  const candidates = [input];
  try {
    if (fs.statSync(input).isDirectory()) {
      candidates.push(
        path.join(input, 'chrome.exe'),
        path.join(input, 'Application', 'chrome.exe'),
        path.join(input, 'chrome-win64', 'chrome.exe'),
        path.join(input, 'chrome-win32', 'chrome.exe')
      );
    }
  } catch { /* 不存在时按文件判断 */ }
  return candidates.find(file => {
    try { return fs.statSync(file).isFile(); } catch { return false; }
  }) || '';
}

function findChromePath() {
  const config = readChromeConfig();
  const configured = config ? resolveChromePath(config.path || config.dir) : '';
  if (configured) return configured;

  const candidates = [
    process.env.CHROME_PATH,
    path.join(process.env.LOCALAPPDATA || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
    path.join(process.env.PROGRAMFILES || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
    path.join(process.env['PROGRAMFILES(X86)'] || '', 'Google', 'Chrome', 'Application', 'chrome.exe')
  ].filter(Boolean);
  return candidates.map(resolveChromePath).find(Boolean) || '';
}

function writeChromeConfig(config) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(CHROME_CONFIG, JSON.stringify(config, null, 2) + '\n', 'utf8');
}

function openChromeBrowser() {
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawn(process.execPath, [CHROME_BROWSER_JS], {
        cwd: ROOT,
        env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' },
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true
      });
    } catch (e) {
      reject(e);
      return;
    }

    let out = '';
    let err = '';
    let settled = false;
    const finish = value => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(value);
    };
    const timer = setTimeout(() => {
      try { child.kill(); } catch { /* ignore */ }
      finish({ ok: false, error: 'Chrome 启动超时' });
    }, 60000);

    child.stdout.on('data', d => { out += d; });
    child.stderr.on('data', d => { err += d; });
    child.on('error', e => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(e);
    });
    child.on('close', code => {
      if (settled) return;
      if (code === 0) {
        let data = null;
        try {
          const line = out.trim().split(/\r?\n/).filter(Boolean).pop();
          data = JSON.parse(line);
        } catch { /* ignore */ }
        finish(data && data.ok ? data : { ok: true });
        return;
      }
      const raw = (err || out || `Chrome 启动失败（退出码 ${code}）`).trim();
      const needChrome = /CHROME_NOT_FOUND/i.test(raw);
      const message = raw.replace(/^CHROME_NOT_FOUND:\s*/i, '');
      finish({ ok: false, error: message, needChrome });
    });
  });
}

async function main() {
  ensurePlaywrightSkill();
  await resolveClaude();
  fs.mkdirSync(DATA_DIR, { recursive: true });

  const startPort = SERVER_PORT;
  let port = startPort;
  for (let i = 0; i < 20; i++) {
    triedPort = port;
    const ok = await new Promise(resolve => {
      const onError = e => { server.removeListener('listening', onListen); resolve(e.code); };
      const onListen = () => { server.removeListener('error', onError); resolve('ok'); };
      server.once('error', onError);
      server.once('listening', onListen);
      server.listen(port, remoteEnabled ? '0.0.0.0' : '127.0.0.1');
    });

    if (ok === 'ok') {
      SERVER_PORT = port;
      const url = `http://127.0.0.1:${port}/`;
      writeRuntime();
      console.log('');
      console.log('  Claude Code 工作台已启动');
      console.log(`  ${url}`);
      if (remoteEnabled) {
        const urls = remoteUrls();
        if (urls.length) {
          console.log('');
          console.log('  远程访问已开启（手机与本机需在同一局域网或 Tailscale）：');
          for (const u of urls) console.log(`  ${u.url}`);
        } else {
          console.log('');
          console.log('  远程访问已开启，但没有检测到可用的局域网 IPv4 地址。');
        }
      }
      console.log('');
      console.log('  关闭这个窗口即可停止服务。');
      console.log('');
      openBrowser(url);
      setTimeout(() => { backfillTitles().catch(() => {}); }, 1500);
      return;
    }

    if (ok === 'EADDRINUSE') {
      if (await isOurInstance(port)) {
        const url = `http://127.0.0.1:${port}/`;
        console.log('  工作台已经在运行，正在打开浏览器…');
        openBrowser(url);
        process.exit(0);
      }
      port++;
      continue;
    }

    console.error('启动失败：', ok);
    process.exit(1);
  }
  console.error('找不到可用端口');
  process.exit(1);
}

process.on('SIGINT', () => {
  for (const r of runners.values()) r.kill();
  process.exit(0);
});
process.on('SIGTERM', () => {
  for (const r of runners.values()) r.kill();
  process.exit(0);
});
process.on('exit', () => {
  for (const r of runners.values()) {
    try { r.kill(); } catch { /* ignore */ }
  }
});

main();

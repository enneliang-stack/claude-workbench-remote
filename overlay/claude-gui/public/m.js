'use strict';

/**
 * 手机端工作台：只做「看进度 / 发消息 / 插话 / 回应确认 / 新建对话」，
 * 实际任务仍由本机的 claude 进程执行。复用 server.js 的 /api/* 接口。
 */
(() => {
  const TOKEN = window.__GUI_TOKEN__ || '';
  const IS_APP = new URLSearchParams(location.search).get('app') === '1';

  const $ = sel => document.querySelector(sel);
  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  };
  const escapeHtml = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));

  const S = {
    boot: null,
    convId: null,
    conv: null,
    live: null,
    running: false,
    liveEls: new Map(),     // block key -> { textEl } / { tool }
    pendingPerm: null,
    pendingAsk: null,
    askSel: null,
    askOther: null,
    askTimer: null,
    pendingEcho: null,      // 新消息的 user_echo 可能先于 run_start 到达
    deleteId: null,         // 当前要删除的对话（列表删除或对话设置删除）
    pendingImages: [],      // 已选、待发送的图片
    pendingRefs: [],        // 已引用、待发送的其它对话
    refSel: new Map(),
    pendingFiles: [],       // 待发送/引用的文件（手机上传或电脑路径）
    pcPath: '',
    pcParent: null,
    pcEntries: [],
    pcSel: new Map(),
    collapsedFolders: new Set(),
    drafts: new Map(),      // convId -> { text, images, refs }
    downloads: [],          // 手机 App 已下载的文件
    loadingOlder: false,
    sending: false,
    liveProcessEl: null,
    liveProcessBody: null,
    liveProcessMeta: null,
    liveProcessState: null,
    liveProcessCollapse: null,
    liveProcessCount: 0,
    liveProcessFiles: new Set(),
    conn: false,
    wasDown: false,
    es: null
  };

  // ---------------------------------------------------------------- 基础

  async function api(path, opts = {}) {
    const headers = { 'x-gui-token': TOKEN };
    if (opts.body != null) headers['content-type'] = 'application/json';
    const timeout = Number(opts.timeout) > 0 ? Number(opts.timeout) : 20000;
    const { timeout: _timeout, ...fetchOpts } = opts;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeout);
    try {
      const res = await fetch(path, {
        ...fetchOpts,
        signal: ctrl.signal,
        headers: { ...headers, ...(opts.headers || {}) }
      });
      let data = null;
      try {
        data = await res.json();
      } catch (e) {
        if (e && e.name === 'AbortError') {
          throw new Error('请求超时，网络太慢或服务未响应');
        }
      }
      if (!res.ok) {
        const err = new Error((data && data.error) || `HTTP ${res.status}`);
        err.notRunning = !!(data && data.notRunning);
        throw err;
      }
      return data || {};
    } catch (e) {
      if (e && e.name === 'AbortError') throw new Error('请求超时，网络太慢或服务未响应');
      throw e;
    } finally {
      clearTimeout(timer);
    }
  }

  let toastTimer = null;
  function toast(msg, kind = '') {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast' + (kind === 'err' ? ' err' : '');
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 2800);
  }

  function fmtTok(n) {
    n = Number(n) || 0;
    if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  }

  function setConn(on) {
    S.conn = on;
    $('#connDot').className = 'conn-dot ' + (on ? 'on' : 'off');
    $('#connBanner').hidden = on;
    $('#connBanner2').hidden = on;
  }

  let bootTimer = null;
  function scheduleBootRefresh() {
    clearTimeout(bootTimer);
    bootTimer = setTimeout(() => { loadBoot().catch(() => {}); }, 1200);
  }

  // ---------------------------------------------------------------- 列表

  async function loadBoot() {
    S.boot = await api('/api/bootstrap');
    renderList();
  }

  function formatBalance(data) {
    const list = Array.isArray(data && data.balances) ? data.balances : [];
    if (!list.length) {
      return data && data.isAvailable === false ? '余额不可用' : '余额 --';
    }
    return list.map(info => {
      const symbol = info.currency === 'CNY' ? '¥' : info.currency === 'USD' ? '$' : '';
      return `${symbol}${info.total_balance ?? '-'}`;
    }).join(' / ');
  }

  async function loadBalance(force) {
    const btn = $('#balanceBtn');
    if (!btn || btn.dataset.busy) return '';
    btn.dataset.busy = '1';
    if (force) btn.textContent = '查询中…';
    try {
      const data = await api('/api/balance' + (force ? '?refresh=1' : ''), { timeout: 25000 });
      if (!data || data.ok === false) {
        throw new Error((data && data.error) || '余额不可用');
      }
      const text = formatBalance(data);
      btn.textContent = text;
      btn.title = text;
      if (force) toast(text);
      return text;
    } catch (e) {
      const msg = /未知接口/.test(e.message)
        ? '余额接口需要重启一次工作台客户端后生效'
        : e.message;
      btn.textContent = '余额 --';
      btn.title = msg;
      if (force) toast(msg, 'err');
      return '';
    } finally {
      delete btn.dataset.busy;
    }
  }

  function saveFolderCollapsed() {
    try {
      localStorage.setItem('m.collapsedFolders', JSON.stringify([...S.collapsedFolders]));
    } catch { /* ignore */ }
  }

  function loadFolderCollapsed() {
    try {
      S.collapsedFolders = new Set(JSON.parse(localStorage.getItem('m.collapsedFolders') || '[]'));
    } catch {
      S.collapsedFolders = new Set();
    }
  }

  function convTime(iso) {
    const d = iso ? new Date(iso) : null;
    if (!d || Number.isNaN(d.getTime())) return '';
    const now = new Date();
    if (d.toDateString() === now.toDateString()) return d.toTimeString().slice(0, 5);
    const y = new Date(now);
    y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return '昨天';
    return `${d.getMonth() + 1}/${d.getDate()}`;
  }

  function renderList() {
    const box = $('#convList');
    box.innerHTML = '';
    const q = ($('#searchInput').value || '').trim().toLowerCase();
    let total = 0;

    for (const folder of (S.boot && S.boot.folders) || []) {
      const convs = (folder.conversations || []).filter(c => {
        if (!q) return true;
        return `${c.title || ''} ${c.preview || ''}`.toLowerCase().includes(q);
      });
      if (!convs.length) continue;
      total += convs.length;

      const sec = el('section', 'folder');
      const key = folder.path || folder.name;
      const collapsed = !q && S.collapsedFolders.has(key);
      const head = el('button', 'folder-head');
      head.type = 'button';
      head.innerHTML =
        `<span class="chev">${collapsed ? '▸' : '▾'}</span>` +
        `<span class="fname">${escapeHtml(folder.name)}</span>` +
        `<span class="fcount">${convs.length}</span>`;
      head.onclick = () => {
        if (collapsed) S.collapsedFolders.delete(key);
        else S.collapsedFolders.add(key);
        saveFolderCollapsed();
        renderList();
      };
      sec.appendChild(head);

      if (!collapsed) {
        for (const c of convs) {
          const row = el('button', 'conv-row');
          row.type = 'button';
          const badges = [];
          if (c.running) badges.push('<span class="badge run">运行中</span>');
          if (c.waiting) badges.push('<span class="badge wait">待确认</span>');
          row.innerHTML =
            `<div class="conv-top"><span class="conv-title">${escapeHtml(c.title || '未命名对话')}</span>` +
            `<span class="conv-time">${escapeHtml(convTime(c.updatedAt))}</span></div>` +
            `<div class="conv-sub"><span class="conv-preview">${escapeHtml(c.preview || '')}</span>${badges.join('')}</div>`;
          // 删除不在列表里常驻 ×；长按对话行约 550ms 再确认删除。
          let pressTimer = null;
          let longPressed = false;
          const clearPress = () => {
            if (pressTimer) {
              clearTimeout(pressTimer);
              pressTimer = null;
            }
          };
          const startPress = () => {
            longPressed = false;
            clearPress();
            pressTimer = setTimeout(() => {
              longPressed = true;
              askDeleteConv(c.id);
            }, 550);
          };
          row.addEventListener('touchstart', startPress, { passive: true });
          row.addEventListener('touchend', clearPress);
          row.addEventListener('touchcancel', clearPress);
          row.addEventListener('touchmove', clearPress, { passive: true });
          row.addEventListener('mousedown', startPress);
          row.addEventListener('mouseup', clearPress);
          row.addEventListener('mouseleave', clearPress);
          row.addEventListener('contextmenu', e => e.preventDefault());
          row.onclick = e => {
            if (longPressed) {
              longPressed = false;
              e.preventDefault();
              e.stopPropagation();
              return;
            }
            openConv(c.id);
          };
          sec.appendChild(row);
        }
      }
      box.appendChild(sec);
    }

    if (!total) {
      box.innerHTML = `<div class="empty">${q ? '没有匹配的对话' : '还没有对话，点右上角 ＋ 新建'}</div>`;
    }
  }

  // ---------------------------------------------------------------- 会话

  function showChat(on) {
    $('#listScreen').hidden = on;
    $('#chatScreen').hidden = !on;
    if (!on) {
      const collapseBtn = document.getElementById('processCollapseBtn');
      if (collapseBtn) collapseBtn.hidden = true;
      try {
        if (window.AndroidDownloads && AndroidDownloads.phoneChatHidden) {
          AndroidDownloads.phoneChatHidden();
        }
      } catch { /* 非 App 环境 */ }
    }
  }

  function saveDraft(id) {
    if (!id) return;
    const text = $('#input').value;
    const images = S.pendingImages.slice();
    const refs = S.pendingRefs.slice();
    const files = S.pendingFiles.slice();
    if (!text.trim() && !images.length && !refs.length && !files.length) {
      S.drafts.delete(id);
      return;
    }
    S.drafts.set(id, { text, images, refs, files });
  }

  function restoreDraft(id) {
    const d = S.drafts.get(id);
    $('#input').value = d ? d.text : '';
    S.pendingImages = d ? d.images.slice() : [];
    S.pendingRefs = d ? d.refs.slice() : [];
    S.pendingFiles = d && d.files ? d.files.slice() : [];
    S.refSel = new Map(S.pendingRefs.map(r => [r.id, r]));
    renderPendingImages();
    renderPendingRefs();
    renderPendingFiles();
    autoGrow();
    updateSendState();
  }

  async function openConv(id, push = true) {
    if (S.convId) saveDraft(S.convId);
    if (push) {
      try { history.pushState({ conv: id }, '', '#c=' + encodeURIComponent(id)); } catch { /* ignore */ }
    }
    S.convId = id;
    S.conv = null;
    S.live = null;
    S.running = false;
    stopLivePolling();
    S.pendingPerm = null;
    S.pendingAsk = null;
    S.askSel = null;
    S.askOther = null;
    S.pendingEcho = null;
    S.pendingImages = [];
    S.pendingRefs = [];
    S.pendingFiles = [];
    S.refSel = new Map();
    S.sending = false;
    $('#input').value = '';
    closeStopConfirm();
    setRunProgress('', '');
    renderPendingImages();
    renderPendingRefs();
    renderPendingFiles();
    showChat(true);
    $('#chatTitle').textContent = '加载中…';
    $('#chatMeta').textContent = '';
    $('#messages').innerHTML = '';
    $('#panels').innerHTML = '';
    updateComposer();
    try {
      const data = await api('/api/conversation?id=' + encodeURIComponent(id) + '&limit=30&light=1', { timeout: 30000 });
      renderConversation(data);
      restoreDraft(id);
      try {
        if (window.AndroidDownloads && AndroidDownloads.phoneChatEntered) {
          AndroidDownloads.phoneChatEntered(S.convId || '');
        }
      } catch { /* 非 App 环境 */ }
    } catch (e) {
      toast('打开失败：' + e.message, 'err');
      closeChat(false);
    }
  }

  function closeChat(push = true) {
    if (push && history.state && history.state.conv) {
      history.back();
      return;
    }
    if (S.convId) saveDraft(S.convId);
    S.convId = null;
    S.conv = null;
    S.live = null;
    S.running = false;
    stopLivePolling();
    S.pendingPerm = null;
    S.pendingAsk = null;
    S.askSel = null;
    S.askOther = null;
    S.pendingImages = [];
    S.pendingRefs = [];
    S.pendingFiles = [];
    S.refSel = new Map();
    S.sending = false;
    $('#input').value = '';
    closeStopConfirm();
    clearAskTimer();
    setRunProgress('', '');
    renderPendingImages();
    renderPendingRefs();
    renderPendingFiles();
    showChat(false);
    loadBoot().catch(() => {});
  }

  function renderConversation(data) {
    S.conv = data;
    S.convId = data.id;
    S.live = data.live || null;
    S.running = !!data.running;
    S.loadingOlder = false;
    S.conv.start = Number.isFinite(data.start) ? data.start : 0;
    S.conv.hasMore = !!data.hasMore;
    S.conv.total = Number.isFinite(data.total) ? data.total : (data.messages || []).length;
    S.pendingPerm = (S.live && S.live.permission) || null;
    S.pendingAsk = (S.live && S.live.ask) || null;
    if (S.pendingAsk) initAskState();

    $('#chatTitle').textContent = data.title || '未命名对话';
    try {
      if (window.AndroidDownloads && AndroidDownloads.phoneChatVisible) {
        AndroidDownloads.phoneChatVisible(data.id || '');
      }
    } catch { /* 非 App 环境 */ }
    updateHeader();
    updateComposer();
    renderMessages(true);
    renderPanels();
    if (S.running) { syncProgressFromLive(); startLivePolling(); }
    else { setRunProgress('', ''); stopLivePolling(); }
  }

  /** readTranscript 只会在压缩后填 contextTokens；没压缩过的会话用最后一条 usage 估算 */
  function contextTokensOf(conv) {
    if (!conv) return 0;
    if (conv.contextTokens > 0) return conv.contextTokens;
    const msgs = conv.messages || [];
    for (let i = msgs.length - 1; i >= 0; i--) {
      const m = msgs[i];
      if (m.role !== 'assistant' || !m.usage) continue;
      const u = m.usage;
      return (u.input_tokens || 0) + (u.cache_creation_input_tokens || 0) + (u.cache_read_input_tokens || 0);
    }
    return 0;
  }

  function updateHeader() {
    const parts = [];
    const ctx = contextTokensOf(S.conv);
    if (ctx > 0) {
      const pct = ctx / 1000000;
      const cls = pct >= 0.8 ? 'ctx-bad' : pct >= 0.5 ? 'ctx-warn' : 'ctx-ok';
      parts.push(`<span class="${cls}">上下文 ${fmtTok(ctx)} · ${Math.round(pct * 100)}%</span>`);
    }
    if (S.running) parts.push('<span class="run">● 运行中</span>');
    if (S.pendingAsk) parts.push('<span class="wait">等待回答</span>');
    else if (S.pendingPerm) parts.push('<span class="wait">等待确认</span>');
    $('#chatMeta').innerHTML = parts.join(' · ');
    updateQuickEffort();
  }

  function updateQuickEffort() {
    const sel = $('#quickEffort');
    if (!sel) return;
    const levels = (S.boot && S.boot.settings && S.boot.settings.effortLevels) ||
      ['low', 'medium', 'high', 'xhigh', 'max'];
    if (!sel.dataset.filled) {
      fillSelect(sel, [{ value: '', label: '默认' }].concat(
        levels.map(e => ({ value: e, label: EFFORT_LABELS[e] || e }))
      ));
      sel.dataset.filled = '1';
    }
    const cur = (S.conv && S.conv.effort) ||
      (S.boot && S.boot.settings && S.boot.settings.defaultEffort) || '';
    sel.value = levels.includes(cur) ? cur : '';
    sel.title = '推理档位：' + (sel.options[sel.selectedIndex] ? sel.options[sel.selectedIndex].text : '默认');
  }

  async function changeQuickEffort() {
    if (!S.convId) return;
    const sel = $('#quickEffort');
    const effort = sel ? sel.value : '';
    try {
      const data = await api('/api/conversation?id=' + encodeURIComponent(S.convId), {
        method: 'PATCH',
        body: JSON.stringify({ effort })
      });
      const conv = (data && data.conversation) || {};
      if (S.conv) S.conv.effort = conv.effort != null ? conv.effort : effort;
      updateHeader();
      toast('推理档位：' + (effort ? (EFFORT_LABELS[effort] || effort) : '默认'));
    } catch (e) {
      toast(e.message, 'err');
      updateHeader();
    }
  }

  function updateComposer() {
    const running = !!S.running;
    $('#stopBtn').hidden = !running;
    const btn = $('#sendBtn');
    btn.textContent = running ? '插话' : '发送';
    btn.classList.toggle('steer', running);
    btn.title = running ? '插话：当前步骤结束后生效' : '发送';
    // 插话提示放在按钮 title 里，输入框只留短占位，避免 placeholder 换行把输入框撑高
    $('#input').placeholder = running ? '插话…' : '发消息…';
    autoGrow();
    updateSendState();
  }

  function updateSendState() {
    const has = !!$('#input').value.trim() || S.pendingImages.length > 0 ||
      S.pendingRefs.length > 0 || S.pendingFiles.length > 0;
    $('#sendBtn').disabled = S.sending || !has;
  }

  // ---------------------------------------------------------------- 渲染消息

  /** 运行中的这一轮交给实时快照渲染，避免和转录里已落盘的消息重复 */
  function historyForRender() {
    const arr = ((S.conv && S.conv.messages) || []).slice();
    const live = S.live;
    if (!live) return arr;
    const started = live.startedAt || 0;
    while (arr.length) {
      const m = arr[arr.length - 1];
      const t = m.ts ? Date.parse(m.ts) : 0;
      const sameText = m.role === 'user' &&
        (live.users || []).some(u => (u.text || '') === (m.text || ''));
      if (m.role === 'assistant' && t && t >= started - 2000) { arr.pop(); continue; }
      if (sameText && t && t >= started - 15000) { arr.pop(); continue; }
      break;
    }
    return arr;
  }

  function mergeMeta(a, b) {
    if (!a) return b;
    if (!b) return a;
    const ua = a.usage || {};
    const ub = b.usage || {};
    return {
      duration: (a.duration || 0) + (b.duration || 0),
      costUsd: (a.costUsd || 0) + (b.costUsd || 0),
      usage: {
        input_tokens: (ua.input_tokens || 0) + (ub.input_tokens || 0),
        output_tokens: (ua.output_tokens || 0) + (ub.output_tokens || 0),
        cache_creation_input_tokens:
          (ua.cache_creation_input_tokens || 0) + (ub.cache_creation_input_tokens || 0),
        cache_read_input_tokens:
          (ua.cache_read_input_tokens || 0) + (ub.cache_read_input_tokens || 0)
      },
      ts: b.ts || a.ts
    };
  }

  /** 一轮任务里连续的 assistant 消息合并，过程只留一行「过程 · N 步」 */
  function groupedHistory() {
    const out = [];
    let cur = null;
    for (const m of historyForRender()) {
      if (m.role === 'assistant') {
        if (!cur) {
          cur = { id: m.id, role: 'assistant', blocks: [], ts: m.ts, meta: m.meta || null };
          out.push(cur);
        }
        if (Array.isArray(m.blocks)) cur.blocks.push(...m.blocks);
        if (m.ts) cur.ts = m.ts;
        if (m.meta) cur.meta = mergeMeta(cur.meta, m.meta);
      } else {
        cur = null;
        out.push(m);
      }
    }
    return out;
  }

  function renderMessages(forceBottom = false, anchor = null) {
    const box = $('#messages');
    // 重建 DOM 前记住位置：用户正在往回翻时，不要被新过程块强行拽到底部
    const wasNear = isNearBottom();
    const oldTop = box.scrollTop;
    box.innerHTML = '';

    if (S.conv && S.conv.hasMore) {
      const older = el('button', 'load-older', '↑ 加载更早消息');
      older.type = 'button';
      older.onclick = () => loadOlderMessages();
      box.appendChild(older);
    }

    for (const m of groupedHistory()) {
      const node = messageNode(m);
      if (node) box.appendChild(node);
    }
    const live = el('div', 'live-turn');
    live.id = 'liveTurn';
    box.appendChild(live);
    if (S.live) renderLiveTurn();
    else if (S.pendingEcho) live.appendChild(userNode(S.pendingEcho));

    if (anchor) {
      // 往上补了内容：保持当前正在看的那一段不动
      box.scrollTop = anchor.top + (box.scrollHeight - anchor.h);
    } else if (forceBottom || wasNear) {
      maybeScroll(true);
    } else {
      box.scrollTop = oldTop;
    }
    scheduleProcessCollapseUpdate();
  }

  function userTextInfo(text) {
    let t = String(text || '');
    let fileRef = false;
    const marker = '[引用的文件/文件夹，请按需读取]';
    const idx = t.indexOf(marker);
    if (idx >= 0) {
      fileRef = true;
      t = t.slice(0, idx).trim();
    }
    const digest = window.ClaudeRefDigest;
    if (digest && typeof digest.hasBlock === 'function' && digest.hasBlock(t)) {
      return { text: digest.stripBlock(t), ref: true, fileRef };
    }
    const re = /<gui-reference-context>[\s\S]*?<\/gui-reference-context>/;
    if (re.test(t)) {
      return { text: t.replace(re, '').trim(), ref: true, fileRef };
    }
    return { text: t, ref: false, fileRef };
  }

  function compactDividerNode() {
    return el('div', 'compact-divider', '——上下文对话已压缩——');
  }

  // 长消息（例如一整段 App 日志）默认收起，和电脑端保持一致。
  const LONG_USER_TEXT_CHARS = 1200;
  const LONG_USER_TEXT_LINES = 16;

  function isLongUserText(text) {
    const s = String(text || '');
    return s.length > LONG_USER_TEXT_CHARS || s.split('\n').length > LONG_USER_TEXT_LINES;
  }

  function isCompactSummaryMessage(m) {
    if (!m || !m.compactSummary) return false;
    // 引用其它对话的消息里可能夹带旧摘要；这种消息必须正常显示
    if (/<gui-reference-context>/i.test(String(m.text || ''))) return false;
    return true;
  }

  function sameUserPayload(a, b) {
    if (!a || !b) return false;
    if (!!a.steer !== !!b.steer) return false;
    if (String(a.text || '') !== String(b.text || '')) return false;
    const ai = Array.isArray(a.images) ? a.images.length : 0;
    const bi = Array.isArray(b.images) ? b.images.length : 0;
    return ai === bi;
  }

  /** 发送成功后先本地画出自己的消息，不依赖 SSE 回显；回显到了再去重。 */
  function appendSentUser(msg) {
    if (!msg || !S.convId) return;
    if (S.running && S.live) {
      if ((S.live.users || []).some(u => sameUserPayload(u, msg))) return;
      S.live.users.push({ ...msg, optimistic: true, afterBlocks: (S.live.blocks || []).length });
    } else {
      if (S.pendingEcho && sameUserPayload(S.pendingEcho, msg)) return;
      S.pendingEcho = { ...msg, optimistic: true };
    }
    const box = $('#liveTurn');
    if (box) {
      box.appendChild(userNode(msg));
      maybeScroll();
    } else {
      renderMessages();
    }
  }

  function userNode(m) {
    if (isCompactSummaryMessage(m)) {
      // 压缩摘要不在手机上调出整段总结，只留一条分隔线
      return compactDividerNode();
    }
    const d = el('div', 'msg user');
    if (m.steer) d.appendChild(el('span', 'steer-tag', '插话'));

    const info = userTextInfo(m.text || '');
    if (info.ref) d.appendChild(el('span', 'ref-tag', '引用对话 · 已按问题筛选'));
    if (info.fileRef) d.appendChild(el('span', 'ref-tag', '引用文件'));

    // 图片放在文字前面，多张时三列小预览
    const imgs = (m.images || []).filter(img => img && img.data);
    if (imgs.length) {
      const grid = el('div', 'user-images' + (imgs.length === 1 ? ' one' : ''));
      for (const img of imgs) {
        const i = el('img');
        i.src = `data:${img.media_type || 'image/png'};base64,${img.data}`;
        i.onclick = () => openImageViewer(i.src);
        grid.appendChild(i);
      }
      d.appendChild(grid);
    }
    if (info.text) {
      const textEl = el('div', 'utext');
      textEl.innerHTML = formatText(info.text);
      enhanceCodeBlocks(textEl);
      d.appendChild(textEl);
      if (isLongUserText(info.text)) {
        textEl.classList.add('collapsed');
        const label = `展开全部 · ${info.text.length.toLocaleString()} 字`;
        const btn = el('button', 'utext-expand', label);
        btn.type = 'button';
        btn.onclick = () => {
          const collapsed = textEl.classList.toggle('collapsed');
          btn.textContent = collapsed ? label : '收起';
        };
        d.appendChild(btn);
      }
    }
    return d;
  }

  function messageNode(m) {
    if (m.role === 'user') return userNode(m);
    const wrap = el('div', 'msg assistant');
    const blocks = (m.blocks || []).filter(Boolean);
    const proc = blocks.filter(b => b.type === 'thinking' || b.type === 'tool_use');
    const texts = blocks.filter(b => b.type === 'text');
    const finalText = texts.length ? texts[texts.length - 1].text : '';

    // 和电脑端一样：思考、工具和中间结论文本收进「过程」，默认合上，只留最后一段结论
    if (proc.length) {
      const lastText = texts.length ? texts[texts.length - 1] : null;
      const earlierTexts = texts.slice(0, -1);
      wrap.appendChild(processNode(proc, earlierTexts));
      if (lastText) {
        const node = blockNode(lastText);
        if (node) wrap.appendChild(node);
      }
    } else {
      for (const b of texts) {
        const node = blockNode(b);
        if (node) wrap.appendChild(node);
      }
    }
    const artifacts = extractArtifactPaths(finalText);
    if (artifacts.length) wrap.appendChild(artifactNode(artifacts));
    const edits = aggregateEdits(blocks);
    if (edits.length) wrap.appendChild(editCardNode(edits));
    if (m.meta) wrap.appendChild(turnMetaNode(m.meta));
    return wrap;
  }

  function blockNode(b) {
    if (!b) return null;
    if (b.type === 'text') {
      const d = el('div', 'txt');
      d.innerHTML = formatText(b.text);
      enhanceCodeBlocks(d);
      return d;
    }
    if (b.type === 'thinking') return thinkingNode(b);
    if (b.type === 'tool_use') return toolNode(b);
    return null;
  }

  function thinkingNode(b) {
    const d = el('details', 'think');
    d.appendChild(el('summary', null, '思考过程'));
    const t = el('div', 'think-text');
    t.textContent = b.thinking || b.text || '';
    d.appendChild(t);
    const c = el('button', 'collapse-btn small', '收起');
    c.type = 'button';
    c.onclick = e => {
      e.preventDefault();
      e.stopPropagation();
      d.open = false;
    };
    d.appendChild(c);
    return d;
  }

  function editedFileOf(b) {
    if (!b || b.type !== 'tool_use') return '';
    if (!/^(Write|Edit|MultiEdit|NotebookEdit)$/.test(b.name || '')) return '';
    const inp = b.input || {};
    const f = inp.file_path || inp.path || inp.notebook_path;
    return typeof f === 'string' ? f : '';
  }

  function splitLines(s) {
    s = String(s == null ? '' : s).replace(/\r\n?/g, '\n');
    if (!s) return [];
    const a = s.split('\n');
    if (a[a.length - 1] === '') a.pop();
    return a;
  }

  function lineDelta(oldS, newS) {
    const a = splitLines(oldS);
    const b = splitLines(newS);
    let p = 0;
    while (p < a.length && p < b.length && a[p] === b[p]) p++;
    let s = 0;
    while (s < a.length - p && s < b.length - p &&
      a[a.length - 1 - s] === b[b.length - 1 - s]) s++;
    return { removed: a.length - p - s, added: b.length - p - s };
  }

  function editToolInfo(name, input) {
    if (!input || typeof input !== 'object') return null;
    if (name === 'Edit') {
      if (typeof input.file_path !== 'string' || !input.file_path) return null;
      const d = lineDelta(input.old_string, input.new_string);
      return { path: input.file_path, added: d.added, removed: d.removed };
    }
    if (name === 'MultiEdit') {
      if (typeof input.file_path !== 'string' || !input.file_path) return null;
      let added = 0;
      let removed = 0;
      for (const e of Array.isArray(input.edits) ? input.edits : []) {
        const d = lineDelta(e && e.old_string, e && e.new_string);
        added += d.added;
        removed += d.removed;
      }
      return { path: input.file_path, added, removed };
    }
    if (name === 'Write') {
      if (typeof input.file_path !== 'string' || !input.file_path) return null;
      return { path: input.file_path, added: splitLines(input.content).length, removed: 0 };
    }
    if (name === 'NotebookEdit') {
      const p = input.notebook_path || input.file_path;
      if (typeof p !== 'string' || !p) return null;
      if (input.edit_mode === 'delete') return { path: p, added: 0, removed: 0 };
      return { path: p, added: splitLines(input.new_source).length, removed: 0 };
    }
    return null;
  }

  /** 按路径聚合本轮/本条消息里的编辑记录 */
  function aggregateEdits(blocks) {
    const map = new Map();
    for (const b of blocks || []) {
      if (!b || b.type !== 'tool_use') continue;
      if (b.result != null && b.isError) continue;
      const info = editToolInfo(b.name, b.input);
      if (!info) continue;
      const key = info.path.replace(/\\/g, '/').toLowerCase();
      let cur = map.get(key);
      if (!cur) {
        cur = { path: info.path, added: 0, removed: 0, edits: 0 };
        map.set(key, cur);
      }
      cur.added += info.added;
      cur.removed += info.removed;
      cur.edits += 1;
    }
    return [...map.values()];
  }

  function artifactSplit(p) {
    const slash = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
    let dir = slash > 0 ? p.slice(0, slash) : '';
    if (/^[A-Za-z]:$/.test(dir)) dir += '\\';
    return { dir, name: slash >= 0 ? p.slice(slash + 1) : p };
  }

  function artifactPath(raw) {
    let p = String(raw || '').trim();
    if (!p || p.includes('://')) return '';
    p = p.replace(/[ \t]+$/, '');
    p = p.replace(/[ \t]*[.,;:!?)\]}>》】”’。，；：、]+$/, '');
    // 电脑是盘符路径，手机是 /sdcard、/workspace 等绝对路径。
    if (!/^(?:[A-Za-z]:[\\/]|\\\\|\/(?:sdcard|storage\/emulated|workspace|root|tmp|opt)\/)/.test(p)) {
      return '';
    }
    if (/[<>{}*|?]/.test(p)) return '';
    const name = artifactSplit(p).name;
    if (!name || !/\.[A-Za-z0-9]{1,10}$/.test(name)) return '';
    return p;
  }

  /** 从最终结论里提取“生成的文件”，电脑/手机路径都支持 */
  function extractArtifactPaths(text) {
    const src = String(text || '');
    if (!src) return [];
    const seen = new Set();
    const out = [];
    const add = raw => {
      const p = artifactPath(raw);
      if (!p) return;
      const key = p.replace(/\//g, '\\').toLowerCase();
      if (seen.has(key)) return;
      seen.add(key);
      const parts = artifactSplit(p);
      out.push({ path: p, name: parts.name, dir: parts.dir });
    };
    const quoted = /[`"'“”‘’]((?:[A-Za-z]:[\\/]|\\\\|\/(?:sdcard|storage\/emulated|workspace|root|tmp|opt)\/)[^`"'“”‘’\r\n]+)[`"'“”‘’]/g;
    for (const m of src.matchAll(quoted)) add(m[1]);
    const bare = /(?:[A-Za-z]:[\\/]|\\\\|\/(?:sdcard|storage\/emulated|workspace|root|tmp|opt)\/)[^\s`"'“”‘’<>|?*，。；：、（）【】《》]+/g;
    for (const m of src.matchAll(bare)) add(m[0]);
    return out.slice(0, 6);
  }

  /** 生成的文件：手机端提供「打开」，直接读取本机原文件 */
  function artifactNode(files) {
    const card = el('div', 'artifact-card');
    const head = el('div', 'artifact-card-head');
    head.appendChild(el('span', null, '生成的文件'));
    head.appendChild(el('span', 'artifact-count', String(files.length)));
    card.appendChild(head);

    const list = el('div', 'artifact-list');
    for (const f of files) {
      const row = el('div', 'artifact-row');
      row.dataset.source = f.path;
      row.dataset.name = f.name;
      row.dataset.dir = f.dir || f.path;
      const main = el('div', 'artifact-main');
      main.appendChild(el('div', 'artifact-name', f.name));
      const downloaded = downloadFor(f.path);
      main.appendChild(el('div', 'artifact-dir',
        downloaded ? downloadedDir(downloaded) : (f.dir || f.path)));
      if (canOpenLocal()) {
        main.style.cursor = 'pointer';
        main.onclick = () => {
          if (!openLocalFile(f)) startDownload(f, null);
        };
      }
      row.appendChild(main);
      const actions = el('div', 'artifact-actions');
      row.appendChild(actions);
      renderArtifactActions(actions, f);
      list.appendChild(row);
    }
    card.appendChild(list);
    return card;
  }

  // ---------------------------------------------------------------- 手机下载

  function fmtBytes(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }

  function normPath(p) {
    return String(p || '').replace(/\//g, '\\').toLowerCase();
  }

  function downloadFor(source) {
    const key = normPath(source);
    return (S.downloads || []).find(d => normPath(d.source) === key) || null;
  }

  function downloadedDir(d) {
    if (!d) return '';
    if (d.publicUri) return '手机 · 下载/Claude工作台/' + (d.name || '');
    return d.path ? '手机 · ' + d.path : '';
  }

  /** 只有手机端页面才直接打开本机文件；App 里的远程电脑端仍走原下载逻辑。 */
  function isPhonePage() {
    return !!document.querySelector('meta[name="workbench-phone"]');
  }

  function canOpenLocal() {
    return isPhonePage() && window.AndroidDownloads && AndroidDownloads.openLocal;
  }

  function openLocalFile(f) {
    if (!canOpenLocal()) return false;
    try {
      return !!AndroidDownloads.openLocal(f.path, f.name);
    } catch {
      return false;
    }
  }

  function openLocalLocation(f) {
    if (!canOpenLocal() || !AndroidDownloads.openLocalLocation) return false;
    try {
      return !!AndroidDownloads.openLocalLocation(f.dir || f.path);
    } catch {
      return false;
    }
  }

  function renderArtifactActions(actions, f) {
    actions.innerHTML = '';
    // 手机本机文件直接打开；失败则回退到下载（远程电脑端的 Windows 路径也走下载）。
    if (canOpenLocal()) {
      const open = el('button', 'file-btn primary', '打开');
      open.type = 'button';
      open.onclick = () => {
        if (!openLocalFile(f)) startDownload(f, open);
      };
      const loc = el('button', 'file-btn', '位置');
      loc.type = 'button';
      loc.title = '在手机文件管理器中打开所在文件夹';
      loc.onclick = () => {
        if (!openLocalLocation(f)) toast('没有找到文件管理器', 'err');
      };
      actions.append(open, loc);
      return;
    }
    const d = downloadFor(f.path);
    if (d) {
      const open = el('button', 'file-btn', '打开');
      open.type = 'button';
      open.onclick = () => openDownloaded(d);
      const del = el('button', 'file-btn danger', '删除');
      del.type = 'button';
      del.onclick = () => deleteDownloaded(d);
      actions.append(open, del);
    } else {
      const dl = el('button', 'file-btn primary', '下载');
      dl.type = 'button';
      dl.onclick = () => startDownload(f, dl);
      actions.appendChild(dl);
    }
  }

  function startDownload(f, btn) {
    if (!S.convId) return;
    const url = location.origin + '/api/download?id=' + encodeURIComponent(S.convId) +
      '&path=' + encodeURIComponent(f.path) + '&token=' + encodeURIComponent(TOKEN);
    if (window.AndroidDownloads) {
      if (btn) { btn.disabled = true; btn.textContent = '下载中…'; }
      try {
        window.AndroidDownloads.download(url, f.path, f.name || 'download');
      } catch (e) {
        if (btn) { btn.disabled = false; btn.textContent = '下载'; }
        toast('下载失败：' + e.message, 'err');
      }
    } else {
      const a = document.createElement('a');
      a.href = url;
      a.download = f.name || 'download';
      document.body.appendChild(a);
      a.click();
      a.remove();
      toast('已交给浏览器下载');
    }
  }

  function updateArtifactActions() {
    document.querySelectorAll('#messages .artifact-row[data-source]').forEach(row => {
      const d = downloadFor(row.dataset.source);
      const dir = row.querySelector('.artifact-dir');
      if (dir) {
        dir.textContent = d
          ? downloadedDir(d)
          : (row.dataset.dir || row.dataset.source);
      }
      const actions = row.querySelector('.artifact-actions');
      if (!actions) return;
      renderArtifactActions(actions, {
        path: row.dataset.source,
        name: row.dataset.name
      });
    });
  }

  function refreshDownloads() {
    if (!window.AndroidDownloads) {
      S.downloads = [];
      updateArtifactActions();
      return;
    }
    try {
      S.downloads = JSON.parse(window.AndroidDownloads.list() || '[]');
    } catch {
      S.downloads = [];
    }
    updateArtifactActions();
    if (!$('#downloadsSheet').hidden) renderDownloads();
  }

  function openDownloaded(d) {
    if (!window.AndroidDownloads) return;
    let ok = false;
    try { ok = window.AndroidDownloads.open(d.path); } catch { ok = false; }
    if (!ok) toast('打开失败', 'err');
  }

  function deleteDownloaded(d) {
    if (!window.AndroidDownloads) return;
    if (!confirm('删除这个已下载的文件？')) return;
    let ok = false;
    try { ok = window.AndroidDownloads.delete(d.path); } catch { ok = false; }
    if (!ok) toast('删除失败', 'err');
    refreshDownloads();
  }

  function renderDownloads() {
    const box = $('#downloadsList');
    box.innerHTML = '';
    if (!window.AndroidDownloads) {
      $('#downloadsNote').textContent = '浏览器版无法在这里管理下载文件，请在浏览器下载管理中查看。';
      box.innerHTML = '<div class="empty">请使用 Claude 工作台 App 下载和管理文件</div>';
      return;
    }
    $('#downloadsNote').textContent = '文件保存在手机 App 的下载目录';
    const list = S.downloads || [];
    if (!list.length) {
      box.innerHTML = '<div class="empty">还没有下载文件</div>';
      return;
    }
    for (const d of list) {
      const row = el('div', 'download-row');
      const main = el('div', 'download-main');
      main.appendChild(el('div', 'download-name', d.name || '文件'));
      const date = d.mtime ? new Date(Number(d.mtime)).toLocaleString() : '';
      main.appendChild(el('div', 'download-meta',
        `${fmtBytes(d.size)}${date ? ' · ' + date : ''}`));
      row.appendChild(main);
      const actions = el('div', 'download-actions');
      const open = el('button', 'file-btn', '打开');
      open.type = 'button';
      open.onclick = () => openDownloaded(d);
      const del = el('button', 'file-btn danger', '删除');
      del.type = 'button';
      del.onclick = () => deleteDownloaded(d);
      actions.append(open, del);
      row.appendChild(actions);
      box.appendChild(row);
    }
  }

  function openDownloadsSheet() {
    if (!window.AndroidDownloads) {
      toast('浏览器版请在浏览器下载管理中查看');
      return;
    }
    refreshDownloads();
    renderDownloads();
    $('#downloadsSheet').hidden = false;
  }

  function closeDownloadsSheet() {
    $('#downloadsSheet').hidden = true;
  }

  window.__onDownloadComplete = (source, ok, error) => {
    refreshDownloads();
    if (ok) toast('下载完成');
    else toast('下载失败：' + (error || '未知错误'), 'err');
  };

  function editCardNode(files) {
    let add = 0;
    let del = 0;
    for (const f of files) {
      add += f.added;
      del += f.removed;
    }
    const card = el('div', 'edit-card');
    const head = el('div', 'edit-card-head');
    head.appendChild(el('span', 'edit-title', `已编辑 ${files.length} 个文件`));
    const total = el('span', 'edit-total');
    total.innerHTML = `<span class="edit-add">+${add}</span> <span class="edit-del">-${del}</span>`;
    head.appendChild(total);
    card.appendChild(head);

    const list = el('div', 'edit-list');
    const hiddenRows = [];
    files.forEach((f, i) => {
      const row = el('div', 'edit-row');
      if (i >= 3) {
        row.hidden = true;
        hiddenRows.push(row);
      }
      const p = el('div', 'edit-path');
      const parts = artifactSplit(f.path);
      if (parts.dir) p.appendChild(el('span', 'ep-dir', parts.dir));
      p.appendChild(el('span', 'ep-name', parts.name));
      row.appendChild(p);
      const stat = el('span', 'edit-stat');
      stat.innerHTML = `<span class="edit-add">+${f.added}</span> <span class="edit-del">-${f.removed}</span>`;
      row.appendChild(stat);
      row.title = '打开 ' + f.path;
      row.style.cursor = 'pointer';
      row.onclick = () => {
        if (!openLocalFile({ path: f.path, name: parts.name, dir: parts.dir })) {
          startDownload({ path: f.path, name: parts.name, dir: parts.dir }, null);
        }
      };
      list.appendChild(row);
    });

    if (hiddenRows.length) {
      const more = el('button', 'edit-more', `展开全部 ${files.length} 个文件`);
      more.type = 'button';
      let open = false;
      more.onclick = () => {
        open = !open;
        for (const row of hiddenRows) row.hidden = !open;
        more.textContent = open ? '收起' : `展开全部 ${files.length} 个文件`;
      };
      list.appendChild(more);
    }

    card.appendChild(list);
    return card;
  }

  function processMetaText(count, files) {
    return `· ${count} 步` + (files && files.size ? ` · ${files.size} 个文件` : '');
  }

  function turnMetaNode(meta) {
    const u = meta.usage || {};
    const parts = [];
    if (meta.duration) parts.push(`用时 ${Math.round(meta.duration / 1000)}s`);
    const inTok = (u.input_tokens || 0) + (u.cache_creation_input_tokens || 0) + (u.cache_read_input_tokens || 0);
    const outTok = u.output_tokens || 0;
    if (inTok || outTok) parts.push(`输入 ${fmtTok(inTok)} / 输出 ${fmtTok(outTok)}`);
    const cny = (u.cache_read_input_tokens || 0) / 1e6 * 0.02 +
      ((u.input_tokens || 0) + (u.cache_creation_input_tokens || 0)) / 1e6 * 1 +
      outTok / 1e6 * 4;
    if (cny > 0.0005) parts.push(`≈¥${cny.toFixed(3)}`);
    return el('div', 'turn-meta', parts.join(' · '));
  }

  function openImageViewer(src) {
    $('#imgViewerImg').src = src;
    $('#imgViewer').hidden = false;
  }

  function closeImageViewer() {
    $('#imgViewer').hidden = true;
    $('#imgViewerImg').src = '';
  }

  /** 历史消息里的「过程」：思考、工具调用、中间结论文本统一折叠，默认合上 */
  function processNode(blocks, extraTexts = []) {
    const d = el('details', 'process');
    const sum = el('summary');
    const label = el('span', 'process-label', '过程');
    const meta = el('span', 'process-meta');
    sum.append(label, meta);

    const body = el('div', 'process-body');
    for (const b of extraTexts) {
      const t = el('div', 'txt temp');
      t.innerHTML = formatText(b.text);
      enhanceCodeBlocks(t);
      body.appendChild(t);
    }
    const files = new Set();
    for (const b of blocks) {
      const node = b.type === 'thinking' ? thinkingNode(b)
        : (b.type === 'tool_use' ? toolNode(b) : null);
      if (node) body.appendChild(node);
      const f = editedFileOf(b);
      if (f) files.add(f);
    }

    d.append(sum, body);
    meta.textContent = processMetaText(blocks.length, files);
    d.addEventListener('toggle', scheduleProcessCollapseUpdate);
    return d;
  }

  // ---------------------------------------------------------------- 浮动的「收起过程」

  let processCollapseTarget = null;
  let processCollapseRaf = 0;

  function ensureProcessCollapseButton() {
    let btn = document.getElementById('processCollapseBtn');
    if (btn) return btn;
    btn = el('button', 'process-collapse-btn');
    btn.id = 'processCollapseBtn';
    btn.type = 'button';
    btn.hidden = true;
    btn.title = '收起上方已经滑出视口的过程';
    btn.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 15l6-6 6 6"/></svg><span>收起</span>';
    btn.addEventListener('click', collapseProcessAbove);
    const host = document.getElementById('chatScreen') || document.body;
    host.appendChild(btn);
    return btn;
  }

  function findProcessToCollapse() {
    const box = document.getElementById('messages');
    if (!box) return null;
    const top = box.getBoundingClientRect().top;
    const acts = box.querySelectorAll('details.process[open]');
    let target = null;
    for (const el of acts) {
      const summary = el.querySelector(':scope > summary');
      if (!summary) continue;
      if (summary.getBoundingClientRect().bottom > top + 4) continue;
      // 过程框整体已经滑出上方就不再显示，只处理仍有一截在视口里的过程。
      if (el.getBoundingClientRect().bottom <= top + 4) continue;
      target = el;
    }
    return target;
  }

  function updateProcessCollapseButton() {
    const btn = ensureProcessCollapseButton();
    const box = document.getElementById('messages');
    if (!box) {
      btn.hidden = true;
      return;
    }
    const target = findProcessToCollapse();
    processCollapseTarget = target;
    btn.hidden = !target;
    if (!target) return;

    const br = box.getBoundingClientRect();
    const ar = target.getBoundingClientRect();
    const btnH = btn.offsetHeight || 30;
    const gap = 8;
    const visibleTop = Math.max(ar.top, br.top);
    const visibleBottom = Math.min(ar.bottom, br.bottom);
    // 过程框快划完、可视高度不够时直接隐藏，避免被挤到相邻消息上。
    if (visibleBottom - visibleTop < btnH + gap * 2) {
      btn.hidden = true;
      processCollapseTarget = null;
      return;
    }
    const minCenter = visibleTop + gap + btnH / 2;
    const maxCenter = visibleBottom - gap - btnH / 2;
    const center = Math.max(minCenter, Math.min(maxCenter, br.top + br.height * 0.7));
    // 放在过程框内部的右侧，避免像之前那样卡在边框上、里外不分。
    const btnW = btn.offsetWidth || 62;
    btn.style.left = Math.round(Math.max(br.left + 6, ar.right - btnW - 10)) + 'px';
    btn.style.top = Math.round(center - btnH / 2) + 'px';
  }

  function scheduleProcessCollapseUpdate() {
    if (processCollapseRaf) return;
    processCollapseRaf = requestAnimationFrame(() => {
      processCollapseRaf = 0;
      updateProcessCollapseButton();
    });
  }

  function collapseProcessAbove() {
    const target = processCollapseTarget;
    if (!target || !target.open || !target.isConnected) {
      scheduleProcessCollapseUpdate();
      return;
    }
    const box = document.getElementById('messages');
    if (!box) return;
    const top = box.getBoundingClientRect().top;
    const rect = target.getBoundingClientRect();
    const inside = rect.bottom > top + 4;
    const beforeScroll = box.scrollTop;
    const beforeHeight = box.scrollHeight;

    target.open = false;
    requestAnimationFrame(() => {
      if (inside) {
        // 正滑在过程正文里：收起后把过程标题带到视口顶部，避免停在空白处
        const summary = target.querySelector(':scope > summary');
        if (summary) {
          const delta = summary.getBoundingClientRect().top - top - 8;
          box.scrollTop = Math.max(0, box.scrollTop + delta);
        }
      } else {
        // 过程整体在上方：按被移除的高度补偿，保持当前位置
        const removed = Math.max(0, beforeHeight - box.scrollHeight);
        box.scrollTop = Math.max(0, beforeScroll - removed);
      }
      scheduleProcessCollapseUpdate();
    });
  }

  /** 极简 markdown：标题/列表/引用/加粗/行内 code/代码块，和电脑端观感一致 */
  function formatInline(escaped) {
    let out = escaped;
    out = out.replace(/`([^`\n]+)`/g, '<code>$1</code>');
    out = out.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    out = out.replace(/~~([^~\n]+)~~/g, '<s>$1</s>');
    out = out.replace(
      /\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g,
      '<a href="$2" target="_blank" rel="noopener">$1</a>'
    );
    return out;
  }

  function formatBlockText(text) {
    return String(text || '').split('\n').map(line => {
      if (!line.trim()) return '<div class="md-gap"></div>';
      let m;
      if ((m = line.match(/^(#{1,6})\s+(.*)$/))) {
        const level = m[1].length;
        return `<div class="md-h md-h${level}">${formatInline(escapeHtml(m[2]))}</div>`;
      }
      if ((m = line.match(/^\s*[-*]\s+(.*)$/))) {
        return `<div class="md-li">• ${formatInline(escapeHtml(m[1]))}</div>`;
      }
      if ((m = line.match(/^\s*(\d+)\.\s+(.*)$/))) {
        return `<div class="md-li">${m[1]}. ${formatInline(escapeHtml(m[2]))}</div>`;
      }
      if ((m = line.match(/^\s*>\s?(.*)$/))) {
        return `<blockquote>${formatInline(escapeHtml(m[1]))}</blockquote>`;
      }
      if (/^\s*---+\s*$/.test(line)) return '<hr>';
      return `<div>${formatInline(escapeHtml(line))}</div>`;
    }).join('');
  }

  function formatText(text) {
    const s = String(text || '');
    const parts = s.split('```');
    if (parts.length === 1) return formatBlockText(s);
    return parts.map((p, i) => {
      if (i % 2) {
        const body = p.replace(/^[^\n]*\n/, '');
        return `<pre class="code">${escapeHtml(body)}</pre>`;
      }
      return formatBlockText(p);
    }).join('');
  }

  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch { /* 走下面的兜底 */ }
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.left = '-9999px';
      ta.style.top = '0';
      document.body.appendChild(ta);
      ta.select();
      ta.setSelectionRange(0, ta.value.length);
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    } catch {
      return false;
    }
  }

  function enhanceCodeBlocks(root) {
    root.querySelectorAll('pre.code').forEach(pre => {
      if (pre.parentElement && pre.parentElement.classList.contains('code-wrap')) return;
      const wrap = el('div', 'code-wrap');
      pre.parentNode.insertBefore(wrap, pre);
      wrap.appendChild(pre);
      const btn = el('button', 'code-copy', '复制');
      btn.type = 'button';
      btn.onclick = async () => {
        const ok = await copyText(pre.textContent || '');
        btn.textContent = ok ? '已复制' : '复制失败';
        setTimeout(() => { btn.textContent = '复制'; }, 1500);
      };
      wrap.appendChild(btn);
    });
  }

  function toolArg(input) {
    if (!input) return '';
    const pick = input.command || input.file_path || input.path || input.pattern ||
      input.query || input.url || input.description || input.prompt || input.task || '';
    if (typeof pick === 'string' && pick.trim()) {
      return pick.replace(/\s+/g, ' ').trim().slice(0, 160);
    }
    try {
      const s = JSON.stringify(input);
      return s === '{}' ? '' : s.slice(0, 160);
    } catch { return ''; }
  }

  function pretty(x) {
    if (typeof x === 'string') return x;
    try { return JSON.stringify(x, null, 2); } catch { return String(x); }
  }

  function toolNode(b) {
    const d = el('details', 'tool');
    const sum = el('summary');
    const name = el('span', 'tool-name', b.name || '工具');
    const arg = el('span', 'tool-arg', toolArg(b.input));
    const state = el('span', 'tool-state');
    sum.append(name, arg, state);

    const body = el('div', 'tool-body');
    const inputPre = el('pre', 'tool-input', b.input ? pretty(b.input) : '');
    const resultPre = el('pre', 'tool-result');
    if (b.result != null) {
      resultPre.textContent = String(b.result);
      if (b.isError) resultPre.classList.add('err');
    } else {
      resultPre.remove();
    }
    body.append(inputPre, resultPre);
    d.append(sum, body);
    d._refs = { arg, state, body, inputPre, resultPre };
    updateToolNode(d, b);
    return d;
  }

  function updateToolNode(d, b) {
    if (!d || !d._refs) return;
    d._block = { ...(d._block || {}), ...b };
    const r = d._refs;
    const arg = toolArg(d._block.input);
    if (arg) r.arg.textContent = arg;
    if (d._block.input && !r.inputPre.textContent) r.inputPre.textContent = pretty(d._block.input);

    if (d._block.result != null) {
      if (!r.resultPre.isConnected) r.body.appendChild(r.resultPre);
      r.resultPre.textContent = String(d._block.result);
      r.resultPre.classList.toggle('err', !!d._block.isError);
      r.state.textContent = d._block.isError ? '失败' : '完成';
      r.state.className = 'tool-state ' + (d._block.isError ? 'err' : 'ok');
    } else if (d._block.done) {
      r.state.textContent = '完成';
      r.state.className = 'tool-state ok';
    } else {
      r.state.textContent = '运行中…';
      r.state.className = 'tool-state run';
    }
  }

  // ---------------------------------------------------------------- 实时一轮

  function ensureLive() {
    if (!S.live) {
      S.live = { startedAt: Date.now(), users: [], blocks: [], permission: null, ask: null };
    }
    return S.live;
  }

  function resetLiveProcess() {
    S.liveProcessEl = null;
    S.liveProcessBody = null;
    S.liveProcessMeta = null;
    S.liveProcessState = null;
    S.liveProcessCollapse = null;
    S.liveProcessCount = 0;
    S.liveProcessFiles = new Set();
  }

  /** 实时一轮的「过程」：默认合上，摘要里显示步数/文件数和当前步骤 */
  function ensureLiveProcess(box) {
    if (S.liveProcessEl) return S.liveProcessEl;
    const d = el('details', 'process');
    const sum = el('summary');
    const label = el('span', 'process-label', '过程');
    const meta = el('span', 'process-meta', '· 0 步');
    const state = el('span', 'process-state');
    sum.append(label, meta, state);

    const body = el('div', 'process-body');
    d.append(sum, body);
    d.addEventListener('toggle', scheduleProcessCollapseUpdate);
    box.appendChild(d);

    S.liveProcessEl = d;
    S.liveProcessBody = body;
    S.liveProcessMeta = meta;
    S.liveProcessState = state;
    S.liveProcessCollapse = null;
    S.liveProcessCount = 0;
    S.liveProcessFiles = new Set();
    return d;
  }

  function setLiveProcessState(text) {
    if (S.liveProcessState) S.liveProcessState.textContent = text || '';
  }

  function bumpLiveProcess(b) {
    S.liveProcessCount += 1;
    const f = editedFileOf(b);
    if (f) S.liveProcessFiles.add(f);
    if (S.liveProcessMeta) {
      S.liveProcessMeta.textContent = processMetaText(S.liveProcessCount, S.liveProcessFiles);
    }
    if (b.type === 'thinking') setLiveProcessState('思考中…');
    else if (b.type === 'tool_use') setLiveProcessState(`${b.name || '工具'} 运行中…`);
  }

  function renderLiveTurn() {
    const box = $('#liveTurn');
    if (!box || !S.live) return;
    box.innerHTML = '';
    S.liveEls.clear();
    resetLiveProcess();

    const users = S.live.users || [];
    const blocks = S.live.blocks || [];
    const byPos = new Map();
    for (const u of users) {
      const pos = Math.max(0, Math.min(blocks.length, u.afterBlocks || 0));
      if (!byPos.has(pos)) byPos.set(pos, []);
      byPos.get(pos).push(u);
    }

    for (let i = 0; i <= blocks.length; i++) {
      for (const u of byPos.get(i) || []) box.appendChild(userNode(u));
      if (i >= blocks.length) continue;
      const b = blocks[i];
      const built = liveBlockNode(b);
      if (!built) continue;
      if (b.type === 'thinking' || b.type === 'tool_use') {
        ensureLiveProcess(box);
        S.liveProcessBody.insertBefore(built.node, S.liveProcessCollapse);
        bumpLiveProcess(b);
      } else {
        box.appendChild(built.node);
      }
      S.liveEls.set(b.key, built.ref);
    }

    const last = blocks[blocks.length - 1];
    if (last && last.type === 'tool_use') {
      setLiveProcessState(last.result != null ? (last.isError ? '失败' : '完成') : '运行中…');
    }
    syncLiveCursor(box);
  }

  // 只在「当前最后一段正在输出的文字」末尾显示闪烁光标。
  // 之前每个 text block 都带 streaming 类，一轮里有多段文字时会到处闪。
  function syncLiveCursor(box) {
    const root = box || $('#liveTurn');
    if (!root) return;
    root.querySelectorAll('.msg.assistant.streaming').forEach(n => n.classList.remove('streaming'));
    if (!S.running || !S.live) return;
    const blocks = S.live.blocks || [];
    const last = blocks[blocks.length - 1];
    if (!last || last.type !== 'text') return;
    const texts = root.querySelectorAll(':scope > .msg.assistant');
    const elLast = texts[texts.length - 1];
    if (elLast) elLast.classList.add('streaming');
  }

  function liveBlockNode(b) {
    if (b.type === 'text') {
      const d = el('div', 'msg assistant');
      const t = el('div', 'txt');
      t.textContent = b.text || '';
      d.appendChild(t);
      return { node: d, ref: { textEl: t } };
    }
    if (b.type === 'thinking') {
      const d = el('details', 'think');
      d.appendChild(el('summary', null, '思考过程'));
      const t = el('div', 'think-text');
      t.textContent = b.text || b.thinking || '';
      d.appendChild(t);
      const c = el('button', 'collapse-btn small', '收起');
      c.type = 'button';
      c.onclick = e => {
        e.preventDefault();
        e.stopPropagation();
        d.open = false;
      };
      d.appendChild(c);
      return { node: d, ref: { textEl: t } };
    }
    if (b.type === 'tool_use') {
      const d = toolNode(b);
      return { node: d, ref: { tool: d } };
    }
    return null;
  }

  function onBlockStart(ev) {
    const live = ensureLive();
    if (live.blocks.some(b => b.key === ev.key)) return;
    const b = { key: ev.key, ...(ev.block || {}) };
    if (b.type === 'text') b.text = b.text || '';
    if (b.type === 'thinking') b.text = b.text || b.thinking || '';
    live.blocks.push(b);

    const box = $('#liveTurn');
    if (!box) return;
    const built = liveBlockNode(b);
    if (!built) return;
    if (b.type === 'thinking' || b.type === 'tool_use') {
      ensureLiveProcess(box);
      S.liveProcessBody.insertBefore(built.node, S.liveProcessCollapse);
      bumpLiveProcess(b);
    } else {
      box.appendChild(built.node);
    }
    S.liveEls.set(ev.key, built.ref);
    syncLiveCursor(box);
    maybeScroll();
  }

  function onBlockSet(ev) {
    const ref = S.liveEls.get(ev.key);
    if (!ref || !ref.tool) return;
    updateToolNode(ref.tool, ev.block || {});
    const f = editedFileOf(ref.tool._block || {});
    if (f && S.liveProcessFiles) {
      S.liveProcessFiles.add(f);
      if (S.liveProcessMeta) {
        S.liveProcessMeta.textContent = processMetaText(S.liveProcessCount, S.liveProcessFiles);
      }
    }
    setLiveProcessState(`${(ref.tool._block && ref.tool._block.name) || '工具'} 运行中…`);
  }

  function onToolResult(ev) {
    const ref = S.liveEls.get('tool:' + ev.toolUseId);
    if (!ref || !ref.tool) return;
    updateToolNode(ref.tool, { result: ev.content, isError: ev.isError, done: true });
    setLiveProcessState(ev.isError ? '失败' : '完成');
  }

  // ---------------------------------------------------------------- 运行状态行

  function progressForThinking(b) {
    const t = String((b && (b.text || b.thinking)) || '').replace(/\s+/g, ' ').trim();
    return t ? '正在思考：' + (t.length > 64 ? t.slice(0, 64) + '…' : t) : '正在思考…';
  }

  function progressForTool(b) {
    const name = (b && b.name) || '工具';
    const raw = b && b.input ? toolArg(b.input) : '';
    const summary = String(raw || '').replace(/\s+/g, ' ').trim();
    return '正在执行 ' + name +
      (summary ? '：' + (summary.length > 80 ? summary.slice(0, 80) + '…' : summary) : '…');
  }

  function setRunProgress(text, kind) {
    const box = $('#runProgress');
    if (!box) return;
    if (!text) {
      box.hidden = true;
      return;
    }
    box.hidden = false;
    const cls = 'run-progress' + (kind ? ' ' + kind : '');
    // 轮询会频繁刷新同一句进度；class 不变时不要重设，避免闪烁光标动画被重置
    if (box.className !== cls) box.className = cls;
    const txt = box.querySelector('.txt');
    if (txt.textContent !== text) txt.textContent = text;
  }

  function syncProgressFromLive() {
    if (!S.running) { setRunProgress('', ''); return; }
    if (S.pendingAsk) { setRunProgress('等待你的回答…', 'wait'); return; }
    if (S.pendingPerm) {
      setRunProgress('等待你的确认：' + (S.pendingPerm.toolName || '操作') + '…', 'wait');
      return;
    }
    const blocks = (S.live && S.live.blocks) || [];
    const last = blocks[blocks.length - 1];
    if (last && last.type === 'tool_use') setRunProgress(progressForTool(last), 'tool');
    else if (last && last.type === 'thinking') setRunProgress(progressForThinking(last), 'thinking');
    else setRunProgress('正在思考…', 'thinking');
  }

  // ---------------------------------------------------------------- 事件

  function handleEvent(ev) {
    const t = ev.t;

    // 全局事件（没有 convId）
    if (!ev.convId) {
      if (t === 'conv_meta') {
        if (S.conv && ev.id === S.convId) {
          if (ev.title) {
            S.conv.title = ev.title;
            $('#chatTitle').textContent = ev.title;
          }
        }
        scheduleBootRefresh();
      } else if (t === 'conv_created' || t === 'conv_removed') {
        scheduleBootRefresh();
      }
      return;
    }

    // 列表页 / 其他对话：只刷新徽标
    if (!S.convId || ev.convId !== S.convId) {
      if (t === 'run_start' || t === 'run_end' || t === 'permission' || t === 'ask') {
        scheduleBootRefresh();
      }
      return;
    }

    switch (t) {
      case 'run_start': {
        S.running = true;
        // 新消息的 user_echo 通常先到，先记下来，重建实时区时不能把它弄丢
        const pending = S.pendingEcho;
        S.pendingEcho = null;
        S.live = {
          startedAt: ev.ts || Date.now(),
          users: pending ? [{ ...pending, afterBlocks: 0 }] : [],
          blocks: [], permission: null, ask: null
        };
        S.pendingPerm = null;
        S.pendingAsk = null;
        S.askSel = null;
        S.askOther = null;
        renderMessages();
        renderPanels();
        updateHeader();
        updateComposer();
        setRunProgress('正在思考…', 'thinking');
        startLivePolling();
        scheduleBootRefresh();
        break;
      }

      case 'user_echo': {
        const msg = ev.message || {};
        let alreadyShown = false;
        if (S.running && S.live) {
          const users = S.live.users || (S.live.users = []);
          const i = users.findIndex(u => u.optimistic && sameUserPayload(u, msg));
          if (i >= 0) {
            // 本地已经先画过这条消息，只把服务端的 id/时间补进去，不再插一个气泡
            users[i] = { ...msg, afterBlocks: users[i].afterBlocks || 0 };
            alreadyShown = true;
          } else {
            users.push({ ...msg, afterBlocks: (S.live.blocks || []).length });
          }
        } else if (S.pendingEcho && S.pendingEcho.optimistic && sameUserPayload(S.pendingEcho, msg)) {
          S.pendingEcho = msg;
          alreadyShown = true;
        } else {
          S.pendingEcho = msg;
        }
        if (!alreadyShown) {
          const box = $('#liveTurn');
          if (box) {
            box.appendChild(userNode(msg));
            maybeScroll();
          }
        }
        break;
      }

      case 'block_start': {
        onBlockStart(ev);
        const b = ev.block || {};
        if (b.type === 'thinking') setRunProgress(progressForThinking(b), 'thinking');
        else if (b.type === 'tool_use') setRunProgress(progressForTool(b), 'tool');
        else if (b.type === 'text') setRunProgress('', '');
        break;
      }

      case 'block_set': {
        onBlockSet(ev);
        const ref = S.liveEls.get(ev.key);
        if (ref && ref.tool) setRunProgress(progressForTool(ref.tool._block), 'tool');
        break;
      }

      case 'delta': {
        const ref = S.liveEls.get(ev.key);
        if (!ref || !ref.textEl) break;
        ref.textEl.textContent += ev.text || '';
        if (ev.kind === 'text') setRunProgress('', '');
        else if (ev.kind === 'thinking') {
          setRunProgress(progressForThinking({ text: ref.textEl.textContent }), 'thinking');
        }
        maybeScroll();
        break;
      }

      case 'tool_result': {
        onToolResult(ev);
        const ref = S.liveEls.get('tool:' + ev.toolUseId);
        const name = (ref && ref.tool && ref.tool._block && ref.tool._block.name) || '工具';
        setRunProgress(
          (ev.isError ? '工具失败：' : '已完成 ') + name + '，等待下一步…',
          ev.isError ? 'wait' : 'thinking'
        );
        break;
      }

      case 'permission':
        S.pendingPerm = ev;
        renderPanels();
        setRunProgress('等待你的确认：' + (ev.toolName || '操作') + '…', 'wait');
        maybeScroll(true);
        break;

      case 'permission_resolved':
        S.pendingPerm = null;
        renderPanels();
        setRunProgress('正在继续…', 'thinking');
        break;

      case 'ask':
        S.pendingAsk = ev;
        initAskState();
        renderPanels();
        setRunProgress('等待你的回答…', 'wait');
        maybeScroll(true);
        break;

      case 'ask_resolved':
        S.pendingAsk = null;
        S.askSel = null;
        S.askOther = null;
        renderPanels();
        setRunProgress('正在继续…', 'thinking');
        if (ev.cancelled) toast('问题已取消');
        else if (ev.auto) toast('已按推荐选项自动继续');
        break;

      case 'run_end':
        S.running = false;
        stopLivePolling();
        syncLiveCursor();
        closeStopConfirm();
        setRunProgress('', '');
        updateHeader();
        updateComposer();
        if (ev.error) toast(ev.error, 'err');
        else if (ev.stopped) toast('已停止');
        setTimeout(() => { if (S.convId) refreshConversation(); }, 700);
        scheduleBootRefresh();
        break;

      case 'error':
        setRunProgress('', '');
        toast(ev.message || '出错了', 'err');
        break;

      case 'compact_boundary': {
        const box = $('#liveTurn') || $('#messages');
        if (box) {
          const last = box.lastElementChild;
          if (!last || !last.classList.contains('compact-divider')) {
            box.appendChild(compactDividerNode());
          }
        }
        if (ev.post && S.conv) {
          S.conv.contextTokens = Number(ev.post) || S.conv.contextTokens;
          updateHeader();
        }
        setRunProgress('正在继续…', 'thinking');
        maybeScroll();
        break;
      }

      case 'status':
        if (ev.status === 'compacting') {
          toast('正在压缩上下文…');
          setRunProgress('正在压缩上下文…', 'compact');
        }
        break;

      default:
        break;
    }
  }

  let refreshing = false;
  async function refreshConversation() {
    if (!S.convId || refreshing) return;
    refreshing = true;
    try {
      const loaded = S.conv && Array.isArray(S.conv.messages) ? S.conv.messages.length : 30;
      const limit = Math.max(30, Math.min(200, loaded + 10));
      const data = await api('/api/conversation?id=' + encodeURIComponent(S.convId) +
        '&limit=' + limit + '&light=1', { timeout: 30000 });
      renderConversation(data);
    } catch { /* 下次事件再试 */ }
    finally { refreshing = false; }
  }

  // SSE 在手机网络/内置 Tailscale 代理上偶尔会「连接还在、事件不到」，
  // 又不会触发 onerror。运行中额外轮询实时快照，保证阶段总结和状态能更新。
  let livePollTimer = null;
  let livePolling = false;
  let lastLiveSeq = -1;
  let lastSseAt = 0;

  function stopLivePolling() {
    if (livePollTimer) { clearInterval(livePollTimer); livePollTimer = null; }
    livePolling = false;
    lastLiveSeq = -1;
  }

  function startLivePolling() {
    if (livePollTimer) return;
    livePollTimer = setInterval(pollLiveSnapshot, 1800);
    pollLiveSnapshot();
  }

  async function pollLiveSnapshot() {
    if (livePolling || !S.convId || !S.running || document.hidden) return;
    // SSE 还在正常推事件时不重复重建 DOM；超过 4 秒没事件才启用轮询兜底
    if (Date.now() - lastSseAt < 4000) return;
    livePolling = true;
    const wantId = S.convId;
    try {
      const data = await api('/api/conversation?id=' + encodeURIComponent(wantId) +
        '&limit=1&light=1', { timeout: 8000 });
      if (!S.convId || S.convId !== wantId) return;
      if (!data.running) {
        if (S.running) {
          S.running = false;
          S.live = null;
          stopLivePolling();
          syncLiveCursor();
          closeStopConfirm();
          setRunProgress('', '');
          updateHeader();
          updateComposer();
          refreshConversation();
        }
        return;
      }
      const live = data.live;
      if (!live) return;
      const seq = Number.isFinite(live.seq) ? live.seq : -1;
      if (seq >= 0 && seq === lastLiveSeq) return;
      lastLiveSeq = seq;

      const oldPerm = S.pendingPerm;
      const oldAsk = S.pendingAsk;
      S.running = true;
      S.live = live;
      S.pendingPerm = live.permission || null;
      S.pendingAsk = live.ask || null;
      if (data.contextTokens > 0 && S.conv) S.conv.contextTokens = data.contextTokens;

      const changed = JSON.stringify(oldPerm) !== JSON.stringify(S.pendingPerm) ||
        JSON.stringify(oldAsk) !== JSON.stringify(S.pendingAsk);
      if (changed) {
        if (S.pendingAsk) initAskState();
        else { S.askSel = null; S.askOther = null; }
        renderPanels();
      }
      const oldProc = document.querySelector('#liveTurn details.process');
      const procOpen = oldProc ? oldProc.open : null;
      renderLiveTurn();
      if (procOpen != null) {
        const nextProc = document.querySelector('#liveTurn details.process');
        if (nextProc) nextProc.open = procOpen;
      }
      updateHeader();
      updateComposer();
      syncProgressFromLive();
      maybeScroll();
    } catch { /* 下次再试，SSE 正常时不受影响 */ }
    finally { livePolling = false; }
  }

  /** 上滑到顶部时，按 before 游标补更早的一页 */
  async function loadOlderMessages() {
    if (!S.convId || !S.conv || !S.conv.hasMore || S.loadingOlder) return;
    S.loadingOlder = true;
    const box = $('#messages');
    const anchor = { h: box.scrollHeight, top: box.scrollTop };
    const btn = box.querySelector('.load-older');
    if (btn) {
      btn.disabled = true;
      btn.textContent = '正在加载更早消息…';
    }
    try {
      const data = await api('/api/conversation?id=' + encodeURIComponent(S.convId) +
        '&limit=30&before=' + encodeURIComponent(S.conv.start || 0) + '&light=1', { timeout: 30000 });
      const older = data.messages || [];
      if (older.length) {
        S.conv.messages = older.concat(S.conv.messages || []);
        S.conv.start = Number.isFinite(data.start)
          ? data.start
          : Math.max(0, (S.conv.start || 0) - older.length);
        S.conv.hasMore = !!data.hasMore;
        S.conv.total = Number.isFinite(data.total) ? data.total : S.conv.messages.length;
      } else {
        S.conv.hasMore = false;
      }
      renderMessages(false, anchor);
    } catch (e) {
      toast('加载更早消息失败：' + e.message, 'err');
      if (btn) {
        btn.disabled = false;
        btn.textContent = '↑ 加载更早消息';
      }
    } finally {
      S.loadingOlder = false;
    }
  }

  // ---------------------------------------------------------------- 面板

  function renderPanels() {
    const box = $('#panels');
    box.innerHTML = '';
    clearAskTimer();
    if (S.pendingPerm) box.appendChild(permPanel(S.pendingPerm));
    if (S.pendingAsk) box.appendChild(askPanel(S.pendingAsk));
    updateHeader();
  }

  function permPanel(ev) {
    const isPlan = ev.toolName === 'ExitPlanMode';
    const d = el('div', 'panel perm');
    const ttl = el('div', 'panel-ttl');
    ttl.innerHTML = isPlan
      ? '计划已就绪，开始执行？'
      : `Claude 请求使用 <b>${escapeHtml(ev.toolName || '工具')}</b>`;
    d.appendChild(ttl);

    const plan = ev.input && (ev.input.plan || ev.input.planText || ev.input.planMarkdown);
    const pre = el('pre');
    pre.textContent = isPlan && typeof plan === 'string' && plan ? plan : pretty(ev.input);
    d.appendChild(pre);

    const actions = el('div', 'panel-actions');
    const deny = el('button', 'btn danger', isPlan ? '继续修改' : '拒绝');
    const allow = el('button', 'btn primary', isPlan ? '开始执行' : '允许');
    deny.type = 'button';
    allow.type = 'button';
    deny.onclick = () => respondPermission(false, isPlan
      ? '用户希望先继续修改计划，请不要开始执行。'
      : '用户在手机上拒绝了该操作');
    allow.onclick = () => respondPermission(true, '');
    actions.append(deny, allow);
    d.appendChild(actions);
    return d;
  }

  function initAskState() {
    if (!S.pendingAsk) {
      S.askSel = null;
      S.askOther = null;
      return;
    }
    S.askSel = S.pendingAsk.questions.map(() => new Set());
    S.askOther = S.pendingAsk.questions.map(() => '');
  }

  function askPanel(ev) {
    const d = el('div', 'panel');
    d.appendChild(el('div', 'panel-ttl', 'Claude 想确认一下'));
    if (!S.askSel || S.askSel.length !== ev.questions.length) initAskState();

    ev.questions.forEach((q, qi) => {
      const qd = el('div', 'q');
      qd.appendChild(el('div', 'q-title', q.question || ''));
      if (q.multiSelect) qd.appendChild(el('div', 'q-hint', '可多选'));

      const opts = el('div', 'q-opts');
      const list = Array.isArray(q.options) ? q.options : [];
      list.forEach(opt => {
        const b = el('button', 'q-opt');
        b.type = 'button';
        b.innerHTML = `<div class="lbl">${escapeHtml(opt.label || '')}</div>` +
          (opt.description ? `<div class="desc">${escapeHtml(opt.description)}</div>` : '');
        if (S.askSel[qi].has(opt.label)) b.classList.add('sel');
        b.onclick = () => {
          if (q.multiSelect) {
            if (S.askSel[qi].has(opt.label)) S.askSel[qi].delete(opt.label);
            else S.askSel[qi].add(opt.label);
          } else {
            S.askSel[qi] = new Set([opt.label]);
          }
          [...opts.children].forEach((child, ci) => {
            child.classList.toggle('sel', S.askSel[qi].has((list[ci] || {}).label));
          });
        };
        opts.appendChild(b);
      });
      qd.appendChild(opts);

      const other = el('input', 'q-other');
      other.placeholder = '其他答案（可选，也可以直接输入）';
      other.value = (S.askOther && S.askOther[qi]) || '';
      other.oninput = () => { S.askOther[qi] = other.value; };
      qd.appendChild(other);
      d.appendChild(qd);
    });

    const note = el('div', 'panel-note');
    note.id = 'askCountdown';
    d.appendChild(note);

    const actions = el('div', 'panel-actions');
    const skip = el('button', 'btn', '跳过');
    const ok = el('button', 'btn primary', '提交');
    skip.type = 'button';
    ok.type = 'button';
    skip.onclick = () => submitAsk(true);
    ok.onclick = () => submitAsk(false);
    actions.append(skip, ok);
    d.appendChild(actions);

    startAskCountdown(ev, note);
    return d;
  }

  function clearAskTimer() {
    if (S.askTimer) {
      clearInterval(S.askTimer);
      S.askTimer = null;
    }
  }

  function startAskCountdown(ev, node) {
    clearAskTimer();
    const deadline = ev.major ? ev.cancelAt : ev.autoAt;
    if (!deadline) {
      node.textContent = '';
      return;
    }
    const tick = () => {
      const left = Math.max(0, Math.round((deadline - Date.now()) / 1000));
      node.innerHTML = ev.major
        ? `若 <b>${left}</b> 秒内无人回答，这一轮会自动暂停`
        : `约 <b>${left}</b> 秒后自动按推荐选项继续`;
      if (left <= 0) clearAskTimer();
    };
    tick();
    S.askTimer = setInterval(tick, 1000);
  }

  async function respondPermission(allow, message) {
    const ev = S.pendingPerm;
    if (!ev) return;
    const buttons = [...$('#panels').querySelectorAll('button')];
    buttons.forEach(b => { b.disabled = true; });
    try {
      await api('/api/permission', {
        method: 'POST',
        body: JSON.stringify({
          id: S.convId, requestId: ev.requestId, allow, message
        })
      });
      S.pendingPerm = null;
      renderPanels();
    } catch (e) {
      buttons.forEach(b => { b.disabled = false; });
      toast(e.message, 'err');
    }
  }

  async function submitAsk(cancel) {
    const ev = S.pendingAsk;
    if (!ev) return;

    let answers = null;
    if (!cancel) {
      answers = {};
      for (let i = 0; i < ev.questions.length; i++) {
        const q = ev.questions[i];
        const sel = [...((S.askSel && S.askSel[i]) || [])];
        const other = ((S.askOther && S.askOther[i]) || '').trim();
        let value = sel.join(', ');
        if (other) value = value ? `${value}, ${other}` : other;
        if (!value) {
          toast('每个问题都选一个选项，或直接输入答案', 'err');
          return;
        }
        answers[q.question] = value;
      }
    }

    const buttons = [...$('#panels').querySelectorAll('button')];
    buttons.forEach(b => { b.disabled = true; });
    try {
      await api('/api/ask', {
        method: 'POST',
        body: JSON.stringify({
          id: S.convId, requestId: ev.requestId, answers, cancel: !!cancel
        })
      });
      S.pendingAsk = null;
      S.askSel = null;
      S.askOther = null;
      renderPanels();
    } catch (e) {
      buttons.forEach(b => { b.disabled = false; });
      toast(e.message, 'err');
    }
  }

  // ---------------------------------------------------------------- 图片

  const MAX_PENDING_IMAGES = 5;

  function renderPendingImages() {
    const box = $('#pendingImages');
    box.innerHTML = '';
    box.hidden = S.pendingImages.length === 0;
    S.pendingImages.forEach((img, i) => {
      const d = el('div', 'pending-img');
      const im = el('img');
      im.src = img.preview;
      im.alt = '';
      im.onclick = () => openImageViewer(img.preview);
      d.appendChild(im);
      const b = el('button', null, '×');
      b.type = 'button';
      b.title = '移除';
      b.onclick = () => {
        S.pendingImages.splice(i, 1);
        renderPendingImages();
        updateSendState();
      };
      d.appendChild(b);
      box.appendChild(d);
    });
  }

  function renderPendingRefs() {
    const box = $('#pendingRefs');
    box.innerHTML = '';
    box.hidden = S.pendingRefs.length === 0;
    S.pendingRefs.forEach(ref => {
      const chip = el('span', 'ref-chip');
      const label = el('span', 'lbl', '引用：' + (ref.title || '未命名对话'));
      const x = el('button', null, '×');
      x.type = 'button';
      x.title = '移除引用';
      x.onclick = () => {
        S.pendingRefs = S.pendingRefs.filter(r => r.id !== ref.id);
        S.refSel.delete(ref.id);
        renderPendingRefs();
        updateSendState();
      };
      chip.append(label, x);
      box.appendChild(chip);
    });
  }

  const MAX_PENDING_FILES = 10;

  function renderPendingFiles() {
    const box = $('#pendingFiles');
    box.innerHTML = '';
    box.hidden = S.pendingFiles.length === 0;
    S.pendingFiles.forEach(f => {
      const chip = el('span', 'ref-chip file-chip');
      chip.title = f.path || f.name || '';
      chip.appendChild(el('span', 'file-ic', '📄'));
      chip.appendChild(el('span', 'lbl', f.name || '文件'));
      if (f.status) chip.appendChild(el('span', 'file-status', f.status));
      const x = el('button', null, '×');
      x.type = 'button';
      x.title = '移除';
      x.onclick = () => {
        S.pendingFiles = S.pendingFiles.filter(xf => xf.id !== f.id);
        renderPendingFiles();
        updateSendState();
      };
      chip.appendChild(x);
      box.appendChild(chip);
    });
  }

  function addPhoneFiles(files) {
    const list = [...(files || [])].filter(Boolean);
    const room = MAX_PENDING_FILES - S.pendingFiles.length;
    if (room <= 0) {
      toast(`最多 ${MAX_PENDING_FILES} 个文件`, 'err');
      return;
    }
    for (const file of list.slice(0, room)) {
      S.pendingFiles.push({
        id: 'f' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        name: file.name || '文件',
        size: file.size || 0,
        file,
        source: 'phone',
        status: ''
      });
    }
    renderPendingFiles();
    updateSendState();
    if (list.length > room) toast(`最多 ${MAX_PENDING_FILES} 个文件，多余的已忽略`, 'err');
  }

  function addPcFiles(files) {
    let added = 0;
    for (const f of files) {
      if (S.pendingFiles.length >= MAX_PENDING_FILES) {
        toast(`最多 ${MAX_PENDING_FILES} 个文件`, 'err');
        break;
      }
      if (S.pendingFiles.some(x => x.source === 'pc' && normPath(x.path) === normPath(f.path))) continue;
      S.pendingFiles.push({
        id: 'f' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        name: f.name || '文件',
        size: f.size || 0,
        path: f.path,
        source: 'pc',
        status: ''
      });
      added++;
    }
    renderPendingFiles();
    updateSendState();
    if (added) toast(`已引用 ${added} 个电脑文件`);
  }

  function uploadPendingFile(f) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload?id=' + encodeURIComponent(S.convId));
      xhr.timeout = 120000;
      xhr.ontimeout = () => reject(new Error('上传超时，网络太慢'));
      xhr.setRequestHeader('x-gui-token', TOKEN);
      xhr.setRequestHeader('x-file-name', encodeURIComponent(f.name || 'upload'));
      xhr.upload.onprogress = e => {
        if (!e.lengthComputable) return;
        f.status = '上传 ' + Math.round(e.loaded / e.total * 100) + '%';
        renderPendingFiles();
      };
      xhr.onload = () => {
        let data = null;
        try { data = JSON.parse(xhr.responseText); } catch { /* ignore */ }
        if (xhr.status >= 200 && xhr.status < 300 && data && data.ok) resolve(data);
        else reject(new Error((data && data.error) || ('HTTP ' + xhr.status)));
      };
      xhr.onerror = () => reject(new Error('上传失败，请检查网络'));
      xhr.send(f.file);
    });
  }

  /** 相册图片通常很大，先缩到 1600px 再转 JPEG；保留较小的 PNG 透明背景 */
  function readImageFile(file) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => {
        try {
          const maxDim = 1600;
          const iw = img.naturalWidth || img.width;
          const ih = img.naturalHeight || img.height;
          const scale = Math.min(1, maxDim / Math.max(iw, ih));
          let w = Math.max(1, Math.round(iw * scale));
          let h = Math.max(1, Math.round(ih * scale));

          const canvas = document.createElement('canvas');
          let type = (file.type === 'image/png' && file.size <= 2.5 * 1024 * 1024)
            ? 'image/png' : 'image/jpeg';
          const draw = () => {
            canvas.width = w;
            canvas.height = h;
            const ctx = canvas.getContext('2d');
            if (type !== 'image/png') {
              ctx.fillStyle = '#fff';
              ctx.fillRect(0, 0, w, h);
            }
            ctx.drawImage(img, 0, 0, w, h);
          };
          draw();

          let dataUrl = canvas.toDataURL(type, 0.85);
          if (dataUrl.length > 4.5 * 1024 * 1024) {
            type = 'image/jpeg';
            w = Math.max(1, Math.round(w * 0.72));
            h = Math.max(1, Math.round(h * 0.72));
            draw();
            dataUrl = canvas.toDataURL('image/jpeg', 0.7);
          }

          URL.revokeObjectURL(url);
          const comma = dataUrl.indexOf(',');
          resolve({
            media_type: dataUrl.slice(5, dataUrl.indexOf(';')),
            data: dataUrl.slice(comma + 1),
            preview: dataUrl,
            name: file.name || '图片'
          });
        } catch (e) {
          URL.revokeObjectURL(url);
          reject(e);
        }
      };
      img.onerror = () => {
        URL.revokeObjectURL(url);
        reject(new Error('无法解码这张图片'));
      };
      img.src = url;
    });
  }

  async function addImageFiles(files) {
    const list = [...(files || [])].filter(f => f && /^image\//.test(f.type));
    if (!list.length) return;
    const room = MAX_PENDING_IMAGES - S.pendingImages.length;
    if (room <= 0) {
      toast(`最多 ${MAX_PENDING_IMAGES} 张图片`, 'err');
      return;
    }
    for (const file of list.slice(0, room)) {
      try {
        const img = await readImageFile(file);
        S.pendingImages.push(img);
        renderPendingImages();
      } catch (e) {
        toast('图片读取失败：' + (file.name || '未知文件'), 'err');
      }
    }
    if (list.length > room) toast(`最多 ${MAX_PENDING_IMAGES} 张，多余的已忽略`, 'err');
    updateSendState();
  }

  // ---------------------------------------------------------------- 发送

  async function composeWithRefs(text, refs) {
    const packs = [];
    for (const r of refs) {
      // handoff=1 只取最近一次压缩摘要 + 最近几十条，避免把整份长转录拉过来
      const c = await api('/api/conversation?id=' + encodeURIComponent(r.id) + '&handoff=1', { timeout: 30000 });
      packs.push({
        id: r.id,
        title: c.title || r.title,
        cwd: c.cwd || r.cwd,
        updatedAt: r.updatedAt,
        messages: c.messages || []
      });
    }
    const digest = window.ClaudeRefDigest;
    if (!digest || typeof digest.buildBlock !== 'function') {
      throw new Error('引用摘要模块未加载');
    }
    const block = digest.buildBlock(packs, text, {
      maxChars: refs.length > 1 ? 15000 : 12000
    });
    return block ? (text ? text + '\n\n' : '') + block : text;
  }

  async function sendMessage() {
    const input = $('#input');
    const text = input.value.trim();
    const images = S.pendingImages.map(x => ({ media_type: x.media_type, data: x.data }));
    const refs = S.pendingRefs.slice();
    const files = S.pendingFiles.slice();
    if ((!text && !images.length && !refs.length && !files.length) || !S.convId || S.sending) return;

    S.sending = true;
    const btn = $('#sendBtn');
    btn.disabled = true;
    const wasRunning = S.running;
    let sentSteer = wasRunning;
    const sendConvId = S.convId;
    try {
      let finalText = text;
      if (refs.length) {
        toast('正在整理引用…');
        finalText = await composeWithRefs(text, refs);
      }

      const filePaths = [];
      const needsUpload = files.some(f => !f.path);
      if (needsUpload) toast('正在上传文件…');
      for (const f of files) {
        if (f.path) {
          filePaths.push(f.path);
          continue;
        }
        if (!f.file) continue;
        f.status = '准备上传…';
        renderPendingFiles();
        const data = await uploadPendingFile(f);
        f.path = data.path;
        f.name = data.name || f.name;
        f.status = '已上传';
        renderPendingFiles();
        filePaths.push(data.path);
      }

      if (S.convId !== sendConvId) {
        toast('已切换对话，这条消息没有发送');
        return;
      }
      if (filePaths.length) {
        const block = '[引用的文件/文件夹，请按需读取]\n' +
          filePaths.map(p => '- [文件] ' + p).join('\n');
        finalText = finalText ? finalText + '\n\n' + block : block;
      }

      const payload = { id: sendConvId, text: finalText };
      if (images.length) payload.images = images;
      const body = JSON.stringify(payload);
      if (wasRunning) {
        try {
          await api('/api/steer', { method: 'POST', body, timeout: 120000 });
        } catch (e) {
          if (e.notRunning || /刚刚结束/.test(e.message)) {
            await api('/api/chat', { method: 'POST', body, timeout: 120000 });
            sentSteer = false;
            S.running = true;
            updateComposer();
          } else {
            throw e;
          }
        }
      } else {
        try {
          await api('/api/chat', { method: 'POST', body, timeout: 120000 });
        } catch (e) {
          // 状态显示没在跑、但服务端其实还在跑：退化成插话，避免消息丢失
          if (/还在处理中/.test(e.message)) {
            await api('/api/steer', { method: 'POST', body, timeout: 120000 });
            sentSteer = true;
          } else {
            throw e;
          }
        }
        S.running = true;
        updateComposer();
      }
      input.value = '';
      S.pendingImages = [];
      S.pendingRefs = [];
      S.pendingFiles = [];
      S.refSel = new Map();
      S.drafts.delete(sendConvId);
      renderPendingImages();
      renderPendingRefs();
      renderPendingFiles();
      autoGrow();
      maybeScroll(true);
      startLivePolling();
      // SSE 可能正在重连；发送成功后先在本地画出这条消息，回显到了会去重
      appendSentUser({
        id: 'local' + Date.now(),
        role: 'user',
        text: finalText,
        images: images.length ? images : undefined,
        steer: sentSteer,
        ts: new Date().toISOString()
      });
      // 悬浮窗插话时 App 会先暂停手机操作；消息发出后通知 App 继续。
      try {
        if (window.AndroidDownloads && AndroidDownloads.phoneSent) AndroidDownloads.phoneSent();
      } catch { /* 非 App 环境 */ }
    } catch (e) {
      // 让未上传成功的文件回到可重试状态
      for (const f of S.pendingFiles) {
        if (!f.path) f.status = '';
      }
      renderPendingFiles();
      toast(e.message || '发送失败', 'err');
    } finally {
      S.sending = false;
      updateSendState();
    }
  }

  function closeStopConfirm() {
    $('#confirmStop').hidden = true;
  }

  function stopRun() {
    if (!S.convId || !S.running) return;
    $('#confirmStop').hidden = false;
  }

  async function doStop() {
    closeStopConfirm();
    if (!S.convId) return;
    try {
      await api('/api/stop', { method: 'POST', body: JSON.stringify({ id: S.convId }) });
    } catch (e) {
      toast(e.message, 'err');
    }
  }

  function autoGrow() {
    const ta = $('#input');
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 132) + 'px';
  }

  function syncViewportHeight() {
    const vv = window.visualViewport;
    const h = vv ? vv.height : window.innerHeight;
    if (h > 0) {
      document.documentElement.style.setProperty('--app-h', Math.round(h) + 'px');
    }
  }

  function isNearBottom() {
    const m = $('#messages');
    return m.scrollHeight - m.scrollTop - m.clientHeight < 100;
  }

  let scrollPending = false;
  let scrollHoldUntil = 0;

  /** 用户手指刚碰过消息区，短时间内不要自动回到底部，避免和滑动抢 */
  function holdAutoScroll() {
    scrollHoldUntil = Date.now() + 1200;
  }

  function maybeScroll(force) {
    if (!force) {
      if (Date.now() < scrollHoldUntil) return;
      if (!isNearBottom()) return;
    }
    if (scrollPending) return;
    scrollPending = true;
    requestAnimationFrame(() => {
      scrollPending = false;
      const m = $('#messages');
      m.scrollTop = m.scrollHeight;
    });
  }

  // ---------------------------------------------------------------- 新对话

  function openSheet() {
    const box = $('#folderList');
    box.innerHTML = '';
    const seen = new Set();
    for (const f of (S.boot && S.boot.folders) || []) {
      if (!f || !f.path || seen.has(f.path)) continue;
      seen.add(f.path);
      const b = el('button', 'folder-pick');
      b.type = 'button';
      b.innerHTML = `<div class="n">${escapeHtml(f.name)}</div><div class="p">${escapeHtml(f.path)}</div>`;
      b.onclick = () => newConversation(f.path);
      box.appendChild(b);
    }
    if (!box.children.length) {
      box.innerHTML = '<div class="empty">还没有文件夹，直接在下面输入路径</div>';
    }
    $('#pathInput').value = '';
    $('#sheet').hidden = false;
  }

  function closeSheet() {
    $('#sheet').hidden = true;
  }

  async function newConversation(cwd) {
    cwd = String(cwd || '').trim();
    if (!cwd) {
      toast('请选择或输入文件夹路径', 'err');
      return;
    }
    try {
      const data = await api('/api/conversation', {
        method: 'POST',
        body: JSON.stringify({ cwd })
      });
      closeSheet();
      await loadBoot();
      openConv(data.id);
    } catch (e) {
      toast(e.message, 'err');
    }
  }

  // ---------------------------------------------------------------- 附件与引用

  function openAttachSheet() { $('#attachSheet').hidden = false; }
  function closeAttachSheet() { $('#attachSheet').hidden = true; }

  function allConversations() {
    const out = [];
    for (const f of (S.boot && S.boot.folders) || []) {
      for (const c of f.conversations || []) out.push({ ...c, folder: f.path });
    }
    return out;
  }

  function openRefPicker() {
    S.refSel = new Map((S.pendingRefs || []).map(r => [r.id, r]));
    $('#refSearch').value = '';
    renderRefList();
    $('#refSheet').hidden = false;
  }

  function closeRefSheet() { $('#refSheet').hidden = true; }

  function updateRefCount() {
    const n = S.refSel.size;
    $('#refCount').textContent = n ? `已选 ${n} / 3` : '未选择（最多 3 个）';
  }

  function renderRefList() {
    const box = $('#refList');
    box.innerHTML = '';
    updateRefCount();
    const q = ($('#refSearch').value || '').trim().toLowerCase();
    const convs = allConversations()
      .filter(c => !S.convId || c.id !== S.convId)
      .filter(c => {
        if (!q) return true;
        return `${c.title || ''} ${c.preview || ''} ${c.folder || ''}`.toLowerCase().includes(q);
      })
      .sort((a, b) => new Date(b.updatedAt || 0) - new Date(a.updatedAt || 0));

    if (!convs.length) {
      box.innerHTML = '<div class="empty">没有可引用的对话</div>';
      return;
    }

    for (const c of convs) {
      const empty = !!c.placeholder || !c.turns;
      const sel = S.refSel.has(c.id);
      const row = el('button', 'ref-item' + (sel ? ' sel' : '') + (empty ? ' off' : ''));
      row.type = 'button';
      row.disabled = empty;
      const folderName = String(c.folder || '').split(/[\\/]/).filter(Boolean).pop() || '';
      row.innerHTML =
        `<span class="check">${sel ? '✓' : ''}</span>` +
        `<span class="main"><span class="t">${escapeHtml(c.title || '未命名对话')}</span>` +
        `<span class="d">${escapeHtml(c.preview || '（没有预览）')}</span>` +
        `<span class="m">${escapeHtml(folderName)} · ${escapeHtml(convTime(c.updatedAt))} · ${Number(c.turns) || 0} 轮</span></span>`;
      row.onclick = () => {
        if (empty) return;
        if (S.refSel.has(c.id)) {
          S.refSel.delete(c.id);
        } else {
          if (S.refSel.size >= 3) {
            toast('最多同时引用 3 个对话', 'err');
            return;
          }
          S.refSel.set(c.id, {
            id: c.id,
            title: c.title || '未命名对话',
            cwd: c.folder || c.cwd,
            updatedAt: c.updatedAt,
            turns: c.turns
          });
        }
        renderRefList();
      };
      box.appendChild(row);
    }
  }

  function confirmRefs() {
    S.pendingRefs = [...S.refSel.values()];
    renderPendingRefs();
    closeRefSheet();
    updateSendState();
    if (S.pendingRefs.length) toast(`已引用 ${S.pendingRefs.length} 个对话`);
  }

  // ---------------------------------------------------------------- 电脑文件浏览

  function openPcFileBrowser() {
    S.pcSel = new Map();
    const start = (S.conv && S.conv.cwd) || '';
    $('#pcFileSheet').hidden = false;
    loadPcDir(start).catch(e => toast('读取电脑目录失败：' + e.message, 'err'));
  }

  function closePcFileSheet() {
    $('#pcFileSheet').hidden = true;
  }

  async function loadPcDir(p) {
    const data = await api('/api/fs?files=1&path=' + encodeURIComponent(p || ''));
    S.pcPath = data.path || '';
    S.pcParent = data.parent || null;
    S.pcEntries = (data.entries || []).filter(e => e.isDir || !e.hidden);
    renderPcList();
  }

  function renderPcList() {
    $('#pcPath').textContent = S.pcPath || '此电脑（选择磁盘）';
    $('#pcUp').disabled = !S.pcParent;
    const box = $('#pcList');
    box.innerHTML = '';
    if (!S.pcEntries.length) {
      box.innerHTML = '<div class="pc-empty">这个文件夹是空的</div>';
    }
    for (const e of S.pcEntries) {
      const sel = !e.isDir && S.pcSel.has(e.path);
      const row = el('button', 'pc-row' + (sel ? ' sel' : ''));
      row.type = 'button';
      row.appendChild(el('span', 'pc-ic', e.isDir ? '📁' : '📄'));
      row.appendChild(el('span', 'pc-name', e.name));
      if (!e.isDir && e.size) row.appendChild(el('span', 'pc-size', fmtBytes(e.size)));
      row.onclick = () => {
        if (e.isDir) {
          loadPcDir(e.path).catch(err => toast('读取失败：' + err.message, 'err'));
          return;
        }
        if (S.pcSel.has(e.path)) S.pcSel.delete(e.path);
        else S.pcSel.set(e.path, e);
        renderPcList();
      };
      box.appendChild(row);
    }
    $('#pcConfirm').textContent = S.pcSel.size ? `引用 (${S.pcSel.size})` : '引用';
    $('#pcConfirm').disabled = S.pcSel.size === 0;
  }

  function pcGoUp() {
    if (!S.pcParent) return;
    loadPcDir(S.pcParent).catch(e => toast('读取失败：' + e.message, 'err'));
  }

  function confirmPcFiles() {
    const files = [...S.pcSel.values()];
    if (!files.length) return;
    addPcFiles(files);
    closePcFileSheet();
  }

  // ---------------------------------------------------------------- 对话设置

  const MODE_LABELS = {
    bypassPermissions: '全部放行',
    auto: '自动判断',
    acceptEdits: '自动接受编辑',
    plan: '只读规划'
  };
  const EFFORT_LABELS = {
    low: '低', medium: '中', high: '高', xhigh: '极高', max: '最高'
  };

  function fillSelect(sel, items) {
    sel.innerHTML = '';
    for (const it of items) {
      const o = document.createElement('option');
      o.value = it.value;
      o.textContent = it.label;
      sel.appendChild(o);
    }
  }

  function openChatSettings() {
    if (!S.conv) return;
    const settings = (S.boot && S.boot.settings) || {};
    const app = (S.boot && S.boot.app) || {};

    $('#setTitle').value = S.conv.title || '';

    const modes = app.permissionModes || Object.keys(MODE_LABELS);
    fillSelect($('#setMode'), modes.map(m => ({ value: m, label: MODE_LABELS[m] || m })));
    $('#setMode').value = S.conv.permissionMode || '';

    const models = [{ value: '', label: '默认（跟随设置）' }].concat(
      (settings.availableModels || []).map(m => ({
        value: m.id,
        label: m.id + (m.roles && m.roles.length ? `（${m.roles.join(' / ')}）` : '')
      }))
    );
    if (S.conv.model && !models.some(m => m.value === S.conv.model)) {
      models.push({ value: S.conv.model, label: S.conv.model });
    }
    fillSelect($('#setModel'), models);
    $('#setModel').value = S.conv.model || '';

    const efforts = [{ value: '', label: '默认' }].concat(
      (settings.effortLevels || []).map(e => ({ value: e, label: EFFORT_LABELS[e] || e }))
    );
    fillSelect($('#setEffort'), efforts);
    $('#setEffort').value = S.conv.effort || '';

    $('#chatSettings').hidden = false;
  }

  function closeChatSettings() {
    $('#chatSettings').hidden = true;
  }

  async function saveChatSettings() {
    if (!S.convId) return;
    const body = {
      title: $('#setTitle').value.trim(),
      model: $('#setModel').value,
      effort: $('#setEffort').value,
      permissionMode: $('#setMode').value
    };
    try {
      const data = await api('/api/conversation?id=' + encodeURIComponent(S.convId), {
        method: 'PATCH',
        body: JSON.stringify(body)
      });
      const conv = (data && data.conversation) || {};
      S.conv.title = conv.title != null ? conv.title : (body.title || null);
      S.conv.model = conv.model != null ? conv.model : body.model;
      S.conv.effort = conv.effort || '';
      S.conv.permissionMode = conv.permissionMode || body.permissionMode;
      $('#chatTitle').textContent = S.conv.title || '未命名对话';
      closeChatSettings();
      updateHeader();
      toast('已保存，下一条消息生效');
      loadBoot().catch(() => {});
    } catch (e) {
      toast(e.message, 'err');
    }
  }

  function askDeleteConv(id) {
    const target = id || S.convId;
    if (!target) return;
    S.deleteId = target;
    $('#confirmDelete').hidden = false;
  }

  function closeDeleteConfirm() {
    $('#confirmDelete').hidden = true;
  }

  async function deleteConversation() {
    const id = S.deleteId || S.convId;
    if (!id) return;
    closeDeleteConfirm();
    try {
      await api('/api/conversation?id=' + encodeURIComponent(id), { method: 'DELETE' });
      const wasCurrent = S.convId === id;
      S.deleteId = null;
      if (wasCurrent) {
        closeChatSettings();
        closeChat(false);
      }
      toast('对话已删除');
      loadBoot().catch(() => {});
    } catch (e) {
      toast(e.message, 'err');
    }
  }

  // ---------------------------------------------------------------- SSE

  let reconnectTimer = null;

  function scheduleReconnect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(() => {
      if (!S.conn) hardReconnect();
    }, 8000);
  }

  /** 网络切换/EventSource 卡死时，手动重建连接；不重载页面 */
  function hardReconnect() {
    if (S.conn) return;
    try { if (S.es) S.es.close(); } catch { /* ignore */ }
    S.es = null;
    connect();
    loadBoot().catch(() => {});
    if (S.convId) refreshConversation();
  }

  function connect() {
    try { if (S.es) S.es.close(); } catch { /* ignore */ }
    const es = new EventSource('/api/stream?token=' + encodeURIComponent(TOKEN));
    S.es = es;
    es.onopen = () => {
      clearTimeout(reconnectTimer);
      lastSseAt = Date.now();
      const wasDown = S.wasDown;
      S.wasDown = false;
      setConn(true);
      if (wasDown) {
        loadBoot().catch(() => {});
        if (S.convId) refreshConversation();
      }
    };
    es.onerror = () => {
      S.wasDown = true;
      setConn(false);
      scheduleReconnect();
    };
    es.onmessage = e => {
      lastSseAt = Date.now();
      try { handleEvent(JSON.parse(e.data)); } catch { /* 忽略坏事件 */ }
    };
  }

  // ---------------------------------------------------------------- 初始化

  function bind() {
    $('#refreshBtn').onclick = () => { loadBoot().catch(e => toast(e.message, 'err')); };
    $('#newBtn').onclick = openSheet;
    $('#sheetClose').onclick = closeSheet;
    $('#sheet').addEventListener('click', e => {
      if (e.target === $('#sheet')) closeSheet();
    });
    $('#pathGo').onclick = () => newConversation($('#pathInput').value);
    $('#pathInput').addEventListener('keydown', e => {
      if (e.key === 'Enter') {
        e.preventDefault();
        newConversation($('#pathInput').value);
      }
    });
    $('#searchInput').addEventListener('input', renderList);
    $('#backBtn').onclick = () => closeChat();
    $('#stopBtn').onclick = stopRun;
    $('#cancelStop').onclick = closeStopConfirm;
    $('#confirmStopBtn').onclick = doStop;
    $('#confirmStop').addEventListener('click', e => {
      if (e.target === $('#confirmStop')) closeStopConfirm();
    });
    const quickEffort = $('#quickEffort');
    if (quickEffort) quickEffort.onchange = changeQuickEffort;
    $('#chatMenuBtn').onclick = openChatSettings;
    $('#settingsClose').onclick = closeChatSettings;
    $('#settingsSave').onclick = saveChatSettings;
    $('#convDelete').onclick = () => askDeleteConv(S.convId);
    $('#cancelDelete').onclick = closeDeleteConfirm;
    $('#confirmDeleteBtn').onclick = deleteConversation;
    $('#chatSettings').addEventListener('click', e => {
      if (e.target === $('#chatSettings')) closeChatSettings();
    });
    $('#confirmDelete').addEventListener('click', e => {
      if (e.target === $('#confirmDelete')) closeDeleteConfirm();
    });

    $('#composer').addEventListener('submit', e => {
      e.preventDefault();
      sendMessage();
    });
    $('#input').addEventListener('keydown', e => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        sendMessage();
      }
    });
    $('#input').addEventListener('input', () => {
      autoGrow();
      updateSendState();
    });
    $('#attachBtn').onclick = openAttachSheet;
    $('#attachClose').onclick = closeAttachSheet;
    $('#attachSheet').addEventListener('click', e => {
      if (e.target === $('#attachSheet')) closeAttachSheet();
    });
    $('#pickImage').onclick = () => {
      closeAttachSheet();
      $('#fileInput').click();
    };
    $('#pickRef').onclick = () => {
      closeAttachSheet();
      openRefPicker();
    };
    $('#pickFile').onclick = () => {
      closeAttachSheet();
      $('#fileAnyInput').click();
    };
    $('#pickPcFile').onclick = () => {
      closeAttachSheet();
      openPcFileBrowser();
    };
    $('#fileAnyInput').addEventListener('change', e => {
      addPhoneFiles(e.target.files);
      e.target.value = '';
    });
    $('#pcFileClose').onclick = closePcFileSheet;
    $('#pcCancel').onclick = closePcFileSheet;
    $('#pcConfirm').onclick = confirmPcFiles;
    $('#pcDrives').onclick = () => {
      loadPcDir('').catch(e => toast('读取磁盘列表失败：' + e.message, 'err'));
    };
    $('#pcUp').onclick = pcGoUp;
    $('#pcFileSheet').addEventListener('click', e => {
      if (e.target === $('#pcFileSheet')) closePcFileSheet();
    });
    $('#refClose').onclick = closeRefSheet;
    $('#refCancel').onclick = closeRefSheet;
    $('#refConfirm').onclick = confirmRefs;
    $('#refSearch').addEventListener('input', renderRefList);
    $('#refSheet').addEventListener('click', e => {
      if (e.target === $('#refSheet')) closeRefSheet();
    });
    $('#balanceBtn').onclick = () => { loadBalance(true); };
    $('#reconnectBtn').onclick = () => location.reload();
    $('#reconnectBtn2').onclick = () => location.reload();
    window.addEventListener('online', () => { if (!S.conn) hardReconnect(); });
    window.addEventListener('pageshow', () => { if (!S.conn) hardReconnect(); });
    $('#downloadsBtn').onclick = openDownloadsSheet;
    $('#downloadsClose').onclick = closeDownloadsSheet;
    $('#downloadsSheet').addEventListener('click', e => {
      if (e.target === $('#downloadsSheet')) closeDownloadsSheet();
    });
    $('#fileInput').addEventListener('change', e => {
      addImageFiles(e.target.files);
      e.target.value = '';
    });
    $('#input').addEventListener('paste', e => {
      const files = [...((e.clipboardData && e.clipboardData.items) || [])]
        .filter(it => it.kind === 'file' && /^image\//.test(it.type))
        .map(it => it.getAsFile())
        .filter(Boolean);
      if (files.length) {
        e.preventDefault();
        addImageFiles(files);
      }
    });

    $('#messages').addEventListener('touchstart', holdAutoScroll, { passive: true });
    $('#messages').addEventListener('wheel', holdAutoScroll, { passive: true });
    $('#messages').addEventListener('scroll', () => {
      scheduleProcessCollapseUpdate();
      const m = $('#messages');
      if (m.scrollTop < 90 && S.conv && S.conv.hasMore && !S.loadingOlder) {
        loadOlderMessages();
      }
    }, { passive: true });

    window.addEventListener('resize', syncViewportHeight);
    window.addEventListener('resize', scheduleProcessCollapseUpdate);
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', syncViewportHeight);
      window.visualViewport.addEventListener('scroll', syncViewportHeight);
    }
    syncViewportHeight();

    $('#imgViewerClose').onclick = closeImageViewer;
    // 点图片本身或空白处都可以关掉看图，符合“再点一下返回”
    $('#imgViewer').addEventListener('click', closeImageViewer);
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') {
        closeImageViewer();
        closeStopConfirm();
        closeChatSettings();
        closeDeleteConfirm();
        closeAttachSheet();
        closeRefSheet();
        closeDownloadsSheet();
        closePcFileSheet();
      }
    });

    window.addEventListener('popstate', e => {
      const st = e.state;
      if (st && st.conv) openConv(st.conv, false);
      else closeChat(false);
    });

    document.addEventListener('visibilitychange', () => {
      if (document.hidden) return;
      if (!S.conn) hardReconnect();
      loadBoot().catch(() => {});
      if (S.convId) {
        refreshConversation();
        if (S.running) startLivePolling();
      }
    });
  }

  async function init() {
    if (!TOKEN) {
      document.body.innerHTML = '<div style="padding:40px;color:#e8e6e3;font:15px system-ui">缺少访问令牌，请重新用带 token 的地址打开。</div>';
      return;
    }
    bind();
    loadFolderCollapsed();
    connect();

    try {
      await loadBoot();
    } catch (e) {
      toast('读取对话列表失败：' + e.message, 'err');
    }
    loadBalance(false);
    refreshDownloads();

    const m = location.hash.match(/^#c=(.+)$/);
    if (m) {
      const id = decodeURIComponent(m[1]);
      history.replaceState(null, '', location.pathname + location.search);
      openConv(id);
    } else {
      showChat(false);
    }

    const tip = $('#installTip');
    const standalone = window.matchMedia('(display-mode: standalone)').matches ||
      window.matchMedia('(display-mode: fullscreen)').matches ||
      window.navigator.standalone;
    tip.hidden = !!standalone || IS_APP;
    if (location.protocol === 'http:' && !IS_APP) $('#httpsTip').hidden = false;

    if ('serviceWorker' in navigator && location.protocol === 'https:') {
      navigator.serviceWorker.register('/sw.js').catch(() => {});
    }
  }

  // 从悬浮窗/外部打开指定对话。
  window.addEventListener('hashchange', () => {
    const m = location.hash.match(/^#c=(.+)$/);
    if (!m) return;
    let id;
    try { id = decodeURIComponent(m[1]); } catch { id = m[1]; }
    if (id && id !== S.convId) openConv(id);
  });

  // App 切到后台/回到前台时同步聊天页可见状态。
  document.addEventListener('visibilitychange', () => {
    try {
      if (!window.AndroidDownloads) return;
      if (document.hidden) {
        if (AndroidDownloads.phoneChatHidden) AndroidDownloads.phoneChatHidden();
        return;
      }
      const c = document.getElementById('chatScreen');
      if (c && !c.hidden) {
        if (AndroidDownloads.phoneChatVisible) {
          AndroidDownloads.phoneChatVisible(S.convId || '');
        }
        if (AndroidDownloads.phoneChatEntered) {
          AndroidDownloads.phoneChatEntered(S.convId || '');
        }
      }
    } catch { /* 非 App 环境 */ }
  });

  // 调试入口：控制台里可以看状态；不影响正常使用
  window.__m = S;

  init();
})();

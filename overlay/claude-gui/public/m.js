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
    needsResumeSync: false, // 后台期间可能漏 run_end：回前台/发送前要重新对齐服务器状态
    lastResumeSyncAt: 0,
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
    pendingQuotes: [],      // 待发送的文字引用
    refSel: new Map(),
    pendingFiles: [],       // 待发送/引用的文件（手机上传或电脑路径）
    filter: 'all',          // 对话列表筛选：all / running / waiting
    unread: new Set(),      // 本轮结束时不在当前对话的会话 id
    todos: null,            // 最新 TodoWrite 清单
    todoOpen: false,
    msgAction: null,        // 当前长按的消息 { text, role, source, sourceId }
    quoteTarget: null,      // 「引用至其他对话」待选择的引用
    fontScale: 'normal',
    pcPath: '',
    pcParent: null,
    pcEntries: [],
    pcSel: new Map(),
    collapsedFolders: new Set(),
    drafts: new Map(),      // convId -> { text, images, refs, files, quotes }
    downloads: [],          // 手机 App 已下载的文件
    status: { data: null, open: false, loading: false, timer: 0 },
    loadingOlder: false,
    sending: false,
    liveProcessEl: null,
    liveProcessBody: null,
    liveProcessMeta: null,
    liveProcessState: null,
    liveProcessCollapse: null,
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

  function hideToast() {
    clearTimeout(toastTimer);
    const t = $('#toast');
    if (t) t.hidden = true;
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

  const FONT_SCALES = { small: .92, normal: 1, large: 1.12, xlarge: 1.24 };

  function applyFontScale(scale) {
    S.fontScale = FONT_SCALES[scale] ? scale : 'normal';
    document.documentElement.style.setProperty('--font-scale', String(FONT_SCALES[S.fontScale]));
    try { localStorage.setItem('m.fontScale', S.fontScale); } catch { /* ignore */ }
  }

  function loadFontScale() {
    let v = 'normal';
    try { v = localStorage.getItem('m.fontScale') || 'normal'; } catch { /* ignore */ }
    applyFontScale(v);
  }

  // 弹层打开时压一条 history；返回键先关弹层，不直接退页面。
  let cancelMsgPress = null;
  let lastMsgScrollAt = 0;
  const sheetStack = [];
  let sheetObserver = null;
  let sheetHistoryCleanup = false;

  function setupSheetBackHandling() {
    if (sheetObserver) return;
    const els = [...document.querySelectorAll('.sheet')];
    const viewer = document.getElementById('imgViewer');
    if (viewer) els.push(viewer);
    sheetObserver = new MutationObserver(records => {
      for (const r of records) {
        const el = r.target;
        if (!el || !el.id) continue;
        if (el.hidden) {
          const i = sheetStack.lastIndexOf(el);
          if (i >= 0) sheetStack.splice(i, 1);
          if (!sheetHistoryCleanup && history.state && history.state.sheet === el.id) {
            sheetHistoryCleanup = true;
            setTimeout(() => {
              try { history.back(); } catch { sheetHistoryCleanup = false; }
            }, 0);
          }
        } else if (!sheetStack.includes(el)) {
          sheetStack.push(el);
          try {
            history.pushState({ ...(history.state || {}), sheet: el.id }, '');
          } catch { /* ignore */ }
        }
      }
    });
    els.forEach(el => sheetObserver.observe(el, { attributes: true, attributeFilter: ['hidden'] }));
  }

  function closeTopSheetFromHistory() {
    if (!sheetStack.length) return false;
    const top = sheetStack.pop();
    if (top.id === 'imgViewer') closeImageViewer();
    else top.hidden = true;
    return true;
  }

  function msgPreview(text) {
    const s = String(text || '')
      .replace(/```[\s\S]*?```/g, ' [代码] ')
      .replace(/<gui-quote>[\s\S]*?<\/gui-quote>/g, '')
      .replace(/^\s*\|.*\|\s*$/gm, ' ')
      .replace(/^\s*#{1,6}\s+/gm, '')
      .replace(/^\s*[-*+]\s+/gm, '')
      .replace(/^\s*\d+\.\s+/gm, '')
      .replace(/[*_~`>]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
    return Array.from(s).slice(0, 60).join('') + (Array.from(s).length > 60 ? '…' : '');
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

  function convMatchesFilter(c) {
    if (S.filter === 'running') return !!c.running;
    if (S.filter === 'waiting') return !!c.waiting;
    return true;
  }

  function emptyState(query) {
    const q = String(query || '').trim();
    if (q) {
      return `<div class="empty-state"><div class="empty-art">⌕</div>` +
        `<div class="empty-title">没有匹配的对话</div>` +
        `<div class="empty-sub">换个关键词，或清空搜索</div></div>`;
    }
    if (S.filter !== 'all') {
      return `<div class="empty-state"><div class="empty-art">◎</div>` +
        `<div class="empty-title">这个筛选下没有对话</div>` +
        `<button type="button" class="empty-act" data-empty-act="all">看全部对话</button></div>`;
    }
    return `<div class="empty-state"><div class="empty-art">✦</div>` +
      `<div class="empty-title">还没有对话</div>` +
      `<div class="empty-sub">选一个工作文件夹，让 Claude 开始干活</div>` +
      `<button type="button" class="empty-act primary" data-empty-act="new">新建对话</button></div>`;
  }

  function renderList() {
    const box = $('#convList');
    box.innerHTML = '';
    const q = ($('#searchInput').value || '').trim().toLowerCase();
    let total = 0;

    for (const folder of (S.boot && S.boot.folders) || []) {
      const convs = (folder.conversations || []).filter(c => {
        if (!convMatchesFilter(c)) return false;
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
          const row = el('button', 'conv-row' + (c.running ? ' running' : ''));
          row.type = 'button';
          const badges = [];
          if (c.running) badges.push('<span class="badge run"><i class="run-dot"></i>运行中</span>');
          if (c.waiting) badges.push('<span class="badge wait">待确认</span>');
          const unread = S.unread.has(c.id) && !c.running && !c.waiting;
          row.innerHTML =
            `<div class="conv-top">` +
            `<span class="conv-title">${unread ? '<i class="unread-dot"></i>' : ''}${escapeHtml(c.title || '未命名对话')}</span>` +
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
      box.innerHTML = emptyState(q);
    }
    updateFilterChips();
  }

  function updateFilterChips() {
    const row = $('#filterRow');
    if (!row) return;
    row.querySelectorAll('[data-filter]').forEach(b => {
      const on = b.dataset.filter === S.filter;
      b.classList.toggle('on', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
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
    const quotes = S.pendingQuotes.slice();
    if (!text.trim() && !images.length && !refs.length && !files.length && !quotes.length) {
      S.drafts.delete(id);
      return;
    }
    S.drafts.set(id, { text, images, refs, files, quotes });
  }

  function restoreDraft(id) {
    const d = S.drafts.get(id);
    $('#input').value = d ? d.text : '';
    S.pendingImages = d ? d.images.slice() : [];
    S.pendingRefs = d ? d.refs.slice() : [];
    S.pendingFiles = d && d.files ? d.files.slice() : [];
    S.pendingQuotes = d && d.quotes ? d.quotes.slice() : [];
    S.refSel = new Map(S.pendingRefs.map(r => [r.id, r]));
    renderPendingImages();
    renderPendingRefs();
    renderPendingFiles();
    renderPendingQuotes();
    autoGrow();
    updateSendState();
  }

  async function openConv(id, push = true) {
    if (S.convId) saveDraft(S.convId);
    stopConvSync();
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
    S.pendingQuotes = [];
    S.pendingFiles = [];
    S.todos = null;
    S.todoOpen = false;
    S.unread.delete(id);
    S.refSel = new Map();
    S.sending = false;
    $('#input').value = '';
    closeStopConfirm();
    setRunProgress('', '');
    renderPendingImages();
    renderPendingRefs();
    renderPendingQuotes();
    renderPendingFiles();
    renderTodoBar();
    showChat(true);
    $('#chatTitle').textContent = '加载中…';
    $('#chatMeta').textContent = '';
    $('#messages').innerHTML = '';
    $('#panels').innerHTML = '';
    updateComposer();
    try {
      const data = await api('/api/conversation?id=' + encodeURIComponent(id) + '&limit=30&light=1&compact=1', { timeout: 30000 });
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
    stopConvSync();
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
    S.pendingQuotes = [];
    S.pendingFiles = [];
    S.todos = null;
    S.todoOpen = false;
    S.refSel = new Map();
    S.sending = false;
    $('#input').value = '';
    closeStopConfirm();
    clearAskTimer();
    setRunProgress('', '');
    renderPendingImages();
    renderPendingRefs();
    renderPendingQuotes();
    renderPendingFiles();
    renderTodoBar();
    showChat(false);
    loadBoot().catch(() => {});
  }

  function renderConversation(data, keepScroll = false) {
    S.conv = data;
    S.convId = data.id;
    S.live = data.live || null;
    S.running = !!data.running;
    S.unread.delete(data.id);
    S.todos = extractTodosFromMessages(data.messages || []);
    renderTodoBar();
    // 任务已经结束：历史里已经有这条用户消息，本地乐观回声不能再画一次
    if (!S.running) S.pendingEcho = null;
    if (S.running) notifyPageTask(true);
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
    if (!keepScroll) {
      readingHistory = false;
      lastScrollTop = 0;
    }
    renderMessages(!keepScroll);
    renderPanels();
    if (S.running) { syncProgressFromLive(); startLivePolling(); }
    else { setRunProgress('', ''); stopLivePolling(); }
    startConvSync();
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
      parts.push(`<span class="${cls}" title="上下文 ${fmtTok(ctx)} tokens">上下文 ${Math.round(pct * 100)}%</span>`);
    }
    if (S.running) parts.push('<span class="run">● 运行中</span>');
    if (S.pendingAsk) parts.push('<span class="wait">等待回答</span>');
    else if (S.pendingPerm) parts.push('<span class="wait">等待确认</span>');
    $('#chatMeta').innerHTML = parts.join(' · ');
    updateQuickEffort();
  }

  function updateQuickEffort() {
    const sel = $('#quickEffort');
    const btn = $('#quickEffortBtn');
    if (!sel || !btn) return;
    const levels = effortLevels();
    if (!sel.dataset.filled) {
      fillSelect(sel, levels.map(e => ({ value: e, label: EFFORT_LABELS[e] || e })));
      sel.dataset.filled = '1';
    }
    const cur = currentEffortValue();
    sel.value = cur;
    btn.textContent = EFFORT_LABELS[cur] || cur;
    btn.title = '推理档位：' + (EFFORT_LABELS[cur] || cur);
  }

  async function changeQuickEffort(effort) {
    if (!S.convId || !effort) return;
    try {
      const data = await api('/api/conversation?id=' + encodeURIComponent(S.convId), {
        method: 'PATCH',
        body: JSON.stringify({ effort })
      });
      const conv = (data && data.conversation) || {};
      if (S.conv) S.conv.effort = conv.effort != null ? conv.effort : effort;
      updateHeader();
      toast('推理档位：' + (EFFORT_LABELS[effort] || effort));
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
      S.pendingRefs.length > 0 || S.pendingFiles.length > 0 || S.pendingQuotes.length > 0;
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

  /** 一轮任务里连续的 assistant 消息合并成一张卡片，过程只留一行；
      最后一次工具调用之后的最终结论全部保留，避免被最后一条覆盖。 */
  function groupedHistory() {
    const out = [];
    let cur = null;
    for (const m of historyForRender()) {
      if (m.role === 'assistant') {
        if (!cur) {
          cur = { id: m.id, role: 'assistant', blocks: [], conclusions: [], segments: [],
            ts: m.ts, meta: m.meta || null };
          out.push(cur);
        }
        const blocks = Array.isArray(m.blocks) ? m.blocks.filter(Boolean) : [];
        const texts = blocks.filter(b => b.type === 'text');
        const hasProc = blocks.some(b => b.type === 'thinking' || b.type === 'tool_use');
        const hasTool = blocks.some(b => b.type === 'tool_use');
        cur.blocks.push(...blocks);
        cur.segments.push({ texts, hasProc, hasTool, meta: m.meta || null });
        if (m.ts) cur.ts = m.ts;
        if (m.meta) cur.meta = mergeMeta(cur.meta, m.meta);
      } else {
        cur = null;
        out.push(m);
      }
    }

    // 最后一次工具调用之后的文字是这一轮的最终结论；更早的过渡文字仍收进过程。
    for (const item of out) {
      if (item.role !== 'assistant' || !item.segments) continue;
      let lastTool = -1;
      let hasProc = false;
      for (let i = 0; i < item.segments.length; i++) {
        if (item.segments[i].hasTool) lastTool = i;
        if (item.segments[i].hasProc) hasProc = true;
      }
      const tail = [];
      const pushSeg = (seg) => {
        seg.texts.forEach((b, i) => {
          // 同一段消息里只有最后一块带统计，避免一条统计重复贴在多段文字后。
          tail.push({ block: b, meta: i === seg.texts.length - 1 ? seg.meta : null });
        });
      };
      if (lastTool >= 0) {
        for (let i = lastTool + 1; i < item.segments.length; i++) {
          pushSeg(item.segments[i]);
        }
      } else if (!hasProc) {
        for (const seg of item.segments) pushSeg(seg);
      }
      // 没有工具后的文字时退回原来的规则：只保留最后一段结论。
      if (!tail.length) {
        for (let i = item.segments.length - 1; i >= 0; i--) {
          const seg = item.segments[i];
          if (seg.texts.length) {
            tail.push({ block: seg.texts[seg.texts.length - 1], meta: seg.meta });
            break;
          }
        }
      }
      item.conclusions = tail;
      delete item.segments;
    }
    return out;
  }

  let olderObserver = null;

  /** 顶部的「加载更早消息」进入视口就自动补一页；保留按钮给不支持观察器的环境。 */
  function watchOlderButton() {
    const box = $('#messages');
    const btn = box && box.querySelector('.load-older');
    if (olderObserver) {
      olderObserver.disconnect();
      olderObserver = null;
    }
    if (!btn || !('IntersectionObserver' in window)) return;
    olderObserver = new IntersectionObserver(entries => {
      if (document.hidden) return;
      if (entries.some(e => e.isIntersecting)) loadOlderMessages();
    }, { root: box, rootMargin: '40px 0px 0px 0px', threshold: 0 });
    olderObserver.observe(btn);
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
    else if (S.pendingEcho) {
      // 服务器历史里已经有这条用户消息时不再重复画（后台回来/任务刚结束的常见情况）
      const echo = S.pendingEcho;
      const msgs = (S.conv && S.conv.messages) || [];
      let lastUser = null;
      for (let i = msgs.length - 1; i >= 0; i--) {
        if (msgs[i] && msgs[i].role === 'user') { lastUser = msgs[i]; break; }
      }
      let dup = false;
      if (lastUser && String(lastUser.text || '') === String(echo.text || '') &&
          !!lastUser.steer === !!echo.steer) {
        const lt = lastUser.ts ? Date.parse(lastUser.ts) : 0;
        const et = echo.ts ? Date.parse(echo.ts) : 0;
        dup = !lt || !et || Math.abs(lt - et) < 120000;
      }
      if (!dup) live.appendChild(userNode(echo));
    }

    if (anchor) {
      // 往上补了内容：保持当前正在看的那一段不动
      box.scrollTop = anchor.top + (box.scrollHeight - anchor.h);
    } else if (forceBottom || wasNear) {
      maybeScroll(true);
    } else {
      box.scrollTop = oldTop;
      // 整段历史重建后继续保持“用户在看历史”的状态，后续状态更新不再回底
      readingHistory = true;
      lastScrollTop = oldTop;
    }
    watchOlderButton();
    scheduleProcessCollapseUpdate();
  }

  function quotePreview(text) {
    const s = String(text || '').replace(/\s+/g, ' ').trim();
    const chars = Array.from(s);
    return chars.slice(0, 5).join('') + (chars.length > 5 ? '…' : '');
  }

  function quoteSourceText(q) {
    const src = String((q && q.source) || '').trim();
    const role = q && q.role === 'user' ? '我的消息' : q && q.role === 'assistant' ? 'Claude 回复' : '';
    return '引自「' + (src || '未知对话') + '」' + (role ? ' · ' + role : '');
  }

  function parseQuoteBlocks(text) {
    const src = String(text || '');
    const re = /<gui-quote>\s*\r?\n([^\n]*)\r?\n([\s\S]*?)<\/gui-quote>/g;
    const quotes = [];
    let out = '';
    let last = 0;
    let m;
    while ((m = re.exec(src))) {
      out += src.slice(last, m.index);
      let meta = {};
      try { meta = JSON.parse(m[1]) || {}; } catch { meta = {}; }
      const t = m[2].replace(/\r\n?/g, '\n').trim();
      if (t) quotes.push({ text: t, source: String(meta.source || ''), role: String(meta.role || '') });
      last = re.lastIndex;
    }
    out += src.slice(last);
    return { text: out.trim(), quotes };
  }

  function quoteNode(q) {
    const box = document.createElement('details');
    box.style.cssText = 'margin:6px 0;padding:6px 8px;border-left:2px solid rgba(229,145,99,.6);border-radius:6px;background:rgba(255,255,255,.06);';
    const sum = document.createElement('summary');
    sum.style.cssText = 'cursor:pointer;list-style:none;';
    const pre = el('div', '', '“' + quotePreview(q.text) + '”');
    pre.style.cssText = 'font-size:12px;line-height:1.45;color:#e59563;';
    const src = el('div', '', quoteSourceText(q));
    src.style.cssText = 'margin-top:2px;font-size:10.5px;color:rgba(255,255,255,.4);';
    sum.append(pre, src);
    const full = el('div', '', q.text);
    full.style.cssText = 'margin-top:6px;white-space:pre-wrap;font-size:12px;line-height:1.5;color:rgba(255,255,255,.75);';
    box.append(sum, full);
    return box;
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
    let ref = false;
    const digest = window.ClaudeRefDigest;
    if (digest && typeof digest.hasBlock === 'function' && digest.hasBlock(t)) {
      t = digest.stripBlock(t);
      ref = true;
    } else {
      const re = /<gui-reference-context>[\s\S]*?<\/gui-reference-context>/;
      if (re.test(t)) {
        t = t.replace(re, '').trim();
        ref = true;
      }
    }
    const parsed = parseQuoteBlocks(t);
    return { text: parsed.text, quotes: parsed.quotes, ref, fileRef };
  }

  function compactDividerNode() {
    return el('div', 'compact-divider', '上下文对话已压缩');
  }

  // 长消息（例如一整段 App 日志）默认收起，和电脑端保持一致。
  const LONG_USER_TEXT_CHARS = 1200;
  const LONG_USER_TEXT_LINES = 16;

  function isLongUserText(text) {
    const s = String(text || '');
    return s.length > LONG_USER_TEXT_CHARS || s.split('\n').length > LONG_USER_TEXT_LINES;
  }

  function isCompactSummaryMessage(m) {
    if (!m) return false;
    const text = String(m.text || '');
    // 只有完整、独立成行的引用块才算引用；摘要正文里可能只是提到这个标签。
    const refBlock = /(?:^|\n)<gui-reference-context>[ \t]*\r?\n[\s\S]*?\r?\n<\/gui-reference-context>[ \t]*(?=\r?\n|$)/i;
    if (refBlock.test(text)) return false;
    if (m.compactSummary || m.isCompactSummary) return true;
    return /^\s*(?:This session is being continued|The summary below covers)/i.test(text);
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
      const sources = imgs.map(img => `data:${img.media_type || 'image/png'};base64,${img.data}`);
      sources.forEach((src, idx) => {
        const i = el('img');
        i.src = src;
        i.onclick = () => openImageViewer(src, sources, idx);
        grid.appendChild(i);
      });
      d.appendChild(grid);
    }
    for (const q of info.quotes || []) d.appendChild(quoteNode(q));
    if (info.text) {
      const textEl = el('div', 'utext');
      // 用户自己写的 Markdown 图片语法只当文字，不加载本地/远程图片
      textEl.innerHTML = formatText(info.text, { images: false });
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
    attachMsgActions(d, m);
    return d;
  }

  function messageNode(m) {
    if (m.role === 'user') return userNode(m);
    const wrap = el('div', 'msg assistant');
    const blocks = (m.blocks || []).filter(Boolean);
    const proc = blocks.filter(b => b.type === 'thinking' || b.type === 'tool_use');
    const texts = blocks.filter(b => b.type === 'text');
    const conclusions = Array.isArray(m.conclusions)
      ? m.conclusions.filter(c => c && c.block)
      : (proc.length ? texts.slice(-1) : texts).map(block => ({ block, meta: null }));
    const conclusionBlocks = conclusions.map(c => c.block);
    const processTexts = texts.filter(b => !conclusionBlocks.includes(b));
    const finalText = conclusions.length
      ? conclusions[conclusions.length - 1].block.text : '';

    // 思考、工具和中间过程文本收进「过程」；最后一次工具后的最终结论全部保留
    if (proc.length || processTexts.length) {
      wrap.appendChild(processNode(proc, processTexts));
    }
    for (const c of conclusions) {
      const node = blockNode(c.block);
      if (!node) continue;
      wrap.appendChild(node);
      if (c.meta) wrap.appendChild(turnMetaNode(c.meta));
    }
    const artifacts = extractArtifactPaths(finalText);
    if (artifacts.length) wrap.appendChild(artifactNode(artifacts));
    const edits = aggregateEdits(blocks);
    if (edits.length) wrap.appendChild(editCardNode(edits));
    // 没有分条统计时（例如旧消息没有 meta）才显示整轮合并统计。
    if (!conclusions.some(c => c.meta) && m.meta) wrap.appendChild(turnMetaNode(m.meta));
    attachMsgActions(wrap, m);
    return wrap;
  }

  function blockNode(b) {
    if (!b || b.type !== 'text') return null;
    const d = el('div', 'txt');
    d.innerHTML = formatText(b.text);
    layoutMarkdownImages(d);
    enhanceCodeBlocks(d);
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

  function editChanges(name, input) {
    const out = [];
    if (!input || typeof input !== 'object') return out;
    if (name === 'Edit' && typeof input.old_string === 'string' && typeof input.new_string === 'string') {
      out.push({ oldText: input.old_string, newText: input.new_string });
    } else if (name === 'MultiEdit' && Array.isArray(input.edits)) {
      for (const e of input.edits) {
        if (e && typeof e.old_string === 'string' && typeof e.new_string === 'string') {
          out.push({ oldText: e.old_string, newText: e.new_string });
        }
      }
    } else if (name === 'Write' && typeof input.content === 'string') {
      out.push({ oldText: '', newText: input.content });
    } else if (name === 'NotebookEdit' && typeof input.new_source === 'string' && input.edit_mode !== 'delete') {
      out.push({ oldText: '', newText: input.new_source });
    }
    return out;
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
        cur = { path: info.path, added: 0, removed: 0, edits: 0, changes: [] };
        map.set(key, cur);
      }
      cur.added += info.added;
      cur.removed += info.removed;
      cur.edits += 1;
      cur.changes.push(...editChanges(b.name, b.input));
    }
    return [...map.values()];
  }

  function diffRows(oldText, newText) {
    const a = splitLines(oldText);
    const b = splitLines(newText);
    let pre = 0;
    while (pre < a.length && pre < b.length && a[pre] === b[pre]) pre++;
    let suf = 0;
    while (suf < a.length - pre && suf < b.length - pre &&
      a[a.length - 1 - suf] === b[b.length - 1 - suf]) suf++;
    const aa = a.slice(pre, a.length - suf);
    const bb = b.slice(pre, b.length - suf);
    const rows = [];
    for (let i = 0; i < pre; i++) rows.push({ type: 'same', text: a[i], oldNo: i + 1, newNo: i + 1 });
    const mid = [];
    if (aa.length * bb.length > 160000 || aa.length + bb.length > 4000) {
      for (let i = 0; i < aa.length; i++) mid.push({ type: 'del', text: aa[i], oldNo: pre + i + 1, newNo: null });
      for (let j = 0; j < bb.length; j++) mid.push({ type: 'add', text: bb[j], oldNo: null, newNo: pre + j + 1 });
    } else {
      const n = aa.length;
      const m = bb.length;
      const dp = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
      for (let i = n - 1; i >= 0; i--) {
        for (let j = m - 1; j >= 0; j--) {
          dp[i][j] = aa[i] === bb[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
        }
      }
      let i = 0;
      let j = 0;
      while (i < n && j < m) {
        if (aa[i] === bb[j]) {
          mid.push({ type: 'same', text: aa[i], oldNo: pre + i + 1, newNo: pre + j + 1 });
          i++;
          j++;
        } else if (dp[i + 1][j] >= dp[i][j + 1]) {
          mid.push({ type: 'del', text: aa[i], oldNo: pre + i + 1, newNo: null });
          i++;
        } else {
          mid.push({ type: 'add', text: bb[j], oldNo: null, newNo: pre + j + 1 });
          j++;
        }
      }
      while (i < n) mid.push({ type: 'del', text: aa[i], oldNo: pre + i + 1, newNo: null }), i++;
      while (j < m) mid.push({ type: 'add', text: bb[j], oldNo: null, newNo: pre + j + 1 }), j++;
    }
    rows.push(...mid);
    for (let k = 0; k < suf; k++) {
      rows.push({
        type: 'same', text: a[a.length - suf + k],
        oldNo: a.length - suf + k + 1, newNo: b.length - suf + k + 1
      });
    }
    return rows;
  }

  function openDiffSheet(f) {
    const parts = artifactSplit(f.path);
    $('#diffTitle').textContent = parts.name || '改动';
    $('#diffMeta').textContent = `${f.path} · ${f.edits} 次修改 · +${f.added} -${f.removed}`;
    const body = $('#diffBody');
    body.innerHTML = '';
    const changes = f.changes || [];
    if (!changes.length) {
      body.innerHTML = '<div class="diff-empty">没有可展示的行级改动</div>';
    } else {
      let left = 1500;
      changes.forEach((c, idx) => {
        const head = el('div', 'diff-hunk', changes.length > 1 ? `第 ${idx + 1} 次修改` : '改动');
        body.appendChild(head);
        if (left <= 0) return;
        const rows = diffRows(c.oldText, c.newText);
        const shown = rows.slice(0, left);
        left -= shown.length;
        const html = shown.map(r => {
          const sign = r.type === 'add' ? '+' : r.type === 'del' ? '-' : ' ';
          return `<div class="diff-row ${r.type}">` +
            `<span class="diff-ln">${r.oldNo == null ? '' : r.oldNo}</span>` +
            `<span class="diff-ln">${r.newNo == null ? '' : r.newNo}</span>` +
            `<span class="diff-sign">${sign}</span>` +
            `<span class="diff-text">${escapeHtml(r.text)}</span></div>`;
        }).join('');
        body.insertAdjacentHTML('beforeend', html);
        if (rows.length > shown.length) {
          body.insertAdjacentHTML('beforeend', '<div class="diff-more">改动太长，只显示前 1500 行</div>');
          left = 0;
        }
      });
    }
    $('#diffSheet').hidden = false;
  }

  function closeDiffSheet() {
    $('#diffSheet').hidden = true;
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

  // ---------------------------------------------------------------- 工作台状态

  function fmtStatusTime(ts) {
    if (!ts) return '';
    const d = new Date(ts);
    if (isNaN(d.getTime())) return '';
    return d.toLocaleString('zh-CN', { hour12: false });
  }

  function statusConvId() {
    return S.convId || (S.boot && S.boot.lastConversation) || '';
  }

  function statusRowNode(title, opts = {}) {
    const row = el('div', 'status-row');
    const main = el('div', 'status-main');
    const titleEl = el('div', 'status-title');
    titleEl.appendChild(el('b', null, title || '（未命名）'));
    if (opts.badge) {
      titleEl.appendChild(el('span', 'status-badge' + (opts.bad ? ' bad' : ''), opts.badge));
    }
    main.appendChild(titleEl);
    if (opts.desc) main.appendChild(el('div', 'status-desc', opts.desc));
    if (opts.meta) main.appendChild(el('div', 'status-meta', opts.meta));
    row.appendChild(main);
    return row;
  }

  function renderStatus(data) {
    const body = $('#statusBody');
    const note = $('#statusNote');
    if (!body) return;
    body.innerHTML = '';
    if (!data) {
      body.appendChild(el('div', 'status-empty', '读取中…'));
      return;
    }
    const group = title => body.appendChild(el('div', 'status-group', title));
    const empty = text => body.appendChild(el('div', 'status-empty', text));

    const sched = data.scheduled || {};
    const tasks = Array.isArray(sched.tasks) ? sched.tasks : [];
    const wakeups = Array.isArray(sched.wakeups) ? sched.wakeups : [];
    group('计划任务');
    if (!tasks.length && !wakeups.length) empty('暂无计划任务');
    for (const t of tasks) {
      const bits = ['cron ' + (t.cron || '?')];
      if (t.permanent) bits.push('持久');
      if (t.source === 'session') bits.push('本会话');
      if (t.lastFiredAt) bits.push('上次 ' + fmtStatusTime(t.lastFiredAt));
      body.appendChild(statusRowNode(t.prompt || ('任务 ' + (t.id || '')), {
        desc: bits.join(' · '),
        meta: [t.createdInProject, t.id].filter(Boolean).join(' · '),
        badge: t.recurring === false ? '一次' : '重复'
      }));
    }
    for (const w of wakeups) {
      const bits = [];
      if (w.delaySeconds) bits.push(w.delaySeconds + ' 秒后');
      if (w.ts) bits.push(fmtStatusTime(w.ts));
      body.appendChild(statusRowNode(w.reason || w.prompt || '稍后继续', {
        desc: w.prompt || '',
        meta: bits.join(' · '),
        badge: w.stop ? '已停止' : '唤醒',
        bad: !!w.stop
      }));
    }

    const agents = data.agents || {};
    const sessions = Array.isArray(agents.sessions) ? agents.sessions : [];
    const subagents = Array.isArray(agents.subagents) ? agents.subagents : [];
    const teams = Array.isArray(agents.teams) ? agents.teams : [];
    group('代理 / 子任务');
    if (!sessions.length && !subagents.length && !teams.length) empty('暂无代理或子任务');
    for (const s of sessions.slice(0, 8)) {
      body.appendChild(statusRowNode(s.name || s.sessionId || ('进程 ' + s.pid), {
        desc: s.cwd || '',
        meta: [s.kind, s.pid ? 'PID ' + s.pid : ''].filter(Boolean).join(' · '),
        badge: s.isCurrent ? '当前' : (s.status || '会话')
      }));
    }
    for (const a of subagents.slice(0, 8)) {
      body.appendChild(statusRowNode(a.description || a.agentType || '子代理', {
        desc: [a.agentType, a.agentId].filter(Boolean).join(' · '),
        badge: a.finished ? '完成' : '运行中'
      }));
    }
    for (const t of teams.slice(0, 5)) {
      body.appendChild(statusRowNode(t.teamName || '代理团队', {
        desc: (t.members || []).map(m => m.name || m.agentType).filter(Boolean).join('、'),
        badge: `${(t.members || []).length} 人`
      }));
    }

    const wt = data.worktrees || {};
    group('工作区');
    if (!wt.isGitRepo) {
      empty(wt.error ? ('Git 不可用：' + wt.error) : '当前目录不是 Git 仓库');
    } else {
      body.appendChild(statusRowNode(wt.branch || 'HEAD', {
        desc: wt.repoRoot || wt.cwd || '',
        meta: [wt.head ? 'HEAD ' + String(wt.head).slice(0, 8) : '',
          wt.ahead ? '领先 ' + wt.ahead : '',
          wt.behind ? '落后 ' + wt.behind : ''].filter(Boolean).join(' · '),
        badge: wt.dirty ? `${wt.dirty} 处改动` : '干净'
      }));
      for (const e of (wt.entries || []).filter(x => !x.isCurrent)) {
        body.appendChild(statusRowNode(e.branch || e.path || 'worktree', {
          desc: e.path || '',
          meta: e.isClaudeManaged ? 'Claude 管理' : '',
          badge: e.isClaudeManaged ? 'Claude' : ''
        }));
      }
    }

    const schedCount = tasks.length + wakeups.length;
    const agentCount = sessions.length + subagents.length + teams.length;
    const gitLabel = wt.isGitRepo ? (wt.branch || 'Git') : '非 Git';
    if (note) note.textContent = `${schedCount} 个计划任务 · ${agentCount} 个代理/子任务 · ${gitLabel}`;
  }

  async function refreshStatus(force = false) {
    if (S.status.loading) return;
    if (!force && !S.status.open) return;
    const convId = statusConvId();
    if (!convId) {
      renderStatus(null);
      const body = $('#statusBody');
      if (body) {
        body.innerHTML = '';
        body.appendChild(el('div', 'status-empty', '先打开一个对话再查看工作台状态'));
      }
      return;
    }
    S.status.loading = true;
    try {
      const data = await api('/api/workbench-status?conv=' + encodeURIComponent(convId));
      if (!S.status.open) return;
      S.status.data = data;
      renderStatus(data);
    } catch (e) {
      if (!S.status.open) return;
      const body = $('#statusBody');
      if (body) {
        body.innerHTML = '';
        body.appendChild(el('div', 'status-empty', /会话不存在/.test(e.message)
          ? '先打开一个对话再查看工作台状态'
          : /404/.test(e.message)
            ? '状态接口还没生效，请重启一次工作台'
            : '读取状态失败：' + e.message));
      }
    } finally {
      S.status.loading = false;
    }
  }

  function openStatusSheet() {
    const sheet = $('#statusSheet');
    if (!sheet) return;
    S.status.open = true;
    sheet.hidden = false;
    if (S.status.data) renderStatus(S.status.data);
    else {
      const body = $('#statusBody');
      if (body) {
        body.innerHTML = '';
        body.appendChild(el('div', 'status-empty', '读取中…'));
      }
    }
    refreshStatus(true);
    clearInterval(S.status.timer);
    S.status.timer = setInterval(() => refreshStatus(false), 5000);
  }

  function closeStatusSheet() {
    const sheet = $('#statusSheet');
    if (!sheet || sheet.hidden) return;
    sheet.hidden = true;
    S.status.open = false;
    clearInterval(S.status.timer);
    S.status.timer = 0;
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
      if (parts.dir) {
        const sep = /[\\/]$/.test(parts.dir) ? '' : '/';
        p.appendChild(el('span', 'ep-dir', parts.dir + sep));
      }
      p.appendChild(el('span', 'ep-name', parts.name));
      row.appendChild(p);
      const stat = el('span', 'edit-stat');
      stat.innerHTML = `<span class="edit-add">+${f.added}</span> <span class="edit-del">-${f.removed}</span>`;
      row.appendChild(stat);
      const hasDiff = Array.isArray(f.changes) && f.changes.length > 0;
      row.title = hasDiff ? '查看改动' : '打开 ' + f.path;
      row.style.cursor = 'pointer';
      if (hasDiff) row.classList.add('has-diff');
      row.onclick = () => {
        if (hasDiff) {
          openDiffSheet(f);
          return;
        }
        if (!openLocalFile({ path: f.path, name: parts.name, dir: parts.dir })) {
          startDownload({ path: f.path, name: parts.name, dir: parts.dir }, null);
        }
      };
      const open = el('button', 'edit-open', '打开');
      open.type = 'button';
      open.title = '打开 ' + f.path;
      open.onclick = e => {
        e.preventDefault();
        e.stopPropagation();
        if (!openLocalFile({ path: f.path, name: parts.name, dir: parts.dir })) {
          startDownload({ path: f.path, name: parts.name, dir: parts.dir }, null);
        }
      };
      row.appendChild(open);
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

  function summarizeBlocks(blocks) {
    const out = {
      command: 0, search: 0, web: 0, agent: 0, todo: 0, other: 0, failed: 0, total: 0,
      edit: new Set(), read: new Set()
    };
    for (const b of blocks || []) {
      if (!b) continue;
      if (b.type === 'thinking') { out.total++; continue; }
      if (b.type !== 'tool_use') continue;
      out.total++;
      const kind = toolKindOf(b.name);
      const failed = !!(b.isError || b.denied || b.cancelled || b.status === 'failed');
      if (failed) out.failed++;
      if (kind === 'edit') {
        const info = editToolInfo(b.name, b.input);
        if (!failed && info && info.path) out.edit.add(info.path.replace(/\\/g, '/').toLowerCase());
        else if (!failed && !info) out.other++;
        continue;
      }
      if (kind === 'read') {
        if (!failed) {
          const p = b.input && (b.input.file_path || b.input.path);
          if (typeof p === 'string' && p) out.read.add(p.replace(/\\/g, '/').toLowerCase());
        }
        continue;
      }
      if (kind === 'command') out.command++;
      else if (kind === 'search') out.search++;
      else if (kind === 'web') out.web++;
      else if (kind === 'agent') out.agent++;
      else if (kind === 'todo') out.todo++;
      else out.other++;
    }
    return out;
  }

  function processMetaText(blocks) {
    if (!Array.isArray(blocks)) return `· ${blocks || 0} 步`;
    const s = summarizeBlocks(blocks);
    const parts = [];
    if (s.command) parts.push(`${s.command} 条命令`);
    if (s.edit.size) parts.push(`改了 ${s.edit.size} 个文件`);
    if (s.read.size) parts.push(`读了 ${s.read.size} 个文件`);
    if (s.search) parts.push(`${s.search} 次搜索`);
    if (s.web) parts.push(`${s.web} 次联网`);
    if (s.agent) parts.push(`${s.agent} 个子代理`);
    if (s.todo) parts.push('更新待办');
    if (s.other) parts.push(`${s.other} 个工具`);
    if (s.failed) parts.push(`${s.failed} 个失败`);
    return '· ' + (parts.length ? parts.join(' · ') : `${s.total} 步`);
  }

  // ---------------------------------------------------------------- 待办进度

  function normalizeTodos(input) {
    const list = Array.isArray(input && input.todos) ? input.todos : [];
    return list.map(t => ({
      content: String((t && (t.content || t.activeForm)) || '').trim(),
      status: String((t && t.status) || 'pending')
    })).filter(t => t.content);
  }

  function extractTodosFromBlocks(blocks) {
    for (let i = (blocks || []).length - 1; i >= 0; i--) {
      const b = blocks[i];
      if (b && b.type === 'tool_use' && b.name === 'TodoWrite') {
        const todos = normalizeTodos(b.input);
        if (todos.length) return todos;
      }
    }
    return null;
  }

  function extractTodosFromMessages(messages) {
    for (let i = (messages || []).length - 1; i >= 0; i--) {
      const todos = extractTodosFromBlocks(messages[i] && messages[i].blocks);
      if (todos) return todos;
    }
    return null;
  }

  function updateTodosFromTool(input) {
    if (!input || !Array.isArray(input.todos)) return;
    const todos = normalizeTodos(input);
    S.todos = todos.length ? todos : null;
    renderTodoBar();
  }

  function renderTodoBar() {
    const bar = $('#todoBar');
    if (!bar) return;
    const todos = S.todos || [];
    if (!todos.length) {
      bar.hidden = true;
      return;
    }
    bar.hidden = false;
    const done = todos.filter(t => t.status === 'completed').length;
    const cur = todos.find(t => t.status === 'in_progress') ||
      todos.find(t => t.status !== 'completed');
    $('#todoCount').textContent = `${done}/${todos.length}`;
    $('#todoNow').textContent = done === todos.length
      ? '全部完成'
      : cur ? (cur.status === 'in_progress' ? '正在：' : '下一步：') + cur.content : '';
    $('#todoFill').style.width = Math.round(done / todos.length * 100) + '%';
    $('#todoToggle').setAttribute('aria-expanded', S.todoOpen ? 'true' : 'false');
    $('#todoChev').textContent = S.todoOpen ? '▴' : '▾';
    const list = $('#todoList');
    list.hidden = !S.todoOpen;
    if (S.todoOpen) {
      list.innerHTML = todos.map(t => {
        const cls = t.status === 'completed' ? ' done' : t.status === 'in_progress' ? ' doing' : '';
        const mark = t.status === 'completed' ? '✓' : t.status === 'in_progress' ? '▸' : '○';
        return `<div class="todo-row${cls}"><span class="todo-mark">${mark}</span>` +
          `<span class="todo-text">${escapeHtml(t.content)}</span></div>`;
      }).join('');
    }
  }

  function toggleTodoBar() {
    S.todoOpen = !S.todoOpen;
    renderTodoBar();
  }

  function turnMetaNode(meta) {
    const u = meta.usage || {};
    const parts = [];
    if (meta.duration) {
      const sec = meta.duration / 1000;
      parts.push(`用时 ${sec < 10 ? sec.toFixed(1) : Math.round(sec)}s`);
    }
    const inTok = (u.input_tokens || 0) + (u.cache_creation_input_tokens || 0) + (u.cache_read_input_tokens || 0);
    const outTok = u.output_tokens || 0;
    if (inTok || outTok) parts.push(`输入 ${fmtTok(inTok)} / 输出 ${fmtTok(outTok)}`);
    const cny = (u.cache_read_input_tokens || 0) / 1e6 * 0.02 +
      ((u.input_tokens || 0) + (u.cache_creation_input_tokens || 0)) / 1e6 * 1 +
      outTok / 1e6 * 4;
    if (cny > 0.0005) parts.push(`≈¥${cny.toFixed(3)}`);
    return el('div', 'turn-meta', parts.join(' · '));
  }

  let imgView = { list: [], index: 0, scale: 1, tx: 0, ty: 0 };
  let imgGesture = null;
  let imgTapTimer = 0;
  let imgLastTapAt = 0;

  function applyImgTransform() {
    const img = $('#imgViewerImg');
    if (!img) return;
    img.style.transform = `translate3d(${imgView.tx}px, ${imgView.ty}px, 0) scale(${imgView.scale})`;
    $('#imgViewer').classList.toggle('zoomed', imgView.scale > 1.01);
  }

  function updateViewerChrome() {
    const n = imgView.list.length;
    const count = $('#imgViewerCount');
    count.hidden = n < 2;
    count.textContent = n > 1 ? `${imgView.index + 1} / ${n}` : '';
    $('#imgViewerPrev').hidden = n < 2 || imgView.index <= 0;
    $('#imgViewerNext').hidden = n < 2 || imgView.index >= n - 1;
  }

  function openImageViewer(src, list, index) {
    const arr = (Array.isArray(list) && list.length ? list : [src]).filter(Boolean);
    let idx = Number.isFinite(index) ? index : arr.indexOf(src);
    if (idx < 0 || idx >= arr.length) idx = 0;
    imgView = { list: arr, index: idx, scale: 1, tx: 0, ty: 0 };
    $('#imgViewerImg').src = arr[idx];
    $('#imgViewer').hidden = false;
    updateViewerChrome();
    applyImgTransform();
  }

  function closeImageViewer() {
    clearTimeout(imgTapTimer);
    imgTapTimer = 0;
    imgLastTapAt = 0;
    imgView = { list: [], index: 0, scale: 1, tx: 0, ty: 0 };
    $('#imgViewer').hidden = true;
    $('#imgViewerImg').src = '';
    $('#imgViewerImg').style.transform = '';
    $('#imgViewer').classList.remove('zoomed');
  }

  function stepImageViewer(delta) {
    if (imgView.list.length < 2) return;
    const next = imgView.index + delta;
    if (next < 0 || next >= imgView.list.length) return;
    imgView.index = next;
    imgView.scale = 1;
    imgView.tx = 0;
    imgView.ty = 0;
    $('#imgViewerImg').src = imgView.list[imgView.index];
    updateViewerChrome();
    applyImgTransform();
  }

  function viewTouchDistance(a, b) {
    const dx = a.clientX - b.clientX;
    const dy = a.clientY - b.clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }

  function setupImageViewerGestures() {
    const stage = $('#imgViewerStage');
    if (!stage || stage.dataset.gesture) return;
    stage.dataset.gesture = '1';

    stage.addEventListener('touchstart', e => {
      if (e.touches.length === 2) {
        clearTimeout(imgTapTimer);
        imgGesture = {
          mode: 'pinch',
          startDist: Math.max(1, viewTouchDistance(e.touches[0], e.touches[1])),
          startScale: imgView.scale,
          startTx: imgView.tx,
          startTy: imgView.ty
        };
        e.preventDefault();
      } else if (e.touches.length === 1) {
        imgGesture = {
          mode: 'pan',
          startX: e.touches[0].clientX,
          startY: e.touches[0].clientY,
          startTx: imgView.tx,
          startTy: imgView.ty,
          moved: false
        };
      }
    }, { passive: false });

    stage.addEventListener('touchmove', e => {
      if (!imgGesture) return;
      if (imgGesture.mode === 'pinch' && e.touches.length === 2) {
        const dist = Math.max(1, viewTouchDistance(e.touches[0], e.touches[1]));
        imgView.scale = Math.max(1, Math.min(8, imgGesture.startScale * dist / imgGesture.startDist));
        if (imgView.scale <= 1.01) { imgView.tx = 0; imgView.ty = 0; }
        applyImgTransform();
        e.preventDefault();
        return;
      }
      if (imgGesture.mode === 'pan' && e.touches.length === 1) {
        const dx = e.touches[0].clientX - imgGesture.startX;
        const dy = e.touches[0].clientY - imgGesture.startY;
        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) imgGesture.moved = true;
        if (imgView.scale > 1.01) {
          imgView.tx = imgGesture.startTx + dx;
          imgView.ty = imgGesture.startTy + dy;
          applyImgTransform();
          e.preventDefault();
        }
      }
    }, { passive: false });

    stage.addEventListener('touchend', e => {
      const g = imgGesture;
      imgGesture = null;
      if (!g) return;
      if (g.mode === 'pinch') {
        if (imgView.scale <= 1.01) { imgView.scale = 1; imgView.tx = 0; imgView.ty = 0; }
        applyImgTransform();
        return;
      }
      if (g.mode !== 'pan') return;
      const dx = (e.changedTouches[0] ? e.changedTouches[0].clientX : g.startX) - g.startX;
      const dy = (e.changedTouches[0] ? e.changedTouches[0].clientY : g.startY) - g.startY;
      if (imgView.scale > 1.01 || g.moved) {
        applyImgTransform();
        return;
      }
      if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) && imgView.list.length > 1) {
        stepImageViewer(dx < 0 ? 1 : -1);
        return;
      }
      if (Math.abs(dx) > 10 || Math.abs(dy) > 10) return;
      // 单击关闭，双击放大；第二次点按会取消关闭定时器
      const now = Date.now();
      if (now - imgLastTapAt < 300) {
        clearTimeout(imgTapTimer);
        imgTapTimer = 0;
        imgLastTapAt = 0;
        imgView.scale = imgView.scale > 1.01 ? 1 : 2.5;
        imgView.tx = 0;
        imgView.ty = 0;
        applyImgTransform();
      } else {
        imgLastTapAt = now;
        imgTapTimer = setTimeout(() => {
          if (Date.now() - imgLastTapAt >= 280) closeImageViewer();
        }, 320);
      }
    });

    stage.addEventListener('wheel', e => {
      e.preventDefault();
      imgView.scale = Math.max(1, Math.min(8, imgView.scale * (e.deltaY < 0 ? 1.18 : .85)));
      if (imgView.scale <= 1.01) { imgView.scale = 1; imgView.tx = 0; imgView.ty = 0; }
      applyImgTransform();
    }, { passive: false });
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
    for (const b of blocks) {
      const node = b.type === 'thinking'
        ? thinkingRow(b)
        : (b.type === 'tool_use' ? toolNode(b) : null);
      if (node) body.appendChild(node);
      if (b.type === 'tool_use' && Array.isArray(b.images) && b.images.length) {
        body.appendChild(imageCardNode(b.images));
      }
    }

    d.append(sum, body);
    meta.textContent = processMetaText(blocks);
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

  // ---------------------------------------------------------------- Markdown

  /** 结论里的本地图片：走本地接口，<img> 需要自己带令牌 */
  function localImageUrl(p) {
    const params = new URLSearchParams();
    if (S.convId) params.set('conv', S.convId);
    params.set('path', p);
    params.set('token', TOKEN);
    return '/api/local-image?' + params.toString();
  }

  function localImageSrc(p) {
    return localImageUrl(p).replace(/&/g, '&amp;');
  }

  function markdownImageHtml(alt, rawPath) {
    let p = String(rawPath || '').trim();
    if (!p) return null;
    p = p.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&#39;/g, "'");
    if (p.startsWith('<') && p.endsWith('>')) p = p.slice(1, -1).trim();
    p = p.replace(/\s+(?:"[^"]*"|'[^']*')$/, '').trim();
    const safeAlt = String(alt || '').replace(/"/g, '&quot;');
    if (/^https?:/i.test(p)) {
      return `<img class="md-image md-image-remote" src="${p.replace(/"/g, '&quot;')}" alt="${safeAlt}" loading="lazy">`;
    }
    if (/^(data:|blob:)/i.test(p)) return null;
    const clean = p.replace(/^file:\/\//i, '').split(/[?#]/)[0];
    if (!/\.(png|jpe?g|webp|gif)$/i.test(clean)) return null;
    return `<img class="md-image" src="${localImageSrc(p)}" alt="${safeAlt}" loading="lazy" decoding="async">`;
  }

  let mdAllowImages = true;

  /** 扫描 Markdown 图片，支持路径里的括号 */
  function replaceMarkdownImages(s, cb) {
    let out = '';
    let i = 0;
    while (i < s.length) {
      const start = s.indexOf('![', i);
      if (start < 0) { out += s.slice(i); break; }
      if (start > 0 && s[start - 1] === '\\') {
        out += s.slice(i, start + 2);
        i = start + 2;
        continue;
      }
      const mid = s.indexOf('](', start + 2);
      if (mid < 0) { out += s.slice(i); break; }
      const alt = s.slice(start + 2, mid);
      if (alt.includes('\n')) {
        out += s.slice(i, start + 2);
        i = start + 2;
        continue;
      }
      let depth = 1;
      let j = mid + 2;
      for (; j < s.length; j++) {
        const ch = s[j];
        if (ch === '\\') { j++; continue; }
        if (ch === '\n') break;
        if (ch === '(') depth++;
        else if (ch === ')') { depth--; if (depth === 0) break; }
      }
      if (depth !== 0 || j >= s.length) {
        out += s.slice(i, mid + 2);
        i = mid + 2;
        continue;
      }
      out += s.slice(i, start);
      out += cb(alt, s.slice(mid + 2, j), s.slice(start, j + 1));
      i = j + 1;
    }
    return out;
  }

  /** 行内格式。输入已经 HTML 转义；行内 code 先占位，避免里面的 ** / _ 被误处理。 */
  function formatInline(escaped) {
    const codes = [];
    let out = String(escaped || '').replace(/`([^`\n]+)`/g, (m, code) => {
      codes.push(`<code>${code}</code>`);
      return `\u0001${codes.length - 1}\u0001`;
    });
    out = replaceMarkdownImages(out, (alt, p) => {
      if (!mdAllowImages) return alt ? `[图片：${alt}]` : '[图片]';
      return markdownImageHtml(alt, p) || (alt ? `[图片：${alt}]` : '[图片]');
    });
    out = out.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g,
      '<a href="$2" target="_blank" rel="noopener">$1</a>');
    out = out.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    out = out.replace(/__([^_\n]+)__/g, '<strong>$1</strong>');
    out = out.replace(/~~([^~\n]+)~~/g, '<s>$1</s>');
    out = out.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>');
    out = out.replace(/(^|[^_])_([^_\n]+)_(?!_)/g, '$1<em>$2</em>');
    return out.replace(/\u0001(\d+)\u0001/g, (m, i) => codes[Number(i)] || '');
  }

  function splitTableRow(line) {
    let s = String(line || '').trim();
    if (!s.includes('|')) return null;
    if (s.startsWith('|')) s = s.slice(1);
    if (s.endsWith('|')) s = s.slice(0, -1);
    return s.split('|').map(x => x.trim());
  }

  function isTableSep(line) {
    const cells = splitTableRow(line);
    return !!cells && cells.length > 0 && cells.every(c => /^:?-{3,}:?$/.test(c));
  }

  function tableHtml(head, rows) {
    const th = head.map(c => `<th>${formatInline(escapeHtml(c))}</th>`).join('');
    const tb = rows.map(r => '<tr>' + head.map((_, i) =>
      `<td>${formatInline(escapeHtml(r[i] || ''))}</td>`).join('') + '</tr>').join('');
    return `<div class="table-wrap"><table><thead><tr>${th}</tr></thead><tbody>${tb}</tbody></table></div>`;
  }

  function codeBlockHtml(lang, code) {
    const safeLang = String(lang || '').toLowerCase();
    let body;
    if (safeLang === 'diff') {
      body = String(code || '').split('\n').map(line => {
        const cls = line.startsWith('+') ? 'add' : line.startsWith('-') ? 'del'
          : line.startsWith('@@') ? 'hunk' : '';
        return cls ? `<span class="diff-${cls}">${escapeHtml(line)}</span>` : escapeHtml(line);
      }).join('');
    } else {
      body = escapeHtml(code);
    }
    const label = safeLang || '代码';
    return `<div class="code-wrap"><div class="code-head">` +
      `<span class="code-lang">${escapeHtml(label)}</span>` +
      `<span class="code-tools">` +
      `<button type="button" class="code-wrap-btn" title="自动换行">换行</button>` +
      `<button type="button" class="code-copy">复制</button>` +
      `</span></div>` +
      `<pre class="code${safeLang === 'diff' ? ' diff' : ''}" data-lang="${escapeHtml(safeLang)}"><code>${body}</code></pre></div>`;
  }

  /** 流式时把最后一段未闭合的标记补上，让字从出现起就是最终样式。 */
  function closeTrailingMarkers(segment) {
    const src = String(segment || '');
    // 只修最后一段普通文本：代码块和行内 code 里的标记不动
    const parts = src.split(/(```[\s\S]*?```)/);
    const tail = parts.length ? parts.pop() : '';
    const inline = tail.split(/(`[^`\n]*`)/);
    let last = inline.length ? inline.pop() : '';
    for (const marker of ['**', '~~', '`']) {
      const count = last.split(marker).length - 1;
      if (count % 2) last += marker;
    }
    inline.push(last);
    parts.push(inline.join(''));
    return parts.join('');
  }

  function markdownBlocks(text, streaming, opts = {}) {
    mdAllowImages = opts.images !== false && !streaming;
    let src = String(text || '').replace(/\r\n?/g, '\n');
    if (streaming) {
      // 只有标记、还没有内容的末行先不显示，避免先闪一下符号
      src = src.replace(/\n(?:#{1,6}|[-*+]|>|\d+\.)\s*$/, '\n');
      // 半截图片/链接先藏住
      src = src.replace(/!\[[^\]\n]*\]\([^)\n]*$/, '');
      src = src.replace(/(^|[^!])\[[^\]\n]*\]\([^)\n]*$/, '$1');
      // 未闭合的代码栅栏先按代码块收尾
      const fences = (src.match(/^```/gm) || []).length;
      if (fences % 2) src += '\n```';
      src = closeTrailingMarkers(src);
    }
    const lines = src.split('\n');
    const blocks = [];
    const push = (html, key) => blocks.push({ html, key });
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      if (!line.trim()) { i++; continue; }

      const fence = line.match(/^```([\w+#.-]*)\s*$/);
      if (fence) {
        const lang = fence[1] || '';
        const body = [];
        i++;
        while (i < lines.length && !/^```\s*$/.test(lines[i])) { body.push(lines[i]); i++; }
        if (i < lines.length) i++;
        const code = body.join('\n');
        push(codeBlockHtml(lang, code), 'code\0' + lang + '\0' + code);
        continue;
      }

      const h = line.match(/^(#{1,6})\s+(.*)$/);
      if (h) {
        push(`<div class="md-h md-h${h[1].length}">${formatInline(escapeHtml(h[2]))}</div>`,
          'h' + h[1].length + '\0' + h[2]);
        i++;
        continue;
      }

      if (/^\s*---+\s*$/.test(line)) { push('<hr>', 'hr'); i++; continue; }

      if (/^\s*>\s?/.test(line)) {
        const q = [];
        while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
          q.push(lines[i].replace(/^\s*>\s?/, ''));
          i++;
        }
        push(`<blockquote>${q.map(x => formatInline(escapeHtml(x))).join('<br>')}</blockquote>`,
          'q\0' + q.join('\n'));
        continue;
      }

      if (line.includes('|') && i + 1 < lines.length && isTableSep(lines[i + 1])) {
        const head = splitTableRow(line);
        i += 2;
        const rows = [];
        while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
          rows.push(splitTableRow(lines[i]));
          i++;
        }
        push(tableHtml(head, rows), 'table\0' + line + '\0' + rows.map(r => r.join('|')).join('\n'));
        continue;
      }

      if (/^\s*(?:[-*+]|\d+\.)\s+/.test(line)) {
        const items = [];
        while (i < lines.length) {
          const m = lines[i].match(/^(\s*)([-*+]|\d+\.)\s+(.*)$/);
          if (!m) break;
          const level = Math.min(2, Math.floor(m[1].length / 2));
          let item = m[3];
          let check = '';
          const task = item.match(/^\[([ xX])\]\s+(.*)$/);
          if (task) {
            check = task[1].toLowerCase() === 'x'
              ? '<span class="md-check done">✓</span>'
              : '<span class="md-check"></span>';
            item = task[2];
          }
          const bullet = check || '<span class="md-bullet">•</span>';
          items.push(`<div class="md-li lv${level}">${bullet}<span>${formatInline(escapeHtml(item))}</span></div>`);
          i++;
        }
        push(`<div class="md-list">${items.join('')}</div>`, 'list\0' + items.join(''));
        continue;
      }

      const para = [];
      while (i < lines.length) {
        const l = lines[i];
        if (!l.trim()) break;
        if (/^```/.test(l) || /^(#{1,6})\s+/.test(l) || /^\s*>\s?/.test(l) ||
            /^\s*(?:[-*+]|\d+\.)\s+/.test(l) || /^\s*---+\s*$/.test(l)) break;
        if (l.includes('|') && i + 1 < lines.length && isTableSep(lines[i + 1])) break;
        para.push(l);
        i++;
      }
      if (!para.length) { para.push(line); i++; }
      push(`<div class="md-p">${para.map(x => formatInline(escapeHtml(x))).join('<br>')}</div>`,
        'p\0' + para.join('\n'));
    }
    return blocks;
  }

  function enhanceRendered(root) {
    if (!root) return;
    root.querySelectorAll('.code-wrap').forEach(wrap => {
      if (wrap.dataset.enhanced) return;
      wrap.dataset.enhanced = '1';
      const pre = wrap.querySelector('pre.code');
      const copy = wrap.querySelector('.code-copy');
      const wrapBtn = wrap.querySelector('.code-wrap-btn');
      if (copy && pre) {
        copy.onclick = async () => {
          const ok = await copyText(pre.textContent || '');
          copy.textContent = ok ? '已复制' : '复制失败';
          setTimeout(() => { copy.textContent = '复制'; }, 1500);
        };
      }
      if (wrapBtn && pre) {
        wrapBtn.onclick = () => {
          const on = pre.classList.toggle('wrap');
          wrapBtn.classList.toggle('on', on);
          wrapBtn.textContent = on ? '不换行' : '换行';
        };
      }
    });
    root.querySelectorAll('img').forEach(img => {
      if (img.dataset.enhanced) return;
      img.dataset.enhanced = '1';
      img.onclick = () => {
        const imgs = [...root.querySelectorAll('img.md-image')];
        if (img.classList.contains('md-image') && imgs.length > 1) {
          openImageViewer(img.src, imgs.map(x => x.src), imgs.indexOf(img));
        } else {
          openImageViewer(img.src);
        }
      };
    });
  }

  function renderMarkdown(node, text, opts = {}) {
    if (!node) return;
    const streaming = !!opts.streaming;
    const blocks = markdownBlocks(text, streaming);
    const keys = blocks.map(b => b.key);
    const prev = node._mdKeys;
    const sameAll = prev && prev.length === keys.length && keys.every((k, i) => k === prev[i]);
    if (sameAll) return;
    const samePrefix = prev && prev.length === keys.length &&
      keys.slice(0, -1).every((k, i) => k === prev[i]);
    if (samePrefix && node.lastElementChild && blocks.length) {
      const holder = document.createElement('div');
      holder.innerHTML = blocks[blocks.length - 1].html;
      const fresh = holder.firstElementChild;
      if (fresh) node.lastElementChild.replaceWith(fresh);
      else node.innerHTML = blocks.map(b => b.html).join('');
    } else {
      node.innerHTML = blocks.map(b => b.html).join('');
    }
    node._mdKeys = keys;
    if (!streaming) layoutMarkdownImages(node);
    enhanceRendered(node);
  }

  function formatText(text, opts = {}) {
    return markdownBlocks(text, false, opts).map(b => b.html).join('');
  }

  /** 多张结论图：连续纯图片段落合成两列/三列，最多显示 6 张 */
  function layoutMarkdownImages(root) {
    if (!root) return;
    let images = [...root.querySelectorAll('img.md-image')];
    if (!images.length) return;

    let hidden = 0;
    if (images.length > 6) {
      const extra = images.slice(6);
      hidden = extra.length;
      const parents = new Set(extra.map(img => img.parentElement).filter(Boolean));
      for (const img of extra) img.remove();
      images = images.slice(0, 6);
      for (const p of parents) {
        if (p.classList.contains('md-p') && !p.textContent.trim() && !p.querySelector('img.md-image')) p.remove();
      }
    }

    const isImageOnly = node =>
      node && node.classList && node.classList.contains('md-p') &&
      !!node.querySelector('img.md-image') && !node.textContent.trim();
    const children = [...root.children];
    let i = 0;
    while (i < children.length) {
      if (!isImageOnly(children[i])) { i++; continue; }
      const group = [];
      while (i < children.length && isImageOnly(children[i])) group.push(children[i++]);
      const imgs = group.flatMap(p => [...p.querySelectorAll('img.md-image')]);
      if (imgs.length < 2) continue;
      const grid = document.createElement('div');
      grid.className = 'md-image-grid ' + (imgs.length >= 5 ? 'n3' : 'n2');
      for (const img of imgs) grid.appendChild(img);
      root.insertBefore(grid, group[0]);
      for (const p of group) p.remove();
    }

    if (hidden > 0) {
      const note = document.createElement('div');
      note.className = 'md-image-more';
      note.textContent = `还有 ${hidden} 张图片未展示`;
      root.appendChild(note);
    }
  }

  function scheduleMarkdownRender(ref) {
    if (!ref || ref._mdTimer) return;
    ref._mdTimer = setTimeout(() => {
      ref._mdTimer = 0;
      if (!ref.textEl || !ref.textEl.isConnected) return;
      renderMarkdown(ref.textEl, ref.text, { streaming: true });
    }, 60);
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
    enhanceRendered(root);
  }

  const TOOL_KINDS = {
    Bash: 'command', Read: 'read', Edit: 'edit', Write: 'edit', MultiEdit: 'edit', NotebookEdit: 'edit',
    Grep: 'search', Glob: 'search', WebSearch: 'web', WebFetch: 'web', Task: 'agent',
    TodoWrite: 'todo', ExitPlanMode: 'plan', AskUserQuestion: 'ask'
  };
  const TOOL_VERBS = {
    command: '运行', read: '读取', edit: '编辑', search: '搜索', web: '联网',
    agent: '子代理', todo: '待办', plan: '计划', ask: '提问', other: '工具'
  };
  const TOOL_ICONS = {
    command: '<path d="M5 7l4 5-4 5M12 17h7"/>',
    read: '<path d="M6 3h9l4 4v14H6z"/><path d="M15 3v5h5M9 13h7M9 17h5"/>',
    edit: '<path d="M4 20l4-1 11-11-3-3L5 16z"/><path d="M14 5l3 3"/>',
    search: '<circle cx="11" cy="11" r="6"/><path d="M16 16l4 4"/>',
    web: '<circle cx="12" cy="12" r="8"/><path d="M4 12h16M12 4c3 3 3 13 0 16M12 4c-3 3-3 13 0 16"/>',
    agent: '<rect x="4" y="7" width="16" height="12" rx="3"/><path d="M12 3v4M8 12h.01M16 12h.01M9 16h6"/>',
    todo: '<path d="M9 6h11M9 12h11M9 18h11"/><path d="M4 6l1.2 1.2L7.5 4.8M4 12l1.2 1.2L7.5 10.8M4 18l1.2 1.2L7.5 16.8"/>',
    plan: '<path d="M5 4h14v16H5z"/><path d="M8 8h8M8 12h8M8 16h5"/>',
    ask: '<path d="M4 5h16v11H9l-5 4z"/><path d="M8 9h8M8 12h5"/>',
    think: '<path d="M9 18h6M10 21h4M8 14a5 5 0 1 1 8 0c-.8.8-1 1.5-1 3H9c0-1.5-.2-2.2-1-3z"/>',
    other: '<path d="M5 5h14v14H5z"/><path d="M9 9h6v6H9z"/>'
  };

  function toolKindOf(name) {
    return TOOL_KINDS[name] || 'other';
  }

  function toolIcon(kind) {
    return `<svg viewBox="0 0 24 24" aria-hidden="true">${TOOL_ICONS[kind] || TOOL_ICONS.other}</svg>`;
  }

  function oneLine(s, max = 160) {
    return String(s == null ? '' : s).replace(/\s+/g, ' ').trim().slice(0, max);
  }

  function toolDetail(name, input) {
    if (!input || typeof input !== 'object') return '';
    const kind = toolKindOf(name);
    if (kind === 'command') return oneLine(input.command || input.description || '');
    if (kind === 'edit') {
      const info = editToolInfo(name, input);
      return info ? info.path + (info.added || info.removed ? `  +${info.added} -${info.removed}` : '') : '';
    }
    if (kind === 'read') return oneLine(input.file_path || input.path || '');
    if (kind === 'search') {
      const where = input.path || input.glob || '';
      return oneLine((input.pattern || input.query || '') + (where ? ' · ' + where : ''));
    }
    if (kind === 'web') return oneLine(input.query || input.url || input.prompt || '');
    if (kind === 'agent') return oneLine(input.description || input.prompt || input.task || '');
    if (kind === 'todo') {
      const todos = Array.isArray(input.todos) ? input.todos : [];
      return todos.length ? `${todos.length} 项` : '';
    }
    if (kind === 'plan') return '计划待确认';
    if (kind === 'ask') {
      const qs = Array.isArray(input.questions) ? input.questions : [];
      return qs.length ? oneLine(qs[0].question || qs[0].header || '') : '';
    }
    const pick = input.command || input.file_path || input.path || input.pattern ||
      input.query || input.url || input.description || input.prompt || input.task || '';
    return typeof pick === 'string' ? oneLine(pick) : '';
  }

  function pretty(x) {
    if (typeof x === 'string') return x;
    try { return JSON.stringify(x, null, 2); } catch { return String(x); }
  }

  function thinkingSnippet(b, max = 50) {
    const t = String((b && (b.text || b.thinking)) || '').replace(/\s+/g, ' ').trim();
    if (!t) return '';
    // 不足 max 就原样显示，不补内容；超过时截到 max，避免停在 “1.” 这类句点上
    const chars = Array.from(t);
    if (chars.length <= max) return t;
    let cut = chars.slice(0, max).join('').replace(/\s+$/, '');
    const space = cut.lastIndexOf(' ');
    if (space >= 30) cut = cut.slice(0, space);
    return cut + '…';
  }

  function thinkingRow(b) {
    const d = el('div', 'tool tool-thinking');
    const ico = el('span', 'tool-ico');
    ico.innerHTML = toolIcon('think');
    const name = el('span', 'tool-name', '思考');
    const arg = el('span', 'tool-arg', thinkingSnippet(b));
    d.append(ico, name, arg);
    d._textEl = arg;
    return d;
  }

  function toolNode(b) {
    const kind = toolKindOf(b.name);
    const d = el('div', 'tool tool-' + kind);
    const ico = el('span', 'tool-ico');
    ico.innerHTML = toolIcon(kind);
    const label = kind === 'other' ? (b.name || '工具') : (TOOL_VERBS[kind] || b.name || '工具');
    const name = el('span', 'tool-name', label);
    const arg = el('span', 'tool-arg', toolDetail(b.name, b.input));
    const state = el('span', 'tool-state');
    d.append(ico, name, arg, state);
    d._refs = { arg, state };
    updateToolNode(d, b);
    return d;
  }

  function updateToolNode(d, b) {
    if (!d || !d._refs) return;
    d._block = { ...(d._block || {}), ...b };
    const r = d._refs;
    const detail = toolDetail(d._block.name, d._block.input);
    r.arg.textContent = detail;
    r.arg.hidden = !detail;

    if (d._block.isError) {
      r.state.textContent = '失败';
      r.state.className = 'tool-state err';
    } else if (d._block.result != null || d._block.done) {
      r.state.textContent = '完成';
      r.state.className = 'tool-state ok';
    } else {
      r.state.textContent = '运行中…';
      r.state.className = 'tool-state run';
    }
  }

  const IMAGE_VIEW_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9.5" r="1.5"/><path d="M21 15l-5-5L5 20"/></svg>';

  function toolImageSrc(url) {
    if (!url) return '';
    return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(TOKEN);
  }

  /** 运行中/过程里的「查看了 N 张图片」卡片，默认收起 */
  function imageCardNode(images) {
    const wrap = el('div', 'image-view');
    const head = el('button', 'image-view-head');
    head.type = 'button';
    const chev = el('span', 'image-view-chev', '›');
    const ico = el('span', 'image-view-ico');
    ico.innerHTML = IMAGE_VIEW_ICON;
    const title = el('span', 'image-view-title');
    head.append(chev, ico, title);

    const thumbs = el('div', 'image-view-thumbs');
    thumbs.hidden = true;
    const render = list => {
      thumbs.innerHTML = '';
      for (const im of list || []) {
        if (!im || !im.url) continue;
        const img = el('img', 'image-view-thumb');
        img.src = toolImageSrc(im.url);
        img.alt = '工具查看的图片';
        img.loading = 'lazy';
        img.onclick = () => {
          const imgs = [...thumbs.querySelectorAll('img')];
          openImageViewer(img.src, imgs.map(x => x.src), imgs.indexOf(img));
        };
        thumbs.appendChild(img);
      }
      title.textContent = (list && list.length) === 1
        ? '查看了 1 张图片'
        : `查看了 ${(list && list.length) || 0} 张图片`;
    };
    render(images);
    head.onclick = () => {
      wrap.classList.toggle('open');
      thumbs.hidden = !wrap.classList.contains('open');
    };
    wrap.append(head, thumbs);
    wrap._update = render;
    return wrap;
  }

  function upsertLiveImageCard(toolUseId, images) {
    if (!images || !images.length) return;
    const key = 'imgview:' + toolUseId;
    const ref = S.liveEls.get(key);
    if (ref && ref.imageCard && ref.imageCard.isConnected) {
      ref.imageCard._update(images);
      return;
    }
    const card = imageCardNode(images);
    const box = $('#liveTurn');
    if (!box) return;
    box.appendChild(card);
    S.liveEls.set(key, { imageCard: card });
    maybeScroll();
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
    S.liveProcessBlocks = [];
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
    S.liveProcessBlocks = [];
    return d;
  }

  function setLiveProcessState(text) {
    if (S.liveProcessState) S.liveProcessState.textContent = text || '';
  }

  function bumpLiveProcess(b) {
    if (S.liveProcessBlocks) S.liveProcessBlocks.push(b);
    if (S.liveProcessMeta) {
      S.liveProcessMeta.textContent = processMetaText(S.liveProcessBlocks || []);
    }
    if (b.type === 'thinking') setLiveProcessState('思考中…');
    else if (b.type === 'tool_use') setLiveProcessState(toolRunningText(toolKindOf(b.name)));
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
      if (b.type === 'thinking') {
        ensureLiveProcess(box);
        const row = thinkingRow(b);
        S.liveProcessBody.insertBefore(row, S.liveProcessCollapse);
        S.liveEls.set(b.key, { thinkingRow: row, textEl: row._textEl, text: b.text || '' });
        bumpLiveProcess(b);
        continue;
      }
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
      const toolId = b.id || (b.key && b.key.startsWith('tool:') ? b.key.slice(5) : '');
      if (b.type === 'tool_use' && Array.isArray(b.images) && b.images.length) {
        const card = imageCardNode(b.images);
        box.appendChild(card);
        if (toolId) S.liveEls.set('imgview:' + toolId, { imageCard: card });
      }
    }

    const last = blocks[blocks.length - 1];
    if (last && last.type === 'tool_use') {
      setLiveProcessState(last.result != null ? (last.isError ? '失败' : '完成') : '运行中…');
    }
    syncLiveCursor(box);
  }

  // 只在「最新一段白字阶段总结」末尾显示闪烁光标；后面接着工具/思考时也保留。
  // 新一轮文字出现时先清掉旧的，保证同一时间只有一个光标。
  function syncLiveCursor(box) {
    const root = box || $('#liveTurn');
    if (!root) return;
    root.querySelectorAll('.msg.assistant.streaming').forEach(n => n.classList.remove('streaming'));
    if (!S.running || !S.live) return;
    const blocks = S.live.blocks || [];
    let lastText = null;
    for (let i = blocks.length - 1; i >= 0; i--) {
      if (blocks[i].type === 'text') { lastText = blocks[i]; break; }
    }
    if (!lastText) return;
    const ref = S.liveEls.get(lastText.key);
    const node = ref && ref.textEl ? ref.textEl.closest('.msg.assistant') : null;
    if (node) {
      node.classList.add('streaming');
    } else {
      const texts = root.querySelectorAll(':scope > .msg.assistant');
      const elLast = texts[texts.length - 1];
      if (elLast) elLast.classList.add('streaming');
    }
  }

  function liveBlockNode(b) {
    if (b.type === 'text') {
      const d = el('div', 'msg assistant');
      const t = el('div', 'txt');
      t.textContent = '';
      renderMarkdown(t, b.text || '', { streaming: true });
      d.appendChild(t);
      attachMsgActions(d, { role: 'assistant', blocks: [b] });
      return { node: d, ref: { textEl: t, text: b.text || '', block: b } };
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
    if (b.type === 'tool_use' && b.name === 'TodoWrite') updateTodosFromTool(b.input);
    if (b.type === 'thinking') {
      const box = $('#liveTurn');
      if (!box) return;
      ensureLiveProcess(box);
      const row = thinkingRow(b);
      S.liveProcessBody.insertBefore(row, S.liveProcessCollapse);
      S.liveEls.set(ev.key, { thinkingRow: row, textEl: row._textEl, text: b.text || '' });
      bumpLiveProcess(b);
      setRunProgress(progressForThinking(b), 'thinking');
      return;
    }

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
    const next = ev.block || {};
    updateToolNode(ref.tool, next);
    if (next.name === 'TodoWrite') updateTodosFromTool(next.input);
    const liveBlock = (S.live.blocks || []).find(b => b.key === ev.key);
    if (liveBlock) Object.assign(liveBlock, next);
    if (S.liveProcessMeta) S.liveProcessMeta.textContent = processMetaText(S.liveProcessBlocks || []);
    const kind = toolKindOf(ref.tool._block && ref.tool._block.name);
    setLiveProcessState(toolRunningText(kind));
  }

  function onToolResult(ev) {
    const ref = S.liveEls.get('tool:' + ev.toolUseId);
    if (!ref || !ref.tool) return;
    updateToolNode(ref.tool, { result: ev.content, isError: ev.isError, done: true });
    const liveBlock = (S.live && S.live.blocks || [])
      .find(b => b.key === 'tool:' + ev.toolUseId);
    if (liveBlock) {
      liveBlock.result = ev.content;
      liveBlock.isError = !!ev.isError;
      liveBlock.done = true;
    }
    if (Array.isArray(ev.images) && ev.images.length) {
      const liveBlock = (S.live && S.live.blocks || []).find(b => b.key === 'tool:' + ev.toolUseId);
      if (liveBlock) liveBlock.images = ev.images;
      upsertLiveImageCard(ev.toolUseId, ev.images);
    }
    setLiveProcessState(ev.isError ? '失败' : '完成');
  }

  // ---------------------------------------------------------------- 运行状态行

  function progressForThinking(b) {
    const t = thinkingSnippet(b, 50);
    return t ? '正在思考：' + t : '正在思考…';
  }

  function toolRunningText(kind) {
    if (kind === 'todo') return '更新中…';
    if (kind === 'plan') return '提交中…';
    if (kind === 'ask') return '等待回答…';
    return (TOOL_VERBS[kind] || '工具') + '中…';
  }

  function progressForTool(b) {
    const kind = toolKindOf((b && b.name) || '');
    const detail = toolDetail((b && b.name) || '', b && b.input);
    if (kind === 'todo') return '正在更新待办…';
    if (kind === 'plan') return '正在整理计划…';
    if (kind === 'ask') return '等待你的回答…';
    const verb = TOOL_VERBS[kind] || '执行';
    return '正在' + verb + (detail ? '：' + (detail.length > 80 ? detail.slice(0, 80) + '…' : detail) : '…');
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
      if (t === 'run_end' && ev.convId) S.unread.add(ev.convId);
      if (t === 'run_start' && ev.convId) S.unread.delete(ev.convId);
      if (t === 'run_start' || t === 'run_end' || t === 'permission' || t === 'ask') {
        scheduleBootRefresh();
      }
      return;
    }

    switch (t) {
      case 'run_start': {
        S.running = true;
        if (S.todos && S.todos.every(t => t.status === 'completed')) {
          S.todos = null;
          renderTodoBar();
        }
        notifyPageTask(true);
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
        if (ref.thinkingRow) {
          ref.text = (ref.text + (ev.text || '')).slice(0, 160);
          if (ref.textEl) ref.textEl.textContent = thinkingSnippet({ text: ref.text });
          setRunProgress(progressForThinking({ text: ref.text }), 'thinking');
          break;
        }
        if (ev.kind === 'text' && ref.text != null) {
          ref.text += ev.text || '';
          if (ref.block) ref.block.text = ref.text;
          scheduleMarkdownRender(ref);
        } else {
          ref.textEl.textContent += ev.text || '';
        }
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
        maybeScroll();
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
        maybeScroll();
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
        S.pendingEcho = null;
        {
          // 结束后把过程图片卡片收回「过程」里；随后刷新历史时会按 images 重新渲染
          const box = $('#liveTurn');
          if (box && S.liveProcessBody) {
            box.querySelectorAll(':scope > .image-view').forEach(card => S.liveProcessBody.appendChild(card));
          }
        }
        notifyPageTask(false);
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
        '&limit=' + limit + '&light=1&compact=1', { timeout: 30000 });
      renderConversation(data, true);
    } catch { /* 下次事件再试 */ }
    finally { refreshing = false; }
  }

  // 电脑端和手机端同时打开同一对话时，SSE 偶尔会静默丢事件。
  // 对话打开期间用很轻的 limit=1 轮询兜底：发现远端开始/结束或历史条数变化就整页对齐。
  let convSyncTimer = null;
  let convSyncBusy = false;

  function startConvSync() {
    if (convSyncTimer) return;
    convSyncTimer = setInterval(pollConversationState, 2000);
  }

  function stopConvSync() {
    if (convSyncTimer) clearInterval(convSyncTimer);
    convSyncTimer = null;
    convSyncBusy = false;
  }

  async function pollConversationState() {
    if (convSyncBusy || !S.convId || !S.conv || document.hidden) return;
    if (S.loadingOlder || S.sending) return;
    // 本地正在跑且 SSE 有事件时，实时区已经够用，不额外拉历史。
    if (S.running && Date.now() - lastSseAt < 4000) return;
    const id = S.convId;
    convSyncBusy = true;
    try {
      const data = await api('/api/conversation?id=' + encodeURIComponent(id) +
        '&limit=1&light=1&compact=1', { timeout: 8000 });
      if (!S.convId || S.convId !== id) return;
      const remoteRunning = !!data.running;
      const localRunning = !!S.running;
      const localTotal = Number(S.conv && S.conv.total) || 0;
      const remoteTotal = Number(data.total) || 0;
      const changed = remoteRunning !== localRunning ||
        (!remoteRunning && !localRunning && remoteTotal !== localTotal) ||
        (remoteRunning && localRunning && remoteTotal > localTotal &&
          Date.now() - lastSseAt > 4000);
      if (changed) await refreshConversation();
    } catch { /* 下次再试 */ }
    finally { convSyncBusy = false; }
  }

  // 回到前台/重新聚焦时强制对齐一次服务器状态。
  // 手机 WebView 在后台会暂停页面，run_end 可能整条丢掉；只靠 SSE/轮询恢复会让
  // S.running/S.live 停在旧任务上，之后发的新消息就被当成插话挂到已完成任务底部。
  let resumeSyncing = false;
  async function resumeSync(force = false) {
    if (!S.convId || resumeSyncing || document.hidden) return;
    if (!force && Date.now() - (S.lastResumeSyncAt || 0) < 1200) return;
    resumeSyncing = true;
    S.lastResumeSyncAt = Date.now();
    const id = S.convId;
    const wasRunning = S.running;
    try {
      const loaded = S.conv && Array.isArray(S.conv.messages) ? S.conv.messages.length : 30;
      // 后台期间可能完成了一整轮任务，多拉一些历史，避免回前台只看到半截。
      const limit = Math.min(200, Math.max(60, loaded + 40));
      const data = await api('/api/conversation?id=' + encodeURIComponent(id) +
        '&limit=' + limit + '&light=1&compact=1', { timeout: 15000 });
      if (!S.convId || S.convId !== id) return;
      if (!data.running) {
        S.running = false;
        S.live = null;
        S.pendingEcho = null;
        stopLivePolling();
        closeStopConfirm();
        setRunProgress('', '');
        if (wasRunning) notifyPageTask(false);
      }
      renderConversation(data, true);
      S.needsResumeSync = false;
    } catch { /* 网络没恢复：保留状态，发送时还有 steer→chat 兜底 */ }
    finally { resumeSyncing = false; }
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
        '&limit=1&light=1&compact=1', { timeout: 8000 });
      if (!S.convId || S.convId !== wantId) return;
      if (!data.running) {
        if (S.running) {
          S.running = false;
          notifyPageTask(false);
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
        '&limit=30&before=' + encodeURIComponent(S.conv.start || 0) + '&light=1&compact=1', { timeout: 30000 });
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
      im.onclick = () => {
        // 只在输入框这一批待发图片里切换
        openImageViewer(img.preview, S.pendingImages.map(x => x.preview), i);
      };
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

  function quoteBlockText(q) {
    const meta = JSON.stringify({
      source: String((q && q.source) || ''),
      conv: String((q && q.sourceId) || ''),
      role: String((q && q.role) || '')
    });
    const excerpt = String((q && q.text) || '')
      .replace(/\r\n?/g, '\n')
      .replace(/<\/?gui-quote/gi, m => '&lt;' + m.slice(1))
      .trim();
    return '<gui-quote>\n' + meta + '\n' + excerpt + '\n</gui-quote>';
  }

  function withQuoteBlocks(text, quotes) {
    const blocks = (quotes || []).map(quoteBlockText).filter(Boolean).join('\n\n');
    const t = String(text || '').trim();
    return blocks ? (blocks + (t ? '\n\n' + t : '')) : t;
  }

  function renderPendingQuotes() {
    const box = $('#pendingQuotes');
    if (!box) return;
    box.innerHTML = '';
    box.hidden = S.pendingQuotes.length === 0;
    S.pendingQuotes.forEach(q => {
      const chip = el('span', 'ref-chip quote-chip');
      chip.title = q.text || '';
      chip.appendChild(el('span', 'quote-ic', '❝'));
      chip.appendChild(el('span', 'lbl', quotePreview(q.text)));
      const x = el('button', null, '×');
      x.type = 'button';
      x.title = '移除引用';
      x.onclick = () => {
        S.pendingQuotes = S.pendingQuotes.filter(xq => xq.id !== q.id);
        renderPendingQuotes();
        updateSendState();
      };
      chip.appendChild(x);
      box.appendChild(chip);
    });
  }

  function addPendingQuote(q) {
    const text = String((q && q.text) || '').trim();
    if (!text) return false;
    const key = String(q.sourceId || '') + '\u0000' + text;
    if (S.pendingQuotes.some(x => String(x.sourceId || '') + '\u0000' + x.text === key)) {
      toast('这段已经引用过了');
      return false;
    }
    if (S.pendingQuotes.length >= 5) {
      toast('最多同时引用 5 段文字', 'err');
      return false;
    }
    S.pendingQuotes.push({
      id: 'q' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5),
      text,
      source: String(q.source || '当前对话'),
      sourceId: String(q.sourceId || ''),
      role: String(q.role || '')
    });
    renderPendingQuotes();
    updateSendState();
    const input = $('#input');
    if (input) input.focus();
    return true;
  }

  function messagePlainText(m) {
    if (!m) return '';
    if (m.role === 'user') {
      const info = userTextInfo(m.text || '');
      const quotes = (info.quotes || []).map(q => q.text).filter(Boolean).join('\n\n');
      return [quotes, info.text].filter(x => x && x.trim()).join('\n\n');
    }
    return ((m.blocks || [])
      .filter(b => b && b.type === 'text')
      .map(b => b.text || '')
      .filter(Boolean)).join('\n\n');
  }

  let msgActionGuardUntil = 0;

  function showMsgActions(m, node) {
    const text = messagePlainText(m);
    if (!text.trim()) {
      toast('这条消息没有可复制或引用的文字');
      return;
    }
    S.msgAction = {
      text,
      node: node || null,
      role: m.role === 'user' ? 'user' : 'assistant',
      source: (S.conv && (S.conv.title || '新对话')) || '当前对话',
      sourceId: (S.conv && S.conv.id) || ''
    };
    hideToast();
    $('#msgActionPreview').textContent = msgPreview(text);
    msgActionGuardUntil = Date.now() + 400;
    $('#msgActions').hidden = false;
  }

  function closeMsgActions() {
    $('#msgActions').hidden = true;
    S.msgAction = null;
  }

  async function handleMsgAction(act) {
    const a = S.msgAction;
    closeMsgActions();
    if (!a) return;
    if (act === 'copy') {
      const ok = await copyText(a.text);
      toast(ok ? '已复制' : '复制失败', ok ? '' : 'err');
      return;
    }
    if (act === 'quote') {
      if (addPendingQuote(a)) toast('已加入引用');
      return;
    }
    if (act === 'select') {
      selectMessageText(a);
      return;
    }
    if (act === 'quoteOther') {
      openQuoteTarget(a);
    }
  }

  // ---------------------------------------------------------------- 选中文字

  let selBarInfo = null;
  let selChangeTimer = 0;
  let selWatchTimer = 0;

  function selectionMessageInfo() {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) return null;
    const text = sel.toString().replace(/\s+/g, ' ').trim();
    if (!text) return null;
    const range = sel.getRangeAt(0);
    let node = range.commonAncestorContainer;
    if (node && node.nodeType === 3) node = node.parentNode;
    const msg = node && node.closest ? node.closest('.msg') : null;
    if (!msg) return null;
    return { text, range, msg };
  }

  function hideSelToolbar() {
    const bar = $('#selToolbar');
    if (bar) {
      bar.hidden = true;
      bar.classList.remove('below');
    }
    selBarInfo = null;
    if (selWatchTimer) {
      clearInterval(selWatchTimer);
      selWatchTimer = 0;
    }
  }

  // 消息区刷新/流式重绘可能把选区弄丢，但不会触发 selectionchange；工具条显示期间轻量校验
  function watchSelToolbar() {
    if (selWatchTimer) return;
    selWatchTimer = setInterval(() => {
      if (!selBarInfo) return;
      const info = selectionMessageInfo();
      if (!info) hideSelToolbar();
      else placeSelToolbar(info);
    }, 300);
  }

  function placeSelToolbar(info) {
    const bar = $('#selToolbar');
    if (!bar || !info) return;
    // App 的系统选择菜单已经带了引用项，页面工具条不再叠在系统 UI 上
    if (window.__nativeSelectionMenu) {
      hideSelToolbar();
      return;
    }
    selBarInfo = {
      text: info.text,
      role: info.msg.classList.contains('user') ? 'user' : 'assistant',
      source: (S.conv && (S.conv.title || '新对话')) || '当前对话',
      sourceId: (S.conv && S.conv.id) || ''
    };
    bar.hidden = false;
    const rect = info.range.getBoundingClientRect();
    const bw = bar.offsetWidth || 200;
    const bh = bar.offsetHeight || 38;
    const cx = Math.min(
      Math.max(rect.left + rect.width / 2, bw / 2 + 8),
      window.innerWidth - bw / 2 - 8
    );
    // 手机 App 里系统菜单通常在选区上方，页面工具条优先放下方，避免互相遮挡
    const appSpaceBelow = window.innerHeight - rect.bottom;
    let below = !!window.AndroidDownloads
      ? appSpaceBelow > bh + 24
      : (rect.top - 8 - bh < 8);
    let top = rect.top - 8;
    if (below) top = Math.min(rect.bottom + 8, window.innerHeight - 8);
    bar.classList.toggle('below', below);
    bar.style.left = cx + 'px';
    bar.style.top = top + 'px';
    watchSelToolbar();
  }

  function refreshSelToolbar() {
    const info = selectionMessageInfo();
    if (info) placeSelToolbar(info);
    else hideSelToolbar();
  }

  function selectMessageText(a) {
    window.__nativeSelectionMenu = false;
    let node = a && a.node;
    // 打开菜单后消息列表可能刷新过，旧节点会脱离文档；按角色和开头文字找回当前节点
    if (!node || !document.contains(node)) {
      const role = a && a.role === 'user' ? 'user' : 'assistant';
      const all = [...document.querySelectorAll('.msg.' + role)];
      const needle = String((a && a.text) || '').slice(0, 40);
      node = all.find(el => (el.innerText || '').includes(needle)) || all[all.length - 1] || null;
    }
    if (!node) return;
    const target = node.querySelector('.txt, .utext') || node;
    if (!document.contains(target)) return;
    const range = document.createRange();
    range.selectNodeContents(target);
    const sel = window.getSelection();
    if (!sel) return;
    sel.removeAllRanges();
    sel.addRange(range);
    setTimeout(refreshSelToolbar, 30);
  }

  async function handleSelAction(act) {
    const info = selBarInfo;
    if (!info) return;
    const q = {
      text: info.text,
      role: info.role,
      source: info.source,
      sourceId: info.sourceId
    };
    const sel = window.getSelection();
    if (sel) sel.removeAllRanges();
    hideSelToolbar();
    if (act === 'copy') {
      const ok = await copyText(info.text);
      toast(ok ? '已复制选中文字' : '复制失败', ok ? '' : 'err');
    } else if (act === 'quote') {
      if (addPendingQuote(q)) toast('已加入引用');
    } else if (act === 'quoteOther') {
      openQuoteTarget(q);
    }
  }

  function setupSelectionToolbar() {
    const bar = $('#selToolbar');
    if (!bar) return;
    // 点工具条时不要清掉选区，否则 click 还没触发系统就收起了选择
    bar.addEventListener('mousedown', e => e.preventDefault());
    bar.addEventListener('touchstart', e => e.preventDefault(), { passive: false });
    bar.addEventListener('click', e => {
      const btn = e.target.closest('[data-sel]');
      if (!btn) return;
      e.preventDefault();
      handleSelAction(btn.dataset.sel);
    });
    document.addEventListener('selectionchange', () => {
      clearTimeout(selChangeTimer);
      selChangeTimer = setTimeout(() => {
        const info = selectionMessageInfo();
        if (info) {
          if (cancelMsgPress) cancelMsgPress();
          placeSelToolbar(info);
        } else {
          window.__nativeSelectionMenu = false;
          hideSelToolbar();
        }
      }, 80);
    });
    $('#messages').addEventListener('scroll', () => {
      if (selBarInfo) hideSelToolbar();
    }, { passive: true });

    // App 的系统选择菜单点「引用 / 引用到其他对话」后由网页接管
    window.__nativeSelectionAction = act => {
      const info = selectionMessageInfo();
      if (!info) return false;
      const q = {
        text: info.text,
        role: info.msg.classList.contains('user') ? 'user' : 'assistant',
        source: (S.conv && (S.conv.title || '新对话')) || '当前对话',
        sourceId: (S.conv && S.conv.id) || ''
      };
      const sel = window.getSelection();
      if (sel) sel.removeAllRanges();
      hideSelToolbar();
      if (act === 'quote') {
        if (addPendingQuote(q)) toast('已加入引用');
      } else if (act === 'quoteOther') {
        openQuoteTarget(q);
      }
      return true;
    };

    // App 输入框菜单里的「粘贴」：把剪贴板文字插到当前光标处
    window.__nativePasteText = text => {
      const el = document.activeElement;
      if (!el) return false;
      const tag = (el.tagName || '').toLowerCase();
      if (tag === 'textarea' || tag === 'input') {
        const start = el.selectionStart == null ? el.value.length : el.selectionStart;
        const end = el.selectionEnd == null ? start : el.selectionEnd;
        try {
          el.setRangeText(text, start, end, 'end');
        } catch (e) {
          el.value = el.value.slice(0, start) + text + el.value.slice(end);
          try { el.selectionStart = el.selectionEnd = start + text.length; } catch (e2) { /* ignore */ }
        }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        return true;
      }
      try {
        return document.execCommand('insertText', false, text);
      } catch (e) {
        return false;
      }
    };
    window.__nativeToast = msg => toast(msg, 'err');
  }

  function attachMsgActions(node, m) {
    if (!node || node._msgActionsBound) return;
    node._msgActionsBound = true;
    node.classList.add('msg-actionable');
    let pressTimer = null;
    let longPressed = false;
    let sx = 0;
    let sy = 0;
    const clear = () => {
      if (pressTimer) {
        clearTimeout(pressTimer);
        pressTimer = null;
      }
      if (cancelMsgPress === clear) cancelMsgPress = null;
    };
    const start = e => {
      if (e.target.closest('button, a, summary, img, .code-tools')) return;
      if (e.type === 'mousedown' && e.button !== 0) return;
      if (Date.now() - lastMsgScrollAt < 250) return;
      sx = e.clientX;
      sy = e.clientY;
      longPressed = false;
      clear();
      cancelMsgPress = clear;
      pressTimer = setTimeout(() => {
        longPressed = true;
        // 长按正文时优先让系统选字；已经出现选区就不再抢长按菜单
        const sel = window.getSelection();
        if (sel && !sel.isCollapsed && selectionMessageInfo()) {
          refreshSelToolbar();
          return;
        }
        showMsgActions(m, node);
      }, 650);
    };
    const move = e => {
      if (Math.abs(e.clientX - sx) > 4 || Math.abs(e.clientY - sy) > 4) clear();
    };
    const end = () => clear();
    node.addEventListener('touchstart', start, { passive: true });
    node.addEventListener('touchmove', move, { passive: true });
    node.addEventListener('touchend', end);
    node.addEventListener('touchcancel', end);
    node.addEventListener('mousedown', start);
    node.addEventListener('mousemove', move);
    node.addEventListener('mouseup', end);
    node.addEventListener('mouseleave', end);
    node.addEventListener('contextmenu', e => {
      if (e.target.closest('button, a, img, summary, .code-tools')) return;
      // 正文交给系统长按选字；空白处仍弹出整条消息菜单
      if (e.target.closest('.txt, .utext, pre, code, .md-p, .md-list, blockquote')) return;
      e.preventDefault();
      showMsgActions(m, node);
    });
    node.addEventListener('click', e => {
      if (!longPressed) return;
      longPressed = false;
      e.preventDefault();
      e.stopPropagation();
    }, true);
  }

  function openQuoteTarget(q) {
    hideToast();
    S.quoteTarget = q;
    $('#quoteTargetSearch').value = '';
    renderQuoteTarget();
    $('#quoteTargetSheet').hidden = false;
    // 不在打开时自动聚焦搜索框，避免手机输入法直接弹出来挡住列表
  }

  function closeQuoteTarget() {
    $('#quoteTargetSheet').hidden = true;
    S.quoteTarget = null;
  }

  function renderQuoteTarget() {
    const box = $('#quoteTargetList');
    if (!box) return;
    box.innerHTML = '';
    const q = ($('#quoteTargetSearch').value || '').trim().toLowerCase();
    const convs = allConversations()
      .filter(c => c.id !== S.convId)
      .filter(c => !q || `${c.title || ''} ${c.preview || ''} ${c.folder || ''}`.toLowerCase().includes(q))
      .sort((a, b) => new Date(b.updatedAt || 0) - new Date(a.updatedAt || 0));
    if (!convs.length) {
      box.innerHTML = '<div class="empty">没有可引用的对话</div>';
      return;
    }
    for (const c of convs) {
      const row = el('button', 'ref-item quote-target-row');
      row.type = 'button';
      const folderName = String(c.folder || '').split(/[\\/]/).filter(Boolean).pop() || '';
      row.innerHTML =
        `<span class="quote-target-ic">❝</span>` +
        `<span class="main"><span class="t">${escapeHtml(c.title || '未命名对话')}</span>` +
        `<span class="d">${escapeHtml(c.preview || '（没有预览）')}</span>` +
        `<span class="m">${escapeHtml(folderName)} · ${escapeHtml(convTime(c.updatedAt))}</span></span>`;
      row.onclick = () => quoteToConversation(c.id);
      box.appendChild(row);
    }
  }

  async function quoteToConversation(id) {
    const q = S.quoteTarget;
    closeQuoteTarget();
    if (!q) return;
    if (S.convId !== id) {
      await openConv(id, true);
      if (S.convId !== id) return;
    }
    if (addPendingQuote(q)) toast('已加入引用');
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

  /** 从文件头读真实宽高：手机 WebView 可能把超大图解码成较小尺寸，naturalWidth 不可靠 */
  function readImageDimensions(file) {
    return new Promise(resolve => {
      const r = new FileReader();
      r.onload = () => {
        try {
          const b = new Uint8Array(r.result);
          if (b.length < 12) { resolve(null); return; }
          let w = 0, h = 0;
          if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) {
            w = (b[16] << 24) | (b[17] << 16) | (b[18] << 8) | b[19];
            h = (b[20] << 24) | (b[21] << 16) | (b[22] << 8) | b[23];
          } else if (b[0] === 0xff && b[1] === 0xd8) {
            let i = 2;
            while (i + 9 < b.length) {
              if (b[i] !== 0xff) { i++; continue; }
              const m = b[i + 1];
              if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
                h = (b[i + 5] << 8) | b[i + 6];
                w = (b[i + 7] << 8) | b[i + 8];
                break;
              }
              const len = (b[i + 2] << 8) | b[i + 3];
              if (len < 2) break;
              i += 2 + len;
            }
          } else if (b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46 &&
                     b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50) {
            const t = String.fromCharCode(b[12], b[13], b[14], b[15]);
            if (t === 'VP8X') {
              w = 1 + (b[24] | (b[25] << 8) | (b[26] << 16));
              h = 1 + (b[27] | (b[28] << 8) | (b[29] << 16));
            } else if (t === 'VP8 ') {
              w = (b[26] | (b[27] << 8)) & 0x3fff;
              h = (b[28] | (b[29] << 8)) & 0x3fff;
            } else if (t === 'VP8L') {
              const bits = b[21] | (b[22] << 8) | (b[23] << 16) | (b[24] << 24);
              w = (bits & 0x3fff) + 1;
              h = ((bits >> 14) & 0x3fff) + 1;
            }
          } else if (b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46) {
            w = b[6] | (b[7] << 8);
            h = b[8] | (b[9] << 8);
          }
          resolve(w > 0 && h > 0 ? { w, h } : null);
        } catch { resolve(null); }
      };
      r.onerror = () => resolve(null);
      r.readAsArrayBuffer(file.slice(0, Math.min(file.size, 256 * 1024)));
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
    let skipped = 0;
    for (const file of list) {
      try {
        // 太长的截图不在手机 WebView 里切图（容易解码成白图/丢内容）；
        // 直接走文件通道原样上传，模型用 Read 读原图。
        const dims = await readImageDimensions(file);
        if (dims && dims.w > 0 && dims.h > 3000 && dims.h / dims.w > 3) {
          addPhoneFiles([file]);
          toast(`长截图 ${dims.w}×${dims.h}：已按文件发送，模型会直接读取原图`);
          continue;
        }
        if (S.pendingImages.length >= MAX_PENDING_IMAGES) { skipped++; continue; }
        const img = await readImageFile(file);
        S.pendingImages.push(img);
        renderPendingImages();
      } catch (e) {
        toast('图片读取失败：' + (file.name || '未知文件'), 'err');
      }
    }
    if (skipped) toast(`最多 ${MAX_PENDING_IMAGES} 张图片，多余的已忽略`, 'err');
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
    const quotes = S.pendingQuotes.slice();
    if ((!text && !images.length && !refs.length && !files.length && !quotes.length) ||
        !S.convId || S.sending) return;

    S.sending = true;
    const btn = $('#sendBtn');
    btn.disabled = true;
    // 从后台回来直接发消息：先对一次服务器状态，避免把新消息当成旧任务的插话。
    if (S.needsResumeSync) await resumeSync(true);
    let wasRunning = S.running;
    let sentSteer = wasRunning;
    const sendConvId = S.convId;
    try {
      let finalText = text;
      if (refs.length) {
        toast('正在整理引用…');
        finalText = await composeWithRefs(text, refs);
      }
      if (quotes.length) finalText = withQuoteBlocks(finalText, quotes);

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
            // 后台期间旧 run_end 丢了：先把旧 live 清掉并补回历史，再按新消息发送，
            // 否则新气泡会被挂到已完成任务的 liveTurn 底部。
            await resumeSync(true);
            S.running = false;
            S.live = null;
            S.needsResumeSync = false;
            renderMessages(true);
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
      notifyPageTask(true);
      input.value = '';
      S.pendingImages = [];
      S.pendingRefs = [];
      S.pendingQuotes = [];
      S.pendingFiles = [];
      S.refSel = new Map();
      S.drafts.delete(sendConvId);
      renderPendingImages();
      renderPendingRefs();
      renderPendingQuotes();
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
  let lastScrollTop = 0;
  // 用户主动上滑看历史后，状态更新不要把人拽回底部
  let readingHistory = false;

  /** 用户手指刚碰过消息区，短时间内不要自动回到底部，避免和滑动抢 */
  function holdAutoScroll() {
    scrollHoldUntil = Date.now() + 1200;
  }

  function maybeScroll(force) {
    if (!force) {
      if (readingHistory) return;
      if (Date.now() < scrollHoldUntil) return;
      if (!isNearBottom()) return;
    } else {
      readingHistory = false;
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
  const FONT_SIZE_LABELS = { small: '小', normal: '标准', large: '大', xlarge: '特大' };
  let optionOnPick = null;

  function effortLevels() {
    return (S.boot && S.boot.settings && S.boot.settings.effortLevels) ||
      ['low', 'medium', 'high', 'xhigh', 'max'];
  }

  function currentEffortValue() {
    const levels = effortLevels();
    const cur = (S.conv && S.conv.effort) ||
      (S.boot && S.boot.settings && S.boot.settings.defaultEffort) || 'max';
    if (levels.includes(cur)) return cur;
    return levels.includes('max') ? 'max' : (levels[levels.length - 1] || 'max');
  }

  function optionPickerOptions(sel) {
    return [...(sel ? sel.options : [])].map(o => ({ value: o.value, label: o.textContent }));
  }

  function updatePickButton(id) {
    const sel = $('#' + id);
    const btn = $('#' + id + 'Btn');
    if (!sel || !btn) return;
    const o = sel.options[sel.selectedIndex];
    btn.textContent = o ? o.textContent : (sel.value || '未设置');
    btn.title = o ? o.textContent : '';
  }

  function updateModeTone() {
    const sel = $('#setMode');
    const btn = $('#setModeBtn');
    if (!sel || !btn) return;
    btn.classList.remove('mode-danger', 'mode-warn', 'mode-safe', 'mode-plan');
    const tone = {
      bypassPermissions: 'mode-danger',
      auto: 'mode-warn',
      acceptEdits: 'mode-safe',
      plan: 'mode-plan'
    }[sel.value];
    if (tone) btn.classList.add(tone);
  }

  function openOptionPicker(title, options, value, onPick) {
    optionOnPick = onPick;
    const t = $('#optionTitle');
    if (t) t.textContent = title;
    const list = $('#optionList');
    if (!list) return;
    list.innerHTML = '';
    (options || []).forEach(opt => {
      const b = el('button', 'option-item' + (String(opt.value) === String(value) ? ' sel' : ''));
      b.type = 'button';
      b.innerHTML = `<span class="opt-main"><span class="opt-label">${escapeHtml(opt.label)}</span>` +
        (opt.desc ? `<span class="opt-desc">${escapeHtml(opt.desc)}</span>` : '') +
        `</span><span class="opt-radio" aria-hidden="true"></span>`;
      b.onclick = () => {
        const fn = optionOnPick;
        closeOptionPicker();
        if (fn) fn(opt.value);
      };
      list.appendChild(b);
    });
    const sheet = $('#optionSheet');
    if (sheet) sheet.hidden = false;
  }

  function closeOptionPicker() {
    optionOnPick = null;
    const sheet = $('#optionSheet');
    if (sheet) sheet.hidden = true;
  }

  function pickSelectOption(id, title, onPick) {
    const sel = $('#' + id);
    if (!sel) return;
    openOptionPicker(title, optionPickerOptions(sel), sel.value, v => {
      sel.value = v;
      updatePickButton(id);
      if (onPick) onPick(v);
    });
  }

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
    const modeVal = S.conv.permissionMode || settings.defaultPermissionMode || modes[0] || '';
    $('#setMode').value = modeVal;
    updatePickButton('setMode');
    updateModeTone();

    const models = (settings.availableModels || []).map(m => ({
      value: m.id,
      label: m.id
    }));
    const modelVal = S.conv.model || settings.defaultModel || (models[0] && models[0].value) || '';
    if (modelVal && !models.some(m => m.value === modelVal)) {
      models.unshift({ value: modelVal, label: modelVal });
    }
    fillSelect($('#setModel'), models);
    $('#setModel').value = modelVal;
    updatePickButton('setModel');

    fillSelect($('#setFontSize'), Object.keys(FONT_SIZE_LABELS).map(k => ({ value: k, label: FONT_SIZE_LABELS[k] })));
    $('#setFontSize').value = S.fontScale;
    updatePickButton('setFontSize');

    $('#chatSettings').hidden = false;
  }

  function closeChatSettings() {
    $('#chatSettings').hidden = true;
    closeOptionPicker();
  }

  async function saveChatSettings() {
    if (!S.convId) return;
    const body = {
      title: $('#setTitle').value.trim(),
      model: $('#setModel').value,
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
      const fontSize = $('#setFontSize');
      if (fontSize) applyFontScale(fontSize.value);
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
    const es = new EventSource('/api/stream?token=' + encodeURIComponent(TOKEN) + '&compact=1');
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
    const filterRow = $('#filterRow');
    if (filterRow) filterRow.addEventListener('click', e => {
      const b = e.target.closest('[data-filter]');
      if (!b) return;
      S.filter = b.dataset.filter || 'all';
      renderList();
    });
    $('#convList').addEventListener('click', e => {
      const b = e.target.closest('[data-empty-act]');
      if (!b) return;
      if (b.dataset.emptyAct === 'new') openSheet();
      else { S.filter = 'all'; renderList(); }
    });
    const todoToggle = $('#todoToggle');
    if (todoToggle) todoToggle.onclick = toggleTodoBar;
    $('#backBtn').onclick = () => closeChat();
    $('#stopBtn').onclick = stopRun;
    $('#cancelStop').onclick = closeStopConfirm;
    $('#confirmStopBtn').onclick = doStop;
    $('#confirmStop').addEventListener('click', e => {
      if (e.target === $('#confirmStop')) closeStopConfirm();
    });
    const quickEffortBtn = $('#quickEffortBtn');
    if (quickEffortBtn) quickEffortBtn.onclick = () => {
      const sel = $('#quickEffort');
      if (!sel) return;
      openOptionPicker('推理档位', optionPickerOptions(sel), sel.value, v => {
        sel.value = v;
        changeQuickEffort(v);
      });
    };
    [['setMode', '权限模式'], ['setModel', '模型'],
      ['setFontSize', '对话字号']].forEach(([id, title]) => {
      const btn = $('#' + id + 'Btn');
      if (btn) btn.onclick = () => pickSelectOption(id, title,
        id === 'setMode' ? updateModeTone : null);
    });
    const optionClose = $('#optionClose');
    if (optionClose) optionClose.onclick = closeOptionPicker;
    const optionSheet = $('#optionSheet');
    if (optionSheet) optionSheet.addEventListener('click', e => {
      if (e.target === optionSheet) closeOptionPicker();
    });
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
    const pickCamera = $('#pickCamera');
    if (pickCamera) pickCamera.onclick = () => {
      closeAttachSheet();
      const input = $('#cameraInput');
      if (input) input.click();
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
    const msgActions = $('#msgActions');
    if (msgActions) msgActions.addEventListener('click', e => {
      if (e.target === msgActions) {
        if (Date.now() < msgActionGuardUntil) return;
        return closeMsgActions();
      }
      const b = e.target.closest('[data-act]');
      if (b) handleMsgAction(b.dataset.act);
    });
    const quoteTargetClose = $('#quoteTargetClose');
    if (quoteTargetClose) quoteTargetClose.onclick = closeQuoteTarget;
    const quoteTargetSearch = $('#quoteTargetSearch');
    if (quoteTargetSearch) quoteTargetSearch.addEventListener('input', renderQuoteTarget);
    const quoteTargetSheet = $('#quoteTargetSheet');
    if (quoteTargetSheet) quoteTargetSheet.addEventListener('click', e => {
      if (e.target === quoteTargetSheet) closeQuoteTarget();
    });
    const diffClose = $('#diffClose');
    if (diffClose) diffClose.onclick = closeDiffSheet;
    const diffSheet = $('#diffSheet');
    if (diffSheet) diffSheet.addEventListener('click', e => {
      if (e.target === diffSheet) closeDiffSheet();
    });
    $('#balanceBtn').onclick = () => { loadBalance(true); };
    $('#reconnectBtn').onclick = () => location.reload();
    $('#reconnectBtn2').onclick = () => location.reload();
    window.addEventListener('online', () => { if (!S.conn) hardReconnect(); });
    window.addEventListener('pageshow', () => {
      if (!S.conn) hardReconnect();
      if (S.convId) void resumeSync(true);
    });
    window.addEventListener('pagehide', () => { S.needsResumeSync = true; });
    window.addEventListener('focus', () => {
      if (S.convId && !document.hidden) void resumeSync();
    });
    $('#downloadsBtn').onclick = openDownloadsSheet;
    $('#downloadsClose').onclick = closeDownloadsSheet;
    $('#downloadsSheet').addEventListener('click', e => {
      if (e.target === $('#downloadsSheet')) closeDownloadsSheet();
    });
    const statusBtn = $('#statusBtn');
    if (statusBtn) statusBtn.onclick = openStatusSheet;
    const statusClose = $('#statusClose');
    if (statusClose) statusClose.onclick = closeStatusSheet;
    const statusRefresh = $('#statusRefresh');
    if (statusRefresh) statusRefresh.onclick = () => refreshStatus(true);
    const statusSheet = $('#statusSheet');
    if (statusSheet) statusSheet.addEventListener('click', e => {
      if (e.target === statusSheet) closeStatusSheet();
    });
    $('#fileInput').addEventListener('change', e => {
      addImageFiles(e.target.files);
      e.target.value = '';
    });
    const cameraInput = $('#cameraInput');
    if (cameraInput) cameraInput.addEventListener('change', e => {
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
      lastMsgScrollAt = Date.now();
      const m = $('#messages');
      const top = m.scrollTop;
      if (top < lastScrollTop - 3 && !isNearBottom()) readingHistory = true;
      else if (isNearBottom()) readingHistory = false;
      lastScrollTop = top;
      if (cancelMsgPress) cancelMsgPress();
    }, { passive: true });
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
    setupSheetBackHandling();
    setupSelectionToolbar();

    const imgViewerClose = $('#imgViewerClose');
    if (imgViewerClose) imgViewerClose.onclick = closeImageViewer;
    const imgViewerPrev = $('#imgViewerPrev');
    if (imgViewerPrev) imgViewerPrev.onclick = () => stepImageViewer(-1);
    const imgViewerNext = $('#imgViewerNext');
    if (imgViewerNext) imgViewerNext.onclick = () => stepImageViewer(1);
    // 点图片外的空白关闭；图片本身用单指点按关闭、双击放大、左右滑切图。
    const imgViewerStage = $('#imgViewerStage');
    if (imgViewerStage) {
      imgViewerStage.addEventListener('click', e => {
        if (e.target === imgViewerStage) closeImageViewer();
      });
      setupImageViewerGestures();
    }
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') {
        closeImageViewer();
        closeStopConfirm();
        closeChatSettings();
        closeDeleteConfirm();
        closeAttachSheet();
        closeRefSheet();
        closeDownloadsSheet();
        closeStatusSheet();
        closePcFileSheet();
        closeMsgActions();
        closeQuoteTarget();
        closeDiffSheet();
        closeOptionPicker();
      }
    });

    window.addEventListener('popstate', e => {
      if (sheetHistoryCleanup) {
        sheetHistoryCleanup = false;
        return;
      }
      if (closeTopSheetFromHistory()) return;
      if (e.state && e.state.sheet) return;
      const st = e.state;
      if (st && st.conv) openConv(st.conv, false);
      else closeChat(false);
    });

    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        S.needsResumeSync = true;
        return;
      }
      if (!S.conn) hardReconnect();
      loadBoot().catch(() => {});
      if (S.convId) {
        void resumeSync(true).then(() => {
          if (S.running) startLivePolling();
        });
      }
    });
  }

  /** 只把手机本机页的任务状态报给 App，用于通知栏和悬浮窗；远程电脑页不参与。 */
  function notifyPageTask(running) {
    try {
      if (!document.querySelector('meta[name="workbench-phone"]')) return;
      if (window.AndroidDownloads && AndroidDownloads.taskState) {
        AndroidDownloads.taskState(JSON.stringify({
          running: !!running,
          title: (S.conv && S.conv.title) || '',
          id: S.convId || ''
        }));
      }
    } catch { /* 非 App 环境 */ }
  }

  /** 远程电脑页显示「电脑端」，手机本机页显示「手机端」；列表和对话页都标上。 */
  function ensureModeBadge() {
    // 手机本机页的 m.html 由 APK 构建脚本注入 workbench-phone；远程电脑页没有这个标记。
    // 不能按 127.0.0.1 判断：App 的内置 Tailscale 代理也是 127.0.0.1:4720。
    const local = !!document.querySelector('meta[name="workbench-phone"]');
    const label = local ? '手机端' : '电脑端';
    const cls = 'mode-badge' + (local ? '' : ' pc');
    const make = () => {
      const badge = document.createElement('span');
      badge.className = cls;
      badge.textContent = label;
      return badge;
    };
    const listHead = document.querySelector('#listScreen .hd-left');
    if (listHead && !document.getElementById('phoneModeBadge') && !document.getElementById('pcModeBadge')) {
      const badge = make();
      badge.id = local ? 'phoneModeBadge' : 'pcModeBadge';
      const h = listHead.querySelector('h1');
      if (h) h.appendChild(badge);
    }
    const chatTitle = document.getElementById('chatTitle');
    if (chatTitle) {
      let chatBadge = document.getElementById('chatModeBadge');
      if (!chatBadge) {
        chatBadge = make();
        chatBadge.id = 'chatModeBadge';
        chatTitle.insertAdjacentElement('afterend', chatBadge);
      }
      chatBadge.className = cls;
      chatBadge.textContent = label;
      chatBadge.hidden = false;
    }
  }

  async function init() {
    if (!TOKEN) {
      document.body.innerHTML = '<div style="padding:40px;color:#e8e6e3;font:15px system-ui">缺少访问令牌，请重新用带 token 的地址打开。</div>';
      return;
    }
    bind();
    ensureModeBadge();
    loadFolderCollapsed();
    loadFontScale();
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

  /** App 原生返回键统一入口：先关最上面的弹层，再退对话，最后才退页面。 */
  window.__workbenchBack = () => {
    if (sheetStack.length) {
      closeTopSheetFromHistory();
      return 'handled';
    }
    const open = [...document.querySelectorAll('.sheet')].filter(e => !e.hidden);
    if (open.length) {
      const el = open[open.length - 1];
      if (el.id === 'imgViewer') closeImageViewer();
      else el.hidden = true;
      return 'handled';
    }
    const viewer = document.getElementById('imgViewer');
    if (viewer && !viewer.hidden) {
      closeImageViewer();
      return 'handled';
    }
    const chat = document.getElementById('chatScreen');
    const back = document.getElementById('backBtn');
    if (chat && !chat.hidden && back) {
      back.click();
      return 'handled';
    }
    return '';
  };

  // 调试入口：控制台里可以看状态；不影响正常使用
  window.__m = S;

  init();
})();

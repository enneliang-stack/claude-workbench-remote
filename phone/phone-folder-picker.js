/* 手机端专属：新对话面板里的“浏览手机文件夹”选择器。 */
(function () {
  'use strict';
  if (window.__phoneFolderPicker) return;
  window.__phoneFolderPicker = true;

  var TOKEN = window.__GUI_TOKEN__ || '';
  var current = '/sdcard';
  var ui = null;
  var toastEl = null;
  var toastTimer = 0;

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function toast(msg) {
    if (!toastEl) {
      toastEl = document.createElement('div');
      toastEl.id = 'phoneFolderToast';
      toastEl.style.cssText = 'position:fixed;left:50%;bottom:88px;transform:translateX(-50%);'
        + 'z-index:10002;width:max-content;max-width:86vw;padding:9px 14px;border-radius:11px;'
        + 'background:#222932;color:#e8e6e3;font-size:12.5px;text-align:center;'
        + 'box-shadow:0 10px 30px rgba(0,0,0,.5);pointer-events:none;';
      document.body.appendChild(toastEl);
    }
    toastEl.textContent = msg;
    toastEl.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.hidden = true; }, 2200);
  }

  function apiFs(path) {
    return fetch('/api/fs?files=0&path=' + encodeURIComponent(path), {
      headers: { 'x-gui-token': TOKEN }
    }).then(function (r) { return r.json(); });
  }

  function ensureUi() {
    if (ui) return ui;
    var box = document.createElement('div');
    box.id = 'phoneFolderPicker';
    box.hidden = true;
    box.style.cssText = 'position:fixed;inset:0;z-index:10000;background:rgba(11,13,16,.98);'
      + 'display:flex;flex-direction:column;overflow:hidden;';

    var hd = document.createElement('div');
    hd.style.cssText = 'display:flex;align-items:center;gap:10px;padding:14px 16px 8px;flex:none;';
    var close = document.createElement('button');
    close.type = 'button';
    close.textContent = '×';
    close.style.cssText = 'width:36px;height:36px;border-radius:18px;border:1px solid #2a323c;'
      + 'background:#151a21;color:#e8e6e3;font-size:20px;';
    close.onclick = function () { closePicker(); };
    var title = document.createElement('b');
    title.textContent = '选择手机文件夹';
    title.style.cssText = 'flex:1;color:#e8e6e3;font-size:17px;white-space:nowrap;';
    hd.appendChild(close); hd.appendChild(title);

    var actions = document.createElement('div');
    actions.style.cssText = 'display:flex;gap:10px;padding:0 16px 12px;'
      + 'border-bottom:1px solid #262e38;flex:none;';
    var newFolder = document.createElement('button');
    newFolder.type = 'button';
    newFolder.textContent = '＋ 新建文件夹';
    newFolder.style.cssText = 'flex:1;padding:10px 12px;border-radius:10px;border:1px solid #4a3324;'
      + 'background:#2a2118;color:#f0a35e;font-size:13px;font-weight:600;';
    newFolder.onclick = function () { openCreateFolder(); };
    var choose = document.createElement('button');
    choose.type = 'button';
    choose.textContent = '选择此文件夹';
    choose.style.cssText = 'flex:1;padding:10px 12px;border-radius:10px;border:1px solid #f0a35e;'
      + 'background:#2a2118;color:#f0a35e;font-size:13px;font-weight:600;';
    choose.onclick = function () { chooseCurrent(); };
    actions.appendChild(newFolder); actions.appendChild(choose);

    var path = document.createElement('div');
    path.style.cssText = 'padding:8px 16px 4px;color:#9aa3ad;font-size:12px;'
      + 'word-break:break-all;flex:none;';

    var quick = document.createElement('div');
    quick.style.cssText = 'display:flex;gap:8px;padding:4px 16px 8px;flex-wrap:wrap;flex:none;';
    [['手机存储', '/sdcard'], ['下载', '/sdcard/Download'], ['文档', '/sdcard/Documents'], ['工作区', '/workspace']]
      .forEach(function (q) {
        var b = document.createElement('button');
        b.type = 'button';
        b.textContent = q[0];
        b.style.cssText = 'padding:6px 10px;border-radius:8px;border:1px solid #2a323c;'
          + 'background:#151a21;color:#9aa3ad;font-size:12px;';
        b.onclick = function () { load(q[1]); };
        quick.appendChild(b);
      });

    var hint = document.createElement('div');
    hint.style.cssText = 'padding:0 16px 8px;color:#6e7680;font-size:11px;flex:none;';
    hint.textContent = '长按 APP 新建的文件夹可删除';

    var list = document.createElement('div');
    list.style.cssText = 'flex:1;overflow:auto;padding:0 16px 24px;-webkit-overflow-scrolling:touch;';

    var note = document.createElement('div');
    note.style.cssText = 'padding:8px 16px 14px;color:#6e7680;font-size:11px;line-height:1.5;flex:none;';
    note.textContent = '看不到手机公共文件夹？回主页 →「设置与日志」→「手机存储：授权全盘访问」，授权后重新启动手机端。';

    box.appendChild(hd); box.appendChild(actions); box.appendChild(path); box.appendChild(quick);
    box.appendChild(hint); box.appendChild(list); box.appendChild(note);
    document.body.appendChild(box);
    ui = { box: box, path: path, list: list };
    return ui;
  }

  function row(name, path, extra, created, longPress) {
    var attrs = '';
    if (longPress) attrs += ' data-longpress="1"';
    if (created) attrs += ' data-created="1"';
    return '<div data-path="' + esc(path) + '"' + attrs
      + ' style="display:flex;align-items:center;justify-content:space-between;'
      + 'padding:13px 4px;border-bottom:1px solid #20262e;color:#e8e6e3;font-size:15px;'
      + 'cursor:pointer;user-select:none;-webkit-user-select:none;-webkit-touch-callout:none;">'
      + '<span>' + esc(name) + '</span><span style="color:#6e7680;font-size:12px;">'
      + esc(extra || '') + '</span></div>';
  }

  function bindRow(el, path, longPress, created) {
    var timer = 0;
    var sx = 0;
    var sy = 0;
    var longPressed = false;
    function cancelPress() {
      if (timer) {
        clearTimeout(timer);
        timer = 0;
      }
    }
    if (longPress) {
      el.addEventListener('pointerdown', function (e) {
        if (e.pointerType === 'mouse' && e.button !== 0) return;
        sx = e.clientX;
        sy = e.clientY;
        longPressed = false;
        cancelPress();
        timer = setTimeout(function () {
          timer = 0;
          longPressed = true;
          askDeleteFolder(path, created);
        }, 650);
      });
      el.addEventListener('pointermove', function (e) {
        if (!timer) return;
        if (Math.abs(e.clientX - sx) > 10 || Math.abs(e.clientY - sy) > 10) cancelPress();
      });
      el.addEventListener('pointerup', cancelPress);
      el.addEventListener('pointercancel', cancelPress);
      el.addEventListener('pointerleave', cancelPress);
      el.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    }
    el.addEventListener('click', function (e) {
      if (longPressed) {
        longPressed = false;
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      load(path);
    });
  }

  function load(path) {
    var u = ensureUi();
    u.box.hidden = false;
    u.box.style.display = 'flex';
    u.list.innerHTML = '<div style="padding:24px;color:#9aa3ad;">读取中…</div>';
    apiFs(path).then(function (d) {
      if (!d || d.error) {
        u.list.innerHTML = '<div style="padding:24px;color:#e0766b;">读取失败：'
          + esc((d && d.error) || '未知错误') + '</div>';
        return;
      }
      current = d.path || path;
      u.path.textContent = current;
      var html = '';
      if (d.parent) html += row('⬆ 上一级', d.parent, '', false, false);
      (d.entries || []).forEach(function (e) {
        if (e && e.isDir) html += row(e.name, e.path, '', !!e.created, true);
      });
      if (!(d.entries || []).some(function (e) { return e && e.isDir; })) {
        html += '<div style="padding:20px;color:#6e7680;">这个文件夹没有子文件夹</div>';
      }
      u.list.innerHTML = html;
      Array.prototype.forEach.call(u.list.querySelectorAll('[data-path]'), function (el) {
        bindRow(
          el,
          el.getAttribute('data-path'),
          el.getAttribute('data-longpress') === '1',
          el.getAttribute('data-created') === '1'
        );
      });
    }).catch(function (e) {
      u.list.innerHTML = '<div style="padding:24px;color:#e0766b;">读取失败：'
        + esc(e && e.message) + '</div>';
    });
  }

  function closePicker() {
    if (!ui) return;
    ui.box.hidden = true;
    ui.box.style.display = 'none';
    if (toastEl) toastEl.hidden = true;
  }

  function askDeleteFolder(folderPath, created) {
    if (!created) {
      toast('只能删除通过 APP 新建的文件夹');
      return;
    }
    if (!folderPath || folderPath === '/sdcard' || folderPath === '/workspace') {
      alert('不能删除手机存储或工作区的根目录');
      return;
    }
    if (!confirm('删除文件夹？\n' + folderPath + '\n\n里面的文件、子文件夹和相关对话会一起删除，无法恢复。')) return;
    fetch('/api/fs/delete', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-gui-token': TOKEN },
      body: JSON.stringify({ path: folderPath })
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (!d || !d.ok) {
        alert('删除失败：' + ((d && d.error) || '未知错误'));
        return;
      }
      var n = Number(d.removedConversations) || 0;
      toast(n ? '文件夹和 ' + n + ' 个对话已删除' : '文件夹已删除');
      if (folderPath === current) {
        var parent = folderPath.replace(/\/[^/]+\/?$/, '') || '/';
        load(parent);
      } else {
        load(current);
      }
    }).catch(function (e) {
      alert('删除失败：' + (e && e.message));
    });
  }

  function openCreateFolder() {
    if (!current) return;
    closeCreateFolder();
    var mask = document.createElement('div');
    mask.id = 'phoneFolderNewSheet';
    mask.style.cssText = 'position:fixed;inset:0;z-index:10001;background:rgba(0,0,0,.58);'
      + 'display:flex;align-items:center;justify-content:center;padding:24px;';
    var card = document.createElement('div');
    card.style.cssText = 'width:100%;max-width:360px;background:#151a21;border:1px solid #2a323c;'
      + 'border-radius:16px;padding:18px;box-shadow:0 18px 50px rgba(0,0,0,.55);';
    card.innerHTML = '<b style="font-size:16px;color:#e8e6e3;">新建文件夹</b>'
      + '<div style="margin:8px 0 14px;color:#9aa3ad;font-size:11.5px;'
      + 'word-break:break-all;font-family:ui-monospace,Consolas,monospace;">' + esc(current) + '</div>'
      + '<input id="phoneNewFolderName" type="text" placeholder="文件夹名称" maxlength="80"'
      + ' autocomplete="off" spellcheck="false" enterkeyhint="done"'
      + ' style="width:100%;box-sizing:border-box;border:1px solid #2a323c;background:#0b0d10;'
      + 'border-radius:10px;padding:10px 12px;color:#e8e6e3;font-size:16px;outline:none;">'
      + '<div id="phoneNewFolderErr" style="display:none;color:#e0766b;font-size:11.5px;margin-top:8px;"></div>'
      + '<div style="display:flex;gap:10px;margin-top:14px;">'
      + '<button id="phoneNewFolderCancel" type="button" style="flex:1;padding:11px;border-radius:10px;'
      + 'border:1px solid #2a323c;background:#1a1f27;color:#e8e6e3;font-size:14px;font-weight:600;">取消</button>'
      + '<button id="phoneNewFolderOk" type="button" style="flex:1;padding:11px;border-radius:10px;'
      + 'border:1px solid #f0a35e;background:#2a2118;color:#f0a35e;font-size:14px;font-weight:600;">创建</button>'
      + '</div>';
    mask.appendChild(card);
    document.body.appendChild(mask);
    mask.addEventListener('click', function (e) {
      if (e.target === mask) closeCreateFolder();
    });
    document.getElementById('phoneNewFolderCancel').onclick = closeCreateFolder;
    var ok = document.getElementById('phoneNewFolderOk');
    ok.disabled = true;
    ok.style.opacity = '.45';
    ok.onclick = createFolder;
    var input = document.getElementById('phoneNewFolderName');
    input.addEventListener('input', function () {
      document.getElementById('phoneNewFolderErr').style.display = 'none';
      var ready = !!input.value.trim();
      ok.disabled = !ready;
      ok.style.opacity = ready ? '1' : '.45';
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        createFolder();
      }
    });
    setTimeout(function () { try { input.focus(); } catch (e) { /* ignore */ } }, 60);
  }

  function closeCreateFolder() {
    var mask = document.getElementById('phoneFolderNewSheet');
    if (mask && mask.parentNode) mask.parentNode.removeChild(mask);
  }

  function createFolder() {
    var input = document.getElementById('phoneNewFolderName');
    var err = document.getElementById('phoneNewFolderErr');
    var ok = document.getElementById('phoneNewFolderOk');
    if (!input || !err || !ok) return;
    var name = (input.value || '').trim();
    if (!name) {
      err.textContent = '请输入文件夹名称';
      err.style.display = 'block';
      return;
    }
    if (ok.disabled) return;
    ok.disabled = true;
    ok.style.opacity = '.45';
    var parent = current;
    fetch('/api/fs/mkdir', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-gui-token': TOKEN },
      body: JSON.stringify({ path: parent, name: name })
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (!d || !d.ok) {
        ok.disabled = false;
        ok.style.opacity = '1';
        err.textContent = (d && d.error) || '创建失败';
        err.style.display = 'block';
        return;
      }
      closeCreateFolder();
      load(d.path || parent);
    }).catch(function (e) {
      ok.disabled = false;
      ok.style.opacity = '1';
      err.textContent = '创建失败：' + (e && e.message);
      err.style.display = 'block';
    });
  }

  function chooseCurrent() {
    var input = document.getElementById('pathInput');
    var go = document.getElementById('pathGo');
    if (!input || !go) {
      alert('新对话面板还没准备好，请先点右上角 +');
      return;
    }
    input.value = current;
    try { input.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) { /* ignore */ }
    closePicker();
    setTimeout(function () { go.click(); }, 60);
  }

  function addButton() {
    var row = document.querySelector('#sheet .path-row');
    if (!row || document.getElementById('phoneFolderBrowse')) return;
    var b = document.createElement('button');
    b.id = 'phoneFolderBrowse';
    b.type = 'button';
    b.className = 'mini-btn';
    b.textContent = '浏览手机文件夹';
    b.style.cssText = 'margin-top:8px;width:100%;';
    b.onclick = function (e) { e.preventDefault(); load('/sdcard'); };
    row.insertAdjacentElement('afterend', b);
  }

  function boot() {
    addButton();
    setInterval(addButton, 1500);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();

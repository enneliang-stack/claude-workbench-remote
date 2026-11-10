/* 手机端专属：新对话面板里的“浏览手机文件夹”选择器。 */
(function () {
  'use strict';
  if (window.__phoneFolderPicker) return;
  window.__phoneFolderPicker = true;

  var TOKEN = window.__GUI_TOKEN__ || '';
  var current = '/sdcard';
  var ui = null;

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
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
    hd.style.cssText = 'display:flex;align-items:center;gap:10px;padding:14px 16px;'
      + 'border-bottom:1px solid #262e38;flex:none;';
    var close = document.createElement('button');
    close.type = 'button';
    close.textContent = '×';
    close.style.cssText = 'width:36px;height:36px;border-radius:18px;border:1px solid #2a323c;'
      + 'background:#151a21;color:#e8e6e3;font-size:20px;';
    close.onclick = function () { closePicker(); };
    var title = document.createElement('b');
    title.textContent = '选择手机文件夹';
    title.style.cssText = 'flex:1;color:#e8e6e3;font-size:17px;';
    var choose = document.createElement('button');
    choose.type = 'button';
    choose.textContent = '选择此文件夹';
    choose.style.cssText = 'padding:8px 12px;border-radius:10px;border:1px solid #f0a35e;'
      + 'background:#2a2118;color:#f0a35e;font-size:13px;';
    choose.onclick = function () { chooseCurrent(); };
    var del = document.createElement('button');
    del.type = 'button';
    del.textContent = '删除';
    del.style.cssText = 'padding:8px 10px;border-radius:10px;border:1px solid #6b3535;'
      + 'background:#2a1717;color:#e0766b;font-size:12px;';
    del.onclick = function () { deleteCurrent(); };
    hd.appendChild(close); hd.appendChild(title); hd.appendChild(del); hd.appendChild(choose);

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

    var list = document.createElement('div');
    list.style.cssText = 'flex:1;overflow:auto;padding:0 16px 24px;-webkit-overflow-scrolling:touch;';

    var note = document.createElement('div');
    note.style.cssText = 'padding:8px 16px 14px;color:#6e7680;font-size:11px;line-height:1.5;flex:none;';
    note.textContent = '看不到手机公共文件夹？回主页 →「设置与日志」→「手机存储：授权全盘访问」，授权后重新启动手机端。';

    box.appendChild(hd); box.appendChild(path); box.appendChild(quick);
    box.appendChild(list); box.appendChild(note);
    document.body.appendChild(box);
    ui = { box: box, path: path, list: list };
    return ui;
  }

  function row(name, path, extra) {
    return '<div data-path="' + esc(path) + '" style="display:flex;align-items:center;'
      + 'justify-content:space-between;padding:13px 4px;border-bottom:1px solid #20262e;'
      + 'color:#e8e6e3;font-size:15px;cursor:pointer;">'
      + '<span>' + esc(name) + '</span><span style="color:#6e7680;font-size:12px;">'
      + esc(extra || '') + '</span></div>';
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
      if (d.parent) html += row('⬆ 上一级', d.parent, '');
      (d.entries || []).forEach(function (e) {
        if (e && e.isDir) html += row(e.name, e.path, '');
      });
      if (!(d.entries || []).some(function (e) { return e && e.isDir; })) {
        html += '<div style="padding:20px;color:#6e7680;">这个文件夹没有子文件夹</div>';
      }
      u.list.innerHTML = html;
      Array.prototype.forEach.call(u.list.querySelectorAll('[data-path]'), function (el) {
        el.onclick = function () { load(el.getAttribute('data-path')); };
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
  }

  function deleteCurrent() {
    var path = current;
    if (!path || path === '/sdcard' || path === '/workspace') {
      alert('不能删除手机存储或工作区的根目录');
      return;
    }
    if (!confirm('删除文件夹？\n' + path + '\n\n里面的文件会一起删除，无法恢复。')) return;
    fetch('/api/fs/delete', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-gui-token': TOKEN },
      body: JSON.stringify({ path: path })
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (!d || !d.ok) {
        alert('删除失败：' + ((d && d.error) || '未知错误'));
        return;
      }
      var parent = path.replace(/\/[^/]+\/?$/, '') || '/';
      load(parent);
    }).catch(function (e) {
      alert('删除失败：' + (e && e.message));
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

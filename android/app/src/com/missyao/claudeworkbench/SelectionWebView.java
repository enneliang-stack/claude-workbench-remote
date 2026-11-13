package com.missyao.claudeworkbench;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * 给 WebView 的系统文字选择菜单补上「引用 / 引用至其他对话」。
 * 网页侧通过 window.__nativeSelectionAction 接管选中文字；没有网页处理的页面不受影响。
 *
 * 输入框等可编辑区域使用 App 自己的四项菜单（复制 → 剪切 → 粘贴 → 全选）并自行执行动作：
 * WebView 的系统菜单项一旦 clear() 后重新 add，点击就不会再触发原生动作，所以不能重建系统项。
 */
public class SelectionWebView extends WebView {
    private static final int MENU_QUOTE = 0x5E1101;
    private static final int MENU_QUOTE_OTHER = 0x5E1102;
    private static final int MENU_PASTE = 0x5E1103;
    private static final int MENU_COPY = 0x5E1104;
    private static final int MENU_CUT = 0x5E1105;
    private static final int MENU_SELECT_ALL = 0x5E1106;

    public SelectionWebView(Context context) {
        super(context);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        final ActionMode.Callback wrapped = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                boolean ok = callback.onCreateActionMode(mode, menu);
                if (!ok) return false;
                if (isEditableMenu(menu)) {
                    rebuildEditableMenu(menu);
                    return true;
                }
                rebuildMenu(menu);
                evaluateJavascript("window.__nativeSelectionMenu=true", null);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                callback.onPrepareActionMode(mode, menu);
                if (isEditableMenu(menu)) {
                    rebuildEditableMenu(menu);
                    return true;
                }
                // WebView 的 onPrepare 会重新补系统项，必须在这里再补一次引用项
                rebuildMenu(menu);
                evaluateJavascript("window.__nativeSelectionMenu=true", null);
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId();
                if (id == MENU_QUOTE) {
                    runSelectionAction("quote");
                    finishLater(mode);
                    return true;
                }
                if (id == MENU_QUOTE_OTHER) {
                    runSelectionAction("quoteOther");
                    finishLater(mode);
                    return true;
                }
                if (id == MENU_PASTE) {
                    cancelSelectAllRetries();
                    pasteIntoEditable();
                    finishLater(mode);
                    return true;
                }
                if (id == MENU_COPY) {
                    cancelSelectAllRetries();
                    copyEditableSelection(mode, false);
                    return true;
                }
                if (id == MENU_CUT) {
                    cancelSelectAllRetries();
                    copyEditableSelection(mode, true);
                    return true;
                }
                if (id == MENU_SELECT_ALL) {
                    // 不能结束 ActionMode，也不刷新菜单：空光标下全选是网页侧
                    // 建立的选区，系统刷新/结束菜单会把选区清掉（表现为闪一下消失）
                    selectAllEditable();
                    return true;
                }
                return callback.onActionItemClicked(mode, item);
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                cancelSelectAllRetries();
                callback.onDestroyActionMode(mode);
                evaluateJavascript("window.__nativeSelectionMenu=false", null);
            }
        };
        // 有些系统把文字选择当成 PRIMARY，菜单会贴到屏幕最上方；
        // 文字选择用 FLOATING 才会跟随选区显示。
        int useType = type == ActionMode.TYPE_PRIMARY ? ActionMode.TYPE_FLOATING : type;
        return super.startActionMode(wrapped, useType);
    }

    /**
     * 输入框/可编辑区域用 App 自己的菜单，只根据系统给了哪些项判断：
     * - 空菜单：空输入框长按，按输入框处理；
     * - 有剪切/粘贴：可编辑区域；
     * - 只有全选、没有复制：光标停在输入框里、没选文字；
     * - 有复制：对话消息选字。
     */
    private boolean isEditableMenu(Menu menu) {
        if (menu.size() == 0) return true;
        boolean hasCopy = false;
        boolean hasSelectAll = false;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            int id = item.getItemId();
            // 忽略本类上一轮加进去的项，只根据系统原始菜单判断
            if (id == MENU_PASTE || id == MENU_COPY || id == MENU_CUT
                    || id == MENU_SELECT_ALL || id == MENU_QUOTE
                    || id == MENU_QUOTE_OTHER) {
                continue;
            }
            MenuItemInfo info = new MenuItemInfo(item);
            if (info.isCut() || info.isPaste()) return true;
            if (info.isCopy()) hasCopy = true;
            if (info.isSelectAll()) hasSelectAll = true;
        }
        return hasSelectAll && !hasCopy;
    }

    /** 输入框菜单：复制 → 剪切 → 粘贴 → 全选，点击全部由本类执行。 */
    private static void rebuildEditableMenu(Menu menu) {
        boolean hasCopy = false;
        boolean hasCut = false;
        boolean hasSelectAll = false;
        for (int i = 0; i < menu.size(); i++) {
            MenuItemInfo info = new MenuItemInfo(menu.getItem(i));
            if (info.isCopy()) hasCopy = true;
            if (info.isCut()) hasCut = true;
            if (info.isSelectAll()) hasSelectAll = true;
        }
        // 输入框里有文字时四项都保留：光标没选中文字时点全选后，
        // 菜单里已经有「复制/剪切」，不需要再刷新菜单就能继续操作
        boolean hasText = hasCopy || hasCut || hasSelectAll;
        menu.clear();
        if (hasText) {
            menu.add(Menu.NONE, MENU_COPY, 0, "复制");
            menu.add(Menu.NONE, MENU_CUT, 1, "剪切");
        }
        menu.add(Menu.NONE, MENU_PASTE, 2, "粘贴");
        if (hasSelectAll) menu.add(Menu.NONE, MENU_SELECT_ALL, 3, "全选");
    }

    /**
     * 消息选字菜单：只保留系统自带的「复制」（点它才是原生复制），
     * 其余系统项（全选/分享/网页搜索等）去掉，再追加引用项。
     * 这样菜单正好三项，引用不会被挤到右侧第二页。
     */
    private static void rebuildMenu(Menu menu) {
        for (int i = menu.size() - 1; i >= 0; i--) {
            MenuItem item = menu.getItem(i);
            int id = item.getItemId();
            if (id == MENU_QUOTE || id == MENU_QUOTE_OTHER) continue;
            MenuItemInfo info = new MenuItemInfo(item);
            if (!info.isCopy()) menu.removeItem(id);
        }
        if (menu.findItem(MENU_QUOTE) == null) {
            // 放最前面，保证引用项在第一页；标签用短名，避免被挤到右侧更多页
            menu.add(Menu.NONE, MENU_QUOTE, -2, "引用");
            menu.add(Menu.NONE, MENU_QUOTE_OTHER, -1, "引用至其他");
        }
    }

    private static final class MenuItemInfo {
        final int id;
        final CharSequence title;
        private final String lower;

        MenuItemInfo(MenuItem item) {
            this.id = item.getItemId();
            this.title = item.getTitle();
            this.lower = String.valueOf(title).toLowerCase();
        }

        boolean isCopy() {
            return id == android.R.id.copy || lower.contains("复制") || lower.contains("copy");
        }

        boolean isCut() {
            return id == android.R.id.cut || lower.contains("剪切") || lower.contains("cut");
        }

        boolean isPaste() {
            return id == android.R.id.paste || lower.contains("粘贴") || lower.contains("paste");
        }

        boolean isSelectAll() {
            return id == android.R.id.selectAll || lower.contains("全选")
                    || lower.contains("select all") || lower.contains("selectall");
        }

        boolean isWebSearchOrShare() {
            return lower.contains("网页搜索") || lower.contains("web search")
                    || lower.contains("分享") || lower.contains("share");
        }
    }

    private void runSelectionAction(String action) {
        evaluateJavascript(
                "window.__nativeSelectionAction && window.__nativeSelectionAction('" + action + "')",
                null);
    }

    // ---------------------------------------------------------------- 输入框动作

    private static final String JS_GET_SELECTION =
            "(function(){var el=document.activeElement;if(el){var t=(el.tagName||'').toLowerCase();"
            + "if(t==='textarea'||t==='input'){var s=el.selectionStart==null?0:el.selectionStart;"
            + "var e=el.selectionEnd==null?s:el.selectionEnd;return el.value.slice(s,e);}}"
            + "return window.getSelection?String(window.getSelection()):'';})()";

    private static final String JS_CUT_SELECTION =
            "(function(){var el=document.activeElement;if(!el)return '';"
            + "var t=(el.tagName||'').toLowerCase();"
            + "if(t==='textarea'||t==='input'){var s=el.selectionStart==null?0:el.selectionStart;"
            + "var e=el.selectionEnd==null?s:el.selectionEnd;if(e<=s)return '';"
            + "var v=el.value.slice(s,e);el.setRangeText('',s,e,'end');"
            + "el.dispatchEvent(new Event('input',{bubbles:true}));return v;}"
            + "if(document.execCommand){document.execCommand('cut');}return '';})()";

    private static final String JS_SELECT_ALL =
            "(function(){var el=document.activeElement;if(!el)return;"
            + "var t=(el.tagName||'').toLowerCase();"
            + "if(t==='textarea'||t==='input'){try{el.focus();}catch(e){}"
            + "try{if(document.execCommand&&document.execCommand('selectAll'))return;}catch(e){}"
            + "try{el.select();}catch(e){}}"
            + "else if(document.execCommand){document.execCommand('selectAll');}})()";

    private void copyEditableSelection(ActionMode mode, boolean cut) {
        evaluateJavascript(cut ? JS_CUT_SELECTION : JS_GET_SELECTION, value -> {
            String text = decodeJsString(value);
            if (!text.isEmpty()) {
                copyToClipboard(text);
                Toast.makeText(getContext(), cut ? "已剪切" : "已复制", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "没有选中文字", Toast.LENGTH_SHORT).show();
            }
        });
        finishLater(mode);
    }

    private int selectAllGeneration = 0;

    /** 全选后过一会儿再补两次：系统可能在菜单交互后把网页侧选区清掉 */
    private void selectAllEditable() {
        final int gen = ++selectAllGeneration;
        evaluateJavascript(JS_SELECT_ALL, null);
        postDelayed(() -> {
            if (gen == selectAllGeneration) evaluateJavascript(JS_SELECT_ALL, null);
        }, 200);
        postDelayed(() -> {
            if (gen == selectAllGeneration) evaluateJavascript(JS_SELECT_ALL, null);
        }, 600);
    }

    private void cancelSelectAllRetries() {
        selectAllGeneration++;
    }

    /** 系统没提供「粘贴」时自己插入剪贴板文字（空输入框长按用）。 */
    private void pasteIntoEditable() {
        String text = readClipboardText();
        if (text == null || text.isEmpty()) {
            evaluateJavascript("window.__nativeToast && window.__nativeToast('剪贴板没有文字')", null);
            return;
        }
        evaluateJavascript(
                "window.__nativePasteText && window.__nativePasteText(" + JSONObject.quote(text) + ")",
                null);
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("文本", text));
        } catch (Exception ignored) {
            // 剪贴板不可用时保持静默，菜单本身已经收起
        }
    }

    private String readClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            ClipData.Item item = clip.getItemAt(0);
            CharSequence text = item.getText();
            if (text == null && clip.getDescription() != null) {
                String mime = clip.getDescription().getMimeType(0);
                if (mime != null && mime.startsWith("text/")) {
                    text = item.coerceToText(getContext());
                }
            }
            return text == null ? null : text.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** evaluateJavascript 返回的是 JSON 字符串字面量，这里还原成普通文本。 */
    private static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return "";
        try {
            Object v = new JSONTokener(value).nextValue();
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    /** 等网页拿到选中文字后再结束 ActionMode，否则系统可能先把选区清掉。 */
    private void finishLater(ActionMode mode) {
        postDelayed(() -> {
            try { mode.finish(); } catch (Exception ignored) { }
        }, 500);
    }
}

package com.missyao.claudeworkbench;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 给 WebView 的系统文字选择菜单补上「引用 / 引用至其他对话」。
 * 网页侧通过 window.__nativeSelectionAction 接管选中文字；没有网页处理的页面不受影响。
 * 输入框等可编辑区域保留系统菜单并统一顺序：复制 → 剪切 → 粘贴 → 全选；
 * 没有选中文字（空输入框）时也补一个「粘贴」，避免长按没有任何反应。
 */
public class SelectionWebView extends WebView {
    private static final int MENU_QUOTE = 0x5E1101;
    private static final int MENU_QUOTE_OTHER = 0x5E1102;
    private static final int MENU_PASTE = 0x5E1103;

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
                // WebView 的 onPrepare 会重新补系统项，必须在这里再排一次序
                rebuildMenu(menu);
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
                    pasteIntoEditable();
                    finishLater(mode);
                    return true;
                }
                return callback.onActionItemClicked(mode, item);
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
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
     * 输入框/可编辑区域用系统菜单（只重排、不删除、不加引用项）。
     * 只根据系统给了哪些菜单项判断，避免网页状态和系统菜单创建时机不同步：
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
            MenuItemInfo info = new MenuItemInfo(menu.getItem(i));
            if (info.isCut() || info.isPaste()) return true;
            if (info.isCopy()) hasCopy = true;
            if (info.isSelectAll()) hasSelectAll = true;
        }
        return hasSelectAll && !hasCopy;
    }

    /** 输入框菜单：复制 → 剪切 → 粘贴 → 全选 → 其余；系统没给「粘贴」时补一个。 */
    private static void rebuildEditableMenu(Menu menu) {
        List<MenuItemInfo> keep = new ArrayList<>();
        boolean hasPaste = false;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            int id = item.getItemId();
            if (id == MENU_PASTE || id == MENU_QUOTE || id == MENU_QUOTE_OTHER) continue;
            MenuItemInfo info = new MenuItemInfo(item);
            keep.add(info);
            if (info.isPaste()) hasPaste = true;
        }
        menu.clear();
        for (int rank = 0; rank <= 3; rank++) {
            for (MenuItemInfo info : keep) {
                if (info.rank() == rank) addItem(menu, info, rank);
            }
            if (rank == 2 && !hasPaste) {
                menu.add(Menu.NONE, MENU_PASTE, 2, "粘贴");
            }
        }
        int order = 100;
        for (MenuItemInfo info : keep) {
            if (info.rank() < 0) addItem(menu, info, order++);
        }
    }

    /** 系统项 + 引用项重新排：复制 → 引用 → 引用至其他对话 → 其余。 */
    private static void rebuildMenu(Menu menu) {
        List<MenuItemInfo> keep = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            int id = item.getItemId();
            if (id == MENU_QUOTE || id == MENU_QUOTE_OTHER || id == MENU_PASTE) continue;
            MenuItemInfo info = new MenuItemInfo(item);
            if (info.isWebSearchOrShare()) continue;
            keep.add(info);
        }
        menu.clear();
        for (MenuItemInfo info : keep) {
            if (info.isCopy()) addItem(menu, info, 0);
        }
        menu.add(Menu.NONE, MENU_QUOTE, 1, "引用");
        menu.add(Menu.NONE, MENU_QUOTE_OTHER, 2, "引用至其他对话");
        int order = 100;
        for (MenuItemInfo info : keep) {
            if (info.isCopy()) continue;
            addItem(menu, info, order++);
        }
    }

    private static final class MenuItemInfo {
        final int id;
        final CharSequence title;
        final boolean enabled;
        final boolean visible;
        final Drawable icon;
        private final String lower;

        MenuItemInfo(MenuItem item) {
            this.id = item.getItemId();
            this.title = item.getTitle();
            this.enabled = item.isEnabled();
            this.visible = item.isVisible();
            this.icon = item.getIcon();
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

        /** 0 复制，1 剪切，2 粘贴，3 全选，-1 其他 */
        int rank() {
            if (isCopy()) return 0;
            if (isCut()) return 1;
            if (isPaste()) return 2;
            if (isSelectAll()) return 3;
            return -1;
        }
    }

    private static void addItem(Menu menu, MenuItemInfo info, int order) {
        MenuItem item = menu.add(Menu.NONE, info.id, order, info.title);
        item.setEnabled(info.enabled);
        item.setVisible(info.visible);
        if (info.icon != null) item.setIcon(info.icon);
    }

    private void runSelectionAction(String action) {
        evaluateJavascript(
                "window.__nativeSelectionAction && window.__nativeSelectionAction('" + action + "')",
                null);
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

    /** 等网页拿到选中文字后再结束 ActionMode，否则系统可能先把选区清掉。 */
    private void finishLater(ActionMode mode) {
        postDelayed(() -> {
            try { mode.finish(); } catch (Exception ignored) { }
        }, 500);
    }
}

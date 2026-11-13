package com.missyao.claudeworkbench;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * 给 WebView 的系统文字选择菜单补上「引用 / 引用到其他对话」。
 * 网页侧通过 window.__nativeSelectionAction 接管选中文字；没有网页处理的页面不受影响。
 */
public class SelectionWebView extends WebView {
    private static final int MENU_QUOTE = 0x5E1101;
    private static final int MENU_QUOTE_OTHER = 0x5E1102;

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
                rebuildMenu(menu);
                evaluateJavascript("window.__nativeSelectionMenu=true", null);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                callback.onPrepareActionMode(mode, menu);
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

    /** 系统项 + 引用项重新排：复制 → 引用 → 引用到其他对话 → 其余。 */
    private static void rebuildMenu(Menu menu) {
        List<MenuItemInfo> keep = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            int id = item.getItemId();
            if (id == MENU_QUOTE || id == MENU_QUOTE_OTHER) continue;
            String title = String.valueOf(item.getTitle()).toLowerCase();
            if (title.contains("网页搜索") || title.contains("web search")
                    || title.contains("分享") || title.contains("share")) {
                continue;
            }
            keep.add(new MenuItemInfo(item, title));
        }
        menu.clear();
        for (MenuItemInfo info : keep) {
            if (info.isCopy()) addItem(menu, info, 0);
        }
        menu.add(Menu.NONE, MENU_QUOTE, 1, "引用");
        menu.add(Menu.NONE, MENU_QUOTE_OTHER, 2, "引用到其他对话");
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

        MenuItemInfo(MenuItem item, String lower) {
            this.id = item.getItemId();
            this.title = item.getTitle();
            this.enabled = item.isEnabled();
            this.visible = item.isVisible();
            this.icon = item.getIcon();
            this.lower = lower;
        }

        boolean isCopy() {
            return lower.contains("复制") || lower.contains("copy");
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

    /** 等网页拿到选中文字后再结束 ActionMode，否则系统可能先把选区清掉。 */
    private void finishLater(ActionMode mode) {
        postDelayed(() -> {
            try { mode.finish(); } catch (Exception ignored) { }
        }, 500);
    }
}

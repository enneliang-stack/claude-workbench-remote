package com.missyao.claudeworkbench;

import android.webkit.JavascriptInterface;

/**
 * 网页在 touchstart / focusin 时同步「当前是不是输入框」，
 * 让系统选择菜单在输入框里保持复制/剪切/粘贴/全选，不塞引用项。
 */
public class SelectionBridge {
    private final SelectionWebView webView;

    public SelectionBridge(SelectionWebView webView) {
        this.webView = webView;
    }

    @JavascriptInterface
    public void setEditable(boolean editable) {
        webView.setEditableContext(editable);
    }
}

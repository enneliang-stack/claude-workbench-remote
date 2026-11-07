package com.missyao.claudeworkbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends Activity {
    // 通用版：不内置任何人的地址/token。第一次打开让用户粘贴电脑「远程访问.cmd」
    // 打印的地址，保存在本机 SharedPreferences 里。
    private static final String PREFS = "remote";
    private static final String PREF_URL = "url";

    private static final int REQ_FILE = 1001;

    WebView webView;
    private ProgressBar progress;
    private LinearLayout errorView;
    private ValueCallback<Uri[]> fileCallback;
    private int fallbackIndex = 0;
    private String primaryUrl = "";
    private boolean setupShowing = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable loadTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        installCrashHandler();
        try {
            createUi();
        } catch (Throwable t) {
            showCrash(t);
        }
    }

    private void createUi() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(11, 13, 16));
        getWindow().setNavigationBarColor(Color.rgb(11, 13, 16));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 13, 16));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 13, 16));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        webView.addJavascriptInterface(new DownloadsBridge(this), "AndroidDownloads");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 只有真正加载出工作台页面才算成功；WebView 的黑色错误页不会取消超时
                view.evaluateJavascript(
                    "(function(){return !!(document.getElementById('listScreen')||document.getElementById('chatScreen')||document.getElementById('app'));})()",
                    value -> {
                        if ("true".equals(value)) {
                            cancelLoadTimeout();
                            fallbackIndex = 0;
                            progress.setVisibility(View.GONE);
                            errorView.setVisibility(View.GONE);
                        }
                    });
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) tryNextUrl();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    // 图片输入走相册；不带 accept 的输入（任意文件）走文件选择器
                    String[] accept = params.getAcceptTypes();
                    boolean imageOnly = accept != null && accept.length > 0;
                    if (imageOnly) {
                        for (String a : accept) {
                            if (a == null || !a.toLowerCase().startsWith("image/")) {
                                imageOnly = false;
                                break;
                            }
                        }
                    }
                    intent.setType(imageOnly ? "image/*" : "*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("确定", (d, w) -> result.confirm())
                    .setNegativeButton("取消", (d, w) -> result.cancel())
                    .setCancelable(false)
                    .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final android.webkit.JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("知道了", (d, w) -> result.confirm())
                    .setCancelable(false)
                    .show();
                return true;
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(96, 96);
        pp.gravity = Gravity.CENTER;
        root.addView(progress, pp);

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        primaryUrl = prefs.getString(PREF_URL, "").trim();
        if (primaryUrl.isEmpty()) showSetupDialog();
        else loadPrimary();
        // 保留系统状态栏（电量/时间），只去掉浏览器地址栏；
        // 不再在 decor 创建前调用 getInsetsController()，避免部分 ROM 空指针。
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            saveCrash(ex);
            if (def != null) def.uncaughtException(thread, ex);
        });
    }

    private void saveCrash(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            Log.e("ClaudeWorkbench", sw.toString());
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                dir.mkdirs();
                FileWriter w = new FileWriter(new File(dir, "crash.txt"), false);
                w.write(sw.toString());
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 启动失败时把堆栈直接显示在屏幕上，方便手机截图反馈 */
    private void showCrash(Throwable t) {
        saveCrash(t);
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.rgb(11, 13, 16));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 70, 40, 70);

        TextView title = new TextView(this);
        title.setText("Claude 工作台启动失败");
        title.setTextColor(Color.rgb(224, 118, 107));
        title.setTextSize(20);
        box.addView(title);

        TextView tv = new TextView(this);
        tv.setText(sw.toString());
        tv.setTextColor(Color.rgb(232, 230, 227));
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = 20;
        box.addView(tv, tp);

        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = 24;
        box.addView(close, bp);

        sc.addView(box);
        setContentView(sc);
    }

    private boolean handleUrl(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        String targetHost = null;
        try { targetHost = Uri.parse(primaryUrl).getHost(); } catch (Exception ignored) { }
        // 工作台同主机的链接留在 WebView；其它链接交给系统浏览器
        if (targetHost != null && targetHost.equalsIgnoreCase(host)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
    }

    /** 首次启动/重新配置：粘贴电脑上「远程访问.cmd」打印的地址（含 token） */
    private void showSetupDialog() {
        if (setupShowing) return;
        setupShowing = true;
        if (progress != null) progress.setVisibility(View.GONE);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setHint("https://电脑名.xxx.ts.net/m/index.html?app=1&token=...");
        if (primaryUrl != null && !primaryUrl.isEmpty()) input.setText(primaryUrl);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("连接工作台")
            .setMessage("在电脑上双击「远程访问.cmd」，把窗口里打印的手机地址（包含 token）粘贴到这里。\n\n"
                + "家里 Wi-Fi 用 192.168 地址；出门用 Tailscale HTTPS 地址。")
            .setView(wrap)
            .setPositiveButton("连接", null)
            .setNegativeButton("退出", (d, w) -> finish())
            .setCancelable(false)
            .create();
        dialog.setOnDismissListener(d -> setupShowing = false);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText().toString().trim();
            if (raw.isEmpty()) {
                input.setError("请粘贴地址");
                return;
            }
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "https://" + raw;
            Uri u = Uri.parse(raw);
            if (u.getHost() == null) {
                input.setError("地址格式不对");
                return;
            }
            // 只填 host 时补上手机版路径；已有 /m/ 的原样使用
            if (!raw.contains("/m/")) {
                String base = raw;
                String query = "";
                int q = raw.indexOf('?');
                if (q >= 0) {
                    base = raw.substring(0, q);
                    query = raw.substring(q + 1);
                }
                base = base.replaceAll("/+$", "");
                raw = base + "/m/index.html?app=1" + (query.isEmpty() ? "" : "&" + query);
            }
            primaryUrl = raw;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_URL, raw).apply();
            dialog.dismiss();
            loadPrimary();
        });
        input.requestFocus();
    }

    private LinearLayout buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.rgb(11, 13, 16));
        box.setPadding(80, 80, 80, 80);

        TextView title = new TextView(this);
        title.setText("连接不上工作台");
        title.setTextColor(Color.rgb(232, 230, 227));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView note = new TextView(this);
        note.setText("请确认：\n1. 手机 Tailscale 已开启并登录\n2. 如果开了其它 VPN/加速器，Tailscale 无法同时连接，先关掉它\n3. 电脑保持开机联网\n4. 或者手机连回家里的 Wi-Fi");
        note.setTextColor(Color.rgb(154, 163, 173));
        note.setTextSize(14);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = 26;
        box.addView(note, np);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(v -> loadPrimary());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = 34;
        box.addView(retry, bp);

        Button setup = new Button(this);
        setup.setText("重新配置地址");
        setup.setOnClickListener(v -> showSetupDialog());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = 10;
        box.addView(setup, sp);
        return box;
    }

    private void loadPrimary() {
        if (primaryUrl == null || primaryUrl.isEmpty()) {
            showSetupDialog();
            return;
        }
        fallbackIndex = 0;
        cancelLoadTimeout();
        errorView.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        webView.loadUrl(primaryUrl);
        startLoadTimeout();
    }

    /** 通用版只连用户配置的地址；超时/失败直接显示错误页，可重新配置。 */
    private void tryNextUrl() {
        cancelLoadTimeout();
        progress.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
    }

    /** WebView 的黑色错误页不会触发 onPageFinished 成功判断，用超时兜底继续回退 */
    private void startLoadTimeout() {
        cancelLoadTimeout();
        loadTimeout = () -> {
            loadTimeout = null;
            tryNextUrl();
        };
        handler.postDelayed(loadTimeout, 7000);
    }

    private void cancelLoadTimeout() {
        if (loadTimeout != null) {
            handler.removeCallbacks(loadTimeout);
            loadTimeout = null;
        }
    }

    /** 返回键先关页面浮层（看图/确认框/面板），再走 WebView 历史，最后退出 App */
    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
            "(function(){var ids=['imgViewer','confirmStop','confirmDelete','attachSheet','refSheet','chatSettings','sheet'];" +
            "for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(e&&!e.hidden){e.hidden=true;" +
            "if(ids[i]==='imgViewer'){var im=document.getElementById('imgViewerImg');if(im)im.src='';}" +
            "return 'handled';}}return '';})()",
            value -> {
                if (value != null && !value.equals("null") && !value.equals("\"\"")) {
                    return;
                }
                finishBack();
            });
    }

    private void finishBack() {
        if (errorView.getVisibility() == View.VISIBLE) {
            loadPrimary();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        cancelLoadTimeout();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

package com.missyao.claudeworkbench;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    public static final String EXTRA_SHOW_LOG = "show_log";
    public static final String EXTRA_INTERJECT = "interject";
    public static final String EXTRA_CONV_ID = "conv_id";
    private static final String PREFS = "remote";
    private static final String PREF_URL = "url";
    private static final String PREF_URLS = "urls";
    private static final String PREF_LAST_MODE = "last_mode";
    private static final String PREF_PC_RECONNECT = "pc_reconnect";
    private static final String UPDATE_MANIFEST_URL =
        "https://raw.githubusercontent.com/enneliang-stack/claude-workbench-remote/main/apk/update.json";
    private static final String UPDATE_MIRROR_PREFIX = "https://ghfast.top/";

    private static final String HIDE_TIPS_JS =
        "(function(){try{if(document.getElementById('apkHideTips'))return;"
        + "var s=document.createElement('style');s.id='apkHideTips';"
        + "s.textContent='#httpsTip,#installTip{display:none!important}';"
        + "if(document.head)document.head.appendChild(s);}catch(e){}})()";
    /** App 内把页面的「重连」交给原生重新探测/重建 WebView，普通浏览器仍走 reload。 */
    private static final String RECONNECT_JS =
        "(function(){try{function bind(id){var b=document.getElementById(id);if(!b)return;"
        + "b.onclick=function(){try{if(window.AndroidDownloads&&AndroidDownloads.reconnectPc"
        + "&&AndroidDownloads.reconnectPc())return;}catch(e){}location.reload();};}"
        + "bind('reconnectBtn');bind('reconnectBtn2');}catch(e){}})()";
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIFY = 1002;
    private static final int REQ_STORAGE = 1003;
    private static final int REQ_CAPTURE = 1004;

    private static final int BG = Color.rgb(11, 13, 16);
    private static final int CARD = Color.rgb(21, 26, 33);
    private static final int STROKE = Color.rgb(38, 46, 56);
    private static final int FG = Color.rgb(232, 230, 227);
    private static final int MUTED = Color.rgb(154, 163, 173);
    private static final int FAINT = Color.rgb(111, 120, 131);
    private static final int ORANGE = Color.rgb(240, 163, 94);
    private static final int GREEN = Color.rgb(76, 195, 138);
    private static final int RED = Color.rgb(224, 118, 107);
    private static final int BLUE = Color.rgb(110, 168, 254);

    WebView webView;
    private ProgressBar progress;
    private TextView loadingText;
    private LinearLayout errorView;
    private TextView errorTitle;
    private TextView errorNote;
    private LinearLayout homeView;
    private TextView homePcStatus;
    private TextView homePhoneStatus;
    private TextView homeStorageStatus;
    private TextView homeControlStatus;
    private TextView homeOverlayStatus;
    private TextView importantUpdateBadge;
    private JSONObject importantUpdateInfo;
    private volatile boolean importantCheckRunning;
    private long importantCheckAt;

    private ValueCallback<Uri[]> fileCallback;
    private Uri captureUri;
    private File captureFile;
    private final List<String> pcUrls = new ArrayList<>();
    private int pcProbeGeneration = 0;
    private boolean pcProbing = false;
    private String mode = "home"; // home | pc | phone
    private ConnectivityManager.NetworkCallback networkCallback;
    private String lastNetworkKey = "";
    private String pcNetworkKey = "";
    private boolean pcNetworkDirty = false;
    private boolean pcReconnectScheduled = false;
    private boolean pcReconnecting = false;
    private boolean resumed = false;

    private final Runnable homeStatusPoll = new Runnable() {
        @Override
        public void run() {
            if (!"home".equals(mode)) return;
            refreshHomeStatus();
            handler.postDelayed(this, 3000);
        }
    };
    private String currentBaseHost = "";
    private int phonePort = 0;
    private boolean phoneStarting = false;
    private String pendingPhoneConvId = "";
    private boolean setupShowing = false;
    private Dialog installDialog;
    private Dialog settingsDialog;
    private Runnable settingsRefresh;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable loadTimeout;
    private Runnable installPoll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        installCrashHandler();
        try {
            createUi();
            maybePrewarmTailscale();
        } catch (Throwable t) {
            showCrash(t);
        }
    }

    private void createUi() {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        homeView = buildHomeView();
        root.addView(homeView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new SelectionWebView(this);
        webView.setBackgroundColor(BG);
        webView.setVisibility(View.GONE);
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
        // App 更新后清一次 WebView 缓存，避免旧的 m.js/m.css 被缓存住（新页面也带 ?v=）。
        int assetTag = LocalEngine.assetVersion();
        SharedPreferences webPrefs = getSharedPreferences("web", MODE_PRIVATE);
        if (webPrefs.getInt("asset_version", 0) != assetTag) {
            try {
                webView.clearCache(true);
            } catch (Exception ignored) {
            }
            webPrefs.edit().putInt("asset_version", assetTag).apply();
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(HIDE_TIPS_JS, null);
                view.evaluateJavascript(RECONNECT_JS, null);
                view.evaluateJavascript(
                    "(function(){return !!(document.getElementById('listScreen')||document.getElementById('chatScreen')||document.getElementById('app')||document.querySelector('input[name=token]'));})()",
                    value -> {
                        if ("true".equals(value)) {
                            cancelLoadTimeout();
                            progress.setVisibility(View.GONE);
                            errorView.setVisibility(View.GONE);
                        }
                    });
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showLoadError();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                // <input capture>：直接调相机，结果通过私有 content URI 交回 WebView。
                if (params.isCaptureEnabled()) {
                    try {
                        File file = CaptureProvider.newCaptureFile(MainActivity.this);
                        Uri uri = CaptureProvider.uriFor(file);
                        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        capture.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                        capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (capture.resolveActivity(getPackageManager()) == null) {
                            throw new ActivityNotFoundException("no camera");
                        }
                        captureUri = uri;
                        captureFile = file;
                        startActivityForResult(capture, REQ_CAPTURE);
                        return true;
                    } catch (Exception e) {
                        cleanupCapture();
                        // 没有相机应用时退回文件选择器，不让页面卡住。
                    }
                }
                try {
                    Intent intent = params.createIntent();
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
                final boolean[] settled = {false};
                Dialog d = MainActivity.this.showDarkMessage("请确认", null, message,
                    "确定", dlg -> {
                        settled[0] = true;
                        result.confirm();
                        return true;
                    },
                    "取消", dlg -> {
                        settled[0] = true;
                        result.cancel();
                        return true;
                    },
                    null, null, MUTED, false);
                d.setCancelable(false);
                d.setOnDismissListener(dlg -> {
                    if (!settled[0]) result.cancel();
                });
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final android.webkit.JsResult result) {
                final boolean[] settled = {false};
                Dialog d = MainActivity.this.showDarkMessage("提示", null, message,
                    "知道了", dlg -> {
                        settled[0] = true;
                        result.confirm();
                        return true;
                    },
                    null, null, null, null, MUTED, false);
                d.setCancelable(false);
                d.setOnDismissListener(dlg -> {
                    if (!settled[0]) result.confirm();
                });
                return true;
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(96), dp(96));
        pp.gravity = Gravity.CENTER;
        root.addView(progress, pp);
        progress.setVisibility(View.GONE);

        loadingText = text("", 13, MUTED);
        loadingText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.topMargin = dp(120);
        root.addView(loadingText, lp);
        loadingText.setVisibility(View.GONE);

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 主页左下角的重要更新提醒：只有 update.json 标记 important=true 且版本更高时才显示。
        importantUpdateBadge = text("", 12, ORANGE);
        importantUpdateBadge.setTypeface(Typeface.DEFAULT_BOLD);
        importantUpdateBadge.setGravity(Gravity.CENTER_VERTICAL);
        importantUpdateBadge.setPadding(dp(14), dp(9), dp(14), dp(9));
        importantUpdateBadge.setBackground(rounded(CARD, ORANGE, 999));
        importantUpdateBadge.setElevation(dp(5));
        importantUpdateBadge.setClickable(true);
        importantUpdateBadge.setFocusable(true);
        importantUpdateBadge.setVisibility(View.GONE);
        importantUpdateBadge.setOnClickListener(v -> {
            if (importantUpdateInfo != null) checkForUpdates();
        });
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.gravity = Gravity.BOTTOM | Gravity.START;
        badgeLp.leftMargin = dp(18);
        badgeLp.bottomMargin = dp(26);
        root.addView(importantUpdateBadge, badgeLp);

        setContentView(root);
        registerNetworkMonitor();
        loadPcUrls();
        LocalConfig.ensureDefaultEffort(this);
        PhoneControlServer.ensureConfigFile(this);
        showHome();
        refreshHomeStatus();
        boolean reconnectPc = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_PC_RECONNECT, false);
        if (reconnectPc) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_PC_RECONNECT, false).apply();
            handler.postDelayed(this::openPcMode, 300);
        } else if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SHOW_LOG, false)) {
            handler.postDelayed(this::showLogDialog, 500);
        }
        if (!reconnectPc && getIntent() != null) {
            String conv = getIntent().getStringExtra(EXTRA_CONV_ID);
            if (conv != null && !conv.isEmpty()) {
                pendingPhoneConvId = conv;
                handler.postDelayed(this::openPhoneMode, 350);
            } else if (getIntent().getBooleanExtra(EXTRA_INTERJECT, false)) {
                handler.postDelayed(this::openPhoneMode, 350);
            }
        }
    }

    private void registerNetworkMonitor() {
        if (networkCallback != null) return;
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            lastNetworkKey = currentNetworkKey();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    handler.post(MainActivity.this::onNetworkMaybeChanged);
                }
                @Override public void onLost(Network network) {
                    handler.post(MainActivity.this::onNetworkMaybeChanged);
                }
                @Override public void onCapabilitiesChanged(Network network,
                                                            NetworkCapabilities caps) {
                    handler.post(MainActivity.this::onNetworkMaybeChanged);
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
    }

    private String currentNetworkKey() {
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            Network n = cm.getActiveNetwork();
            if (n == null) return "none";
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return "none";
            String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cell"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "eth"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn" : "other";
            return type + ":" + n;
        } catch (Exception e) {
            return "";
        }
    }

    private void onNetworkMaybeChanged() {
        if (isFinishing() || isDestroyed()) return;
        String key = currentNetworkKey();
        if (key.isEmpty() || key.equals(lastNetworkKey)) return;
        lastNetworkKey = key;
        if (!pcNetworkKey.isEmpty() && !key.equals(pcNetworkKey)) {
            // 网络变了，旧 WebView 连接可能还绑在上一张网卡上；下次进电脑端时整体重建。
            pcNetworkDirty = true;
        }
        if (!resumed || !"pc".equals(mode) || "none".equals(key)
                || pcReconnectScheduled || pcReconnecting) {
            return;
        }
        pcReconnectScheduled = true;
        handler.postDelayed(() -> {
            pcReconnectScheduled = false;
            if ("pc".equals(mode) && !isFinishing()) recreateForPcReconnect(true);
        }, 800);
    }

    private void recreateForPcReconnect(boolean resetTailscale) {
        if (isFinishing() || isDestroyed() || pcReconnecting) return;
        pcReconnecting = true;
        final Runnable recreate = () -> {
            if (isFinishing() || isDestroyed()) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_PC_RECONNECT, true).apply();
            pcProbeGeneration++;
            pcProbing = false;
            recreate();
        };
        if (!resetTailscale) {
            recreate.run();
            return;
        }
        new Thread(() -> {
            try {
                LocalEngine.stopTailscaleProxy(MainActivity.this);
            } catch (Exception ignored) {
            }
            runOnUiThread(recreate);
        }, "pc-net-reconnect").start();
    }

    /** 页面里的「重连」在 App 内改走原生重建；返回 false 时页面仍会 location.reload。 */
    boolean reconnectPcFromPage() {
        if (!"pc".equals(mode)) return false;
        handler.post(() -> recreateForPcReconnect(true));
        return true;
    }

    // ---------------------------------------------------------------- 主页

    private LinearLayout buildHomeView() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(22), dp(46), dp(22), dp(26));

        TextView title = text("Claude 工作台", 26, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        TextView sub = text("选择在这台手机上工作，或远程连接电脑", 13, MUTED);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(6);
        page.addView(sub, sp);

        homePcStatus = text("", 12, BLUE);
        page.addView(card("电脑端", "远程连接你的电脑，使用完整仓库和工具链", BLUE,
            homePcStatus, this::openPcMode), marginTop(dp(26)));

        homePhoneStatus = text("", 12, ORANGE);
        page.addView(card("手机端", "直接在这台手机上运行 Claude Code", ORANGE,
            homePhoneStatus, this::openPhoneMode), marginTop(dp(14)));

        page.addView(buildHomeSettingsCard(), marginTop(dp(18)));

        TextView tip = text("手机端不依赖电脑；电脑端需要电脑开机并运行「远程访问」。\n"
            + "手机端首次安装约 500 MB，需要 1.8 GB 以上空间。", 11, Color.rgb(110, 118, 128));
        tip.setLineSpacing(dp(3), 1f);
        page.addView(tip, marginTop(dp(18)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(page, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new LinearLayout(this) {{
            setOrientation(LinearLayout.VERTICAL);
            addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }};
    }

    private TextView homeChip() {
        TextView t = text("", 10, MUTED);
        t.setPadding(dp(6), dp(3), dp(6), dp(3));
        t.setBackground(rounded(0xFF1B2028, STROKE, 999));
        return t;
    }

    private void setStatusChip(TextView v, String label, int color) {
        if (v == null) return;
        String text = "● " + label;
        if (text.contentEquals(v.getText()) && color == v.getCurrentTextColor()) return;
        v.setVisibility(View.VISIBLE);
        v.setText(text);
        v.setTextColor(color);
        v.setBackground(rounded(
            Color.argb(20, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)),
            999));
    }

    private LinearLayout buildHomeSettingsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(12), dp(13));
        card.setBackground(rounded(CARD, STROKE, 16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showPhoneSettings());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("⚙", 18, MUTED);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(0xFF1B2028, STROKE, 12));
        top.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(11), 0, 0, 0);
        TextView title = text("设置与日志", 15, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        main.addView(title);
        TextView sub = text("权限、运行环境与更新", 11, MUTED);
        sub.setPadding(0, dp(2), 0, 0);
        main.addView(sub);
        top.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text("›", 20, FAINT);
        arrow.setPadding(dp(6), 0, 0, 0);
        top.addView(arrow);
        card.addView(top);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        homeStorageStatus = homeChip();
        chips.addView(homeStorageStatus);
        homeControlStatus = homeChip();
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c1.leftMargin = dp(6);
        chips.addView(homeControlStatus, c1);
        homeOverlayStatus = homeChip();
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c2.leftMargin = dp(6);
        chips.addView(homeOverlayStatus, c2);
        card.addView(chips, marginTop(dp(10)));
        return card;
    }

    private LinearLayout card(String title, String subtitle, int accent, TextView status, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(rounded(CARD, STROKE, 18));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> onClick.run());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 20, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text("›", 24, accent);
        row.addView(arrow);
        card.addView(row);

        TextView d = text(subtitle, 13, MUTED);
        d.setLineSpacing(dp(2), 1f);
        card.addView(d, marginTop(dp(5)));

        card.addView(status, marginTop(dp(10)));
        return card;
    }

    public void showHome() {
        dismissSettingsDialog();
        mode = "home";
        PhoneControlServer.setPhoneModeVisible(false);
        PhoneControlServer.onPhoneChatHidden();
        pcProbeGeneration++;
        pcProbing = false;
        cancelLoadTimeout();
        if (webView != null) {
            webView.stopLoading();
            webView.setVisibility(View.GONE);
        }
        if (progress != null) progress.setVisibility(View.GONE);
        if (loadingText != null) loadingText.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (homeView != null) homeView.setVisibility(View.VISIBLE);
        updateImportantBadge();
        refreshHomeStatus();
        handler.removeCallbacks(homeStatusPoll);
        handler.postDelayed(homeStatusPoll, 3000);
    }

    private void refreshHomeStatusSoon() {
        handler.postDelayed(this::refreshHomeStatus, 400);
        handler.postDelayed(this::refreshHomeStatus, 1200);
    }

    private void refreshHomeStatus() {
        if (homePcStatus == null) return;
        if (pcUrls.isEmpty()) {
            homePcStatus.setText("未配置 · 点此粘贴电脑的远程地址");
        } else {
            String first = pcUrls.get(0);
            String host = "";
            try {
                host = Uri.parse(first).getHost();
            } catch (Exception ignored) {
            }
            String extra = pcUrls.size() > 1 ? "（共 " + pcUrls.size() + " 个，自动尝试）" : "";
            homePcStatus.setText("已配置 · " + (host == null ? first : host) + extra);
        }

        homePhoneStatus.setTextColor(
            LocalEngine.isRunning() && LocalEngine.currentPort() > 0 ? GREEN : ORANGE);
        if (LocalEngine.isInstalling()) {
            homePhoneStatus.setText("安装中 · 点此查看进度");
        } else if (!LocalConfig.isConfigured(this)) {
            homePhoneStatus.setText("未配置 · 点此填写 DeepSeek API Key");
        } else if (LocalEngine.isRunning() && LocalEngine.currentPort() > 0) {
            homePhoneStatus.setText("运行中 · 127.0.0.1:" + LocalEngine.currentPort());
        } else if (LocalEngine.isInstalled(this)) {
            homePhoneStatus.setText("已安装 · 点此启动");
        } else {
            homePhoneStatus.setText("环境未安装 · 点此安装（约 500 MB）");
        }

        boolean storageOk = LocalConfig.hasAllFilesAccess(this);
        setStatusChip(homeStorageStatus, storageOk ? "存储 已授权" : "存储 未授权",
            storageOk ? GREEN : ORANGE);
        if (PhoneControlServer.isRunning()) {
            setStatusChip(homeControlStatus, "控制 已开启", GREEN);
        } else if (PhoneControlServer.isEnabled(this)) {
            setStatusChip(homeControlStatus, "控制 待授权", ORANGE);
        } else {
            setStatusChip(homeControlStatus, "控制 未开启", ORANGE);
        }
        boolean overlayOk = Settings.canDrawOverlays(this);
        setStatusChip(homeOverlayStatus, overlayOk ? "悬浮窗 已授权" : "悬浮窗 未授权",
            overlayOk ? GREEN : ORANGE);
    }

    private void updateImportantBadge() {
        if (importantUpdateBadge == null) return;
        boolean show = "home".equals(mode) && importantUpdateInfo != null;
        if (show) {
            importantUpdateBadge.setText("重要更新 v"
                + importantUpdateInfo.optString("versionName", ""));
        }
        importantUpdateBadge.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 静默检查 update.json：只有标记 important=true 且版本更高时才在主页左下角显示提醒。 */
    private void checkImportantUpdateSilently() {
        long now = System.currentTimeMillis();
        if (now - importantCheckAt < 60 * 1000L) return;
        if (importantCheckRunning) return;
        importantCheckAt = now;
        importantCheckRunning = true;
        new Thread(() -> {
            JSONObject found = null;
            boolean ok = false;
            try {
                String json = fetchUpdateText(UPDATE_MANIFEST_URL);
                JSONObject o = new JSONObject(json);
                int newCode = o.optInt("versionCode", 0);
                boolean important = o.optBoolean("important", false)
                    || o.optBoolean("importantUpdate", false);
                int oldCode = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
                if (important && newCode > oldCode) found = o;
                ok = true;
            } catch (Exception ignored) {
                // 静默检查，失败不打扰；下次回到 App 或手动检查时再试。
            } finally {
                importantCheckRunning = false;
                if (!ok) importantCheckAt = 0;
            }
            if (!ok) return;
            final JSONObject info = found;
            runOnUiThread(() -> {
                importantUpdateInfo = info;
                updateImportantBadge();
            });
        }, "important-update-check").start();
    }

    // ---------------------------------------------------------------- 电脑端

    private void openPcMode() {
        dismissSettingsDialog();
        if (pcNetworkDirty) {
            pcNetworkDirty = false;
            recreateForPcReconnect(true);
            return;
        }
        if (pcUrls.isEmpty()) {
            String tsPc = LocalConfig.tsPcUrl(this);
            String normalized = normalizePcUrl(tsPc);
            if (normalized != null) {
                pcUrls.add(normalized);
                savePcUrls();
            } else {
                showSetupDialog();
                return;
            }
        }
        if (pcProbing) return;
        pcProbing = true;
        mode = "pc";
        showLoading("正在连接电脑端…");
        final int generation = ++pcProbeGeneration;
        new Thread(() -> {
            waitForNetworkReady(3000);
            final boolean cellular = isCellularNetwork();
            final boolean tsEnabled = LocalConfig.tsEnabled(MainActivity.this)
                && !LocalConfig.tsPcUrl(MainActivity.this).isEmpty();
            String found = null;
            boolean builtinTried = false;

            // 5G/蜂窝网络下家里 Wi-Fi 地址一定不通。直接启动内置 Tailscale，
            // 不再先等 LAN + Tailscale 两次连接超时（旧版这里要白等约 8 秒）。
            if (cellular && tsEnabled) {
                builtinTried = true;
                found = startBuiltinAndProbe(generation);
            }

            if (found == null) {
                // 同时探测所有可用地址，谁先返回首字节就用谁；避免只按顺序
                // 选中一个虽然能用、但延迟更高的地址。
                found = probeFastestPcUrl(new ArrayList<>(pcUrls), generation, cellular);
            }

            // 配置的地址都不通时，再启动内置 tsnet 代理（不依赖 Tailscale App）。
            if (found == null && !builtinTried && tsEnabled) {
                found = startBuiltinAndProbe(generation);
            }
            final String target = found;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (generation != pcProbeGeneration || !"pc".equals(mode)) return;
                pcProbing = false;
                if (target == null) {
                    showLoadError();
                    return;
                }
                promotePcUrl(target);
                showWebView(target, "pc");
            });
        }, "pc-probe").start();
    }

    /** 内置 Tailscale 刚连上时对端路径可能还没就绪，给几次短重试。 */
    private boolean probeBuiltinPc(int generation) {
        String local = LocalConfig.tsLocalUrl(this);
        if (local.isEmpty()) return false;
        for (int i = 0; i < 3; i++) {
            if (generation != pcProbeGeneration || !"pc".equals(mode)) return false;
            if (probePcUrl(local, 2500, 12000)) return true;
            if (i < 2) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** 启动内置 Tailscale，再用它本机的 127.0.0.1:4720 探测电脑页面。 */
    private String startBuiltinAndProbe(int generation) {
        if (generation != pcProbeGeneration || !"pc".equals(mode)) return null;
        // 代理已经在跑时不要再显示“启动”，直接用它探测；返回主页不会停掉它。
        if (LocalEngine.isTailscaleProxyRunning() && probeBuiltinPc(generation)) {
            return LocalConfig.tsLocalUrl(MainActivity.this);
        }
        runOnUiThread(() -> {
            if (generation == pcProbeGeneration && "pc".equals(mode) && loadingText != null) {
                loadingText.setText("正在启动内置 Tailscale…");
            }
        });
        try {
            LocalEngine.startTailscaleProxy(MainActivity.this,
                line -> LocalEngine.log(MainActivity.this, line));
            if (generation != pcProbeGeneration || !"pc".equals(mode)) return null;
            if (probeBuiltinPc(generation)) {
                runOnUiThread(() -> {
                    if (generation == pcProbeGeneration && "pc".equals(mode) && loadingText != null) {
                        loadingText.setText("正在打开电脑端…");
                    }
                });
                return LocalConfig.tsLocalUrl(MainActivity.this);
            }
        } catch (Exception e) {
            LocalEngine.log(MainActivity.this, "内置 Tailscale 启动失败（继续其它地址）：" + e);
        }
        return null;
    }

    /**
     * 上次用的是电脑端、当前又在蜂窝网络：App 一打开就在后台预热内置 Tailscale。
     * 这样用户点「电脑端」时代理往往已经连好，不用再等 6 秒 tsnet 登录。
     */
    private void maybePrewarmTailscale() {
        try {
            String last = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_LAST_MODE, "");
            if (!"pc".equals(last)) return;
            if (!LocalConfig.tsEnabled(this) || LocalConfig.tsPcUrl(this).isEmpty()) return;
            if (!isCellularNetwork() || LocalEngine.isTailscaleProxyRunning()) return;
            handler.postDelayed(() -> new Thread(() -> {
                try {
                    LocalEngine.startTailscaleProxy(MainActivity.this,
                        line -> LocalEngine.log(MainActivity.this, line));
                    LocalEngine.log(MainActivity.this, "内置 Tailscale 已预连接");
                } catch (Exception e) {
                    LocalEngine.log(MainActivity.this, "内置 Tailscale 预连接失败：" + e);
                }
            }, "tsnet-prewarm").start(), 800);
        } catch (Exception ignored) {
        }
    }

    /** 更新/刚启动时网络可能还没通过系统校验，先短暂等它就绪再探测电脑端。 */
    private void waitForNetworkReady(int timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network n = cm.getActiveNetwork();
                    NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
                    if (caps != null
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isCellularNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Exception e) {
            return false;
        }
    }

    /** 家里/局域网地址；蜂窝网络下直接跳过，不用白等连接超时。 */
    private boolean isPrivateLanUrl(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            if (host.equals("localhost")) return true;
            if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                String[] p = host.split("\\.");
                int a = Integer.parseInt(p[0]);
                int b = Integer.parseInt(p[1]);
                return a == 10 || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || a == 127 || (a == 169 && b == 254);
            }
            return !host.contains(".") || host.endsWith(".local")
                || host.endsWith(".lan") || host.endsWith(".home");
        } catch (Exception e) {
            return false;
        }
    }

    /** 逐个探测配置的地址，哪个在当前网络下能打开就用哪个。 */
    private boolean probePcUrl(String url) {
        return probePcUrl(url, 4000, 6000);
    }

    private boolean probePcUrl(String url, int connectMs, int readMs) {
        return probePcLatency(url, connectMs, readMs) >= 0;
    }

    /** 返回首字节耗时（毫秒），失败返回 -1；用于在多个地址里选最快的一条。 */
    private long probePcLatency(String url, int connectMs, int readMs) {
        HttpURLConnection con = null;
        long started = System.currentTimeMillis();
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(connectMs);
            con.setReadTimeout(readMs);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "ClaudeWorkbench/2.1 (Android)");
            // 探测只要判断返回内容，不要求压缩；避免代理 gzip 后误判。
            con.setRequestProperty("Accept-Encoding", "identity");
            int code = con.getResponseCode();
            if (code < 200 || code >= 400) return -1L;
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[8192];
                int n = in.read(buf);
                if (n <= 0) return -1L;
                String head = new String(buf, 0, n, StandardCharsets.UTF_8);
                if (head.contains("Claude 工作台") || head.contains("listScreen")
                        || head.contains("workbench") || head.contains("token")) {
                    return System.currentTimeMillis() - started;
                }
                return -1L;
            }
        } catch (Exception e) {
            return -1L;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** 并行探测配置的地址，选首字节最快的一条；不按配置顺序硬选。 */
    private String probeFastestPcUrl(List<String> urls, int generation, boolean cellular) {
        final List<String> list = new ArrayList<>();
        for (String u : urls) {
            if (generation != pcProbeGeneration || !"pc".equals(mode)) break;
            if (cellular && isPrivateLanUrl(u)) continue;
            list.add(u);
        }
        if (list.isEmpty()) return null;
        final Object lock = new Object();
        final String[] best = {null};
        final long[] bestMs = {Long.MAX_VALUE};
        final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(list.size());
        for (String u : list) {
            Thread t = new Thread(() -> {
                long ms = probePcLatency(u, 3000, 4500);
                synchronized (lock) {
                    if (ms >= 0 && ms < bestMs[0]) {
                        bestMs[0] = ms;
                        best[0] = u;
                    }
                }
                latch.countDown();
            }, "pc-probe");
            t.setDaemon(true);
            t.start();
        }
        try {
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            return best[0];
        }
    }

    private void promotePcUrl(String url) {
        String u = normalizePcUrl(url);
        if (u == null) return;
        try {
            if ("127.0.0.1".equals(Uri.parse(u).getHost())) return;
        } catch (Exception ignored) {
        }
        pcUrls.remove(u);
        pcUrls.add(0, u);
        while (pcUrls.size() > 8) pcUrls.remove(pcUrls.size() - 1);
        savePcUrls();
    }

    private void showSetupDialog() {
        if (setupShowing) return;
        setupShowing = true;
        if (progress != null) progress.setVisibility(View.GONE);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint("每行一个地址，例如：\n"
            + "http://192.168.x.x:4711/m/index.html?token=...\n"
            + "http://100.x.x.x:4711/m/index.html?token=...");
        if (!pcUrls.isEmpty()) input.setText(joinPcUrls());
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView intro = text("把电脑「远程访问.cmd」窗口里的地址整段粘贴进来，可以带多行和说明文字；"
            + "App 会提取所有 http/https 地址并自动尝试。\n\n"
            + "建议家里 Wi-Fi 和 Tailscale 各放一条，换网络时不用重新填。", 11.5f, MUTED);
        intro.setLineSpacing(dp(3), 1f);
        box.addView(intro);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(10);
        box.addView(wrap, ilp);

        Dialog dialog = showDarkDialog("连接电脑端", "可填多条地址，自动尝试", box,
            "连接", d -> {
                List<String> urls = extractPcUrls(input.getText().toString());
                if (urls.isEmpty()) {
                    input.setError("没有识别到 http/https 地址");
                    return false;
                }
                pcUrls.clear();
                pcUrls.addAll(urls);
                savePcUrls();
                refreshHomeStatus();
                handler.post(this::openPcMode);
                return true;
            },
            "取消", d -> true,
            null, null, MUTED, false, 0);
        dialog.setCancelable(false);
        dialog.setOnDismissListener(d -> setupShowing = false);
        input.requestFocus();
    }

    private void loadPcUrls() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        pcUrls.clear();
        String list = p.getString(PREF_URLS, "");
        for (String line : list.split("\\r?\\n")) {
            String u = normalizePcUrl(line);
            if (u != null && !pcUrls.contains(u)) pcUrls.add(u);
        }
        String old = normalizePcUrl(p.getString(PREF_URL, ""));
        if (old != null && !pcUrls.contains(old)) pcUrls.add(0, old);
    }

    private void savePcUrls() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString(PREF_URLS, joinPcUrls());
        e.putString(PREF_URL, pcUrls.isEmpty() ? "" : pcUrls.get(0));
        e.apply();
    }

    private String joinPcUrls() {
        StringBuilder sb = new StringBuilder();
        for (String u : pcUrls) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(u);
        }
        return sb.toString();
    }

    private List<String> extractPcUrls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            String raw = m.group().replaceAll("[.,;!?，。；：！？）)\\]}]+$", "");
            String u = normalizePcUrl(raw);
            if (u != null && !out.contains(u)) out.add(u);
        }
        if (out.isEmpty()) {
            String t = text.trim();
            if (!t.isEmpty() && !t.contains("\n") && !t.contains(" ")) {
                String u = normalizePcUrl(t);
                if (u != null) out.add(u);
            }
        }
        return out;
    }

    private String normalizePcUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        String scheme = s.toLowerCase(Locale.ROOT);
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
            String host = s.split("[/:]")[0];
            boolean local = host.startsWith("192.168.") || host.startsWith("10.")
                || host.startsWith("127.") || host.startsWith("100.")
                || host.equals("localhost")
                || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
            s = (local ? "http://" : "https://") + s;
        }
        try {
            Uri u = Uri.parse(s);
            if (u.getHost() == null || u.getHost().isEmpty()) return null;
        } catch (Exception e) {
            return null;
        }
        int q = s.indexOf('?');
        String base = q >= 0 ? s.substring(0, q) : s;
        String query = q >= 0 ? s.substring(q + 1) : "";
        String lower = base.toLowerCase(Locale.ROOT);
        if (!lower.contains("/m/") && !lower.endsWith("/m")) {
            base = base.replaceAll("/+$", "") + "/m/index.html?app=1";
            if (!query.isEmpty()) base += "&" + query;
            s = base;
        }
        return s;
    }

    // ---------------------------------------------------------------- 手机端

    private void openPhoneMode() {
        dismissSettingsDialog();
        if (!LocalConfig.isConfigured(this)) {
            showPhoneConfigDialog(true);
            return;
        }
        if (!LocalEngine.isInstalled(this)) {
            showInstallDialog();
            return;
        }
        ensureNotificationPermission();
        startPhoneMode();
    }

    private void startPhoneMode() {
        if (phoneStarting) return;
        if (LocalEngine.isRunning() && LocalEngine.currentPort() > 0) {
            phonePort = LocalEngine.currentPort();
            phoneStarting = true;
            mode = "phone";
            showLoading("正在检查手机端页面…");
            checkPhonePageThenShow(phonePort);
            return;
        }
        phoneStarting = true;
        mode = "phone";
        showLoading("正在启动手机端…");
        try {
            EngineService.start(this);
        } catch (Exception e) {
            phoneStarting = false;
            LocalEngine.log(this, "启动前台服务失败：" + e);
            showLoadError();
            return;
        }

        final long deadline = System.currentTimeMillis() + 70000;
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!"phone".equals(mode)) {
                phoneStarting = false;
                return;
            }
            if (LocalEngine.isRunning() && LocalEngine.currentPort() > 0) {
                phonePort = LocalEngine.currentPort();
                showLoading("正在检查手机端页面…");
                // 页面校验必须放后台线程，否则主线程网络请求会抛 NetworkOnMainThreadException。
                checkPhonePageThenShow(phonePort);
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                phoneStarting = false;
                showLoadError();
                return;
            }
            handler.postDelayed(poll[0], 500);
        };
        handler.postDelayed(poll[0], 500);
    }

    /** 后台校验手机端页面，成功后再切 WebView；避免主线程网络异常。 */
    private void checkPhonePageThenShow(final int port) {
        new Thread(() -> {
            final boolean ok = LocalEngine.phonePageOk(MainActivity.this, port);
            final String probe = ok ? "" : LocalEngine.phonePageProbe(MainActivity.this, port);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !"phone".equals(mode)) {
                    phoneStarting = false;
                    return;
                }
                if (!ok) {
                    phoneStarting = false;
                    LocalEngine.log(this, "手机端页面校验失败：" + probe);
                    showLoadError();
                    return;
                }
                phoneStarting = false;
                phonePort = port;
                showWebView(phoneUrl(port), "phone");
            });
        }, "phone-page-check").start();
    }

    private String phoneUrl(int port) {
        String token = LocalEngine.readGuiToken(this);
        String url = "http://127.0.0.1:" + port + "/m/index.html?app=1"
            + (token.isEmpty() ? "" : "&token=" + Uri.encode(token));
        if (pendingPhoneConvId != null && !pendingPhoneConvId.isEmpty()) {
            url += "#c=" + Uri.encode(pendingPhoneConvId);
        }
        return url;
    }

    private void showPhoneConfigDialog(boolean thenInstall) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(2), 0, dp(2), 0);

        EditText key = edit("DeepSeek API Key（sk-…）", LocalConfig.apiKey(this), true);
        box.addView(label("API Key"));
        box.addView(key, marginTop(dp(5)));

        EditText base = edit("Base URL", LocalConfig.baseUrl(this), false);
        base.setText(LocalConfig.baseUrl(this));
        box.addView(label("Base URL"));
        box.addView(base, marginTop(dp(5)));

        EditText model = edit("模型", LocalConfig.model(this), false);
        model.setText(LocalConfig.model(this));
        box.addView(label("模型"));
        box.addView(model, marginTop(dp(5)));

        EditText effort = edit("推理档位（留空或 low/medium/high/xhigh/max）", LocalConfig.effort(this), false);
        effort.setText(LocalConfig.effort(this));
        box.addView(label("推理档位"));
        box.addView(effort, marginTop(dp(5)));
        box.addView(text("默认接 DeepSeek 的 Anthropic 兼容接口；模型名可填 deepseek-flash[1m]。",
            11, MUTED), marginTop(dp(8)));

        showDarkDialog("手机端配置", "DeepSeek 地址、Key 和默认模型", box,
            "保存", d -> {
                String k = key.getText().toString().trim();
                if (k.isEmpty()) {
                    key.setError("请填写 API Key");
                    return false;
                }
                LocalConfig.save(this, k, base.getText().toString(), model.getText().toString(),
                    effort.getText().toString());
                refreshHomeStatus();
                if (thenInstall) {
                    handler.post(this::showInstallDialog);
                } else {
                    Toast.makeText(this, "已保存，重新进入手机端后生效", Toast.LENGTH_LONG).show();
                }
                return true;
            },
            "取消", d -> true,
            null, null, MUTED, false, 0);
    }

    private void showInstallDialog() {
        if (LocalEngine.isInstalling()) {
            showLogDialog();
            return;
        }
        ensureNotificationPermission();
        LocalEngine.log(this, "===== 开始安装手机端 =====");

        TextView logView = text("", 10, Color.rgb(180, 186, 194));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(10), dp(6), dp(10), dp(6));
        scroll.setBackground(rounded(0xFF161A20, STROKE, 10));
        scroll.addView(logView);
        int h = dp(220);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView intro = text("首次安装会下载 Alpine、Node 和 Claude Code，约 500 MB，"
            + "需要 1.8 GB 以上空间。安装期间请保持网络；关闭窗口后仍在后台安装。",
            11.5f, MUTED);
        intro.setLineSpacing(dp(3), 1f);
        box.addView(intro);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h);
        slp.topMargin = dp(10);
        box.addView(scroll, slp);

        installDialog = showDarkDialog("安装手机端环境", "约 500 MB · 需要 1.8 GB 空间", box,
            "后台安装", d -> true,
            "关闭", d -> true,
            null, null, MUTED, false, 0);
        try {
            EngineService.install(this);
        } catch (Exception e) {
            LocalEngine.log(this, "启动安装服务失败：" + e);
            if (installDialog != null && installDialog.isShowing()) installDialog.dismiss();
            installDialog = null;
            Toast.makeText(this, "无法启动安装服务：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        final String[] installShown = {""};
        installPoll = new Runnable() {
            @Override
            public void run() {
                String log = LocalEngine.readLogTail(MainActivity.this, 6000);
                if (!log.equals(installShown[0])) {
                    View child = scroll.getChildAt(0);
                    boolean follow = child == null || child.getHeight() == 0
                        || child.getBottom() - (scroll.getHeight() + scroll.getScrollY()) < dp(24);
                    int keepY = scroll.getScrollY();
                    installShown[0] = log;
                    logView.setText(log);
                    scroll.post(() -> {
                        if (follow) scroll.fullScroll(View.FOCUS_DOWN);
                        else scroll.scrollTo(0, keepY);
                    });
                }
                boolean done = LocalEngine.isInstalled(MainActivity.this) && !LocalEngine.isInstalling();
                if (done) {
                    if (installDialog != null && installDialog.isShowing()) installDialog.dismiss();
                    installDialog = null;
                    Toast.makeText(MainActivity.this, "手机端环境已安装", Toast.LENGTH_SHORT).show();
                    refreshHomeStatus();
                    if (!"pc".equals(mode)) startPhoneMode();
                    return;
                }
                if (!LocalEngine.isInstalling() && LocalEngine.lastError() != null
                    && !LocalEngine.lastError().isEmpty()) {
                    logView.append("\n\n安装失败：" + LocalEngine.lastError());
                    return;
                }
                handler.postDelayed(this, 800);
            }
        };
        handler.postDelayed(installPoll, 800);
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private LinearLayout settingsGroup() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(rounded(CARD, STROKE, 16));
        return g;
    }

    private TextView settingsGroupLabel(String s) {
        TextView t = text(s, 11, FAINT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), dp(16), 0, dp(6));
        return t;
    }

    private View settingsDivider() {
        View v = new View(this);
        v.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private StateListDrawable rowPressBg() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, rounded(0xFF1B2028, Color.TRANSPARENT, 12));
        d.addState(new int[]{}, rounded(Color.TRANSPARENT, Color.TRANSPARENT, 12));
        return d;
    }

    private class SettingsRow extends LinearLayout {
        TextView titleView;
        TextView subtitleView;
        TextView chip;

        SettingsRow() {
            super(MainActivity.this);
        }

        void setTitle(String s) {
            if (titleView != null) titleView.setText(s);
        }

        void setSubtitle(String s) {
            if (subtitleView != null) subtitleView.setText(s);
        }

        void setStatus(String s, int color) {
            if (chip == null) return;
            chip.setText(s);
            chip.setTextColor(color);
            chip.setBackground(rounded(Color.TRANSPARENT,
                Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)), 999));
        }
    }

    private SettingsRow settingsRow(String title, String subtitle, String status,
                                    int statusColor, boolean danger, Runnable action) {
        SettingsRow row = new SettingsRow();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        row.setBackground(rowPressBg());
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 14, danger ? RED : FG);
        row.titleView = t;
        main.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 11, MUTED);
            sub.setPadding(0, dp(2), 0, 0);
            row.subtitleView = sub;
            main.addView(sub);
        }
        row.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (status != null && !status.isEmpty()) {
            TextView chip = text(status, 11, statusColor);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            chip.setBackground(rounded(Color.TRANSPARENT,
                Color.argb(90, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor)),
                999));
            row.chip = chip;
            row.addView(chip);
        }
        TextView chev = text("›", 18, FAINT);
        chev.setPadding(dp(8), 0, 0, 0);
        row.addView(chev);
        if (action != null) row.setOnClickListener(v -> action.run());
        return row;
    }

    private SettingsRow settingsRow(String title, String subtitle, String status,
                                    int statusColor, Runnable action) {
        return settingsRow(title, subtitle, status, statusColor, false, action);
    }

    private int dialogWidth(float ratio, int maxDp) {
        int width = (int) (getResources().getDisplayMetrics().widthPixels * ratio);
        return Math.min(width, dp(maxDp));
    }

    /** 在 show() 之前固定窗口尺寸/位置/动画，避免弹出后再改布局造成闪动。 */
    private void configureDialogWindow(Dialog dialog, int width, int height) {
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.CENTER);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.62f);
        w.setWindowAnimations(R.style.WorkbenchDialogAnimation);
        w.setLayout(width, height);
    }

    private void dismissSettingsDialog() {
        Dialog d = settingsDialog;
        settingsDialog = null;
        settingsRefresh = null;
        if (d != null && d.isShowing()) d.dismiss();
    }

    private void refreshSettingsDialogStatus() {
        if (settingsDialog != null && settingsDialog.isShowing() && settingsRefresh != null) {
            settingsRefresh.run();
        }
    }

    private void showPhoneSettings() {
        dismissSettingsDialog();
        final boolean controlOn = PhoneControlServer.isRunning();
        final boolean controlWaiting = !controlOn && PhoneControlServer.isEnabled(this);
        final String controlStatus = controlOn ? "已开启" : controlWaiting ? "等待授权" : "未开启";
        final int controlColor = controlOn ? GREEN : ORANGE;
        final boolean tsOn = LocalEngine.isTailscaleProxyRunning();
        final boolean tsConfigured = LocalConfig.tsEnabled(this);
        final String tsStatus = tsOn ? "运行中" : tsConfigured ? "已配置" : "未配置";
        final int tsColor = (tsOn || tsConfigured) ? GREEN : ORANGE;
        final boolean storageOk = LocalConfig.hasAllFilesAccess(this);
        final boolean overlayOk = Settings.canDrawOverlays(this);
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { /* ignore */ }
        final String versionText = version == null ? "" : version;
        final String versionSub = versionText.isEmpty() ? "Claude 工作台 · 手机端"
            : "Claude 工作台 · v" + versionText;

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackground(rounded(CARD, STROKE, 20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("设置与日志", 18, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        TextView subtitle = text(versionSub, 11, MUTED);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView closeX = text("×", 22, MUTED);
        closeX.setGravity(Gravity.CENTER);
        closeX.setOnClickListener(v -> dialog.dismiss());
        head.addView(closeX, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(2));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        content.addView(settingsGroupLabel("连接与模型"));
        LinearLayout g1 = settingsGroup();
        g1.addView(settingsRow("API Key 与模型", "DeepSeek 地址、Key、默认模型", null, 0,
            () -> showPhoneConfigDialog(false)));
        g1.addView(settingsDivider());
        SettingsRow controlRow = settingsRow("手机控制", "无障碍点按与读屏", controlStatus, controlColor,
            this::showPhoneControlDialog);
        g1.addView(controlRow);
        g1.addView(settingsDivider());
        SettingsRow tsRow = settingsRow("内置 Tailscale", "免装 Tailscale App", tsStatus, tsColor,
            this::showTailscaleDialog);
        g1.addView(tsRow);
        content.addView(g1);

        content.addView(settingsGroupLabel("设备权限"));
        LinearLayout g2 = settingsGroup();
        SettingsRow storageRow = settingsRow("手机存储", "读取和保存手机文件",
            storageOk ? "全盘" : "未授权", storageOk ? GREEN : ORANGE,
            this::openStorageSettings);
        g2.addView(storageRow);
        g2.addView(settingsDivider());
        SettingsRow overlayRow = settingsRow("悬浮窗权限", "插话与完成提示",
            overlayOk ? "已授权" : "未授权", overlayOk ? GREEN : ORANGE,
            this::openOverlaySettings);
        g2.addView(overlayRow);
        content.addView(g2);

        content.addView(settingsGroupLabel("运行与维护"));
        LinearLayout g3 = settingsGroup();
        g3.addView(settingsRow("查看运行日志", "手机端引擎实时输出", null, 0,
            this::showLogDialog));
        g3.addView(settingsDivider());
        SettingsRow engineRow = settingsRow(
            LocalEngine.isRunning() ? "停止手机端" : "启动手机端",
            LocalEngine.isRunning() ? "正在运行" : "127.0.0.1 本机服务",
            LocalEngine.isRunning() ? "运行中" : "已停止",
            LocalEngine.isRunning() ? GREEN : FAINT, () -> {
                dialog.dismiss();
                if (LocalEngine.isRunning()) {
                    EngineService.stop(this);
                    Toast.makeText(this, "已停止手机端", Toast.LENGTH_SHORT).show();
                    refreshHomeStatus();
                    refreshHomeStatusSoon();
                } else {
                    openPhoneMode();
                }
            });
        g3.addView(engineRow);
        g3.addView(settingsDivider());
        g3.addView(settingsRow("检查更新", versionText.isEmpty() ? "" : "当前 " + versionText, null, 0,
            this::checkForUpdates));
        g3.addView(settingsDivider());
        g3.addView(settingsRow("重新安装手机端环境", "重装 Node / Claude Code，保留对话", null, 0, () ->
            showDarkMessage("重新安装？", "保留对话记录",
                "会重新下载 Alpine 并重装 Node / Claude Code，已安装的对话记录会先备份再恢复。",
                "重新安装", d -> {
                    dialog.dismiss();
                    Toast.makeText(this, "正在重置环境…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        try {
                            LocalEngine.resetForReinstall(MainActivity.this);
                            runOnUiThread(() -> showInstallDialog());
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "重置失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }, "phone-reset").start();
                    return true;
                },
                "取消", d -> true,
                null, null, MUTED, false)));
        g3.addView(settingsDivider());
        g3.addView(settingsRow("清空所有手机端数据与环境", "对话、配置、运行环境都会删除", null, 0, true,
            this::confirmClearAllData));
        g3.addView(settingsDivider());
        g3.addView(settingsRow("卸载 App", "保留还是删除手机端数据？", null, 0, true,
            this::confirmUninstall));
        content.addView(g3);

        TextView close = text("关闭", 14, FG);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(12), 0, dp(12));
        close.setBackground(rounded(0xFF1A1F27, STROKE, 12));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(14);
        close.setLayoutParams(closeLp);
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close);

        settingsRefresh = () -> {
            boolean cOn = PhoneControlServer.isRunning();
            boolean cWaiting = !cOn && PhoneControlServer.isEnabled(this);
            controlRow.setStatus(cOn ? "已开启" : cWaiting ? "等待授权" : "未开启",
                cOn ? GREEN : ORANGE);
            boolean tOn = LocalEngine.isTailscaleProxyRunning();
            boolean tCfg = LocalConfig.tsEnabled(this);
            tsRow.setStatus(tOn ? "运行中" : tCfg ? "已配置" : "未配置",
                (tOn || tCfg) ? GREEN : ORANGE);
            boolean sOk = LocalConfig.hasAllFilesAccess(this);
            storageRow.setStatus(sOk ? "全盘" : "未授权", sOk ? GREEN : ORANGE);
            boolean oOk = Settings.canDrawOverlays(this);
            overlayRow.setStatus(oOk ? "已授权" : "未授权", oOk ? GREEN : ORANGE);
            boolean running = LocalEngine.isRunning();
            engineRow.setTitle(running ? "停止手机端" : "启动手机端");
            engineRow.setSubtitle(running ? "正在运行" : "127.0.0.1 本机服务");
            engineRow.setStatus(running ? "运行中" : "已停止", running ? GREEN : FAINT);
        };

        dialog.setContentView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureDialogWindow(dialog, dialogWidth(0.92f, 480),
            (int) (getResources().getDisplayMetrics().heightPixels * 0.86f));
        settingsDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (settingsDialog == dialog) {
                settingsDialog = null;
                settingsRefresh = null;
            }
        });
        dialog.show();
    }

    // ---------------------------------------------------------------- 检查更新

    private LinearLayout updateInfoRow(String label, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        TextView l = text(label, 12, MUTED);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = text(value, 12, valueColor);
        v.setGravity(Gravity.RIGHT);
        row.addView(v);
        return row;
    }

    interface DarkAction {
        boolean run(Dialog dialog);
    }

    /** 内容少时自适应高度，内容多时限制最大高度并在内部滚动，避免按钮被挤出屏幕。 */
    private static class MaxHeightScrollView extends ScrollView {
        private int maxHeight;

        MaxHeightScrollView(Context context) {
            super(context);
        }

        void setMaxHeight(int height) {
            maxHeight = height;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (maxHeight > 0) {
                heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                    maxHeight, View.MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    Dialog showDarkDialog(String title, String subtitle, View content,
                                  String primaryLabel, DarkAction primary,
                                  String secondaryLabel, DarkAction secondary,
                                  String tertiaryLabel, DarkAction tertiary, int tertiaryColor,
                                  boolean dangerPrimary, int contentHeightDp) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.setBackground(rounded(CARD, STROKE, 20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 18, FG);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 11, MUTED);
            sub.setPadding(0, dp(2), 0, 0);
            titles.addView(sub);
        }
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView closeX = text("×", 22, MUTED);
        closeX.setGravity(Gravity.CENTER);
        closeX.setOnClickListener(v -> dialog.dismiss());
        head.addView(closeX, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(head);

        if (content != null) {
            MaxHeightScrollView scroll = new MaxHeightScrollView(this);
            scroll.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.55f));
            scroll.addView(content);
            int h = contentHeightDp > 0 ? dp(contentHeightDp) : ViewGroup.LayoutParams.WRAP_CONTENT;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
            lp.topMargin = dp(12);
            root.addView(scroll, lp);
        }

        if (tertiaryLabel != null && tertiary != null) {
            TextView link = text(tertiaryLabel, 12, tertiaryColor);
            link.setPadding(dp(4), dp(10), dp(4), 0);
            link.setOnClickListener(v -> {
                if (tertiary.run(dialog)) dialog.dismiss();
            });
            root.addView(link);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(14);

        if (primaryLabel != null && primary != null) {
            TextView primaryBtn = text(primaryLabel, 14, Color.rgb(27, 18, 12));
            primaryBtn.setTypeface(Typeface.DEFAULT_BOLD);
            primaryBtn.setGravity(Gravity.CENTER);
            primaryBtn.setPadding(0, dp(12), 0, dp(12));
            primaryBtn.setBackground(rounded(dangerPrimary ? RED : ORANGE,
                dangerPrimary ? RED : ORANGE, 12));
            primaryBtn.setOnClickListener(v -> {
                if (primary.run(dialog)) dialog.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            actions.addView(primaryBtn, lp);
        }
        if (secondaryLabel != null && secondary != null) {
            TextView secondaryBtn = text(secondaryLabel, 14, FG);
            secondaryBtn.setGravity(Gravity.CENTER);
            secondaryBtn.setPadding(0, dp(12), 0, dp(12));
            secondaryBtn.setBackground(rounded(0xFF1A1F27, STROKE, 12));
            secondaryBtn.setOnClickListener(v -> {
                if (secondary.run(dialog)) dialog.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (primaryLabel != null) lp.leftMargin = dp(10);
            actions.addView(secondaryBtn, lp);
        }
        if (actions.getChildCount() > 0) root.addView(actions, alp);

        dialog.setContentView(root);
        configureDialogWindow(dialog, dialogWidth(0.92f, 520),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setOnDismissListener(d -> refreshSettingsDialogStatus());
        dialog.show();
        return dialog;
    }

    private Dialog showDarkMessage(String title, String subtitle, String message,
                                   String primaryLabel, DarkAction primary,
                                   String secondaryLabel, DarkAction secondary,
                                   String tertiaryLabel, DarkAction tertiary, int tertiaryColor,
                                   boolean dangerPrimary) {
        TextView body = text(message, 13f, Color.rgb(200, 205, 212));
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(dp(2), 0, dp(2), 0);
        return showDarkDialog(title, subtitle, body, primaryLabel, primary,
            secondaryLabel, secondary, tertiaryLabel, tertiary, tertiaryColor, dangerPrimary, 0);
    }

    private void showInfoDialog(String title, String message, String buttonLabel) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.setBackground(rounded(CARD, STROKE, 20));

        TextView t = text(title, 18, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);
        TextView m = text(message, 13, MUTED);
        m.setLineSpacing(dp(3), 1f);
        m.setPadding(0, dp(10), 0, 0);
        root.addView(m);

        TextView close = text(buttonLabel, 14, FG);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(12), 0, dp(12));
        close.setBackground(rounded(0xFF1A1F27, STROKE, 12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close);

        dialog.setContentView(root);
        configureDialogWindow(dialog, dialogWidth(0.9f, 480),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setOnDismissListener(d -> refreshSettingsDialogStatus());
        dialog.show();
    }

    private void showUpdateDialog(String oldName, int oldCode, String newName, int newCode,
                                  String channel, String notes, boolean slim, boolean important,
                                  Runnable onDownload) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.setBackground(rounded(CARD, STROKE, 20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("发现新版本", 18, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        TextView sub = text("Claude 工作台 · 手机端", 11, MUTED);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (important) {
            TextView imp = text("重要", 10, ORANGE);
            imp.setPadding(dp(8), dp(3), dp(8), dp(3));
            imp.setBackground(rounded(Color.argb(20, 240, 163, 94),
                Color.argb(100, 240, 163, 94), 999));
            head.addView(imp);
        }
        TextView closeX = text("×", 22, MUTED);
        closeX.setGravity(Gravity.CENTER);
        closeX.setOnClickListener(v -> dialog.dismiss());
        head.addView(closeX, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(head);

        LinearLayout info = settingsGroup();
        info.addView(updateInfoRow("当前版本", oldName + "（" + oldCode + "）", MUTED));
        info.addView(settingsDivider());
        info.addView(updateInfoRow("新版本", newName + "（" + newCode + "）", ORANGE));
        info.addView(settingsDivider());
        info.addView(updateInfoRow("更新方式", channel, slim ? GREEN : ORANGE));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(14);
        root.addView(info, ilp);

        root.addView(settingsGroupLabel("更新内容"));
        ScrollView notesScroll = new ScrollView(this);
        TextView notesView = text(notes.isEmpty() ? "建议更新到最新版本。" : notes, 12.5f, MUTED);
        notesView.setLineSpacing(dp(3), 1f);
        notesView.setPadding(dp(14), dp(12), dp(14), dp(12));
        notesScroll.addView(notesView);
        notesScroll.setBackground(rounded(CARD, STROKE, 16));
        root.addView(notesScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        if (slim) {
            TextView tip = text("本机已装手机端环境，只更新 App，约 8 MB。", 11, GREEN);
            tip.setPadding(dp(4), dp(8), dp(4), 0);
            root.addView(tip);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(14);
        TextView later = text("稍后", 14, MUTED);
        later.setGravity(Gravity.CENTER);
        later.setPadding(0, dp(12), 0, dp(12));
        later.setBackground(rounded(0xFF1A1F27, STROKE, 12));
        later.setOnClickListener(v -> dialog.dismiss());
        TextView download = text("下载并安装", 14, Color.rgb(27, 18, 12));
        download.setTypeface(Typeface.DEFAULT_BOLD);
        download.setGravity(Gravity.CENTER);
        download.setPadding(0, dp(12), 0, dp(12));
        download.setBackground(rounded(ORANGE, ORANGE, 12));
        download.setOnClickListener(v -> {
            dialog.dismiss();
            onDownload.run();
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp2.leftMargin = dp(10);
        actions.addView(later, lp1);
        actions.addView(download, lp2);
        root.addView(actions, alp);

        dialog.setContentView(root);
        configureDialogWindow(dialog, dialogWidth(0.92f, 520),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setOnDismissListener(d -> refreshSettingsDialogStatus());
        dialog.show();
    }

    private void checkForUpdates() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String json = fetchUpdateText(UPDATE_MANIFEST_URL);
                JSONObject o = new JSONObject(json);
                if (!o.has("versionCode") || o.optInt("versionCode", 0) <= 0) {
                    throw new java.io.IOException("更新清单无效（可能还没发布）");
                }
                final int newCode = o.optInt("versionCode", 0);
                final String newName = o.optString("versionName", "");
                final String apkUrl = o.optString("apk", "");
                final String sha = o.optString("sha256", "");
                final String slimUrl = o.optString("slimApk", "");
                final String slimSha = o.optString("slimSha256", "");
                final String notes = o.optString("notes", "");
                final boolean important = o.optBoolean("important", false)
                    || o.optBoolean("importantUpdate", false);
                final int oldCode = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
                final String oldName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
                final boolean installed = LocalEngine.isInstalled(MainActivity.this);
                final boolean useSlim = installed && !slimUrl.isEmpty() && !slimSha.isEmpty();
                final String chosenUrl = useSlim ? slimUrl : apkUrl;
                final String chosenSha = useSlim ? slimSha : sha;
                final String channel = useSlim
                    ? "精简更新（仅 App，约 8 MB，不用重下环境）"
                    : "完整包（含手机环境，约 194 MB）";
                final JSONObject manifest = o;
                runOnUiThread(() -> {
                    // 手动检查也要同步主页的重要更新标记；否则只有重启后的静默检查才会出现。
                    importantUpdateInfo = newCode > oldCode && important ? manifest : null;
                    updateImportantBadge();
                    if (newCode <= oldCode) {
                        showInfoDialog("已是最新版本",
                            "当前版本：" + oldName + "（" + oldCode + "）", "知道了");
                        return;
                    }
                    if (chosenUrl.isEmpty()) {
                        Toast.makeText(MainActivity.this,
                            "更新清单里没有安装包地址", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showUpdateDialog(oldName, oldCode, newName, newCode, channel, notes,
                        useSlim, important,
                        () -> downloadUpdate(chosenUrl, newName, chosenSha));
                });
            } catch (Exception e) {
                runOnUiThread(() -> showInfoDialog("检查更新失败",
                    "无法读取更新清单：\n" + e.getMessage()
                        + "\n\n请确认网络能访问 GitHub，或稍后再试。", "知道了"));
            }
        }, "check-update").start();
    }

    private String fetchUpdateText(String url) throws Exception {
        Exception last = null;
        for (String u : mirroredUpdateUrls(url)) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(u).openConnection();
                con.setConnectTimeout(12000);
                con.setReadTimeout(20000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent", "ClaudeWorkbench/2.4 (Android)");
                con.setRequestProperty("Cache-Control", "no-cache");
                int code = con.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    String text = out.toString("UTF-8");
                    if (!text.trim().startsWith("{")) {
                        throw new java.io.IOException("更新清单格式错误");
                    }
                    return text;
                }
            } catch (Exception e) {
                last = e;
            } finally {
                if (con != null) con.disconnect();
            }
        }
        throw last == null ? new java.io.IOException("网络不可用") : last;
    }

    private String[] mirroredUpdateUrls(String url) {
        if (url.startsWith("https://github.com/")
                || url.startsWith("https://raw.githubusercontent.com/")
                || url.startsWith("https://objects.githubusercontent.com/")) {
            return new String[]{UPDATE_MIRROR_PREFIX + url, url};
        }
        return new String[]{url};
    }

    private void downloadUpdate(String url, String versionName, String sha) {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "shared");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "无法创建下载目录", Toast.LENGTH_LONG).show();
            return;
        }
        String safe = versionName == null ? "new"
            : versionName.replaceAll("[^0-9A-Za-z._-]", "_");
        final File target = new File(dir, "ClaudeWorkbench-" + safe + ".apk");
        final Dialog pd = new Dialog(this);
        pd.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.setBackground(rounded(CARD, STROKE, 18));
        TextView title = text("下载更新 " + versionName, 16, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView msg = text("准备下载…", 12, MUTED);
        msg.setPadding(0, dp(4), 0, dp(12));
        root.addView(msg);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(ORANGE));
        root.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        TextView cancel = text("取消下载", 13, FG);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(10), 0, dp(10));
        cancel.setBackground(rounded(0xFF1A1F27, STROKE, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        root.addView(cancel, clp);
        pd.setContentView(root);
        configureDialogWindow(pd, dialogWidth(0.86f, 460),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        final boolean[] canceled = {false};
        pd.setCancelable(true);
        pd.setOnCancelListener(d -> canceled[0] = true);
        cancel.setOnClickListener(v -> {
            canceled[0] = true;
            pd.dismiss();
        });
        pd.show();
        new Thread(() -> {
            Exception last = null;
            for (String u : mirroredUpdateUrls(url)) {
                try {
                    downloadUpdateFile(u, target, sha, pd, bar, msg, canceled);
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    if (canceled[0]) break;
                }
            }
            final Exception err = last;
            runOnUiThread(() -> {
                if (pd.isShowing()) pd.dismiss();
                if (err != null) {
                    if (!canceled[0]) {
                        Toast.makeText(MainActivity.this,
                            "下载失败：" + err.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                Toast.makeText(MainActivity.this, "下载完成，正在打开安装器…",
                    Toast.LENGTH_SHORT).show();
                DownloadsBridge bridge = new DownloadsBridge(MainActivity.this);
                if (!bridge.open(target.getAbsolutePath())) {
                    Toast.makeText(MainActivity.this,
                        "下载完成，但无法打开安装器", Toast.LENGTH_LONG).show();
                }
            });
        }, "download-update").start();
    }

    private void downloadUpdateFile(String url, File target, String sha, Dialog pd,
                                    ProgressBar bar, TextView msg, boolean[] canceled)
        throws Exception {
        File part = new File(target.getAbsolutePath() + ".part");
        part.delete();
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "ClaudeWorkbench/2.4 (Android)");
            con.setRequestProperty("Accept", "application/octet-stream");
            int code = con.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            long total = con.getContentLengthLong();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long got = 0;
            int lastPct = -1;
            try (InputStream in = con.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (canceled[0]) throw new java.io.IOException("已取消");
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) Math.min(99, got * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            runOnUiThread(() -> {
                                if (!pd.isShowing()) return;
                                bar.setProgress(p);
                                msg.setText("已下载 " + p + "%");
                            });
                        }
                    }
                }
            }
            if (sha != null && !sha.isEmpty()) {
                String gotSha = hex(md.digest());
                if (!sha.equalsIgnoreCase(gotSha)) {
                    part.delete();
                    throw new java.io.IOException("SHA-256 校验失败");
                }
            }
            if (!part.renameTo(target)) {
                try (InputStream in = new FileInputStream(part);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                part.delete();
            }
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void confirmClearAllData() {
        showDarkMessage("清空所有手机端数据？", "此操作不可恢复",
            "会停止手机端并删除：Alpine / Node / Claude 运行环境、/workspace、"
                + "手机端对话记录、API Key、Tailscale 配置、日志和手机控制配置。\n\n"
                + "不会删除 /sdcard 里的照片、下载等用户文件；电脑端的数据也不受影响。",
            "清空", d -> {
                dismissSettingsDialog();
                doClearAllData(false);
                return true;
            },
            "取消", d -> true,
            null, null, MUTED, true);
    }

    private void confirmUninstall() {
        showDarkMessage("卸载 App", "Android 卸载会自动删除 App 内部数据",
            "选择「清空并卸载」会先停掉手机端，再删除 Alpine / Node / Claude、"
                + "/workspace、手机端对话和配置；不会删除 /sdcard 里的用户文件。\n\n"
                + "选择「直接卸载」则交给系统卸载器处理。",
            "清空并卸载", d -> {
                dismissSettingsDialog();
                doClearAllData(true);
                return true;
            },
            "直接卸载", d -> {
                dismissSettingsDialog();
                launchUninstall();
                return true;
            },
            "取消", d -> true, MUTED, true);
    }

    private void doClearAllData(boolean thenUninstall) {
        Toast.makeText(this, thenUninstall ? "正在清空并准备卸载…" : "正在清空手机端数据…",
            Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                LocalEngine.clearAllData(MainActivity.this);
                runOnUiThread(() -> {
                    pcUrls.clear();
                    try {
                        android.webkit.CookieManager.getInstance().removeAllCookies(null);
                        android.webkit.WebStorage.getInstance().deleteAllData();
                        if (webView != null) {
                            webView.clearCache(true);
                            webView.clearHistory();
                            webView.loadUrl("about:blank");
                        }
                    } catch (Exception ignored) {
                    }
                    showHome();
                    refreshHomeStatus();
                    Toast.makeText(MainActivity.this, "手机端数据已清空", Toast.LENGTH_LONG).show();
                    if (thenUninstall) launchUninstall();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "清空失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "phone-clear").start();
    }

    private void openOverlaySettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开悬浮窗权限设置：" + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(this::refreshHomeStatus, 1200);
    }

    private void launchUninstall() {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统卸载器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showTailscaleDialog() {
        String pc = LocalConfig.tsPcUrl(this);
        if (pc.isEmpty()) {
            for (String u : pcUrls) {
                try {
                    String h = Uri.parse(u).getHost();
                    if (h != null && (h.startsWith("100.") || h.endsWith(".ts.net"))) {
                        pc = u;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        boolean running = LocalEngine.isTailscaleProxyRunning();
        String state = running ? "运行中"
            : LocalConfig.tsEnabled(this) ? "已配置，未启动"
            : "未配置";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(2), 0, dp(2), 0);

        EditText key = edit("Tailscale auth key", LocalConfig.tsAuthKey(this), true);
        box.addView(label("Auth key"));
        box.addView(key, marginTop(dp(5)));

        EditText pcInput = edit("电脑 Tailscale 地址（含 token）", pc, false);
        pcInput.setSingleLine(false);
        pcInput.setMinLines(2);
        box.addView(label("电脑地址"));
        box.addView(pcInput, marginTop(dp(5)));

        TextView note = text(
            "在 Tailscale 后台 → Settings → Keys → Generate auth key，生成 reusable key 粘贴到上面。\n"
            + "它会以 claude-phone 设备加入你的 tailnet，在 App 内跑用户态代理，不占用 Android VPN，"
            + "不需要安装 Tailscale App。\n"
            + "首次必须填 auth key；连接成功后设备状态保存在手机，之后可以留空。\n"
            + "电脑地址填电脑的 Tailscale 地址（通常 100.x 或 *.ts.net，带 token）。\n"
            + "auth key 只保存在手机本地，不要发给别人。",
            11, MUTED);
        note.setLineSpacing(dp(3), 1f);
        box.addView(note, marginTop(dp(10)));

        showDarkDialog("内置 Tailscale", "状态：" + state, box,
            "保存并启动", d -> {
                List<String> urls = extractPcUrls(pcInput.getText().toString());
                if (urls.isEmpty()) {
                    pcInput.setError("请填写电脑的 Tailscale 地址");
                    return false;
                }
                String k = key.getText().toString().trim();
                if (k.isEmpty() && !LocalEngine.hasTsState(this)) {
                    key.setError("首次使用需要 Tailscale auth key");
                    return false;
                }
                LocalConfig.saveTs(this, k, urls.get(0));
                LocalConfig.setTsEnabled(this, true);
                Toast.makeText(this, "正在启动内置 Tailscale…", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try {
                        LocalEngine.startTailscaleProxy(MainActivity.this,
                            line -> LocalEngine.log(MainActivity.this, line));
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                "内置 Tailscale 已连接", Toast.LENGTH_SHORT).show();
                            refreshHomeStatus();
                            refreshSettingsDialogStatus();
                        });
                    } catch (Exception e) {
                        LocalConfig.setTsEnabled(MainActivity.this, false);
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                "内置 Tailscale 启动失败：" + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                            refreshSettingsDialogStatus();
                            showLogDialog();
                        });
                    }
                }, "tsnet-start").start();
                return true;
            },
            "停止", d -> {
                LocalConfig.setTsEnabled(this, false);
                LocalEngine.stopTailscaleProxy(this);
                Toast.makeText(this, "内置 Tailscale 已停止", Toast.LENGTH_SHORT).show();
                refreshHomeStatus();
                return true;
            },
            "取消", d -> true, MUTED, false, 0);
    }

    private void showPhoneControlDialog() {
        boolean running = PhoneControlServer.isRunning();
        boolean connected = PhoneControlServer.isAccessibilityConnected();
        boolean enabled = PhoneControlServer.isEnabled(this);
        boolean auto = PhoneControlServer.canAutoEnableAccessibility(this);
        String state = running ? "已开启"
            : enabled ? (connected ? "已授权，服务未启动" : "等待系统无障碍授权")
            : "未开启";
        String msg = "开启后，手机端 Claude 可以读取当前屏幕、点按、输入和打开 App。\n"
            + "密码框、支付、转账、验证码会被拒绝并交还给你；不用时可在同一菜单关闭。\n"
            + "操作时顶部会显示悬浮窗，可停止或插话。悬浮窗内输入需要系统「显示在其他应用上层」"
            + "权限；没授权时会提示先开启该权限，而不是切走当前页面。\n\n"
            + (auto
                ? "已授予自动保持权限：系统关闭无障碍后，打开 App 会自动重新开启。\n\n"
                : "想让无障碍一直保持：电脑连手机后运行一次 pc\\授权无障碍自动保持.cmd"
                    + "（需开启 USB 调试），之后 App 会自动恢复。\n\n")
            + "Android 13+ 侧载应用如果无障碍开关是灰的：\n"
            + "系统设置 → 应用 → Claude 工作台 → 右上角 ⋮ → 允许受限设置，再回来开启。";
        showDarkMessage("手机控制（无障碍点按）", "状态：" + state, msg,
            "开启", d -> {
                PhoneControlServer.setEnabled(this, true);
                if (PhoneControlServer.isAccessibilityConnected()) {
                    Toast.makeText(this, "手机控制已开启", Toast.LENGTH_SHORT).show();
                    refreshHomeStatus();
                } else if (PhoneControlServer.ensureAccessibilityEnabled(this)) {
                    Toast.makeText(this, "已自动开启无障碍，正在连接…", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(() -> {
                        refreshHomeStatus();
                        refreshSettingsDialogStatus();
                    }, 1800);
                } else {
                    openAccessibilitySettings();
                }
                return true;
            },
            "关闭", d -> {
                PhoneControlServer.setEnabled(this, false);
                Toast.makeText(this, "手机控制已关闭", Toast.LENGTH_SHORT).show();
                refreshHomeStatus();
                return true;
            },
            "系统无障碍设置", d -> {
                openAccessibilitySettings();
                return true;
            }, ORANGE, false);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "请在列表里打开「Claude 工作台」", Toast.LENGTH_LONG).show();
            handler.postDelayed(this::refreshHomeStatus, 1500);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void openStorageSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_STORAGE);
            } else {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开存储权限设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void showLogDialog() {
        final CheckBox onlyErrors = new CheckBox(this);
        onlyErrors.setText("只看报错");
        onlyErrors.setTextColor(Color.rgb(220, 220, 220));
        onlyErrors.setChecked(true);

        TextView hint = text("默认只显示报错，复制时也只复制这些；取消勾选可查看全部日志。", 11, MUTED);
        hint.setPadding(dp(4), 0, dp(4), dp(6));

        TextView tv = text("", 10, Color.rgb(220, 220, 220));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(tv);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(onlyErrors);
        box.addView(hint);
        box.addView(scroll);

        final String[] shown = {""};
        final Runnable[] update = new Runnable[1];
        update[0] = () -> {
            String full = onlyErrors.isChecked()
                ? LocalEngine.readLogTail(this, 300000)
                : LocalEngine.readLog(this);
            String next = onlyErrors.isChecked() ? filterErrorLog(full) : full;
            if (next.equals(shown[0])) return;
            View child = scroll.getChildAt(0);
            boolean atBottom = child == null || child.getHeight() == 0
                || child.getBottom() - (scroll.getHeight() + scroll.getScrollY()) < dp(32);
            int keepY = scroll.getScrollY();
            shown[0] = next;
            tv.setText(next);
            scroll.post(() -> {
                if (atBottom) scroll.fullScroll(View.FOCUS_DOWN);
                else scroll.scrollTo(0, keepY);
            });
        };

        final Dialog dlg = showDarkDialog("手机端运行日志", "自动刷新", box,
            "复制", d -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("phone.log", tv.getText()));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                return false;
            },
            "关闭", d -> true,
            "清空", d -> {
                LocalEngine.logFile(this).delete();
                shown[0] = "";
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                return false;
            }, MUTED, false, 0);
        onlyErrors.setOnCheckedChangeListener((b, checked) -> {
            shown[0] = "";
            update[0].run();
        });
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            if (!dlg.isShowing()) return;
            update[0].run();
            handler.postDelayed(refresh[0], 1000);
        };
        dlg.setOnDismissListener(d -> {
            handler.removeCallbacks(refresh[0]);
            refreshSettingsDialogStatus();
        });
        update[0].run();
        tv.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        handler.postDelayed(refresh[0], 800);
    }

    /** 日志页默认只显示报错；保留 Java 堆栈、失败/超时/异常等关键行。 */
    private static String filterErrorLog(String text) {
        if (text == null || text.isEmpty()) return "暂无日志。\n";
        if (text.length() > 300000) text = text.substring(text.length() - 300000);
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String line : text.split("\n", -1)) {
            String l = line.toLowerCase(Locale.ROOT);
            String t = line.trim();
            boolean head = l.contains("error") || l.contains("fail") || l.contains("fatal")
                || l.contains("panic") || l.contains("exception") || l.contains("timeout")
                || l.contains("refused") || l.contains("denied") || l.contains("unreachable")
                || l.contains("unexpected") || l.contains("crash") || l.contains("stack trace")
                || l.contains("失败") || l.contains("错误") || l.contains("异常")
                || l.contains("超时") || l.contains("崩溃") || l.contains("未连接")
                || l.contains("连接失败") || l.contains("无法") || l.contains("断开")
                || l.contains("拒绝");
            boolean stack = t.contains("	at ") || t.contains("Caused by:")
                || t.contains("Suppressed:") || t.startsWith("... ")
                || (t.contains(".java:") && t.contains("("));
            if (!head && !stack) continue;
            out.append(line).append('\n');
            if (++shown >= 2000) {
                out.append("…（报错较多，只显示最近 2000 行）\n");
                break;
            }
        }
        if (shown == 0) return "暂无报错记录。取消勾选「只看报错」可查看全部日志。\n";
        return out.toString();
    }

    // ---------------------------------------------------------------- WebView

    private void showWebView(String url, String newMode) {
        mode = newMode;
        if ("pc".equals(newMode)) {
            pcNetworkKey = currentNetworkKey();
            pcNetworkDirty = false;
        }
        PhoneControlServer.setPhoneModeVisible("phone".equals(newMode));
        currentBaseHost = "";
        try {
            currentBaseHost = Uri.parse(url).getHost();
        } catch (Exception ignored) {
        }
        homeView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        if (loadingText != null) loadingText.setVisibility(View.GONE);
        updateImportantBadge();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_MODE, mode).apply();
        cancelLoadTimeout();
        // 切回手机端时先清掉 WebView 里残留的电脑端页面；
        // 万一手机端页面加载失败，也不会继续显示上一次电脑端的内容。
        if ("phone".equals(newMode)) {
            pendingPhoneConvId = "";
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
            } catch (Exception ignored) {
            }
        }
        webView.loadUrl(url);
        startLoadTimeout();
    }

    private void showLoading(String text) {
        homeView.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        if (loadingText != null) {
            loadingText.setText(text == null ? "" : text);
            loadingText.setVisibility(View.VISIBLE);
        }
        updateImportantBadge();
    }

    private boolean handleUrl(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        if (currentBaseHost != null && currentBaseHost.equalsIgnoreCase(host)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
    }

    private void startLoadTimeout() {
        cancelLoadTimeout();
        loadTimeout = () -> {
            loadTimeout = null;
            showLoadError();
        };
        handler.postDelayed(loadTimeout, 22000);
    }

    private void cancelLoadTimeout() {
        if (loadTimeout != null) {
            handler.removeCallbacks(loadTimeout);
            loadTimeout = null;
        }
    }

    private void showLoadError() {
        cancelLoadTimeout();
        progress.setVisibility(View.GONE);
        if (loadingText != null) loadingText.setVisibility(View.GONE);
        if (mode == null || "home".equals(mode)) return;
        if ("phone".equals(mode)) {
            errorTitle.setText("手机端启动失败");
            errorNote.setText("请查看「设置与日志」里的手机端运行日志。\n"
                + "常见原因：首次安装未完成、网络无法访问 claude.ai、手机系统杀掉了后台进程。");
        } else {
            errorTitle.setText("连接不上电脑端");
            String tried = pcUrls.isEmpty() ? "" : "已尝试 " + pcUrls.size() + " 个配置地址。\n";
            errorNote.setText(tried + "请确认：\n1. 手机 Tailscale 已开启并登录\n"
                + "2. 如果开了其它 VPN/加速器，Tailscale 无法同时连接，先关掉它\n"
                + "3. 电脑保持开机联网\n4. 或者手机连回家里的 Wi-Fi");
        }
        errorView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        updateImportantBadge();
        refreshHomeStatus();
    }

    private LinearLayout buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(BG);
        box.setPadding(dp(40), dp(40), dp(40), dp(40));

        errorTitle = text("连接不上工作台", 20, FG);
        errorTitle.setGravity(Gravity.CENTER);
        box.addView(errorTitle);

        errorNote = text("", 14, MUTED);
        errorNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(16);
        box.addView(errorNote, np);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(v -> {
            if ("phone".equals(mode)) startPhoneMode();
            else openPcMode();
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(24);
        box.addView(retry, bp);

        Button setup = new Button(this);
        setup.setText("配置");
        setup.setOnClickListener(v -> {
            if ("phone".equals(mode)) showPhoneConfigDialog(false);
            else showSetupDialog();
        });
        box.addView(setup, bp);

        Button tailscale = new Button(this);
        tailscale.setText("打开 Tailscale");
        tailscale.setOnClickListener(v -> {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
                if (i == null) {
                    Toast.makeText(this, "没有找到 Tailscale；可先设成系统「始终开启 VPN」", Toast.LENGTH_LONG).show();
                    return;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开 Tailscale", Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(tailscale, bp);

        Button home = new Button(this);
        home.setText("返回主页");
        home.setOnClickListener(v -> showHome());
        box.addView(home, bp);

        return box;
    }

    // ---------------------------------------------------------------- 生命周期/返回

    @Override
    public void onBackPressed() {
        if (mode == null || "home".equals(mode) || webView == null) {
            super.onBackPressed();
            return;
        }
        if (errorView.getVisibility() == View.VISIBLE || pcProbing) {
            showHome();
            return;
        }
        webView.evaluateJavascript(
            "(function(){" +
            "try{if(window.__workbenchBack){var r=window.__workbenchBack();if(r)return r;}}catch(e){}" +
            "var ids=['imgViewer','confirmStop','confirmDelete','attachSheet','refSheet','chatSettings','sheet','downloadsSheet','pcFileSheet','phoneFolderPicker','optionSheet','quoteTargetSheet','msgActions','diffSheet'];" +
            "for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(e&&!e.hidden){e.hidden=true;" +
            "if(ids[i]==='imgViewer'){var im=document.getElementById('imgViewerImg');if(im)im.src='';}" +
            "return 'handled';}}" +
            "var c=document.getElementById('chatScreen');var b=document.getElementById('backBtn');" +
            "if(c&&!c.hidden&&b){b.click();return 'handled';}" +
            "return '';})()",
            value -> {
                if (value != null && !value.equals("null") && !value.equals("\"\"")) return;
                webView.evaluateJavascript(
                    "(function(){var l=document.getElementById('listScreen');" +
                    "return !!(l && !l.hidden && l.offsetParent!==null);})()",
                    atList -> {
                        // 不再沿 WebView 历史回退到上一个模式（电脑端/手机端会互相串）。
                        // 页面内的弹窗/对话返回已由上面的 JS 处理；这里统一回主页。
                        showHome();
                    });
            });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_LOG, false)) {
            showLogDialog();
            return;
        }
        if (intent != null) {
            String conv = intent.getStringExtra(EXTRA_CONV_ID);
            if (conv != null && !conv.isEmpty()) {
                if ("phone".equals(mode) && webView != null && webView.getVisibility() == View.VISIBLE) {
                    webView.evaluateJavascript(
                        "location.hash='#c=" + Uri.encode(conv) + "';", null);
                } else {
                    pendingPhoneConvId = conv;
                    openPhoneMode();
                }
                return;
            }
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_INTERJECT, false)) {
            if ("phone".equals(mode) && webView != null && webView.getVisibility() == View.VISIBLE) {
                webView.evaluateJavascript(
                    "(function(){var i=document.getElementById('input');if(i){i.focus();}})()", null);
            } else {
                openPhoneMode();
            }
            return;
        }
        // 从桌面图标再次进入时回到主页；从普通通知进入则保留当前页面
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())) showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if ("pc".equals(mode) && pcNetworkDirty && !pcReconnecting) {
            recreateForPcReconnect(true);
            return;
        }
        PhoneControlServer.setAppForeground(true);
        // 用户已经回到 App，清掉“几个任务已完成”的通知计数；运行中的任务保留。
        TaskStatus.clearCompleted();
        EngineService.notifyTaskStatusChanged(this);
        maybeAutoEnableAccessibility();
        refreshHomeStatus();
        refreshSettingsDialogStatus();
        checkImportantUpdateSilently();
    }

    @Override
    protected void onPause() {
        resumed = false;
        PhoneControlServer.setAppForeground(false);
        super.onPause();
    }

    /**
     * 已经用 ADB 授权 WRITE_SECURE_SETTINGS 时，系统把无障碍关掉后，
     * 每次回到 App 自动重新打开，不用再去系统设置点一遍。
     */
    private void maybeAutoEnableAccessibility() {
        if (!PhoneControlServer.isEnabled(this)
                || PhoneControlServer.isAccessibilityConnected()
                || !PhoneControlServer.canAutoEnableAccessibility(this)) {
            return;
        }
        if (PhoneControlServer.ensureAccessibilityEnabled(this)) {
            handler.postDelayed(() -> {
                refreshHomeStatus();
                if (PhoneControlServer.isRunning()) {
                    Toast.makeText(this, "已自动恢复无障碍授权", Toast.LENGTH_SHORT).show();
                }
            }, 1800);
        }
    }

    private void cleanupCapture() {
        if (captureFile != null) {
            try { captureFile.delete(); } catch (Exception ignored) { /* ignore */ }
        }
        captureUri = null;
        captureFile = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_CAPTURE) {
            Uri shot = null;
            if (resultCode == RESULT_OK && captureUri != null
                    && captureFile != null && captureFile.length() > 0) {
                shot = captureUri;
                captureUri = null;
                captureFile = null;
            } else {
                cleanupCapture();
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(shot == null ? null : new Uri[]{shot});
                fileCallback = null;
            }
            return;
        }
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
        if (requestCode == REQ_STORAGE) {
            refreshHomeStatus();
            Toast.makeText(this, "存储权限修改后，重新进入手机端生效", Toast.LENGTH_LONG).show();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY) {
            // 通知权限被拒不影响前台服务，只是看不到常驻通知
        }
    }

    @Override
    protected void onDestroy() {
        cancelLoadTimeout();
        pcProbeGeneration++;
        pcProbing = false;
        resumed = false;
        if (networkCallback != null) {
            try {
                ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (installDialog != null && installDialog.isShowing()) installDialog.dismiss();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 工具

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, MUTED);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private EditText edit(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setText(value == null ? "" : value);
        e.setInputType(InputType.TYPE_CLASS_TEXT
            | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        return e;
    }

    private LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = px;
        return p;
    }

    private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp((int) radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    private void showCrash(Throwable t) {
        saveCrash(t);
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(BG);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(40), dp(70), dp(40), dp(70));

        TextView title = text("Claude 工作台启动失败", 20, Color.rgb(224, 118, 107));
        box.addView(title);

        TextView tv = text(sw.toString(), 11, FG);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(20);
        box.addView(tv, tp);

        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(v -> finish());
        box.addView(close, marginTop(dp(24)));

        sc.addView(box);
        setContentView(sc);
    }
}

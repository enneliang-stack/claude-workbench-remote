package com.missyao.claudeworkbench;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
    private static final String UPDATE_MANIFEST_URL =
        "https://raw.githubusercontent.com/enneliang-stack/claude-workbench-remote/main/apk/update.json";
    private static final String UPDATE_MIRROR_PREFIX = "https://ghfast.top/";

    private static final String HIDE_TIPS_JS =
        "(function(){try{if(document.getElementById('apkHideTips'))return;"
        + "var s=document.createElement('style');s.id='apkHideTips';"
        + "s.textContent='#httpsTip,#installTip{display:none!important}';"
        + "if(document.head)document.head.appendChild(s);}catch(e){}})()";
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIFY = 1002;
    private static final int REQ_STORAGE = 1003;

    private static final int BG = Color.rgb(11, 13, 16);
    private static final int CARD = Color.rgb(21, 26, 33);
    private static final int STROKE = Color.rgb(38, 46, 56);
    private static final int FG = Color.rgb(232, 230, 227);
    private static final int MUTED = Color.rgb(154, 163, 173);
    private static final int ORANGE = Color.rgb(240, 163, 94);
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

    private ValueCallback<Uri[]> fileCallback;
    private final List<String> pcUrls = new ArrayList<>();
    private int pcProbeGeneration = 0;
    private boolean pcProbing = false;
    private String mode = "home"; // home | pc | phone
    private String currentBaseHost = "";
    private int phonePort = 0;
    private boolean phoneStarting = false;
    private String pendingPhoneConvId = "";
    private boolean setupShowing = false;
    private AlertDialog installDialog;
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

        webView = new WebView(this);
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

        setContentView(root);
        loadPcUrls();
        LocalConfig.ensureDefaultEffort(this);
        PhoneControlServer.ensureConfigFile(this);
        showHome();
        refreshHomeStatus();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SHOW_LOG, false)) {
            handler.postDelayed(this::showLogDialog, 500);
        }
        if (getIntent() != null) {
            String conv = getIntent().getStringExtra(EXTRA_CONV_ID);
            if (conv != null && !conv.isEmpty()) {
                pendingPhoneConvId = conv;
                handler.postDelayed(this::openPhoneMode, 350);
            } else if (getIntent().getBooleanExtra(EXTRA_INTERJECT, false)) {
                handler.postDelayed(this::openPhoneMode, 350);
            }
        }
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

        TextView settings = text("设置与日志  ›", 13, MUTED);
        settings.setPadding(dp(6), dp(18), dp(6), dp(4));
        settings.setOnClickListener(v -> showPhoneSettings());
        page.addView(settings);

        homeStorageStatus = text("", 11, MUTED);
        page.addView(homeStorageStatus, marginTop(dp(2)));

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
        refreshHomeStatus();
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

        if (homeStorageStatus != null) {
            String storage = LocalConfig.hasAllFilesAccess(this)
                ? "手机存储：已授权全盘访问"
                : "手机存储：仅 App 专属目录（可在设置里授权全盘）";
            String control;
            if (PhoneControlServer.isRunning()) control = "手机控制：已开启";
            else if (PhoneControlServer.isEnabled(this)) control = "手机控制：等待无障碍授权";
            else control = "手机控制：未开启";
            homeStorageStatus.setText(storage + " · " + control);
        }
    }

    // ---------------------------------------------------------------- 电脑端

    private void openPcMode() {
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
                List<String> candidates = new ArrayList<>(pcUrls);
                if (!cellular) {
                    // Wi-Fi 下优先试局域网地址，避免上次外出留下的 Tailscale
                    // 地址先占一个 3 秒超时。
                    candidates.sort((a, b) -> Boolean.compare(
                        isPrivateLanUrl(b), isPrivateLanUrl(a)));
                }
                for (String u : candidates) {
                    if (generation != pcProbeGeneration || !"pc".equals(mode)) break;
                    if (cellular && isPrivateLanUrl(u)) continue;
                    if (probePcUrl(u, 3000, 4500)) {
                        found = u;
                        break;
                    }
                }
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

    /** 启动内置 Tailscale，再用它本机的 127.0.0.1:4720 探测电脑页面。 */
    private String startBuiltinAndProbe(int generation) {
        if (generation != pcProbeGeneration || !"pc".equals(mode)) return null;
        runOnUiThread(() -> {
            if (generation == pcProbeGeneration && "pc".equals(mode) && loadingText != null) {
                loadingText.setText("正在启动内置 Tailscale…");
            }
        });
        try {
            LocalEngine.startTailscaleProxy(MainActivity.this,
                line -> LocalEngine.log(MainActivity.this, line));
            if (generation != pcProbeGeneration || !"pc".equals(mode)) return null;
            String local = LocalConfig.tsLocalUrl(MainActivity.this);
            if (!local.isEmpty() && probePcUrl(local, 2500, 12000)) {
                runOnUiThread(() -> {
                    if (generation == pcProbeGeneration && "pc".equals(mode) && loadingText != null) {
                        loadingText.setText("正在打开电脑端…");
                    }
                });
                return local;
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
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(connectMs);
            con.setReadTimeout(readMs);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "ClaudeWorkbench/2.1 (Android)");
            // 探测只要判断返回内容，不要求压缩；避免代理 gzip 后误判。
            con.setRequestProperty("Accept-Encoding", "identity");
            int code = con.getResponseCode();
            if (code < 200 || code >= 400) return false;
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[8192];
                int n = in.read(buf);
                if (n <= 0) return false;
                String head = new String(buf, 0, n, StandardCharsets.UTF_8);
                return head.contains("Claude 工作台") || head.contains("listScreen")
                    || head.contains("workbench") || head.contains("token");
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
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
        int pad = dp(20);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("连接电脑端")
            .setMessage("把电脑「远程访问.cmd」窗口里的地址整段粘贴进来，可以带多行和说明文字；"
                + "App 会提取所有 http/https 地址并自动尝试。\n\n"
                + "建议家里 Wi-Fi 和 Tailscale 各放一条，换网络时不用重新填。")
            .setView(wrap)
            .setPositiveButton("连接", null)
            .setNegativeButton("取消", (d, w) -> {
            })
            .setCancelable(false)
            .create();
        dialog.setOnDismissListener(d -> setupShowing = false);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> urls = extractPcUrls(input.getText().toString());
            if (urls.isEmpty()) {
                input.setError("没有识别到 http/https 地址");
                return;
            }
            pcUrls.clear();
            pcUrls.addAll(urls);
            savePcUrls();
            dialog.dismiss();
            refreshHomeStatus();
            openPcMode();
        });
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
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

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

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("手机端配置")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) {
                key.setError("请填写 API Key");
                return;
            }
            LocalConfig.save(this, k, base.getText().toString(), model.getText().toString(),
                effort.getText().toString());
            dlg.dismiss();
            refreshHomeStatus();
            if (thenInstall) showInstallDialog();
            else Toast.makeText(this, "已保存，重新进入手机端后生效", Toast.LENGTH_LONG).show();
        }));
        dlg.show();
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
        scroll.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(logView);
        int h = dp(220);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h));

        installDialog = new AlertDialog.Builder(this)
            .setTitle("安装手机端环境")
            .setMessage("首次安装会下载 Alpine、Node 和 Claude Code，约 500 MB，需要 1.8 GB 以上空间。"
                + "安装期间请保持网络；关闭窗口后仍在后台安装。")
            .setView(scroll)
            .setPositiveButton("后台安装", (d, w) -> {
            })
            .setNegativeButton("关闭", (d, w) -> {
            })
            .create();
        installDialog.show();
        try {
            EngineService.install(this);
        } catch (Exception e) {
            LocalEngine.log(this, "启动安装服务失败：" + e);
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

    private void showPhoneSettings() {
        String storage = LocalConfig.hasAllFilesAccess(this) ? "手机存储：已授权全盘" : "手机存储：授权全盘访问";
        String control = PhoneControlServer.isRunning()
            ? "手机控制：已开启"
            : PhoneControlServer.isEnabled(this)
                ? "手机控制：等待无障碍授权"
                : "手机控制：未开启";
        String ts = LocalEngine.isTailscaleProxyRunning()
            ? "内置 Tailscale：运行中"
            : LocalConfig.tsEnabled(this)
                ? "内置 Tailscale：已配置，未启动"
                : "内置 Tailscale：未配置（免装 Tailscale App）";
        String[] items = {
            "修改 API Key / 模型",
            control,
            ts,
            storage,
            "查看运行日志",
            LocalEngine.isRunning() ? "停止手机端" : "启动手机端",
            "重新安装手机端环境",
            "清空所有手机端数据与环境",
            "卸载 App",
            Settings.canDrawOverlays(this)
                ? "悬浮窗权限：已授权"
                : "悬浮窗权限：未授权（插话会打开聊天页）",
            "检查更新"
        };
        new AlertDialog.Builder(this)
            .setTitle("设置与日志")
            .setItems(items, (d, which) -> {
                if (which == 0) showPhoneConfigDialog(false);
                else if (which == 1) showPhoneControlDialog();
                else if (which == 2) showTailscaleDialog();
                else if (which == 3) openStorageSettings();
                else if (which == 4) showLogDialog();
                else if (which == 5) {
                    if (LocalEngine.isRunning()) {
                        EngineService.stop(this);
                        Toast.makeText(this, "已停止手机端", Toast.LENGTH_SHORT).show();
                        refreshHomeStatus();
                    } else {
                        openPhoneMode();
                    }
                } else if (which == 6) {
                    new AlertDialog.Builder(this)
                        .setTitle("重新安装？")
                        .setMessage("会重新下载 Alpine 并重装 Node / Claude Code，已安装的对话记录会先备份再恢复。")
                        .setPositiveButton("重新安装", (dd, ww) -> {
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
                        })
                        .setNegativeButton("取消", null)
                        .show();
                } else if (which == 7) {
                    confirmClearAllData();
                } else if (which == 8) {
                    confirmUninstall();
                } else if (which == 9) {
                    openOverlaySettings();
                } else if (which == 10) {
                    checkForUpdates();
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    // ---------------------------------------------------------------- 检查更新

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
                runOnUiThread(() -> {
                    if (newCode <= oldCode) {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("已是最新版本")
                            .setMessage("当前版本：" + oldName + "（" + oldCode + "）")
                            .setPositiveButton("知道了", null)
                            .show();
                        return;
                    }
                    if (chosenUrl.isEmpty()) {
                        Toast.makeText(MainActivity.this,
                            "更新清单里没有安装包地址", Toast.LENGTH_LONG).show();
                        return;
                    }
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("发现新版本 " + newName)
                        .setMessage("当前版本：" + oldName + "（" + oldCode + "）\n"
                            + "新版本：" + newName + "（" + newCode + "）\n"
                            + "更新方式：" + channel + "\n\n"
                            + (notes.isEmpty() ? "建议更新到最新版本。" : notes)
                            + (useSlim
                                ? "\n\n本机已经装好手机端环境，只更新 App，不需要再下 194 MB。"
                                : "\n\n完整安装包较大（约 190 MB），建议在 Wi-Fi 下下载。"))
                        .setPositiveButton("下载并安装",
                            (d, w) -> downloadUpdate(chosenUrl, newName, chosenSha))
                        .setNegativeButton("稍后", null)
                        .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("检查更新失败")
                    .setMessage("无法读取更新清单：\n" + e.getMessage()
                        + "\n\n请确认网络能访问 GitHub，或稍后再试。")
                    .setPositiveButton("知道了", null)
                    .show());
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
        final ProgressDialog pd = new ProgressDialog(this);
        final boolean[] canceled = {false};
        pd.setTitle("下载更新 " + versionName);
        pd.setMessage("准备下载…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(true);
        pd.setOnCancelListener(d -> canceled[0] = true);
        pd.show();
        new Thread(() -> {
            Exception last = null;
            for (String u : mirroredUpdateUrls(url)) {
                try {
                    downloadUpdateFile(u, target, sha, pd, canceled);
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    if (canceled[0]) break;
                }
            }
            final Exception err = last;
            if (err == null) {
                // 安装前先停掉内置 Tailscale，避免旧 App 进程留下的代理占着 4720，
                // 导致更新后第一次进电脑端连不上。
                try {
                    LocalEngine.stopTailscaleProxy(MainActivity.this);
                } catch (Exception e) {
                    LocalEngine.log(MainActivity.this, "更新前停止内置 Tailscale 失败：" + e);
                }
            }
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

    private void downloadUpdateFile(String url, File target, String sha,
                                    ProgressDialog pd, boolean[] canceled) throws Exception {
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
                                if (pd.isShowing()) pd.setProgress(p);
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
        new AlertDialog.Builder(this)
            .setTitle("清空所有手机端数据？")
            .setMessage("会停止手机端并删除：Alpine / Node / Claude 运行环境、/workspace、"
                + "手机端对话记录、API Key、Tailscale 配置、日志和手机控制配置。\n\n"
                + "不会删除 /sdcard 里的照片、下载等用户文件；电脑端的数据也不受影响。\n\n"
                + "此操作不可恢复。")
            .setPositiveButton("清空", (d, w) -> doClearAllData(false))
            .setNegativeButton("取消", null)
            .show();
    }

    private void confirmUninstall() {
        new AlertDialog.Builder(this)
            .setTitle("卸载 App")
            .setMessage("Android 卸载会自动删除 App 内部数据。\n\n"
                + "选择「清空并卸载」会先停掉手机端，再删除 Alpine / Node / Claude、"
                + "/workspace、手机端对话和配置；不会删除 /sdcard 里的用户文件。\n\n"
                + "选择「直接卸载」则交给系统卸载器处理。")
            .setPositiveButton("清空并卸载", (d, w) -> doClearAllData(true))
            .setNeutralButton("直接卸载", (d, w) -> launchUninstall())
            .setNegativeButton("取消", null)
            .show();
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
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        EditText key = edit("Tailscale auth key", LocalConfig.tsAuthKey(this), true);
        box.addView(label("Auth key"));
        box.addView(key, marginTop(dp(5)));

        EditText pcInput = edit("电脑 Tailscale 地址（含 token）", pc, false);
        pcInput.setSingleLine(false);
        pcInput.setMinLines(2);
        box.addView(label("电脑地址"));
        box.addView(pcInput, marginTop(dp(5)));

        TextView note = text(
            "状态：" + state + "\n\n"
            + "在 Tailscale 后台 → Settings → Keys → Generate auth key，生成 reusable key 粘贴到上面。\n"
            + "它会以 claude-phone 设备加入你的 tailnet，在 App 内跑用户态代理，不占用 Android VPN，"
            + "不需要安装 Tailscale App。\n"
            + "首次必须填 auth key；连接成功后设备状态保存在手机，之后可以留空。\n"
            + "电脑地址填电脑的 Tailscale 地址（通常 100.x 或 *.ts.net，带 token）。\n"
            + "auth key 只保存在手机本地，不要发给别人。",
            11, MUTED);
        note.setLineSpacing(dp(3), 1f);
        box.addView(note, marginTop(dp(10)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("内置 Tailscale（免装 App）")
            .setView(scroll)
            .setPositiveButton("保存并启动", null)
            .setNegativeButton("停止", (d, w) -> {
                LocalConfig.setTsEnabled(this, false);
                LocalEngine.stopTailscaleProxy(this);
                Toast.makeText(this, "内置 Tailscale 已停止", Toast.LENGTH_SHORT).show();
                refreshHomeStatus();
            })
            .setNeutralButton("取消", null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> urls = extractPcUrls(pcInput.getText().toString());
            if (urls.isEmpty()) {
                pcInput.setError("请填写电脑的 Tailscale 地址");
                return;
            }
            String k = key.getText().toString().trim();
            if (k.isEmpty() && !LocalEngine.hasTsState(this)) {
                key.setError("首次使用需要 Tailscale auth key");
                return;
            }
            LocalConfig.saveTs(this, k, urls.get(0));
            LocalConfig.setTsEnabled(this, true);
            dlg.dismiss();
            Toast.makeText(this, "正在启动内置 Tailscale…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    LocalEngine.startTailscaleProxy(MainActivity.this,
                        line -> LocalEngine.log(MainActivity.this, line));
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "内置 Tailscale 已连接", Toast.LENGTH_SHORT).show();
                        refreshHomeStatus();
                    });
                } catch (Exception e) {
                    LocalConfig.setTsEnabled(MainActivity.this, false);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                            "内置 Tailscale 启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        showLogDialog();
                    });
                }
            }, "tsnet-start").start();
        }));
        dlg.show();
    }

    private void showPhoneControlDialog() {
        boolean running = PhoneControlServer.isRunning();
        boolean connected = PhoneControlServer.isAccessibilityConnected();
        boolean enabled = PhoneControlServer.isEnabled(this);
        boolean auto = PhoneControlServer.canAutoEnableAccessibility(this);
        String state = running ? "已开启"
            : enabled ? (connected ? "已授权，服务未启动" : "等待系统无障碍授权")
            : "未开启";
        String msg = "状态：" + state + "\n\n"
            + "开启后，手机端 Claude 可以读取当前屏幕、点按、输入和打开 App。\n"
            + "密码框、支付、转账、验证码会被拒绝并交还给你；不用时可在同一菜单关闭。\n"
            + "操作时顶部会显示悬浮窗，可停止或插话。悬浮窗内输入需要系统「显示在其他应用上层」"
            + "权限；没授权时会提示先开启该权限，而不是切走当前页面。\n\n"
            + (auto
                ? "已授予自动保持权限：系统关闭无障碍后，打开 App 会自动重新开启。\n\n"
                : "想让无障碍一直保持：电脑连手机后运行一次 pc\\授权无障碍自动保持.cmd"
                    + "（需开启 USB 调试），之后 App 会自动恢复。\n\n")
            + "Android 13+ 侧载应用如果无障碍开关是灰的：\n"
            + "系统设置 → 应用 → Claude 工作台 → 右上角 ⋮ → 允许受限设置，再回来开启。";
        new AlertDialog.Builder(this)
            .setTitle("手机控制（无障碍点按）")
            .setMessage(msg)
            .setPositiveButton("开启", (d, w) -> {
                PhoneControlServer.setEnabled(this, true);
                if (PhoneControlServer.isAccessibilityConnected()) {
                    Toast.makeText(this, "手机控制已开启", Toast.LENGTH_SHORT).show();
                    refreshHomeStatus();
                } else if (PhoneControlServer.ensureAccessibilityEnabled(this)) {
                    Toast.makeText(this, "已自动开启无障碍，正在连接…", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(this::refreshHomeStatus, 1800);
                } else {
                    openAccessibilitySettings();
                }
            })
            .setNegativeButton("关闭", (d, w) -> {
                PhoneControlServer.setEnabled(this, false);
                Toast.makeText(this, "手机控制已关闭", Toast.LENGTH_SHORT).show();
                refreshHomeStatus();
            })
            .setNeutralButton("系统无障碍设置", (d, w) -> openAccessibilitySettings())
            .show();
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
            ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

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

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("手机端运行日志（自动刷新）")
            .setView(box)
            .setPositiveButton("复制", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("phone.log", tv.getText()));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("清空", (d, w) -> {
                LocalEngine.logFile(this).delete();
                shown[0] = "";
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("关闭", null)
            .create();
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
        dlg.setOnDismissListener(d -> handler.removeCallbacks(refresh[0]));
        dlg.show();
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
            "(function(){var ids=['imgViewer','confirmStop','confirmDelete','attachSheet','refSheet','chatSettings','sheet','downloadsSheet','pcFileSheet','phoneFolderPicker'];" +
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
        PhoneControlServer.setAppForeground(true);
        // 用户已经回到 App，清掉“几个任务已完成”的通知计数；运行中的任务保留。
        TaskStatus.clearCompleted();
        EngineService.notifyTaskStatusChanged(this);
        maybeAutoEnableAccessibility();
        refreshHomeStatus();
    }

    @Override
    protected void onPause() {
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

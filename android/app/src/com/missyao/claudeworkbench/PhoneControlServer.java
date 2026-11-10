package com.missyao.claudeworkbench;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;

/**
 * 手机控制 HTTP 服务：只监听 127.0.0.1，随机 token。
 * proot 里的 phone-mcp.js 读取 data/phone-control.json 后调用这里。
 */
public final class PhoneControlServer {
    private static final String PREFS = "phone_control";
    private static final String PREF_ENABLED = "enabled";
    private static final int DEFAULT_UI_NODES = 240;
    private static final int MAX_UI_NODES = 600;
    private static final int MAX_TREE = 1800;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Executor MAIN_EXEC = command -> {
        MAIN.post(command);
    };

    private static AccessibilityService service;
    private static Context appContext;
    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static String token = "";
    private static int port = 0;

    // 悬浮窗状态：最近一次工具动作、是否暂停、Claude 是否还在跑。
    private static volatile String actionText = "";
    private static volatile String lastText = "";
    private static volatile boolean toolBusy = false;
    private static volatile boolean paused = false;
    private static volatile boolean claudeRunning = false;
    private static volatile long lastActionAt = 0;
    private static volatile boolean refreshingRun = false;
    // 任务结束后的完成提示：不自动过期，直到用户打开 App 里对应的对话。
    private static volatile boolean completionPending = false;
    private static volatile String completionConv = "";
    private static volatile String activeConv = "";
    // App 手机页当前是否停在一个对话上（由 WebView 的 JS 桥接上报）。
    private static volatile String phoneChatConv = "";
    // App 的 Activity 是否在前台；后台时 WebView 的可见状态可能残留，不能据此清完成提示。
    private static volatile boolean appForeground = false;
    // App 当前是否停留在手机端 WebView（而不是主页/电脑端）。
    private static volatile boolean phoneModeVisible = false;
    private static List<AppEntry> cachedApps;
    private static long cachedAppsAt = 0;

    public interface ResultCallback {
        void onResult(boolean ok, String message);
    }

    private PhoneControlServer() {
    }

    public static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, enabled).apply();
        appContext = c.getApplicationContext();
        if (enabled) {
            paused = false;
            toolBusy = false;
            lastText = "";
            lastActionAt = 0;
            completionPending = false;
            completionConv = "";
            activeConv = "";
            phoneChatConv = "";
            if (service != null) start(service);
            else writeConfig(c, false, 0, "");
        } else {
            stop();
        }
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            return serverSocket != null && !serverSocket.isClosed();
        }
    }

    public static boolean isAccessibilityConnected() {
        return service != null;
    }

    /** 是否已通过 ADB 一次性授予 WRITE_SECURE_SETTINGS（授予后可自动保持无障碍）。 */
    public static boolean canAutoEnableAccessibility(Context c) {
        return c != null && c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 用 WRITE_SECURE_SETTINGS 把本应用的无障碍服务写回系统开关。
     * 普通安装拿不到这个权限；需要用户用一次 ADB 授权（见 pc\\授权无障碍自动保持.cmd）。
     */
    public static boolean ensureAccessibilityEnabled(Context c) {
        if (!canAutoEnableAccessibility(c)) return false;
        try {
            String flat = new ComponentName(c, PhoneAccessibilityService.class).flattenToString();
            android.content.ContentResolver cr = c.getContentResolver();
            String cur = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean present = false;
            if (cur != null) {
                for (String s : cur.split(":")) {
                    if (flat.equalsIgnoreCase(s.trim())) {
                        present = true;
                        break;
                    }
                }
            }
            if (!present) {
                String next = (cur == null || cur.trim().isEmpty())
                    ? flat : cur + ":" + flat;
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            }
            if (Settings.Secure.getInt(
                    cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPaused() {
        return paused;
    }

    public static boolean isToolBusy() {
        return toolBusy;
    }

    public static boolean isClaudeRunning() {
        return claudeRunning;
    }

    public static boolean isCompletionPending() {
        return completionPending;
    }

    public static String completionConvId() {
        return completionConv;
    }

    public static boolean isPhoneChatVisible() {
        return appForeground && phoneModeVisible && !phoneChatConv.isEmpty();
    }

    /** App 进前台/退后台。后台时即使 WebView 还停在对话上，也不算“用户正在看”。 */
    public static void setAppForeground(boolean foreground) {
        appForeground = foreground;
    }

    /** 当前是否打开着手机端 WebView；主页/电脑端时即使 JS 还留着对话 id 也不算。 */
    public static void setPhoneModeVisible(boolean visible) {
        phoneModeVisible = visible;
    }

    /** JS 桥接：App 手机页当前停留在某个对话上；只记录，不主动清完成提示。 */
    public static void onPhoneChatVisible(String id) {
        phoneChatConv = id == null ? "" : id.trim();
    }

    /** 用户主动打开/切回对应对话时，完成提示才算已看到。 */
    public static void onPhoneChatEntered(String id) {
        phoneChatConv = id == null ? "" : id.trim();
        if (appForeground && phoneModeVisible && completionPending && !phoneChatConv.isEmpty()
                && (completionConv.isEmpty() || completionConv.equals(phoneChatConv))) {
            dismissCompletion();
        }
    }

    public static void onPhoneChatHidden() {
        phoneChatConv = "";
    }

    /** 用户回到 App 手机页、看到任务结束后，清掉“任务已完成”提示，避免以后再弹。 */
    public static void markCompletionSeen() {
        if (!claudeRunning) {
            completionPending = false;
            completionConv = "";
            lastText = "";
            lastActionAt = 0;
        }
    }

    /** 悬浮窗绿色 ✓：只收起「任务已完成」提示，不打开 App。 */
    public static void dismissCompletion() {
        completionPending = false;
        completionConv = "";
        lastText = "";
        lastActionAt = 0;
    }

    public static void setPaused(boolean value) {
        paused = value;
        lastActionAt = System.currentTimeMillis();
        if (value) lastText = "手机操作已暂停";
    }

    public static void clearPause() {
        if (paused) {
            paused = false;
            lastText = "";
            lastActionAt = System.currentTimeMillis();
        }
    }

    public static String overlayStatus() {
        if (paused) return toolBusy ? "手机操作已暂停（等当前步骤收尾）" : "手机操作已暂停";
        if (toolBusy) return "正在" + actionText + "…";
        if (completionPending && !claudeRunning) return "任务已完成";
        if (claudeRunning) return lastText.isEmpty() ? "任务进行中，等待下一步…" : lastText + " · 任务进行中";
        if (lastActionAt > 0 && System.currentTimeMillis() - lastActionAt < 12000) return lastText;
        return "";
    }

    public static boolean shouldShowOverlay(Context c) {
        if (c == null || !isEnabled(c) || !isAccessibilityConnected() || !isRunning()) return false;
        if (completionPending && !claudeRunning) return true; // 常驻，直到用户打开对应对话
        if (paused || toolBusy || claudeRunning) return true;
        return lastActionAt > 0 && System.currentTimeMillis() - lastActionAt < 12000;
    }

    /** 悬浮窗每秒查一次 Claude 是否还在跑，用来区分「进行中」和「已完成」。 */
    public static void refreshRunState() {
        final Context c = appContext;
        if (c == null || refreshingRun || !isRunning()) {
            if (!isRunning()) claudeRunning = false;
            return;
        }
        if (LocalEngine.currentPort() <= 0) {
            claudeRunning = false;
            return;
        }
        refreshingRun = true;
        new Thread(() -> {
            try {
                JSONObject r = LocalEngine.phoneServerCall(c, "GET", "/api/phone/current", null, 1800);
                boolean running = r != null && r.optBoolean("running", false);
                String id = r != null ? r.optString("id", "") : "";
                if (!id.isEmpty()) activeConv = id;
                boolean was = claudeRunning;
                claudeRunning = running;
                if (!was && running) {
                    completionPending = false;
                    completionConv = "";
                }
                if (was && !running) {
                    // 任务结束一定进入完成态。是否清掉由用户点 ✓、点完成面板
                    // 或主动打开/切回对应对话决定，不能因为页面刚好在渲染就吞掉。
                    completionPending = true;
                    completionConv = activeConv;
                    lastActionAt = System.currentTimeMillis();
                    lastText = "任务已完成";
                }
            } catch (Exception ignored) {
                claudeRunning = false;
            } finally {
                refreshingRun = false;
            }
        }, "phone-run-state").start();
    }

    public static void stopCurrent() {
        final Context c = appContext;
        paused = true;
        toolBusy = false;
        actionText = "";
        lastText = "正在停止…";
        lastActionAt = System.currentTimeMillis();
        if (c == null) {
            paused = false;
            return;
        }
        new Thread(() -> {
            try {
                LocalEngine.phoneServerCall(c, "POST", "/api/stop", new JSONObject(), 3000);
                lastText = "已停止";
            } catch (Exception e) {
                lastText = "停止失败：" + e.getMessage();
            } finally {
                paused = false;
            }
            lastActionAt = System.currentTimeMillis();
        }, "phone-stop").start();
    }

    /** 悬浮窗插话：直接发给当前运行中的 Claude 会话。 */
    public static void steer(String text, ResultCallback callback) {
        final Context c = appContext;
        if (c == null) {
            callback.onResult(false, "App 上下文不可用");
            return;
        }
        final String msg = text == null ? "" : text.trim();
        if (msg.isEmpty()) {
            callback.onResult(false, "插话内容为空");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject cur = LocalEngine.phoneServerCall(c, "GET", "/api/phone/current", null, 2000);
                String id = cur != null ? cur.optString("id", "") : "";
                if (id.isEmpty() || cur == null || !cur.optBoolean("running", false)) {
                    callback.onResult(false, "当前没有运行中的对话");
                    return;
                }
                JSONObject body = new JSONObject();
                body.put("id", id);
                body.put("text", msg);
                JSONObject r = LocalEngine.phoneServerCall(c, "POST", "/api/steer", body, 120000);
                if (r != null && r.optBoolean("ok", false)) {
                    paused = false;
                    lastText = "已插话";
                    lastActionAt = System.currentTimeMillis();
                    callback.onResult(true, "已插话");
                } else {
                    callback.onResult(false, r != null ? r.optString("error", "插话失败") : "插话失败");
                }
            } catch (Exception e) {
                callback.onResult(false, "插话失败：" + e.getMessage());
            }
        }, "phone-steer").start();
    }

    /**
     * 任务完成后的「发消息」：会话已经结束，正常走 /api/chat 重新开始一轮；
     * 如果这期间它又跑起来了，就退化成插话，避免消息丢掉。
     */
    public static void sendMessage(String text, ResultCallback callback) {
        final Context c = appContext;
        if (c == null) {
            callback.onResult(false, "App 上下文不可用");
            return;
        }
        final String msg = text == null ? "" : text.trim();
        if (msg.isEmpty()) {
            callback.onResult(false, "消息内容为空");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject cur = LocalEngine.phoneServerCall(c, "GET", "/api/phone/current", null, 2000);
                boolean running = cur != null && cur.optBoolean("running", false);
                String activeId = cur != null ? cur.optString("id", "") : "";
                String target = !activeId.isEmpty() ? activeId : completionConv;
                if (target == null || target.isEmpty()) target = activeConv;
                if (target == null || target.isEmpty()) target = phoneChatConv;
                if (target == null || target.isEmpty()) {
                    callback.onResult(false, "没有可发送的对话");
                    return;
                }
                JSONObject body = new JSONObject();
                body.put("id", target);
                body.put("text", msg);
                JSONObject r;
                if (running) {
                    r = LocalEngine.phoneServerCall(c, "POST", "/api/steer", body, 120000);
                    if (!(r != null && r.optBoolean("ok", false))
                            && (r == null || r.optBoolean("notRunning", false)
                                || r.optString("error", "").contains("刚刚结束"))) {
                        r = LocalEngine.phoneServerCall(c, "POST", "/api/chat", body, 120000);
                        running = false;
                    }
                } else {
                    r = LocalEngine.phoneServerCall(c, "POST", "/api/chat", body, 120000);
                    if (!(r != null && r.optBoolean("ok", false))
                            && r != null && r.optString("error", "").contains("还在处理中")) {
                        r = LocalEngine.phoneServerCall(c, "POST", "/api/steer", body, 120000);
                        running = true;
                    }
                }
                if (r != null && r.optBoolean("ok", false)) {
                    paused = false;
                    completionPending = false;
                    completionConv = "";
                    activeConv = target;
                    claudeRunning = true;
                    lastText = running ? "已插话" : "已发送";
                    lastActionAt = System.currentTimeMillis();
                    callback.onResult(true, running ? "已插话" : "已发送");
                } else {
                    callback.onResult(false,
                        r != null ? r.optString("error", "发送失败") : "发送失败");
                }
            } catch (Exception e) {
                callback.onResult(false, "发送失败：" + e.getMessage());
            }
        }, "phone-send").start();
    }

    private static void beginAction(String text) {
        actionText = text;
        toolBusy = true;
        lastActionAt = System.currentTimeMillis();
    }

    private static void endAction(String text, boolean ok) {
        toolBusy = false;
        actionText = "";
        lastText = text + (ok ? " 完成" : " 失败");
        lastActionAt = System.currentTimeMillis();
    }

    private static String actionTextFor(String path, JSONObject body) {
        switch (path) {
            case "/ui": return "读取屏幕";
            case "/tap": return "点击坐标";
            case "/tap_text": return "点击「" + body.optString("text", "") + "」";
            case "/type": return "输入文字";
            case "/swipe": return "滑动";
            case "/global": return "系统操作 " + body.optString("action", "");
            case "/open_app": return "打开「" + body.optString("name", "") + "」";
            case "/apps": return "读取 App 列表";
            case "/screenshot": return "截图";
            default: return path;
        }
    }

    public static void onAccessibilityConnected(AccessibilityService s) {
        service = s;
        appContext = s.getApplicationContext();
        if (isEnabled(appContext)) start(s);
        else writeConfig(appContext, false, 0, "");
    }

    public static void onAccessibilityDisconnected() {
        service = null;
        stop();
    }

    /** 首次使用时写一个 disabled 配置，让 MCP 桥能给出明确提示。 */
    public static void ensureConfigFile(Context c) {
        if (isRunning()) {
            writeConfig(c, true, port, token);
        } else if (!configFile(c).isFile()) {
            writeConfig(c, false, 0, "");
        }
    }

    public static void refreshConfig(Context c) {
        if (isRunning()) writeConfig(c, true, port, token);
        else if (!configFile(c).isFile()) writeConfig(c, false, 0, "");
    }

    private static void start(final AccessibilityService s) {
        appContext = s.getApplicationContext();
        new Thread(() -> startBlocking(s), "phone-control-start").start();
    }

    private static void startBlocking(AccessibilityService s) {
        synchronized (LOCK) {
            if (appContext == null || !isEnabled(appContext)) return;
            if (serverSocket != null && !serverSocket.isClosed()) return;
            try {
                ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                ss.setReuseAddress(true);
                serverSocket = ss;
                port = ss.getLocalPort();
                token = randomToken();
                service = s;
                appContext = s.getApplicationContext();
                writeConfig(appContext, true, port, token);
                acceptThread = new Thread(() -> {
                    while (!ss.isClosed()) {
                        try {
                            Socket sock = ss.accept();
                            Thread t = new Thread(() -> handle(sock), "phone-ctrl");
                            t.setDaemon(true);
                            t.start();
                        } catch (IOException e) {
                            break;
                        }
                    }
                }, "phone-control-accept");
                acceptThread.setDaemon(true);
                acceptThread.start();
                LocalEngine.log(appContext, "[phone-control] 已启动 127.0.0.1:" + port);
            } catch (Exception e) {
                serverSocket = null;
                port = 0;
                token = "";
                LocalEngine.log(appContext, "[phone-control] 启动失败：" + e);
                if (appContext != null) writeConfig(appContext, false, 0, "");
            }
        }
    }

    private static void stop() {
        synchronized (LOCK) {
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {
            }
            if (acceptThread != null) acceptThread.interrupt();
            serverSocket = null;
            acceptThread = null;
            port = 0;
            token = "";
            if (appContext != null) {
                writeConfig(appContext, false, 0, "");
                LocalEngine.log(appContext, "[phone-control] 已停止");
            }
        }
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static File configFile(Context c) {
        return new File(new File(LocalEngine.phoneDir(c), "data"), "phone-control.json");
    }

    private static void writeConfig(Context c, boolean enabled, int portValue, String tokenValue) {
        try {
            File f = configFile(c);
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("port", portValue);
            o.put("token", tokenValue);
            o.put("updatedAt", System.currentTimeMillis());
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            try {
                f.setReadable(true, true);
                f.setWritable(true, true);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static void handle(Socket socket) {
        Socket s = socket;
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                headBuf.write(b);
                if (headBuf.size() >= 4) {
                    byte[] h = headBuf.toByteArray();
                    int n = h.length;
                    if (h[n - 4] == 13 && h[n - 3] == 10 && h[n - 2] == 13 && h[n - 1] == 10) break;
                }
                if (headBuf.size() > 32768) break;
            }
            if (headBuf.size() == 0) return;
            String header = headBuf.toString("ISO-8859-1");
            String[] lines = header.split("\r\n");
            if (lines.length == 0) return;
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0];
            String path = first[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            int contentLength = 0;
            String auth = "";
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c < 0) continue;
                String k = lines[i].substring(0, c).trim().toLowerCase(Locale.US);
                String v = lines[i].substring(c + 1).trim();
                if (k.equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(v);
                    } catch (Exception ignored) {
                    }
                } else if (k.equals("authorization")) {
                    auth = v;
                }
            }
            if (contentLength > MAX_BODY) {
                respond(s.getOutputStream(), 413, error("请求体过大"));
                return;
            }
            byte[] bodyBytes = new byte[Math.max(0, contentLength)];
            int off = 0;
            while (off < bodyBytes.length) {
                int n = in.read(bodyBytes, off, bodyBytes.length - off);
                if (n < 0) break;
                off += n;
            }
            String bodyText = new String(bodyBytes, 0, off, StandardCharsets.UTF_8);
            if (!token.isEmpty() && !("Bearer " + token).equals(auth)) {
                respond(s.getOutputStream(), 401, error("未授权"));
                return;
            }
            if (token.isEmpty()) {
                respond(s.getOutputStream(), 503, error("手机控制未开启"));
                return;
            }
            JSONObject body = bodyText.trim().isEmpty() ? new JSONObject() : new JSONObject(bodyText);
            JSONObject result;
            if (!"POST".equals(method) && !"GET".equals(method)) {
                respond(s.getOutputStream(), 405, error("方法不支持"));
                return;
            }
            if (paused && !"/status".equals(path)) {
                respond(s.getOutputStream(), 200, error("用户正在悬浮窗插话/暂停，手机操作已暂停"));
                return;
            }
            String action = actionTextFor(path, body);
            if (!"/status".equals(path)) beginAction(action);
            switch (path) {
                case "/status":
                    result = status();
                    break;
                case "/ui":
                    result = ui(body);
                    break;
                case "/tap":
                    result = tap(body);
                    break;
                case "/tap_text":
                    result = tapText(body);
                    break;
                case "/type":
                    result = typeText(body);
                    break;
                case "/swipe":
                    result = swipe(body);
                    break;
                case "/global":
                    result = global(body);
                    break;
                case "/open_app":
                    result = openApp(body);
                    break;
                case "/apps":
                    result = apps();
                    break;
                case "/screenshot":
                    result = screenshot();
                    break;
                default:
                    result = error("未知接口：" + path);
            }
            if (!"/status".equals(path)) endAction(action, result.optBoolean("ok", false));
            audit(path, body, result);
            respond(s.getOutputStream(), 200, result);
        } catch (Exception e) {
            try {
                respond(s.getOutputStream(), 200, error(String.valueOf(e.getMessage())));
            } catch (Exception ignored) {
            }
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void respond(OutputStream out, int code, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message == null ? "未知错误" : message);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void audit(String path, JSONObject body, JSONObject result) {
        if (appContext == null) return;
        try {
            String action = path;
            if ("/type".equals(path)) {
                action += " chars=" + String.valueOf(body.optString("text", "")).length();
            } else if ("/tap_text".equals(path)) {
                action += " text=" + body.optString("text", "");
            } else if ("/tap".equals(path)) {
                action += " @" + body.optInt("x") + "," + body.optInt("y");
            } else if ("/open_app".equals(path)) {
                action += " " + body.optString("name", "");
            } else if ("/global".equals(path)) {
                action += " " + body.optString("action", "");
            }
            LocalEngine.log(appContext, "[phone] " + action + " -> ok=" + result.optBoolean("ok", false));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 工具

    private static AccessibilityService requireService() throws Exception {
        if (service == null) throw new Exception("无障碍服务未连接，请在系统设置里开启 Claude 工作台的手机控制");
        return service;
    }

    private static DisplayMetrics screenMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            android.hardware.display.DisplayManager mgr = (android.hardware.display.DisplayManager)
                    appContext.getSystemService(Context.DISPLAY_SERVICE);
            Display d = mgr != null ? mgr.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d != null) d.getRealMetrics(dm);
            else dm.setTo(android.content.res.Resources.getSystem().getDisplayMetrics());
        } catch (Exception e) {
            dm.setTo(android.content.res.Resources.getSystem().getDisplayMetrics());
        }
        return dm;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        requireService();
        FutureTask<T> task = new FutureTask<>(callable);
        MAIN.post(task);
        return task.get(8, TimeUnit.SECONDS);
    }

    private static AccessibilityNodeInfo root() throws Exception {
        return onMain(() -> requireService().getRootInActiveWindow());
    }

    private static JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("enabled", true);
        o.put("accessibility", service != null);
        o.put("running", isRunning());
        o.put("port", port);
        o.put("paused", paused);
        o.put("toolBusy", toolBusy);
        o.put("claudeRunning", claudeRunning);
        o.put("action", actionText);
        DisplayMetrics dm = screenMetrics();
        JSONObject screen = new JSONObject();
        screen.put("width", dm.widthPixels);
        screen.put("height", dm.heightPixels);
        o.put("screen", screen);
        AccessibilityNodeInfo r = root();
        if (r != null) {
            o.put("package", str(r.getPackageName()));
        }
        return o;
    }

    private static JSONObject ui(JSONObject body) throws Exception {
        AccessibilityNodeInfo r = root();
        JSONObject o = new JSONObject();
        o.put("ok", true);
        if (r == null) {
            o.put("error", "当前没有可读窗口");
            o.put("nodes", new JSONArray());
            return o;
        }
        int max = body != null ? body.optInt("max_nodes", DEFAULT_UI_NODES) : DEFAULT_UI_NODES;
        if (max <= 0) max = DEFAULT_UI_NODES;
        max = Math.min(max, MAX_UI_NODES);
        DisplayMetrics dm = screenMetrics();
        JSONObject screen = new JSONObject();
        screen.put("width", dm.widthPixels);
        screen.put("height", dm.heightPixels);
        o.put("screen", screen);
        o.put("package", str(r.getPackageName()));
        JSONArray nodes = new JSONArray();
        collect(r, nodes, 0, new int[]{0}, max);
        redactOtpCodes(nodes);
        o.put("nodes", nodes);
        o.put("count", nodes.length());
        return o;
    }

    private static void collect(AccessibilityNodeInfo node, JSONArray out, int depth, int[] count, int max) {
        if (node == null || depth > 24 || count[0] >= MAX_TREE) return;
        count[0]++;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        // 密码框绝不把内容/提示交给模型，只告诉它「这里是密码框」。
        boolean password = node.isPassword();
        String text = password ? "" : redactSensitiveDigits(str(node.getText()));
        String desc = password ? "" : redactSensitiveDigits(str(node.getContentDescription()));
        String hint = password ? "" : (Build.VERSION.SDK_INT >= 26 ? str(node.getHintText()) : "");
        boolean clickable = node.isClickable();
        boolean editable = node.isEditable();
        boolean scrollable = node.isScrollable();
        boolean checkable = node.isCheckable();
        boolean visible = node.isVisibleToUser();
        boolean interesting = !text.isEmpty() || !desc.isEmpty() || !hint.isEmpty()
                || clickable || editable || scrollable || checkable || node.isFocused()
                || node.isSelected();
        if (interesting && visible && rect.width() > 0 && rect.height() > 0 && out.length() < max) {
            try {
                JSONObject o = new JSONObject();
                o.put("text", trunc(text, 160));
                if (!desc.isEmpty()) o.put("desc", trunc(desc, 100));
                if (!hint.isEmpty()) o.put("hint", trunc(hint, 80));
                o.put("id", trunc(str(node.getViewIdResourceName()), 100));
                o.put("cls", trunc(str(node.getClassName()), 80));
                o.put("bounds", rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom);
                o.put("cx", (rect.left + rect.right) / 2);
                o.put("cy", (rect.top + rect.bottom) / 2);
                o.put("clickable", clickable);
                o.put("editable", editable);
                o.put("scrollable", scrollable);
                o.put("checkable", checkable);
                o.put("checked", node.isChecked());
                o.put("focused", node.isFocused());
                o.put("selected", node.isSelected());
                o.put("enabled", node.isEnabled());
                o.put("password", password);
                out.put(o);
            } catch (Exception ignored) {
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                collect(child, out, depth + 1, count, max);
            } catch (Exception ignored) {
            }
        }
    }

    private static List<AccessibilityNodeInfo> allNodes() throws Exception {
        AccessibilityNodeInfo r = root();
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        collectAll(r, list, 0, new int[]{0});
        return list;
    }

    private static void collectAll(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth, int[] count) {
        if (node == null || depth > 30 || count[0] >= MAX_TREE) return;
        count[0]++;
        out.add(node);
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            try {
                collectAll(node.getChild(i), out, depth + 1, count);
            } catch (Exception ignored) {
            }
        }
    }

    private static String nodeText(AccessibilityNodeInfo n) {
        if (n == null) return "";
        if (n.isPassword()) return "密码框";
        String t = str(n.getText());
        String d = str(n.getContentDescription());
        String h = Build.VERSION.SDK_INT >= 26 ? str(n.getHintText()) : "";
        return redactSensitiveDigits((t + " " + d + " " + h).trim());
    }

    /** 银行卡号、身份证号这类 13-19 位长数字不交给模型。 */
    private static String redactSensitiveDigits(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        return s.replaceAll("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)", "[数字已隐藏]");
    }

    /** 验证码界面里单独出现的 4-8 位数字也隐藏。 */
    private static void redactOtpCodes(JSONArray nodes) {
        boolean otpScreen = false;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject o = nodes.optJSONObject(i);
            if (o == null) continue;
            String s = o.optString("text", "") + " "
                + o.optString("desc", "") + " " + o.optString("hint", "");
            if (s.contains("验证码")) {
                otpScreen = true;
                break;
            }
        }
        if (!otpScreen) return;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject o = nodes.optJSONObject(i);
            if (o == null) continue;
            try {
                if (o.optString("text", "").trim().matches("\\d{4,8}")) {
                    o.put("text", "[验证码已隐藏]");
                }
                if (o.optString("desc", "").trim().matches("\\d{4,8}")) {
                    o.put("desc", "[验证码已隐藏]");
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static AccessibilityNodeInfo findNode(String query, boolean exact) throws Exception {
        if (query == null || query.trim().isEmpty()) return null;
        String q = query.trim();
        String ql = q.toLowerCase(Locale.ROOT);
        AccessibilityNodeInfo contains = null;

        // 先用系统按文字查找，避免每次都遍历整棵树；找不到再退回全量扫描。
        AccessibilityNodeInfo r = root();
        if (r != null) {
            List<AccessibilityNodeInfo> found = null;
            try { found = r.findAccessibilityNodeInfosByText(q); } catch (Exception ignored) {
            }
            if (found != null) {
                for (AccessibilityNodeInfo n : found) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    String s = nodeText(n);
                    if (s.isEmpty()) continue;
                    boolean hit = exact ? s.equals(q) : s.toLowerCase(Locale.ROOT).contains(ql);
                    if (!hit) continue;
                    AccessibilityNodeInfo clickable = clickableAncestor(n);
                    if (clickable != null) return clickable;
                    if (contains == null) contains = n;
                }
            }
        }
        if (contains != null) return contains;

        for (AccessibilityNodeInfo n : allNodes()) {
            if (n == null || !n.isVisibleToUser()) continue;
            String s = nodeText(n);
            if (s.isEmpty()) continue;
            String sl = s.toLowerCase(Locale.ROOT);
            boolean hit = exact ? s.equals(q) : sl.contains(ql);
            if (!hit) continue;
            AccessibilityNodeInfo clickable = clickableAncestor(n);
            if (clickable != null) return clickable;
            if (contains == null) contains = n;
        }
        return contains;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo focusedEditable() throws Exception {
        AccessibilityNodeInfo r = root();
        if (r != null) {
            try {
                AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f != null && f.isEditable()) return f;
            } catch (Exception ignored) {
            }
        }
        AccessibilityNodeInfo first = null;
        for (AccessibilityNodeInfo n : allNodes()) {
            if (n == null || !n.isVisibleToUser()) continue;
            if (!n.isEditable()) continue;
            if (n.isFocused()) return n;
            if (first == null) first = n;
        }
        return first;
    }

    private static JSONObject tap(JSONObject body) throws Exception {
        int x = body.optInt("x", -1);
        int y = body.optInt("y", -1);
        if (x < 0 || y < 0) return error("缺少 x/y");
        DisplayMetrics dm = screenMetrics();
        if (x > dm.widthPixels || y > dm.heightPixels) return error("坐标超出屏幕");
        boolean ok = tapAt(x, y);
        JSONObject o = new JSONObject();
        o.put("ok", ok);
        o.put("x", x);
        o.put("y", y);
        if (!ok) o.put("error", "点按失败");
        return o;
    }

    private static boolean tapAt(int x, int y) throws Exception {
        return onMain(() -> {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 40);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return requireService().dispatchGesture(g, null, null);
        });
    }

    private static boolean swipeAt(int x1, int y1, int x2, int y2, int duration) throws Exception {
        return onMain(() -> {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, Math.max(100, Math.min(2000, duration)));
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return requireService().dispatchGesture(g, null, null);
        });
    }

    private static JSONObject tapText(JSONObject body) throws Exception {
        String text = body.optString("text", "").trim();
        if (text.isEmpty()) return error("缺少 text");
        if (sensitive(text)) return needsUser("目标文字涉及密码/支付/验证码，已交还用户");
        boolean exact = body.optBoolean("exact", false);
        AccessibilityNodeInfo node = findNode(text, exact);
        if (node == null) return error("没找到文字：" + text);
        String matched = nodeText(node);
        if (sensitive(matched)) return needsUser("目标控件涉及密码/支付/验证码，已交还用户");
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        boolean clicked = false;
        String method = "gesture";
        try {
            clicked = onMain(() -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        } catch (Exception ignored) {
        }
        if (clicked) {
            method = "click";
        } else {
            clicked = tapAt((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2);
        }
        JSONObject o = new JSONObject();
        o.put("ok", clicked);
        o.put("method", method);
        o.put("matched", trunc(matched, 200));
        o.put("bounds", rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom);
        if (!clicked) o.put("error", "控件点击失败");
        return o;
    }

    private static JSONObject typeText(JSONObject body) throws Exception {
        String text = body.optString("text", "");
        if (text.isEmpty()) return error("缺少 text");
        AccessibilityNodeInfo node = focusedEditable();
        if (node == null) return error("没有找到输入框，请先用 phone_tap_text 点一下输入框");
        if (node.isPassword()) return needsUser("目标是密码框，已交还用户");
        String targetText = nodeText(node);
        if (sensitive(targetText)) return needsUser("目标输入框涉及密码/验证码/支付，已交还用户");
        if (text.matches("[0-9]{4,8}") && screenContains("验证码")) {
            return needsUser("当前页面可能是验证码界面，已交还用户");
        }
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = onMain(() -> node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b));
        if (!ok) {
            try {
                onMain(() -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS));
                ok = onMain(() -> node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b));
            } catch (Exception ignored) {
            }
        }
        JSONObject o = new JSONObject();
        o.put("ok", ok);
        o.put("chars", text.length());
        if (!ok) o.put("error", "这个输入框不支持直接写入，请手动输入");
        return o;
    }

    private static JSONObject swipe(JSONObject body) throws Exception {
        int x1 = body.optInt("x1", -1), y1 = body.optInt("y1", -1);
        int x2 = body.optInt("x2", -1), y2 = body.optInt("y2", -1);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return error("缺少坐标");
        int duration = body.optInt("duration", 350);
        boolean ok = swipeAt(x1, y1, x2, y2, duration);
        JSONObject o = new JSONObject();
        o.put("ok", ok);
        if (!ok) o.put("error", "滑动失败");
        return o;
    }

    private static JSONObject global(JSONObject body) throws Exception {
        String action = body.optString("action", "").trim().toLowerCase(Locale.US);
        int code;
        switch (action) {
            case "back": code = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "home": code = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "recents": code = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": code = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings":
            case "quick": code = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            default: return error("action 只能是 back/home/recents/notifications/quick_settings");
        }
        boolean ok = onMain(() -> requireService().performGlobalAction(code));
        JSONObject o = new JSONObject();
        o.put("ok", ok);
        o.put("action", action);
        if (!ok) o.put("error", "系统操作失败");
        return o;
    }

    private static JSONObject openApp(JSONObject body) throws Exception {
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) return error("缺少 name");
        PackageManager pm = appContext.getPackageManager();
        String pkg = "";
        String label = "";
        if (name.contains(".")) {
            Intent direct = pm.getLaunchIntentForPackage(name);
            if (direct != null) {
                pkg = name;
                label = name;
            }
        }
        if (pkg.isEmpty()) {
            List<AppEntry> apps = installedApps(pm);
            String nl = name.toLowerCase(Locale.ROOT);
            for (AppEntry e : apps) {
                if (e.label.equalsIgnoreCase(name) || e.label.toLowerCase(Locale.ROOT).contains(nl)
                        || e.pkg.equalsIgnoreCase(name)) {
                    pkg = e.pkg;
                    label = e.label;
                    break;
                }
            }
        }
        if (pkg.isEmpty()) return error("没找到 App：" + name);
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return error("这个 App 没有可启动界面：" + pkg);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appContext.startActivity(launch);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("package", pkg);
        o.put("label", label);
        return o;
    }

    private static final class AppEntry {
        final String label;
        final String pkg;

        AppEntry(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }

    private static List<AppEntry> installedApps(PackageManager pm) {
        long now = System.currentTimeMillis();
        if (cachedApps != null && now - cachedAppsAt < 60000) return cachedApps;
        List<AppEntry> out = new ArrayList<>();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                String label = str(ri.loadLabel(pm));
                if (label.isEmpty()) label = ri.activityInfo.packageName;
                out.add(new AppEntry(label, ri.activityInfo.packageName));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> a.label.compareTo(b.label));
        cachedApps = out;
        cachedAppsAt = now;
        return out;
    }

    private static JSONObject apps() {
        List<AppEntry> list = installedApps(appContext.getPackageManager());
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size() && i < 200; i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("label", list.get(i).label);
                o.put("package", list.get(i).pkg);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("apps", arr);
            o.put("count", arr.length());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject screenshot() throws Exception {
        if (Build.VERSION.SDK_INT < 30) return error("系统版本低于 Android 11，不支持无障碍截图");
        String blocked = captureBlockReason();
        if (blocked != null) {
            return needsUser("当前界面包含" + blocked + "，为避免截屏泄露已交还用户");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] png = new String[1];
        final String[] err = new String[1];
        requireService().takeScreenshot(Display.DEFAULT_DISPLAY, MAIN_EXEC,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        HardwareBuffer hb = null;
                        try {
                            hb = result.getHardwareBuffer();
                            Bitmap raw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB));
                            if (raw == null) {
                                err[0] = "截图解码失败";
                                return;
                            }
                            Bitmap src = raw;
                            if (raw.getConfig() != Bitmap.Config.ARGB_8888) {
                                Bitmap copy = raw.copy(Bitmap.Config.ARGB_8888, false);
                                if (copy != null) src = copy;
                            }
                            int max = Math.max(src.getWidth(), src.getHeight());
                            Bitmap outBmp = src;
                            if (max > 1080) {
                                float scale = 1080f / max;
                                outBmp = Bitmap.createScaledBitmap(src,
                                        Math.max(1, Math.round(src.getWidth() * scale)),
                                        Math.max(1, Math.round(src.getHeight() * scale)), true);
                            }
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            outBmp.compress(Bitmap.CompressFormat.PNG, 75, baos);
                            byte[] bytes = baos.toByteArray();
                            if (bytes.length > 6 * 1024 * 1024) {
                                err[0] = "截图太大";
                            } else {
                                png[0] = Base64.encodeToString(bytes, Base64.NO_WRAP);
                            }
                            if (outBmp != src) outBmp.recycle();
                            if (src != raw) src.recycle();
                        } catch (Exception e) {
                            err[0] = String.valueOf(e.getMessage());
                        } finally {
                            if (hb != null) hb.close();
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        err[0] = "截图失败（" + errorCode + "），当前界面可能禁止截屏";
                        latch.countDown();
                    }
                });
        if (!latch.await(10, TimeUnit.SECONDS)) return error("截图超时");
        if (err[0] != null) return error(err[0]);
        if (png[0] == null) return error("截图失败");
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("mime", "image/png");
        o.put("png", png[0]);
        return o;
    }

    private static boolean screenContains(String keyword) {
        try {
            for (AccessibilityNodeInfo n : allNodes()) {
                if (nodeText(n).contains(keyword)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 截图前检查：密码框、验证码、银行卡/转账/支付确认等界面不截图。 */
    private static String captureBlockReason() {
        String[] risky = {
            "验证码", "银行卡", "卡号", "CVV", "安全码", "身份证",
            "转账", "确认支付", "立即支付", "付款", "密码",
            "password", "passcode", "verification code", "otp",
            "bank card", "payment", "pay now", "transfer", "cvv"
        };
        try {
            for (AccessibilityNodeInfo n : allNodes()) {
                if (n.isPassword()) return "密码输入框";
                String text = nodeText(n).toLowerCase(Locale.ROOT);
                for (String w : risky) {
                    if (text.contains(w)) return w;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean sensitive(String text) {
        if (text == null) return false;
        String s = text.toLowerCase(Locale.ROOT);
        String[] words = {
                "密码", "支付", "付款", "转账", "验证码", "银行卡",
                "免密", "指纹支付", "面容支付", "确认支付", "立即支付",
                "确认购买", "提交订单",
                "password", "passcode", "verification code", "otp",
                "bank card", "payment", "pay now", "transfer", "cvv"
        };
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static JSONObject needsUser(String reason) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("needsUser", true);
            o.put("error", reason);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}

package com.missyao.claudeworkbench;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.StatFs;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * 手机本机引擎：Alpine + proot + Node + Claude Code。
 *
 * 只负责“把 Linux 环境装好、把本机服务跑起来”，聊天逻辑复用工作台的 server.js。
 * proot 运行库来自 Buddy（Apache-2.0 应用代码 / proot GPL-2.0），见 licenses/。
 */
public final class LocalEngine {
    private static final String TAG = "ClaudeLocal";
    private static final String ALPINE_VERSION = "3.24.2";
    private static final String ALPINE_SHA256 =
            "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773";
    private static final String[] ALPINE_URLS = {
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/releases/aarch64/alpine-minirootfs-"
                    + ALPINE_VERSION + "-aarch64.tar.gz",
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-"
                    + ALPINE_VERSION + "-aarch64.tar.gz"
    };
    private static final int SERVER_PORT = 4712;
    private static final int ASSET_VERSION = 100;
    private static final String GCS_BASE =
            "https://storage.googleapis.com/claude-code-dist-86c565f3-f756-42ad-8dfa-d59b1c096819/claude-code-releases";
    private static final String FALLBACK_CLAUDE_VERSION = "2.1.319";
    private static final long FALLBACK_CLAUDE_SIZE = 324104456L;
    private static final String FALLBACK_CLAUDE_SHA256 =
            "244e1aaedb73cb73571c2d5f2380f7ad251ba7e010d2747a203d631a1e7bd2e8";
    private static final Object LOCK = new Object();

    public static final int TS_PROXY_PORT = 4720;

    private static Process serverProcess;
    private static Thread watchThread;
    private static Process tsProxyProcess;
    private static Thread tsWatchThread;
    private static boolean tsProxyRunning;
    private static volatile String tsProxyEffectiveDerp;
    private static final Object TS_LOCK = new Object();
    private static boolean installing;
    private static boolean running;
    private static int currentPort;
    private static String lastError = "";

    public interface Listener {
        void onProgress(String line);
    }

    private LocalEngine() {
    }

    public static File baseDir(Context c) {
        return new File(c.getFilesDir(), "linux");
    }

    public static File alpineDir(Context c) {
        return new File(baseDir(c), "alpine");
    }

    public static File phoneDir(Context c) {
        return new File(baseDir(c), "phone");
    }

    public static File workspaceDir(Context c) {
        return new File(baseDir(c), "workspace");
    }

    public static File logFile(Context c) {
        return new File(baseDir(c), "phone.log");
    }

    /** 手机页 m.js/m.css 的缓存版本；App 升级后 MainActivity 用它清一次 WebView 缓存。 */
    public static int assetVersion() {
        return ASSET_VERSION;
    }

    public static boolean isInstalling() {
        return installing;
    }

    public static boolean isRunning() {
        return running;
    }

    public static int currentPort() {
        return currentPort;
    }

    /** 读取手机端 Node 服务的访问 token，供悬浮窗停止/插话调用本机 API。 */
    public static String readGuiToken(Context c) {
        try {
            File f = new File(new File(phoneDir(c), "data"), "gui-token.json");
            JSONObject o = new JSONObject(readText(f));
            return o.optString("token", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 调用手机端 Node 服务的本机 API（悬浮窗停止/插话用）。 */
    public static JSONObject phoneServerCall(Context c, String method, String path,
                                             JSONObject body, int timeoutMs) throws Exception {
        int port = currentPort;
        if (port <= 0) throw new IOException("手机端服务未运行");
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            con.setRequestMethod(method);
            con.setConnectTimeout(1500);
            con.setReadTimeout(timeoutMs);
            con.setRequestProperty("Accept-Encoding", "identity");
            String token = readGuiToken(c);
            if (!token.isEmpty()) con.setRequestProperty("x-gui-token", token);
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                con.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = con.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = con.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            String text = out.toString("UTF-8");
            JSONObject result;
            try {
                result = new JSONObject(text);
            } catch (Exception e) {
                result = new JSONObject();
                result.put("error", text.isEmpty() ? ("HTTP " + code) : text);
            }
            if (code < 200 || code >= 300) {
                throw new IOException(result.optString("error", "HTTP " + code));
            }
            return result;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String phonePageUrl(Context c, int port) {
        String token = readGuiToken(c);
        return "http://127.0.0.1:" + port + "/m/index.html?app=1"
            + (token.isEmpty() ? "" : "&token=" + Uri.encode(token));
    }

    /** 确认手机端服务返回的是本机手机页，而不是旧页面/登录页。 */
    public static boolean phonePageOk(Context c, int port) {
        if (port <= 0) return false;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(phonePageUrl(c, port)).openConnection();
            con.setConnectTimeout(1200);
            con.setReadTimeout(2500);
            con.setRequestProperty("Accept-Encoding", "identity");
            String token = readGuiToken(c);
            if (!token.isEmpty()) con.setRequestProperty("x-gui-token", token);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
                return out.toString("UTF-8").contains("workbench-phone");
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** 校验失败时把状态码和页面开头写进日志，方便判断是登录页、404 还是旧页面。 */
    public static String phonePageProbe(Context c, int port) {
        if (port <= 0) return "port=0";
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(phonePageUrl(c, port)).openConnection();
            con.setConnectTimeout(1200);
            con.setReadTimeout(2500);
            con.setRequestProperty("Accept-Encoding", "identity");
            String token = readGuiToken(c);
            if (!token.isEmpty()) con.setRequestProperty("x-gui-token", token);
            int code = con.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 600) out.write(buf, 0, n);
            }
            String text = out.toString("UTF-8").replace('\n', ' ').trim();
            if (!token.isEmpty()) text = text.replace(token, "***");
            if (text.length() > 260) text = text.substring(0, 260);
            return "HTTP " + code + " " + text;
        } catch (Exception e) {
            return "probe error: " + e;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public static String lastError() {
        return lastError;
    }

    public static boolean isInstalled(Context c) {
        return new File(alpineDir(c), "etc/alpine-release").isFile()
                && new File(alpineDir(c), "usr/bin/node").isFile()
                && new File(alpineDir(c), "root/.local/bin/claude").isFile()
                && new File(phoneDir(c), "server.js").isFile();
    }

    /**
     * 清空手机端全部数据：Alpine/Node/Claude、/workspace、对话、配置、日志和 token。
     * 不删除 /sdcard 里用户自己的文件；也不删除电脑端数据。
     */
    public static void clearAllData(Context c) throws Exception {
        synchronized (LOCK) {
            try { EngineService.stop(c); } catch (Exception ignored) {
            }
            stopServer(c);
            stopTailscaleProxy(c);
            try { PhoneControlServer.setEnabled(c, false); } catch (Exception ignored) {
            }
            deleteTree(baseDir(c));
            File ext = c.getExternalFilesDir(null);
            if (ext != null) deleteTree(ext);
            File cache = c.getCacheDir();
            File[] cached = cache != null ? cache.listFiles() : null;
            if (cached != null) {
                for (File f : cached) deleteTree(f);
            }
            c.getSharedPreferences("local", Context.MODE_PRIVATE).edit().clear().apply();
            c.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().clear().apply();
            c.getSharedPreferences("phone_control", Context.MODE_PRIVATE).edit().clear().apply();
            running = false;
            currentPort = 0;
            tsProxyRunning = false;
        }
    }

    /** 重装前备份对话，再删掉根文件系统；install() 解压后会自动恢复。 */
    public static void resetForReinstall(Context c) throws Exception {
        synchronized (LOCK) {
            if (installing) throw new IllegalStateException("正在安装，请稍候");
            stopServer(c);
            File base = baseDir(c);
            File backup = new File(base, "reset-backup");
            deleteTree(backup);
            File root = alpineDir(c);
            File claudeDir = new File(root, "root/.claude");
            File claudeJson = new File(root, "root/.claude.json");
            backup.mkdirs();
            if (claudeDir.isDirectory()) copyTree(claudeDir, new File(backup, ".claude"));
            if (claudeJson.isFile()) copy(claudeJson, new File(backup, ".claude.json"));
            deleteTree(root);
            deleteTree(phoneDir(c));
            File cache = new File(c.getCacheDir(), "alpine.tar.gz");
            cache.delete();
            log(c, "已重置手机端环境，对话备份在 " + backup);
        }
    }

    private static void restoreBackup(Context c) {
        File backup = new File(baseDir(c), "reset-backup");
        if (!backup.isDirectory()) return;
        try {
            File root = new File(alpineDir(c), "root");
            File bClaude = new File(backup, ".claude");
            if (bClaude.isDirectory()) copyTree(bClaude, new File(root, ".claude"));
            File bJson = new File(backup, ".claude.json");
            if (bJson.isFile()) copy(bJson, new File(root, ".claude.json"));
            deleteTree(backup);
            log(c, "已恢复手机端对话记录");
        } catch (Exception e) {
            log(c, "恢复对话备份失败：" + e);
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File kid : kids) copyTree(kid, new File(dst, kid.getName()));
            }
        } else {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            copy(src, dst);
        }
    }

    /** 安装/修复手机端环境。可重复调用，已经完成的步骤会跳过。 */
    public static void install(Context c, Listener listener) throws Exception {
        synchronized (LOCK) {
            if (installing) throw new IllegalStateException("正在安装，请稍候");
            installing = true;
            lastError = "";
            final Listener logListener = line -> {
                log(c, line);
                if (listener != null) listener.onProgress(line);
            };
            try {
                ensureSpace(c);
                installRootfs(c, logListener);
                copyAssets(c, logListener);
                prepareDirs(c);
                PhoneControlServer.ensureConfigFile(c);
                progress(logListener, "检查 proot 兼容性…");
                String probe = exec(c, "uname -m && echo PROOT_OK", 60);
                if (!probe.contains("PROOT_OK")) {
                    throw new IOException("proot 无法启动：" + tail(probe, 800));
                }
                String mount = exec(c, "test -f /opt/workbench/server.js"
                    + " && test -f /opt/workbench/start.sh && echo MOUNT_OK", 30);
                if (!mount.contains("MOUNT_OK")) {
                    throw new IOException("手机端资源挂载失败：" + tail(mount, 600));
                }
                LocalConfig.writeClaudeSettings(c, alpineDir(c));
                LocalConfig.writeClaudeJson(c, alpineDir(c));
                ensurePackages(c, logListener);
                installClaude(c, logListener);
                verify(c, logListener);
                writeMarker(c, "installed");
                cleanupBundledAssets(c);
                progress(logListener, "手机端环境已就绪");
            } catch (Exception e) {
                lastError = String.valueOf(e.getMessage());
                log(c, "安装失败：" + Log.getStackTraceString(e));
                throw e;
            } finally {
                installing = false;
            }
        }
    }

    /** 安装成功后删掉已经用不到的离线包副本，给手机省 180 MB 左右。 */
    private static void cleanupBundledAssets(Context c) {
        try {
            deleteTree(new File(phoneDir(c), "apk"));
            File tgz = new File(phoneDir(c), "claude-musl.tgz");
            if (tgz.isFile()) tgz.delete();
            File rootfs = new File(phoneDir(c),
                "alpine-minirootfs-" + ALPINE_VERSION + "-aarch64.tar.gz");
            if (rootfs.isFile()) rootfs.delete();
        } catch (Exception ignored) {
        }
    }

    private static void ensureSpace(Context c) throws IOException {
        StatFs stat = new StatFs(c.getFilesDir().getAbsolutePath());
        long free = stat.getAvailableBytes();
        if (free < 1800L * 1024 * 1024) {
            throw new IOException("剩余空间不足 1.8 GB（当前 " + (free / 1024 / 1024) + " MB），请清理后再安装");
        }
    }

    private static void installRootfs(Context c, Listener listener) throws Exception {
        File root = alpineDir(c);
        if (new File(root, "etc/alpine-release").isFile()) return;
        File cache = new File(c.getCacheDir(), "alpine.tar.gz");
        boolean ok = cache.isFile() && ALPINE_SHA256.equalsIgnoreCase(sha256(cache));
        if (!ok) {
            // APK 内置了 Alpine 根文件系统（约 3.8 MB），优先离线使用。
            progress(listener, "解压内置 Alpine 根文件系统…");
            String assetName = "alpine-minirootfs-" + ALPINE_VERSION + "-aarch64.tar.gz";
            try (InputStream in = c.getAssets().open("phone/" + assetName);
                 FileOutputStream out = new FileOutputStream(cache)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                ok = ALPINE_SHA256.equalsIgnoreCase(sha256(cache));
                if (!ok) {
                    cache.delete();
                    log(c, "内置 Alpine 校验失败，改为联网下载");
                }
            } catch (Exception e) {
                cache.delete();
                log(c, "读取内置 Alpine 失败，改为联网下载：" + e.getMessage());
            }
        }
        if (!ok) {
            progress(listener, "下载 Alpine 根文件系统…");
            Exception last = null;
            for (String url : ALPINE_URLS) {
                try {
                    download(c, url, cache, listener);
                    String sum = sha256(cache);
                    if (!ALPINE_SHA256.equalsIgnoreCase(sum)) {
                        throw new IOException("Alpine 校验失败：" + sum);
                    }
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    log(c, "Alpine 下载失败 " + url + "：" + e.getMessage());
                }
            }
            if (last != null) throw last;
        }

        File parent = root.getParentFile();
        if (parent != null) parent.mkdirs();
        File staging = new File(parent, "alpine.partial");
        deleteTree(staging);
        staging.mkdirs();
        progress(listener, "解压 Alpine…");
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(cache)))) {
            untar(in, staging);
        }
        writeFile(new File(staging, "etc/resolv.conf"), "nameserver 223.5.5.5\nnameserver 1.1.1.1\n");
        writeFile(new File(staging, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n");
        new File(staging, "root").mkdirs();
        new File(staging, "tmp").mkdirs();
        chmod(new File(staging, "tmp"), 0777);
        deleteTree(root);
        if (!staging.renameTo(root)) throw new IOException("Alpine 解压目录移动失败");
        restoreBackup(c);
        cache.delete();
        progress(listener, "Alpine 已就绪");
    }

    private static boolean copyAssets(Context c, Listener listener) throws Exception {
        File dest = phoneDir(c);
        File marker = new File(dest, "asset-version");
        if (marker.isFile()) {
            try {
                String v = readText(marker).trim();
                if (String.valueOf(ASSET_VERSION).equals(v)
                        && assetEquals(c, "phone/server.js", new File(dest, "server.js"))
                        && assetEquals(c, "phone/public/m.html", new File(dest, "public/m.html"))
                        && assetEquals(c, "phone/phone-mcp.js", new File(dest, "phone-mcp.js"))) {
                    return false;
                }
                log(c, "手机端资源与 APK 不一致，强制刷新");
            } catch (Exception ignored) {
            }
        }
        progress(listener, "部署手机端界面…");
        deleteTree(new File(dest, "public"));
        deleteTree(new File(dest, "server.js"));
        deleteTree(new File(dest, "start.sh"));
        dest.mkdirs();
        copyAssetDir(c.getAssets(), "phone", dest);
        chmod(new File(dest, "start.sh"), 0755);
        File tsproxy = new File(dest, "tsproxy");
        if (tsproxy.isFile()) chmod(tsproxy, 0755);
        writeFile(marker, String.valueOf(ASSET_VERSION));
        return true;
    }

    private static void prepareDirs(Context c) throws Exception {
        File root = alpineDir(c);
        new File(root, "root/.claude").mkdirs();
        new File(root, "workspace").mkdirs();
        new File(root, "sdcard").mkdirs();
        new File(root, "opt/workbench").mkdirs();
        new File(root, "tmp").mkdirs();
        chmod(new File(root, "tmp"), 0777);
        workspaceDir(c).mkdirs();
        File data = new File(phoneDir(c), "data");
        new File(data, "uploads").mkdirs();
    }

    private static void ensurePackages(Context c, Listener listener) throws Exception {
        if (new File(alpineDir(c), "usr/bin/node").isFile()
                && new File(alpineDir(c), "usr/bin/npm").isFile()
                && new File(alpineDir(c), "usr/bin/curl").isFile()) {
            return;
        }
        // APK 里带了完整的 Alpine aarch64 包；先在本地仓库离线安装。
        File bundledApk = new File(phoneDir(c), "apk");
        File[] apkFiles = bundledApk.listFiles((dir, name) -> name.endsWith(".apk"));
        if (apkFiles != null && apkFiles.length > 0) {
            progress(listener, "安装内置工具和 Node（无需下载）…");
            String cmd = "set +e; "
                + "cp /etc/apk/repositories /tmp/repos.bak 2>/dev/null; "
                + "printf '/opt/workbench/apk\\n' > /etc/apk/repositories; "
                + "cd /opt/workbench/apk && apk index -o APKINDEX.tar.gz *.apk "
                + ">/tmp/apk-index.log 2>&1; "
                + "apk add --no-network --allow-untrusted "
                + "bash curl jq libgcc libstdc++ ripgrep git ca-certificates "
                + "procps coreutils findutils nodejs npm "
                + ">/tmp/apk-offline.log 2>&1; RC=$?; "
                + "if [ $RC -ne 0 ]; then "
                + "apk add --no-network --allow-untrusted /opt/workbench/apk/*.apk "
                + ">>/tmp/apk-offline.log 2>&1; RC=$?; fi; "
                + "if [ -s /tmp/repos.bak ]; then "
                + "cp /tmp/repos.bak /etc/apk/repositories; "
                + "else printf 'https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/main\\n"
                + "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/community\\n' "
                + "> /etc/apk/repositories; fi; "
                + "if [ $RC -eq 0 ]; then echo TOOLS_OK; "
                + "else tail -20 /tmp/apk-offline.log; fi";
            String out = exec(c, cmd, 1800);
            if (out.contains("TOOLS_OK")) {
                deleteTree(bundledApk);
                log(c, "内置 Alpine 包安装完成");
                progress(listener, "Node 与工具已安装（内置）");
                return;
            }
            log(c, "内置包安装失败，改用在线源：" + tail(out, 600));
        }

        progress(listener, "安装 Alpine 工具和 Node（约 1-3 分钟）…");
        File repos = new File(alpineDir(c), "etc/apk/repositories");
        String tsinghua = "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/main\n"
                + "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/community\n";
        String official = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n"
                + "https://dl-cdn.alpinelinux.org/alpine/v3.24/community\n";
        String cmd = "apk update -q && apk add -q bash curl jq libgcc libstdc++ ripgrep git "
                + "ca-certificates procps coreutils findutils nodejs npm && echo TOOLS_OK";
        writeFile(repos, tsinghua);
        String out = exec(c, cmd, 1800);
        if (!out.contains("TOOLS_OK")) {
            log(c, "清华 Alpine 镜像失败，改用官方源重试");
            writeFile(repos, official);
            out = exec(c, cmd, 1800);
        }
        if (!out.contains("TOOLS_OK")) {
            throw new IOException("安装工具失败：" + tail(out, 600));
        }
        progress(listener, "Node 与工具已安装");
    }

    private static void installClaude(Context c, Listener listener) throws Exception {
        File bin = new File(alpineDir(c), "root/.local/bin/claude");
        if (bin.isFile()) return;

        if (installBundledClaude(c, listener, bin)) return;

        Exception last = null;
        try {
            downloadClaudeBinary(c, listener, bin);
            return;
        } catch (Exception e) {
            last = e;
            log(c, "GCS 安装 Claude Code 失败：" + e.getMessage());
        }

        // 备用：官方安装脚本（部分地区可访问 claude.ai 时会成功）
        progress(listener, "改用官方安装脚本…");
        try {
            String cmd = "curl -fsSL --connect-timeout 20 --max-time 900 "
                    + "https://claude.ai/install.sh -o /tmp/claude-install.sh"
                    + " && bash /tmp/claude-install.sh"
                    + " && /root/.local/bin/claude --version && echo AGENT_OK";
            String out = exec(c, cmd, 2400);
            if (out.contains("AGENT_OK") && bin.isFile()) {
                new File(phoneDir(c), "legacy-cli").delete();
                progress(listener, "Claude Code 已安装");
                return;
            }
            last = new IOException("脚本输出：" + tail(out, 600));
        } catch (Exception e) {
            last = e;
        }

        // 备用 2：npm 2.1.112 —— 最后一个还带 cli.js 的版本，国内镜像可下。
        // 它不支持新版 manual 权限模式，服务端会用 CLAUDE_LEGACY=1 切到 default。
        progress(listener, "尝试 npm 备用版（2.1.112）…");
        try {
            String cmd = "if npm install -g @anthropic-ai/claude-code@2.1.112 "
                    + "--registry=https://registry.npmmirror.com --no-audit --no-fund >/tmp/npm.log 2>&1; then "
                    + "CLI=$(readlink -f \"$(command -v claude)\" 2>/dev/null); "
                    + "[ -n \"$CLI\" ] || CLI=$(find /usr/local/lib/node_modules /usr/lib/node_modules "
                    + "-path '*/@anthropic-ai/claude-code/cli.js' 2>/dev/null | head -1); "
                    + "[ -n \"$CLI\" ] || { echo CLI_NOT_FOUND; exit 1; }; "
                    + "mkdir -p /root/.local/bin"
                    + " && ln -sf \"$CLI\" /root/.local/bin/claude"
                    + " && chmod +x \"$CLI\""
                    + " && /root/.local/bin/claude --version && echo AGENT_OK; "
                    + "else tail -30 /tmp/npm.log; fi";
            String out = exec(c, cmd, 1800);
            if (out.contains("AGENT_OK") && bin.exists()) {
                writeMarker(c, "legacy-cli");
                progress(listener, "Claude Code 备用版已安装");
                return;
            }
            last = new IOException("npm 输出：" + tail(out, 600));
        } catch (Exception e) {
            last = e;
        }
        throw new IOException("安装 Claude Code 失败：" + (last == null ? "未知错误" : last.getMessage()));
    }

    /**
     * APK 内置的 Claude Code 原生包。放在 assets/phone/claude-musl.tgz，
     * 安装时复制到 /opt/workbench，再用 proot 里的 tar 解出 package/claude。
     * 这样首次安装不需要从 GCS 下 324 MB，国内也不用挂 VPN。
     */
    private static boolean installBundledClaude(Context c, Listener listener, File bin) {
        File tgz = new File(phoneDir(c), "claude-musl.tgz");
        if (!tgz.isFile()) return false;
        progress(listener, "安装内置 Claude Code（无需下载）…");
        try {
            String out = exec(c,
                "set -e; rm -rf /tmp/cc-bundle; mkdir -p /tmp/cc-bundle /root/.local/bin; "
                    + "tar -xzf /opt/workbench/claude-musl.tgz -C /tmp/cc-bundle package/claude; "
                    + "rm -f /root/.local/bin/claude; "
                    + "mv /tmp/cc-bundle/package/claude /root/.local/bin/claude; "
                    + "chmod 755 /root/.local/bin/claude; "
                    + "rm -rf /tmp/cc-bundle; "
                    + "/root/.local/bin/claude --version && echo AGENT_OK", 900);
            if (out.contains("AGENT_OK") && bin.isFile()) {
                new File(phoneDir(c), "legacy-cli").delete();
                // 安装完成后删掉 144 MB 压缩包；APK 里仍有，重装时会重新复制。
                tgz.delete();
                log(c, "内置 Claude Code 已安装：" + out.trim());
                progress(listener, "内置 Claude Code 已安装");
                return true;
            }
            log(c, "内置 Claude Code 无法运行：" + tail(out, 600));
        } catch (Exception e) {
            log(c, "内置 Claude Code 安装失败：" + e.getMessage());
        }
        return false;
    }

    /** 从官方 GCS 下载 linux-arm64-musl 单文件，校验后放进 ~/.local/bin。 */
    private static void downloadClaudeBinary(Context c, Listener listener, File bin) throws Exception {
        String version = FALLBACK_CLAUDE_VERSION;
        long size = FALLBACK_CLAUDE_SIZE;
        String sha = FALLBACK_CLAUDE_SHA256;
        try {
            String latest = fetchText(GCS_BASE + "/latest", 20).trim();
            if (latest.matches("[0-9][0-9.]*")) version = latest;
        } catch (Exception e) {
            log(c, "读取 Claude Code 最新版本失败，使用内置版本：" + e.getMessage());
        }
        try {
            JSONObject manifest = new JSONObject(fetchText(GCS_BASE + "/" + version + "/manifest.json", 30));
            JSONObject p = manifest.getJSONObject("platforms").getJSONObject("linux-arm64-musl");
            size = p.optLong("size", size);
            String s = p.optString("checksum", "");
            if (s.length() == 64) sha = s;
        } catch (Exception e) {
            log(c, "读取 Claude Code manifest 失败，使用内置校验值：" + e.getMessage());
            version = FALLBACK_CLAUDE_VERSION;
            size = FALLBACK_CLAUDE_SIZE;
            sha = FALLBACK_CLAUDE_SHA256;
        }

        File dir = new File(baseDir(c), "downloads");
        dir.mkdirs();
        File tmp = new File(dir, "claude-" + version + ".part");
        progress(listener, "下载 Claude Code " + version + "（约 " + (size / 1024 / 1024) + " MB）…");

        String[] urls = {
                GCS_BASE + "/" + version + "/linux-arm64-musl/claude",
                "https://downloads.claude.ai/claude-code-releases/" + version + "/linux-arm64-musl/claude"
        };
        Exception last = null;
        boolean ok = false;
        for (String url : urls) {
            for (int attempt = 1; attempt <= 2 && !ok; attempt++) {
                try {
                    downloadFile(url, tmp, size, listener, "Claude Code");
                    String got = sha256(tmp);
                    if (!sha.equalsIgnoreCase(got)) {
                        tmp.delete();
                        throw new IOException("校验失败：" + got);
                    }
                    ok = true;
                } catch (Exception e) {
                    last = e;
                    log(c, "下载 Claude Code 失败（" + url + "，第 " + attempt + " 次）：" + e.getMessage());
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            if (ok) break;
        }
        if (!ok) throw new IOException("下载 Claude Code 失败：" + (last == null ? "未知错误" : last.getMessage()));

        File parent = bin.getParentFile();
        if (parent != null) parent.mkdirs();
        deleteTree(bin);
        if (!tmp.renameTo(bin)) {
            copy(tmp, bin);
            tmp.delete();
        }
        chmod(bin, 0755);
        String out = exec(c, "/root/.local/bin/claude --version && echo AGENT_OK", 180);
        if (!out.contains("AGENT_OK")) {
            throw new IOException("Claude Code 下载后无法运行：" + tail(out, 600));
        }
        new File(phoneDir(c), "legacy-cli").delete();
        log(c, "Claude Code 已安装：" + out.trim());
        progress(listener, "Claude Code 已安装");
    }

    private static String fetchText(String url, int timeoutSec) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(timeoutSec * 1000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "ClaudeWorkbench/2.0 (Android)");
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            con.disconnect();
        }
    }

    private static void downloadFile(String url, File dest, long expected, Listener listener, String label)
            throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(60000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "ClaudeWorkbench/2.1 (Android)");
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = con.getContentLengthLong();
            if (total <= 0) total = expected;
            long read = 0;
            int lastPct = -1;
            long lastCheck = System.currentTimeMillis();
            long lastBytes = 0;
            try (InputStream in = new BufferedInputStream(con.getInputStream());
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int pct = (int) (read * 100 / total);
                        if (pct >= lastPct + 5) {
                            lastPct = pct;
                            progress(listener, "下载 " + label + "… " + pct + "%");
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastCheck >= 15000) {
                        long delta = read - lastBytes;
                        long rate = delta * 1000 / Math.max(1, now - lastCheck);
                        if (rate < 40L * 1024) {
                            throw new IOException("下载速度过慢（" + (rate / 1024) + " KB/s），切换下载源");
                        }
                        lastCheck = now;
                        lastBytes = read;
                    }
                }
            }
            if (expected > 0 && read != expected) {
                throw new IOException("文件大小不符：期望 " + expected + "，实际 " + read);
            }
        } finally {
            con.disconnect();
        }
    }

    private static void verify(Context c, Listener listener) throws Exception {
        progress(listener, "检查运行环境…");
        String out = exec(c, "node --version && /root/.local/bin/claude --version && echo VERIFY_OK", 120);
        if (!out.contains("VERIFY_OK")) {
            throw new IOException("运行环境检查失败：" + tail(out, 500));
        }
        log(c, "环境检查通过：" + out.trim());
    }

    /** 启动本机服务，返回实际端口。 */
    public static int startServer(Context c, Listener listener) throws Exception {
        synchronized (LOCK) {
            if (!isInstalled(c)) throw new IOException("手机端环境尚未安装");
            // App 升级后即使环境已装好，也要按 ASSET_VERSION 刷新 server.js / 手机页 / MCP 资源。
            boolean assetsChanged = false;
            try {
                assetsChanged = copyAssets(c, listener);
            } catch (Exception e) {
                log(c, "刷新手机端资源失败，继续用现有文件：" + e);
            }
            prepareDirs(c);
            LocalConfig.writeClaudeSettings(c, alpineDir(c));
            LocalConfig.writeClaudeJson(c, alpineDir(c));
            PhoneControlServer.refreshConfig(c);

            boolean serving = running && ping(currentPort);
            if (serving && !assetsChanged) return currentPort;
            stopServer(c);
            // 上一次 App 进程退出后，proot 里的 node 可能还活着并占着 4712；
            // 只杀已知子进程不够，否则新 server 会换端口，App 探测仍连到旧登录页。
            try {
                // 只匹配 Node 进程自身的命令行，避免 pkill 匹配到执行它的 proot 包装进程。
                // 同时覆盖 node server.js 与 node /opt/workbench/server.js 两种启动方式。
                exec(c, "pkill -f '^node .*server\\.js' 2>/dev/null || true;"
                    + " pkill -f '^/usr/bin/node .*server\\.js' 2>/dev/null || true", 15);
                log(c, "已清理旧手机端 Node 进程");
            } catch (Exception e) {
                log(c, "清理旧手机端 Node 失败：" + e.getMessage());
            }

            File alpine = alpineDir(c);
            File start = new File(phoneDir(c), "start.sh");
            log(c, "启动前检查：node=" + new File(alpine, "usr/bin/node").isFile()
                + " claude=" + new File(alpine, "root/.local/bin/claude").exists()
                + " server=" + new File(phoneDir(c), "server.js").isFile()
                + " start=" + start.isFile());
            String mount = exec(c, "test -f /opt/workbench/server.js && echo MOUNT_OK", 30);
            if (!mount.contains("MOUNT_OK")) {
                throw new IOException("手机端资源挂载失败：" + tail(mount, 600));
            }
            progress(listener, "启动手机端服务…");
            List<String> cmd = buildCommand(c, "cd /opt/workbench && exec node server.js", true);
            log(c, "启动命令：" + cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            prepareProcess(pb, c);
            log(c, "\n===== " + new java.util.Date() + " 启动手机端服务 =====");
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(c)));
            serverProcess = pb.start();
            try {
                serverProcess.getOutputStream().close();
            } catch (Exception ignored) {
            }

            long deadline = System.currentTimeMillis() + 90000;
            while (System.currentTimeMillis() < deadline) {
                int port = readRuntimePort(c);
                if (port <= 0) port = SERVER_PORT;
                // 不能只看端口能 ping：旧 Node 可能还活着并返回登录页。
                // 必须返回带 workbench-phone 标记的手机页，才采用这个端口。
                if (ping(port) && phonePageOk(c, port)) {
                    running = true;
                    currentPort = port;
                    startWatch();
                    progress(listener, "手机端服务已启动");
                    return port;
                }
                // 万一 runtime.json 没读到/写到不同位置，直接扫本机端口兜底
                for (int p = SERVER_PORT; p < SERVER_PORT + 20; p++) {
                    if (p == port) continue;
                    if (ping(p) && phonePageOk(c, p)) {
                        running = true;
                        currentPort = p;
                        startWatch();
                        progress(listener, "手机端服务已启动");
                        return p;
                    }
                }
                if (!serverProcess.isAlive()) {
                    String tail = tail(readLog(c), 2000);
                    throw new IOException("手机端服务启动失败：" + tail);
                }
                Thread.sleep(500);
            }
            throw new IOException("手机端服务启动超时，最近日志：" + tail(readLog(c), 1500));
        }
    }

    public static void stopServer(Context c) {
        synchronized (LOCK) {
            Process p = serverProcess;
            serverProcess = null;
            running = false;
            currentPort = 0;
            if (watchThread != null) {
                watchThread.interrupt();
                watchThread = null;
            }
            if (p != null) {
                try {
                    p.destroy();
                    if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (Exception ignored) {
                    try {
                        p.destroyForcibly();
                    } catch (Exception ignored2) {
                    }
                }
            }
            // proot 以 --kill-on-exit 启动，父进程退出会带走 Node 子进程。
            // 这里不再跑 pkill，避免停止时又启动一次 proot 导致主线程卡顿。
        }
    }

    /** 看住 proot 父进程；它退出时把 running 置回 false，主页和重试才不会连死端口。 */
    private static void startWatch() {
        if (watchThread != null) watchThread.interrupt();
        watchThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) return;
                Process p = serverProcess;
                if (p != null && !p.isAlive()) {
                    running = false;
                    currentPort = 0;
                    return;
                }
            }
        }, "phone-server-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    public static boolean hasTsState(Context c) {
        File dir = new File(new File(phoneDir(c), "data"), "tsnet");
        File[] kids = dir.listFiles();
        return kids != null && kids.length > 0;
    }

    public static boolean isTailscaleProxyRunning() {
        return tsProxyRunning;
    }

    public static JSONObject tailscaleStatus() {
        return tsStatus();
    }

    /**
     * 部分国内 5G 会返回 Tailscale 控制面的 IPv6，但实际没有 IPv6 路由。
     * 把控制面的 IPv4 写进 proot 的 /etc/hosts，让 tsnet 优先走 IPv4。
     */
    private static void ensureTsHosts(Context c) {
        try {
            File hosts = new File(alpineDir(c), "etc/hosts");
            String existing = hosts.isFile() ? readText(hosts) : "";
            StringBuilder keep = new StringBuilder();
            for (String line : existing.split("\\r?\\n")) {
                String l = line.trim();
                if (l.contains("controlplane.tailscale.com")
                    || l.contains("login.tailscale.com")
                    || l.contains("derp1c.tailscale.com")) {
                    continue;
                }
                if (!l.isEmpty()) keep.append(line).append('\n');
            }
            StringBuilder add = new StringBuilder();
            String[][] fallback = {
                {"controlplane.tailscale.com", "192.200.0.104"},
                {"controlplane.tailscale.com", "192.200.0.105"},
                {"controlplane.tailscale.com", "192.200.0.106"},
                {"controlplane.tailscale.com", "192.200.0.107"},
                {"controlplane.tailscale.com", "192.200.0.110"},
                {"controlplane.tailscale.com", "192.200.0.113"},
                {"derp1c.tailscale.com", "104.248.8.210"}
            };
            for (String host : new String[]{
                "controlplane.tailscale.com", "derp1c.tailscale.com", "login.tailscale.com"
            }) {
                boolean any = false;
                try {
                    for (InetAddress a : InetAddress.getAllByName(host)) {
                        if (a instanceof Inet4Address) {
                            add.append(a.getHostAddress()).append(' ').append(host).append('\n');
                            any = true;
                        }
                    }
                } catch (Exception e) {
                    log(c, "解析 " + host + " 失败：" + e.getMessage());
                }
                if (!any) {
                    for (String[] f : fallback) {
                        if (f[0].equals(host)) {
                            add.append(f[1]).append(' ').append(host).append('\n');
                        }
                    }
                }
            }
            writeFile(hosts, keep.toString() + add.toString());
            log(c, "内置 Tailscale hosts 已更新：" + add.toString().replace('\n', ' '));
        } catch (Exception e) {
            log(c, "写内置 Tailscale hosts 失败：" + e);
        }
    }

    /**
     * Android 的默认路由在策略路由表里，proot 里的 Linux netmon 读 /proc/net/route 会看到空默认路由。
     * 给 tsproxy 单独绑定一个只含默认路由的 /proc/net/route，告诉它当前活动网卡。
     */
    private static String activeNetworkInterface(Context c) {
        String iface = "rmnet_data2";
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
                if (lp != null && lp.getInterfaceName() != null && !lp.getInterfaceName().isEmpty()) {
                    iface = lp.getInterfaceName();
                }
            }
        } catch (Exception e) {
            log(c, "读取活动网卡失败，使用 " + iface + "：" + e.getMessage());
        }
        return iface;
    }

    /**
     * 空配置 = 智能策略：有全局 IPv6 让 Tailscale 自动选，IPv4-only 时用 2（SFO）。
     * 显式填 2 或其他数字 = 强制该区域，不再按 IPv6 改写。
     */
    private static String desiredDerp(Context c) {
        String configured = LocalConfig.tsDerp(c);
        if (!configured.isEmpty()) return configured;
        return hasPhysicalIpv6(c) ? "" : "2";
    }

    /** 网络变化后，当前网络应使用的 DERP 是否和正在跑的代理不一致。 */
    public static boolean tsDerpPolicyChanged(Context c) {
        String current = tsProxyEffectiveDerp;
        if (current == null) {
            // App 进程重启后从持久化记录恢复；没有记录时宁可重建一次，
            // 也不要在升级后继续用旧策略（例如旧的强制 SFO）。
            current = LocalConfig.tsDerpEffective(c);
            tsProxyEffectiveDerp = current;
            if (current == null) return true;
        }
        return !current.equals(desiredDerp(c));
    }

    private static boolean linkHasGlobalIpv6(LinkProperties lp) {
        if (lp == null) return false;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (!(a instanceof Inet6Address)
                    || a.isLinkLocalAddress() || a.isLoopbackAddress()) {
                continue;
            }
            byte[] b = a.getAddress();
            // 只认全局单播 2000::/3；ULA(fc/fd)、组播等不算“能直连的公网 IPv6”。
            if (b != null && b.length == 16 && (b[0] & 0xe0) == 0x20) return true;
        }
        return false;
    }

    /** 当前活动网络是否有公网 IPv6；有 IPv6 时 Tailscale 更容易直连。 */
    private static boolean hasPhysicalIpv6(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            // 先看当前活动网络：手机常见 Wi-Fi 在用、闲置蜂窝仍有 IPv6，
            // 不能因为闲置网络有 IPv6 就把当前 Wi-Fi 误判成 IPv6 网络。
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    return linkHasGlobalIpv6(cm.getLinkProperties(active));
                }
            }
            // 活动网络是 VPN 等无法直接判断时，再看物理网络。
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null) continue;
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    continue;
                }
                if (linkHasGlobalIpv6(cm.getLinkProperties(n))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static File ensureTsRoute(Context c) {
        String iface = activeNetworkInterface(c);
        File f = new File(phoneDir(c), "ts-route");
        String content = "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT\n"
            + iface + "\t00000000\t00000000\t0001\t0\t0\t0\t00000000\t0\t0\t0\n";
        try {
            writeFile(f, content);
            log(c, "内置 Tailscale 默认网卡：" + iface);
        } catch (Exception e) {
            log(c, "写 ts-route 失败：" + e);
        }
        return f;
    }

    /** 当前 peer 是否在线；null 表示状态接口不可用/未知。 */
    public static Boolean tsPeerOnline(Context c) {
        JSONObject st = tsStatus();
        if (st == null) return null;
        return st.optBoolean("peerOnline", false);
    }

    private static JSONObject tsStatus() {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL("http://127.0.0.1:" + TS_PROXY_PORT + "/__tsproxy/status").openConnection();
            con.setConnectTimeout(800);
            // 状态接口现在会顺带查一次 tailnet 线路，给 2 秒读超时。
            con.setReadTimeout(2000);
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** 启动内置 tsnet 代理，等它真正连上 tailnet 再返回。 */
    public static void startTailscaleProxy(Context c, Listener listener) throws Exception {
        startTailscaleProxy(c, listener, 1);
    }

    private static void startTailscaleProxy(Context c, Listener listener, int attempt) throws Exception {
        synchronized (TS_LOCK) {
            if (!isInstalled(c)) throw new IOException("手机端环境尚未安装");
            String pcUrl = LocalConfig.tsPcUrl(c);
            String target = LocalConfig.tsTargetUrl(c);
            String key = LocalConfig.tsAuthKey(c);
            if (pcUrl.isEmpty() || target.isEmpty()) {
                throw new IOException("还没有配置电脑的 Tailscale 地址");
            }
            if (key.isEmpty() && !hasTsState(c)) {
                throw new IOException("首次使用需要 Tailscale auth key");
            }
            try {
                copyAssets(c, listener);
            } catch (Exception e) {
                log(c, "刷新 tsproxy 资源失败，继续用现有文件：" + e);
            }
            prepareDirs(c);
            File bin = new File(phoneDir(c), "tsproxy");
            if (!bin.isFile()) throw new IOException("tsproxy 未安装，请重新安装手机端环境");

            // 更新 App 后旧进程可能已经退出，但它启动的 tsproxy 还占着 4720。
            // 只有「已连上且目标没变」才复用；目标变了要继续走下面的停止/重启，
            // 否则用户改了电脑地址后仍在连旧目标。
            String derp = desiredDerp(c);
            JSONObject existing = tsStatus();
            boolean canReuse = existing != null && existing.optBoolean("up", false)
                && target.equals(existing.optString("target", ""));
            boolean policyChanged = canReuse && tsDerpPolicyChanged(c);
            if (canReuse && !policyChanged) {
                tsProxyRunning = true;
                tsProxyProcess = null;
                tsProxyEffectiveDerp = derp;
                LocalConfig.setTsDerpEffective(c, derp);
                startTsWatch();
                String line = "复用已连接的内置 Tailscale";
                log(c, line);
                progress(listener, line);
                return;
            }
            if (policyChanged) {
                log(c, "DERP 策略变化，重启内置 Tailscale");
            }
            stopTailscaleProxy(c);
            // 上一次 App 进程留下的代理可能还在占 4720；起新进程前清掉。
            try {
                exec(c, "pkill -f '^/opt/workbench/tsproxy' 2>/dev/null || true", 15);
            } catch (Exception e) {
                log(c, "清理旧 tsproxy 失败：" + e.getMessage());
            }
            ensureTsHosts(c);

            String iface = activeNetworkInterface(c);
            if (LocalConfig.tsDerp(c).isEmpty()) {
                log(c, derp.isEmpty()
                    ? "DERP 自动（检测到全局 IPv6）"
                    : "DERP 2（当前网络无全局 IPv6）");
            } else {
                log(c, "DERP " + derp + "（指定）");
            }
            tsProxyEffectiveDerp = derp;
            LocalConfig.setTsDerpEffective(c, derp);
            List<String> env = new ArrayList<>();
            env.add("TS_AUTHKEY=" + key);
            env.add("TS_TARGET=" + target);
            env.add("TS_STATE_DIR=/opt/workbench/data/tsnet");
            env.add("TS_LISTEN=127.0.0.1:" + TS_PROXY_PORT);
            env.add("TS_HOSTNAME=claude-phone");
            env.add("TS_FAKE_DEFAULT_IFACE=" + iface);
            if (!derp.isEmpty()) env.add("TS_PREFER_DERP=" + derp);
            env.add("GODEBUG=asyncpreemptoff=1");
            env.add("GOMAXPROCS=4");
            List<String> cmd = buildCommand(c, "exec /opt/workbench/tsproxy", false,
                env.toArray(new String[0]));
            log(c, "启动内置 Tailscale -> " + target + "，默认网卡 " + iface
                + "，DERP " + (derp.isEmpty() ? "自动" : derp)
                + "（auth key " + (key.isEmpty() ? "使用已保存状态" : "已提供") + "）");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            prepareProcess(pb, c);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(c)));
            tsProxyProcess = pb.start();
            try {
                tsProxyProcess.getOutputStream().close();
            } catch (Exception ignored) {
            }

            // 首轮经常是旧进程/网络还没就绪，健康连接通常 3-5 秒内完成；10 秒没连上就尽早重试。
            long timeout = attempt <= 1 ? 10000 : 60000;
            long deadline = System.currentTimeMillis() + timeout;
            String last = "";
            while (System.currentTimeMillis() < deadline) {
                JSONObject st = tsStatus();
                if (st != null) {
                    if (st.optBoolean("up", false)) {
                        tsProxyRunning = true;
                        startTsWatch();
                        boolean direct = st.optBoolean("direct", false);
                        String relay = st.optString("relay", "");
                        String line = direct
                            ? "内置 Tailscale 已连接（直连）"
                            : (relay.isEmpty() ? "内置 Tailscale 已连接（中继）"
                                : "内置 Tailscale 已连接（中继 " + relay + "）");
                        log(c, line);
                        progress(listener, line);
                        return;
                    }
                    String err = st.optString("error", "");
                    if (!err.isEmpty()) {
                        stopTailscaleProxy(c);
                        if (attempt < 2) {
                            log(c, "内置 Tailscale 连接失败，自动重试一次：" + err);
                            progress(listener, "内置 Tailscale 连接失败，正在自动重试…");
                            sleepBeforeTsRetry();
                            startTailscaleProxy(c, listener, attempt + 1);
                            return;
                        }
                        throw new IOException("内置 Tailscale 连接失败：" + err);
                    }
                }
                if (tsProxyProcess == null || !tsProxyProcess.isAlive()) {
                    String msg = "内置 Tailscale 启动失败："
                        + (last.isEmpty() ? tail(readLog(c), 1500) : last);
                    stopTailscaleProxy(c);
                    if (attempt < 2) {
                        log(c, "内置 Tailscale 进程退出，自动重试一次");
                        progress(listener, "内置 Tailscale 启动失败，正在自动重试…");
                        sleepBeforeTsRetry();
                        startTailscaleProxy(c, listener, attempt + 1);
                        return;
                    }
                    throw new IOException(msg);
                }
                Thread.sleep(500);
            }
            stopTailscaleProxy(c);
            if (attempt < 2) {
                log(c, "内置 Tailscale 连接超时（" + (timeout / 1000) + "s），自动重试一次");
                progress(listener, "内置 Tailscale 连接超时，正在自动重试…");
                sleepBeforeTsRetry();
                startTailscaleProxy(c, listener, attempt + 1);
                return;
            }
            throw new IOException("内置 Tailscale 连接超时（" + (timeout / 1000) + "s）" + (last.isEmpty() ? "" : "：" + last));
        }
    }

    private static void sleepBeforeTsRetry() throws IOException {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("内置 Tailscale 重试已取消");
        }
    }

    public static void stopTailscaleProxy(Context c) {
        synchronized (TS_LOCK) {
            Process p = tsProxyProcess;
            boolean wasRunning = tsProxyRunning;
            tsProxyProcess = null;
            tsProxyRunning = false;
            tsProxyEffectiveDerp = null;
            LocalConfig.clearTsDerpEffective(c);
            if (tsWatchThread != null) {
                tsWatchThread.interrupt();
                tsWatchThread = null;
            }
            if (p != null) {
                try {
                    p.destroy();
                    if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (Exception ignored) {
                    try {
                        p.destroyForcibly();
                    } catch (Exception ignored2) {
                    }
                }
                log(c, "内置 Tailscale 已停止");
            } else if (wasRunning) {
                // 复用的代理没有 Process 句柄，按路径清掉。
                try {
                    exec(c, "pkill -f '^/opt/workbench/tsproxy' 2>/dev/null || true", 8);
                } catch (Exception e) {
                    log(c, "清理复用的 tsproxy 失败：" + e.getMessage());
                }
                log(c, "内置 Tailscale 已停止");
            }
        }
    }

    private static void startTsWatch() {
        if (tsWatchThread != null) tsWatchThread.interrupt();
        tsWatchThread = new Thread(() -> {
            while (tsProxyRunning) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!tsProxyRunning) return;
                Process p = tsProxyProcess;
                if (p == null) {
                    // 复用了上一个 App 进程留下的 tsproxy，没有 Process 句柄，
                    // 用本地状态接口判断它是否还活着。
                    JSONObject st = tsStatus();
                    if (st == null || !st.optBoolean("up", false)) {
                        tsProxyRunning = false;
                        return;
                    }
                    continue;
                }
                if (!p.isAlive()) {
                    tsProxyRunning = false;
                    return;
                }
            }
        }, "tsproxy-watch");
        tsWatchThread.setDaemon(true);
        tsWatchThread.start();
    }

    private static List<String> buildCommand(Context c, String shell, boolean serverEnv, String... extraEnv) throws Exception {
        return buildCommandWithBinds(c, shell, serverEnv, null, extraEnv);
    }

    private static List<String> buildCommandWithBinds(Context c, String shell, boolean serverEnv,
                                                       String[] extraBinds, String... extraEnv) throws Exception {
        File alpine = alpineDir(c);
        File phone = phoneDir(c);
        File workspace = workspaceDir(c);
        File sdcard = LocalConfig.externalRoot(c);
        prepareDirs(c);

        List<String> cmd = new ArrayList<>();
        cmd.add(new File(c.getApplicationInfo().nativeLibraryDir, "libproot.so").getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(alpine.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        addBind(cmd, phone.getAbsolutePath(), "/opt/workbench");
        addBind(cmd, workspace.getAbsolutePath(), "/workspace");
        addBind(cmd, sdcard.getAbsolutePath(), "/sdcard");
        if (extraBinds != null) {
            for (int i = 0; i + 1 < extraBinds.length; i += 2) {
                addBind(cmd, extraBinds[i], extraBinds[i + 1]);
            }
        }
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("LANG=C.UTF-8");
        cmd.add("TERM=xterm-256color");
        cmd.add("COLORTERM=truecolor");
        cmd.add("PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        if (serverEnv) {
            cmd.add("PORT=" + SERVER_PORT);
            cmd.add("NO_OPEN=1");
            cmd.add("WORKBENCH_PHONE=1");
            if (new File(phoneDir(c), "legacy-cli").isFile()) cmd.add("CLAUDE_LEGACY=1");
        }
        if (extraEnv != null) {
            for (String e : extraEnv) {
                if (e != null && !e.isEmpty()) cmd.add(e);
            }
        }
        cmd.add("/bin/sh");
        cmd.add("-c");
        cmd.add(shell);
        return cmd;
    }

    private static void addBind(List<String> cmd, String host, String guest) {
        cmd.add("-b");
        cmd.add(host + ":" + guest);
    }

    private static void prepareProcess(ProcessBuilder pb, Context c) {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File tmp = new File(c.getCacheDir(), "proot");
        tmp.mkdirs();
        pb.environment().put("PROOT_LOADER", lib + "/libproot-loader.so");
        pb.environment().put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", lib);
        // 部分 ROM 的 seccomp 会拦截 proot 的加速路径；关掉它换兼容性，
        // 本项目主要是等 API/文件操作，这点开销可以接受。
        pb.environment().put("PROOT_NO_SECCOMP", "1");
    }

    /** 在 proot 里执行一条 shell 命令，返回完整输出。 */
    public static String exec(Context c, String shell, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(c, shell, false));
        pb.redirectErrorStream(true);
        prepareProcess(pb, c);
        Process p = pb.start();
        try {
            p.getOutputStream().close();
        } catch (Exception ignored) {
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (out) {
                        if (out.size() < 400_000) out.write(buf, 0, n);
                    }
                }
            } catch (IOException ignored) {
            }
        });
        reader.start();
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        reader.join(2000);
        String text;
        synchronized (out) {
            text = out.toString("UTF-8");
        }
        if (!done) throw new IOException("命令超时：" + shell);
        String result = text + "\n[exit " + (done ? p.exitValue() : -1) + "]";
        log(c, "$ " + shell + "\n" + tail(result, 4000));
        return result;
    }

    private static boolean ping(int port) {
        if (port <= 0) return false;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/ping").openConnection();
            con.setConnectTimeout(800);
            con.setReadTimeout(800);
            if (con.getResponseCode() != 200) return false;
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[256];
                int n = in.read(buf);
                String s = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                return s.contains("\"app\":\"claude-gui\"");
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static int readRuntimePort(Context c) {
        File f = new File(new File(phoneDir(c), "data"), "runtime.json");
        try {
            JSONObject o = new JSONObject(readText(f));
            return o.optInt("port", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 下载/解压

    private static void download(Context c, String url, File dest, Listener listener) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(60000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "ClaudeWorkbench/2.0 (Android)");
        int code = con.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        long total = con.getContentLengthLong();
        long read = 0;
        int lastPct = -1;
        try (InputStream in = new BufferedInputStream(con.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0) {
                    int pct = (int) (read * 100 / total);
                    if (pct >= lastPct + 5) {
                        lastPct = pct;
                        progress(listener, "下载 Alpine… " + pct + "%");
                    }
                }
            }
        } finally {
            con.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 极简 tar 读取（Alpine minirootfs 用到的文件/目录/符号链接/hardlink/PAX）。 */
    private static void untar(InputStream in, File dest) throws IOException {
        byte[] h = new byte[512];
        String longName = null, longLink = null;
        while (readFully(in, h)) {
            if (isZero(h)) break;
            String name = longName != null ? longName : str(h, 0, 100);
            String prefix = str(h, 345, 155);
            if (longName == null && !prefix.isEmpty()) name = prefix + "/" + name;
            String link = longLink != null ? longLink : str(h, 157, 100);
            longName = null;
            longLink = null;
            int mode = (int) octal(h, 100, 8);
            long size = octal(h, 124, 12);
            char type = (char) h[156];
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                byte[] data = readData(in, size);
                if (type == 'L') longName = cstr(data);
                else if (type == 'K') longLink = cstr(data);
                else if (type == 'x') {
                    String[] pax = paxPathAndLink(data);
                    longName = pax[0];
                    longLink = pax[1];
                }
                continue;
            }
            while (name.startsWith("./")) name = name.substring(2);
            if (name.isEmpty() || name.equals(".")) {
                skip(in, size);
                continue;
            }
            if (name.startsWith("/") || ("/" + name + "/").contains("/../")) {
                throw new IOException("压缩包包含不安全路径：" + name);
            }
            File f = new File(dest, name);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            switch (type) {
                case '5':
                    f.mkdirs();
                    chmod(f, mode == 0 ? 0755 : mode);
                    skip(in, size);
                    break;
                case '2':
                    f.delete();
                    try {
                        Os.symlink(link, f.getPath());
                    } catch (Exception e) {
                        throw new IOException("创建符号链接失败：" + name + " " + e.getMessage());
                    }
                    skip(in, size);
                    break;
                case '1': {
                    File src = new File(dest, link.startsWith("./") ? link.substring(2) : link);
                    copy(src, f);
                    chmod(f, mode);
                    skip(in, size);
                    break;
                }
                case '0':
                case '\0':
                case '7':
                    try (OutputStream out = new FileOutputStream(f)) {
                        long left = size;
                        byte[] buf = new byte[64 * 1024];
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("压缩包不完整");
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    skipPadding(in, size);
                    chmod(f, mode);
                    break;
                default:
                    skip(in, size);
            }
        }
    }

    private static String[] paxPathAndLink(byte[] data) {
        String path = null, link = null;
        String s = new String(data, StandardCharsets.UTF_8);
        int i = 0;
        while (i < s.length()) {
            int sp = s.indexOf(' ', i);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(s.substring(i, sp));
            } catch (Exception e) {
                break;
            }
            String rec = s.substring(sp + 1, Math.min(s.length(), i + len - 1));
            if (rec.startsWith("path=")) path = rec.substring(5);
            else if (rec.startsWith("linkpath=")) link = rec.substring(9);
            i += len;
        }
        return new String[]{path, link};
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getPath(), mode & 07777);
        } catch (Exception ignored) {
        }
    }

    private static boolean isLink(File f) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] d = new byte[(int) size];
        if (!readFully(in, d)) throw new IOException("压缩包不完整");
        skipPadding(in, size);
        return d;
    }

    private static void skip(InputStream in, long size) throws IOException {
        long total = size + ((512 - size % 512) % 512);
        while (total > 0) {
            long n = in.skip(total);
            if (n <= 0) {
                if (in.read() < 0) throw new IOException("压缩包不完整");
                n = 1;
            }
            total -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - size % 512) % 512;
        while (pad > 0) {
            long n = in.skip(pad);
            if (n <= 0) {
                if (in.read() < 0) return;
                n = 1;
            }
            pad -= n;
        }
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static String cstr(byte[] b) {
        int end = 0;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte x = b[i];
            if (x == 0 || x == ' ') {
                if (v > 0) break;
                continue;
            }
            if (x < '0' || x > '7') break;
            v = v * 8 + (x - '0');
        }
        return v;
    }

    // ---------------------------------------------------------------- 资源/日志

    private static boolean assetEquals(Context c, String assetPath, File f) {
        if (f == null || !f.isFile()) return false;
        try (InputStream in = c.getAssets().open(assetPath);
             InputStream file = new FileInputStream(f)) {
            return Arrays.equals(readAll(in), readAll(file));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void copyAssetDir(AssetManager am, String assetPath, File dest) throws IOException {
        String[] kids = am.list(assetPath);
        if (kids == null || kids.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(assetPath); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return;
        }
        dest.mkdirs();
        for (String kid : kids) {
            copyAssetDir(am, assetPath + "/" + kid, new File(dest, kid));
        }
    }

    private static void writeFile(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        if (f.exists() || isLink(f)) f.delete();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void writeMarker(Context c, String name) throws IOException {
        File f = new File(phoneDir(c), name);
        writeFile(f, new java.util.Date().toString());
    }

    private static void deleteTree(File f) {
        if (!f.exists() && !isLink(f)) return;
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteTree(k);
            }
        }
        f.delete();
    }

    public static void log(Context c, String line) {
        try {
            File f = logFile(c);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(("[" + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + "] " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    public static String readLog(Context c) {
        try {
            return readText(logFile(c));
        } catch (Exception e) {
            return "";
        }
    }

    /** 只读日志末尾，避免日志很长时界面每秒读取整个文件。 */
    public static String readLogTail(Context c, int maxBytes) {
        File f = logFile(c);
        if (!f.isFile()) return "";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - Math.max(1, maxBytes));
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    private static void progress(Listener l, String line) {
        Log.i(TAG, line);
        if (l != null) l.onProgress(line);
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}

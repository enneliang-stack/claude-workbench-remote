package com.missyao.claudeworkbench;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 手机端下载管理：只操作 App 自己的 external files/downloads/shared 目录，
 * 打开时通过 DownloadProvider 的 content:// 授权给查看器。
 */
public class DownloadsBridge {
    private final MainActivity activity;
    private final Object lock = new Object();

    public DownloadsBridge(MainActivity activity) {
        this.activity = activity;
    }

    /** 手机端页面右下角的「返回主页」按钮 */
    @JavascriptInterface
    public void goHome() {
        activity.runOnUiThread(activity::showHome);
    }

    /** 悬浮窗插话后，手机端页面发送成功时通知控制层恢复自动化。 */
    @JavascriptInterface
    public void phoneSent() {
        PhoneControlServer.clearPause();
    }

    /** 手机端聊天页当前显示的对话；用于“任务已完成”悬浮窗在用户查看后消失。 */
    @JavascriptInterface
    public void phoneChatVisible(String id) {
        PhoneControlServer.onPhoneChatVisible(id);
    }

    @JavascriptInterface
    public void phoneChatEntered(String id) {
        PhoneControlServer.onPhoneChatEntered(id);
    }

    @JavascriptInterface
    public void phoneChatHidden() {
        PhoneControlServer.onPhoneChatHidden();
    }

    /** 直接打开手机本机文件（/sdcard、/workspace 等），不复制到下载目录。 */
    @JavascriptInterface
    public boolean openLocal(String path, String name) {
        final Uri uri = DownloadProvider.localUri(activity, path);
        if (uri == null) return false;
        String display = name != null && !name.isEmpty() ? name : path;
        final String mimeType = mimeOf(display);
        activity.runOnUiThread(() -> {
            // 优先直接用 WPS/vivo Office/Word 这类文档应用打开，
            // 跳过“在文件管理中显示”这种会转去 Google 的入口。
            if (tryPreferredViewer(uri, mimeType)) return;
            if (showOpenChooser(uri, mimeType)) return;
            Toast.makeText(activity, "没有能打开这个文件的应用", Toast.LENGTH_LONG).show();
        });
        return true;
    }

    private Intent localViewIntent(Uri uri, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("", uri));
        return intent;
    }

    private boolean tryPreferredViewer(Uri uri, String mimeType) {
        try {
            PackageManager pm = activity.getPackageManager();
            List<ResolveInfo> apps = pm.queryIntentActivities(localViewIntent(uri, mimeType), 0);
            for (ResolveInfo ri : apps) {
                if (ri == null || ri.activityInfo == null || ri.activityInfo.name == null) continue;
                String pkg = ri.activityInfo.packageName == null
                    ? "" : ri.activityInfo.packageName.toLowerCase();
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase();
                if (pkg.equals(activity.getPackageName())) continue;
                if (pkg.contains("documentsui") || pkg.contains("nbu.files")
                        || pkg.contains("intentresolver") || pkg.contains("browser")
                        || pkg.contains("chrome") || pkg.contains("lark")
                        || pkg.contains("filemanager") || pkg.contains("filebrowser")
                        || label.contains("文件") || label.contains("浏览器")
                        || label.contains("飞书") || label.contains("google")) continue;
                if (label.contains("wps") || label.contains("office")
                        || label.contains("word") || label.contains("文档")
                        || label.contains("办公") || label.contains("vivo")) {
                    Intent direct = localViewIntent(uri, mimeType);
                    direct.setComponent(new ComponentName(
                        ri.activityInfo.packageName, ri.activityInfo.name));
                    activity.startActivity(direct);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean showOpenChooser(Uri uri, String mimeType) {
        try {
            activity.startActivity(Intent.createChooser(localViewIntent(uri, mimeType), "打开文件"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 打开手机自己的文件管理器（尽量选非 Google 的），让用户直接看到生成目录。 */
    @JavascriptInterface
    public boolean openLocalLocation(String path) {
        String p = path == null ? "" : path.trim();
        if (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        final String target = p;
        activity.runOnUiThread(() -> showLocalFolderDialog(target));
        return true;
    }

    /** 直接在 App 里列出这个本机文件夹，点文件就打开；不依赖系统文件管理器的深链接。 */
    private void showLocalFolderDialog(String path) {
        String p = path == null ? "" : path.trim();
        File dir = DownloadProvider.resolveLocalDir(activity, p);
        if (dir == null) {
            int slash = p.lastIndexOf('/');
            if (slash > 0) {
                p = p.substring(0, slash);
                dir = DownloadProvider.resolveLocalDir(activity, p);
            }
        }
        if (dir == null) {
            Toast.makeText(activity, "文件夹不存在：" + p, Toast.LENGTH_LONG).show();
            return;
        }
        final String guestDir = p;
        File[] files = dir.listFiles();
        if (files == null) {
            Toast.makeText(activity, "无法读取文件夹：" + p, Toast.LENGTH_LONG).show();
            return;
        }
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        final List<File> entries = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        String parent = parentGuest(guestDir);
        if (parent != null) {
            entries.add(null);
            labels.add("⬆  上一级");
        }
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            entries.add(f);
            labels.add((f.isDirectory() ? "📁  " : "📄  ") + f.getName()
                + (f.isDirectory() ? "  ·  文件夹" : "  ·  " + fmtSize(f.length())));
        }
        new AlertDialog.Builder(activity)
            .setTitle("位置  " + guestDir)
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                File f = entries.get(which);
                if (f == null) {
                    showLocalFolderDialog(parent);
                    return;
                }
                String child = guestDir.endsWith("/")
                    ? guestDir + f.getName() : guestDir + "/" + f.getName();
                if (f.isDirectory()) showLocalFolderDialog(child);
                else openLocal(child, f.getName());
            })
            .setPositiveButton("关闭", null)
            .show();
    }

    private String parentGuest(String p) {
        int slash = p.lastIndexOf('/');
        if (slash <= 0) return null;
        String parent = p.substring(0, slash);
        if (parent.isEmpty()) return null;
        return DownloadProvider.resolveLocalDir(activity, parent) != null ? parent : null;
    }

    private static String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", n / 1024.0 / 1024.0);
    }

    /** 在系统文件管理器里打开 /sdcard 下的文件夹；App 私有目录会提示不可见。 */
    @JavascriptInterface
    public boolean openLocalFolder(String path) {
        String p = path == null ? "" : path.trim();
        if (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        String rel = null;
        if (p.startsWith("/sdcard/")) {
            rel = p.substring("/sdcard/".length());
        } else if (p.startsWith("/storage/emulated/0/")) {
            rel = p.substring("/storage/emulated/0/".length());
        }
        if (rel != null && !rel.isEmpty()) {
            final Uri uri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + rel);
            activity.runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "vnd.android.document/directory");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    activity.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(activity, "没有能打开文件夹的应用", Toast.LENGTH_LONG).show();
                }
            });
            return true;
        }
        activity.runOnUiThread(() -> Toast.makeText(activity,
            "这个文件夹在 App 私有工作区，系统文件管理器看不到；可以用「打开」查看文件",
            Toast.LENGTH_LONG).show());
        return true;
    }

    private File dir() {
        File base = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(activity.getFilesDir(), "downloads");
        File d = new File(base, "shared");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File indexFile() {
        return new File(dir(), "_index.json");
    }

    private String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        switch (ext) {
            case "md": case "markdown": return "text/plain";
            case "txt": case "log": case "ini": case "conf": return "text/plain";
            case "json": return "text/plain";
            case "yaml": case "yml": return "text/plain";
            case "xml": return "text/plain";
            case "csv": return "text/plain";
            case "js": case "mjs": return "text/plain";
            case "ts": return "text/plain";
            case "py": return "text/plain";
            case "java": return "text/plain";
            case "sh": return "text/plain";
            case "html": case "htm": return "text/html";
            case "css": return "text/plain";
            case "zip": return "application/zip";
            case "7z": return "application/x-7z-compressed";
            case "rar": return "application/vnd.rar";
            case "apk": return "application/vnd.android.package-archive";
            case "pdf": return "application/pdf";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "epub": return "application/epub+zip";
            default: break;
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    /**
     * Android 10+ 额外导出一份到系统「下载/Claude工作台」。
     * App 私有目录里的 content:// 很多查看器（尤其 Google 系）不肯读，
     * 导出公共副本后文件管理器和查看器都能直接打开。
     */
    private String exportToPublic(File src, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "";
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Claude工作台");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = activity.getContentResolver()
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return "";
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("无法写入系统下载目录");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            activity.getContentResolver().update(uri, done, null, null);
            return uri.toString();
        } catch (Exception e) {
            if (uri != null) {
                try { activity.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            }
            return "";
        }
    }

    private void deletePublic(String publicUri) {
        if (publicUri == null || publicUri.isEmpty()) return;
        try {
            activity.getContentResolver().delete(Uri.parse(publicUri), null, null);
        } catch (Exception ignored) {
        }
    }

    private String findPublicByPath(String path) {
        synchronized (lock) {
            JSONArray arr = readIndex();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && norm(o.optString("path")).equals(norm(path))) {
                    return o.optString("publicUri", "");
                }
            }
        }
        return "";
    }

    private String findPublicBySource(String source) {
        synchronized (lock) {
            JSONArray arr = readIndex();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && norm(o.optString("source")).equals(norm(source))) {
                    return o.optString("publicUri", "");
                }
            }
        }
        return "";
    }

    private String readAll(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            int len = (int) Math.min(f.length(), 2 * 1024 * 1024);
            byte[] buf = new byte[Math.max(1, len)];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private synchronized JSONArray readIndex() {
        try {
            String s = readAll(indexFile());
            return s.isEmpty() ? new JSONArray() : new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private synchronized void writeIndex(JSONArray arr) {
        try (FileOutputStream out = new FileOutputStream(indexFile())) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private String norm(String p) {
        return p == null ? "" : p.replace('/', '\\').toLowerCase();
    }

    @JavascriptInterface
    public String list() {
        synchronized (lock) {
            JSONArray arr = readIndex();
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                File f = new File(o.optString("path"));
                if (!f.isFile()) continue;
                try {
                    o.put("size", f.length());
                    o.put("mtime", f.lastModified());
                } catch (Exception ignored) {
                }
                out.put(o);
            }
            writeIndex(out);
            return out.toString();
        }
    }

    @JavascriptInterface
    public void download(String url, String source, String name) {
        new Thread(() -> downloadInternal(url, source, name)).start();
    }

    private void downloadInternal(String url, String source, String name) {
        try {
            URL u = new URL(url);
            String host = u.getHost();
            String scheme = u.getProtocol();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host) || "[::1]".equals(host);
            if (loopback) {
                // 只放行本机页面/内置 Tailscale 代理使用的端口（4711 电脑端、4712 手机端、4720 代理）。
                int p = u.getPort();
                loopback = p >= 4711 && p <= 4740;
            }
            if (!web || host == null || !(host.endsWith(".ts.net") || host.startsWith("100.")
                    || host.startsWith("192.168.") || host.startsWith("10.")
                    || host.startsWith("172.") || loopback)) {
                notifyPage(source, false, "不允许的下载地址");
                return;
            }

            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                String detail = "";
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        byte[] b = new byte[300];
                        int n = es.read(b);
                        if (n > 0) detail = new String(b, 0, n, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignored) {
                }
                notifyPage(source, false,
                    "HTTP " + code + (detail.isEmpty() ? "" : "：" + detail));
                return;
            }

            File target = uniqueFile(name, source);
            File part = new File(target.getAbsolutePath() + ".part");
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!part.renameTo(target)) {
                try (FileInputStream in = new FileInputStream(part);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                part.delete();
            }

            String oldPublic = findPublicBySource(source);
            String publicUri = exportToPublic(target, target.getName());
            if (!publicUri.isEmpty()) deletePublic(oldPublic);

            synchronized (lock) {
                JSONArray arr = readIndex();
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (norm(o.optString("source")).equals(norm(source))) continue;
                    next.put(o);
                }
                JSONObject o = new JSONObject();
                o.put("source", source);
                o.put("name", target.getName());
                o.put("path", target.getAbsolutePath());
                o.put("size", target.length());
                o.put("mtime", System.currentTimeMillis());
                if (!publicUri.isEmpty()) o.put("publicUri", publicUri);
                next.put(o);
                writeIndex(next);
            }
            notifyPage(source, true, "");
        } catch (Exception e) {
            notifyPage(source, false, e.getMessage() == null ? "下载失败" : e.getMessage());
        }
    }

    private File uniqueFile(String name, String source) {
        File d = dir();
        String safe = name == null ? "download"
            : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "download";
        File f = new File(d, safe);
        if (!f.exists()) return f;

        // 同一个源重复下载：沿用已有文件名，覆盖
        synchronized (lock) {
            JSONArray arr = readIndex();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && norm(o.optString("source")).equals(norm(source))) {
                    File old = new File(o.optString("path"));
                    if (old.isFile()) return old;
                }
            }
        }

        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File n = new File(d, base + " (" + i + ")" + ext);
            if (!n.exists()) return n;
        }
        return new File(d, base + "-" + System.currentTimeMillis() + ext);
    }

    private File checkedFile(String path) {
        try {
            File d = dir();
            String base = d.getCanonicalPath() + File.separator;
            File f = new File(path);
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(base) || !f.isFile()) return null;
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public boolean open(String path) {
        File f = checkedFile(path);
        if (f == null) return false;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        final String mimeType = mimeOf(name);
        boolean isApk = "apk".equals(ext);

        String publicUri = findPublicByPath(path);
        if (!publicUri.isEmpty()) {
            try (android.os.ParcelFileDescriptor p =
                     activity.getContentResolver().openFileDescriptor(Uri.parse(publicUri), "r")) {
                if (p == null) publicUri = "";
            } catch (Exception e) {
                publicUri = "";
            }
        }
        Uri uri = publicUri.isEmpty()
            ? Uri.parse("content://com.missyao.claudeworkbench.files/" + Uri.encode(name))
            : Uri.parse(publicUri);
        activity.runOnUiThread(() -> {
            // 安装 APK 需要本 App 有“安装未知应用”权限，否则系统安装器会直接拒绝
            if (isApk && Build.VERSION.SDK_INT >= 26
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                try {
                    android.content.Intent settings = new android.content.Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(settings);
                    Toast.makeText(activity,
                        "请允许「Claude 工作台」安装未知应用，再点一次打开",
                        Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(activity, "请在系统设置里允许安装未知应用", Toast.LENGTH_LONG).show();
                }
                return;
            }

            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("", uri));

            try {
                if (isApk) {
                    activity.startActivity(intent);
                } else {
                    activity.startActivity(android.content.Intent.createChooser(intent, "打开文件"));
                }
            } catch (ActivityNotFoundException e) {
                if (isApk) {
                    try {
                        android.content.Intent installer = new android.content.Intent(
                            android.content.Intent.ACTION_INSTALL_PACKAGE);
                        installer.setData(uri);
                        installer.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        installer.setClipData(ClipData.newRawUri("", uri));
                        activity.startActivity(installer);
                        return;
                    } catch (Exception ignored) {
                    }
                }
                Toast.makeText(activity, "没有能打开这个文件的应用", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(activity, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        return true;
    }

    @JavascriptInterface
    public boolean delete(String path) {
        File f = checkedFile(path);
        if (f == null) return false;
        String publicUri = findPublicByPath(path);
        boolean ok = f.delete();
        if (ok) {
            deletePublic(publicUri);
            synchronized (lock) {
                JSONArray arr = readIndex();
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (norm(o.optString("path")).equals(norm(path))) continue;
                    next.put(o);
                }
                writeIndex(next);
            }
        }
        return ok;
    }

    private void notifyPage(String source, boolean ok, String error) {
        String js = "window.__onDownloadComplete&&window.__onDownloadComplete(" +
            JSONObject.quote(source) + "," + ok + "," + JSONObject.quote(error) + ")";
        activity.runOnUiThread(() -> {
            if (activity.webView != null) {
                activity.webView.evaluateJavascript(js, null);
            }
        });
    }
}

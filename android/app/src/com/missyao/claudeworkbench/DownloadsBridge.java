package com.missyao.claudeworkbench;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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
            if (host == null || !(host.endsWith(".ts.net") || host.startsWith("100.")
                    || host.startsWith("192.168.") || host.startsWith("10.")
                    || host.startsWith("172."))) {
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

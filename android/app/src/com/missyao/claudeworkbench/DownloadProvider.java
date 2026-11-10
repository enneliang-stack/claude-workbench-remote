package com.missyao.claudeworkbench;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读文件出口：
 * - 普通下载：content://…/文件名，暴露 App 下载目录；
 * - 手机本机文件：content://…/local?path=/sdcard/…，供「打开文件」直接读取，
 *   不用先把文件复制到下载目录。Provider 不导出，打开时只授权单个 URI。
 */
public class DownloadProvider extends ContentProvider {
    public static final String AUTHORITY = "com.missyao.claudeworkbench.files";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File dir() {
        File base = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(getContext().getFilesDir(), "downloads");
        return new File(base, "shared");
    }

    /** 把手机端页面看到的 /sdcard、/workspace 等路径映射成 App 进程能读的真实文件。 */
    static File resolveLocal(Context c, String guestPath) {
        if (c == null || guestPath == null) return null;
        String p = guestPath.trim();
        File base;
        String rel;
        if (p.startsWith("/sdcard/")) {
            base = LocalConfig.externalRoot(c);
            rel = p.substring("/sdcard/".length());
        } else if (p.startsWith("/storage/emulated/0/")) {
            base = Environment.getExternalStorageDirectory();
            rel = p.substring("/storage/emulated/0/".length());
        } else if (p.startsWith("/workspace/")) {
            base = LocalEngine.workspaceDir(c);
            rel = p.substring("/workspace/".length());
        } else if (p.startsWith("/root/")) {
            base = new File(LocalEngine.alpineDir(c), "root");
            rel = p.substring("/root/".length());
        } else if (p.startsWith("/tmp/")) {
            base = new File(LocalEngine.alpineDir(c), "tmp");
            rel = p.substring("/tmp/".length());
        } else if (p.startsWith("/opt/")) {
            base = new File(LocalEngine.alpineDir(c), "opt");
            rel = p.substring("/opt/".length());
        } else {
            return null;
        }
        try {
            File b = base.getCanonicalFile();
            File f = new File(b, rel).getCanonicalFile();
            if (!f.getPath().startsWith(b.getPath() + File.separator)) return null;
            return f.isFile() && f.canRead() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 和 resolveLocal 同样的路径映射，但返回目录。 */
    static File resolveLocalDir(Context c, String guestPath) {
        if (c == null || guestPath == null) return null;
        String p = guestPath.trim();
        File base;
        String rel;
        if (p.startsWith("/sdcard/")) {
            base = LocalConfig.externalRoot(c);
            rel = p.substring("/sdcard/".length());
        } else if (p.equals("/sdcard")) {
            base = LocalConfig.externalRoot(c);
            rel = "";
        } else if (p.startsWith("/storage/emulated/0/")) {
            base = Environment.getExternalStorageDirectory();
            rel = p.substring("/storage/emulated/0/".length());
        } else if (p.equals("/storage/emulated/0")) {
            base = Environment.getExternalStorageDirectory();
            rel = "";
        } else if (p.startsWith("/workspace/")) {
            base = LocalEngine.workspaceDir(c);
            rel = p.substring("/workspace/".length());
        } else if (p.equals("/workspace")) {
            base = LocalEngine.workspaceDir(c);
            rel = "";
        } else if (p.startsWith("/root/")) {
            base = new File(LocalEngine.alpineDir(c), "root");
            rel = p.substring("/root/".length());
        } else if (p.startsWith("/tmp/")) {
            base = new File(LocalEngine.alpineDir(c), "tmp");
            rel = p.substring("/tmp/".length());
        } else if (p.startsWith("/opt/")) {
            base = new File(LocalEngine.alpineDir(c), "opt");
            rel = p.substring("/opt/".length());
        } else {
            return null;
        }
        try {
            File b = base.getCanonicalFile();
            File f = rel.isEmpty() ? b : new File(b, rel).getCanonicalFile();
            String bp = b.getPath();
            if (!f.getPath().equals(bp) && !f.getPath().startsWith(bp + File.separator)) return null;
            return f.isDirectory() && f.canRead() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 给本机文件生成一个只读 content URI；文件不存在/越界时返回 null。 */
    public static Uri localUri(Context c, String guestPath) {
        File f = resolveLocal(c, guestPath);
        if (f == null) return null;
        return new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath("local")
            .appendQueryParameter("path", guestPath)
            .appendQueryParameter("name", f.getName())
            .build();
    }

    private static boolean isLocal(Uri uri) {
        return "local".equals(uri.getLastPathSegment());
    }

    private String displayName(Uri uri) {
        if (isLocal(uri)) {
            String name = uri.getQueryParameter("name");
            if (name != null && !name.isEmpty()) return name;
            File f = resolveLocal(getContext(), uri.getQueryParameter("path"));
            return f == null ? null : f.getName();
        }
        return uri.getLastPathSegment();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            if (isLocal(uri)) {
                File f = resolveLocal(getContext(), uri.getQueryParameter("path"));
                if (f == null) throw new FileNotFoundException();
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            String name = uri.getLastPathSegment();
            if (name == null) throw new FileNotFoundException();
            File d = dir();
            String base = d.getCanonicalPath() + File.separator;
            File f = new File(d, name);
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(base) || !f.isFile()) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            throw new FileNotFoundException();
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = displayName(uri);
        return mimeOf(name == null ? "" : name);
    }

    private static String mimeOf(String name) {
        if (name == null) name = "";
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

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        if (isLocal(uri)) {
            f = resolveLocal(getContext(), uri.getQueryParameter("path"));
        } else {
            String name = uri.getLastPathSegment();
            if (name == null) return null;
            f = new File(dir(), name);
            try {
                String base = dir().getCanonicalPath() + File.separator;
                String canonical = f.getCanonicalPath();
                if (!canonical.startsWith(base) || !f.isFile()) return null;
            } catch (Exception e) {
                return null;
            }
        }
        if (f == null || !f.isFile()) return null;
        String[] cols = projection != null ? projection
            : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

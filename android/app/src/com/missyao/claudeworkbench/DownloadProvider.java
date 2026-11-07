package com.missyao.claudeworkbench;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** 只读暴露 App 下载目录里的文件，给系统查看器打开用。 */
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

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null) throw new FileNotFoundException();
        try {
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
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
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

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        File d = dir();
        File f = new File(d, name);
        try {
            String base = d.getCanonicalPath() + File.separator;
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(base) || !f.isFile()) return null;
        } catch (Exception e) {
            return null;
        }
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

package com.missyao.claudeworkbench;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 拍照临时文件出口：WebView 的 <input capture> 把相机结果交回页面时，
 * 需要一个可写的 content:// URI。文件只放在 App 私有 cache/captures，
 * provider 不导出，只按单个 URI 授权给相机应用。
 */
public class CaptureProvider extends ContentProvider {
    public static final String AUTHORITY = "com.missyao.claudeworkbench.capture";

    @Override
    public boolean onCreate() {
        return true;
    }

    static File dir(Context c) {
        File base = new File(c.getCacheDir(), "captures");
        if (!base.isDirectory()) base.mkdirs();
        return base;
    }

    static File newCaptureFile(Context c) {
        File d = dir(c);
        cleanupOld(d);
        String name = "capture_" + System.currentTimeMillis() + ".jpg";
        return new File(d, name);
    }

    /** 拍照临时文件只在 cache 里，成功上传后原实现不删除；按时间/数量做一次轻量清理。 */
    private static void cleanupOld(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (!f.isFile()) continue;
            if (i >= 20 || f.lastModified() < cutoff) {
                try { f.delete(); } catch (Exception ignored) { /* ignore */ }
            }
        }
    }

    static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(file.getName())
                .build();
    }

    private File fileFor(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("\\")) return null;
        try {
            File base = dir(getContext()).getCanonicalFile();
            File f = new File(base, name).getCanonicalFile();
            if (!f.getPath().startsWith(base.getPath() + File.separator)) return null;
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null) throw new FileNotFoundException("bad capture uri");
        int flags;
        if ("r".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_ONLY;
        } else if ("w".equals(mode) || "wt".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("rw".equals(mode) || "rwt".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else {
            throw new FileNotFoundException("unsupported mode " + mode);
        }
        return ParcelFileDescriptor.open(f, flags);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
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

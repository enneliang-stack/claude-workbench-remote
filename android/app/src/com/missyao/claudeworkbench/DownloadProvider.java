package com.missyao.claudeworkbench;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
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
        if (dot >= 0) {
            String mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
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

package com.iqoofurry.sheetinstaller;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Отдаёт собственный APK приложения по content://.
 *
 * Нужен только для одного: вызвать системный диалог «Открыть с помощью» для
 * apk-интента после удаления стокового установщика, чтобы пользователь мог
 * выбрать Custom Installer и нажать «Всегда».
 */
public class SelfApkProvider extends ContentProvider {
    static final String AUTHORITY = "com.iqoofurry.sheetinstaller.self";
    static final Uri SELF_URI = Uri.parse("content://" + AUTHORITY + "/app.apk");
    static final String APK_MIME = "application/vnd.android.package-archive";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return APK_MIME;
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

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.startsWith("r")) {
            throw new FileNotFoundException("read-only provider");
        }
        File apk = new File(getContext().getPackageCodePath());
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
package org.recgram.rec;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Перенос готовой записи в общую папку: Recordings/RecGram (Android 12+) или Music/RecGram. */
final class RecordingStore {

    static final String SUBDIR = "RecGram";

    private RecordingStore() {
    }

    static String relativeDir() {
        String base = Build.VERSION.SDK_INT >= 31 ? Environment.DIRECTORY_RECORDINGS : Environment.DIRECTORY_MUSIC;
        return base + "/" + SUBDIR;
    }

    /** Копирует файл в общую папку. Возвращает путь для показа пользователю или null при ошибке. */
    static String publish(File src, String title) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = ApplicationLoader.applicationContext.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, CallRecorder.mimeType());
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir());
                v.put(MediaStore.MediaColumns.TITLE, title);
                v.put(MediaStore.Audio.Media.ARTIST, "RecGram");
                v.put(MediaStore.Audio.Media.ALBUM, "RecGram");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                if (Build.VERSION.SDK_INT >= 31) {
                    v.put(MediaStore.Audio.Media.IS_RECORDING, 1);
                }
                Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (uri == null) {
                    return null;
                }
                try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                    copy(in, out);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUBDIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    return null;
                }
                File dst = new File(dir, src.getName());
                try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                    copy(in, out);
                }
                android.media.MediaScannerConnection.scanFile(ApplicationLoader.applicationContext, new String[]{dst.getAbsolutePath()}, null, null);
            }
            return relativeDir() + "/" + src.getName();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }
}

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
    static String videoDir() {
        return Environment.DIRECTORY_MOVIES + "/" + SUBDIR;
    }

    static String publish(File src, String title) {
        final boolean video = src.getName().endsWith(".mp4");
        final String dirRel = video ? videoDir() : relativeDir();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = ApplicationLoader.applicationContext.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : CallRecorder.mimeType());
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, dirRel);
                v.put(MediaStore.MediaColumns.TITLE, title);
                if (!video) {
                    v.put(MediaStore.Audio.Media.ARTIST, "RecGram");
                    v.put(MediaStore.Audio.Media.ALBUM, "RecGram");
                }
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                if (Build.VERSION.SDK_INT >= 31 && !video) {
                    v.put(MediaStore.Audio.Media.IS_RECORDING, 1);
                }
                Uri uri = cr.insert(video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
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
                File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC), SUBDIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    return null;
                }
                File dst = new File(dir, src.getName());
                try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                    copy(in, out);
                }
                android.media.MediaScannerConnection.scanFile(ApplicationLoader.applicationContext, new String[]{dst.getAbsolutePath()}, null, null);
            }
            return dirRel + "/" + src.getName();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * JSON с данными звонка рядом с записью. Android может не пустить не-аудиофайл в папку записей,
     * тогда файл кладётся в Documents/RecGram. Возвращает путь или null.
     */
    static String publishJson(File src) {
        String[] dirs = {relativeDir(), Environment.DIRECTORY_DOCUMENTS + "/" + SUBDIR};
        if (Build.VERSION.SDK_INT < 29) {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUBDIR);
                File dst = new File(dir, src.getName());
                try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                    copy(in, out);
                }
                return Environment.DIRECTORY_MUSIC + "/" + SUBDIR + "/" + src.getName();
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            }
        }
        ContentResolver cr = ApplicationLoader.applicationContext.getContentResolver();
        for (String dir : dirs) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir);
                Uri uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (uri == null) {
                    continue;
                }
                try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                    copy(in, out);
                }
                return dir + "/" + src.getName();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }
}

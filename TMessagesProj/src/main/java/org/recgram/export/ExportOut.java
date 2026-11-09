package org.recgram.export;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Папка экспорта в Documents/RecGram/Export/&lt;имя&gt;. Android 10+ — через MediaStore
 * (разрешения не нужны), старше — напрямую в файловую систему.
 */
final class ExportOut {

    /** Путь от корня памяти, например Documents/RecGram/Export/ChatExport_Мама_2026-11-09_19-20. */
    final String relDir;
    private final Set<String> used = new HashSet<>();

    ExportOut(String folderName) {
        relDir = Environment.DIRECTORY_DOCUMENTS + "/RecGram/Export/" + folderName;
    }

    /** Уникальное имя внутри подпапки (photos/, files/ …), чтобы ссылки в HTML не разъехались. */
    synchronized String uniqueName(String sub, String name) {
        name = sanitize(name);
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String candidate = name;
        for (int i = 2; used.contains(sub + "/" + candidate); i++) {
            candidate = base + " (" + i + ")" + ext;
        }
        used.add(sub + "/" + candidate);
        return candidate;
    }

    static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "file";
        }
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (s.startsWith(".")) {
            s = "_" + s;
        }
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    OutputStream open(String sub, String name) throws IOException {
        String rel = sub == null || sub.isEmpty() ? relDir : relDir + "/" + sub;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ApplicationLoader.applicationContext.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            Uri uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) {
                throw new IOException("MediaStore insert failed: " + rel + "/" + name);
            }
            OutputStream out = cr.openOutputStream(uri);
            if (out == null) {
                throw new IOException("MediaStore open failed: " + rel + "/" + name);
            }
            return out;
        }
        File dir = new File(Environment.getExternalStorageDirectory(), rel);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdirs failed: " + dir);
        }
        return new FileOutputStream(new File(dir, name));
    }

    void writeText(String sub, String name, String text) throws IOException {
        try (OutputStream out = open(sub, name)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    void copy(File src, String sub, String name) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = open(sub, name)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "html": return "text/html";
            case "json": return "application/json";
            case "css": return "text/css";
        }
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }
}

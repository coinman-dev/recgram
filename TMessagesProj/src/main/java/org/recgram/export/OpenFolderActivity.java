package org.recgram.export;

import static org.recgram.RLocale.s;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.Toast;

import org.telegram.messenger.FileLog;

import java.io.File;

/**
 * Открывает папку экспорта в файловом менеджере. Прозрачная и без истории: через неё открывается
 * папка из уведомления «Экспорт завершён» (с Android 12 из уведомления можно запускать только activity).
 * Единого способа открыть папку в Android нет, поэтому пробуем по очереди:
 * «Мои файлы» Samsung, системное приложение «Файлы» (DocumentsUI), затем любой менеджер для папки.
 */
public class OpenFolderActivity extends Activity {

    private static final String EXTRA_DIR = "dir";

    /** Intent для уведомления; relDir — путь от корня памяти, например Documents/RecGram/Export/… */
    public static PendingIntent pendingIntent(Context ctx, String relDir, int requestCode) {
        Intent i = new Intent(ctx, OpenFolderActivity.class).putExtra(EXTRA_DIR, relDir)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String dir = getIntent().getStringExtra(EXTRA_DIR);
        if (dir != null) open(this, dir);
        finish();
    }

    /** Открыть папку; если не получилось — сообщение с путём. */
    public static boolean open(Context ctx, String relDir) {
        for (Intent i : candidates(relDir)) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(i);
                return true;
            } catch (Throwable e) {
                FileLog.d("RecGram: не открылось " + i.getAction() + " " + i.getPackage() + ": " + e.getClass().getSimpleName());
            }
        }
        Toast.makeText(ctx, s("Нет приложения, которое открывает папки. Файлы лежат в ", "No app can open folders. The files are in ") + relDir, Toast.LENGTH_LONG).show();
        return false;
    }

    private static Intent[] candidates(String relDir) {
        String abs = new File(Environment.getExternalStorageDirectory(), relDir).getAbsolutePath();
        // «Мои файлы» Samsung: открывает сразу нужную папку
        Intent samsung = new Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
                .setPackage("com.sec.android.app.myfiles")
                .putExtra("samsung.myfiles.intent.extra.START_PATH", abs);
        // «Файлы» Android (DocumentsUI): документ-папка в основной памяти
        Uri doc = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + relDir);
        Intent docsUi = new Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR);
        // прочие файловые менеджеры понимают путь с типом resource/folder
        Intent generic = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file://" + abs), "resource/folder");
        return new Intent[]{samsung, docsUi, generic};
    }
}

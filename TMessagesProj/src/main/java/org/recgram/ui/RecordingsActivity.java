package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Toast;

import org.json.JSONObject;
import org.recgram.rec.CallRecordController;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Список записей звонков RecGram из общей папки. */
public class RecordingsActivity extends UniversalFragment {

    private static final int ID_BASE = 1000;

    private static final class Rec {
        long id;
        String name;
        long durationMs;
        long dateSec;
        long size;
        String mime;
        Uri uri;
    }

    private final ArrayList<Rec> recs = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        load();
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return s("Записи звонков", "Call recordings");
    }

    private void load() {
        recs.clear();
        ContentResolver cr = getParentActivity() != null ? getParentActivity().getContentResolver()
                : org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE};
        String where;
        String[] args;
        if (Build.VERSION.SDK_INT >= 29) {
            where = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{"%/RecGram/%"};
        } else {
            where = MediaStore.Audio.Media.DATA + " LIKE ?";
            args = new String[]{"%/RecGram/%"};
        }
        try (Cursor c = cr.query(collection, proj, where, args, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c == null) {
                return;
            }
            while (c.moveToNext()) {
                Rec r = new Rec();
                r.id = c.getLong(0);
                r.name = c.getString(1);
                r.durationMs = c.getLong(2);
                r.dateSec = c.getLong(3);
                r.size = c.getLong(4);
                r.mime = c.getString(5);
                r.uri = ContentUris.withAppendedId(collection, r.id);
                recs.add(r);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String baseName(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : String.valueOf(name);
    }

    private static String duration(long ms) {
        long sec = ms / 1000;
        return sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (recs.isEmpty()) {
            items.add(UItem.asShadow(s("Записей пока нет. Они появятся в папке Recordings/RecGram после записанного звонка.",
                    "No recordings yet. They appear in Recordings/RecGram after a recorded call.")));
            return;
        }
        items.add(UItem.asHeader(s("Recordings/RecGram — ", "Recordings/RecGram — ") + recs.size()));
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            items.add(UItem.asButton(ID_BASE + i, baseName(r.name), duration(r.durationMs)));
        }
        items.add(UItem.asShadow(s("Нажмите на запись, чтобы прослушать, поделиться, посмотреть данные звонка или удалить.",
                "Tap a recording to play, share, view call data or delete it.")));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        int i = item.id - ID_BASE;
        if (i < 0 || i >= recs.size()) {
            return;
        }
        Rec r = recs.get(i);
        String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(r.dateSec * 1000));
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(baseName(r.name));
        b.setMessage(date + " · " + duration(r.durationMs) + " · " + Formatter.formatShortFileSize(getParentActivity(), r.size));
        b.setItems(new CharSequence[]{
                s("Прослушать", "Play"),
                s("Поделиться", "Share"),
                s("Данные звонка", "Call data"),
                s("Удалить", "Delete")
        }, (d, which) -> {
            switch (which) {
                case 0: play(r); break;
                case 1: share(r); break;
                case 2: showInfo(r); break;
                case 3: confirmDelete(r); break;
            }
        });
        showDialog(b.create());
    }

    private void play(Rec r) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(r.uri, r.mime != null ? r.mime : "audio/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, s("Прослушать", "Play")));
        } catch (Throwable e) {
            Toast.makeText(getParentActivity(), s("Нет приложения для воспроизведения", "No app to play it"), Toast.LENGTH_SHORT).show();
        }
    }

    private void share(Rec r) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(r.mime != null ? r.mime : "audio/*");
        intent.putExtra(Intent.EXTRA_STREAM, r.uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getParentActivity().startActivity(Intent.createChooser(intent, s("Поделиться записью", "Share recording")));
    }

    private void showInfo(Rec r) {
        StringBuilder sb = new StringBuilder();
        File json = new File(CallRecordController.recordingsDir(), baseName(r.name) + ".json");
        if (json.exists()) {
            try (FileInputStream in = new FileInputStream(json)) {
                byte[] data = new byte[(int) json.length()];
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                JSONObject o = new JSONObject(new String(data, 0, off, StandardCharsets.UTF_8));
                line(sb, s("Начало", "Started"), o.optString("started"));
                line(sb, s("Длительность", "Duration"), duration(o.optLong("duration_ms")));
                line(sb, s("Направление", "Direction"), "outgoing".equals(o.optString("direction")) ? s("исходящий", "outgoing") : s("входящий", "incoming"));
                line(sb, s("Собеседник", "Other party"), o.optString("peer_name"));
                line(sb, s("Телефон", "Phone"), o.optString("peer_phone"));
                line(sb, s("Страна", "Country"), o.optString("country") + " (" + o.optString("country_rule") + ")");
                line(sb, s("Собеседник предупреждён", "Other party notified"), o.optBoolean("peer_notified") ? s("да", "yes") : s("нет", "no"));
                line(sb, s("Формат", "Format"), o.optString("codec"));
                line(sb, "SHA-256", o.optString("sha256"));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (sb.length() == 0) {
            sb.append(s("Данных звонка нет: запись сделана старой версией или приложение переустанавливалось.",
                    "No call data: the recording was made by an older version or the app was reinstalled."));
        } else {
            sb.append("\n").append(s("SHA-256 посчитана сразу после записи. Если файл изменить, сумма не совпадёт.",
                    "SHA-256 was computed right after recording. If the file is changed, it will not match."));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(s("Данные звонка", "Call data"));
        b.setMessage(sb.toString());
        b.setPositiveButton(s("Закрыть", "Close"), null);
        showDialog(b.create());
    }

    private static void line(StringBuilder sb, String k, String v) {
        if (v != null && !v.isEmpty()) {
            sb.append(k).append(": ").append(v).append("\n");
        }
    }

    private void confirmDelete(Rec r) {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(s("Удалить запись?", "Delete recording?"));
        b.setMessage(baseName(r.name));
        b.setPositiveButton(s("Удалить", "Delete"), (d, w) -> {
            try {
                getParentActivity().getContentResolver().delete(r.uri, null, null);
                new File(CallRecordController.recordingsDir(), baseName(r.name) + ".json").delete();
            } catch (Throwable e) {
                FileLog.e(e);
                Toast.makeText(getParentActivity(), s("Не удалось удалить: удалите файл в «Моих файлах».",
                        "Could not delete: remove the file in your file manager."), Toast.LENGTH_LONG).show();
            }
            load();
            listView.adapter.update(true);
        });
        b.setNegativeButton(s("Отмена", "Cancel"), null);
        AlertDialog dialog = b.create();
        showDialog(dialog);
        android.widget.TextView btn = (android.widget.TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (btn != null) {
            btn.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_text_RedBold));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

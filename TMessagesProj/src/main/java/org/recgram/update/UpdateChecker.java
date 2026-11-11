package org.recgram.update;

import static org.recgram.RLocale.s;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import androidx.core.util.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.recgram.RecGramVersion;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Проверка новых версий RecGram: последний (не пред-) релиз на GitHub, раз в сутки при открытии
 * приложения. Если версия новее установленной — окно со ссылкой на APK. Встроенное обновление
 * Telegram в RecGram выключено (оно поставило бы официальный Telegram), поэтому проверяем сами.
 * На GitHub уходит только обычный запрос к API без данных пользователя.
 */
public final class UpdateChecker {

    private UpdateChecker() {
    }

    private static final String API = "https://api.github.com/repos/coinman-dev/recgram/releases/latest";
    public static final String RELEASES = "https://github.com/coinman-dev/recgram/releases";
    private static final long DAY = 24L * 3600 * 1000;

    private static boolean running;
    private static boolean shownThisSession;

    /** Найденный релиз. */
    public static final class Release {
        public String version;   // «1.0.1»
        public String notes;     // текст релиза (может быть пустым)
        public String pageUrl;   // страница релиза
        public String apkUrl;    // прямая ссылка на APK релиза (RecGram-v1.2.3.apk), если есть
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("recgram_update", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", true);
    }

    public static void setEnabled(boolean v) {
        prefs().edit().putBoolean("enabled", v).apply();
    }

    /** Из LaunchActivity.onResume: не чаще раза в сутки, окно — не больше раза за запуск. */
    public static void checkIfDue() {
        try {
            if (!isEnabled() || shownThisSession) return;
            long last = prefs().getLong("last_check", 0);
            if (System.currentTimeMillis() - last < DAY) return;
            check(r -> {
                if (r == null || r.version.equals(prefs().getString("skipped", null))) return;
                BaseFragment f = LaunchActivity.getLastFragment();
                if (f != null && f.getParentActivity() != null) {
                    shownThisSession = true;
                    showDialog(f, r);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Проверить сейчас. Результат в UI-потоке: релиз новее установленного, или null (нет новее / ошибка). */
    public static void check(Consumer<Release> done) {
        if (running) return;
        running = true;
        new Thread(() -> {
            Release found = null;
            boolean ok = false;
            try {
                found = fetch();
                ok = true;
            } catch (Throwable e) {
                FileLog.d("RecGram: проверка обновлений не удалась: " + e);
            }
            if (ok) prefs().edit().putLong("last_check", System.currentTimeMillis()).apply();
            final boolean reached = ok;
            final Release r = found != null && isNewer(found.version, RecGramVersion.own()) ? found : null;
            AndroidUtilities.runOnUIThread(() -> {
                running = false;
                lastError = !reached;
                done.accept(r);
            });
        }, "RecGramUpdate").start();
    }

    /** Последняя проверка не дошла до GitHub (нет сети, репозиторий недоступен). */
    public static volatile boolean lastError;

    private static Release fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "RecGram/" + RecGramVersion.own());
        try {
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            JSONObject o;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 2 * 1024 * 1024) out.write(buf, 0, n);
                o = new JSONObject(out.toString("UTF-8"));
            }
            Release r = new Release();
            r.version = o.optString("tag_name").replaceFirst("^[vV]", "");
            r.notes = o.optString("body", "");
            r.pageUrl = o.optString("html_url", RELEASES);
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a != null && a.optString("name").matches("RecGram.*\\.apk")) {
                        r.apkUrl = a.optString("browser_download_url", null);
                    }
                }
            }
            if (TextUtils.isEmpty(r.version)) throw new IllegalStateException("нет tag_name");
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** «1.0.10» новее «1.0.9»; суффиксы (-beta.1) у latest-релиза не встречаются, но отбрасываются. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate), b = parse(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parse(String v) {
        int[] r = new int[3];
        String[] p = v.split("-")[0].split("\\.");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try {
                r[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException ignore) {
            }
        }
        return r;
    }

    public static void showDialog(BaseFragment f, Release r) {
        Context ctx = f.getParentActivity();
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(s("Вышла RecGram ", "RecGram ") + r.version + s("", " is out"));
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600).trim() + "…";
        b.setMessage(s("Установлена ", "Installed: ") + RecGramVersion.display() + "."
                + (notes.isEmpty() ? "" : "\n\n" + notes)
                + "\n\n" + s("Новая версия ставится поверх текущей, данные сохраняются.",
                "The new version installs over this one; your data stays."));
        b.setPositiveButton(s("Скачать", "Download"), (d, w) -> open(ctx, r.apkUrl != null ? r.apkUrl : r.pageUrl));
        b.setNeutralButton(s("Пропустить", "Skip"), (d, w) -> prefs().edit().putString("skipped", r.version).apply());
        b.setNegativeButton(s("Позже", "Later"), null);
        f.showDialog(b.create());
    }

    public static void open(Context ctx, String url) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

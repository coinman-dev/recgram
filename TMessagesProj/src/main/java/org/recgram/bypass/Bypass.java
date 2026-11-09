package org.recgram.bypass;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import androidx.core.util.Consumer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Обход блокировок внутри RecGram: защита от DPI в сетевом модуле Telegram и работа со списком прокси.
 * Без системного VPN — затрагивает только соединения самого приложения.
 */
public final class Bypass {

    public static final int DPI_OFF = 0, DPI_SPLIT = 1, DPI_DISORDER = 2, DPI_OOB = 3;

    private Bypass() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("recgram_bypass", Context.MODE_PRIVATE);
    }

    public static int getDpiMode() { return prefs().getInt("dpi_mode", DPI_OFF); }
    public static boolean isDpiDirect() { return prefs().getBoolean("dpi_direct", false); }
    public static String getSubscriptionUrl() { return prefs().getString("subscription_url", ""); }

    public static void setDpi(int mode, boolean direct) {
        prefs().edit().putInt("dpi_mode", mode).putBoolean("dpi_direct", direct).apply();
        applyDpi();
        reconnect();
    }

    public static void setSubscriptionUrl(String url) {
        prefs().edit().putString("subscription_url", url == null ? "" : url.trim()).apply();
    }

    // ------------------------------------------------------------------ первый запуск

    /** Страны, где Telegram блокируют или сильно ограничивают: без обхода войти не получится. */
    private static final java.util.Set<String> BLOCKING = new java.util.HashSet<>(java.util.Arrays.asList(
            "RU", "IR", "CN", "TM", "VN", "KP"));

    /** Страна по SIM-карте или сети; если не удалось — по языку системы. */
    public static String countryIso() {
        String iso = org.recgram.rules.RecordingRules.detectIso();
        if (TextUtils.isEmpty(iso)) {
            iso = java.util.Locale.getDefault().getCountry();
        }
        return iso == null ? "" : iso.toUpperCase(java.util.Locale.US);
    }

    public static boolean isBlockingCountry(String iso) {
        return iso != null && BLOCKING.contains(iso);
    }

    /** Шаг «Подключение к Telegram» пройден (показывается один раз, после ввода API-ключа). */
    public static boolean isSetupDone() { return prefs().getBoolean("setup_done", false); }
    public static void setSetupDone() { prefs().edit().putBoolean("setup_done", true).commit(); }

    private static boolean applied;

    /** Передать режим в сетевой модуль. Вызывается при запуске сетевого модуля и при изменении настройки. */
    public static void applyDpi() {
        try {
            ConnectionsManager.native_setDpiDesync(getDpiMode(), isDpiDirect());
            applied = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void applyDpiOnce() {
        if (!applied) {
            applyDpi();
        }
    }

    /** Переподключиться с текущими настройками прокси, чтобы новый режим подействовал сразу. */
    private static void reconnect() {
        boolean enabled = SharedConfig.isProxyEnabled();
        ConnectionsManager.setProxySettings(enabled, enabled ? SharedConfig.currentProxy.settings : ProxySettings.EMPTY);
    }

    // ------------------------------------------------------------------ импорт

    private static final Pattern LINK = Pattern.compile(
            "(tg://(?:proxy|socks)\\?[^\\s\"'<>]+|https?://(?:t\\.me|telegram\\.me|telegram\\.dog)/(?:proxy|socks)\\?[^\\s\"'<>]+)",
            Pattern.CASE_INSENSITIVE);

    /** Добавляет все ссылки на прокси из текста. Возвращает число новых прокси. */
    public static int importLinks(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        int added = 0;
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            try {
                String link = m.group(1).replace("&amp;", "&");
                ProxySettings s = ProxySettings.fromUri(Uri.parse(link));
                if (s == null || !s.isValid()) {
                    continue;
                }
                int before = SharedConfig.proxyList.size();
                SharedConfig.addProxy(new SharedConfig.ProxyInfo(s));
                if (SharedConfig.proxyList.size() > before) {
                    added++;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (added > 0) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }
        return added;
    }

    /** Загружает список по ссылке подписки (обычный текст или Base64) и добавляет прокси. Результат — в UI-потоке; -1 при ошибке. */
    public static void updateSubscription(Consumer<Integer> done) {
        String url = getSubscriptionUrl();
        if (TextUtils.isEmpty(url)) {
            done.accept(-1);
            return;
        }
        new Thread(() -> {
            String text = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "RecGram");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 4 * 1024 * 1024) {
                        out.write(buf, 0, n);
                    }
                    text = out.toString("UTF-8");
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String body = text;
            AndroidUtilities.runOnUIThread(() -> {
                if (body == null) {
                    done.accept(-1);
                    return;
                }
                int n = importLinks(body);
                if (n == 0 && !LINK.matcher(body).find()) {
                    try {
                        n = importLinks(new String(Base64.decode(body.trim(), Base64.DEFAULT), StandardCharsets.UTF_8));
                    } catch (Throwable ignore) {
                    }
                }
                done.accept(n);
            });
        }, "RecGramSubscription").start();
    }

    // ------------------------------------------------------------------ проверка

    /** Ход проверки: сколько проверено, сколько всего, сколько ответили. Вызывается в UI-потоке. */
    public interface CheckProgress {
        void onProgress(int checked, int total, int ok);
    }

    public static void checkAll(Consumer<ArrayList<SharedConfig.ProxyInfo>> done) {
        checkAll(null, done);
    }

    /** Проверяет все прокси. По окончании (в UI-потоке) — список рабочих, от быстрого к медленному. */
    public static void checkAll(CheckProgress progress, Consumer<ArrayList<SharedConfig.ProxyInfo>> done) {
        SharedConfig.loadProxyList();
        ArrayList<SharedConfig.ProxyInfo> list = new ArrayList<>(SharedConfig.proxyList);
        if (list.isEmpty()) {
            done.accept(new ArrayList<>());
            return;
        }
        int account = UserConfig.selectedAccount;
        int total = list.size();
        int[] checked = {0};
        int[] ok = {0};
        boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            ArrayList<SharedConfig.ProxyInfo> working = new ArrayList<>();
            for (SharedConfig.ProxyInfo p : list) {
                p.checking = false;
                if (p.available && p.ping > 0) working.add(p);
            }
            Collections.sort(working, (a, b) -> Long.compare(a.ping, b.ping));
            SharedConfig.saveProxyList();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            done.accept(working);
        };
        if (progress != null) {
            progress.onProgress(0, total, 0);
        }
        for (SharedConfig.ProxyInfo p : list) {
            p.checking = true;
            p.available = false;
            p.ping = 0;
            ConnectionsManager.getInstance(account).checkProxy(p.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                if (finished[0]) return;
                p.checking = false;
                p.availableCheckTime = android.os.SystemClock.elapsedRealtime();
                p.available = time > 0;
                p.ping = time > 0 ? time : 0;
                checked[0]++;
                if (time > 0) ok[0]++;
                if (progress != null) {
                    progress.onProgress(checked[0], total, ok[0]);
                }
                if (checked[0] >= total) finish.run();
            }));
        }
        AndroidUtilities.runOnUIThread(finish, CHECK_TIMEOUT_MS);
    }

    /** Сколько ждать ответа всех прокси в одном режиме. */
    public static final int CHECK_TIMEOUT_MS = 15000;

    /** Включить прокси так же, как это делает список прокси Telegram. */
    public static void enable(SharedConfig.ProxyInfo info) {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.commit();
        SharedConfig.currentProxy = info;
        ConnectionsManager.setProxySettings(true, info.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    /** Ход автоподбора. Вызывается в UI-потоке. */
    public interface AutoProgress {
        /** step — номер режима (с 1), steps — всего режимов; checked/total/ok — проверка прокси в этом режиме. */
        void onStep(int mode, int step, int steps, int checked, int total, int ok);

        /** Режим проверен: сколько прокси ответили и лучший пинг (0 — ни один). */
        void onModeDone(int mode, int ok, long bestPing);
    }

    /**
     * Автоподбор режима защиты: для каждого режима проверяет все прокси и оставляет режим,
     * при котором работает больше прокси (при равенстве — с самым быстрым). Результат — [режим, рабочих, пинг] или null.
     */
    public static void autoSelect(boolean direct, AutoProgress progress, Consumer<long[]> done) {
        int[] modes = {DPI_OFF, DPI_SPLIT, DPI_DISORDER, DPI_OOB};
        long[] best = {-1, 0, Long.MAX_VALUE};
        int startMode = getDpiMode();
        boolean startDirect = isDpiDirect();
        tryMode(modes, 0, direct, best, progress, () -> {
            if (best[0] < 0) {
                setDpi(startMode, startDirect);
                done.accept(null);
            } else {
                setDpi((int) best[0], direct);
                done.accept(best.clone());
            }
        });
    }

    private static void tryMode(int[] modes, int i, boolean direct, long[] best, AutoProgress progress, Runnable end) {
        if (i >= modes.length) {
            end.run();
            return;
        }
        int mode = modes[i];
        prefs().edit().putInt("dpi_mode", mode).putBoolean("dpi_direct", direct).apply();
        applyDpi();
        checkAll((checked, total, ok) -> {
            if (progress != null) progress.onStep(mode, i + 1, modes.length, checked, total, ok);
        }, ok -> {
            long ping = ok.isEmpty() ? 0 : ok.get(0).ping;
            if (progress != null) progress.onModeDone(mode, ok.size(), ping);
            if (!ok.isEmpty()) {
                long count = ok.size();
                if (count > best[1] || count == best[1] && ping < best[2]) {
                    best[0] = mode;
                    best[1] = count;
                    best[2] = ping;
                }
            }
            tryMode(modes, i + 1, direct, best, progress, end);
        });
    }
}

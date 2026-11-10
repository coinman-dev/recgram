package org.recgram.bypass;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.core.util.Consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Встроенный Xray-клиент (VLESS/Reality/xhttp и др.): только трафик RecGram, без системного VPN.
 * Xray (libxray.so, собирается recgram/tools/build_xray.sh) запускается отдельным процессом с inbound SOCKS5
 * на 127.0.0.1 со случайным логином и паролем; сетевой модуль Telegram ходит через этот SOCKS5.
 * Конфиг передаётся через stdin — на диск не пишется.
 */
public final class Vless {

    private Vless() {
    }

    private static final String LIB = "libxray.so";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> new Thread(r, "RecGramXray"));
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private static Process process;        // основной процесс (под замком Vless.class)
    private static String runningId;
    private static volatile String lastError;
    private static volatile boolean ready;
    private static int restarts;
    private static boolean observing;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("recgram_vless", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ список серверов

    public static ArrayList<VlessConfig> list() {
        ArrayList<VlessConfig> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                VlessConfig c = VlessConfig.fromJson(a.getJSONObject(i));
                if (c != null) {
                    c.ping = pings.containsKey(c.id) ? pings.get(c.id) : -1;
                    out.add(c);
                }
            }
        } catch (JSONException e) {
            FileLog.e(e);
        }
        return out;
    }

    private static final java.util.HashMap<String, Long> pings = new java.util.HashMap<>();

    private static void save(ArrayList<VlessConfig> list) {
        JSONArray a = new JSONArray();
        for (VlessConfig c : list) {
            try {
                a.put(c.toJson());
            } catch (JSONException e) {
                FileLog.e(e);
            }
        }
        prefs().edit().putString("list", a.toString()).commit();
    }

    public static VlessConfig find(String id) {
        if (id == null) return null;
        for (VlessConfig c : list()) {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    /** Добавляет серверы из текста (ссылки vless://, JSON). Возвращает число новых. */
    public static int importText(String text) {
        ArrayList<VlessConfig> found = VlessConfig.parseAll(text);
        if (found.isEmpty()) return 0;
        ArrayList<VlessConfig> list = list();
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (VlessConfig c : list) keys.add(c.dedupKey());
        int added = 0;
        for (VlessConfig c : found) {
            if (keys.add(c.dedupKey())) {
                list.add(c);
                added++;
            }
        }
        if (added > 0) {
            save(list);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }
        return added;
    }

    public static void delete(VlessConfig c) {
        if (c.id.equals(getSelectedId()) && isEnabled()) {
            disable();
        }
        ArrayList<VlessConfig> list = list();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(c.id)) list.remove(i);
        }
        save(list);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    public static void rename(VlessConfig c, String name) {
        ArrayList<VlessConfig> list = list();
        for (VlessConfig x : list) {
            if (x.id.equals(c.id)) x.name = name;
        }
        save(list);
    }

    // ------------------------------------------------------------------ состояние

    public static boolean isAvailable() {
        File f = binary();
        return f.exists() && f.canExecute();
    }

    private static File binary() {
        return new File(ApplicationLoader.applicationContext.getApplicationInfo().nativeLibraryDir, LIB);
    }

    public static String getSelectedId() { return prefs().getString("selected", null); }
    public static boolean isEnabled() { return prefs().getBoolean("enabled", false); }
    public static boolean isReady() { return ready; }
    public static String getLastError() { return lastError; }

    /** Telegram сейчас ходит через наш локальный SOCKS5. */
    public static boolean isActive() {
        return isEnabled() && localProxyInUse();
    }

    /**
     * Прокси Telegram сейчас — наш локальный SOCKS5. Читаем прямо из настроек: SharedConfig.currentProxy
     * до загрузки списка прокси пуст (при запуске приложения), по нему VLESS ошибочно выключался.
     */
    private static boolean localProxyInUse() {
        SharedPreferences p = MessagesController.getGlobalMainSettings();
        return p.getBoolean("proxy_enabled", false) && isLocal(ProxySettings.fromSharedPreferences(p));
    }

    public static boolean isLocal(ProxySettings s) {
        return s != null && s.getType() == ProxySettings.Type.SOCKS5 && "127.0.0.1".equals(s.getAddress())
                && s.getUser().equals(prefs().getString("user", "\0"));
    }

    public static synchronized String logText() {
        StringBuilder b = new StringBuilder();
        for (String s : LOG) b.append(s).append('\n');
        return b.toString().trim();
    }

    private static synchronized void log(String line) {
        // uuid и прочие длинные секреты в журнал не пускаем
        line = line.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>");
        LOG.addLast(line);
        while (LOG.size() > 60) LOG.removeFirst();
    }

    private static String randomToken() {
        SecureRandom r = new SecureRandom();
        String abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    /** Логин, пароль и порт локального SOCKS5 (создаются один раз). */
    private static ProxySettings localSettings(boolean newPort) {
        SharedPreferences p = prefs();
        SharedPreferences.Editor e = p.edit();
        if (!p.contains("user")) e.putString("user", randomToken()).putString("pass", randomToken());
        int port = p.getInt("port", 0);
        if (port <= 0 || newPort) {
            port = 20000 + new SecureRandom().nextInt(40000);
            e.putInt("port", port);
        }
        e.commit();
        return ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress("127.0.0.1")
                .setPort(port)
                .setUser(p.getString("user", ""))
                .setPassword(p.getString("pass", ""))
                .build();
    }

    // ------------------------------------------------------------------ конфиг Xray

    /** Копия outbound с tag и IP вместо имени сервера (у Go на Android нет системного DNS). */
    private static JSONObject prepareOutbound(VlessConfig c, String tag) throws JSONException {
        JSONObject ob = new JSONObject(c.outbound.toString());
        ob.put("tag", tag);
        JSONObject settings = ob.optJSONObject("settings");
        if (settings != null) {
            JSONObject srv = VlessConfig.firstServer(settings);
            JSONObject target = srv != null ? srv : settings.has("address") ? settings : null;
            if (target != null) {
                String host = target.optString("address");
                String ip = resolve(host);
                if (ip != null && !ip.equals(host)) {
                    target.put("address", ip);
                    keepHostName(ob, host);
                }
            }
        }
        return ob;
    }

    /** Без явного имени TLS/Host берёт адрес сервера — после замены на IP подставляем прежнее имя. */
    private static void keepHostName(JSONObject ob, String host) throws JSONException {
        JSONObject ss = ob.optJSONObject("streamSettings");
        if (ss == null) return;
        String sec = ss.optString("security", "none");
        if ("tls".equals(sec)) {
            JSONObject tls = ss.optJSONObject("tlsSettings");
            if (tls == null) ss.put("tlsSettings", tls = new JSONObject());
            if (TextUtils.isEmpty(tls.optString("serverName"))) tls.put("serverName", host);
        }
        String[] keys = {"wsSettings", "httpupgradeSettings", "xhttpSettings", "splithttpSettings"};
        String net = ss.optString("network", "tcp");
        for (String k : keys) {
            if (k.startsWith(net) || "splithttp".equals(net) && k.startsWith("xhttp")) {
                JSONObject t = ss.optJSONObject(k);
                if (t == null) ss.put(k, t = new JSONObject());
                if (TextUtils.isEmpty(t.optString("host")) && !"tls".equals(sec) && !"reality".equals(sec)) t.put("host", host);
            }
        }
    }

    private static String resolve(String host) {
        if (TextUtils.isEmpty(host) || host.matches("[0-9.]+") || host.contains(":")) return host;
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) {
                if (a instanceof Inet4Address) return a.getHostAddress();
            }
            return all.length > 0 ? all[0].getHostAddress() : null;
        } catch (Throwable e) {
            log("DNS: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static JSONObject socksInbound(String tag, ProxySettings s) throws JSONException {
        JSONObject acc = new JSONObject().put("user", s.getUser()).put("pass", s.getPassword());
        JSONObject set = new JSONObject().put("auth", "password").put("accounts", new JSONArray().put(acc))
                .put("udp", true).put("ip", "127.0.0.1");
        return new JSONObject().put("tag", tag).put("listen", "127.0.0.1").put("port", s.getPort())
                .put("protocol", "socks").put("settings", set);
    }

    private static JSONObject baseConfig() throws JSONException {
        // access: none — адреса, куда ходит Telegram, в журнал не пишем
        return new JSONObject().put("log", new JSONObject().put("loglevel", "warning").put("access", "none"));
    }

    // ------------------------------------------------------------------ процесс

    private static Process spawn(String config, String name, Runnable onExit) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(binary().getAbsolutePath(), "run", "-c", "stdin:");
        pb.redirectErrorStream(true);
        pb.directory(ApplicationLoader.applicationContext.getFilesDir());
        pb.environment().put("XRAY_LOCATION_ASSET", ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath());
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) {
            os.write(config.getBytes(StandardCharsets.UTF_8));
        }
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log(name + ": " + line);
                    if (line.contains("Failed") || line.contains("failed to") || line.contains("[Error]")) {
                        lastError = line.replaceFirst("^.*?(Failed|failed to|\\[Error\\])", "$1");
                    }
                }
            } catch (Throwable ignore) {
            }
            if (onExit != null) onExit.run();
        }, "RecGramXrayLog");
        t.setDaemon(true);
        t.start();
        return p;
    }

    private static boolean waitPort(int port, Process p, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (!alive(p)) return false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (Throwable ignore) {
            }
            SystemClock.sleep(100);
        }
        return false;
    }

    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Процесс, оставшийся от прошлого запуска приложения (обычно Android убивает его вместе с приложением). */
    private static void killStale() {
        int pid = prefs().getInt("pid", 0);
        if (pid <= 0) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n > 0 && new String(b, 0, n, StandardCharsets.UTF_8).contains(LIB)) {
                android.os.Process.killProcess(pid);
            }
        } catch (Throwable ignore) {
        }
        prefs().edit().remove("pid").apply();
    }

    /** Запускает (или перезапускает) основной процесс для выбранного сервера. Вызывается в EXEC. */
    private static boolean startBlocking(VlessConfig c) {
        stopBlocking();
        killStale();
        ready = false;
        lastError = null;
        if (!isAvailable()) {
            lastError = "libxray.so не найден";
            return false;
        }
        ProxySettings local = localSettings(false);
        if (!portFree(local.getPort())) {
            local = localSettings(true);
        }
        try {
            JSONObject cfg = baseConfig();
            cfg.put("inbounds", new JSONArray().put(socksInbound("socks", local)));
            cfg.put("outbounds", new JSONArray().put(prepareOutbound(c, "proxy")));
            log("start: " + c.name + " (" + c.summary() + ")");
            Process[] self = new Process[1];
            Process p = spawn(cfg.toString(), "xray", () -> onMainExit(self[0]));
            self[0] = p;
            synchronized (Vless.class) {
                process = p;
                runningId = c.id;
            }
            prefs().edit().putInt("pid", pidOf(p)).apply();
            if (!waitPort(local.getPort(), p, 5000)) {
                if (lastError == null) lastError = alive(p) ? "Xray не открыл порт" : "Xray завершился";
                return false;
            }
            ready = true;
            restarts = 0;
            final ProxySettings settings = local;
            AndroidUtilities.runOnUIThread(() -> {
                if (isEnabled() && c.id.equals(getSelectedId())) {
                    useLocalProxy(settings);
                }
            });
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        } finally {
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
        }
    }

    private static void onMainExit(Process p) {
        boolean wanted;
        synchronized (Vless.class) {
            if (process != p) return; // остановлен намеренно или заменён новым
            process = null;
            runningId = null;
            wanted = isEnabled();
        }
        ready = false;
        log("xray: процесс завершился");
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
        if (wanted && restarts < 5) {
            restarts++;
            long delay = 2000L * restarts;
            AndroidUtilities.runOnUIThread(() -> {
                if (isEnabled() && process == null) start();
            }, delay);
        }
    }

    private static void stopBlocking() {
        Process p;
        synchronized (Vless.class) {
            p = process;
            process = null;
            runningId = null;
        }
        ready = false;
        if (p != null) {
            p.destroy();
            prefs().edit().remove("pid").apply();
        }
    }

    private static void start() {
        VlessConfig c = find(getSelectedId());
        if (c == null) return;
        observe();
        EXEC.execute(() -> startBlocking(c));
    }

    /** При запуске сетевого модуля: если VLESS включён — поднимаем Xray. */
    public static void startIfEnabled() {
        try {
            if (!isEnabled()) return;
            synchronized (Vless.class) {
                if (process != null) return;
            }
            AndroidUtilities.runOnUIThread(Vless::start);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Включить сервер: запустить Xray и направить Telegram через него. Результат — в UI-потоке. */
    public static void enable(VlessConfig c, Consumer<Boolean> done) {
        prefs().edit().putString("selected", c.id).putBoolean("enabled", true).commit();
        restarts = 0;
        observe();
        EXEC.execute(() -> {
            boolean ok = startBlocking(c);
            AndroidUtilities.runOnUIThread(() -> {
                if (!ok) {
                    prefs().edit().putBoolean("enabled", false).apply();
                    stop();
                }
                if (done != null) done.accept(ok);
            });
        });
    }

    /** Выключить VLESS: остановить Xray и выключить прокси, если Telegram ходил через него. */
    public static void disable() {
        boolean wasActive = isActive();
        prefs().edit().putBoolean("enabled", false).commit();
        stop();
        if (wasActive) {
            SharedPreferences.Editor e = MessagesController.getGlobalMainSettings().edit();
            e.putBoolean("proxy_enabled", false);
            e.commit();
            ConnectionsManager.setProxySettings(false, null);
        }
        removeLocalEntries();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private static void stop() {
        EXEC.execute(Vless::stopBlocking);
    }

    private static void useLocalProxy(ProxySettings s) {
        SharedPreferences mp = MessagesController.getGlobalMainSettings();
        if (mp.getBoolean("proxy_enabled", false) && s.equals(ProxySettings.fromSharedPreferences(mp))) {
            reconnect(); // уже настроен — просто не ждать паузы между попытками
            return;
        }
        removeLocalEntries();
        Bypass.enable(new SharedConfig.ProxyInfo(s));
    }

    private static void reconnect() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a == UserConfig.selectedAccount || UserConfig.getInstance(a).isClientActivated()) {
                try {
                    ConnectionsManager.native_recgramReconnect(a);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
    }

    /** Наш локальный SOCKS5 не должен оставаться в списке прокси Telegram. */
    private static void removeLocalEntries() {
        SharedConfig.loadProxyList();
        boolean changed = false;
        for (int i = SharedConfig.proxyList.size() - 1; i >= 0; i--) {
            SharedConfig.ProxyInfo p = SharedConfig.proxyList.get(i);
            if (p != SharedConfig.currentProxy && isLocal(p.settings)) {
                SharedConfig.proxyList.remove(i);
                changed = true;
            }
        }
        if (changed) SharedConfig.saveProxyList();
    }

    /** Пользователь выбрал другой прокси в списке Telegram — VLESS выключается. */
    private static void observe() {
        if (observing) return;
        observing = true;
        NotificationCenter.getGlobalInstance().addObserver(new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int account, Object... args) {
                if (!isEnabled() || !ready) return;
                if (!localProxyInUse()) {
                    prefs().edit().putBoolean("enabled", false).apply();
                    stop();
                    removeLocalEntries();
                }
            }
        }, NotificationCenter.proxySettingsChanged);
    }

    // ------------------------------------------------------------------ проверка

    /**
     * Проверяет серверы: временный процесс Xray с отдельным SOCKS5 на каждый сервер,
     * через каждый — проверка прокси Telegram (пинг до дата-центра). Результат в UI-потоке:
     * список, отсортированный от быстрого к медленному (ping 0 — не отвечает).
     */
    public static void checkAll(Consumer<ArrayList<VlessConfig>> done) {
        ArrayList<VlessConfig> list = list();
        if (list.isEmpty() || !isAvailable()) {
            done.accept(list);
            return;
        }
        new Thread(() -> {
            Process p = null;
            ArrayList<ProxySettings> socks = new ArrayList<>();
            try {
                JSONObject cfg = baseConfig();
                JSONArray in = new JSONArray(), out = new JSONArray(), rules = new JSONArray();
                SecureRandom r = new SecureRandom();
                String user = randomToken(), pass = randomToken();
                for (int i = 0; i < list.size(); i++) {
                    int port;
                    do {
                        port = 20000 + r.nextInt(40000);
                    } while (!portFree(port));
                    ProxySettings s = ProxySettings.builder().setType(ProxySettings.Type.SOCKS5).setAddress("127.0.0.1")
                            .setPort(port).setUser(user).setPassword(pass).build();
                    socks.add(s);
                    in.put(socksInbound("in" + i, s));
                    out.put(prepareOutbound(list.get(i), "out" + i));
                    rules.put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put("in" + i)).put("outboundTag", "out" + i));
                }
                cfg.put("inbounds", in).put("outbounds", out).put("routing", new JSONObject().put("rules", rules));
                p = spawn(cfg.toString(), "check", null);
                if (!waitPort(socks.get(socks.size() - 1).getPort(), p, 5000)) {
                    throw new IllegalStateException(lastError != null ? lastError : "Xray не запустился");
                }
            } catch (Throwable e) {
                FileLog.e(e);
                if (p != null) p.destroy();
                AndroidUtilities.runOnUIThread(() -> {
                    for (VlessConfig c : list) pings.put(c.id, 0L);
                    done.accept(list());
                });
                return;
            }
            final Process proc = p;
            AndroidUtilities.runOnUIThread(() -> {
                int[] left = {list.size()};
                boolean[] finished = {false};
                Runnable finish = () -> {
                    if (finished[0]) return;
                    finished[0] = true;
                    proc.destroy();
                    for (VlessConfig c : list) {
                        if (!pings.containsKey(c.id) || pings.get(c.id) < 0) pings.put(c.id, 0L);
                    }
                    ArrayList<VlessConfig> res = list();
                    Collections.sort(res, (a, b) -> Long.compare(a.ping > 0 ? a.ping : Long.MAX_VALUE, b.ping > 0 ? b.ping : Long.MAX_VALUE));
                    done.accept(res);
                };
                for (int i = 0; i < list.size(); i++) {
                    String id = list.get(i).id;
                    pings.put(id, -1L);
                    ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(socks.get(i), time -> AndroidUtilities.runOnUIThread(() -> {
                        if (finished[0]) return;
                        pings.put(id, time > 0 ? time : 0L);
                        if (--left[0] <= 0) finish.run();
                    }));
                }
                AndroidUtilities.runOnUIThread(finish, 20000);
            });
        }, "RecGramXrayCheck").start();
    }
}

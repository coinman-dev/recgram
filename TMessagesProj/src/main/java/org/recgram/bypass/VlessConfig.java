package org.recgram.bypass;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сервер для встроенного Xray: имя и outbound в формате Xray (JSON).
 * Разбирает ссылки vless:// (как «Поделиться» в v2RayTun/v2rayNG), outbound Xray, полный конфиг Xray,
 * конфигурацию v2RayTun (поле xrayOutbound) и его файл shared_preferences.json.
 * Секреты (uuid, ключи) хранятся только в настройках приложения и не попадают в журнал.
 */
public final class VlessConfig {

    public final String id;
    public String name;
    /** outbound Xray без tag (tag ставится при запуске). */
    public final JSONObject outbound;

    /** Результат последней проверки: пинг до Telegram в мс, 0 — не отвечает, -1 — не проверялся. */
    public long ping = -1;

    public VlessConfig(String id, String name, JSONObject outbound) {
        this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        this.name = name;
        this.outbound = outbound;
    }

    public String protocol() {
        return outbound.optString("protocol", "?");
    }

    /** Сервер AmneziaWG/WireGuard (запускается libawg.so), а не Xray. */
    public boolean isAwg() {
        return AwgConfig.isAwg(outbound);
    }

    /** Текст .conf для AmneziaWG. */
    String awgConf() {
        return outbound.optString("conf");
    }

    /** Адрес сервера (для сравнения дубликатов и показа): host:port. */
    public String address() {
        if (isAwg()) return AwgConfig.endpoint(awgConf());
        JSONObject s = outbound.optJSONObject("settings");
        if (s == null) return "";
        JSONObject srv = firstServer(s);
        if (srv != null) {
            return srv.optString("address") + ":" + srv.optInt("port");
        }
        return s.optString("address") + ":" + s.optInt("port");
    }

    /** Кратко: протокол · транспорт · защита, например «vless · xhttp · reality». */
    public String summary() {
        if (isAwg()) return AwgConfig.summary(awgConf());
        JSONObject ss = outbound.optJSONObject("streamSettings");
        String net = ss != null ? ss.optString("network", "tcp") : "tcp";
        String sec = ss != null ? ss.optString("security", "none") : "none";
        return protocol() + " · " + net + ("none".equals(sec) || sec.isEmpty() ? "" : " · " + sec);
    }

    /** Ключ для поиска дубликатов: всё содержимое outbound без tag. */
    String dedupKey() {
        try {
            JSONObject o = new JSONObject(outbound.toString());
            o.remove("tag");
            o.remove("mux");
            return o.toString();
        } catch (JSONException e) {
            return outbound.toString();
        }
    }

    static JSONObject firstServer(JSONObject settings) {
        JSONArray arr = settings.optJSONArray("vnext");
        if (arr == null) arr = settings.optJSONArray("servers");
        return arr != null && arr.length() > 0 ? arr.optJSONObject(0) : null;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("outbound", outbound);
        return o;
    }

    static VlessConfig fromJson(JSONObject o) {
        JSONObject ob = o.optJSONObject("outbound");
        if (ob == null) return null;
        return new VlessConfig(o.optString("id"), o.optString("name"), ob);
    }

    // ------------------------------------------------------------------ импорт

    private static final Pattern VLESS_LINK = Pattern.compile("vless://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    /** Протоколы Xray, которые принимаем из JSON (ссылки — только vless://). */
    private static final String[] PROTOCOLS = {"vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "wireguard"};

    public static boolean containsLinks(String text) {
        return text != null && (VLESS_LINK.matcher(text).find() || AwgConfig.looksLike(text));
    }

    /**
     * Все серверы из текста: ссылки vless:// и/или JSON (outbound, конфиг Xray, v2RayTun),
     * конфиги AmneziaWG/WireGuard (.conf) и ссылки vpn:// из Amnezia.
     */
    public static ArrayList<VlessConfig> parseAll(String text) {
        ArrayList<VlessConfig> out = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return out;
        AwgConfig.parseAll(text, out);
        String t = text.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                Object json = t.startsWith("{") ? new JSONObject(t) : new JSONArray(t);
                collectJson(json, null, out, 0);
            } catch (JSONException ignore) {
            }
        }
        Matcher m = VLESS_LINK.matcher(text);
        while (m.find()) {
            try {
                VlessConfig c = fromLink(m.group().replace("&amp;", "&"));
                if (c != null) out.add(c);
            } catch (Throwable ignore) {
            }
        }
        return out;
    }

    private static boolean isProxyOutbound(JSONObject o) {
        String p = o.optString("protocol");
        for (String s : PROTOCOLS) {
            if (s.equals(p)) return o.has("settings");
        }
        return false;
    }

    /** Обходит JSON и собирает outbound-ы: v2RayTun хранит конфигурации строками JSON внутри JSON. */
    private static void collectJson(Object json, String name, ArrayList<VlessConfig> out, int depth) throws JSONException {
        if (depth > 6 || json == null) return;
        if (json instanceof JSONArray) {
            JSONArray a = (JSONArray) json;
            for (int i = 0; i < a.length(); i++) collectJson(a.opt(i), name, out, depth + 1);
            return;
        }
        if (json instanceof String) {
            String s = ((String) json).trim();
            if (s.startsWith("{")) {
                try {
                    collectJson(new JSONObject(s), name, out, depth + 1);
                } catch (JSONException ignore) {
                }
            } else if (s.regionMatches(true, 0, "vless://", 0, 8)) {
                VlessConfig c = fromLink(s);
                if (c != null) out.add(c);
            }
            return;
        }
        if (!(json instanceof JSONObject)) return;
        JSONObject o = (JSONObject) json;
        // v2RayTun: {"remarks": …, "xrayOutbound": {…}}
        JSONObject xo = o.optJSONObject("xrayOutbound");
        if (xo == null && o.optString("xrayOutbound").startsWith("{")) {
            xo = new JSONObject(o.optString("xrayOutbound"));
        }
        String remarks = o.optString("remarks", o.optString("ps", name));
        if (xo != null && isProxyOutbound(xo)) {
            out.add(new VlessConfig(null, nameOr(remarks, xo), xo));
            return;
        }
        // одиночный outbound
        if (isProxyOutbound(o)) {
            out.add(new VlessConfig(null, nameOr(remarks, o), o));
            return;
        }
        // полный конфиг Xray: outbounds[]
        JSONArray obs = o.optJSONArray("outbounds");
        if (obs != null) {
            for (int i = 0; i < obs.length(); i++) {
                JSONObject ob = obs.optJSONObject(i);
                if (ob != null && isProxyOutbound(ob)) {
                    out.add(new VlessConfig(null, nameOr(remarks, ob), ob));
                }
            }
            return;
        }
        // прочие объекты (например shared_preferences.json): смотрим вложенные значения
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if ("flutter.configurations".equals(k) || "flutter.selected_config".equals(k)) continue;
            collectJson(o.opt(k), name, out, depth + 1);
        }
    }

    private static String nameOr(String name, JSONObject outbound) {
        if (!TextUtils.isEmpty(name)) return name;
        String tag = outbound.optString("tag");
        if (!TextUtils.isEmpty(tag) && !"proxy".equals(tag)) return tag;
        JSONObject s = outbound.optJSONObject("settings");
        JSONObject srv = s != null ? firstServer(s) : null;
        return srv != null ? srv.optString("address") : outbound.optString("protocol");
    }

    /**
     * vless://uuid@host:port?type=…&security=…&…#имя — формат ссылок XTLS (обсуждение Xray-core #716)
     * с параметрами xhttp (mode, extra) и Reality (pbk, sid, spx, pqv).
     */
    public static VlessConfig fromLink(String link) throws JSONException {
        Uri u = Uri.parse(link.trim());
        if (!"vless".equalsIgnoreCase(u.getScheme())) return null;
        String uuid = u.getUserInfo();
        String host = u.getHost();
        int port = u.getPort();
        if (TextUtils.isEmpty(uuid) || TextUtils.isEmpty(host) || port <= 0) return null;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);

        JSONObject user = new JSONObject();
        user.put("id", Uri.decode(uuid));
        user.put("encryption", q(u, "encryption", "none"));
        String flow = q(u, "flow", "");
        if (!flow.isEmpty()) user.put("flow", flow);
        JSONObject server = new JSONObject();
        server.put("address", host);
        server.put("port", port);
        server.put("users", new JSONArray().put(user));
        JSONObject settings = new JSONObject();
        settings.put("vnext", new JSONArray().put(server));

        String type = q(u, "type", "tcp").toLowerCase(Locale.US);
        if ("http".equals(type) || "h2".equals(type)) type = "xhttp"; // h2 в новых Xray заменён на xhttp
        String security = q(u, "security", "none").toLowerCase(Locale.US);
        String sni = q(u, "sni", "");
        String fp = q(u, "fp", "");
        String path = q(u, "path", "");
        String hostHdr = q(u, "host", "");

        JSONObject ss = new JSONObject();
        ss.put("network", type);
        ss.put("security", security);
        if ("tls".equals(security)) {
            JSONObject tls = new JSONObject();
            if (!sni.isEmpty()) tls.put("serverName", sni);
            if (!fp.isEmpty()) tls.put("fingerprint", fp);
            String alpn = q(u, "alpn", "");
            if (!alpn.isEmpty()) tls.put("alpn", new JSONArray(java.util.Arrays.asList(alpn.split(","))));
            String insecure = q(u, "allowInsecure", q(u, "insecure", ""));
            if ("1".equals(insecure) || "true".equalsIgnoreCase(insecure)) tls.put("allowInsecure", true);
            String ech = q(u, "ech", "");
            if (!ech.isEmpty()) tls.put("echConfigList", ech);
            ss.put("tlsSettings", tls);
        } else if ("reality".equals(security)) {
            JSONObject r = new JSONObject();
            if (!sni.isEmpty()) r.put("serverName", sni);
            r.put("fingerprint", fp.isEmpty() ? "chrome" : fp);
            r.put("publicKey", q(u, "pbk", ""));
            r.put("shortId", q(u, "sid", ""));
            String spx = q(u, "spx", "");
            if (!spx.isEmpty()) r.put("spiderX", spx);
            String pqv = q(u, "pqv", "");
            if (!pqv.isEmpty()) r.put("mldsa65Verify", pqv);
            ss.put("realitySettings", r);
        }
        switch (type) {
            case "ws": {
                JSONObject ws = new JSONObject();
                if (!path.isEmpty()) ws.put("path", path);
                if (!hostHdr.isEmpty()) ws.put("host", hostHdr);
                ss.put("wsSettings", ws);
                break;
            }
            case "httpupgrade": {
                JSONObject hu = new JSONObject();
                if (!path.isEmpty()) hu.put("path", path);
                if (!hostHdr.isEmpty()) hu.put("host", hostHdr);
                ss.put("httpupgradeSettings", hu);
                break;
            }
            case "xhttp":
            case "splithttp": {
                JSONObject xh = new JSONObject();
                xh.put("path", path.isEmpty() ? "/" : path);
                if (!hostHdr.isEmpty()) xh.put("host", hostHdr);
                xh.put("mode", q(u, "mode", "auto"));
                String extra = q(u, "extra", "");
                if (!extra.isEmpty()) {
                    try {
                        xh.put("extra", new JSONObject(extra));
                    } catch (JSONException ignore) {
                    }
                }
                ss.put("xhttpSettings", xh);
                break;
            }
            case "grpc": {
                JSONObject g = new JSONObject();
                g.put("serviceName", q(u, "serviceName", path));
                String authority = q(u, "authority", "");
                if (!authority.isEmpty()) g.put("authority", authority);
                if ("multi".equals(q(u, "mode", ""))) g.put("multiMode", true);
                ss.put("grpcSettings", g);
                break;
            }
            case "kcp": {
                JSONObject k = new JSONObject();
                String seed = q(u, "seed", "");
                if (!seed.isEmpty()) k.put("seed", seed);
                String ht = q(u, "headerType", "");
                if (!ht.isEmpty()) k.put("header", new JSONObject().put("type", ht));
                ss.put("kcpSettings", k);
                break;
            }
            case "tcp":
            case "raw": {
                if ("http".equals(q(u, "headerType", ""))) {
                    JSONObject req = new JSONObject();
                    req.put("path", new JSONArray().put(path.isEmpty() ? "/" : path));
                    if (!hostHdr.isEmpty()) {
                        req.put("headers", new JSONObject().put("Host", new JSONArray(java.util.Arrays.asList(hostHdr.split(",")))));
                    }
                    ss.put("tcpSettings", new JSONObject().put("header", new JSONObject().put("type", "http").put("request", req)));
                }
                break;
            }
        }

        JSONObject ob = new JSONObject();
        ob.put("protocol", "vless");
        ob.put("settings", settings);
        ob.put("streamSettings", ss);
        String name = u.getFragment();
        return new VlessConfig(null, TextUtils.isEmpty(name) ? host : name, ob);
    }

    private static String q(Uri u, String key, String def) {
        try {
            String v = u.getQueryParameter(key);
            return v == null ? def : v.trim();
        } catch (Throwable e) {
            return def;
        }
    }

    /** Текст подписки может быть в Base64 (обычном или URL-safe). */
    public static String maybeBase64(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (t.contains("://") || t.startsWith("{") || t.startsWith("[") || AwgConfig.looksLike(t)) return body;
        try {
            return new String(Base64.decode(t, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            try {
                return new String(Base64.decode(t, Base64.URL_SAFE), StandardCharsets.UTF_8);
            } catch (Throwable e2) {
                return body;
            }
        }
    }
}

package org.recgram.bypass;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Серверы AmneziaWG (и обычного WireGuard): файл .conf и ссылки vpn:// из приложения Amnezia.
 * Хранятся в общем списке {@link Vless} как outbound {"protocol":"amneziawg","conf":"…"};
 * запускает их libawg.so (recgram/awg). Ключи в журнал не попадают.
 */
public final class AwgConfig {

    private AwgConfig() {
    }

    public static final String PROTOCOL = "amneziawg";

    private static final Pattern VPN_LINK = Pattern.compile("vpn://[A-Za-z0-9_\\-+/=]+");
    private static final Pattern INTERFACE = Pattern.compile("^\\s*\\[Interface\\]", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    public static boolean isAwg(JSONObject outbound) {
        return outbound != null && PROTOCOL.equals(outbound.optString("protocol"));
    }

    /** Текст похож на конфиг AmneziaWG/WireGuard или содержит ссылку vpn://. */
    public static boolean looksLike(String text) {
        return text != null && (VPN_LINK.matcher(text).find() || isConf(text));
    }

    public static boolean isConf(String text) {
        if (text == null) return false;
        String l = text.toLowerCase(Locale.ROOT);
        return INTERFACE.matcher(text).find() && l.contains("[peer]") && l.contains("privatekey");
    }

    /** Все серверы AmneziaWG из текста: один или несколько .conf подряд и ссылки vpn://. */
    static void parseAll(String text, ArrayList<VlessConfig> out) {
        if (TextUtils.isEmpty(text)) return;
        if (isConf(text)) {
            Matcher m = INTERFACE.matcher(text);
            ArrayList<Integer> starts = new ArrayList<>();
            while (m.find()) starts.add(m.start());
            for (int i = 0; i < starts.size(); i++) {
                // первый кусок — с начала текста: там может быть строка «# Name = …»
                String chunk = text.substring(i == 0 ? 0 : starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length());
                VlessConfig c = make(chunk, null);
                if (c != null) out.add(c);
            }
        }
        Matcher m = VPN_LINK.matcher(text);
        while (m.find()) {
            try {
                String json = decodeVpnLink(m.group());
                if (json != null) fromAmneziaJson(new JSONObject(json), out);
            } catch (Throwable ignore) {
            }
        }
    }

    /** Сервер из текста одного .conf; null, если в нём нет ключей или адреса сервера. */
    static VlessConfig make(String conf, String name) {
        StringBuilder b = new StringBuilder();
        String comment = null;
        for (String line : conf.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.startsWith("#")) {
                Matcher nm = Pattern.compile("^#+\\s*(?:Name|Название)\\s*[=:]\\s*(.+)$", Pattern.CASE_INSENSITIVE).matcher(t);
                if (nm.find() && comment == null) comment = nm.group(1).trim();
                continue;
            }
            if (!t.isEmpty()) b.append(t).append('\n');
        }
        String clean = b.toString();
        if (value(clean, "PrivateKey") == null || value(clean, "PublicKey") == null || value(clean, "Endpoint") == null) {
            return null;
        }
        if (TextUtils.isEmpty(name)) name = comment;
        if (TextUtils.isEmpty(name)) name = "AmneziaWG " + host(endpoint(clean));
        try {
            JSONObject ob = new JSONObject().put("protocol", PROTOCOL).put("conf", clean);
            return new VlessConfig(null, name, ob);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Значение параметра (первое вхождение), без учёта регистра имени. */
    static String value(String conf, String key) {
        Matcher m = Pattern.compile("^\\s*" + key + "\\s*=\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(conf);
        return m.find() && !m.group(1).isEmpty() ? m.group(1) : null;
    }

    static String endpoint(String conf) {
        String e = value(conf, "Endpoint");
        return e == null ? "" : e;
    }

    static String host(String endpoint) {
        if (endpoint.startsWith("[")) {
            int i = endpoint.indexOf(']');
            return i > 0 ? endpoint.substring(1, i) : endpoint;
        }
        int i = endpoint.lastIndexOf(':');
        return i > 0 ? endpoint.substring(0, i) : endpoint;
    }

    static int port(String endpoint) {
        int i = endpoint.lastIndexOf(':');
        try {
            return i > 0 ? Integer.parseInt(endpoint.substring(i + 1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * «amneziawg 3» (HeaderProtectionKey, RandomTrailers, диапазоны таймингов), «amneziawg 2.0»
     * (S3/S4 или диапазоны H1–H4), «amneziawg» или «wireguard» без обфускации.
     */
    static String summary(String conf) {
        if (value(conf, "HeaderProtectionKey") != null || value(conf, "RandomTrailers") != null
                || value(conf, "ContentPaddingAddition") != null || value(conf, "RekeyAfterTime") != null) {
            return "amneziawg 3";
        }
        boolean awg = value(conf, "Jc") != null || value(conf, "H1") != null || value(conf, "S1") != null;
        if (!awg) return "wireguard";
        String h1 = value(conf, "H1");
        boolean v2 = value(conf, "S3") != null || value(conf, "S4") != null || h1 != null && h1.contains("-");
        return v2 ? "amneziawg 2.0" : "amneziawg";
    }

    // ------------------------------------------------------------------ ссылки vpn:// (Amnezia)

    /** vpn:// + Base64url(qCompress(JSON)): 4 байта длины, затем zlib. */
    static String decodeVpnLink(String link) {
        String b64 = link.substring("vpn://".length()).replace('+', '-').replace('/', '_');
        byte[] data = Base64.decode(b64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        for (int skip : new int[]{4, 0}) {
            try {
                Inflater inf = new Inflater();
                inf.setInput(data, skip, data.length - skip);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    out.write(buf, 0, n);
                    if (out.size() > 1 << 20) break;
                }
                inf.end();
                String s = out.toString("UTF-8").trim();
                if (s.startsWith("{")) return s;
            } catch (Throwable ignore) {
            }
        }
        String plain = new String(data, StandardCharsets.UTF_8).trim();
        return plain.startsWith("{") ? plain : null;
    }

    /**
     * JSON ссылки Amnezia: containers[].awg.last_config — строка JSON с полем config (текст .conf).
     * В config бывают подстановки $PRIMARY_DNS/$SECONDARY_DNS — берём dns1/dns2 из ссылки.
     */
    private static void fromAmneziaJson(JSONObject o, ArrayList<VlessConfig> out) {
        String name = o.optString("description", o.optString("name", ""));
        String dns1 = o.optString("dns1", "1.1.1.1"), dns2 = o.optString("dns2", "1.0.0.1");
        ArrayList<String> confs = new ArrayList<>();
        collectConfs(o, confs, 0);
        for (String conf : confs) {
            conf = conf.replace("$PRIMARY_DNS", dns1).replace("$SECONDARY_DNS", dns2);
            VlessConfig c = make(conf, TextUtils.isEmpty(name) ? null : name);
            if (c != null) out.add(c);
        }
    }

    private static void collectConfs(Object v, ArrayList<String> out, int depth) {
        if (depth > 8 || v == null) return;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.startsWith("{")) {
                try {
                    collectConfs(new JSONObject(s), out, depth + 1);
                } catch (Throwable ignore) {
                }
            } else if (isConf(s)) {
                out.add(s);
            }
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) collectConfs(a.opt(i), out, depth + 1);
        } else if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) collectConfs(o.opt(keys.next()), out, depth + 1);
        }
    }
}

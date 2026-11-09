package org.recgram.rules;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.recgram.RLocale;
import org.recgram.RecGramConfig;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Правила записи по странам из assets/recgram/recording_rules.json. */
public final class RecordingRules {

    public enum Color { GREEN, YELLOW, RED }

    public static final class Country {
        public String id, iso, regionRu, regionEn, self, threat, law, noteRu;
        public Boolean sharePenalized;

        public Color color() {
            return colorOf(self);
        }

        public String displayName() {
            String name = new Locale("", iso).getDisplayCountry(RLocale.uiLocale());
            if ("CY-N".equals(id)) {
                return RLocale.isRussian() ? regionRu : regionEn;
            }
            String region = RLocale.isRussian() ? regionRu : regionEn;
            return TextUtils.isEmpty(region) ? name : name + " — " + region;
        }
    }

    private static List<Country> countries;

    private RecordingRules() {
    }

    public static Color colorOf(String self) {
        if (self == null) {
            return Color.YELLOW;
        }
        switch (self) {
            case "allowed":
            case "allowed_court_limited":
            case "intent_misuse":
                return Color.GREEN;
            case "criminal_prohibited":
            case "absolute":
                return Color.RED;
            default:
                return Color.YELLOW;
        }
    }

    public static int colorValue(Color c) {
        switch (c) {
            case GREEN: return 0xFF34C759;
            case RED: return 0xFFFF3B30;
            default: return 0xFFFFB300;
        }
    }

    public static synchronized List<Country> getCountries() {
        if (countries == null) {
            countries = load();
        }
        return countries;
    }

    private static List<Country> load() {
        ArrayList<Country> list = new ArrayList<>();
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("recgram/recording_rules.json")) {
            byte[] data = new byte[in.available()];
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
            JSONArray arr = new JSONObject(new String(data, 0, off, StandardCharsets.UTF_8)).getJSONArray("countries");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Country c = new Country();
                c.id = o.getString("id");
                c.iso = o.getString("iso");
                c.regionRu = o.optString("region_ru", null);
                c.regionEn = o.optString("region_en", null);
                c.self = o.getString("self");
                c.threat = o.getString("threat");
                c.law = o.optString("law", "");
                c.noteRu = o.optString("note_ru", "");
                c.sharePenalized = o.isNull("share_penalized") ? null : o.optBoolean("share_penalized");
                list.add(c);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        final Collator collator = Collator.getInstance(RLocale.uiLocale());
        Collections.sort(list, (a, b) -> collator.compare(a.displayName(), b.displayName()));
        return list;
    }

    public static Country find(String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        for (Country c : getCountries()) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** Страна, которой нет в списке: правила неизвестны (жёлтый). */
    public static Country unknown(String iso) {
        Country c = new Country();
        c.id = iso;
        c.iso = iso;
        c.self = "unknown";
        c.threat = "unknown";
        c.law = "";
        c.noteRu = "";
        return c;
    }

    /** Выбранная пользователем страна или null. */
    public static Country selected() {
        String id = RecGramConfig.getCountryId();
        Country c = find(id);
        if (c == null && !TextUtils.isEmpty(id) && id.length() == 2) {
            c = unknown(id);
        }
        return c;
    }

    /** ISO-код страны по сети или SIM, например "RU". Пустая строка, если не удалось. */
    public static String detectIso() {
        try {
            TelephonyManager tm = (TelephonyManager) ApplicationLoader.applicationContext.getSystemService(Context.TELEPHONY_SERVICE);
            String iso = tm != null ? tm.getNetworkCountryIso() : null;
            if (TextUtils.isEmpty(iso) && tm != null) {
                iso = tm.getSimCountryIso();
            }
            return iso == null ? "" : iso.toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** Все страны мира по ISO-кодам Java, чтобы можно было выбрать и не исследованную страну. */
    public static List<Country> allWithUnknown() {
        ArrayList<Country> result = new ArrayList<>(getCountries());
        ArrayList<String> known = new ArrayList<>();
        for (Country c : result) {
            known.add(c.iso);
        }
        for (String iso : Locale.getISOCountries()) {
            if (!known.contains(iso)) {
                result.add(unknown(iso));
            }
        }
        final Collator collator = Collator.getInstance(RLocale.uiLocale());
        Collections.sort(result, (a, b) -> collator.compare(a.displayName(), b.displayName()));
        return result;
    }

    /** Можно ли сейчас записывать (без учёта страны собеседника). */
    public static boolean isRecordingAllowed() {
        Country c = selected();
        if (c == null) {
            return false;
        }
        switch (c.color()) {
            case GREEN: return true;
            case YELLOW: return c.id.equals(RecGramConfig.getAcceptedRiskCountryId());
            default:
                // Красная страна: запись возможна только с голосовым предупреждением,
                // кроме стран, где нужно явное согласие (absolute)
                return "criminal_prohibited".equals(c.self) && RecGramConfig.isVoiceNotice();
        }
    }

    /** Нужно ли голосовое предупреждение перед записью. */
    public static boolean voiceNoticeRequired() {
        Country c = selected();
        return RecGramConfig.isVoiceNotice() || (c != null && c.color() == Color.RED);
    }
}

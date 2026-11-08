package org.recgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** Настройки RecGram. Хранятся отдельно от настроек Telegram. */
public final class RecGramConfig {

    public static final int RECORD_OFF = 0;
    public static final int RECORD_ASK = 1;
    public static final int RECORD_AUTO = 2;

    private RecGramConfig() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("recgram_config", Context.MODE_PRIVATE);
    }

    private static boolean b(String key, boolean def) {
        return prefs().getBoolean(key, def);
    }

    private static void put(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    // Запись звонков
    public static int getRecordingMode() { return prefs().getInt("recording_mode", RECORD_OFF); }
    public static void setRecordingMode(int mode) { prefs().edit().putInt("recording_mode", mode).apply(); }
    public static boolean isRecordAudio() { return b("record_audio", true); }
    public static void setRecordAudio(boolean v) { put("record_audio", v); }
    public static boolean isRecordVideo() { return b("record_video", false); }
    public static void setRecordVideo(boolean v) { put("record_video", v); }
    public static boolean isNotifyPeer() { return b("notify_peer", true); }
    public static void setNotifyPeer(boolean v) { put("notify_peer", v); }

    /** id из recording_rules.json, пустая строка — страна не выбрана. */
    public static String getCountryId() { return prefs().getString("country_id", ""); }
    public static void setCountryId(String id) { prefs().edit().putString("country_id", id).apply(); }
    /** Пользователь подтвердил предупреждение для жёлтой страны (хранится id страны). */
    public static String getAcceptedRiskCountryId() { return prefs().getString("accepted_risk_country", ""); }
    public static void setAcceptedRiskCountryId(String id) { prefs().edit().putString("accepted_risk_country", id).apply(); }

    // Защищённый контент
    public static boolean isAllowScreenshots() { return b("allow_screenshots", false); }
    public static void setAllowScreenshots(boolean v) { put("allow_screenshots", v); }
    public static boolean isSecretScreenshotNotify() { return b("secret_screenshot_notify", true); }
    public static void setSecretScreenshotNotify(boolean v) { put("secret_screenshot_notify", v); }
    /** Копирование текста и сохранение медиа из защищённых чатов. */
    public static boolean isAllowCopySave() { return b("allow_copy_save", false); }
    public static void setAllowCopySave(boolean v) { put("allow_copy_save", v); }
    public static boolean isAllowResendProtected() { return b("allow_resend_protected", false); }
    public static void setAllowResendProtected(boolean v) { put("allow_resend_protected", v); }
    public static boolean isAllowStoriesDownload() { return b("allow_stories_download", false); }
    public static void setAllowStoriesDownload(boolean v) { put("allow_stories_download", v); }
    /** Работает только со своим API-ключом: п. 1.4 правил Telegram API. */
    public static boolean isAllowSelfDestructSave() { return b("allow_self_destruct_save", false) && ApiKeys.isCustom(); }
    public static void setAllowSelfDestructSave(boolean v) { put("allow_self_destruct_save", v); }
}

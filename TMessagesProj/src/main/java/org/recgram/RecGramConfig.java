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
    public static boolean isNotifyPeer() { return b("notify_peer", false); }
    public static void setNotifyPeer(boolean v) { put("notify_peer", v); }
    /** Кодек видеозаписи: H.265 (по умолчанию) или H.264. */
    public static boolean isVideoHevc() { return b("video_hevc", true); }
    public static void setVideoHevc(boolean v) { put("video_hevc", v); }
    public static final int[] VIDEO_BITRATES_HEVC = {2_000_000, 2_500_000, 3_000_000};
    public static final int[] VIDEO_BITRATES_AVC = {2_500_000, 3_000_000, 3_500_000};
    public static int getVideoBitrate(boolean hevc) {
        int[] opts = hevc ? VIDEO_BITRATES_HEVC : VIDEO_BITRATES_AVC;
        int v = prefs().getInt(hevc ? "video_bitrate_hevc" : "video_bitrate_avc", opts[0]);
        for (int o : opts) if (o == v) return v;
        return opts[0];
    }
    public static void setVideoBitrate(boolean hevc, int bps) {
        prefs().edit().putInt(hevc ? "video_bitrate_hevc" : "video_bitrate_avc", bps).apply();
    }
    /** Битрейт аудиозаписи: 32 / 48 / 64 кбит/с (по умолчанию 48). */
    public static final int[] AUDIO_BITRATES = {32_000, 48_000, 64_000};
    public static int getAudioBitrate() {
        int v = prefs().getInt("audio_bitrate", 48_000);
        for (int o : AUDIO_BITRATES) if (o == v) return v;
        return 48_000;
    }
    public static void setAudioBitrate(int bps) { prefs().edit().putInt("audio_bitrate", bps).apply(); }
    /** Голосовое предупреждение собеседнику в начале звонка (в красных странах — обязательно). */
    public static boolean isVoiceNotice() { return b("voice_notice", false); }
    public static void setVoiceNotice(boolean v) { put("voice_notice", v); }
    /** Предупреждения уже выставлены по цвету выбранной страны (один раз, при выборе страны). */
    public static boolean isNoticeDefaultsApplied() { return b("notice_defaults_v2", false); }
    public static void setNoticeDefaultsApplied() { put("notice_defaults_v2", true); }
    /** Выравнивать громкость голосов при записи. */
    public static boolean isAutoLevel() { return b("auto_level", true); }
    public static void setAutoLevel(boolean v) { put("auto_level", v); }

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

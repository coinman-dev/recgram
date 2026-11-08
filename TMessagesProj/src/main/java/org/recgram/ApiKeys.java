package org.recgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;

/** Встроенный API-ключ или свой ключ пользователя (этап 7, пока только хранение). */
public final class ApiKeys {

    private ApiKeys() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("recgram_api", Context.MODE_PRIVATE);
    }

    public static boolean hasBuiltIn() {
        return BuildConfig.RECGRAM_API_ID != 0 && !TextUtils.isEmpty(BuildConfig.RECGRAM_API_HASH);
    }

    /** Используется ли сейчас свой ключ пользователя. */
    public static boolean isCustom() {
        return prefs().getInt("custom_api_id", 0) != 0 && !TextUtils.isEmpty(prefs().getString("custom_api_hash", ""));
    }

    public static int getCustomId() {
        return prefs().getInt("custom_api_id", 0);
    }

    public static boolean isValid(String id, String hash) {
        return id != null && id.matches("[0-9]{1,10}") && hash != null && hash.matches("[0-9a-fA-F]{32}");
    }
}

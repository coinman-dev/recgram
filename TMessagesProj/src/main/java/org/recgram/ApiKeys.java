package org.recgram;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;

/**
 * Свой API-ключ пользователя. Встроенного ключа у RecGram нет: каждый вводит свой при первом запуске.
 * Ключ передаётся в сетевой модуль при запуске, поэтому после смены нужен перезапуск.
 */
public final class ApiKeys {

    private ApiKeys() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("recgram_api", Context.MODE_PRIVATE);
    }

    public static boolean hasBuiltIn() {
        return BuildConfig.RECGRAM_API_ID != 0 && !TextUtils.isEmpty(BuildConfig.RECGRAM_API_HASH);
    }

    /** Ключ введён — можно входить в Telegram. */
    public static boolean isConfigured() {
        return isCustom();
    }

    /** Используется ли свой ключ пользователя. */
    public static boolean isCustom() {
        return getCustomId() != 0 && !TextUtils.isEmpty(prefs().getString("custom_api_hash", ""));
    }

    public static int getCustomId() {
        return prefs().getInt("custom_api_id", 0);
    }

    /** Ключ, который сейчас используется (для показа). */
    public static int currentId() {
        return BuildVars.APP_ID;
    }

    public static boolean isValid(String id, String hash) {
        return id != null && id.matches("[0-9]{1,9}") && Integer.parseInt(id) > 0
                && hash != null && hash.matches("[0-9a-fA-F]{32}");
    }

    /** Вызывается из ApplicationLoader.onCreate до запуска сетевого модуля. */
    public static void apply() {
        try {
            if (isCustom()) {
                BuildVars.APP_ID = getCustomId();
                BuildVars.APP_HASH = prefs().getString("custom_api_hash", "");
            } else {
                BuildVars.APP_ID = 0;
                BuildVars.APP_HASH = "";
            }
        } catch (Throwable ignore) {
        }
    }

    public static void setCustom(String id, String hash) {
        prefs().edit().putInt("custom_api_id", Integer.parseInt(id)).putString("custom_api_hash", hash.toLowerCase()).commit();
    }

    public static void clearCustom() {
        prefs().edit().remove("custom_api_id").remove("custom_api_hash").commit();
        // Исчезающие медиа работают только со своим ключом
        RecGramConfig.setAllowSelfDestructSave(false);
    }

    /** Перезапуск приложения, чтобы новый ключ подхватился сетевым модулем. */
    public static void restartApp() {
        Context ctx = ApplicationLoader.applicationContext;
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0x52454347, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 600, pi);
        }
        System.exit(0);
    }
}

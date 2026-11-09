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

    /**
     * Ошибка ключа при входе (auth.sendCode). Telegram её молча игнорирует, поэтому показываем
     * понятное сообщение с возможностью ввести ключ заново. Возвращает true, если ошибка обработана.
     */
    public static boolean handleAuthError(org.telegram.ui.ActionBar.BaseFragment fragment, String error) {
        if (error == null || fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        boolean invalid = error.contains("API_ID_INVALID");
        boolean flood = error.contains("API_ID_PUBLISHED_FLOOD");
        if (!invalid && !flood) {
            return false;
        }
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(RLocale.s("Неверный API-ключ", "Invalid API key"));
        if (invalid) {
            b.setMessage(RLocale.s(
                    "Telegram не принял ключ: api_id " + getCustomId() + ". Проверьте api_id и api_hash на my.telegram.org → API development tools и введите их заново.",
                    "Telegram rejected the key: api_id " + getCustomId() + ". Check api_id and api_hash at my.telegram.org → API development tools and enter them again."));
        } else {
            b.setMessage(RLocale.s(
                    "Telegram заблокировал этот ключ, потому что он опубликован. Создайте свой ключ на my.telegram.org и введите его.",
                    "Telegram blocked this key because it was published. Create your own key at my.telegram.org and enter it."));
        }
        b.setPositiveButton(RLocale.s("Ввести заново", "Enter again"), (d, w) -> {
            clearCustom();
            restartApp();
        });
        b.setNegativeButton(RLocale.s("Отмена", "Cancel"), null);
        fragment.showDialog(b.create());
        return true;
    }

    /** Перезапуск приложения, чтобы новый ключ подхватился сетевым модулем. */
    public static void restartApp() {
        // Будильник с PendingIntent на Android 10+ запускает процесс, но не открывает окно из фона.
        // Поэтому, пока приложение на экране, запускаем служебный экран в отдельном процессе — он и откроет RecGram.
        Context ctx = ApplicationLoader.applicationContext;
        Intent intent = new Intent(ctx, org.recgram.ui.RestartActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(org.recgram.ui.RestartActivity.EXTRA_PID, android.os.Process.myPid());
        ctx.startActivity(intent);
        Runtime.getRuntime().exit(0);
    }
}

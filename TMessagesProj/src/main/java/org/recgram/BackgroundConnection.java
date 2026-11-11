package org.recgram;

import static org.recgram.RLocale.s;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/**
 * Уведомления без Firebase: RecGram держит своё фоновое соединение (NotificationsService).
 * Чтобы Android (особенно Samsung, Xiaomi) его не усыплял, нужно исключение из экономии батареи —
 * один раз объясняем и открываем системный запрос.
 */
public final class BackgroundConnection {

    private BackgroundConnection() {
    }

    private static boolean askedThisSession;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("recgram_config", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return MessagesController.getGlobalNotificationsSettings().getBoolean("pushService", true);
    }

    public static boolean isBatteryExempt() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName());
    }

    /** Из LaunchActivity.onResume: если вошли в аккаунт и исключения нет — один раз спросить. */
    public static void checkBatteryPrompt() {
        try {
            if (askedThisSession || !isEnabled()) return;
            if (isBatteryExempt() && !PhoneSetup.hasVendorSteps()) return;
            if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) return;
            if (prefs().getBoolean("background_setup_prompt_shown", false)) return;
            BaseFragment f = LaunchActivity.getLastFragment();
            if (f == null || f.getParentActivity() == null) return;
            askedThisSession = true;
            prefs().edit().putBoolean("background_setup_prompt_shown", true).apply();
            showPrompt(f);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void showPrompt(BaseFragment f) {
        AlertDialog.Builder b = new AlertDialog.Builder(f.getParentActivity());
        b.setTitle(s("Уведомления о сообщениях", "Message notifications"));
        b.setMessage(s(
                "RecGram получает сообщения по своему фоновому соединению: push-уведомления Telegram приходят только в официальное приложение. Чтобы телефон не отключал это соединение, разрешите RecGram работать без ограничений экономии батареи, а на vivo, Xiaomi, HONOR, OPPO и Samsung — ещё и пройдите шаги в фирменных настройках.\n\nВ шторке будет тихое уведомление «RecGram на связи» — его можно скрыть в настройках уведомлений RecGram (категория «Фоновое соединение»).",
                "RecGram receives messages over its own background connection: Telegram push notifications only reach the official app. So that the phone does not cut this connection, let RecGram run without battery restrictions, and on vivo, Xiaomi, HONOR, OPPO and Samsung also go through the steps in the vendor settings.\n\nA silent \"RecGram is connected\" notification stays in the shade; you can hide it in RecGram notification settings (\"Background connection\" category)."));
        if (PhoneSetup.hasVendorSteps()) {
            // vivo, Xiaomi, HONOR, OPPO, Samsung: кроме батареи нужны фирменные переключатели — по шагам
            b.setPositiveButton(s("Настроить", "Set up"), (d, w) -> f.presentFragment(new org.recgram.ui.BackgroundSetupActivity()));
        } else {
            b.setPositiveButton(s("Разрешить", "Allow"), (d, w) -> requestExemption(f.getParentActivity()));
        }
        b.setNegativeButton(s("Не сейчас", "Not now"), null);
        f.showDialog(b.create());
    }

    public static void requestExemption(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            ctx.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Throwable e) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
    }
}

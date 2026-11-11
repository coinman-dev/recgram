package org.recgram;

import static org.recgram.RLocale.s;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Что нужно сказать телефону, чтобы RecGram получал сообщения в фоне: шаги по порядку.
 *
 * Часть шагов — настройки Android (уведомления, экономия батареи, «приостанавливать неиспользуемые»):
 * их можно прочитать, выполненный шаг отмечается сам. Другая часть — фирменные диспетчеры питания
 * vivo, Xiaomi, HONOR/Huawei, OPPO, Samsung: их состояние не видно ни одному API, можно только открыть
 * нужный экран и назвать переключатель словами производителя, а пользователь отмечает «Сделано».
 *
 * Таблица перенесена из own-locator (core/common/PhoneSetup.kt, OemPowerSettings.kt): экраны и тексты
 * там проверены на телефонах — vivo на OriginOS (30.09–01.10.2026), Samsung Galaxy S21 (One UI 7),
 * HONOR (MagicOS). Без фирменных шагов фоновое соединение на этих телефонах выгружается, как только
 * гаснет экран или ночью, хотя «оптимизация батареи» уже выключена.
 */
public final class PhoneSetup {

    private PhoneSetup() {
    }

    public enum Check { NOTIFICATIONS, BATTERY, UNUSED_APP_PAUSE, MANUAL }

    /** Способ открыть экран настроек. */
    static final class Screen {
        String action;
        String pkg, cls;
        boolean packageUri;
        String[] packageExtras = {};
        String intKey;
        /** Ключ пункта, к которому «Настройки» прокрутят страницу и подсветят его. */
        String highlightKey;
        int intValue;
        String category;

        static Screen action(String a) {
            Screen s = new Screen();
            s.action = a;
            return s;
        }

        static Screen component(String pkg, String cls) {
            Screen s = new Screen();
            s.pkg = pkg;
            s.cls = cls;
            return s;
        }

        Screen withPackageUri() {
            packageUri = true;
            return this;
        }

        Screen packageExtras(String... keys) {
            packageExtras = keys;
            return this;
        }

        Screen intExtra(String key, int value) {
            intKey = key;
            intValue = value;
            return this;
        }

        Screen category(String c) {
            category = c;
            return this;
        }
    }

    public static final class Step {
        public final String key;
        public final String title;
        public final String text;
        public final Check check;
        /** Если этот шаг выполнен — данный не нужен (Samsung: «без ограничений» по батарее = не уснёт). */
        final Check unneededWhen;
        final List<Screen> screens = new ArrayList<>();

        Step(String key, String title, String text, Check check, Check unneededWhen, Screen... screens) {
            this.key = key;
            this.title = title;
            this.text = text;
            this.check = check;
            this.unneededWhen = unneededWhen;
            for (Screen sc : screens) this.screens.add(sc);
        }
    }

    private static final Screen APP_DETAILS = Screen.action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).withPackageUri();
    private static final Screen BATTERY_SETTINGS = Screen.action(Intent.ACTION_POWER_USAGE_SUMMARY);
    private static final Screen HOME_SCREEN = Screen.action(Intent.ACTION_MAIN).category(Intent.CATEGORY_HOME);

    public static String manufacturer() {
        return Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
    }

    /** Есть ли у марки свой диспетчер питания, который надо настраивать вручную. */
    public static boolean hasVendorSteps() {
        for (Step st : steps()) if (st.check == Check.MANUAL) return true;
        return false;
    }

    public static List<Step> steps() {
        ArrayList<Step> list = new ArrayList<>();
        list.add(new Step("notifications", s("Уведомления", "Notifications"),
                s("Включите уведомления RecGram — через них приходят сообщения. Категорию «Фоновое соединение» можно выключить: это только значок «RecGram на связи», соединение работает и без него.",
                        "Turn on notifications for RecGram — messages arrive through them. The \"Background connection\" category may be turned off: it is only the \"RecGram is connected\" icon, the connection works without it."),
                Check.NOTIFICATIONS, null,
                Screen.action(Settings.ACTION_APP_NOTIFICATION_SETTINGS).packageExtras(Settings.EXTRA_APP_PACKAGE)));
        list.add(new Step("battery", s("Оптимизация батареи", "Battery optimisation"),
                s("Разрешите RecGram работать без оптимизации батареи. Иначе Android усыпляет приложение, как только гаснет экран, и сообщения перестают приходить.\n\nЕсли уже разрешено, кнопка откроет страницу RecGram, прокрученную к пункту «Батарея»: нажмите его и проверьте, что выбрано «Без ограничений» (на Samsung — «Не ограничено»).",
                        "Let RecGram run without battery optimisation. Otherwise Android puts the app to sleep as soon as the screen goes off, and messages stop arriving.\n\nIf already allowed, the button opens the RecGram page scrolled to \"Battery\": tap it and check that \"Unrestricted\" is selected."),
                Check.BATTERY, null,
                Screen.action(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).withPackageUri(),
                Screen.action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        if (Build.VERSION.SDK_INT >= 30) {
            list.add(new Step("unused", s("Не приостанавливать приложение", "Do not pause the app"),
                    s("Выключите «Приостанавливать работу приложения, если оно не используется» (на Samsung — «Удалять разрешения, если не используется»). Иначе через несколько недель без открытия Android остановит RecGram и уведомления.",
                            "Turn off \"Pause app activity if unused\" (Samsung: \"Remove permissions if app is unused\"). Otherwise, after a few weeks without being opened, Android stops RecGram and its notifications."),
                    Check.UNUSED_APP_PAUSE, null,
                    Screen.action(Intent.ACTION_AUTO_REVOKE_PERMISSIONS).withPackageUri()));
        }
        list.addAll(vendorSteps(manufacturer()));
        return list;
    }

    private static List<Step> vendorSteps(String m) {
        ArrayList<Step> list = new ArrayList<>();
        switch (m) {
            case "vivo":
            case "iqoo":
                list.add(new Step("vivo_permissions", s("Автозапуск", "Autostart"),
                        s("На экране RecGram включите «Автозапуск» (если есть — также «Отображать на заблок. экране» и «Всплывающие окна в фоновом режиме»). Без автозапуска vivo не держит приложение в фоне.\n\nЕсли открылся список приложений — найдите в нём RecGram.",
                                "On the RecGram screen turn on \"Autostart\" (and, if present, \"Display on lock screen\" and \"Background pop-ups\"). Without autostart vivo does not keep the app in the background.\n\nIf a list of apps opens, find RecGram in it."),
                        Check.MANUAL, null,
                        Screen.component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity").packageExtras("packagename"),
                        Screen.component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                        Screen.component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")));
                list.add(new Step("vivo_power", s("Энергопотребление в фоне", "Background power use"),
                        s("Откроется список «Фоновое управление питанием». Найдите RecGram и выберите «Разрешить энергопотребление в фоновом режиме» вместо «Интеллектуального управления» — оно выгружает приложение, когда экран выключен.",
                                "The \"Background power consumption management\" list opens. Find RecGram and choose \"Allow background power consumption\" instead of smart management, which unloads the app when the screen is off."),
                        Check.MANUAL, null,
                        Screen.component("com.iqoo.powersaving", "com.iqoo.powersaving.BackgroundHighUsageActivity"),
                        BATTERY_SETTINGS));
                list.add(new Step("vivo_sleep", s("Оптимизация во время сна", "Sleep standby optimisation"),
                        s("Главная настройка для vivo: именно она выгружает приложение ночью.\n\nОткроется «Управление энергосбережением». Прокрутите в самый низ, до «Стратегии оптимизации системы», и выключите «Оптимизация режима ожидания во время сна».\n\nЧуть выше на том же экране выключите «Отключаться от Интернета через 5 мин после выключения экрана».",
                                "The key setting on vivo: this is what unloads the app at night.\n\n\"Power saving management\" opens. Scroll to the very bottom, to \"System optimisation strategies\", and turn off \"Sleep standby optimisation\".\n\nA little higher on the same screen, turn off disconnecting from the internet 5 minutes after the screen goes off."),
                        Check.MANUAL, null,
                        Screen.component("com.iqoo.powersaving", "com.iqoo.powersaving.UpSlidePowerManagerActivity"),
                        Screen.component("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
                        BATTERY_SETTINGS));
                list.add(new Step("vivo_lock_recents", s("Закрепите RecGram в недавних", "Lock RecGram in recents"),
                        s("На vivo смахнуть приложение из списка недавних — значит полностью его закрыть вместе с фоновым соединением. Никакие настройки выше от этого не защищают.\n\nКнопка откроет рабочий стол. Смахните вверх от нижнего края и задержите палец: появятся недавние приложения. Потяните карточку RecGram вниз и нажмите на замок. Закреплённое приложение не закрывается ни смахиванием, ни «очистить всё».",
                                "On vivo, swiping the app off the recents screen closes it completely, background connection included — none of the settings above prevent that.\n\nThe button takes you to the home screen. Swipe up from the bottom edge and hold: recent apps appear. Pull the RecGram card down and tap the lock. A locked app is not closed by a swipe or by \"clear all\"."),
                        Check.MANUAL, null, HOME_SCREEN));
                break;
            case "samsung":
                list.add(new Step("samsung_never_sleep", s("Не уходить в сон", "Never sleep"),
                        s("Откроется список «Не уходят в сон автоматически». Нажмите «+», отметьте RecGram и подтвердите. Иначе Samsung может перевести приложение в спящий режим — тогда сообщения не приходят.\n\nЕсли RecGram нет среди приложений для выбора — у него уже «Без ограничений» по батарее, шаг можно пропустить.",
                                "The \"Never auto sleeping apps\" list opens. Tap \"+\", tick RecGram and confirm. Otherwise Samsung may put the app to sleep, and then messages do not arrive.\n\nIf RecGram is not offered, its battery use is already \"Unrestricted\" and this step can be skipped."),
                        Check.MANUAL, Check.BATTERY,
                        Screen.component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity").intExtra("activity_type", 2),
                        Screen.component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")));
                break;
            case "xiaomi":
            case "redmi":
            case "poco":
                list.add(new Step("xiaomi_autostart", s("Автозапуск", "Autostart"),
                        s("Включите «Автозапуск» для RecGram. Без него после перезагрузки или очистки памяти приложение не запустится само.",
                                "Turn on \"Autostart\" for RecGram. Without it the app will not start by itself after a reboot or a memory clean-up."),
                        Check.MANUAL, null,
                        Screen.component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                        Screen.component("com.miui.securitycenter", "com.miui.securitycenter.Main")));
                list.add(new Step("xiaomi_battery", s("Экономия энергии", "Battery saver"),
                        s("Выберите «Нет ограничений». Если открылась страница приложения — Батарея (Экономия энергии) → «Нет ограничений».",
                                "Choose \"No restrictions\". If the app page opens instead: Battery saver → \"No restrictions\"."),
                        Check.MANUAL, null,
                        Screen.component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity").packageExtras("package_name")));
                break;
            case "honor":
            case "huawei":
                list.add(new Step("huawei_launch", s("Запуск приложений", "App launch"),
                        s("Батарея → Запуск приложений → RecGram: выключите «Автоматически» и включите все три — «Автозапуск», «Косвенный запуск», «Работа в фоновом режиме». Там же включите «Оставаться в сети во время сна».",
                                "Battery → Launch → RecGram: turn OFF \"Manage automatically\" and turn ON all three — \"Auto-launch\", \"Secondary launch\", \"Run in background\". While there, enable \"Stay connected when device sleeps\"."),
                        Check.MANUAL, null,
                        Screen.component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                        Screen.component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                        Screen.component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                        Screen.component("com.huawei.systemmanager", "com.huawei.systemmanager.MainActivity")));
                break;
            case "oppo":
            case "realme":
            case "oneplus":
                list.add(new Step("oppo_launch", s("Автозапуск и работа в фоне", "Autostart and background work"),
                        s("Запуск приложений → разрешите RecGram автозапуск и работу в фоне; в «Батарее» выберите «Разрешить фоновую работу».",
                                "Startup manager → allow RecGram to auto-start and run in the background; in Battery, choose \"Allow background activity\"."),
                        Check.MANUAL, null,
                        Screen.component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                        Screen.component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                        Screen.component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")));
                break;
            case "meizu":
                list.add(new Step("meizu_launch", s("Автозапуск и работа в фоне", "Autostart and background work"),
                        s("Разрешите RecGram автозапуск и работу в фоновом режиме в фирменном диспетчере питания.",
                                "Allow RecGram to auto-start and run in the background in the vendor power manager."),
                        Check.MANUAL, null,
                        Screen.component("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")));
                break;
            default:
                break;
        }
        return list;
    }

    // ------------------------------------------------------------------ состояние

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("recgram_phone_setup", Context.MODE_PRIVATE);
    }

    /** true/false — что видно Android; для фирменных шагов — отметка пользователя «Сделано». */
    public static boolean isDone(Context ctx, Step st) {
        if (st.unneededWhen != null && isDone(ctx, st.unneededWhen)) return true;
        if (st.check == Check.MANUAL) return prefs().getBoolean(st.key, false);
        return isDone(ctx, st.check);
    }

    static boolean isDone(Context ctx, Check c) {
        try {
            switch (c) {
                case NOTIFICATIONS:
                    return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
                case BATTERY:
                    if (Build.VERSION.SDK_INT < 23) return true;
                    PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    return pm == null || pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
                case UNUSED_APP_PAUSE:
                    return Build.VERSION.SDK_INT < 30 || ctx.getPackageManager().isAutoRevokeWhitelisted();
                default:
                    return false;
            }
        } catch (Throwable e) {
            return true; // не смогли прочитать — не пугаем пользователя
        }
    }

    public static void setManualDone(Step st, boolean done) {
        prefs().edit().putBoolean(st.key, done).apply();
    }

    /** Сколько шагов ещё не сделано. */
    public static int pendingCount(Context ctx) {
        int n = 0;
        for (Step st : steps()) if (!isDone(ctx, st)) n++;
        return n;
    }

    /**
     * Открыть экран шага: кандидаты по очереди, последний — страница RecGram в настройках.
     * Не через resolveActivity: с Android 11 чужие пакеты без объявления не видны, и он ответил бы
     * «нет такого экрана» даже на vivo/Xiaomi — запуск же видимости не требует.
     */
    public static boolean open(Context ctx, Step st) {
        ArrayList<Screen> all = new ArrayList<>(st.screens);
        if (st.check == Check.BATTERY && isDone(ctx, Check.BATTERY)) {
            // Запрос «прекратить оптимизацию» для уже исключённого приложения закрывается сразу, без окна —
            // выглядит как «не открывается». Открываем страницу RecGram (там «Батарея: Без ограничений»),
            // а не общий список всех приложений. Экран батареи одного приложения
            // (VIEW_ADVANCED_POWER_USAGE_DETAIL) не используем: на Galaxy S21 / One UI 7 он роняет «Настройки».
            // Нужный экран («Не ограничено / Оптимизировано / Ограничено», PowerBackgroundUsageDetail) открывается
            // только изнутри «Настроек»; ближе всего — страница RecGram, прокрученная к пункту «Батарея»
            // (:settings:fragment_args_key — проверено на Galaxy S21, One UI 7).
            all.clear();
            Screen details = Screen.action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).withPackageUri();
            details.highlightKey = "battery";
            all.add(details);
            all.add(Screen.action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
        all.add(APP_DETAILS);
        for (Screen sc : all) {
            try {
                ctx.startActivity(build(ctx, sc));
                return true;
            } catch (Throwable e) {
                FileLog.d("RecGram: экран " + (sc.cls != null ? sc.cls : sc.action) + " не открылся: " + e.getClass().getSimpleName());
            }
        }
        return false;
    }

    private static Intent build(Context ctx, Screen sc) {
        Intent i = new Intent();
        if (sc.action != null) i.setAction(sc.action);
        if (sc.pkg != null) i.setComponent(new ComponentName(sc.pkg, sc.cls));
        if (sc.packageUri) i.setData(Uri.fromParts("package", ctx.getPackageName(), null));
        for (String k : sc.packageExtras) i.putExtra(k, ctx.getPackageName());
        if (sc.intKey != null) i.putExtra(sc.intKey, sc.intValue);
        if (sc.highlightKey != null) i.putExtra(":settings:fragment_args_key", sc.highlightKey); // прокрутить к пункту
        if (sc.category != null) i.addCategory(sc.category);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}

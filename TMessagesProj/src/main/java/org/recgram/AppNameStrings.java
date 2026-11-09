package org.recgram;

import android.util.SparseArray;

import org.telegram.messenger.ApplicationLoader;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Строки, где «Telegram» означает само приложение (разрешения, уведомления, кэш, блокировка):
 * в них показываем «RecGram». Там, где речь о сервисе Telegram (облако, Premium, звонки),
 * слово не меняется. Работает и для языковых пакетов с сервера.
 */
public final class AppNameStrings {

    private AppNameStrings() {
    }

    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "ContactsPermissionAlert", "NotificationsPermissionAlertSubtitle",
            "NotificationHiddenName", "NotificationHiddenChatName",
            "AppUpdate", "AppUpdateBeta", "UpdateTelegram", "UpdateAppAlert", "Gift2ExportTONUpdateRequiredText",
            "ScreenCaptureAlert", "StorageUsageTelegram", "StorageUsageTelegramLess",
            "AllowReadCall", "AllowReadCallAndLog", "AllowReadCallLog", "AllowFillNumber",
            "VoipNeedMicPermissionWithHint", "VoipNeedMicCameraPermissionWithHint", "VoipNeedCameraPermission",
            "AllowBackgroundActivityInfo", "AllowBackgroundActivityInfoOneUIBelowS", "AllowBackgroundActivityInfoOneUIAboveS",
            "SdCardAlert", "SdCardErrorDescription", "AppLocked",
            "LowDiskSpaceMessage", "ClearingCacheDescription", "LocalDatabaseInfo", "ChangePasscodeInfo",
            "LiveLocationPausedText", "BrowserExternalRestricted"
    ));

    private static final SparseArray<Boolean> byRes = new SparseArray<>();

    private static boolean matches(String key) {
        return key != null && (KEYS.contains(key) || key.startsWith("Permission"));
    }

    /** Вызывается из LocaleController для каждой строки; быстрый выход, если «Telegram» в ней нет. */
    public static String fix(String key, int res, String value) {
        if (value == null || !value.contains("Telegram")) {
            return value;
        }
        boolean replace;
        if (key != null) {
            replace = matches(key);
        } else if (res != 0) {
            Boolean cached;
            synchronized (byRes) {
                cached = byRes.get(res);
            }
            if (cached == null) {
                String name = null;
                try {
                    name = ApplicationLoader.applicationContext.getResources().getResourceEntryName(res);
                } catch (Throwable ignore) {
                }
                cached = matches(name);
                synchronized (byRes) {
                    byRes.put(res, cached);
                }
            }
            replace = cached;
        } else {
            replace = false;
        }
        return replace ? value.replace("Telegram", "RecGram") : value;
    }
}

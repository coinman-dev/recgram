package org.recgram;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;

/**
 * Версия RecGram для показа: «1.0.0 (13.2.1)» — своя версия и версия Telegram, на которой основан RecGram.
 * Серверам Telegram по-прежнему уходит только версия Telegram (versionName APK, ConnectionsManager.appVersion):
 * незнакомая версия клиента им ни к чему.
 */
public final class RecGramVersion {

    private RecGramVersion() {
    }

    /** Своя версия, например «1.0.0» (gradle.properties: RECGRAM_VERSION). */
    public static String own() {
        return BuildConfig.RECGRAM_VERSION;
    }

    /** «1.0.0 (13.2.1)». */
    public static String display() {
        return own() + " (" + BuildVars.BUILD_VERSION_STRING + ")";
    }
}

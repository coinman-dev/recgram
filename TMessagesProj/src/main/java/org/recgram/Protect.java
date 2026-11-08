package org.recgram;

/** Проверки снятия ограничений защищённого контента. Вызываются из кода Telegram. */
public final class Protect {

    private Protect() {
    }

    /** Не ставить FLAG_SECURE для защищённого контента. */
    public static boolean screenshots() {
        return RecGramConfig.isAllowScreenshots();
    }

    /** Отправлять ли собеседнику уведомление о скриншоте. */
    public static boolean screenshotNotify() {
        return !RecGramConfig.isAllowScreenshots() || RecGramConfig.isSecretScreenshotNotify();
    }
}

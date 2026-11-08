package org.recgram;

/** Проверки снятия ограничений защищённого контента. Вызываются из кода Telegram. */
public final class Protect {

    private Protect() {
    }

    /** Не ставить FLAG_SECURE для защищённого контента. */
    public static boolean screenshots() {
        return RecGramConfig.isAllowScreenshots();
    }

    /** Копирование и сохранение из защищённых чатов разрешено. */
    public static boolean copySave() {
        return RecGramConfig.isAllowCopySave();
    }

    /** Флаг запрета пересылки у сообщения с учётом настройки RecGram. */
    public static boolean nf(org.telegram.tgnet.TLRPC.Message m) {
        return m != null && m.noforwards && !copySave();
    }

    /** Отправлять ли собеседнику уведомление о скриншоте. */
    public static boolean screenshotNotify() {
        return !RecGramConfig.isAllowScreenshots() || RecGramConfig.isSecretScreenshotNotify();
    }
}

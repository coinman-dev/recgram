package org.recgram;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

/** Строки RecGram: русский для русского интерфейса, иначе английский. */
public final class RLocale {

    private RLocale() {
    }

    public static boolean isRussian() {
        LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
        String code = info != null && info.shortName != null ? info.shortName : Locale.getDefault().getLanguage();
        return code.toLowerCase(Locale.ROOT).startsWith("ru");
    }

    public static String s(String ru, String en) {
        return isRussian() ? ru : en;
    }

    public static Locale uiLocale() {
        return isRussian() ? new Locale("ru") : Locale.ENGLISH;
    }
}

package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

import org.recgram.RLocale;
import org.recgram.rules.RecordingRules;

/** Тексты для правил записи: цветная точка, короткий статус, пояснение. */
final class RuleTexts {

    private RuleTexts() {
    }

    static CharSequence dotted(RecordingRules.Country c) {
        SpannableStringBuilder sb = new SpannableStringBuilder("●  ");
        sb.setSpan(new ForegroundColorSpan(RecordingRules.colorValue(c.color())), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(c.displayName());
        return sb;
    }

    static String status(RecordingRules.Color color) {
        switch (color) {
            case GREEN: return s("можно", "allowed");
            case RED: return s("нельзя", "not allowed");
            default: return s("риск", "risky");
        }
    }

    static String explain(RecordingRules.Country c) {
        String text;
        switch (c.self) {
            case "allowed":
                text = s("Запись своего разговора без согласия собеседника законна.",
                        "Recording your own conversation without the other party's consent is legal.");
                break;
            case "allowed_court_limited":
                text = s("Запись законна, но суд может не принять её как доказательство.",
                        "Recording is legal, but a court may not accept it as evidence.");
                break;
            case "intent_misuse":
                text = s("Запись для себя законна. Наказуемы запись с целью злоупотребить ею и передача записи посторонним.",
                        "Recording for yourself is legal. Recording with intent to misuse it, or passing it to others, is punishable.");
                break;
            case "notice_required":
                text = s("По закону собеседника нужно предупредить о записи в начале разговора.",
                        "The law requires telling the other party about the recording at the start of the call.");
                break;
            case "civil_prohibited":
                text = s("Запись без согласия — не преступление, но нарушение прав собеседника: он может подать иск.",
                        "Recording without consent is not a crime, but it violates the other party's rights: they can sue you.");
                break;
            case "criminal_prohibited":
                text = s("Запись без согласия собеседника — уголовное преступление. Запись звонков в RecGram отключена.",
                        "Recording without the other party's consent is a criminal offence. Call recording in RecGram is disabled.");
                break;
            case "absolute":
                text = s("Запись без согласия запрещена даже при угрозах от знакомого человека. Запись звонков в RecGram отключена.",
                        "Recording without consent is prohibited even when a known person threatens you. Call recording in RecGram is disabled.");
                break;
            default:
                text = s("Правила для этой страны неизвестны или не проверены. Решение за вами.",
                        "The rules for this country are unknown or unverified. The decision is yours.");
        }
        if (Boolean.TRUE.equals(c.sharePenalized)) {
            text += "\n" + s("Передача записи посторонним (кроме суда, полиции и адвоката) наказуема.",
                    "Passing the recording to others (except a court, police or a lawyer) is punishable.");
        }
        if (!TextUtils.isEmpty(c.law)) {
            text += "\n\n" + s("Закон: ", "Law: ") + c.law;
        }
        if (RLocale.isRussian() && !TextUtils.isEmpty(c.noteRu)) {
            text += "\n\n" + c.noteRu;
        }
        text += "\n\n" + s("Это справка, а не юридическая консультация.", "This is general information, not legal advice.");
        return text;
    }
}

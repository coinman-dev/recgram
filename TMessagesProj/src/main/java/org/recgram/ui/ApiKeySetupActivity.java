package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.recgram.ApiKeys;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Первый запуск: у RecGram нет встроенного ключа, каждый пользователь вводит свой api_id и api_hash.
 * Здесь же — фоновое соединение для уведомлений (push Telegram в сторонних клиентах не работает).
 */
public class ApiKeySetupActivity extends BaseFragment {

    private EditTextBoldCursor idField;
    private EditTextBoldCursor hashField;
    private TextCheckCell bgCell;

    @Override
    public View createView(Context context) {
        actionBar.setTitle(s("RecGram — первый запуск", "RecGram — first start"));
        actionBar.setBackButtonImage(0);

        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box);

        box.addView(text(context, s("Нужен ваш API-ключ Telegram", "Your Telegram API key is needed"), 20, true, Theme.key_windowBackgroundWhiteBlackText));
        box.addView(text(context, s(
                "RecGram — неофициальный клиент. У него нет общего ключа: каждый пользователь работает со своим. "
                        + "Так блокировка чужого ключа вас не коснётся, а ваш номер нигде не публикуется.",
                "RecGram is an unofficial client. It has no shared key: every user works with their own. "
                        + "A blocked key of someone else does not affect you, and your number is never published."),
                15, false, Theme.key_windowBackgroundWhiteGrayText2), LayoutHelper.createLinear(-1, -2, 0, 8, 0, 16));

        box.addView(text(context, s("Как получить (2 минуты)", "How to get it (2 minutes)"), 16, true, Theme.key_windowBackgroundWhiteBlueHeader));
        box.addView(text(context, s(
                "1. Откройте my.telegram.org и войдите по номеру телефона. Код придёт в Telegram, не по SMS.\n"
                        + "2. Нажмите «API development tools».\n"
                        + "3. Заполните форму: App title — любое (например, RecGram), Short name — латиницей, 5–32 символа, Platform — Android. Остальное можно не заполнять.\n"
                        + "4. Нажмите «Create application» и скопируйте сюда «App api_id» (число) и «App api_hash» (32 символа).\n\n"
                        + "Если форма отвечает «ERROR» — попробуйте без VPN, в другом браузере или через несколько часов. На одном аккаунте можно создать только одно приложение; ключ не нужно никому показывать.",
                "1. Open my.telegram.org and sign in with your phone number. The code arrives in Telegram, not by SMS.\n"
                        + "2. Tap \"API development tools\".\n"
                        + "3. Fill in the form: App title — anything (e.g. RecGram), Short name — Latin letters, 5–32 characters, Platform — Android. The rest is optional.\n"
                        + "4. Tap \"Create application\" and copy \"App api_id\" (a number) and \"App api_hash\" (32 characters) here.\n\n"
                        + "If the form says \"ERROR\", try without a VPN, in another browser or a few hours later. One account can create only one app; never share your key."),
                15, false, Theme.key_windowBackgroundWhiteBlackText), LayoutHelper.createLinear(-1, -2, 0, 6, 0, 12));

        TextView open = button(context, s("Открыть my.telegram.org", "Open my.telegram.org"), false);
        open.setOnClickListener(v -> Browser.openUrl(getParentActivity(), "https://my.telegram.org"));
        box.addView(open, LayoutHelper.createLinear(-1, 48, 0, 0, 0, 16));

        idField = field(context, "api_id", true);
        hashField = field(context, "api_hash", false);
        box.addView(idField, LayoutHelper.createLinear(-1, 48, 0, 0, 0, 8));
        box.addView(hashField, LayoutHelper.createLinear(-1, 48, 0, 0, 0, 16));

        bgCell = new TextCheckCell(context);
        bgCell.setTextAndValueAndCheck(s("Фоновое соединение для уведомлений", "Background connection for notifications"),
                s("Сервер Telegram не может отправлять push сторонним клиентам. Чтобы сообщения и звонки приходили, RecGram держит соединение в фоне (немного больше расход батареи).",
                        "Telegram servers cannot send push to third-party clients. To receive messages and calls, RecGram keeps a background connection (slightly higher battery use)."),
                true, true, false);
        bgCell.setOnClickListener(v -> bgCell.setChecked(!bgCell.isChecked()));
        box.addView(bgCell, LayoutHelper.createLinear(-1, -2, -20, 0, -20, 16));

        TextView go = button(context, s("Продолжить", "Continue"), true);
        go.setOnClickListener(v -> save());
        box.addView(go, LayoutHelper.createLinear(-1, 50));

        fragmentView = scroll;
        return fragmentView;
    }

    private void save() {
        String id = idField.getText().toString().trim();
        String hash = hashField.getText().toString().trim();
        if (!ApiKeys.isValid(id, hash)) {
            AndroidUtilities.shakeViewSpring(ApiKeys.isValid(id, "0123456789abcdef0123456789abcdef") ? hashField : idField);
            Toast.makeText(getParentActivity(), s("api_id — число, api_hash — 32 символа 0-9 и a-f.",
                    "api_id is a number, api_hash is 32 characters 0-9 and a-f."), Toast.LENGTH_LONG).show();
            return;
        }
        ApiKeys.setCustom(id, hash);
        boolean bg = bgCell.isChecked();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            SharedPreferences p = MessagesController.getNotificationsSettings(a);
            p.edit().putBoolean("pushService", bg).putBoolean("pushConnection", bg).commit();
        }
        AndroidUtilities.hideKeyboard(hashField);
        Toast.makeText(getParentActivity(), s("Ключ сохранён, перезапуск…", "Key saved, restarting…"), Toast.LENGTH_SHORT).show();
        Utilities.globalQueue.postRunnable(ApiKeys::restartApp, 800);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        return true; // без ключа дальше нельзя — «Назад» закрывает приложение
    }

    private TextView text(Context ctx, CharSequence t, int sp, boolean bold, int colorKey) {
        TextView v = new TextView(ctx);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        v.setTextColor(Theme.getColor(colorKey));
        v.setLineSpacing(AndroidUtilities.dp(2), 1f);
        if (bold) v.setTypeface(AndroidUtilities.bold());
        return v;
    }

    private TextView button(Context ctx, CharSequence t, boolean primary) {
        TextView b = new TextView(ctx);
        b.setText(t);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(10));
        if (primary) {
            bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        } else {
            bg.setColor(0);
            bg.setStroke(AndroidUtilities.dp(1), Theme.getColor(Theme.key_featuredStickers_addButton));
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        }
        b.setBackground(bg);
        return b;
    }

    private EditTextBoldCursor field(Context ctx, String hint, boolean digits) {
        EditTextBoldCursor e = new EditTextBoldCursor(ctx);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        e.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(digits ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setBackground(null);
        e.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        e.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        return e;
    }
}

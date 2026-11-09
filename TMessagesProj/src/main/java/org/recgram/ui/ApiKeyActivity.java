package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.recgram.ApiKeys;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Настройки → RecGram → API-ключ: встроенный или свой. */
public class ApiKeyActivity extends UniversalFragment {

    private static final int ID_SET = 1;
    private static final int ID_RESET = 2;
    private static final int ID_HELP = 3;

    @Override
    protected CharSequence getTitle() {
        return s("API-ключ", "API key");
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean custom = ApiKeys.isCustom();
        items.add(UItem.asHeader(s("Сейчас используется", "Currently used")));
        items.add(UItem.asButton(0, s("Ваш ключ", "Your key"), "api_id " + ApiKeys.currentId()));
        items.add(UItem.asShadow(s(
                "У RecGram нет общего ключа: каждый пользователь работает со своим, полученным на my.telegram.org.",
                "RecGram has no shared key: every user works with their own key from my.telegram.org.")));

        items.add(UItem.asButton(ID_SET, s("Сменить ключ", "Change key")).accent());
        items.add(UItem.asShadow(s("После смены ключа приложение перезапустится.", "The app restarts after the key is changed.")));

        items.add(UItem.asHeader(s("Как получить свой ключ", "How to get your own key")));
        items.add(UItem.asButton(ID_HELP, "my.telegram.org"));
        items.add(UItem.asShadow(s(
                "1. Войдите на my.telegram.org по номеру телефона (лучше с отдельного аккаунта).\n2. Откройте «API development tools».\n3. Заполните форму: название, короткое имя (латиница), платформа Android.\n4. Скопируйте «App api_id» и «App api_hash».\nЕсли форма отвечает «ERROR», попробуйте без VPN, в другом браузере или позже. На одном аккаунте можно создать только одно приложение.",
                "1. Sign in at my.telegram.org with your phone number (preferably a separate account).\n2. Open \"API development tools\".\n3. Fill in the form: title, short name (Latin letters), platform Android.\n4. Copy \"App api_id\" and \"App api_hash\".\nIf the form says \"ERROR\", try without a VPN, in another browser or later. One account can create only one app.")));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_SET:
                showKeyDialog();
                break;
            case ID_RESET: {
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(s("Вернуть встроенный ключ?", "Use the built-in key?"));
                b.setMessage(s("Приложение перезапустится. Сохранение исчезающих медиа будет выключено.",
                        "The app will restart. Saving disappearing media will be turned off."));
                b.setPositiveButton(s("Вернуть", "Use built-in"), (d, w) -> {
                    ApiKeys.clearCustom();
                    ApiKeys.restartApp();
                });
                b.setNegativeButton(s("Отмена", "Cancel"), null);
                showDialog(b.create());
                break;
            }
            case ID_HELP:
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), "https://my.telegram.org");
                break;
        }
    }

    private EditTextBoldCursor field(Context ctx, String hint, boolean digits) {
        EditTextBoldCursor e = new EditTextBoldCursor(ctx);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        e.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        e.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(digits ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setBackground(null);
        e.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        e.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        e.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        return e;
    }

    private void showKeyDialog() {
        Context ctx = getParentActivity();
        if (ctx == null) {
            return;
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor id = field(ctx, "api_id", true);
        EditTextBoldCursor hash = field(ctx, "api_hash", false);
        box.addView(id, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 24, 8, 24, 8));
        box.addView(hash, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 24, 8, 24, 8));

        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(s("Свой API-ключ", "Your own API key"));
        b.setMessage(s("Данные с my.telegram.org → API development tools.", "From my.telegram.org → API development tools."));
        b.setView(box);
        b.setPositiveButton(s("Сохранить", "Save"), null);
        b.setNegativeButton(s("Отмена", "Cancel"), null);
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(d -> {
            View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (save != null) {
                save.setOnClickListener(v -> {
                    String sid = id.getText().toString().trim();
                    String shash = hash.getText().toString().trim();
                    if (!ApiKeys.isValid(sid, shash)) {
                        AndroidUtilities.shakeViewSpring(ApiKeys.isValid(sid, "0123456789abcdef0123456789abcdef") ? hash : id);
                        Toast.makeText(ctx, s("api_id — число, api_hash — 32 символа 0-9 и a-f.",
                                "api_id is a number, api_hash is 32 characters 0-9 and a-f."), Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    ApiKeys.setCustom(sid, shash);
                    Toast.makeText(ctx, s("Ключ сохранён, перезапуск…", "Key saved, restarting…"), Toast.LENGTH_SHORT).show();
                    Utilities.globalQueue.postRunnable(ApiKeys::restartApp, 800);
                });
            }
        });
        showDialog(dialog);
        AndroidUtilities.runOnUIThread(() -> {
            id.requestFocus();
            AndroidUtilities.showKeyboard(id);
        }, 300);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

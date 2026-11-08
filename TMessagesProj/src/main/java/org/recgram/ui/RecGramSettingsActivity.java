package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import org.recgram.ApiKeys;
import org.recgram.RecGramConfig;
import org.recgram.rules.RecordingRules;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Настройки → RecGram. */
public class RecGramSettingsActivity extends UniversalFragment {

    private static final int ID_RECORDING = 1;
    private static final int ID_SCREENSHOTS = 10;
    private static final int ID_SECRET_NOTIFY = 11;
    private static final int ID_COPY = 12;
    private static final int ID_SAVE = 13;
    private static final int ID_RESEND = 14;
    private static final int ID_STORIES = 15;
    private static final int ID_SELF_DESTRUCT = 16;
    private static final int ID_API = 20;
    private static final int ID_VERSION = 30;

    @Override
    protected CharSequence getTitle() {
        return "RecGram";
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        RecordingRules.Country c = RecordingRules.selected();
        CharSequence recValue;
        if (c == null) {
            recValue = s("страна не выбрана", "country not set");
        } else if (!RecordingRules.isRecordingAllowed()) {
            recValue = s("выключена", "off");
        } else {
            switch (RecGramConfig.getRecordingMode()) {
                case RecGramConfig.RECORD_ASK: recValue = s("спрашивать", "ask"); break;
                case RecGramConfig.RECORD_AUTO: recValue = s("все звонки", "all calls"); break;
                default: recValue = s("выключена", "off");
            }
        }
        items.add(UItem.asHeader(s("Звонки", "Calls")));
        items.add(UItem.asButton(ID_RECORDING, R.drawable.msg_voicechat, s("Запись звонков", "Call recording"), recValue));
        items.add(UItem.asShadow(null));

        boolean custom = ApiKeys.isCustom();
        items.add(UItem.asHeader(s("Защищённый контент", "Protected content")));
        items.add(UItem.asCheck(ID_SCREENSHOTS, s("Разрешить скриншоты и запись экрана", "Allow screenshots and screen recording")).setChecked(RecGramConfig.isAllowScreenshots()));
        items.add(UItem.asCheck(ID_SECRET_NOTIFY, s("Уведомлять о скриншоте в секретных чатах", "Notify about screenshots in secret chats")).setChecked(RecGramConfig.isSecretScreenshotNotify()));
        items.add(UItem.asCheck(ID_COPY, s("Копировать текст из защищённых чатов", "Copy text from protected chats")).setChecked(RecGramConfig.isAllowCopyProtected()));
        items.add(UItem.asCheck(ID_SAVE, s("Сохранять медиа из защищённых чатов", "Save media from protected chats")).setChecked(RecGramConfig.isAllowSaveProtected()));
        items.add(UItem.asCheck(ID_RESEND, s("Переотправлять сообщения из защищённых чатов", "Resend messages from protected chats")).setChecked(RecGramConfig.isAllowResendProtected()));
        items.add(UItem.asCheck(ID_STORIES, s("Скачивать истории", "Download stories")).setChecked(RecGramConfig.isAllowStoriesDownload()));
        items.add(UItem.asCheck(ID_SELF_DESTRUCT, s("Сохранять исчезающие медиа", "Save self-destructing media")).setChecked(RecGramConfig.isAllowSelfDestructSave()).setEnabled(custom));
        items.add(UItem.asShadow(s(
                "Всё выключено по умолчанию. За использование чужого и платного контента отвечаете вы."
                        + (custom ? "" : "\nИсчезающие медиа доступны только со своим API-ключом."),
                "Everything is off by default. You are responsible for how you use other people's and paid content."
                        + (custom ? "" : "\nSelf-destructing media is available only with your own API key."))));

        items.add(UItem.asHeader("API"));
        items.add(UItem.asButton(ID_API, R.drawable.msg_permissions, s("API-ключ", "API key"),
                custom ? s("свой", "your own") : s("встроенный", "built-in")));
        items.add(UItem.asShadow(s("Свой ключ можно получить на my.telegram.org.", "You can get your own key at my.telegram.org.")));

        items.add(UItem.asHeader(s("О приложении", "About")));
        items.add(UItem.asButton(ID_VERSION, R.drawable.msg_info, "RecGram", BuildVars.BUILD_VERSION_STRING));
        items.add(UItem.asShadow(s(
                "RecGram — неофициальный клиент Telegram с открытым исходным кодом (GPLv2). Он не связан с Telegram FZ-LLC.",
                "RecGram is an unofficial open-source Telegram client (GPLv2). It is not affiliated with Telegram FZ-LLC.")));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_RECORDING:
                presentFragment(new RecordingSettingsActivity());
                return;
            case ID_SCREENSHOTS:
                toggleProtected(() -> RecGramConfig.setAllowScreenshots(!RecGramConfig.isAllowScreenshots()), !RecGramConfig.isAllowScreenshots());
                return;
            case ID_SECRET_NOTIFY:
                RecGramConfig.setSecretScreenshotNotify(!RecGramConfig.isSecretScreenshotNotify());
                break;
            case ID_COPY:
                toggleProtected(() -> RecGramConfig.setAllowCopyProtected(!RecGramConfig.isAllowCopyProtected()), !RecGramConfig.isAllowCopyProtected());
                return;
            case ID_SAVE:
                toggleProtected(() -> RecGramConfig.setAllowSaveProtected(!RecGramConfig.isAllowSaveProtected()), !RecGramConfig.isAllowSaveProtected());
                return;
            case ID_RESEND:
                toggleProtected(() -> RecGramConfig.setAllowResendProtected(!RecGramConfig.isAllowResendProtected()), !RecGramConfig.isAllowResendProtected());
                return;
            case ID_STORIES:
                toggleProtected(() -> RecGramConfig.setAllowStoriesDownload(!RecGramConfig.isAllowStoriesDownload()), !RecGramConfig.isAllowStoriesDownload());
                return;
            case ID_SELF_DESTRUCT:
                toggleSelfDestruct();
                return;
            case ID_API:
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, s("Смена ключа появится в следующей версии.", "Changing the key arrives in the next version.")).show();
                return;
            default:
                return;
        }
        listView.adapter.update(true);
    }

    /** Первое включение любой функции защищённого контента — с предупреждением. */
    private void toggleProtected(Runnable toggle, boolean enabling) {
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("recgram_config", Context.MODE_PRIVATE);
        if (!enabling || p.getBoolean("protected_disclaimer_shown", false)) {
            toggle.run();
            listView.adapter.update(true);
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(s("Защищённый контент", "Protected content"));
        b.setMessage(s(
                "Авторы защищённых чатов запретили копирование и пересылку. Снимая запрет, вы берёте ответственность за использование чужого и платного контента на себя.",
                "The owners of protected chats have disabled copying and forwarding. By lifting this restriction you take responsibility for how you use other people's and paid content."));
        b.setPositiveButton(s("Понимаю", "I understand"), (d, w) -> {
            p.edit().putBoolean("protected_disclaimer_shown", true).apply();
            toggle.run();
            listView.adapter.update(true);
        });
        b.setNegativeButton(s("Отмена", "Cancel"), null);
        showDialog(b.create());
    }

    private void toggleSelfDestruct() {
        if (!ApiKeys.isCustom()) {
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(s("Нужен свой API-ключ", "Your own API key is required"));
            b.setMessage(s("Сохранение исчезающих медиа нарушает правила Telegram API (п. 1.4), поэтому со встроенным ключом оно недоступно.",
                    "Saving self-destructing media violates the Telegram API terms (section 1.4), so it is not available with the built-in key."));
            b.setPositiveButton(s("Понятно", "OK"), null);
            showDialog(b.create());
            return;
        }
        if (RecGramConfig.isAllowSelfDestructSave()) {
            RecGramConfig.setAllowSelfDestructSave(false);
            listView.adapter.update(true);
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(s("Исчезающие медиа", "Self-destructing media"));
        b.setMessage(s("Это нарушает правила Telegram API (п. 1.4). Telegram может заблокировать ваш API-ключ. Включаете на свой риск.",
                "This violates the Telegram API terms (section 1.4). Telegram may block your API key. Enable at your own risk."));
        b.setPositiveButton(s("Понимаю", "I understand"), (d, w) -> {
            RecGramConfig.setAllowSelfDestructSave(true);
            listView.adapter.update(true);
        });
        b.setNegativeButton(s("Отмена", "Cancel"), null);
        showDialog(b.create());
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

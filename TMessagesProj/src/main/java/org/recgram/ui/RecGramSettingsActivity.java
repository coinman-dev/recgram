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
    private static final int ID_BYPASS = 2;
    private static final int ID_PRESERVE_DELETED = 3;
    private static final int ID_SECRET_NOTIFY = 11;
    private static final int ID_COPY = 12;
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

        int dpi = org.recgram.bypass.Bypass.getDpiMode();
        items.add(UItem.asHeader(s("Сеть", "Network")));
        items.add(UItem.asButton(ID_BYPASS, R.drawable.msg_policy, s("Обход блокировок", "Bypass blocking"),
                dpi == org.recgram.bypass.Bypass.DPI_OFF ? s("DPI: выкл", "DPI: off") : s("DPI: вкл", "DPI: on")));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(s("История", "History")));
        items.add(UItem.asCheck(ID_PRESERVE_DELETED, s("Сохранять удалённое", "Keep deleted content")).setChecked(RecGramConfig.isPreserveDeleted()));
        items.add(UItem.asShadow(s(
                "Удалённые сообщения, фото, файлы и чаты останутся на этом устройстве и будут показаны серым. Удаление в Telegram выполняется как обычно. Уже сохранённые копии не удаляются при выключении опции.",
                "Deleted messages, photos, files and chats will stay on this device and appear dimmed. They are still deleted from Telegram as usual. Turning this off does not erase copies that are already saved.")));

        boolean custom = ApiKeys.isCustom();
        items.add(UItem.asHeader(s("Защищённый контент", "Protected content")));
        items.add(UItem.asCheck(ID_COPY, s("Скриншоты, копирование и сохранение", "Screenshots, copying and saving")).setChecked(RecGramConfig.isAllowProtected()));
        items.add(UItem.asCheck(ID_SECRET_NOTIFY, s("Сообщать о скриншоте", "Report screenshots")).setChecked(RecGramConfig.isSecretScreenshotNotify()));
        items.add(UItem.asCheck(ID_RESEND, s("Переотправлять", "Resend")).setChecked(RecGramConfig.isAllowResendProtected()));
        items.add(UItem.asCheck(ID_STORIES, s("Скачивать истории", "Download stories")).setChecked(RecGramConfig.isAllowStoriesDownload()));
        items.add(UItem.asCheck(ID_SELF_DESTRUCT, s("Сохранять исчезающие медиа", "Save disappearing media")).setChecked(RecGramConfig.isAllowSelfDestructSave()).setEnabled(custom));
        items.add(UItem.asShadow(s(
                "Действует в каналах и чатах с запретом копирования и в секретных чатах. «Скриншоты, копирование и сохранение» — скриншоты и запись экрана, копирование текста, сохранение фото, видео и файлов. «Сообщать о скриншоте» — собеседник в секретном чате узнает, что вы сделали скриншот. «Переотправлять» — сообщение уходит как новое, без пометки «Переслано».\n"
                        + "Всё выключено по умолчанию. За использование чужого и платного контента отвечаете вы."
                        + (custom ? "" : "\nИсчезающие медиа доступны только со своим API-ключом."),
                "Applies to channels and chats that forbid copying and to secret chats. \"Screenshots, copying and saving\" allows screenshots and screen recording, copying text and saving photos, videos and files. \"Report screenshots\" lets the other party in a secret chat know you took a screenshot. \"Resend\" sends the message as a new one, without the \"Forwarded\" label.\n"
                        + "Everything is off by default. You are responsible for how you use other people's and paid content."
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
            case ID_BYPASS:
                presentFragment(new BypassActivity());
                return;
            case ID_PRESERVE_DELETED:
                togglePreserveDeleted();
                return;
            case ID_SECRET_NOTIFY:
                RecGramConfig.setSecretScreenshotNotify(!RecGramConfig.isSecretScreenshotNotify());
                break;
            case ID_COPY:
                toggleProtected(() -> RecGramConfig.setAllowProtected(!RecGramConfig.isAllowProtected()), !RecGramConfig.isAllowProtected());
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
                presentFragment(new ApiKeyActivity());
                return;
            default:
                return;
        }
        listView.adapter.update(true);
    }

    private void togglePreserveDeleted() {
        if (RecGramConfig.isPreserveDeleted()) {
            RecGramConfig.setPreserveDeleted(false);
            listView.adapter.update(true);
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(s("Сохранять удалённое", "Keep deleted content"));
        b.setMessage(s(
                "Сохранение удалённых сообщений и чатов может нарушать правила Telegram API (п. 1.4). Telegram может заблокировать ваш API-ключ. Включаете на свой риск.",
                "Keeping deleted messages and chats may violate the Telegram API terms (section 1.4). Telegram may block your API key. Enable at your own risk."));
        b.setPositiveButton(s("Понимаю", "I understand"), (d, w) -> {
            RecGramConfig.setPreserveDeleted(true);
            listView.adapter.update(true);
        });
        b.setNegativeButton(s("Отмена", "Cancel"), null);
        showDialog(b.create());
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
            b.setPositiveButton(s("Ввести свой ключ", "Enter your key"), (d, w) -> presentFragment(new ApiKeyActivity()));
            b.setNegativeButton(s("Отмена", "Cancel"), null);
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

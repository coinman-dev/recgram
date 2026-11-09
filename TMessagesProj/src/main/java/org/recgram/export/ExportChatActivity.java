package org.recgram.export;

import static org.recgram.RLocale.s;

import android.view.View;
import android.widget.Toast;

import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Меню чата → «Экспорт чата»: что выгружать и ход экспорта. */
public class ExportChatActivity extends UniversalFragment implements ChatExporter.Listener {

    private static final int ID_HTML = 1, ID_JSON = 2;
    private static final int ID_MEDIA_BASE = 100;
    private static final int ID_SIZE_BASE = 200;
    private static final int ID_START = 300, ID_CANCEL = 301;

    private static final int[] MEDIA = {ChatExporter.PHOTOS, ChatExporter.VIDEOS, ChatExporter.VOICE, ChatExporter.ROUND,
            ChatExporter.STICKERS, ChatExporter.GIFS, ChatExporter.MUSIC, ChatExporter.FILES};
    private static final long MB = 1024L * 1024;
    private static final long[] SIZES = {8 * MB, 50 * MB, 500 * MB, 0};

    private final long dialogId;
    private final ChatExporter.Options opt = new ChatExporter.Options();

    public ExportChatActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    private static String mediaTitle(int flag) {
        switch (flag) {
            case ChatExporter.PHOTOS: return s("Фото", "Photos");
            case ChatExporter.VIDEOS: return s("Видео", "Videos");
            case ChatExporter.VOICE: return s("Голосовые сообщения", "Voice messages");
            case ChatExporter.ROUND: return s("Видеосообщения", "Video messages");
            case ChatExporter.STICKERS: return s("Стикеры", "Stickers");
            case ChatExporter.GIFS: return "GIF";
            case ChatExporter.MUSIC: return s("Музыка", "Music");
            default: return s("Файлы", "Files");
        }
    }

    private static String sizeTitle(long v) {
        return v == 0 ? s("Без ограничения", "No limit") : s("До ", "Up to ") + (v / MB) + s(" МБ", " MB");
    }

    @Override
    protected CharSequence getTitle() {
        return s("Экспорт чата", "Export chat");
    }

    @Override
    public boolean onFragmentCreate() {
        ChatExporter.addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        ChatExporter.removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onExportProgress(ChatExporter e) {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    private ChatExporter mine() {
        ChatExporter e = ChatExporter.current();
        return e != null && e.dialogId == dialogId && e.account == currentAccount ? e : null;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        ChatExporter e = mine();
        ChatExporter other = ChatExporter.current();
        String title = ChatExporter.titleOf(currentAccount, dialogId);

        if (e != null) {
            items.add(UItem.asHeader(e.finished ? s("Экспорт завершён", "Export finished") : s("Идёт экспорт", "Exporting")));
            items.add(UItem.asButton(0, e.title, ""));
            items.add(UItem.asShadow(e.statusLine() + "\n\n" + s("Папка: ", "Folder: ") + e.folder()
                    + (e.finished ? "" : "\n\n" + s("Можно выйти из этого экрана — экспорт продолжится, ход виден в уведомлении.",
                    "You can leave this screen — the export continues, progress is shown in the notification."))));
            if (!e.finished) {
                items.add(UItem.asButton(ID_CANCEL, s("Остановить", "Stop")).red());
                return;
            }
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(s("Чат", "Chat")));
        items.add(UItem.asButton(0, title, ""));
        items.add(UItem.asShadow(s("Сохраняется в Documents/RecGram/Export, как в Telegram Desktop: страницы HTML, result.json и папки с файлами.",
                "Saved to Documents/RecGram/Export, like Telegram Desktop: HTML pages, result.json and folders with files.")));

        items.add(UItem.asHeader(s("Вложения", "Media")));
        for (int i = 0; i < MEDIA.length; i++) {
            items.add(UItem.asCheck(ID_MEDIA_BASE + i, mediaTitle(MEDIA[i])).setChecked((opt.media & MEDIA[i]) != 0));
        }
        items.add(UItem.asShadow(s("Текст сообщений выгружается всегда. Защищённые сообщения выгружаются, только если в настройках RecGram разрешено сохранение защищённого контента.",
                "Message text is always exported. Protected messages are exported only if saving protected content is allowed in RecGram settings.")));

        items.add(UItem.asHeader(s("Размер файла", "File size")));
        for (int i = 0; i < SIZES.length; i++) {
            items.add(UItem.asRadio(ID_SIZE_BASE + i, sizeTitle(SIZES[i])).setChecked(opt.maxFileSize == SIZES[i]));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(s("Формат", "Format")));
        items.add(UItem.asCheck(ID_HTML, s("HTML — для чтения", "HTML — human-readable")).setChecked(opt.html));
        items.add(UItem.asCheck(ID_JSON, s("JSON — для программ", "JSON — machine-readable")).setChecked(opt.json));
        items.add(UItem.asShadow(null));

        boolean busy = other != null && !other.finished;
        items.add(UItem.asButton(ID_START, s("Экспортировать", "Export")).accent());
        if (busy) {
            items.add(UItem.asShadow(s("Сейчас идёт экспорт другого чата: ", "Another chat is being exported: ") + other.title));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_HTML) {
            opt.html = !opt.html;
        } else if (id == ID_JSON) {
            opt.json = !opt.json;
        } else if (id >= ID_MEDIA_BASE && id < ID_MEDIA_BASE + MEDIA.length) {
            opt.media ^= MEDIA[id - ID_MEDIA_BASE];
        } else if (id >= ID_SIZE_BASE && id < ID_SIZE_BASE + SIZES.length) {
            opt.maxFileSize = SIZES[id - ID_SIZE_BASE];
        } else if (id == ID_CANCEL) {
            ChatExporter e = mine();
            if (e != null) e.cancel();
        } else if (id == ID_START) {
            if (!opt.html && !opt.json) {
                Toast.makeText(getParentActivity(), s("Выберите хотя бы один формат", "Choose at least one format"), Toast.LENGTH_SHORT).show();
                return;
            }
            if (ChatExporter.isBlockedByProtection(currentAccount, dialogId)) {
                Toast.makeText(getParentActivity(), s("Чат защищён от копирования. Разрешите сохранение защищённого контента в настройках RecGram.",
                        "This chat is protected. Allow saving protected content in RecGram settings."), Toast.LENGTH_LONG).show();
                return;
            }
            ChatExporter.Options o = new ChatExporter.Options();
            o.html = opt.html;
            o.json = opt.json;
            o.media = opt.media;
            o.maxFileSize = opt.maxFileSize;
            if (ChatExporter.start(currentAccount, dialogId, o) == null) {
                Toast.makeText(getParentActivity(), s("Дождитесь окончания текущего экспорта", "Wait for the current export to finish"), Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            return;
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.view.View;

import org.recgram.RecGramConfig;
import org.recgram.rules.RecordingRules;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Настройки → RecGram → Запись звонков. */
public class RecordingSettingsActivity extends UniversalFragment {

    private static final int ID_COUNTRY = 1;
    private static final int ID_MODE_OFF = 10;
    private static final int ID_MODE_ASK = 11;
    private static final int ID_MODE_AUTO = 12;
    private static final int ID_AUDIO = 20;
    private static final int ID_VIDEO = 21;
    private static final int ID_NOTIFY = 22;

    @Override
    protected CharSequence getTitle() {
        return s("Запись звонков", "Call recording");
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
        boolean allowed = RecordingRules.isRecordingAllowed();

        items.add(UItem.asHeader(s("Страна", "Country")));
        items.add(UItem.asButton(ID_COUNTRY, s("В какой стране вы находитесь?", "Which country are you in?"),
                c == null ? s("Не выбрана", "Not set") : RuleTexts.dotted(c)));
        String info;
        if (c == null) {
            info = s("Выберите страну, иначе запись не включится.", "Choose your country, otherwise recording stays off.");
        } else if (c.color() == RecordingRules.Color.YELLOW && !allowed) {
            info = s("Запись выключена: вы не подтвердили предупреждение для этой страны.",
                    "Recording is off: you have not accepted the warning for this country.");
        } else {
            info = RuleTexts.explain(c);
        }
        items.add(UItem.asShadow(info));

        int mode = allowed ? RecGramConfig.getRecordingMode() : RecGramConfig.RECORD_OFF;
        items.add(UItem.asHeader(s("Когда записывать", "When to record")));
        items.add(UItem.asRadio(ID_MODE_OFF, s("Выключено", "Off")).setChecked(mode == RecGramConfig.RECORD_OFF));
        items.add(UItem.asRadio(ID_MODE_ASK, s("Спрашивать перед звонком", "Ask before each call")).setChecked(mode == RecGramConfig.RECORD_ASK).setEnabled(allowed));
        items.add(UItem.asRadio(ID_MODE_AUTO, s("Записывать все звонки", "Record all calls")).setChecked(mode == RecGramConfig.RECORD_AUTO).setEnabled(allowed));
        items.add(UItem.asShadow(s("Во время записи всегда виден значок записи. Скрытой записи в RecGram нет.",
                "A recording indicator is always visible while recording. RecGram has no hidden recording.")));

        items.add(UItem.asHeader(s("Что записывать", "What to record")));
        items.add(UItem.asCheck(ID_AUDIO, s("Звук", "Audio")).setChecked(RecGramConfig.isRecordAudio()).setEnabled(allowed));
        items.add(UItem.asCheck(ID_VIDEO, s("Видео (в разработке)", "Video (in development)")).setChecked(false).setEnabled(false));
        items.add(UItem.asCheck(ID_NOTIFY, s("Сообщать собеседнику о записи", "Tell the other party about recording")).setChecked(RecGramConfig.isNotifyPeer()).setEnabled(allowed));
        items.add(UItem.asShadow(s("В начале записи собеседнику уйдёт сообщение «Этот звонок записывается». Сама запись звонков появится в следующих версиях.",
                "When recording starts, the other party gets the message \"This call is being recorded\". Call recording itself arrives in upcoming versions.")));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        boolean allowed = RecordingRules.isRecordingAllowed();
        switch (item.id) {
            case ID_COUNTRY:
                presentFragment(new CountrySelectActivity());
                return;
            case ID_MODE_OFF:
                RecGramConfig.setRecordingMode(RecGramConfig.RECORD_OFF);
                break;
            case ID_MODE_ASK:
            case ID_MODE_AUTO:
                if (!allowed) return;
                RecGramConfig.setRecordingMode(item.id == ID_MODE_ASK ? RecGramConfig.RECORD_ASK : RecGramConfig.RECORD_AUTO);
                break;
            case ID_AUDIO:
                if (!allowed) return;
                RecGramConfig.setRecordAudio(!RecGramConfig.isRecordAudio());
                break;
            case ID_NOTIFY:
                if (!allowed) return;
                RecGramConfig.setNotifyPeer(!RecGramConfig.isNotifyPeer());
                break;
            default:
                return;
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

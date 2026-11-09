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
    private static final int ID_LEVEL = 23;
    private static final int ID_VOICE = 24;
    private static final int ID_RECORDINGS = 30;
    private static final int ID_CODEC_HEVC = 40;
    private static final int ID_CODEC_AVC = 41;
    private static final int ID_BITRATE_BASE = 50;

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

        items.add(UItem.asButton(ID_RECORDINGS, org.telegram.messenger.R.drawable.msg_voicechat, s("Записи звонков", "Call recordings")));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(s("В какой стране вы находитесь?", "Which country are you in?")));
        items.add(UItem.asButton(ID_COUNTRY,
                c == null ? s("Выбрать страну", "Choose country") : RuleTexts.dotted(c),
                c == null ? "" : s("изменить", "change")));
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
        items.add(UItem.asRadio(ID_MODE_ASK, s("Вручную: кнопка в звонке", "Manually: button in call")).setChecked(mode == RecGramConfig.RECORD_ASK).setEnabled(allowed));
        items.add(UItem.asRadio(ID_MODE_AUTO, s("Записывать все звонки", "Record all calls")).setChecked(mode == RecGramConfig.RECORD_AUTO).setEnabled(allowed));
        items.add(UItem.asShadow(s("Во время записи на экране звонка видна отметка «REC» и висит уведомление. Нажатие на отметку останавливает запись. Скрытой записи в RecGram нет.",
                "While recording, the call screen shows a \"REC\" mark and a notification is shown. Tap the mark to stop. RecGram has no hidden recording.")));

        items.add(UItem.asHeader(s("Что записывать", "What to record")));
        items.add(UItem.asCheck(ID_AUDIO, s("Звук", "Audio")).setChecked(RecGramConfig.isRecordAudio()).setEnabled(allowed));
        items.add(UItem.asCheck(ID_VIDEO, s("Видео в видеозвонках", "Video in video calls")).setChecked(RecGramConfig.isRecordVideo()).setEnabled(allowed));
        items.add(UItem.asCheck(ID_LEVEL, s("Выравнивать громкость голосов", "Even out voice volume")).setChecked(RecGramConfig.isAutoLevel()).setEnabled(allowed));
        boolean red = c != null && c.color() == RecordingRules.Color.RED;
        boolean voiceAllowed = c != null && !"absolute".equals(c.self);
        items.add(UItem.asCheck(ID_VOICE, s("Голосовое предупреждение", "Voice notice"))
                .setChecked(RecGramConfig.isVoiceNotice()).setEnabled(voiceAllowed));
        items.add(UItem.asCheck(ID_NOTIFY, s("Сообщать собеседнику о записи", "Tell the other party about recording")).setChecked(RecGramConfig.isNotifyPeer()).setEnabled(allowed));
        items.add(UItem.asShadow(s("Голосовое предупреждение: в начале звонка собеседник слышит «Внимание. Этот разговор записывается», запись начинается после него. "
                        + (red ? "В вашей стране запись без согласия запрещена, поэтому запись возможна только с голосовым предупреждением." : ""),
                "Voice notice: at the start of the call the other party hears \"Attention. This call is being recorded\", recording starts after it. "
                        + (red ? "Recording without consent is prohibited in your country, so recording is possible only with the voice notice." : ""))));
        items.add(UItem.asShadow(s("Звонки записываются в Opus, моно, 64 кбит/с — папка Recordings/RecGram, данные звонка внутри файла. Видеозвонки — в MP4, папка Movies/RecGram. Сообщение о записи уходит собеседнику до ответа на звонок.",
                "Calls are recorded as Opus, mono, 64 kbps in Recordings/RecGram, call data inside the file. Video calls are recorded as MP4 in Movies/RecGram. The recording notice is sent to the other party before the call is answered.")));

        if (RecGramConfig.isRecordVideo()) {
            boolean hevc = RecGramConfig.isVideoHevc();
            items.add(UItem.asHeader(s("Кодек видео", "Video codec")));
            items.add(UItem.asRadio(ID_CODEC_HEVC, "H.265").setChecked(hevc).setEnabled(allowed));
            items.add(UItem.asRadio(ID_CODEC_AVC, "H.264").setChecked(!hevc).setEnabled(allowed));
            items.add(UItem.asShadow(s("H.265 даёт ~30% экономии размера, но старые плееры (программы) не умеют его проигрывать. Если телефон не умеет кодировать H.265, запись пойдёт в H.264.",
                    "H.265 saves ~30% of size, but older players cannot play it. If the phone cannot encode H.265, H.264 is used.")));
            items.add(UItem.asHeader(s("Битрейт видео", "Video bitrate")));
            int[] opts = hevc ? RecGramConfig.VIDEO_BITRATES_HEVC : RecGramConfig.VIDEO_BITRATES_AVC;
            int current = RecGramConfig.getVideoBitrate(hevc);
            for (int i = 0; i < opts.length; i++) {
                items.add(UItem.asRadio(ID_BITRATE_BASE + i, String.format(java.util.Locale.US, "%.1f Mb/s", opts[i] / 1_000_000f))
                        .setChecked(opts[i] == current).setEnabled(allowed));
            }
            items.add(UItem.asShadow(s("Выше битрейт — лучше картинка, но больше файл.", "Higher bitrate — better picture, bigger file.")));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        boolean allowed = RecordingRules.isRecordingAllowed();
        switch (item.id) {
            case ID_RECORDINGS:
                presentFragment(new RecordingsActivity());
                return;
            case ID_CODEC_HEVC:
            case ID_CODEC_AVC:
                if (!allowed) return;
                RecGramConfig.setVideoHevc(item.id == ID_CODEC_HEVC);
                break;
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
            case ID_VIDEO:
                if (!allowed) return;
                RecGramConfig.setRecordVideo(!RecGramConfig.isRecordVideo());
                break;
            case ID_VOICE: {
                if (c0() == null || "absolute".equals(c0().self)) return;
                boolean on = !RecGramConfig.isVoiceNotice();
                RecGramConfig.setVoiceNotice(on);
                if (on) {
                    org.recgram.rec.VoiceNotice.prepare();
                } else if (!RecordingRules.isRecordingAllowed()) {
                    RecGramConfig.setRecordingMode(RecGramConfig.RECORD_OFF);
                }
                break;
            }
            case ID_LEVEL:
                if (!allowed) return;
                RecGramConfig.setAutoLevel(!RecGramConfig.isAutoLevel());
                break;
            case ID_NOTIFY:
                if (!allowed) return;
                RecGramConfig.setNotifyPeer(!RecGramConfig.isNotifyPeer());
                break;
            default:
                int bi = item.id - ID_BITRATE_BASE;
                boolean hevc = RecGramConfig.isVideoHevc();
                int[] opts = hevc ? RecGramConfig.VIDEO_BITRATES_HEVC : RecGramConfig.VIDEO_BITRATES_AVC;
                if (!allowed || bi < 0 || bi >= opts.length) return;
                RecGramConfig.setVideoBitrate(hevc, opts[bi]);
                break;
        }
        listView.adapter.update(true);
    }

    private static RecordingRules.Country c0() {
        return RecordingRules.selected();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

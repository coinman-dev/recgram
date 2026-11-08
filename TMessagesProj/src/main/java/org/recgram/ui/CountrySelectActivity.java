package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.text.TextUtils;
import android.view.View;

import org.recgram.RecGramConfig;
import org.recgram.rules.RecordingRules;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** «В какой стране вы находитесь?» — список стран с цветной меткой. Страны с регионами выбираются в два шага. */
public class CountrySelectActivity extends UniversalFragment {

    private static final int ID_GROUP = 1000;
    private static final int ID_DETECTED = 900;

    /** Группы по ISO-коду: одна запись — страна целиком, несколько — регионы. */
    private final ArrayList<List<RecordingRules.Country>> groups = new ArrayList<>();
    private final ArrayList<List<RecordingRules.Country>> detected = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        LinkedHashMap<String, List<RecordingRules.Country>> byIso = new LinkedHashMap<>();
        for (RecordingRules.Country c : RecordingRules.allWithUnknown()) {
            List<RecordingRules.Country> g = byIso.get(c.iso);
            if (g == null) {
                byIso.put(c.iso, g = new ArrayList<>());
            }
            g.add(c);
        }
        groups.addAll(byIso.values());
        String iso = RecordingRules.detectIso();
        if (!iso.isEmpty()) {
            if (byIso.containsKey(iso)) {
                detected.add(byIso.get(iso));
            }
            // Сети Северного Кипра имеют код Турции.
            if ("TR".equals(iso) && byIso.containsKey("CY")) {
                detected.add(byIso.get("CY"));
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return s("Ваша страна", "Your country");
    }

    private static String groupName(List<RecordingRules.Country> g) {
        return new Locale("", g.get(0).iso).getDisplayCountry(org.recgram.RLocale.uiLocale());
    }

    private static String regionName(RecordingRules.Country c) {
        String region = org.recgram.RLocale.isRussian() ? c.regionRu : c.regionEn;
        if (!TextUtils.isEmpty(region)) {
            return region;
        }
        if ("CY".equals(c.id)) {
            return s("Республика Кипр", "Republic of Cyprus");
        }
        return c.displayName();
    }

    private UItem row(int id, List<RecordingRules.Country> g, String selected) {
        if (g.size() == 1) {
            RecordingRules.Country c = g.get(0);
            return UItem.asRadio(id, c.displayName(), RuleTexts.coloredStatus(c.color())).setChecked(c.id.equals(selected));
        }
        RecordingRules.Country chosen = null;
        for (RecordingRules.Country c : g) {
            if (c.id.equals(selected)) {
                chosen = c;
            }
        }
        CharSequence value = chosen != null ? RuleTexts.coloredStatus(chosen.color()) : s("регионы ›", "regions ›");
        String name = chosen != null ? groupName(g) + ": " + regionName(chosen) : groupName(g);
        return UItem.asRadio(id, name, value).setChecked(chosen != null);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String selected = RecGramConfig.getCountryId();
        items.add(UItem.asShadow(s(
                "От страны зависит, можно ли записывать звонки.\n🟢 можно · 🟡 непонятно или есть риск, решаете вы · 🔴 нельзя, запись не работает",
                "Your country decides whether calls can be recorded.\n🟢 allowed · 🟡 unclear or risky, you decide · 🔴 not allowed, recording is off")));
        if (!detected.isEmpty()) {
            items.add(UItem.asHeader(s("Определено по сети", "Detected from network")));
            for (int i = 0; i < detected.size(); i++) {
                items.add(row(ID_DETECTED + i, detected.get(i), selected));
            }
            items.add(UItem.asShadow(detected.size() > 1
                    ? s("Сети Северного Кипра определяются как Турция. Если вы на Северном Кипре, выберите «Кипр».",
                        "Northern Cyprus networks show up as Turkey. If you are in Northern Cyprus, choose \"Cyprus\".")
                    : null));
        }
        items.add(UItem.asHeader(s("Все страны", "All countries")));
        for (int i = 0; i < groups.size(); i++) {
            items.add(row(ID_GROUP + i, groups.get(i), selected));
        }
        items.add(UItem.asShadow(s("Это справка, а не юридическая консультация. Законы меняются.",
                "This is general information, not legal advice. Laws change.")));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        List<RecordingRules.Country> g = null;
        if (item.id >= ID_GROUP && item.id - ID_GROUP < groups.size()) {
            g = groups.get(item.id - ID_GROUP);
        } else if (item.id >= ID_DETECTED && item.id - ID_DETECTED < detected.size()) {
            g = detected.get(item.id - ID_DETECTED);
        }
        if (g == null) {
            return;
        }
        if (g.size() == 1) {
            choose(g.get(0));
            return;
        }
        final List<RecordingRules.Country> regions = g;
        CharSequence[] names = new CharSequence[regions.size()];
        for (int i = 0; i < regions.size(); i++) {
            names[i] = RuleTexts.dotted(regions.get(i).color(), regionName(regions.get(i)));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(groupName(regions));
        b.setItems(names, (dialog, which) -> choose(regions.get(which)));
        showDialog(b.create());
    }

    private void choose(RecordingRules.Country c) {
        final RecordingRules.Color color = c.color();
        if (color == RecordingRules.Color.GREEN) {
            apply(c, false);
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(c.displayName());
        b.setMessage(RuleTexts.explain(c));
        if (color == RecordingRules.Color.YELLOW) {
            b.setPositiveButton(s("Понимаю, включить", "I understand, enable"), (d, w) -> apply(c, true));
            b.setNegativeButton(s("Без записи", "Without recording"), (d, w) -> apply(c, false));
        } else {
            b.setPositiveButton(s("Понятно", "OK"), (d, w) -> apply(c, false));
        }
        showDialog(b.create());
    }

    private void apply(RecordingRules.Country c, boolean acceptRisk) {
        RecGramConfig.setCountryId(c.id);
        RecGramConfig.setAcceptedRiskCountryId(acceptRisk ? c.id : "");
        if (!RecordingRules.isRecordingAllowed()) {
            RecGramConfig.setRecordingMode(RecGramConfig.RECORD_OFF);
        }
        finishFragment();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

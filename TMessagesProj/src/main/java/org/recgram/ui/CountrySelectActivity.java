package org.recgram.ui;

import static org.recgram.RLocale.s;

import android.view.View;

import org.recgram.RecGramConfig;
import org.recgram.rules.RecordingRules;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/** «В какой стране вы находитесь?» — список стран с цветной меткой. */
public class CountrySelectActivity extends UniversalFragment {

    private static final int ID_BASE = 1000;
    private static final int ID_DETECTED = 999;

    private List<RecordingRules.Country> countries;
    private RecordingRules.Country detected;

    @Override
    public boolean onFragmentCreate() {
        countries = RecordingRules.allWithUnknown();
        String iso = RecordingRules.detectIso();
        if (!iso.isEmpty()) {
            for (RecordingRules.Country c : countries) {
                if (c.iso.equals(iso)) {
                    detected = c;
                    break;
                }
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return s("Ваша страна", "Your country");
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String selected = RecGramConfig.getCountryId();
        items.add(UItem.asShadow(s(
                "От страны зависит, можно ли записывать звонки.\n🟢 можно · 🟡 непонятно или есть риск, решаете вы · 🔴 нельзя, запись не работает",
                "Your country decides whether calls can be recorded.\n🟢 allowed · 🟡 unclear or risky, you decide · 🔴 not allowed, recording is off")));
        if (detected != null) {
            items.add(UItem.asHeader(s("Определено по сети", "Detected from network")));
            items.add(UItem.asRadio(ID_DETECTED, RuleTexts.dotted(detected), RuleTexts.status(detected.color())).setChecked(detected.id.equals(selected)));
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asHeader(s("Все страны", "All countries")));
        for (int i = 0; i < countries.size(); i++) {
            RecordingRules.Country c = countries.get(i);
            items.add(UItem.asRadio(ID_BASE + i, RuleTexts.dotted(c), RuleTexts.status(c.color())).setChecked(c.id.equals(selected)));
        }
        items.add(UItem.asShadow(s("Это справка, а не юридическая консультация. Законы меняются.",
                "This is general information, not legal advice. Laws change.")));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        RecordingRules.Country c = null;
        if (item.id == ID_DETECTED) {
            c = detected;
        } else if (item.id >= ID_BASE && item.id - ID_BASE < countries.size()) {
            c = countries.get(item.id - ID_BASE);
        }
        if (c != null) {
            choose(c);
        }
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

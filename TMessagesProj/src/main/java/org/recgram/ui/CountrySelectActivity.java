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
        String name = groupName(g);
        if (chosen != null) {
            String region = regionName(chosen);
            name = region.contains(name) ? region : name + ": " + region;
        }
        return UItem.asRadio(id, name, value).setChecked(chosen != null);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String selected = RecGramConfig.getCountryId();
        items.add(UItem.asShadow(s(
                "От страны зависит, можно ли записывать звонки.\n\n🟢 Зелёный — можно\n🟡 Жёлтый — непонятно или есть риск, решаете вы\n🔴 Красный — только с голосовым предупреждением в начале звонка\n⚫ Серый — нельзя, запись не работает",
                "Your country decides whether calls can be recorded.\n\n🟢 Green — allowed\n🟡 Yellow — unclear or risky, you decide\n🔴 Red — only with a voice notice at the start of the call\n⚫ Grey — not allowed, recording is off")));
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
        items.add(UItem.asCustom(allHeader()));
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        int shown = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (!q.isEmpty() && !matches(groups.get(i), q)) {
                continue;
            }
            items.add(row(ID_GROUP + i, groups.get(i), selected));
            shown++;
        }
        if (shown == 0) {
            items.add(UItem.asShadow(s("Ничего не найдено", "Nothing found")));
        }
        items.add(UItem.asShadow(s("Это справка, а не юридическая консультация. Законы меняются.",
                "This is general information, not legal advice. Laws change.")));
    }

    private String query = "";
    private android.widget.FrameLayout header;

    /** Строка «Все страны» с полем поиска справа. */
    private View allHeader() {
        if (header != null) {
            return header;
        }
        android.content.Context ctx = getContext();
        header = new android.widget.FrameLayout(ctx);
        header.setBackgroundColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhite));
        android.widget.TextView title = new android.widget.TextView(ctx);
        title.setText(s("Все страны", "All countries"));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        title.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueHeader));
        header.addView(title, org.telegram.ui.Components.LayoutHelper.createFrame(-2, -2, android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL, 21, 0, 0, 0));
        org.telegram.ui.Components.EditTextBoldCursor search = new org.telegram.ui.Components.EditTextBoldCursor(ctx);
        search.setHint(s("🔍 Поиск", "🔍 Search"));
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        search.setSingleLine(true);
        search.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        search.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText));
        search.setHintTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteHintText));
        search.setCursorColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText));
        search.setBackground(null);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                if (query.equals(e.toString())) {
                    return;
                }
                query = e.toString();
                listView.adapter.update(false);
                // Список перестраивается и поле теряет фокус — возвращаем его
                search.post(() -> {
                    if (!search.hasFocus()) {
                        search.requestFocus();
                        search.setSelection(search.length());
                    }
                });
            }
        });
        header.addView(search, org.telegram.ui.Components.LayoutHelper.createFrame(180, 44, android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        header.setMinimumHeight(org.telegram.messenger.AndroidUtilities.dp(48));
        return header;
    }

    /** Поиск по названию на языке интерфейса, по-английски, по ISO-коду и регионам. */
    private static boolean matches(List<RecordingRules.Country> g, String q) {
        String iso = g.get(0).iso;
        if (iso.toLowerCase(java.util.Locale.ROOT).startsWith(q)) return true;
        if (groupName(g).toLowerCase().contains(q)) return true;
        if (new java.util.Locale("", iso).getDisplayCountry(java.util.Locale.ENGLISH).toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        for (RecordingRules.Country c : g) {
            if (c.regionRu != null && c.regionRu.toLowerCase().contains(q)) return true;
            if (c.regionEn != null && c.regionEn.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        }
        return false;
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

#!/usr/bin/env python3
"""Этап 1: пункт RecGram в главных настройках Telegram."""
import pathlib, sys
p = pathlib.Path(sys.argv[1]) / "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java"
s = p.read_text(encoding="utf-8")
anchor = "getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));\n"
assert s.count(anchor) == 1, "anchor fill"
s = s.replace(anchor, anchor + "        items.add(SettingCell.Factory.of(1001, IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom, R.drawable.settings_calls, \"RecGram\", org.recgram.RLocale.s(\"Запись звонков, скриншоты, API\", \"Call recording, screenshots, API\"))); // RecGram\n")
anchor2 = "        switch (item.id) {\n            case 1:\n                presentSettingFragment(new UserInfoActivity());"
assert s.count(anchor2) == 1, "anchor click"
s = s.replace(anchor2, "        switch (item.id) {\n            case 1001: // RecGram\n                presentSettingFragment(new org.recgram.ui.RecGramSettingsActivity());\n                break;\n            case 1:\n                presentSettingFragment(new UserInfoActivity());")
p.write_text(s, encoding="utf-8")
print("ok")

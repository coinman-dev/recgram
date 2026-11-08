#!/usr/bin/env python3
"""Экран приветствия: красный самолётик, надпись RecGram, своё описание."""
import pathlib, sys
p = pathlib.Path(sys.argv[1]) / "TMessagesProj/src/main/java/org/telegram/ui/IntroActivity.java"
s = p.read_text(encoding="utf-8")
pairs = [
    ("            loadTexture(R.drawable.intro_tg_plane, 21);\n",
     "            loadTexture(R.drawable.intro_tg_plane, 21, 0xFFFF3B30, false); // RecGram: красный самолётик\n"),
    ("        SpannableStringBuilder ssb = new SpannableStringBuilder(LocaleController.getString(R.string.Page1Title));\n"
     "        ssb.setSpan(new ImageSpan(logoDrawable), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);\n"
     "        titles[0] = ssb;\n",
     "        SpannableStringBuilder ssb = new SpannableStringBuilder(\"RecGram\"); // RecGram: вместо логотипа Telegram\n"
     "        titles[0] = ssb;\n"
     "        messages[0] = org.recgram.RLocale.s(\"Telegram с записью звонков\\nи без ограничений на копирование.\\nНеофициальный клиент.\",\n"
     "                \"Telegram with call recording\\nand no copy restrictions.\\nUnofficial client.\"); // RecGram\n"),
]
for old, new in pairs:
    assert s.count(old) == 1, old[:60]
    s = s.replace(old, new)
p.write_text(s, encoding="utf-8")
print("ok")

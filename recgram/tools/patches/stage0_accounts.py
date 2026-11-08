#!/usr/bin/env python3
"""Этап 0: свой тип аккаунта и MIME-типы контактов, чтобы не конфликтовать с официальным Telegram."""
import pathlib, sys
root = pathlib.Path(sys.argv[1]) / "TMessagesProj/src/main"
jobs = {
    "AndroidManifest.xml": [("vnd.org.telegram.messenger.android", "vnd.app.recgram.android")],
    "java/org/telegram/ui/LaunchActivity.java": [("vnd.org.telegram.messenger.android", "vnd.app.recgram.android")],
    "java/org/telegram/messenger/ContactsController.java": [
        ("vnd.org.telegram.messenger.android", "vnd.app.recgram.android"),
        ('"org.telegram.messenger"', '"app.recgram" /* RecGram */'),
    ],
    "res/xml/contacts.xml": [("vnd.org.telegram.messenger.android", "vnd.app.recgram.android")],
    "res/xml/auth.xml": [('android:accountType="org.telegram.messenger"', 'android:accountType="app.recgram"')],
    "res/xml/sync_contacts.xml": [('android:accountType="org.telegram.messenger"', 'android:accountType="app.recgram"')],
    "res/xml/auth_menu.xml": [('android:targetPackage="org.telegram.messenger"', 'android:targetPackage="app.recgram"')],
}
for rel, pairs in jobs.items():
    p = root / rel
    s = p.read_text(encoding="utf-8")
    for old, new in pairs:
        n = s.count(old)
        if n == 0:
            sys.exit(f"[{rel}] не найдено: {old}")
        s = s.replace(old, new)
        print(f"ok {rel}: {n}x {old}")
    p.write_text(s, encoding="utf-8")

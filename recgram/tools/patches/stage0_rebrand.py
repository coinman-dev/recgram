#!/usr/bin/env python3
"""Этап 0: переименование Telegram -> RecGram. Запуск: python3 -I stage0_rebrand.py <корень исходников>"""
import json, pathlib, re, sys

root = pathlib.Path(sys.argv[1])

def edit(rel, pairs, count_check=True):
    p = root / rel
    s = p.read_text(encoding="utf-8")
    for old, new in pairs:
        if old not in s:
            sys.exit(f"[{rel}] не найдено: {old!r}")
        s = s.replace(old, new, 1)
    p.write_text(s, encoding="utf-8")
    print("ok", rel)

# 1. Имя пакета
edit("gradle.properties", [("APP_PACKAGE=org.telegram.messenger", "APP_PACKAGE=app.recgram")])

# 2. Название приложения во всех локалях
for p in (root / "TMessagesProj/src/main/res").glob("values*/strings.xml"):
    s = p.read_text(encoding="utf-8")
    n = re.sub(r'(<string name="AppName">)[^<]*(</string>)', r"\1RecGram\2", s)
    n = re.sub(r'(<string name="AppNameBeta">)[^<]*(</string>)', r"\1RecGram Beta\2", n)
    if n != s:
        p.write_text(n, encoding="utf-8")
        print("ok", p.relative_to(root))

# 3. API-ключ из local.properties или переменных окружения (в git не попадает)
edit("TMessagesProj/build.gradle", [(
    "        multiDexEnabled true\n\n        externalNativeBuild {",
    "        multiDexEnabled true\n\n"
    "        // RecGram: встроенный API-ключ берётся из local.properties или переменных окружения\n"
    "        buildConfigField \"int\", \"RECGRAM_API_ID\", (getProps(\"RECGRAM_API_ID\") ?: System.getenv(\"RECGRAM_API_ID\") ?: \"0\")\n"
    "        buildConfigField \"String\", \"RECGRAM_API_HASH\", \"\\\"\" + (getProps(\"RECGRAM_API_HASH\") ?: System.getenv(\"RECGRAM_API_HASH\") ?: \"\") + \"\\\"\"\n\n"
    "        externalNativeBuild {",
)])

# 4. BuildVars: свой ключ, отключение функций, привязанных к официальным ключам Telegram
edit("TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java", [
    ("public static boolean CHECK_UPDATES = true;", "public static boolean CHECK_UPDATES = false; // RecGram: обновления Telegram нам не подходят"),
    ('public static int APP_ID = 4;', 'public static int APP_ID = BuildConfig.RECGRAM_API_ID; // RecGram'),
    ('public static String APP_HASH = "014b35b6184100b085b0d0572f9b5103";', 'public static String APP_HASH = BuildConfig.RECGRAM_API_HASH; // RecGram'),
    ('public static String SAFETYNET_KEY = "AIzaSyDqt8P-7F7CPCseMkOiVRgb1LY8RN1bvH8";', 'public static String SAFETYNET_KEY = ""; // RecGram: ключ Telegram'),
    ("public static boolean IS_BILLING_UNAVAILABLE = false;", "public static boolean IS_BILLING_UNAVAILABLE = true; // RecGram"),
    ("public static boolean SUPPORTS_PASSKEYS = true;", "public static boolean SUPPORTS_PASSKEYS = false; // RecGram: работает только с официальными app id"),
    ("public static boolean SUPPORTS_FPNV = true;", "public static boolean SUPPORTS_FPNV = false; // RecGram: Firebase-проверка номера Telegram"),
])

# 5. Заглушка Firebase (у Telegram свой проект, его ключи нам не принадлежат)
def client(pkg, n):
    return {
        "client_info": {"mobilesdk_app_id": f"1:000000000000:android:00000000000000{n:02d}",
                        "android_client_info": {"package_name": pkg}},
        "oauth_client": [],
        "api_key": [{"current_key": "AIzaSyRecGramPlaceholderKeyNotReal0000"}],
        "services": {"appinvite_service": {"other_platform_oauth_client": []}},
    }
gs = {
    "project_info": {"project_number": "000000000000", "project_id": "recgram-placeholder",
                     "storage_bucket": "recgram-placeholder.appspot.com"},
    "client": [client(p, i) for i, p in enumerate(["app.recgram", "app.recgram.beta", "app.recgram.web"])],
    "configuration_version": "1",
}
for rel in ["TMessagesProj/google-services.json", "TMessagesProj_App/google-services.json"]:
    (root / rel).write_text(json.dumps(gs, indent=2) + "\n", encoding="utf-8")
    print("ok", rel)

# 6. Временная своя иконка: кнопка записи, без самолётика Telegram
res = root / "TMessagesProj/src/main/res"
(res / "drawable/recgram_icon_background.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#1E2A3A" android:pathData="M0,0h108v108h-108z" />
</vector>
''', encoding="utf-8")
fg = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#00000000" android:strokeColor="{ring}" android:strokeWidth="5"
        android:pathData="M54,30a24,24 0,1 1,0 48a24,24 0,1 1,0 -48z" />
    <path android:fillColor="{dot}" android:pathData="M54,42a12,12 0,1 1,0 24a12,12 0,1 1,0 -24z" />
</vector>
'''
(res / "drawable/recgram_icon_foreground.xml").write_text(fg.format(ring="#FFFFFF", dot="#FF3B30"), encoding="utf-8")
(res / "drawable/recgram_icon_monochrome.xml").write_text(fg.format(ring="#FFFFFF", dot="#FFFFFF"), encoding="utf-8")
icon = '''<?xml version="1.0" encoding="utf-8"?>
<!-- RecGram: временная иконка -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/recgram_icon_background" />
    <foreground android:drawable="@drawable/recgram_icon_foreground" />
    <monochrome android:drawable="@drawable/recgram_icon_monochrome" />
</adaptive-icon>'''
for name in ["ic_launcher.xml", "ic_launcher_round.xml"]:
    (res / "mipmap-anydpi-v26" / name).write_text(icon, encoding="utf-8")
    print("ok mipmap-anydpi-v26/" + name)

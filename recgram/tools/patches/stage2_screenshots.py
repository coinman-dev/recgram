#!/usr/bin/env python3
"""Этап 2а: разрешение скриншотов и уведомление о скриншоте по настройкам RecGram."""
import pathlib, sys
root = pathlib.Path(sys.argv[1]) / "TMessagesProj/src/main/java"
G = "!org.recgram.Protect.screenshots()"
ADD = "flags |= WindowManager.LayoutParams.FLAG_SECURE;"

def patch(rel, pairs):
    p = root / rel
    s = p.read_text(encoding="utf-8")
    for old, new, n in pairs:
        c = s.count(old)
        if c != n:
            sys.exit(f"[{rel}] ожидалось {n}, найдено {c}: {old!r}")
        s = s.replace(old, new)
    p.write_text(s, encoding="utf-8")
    print("ok", rel)

(root / "org/recgram/Protect.java").write_text('''package org.recgram;

/** Проверки снятия ограничений защищённого контента. Вызываются из кода Telegram. */
public final class Protect {

    private Protect() {
    }

    /** Не ставить FLAG_SECURE для защищённого контента. */
    public static boolean screenshots() {
        return RecGramConfig.isAllowScreenshots();
    }

    /** Отправлять ли собеседнику уведомление о скриншоте. */
    public static boolean screenshotNotify() {
        return !RecGramConfig.isAllowScreenshots() || RecGramConfig.isSecretScreenshotNotify();
    }
}
''', encoding="utf-8")
print("ok Protect.java")

patch("org/telegram/messenger/FlagSecureReason.java", [
    ("        if (isSecuredNow(window)) {", f"        if (isSecuredNow(window) && {G}) {{ // RecGram", 1)])
patch("org/telegram/ui/Stories/StoryViewer.java", [
    ("        allowScreenshots = !isShowing || allowScreenshots;",
     "        allowScreenshots = !isShowing || allowScreenshots || org.recgram.Protect.screenshots(); // RecGram", 1)])
patch("org/telegram/ui/SecretVoicePlayer.java", [
    ("        if (!BuildVars.DEBUG_PRIVATE_VERSION) {\n            params." + ADD,
     f"        if (!BuildVars.DEBUG_PRIVATE_VERSION && {G}) {{ // RecGram\n            params." + ADD, 1)])
patch("org/telegram/ui/GroupCallActivity.java", [
    ("                if (!BuildVars.DEBUG_PRIVATE_VERSION) {\n                    params." + ADD,
     f"                if (!BuildVars.DEBUG_PRIVATE_VERSION && {G}) {{ // RecGram\n                    params." + ADD, 1)])
patch("org/telegram/ui/PhotoViewer.java", [
    ("                windowLayoutParams." + ADD,
     f"                if ({G}) windowLayoutParams." + ADD + " // RecGram", 1)])
patch("org/telegram/ui/SecretMediaViewer.java", [
    ("        windowLayoutParams." + ADD,
     f"        if ({G}) windowLayoutParams." + ADD + " // RecGram", 1)])
patch("org/telegram/ui/Components/TranslateAlert2.java", [
    ("        if (noforwards) {\n            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);",
     f"        if (noforwards && {G}) {{ // RecGram\n            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);", 1)])
patch("org/telegram/messenger/MediaController.java", [
    ("        if (send) {\n            if (lastSecretChat != null) {",
     "        if (send && org.recgram.Protect.screenshotNotify()) { // RecGram\n            if (lastSecretChat != null) {", 1)])

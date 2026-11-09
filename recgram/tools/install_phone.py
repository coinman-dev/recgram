#!/usr/bin/env python3
"""
Установка APK на телефон с автоматическим ответом на окна защиты.

Запуск: python -I recgram/tools/install_phone.py [путь к apk]
По умолчанию ставит builds/RecGram-dev-latest-debug.apk.

Что делает:
- запускает adb install -r (adb Windows, порт 5137);
- пока идёт установка и 40 секунд после неё смотрит на экран и нажимает
  «Все равно установить» (Samsung «Защита приложений») и кнопки антивируса,
  которые оставляют приложение (игнорировать / оставить / доверять);
- никогда не нажимает «Удалить» и «Не устанавливать»;
- незнакомые окна с упоминанием RecGram сохраняет снимком в tmp/install_unknown_*.png.
"""
import os
import pathlib
import subprocess
import sys
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import ui  # noqa: E402

# Кнопки, которые можно нажимать (нижний регистр, точное совпадение текста кнопки)
ALLOW = [
    "все равно установить", "всё равно установить", "install anyway",
    "игнорировать", "ignore", "оставить", "keep", "keep app", "оставить приложение",
    "доверять", "trust", "trust app", "доверять приложению", "пропустить", "skip",
    "не удалять", "don't uninstall", "allow", "разрешить",
]
# Кнопки, которые нажимать нельзя никогда
DENY = ["удалить", "uninstall", "не устанавливать", "don't install", "отмена", "cancel"]


def buttons():
    try:
        for n, c in ui.nodes():
            text = (n.get("text") or "").strip()
            if text and n.get("clickable") == "true":
                yield text, c
    except Exception:
        return


def screen_mentions(word):
    try:
        return any(word.lower() in ui.label(n).lower() for n, _ in ui.nodes())
    except Exception:
        return False


def watch(stop_at, done):
    seen_unknown = set()
    while time.time() < stop_at[0]:
        found = list(buttons())
        tapped = False
        # Avast One: «Найдено подозрительное приложение» с кнопками «Отмена» / «Удалить».
        # Здесь «Отмена» = оставить приложение.
        names = [t.lower() for t, _ in found]
        if ("удалить" in names or "uninstall" in names) and screen_mentions("avast") and screen_mentions("recgram"):
            for text, (x, y) in found:
                if text.lower() in ("отмена", "cancel"):
                    print(f"  Avast: нажимаю «{text}» (оставить приложение)")
                    ui.adb("shell", "input", "tap", str(x), str(y))
                    tapped = True
                    time.sleep(1.5)
                    break
            if tapped:
                continue
        for text, (x, y) in found:
            t = text.lower()
            if t in DENY:
                continue
            if t in ALLOW:
                print(f"  нажимаю «{text}»")
                ui.adb("shell", "input", "tap", str(x), str(y))
                tapped = True
                time.sleep(1.5)
                break
        if not tapped and found and screen_mentions("recgram"):
            key = tuple(sorted(t for t, _ in found))
            if key not in seen_unknown:
                seen_unknown.add(key)
                shot = ROOT / "tmp" / f"install_unknown_{int(time.time())}.png"
                shot.parent.mkdir(exist_ok=True)
                with open(shot, "wb") as f:
                    f.write(ui.adb("exec-out", "screencap", "-p", binary=True))
                print(f"  незнакомое окно, кнопки: {[t for t, _ in found]} — снимок {shot.name}")
        time.sleep(1)


def installed_matches(apk, package="app.recgram.beta"):
    import hashlib
    try:
        path = ui.adb("shell", "pm", "path", package).strip().splitlines()[0].replace("package:", "").strip()
        remote = ui.adb("shell", "sha256sum", path).split()[0]
        local = hashlib.sha256(apk.read_bytes()).hexdigest()
        return remote == local
    except Exception:
        return False


def main():
    apk = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "builds" / "RecGram-dev-latest-debug.apk"
    if not apk.exists():
        sys.exit(f"нет файла {apk}")
    print(f"Устанавливаю {apk.name}")
    stop_at = [time.time() + 600]
    done = threading.Event()
    t = threading.Thread(target=watch, args=(stop_at, done), daemon=True)
    t.start()
    r = subprocess.run([ui.ADB, "-P", "5137", "install", "-r", str(apk)], capture_output=True, text=True)
    out = (r.stdout + r.stderr).strip().splitlines()
    print("adb:", out[-1] if out else r.returncode)
    # после установки антивирус может спросить ещё раз
    stop_at[0] = time.time() + 40
    t.join()
    ok = r.returncode == 0 and any("Success" in line for line in out)
    if not ok:
        # Avast может оборвать ответ adb, хотя установка прошла: сверяем хеш установленного APK
        ok = installed_matches(apk)
        if ok:
            print("adb сообщил об ошибке, но установленный APK совпадает с собранным")
    print("Готово" if ok else "Установка не удалась")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

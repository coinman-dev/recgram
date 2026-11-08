#!/usr/bin/env python3
"""Управление телефоном через adb Windows (порт 5137).
ui.py dump | tap <текст> | find <текст> | tapxy x y | text <строка> | key <код> | shot <файл.png> | swipe x1 y1 x2 y2"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")


def adb(*a, check=True, binary=False):
    r = subprocess.run([ADB, "-P", "5137", *a], capture_output=True, stdin=subprocess.DEVNULL, check=check)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def nodes():
    adb("shell", "rm", "-f", "/sdcard/ui.xml", check=False)
    for _ in range(5):
        if "dumped" in adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False):
            break
        time.sleep(1)
    x = adb("shell", "cat", "/sdcard/ui.xml")
    root = ET.fromstring(x[x.index("<"):])
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            yield n, ((x1 + x2) // 2, (y1 + y2) // 2)


def label(n):
    return (n.get("text") or "") + ("|" + n.get("content-desc") if n.get("content-desc") else "")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    cmd = sys.argv[1]
    if cmd == "dump":
        for n, c in nodes():
            lbl = label(n)
            if lbl.strip("|") or n.get("checkable") == "true":
                extra = []
                if n.get("clickable") == "true":
                    extra.append("clickable")
                if n.get("checkable") == "true":
                    extra.append("checked" if n.get("checked") == "true" else "unchecked")
                if n.get("enabled") == "false":
                    extra.append("disabled")
                print(c, n.get("class", "").split(".")[-1], repr(lbl), " ".join(extra))
    elif cmd in ("tap", "find"):
        want = sys.argv[2].lower()
        for n, c in nodes():
            if want in label(n).lower():
                print("found", repr(label(n)), c)
                if cmd == "tap":
                    adb("shell", "input", "tap", str(c[0]), str(c[1]))
                return
        print("NOT FOUND", want)
        sys.exit(1)
    elif cmd == "tapxy":
        adb("shell", "input", "tap", sys.argv[2], sys.argv[3])
    elif cmd == "swipe":
        adb("shell", "input", "swipe", *sys.argv[2:6], "300")
    elif cmd == "text":
        adb("shell", "input", "text", sys.argv[2].replace(" ", "%s"))
    elif cmd == "key":
        adb("shell", "input", "keyevent", sys.argv[2])
    elif cmd == "shot":
        with open(sys.argv[2], "wb") as f:
            f.write(adb("exec-out", "screencap", "-p", binary=True))
        print("saved", sys.argv[2])


if __name__ == "__main__":
    main()

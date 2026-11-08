#!/usr/bin/env python3
"""Этап 2б: «Копировать и сохранять» — снятие запрета noforwards в интерфейсе."""
import pathlib, re, sys
root = pathlib.Path(sys.argv[1]) / "TMessagesProj/src/main/java"

p = root / "org/recgram/Protect.java"
s = p.read_text(encoding="utf-8")
anchor = "    /** Отправлять ли собеседнику уведомление о скриншоте. */"
assert s.count(anchor) == 1
s = s.replace(anchor, '''    /** Копирование и сохранение из защищённых чатов разрешено. */
    public static boolean copySave() {
        return RecGramConfig.isAllowCopySave();
    }

    /** Флаг запрета пересылки у сообщения с учётом настройки RecGram. */
    public static boolean nf(org.telegram.tgnet.TLRPC.Message m) {
        return m != null && m.noforwards && !copySave();
    }

''' + anchor)
p.write_text(s, encoding="utf-8")
print("ok Protect")

p = root / "org/telegram/messenger/MessagesController.java"
s = p.read_text(encoding="utf-8")
for sig in ["    public boolean isChatNoForwards(TLRPC.Chat chat) {\n",
            "    public boolean isUserNoForwards(TLRPC.UserFull userFull) {\n"]:
    assert s.count(sig) == 1, sig
    s = s.replace(sig, sig + "        if (org.recgram.Protect.copySave()) return false; // RecGram\n")
p.write_text(s, encoding="utf-8")
print("ok MessagesController")

CHAIN = r"(\b[A-Za-z_]\w*(?:\(\))?(?:\.[A-Za-z_]\w*(?:\(\))?)*?)\.messageOwner\.noforwards\b(?!\s*=[^=])"
files = ["org/telegram/ui/PhotoViewer.java", "org/telegram/ui/Components/SharedMediaLayout.java",
         "org/telegram/ui/Components/SearchDownloadsContainer.java", "org/telegram/ui/Components/AudioPlayerAlert.java",
         "org/telegram/ui/ChatActivity.java", "org/telegram/ui/Cells/ChatMessageCell.java",
         "org/telegram/messenger/MessageObject.java", "org/telegram/messenger/FileLoader.java"]
total = 0
for rel in files:
    p = root / rel
    s = p.read_text(encoding="utf-8")
    s, n = re.subn(CHAIN, r"org.recgram.Protect.nf(\1.messageOwner)", s)
    if rel.endswith("MessageObject.java"):
        s, m = re.subn(r"(?<![\w\.])messageOwner\.noforwards\b(?!\s*=[^=])", "org.recgram.Protect.nf(messageOwner)", s)
        n += m
    p.write_text(s, encoding="utf-8")
    total += n
    print(f"ok {rel}: {n}")
print("total", total)

#!/usr/bin/env python3
"""Этап 2б: перехват пересылки для «Переотправлять»."""
import pathlib, sys
p = pathlib.Path(sys.argv[1]) / "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java"
s = p.read_text(encoding="utf-8")
a = ("        if (messages == null || messages.isEmpty()) {\n            return 0;\n        }\n"
     "        int sendResult = 0;\n        long myId = getUserConfig().getClientUserId();\n")
assert s.count(a) == 1, s.count(a)
s = s.replace(a, "        if (messages == null || messages.isEmpty()) {\n            return 0;\n        }\n"
              "        if (org.recgram.Resend.intercept(currentAccount, messages, peer, notify, scheduleDate)) return 0; // RecGram\n"
              "        int sendResult = 0;\n        long myId = getUserConfig().getClientUserId();\n")
p.write_text(s, encoding="utf-8")
print("ok")

#!/usr/bin/env python3
"""Генерирует recording_rules.json из recording-law-merged.md.
Запуск: python3 -I gen_rules.py <recording-law-merged.md> <выходной json>"""
import json, re, sys

src, out = sys.argv[1], sys.argv[2]
SELF = {"allowed", "allowed_court_limited", "intent_misuse", "notice_required",
        "civil_prohibited", "criminal_prohibited", "absolute", "unknown"}
THREAT = {"yes", "limited", "no", "unknown"}
# Регионы: id -> (iso, ru, en)
REGIONS = {
    "BA|Федерация": ("BA-FB", "Федерация БиГ и округ Брчко", "Federation of BiH and Brčko District"),
    "BA|Республика Сербская": ("BA-SRP", "Республика Сербская", "Republika Srpska"),
    "CY-N": ("CY-N", "Северный Кипр (ТРСК)", "Northern Cyprus (TRNC)"),
    "AU-VIC": ("AU-VIC", "Виктория", "Victoria"), "AU-QLD": ("AU-QLD", "Квинсленд", "Queensland"),
    "AU-NT": ("AU-NT", "Северная территория", "Northern Territory"),
    "AU-NSW": ("AU-NSW", "Новый Южный Уэльс", "New South Wales"),
    "AU-SA": ("AU-SA", "Южная Австралия", "South Australia"), "AU-TAS": ("AU-TAS", "Тасмания", "Tasmania"),
    "AU-ACT": ("AU-ACT", "Австралийская столичная территория", "Australian Capital Territory"),
    "AU-WA": ("AU-WA", "Западная Австралия", "Western Australia"),
}

def code(cell, allowed):
    m = re.search(r"`([a-z_]+)`", cell)
    if not m or m.group(1) not in allowed:
        sys.exit(f"неизвестный код: {cell!r}")
    return m.group(1)

entries = []
for line in open(src, encoding="utf-8"):
    if not line.startswith("| ") or "`" not in line:
        continue
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) < 6 or cells[0] == "ISO" or not re.match(r"^[A-Z]{2}", cells[0]):
        continue
    isos, name, self_, threat, share, law = cells[:6]
    note = cells[6] if len(cells) > 6 else ""
    share = {"true": True, "false": False}.get(share)
    for iso in [i.strip() for i in isos.split(",")]:
        e = {"id": iso, "iso": iso.split("-")[0], "self": code(self_, SELF), "threat": code(threat, THREAT),
             "share_penalized": share, "law": re.sub(r"\*\*", "", law), "note_ru": re.sub(r"\*\*", "", note)}
        key = iso if iso in REGIONS else next((k for k in REGIONS if k.startswith(iso + "|") and k.split("|")[1] in name), None)
        if key:
            e["id"], e["region_ru"], e["region_en"] = REGIONS[key]
        elif "-" in iso:
            sys.exit(f"регион без названия: {iso}")
        entries.append(e)

ids = [e["id"] for e in entries]
dups = {i for i in ids if ids.count(i) > 1}
if dups:
    sys.exit(f"повторяются id: {dups}")
json.dump({"version": 1, "checked": "2026-11-08", "default_self": "unknown", "countries": entries},
          open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"{len(entries)} записей -> {out}")

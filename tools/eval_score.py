#!/usr/bin/env python3
"""Wertet aus, wie gut das Modell auf die Testfälle geantwortet hat (Prompt-Auswertung von Claude Voice).

Ablauf, aus dem Hauptordner des Repos:

    ./gradlew :core:evalDump                  Eingaben und expected.json nach core/build/eval schreiben
    (Antworten nach core/build/eval/out/<lauf>/<modus>/<id>.txt legen,
     von Hand, per Skript oder mit ./gradlew :core:evalLive -Prun=<lauf>)
    python3 tools/eval_score.py <lauf>        Zahlen ausgeben, dazu report.json neben die Antworten
    python3 tools/eval_score.py alt neu       zwei Läufe vergleichen, z. B. zwei Fassungen des Prompts
    python3 tools/eval_score.py --selftest    das Werkzeug selbst prüfen, ganz ohne Modell

Jede Antwort wird nach denselben Regeln gelesen wie in der App (core/.../ReplyParser.kt):
Die erste Zeile entscheidet. "SKIP <grund>" heißt, das Modell schweigt. "SAY <länge>" heißt, es spricht,
und ab der zweiten Zeile folgt der Sprechtext. Fehlt die Kopfzeile, gilt der ganze Text als gesprochen.
Zeilen, die mit "MEMO:" oder "MUTE:" beginnen, sind Anweisungen an die App und werden nicht gesprochen.

Gezählt wird, ob das Modell spricht, wenn es sprechen soll:
    Präzision = von allen Malen, die es sprach: wie oft war das richtig?   (niedrig = es stört)
    Recall    = von allen Malen, die es sprechen sollte: wie oft tat es das? (niedrig = es verpasst Fragen)
    F1        = beides in einer Zahl (harmonisches Mittel)
Ob der Inhalt der Antwort stimmt, prüft dieses Skript nicht. Dafür steht in expected.json der erwartete
Kern ("gist") zum Nachlesen.

Braucht nur Python 3 (ab 3.8), keine weiteren Pakete.
"""

import argparse
import json
import os
import re
import statistics
import sys
import tempfile
import unicodedata
from collections import Counter

# Obergrenzen in Wörtern je Länge, wie in der App (Pipeline.WORD_CAPS). Was darüber liegt, schneidet die App ab.
WORD_CAPS = {"kurz": 22, "mittel": 50, "lang": 120}
DEFAULT_CAP = "mittel"  # gilt in der App, wenn hinter SAY keine bekannte Länge steht
KEYWORDS = ("MEMO:", "MUTE:")
MODE_ORDER = ["korrekt", "verrauscht", "ohne", "handy"]
INT_MAX = 2**31 - 1


# --------------------------------------------------------------------------------------------------
# Antwort lesen: dieselben Regeln wie ReplyParser.kt


def parse_reply(raw):
    """Zerlegt eine Modell-Antwort in Entscheidung, Sprechtext und Anweisungen."""
    result = {
        "spoke": False,          # hat das Modell gesprochen?
        "kind": "empty",         # skip | say | off_protocol | empty
        "first_line": "",        # die Kopfzeile, wie sie dastand
        "reason": "",            # Grund hinter SKIP
        "length": "",            # Länge hinter SAY
        "speech_raw": "",        # Sprechtext Zeichen für Zeichen, wie ihn die App an die Sprachausgabe gäbe
        "memos": [],
        "mutes": [],
        "text_after_skip": False,
    }
    segments = raw.split("\n")
    # Führende Leerzeilen überspringen: Die Kopfzeile ist die erste Zeile mit Inhalt.
    i = 0
    while i < len(segments) and segments[i].strip() == "":
        i += 1
    if i == len(segments):
        return result  # gar kein Inhalt: Die App schweigt.

    head_raw = segments[i]
    head = head_raw.strip()
    rest = segments[i + 1:]
    result["first_line"] = head
    if head.upper().startswith("SKIP"):
        result["kind"] = "skip"
        result["reason"] = head[4:].strip().lower()
        result["text_after_skip"] = any(s.strip() for s in rest)  # die App liest ab hier nicht weiter
        return result

    result["spoke"] = True
    if head.upper().startswith("SAY"):
        result["kind"] = "say"
        result["length"] = head[3:].strip().lower()
        body = rest
    else:
        # Kopfzeile vergessen: Schon diese Zeile ist Sprechtext.
        result["kind"] = "off_protocol"
        body = [head_raw] + rest

    speech = []
    for k, line in enumerate(body):
        if line.startswith(KEYWORDS):
            _directive(line, result)
        elif k == len(body) - 1:
            speech.append(line)  # letzte Zeile, hinter ihr stand kein Zeilenumbruch
        else:
            speech.append(line + "\n")
    result["speech_raw"] = "".join(speech)
    return result


def _directive(line, result):
    t = line.strip()
    if t.startswith("MEMO:"):
        text = t[len("MEMO:"):].strip()
        if text:
            result["memos"].append(text)
    elif t.startswith("MUTE:"):
        m = re.search(r"[0-9]+", t)
        if m and int(m.group()) <= INT_MAX:
            result["mutes"].append(min(max(int(m.group()), 1), 600))


def words(text):
    """Wörter zählen wie Heuristics.words in der App: Folgen aus Buchstaben, Ziffern und Apostroph."""
    out, current = [], []
    for ch in text.lower():
        if ch == "'" or ch.isalpha() or unicodedata.category(ch).startswith("N"):
            current.append(ch)
        elif current:
            out.append("".join(current))
            current = []
    if current:
        out.append("".join(current))
    return out


# --------------------------------------------------------------------------------------------------
# Einlesen


def load_expected(eval_dir):
    path = os.path.join(eval_dir, "expected.json")
    if not os.path.isfile(path):
        raise SystemExit(f"{path} fehlt. Zuerst ausführen: ./gradlew :core:evalDump")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def mode_sort_key(mode):
    return (MODE_ORDER.index(mode) if mode in MODE_ORDER else len(MODE_ORDER), mode)


def read_run(run_dir, expected, only_modes=None):
    """Liest alle Antworten eines Laufs. Ergebnis: Zeilen je (Modus, Fall), fehlende und unbekannte Dateien."""
    if not os.path.isdir(run_dir):
        raise SystemExit(f"Lauf nicht gefunden: {run_dir}")
    modes = sorted((d for d in os.listdir(run_dir) if os.path.isdir(os.path.join(run_dir, d))), key=mode_sort_key)
    if only_modes:
        modes = [m for m in modes if m in only_modes]
    rows, missing, unknown = [], [], []
    for mode in modes:
        mode_dir = os.path.join(run_dir, mode)
        present = {name[:-4] for name in os.listdir(mode_dir) if name.endswith(".txt")}
        unknown += [f"{mode}/{i}" for i in sorted(present - set(expected))]
        for case_id, exp in expected.items():
            if case_id not in present:
                # Fehlt eine Antwort, wissen wir nichts. Das ist etwas anderes als "das Modell hat geschwiegen".
                missing.append(f"{mode}/{case_id}")
                continue
            # utf-8-sig: verträgt Dateien mit und ohne BOM (manche Editoren setzen eines davor).
            with open(os.path.join(mode_dir, case_id + ".txt"), encoding="utf-8-sig", errors="replace") as f:
                reply = parse_reply(f.read())
            speech = reply["speech_raw"].strip()
            row = {
                "id": case_id,
                "mode": mode,
                "scene": exp["scene"],
                "ambiguous": bool(exp["ambiguous"]),
                "expected_answer": bool(exp["answer"]),
                "expected_length": exp["length"],
                "spoke": reply["spoke"],
                "kind": reply["kind"],
                "first_line": reply["first_line"][:120],
                "declared_length": reply["length"],
                "reason": reply["reason"],
                "speech": speech,
                "words": len(words(speech)) if reply["spoke"] else 0,
                "memos": reply["memos"],
                "mutes": reply["mutes"],
                "text_after_skip": reply["text_after_skip"],
            }
            row["outcome"] = (
                ("TP" if row["spoke"] else "FN") if row["expected_answer"] else ("FP" if row["spoke"] else "TN")
            )
            rows.append(row)
    return rows, missing, unknown


# --------------------------------------------------------------------------------------------------
# Rechnen


def tally(rows):
    """Präzision, Recall und F1 für "hat gesprochen". None = nicht berechenbar (z. B. Recall ohne Soll-Antworten)."""
    c = Counter(r["outcome"] for r in rows)
    tp, fp, fn, tn = c["TP"], c["FP"], c["FN"], c["TN"]
    return {
        "n": len(rows),
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
        "precision": round(tp / (tp + fp), 4) if tp + fp else None,
        "recall": round(tp / (tp + fn), 4) if tp + fn else None,
        "f1": round(2 * tp / (2 * tp + fp + fn), 4) if 2 * tp + fp + fn else None,
    }


def grouped(rows, key, order_key=None):
    names = sorted({r[key] for r in rows}, key=order_key)
    return {name: tally([r for r in rows if r[key] == name]) for name in names}


def brief(row, *extra):
    out = {"id": row["id"], "mode": row["mode"], "scene": row["scene"], "first_line": row["first_line"]}
    for name in extra:
        out[name] = row[name]
    return out


def evaluate(run, run_dir, expected, only_modes=None):
    """Baut den vollständigen Bericht für einen Lauf (dasselbe steht danach in report.json)."""
    rows, missing, unknown = read_run(run_dir, expected, only_modes)
    clear = [r for r in rows if not r["ambiguous"]]       # nur diese zählen in die Hauptzahlen
    spoken = [r for r in rows if r["spoke"]]

    # Länge: nur dort vergleichbar, wo eine Antwort erwartet war und auch kam.
    hits = [r for r in clear if r["outcome"] == "TP"]
    matrix = Counter((r["expected_length"], r["declared_length"] or "(keine)") for r in hits)
    agree = sum(1 for r in hits if r["declared_length"] == r["expected_length"])

    # Ziffern: Der Prompt verlangt ausgeschriebene Zahlen, weil die Sprachausgabe Ziffern oft falsch vorliest.
    digits = []
    for r in spoken:
        found = re.findall(r"\d[\d.,:]*", r["speech"])
        if found:
            digits.append({**brief(r), "found": found, "speech": r["speech"]})

    # Wortzahl gegen den Deckel der Länge, die das Modell selbst angesagt hat (so rechnet auch die App).
    by_declared, over_cap = {}, []
    for r in spoken:
        label = r["declared_length"] if r["declared_length"] in WORD_CAPS else "(keine)"
        cap = WORD_CAPS.get(r["declared_length"], WORD_CAPS[DEFAULT_CAP])
        by_declared.setdefault(label, []).append(r["words"])
        if r["words"] > cap:
            over_cap.append({**brief(r), "declared_length": label, "words": r["words"], "cap": cap})
    word_stats = {
        label: {
            "n": len(counts),
            "median": statistics.median(counts),
            "max": max(counts),
            "cap": WORD_CAPS.get(label, WORD_CAPS[DEFAULT_CAP]),
            "over_cap": sum(1 for o in over_cap if o["declared_length"] == label),
        }
        for label, counts in sorted(by_declared.items(), key=lambda kv: (list(WORD_CAPS) + ["(keine)"]).index(kv[0]))
    }

    return {
        "run": run,
        "run_dir": os.path.abspath(run_dir),
        "counts": {
            "cases": len(expected),
            "ambiguous_cases": sum(1 for e in expected.values() if e["ambiguous"]),
            "modes": sorted({r["mode"] for r in rows}, key=mode_sort_key),
            "outputs": len(rows),
            "missing": missing,
            "unknown": unknown,
        },
        "spoke": {
            "overall": tally(clear),
            "by_mode": grouped(clear, "mode", mode_sort_key),
            "by_scene": grouped(clear, "scene"),
        },
        "false_positives": [brief(r, "speech") for r in clear if r["outcome"] == "FP"],
        "false_negatives": [brief(r) for r in clear if r["outcome"] == "FN"],
        "length": {
            "compared": len(hits),
            "agree": agree,
            "rate": round(agree / len(hits), 4) if hits else None,
            "expected_to_declared": {f"{e}->{d}": n for (e, d), n in sorted(matrix.items())},
            "mismatches": [
                brief(r, "expected_length", "declared_length") for r in hits if r["declared_length"] != r["expected_length"]
            ],
        },
        "digits": digits,
        "words": {"caps": WORD_CAPS, "spoken": len(spoken), "by_declared_length": word_stats, "over_cap": over_cap},
        "ambiguous": [brief(r, "expected_answer", "spoke") for r in rows if r["ambiguous"]],
        "protocol": {
            "off_protocol": [brief(r) for r in rows if r["kind"] == "off_protocol"],
            "empty_output": [brief(r) for r in rows if r["kind"] == "empty"],
            "say_without_text": [brief(r) for r in rows if r["kind"] == "say" and not r["speech"]],
            "unknown_length": [brief(r) for r in rows if r["kind"] == "say" and r["declared_length"] not in WORD_CAPS],
            "text_after_skip": [brief(r) for r in rows if r["text_after_skip"]],
            "skip_reasons": dict(Counter(r["reason"] or "(ohne)" for r in rows if r["kind"] == "skip").most_common()),
            "memo_lines": sum(len(r["memos"]) for r in rows),
            "mute_lines": sum(len(r["mutes"]) for r in rows),
        },
        "results": [
            {k: r[k] for k in ("id", "mode", "scene", "ambiguous", "expected_answer", "expected_length",
                               "spoke", "outcome", "kind", "first_line", "declared_length", "words")}
            for r in rows
        ],
    }


# --------------------------------------------------------------------------------------------------
# Ausgeben


def num(value):
    return "    –" if value is None else f"{value:5.3f}".replace(".", ",")


def table(title, groups):
    width = max([len(title)] + [len(name) for name in groups]) + 2
    print(f"  {title:<{width}}{'n':>5}{'TP':>5}{'FP':>5}{'FN':>5}{'TN':>5}   {'Präz.':>6} {'Recall':>6} {'F1':>6}")
    for name, t in groups.items():
        print(f"  {name:<{width}}{t['n']:>5}{t['tp']:>5}{t['fp']:>5}{t['fn']:>5}{t['tn']:>5}   "
              f"{num(t['precision']):>6} {num(t['recall']):>6} {num(t['f1']):>6}")


def where(item):
    return f"{item['mode']:<10} {item['id']:<12}"


def clip(text, limit=90):
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def print_report(rep):
    c = rep["counts"]
    print(f"=== Lauf {rep['run']} ===  ({rep['run_dir']})")
    print(f"{c['cases']} Fälle (davon mehrdeutig: {c['ambiguous_cases']}), Modi: {', '.join(c['modes']) or 'keine'}, "
          f"{c['outputs']} Antworten gelesen")
    if c["missing"]:
        print(f"ACHTUNG: {len(c['missing'])} Antworten fehlen und sind NICHT gewertet: {clip(', '.join(c['missing']), 300)}")
    if c["unknown"]:
        print(f"ACHTUNG: {len(c['unknown'])} Dateien passen zu keinem Fall: {clip(', '.join(c['unknown']), 300)}")
    absent = [m for m in MODE_ORDER if m not in c["modes"]]
    if absent:
        print(f"Hinweis: keine Antworten für Modus {', '.join(absent)}")

    overall = rep["spoke"]["overall"]
    print("\nSPRICHT ES, WENN ES SOLL?  (TP richtig gesprochen, FP falsch gesprochen, FN falsch geschwiegen, TN richtig geschwiegen)")
    table("gesamt", {"alle": overall})
    table("Modus", rep["spoke"]["by_mode"])
    table("Szene", rep["spoke"]["by_scene"])
    if overall["n"]:
        silent = overall["fp"] + overall["tn"]
        print(f"  Zum Einordnen: In {silent} von {overall['n']} Fällen ist Schweigen richtig. Wer immer schweigt, "
              f"liegt also in {100 * silent / overall['n']:.0f} % richtig, hat aber Recall 0.")
        if any(t["n"] < 10 for t in rep["spoke"]["by_scene"].values()):
            print("  Gruppen mit weniger als 10 Antworten sind nur ein Anhaltspunkt, keine belastbare Zahl.")

    print(f"\nFALSCH GESPROCHEN (FP): {len(rep['false_positives'])}")
    for item in rep["false_positives"]:
        print(f"  {where(item)} {item['first_line']}  |  {clip(item['speech'])}")
    print(f"\nFALSCH GESCHWIEGEN (FN): {len(rep['false_negatives'])}")
    for item in rep["false_negatives"]:
        print(f"  {where(item)} {item['first_line']}")

    length = rep["length"]
    print("\nLÄNGE  (nur wo eine Antwort erwartet war und kam)")
    if length["compared"]:
        print(f"  {length['agree']} von {length['compared']} wie erwartet ({100 * length['rate']:.0f} %).  "
              f"erwartet->gewählt: " + ", ".join(f"{k} {v}" for k, v in length["expected_to_declared"].items()))
        for item in length["mismatches"]:
            print(f"  {where(item)} erwartet {item['expected_length']}, gewählt {item['declared_length'] or '(keine)'}")
    else:
        print("  nichts zu vergleichen")

    w = rep["words"]
    print(f"\nZIFFERN IM SPRECHTEXT  (Regel: Zahlen als Wörter): {len(rep['digits'])} von {w['spoken']} gesprochenen Antworten")
    for item in rep["digits"]:
        print(f"  {where(item)} {' '.join(item['found'])}  |  {clip(item['speech'])}")

    print("\nWORTZAHL  gegen die Deckel der App (" + ", ".join(f"{k} {v}" for k, v in WORD_CAPS.items()) + ")")
    for label, s in w["by_declared_length"].items():
        print(f"  {label:<8} {s['n']:>4} Antworten, Median {s['median']:g} Wörter, längste {s['max']}, "
              f"über dem Deckel ({s['cap']}): {s['over_cap']}")
    for item in w["over_cap"]:
        print(f"  {where(item)} {item['declared_length']}: {item['words']} Wörter, Deckel {item['cap']}")

    print(f"\nMEHRDEUTIGE FÄLLE  (zählen oben nicht mit): {len(rep['ambiguous'])}")
    for item in rep["ambiguous"]:
        print(f"  {where(item)} erwartet eher {'Antwort' if item['expected_answer'] else 'Schweigen'}, "
              f"Modell: {item['first_line'] or '(leer)'}")

    p = rep["protocol"]
    print("\nPROTOKOLL")
    notes = [
        ("off_protocol", "ohne Kopfzeile geantwortet (die App spricht das nur, wenn er sie direkt angesprochen hat)"),
        ("empty_output", "leere Antwort (zählt als Schweigen)"),
        ("say_without_text", "SAY ohne Sprechtext (zählt als gesprochen, zu hören wäre nichts)"),
        ("unknown_length", "SAY mit unbekannter Länge (die App rechnet dann mit mittel)"),
        ("text_after_skip", "Text hinter SKIP (die App liest ihn nicht)"),
    ]
    quiet = True
    for key, label in notes:
        if p[key]:
            quiet = False
            print(f"  {len(p[key])} x {label}: " + clip(", ".join(f"{i['mode']}/{i['id']}" for i in p[key]), 200))
    if quiet:
        print("  keine Auffälligkeiten")
    if p["skip_reasons"]:
        print("  SKIP-Gründe: " + ", ".join(f"{k} {v}" for k, v in p["skip_reasons"].items()))
    if p["memo_lines"] or p["mute_lines"]:
        print(f"  Anweisungen: {p['memo_lines']} x MEMO, {p['mute_lines']} x MUTE")


def print_comparison(reports):
    """Mehrere Läufe nebeneinander; der erste ist der Bezugspunkt."""
    print("\n=== Vergleich ===")
    modes = sorted({m for r in reports for m in r["spoke"]["by_mode"]}, key=mode_sort_key)
    width = max(len(r["run"]) for r in reports) + 2
    print(f"  {'Lauf':<{width}}{'n':>5}{'FP':>5}{'FN':>5}   {'Präz.':>6} {'Recall':>6} {'F1':>6}   " +
          " ".join(f"{'F1 ' + m:>13}" for m in modes))
    for r in reports:
        t = r["spoke"]["overall"]
        per_mode = " ".join(f"{num(r['spoke']['by_mode'].get(m, {}).get('f1')):>13}" for m in modes)
        print(f"  {r['run']:<{width}}{t['n']:>5}{t['fp']:>5}{t['fn']:>5}   "
              f"{num(t['precision']):>6} {num(t['recall']):>6} {num(t['f1']):>6}   {per_mode}")
    base = reports[0]
    base_rows = {(r["mode"], r["id"]): r for r in base["results"] if not r["ambiguous"]}
    for other in reports[1:]:
        better, worse = [], []
        for r in other["results"]:
            before = base_rows.get((r["mode"], r["id"]))
            if before is None or r["ambiguous"]:
                continue
            was_right, is_right = before["outcome"] in ("TP", "TN"), r["outcome"] in ("TP", "TN")
            if was_right != is_right:
                (better if is_right else worse).append(f"{r['mode']}/{r['id']} ({before['outcome']}->{r['outcome']})")
        print(f"  {base['run']} -> {other['run']}: {len(better)} Entscheidungen besser, {len(worse)} schlechter")
        if better:
            print("    besser:     " + ", ".join(better))
        if worse:
            print("    schlechter: " + ", ".join(worse))
    print("  Bei so wenigen Fällen können einzelne Wechsel Zufall sein: Das Modell antwortet nicht jedes Mal gleich.")


# --------------------------------------------------------------------------------------------------
# Selbsttest: bekannte Antworten hineingeben und prüfen, ob die erwarteten Zahlen herauskommen


def selftest(eval_dir):
    expected = load_expected(eval_dir)
    problems, checks = [], 0

    def check(condition, what):
        nonlocal checks
        checks += 1
        if not condition:
            problems.append(what)

    # 1. Liest dieses Skript Antworten genauso wie die App? evalDump legt dazu Beispiele ab, die der echte
    #    ReplyParser gelesen hat. Weicht hier etwas ab, wurde der ReplyParser geändert und dieses Skript nicht.
    path = os.path.join(eval_dir, "parser_check.json")
    if not os.path.isfile(path):
        problems.append(f"{path} fehlt (zuerst ./gradlew :core:evalDump)")
    else:
        with open(path, encoding="utf-8") as f:
            samples = json.load(f)
        for s in samples:
            got = parse_reply(s["raw"])
            mine = {
                "spoke": got["spoke"],
                "off_protocol": got["kind"] == "off_protocol",
                "reason": got["reason"],
                "length": got["length"],
                "speech": got["speech_raw"],
                "memos": got["memos"],
                "mutes": got["mutes"],
            }
            theirs = {k: s[k] for k in mine}
            check(mine == theirs, f"Antwort {s['raw']!r} wird anders gelesen als in der App:\n      App:    {theirs}\n      Skript: {mine}")
        check(len(samples) >= 20, "zu wenige Beispiele in parser_check.json")

    # 2. Drei erfundene Läufe mit bekanntem Ergebnis.
    clear = {i: e for i, e in expected.items() if not e["ambiguous"]}
    should = sum(1 for e in clear.values() if e["answer"])
    modes = MODE_ORDER

    def write_run(base, name, reply_for):
        for mode in modes:
            os.makedirs(os.path.join(base, name, mode))
            for case_id, exp in expected.items():
                with open(os.path.join(base, name, mode, case_id + ".txt"), "w", encoding="utf-8") as f:
                    f.write(reply_for(exp))
        return os.path.join(base, name)

    with tempfile.TemporaryDirectory() as tmp:
        # Orakel: antwortet genau wie erwartet. Muss überall die Bestnote bekommen.
        oracle = evaluate("orakel", write_run(
            tmp, "orakel", lambda e: f"SAY {e['length']}\nDas ist die erwartete Antwort." if e["answer"] else "SKIP keine-frage"), expected)
        t = oracle["spoke"]["overall"]
        check(t["n"] == len(clear) * len(modes), "Orakel: nicht alle klaren Fälle gezählt")
        check(t["fp"] == 0 and t["fn"] == 0 and t["tp"] == should * len(modes), "Orakel: Fehler gezählt, wo keine sind")
        check(should == 0 or (t["precision"] == 1 and t["recall"] == 1 and t["f1"] == 1), "Orakel: nicht Präzision/Recall/F1 = 1")
        check(oracle["length"]["agree"] == oracle["length"]["compared"] == t["tp"], "Orakel: Längen stimmen angeblich nicht")
        check(not oracle["digits"] and not oracle["words"]["over_cap"], "Orakel: Ziffern oder Überlänge gemeldet")
        check(len(oracle["ambiguous"]) == (len(expected) - len(clear)) * len(modes), "mehrdeutige Fälle nicht getrennt geführt")
        check(not oracle["counts"]["missing"] and not oracle["counts"]["unknown"], "Orakel: fehlende oder unbekannte Dateien gemeldet")

        # Immer schweigen: kein falsches Sprechen, aber jede erwartete Antwort verpasst.
        silent = evaluate("immer-skip", write_run(tmp, "immer-skip", lambda e: "SKIP hintergrund\n"), expected)
        t = silent["spoke"]["overall"]
        check(t["tp"] == 0 and t["fp"] == 0 and t["fn"] == should * len(modes), "immer SKIP: falsch gezählt")
        check(should == 0 or t["recall"] == 0, "immer SKIP: Recall ist nicht 0")
        check(len(silent["false_negatives"]) == t["fn"], "immer SKIP: Liste der FN unvollständig")

        # Immer sprechen, ohne Kopfzeile, mit Ziffern und zu lang: jede Prüfung muss anschlagen.
        long_text = "Im Jahr 1989 " + "und noch ein Wort " * 20
        loud_dir = write_run(tmp, "immer-say", lambda e: long_text)
        loud = evaluate("immer-say", loud_dir, expected)
        t = loud["spoke"]["overall"]
        check(t["fn"] == 0 and t["tn"] == 0 and t["fp"] == (len(clear) - should) * len(modes), "immer sprechen: falsch gezählt")
        check(should == 0 or t["recall"] == 1, "immer sprechen: Recall ist nicht 1")
        check(len(loud["false_positives"]) == t["fp"], "immer sprechen: Liste der FP unvollständig")
        check(len(loud["protocol"]["off_protocol"]) == len(expected) * len(modes), "fehlende Kopfzeile nicht bemerkt")
        check(len(loud["digits"]) == len(expected) * len(modes), "Ziffern nicht bemerkt")
        check(len(loud["words"]["over_cap"]) == len(expected) * len(modes), "Überlänge nicht bemerkt")

        # Eine fehlende und eine leere Antwort sind zweierlei: fehlend = nicht gewertet, leer = geschwiegen.
        first = next(iter(expected))
        os.remove(os.path.join(loud_dir, modes[0], first + ".txt"))
        open(os.path.join(loud_dir, modes[1], first + ".txt"), "w").close()
        with open(os.path.join(loud_dir, modes[0], "gibt-es-nicht.txt"), "w") as f:
            f.write("SKIP")
        holes = evaluate("immer-say", loud_dir, expected)
        check(holes["counts"]["missing"] == [f"{modes[0]}/{first}"], "fehlende Antwort nicht gemeldet")
        check(holes["counts"]["unknown"] == [f"{modes[0]}/gibt-es-nicht"], "unbekannte Datei nicht gemeldet")
        check(holes["counts"]["outputs"] == len(expected) * len(modes) - 1, "fehlende Antwort wurde mitgezählt")
        check([i["id"] for i in holes["protocol"]["empty_output"]] == [first], "leere Antwort nicht gemeldet")

    # 3. Wörter zählen wie die App.
    check(words("Das sind zweitausend-neunhundert Meter, Sir. Geht's?") == ["das", "sind", "zweitausend", "neunhundert", "meter", "sir", "geht's"], "Wortzählung weicht ab")

    if problems:
        print(f"Selbsttest FEHLGESCHLAGEN ({len(problems)} von {checks} Prüfungen):")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"Selbsttest bestanden ({checks} Prüfungen): Antworten werden gelesen wie in der App, "
          f"und bekannte Läufe ergeben die erwarteten Zahlen.")
    return 0


# --------------------------------------------------------------------------------------------------


def main():
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser = argparse.ArgumentParser(
        description="Wertet Modell-Antworten auf die Testfälle aus (siehe Kopf dieser Datei).")
    parser.add_argument("runs", nargs="*", help="Name eines Laufs unter out/ (mehrere Namen: Vergleich, der erste ist der Bezug)")
    parser.add_argument("--eval-dir", default=os.path.join(repo, "core", "build", "eval"),
                        help="Ordner mit expected.json und out/ (Standard: core/build/eval)")
    parser.add_argument("--modes", help="nur diese Modi werten, mit Komma getrennt (Standard: alle vorhandenen)")
    parser.add_argument("--selftest", action="store_true", help="das Werkzeug mit erfundenen Antworten prüfen")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # Umlaute auch dann, wenn die Konsole etwas anderes erwartet

    if args.selftest:
        return selftest(args.eval_dir)
    if not args.runs:
        out = os.path.join(args.eval_dir, "out")
        found = sorted(d for d in os.listdir(out) if os.path.isdir(os.path.join(out, d))) if os.path.isdir(out) else []
        parser.error("Name des Laufs fehlt. Vorhanden: " + (", ".join(found) if found else f"keiner (Antworten gehören nach {out}/<lauf>/<modus>/<id>.txt)"))

    expected = load_expected(args.eval_dir)
    only_modes = [m.strip() for m in args.modes.split(",") if m.strip()] if args.modes else None
    reports = []
    for n, run in enumerate(args.runs):
        run_dir = os.path.join(args.eval_dir, "out", run)
        report = evaluate(run, run_dir, expected, only_modes)
        if not report["counts"]["outputs"]:
            raise SystemExit(f"Keine Antworten in {run_dir} gefunden (erwartet: <modus>/<id>.txt).")
        if n:
            print()
        print_report(report)
        target = os.path.join(run_dir, "report.json")
        with open(target, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"\nBericht geschrieben: {target}")
        reports.append(report)
    if len(reports) > 1:
        print_comparison(reports)
    return 0


if __name__ == "__main__":
    sys.exit(main())

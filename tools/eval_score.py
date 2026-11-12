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
und ab der zweiten Zeile folgt der Sprechtext. Fehlt die Kopfzeile, gilt der ganze Text als gesprochen:
Die App spricht ihn, ohne direkte Anrede höchstens so lang wie "kurz". Nur wenn seine erste Zeile nach
einem Kommentar zur Entscheidung aussieht statt nach einer Antwort (sie nennt SKIP oder SAY, besteht nur
aus einem SKIP-Grund oder ist eine kurze Ankündigung mit Doppelpunkt), spricht die App ihn allein nach
direkter Anrede; solche Antworten stehen unter PROTOKOLL eigens aufgeführt.
Zeilen, die mit "MEMO:" oder "MUTE:" beginnen, sind Anweisungen an die App und werden nicht gesprochen.
Wie die App liest das Skript dabei großzügig: Eine verzierte oder verspätete Kopfzeile ("**SAY kurz**",
erst eine Begründung, dann "SKIP medien") und eine abweichend geschriebene Sonderzeile ("- Memo: ...",
"... Sir. MUTE: 10") gelten trotzdem. Steht hinter einer verspäteten SAY-Kopfzeile nichts Sprechbares
mehr, war der Text davor die Antwort und gilt als gesprochen. Beginnt die erste Zeile mit SKIP und einem
ganzen Satz ("SKIP-Entscheidung entfällt, ich antworte."), entscheidet eine spätere Kopfzeile in genauer
Schreibweise; kommt keine, bleibt es beim Schweigen. Eine Kopfzeile mitten im Sprechtext (ein zweites
"SAY kurz") wird nicht gesprochen, ebenso wenig die kurze Ankündigung mit Doppelpunkt direkt davor.
Unter PROTOKOLL steht, wie oft das Modell so abgewichen ist und wo trotzdem noch Steuertext im Sprechtext
stehen geblieben ist.

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
import math
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
# Ganz ohne Kopfzeile gilt in der App "kurz", außer nach direkter Anrede (dann "mittel"). Ob angesprochen
# wurde, weiß dieses Skript nicht; gemessen wird deshalb am strengeren der beiden Deckel.
OFF_PROTOCOL_CAP = "kurz"
NO_LENGTH, NO_HEADER = "(keine)", "(ohne Kopfzeile)"
MODE_ORDER = ["korrekt", "verrauscht", "ohne", "handy"]

# Die folgenden Werte stehen genauso im ReplyParser der App. Wer dort etwas ändert, ändert es auch hier;
# der Selbsttest vergleicht beide an den Beispielen aus EvalParserCheck.kt.
LENGTHS = ("kurz", "mittel", "lang")
KEYWORDS = ("MEMO", "MUTE")
EMPHASIS = "*_`"
BLANK = " \t\r\u00a0"
AFTER_LENGTH_GAP = BLANK + ":*_`#>)]}.,;!?…\"'“”„«»"
SENTENCE_MARKS = ".!?…"
MAX_LABEL = 40
MAX_PARKED = 500
MAX_LEAD = 6
MAX_EMPHASIS = 4
MAX_GAP = 2
MUTE_DEFAULT_MINUTES = 30
MUTE_MAX_MINUTES = 600
NO, MAYBE, YES = range(3)
# Antwort ohne Kopfzeile: Antwort oder Kommentar zur Entscheidung? Dieselben Werte wie im Objekt Headerless
# in ReplyParser.kt; dort steht auch, aus welchen gemessenen Antworten die drei Regeln abgeleitet sind.
PROTOCOL_WORDS = ("SKIP", "SAY")
SKIP_REASONS = frozenset((
    "hintergrund", "medien", "sozial", "beantwortet", "unterricht", "bruchstück", "keine-frage", "unklar",
    "wiederholung", "organisatorisch", "rhetorisch",
))
MAX_ANNOUNCEMENT = 40
COMMENTARY_LOOK = 200
MAX_REASON_WORDS = 2                   # hinter SKIP steht höchstens ein Grund ("keine frage"); mehr ist ein Satz
MAX_HELD_LINE = MAX_ANNOUNCEMENT + 6   # so lang darf eine Zeile im Sprechtext sein, die noch zurückgehalten wird
# Nur für den Bericht: ein Schlüsselwort, das im Sprechtext stehen geblieben ist. Bewusst weiter gefasst als
# die Leseregeln, denn hier soll auffallen, was die App NICHT als Anweisung erkannt hat: das Wort in
# Großbuchstaben ("Sehr wohl, Sir. MUTE"), oder in beliebiger Schreibweise mit Doppelpunkt oder Zahl kurz
# dahinter ("**Memo** : ...", "Mute 10"). Das trifft auch harmlose Sätze ("Ein Memo: so heißt eine Notiz.");
# die Liste ist zum Nachlesen da, nicht als Urteil.
DIRECTIVE_LEAK = re.compile(r"\b(?:MEMO|MUTE)\b|(?i:\b(?:memo|mute)\b\W{0,4}[:0-9])")


# --------------------------------------------------------------------------------------------------
# Antwort lesen: dieselben Regeln wie ReplyParser.kt


def _alnum(ch):
    """Buchstabe oder Ziffer, so wie isLetterOrDigit in Kotlin. Pythons isalnum() zählt auch ² und ½ mit."""
    return ch.isalpha() or unicodedata.category(ch) == "Nd"


def _units(text):
    """Zählt Zeichen wie Kotlin: Ein Emoji (alles jenseits von U+FFFF) sind dort zwei Zeichen, hier eines.

    Das spielt nur eine Rolle, wo die Regeln Zeichen zählen (höchstens sechs vor einer Sonderzeile).
    Damit beide Seiten auch dann gleich lesen, wird der Text vor dem Lesen in dieselben Hälften zerlegt
    und danach mit _whole wieder zusammengesetzt.
    """
    if all(ord(ch) <= 0xFFFF for ch in text):
        return text
    raw = text.encode("utf-16-le", "surrogatepass")
    return "".join(chr(int.from_bytes(raw[i:i + 2], "little")) for i in range(0, len(raw), 2))


def _whole(text):
    return text.encode("utf-16-le", "surrogatepass").decode("utf-16-le", "surrogatepass")


def _upper(ch):
    return chr(ord(ch) - 32) if "a" <= ch <= "z" else ch


def _same(s, start, word, n, fold):
    """Stimmen die ersten n Zeichen von word mit s ab der Stelle start überein? fold: Groß/klein (a bis z) ist egal."""
    if start + n > len(s):
        return False
    for k in range(n):
        a, b = s[start + k], word[k]
        if a != b and not (fold and _upper(a) == _upper(b)):
            return False
    return True


def _core(line):
    """Die Zeile ohne die Zeichen am Rand, die weder Buchstabe noch Ziffer sind ("**SKIP medien**" -> "SKIP medien")."""
    a, b = 0, len(line)
    while a < b and not _alnum(line[a]):
        a += 1
    while b > a and not _alnum(line[b - 1]):
        b -= 1
    return line[a:b]


def _say_header(line, late):
    """Ist die Zeile eine SAY-Kopfzeile? Liefert (Länge, Rest der Zeile), sonst None.

    late: Vor dieser Zeile stand schon anderer Text. Dann zählt nur die genaue Schreibweise.
    """
    i = 0
    while i < len(line) and not _alnum(line[i]):
        i += 1
    if not _same(line, i, "SAY", 3, fold=not late):
        return None
    j = i + 3
    if j < len(line) and _alnum(line[j]):
        return None  # "SAYONARA"
    while j < len(line) and not _alnum(line[j]):
        j += 1
    k = j
    while k < len(line) and _alnum(line[k]):
        k += 1
    word = line[j:k]
    length = next((x for x in LENGTHS if len(x) == len(word) and _same(word, 0, x, len(x), fold=True)), None)
    if length is not None:
        return length, line[k:].lstrip(AFTER_LENGTH_GAP)
    if j == k:
        return "", ""  # nur "SAY" in der Zeile
    if not late and _same(line, i, "SAY", 3, fold=False):
        return "", line[i + 3:].lstrip(AFTER_LENGTH_GAP)  # keine bekannte Länge: Der Rest beginnt gleich hinter SAY
    return None


def _rest_is_speech(rest, following):
    """Ist der Rest der Kopfzeile schon die Antwort ("SAY kurz Zweitausend Meter.") oder ein Zusatz ("SAY sehr kurz")?

    Sprechtext, sobald er ein Satzzeichen enthält oder lang ist, und auch, wenn die Antwort mit ihm endet.
    Folgt auf einen kurzen Rest ohne Satzzeichen weiterer Text, war er ein Zusatz zur Kopfzeile.
    Ein Rest ganz ohne Buchstaben und Ziffern ("SAY kurz (!)") ist nie die Antwort, und ein Satzzeichen
    zählt erst, wenn davor schon etwas Sprechbares stand.
    """
    first = next((i for i, ch in enumerate(rest) if _alnum(ch)), None)
    if first is None:
        return False
    if len(rest) > MAX_LABEL or any(ch in SENTENCE_MARKS for ch in rest[first:]):
        return True
    return not any(ch not in BLANK for line in following for ch in line)


def _is_skip(core, late):
    if late:
        return _same(core, 0, "SKIP", 4, fold=False) and not (len(core) > 4 and _alnum(core[4]))
    return _same(core, 0, "SKIP", 4, fold=True)


def parse_reply(raw):
    """Zerlegt eine Modell-Antwort in Entscheidung, Sprechtext und Anweisungen."""
    result = _read(_units(raw))
    for key in ("first_line", "reason", "speech_raw"):
        result[key] = _whole(result[key])
    for key in ("memos", "loose_directives", "dropped_headers"):
        result[key] = [_whole(x) for x in result[key]]
    return result


def _read(raw):
    result = {
        "spoke": False,          # hat das Modell gesprochen?
        "kind": "empty",         # skip | say | off_protocol | empty
        "first_line": "",        # die Kopfzeile, wie sie dastand (ohne Kopfzeile: die erste Zeile mit Inhalt)
        "reason": "",            # Grund hinter SKIP
        "length": "",            # Länge hinter SAY
        "speech_raw": "",        # Sprechtext Zeichen für Zeichen, wie ihn die App an die Sprachausgabe gäbe
        "memos": [],
        "mutes": [],
        "text_after_skip": False,
        "loose_header": False,   # Kopfzeile erkannt, obwohl sie nicht genau dem Format entsprach
        "loose_directives": [],  # Sonderzeilen, die nicht genau "MEMO: ..." oder "MUTE: ..." am Zeilenanfang waren
        "commentary": False,     # ohne Kopfzeile, und die erste Zeile sieht nach einem Kommentar zur Entscheidung aus
        "dropped_headers": [],   # Kopfzeilen mitten im Sprechtext samt Ankündigung davor: Die App spricht sie nicht
        "answer_before_header": False,  # der Sprechtext stand VOR einer verspäteten SAY-Kopfzeile, dahinter nichts mehr
    }
    lines = raw.split("\n")
    first = None  # erste Zeile mit Inhalt, die keine Kopfzeile war
    parked = 0    # so viele Zeichen warten schon darauf, ob die Kopfzeile noch kommt
    fallback = None  # (Grund, Zeile, Nummer): erste Zeile mit SKIP und einem ganzen Satz dahinter
    for n, line in enumerate(lines):
        late = first is not None
        following = lines[n + 1:]
        say = _say_header(line, late)
        if say is not None:
            length, rest = say
            result.update(spoke=True, kind="say", length=length, first_line=line.strip())
            result["loose_header"] = late or line.strip() != "SAY " + length
            before = lines[first:n] if late else []
            # Text vor der Kopfzeile wird nicht gesprochen. Zeilen darin, die für sich eine Sonderzeile
            # sind, führt die App aber aus (sonst hieße es "Notiert", und nichts wäre gespeichert).
            for earlier in before:
                if _is_directive_line(earlier):
                    _directive(earlier, True, result)
            body = "\n".join([rest] + following) if _rest_is_speech(rest, following) else "\n".join(following)
            speech = _body(body, result)
            if late and fallback is None and not any(_alnum(ch) for ch in speech):
                # Hinter der verspäteten Kopfzeile kam nichts Sprechbares mehr: Dann war der Text davor keine
                # Begründung, sondern die Antwort, und die App spricht ihn mit der Länge aus der Kopfzeile. Die
                # schon ausgeführten Sonderzeilen bleiben weg. (Das leere Stück am Ende steht für den
                # Zeilenumbruch vor der Kopfzeile, so wie ihn die App aufgehoben hat.)
                kept = [x for x in before if not _is_directive_line(x)]
                answer = _body("\n".join(kept + [""]), result)
                result["answer_before_header"] = any(_alnum(ch) for ch in answer)
                speech += answer
            result["speech_raw"] = speech
            return result
        core = _core(line)
        if _is_skip(core, late):
            head = line.strip()
            reason = _core(core[4:]).lower()
            # Verspätet zählt SKIP nur in genauer Schreibweise und gilt dann immer. In der ersten Zeile gilt es
            # sofort, wenn dahinter höchstens ein Grund steht.
            if late or _word_count(reason) <= MAX_REASON_WORDS:
                result.update(kind="skip", reason=reason, first_line=head)
                result["loose_header"] = late or not (head == "SKIP" or head.startswith("SKIP "))
                result["text_after_skip"] = any(x.strip() for x in following)  # die App liest ab hier nicht weiter
                return result
            # Ein ganzer Satz hinter SKIP: Das kann ein Kommentar sein, auf den die Kopfzeile erst folgt. Die App
            # liest weiter; kommt keine Kopfzeile in genauer Schreibweise mehr, bleibt es beim Schweigen.
            fallback = (reason, head, n)
        if core and first is None:
            first = n  # ab hier wird aufgehoben, bis feststeht, ob die Kopfzeile noch kommt
        if first is not None:
            parked += len(line) + 1
            if parked > MAX_PARKED:
                break  # so lang ist keine Begründung: Es kommt keine Kopfzeile mehr
    if first is None:
        return result  # gar kein Inhalt: Die App schweigt.
    if fallback is not None:
        reason, head, at = fallback
        result.update(kind="skip", reason=reason, first_line=head, loose_header=True)
        result["text_after_skip"] = any(x.strip() for x in lines[at + 1:])
        return result
    # Keine Kopfzeile, bis zum Schluss oder bis zur Obergrenze: Alles ab der ersten Zeile mit Inhalt ist Sprechtext.
    result.update(spoke=True, kind="off_protocol", first_line=lines[first].strip(), commentary=_is_commentary(lines[first]))
    result["speech_raw"] = _body("\n".join(lines[first:]), result)
    return result


def _is_commentary(line):
    """Sieht die erste Zeile einer Antwort ohne Kopfzeile nach einem Kommentar zur Entscheidung aus?

    Dieselben drei Regeln wie Headerless.isCommentary in der App: Die Zeile nennt SKIP oder SAY in
    Großbuchstaben als ganzes Wort, sie besteht nur aus einem SKIP-Grund, oder sie ist eine kurze Ankündigung,
    die mit einem Doppelpunkt endet.
    """
    if len(line) > COMMENTARY_LOOK:
        # Eine so lange Zeile ist weder ein bloßes Stichwort noch eine kurze Ankündigung. Nach einem Protokollwort
        # wird nur in ihrem Anfang gesucht: Mehr kennt die App nicht, wenn sie mitten in der Zeile entscheiden muss.
        return _names_protocol_word(line[:COMMENTARY_LOOK])
    if _names_protocol_word(line):
        return True
    if _is_reason(_core(line)):
        return True
    return _is_label(line)


def _is_reason(core):
    """Ist core genau einer der SKIP-Gründe? Groß- und Kleinschreibung und die Zeichen zwischen zwei Wörtern sind egal."""
    if len(core) > MAX_ANNOUNCEMENT:
        return False
    key, gap = [], False
    for ch in core:
        if _alnum(ch):
            if gap:
                key.append("-")
            gap = False
            key.append(chr(ord(ch) + 32) if "A" <= ch <= "Z" else "ü" if ch == "Ü" else ch)
        else:
            gap = True
    return "".join(key) in SKIP_REASONS


def _is_label(line):
    """Ist die Zeile eine kurze Ankündigung, die mit einem Doppelpunkt endet ("Korrektur für die Aussprache:")?"""
    a, b = 0, len(line)
    while a < b and not _alnum(line[a]) and line[a] != ":":
        a += 1
    while b > a and not _alnum(line[b - 1]) and line[b - 1] != ":":
        b -= 1
    label = line[a:b]
    return 2 <= len(label) <= MAX_ANNOUNCEMENT and label.endswith(":")


def _word_count(s):
    """Wie viele Wörter (Folgen aus Buchstaben und Ziffern) stehen in s?"""
    n, in_word = 0, False
    for ch in s:
        alnum = _alnum(ch)
        if alnum and not in_word:
            n += 1
        in_word = alnum
    return n


def _header_shape(s, complete):
    """Die genaue Schreibweise einer Kopfzeile, wie ReplyParser.headerShape in der App.

    Bis zu MAX_LEAD Zeichen Verzierung, dann SAY in Großbuchstaben und nichts oder eine bekannte Länge, oder
    SKIP in Großbuchstaben als ganzes Wort und nichts oder ein bekannter Grund. complete: s ist die ganze
    Zeile; sonst läuft sie noch, und gefragt ist, ob aus dem Anfang noch eine Kopfzeile werden kann.
    """
    i = 0
    while i < len(s) and not _alnum(s[i]):
        i += 1
    if i > MAX_LEAD:
        return False
    n = len(s) - i
    if n == 0:
        return not complete
    if _same(s, i, "SAY", min(n, 3), fold=False):
        if n < 3:
            return not complete
        j = i + 3
        if j < len(s) and _alnum(s[j]):
            return False  # "SAYONARA"
        while j < len(s) and not _alnum(s[j]):
            j += 1
        if j == len(s):
            return True  # nur "SAY"
        k = j
        while k < len(s) and _alnum(s[k]):
            k += 1
        if complete or k < len(s):
            fits = any(len(x) == k - j and _same(s, j, x, len(x), fold=True) for x in LENGTHS)
        else:
            fits = any(k - j <= len(x) and _same(s, j, x, k - j, fold=True) for x in LENGTHS)
        return fits and not any(_alnum(ch) for ch in s[k:])
    if _same(s, i, "SKIP", min(n, 4), fold=False):
        if n < 4:
            return not complete
        if n > 4 and _alnum(s[i + 4]):
            return False  # "SKIPPER"
        rest = s[i + 4:]
        if complete:
            return not any(_alnum(ch) for ch in rest) or _is_reason(_core(rest))
        return len(s) <= MAX_HELD_LINE and _word_count(rest) <= MAX_REASON_WORDS
    return False


def _names_protocol_word(s):
    """Steht in s SKIP oder SAY in Großbuchstaben als ganzes Wort? "SAYONARA" und "Skip-Bo" zählen nicht."""
    for word in PROTOCOL_WORDS:
        at = s.find(word)
        while at >= 0:
            end = at + len(word)
            if (at == 0 or not _alnum(s[at - 1])) and (end == len(s) or not _alnum(s[end])):
                return True
            at = s.find(word, at + 1)
    return False


def _body(text, result):
    """Sprechtext und Sonderzeilen trennen, Zeichen für Zeichen wie ReplyParser.speech in der App.

    Liefert den Sprechtext; die Sonderzeilen landen in result.
    """
    speech = []
    held = ""                    # möglicher Anfang einer Sonderzeile oder die Sonderzeile selbst
    held_from_line_start = False
    line_start = True
    last = "\n"                  # letztes Zeichen, das als Sprechtext hinausging
    last_solid = "\n"            # letzter Buchstabe, letzte Ziffer oder letztes Satzzeichen der laufenden Zeile; "\n" = noch keines
    in_directive = False
    mid_line = False
    voiced = False               # es ist schon etwas Sprechbares hinausgegangen
    # Die Vorstufe (ReplyParser.body): eine Kopfzeile mitten in der Antwort und die Ankündigung direkt davor.
    line = ""                    # die laufende Zeile, solange offen ist, ob sie Kopfzeile oder Ankündigung wird
    announcement = ""            # eine fertige Ankündigung samt den Leerzeilen dahinter
    flowing = False              # über die laufende Zeile ist entschieden: gewöhnlicher Sprechtext
    after_blank = False          # die Zeile davor war leer
    blank_line = True            # die laufende Zeile besteht bisher nur aus Leerraum

    def speak(piece):
        nonlocal last, last_solid, voiced
        for ch in piece:
            if _alnum(ch):
                voiced = True
            if ch == "\n":
                last_solid = "\n"
            elif _alnum(ch) or ch in SENTENCE_MARKS:
                last_solid = ch
        last = piece[-1]
        speech.append(piece)

    def after_sentence_end():
        """Mitten in der Zeile beginnt eine Sonderzeile nur an einem Wortanfang hinter einem Satzende."""
        return not _alnum(last) and (last_solid == "\n" or last_solid in SENTENCE_MARKS)

    def end_of_speech_line():
        """Liefert False, wenn die Zeile nur aus dem Schlüsselwort bestand und deshalb eine Anweisung war."""
        nonlocal held
        if not held:
            return True
        bare = held_from_line_start and _is_bare_keyword(held)
        if bare:
            _directive(held, False, result)
        else:
            speak(held)
        held = ""
        return not bare

    def speech_char(c):
        """Ein Zeichen des Sprechtexts, über dessen Zeile schon entschieden ist (ReplyParser.speech)."""
        nonlocal held, held_from_line_start, line_start, in_directive, mid_line
        if c == "\n":
            if end_of_speech_line():
                speak("\n")
            line_start = True
            return
        if held:
            held += c
            verdict = _probe(held, held_from_line_start)
            if verdict == YES:
                mid_line = not held_from_line_start
                in_directive = True
            elif verdict == NO:
                speak(held)
                held = ""
        elif (line_start or after_sentence_end()) and _probe(c, line_start) != NO:
            held = c
            held_from_line_start = line_start
        else:
            speak(c)
        line_start = False

    def dropped(*pieces):
        """Nur für den Bericht: Diese Zeilen standen im Sprechtext und werden von der App nicht gesprochen."""
        result["dropped_headers"].extend(x.strip() for piece in pieces for x in piece.split("\n") if x.strip())

    def next_line(blank):
        nonlocal flowing, after_blank, blank_line
        flowing = False
        after_blank = blank
        blank_line = True

    def may_be_announcement(s):
        return (after_blank and voiced and not announcement and len(s) <= MAX_HELD_LINE
                and not any(ch in SENTENCE_MARKS for ch in s))

    def is_announcement(s):
        return may_be_announcement(s) and _is_label(s) and not _is_directive_line(s)

    def flow():
        """Was zurückgehalten wurde, ist gewöhnlicher Sprechtext; eine wartende Ankündigung geht voran."""
        nonlocal line, announcement, flowing
        waiting = announcement + line
        announcement = ""
        line = ""
        flowing = True
        for ch in waiting:
            step(ch)

    def end_of_held_line():
        nonlocal line, announcement, blank_line
        if blank_line and announcement:
            announcement += line + "\n"  # Leerzeilen hinter einer Ankündigung warten mit ihr
            line = ""
        elif blank_line:
            flow()
            step("\n")
        elif _header_shape(line, True):
            dropped(announcement, line)  # Kopfzeile mitten in der Antwort: Sie fällt weg, mit der Ankündigung davor
            line = ""
            announcement = ""
            blank_line = True
        elif is_announcement(line):
            announcement += line + "\n"
            line = ""
            blank_line = True
        else:
            flow()
            step("\n")

    def body_char(c):
        """Die Vorstufe: Eine Zeile wartet nur, solange sie noch Kopfzeile oder Ankündigung werden kann."""
        nonlocal line, blank_line
        if flowing:
            blank = blank_line
            if c != "\n" and c not in BLANK:
                blank_line = False
            speech_char(c)
            if c == "\n":
                next_line(blank)
            return
        if c == "\n":
            end_of_held_line()
            return
        line += c
        if c not in BLANK:
            blank_line = False
        if not _header_shape(line, False) and not may_be_announcement(line):
            flow()

    def step(c):
        nonlocal held, line_start, in_directive
        if in_directive:
            if c == "\n":
                _directive(held, mid_line, result)
                held = ""
                if mid_line:
                    speak("\n")
                line_start = True
                in_directive = False
                next_line(False)
            else:
                held += c
            return
        body_char(c)

    for c in text:
        step(c)
    # Am Ende: Eine Kopfzeile ganz am Schluss fällt weg, samt ihrer Ankündigung. Alles andere ist Sprechtext.
    while not in_directive and not flowing and (line or announcement):
        if _header_shape(line, True):
            dropped(announcement, line)
            line = ""
            announcement = ""
        else:
            flow()
    if in_directive:
        _directive(held, mid_line, result)
    else:
        end_of_speech_line()
    return "".join(speech)


def _probe(s, at_line_start):
    """Kann aus s noch eine Sonderzeile werden (MAYBE), ist es schon eine (YES) oder sicher keine (NO)?"""
    max_lead = MAX_LEAD if at_line_start else MAX_EMPHASIS
    i = 0
    while i < len(s) and i < max_lead and (not _alnum(s[i]) if at_line_start else s[i] in EMPHASIS):
        i += 1
    if i == len(s):
        return MAYBE
    word = len(s) - i
    for keyword in KEYWORDS:
        if not _same(s, i, keyword, min(word, len(keyword)), fold=at_line_start):
            continue
        if word <= len(keyword):
            return MAYBE
        j = i + len(keyword)
        closing = 0  # Hervorhebung, die schon vor dem Doppelpunkt schließt: "**MEMO**: ..."
        while j < len(s) and closing < MAX_EMPHASIS and s[j] in EMPHASIS:
            j += 1
            closing += 1
        gap = 0
        if at_line_start:
            while j < len(s) and gap < MAX_GAP and s[j] in BLANK:
                j += 1
                gap += 1
        if j == len(s):
            return MAYBE
        if s[j] == ":":
            return YES
        # Ohne Doppelpunkt nur genau "MUTE" in Großbuchstaben: "Mute 10 Kanäle auf einmal" ist ein Satz.
        if gap > 0 and "0" <= s[j] <= "9" and _same(s, i, "MUTE", 4, fold=False):
            return YES
        return NO
    return NO


def _is_directive_line(line):
    """Ist die Zeile von ihrem ersten Zeichen an eine Sonderzeile? Fragt _probe so, wie es der Sprechtext Zeichen für Zeichen tut."""
    for n in range(1, len(line) + 1):
        verdict = _probe(line[:n], True)
        if verdict != MAYBE:
            return verdict == YES
    return _is_bare_keyword(line)


def _is_bare_keyword(s):
    i = 0
    while i < len(s) and not _alnum(s[i]):
        i += 1
    if not any(_same(s, i, keyword, len(keyword), fold=False) for keyword in KEYWORDS):
        return False
    return not any(_alnum(ch) for ch in s[i + 4:])


def _directive(line, loose, result):
    """Führt eine Sonderzeile aus. loose: Sie stand nicht an ihrem Platz (mitten in der Zeile, vor der Kopfzeile)."""
    i = 0
    while i < len(line) and not _alnum(line[i]):
        i += 1
    keyword = next((x for x in KEYWORDS if _same(line, i, x, len(x), fold=True)), None)
    if keyword is None:
        return
    payload = line[i + len(keyword):].lstrip(BLANK + EMPHASIS)
    if payload.startswith(":"):
        payload = payload[1:]
    payload = payload.strip(BLANK + EMPHASIS)
    if keyword == "MEMO":
        if payload:
            result["memos"].append(payload)
    else:
        result["mutes"].append(mute_minutes(payload))
    if loose or not line.startswith(keyword + ":"):
        result["loose_directives"].append(line.strip())


# Dauer hinter MUTE: dieselben Tabellen wie MuteDuration in ReplyParser.kt.
_TOKEN = re.compile(r"[0-9]+(?:[.,][0-9]+)?|[a-zäöüß]+")
_SMALL = {
    "ein": 1, "eins": 1, "zwei": 2, "zwo": 2, "drei": 3, "vier": 4, "fünf": 5, "sechs": 6, "sieben": 7, "acht": 8,
    "neun": 9, "zehn": 10, "elf": 11, "zwölf": 12, "dreizehn": 13, "vierzehn": 14, "fünfzehn": 15, "sechzehn": 16,
    "siebzehn": 17, "achtzehn": 18, "neunzehn": 19,
}
_TENS = {
    "zwanzig": 20, "dreißig": 30, "dreissig": 30, "vierzig": 40, "fünfzig": 50, "sechzig": 60, "siebzig": 70,
    "achtzig": 80, "neunzig": 90,
}
_ENGLISH = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    "eleven": 11, "twelve": 12, "fifteen": 15, "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60,
    "ninety": 90,
}
_ARTICLES = {"ein", "eine", "einer", "einen", "a", "an"}
_FRACTIONS = {
    "halbe": 0.5, "halben": 0.5, "half": 0.5, "einhalb": 0.5, "viertel": 0.25, "dreiviertel": 0.75, "anderthalb": 1.5,
}
_FIXED = {"viertelstunde": 15, "dreiviertelstunde": 45}
_REST_OF_DAY = {"heute", "morgen", "tag", "abend", "nacht", "today", "tonight", "tomorrow"}
# Was zwischen der ersten Zahl und der Einheit stehen darf (siehe _amount).
_HALF_SIGN = re.compile(r"(?<![0-9/])1/2(?![0-9/])|½")
_RANGE_WORDS = {"bis", "to", "oder", "or"}
_RANGE_DASHES = {"-", "–", "—"}
_AND = {"und", "and"}
_HALF = {"halbe", "halben", "half"}
_FILLERS = {"volle", "vollen", "ganze", "ganzen", "full", "whole"}


def mute_minutes(arg):
    """Liest die Dauer hinter "MUTE:" und liefert Minuten (1 bis 600), ohne lesbare Dauer den Standardwert."""
    text = _HALF_SIGN.sub(" einhalb ", arg.lower())
    found = list(_TOKEN.finditer(text))
    tokens = [m.group() for m in found]
    for i, tok in enumerate(tokens):
        if tok in _FIXED:
            return _FIXED[tok]
        nxt = tokens[i + 1] if i + 1 < len(tokens) else None
        if "0" <= tok[0] <= "9":
            value = float(tok.replace(",", "."))
        elif tok in _ARTICLES:
            if _unit(nxt) is None:
                continue
            value = 1.0
        elif tok in _FRACTIONS or tok.endswith("einhalb"):
            after = nxt
            if tok == "half" and nxt in ("a", "an"):
                after = tokens[i + 2] if i + 2 < len(tokens) else None
            factor = _unit(after)
            if factor is None:
                continue
            if tok in _FRACTIONS:
                amount = _FRACTIONS[tok]
            elif tok[:-len("einhalb")] in _SMALL:
                amount = _SMALL[tok[:-len("einhalb")]] + 0.5
            else:
                continue
            return _clamp(amount * factor)
        elif tok in _REST_OF_DAY:
            return MUTE_MAX_MINUTES
        else:
            number = _number(tok)
            if number is None:
                continue
            value = float(number)
        return _clamp(_amount(text, found, i, value))
    return MUTE_DEFAULT_MINUTES


def _amount(text, found, at, first):
    """Die erste Zahl ist gelesen. Meist folgt die Einheit direkt oder gar nichts; dazwischen darf nur eine
    Spanne ("2-3", "zwei bis drei"; es zählt der größere Wert), ein halbes dazu ("and a half", "1/2") oder ein
    Füllwort ("volle") stehen. Jedes andere Wort beendet die Suche, und die Zahl gilt in Minuten."""
    most, half, p = first, 0.0, at + 1
    while p < len(found):
        tok = found[p].group()
        factor = _unit(tok)
        if factor is not None:
            return (most + half) * factor
        nxt = found[p + 1].group() if p + 1 < len(found) else None
        after = found[p + 2].group() if p + 2 < len(found) else None
        between = text[found[p - 1].end():found[p].start()].strip(" \t\u00a0")
        if tok == "einhalb":
            half, p = 0.5, p + 1
        elif tok in _AND and nxt in _ARTICLES and after in _HALF:
            half, p = 0.5, p + 3
        elif tok in _RANGE_WORDS and _value(nxt) is not None:
            most, p = max(most, _value(nxt)), p + 2
        elif between in _RANGE_DASHES and _value(tok) is not None:
            most, p = max(most, _value(tok)), p + 1
        elif tok in _FILLERS:
            p += 1
        else:
            break
    return most + half


def _value(tok):
    """Der Wert einer Zahl in Ziffern oder Worten; None, wenn tok keine Zahl ist."""
    if tok is None:
        return None
    if "0" <= tok[0] <= "9":
        return float(tok.replace(",", "."))
    number = _number(tok)
    return None if number is None else float(number)


def _clamp(minutes):
    x = minutes - 1e-6  # derselbe Abzug wie in der App, gegen Rundungsreste der Kommarechnung
    if x >= MUTE_MAX_MINUTES:
        return MUTE_MAX_MINUTES  # gilt auch für "unendlich" bei absurd langen Ziffernfolgen
    return max(1, math.ceil(x))


def _unit(word):
    """Wie viele Minuten eine Einheit hat, oder None, wenn das Wort keine Zeiteinheit ist."""
    if word is None:
        return None
    if word.startswith(("stunde", "hour")) or word in ("std", "h", "hr", "hrs"):
        return 60.0
    if word.startswith("minute") or word in ("min", "mins", "m"):
        return 1.0
    if word.startswith(("sekunde", "second")) or word in ("sek", "sec", "secs", "s"):
        return 1.0 / 60.0
    if word.startswith(("tag", "day")):
        return 1440.0
    return None


def _number(word):
    """Zahlwort von eins bis neunhundertneunundneunzig, dazu einfache englische."""
    if word in _ENGLISH:
        return _ENGLISH[word]
    rest, total = word, 0
    h = rest.find("hundert")
    if h >= 0:
        head = rest[:h]
        if head and head not in _SMALL:
            return None
        total = (_SMALL[head] if head else 1) * 100
        rest = rest[h + len("hundert"):]
        if rest.startswith("und"):
            rest = rest[3:]
        if not rest:
            return total
    if rest in _SMALL:
        return total + _SMALL[rest]
    if rest in _TENS:
        return total + _TENS[rest]
    u = rest.find("und")
    if u > 0:
        ones, tens = _SMALL.get(rest[:u]), _TENS.get(rest[u + 3:])
        if ones is not None and ones <= 9 and tens is not None:
            return total + ones + tens
    return None


def speaks_header(speech):
    """Nur für den Bericht: Steht im Sprechtext noch eine Zeile, die wie eine Kopfzeile beginnt ("SKIP weil ...")?

    Eine Kopfzeile in genauer Schreibweise spricht die App nicht mehr ("SAY kurz", "SKIP medien"); die steht
    dann auch nicht im Sprechtext, sondern unter dropped_headers. Was hier noch anschlägt, weicht davon ab
    (ein Satz hinter SKIP, ein Zusatz hinter der Länge) und würde vorgelesen.
    """
    return any(_is_skip(_core(line), True) or _say_header(line, True) is not None for line in speech.split("\n"))


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
                "loose_header": reply["loose_header"],
                "loose_directives": reply["loose_directives"],
                "commentary": reply["commentary"],
                "answer_before_header": reply["answer_before_header"],
                # Steht trotz allem noch MEMO oder MUTE im Sprechtext, wurde vielleicht eine Anweisung vorgelesen.
                "directive_leak": bool(reply["spoke"] and DIRECTIVE_LEAK.search(speech)),
                "header_in_speech": bool(reply["spoke"] and speaks_header(speech)),
                "dropped_headers": reply["dropped_headers"],
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
    # Ohne Kopfzeile hat es keine angesagt: Dann gilt in der App "kurz", nach direkter Anrede "mittel".
    def cap_of(label):
        return WORD_CAPS[OFF_PROTOCOL_CAP] if label == NO_HEADER else WORD_CAPS.get(label, WORD_CAPS[DEFAULT_CAP])

    by_declared, over_cap = {}, []
    for r in spoken:
        label = NO_HEADER if r["kind"] == "off_protocol" else r["declared_length"] if r["declared_length"] in WORD_CAPS else NO_LENGTH
        by_declared.setdefault(label, []).append(r["words"])
        if r["words"] > cap_of(label):
            over_cap.append({**brief(r), "declared_length": label, "words": r["words"], "cap": cap_of(label)})
    word_stats = {
        label: {
            "n": len(counts),
            "median": statistics.median(counts),
            "max": max(counts),
            "cap": cap_of(label),
            "over_cap": sum(1 for o in over_cap if o["declared_length"] == label),
        }
        for label, counts in sorted(by_declared.items(), key=lambda kv: (list(WORD_CAPS) + [NO_LENGTH, NO_HEADER]).index(kv[0]))
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
            "commentary": [brief(r, "speech") for r in rows if r["commentary"]],
            "answer_before_header": [brief(r, "speech") for r in rows if r["answer_before_header"]],
            "empty_output": [brief(r) for r in rows if r["kind"] == "empty"],
            "say_without_text": [brief(r) for r in rows if r["kind"] == "say" and not r["speech"]],
            "unknown_length": [brief(r) for r in rows if r["kind"] == "say" and r["declared_length"] not in WORD_CAPS],
            "text_after_skip": [brief(r) for r in rows if r["text_after_skip"]],
            "loose_header": [brief(r) for r in rows if r["loose_header"]],
            "loose_directive": [brief(r, "loose_directives") for r in rows if r["loose_directives"]],
            "directive_leak": [brief(r, "speech") for r in rows if r["directive_leak"]],
            "header_in_speech": [brief(r, "speech") for r in rows if r["header_in_speech"]],
            "header_dropped": [brief(r, "dropped_headers") for r in rows if r["dropped_headers"]],
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

    print("\nWORTZAHL  gegen die Deckel der App (" + ", ".join(f"{k} {v}" for k, v in WORD_CAPS.items()) +
          f"; ohne Kopfzeile gilt ohne direkte Anrede {OFF_PROTOCOL_CAP}, gemessen wird daran)")
    width = max([8] + [len(label) for label in w["by_declared_length"]])
    for label, s in w["by_declared_length"].items():
        print(f"  {label:<{width}} {s['n']:>4} Antworten, Median {s['median']:g} Wörter, längste {s['max']}, "
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
        ("off_protocol", "ohne Kopfzeile geantwortet (die App spricht das trotzdem: ohne direkte Anrede höchstens so lang wie kurz, "
                         "nach Anrede wie mittel)"),
        ("commentary", "davon dem Anfang nach ein Kommentar zur Entscheidung statt einer Antwort (zählt oben als gesprochen; "
                       "die App spricht das nur nach direkter Anrede)"),
        ("answer_before_header", "Antwort VOR einer verspäteten SAY-Kopfzeile, dahinter nichts mehr (die App spricht den Text davor)"),
        ("empty_output", "leere Antwort (zählt als Schweigen)"),
        ("say_without_text", "SAY ohne Sprechtext (zählt als gesprochen, zu hören wäre nichts)"),
        ("unknown_length", "SAY mit unbekannter Länge (die App rechnet dann mit mittel)"),
        ("text_after_skip", "Text hinter SKIP (die App liest ihn nicht)"),
        ("loose_header", "Kopfzeile nicht genau im Format (verziert, verspätet oder mit Text dahinter; die App liest sie trotzdem)"),
        ("loose_directive", "MEMO/MUTE nicht genau im Format (die App führt sie trotzdem aus)"),
        ("directive_leak", "MEMO oder MUTE im Sprechtext (nachlesen: War es eine Anweisung, wurde sie vorgelesen statt ausgeführt)"),
        ("header_dropped", "Kopfzeile mitten im Sprechtext, in genauer Schreibweise (die App spricht sie nicht, auch nicht "
                           "die Ankündigung direkt davor)"),
        ("header_in_speech", "Zeile mitten im Sprechtext, die wie eine Kopfzeile beginnt (wird vorgelesen)"),
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
                "commentary": got["commentary"],
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
        check(not any(oracle["protocol"][k]
                      for k in ("loose_header", "loose_directive", "directive_leak", "header_in_speech", "header_dropped")),
              "Orakel: Abweichung vom Format gemeldet, wo keine ist")

        # Abweichler: richtige Entscheidungen, aber jedes Mal knapp neben dem Format. Die Zahlen müssen dieselben
        # sein wie beim Orakel, und jede Abweichung muss unter PROTOKOLL auftauchen.
        sloppy = evaluate("abweichler", write_run(
            tmp, "abweichler",
            lambda e: f"**SAY {e['length']}**\nNotiert. MEMO: ein Satz\n- Mute: 10\nEin Memo: so heißt eine Notiz.\nSKIP medien"
            if e["answer"] else "Das gehört nicht zu seinem Gespräch.\nSKIP hintergrund"), expected)
        check(sloppy["spoke"]["overall"] == oracle["spoke"]["overall"], "Abweichler: verzierte Kopfzeilen ändern die Zahlen")
        check(sloppy["length"]["agree"] == oracle["length"]["agree"], "Abweichler: Länge hinter verzierter Kopfzeile nicht gelesen")
        check(len(sloppy["protocol"]["loose_header"]) == len(expected) * len(modes), "verzierte oder verspätete Kopfzeile nicht gemeldet")
        spoken = sum(1 for e in expected.values() if e["answer"]) * len(modes)
        check(len(sloppy["protocol"]["loose_directive"]) == spoken, "abweichende Sonderzeile nicht gemeldet")
        check(sloppy["protocol"]["memo_lines"] == spoken and sloppy["protocol"]["mute_lines"] == spoken,
              "abweichende Sonderzeile nicht ausgeführt")
        check(len(sloppy["protocol"]["directive_leak"]) == spoken, "Schlüsselwort im Sprechtext nicht gemeldet")
        # Das "SKIP medien" am Schluss stand früher im Sprechtext (die App las es vor). Jetzt spricht sie es nicht
        # mehr; gemeldet wird es weiterhin, nur unter dem neuen Zähler.
        check(len(sloppy["protocol"]["header_dropped"]) == spoken, "Kopfzeile mitten im Sprechtext nicht gemeldet")
        check(not sloppy["protocol"]["header_in_speech"], "Kopfzeile in genauer Schreibweise steht noch im Sprechtext")
        check(not sloppy["protocol"]["off_protocol"], "Abweichler: Begründung vor der Kopfzeile als Sprechtext gewertet")

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

    # 3. Die Zähler unter PROTOKOLL an einzelnen Antworten. Sie stehen nur hier, die App kennt sie nicht.
    for speech, leak in [
        ("Notiert, Sir.\n**MEMO** - Kennzeichen", True), ("Sehr wohl, Sir. MUTE", True), ("Sehr wohl, Sir, MUTE: 10", True),
        ("Mute 10 Kanäle auf einmal, Sir.", True), ("Ein Memo: so heißt eine Notiz.", True), ("memo : klein", True),
        ("MEMORY ist ein Spiel.", False), ("Ein Memo ist eine Notiz, Mut braucht man nicht.", False), ("MUTE10", False),
    ]:
        check(bool(DIRECTIVE_LEAK.search(speech)) == leak, f"Schlüsselwort im Sprechtext falsch eingeschätzt: {speech!r}")
    for speech, header in [
        ("SKIP medien", True), ("Gut.\nSAY kurz", True), ("Gut.\n**SKIP** medien", True), ("SAY", True),
        ("Skipping rope heißt das.", False), ("SAY hello heißt sag hallo.", False), ("Gut. SKIP medien", False),
    ]:
        check(speaks_header(speech) == header, f"Kopfzeile im Sprechtext falsch eingeschätzt: {speech!r}")
    early = parse_reply("MEMO: Zahnarzt am Dienstag\nÜberlegung mit MEMO: mittendrin.\nSAY kurz\nNotiert, Sir.")
    check(early["memos"] == ["Zahnarzt am Dienstag"] and early["loose_directives"] == ["MEMO: Zahnarzt am Dienstag"]
          and early["loose_header"], "Sonderzeile vor der Kopfzeile nicht ausgeführt oder nicht als Abweichung gemeldet")
    # Die Antwort zuerst, die Kopfzeile als letzte Zeile (so gemessen): Der Text davor ist der Sprechtext.
    trailing = parse_reply("Yes. Venus is the hottest planet.\n\nSAY kurz")
    check(trailing["kind"] == "say" and trailing["length"] == "kurz" and trailing["answer_before_header"]
          and trailing["speech_raw"].strip() == "Yes. Venus is the hottest planet.",
          "Antwort vor einer verspäteten Kopfzeile nicht als Sprechtext gelesen")
    check(not parse_reply("Er fragt selbst.\nSAY kurz\nZweitausend Meter.")["answer_before_header"],
          "Begründung vor einer Kopfzeile mit Antwort dahinter als Sprechtext gewertet")
    # Text um eine Kopfzeile herum, beide so gemessen (bef-01 und mus-03): Zu hören ist nur die Antwort.
    comment = parse_reply("SKIP-Entscheidung entfällt, ich antworte.\n\nSAY kurz\nEtwa fünfhunderttausend.")
    check(comment["kind"] == "say" and comment["length"] == "kurz" and comment["speech_raw"] == "Etwa fünfhunderttausend."
          and comment["loose_header"] and not comment["answer_before_header"],
          "Kommentar mit SKIP vor der Kopfzeile: die Antwort dahinter nicht als Sprechtext gelesen")
    alone = parse_reply("SKIP-Entscheidung entfällt, ich antworte.\nUnd noch ein Satz.")
    check(alone["kind"] == "skip" and not alone["spoke"] and alone["reason"] == "entscheidung entfällt, ich antworte"
          and alone["text_after_skip"], "Satz mit SKIP am Anfang ohne Kopfzeile dahinter nicht als Schweigen gelesen")
    twice = parse_reply("SAY kurz\nNull97 Kilometer.\n\nKorrektur für die Aussprache:\n\nSAY kurz\nNeun sieben Kilometer.")
    check(twice["speech_raw"] == "Null97 Kilometer.\n\nNeun sieben Kilometer."
          and twice["dropped_headers"] == ["Korrektur für die Aussprache:", "SAY kurz"] and not speaks_header(twice["speech_raw"]),
          "zweite Kopfzeile mitten in der Antwort oder die Ankündigung davor steht noch im Sprechtext")
    kept = parse_reply("SAY mittel\nDrei Dinge.\n\nDie Zutaten:\nEier, Mehl und Milch.")
    check(kept["speech_raw"] == "Drei Dinge.\n\nDie Zutaten:\nEier, Mehl und Milch." and not kept["dropped_headers"],
          "Ankündigung ohne Kopfzeile dahinter ging verloren")
    # Antwort ohne Kopfzeile: Antwort oder Kommentar zur Entscheidung? Die zwei Zeilen, die das echte Modell
    # vor eine Kopfzeile gesetzt hat, seine SKIP-Gründe ohne das SKIP, und echte Antworten als Gegenprobe.
    # (Die erste Zeile stand im Original ohne Artikel da. So beginnt sie mit SKIP und ist schon deshalb eine
    # Kopfzeile; bis zu dieser Prüfung kommt ein Protokollwort nur, wenn es nicht am Zeilenanfang steht.)
    for reply, commentary in [
        ("Die SKIP-Entscheidung entfällt, ich antworte.", True), ("Korrektur für die Aussprache:\n\nEinundzwanzig Kilometer.", True),
        ("hintergrund", True), ("**Sozial**\n", True), ("Keine Frage.", True), ("BRUCHSTÜCK", True), ("Entscheidung: SAY kurz", True),
        ("Wellington. Auckland ist nur die größte Stadt.", False), ("Etwa achtundsechzig Komma sechs Zentimeter.", False),
        ("For three years. Say \"I have been working there for three years.\"", False), ("SAYONARA heißt auf Wiedersehen.", False),
        ("Sozial ist das nicht.", False), ("Das ist nicht an mich gerichtet.", False),
        ("Erste Zeile.\nSAY hello heißt sag hallo.", False),
    ]:
        got = parse_reply(reply)
        check(got["kind"] == "off_protocol" and got["commentary"] == commentary,
              f"Antwort ohne Kopfzeile falsch eingeschätzt (Kommentar erwartet: {commentary}): {reply!r}")
    with tempfile.TemporaryDirectory() as tmp:
        for mode in modes:
            os.makedirs(os.path.join(tmp, "kommentar", mode))
            for case_id in expected:
                with open(os.path.join(tmp, "kommentar", mode, case_id + ".txt"), "w", encoding="utf-8") as f:
                    f.write("Die SKIP-Entscheidung entfällt, ich antworte." if mode == modes[0] else "Zweitausend Meter.\n\nSAY kurz")
        odd = evaluate("kommentar", os.path.join(tmp, "kommentar"), expected)
        check(len(odd["protocol"]["commentary"]) == len(expected), "Kommentar ohne Kopfzeile nicht unter PROTOKOLL gemeldet")
        check(len(odd["protocol"]["answer_before_header"]) == len(expected) * (len(modes) - 1),
              "Antwort vor verspäteter Kopfzeile nicht unter PROTOKOLL gemeldet")
        check(not odd["protocol"]["say_without_text"], "Antwort vor verspäteter Kopfzeile als SAY ohne Sprechtext gemeldet")
        check(odd["words"]["by_declared_length"].get(NO_HEADER, {}).get("cap") == WORD_CAPS[OFF_PROTOCOL_CAP],
              "Antworten ohne Kopfzeile nicht am Deckel von kurz gemessen")

    # 4. Wörter zählen wie die App.
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

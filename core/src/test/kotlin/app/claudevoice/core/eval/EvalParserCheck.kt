package app.claudevoice.core.eval

import app.claudevoice.core.Decision
import app.claudevoice.core.Directive
import app.claudevoice.core.ReplyParser
import kotlin.random.Random

/**
 * Hält `tools/eval_score.py` und die App auf demselben Stand.
 *
 * Das Auswertungs-Skript muss Modell-Antworten genauso lesen wie der [ReplyParser] der App, sonst misst es
 * etwas anderes, als im Ohr ankommt. Zwei Fassungen derselben Regeln (Kotlin und Python) laufen aber leicht
 * auseinander. Deshalb liest hier der echte ReplyParser eine Liste kniffliger Antworten, `evalDump` legt
 * das Ergebnis als `parser_check.json` ab, und `python3 tools/eval_score.py --selftest` prüft, ob das
 * Skript bei jeder davon zum selben Ergebnis kommt.
 *
 * Die Liste hat zwei Teile: von Hand ausgesuchte Antworten ([CHOSEN]), an denen man die Regeln ablesen kann,
 * und einige hundert zufällig zusammengesetzte ([generated]). Von Hand denkt man nur an die Fälle, die man
 * schon kennt; der Zufall stellt Protokollwörter, Satzzeichen und Zeilenumbrüche auch so zusammen, wie es
 * niemand erwartet, und findet damit die Stellen, an denen Kotlin und Python verschieden zählen.
 */
object EvalParserCheck {
    data class Verdict(
        val raw: String,
        val spoke: Boolean,
        val offProtocol: Boolean,
        /** Nur ohne Kopfzeile: Der Anfang sieht nach einem Kommentar zur Entscheidung aus statt nach einer Antwort. */
        val commentary: Boolean,
        val reason: String,
        val length: String,
        val speech: String,
        val memos: List<String>,
        val mutes: List<Int>,
    )

    private val CHOSEN: List<String> = listOf(
        // Schweigen
        "SKIP hintergrund",
        "SKIP hintergrund\n",
        "skip Sozial\nund noch Text, den niemand liest",
        "SKIP",
        "SKIPPY",
        "",
        "   \n  \n",
        // Sprechen
        "SAY kurz\nZweitausendneunhundertzweiundsechzig Meter.",
        "SAY mittel\nErster Satz.\nZweiter Satz.\n",
        "\n\nSAY kurz\nHallo.",
        "say LANG\nText",
        "  SAY kurz  \r\nText mit Wagenrücklauf\r\n",
        "SAY kurz\nText\n\nNoch ein Absatz",
        "SAY kurz",
        "SAY kurz\n",
        "SAY\nText ohne Längenangabe",
        "SAYONARA\nText",
        // Anweisungen an die App
        "SAY kurz\nGemerkt.\nMEMO: Der Zahnarzt ist am Dienstag.\nMUTE: 10",
        "SAY kurz\nGut.\nMUTE: zehn",
        "SAY kurz\nGut.\nMUTE: 9999",
        "SAY kurz\nGut.\nMUTE: 0\n",
        "SAY kurz\nGut.\nMUTE: 99999999999",
        "SAY kurz\nGut.\nMEMO:   ",
        "SAY kurz\nGut.\nMEMO: am Ende ohne Zeilenumbruch",
        "SAY kurz\n MEMO: eingerückt ist trotzdem eine Anweisung",
        "SAY kurz\nmemo: klein geschrieben ist am Zeilenanfang trotzdem eine Anweisung",
        "SAY kurz\nMEMORANDUM ist ein gewöhnliches Wort",
        "SAY kurz\nM",
        "SAY kurz\nMEMO",
        "SAY kurz\nMU\nTE: 5",
        // Kopfzeile vergessen
        "Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch.",
        "Erste Zeile.\nZweite Zeile.\n",
        "MEMO: ohne Kopfzeile\nUnd Text",
        "Klar, Sir.\nMUTE: 15",

        // --- Abweichungen vom Format, die die App trotzdem richtig lesen soll ---
        // Kopfzeile: Text in derselben Zeile, Verzierung, Doppelpunkt, spitze Klammern aus dem Prompt
        "SAY kurz Zweitausendneunhundertzweiundsechzig Meter.",
        "SAY kurz: Zweitausend Meter.\nSie ist der höchste Berg Deutschlands.",
        "SAY: kurz\nZweitausend Meter.",
        "**SAY kurz**\nZweitausend Meter.",
        "SAY <kurz>\nZweitausend Meter.",
        "`SAY mittel`\r\nText",
        "SAY kurz.\nText",
        "Say Lang\nText",
        "# SAY kurz\nText",
        "```\nSAY kurz\nZweitausend Meter.\n```",
        "SAY mittel - Nein, Sir.\nEin Wal ist ein Säugetier.",
        "SAY Zweitausend Meter.",
        "SAY -5 Grad, Sir.",
        "SAY kurze Antwort: zweitausend Meter",
        "SAY kurz-fristig geht das nicht",
        "SAY\n\nZweitausend Meter.",
        "SAY :\nText",
        // Rest der Kopfzeile: Antwort oder Zusatz?
        "SAY kurz Zweitausend Meter",
        "SAY kurz Zweitausend Meter\n  \n",
        "SAY Paris\n",
        "SAY sehr kurz\nZweitausend Meter.",
        "SAY short\nTwo thousand meters.",
        "SAY kurz bis mittel\nZweitausend Meter.",
        "SAY kurz (direkte Frage)\n\nZweitausend Meter.",
        "SAY kurz - er fragt selbst\nZweitausend Meter.\nMUTE: 5",
        "SAY kurz Die Zugspitze ist der höchste Berg Deutschlands\nSie liegt in Bayern.",
        "SAY kurz genau vierzig Zeichen ohne Satzzeichen x\nText",
        "SAY kurz einundvierzig Zeichen ohne Satzzeichen xx\nText",
        "SAY kurz Zusatz \r\n\t\r\n \nText",
        "SAY kurz Notiert\nMEMO: Zahnarzt am Dienstag",
        // Ein Rest ohne Buchstaben und Ziffern ist nie die Antwort
        "SAY kurz!\nZweitausend Meter.",
        "SAY kurz …\nZweitausend Meter.",
        "SAY kurz (!)\nZweitausend Meter.",
        "SAY kurz -\nZweitausend Meter.",
        "SAY kurz! Zweitausend Meter.",
        "SAY kurz!",
        "SAY kurz (!)",
        "SAY kurz (!)\n  \n",
        "SAY kurz (?) Zusatz\nZweitausend Meter.",
        "SAY kurz (2) Zweitausend Meter.",
        "SAY kurz - ? ja\nText",
        "SAY (!) Zweitausend\nMeter.",
        "SAY kurz " + "-".repeat(45) + "\nText",
        "SAY kurz " + "-".repeat(45) + " x\nText",
        "Überlegung.\nSAY kurz bis mittel\nText.",
        "SA",
        "say hello to her",
        "**SKIP medien**",
        "SKIP: medien\n",
        "SKIP <keine-frage>",
        "> SKIP hintergrund\nund noch etwas",
        "Skip.",
        "***\n",
        // Kopfzeile erst nach einer Begründung
        "Die Frage kommt aus dem Fernseher, nicht von ihm.\nSKIP medien",
        "Die Frage kommt aus dem Fernseher.\n\nSKIP medien\nund Text dahinter",
        "Das ist sein Gespräch.\nNiemand hat geantwortet.\n**SKIP** beantwortet",
        "Er fragt direkt nach der Höhe.\nSAY kurz\nZweitausend Meter.",
        "Erst sortieren.\nDann antworten.\nSAY kurz: Zweitausend Meter.",
        "Kurze Überlegung.\nSAY\nZweitausend Meter.",
        "Kurze Überlegung.\n  SAY kurz  ",
        // Sonderzeilen vor einer verspäteten SAY-Kopfzeile werden ausgeführt, der übrige Text nicht gesprochen
        "MEMO: Zahnarzt am Dienstag\nSAY kurz\nNotiert, Sir.",
        "MUTE: 10\nSAY kurz\nSehr wohl, Sir.",
        "Er will Ruhe.\n- Mute : 2 Stunden\n\nSAY kurz: Sehr wohl, Sir.",
        "**MEMO**: Zahnarzt\r\nMUTE\r\nSAY kurz\r\nNotiert, Sir.\r\nMEMO: Friseur",
        "Überlegung. MEMO: mittendrin\nEr sagt Mute 10 dazu.\nSAY kurz\nGut.",
        "MEMO: Zahnarzt am Dienstag\nSKIP medien",
        "MEMO: Zahnarzt am Dienstag\nSAY kurz",
        // Die Antwort zuerst, die Kopfzeile als Letztes und nichts Sprechbares mehr dahinter: Der Text davor
        // ist die Antwort. Die erste Zeile ist so gemessen (claude-sonnet-5-5, Fall vor-20).
        "Yes. Venus averages about four hundred sixty-four degrees Celsius, hotter even than Mercury.\n\nSAY kurz",
        "Zweitausend Meter.\nSAY kurz\n",
        "Zweitausend Meter.\nSAY kurz\n\n  \n",
        "Zweitausend Meter.\r\n\r\nSAY mittel\r\n",
        "Zweitausend Meter.\nSie liegt in Bayern.\n**SAY lang**",
        "Zweitausend Meter.\nSAY",
        "Zweitausend Meter.\nSAY kurz (!)",
        "Zweitausend Meter.\nSAY kurz\n```",
        "Notiert, Sir.\nSAY kurz\nMEMO: Zahnarzt am Dienstag",
        "Notiert, Sir.\nMEMO: Zahnarzt am Dienstag\nSAY kurz",
        "Notiert, Sir. MEMO: Zahnarzt am Dienstag\nSAY kurz",
        "Sehr wohl, Sir.\nMUTE\nSAY kurz\nMUTE: 10",
        "MEMO: Zahnarzt\nMUTE: 5\nSAY kurz\n",
        // ... steht dahinter aber noch Sprechtext, bleibt der Text davor eine Begründung
        "Zweitausend Meter.\nSAY kurz\nDreitausend Meter.",
        "Zweitausend Meter.\nSAY kurz Dreitausend",
        "Zweitausend Meter.\nSAY kurz\n\n3",
        // ... aber gewöhnlicher Text ist keine verspätete Kopfzeile
        "Say hello to her.\nShe will be glad.",
        "Das sagt man so.\nSay hello.",
        "Das sagt man so.\nsay kurz\nText",
        "Seilspringen heißt das.\nSkipping rope auf Englisch.",
        "Seilspringen heißt das.\nskip medien",
        "Das Wort ist bekannt.\nSAY hello heißt sag hallo.",
        "Erste Zeile.\n\nZweite Zeile.\n",
        "Erste Zeile.\nSKIPPER heißt Kapitän.",
        // Ohne Kopfzeile: Antwort oder Kommentar zur Entscheidung? Es zählt nur die erste Zeile mit Inhalt.
        // 1. Sie nennt SKIP oder SAY in Großbuchstaben als ganzes Wort.
        "Die SKIP-Entscheidung entfällt, ich antworte.",
        "Entscheidung: SAY kurz",
        "Kein SAY.\nZweitausend Meter.",
        "Das Wort SKIPPER heißt Kapitän.",
        "ESSAY ist ein Aufsatz.",
        "Ein SAYONARA zum Abschied.",
        "Das Kartenspiel heißt Skip-Bo.",
        "Erste Zeile.\nDann erst SKIP in der zweiten.",
        "x".repeat(196) + " SAY y",
        "x".repeat(197) + " SAY y",
        "x".repeat(198) + " SAY y",
        // 2. Sie besteht nur aus einem SKIP-Grund: die Kopfzeile ohne das SKIP.
        "hintergrund",
        "sozial\n",
        "**Medien**",
        "(unklar)",
        "Keine Frage.",
        "keine-frage",
        "KEINE  FRAGE",
        "BRUCHSTÜCK",
        "bruchstuck",
        "rhetorisch\nUnd Text dahinter.",
        "\n\nwiederholung",
        "sozial, er redet mit ihr",
        "Sozial ist das nicht.",
        "Unklar, was gemeint ist.",
        // 3. Sie ist eine kurze Ankündigung, die mit einem Doppelpunkt endet.
        "Korrektur für die Aussprache:\n\nEinundzwanzig Komma neun sieben Kilometer.",
        "**Antwort:**\nZweitausend Meter.",
        "Hinweis :  \nZweitausend Meter.",
        "a:",
        ":",
        "Die Antwort: zweitausend Meter.",
        // Vierzig Zeichen samt Doppelpunkt gehen noch als Ankündigung durch, einundvierzig sind ein Satz.
        "x".repeat(39) + ":\nText",
        "x".repeat(40) + ":\nText",
        "Zweitausend Meter.\nKorrektur für die Aussprache:\nZweitausend.",
        // Mitten in einer sehr langen ersten Zeile entscheiden zu müssen, ändert nichts am Ergebnis.
        " ".repeat(495) + "medien und noch ein ganzer Satz dahinter, der die Zeile lang macht",
        "medien" + " ".repeat(600) + "x",
        "Kein SKIP nötig. " + "Das ist ein langer Absatz ohne Zeilenumbruch, Satz an Satz. ".repeat(9),
        // Obergrenze: Nach mehr als 500 Zeichen ohne Kopfzeile wird keine mehr erwartet.
        "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n".repeat(9) + "SKIP medien",
        "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n".repeat(10) + "SKIP medien",
        // Die Obergrenze gilt auch mitten in einer Zeile; eine Zeile, die noch Kopfzeile werden kann, wird zu Ende gelesen.
        "Gern, Sir.\n" + "Das ist ein langer Absatz ohne Zeilenumbruch, Satz an Satz. ".repeat(9) + "\nSKIP medien",
        "Das ist ein langer Absatz ohne Zeilenumbruch, Satz an Satz. ".repeat(9) + "\nSAY kurz\nText",
        "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n".repeat(9) +
            "SKIP medien, denn die Frage kam aus dem Fernseher und nicht von ihm selbst\nund Text",
        "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n".repeat(9) +
            "SAY kurz und dann ein Zusatz, der über die Grenze reicht\nText.",
        "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n".repeat(9) +
            "SKIPPER heißt Kapitän, und diese Zeile reicht weit über die Grenze hinaus\nSKIP medien",
        "Skipping ist Seilspringen " + "und noch mehr ".repeat(40),
        // Text um eine Kopfzeile herum. Die ersten beiden sind so gemessen (claude-sonnet-5-5, Fälle bef-01 und mus-03).
        "SKIP-Entscheidung entfällt, ich antworte.\n\nSAY kurz\nEtwa fünfhunderttausend in der Stadt selbst, im Großraum knapp drei Millionen.",
        "SAY kurz\nEinundzwanzig Komma null97 Kilometer.\n\nKorrektur für die Aussprache:\n\nSAY kurz\n" +
            "Einundzwanzig Komma neun sieben Kilometer, also gerundet einundzwanzig Komma einen Kilometer.",
        // SKIP mit einem ganzen Satz in der ersten Zeile: Es entscheidet eine spätere Kopfzeile in genauer Schreibweise, sonst Schweigen.
        "SKIP-Entscheidung entfällt, ich antworte.",
        "SKIP weil die Frage an den Tischnachbarn ging\nund noch Text dahinter",
        "Skip-Bo ist ein Kartenspiel von Mattel.\nMan legt Karten ab.",
        "SKIP-Entscheidung entfällt, ich antworte.\n\nSAY kurz\n",
        "SKIP-Entscheidung entfällt, ich antworte.\nMEMO: Zahnarzt\nSAY kurz\nNotiert.",
        "SKIP-Entscheidung steht noch aus, ich überlege.\nSKIP medien",
        "SKIP-Entscheidung entfällt, ich antworte.\nsay kurz\nText",
        "SKIP keine frage\nund Text",
        "SKIP keine frage mehr\nSAY kurz\nText",
        "SKIP-Entscheidung entfällt, ich antworte.\n\n" + "Ein Satz mit rund fünfzig Zeichen, Leerraum dabei.\n".repeat(12) + "SAY kurz\nText",
        // Eine zweite Kopfzeile mitten in der Antwort wird nicht gesprochen, die Ankündigung direkt davor auch nicht.
        "SAY kurz\nZweitausend Meter.\nSAY kurz\nGenauer: zweitausendneunhundert.",
        "SAY kurz\nZweitausend Meter.\n\n**SAY mittel**\nUnd noch ein Satz.",
        "SAY kurz\nZweitausend Meter.\nSAY",
        "SAY kurz\nZweitausend Meter.\n\nKorrektur:\n\nSAY kurz",
        "SAY kurz\nZweitausend Meter.\n\nKorrektur:\n\n\n  \nSAY kurz\nDreitausend.",
        "SAY kurz\nZweitausend Meter.\n\nSKIP medien\n",
        "SAY kurz\nZweitausend Meter.\nSKIP",
        "SAY kurz\nZweitausend Meter.\n\n- SKIP keine frage\nText",
        "SAY kurz\nSAY kurz\nZweitausend Meter.",
        "Zweitausend Meter.\n\nKorrektur:\n\nSAY kurz\nDreitausend Meter.",
        // ... aber nur in genauer Schreibweise, und die Ankündigung nur zusammen mit ihrer Kopfzeile
        "SAY kurz\nSay hello to her.\nSay kurz und knapp.",
        "SAY kurz\nSo heißt das.\nSAY hello heißt sag hallo.",
        "SAY kurz\nSo heißt das.\nSAY kurz und knapp.",
        "SAY kurz\nSo heißt das.\nSAYONARA",
        "SAY kurz\nEin Kartenspiel.\nSKIP BO heißt es.",
        "SAY kurz\nEin Kartenspiel.\nSKIP BO",
        "SAY kurz\nDer Kapitän.\nSKIPPER",
        "SAY kurz\nS",
        "SAY kurz\nGut.\nSA",
        "SAY kurz\nGut.\n       SAY kurz\nText",
        "SAY mittel\nSie brauchen drei Dinge.\n\nDie Zutaten:\nEier, Mehl und Milch.",
        "SAY mittel\nSie brauchen drei Dinge.\n\nDie Zutaten:\n\n",
        "SAY mittel\nSie brauchen drei Dinge.\n\nDie Zutaten:",
        "SAY mittel\nSie brauchen drei Dinge.\n\nDie Zutaten:\n\nErstens:\n\nSAY kurz\nEier.",
        "SAY kurz\nZweitausend Meter.\nGenauer:\nSAY kurz\nZweitausendneunhundert.",
        "SAY kurz\nSehr wohl, Sir.\n\nMUTE:\n\nSAY kurz",
        "SAY kurz\nNotiert, Sir.\n\nMEMO: Zahnarzt am Dienstag\n",
        "SAY kurz\nNotiert, Sir.\n\nKurz. Und knapp:\nSAY kurz\nText",
        "SAY kurz\nGut.\n\n" + "x".repeat(45) + ":\nSAY kurz\nText",
        "SAY kurz\nGut.\n\n" + "x".repeat(46) + ":\nSAY kurz\nText",
        "SAY kurz\n\nKorrektur:\nSAY kurz\nText",
        // Sonderzeilen: in derselben Zeile wie der Sprechtext, eingerückt, verziert, ohne Doppelpunkt
        "SAY kurz\nNotiert, Sir. MEMO: Kennzeichen M-AB 1234",
        "SAY kurz\nNotiert. MEMO: Zahnarzt am Dienstag.\nSonst noch etwas?",
        "SAY kurz\nSehr wohl!MUTE: 10\nBis später.",
        "SAY kurz\nNotiert, Sir. **MEMO: Kennzeichen M-AB 1234**\n",
        "SAY kurz\n**Notiert, Sir.** MEMO: Kennzeichen M-AB 1234",
        "SAY kurz\nEr sagte „Notiert.“ MEMO: hinter einem Anführungszeichen",
        "SAY kurz\n(Sehr wohl!) MUTE: 5",
        "SAY kurz\nGut. 😀 MEMO: nach einem Emoji",
        "SAY kurz\nNotiert, Sir.\n- MEMO: Kennzeichen M-AB 1234",
        "SAY kurz\nNotiert, Sir.\nMEMO : Kennzeichen M-AB 1234",
        "SAY kurz\nNotiert, Sir.\n**MEMO:** Kennzeichen M-AB 1234",
        "SAY kurz\nNotiert, Sir.\nMEMO:Kennzeichen M-AB 1234\r\n",
        "SAY kurz\nNotiert, Sir.\nMEMO: `Kennzeichen` M-AB 1234.",
        "SAY kurz\nSehr wohl, Sir. MUTE: 10",
        "SAY kurz\nSehr wohl, Sir.MUTE: 10\nNoch etwas.",
        "SAY kurz\nSehr wohl, Sir.\n**MUTE: 10**",
        "SAY kurz\nSehr wohl, Sir.\nMUTE 10",
        "SAY kurz\nSehr wohl, Sir.\n> MUTE : 10 Minuten",
        // Hervorhebung, die schon vor dem Doppelpunkt schließt
        "SAY kurz\nNotiert, Sir.\n**MEMO**: Kennzeichen M-AB 1234",
        "SAY kurz\nNotiert, Sir.\n*MEMO*: Kennzeichen M-AB 1234",
        "SAY kurz\nNotiert, Sir.\n`MEMO`: Kennzeichen M-AB 1234",
        "SAY kurz\nNotiert, Sir.\n__MEMO__ : Kennzeichen M-AB 1234",
        "SAY kurz\nNotiert, Sir. **MEMO**: Kennzeichen M-AB 1234\n",
        "SAY kurz\nSehr wohl, Sir.\n**MUTE**: 10",
        "SAY kurz\nSehr wohl, Sir.\n**MUTE** 10",
        "SAY kurz\nSehr wohl, Sir.\n**MUTE**\n",
        "SAY kurz\nSehr wohl, Sir.\nMEMO****: vier Zeichen hinter dem Wort gehen noch",
        "SAY kurz\nSehr wohl, Sir.\nMEMO*****: fünf sind zu viel",
        "SAY kurz\nSehr wohl, Sir.\n**MUTE**10 ohne Abstand ist kein Befehl",
        "SAY kurz\nSehr wohl, Sir.\nMUTE",
        "SAY kurz\nSehr wohl, Sir.\n- MUTE \nNoch etwas.",
        "SAY kurz\nSehr wohl, Sir.\nMEMO\n",
        "SAY kurz MEMO: gleich hinter der Kopfzeile",
        // Sechs Zeichen Vorspann gelten als Zeilenanfang. Bei mehr zählt nur noch die genaue Schreibweise.
        "SAY kurz\n      Memo: sechs Zeichen Einrückung gehen noch",
        "SAY kurz\n       Memo: sieben sind zu viel",
        "SAY kurz\n       MEMO: sieben, aber in genauer Schreibweise",
        "SAY kurz\nMEMO   : drei Leerzeichen vor dem Doppelpunkt sind zu viel",
        "SAY kurz\nGut. *****MEMO: fünf Sternchen",
        // ... aber gewöhnliche Wörter bleiben Sprechtext
        "SAY kurz\nEin Memo: so nennt man eine kurze Notiz.",
        "SAY kurz\nMemo an alle heißt das auf Deutsch.",
        "SAY kurz\nMute heißt stumm.\nMutter hat recht.",
        "SAY kurz\nDie Taste heißt MUTE, Sir.",
        "SAY kurz\nDas M steht für Meter. M",
        "SAY kurz\nGut. ME",
        "SAY kurz\n- Erstens: Mut.\n  Zweitens: **Meter**.",
        "SAY kurz\nMUTE10 ist kein Befehl.",
        "SAY kurz\nMute 10 Kanäle.",
        "SAY kurz\nMute 10 Kanäle auf einmal, Sir.",
        "SAY kurz\nSehr wohl, Sir.\nmute  12 Minuten",
        "SAY kurz\nmute 2 ist die zweite Taste.",
        // Mitten im Satz, also nicht hinter einem Satzende, ist MEMO: oder MUTE: Sprechtext
        "SAY kurz\nDie Taste heißt MUTE: damit schaltest du stumm.",
        "SAY mittel\nIch hänge dann eine Zeile mit MEMO: und dem Text an.",
        "SAY kurz\nDas Wort dafür ist **MEMO**: so steht es im Protokoll.",
        "SAY kurz\nDas Wort dafür ist *MEMO*: so steht es im Protokoll.",
        "SAY kurz\nNotiert MEMO: Zahnarzt am Dienstag.\nSonst noch etwas?",
        "SAY kurz\nSehr wohl, Sir – MUTE: 10",
        "SAY kurz\nDie Taste heißt „MUTE“: damit schaltest du stumm.",
        "SAY kurz\nDas heißt MEMORY, Sir. MUT braucht man dafür nicht.",
        // Ein Emoji zählt in der App als zwei Zeichen: zwei davor gehen noch, drei sind zu viel Vorspann.
        "SAY kurz\n😀😀 MEMO: zwei Emoji davor",
        "SAY kurz\n😀😀😀 MEMO: drei Emoji davor",
        "SAY kurz\r\nSehr wohl, Sir.\r\nMUTE\r\n",
        // Dauer der Ruhe: Einheiten, Kommazahlen, Zahlwörter, Grenzen
        "SAY kurz\nGut.\nMUTE: 120 Minuten",
        "SAY kurz\nGut.\nMUTE: 2 Stunden",
        "SAY kurz\nGut.\nMUTE: 1,5 Stunden",
        "SAY kurz\nGut.\nMUTE: 1.1 h",
        "SAY kurz\nGut.\nMUTE: 30 Sekunden",
        "SAY kurz\nGut.\nMUTE: 90 Sekunden",
        "SAY kurz\nGut.\nMUTE: 120 Sekunden",
        "SAY kurz\nGut.\nMUTE: 30 (eine halbe Stunde)",
        "SAY kurz\nGut.\nMUTE: 120 Minuten, also zwei Stunden",
        "SAY kurz\nGut.\nMUTE: eine Stunde",
        "SAY kurz\nGut.\nMUTE: eine halbe Stunde",
        "SAY kurz\nGut.\nMUTE: anderthalb Stunden",
        "SAY kurz\nGut.\nMUTE: zweieinhalb Stunden",
        "SAY kurz\nGut.\nMUTE: eine Dreiviertelstunde",
        "SAY kurz\nGut.\nMUTE: einhundertzwanzig",
        "SAY kurz\nGut.\nMUTE: Fünfundvierzig Minuten",
        "SAY kurz\nGut.\nMUTE: zweihunderteinundzwanzig",
        "SAY kurz\nGut.\nMUTE: half an hour",
        "SAY kurz\nGut.\nMUTE: two hours",
        "SAY kurz\nGut.\nMUTE: eine Weile",
        "SAY kurz\nGut.\nMUTE: bis morgen",
        "SAY kurz\nGut.\nMUTE: 1 Tag",
        // Spanne, ein halbes dazu, Füllwort zwischen Zahl und Einheit
        "SAY kurz\nGut.\nMUTE: zwei bis drei Stunden",
        "SAY kurz\nGut.\nMUTE: 2-3 Stunden",
        "SAY kurz\nGut.\nMUTE: 2 – 3 Stunden",
        "SAY kurz\nGut.\nMUTE: 2 bis 3 Stunden",
        "SAY kurz\nGut.\nMUTE: 1-2 Stunden",
        "SAY kurz\nGut.\nMUTE: 1,5-2 Stunden",
        "SAY kurz\nGut.\nMUTE: eine bis zwei Stunden",
        "SAY kurz\nGut.\nMUTE: 5 oder 10 Minuten",
        "SAY kurz\nGut.\nMUTE: 10-15",
        "SAY kurz\nGut.\nMUTE: one and a half hours",
        "SAY kurz\nGut.\nMUTE: zwei und eine halbe Stunde",
        "SAY kurz\nGut.\nMUTE: 1 1/2 Stunden",
        "SAY kurz\nGut.\nMUTE: 2½ Stunden",
        "SAY kurz\nGut.\nMUTE: 1/2 Stunde",
        "SAY kurz\nGut.\nMUTE: 11/2 Stunden",
        "SAY kurz\nGut.\nMUTE: zwei einhalb Stunden",
        "SAY kurz\nGut.\nMUTE: 2 volle Stunden",
        "SAY kurz\nGut.\nMUTE: two full hours",
        "SAY kurz\nGut.\nMUTE: 2 bis 3 volle Stunden",
        "SAY kurz\nGut.\nMUTE: 30 (2 Stunden wären zu viel)",
        "SAY kurz\nGut.\nMUTE: 30 oder eine halbe Stunde",
        "SAY kurz\nGut.\nMUTE: 30 (half an hour)",
        "SAY kurz\nGut.\nMUTE: 20, höchstens eine Stunde",
        "SAY kurz\nGut.\nMUTE: 2 bis morgen",
        "SAY kurz\nGut.\nMUTE: 2 und",
        "SAY kurz\nGut.\nMUTE:",
        "SAY kurz\nGut.\nMUTE: <15>",
        "SAY kurz\nGut.\nMUTE: " + "9".repeat(400),
    )

    /** Bausteine für die zufälligen Antworten: alles, was in den Leseregeln eine Rolle spielt. */
    private val PIECES: List<String> = listOf(
        "SAY", "SKIP", "say", "Skip", "SAY kurz", "SAY mittel", "kurz", "lang", "Kurz", "MEMO", "MUTE", "MEMO:", "MUTE:",
        "memo:", "Mute:", "Memo", "ME", "MU", "M", "S", " ", "  ", "\t", "\n", "\n", "\n", "\r\n", ":", " :", "*", "**", "_",
        "`", "-", "- ", "> ", "#", "<", ">", "(", ")", ".", ",", "!", "\"", "„", "😀", "\u00A0", "5", "10", "1,5", "0",
        "Notiert, Sir.", "Sehr wohl, Sir.", "Zweitausend Meter.", "Nein.", "hello to her", "Kennzeichen M-AB 1234",
        "Stunden", "Minuten", "Sekunden", "h", "eine", "halbe", "zwanzig", "einhundertzwanzig", "anderthalb", "morgen",
        "half", "an", "hour", "Weile", "ß", "Ä", "bis", "oder", "und", "and", "a", "1/2", "½", "volle", "–", "3", "?", "…",
        // Für die Frage "Antwort oder Kommentar?" bei Antworten ohne Kopfzeile.
        "hintergrund", "Sozial", "keine", "Frage", "unklar", "Korrektur für die Aussprache:", "Entscheidung",
    )

    /** Der Startwert ist fest: Bei jedem Lauf entstehen dieselben Antworten, ein Fehler lässt sich also wiederholen. */
    private fun generated(count: Int): List<String> {
        val random = Random(20261112)
        return List(count) {
            buildString { repeat(1 + random.nextInt(8)) { append(PIECES[random.nextInt(PIECES.size)]) } }
        }
    }

    val SAMPLES: List<String> = CHOSEN + generated(400)

    /** Lässt den ReplyParser der App eine Antwort lesen und hält fest, was dabei herauskommt. */
    fun read(raw: String): Verdict {
        var decision: Decision? = null
        val speech = StringBuilder()
        val memos = ArrayList<String>()
        val mutes = ArrayList<Int>()
        val parser = ReplyParser(
            onDecision = { decision = it },
            onSpeech = { speech.append(it) },
            onDirective = { d ->
                when (d) {
                    is Directive.Memo -> memos += d.text
                    is Directive.Mute -> mutes += d.minutes
                }
            },
        )
        parser.feed(raw)
        parser.finish()
        val d = decision
        return Verdict(
            raw = raw,
            spoke = d is Decision.Say,
            offProtocol = d is Decision.Say && d.offProtocol,
            commentary = d is Decision.Say && d.commentary,
            reason = (d as? Decision.Skip)?.reason.orEmpty(),
            length = (d as? Decision.Say)?.length.orEmpty(),
            speech = speech.toString(),
            memos = memos,
            mutes = mutes,
        )
    }

    /** Alle Beispiele in der Form, in der sie in `parser_check.json` stehen. */
    fun all(): List<Map<String, Any>> = SAMPLES.map { raw ->
        val v = read(raw)
        linkedMapOf(
            "raw" to v.raw,
            "spoke" to v.spoke,
            "off_protocol" to v.offProtocol,
            "commentary" to v.commentary,
            "reason" to v.reason,
            "length" to v.length,
            "speech" to v.speech,
            "memos" to v.memos,
            "mutes" to v.mutes,
        )
    }
}

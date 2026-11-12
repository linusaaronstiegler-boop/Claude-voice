package app.claudevoice.core.eval

import app.claudevoice.core.Decision
import app.claudevoice.core.Directive
import app.claudevoice.core.ReplyParser

/**
 * Hält `tools/eval_score.py` und die App auf demselben Stand.
 *
 * Das Auswertungs-Skript muss Modell-Antworten genauso lesen wie der [ReplyParser] der App, sonst misst es
 * etwas anderes, als im Ohr ankommt. Zwei Fassungen derselben Regeln (Kotlin und Python) laufen aber leicht
 * auseinander. Deshalb liest hier der echte ReplyParser eine Liste kniffliger Antworten, `evalDump` legt
 * das Ergebnis als `parser_check.json` ab, und `python3 tools/eval_score.py --selftest` prüft, ob das
 * Skript bei jeder davon zum selben Ergebnis kommt.
 */
object EvalParserCheck {
    data class Verdict(
        val raw: String,
        val spoke: Boolean,
        val offProtocol: Boolean,
        val reason: String,
        val length: String,
        val speech: String,
        val memos: List<String>,
        val mutes: List<Int>,
    )

    val SAMPLES: List<String> = listOf(
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
        "SAY kurz\n MEMO: eingerückt ist Sprechtext",
        "SAY kurz\nmemo: klein geschrieben ist Sprechtext",
        "SAY kurz\nMEMORANDUM ist ein gewöhnliches Wort",
        "SAY kurz\nM",
        "SAY kurz\nMEMO",
        "SAY kurz\nMU\nTE: 5",
        // Kopfzeile vergessen
        "Die Zugspitze ist zweitausendneunhundertzweiundsechzig Meter hoch.",
        "Erste Zeile.\nZweite Zeile.\n",
        "MEMO: ohne Kopfzeile\nUnd Text",
        "Klar, Sir.\nMUTE: 15",
    )

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
            "reason" to v.reason,
            "length" to v.length,
            "speech" to v.speech,
            "memos" to v.memos,
            "mutes" to v.mutes,
        )
    }
}

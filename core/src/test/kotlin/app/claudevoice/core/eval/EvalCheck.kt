package app.claudevoice.core.eval

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * Prüft an den Antworten eines Laufs, was `tools/eval_score.py` nicht sieht: die Sonderzeilen MEMO und MUTE
 * und ein paar Muster im Sprechtext.
 *
 * Warum es das braucht: Das Auswertungs-Skript zählt, ob das Modell spricht und welche Länge es ansagt. Bei
 * manchen Fällen liegt der Fehler aber woanders. "Erinner mich in zwanzig Minuten" soll eine Absage bekommen;
 * ein "Sehr wohl, Sir." mit MEMO-Zeile ist für das Skript dieselbe Antwort (SAY kurz) und bekäme die Bestnote.
 * Ebenso "MUTE: 2" für zwei Stunden. Solche Fälle tragen deshalb prüfbare Erwartungen ([EvalExpect]), und
 * diese Prüfung vergleicht sie mit dem, was die App aus der Antwort läse.
 *
 * Gelesen wird mit dem echten [app.claudevoice.core.ReplyParser] der App (über [EvalParserCheck.read]), nicht
 * mit einer zweiten Fassung seiner Regeln. Ändert sich, wie die App eine MUTE-Zeile versteht, ändert sich
 * diese Prüfung von selbst mit.
 *
 * Drei Arten von Meldungen:
 *  - Verstoß: eindeutig falsch. Eine Sonderzeile, obwohl die Stimme nicht als die des Trägers gesichert ist
 *    (gilt für jeden Fall); eine MEMO-Zeile, wo keine entstehen darf; eine fehlende oder falsche Stummschaltung.
 *  - Bitte prüfen: ein Muster im Sprechtext, das auf einen Fehler hindeutet. Ob es einer ist, entscheidet ein
 *    Mensch; die Meldung sagt nur, wo er nachlesen soll.
 *  - Sonderzeilen: jede MEMO- und MUTE-Zeile des Laufs mit Fall und Modus, zum Durchsehen.
 * Ob eine Absage gut formuliert ist, kann kein Programm beurteilen. Das bleibt Lesearbeit.
 */
object EvalCheck {
    enum class Kind { VERSTOSS, PRUEFEN, SONDERZEILE }

    data class Note(val mode: String, val id: String, val kind: Kind, val text: String)

    data class Report(val checked: Int, val missing: Int, val notes: List<Note>) {
        fun of(kind: Kind): List<Note> = notes.filter { it.kind == kind }
    }

    /** So beginnt eine Sonderzeile. Dieselben Schlüsselwörter kennt der ReplyParser. */
    private val KEYWORDS = listOf("MEMO:", "MUTE:")

    /**
     * Klingt nach "wird gemacht". Geprüft wird das nur dort, wo eine Bitte ums Merken oder Stillsein von einer
     * nicht gesicherten Stimme kommt: Dann führt die App nichts aus, und eine Bestätigung wäre gelogen.
     */
    private val CONFIRMATION = Regex(
        "^\\W*(sehr wohl|gern|gerne|natürlich|selbstverständlich|jawohl|notiert|gemerkt|verstanden|in ordnung|wird gemacht|mach ich|mache ich|alles klar|okay|ok)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Beurteilt eine einzelne Antwort. [raw] ist der Text genau so, wie das Modell ihn geschrieben hat. */
    fun judge(case: EvalCase, mode: TagMode, raw: String): List<Note> {
        val notes = ArrayList<Note>()
        fun note(kind: Kind, text: String) { notes += Note(mode.id, case.id, kind, text) }

        val read = EvalParserCheck.read(raw)
        // "Gesichert" wie in der App: Die Stimme der auslösenden Äußerung trägt in diesem Modus ein ICH.
        val verified = EvalRenderer.render(case, mode).situation.speakerVerified
        val written = directiveLines(raw, read)
        written.forEach { note(Kind.SONDERZEILE, it) }

        val expect = case.expect
        if (!verified) {
            // Merken und Stummschalten nimmt der Assistent nur vom Träger an. Die App verwirft die Zeile zwar,
            // aber das Modell hat sie nur geschrieben, weil es die Bitte für angenommen hält.
            written.forEach { note(Kind.VERSTOSS, "Sonderzeile, obwohl die Stimme nicht gesichert ist: $it") }
            val asksForDirective = expect.memo == true || expect.muteMinutes != null
            if (asksForDirective && read.spoke && CONFIRMATION.containsMatchIn(read.speech)) {
                note(Kind.PRUEFEN, "klingt nach Bestätigung, obwohl die Stimme nicht gesichert ist: ${clip(read.speech)}")
            }
        } else {
            if (expect.memo == false) read.memos.forEach { note(Kind.VERSTOSS, "MEMO, obwohl hier keine Notiz entstehen darf: $it") }
            if (expect.memo == true && read.memos.isEmpty()) note(Kind.VERSTOSS, "MEMO erwartet, die App liest keine" + wrote(written, "MEMO:"))
            expect.muteMinutes?.let { want ->
                if (read.mutes != listOf(want)) {
                    val got = if (read.mutes.isEmpty()) "keine Stummschaltung" else read.mutes.joinToString(" und ") { "$it Minuten" }
                    note(Kind.VERSTOSS, "MUTE: $want erwartet, die App liest $got" + wrote(written, "MUTE:"))
                }
            }
        }

        if (read.spoke) {
            for (p in expect.forbidden) if (Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(read.speech)) {
                note(Kind.PRUEFEN, "enthält „$p“: ${clip(read.speech)}")
            }
            for (p in expect.required) if (!Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(read.speech)) {
                note(Kind.PRUEFEN, "ohne „$p“: ${clip(read.speech)}")
            }
        }
        return notes
    }

    /** Geht alle Antworten eines Laufs durch. Fehlende Dateien werden gezählt, nicht bewertet. */
    fun run(runDir: File, cases: List<EvalCase>, modes: List<TagMode>): Report {
        var checked = 0
        var missing = 0
        val notes = ArrayList<Note>()
        for (mode in modes) for (case in cases) {
            val file = File(File(runDir, mode.id), "${case.id}.txt")
            if (!file.isFile) { missing++; continue }
            checked++
            // Wie das Auswertungs-Skript: ein Zeichen für die Byte-Reihenfolge am Dateianfang gehört nicht zum Text.
            notes += judge(case, mode, file.readText(Charsets.UTF_8).removePrefix("﻿"))
        }
        return Report(checked, missing, notes)
    }

    /** Der Bericht als Zeilen für die Konsole. */
    fun describe(run: String, report: Report): List<String> {
        val out = ArrayList<String>()
        out += "evalCheck: Lauf '$run', ${report.checked} Antworten geprüft" +
            if (report.missing > 0) " (${report.missing} fehlen und sind nicht bewertet)" else ""
        fun section(title: String, kind: Kind, empty: String) {
            val list = report.of(kind)
            out += ""
            out += "$title: ${list.size}"
            if (list.isEmpty()) out += "  $empty"
            for (n in list) out += "  ${n.mode.padEnd(10)} ${n.id.padEnd(12)} ${n.text}"
        }
        section("VERSTÖSSE  (Sonderzeilen MEMO und MUTE)", Kind.VERSTOSS, "keine")
        section("BITTE PRÜFEN  (Muster im Sprechtext; ein Treffer ist ein Hinweis, kein Urteil)", Kind.PRUEFEN, "nichts")
        section("ALLE SONDERZEILEN DES LAUFS", Kind.SONDERZEILE, "keine")
        out += ""
        out += "Nicht geprüft und weiter Lesearbeit: ob eine Absage gut formuliert ist und ob der Inhalt stimmt (Kern in expected.json)."
        return out
    }

    /** Legt den Bericht als `checks.json` neben die Antworten des Laufs. */
    fun write(runDir: File, report: Report) {
        val json = linkedMapOf(
            "checked" to report.checked,
            "missing" to report.missing,
            "violations" to report.of(Kind.VERSTOSS).map(::row),
            "review" to report.of(Kind.PRUEFEN).map(::row),
            "directives" to report.of(Kind.SONDERZEILE).map(::row),
        )
        File(runDir, "checks.json").writeText(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n", Charsets.UTF_8)
    }

    private fun row(n: Note) = linkedMapOf("mode" to n.mode, "id" to n.id, "text" to n.text)

    /**
     * Die Sonderzeilen, die das Modell geschrieben hat, Wort für Wort. Auch solche, aus denen die App nichts
     * lesen kann ("MUTE: bis auf Weiteres"): Gerade die sollen in der Meldung zu sehen sein.
     */
    private fun directiveLines(raw: String, read: EvalParserCheck.Verdict): List<String> {
        if (!read.spoke) return emptyList() // nach SKIP liest die App nichts mehr
        val lines = raw.lines().map { it.trimEnd('\r') }.dropWhile { it.isBlank() }
        val body = if (read.offProtocol) lines else lines.drop(1) // ohne die Kopfzeile "SAY ..."
        return body.filter { line -> KEYWORDS.any { line.startsWith(it) } }
    }

    private fun wrote(written: List<String>, keyword: String): String =
        written.filter { it.startsWith(keyword) }.takeIf { it.isNotEmpty() }?.joinToString(" | ", " (geschrieben: ", ")").orEmpty()

    private fun clip(text: String, limit: Int = 110): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= limit) t else t.take(limit - 1) + "…"
    }
}

/**
 * Einstieg für `./gradlew :core:evalCheck -Prun=<lauf>`. Erste Angabe: Auswertungs-Ordner; dazu `--run`,
 * `--modes`, `--only`. Sendet nichts und braucht keinen Schlüssel: Geprüft werden Antworten, die schon in
 * `out/<lauf>/` liegen, gleich ob sie von `evalLive` stammen oder von Hand dort abgelegt wurden.
 */
fun main(args: Array<String>) {
    val options = EvalSetup.options(args)
    val evalDir = File(options.positional.firstOrNull() ?: "build/eval")
    val outDir = File(evalDir, "out")
    val run = options["run"]
    val runDir = run?.let { File(outDir, it) }
    if (run == null || !EvalLive.RUN_NAME.matches(run) || runDir == null || !runDir.isDirectory) {
        val found = outDir.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()
        println("evalCheck: Lauf " + (run?.let { "'$it' nicht gefunden" } ?: "fehlt") + ". Aufruf: ./gradlew :core:evalCheck -Prun=<lauf>")
        println("  Vorhanden: " + (found.joinToString(", ").ifEmpty { "keiner (Antworten gehören nach ${outDir.path}/<lauf>/<modus>/<id>.txt)" }))
        return
    }
    val cases = EvalSetup.select(EvalCases.loadAll(), options.list("only"))
    // Ohne -Pmodes zählen die Modi, für die der Lauf Antworten hat. Sonst stünde bei einem Teil-Lauf jeder
    // nicht gefahrene Modus als "fehlt" im Bericht.
    val modes = EvalSetup.modes(options.list("modes")).filter { options.list("modes").isNotEmpty() || File(runDir, it.id).isDirectory }
    val report = EvalCheck.run(runDir, cases, modes)
    EvalCheck.describe(run, report).forEach(::println)
    EvalCheck.write(runDir, report)
    println("Bericht geschrieben: ${File(runDir, "checks.json").absolutePath}")
}

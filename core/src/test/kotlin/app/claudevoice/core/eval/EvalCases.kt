package app.claudevoice.core.eval

import app.claudevoice.core.MicMode
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.File

/**
 * Eine Zeile eines Testfalls, so wie sie "in Wahrheit" war: wer gesprochen hat ([who], z. B. "mira"),
 * in welcher Rolle ([speaker]: "ich", "alfred" oder irgendeine andere wie "partner", "dritte", "medien")
 * und wie nah am Mikrofon ([proximity]). Was die App davon zu sehen bekommt, entscheidet erst der [EvalRenderer].
 */
data class EvalLine(
    /** Alter in Sekunden vor der auslösenden Äußerung; bei der auslösenden Äußerung selbst 0. */
    val agoSec: Long,
    val who: String,
    val speaker: String,
    val proximity: String,
    val text: String,
)

/** Was ein Mensch für richtig hält: sprechen oder schweigen, und wenn sprechen, wie lang und mit welchem Kern. */
data class EvalExpect(val answer: Boolean, val length: String, val gist: String, val why: String)

/** Ein Testfall: ein Gesprächsausschnitt, die auslösende Äußerung und die erwartete Entscheidung. */
data class EvalCase(
    val id: String,
    val scene: String,
    val mic: MicMode,
    val musicPlaying: Boolean,
    val assistantSpokeAgoSec: Long?,
    val history: List<EvalLine>,
    val trigger: EvalLine,
    val expect: EvalExpect,
    /** Mehrdeutig = Menschen wären sich hier selbst uneins. Solche Fälle zählen nicht in die Hauptzahlen. */
    val ambiguous: Boolean,
    val tags: List<String>,
    /** Name der Datei, aus der der Fall stammt (nur für Fehlermeldungen und Übersichten). */
    val file: String,
)

/** Eine oder mehrere Falldateien halten das Format nicht ein. Die Meldung nennt jede Stelle einzeln. */
class EvalFormatException(val problems: List<String>) :
    RuntimeException("Testfälle fehlerhaft (${problems.size}):\n" + problems.joinToString("\n") { " - $it" })

/**
 * Lädt die Testfälle aus `src/test/resources/eval/` (alle `*.json`) und prüft sie streng.
 *
 * Warum so streng: Die Fälle sind der Maßstab, an dem der Prompt gemessen wird. Ein Tippfehler im
 * Feldnamen ("anwser") würde sonst still als "fehlt, also false" durchgehen, und die Zahlen sähen
 * trotzdem überzeugend aus. Deshalb gilt: unbekannte Felder, fehlende Felder, falsche Typen und
 * Widersprüche innerhalb eines Falls sind Fehler, und der Unit-Test lässt den Build daran scheitern.
 */
object EvalCases {
    const val RESOURCE_DIR = "eval"

    val LENGTHS = setOf("kurz", "mittel", "lang")
    const val NO_LENGTH = "none"
    val PROXIMITIES = setOf("nah", "mittel", "fern")
    const val WEARER = "ich"
    const val ASSISTANT = "alfred"

    private val CASE_KEYS = setOf(
        "id", "scene", "mic", "music_playing", "assistant_spoke_ago_s", "history", "trigger", "expect", "ambiguous", "tags",
    )
    private val HISTORY_KEYS = setOf("ago_s", "who", "speaker", "proximity", "text")
    private val TRIGGER_KEYS = setOf("who", "speaker", "proximity", "text")
    private val EXPECT_KEYS = setOf("answer", "length", "gist", "why")

    /**
     * IDs werden zu Dateinamen (`cases/<modus>/<id>.txt`), Szenen zu Gruppen in der Auswertung. Deshalb nur
     * Kleinbuchstaben, Ziffern, Binde- und Unterstriche: "Party" und "party" wären sonst zwei Gruppen.
     */
    private val SLUG = Regex("[a-z0-9]+([-_][a-z0-9]+)*")
    private const val SLUG_RULE = "darf nur Kleinbuchstaben, Ziffern, Binde- und Unterstriche enthalten"
    private const val MAX_AGO_SEC = 3_600L

    // Doppelte Schlüssel und Text hinter dem JSON sind fast immer Tippfehler, also Fehler statt "der letzte gewinnt".
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /** Der Ordner mit den Falldateien, so wie er im Test-Klassenpfad liegt. */
    fun resourceDir(): File {
        val url = EvalCases::class.java.classLoader.getResource(RESOURCE_DIR)
            ?: error("Ordner '$RESOURCE_DIR' fehlt im Test-Klassenpfad (erwartet: core/src/test/resources/$RESOURCE_DIR)")
        check(url.protocol == "file") { "Die Testfälle müssen als Ordner vorliegen, nicht in einem Archiv: $url" }
        return File(url.toURI())
    }

    /** Die Falldateien in fester Reihenfolge (nach Namen), damit jede Ausgabe reproduzierbar ist. */
    fun files(dir: File = resourceDir()): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    /** Lädt und prüft alle Falldateien. Wirft [EvalFormatException] mit allen gefundenen Fehlern auf einmal. */
    fun loadAll(dir: File = resourceDir()): List<EvalCase> {
        val files = files(dir)
        if (files.isEmpty()) throw EvalFormatException(listOf("$dir: keine *.json-Dateien gefunden"))
        return parseAll(files.map { it.name to it.readText(Charsets.UTF_8) })
    }

    /** Wie [loadAll], aber mit Texten statt Dateien (so lässt sich die Prüfung selbst testen). */
    fun parseAll(sources: List<Pair<String, String>>): List<EvalCase> {
        val problems = ArrayList<String>()
        val cases = sources.flatMap { (name, json) -> readFile(name, json, problems) }
        cases.groupBy { it.id }.filter { it.value.size > 1 }.forEach { (id, same) ->
            problems += "id '$id' kommt mehrfach vor (${same.joinToString(", ") { it.file }})"
        }
        if (problems.isNotEmpty()) throw EvalFormatException(problems)
        return cases
    }

    fun parse(fileName: String, json: String): List<EvalCase> = parseAll(listOf(fileName to json))

    // ------------------------------------------------------------------------------------------

    private fun readFile(file: String, json: String, problems: MutableList<String>): List<EvalCase> {
        val root = try {
            mapper.readTree(json)
        } catch (e: JsonProcessingException) {
            problems += "$file: kein gültiges JSON (${e.originalMessage}, Zeile ${e.location?.lineNr ?: "?"})"
            return emptyList()
        }
        if (root == null || !root.isArray) {
            problems += "$file: erwartet wird eine Liste von Fällen ([ ... ])"
            return emptyList()
        }
        if (root.isEmpty) problems += "$file: enthält keinen Fall"
        return root.mapIndexedNotNull { i, node ->
            // Für die Ortsangabe die ID benutzen, wenn sie lesbar ist, sonst die Position in der Datei.
            val at = "$file: Fall ${node.get("id")?.takeIf { it.isTextual }?.asText() ?: "#${i + 1}"}"
            val before = problems.size
            val case = readCase(file, node, Reporter(at, problems))
            case.takeIf { problems.size == before }
        }
    }

    /** Sammelt Fehler mit Ortsangabe, statt beim ersten abzubrechen: Wer Fälle schreibt, will alle auf einmal sehen. */
    private class Reporter(private val at: String, private val problems: MutableList<String>) {
        fun fail(where: String, what: String) { problems += "$at, $where: $what" }
        fun fail(what: String) { problems += "$at: $what" }
    }

    private fun readCase(file: String, node: JsonNode, r: Reporter): EvalCase? {
        if (!keysOk(node, CASE_KEYS, "Fall", r)) return null

        val id = string(node, "id", "id", r)
        if (id != null && !SLUG.matches(id)) r.fail("id", "'$id' $SLUG_RULE")
        val scene = string(node, "scene", "scene", r)
        if (scene != null && !SLUG.matches(scene)) r.fail("scene", "'$scene' $SLUG_RULE")
        val mic = when (val m = string(node, "mic", "mic", r)) {
            null -> null
            "airpods" -> MicMode.AIRPODS
            "handy" -> MicMode.HANDY
            else -> { r.fail("mic", "'$m' ist unbekannt (erlaubt: airpods, handy)"); null }
        }
        val music = bool(node, "music_playing", "music_playing", r)
        val spokeNode = node.get("assistant_spoke_ago_s")
        val spokeAgo = if (spokeNode.isNull) null else seconds(spokeNode, "assistant_spoke_ago_s", r)
        val ambiguous = bool(node, "ambiguous", "ambiguous", r)

        val historyNode = node.get("history")
        val history = if (!historyNode.isArray) {
            r.fail("history", "muss eine Liste sein"); null
        } else historyNode.mapIndexed { i, h ->
            val where = "history[$i]"
            if (keysOk(h, HISTORY_KEYS, where, r)) {
                val ago = seconds(h.get("ago_s"), "$where.ago_s", r)
                readLine(h, where, ago, r)
            } else null
        }
        val triggerNode = node.get("trigger")
        val trigger = if (keysOk(triggerNode, TRIGGER_KEYS, "trigger", r)) readLine(triggerNode, "trigger", 0, r) else null
        val expect = readExpect(node.get("expect"), ambiguous, r)

        val tagsNode = node.get("tags")
        val tags = if (!tagsNode.isArray) {
            r.fail("tags", "muss eine Liste sein"); null
        } else tagsNode.mapIndexed { i, t ->
            if (t.isTextual && t.asText().isNotBlank()) t.asText() else { r.fail("tags[$i]", "muss ein nicht leerer Text sein"); "" }
        }

        if (history != null && history.none { it == null }) {
            checkConversation(history.filterNotNull(), trigger, spokeNode.isNull, r)
        }
        if (id == null || scene == null || mic == null || music == null || ambiguous == null ||
            history == null || history.any { it == null } || trigger == null || expect == null || tags == null
        ) return null
        return EvalCase(id, scene, mic, music, spokeAgo, history.filterNotNull(), trigger, expect, ambiguous, tags, file)
    }

    private fun readLine(node: JsonNode, where: String, agoSec: Long?, r: Reporter): EvalLine? {
        val who = string(node, "who", "$where.who", r)
        if (who != null && !SLUG.matches(who)) r.fail("$where.who", "'$who' $SLUG_RULE")
        val speaker = string(node, "speaker", "$where.speaker", r)
        val proximity = string(node, "proximity", "$where.proximity", r)
        if (proximity != null && proximity !in PROXIMITIES) {
            r.fail("$where.proximity", "'$proximity' ist unbekannt (erlaubt: ${PROXIMITIES.joinToString(", ")})")
        }
        val text = string(node, "text", "$where.text", r)
        // Die Mitschrift ist zeilenweise aufgebaut; ein Zeilenumbruch im Text würde eine zweite, unmarkierte Zeile erzeugen.
        if (text != null && (text.contains('\n') || text.contains('\r'))) r.fail("$where.text", "darf keinen Zeilenumbruch enthalten")
        if (who != null && speaker != null) {
            // Träger und Assistent sind feste Personen: Bei ihnen müssen Name und Rolle übereinstimmen,
            // sonst bekäme z. B. der Träger zwei verschiedene Sprecher-Kürzel.
            for (fixed in listOf(WEARER, ASSISTANT)) {
                if ((who == fixed) != (speaker == fixed)) r.fail(where, "who '$who' und speaker '$speaker' widersprechen sich ('$fixed' gilt für beide oder keinen)")
            }
        }
        if (who == null || speaker == null || proximity == null || proximity !in PROXIMITIES || text == null || agoSec == null) return null
        return EvalLine(agoSec, who, speaker, proximity, text)
    }

    private fun readExpect(node: JsonNode, ambiguous: Boolean?, r: Reporter): EvalExpect? {
        if (!keysOk(node, EXPECT_KEYS, "expect", r)) return null
        val answer = bool(node, "answer", "expect.answer", r)
        val length = string(node, "length", "expect.length", r)
        val gist = string(node, "gist", "expect.gist", r, allowBlank = true)
        val why = string(node, "why", "expect.why", r)
        if (answer == null || length == null || gist == null || why == null) return null
        var ok = true
        if (answer) {
            if (length !in LENGTHS) { r.fail("expect.length", "'$length' passt nicht zu answer=true (erlaubt: ${LENGTHS.joinToString(", ")})"); ok = false }
            if (gist.isBlank()) { r.fail("expect.gist", "fehlt, obwohl eine Antwort erwartet wird"); ok = false }
        } else {
            if (length != NO_LENGTH) { r.fail("expect.length", "'$length' passt nicht zu answer=false (erwartet: $NO_LENGTH)"); ok = false }
            // Ein Kern ohne erwartete Antwort deutet auf ein versehentlich umgekipptes Etikett hin.
            // Nur bei mehrdeutigen Fällen darf er stehen ("falls er doch spricht, dann das").
            if (gist.isNotBlank() && ambiguous == false) { r.fail("expect.gist", "muss leer sein, wenn keine Antwort erwartet wird"); ok = false }
        }
        return if (ok) EvalExpect(answer, length, gist, why) else null
    }

    /** Prüfungen über mehrere Zeilen hinweg: Reihenfolge, feste Rollen, Widersprüche zur Lage. */
    private fun checkConversation(history: List<EvalLine>, trigger: EvalLine?, spokeAgoMissing: Boolean, r: Reporter) {
        for (i in 1 until history.size) {
            if (history[i].agoSec > history[i - 1].agoSec) {
                r.fail("history[$i].ago_s", "die Mitschrift muss von alt nach neu sortiert sein (${history[i].agoSec} s nach ${history[i - 1].agoSec} s)")
            }
        }
        val all = history + listOfNotNull(trigger)
        all.groupBy { it.who }.forEach { (who, lines) ->
            val roles = lines.map { it.speaker }.distinct()
            // Eine Ausnahme ist erlaubt und realistisch: Jemand vom Nachbargespräch ("dritte") wendet sich
            // dem Träger zu und wird damit zum Gesprächspartner.
            val joined = roles.toSet() == setOf("dritte", "partner")
            if (roles.size > 1 && !joined) r.fail("who '$who'", "hat mehrere Rollen (${roles.joinToString(", ")})")
        }
        if (trigger?.speaker == ASSISTANT) r.fail("trigger", "der Assistent kann sich nicht selbst auslösen")
        // Steht eine Zeile des Assistenten in der Mitschrift, hat er gesprochen. Ohne Zeitangabe würde die
        // Lage dem Modell das Gegenteil sagen ("in den letzten Minuten nicht").
        if (spokeAgoMissing && history.any { it.speaker == ASSISTANT }) {
            r.fail("assistant_spoke_ago_s", "ist null, obwohl die Mitschrift eine Zeile des Assistenten enthält")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Kleine Helfer: je ein Feld lesen und den Typ prüfen.

    private fun keysOk(node: JsonNode?, expected: Set<String>, where: String, r: Reporter): Boolean {
        if (node == null || !node.isObject) { r.fail(where, "muss ein Objekt sein ({ ... })"); return false }
        val actual = node.fieldNames().asSequence().toSet()
        val missing = expected - actual
        val unknown = actual - expected
        if (missing.isNotEmpty()) r.fail(where, "Feld fehlt: ${missing.joinToString(", ")}")
        if (unknown.isNotEmpty()) r.fail(where, "unbekanntes Feld: ${unknown.joinToString(", ")}")
        return missing.isEmpty() && unknown.isEmpty()
    }

    private fun string(node: JsonNode, key: String, where: String, r: Reporter, allowBlank: Boolean = false): String? {
        val v = node.get(key)
        if (v == null || !v.isTextual) { r.fail(where, "muss ein Text sein"); return null }
        if (!allowBlank && v.asText().isBlank()) { r.fail(where, "darf nicht leer sein"); return null }
        return v.asText()
    }

    private fun bool(node: JsonNode, key: String, where: String, r: Reporter): Boolean? {
        val v = node.get(key)
        if (v == null || !v.isBoolean) { r.fail(where, "muss true oder false sein (ohne Anführungszeichen)"); return null }
        return v.asBoolean()
    }

    private fun seconds(v: JsonNode?, where: String, r: Reporter): Long? {
        if (v == null || !v.isIntegralNumber || !v.canConvertToLong() || v.asLong() !in 0..MAX_AGO_SEC) {
            r.fail(where, "muss eine ganze Zahl von Sekunden zwischen 0 und $MAX_AGO_SEC sein")
            return null
        }
        return v.asLong()
    }
}

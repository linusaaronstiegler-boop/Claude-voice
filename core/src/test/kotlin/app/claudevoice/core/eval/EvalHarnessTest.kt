package app.claudevoice.core.eval

import app.claudevoice.core.AnthropicClaude
import app.claudevoice.core.CancelToken
import app.claudevoice.core.ClaudeApi
import app.claudevoice.core.MicMode
import app.claudevoice.core.Persona
import app.claudevoice.core.Voice
import app.claudevoice.core.VoiceClassifier
import app.claudevoice.core.VoiceFeatures
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prüft das Auswertungs-Werkzeug selbst: Sind die Falldateien in Ordnung, entsteht aus demselben Fall
 * immer dieselbe Eingabe, und landen die Dateien dort, wo `tools/eval_score.py` sie sucht?
 * Das Modell wird hier nicht gefragt; echte Läufe macht `./gradlew :core:evalLive`.
 */
class EvalHarnessTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cases by lazy { EvalCases.loadAll() }

    // ------------------------------------------------------------------------------------------
    // Falldateien

    @Test fun `alle Falldateien im Ordner sind gueltig und werden geladen`() {
        val files = EvalCases.files()
        assertTrue("keine Falldateien gefunden", files.isNotEmpty())
        // Jede *.json im Ordner muss Fälle beisteuern, auch später hinzugefügte.
        assertEquals(files.map { it.name }, cases.map { it.file }.distinct())
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        // Eine Auswertung nur aus "sprich"- oder nur aus "schweig"-Fällen wäre mit einer festen Antwort zu gewinnen.
        assertTrue(cases.any { it.expect.answer } && cases.any { !it.expect.answer })
    }

    private val valid = """
[{
  "id": "t-01", "scene": "alltag", "mic": "airpods", "music_playing": false, "assistant_spoke_ago_s": 12,
  "history": [
    {"ago_s": 40, "who": "ich", "speaker": "ich", "proximity": "nah", "text": "wir waren auf der Zugspitze"},
    {"ago_s": 31, "who": "mira", "speaker": "partner", "proximity": "mittel", "text": "schön da oben"},
    {"ago_s": 20, "who": "alfred", "speaker": "alfred", "proximity": "nah", "text": "Zweitausendneunhundertzweiundsechzig Meter."},
    {"ago_s": 9, "who": "tisch-b", "speaker": "dritte", "proximity": "fern", "text": "zahlen bitte"}
  ],
  "trigger": {"who": "mira", "speaker": "partner", "proximity": "mittel", "text": "und seit wann fährt da eine Bahn hoch"},
  "expect": {"answer": true, "length": "kurz", "gist": "Seit neunzehnhundertdreißig.", "why": "Offene Sachfrage im Gespräch."},
  "ambiguous": false,
  "tags": ["fact-question"]
}]
""".trim()

    private fun fixture(): EvalCase = EvalCases.parse("t.json", valid).single()

    /** Das letzte Feld von "expect" in der Vorlage; dahinter lassen sich weitere Felder anhängen. */
    private val WHY = "\"why\": \"Offene Sachfrage im Gespräch.\""

    @Test fun `pruefbare Erwartungen sind freiwillig und werden vollstaendig gelesen`() {
        // Ohne die Angaben (so sind alle älteren Falldateien geschrieben) bleibt alles leer.
        assertEquals(EvalExpect(true, "kurz", "Seit neunzehnhundertdreißig.", "Offene Sachfrage im Gespräch."), fixture().expect)
        assertNull(fixture().expect.memo)
        assertNull(fixture().expect.muteMinutes)
        val json = valid.replaceFirst(WHY, "$WHY, \"memo\": false, \"mute_minutes\": 120, \"forbidden\": [\"\\\\bGrad\\\\b\", \"regn\"], \"required\": [\"zweitausend\"]")
        val e = EvalCases.parse("t.json", json).single().expect
        assertEquals(false, e.memo)
        assertEquals(120, e.muteMinutes)
        assertEquals(listOf("\\bGrad\\b", "regn"), e.forbidden)
        assertEquals(listOf("zweitausend"), e.required)
        // Die mitgelieferten Fälle nutzen die Angaben auch: Sonst gäbe es nichts, woran ein Lauf scheitern könnte.
        assertTrue(cases.any { it.expect.memo == false } && cases.any { it.expect.muteMinutes != null })
        assertTrue(cases.any { it.expect.forbidden.isNotEmpty() } && cases.any { it.expect.required.isNotEmpty() })
    }

    @Test fun `eine gueltige Falldatei wird vollstaendig gelesen`() {
        val c = fixture()
        assertEquals("t-01", c.id)
        assertEquals(MicMode.AIRPODS, c.mic)
        assertEquals(12L, c.assistantSpokeAgoSec)
        assertEquals(listOf(40L, 31L, 20L, 9L), c.history.map { it.agoSec })
        assertEquals(EvalLine(0, "mira", "partner", "mittel", "und seit wann fährt da eine Bahn hoch"), c.trigger)
        assertEquals(EvalExpect(true, "kurz", "Seit neunzehnhundertdreißig.", "Offene Sachfrage im Gespräch."), c.expect)
        assertEquals(listOf("fact-question"), c.tags)
    }

    @Test fun `kaputte Falldateien werden mit Grund abgelehnt`() {
        // Je Zeile: was an der gültigen Datei verändert wird, und woran man die Fehlermeldung erkennt.
        val broken = listOf(
            Triple("\"ambiguous\": false,", "", "Feld fehlt: ambiguous"),
            Triple("\"ambiguous\": false,", "\"ambiguous\": false, \"note\": \"x\",", "unbekanntes Feld: note"),
            Triple("\"ago_s\": 9,", "\"ago_s\": 9, \"lang\": \"de\",", "unbekanntes Feld: lang"),
            Triple("\"music_playing\": false", "\"music_playing\": \"false\"", "music_playing: muss true oder false sein"),
            Triple("\"mic\": \"airpods\"", "\"mic\": \"headset\"", "'headset' ist unbekannt"),
            Triple("\"proximity\": \"fern\"", "\"proximity\": \"weit\"", "'weit' ist unbekannt"),
            Triple("\"length\": \"kurz\"", "\"length\": \"none\"", "passt nicht zu answer=true"),
            Triple("\"length\": \"kurz\"", "\"length\": \"knapp\"", "passt nicht zu answer=true"),
            Triple("\"answer\": true, \"length\": \"kurz\"", "\"answer\": false, \"length\": \"none\"", "muss leer sein, wenn keine Antwort erwartet wird"),
            Triple("\"answer\": true", "\"answer\": false", "passt nicht zu answer=false"),
            Triple("\"gist\": \"Seit neunzehnhundertdreißig.\"", "\"gist\": \"\"", "fehlt, obwohl eine Antwort erwartet wird"),
            Triple("\"ago_s\": 31", "\"ago_s\": 50", "von alt nach neu sortiert"),
            Triple("\"ago_s\": 9,", "\"ago_s\": 9.5,", "ganze Zahl von Sekunden"),
            Triple("\"ago_s\": 9,", "\"ago_s\": -1,", "ganze Zahl von Sekunden"),
            Triple("schön da oben", "schön\\nda oben", "darf keinen Zeilenumbruch enthalten"),
            Triple("zahlen bitte", " ", "darf nicht leer sein"),
            Triple("\"trigger\": {\"who\": \"mira\", \"speaker\": \"partner\"", "\"trigger\": {\"who\": \"alfred\", \"speaker\": \"alfred\"", "nicht selbst auslösen"),
            Triple("\"trigger\": {\"who\": \"mira\", \"speaker\": \"partner\"", "\"trigger\": {\"who\": \"mira\", \"speaker\": \"medien\"", "hat mehrere Rollen"),
            Triple("\"assistant_spoke_ago_s\": 12", "\"assistant_spoke_ago_s\": null", "obwohl die Mitschrift eine Zeile des Assistenten enthält"),
            Triple("\"who\": \"ich\", \"speaker\": \"ich\"", "\"who\": \"ich\", \"speaker\": \"partner\"", "widersprechen sich"),
            Triple("\"id\": \"t-01\"", "\"id\": \"T 01\"", "darf nur Kleinbuchstaben"),
            Triple("\"id\": \"t-01\"", "\"id\": \"t-01\", \"id\": \"t-02\"", "kein gültiges JSON"),
            Triple("\"tags\": [\"fact-question\"]", "\"tags\": [\"\"]", "muss ein nicht leerer Text sein"),
            Triple("\"tags\": [\"fact-question\"]", "\"tags\": \"fact-question\"", "tags: muss eine Liste sein"),
            Triple("}]", "}] und noch etwas", "kein gültiges JSON"),
            Triple("}]", "}", "kein gültiges JSON"),
            // Die freiwilligen, prüfbaren Erwartungen (siehe EvalExpect)
            Triple(WHY, "$WHY, \"mute\": 30", "unbekanntes Feld: mute"),
            Triple(WHY, "$WHY, \"mute_minutes\": 0", "ganze Zahl von Minuten zwischen 1 und 600"),
            Triple(WHY, "$WHY, \"mute_minutes\": 601", "ganze Zahl von Minuten zwischen 1 und 600"),
            Triple(WHY, "$WHY, \"mute_minutes\": \"30\"", "ganze Zahl von Minuten zwischen 1 und 600"),
            Triple(WHY, "$WHY, \"memo\": \"nein\"", "expect.memo: muss true oder false sein"),
            Triple(WHY, "$WHY, \"forbidden\": []", "expect.forbidden: muss eine nicht leere Liste von Mustern sein"),
            Triple(WHY, "$WHY, \"required\": \"Grad\"", "expect.required: muss eine nicht leere Liste von Mustern sein"),
            Triple(WHY, "$WHY, \"forbidden\": [\"(\"]", "expect.forbidden[0]: muss ein gültiges Muster"),
            Triple(WHY, "$WHY, \"required\": [\"Grad\", 7]", "expect.required[1]: muss ein gültiges Muster"),
            Triple(
                "\"answer\": true, \"length\": \"kurz\", \"gist\": \"Seit neunzehnhundertdreißig.\", $WHY",
                "\"answer\": false, \"length\": \"none\", \"gist\": \"\", $WHY, \"memo\": false",
                "expect.memo: passt nicht zu answer=false",
            ),
        )
        for ((old, new, expected) in broken) {
            assertTrue("Vorlage enthält nicht: $old", valid.contains(old))
            val message = rejected(listOf("t.json" to valid.replaceFirst(old, new)))
            assertTrue("Erwartet '$expected' in:\n$message", message.contains(expected))
            assertTrue("Die Meldung nennt die Datei:\n$message", message.contains("t.json"))
        }
        assertTrue(rejected(listOf("t.json" to valid.trim().removePrefix("[").removeSuffix("]"))).contains("Liste von Fällen"))
        assertTrue(rejected(listOf("t.json" to "[]")).contains("enthält keinen Fall"))
        assertTrue(rejected(listOf("a.json" to valid, "b.json" to valid)).contains("id 't-01' kommt mehrfach vor (a.json, b.json)"))
    }

    @Test fun `mehrere Fehler werden zusammen gemeldet`() {
        val json = valid.replaceFirst("\"mic\": \"airpods\"", "\"mic\": \"headset\"").replaceFirst("\"ago_s\": 31", "\"ago_s\": 50")
        val message = rejected(listOf("t.json" to json))
        assertTrue(message.contains("'headset' ist unbekannt") && message.contains("von alt nach neu sortiert"))
        assertTrue(message.contains("Fall t-01"))
    }

    @Test fun `ein Ordner ohne Falldateien ist ein Fehler`() {
        try {
            EvalCases.loadAll(tmp.newFolder("leer"))
            fail("leerer Ordner hätte abgelehnt werden müssen")
        } catch (e: EvalFormatException) {
            assertTrue(e.message!!.contains("keine *.json-Dateien"))
        }
    }

    private fun rejected(sources: List<Pair<String, String>>): String = try {
        EvalCases.parseAll(sources)
        fail("hätte abgelehnt werden müssen")
        ""
    } catch (e: EvalFormatException) {
        e.message!!
    }

    // ------------------------------------------------------------------------------------------
    // Darstellung

    private val lage = """
<lage>
Zeit: Donnerstag, 12. November 2026, 17:40
Mikrofon: Kopfhörer-Mikrofon (seine eigene Stimme ist mit Abstand die lauteste)
Seine Musik läuft: nein
Du hast zuletzt gesprochen: vor 12 s
Szene: noch keine Einschätzung
Sprecher der letzten Äußerung: nicht gesichert
</lage>
""".trim()

    @Test fun `korrekt nimmt Stimme und Kuerzel aus der Wahrheit`() {
        val expected = """
$lage
<mitschrift>
[vor 40 s] ICH S1: wir waren auf der Zugspitze
[vor 31 s] NAH S2: schön da oben
[vor 20 s] ALFRED: Zweitausendneunhundertzweiundsechzig Meter.
[vor 9 s] FERN S3: zahlen bitte
>> [jetzt] NAH S2: und seit wann fährt da eine Bahn hoch
</mitschrift>
""".trim()
        assertEquals(expected, EvalRenderer.userMessage(fixture(), TagMode.KORREKT))
    }

    @Test fun `ohne laesst nur die eigenen Zeilen des Assistenten erkennbar`() {
        val expected = """
$lage
<mitschrift>
[vor 40 s] ?: wir waren auf der Zugspitze
[vor 31 s] ?: schön da oben
[vor 20 s] ALFRED: Zweitausendneunhundertzweiundsechzig Meter.
[vor 9 s] ?: zahlen bitte
>> [jetzt] ?: und seit wann fährt da eine Bahn hoch
</mitschrift>
""".trim()
        assertEquals(expected, EvalRenderer.userMessage(fixture(), TagMode.OHNE))
    }

    @Test fun `handy zeigt den Fall so, wie ihn das Handy-Mikrofon liefert`() {
        // Kein ICH und kein NAH, aber die richtigen Kürzel; die leise Stimme vom Nachbartisch bleibt FERN.
        // Die Lage nennt das Handy-Mikrofon, obwohl der Fall für das Kopfhörer-Mikrofon geschrieben ist.
        val expected = """
<lage>
Zeit: Donnerstag, 12. November 2026, 17:40
Mikrofon: Handy-Mikrofon (nimmt den ganzen Raum auf; seine eigene Stimme ist in dieser Mitschrift nicht von anderen zu unterscheiden und steht meist als ? da, nicht als ICH)
Seine Musik läuft: nein
Du hast zuletzt gesprochen: vor 12 s
Szene: noch keine Einschätzung
Sprecher der letzten Äußerung: nicht gesichert
</lage>
<mitschrift>
[vor 40 s] ? S1: wir waren auf der Zugspitze
[vor 31 s] ? S2: schön da oben
[vor 20 s] ALFRED: Zweitausendneunhundertzweiundsechzig Meter.
[vor 9 s] FERN S3: zahlen bitte
>> [jetzt] ? S2: und seit wann fährt da eine Bahn hoch
</mitschrift>
""".trim()
        assertEquals(expected, EvalRenderer.userMessage(fixture(), TagMode.HANDY))
    }

    /** Die Stimmen-Angaben vor den Zeilen der Mitschrift, so wie das Modell sie liest. */
    private fun labelsIn(userMessage: String): List<String> =
        Regex("(?m)^(?:>> )?\\[[^\\]]+] (?:\\(abseits\\) )?(ICH|NAH|FERN|\\?|ALFRED)(?=[ :])")
            .findAll(userMessage.substringAfter("<mitschrift>\n")).map { it.groupValues[1] }.toList()

    @Test fun `handy kennt in keinem Fall ein ICH und behaelt die Kuerzel`() {
        for (c in cases) {
            val truth = EvalRenderer.render(c, TagMode.KORREKT)
            val phone = EvalRenderer.render(c, TagMode.HANDY)
            assertEquals(c.id, MicMode.HANDY, phone.situation.mic)
            // Ohne ICH ist nie jemand "gesichert": Merken und Stummschalten bleiben in diesem Modus zu.
            assertFalse(c.id, phone.situation.speakerVerified)
            for ((t, p) in (truth.history + truth.trigger).zip(phone.history + phone.trigger)) {
                assertEquals(c.id, t.speaker, p.speaker)
                // Vom Stimmenfilter kommt am Handy NAH oder FERN ...
                if (t.voice == Voice.ALFRED) assertEquals(c.id, t, p)
                else assertTrue("${c.id}: ${p.voice}", p.voice == Voice.NAH || p.voice == Voice.FERN)
            }
            // ... und das Modell liest statt NAH ein "?", mit dem Hinweis in der Lage.
            val text = EvalRenderer.userMessage(c, TagMode.HANDY)
            val labels = labelsIn(text)
            assertEquals(c.id, phone.history.size + 1, labels.size)
            assertTrue("${c.id}: $labels", labels.all { it == "?" || it == "FERN" || it == "ALFRED" })
            assertTrue(c.id, text.contains("steht meist als ? da, nicht als ICH)\n"))
        }
        // Der Träger weit weg vom Handy (er kocht, das Handy liegt beim Radio) ist ebenfalls FERN, nicht "?".
        val far = fixture().copy(trigger = EvalLine(0, "ich", "ich", "fern", "wie viele Teelöffel sind ein Esslöffel"))
        assertEquals(Voice.FERN, EvalRenderer.render(far, TagMode.HANDY).trigger.voice)
    }

    @Test fun `Lage und Etiketten widersprechen sich in keinem Fall und keinem Modus`() {
        // Der Hinweis "nicht als ICH" steht genau dann in der Lage, wenn sie das Handy-Mikrofon nennt und in
        // der Mitschrift kein ICH vorkommt. Und neben dem Hinweis steht nie ein NAH: Das hieße "jemand anderes"
        // für eine Stimme, von der die Lage gerade sagt, sie könne die des Trägers sein.
        val withHint = LinkedHashMap<String, Int>()
        val phoneCases = cases.count { it.mic == MicMode.HANDY }
        for (mode in TagMode.entries) for (c in cases) {
            val text = EvalRenderer.userMessage(c, mode)
            val labels = labelsIn(text)
            val phone = text.contains("\nMikrofon: Handy-Mikrofon")
            val hint = text.contains("nicht als ICH")
            assertEquals("${mode.id}/${c.id}", phone && "ICH" !in labels, hint)
            if (hint) assertFalse("${mode.id}/${c.id}: $labels", "NAH" in labels)
            // Ohne Hinweis steht am Handy die Lage, wie sie vor dem Hinweis war.
            if (phone && !hint) assertTrue("${mode.id}/${c.id}", text.contains("\nMikrofon: Handy-Mikrofon (nimmt den ganzen Raum auf)\n"))
            if (hint && mode != TagMode.HANDY) withHint.merge(mode.id, 1, Int::plus)
        }
        // Zur Einordnung der Zahlen eines Laufs: So viele der Fälle am Handy-Mikrofon tragen je Modus den Hinweis.
        println("Hinweis zum Handy-Mikrofon: " + TagMode.entries.filter { it != TagMode.HANDY }
            .joinToString(", ") { "${it.id} ${withHint[it.id] ?: 0} von $phoneCases" } + ", handy alle ${cases.size}")
    }

    @Test fun `zwischen der letzten Antwort in der Mitschrift und der Angabe der Lage liegt kein Wortwechsel`() {
        // In der App stammen beide Angaben aus demselben Zeitpunkt. Ein paar Sekunden Abstand sind in den Fällen
        // üblich; liegen aber zwei oder mehr Zeilen dazwischen, behauptet die Lage eine Antwort, die in der
        // Mitschrift fehlt, und der Fall zeigt etwas, das die App so nie sendet.
        val bad = cases.mapNotNull { c ->
            val spoke = c.assistantSpokeAgoSec ?: return@mapNotNull null
            val last = c.history.filter { it.speaker == EvalCases.ASSISTANT }.minOfOrNull { it.agoSec } ?: return@mapNotNull null
            val between = c.history.filter { it.speaker != EvalCases.ASSISTANT && it.agoSec < last && it.agoSec > spoke }
            if (between.size >= 2) "${c.id}: Lage 'vor $spoke s', Zeile des Assistenten 'vor $last s', dazwischen ${between.size} Zeilen" else null
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun `handy zeigt nur, was der Stimmenfilter am Handy ohne Profil wirklich liefert`() {
        // Der Modus bildet den echten Stimmenfilter nach. Ändert sich dessen Regel, muss sich der Modus mit ändern.
        val classifier = VoiceClassifier(MicMode.HANDY)
        fun heard(levelDb: Double) = classifier.classify(VoiceFeatures(levelDb, 150.0, 800))
        val byProximity = mapOf("nah" to heard(-22.0), "mittel" to heard(-30.0), "fern" to heard(-46.0))
        for ((proximity, voice) in byProximity) {
            val c = fixture().copy(history = emptyList(), trigger = EvalLine(0, "mira", "partner", proximity, "wie hoch ist die Zugspitze"))
            assertEquals(proximity, voice, EvalRenderer.render(c, TagMode.HANDY).trigger.voice)
        }
    }

    @Test fun `die Lage kommt aus dem Fall und die Szene bleibt leer`() {
        val c = fixture().copy(mic = MicMode.HANDY, musicPlaying = true, assistantSpokeAgoSec = null, history = emptyList())
        for (mode in TagMode.entries) {
            val turn = EvalRenderer.render(c, mode)
            assertEquals(EvalRenderer.LOCAL_TIME, turn.situation.localTime)
            assertEquals(MicMode.HANDY, turn.situation.mic)
            assertTrue(turn.situation.musicPlaying)
            assertNull(turn.situation.assistantSpokeAgoSec)
            assertNull(turn.situation.scene)
            assertTrue(turn.situation.sessionNotes.isEmpty())
            assertTrue(turn.history.isEmpty())
            assertFalse(turn.trigger.aside)
        }
        // Nur der Modus "handy" setzt sich über das Mikrofon des Falls hinweg (siehe TagMode.HANDY).
        for (mode in TagMode.entries) {
            val mic = EvalRenderer.render(fixture(), mode).situation.mic
            assertEquals(mode.id, if (mode == TagMode.HANDY) MicMode.HANDY else MicMode.AIRPODS, mic)
        }
    }

    @Test fun `Darstellung ist deterministisch`() {
        val again = EvalCases.loadAll() // frisch geladen: nichts darf an einem Zustand im Speicher hängen
        assertEquals(cases, again)
        for ((a, b) in cases.zip(again)) for (mode in TagMode.entries) {
            assertEquals(EvalRenderer.render(a, mode), EvalRenderer.render(b, mode))
            assertEquals(EvalRenderer.userMessage(a, mode), EvalRenderer.userMessage(b, mode))
        }
        // Der Text jeder Zeile und ihr Alter sind in allen Modi gleich; nur die Angaben davor unterscheiden sich.
        for (c in cases) {
            val shapes = TagMode.entries.map { m -> EvalRenderer.render(c, m).let { t -> (t.history + t.trigger).map { it.atMs to it.text } } }
            assertEquals(1, shapes.distinct().size)
        }
    }

    @Test fun `verrauscht bleibt Zeichen fuer Zeichen gleich`() {
        // Festgeschriebenes Ergebnis (unabhängig nachgerechnet): Die erste Zeile bekommt eine falsche Stimme
        // (ICH wird FERN), die auslösende Äußerung verliert ihr Kürzel. Ändert sich diese Ausgabe, hat sich
        // das Würfeln geändert, und Läufe von vorher sind mit Läufen von nachher nicht mehr vergleichbar.
        // Dann bewusst entscheiden, nicht nur den Text hier anpassen.
        val expected = """
$lage
<mitschrift>
[vor 40 s] FERN S1: wir waren auf der Zugspitze
[vor 31 s] NAH S2: schön da oben
[vor 20 s] ALFRED: Zweitausendneunhundertzweiundsechzig Meter.
[vor 9 s] FERN S3: zahlen bitte
>> [jetzt] NAH: und seit wann fährt da eine Bahn hoch
</mitschrift>
""".trim()
        assertEquals(expected, EvalRenderer.userMessage(fixture(), TagMode.VERRAUSCHT))
    }

    @Test fun `verrauscht stoert etwa im verlangten Mass`() {
        // Künstliche Fälle statt der echten Dateien: So hängt der Test nicht daran, welche Fälle es gerade gibt.
        val synthetic = (1..300).map { n ->
            val people = listOf("ich" to "ich", "mira" to "partner", "tisch" to "dritte", "alfred" to "alfred", "radio" to "medien")
            val history = (0 until 7).map { i ->
                val (who, role) = people[(n + i) % people.size]
                EvalLine((70 - 10 * i).toLong(), who, role, if (i % 3 == 0) "fern" else "mittel", "zeile $i")
            }
            fixture().copy(id = "rausch-$n", history = history, trigger = EvalLine(0, "ich", "ich", "nah", "frage $n"))
        }
        var lines = 0; var wrongVoice = 0; var lost = 0; var swapped = 0
        var triggers = 0; var triggerTouched = 0
        for (c in synthetic) {
            val truth = EvalRenderer.render(c, TagMode.KORREKT).let { it.history + it.trigger }
            val noisy = EvalRenderer.render(c, TagMode.VERRAUSCHT).let { it.history + it.trigger }
            for ((t, n) in truth.zip(noisy)) {
                assertEquals(t.text, n.text)
                assertEquals(t.atMs, n.atMs)
                if (t.voice == Voice.ALFRED) { assertEquals(t, n); continue }
                assertNotEquals(Voice.ALFRED, n.voice) // kein Fehler der Akustik macht aus einer Stimme den Assistenten
                lines++
                if (n.voice != t.voice) wrongVoice++
                if (n.speaker == null) lost++ else if (n.speaker != t.speaker) swapped++
            }
            triggers++
            if (noisy.last().copy(id = 0) != truth.last().copy(id = 0)) triggerTouched++
        }
        fun share(count: Int, of: Int) = count.toDouble() / of
        assertTrue("falsche Stimme: ${share(wrongVoice, lines)}", share(wrongVoice, lines) in 0.20..0.30)
        assertTrue("Kürzel fehlt: ${share(lost, lines)}", share(lost, lines) in 0.20..0.30)
        assertTrue("Kürzel vertauscht: ${share(swapped, lines)}", share(swapped, lines) in 0.07..0.13)
        // Die auslösende Äußerung ist nicht ausgenommen: 1 - 0,75 * 0,65 = rund die Hälfte ist irgendwie gestört.
        assertTrue("Auslöser gestört: ${share(triggerTouched, triggers)}", share(triggerTouched, triggers) in 0.40..0.62)
    }

    @Test fun `spricht nur einer, erfindet die Stoerung eine zweite Stimme statt eine fremde zu verwechseln`() {
        val solo = (1..200).map { n ->
            fixture().copy(
                id = "allein-$n",
                assistantSpokeAgoSec = null,
                history = (0 until 5).map { EvalLine((50 - 10 * it).toLong(), "ich", "ich", "nah", "zeile $it") },
                trigger = EvalLine(0, "ich", "ich", "nah", "frage"),
            )
        }
        val labels = solo.flatMap { c -> EvalRenderer.render(c, TagMode.VERRAUSCHT).let { it.history + it.trigger }.map { it.speaker } }
        assertEquals(setOf(null, "S1", "S2"), labels.toSet())
    }

    // ------------------------------------------------------------------------------------------
    // Stapel und Dump

    @Test fun `Stapel enthalten jeden Fall genau einmal und sind bei jedem Aufruf gleich`() {
        for (mode in TagMode.entries) {
            val plan = EvalBatches.plan(cases, mode)
            assertEquals(cases.map { it.id }.sorted(), plan.flatten().sorted())
            assertTrue(plan.dropLast(1).all { it.size == EvalBatches.SIZE } && plan.last().size in 1..EvalBatches.SIZE)
            assertEquals(plan, EvalBatches.plan(cases.shuffled(java.util.Random(7)), mode)) // unabhängig von der Reihenfolge der Eingabe
        }
        assertNotEquals(EvalBatches.plan(cases, TagMode.KORREKT), EvalBatches.plan(cases, TagMode.OHNE))
    }

    @Test fun `Stapel mischen die Szenen`() {
        val scenes = listOf("solo", "party", "unterricht")
        val synthetic = (0 until 36).map { n -> fixture().copy(id = "s-${n.toString().padStart(2, '0')}", scene = scenes[n / 12]) }
        val sceneOf = synthetic.associate { it.id to it.scene }
        for (mode in TagMode.entries) for (batch in EvalBatches.plan(synthetic, mode)) {
            assertTrue("Stapel aus nur einer Szene: $batch", batch.map { sceneOf.getValue(it) }.distinct().size >= 2)
        }
    }

    @Test fun `evalDump schreibt Prompt, Faelle, Erwartung und Stapel`() {
        val out = tmp.newFolder("eval")
        // Ein früherer Lauf und eine veraltete Eingabe liegen schon da.
        val keep = File(out, "out/alt/korrekt/x.txt").apply { parentFile.mkdirs(); writeText("SKIP medien") }
        val stale = File(out, "cases/korrekt/gibt-es-nicht-mehr.txt").apply { parentFile.mkdirs(); writeText("alt") }

        val system = EvalSetup.systemPrompt(null)
        EvalDump.write(out, cases, system)

        assertEquals(Persona.systemPrompt(EvalSetup.PROFILE), File(out, "system.txt").readText())
        assertTrue(system.contains("Du bist Alfred") && system.contains("„Jarvis“") && system.contains("„Sir“"))
        assertTrue("Modell-Antworten früherer Läufe bleiben liegen", keep.isFile)
        assertFalse("veraltete Eingaben verschwinden", stale.exists())

        for (mode in TagMode.entries) {
            val dir = File(out, "cases/${mode.id}")
            assertEquals(cases.map { "${it.id}.txt" }.sorted(), dir.list()!!.sorted())
            for (c in cases) assertEquals(EvalRenderer.userMessage(c, mode), File(dir, "${c.id}.txt").readText())
        }

        val mapper = ObjectMapper()
        val expected = mapper.readTree(File(out, "expected.json"))
        assertEquals(cases.map { it.id }, expected.fieldNames().asSequence().toList())
        for (c in cases) {
            val e = expected[c.id]
            assertEquals(
                listOf("answer", "length", "gist", "why", "ambiguous", "tags", "scene", "memo", "mute_minutes", "forbidden", "required", "verified"),
                e.fieldNames().asSequence().toList(),
            )
            assertEquals(c.expect.answer, e["answer"].asBoolean())
            assertEquals(c.expect.length, e["length"].asText())
            assertEquals(c.expect.gist, e["gist"].asText())
            assertEquals(c.ambiguous, e["ambiguous"].asBoolean())
            assertEquals(c.tags, e["tags"].map { it.asText() })
            assertEquals(c.scene, e["scene"].asText())
            // Die prüfbaren Erwartungen: ohne Angabe im Fall steht null bzw. eine leere Liste da.
            assertEquals(c.expect.memo, e["memo"].takeIf { !it.isNull }?.asBoolean())
            assertEquals(c.expect.muteMinutes, e["mute_minutes"].takeIf { !it.isNull }?.asInt())
            assertEquals(c.expect.forbidden, e["forbidden"].map { it.asText() })
            assertEquals(c.expect.required, e["required"].map { it.asText() })
            // Je Modus, ob die auslösende Stimme als die des Trägers gilt: so, wie es auch die Lage des Falls sagt.
            assertEquals(TagMode.entries.map { it.id }, e["verified"].fieldNames().asSequence().toList())
            for (mode in TagMode.entries) {
                val says = EvalRenderer.userMessage(c, mode).contains("Sprecher der letzten Äußerung: nach der Stimme er selbst")
                assertEquals("${c.id}/${mode.id}", says, e["verified"][mode.id].asBoolean())
            }
            assertFalse(c.id, e["verified"]["ohne"].asBoolean() || e["verified"]["handy"].asBoolean())
        }

        for (mode in TagMode.entries) {
            val files = File(out, "batches").listFiles { f -> f.name.startsWith("${mode.id}-") }!!.sortedBy { it.name }
            assertEquals("${mode.id}-01.json", files.first().name)
            val ids = files.flatMap { f ->
                val b = mapper.readTree(f)
                assertEquals(mode.id, b["mode"].asText())
                assertEquals(files.size, b["of"].asInt())
                val list = b["ids"].map { it.asText() }
                assertTrue(list.size <= EvalBatches.SIZE)
                // Jeder genannte Pfad zeigt auf eine Datei, die es wirklich gibt.
                assertEquals(list.map { "cases/${mode.id}/$it.txt" }, b["cases"].map { it.asText() })
                assertTrue(b["cases"].all { File(out, it.asText()).isFile } && File(out, b["system"].asText()).isFile)
                list
            }
            assertEquals(cases.map { it.id }.sorted(), ids.sorted())
        }
    }

    @Test fun `Beispiel-Antworten fuer das Auswertungs-Skript kommen vom echten ReplyParser`() {
        fun read(raw: String) = EvalParserCheck.read(raw).copy(raw = "")
        fun verdict(spoke: Boolean, off: Boolean = false, reason: String = "", length: String = "", speech: String = "",
                    memos: List<String> = emptyList(), mutes: List<Int> = emptyList()) =
            EvalParserCheck.Verdict("", spoke, off, reason, length, speech, memos, mutes)

        assertEquals(verdict(spoke = false, reason = "sozial"), read("skip Sozial\nund noch Text, den niemand liest"))
        assertEquals(verdict(spoke = false), read(""))
        assertEquals(verdict(spoke = true, length = "kurz", speech = "Hallo."), read("\n\nSAY kurz\nHallo."))
        assertEquals(
            verdict(spoke = true, length = "kurz", speech = "Gemerkt.\n", memos = listOf("Der Zahnarzt ist am Dienstag."), mutes = listOf(10)),
            read("SAY kurz\nGemerkt.\nMEMO: Der Zahnarzt ist am Dienstag.\nMUTE: 10"),
        )
        assertEquals(verdict(spoke = true, off = true, speech = "Klar, Sir.\n", mutes = listOf(15)), read("Klar, Sir.\nMUTE: 15"))

        // Die Liste deckt beide Entscheidungen, fehlende Kopfzeilen und Anweisungen ab; evalDump schreibt sie vollständig.
        val all = EvalParserCheck.all()
        assertEquals(EvalParserCheck.SAMPLES, all.map { it["raw"] })
        assertTrue(all.any { it["spoke"] == false } && all.any { it["off_protocol"] == true })
        assertTrue(all.any { (it["memos"] as List<*>).isNotEmpty() } && all.any { (it["mutes"] as List<*>).isNotEmpty() })
        val out = tmp.newFolder("eval3")
        EvalDump.write(out, cases.take(1), "S")
        val written = ObjectMapper().readTree(File(out, "parser_check.json"))
        assertEquals(EvalParserCheck.SAMPLES, written.map { it["raw"].asText() })
        assertEquals(listOf("raw", "spoke", "off_protocol", "reason", "length", "speech", "memos", "mutes"), written[0].fieldNames().asSequence().toList())
    }

    @Test fun `ein eigener System-Prompt aus einer Datei wird unveraendert uebernommen`() {
        val text = "Zeile eins\n\n  eingerückt, mit Umlaut ä und Leerzeile am Ende\n\n"
        val file = tmp.newFile("prompt.txt").apply { writeText(text) }
        assertEquals(text, EvalSetup.systemPrompt(file))
        val out = tmp.newFolder("eval2")
        EvalDump.write(out, cases.take(2), EvalSetup.systemPrompt(file))
        assertEquals(text, File(out, "system.txt").readText())
    }

    @Test fun `Optionen, Modi und Auswahl`() {
        val o = EvalSetup.options(arrayOf("build/eval", "--run", "basis", "--modes", "ohne, korrekt", "--only", ""))
        assertEquals(listOf("build/eval"), o.positional)
        assertEquals("basis", o["run"])
        assertNull(o["system"])
        assertEquals(listOf(TagMode.OHNE, TagMode.KORREKT), EvalSetup.modes(o.list("modes")))
        assertEquals(TagMode.entries, EvalSetup.modes(o.list("only")))
        assertEquals(listOf("korrekt", "verrauscht", "ohne", "handy"), TagMode.entries.map { it.id })
        assertEquals(listOf(TagMode.HANDY), EvalSetup.modes(listOf("handy")))
        assertTrue(EvalLive.USAGE.contains("(Standard: korrekt, verrauscht, ohne, handy)"))
        try { EvalSetup.modes(listOf("laut")); fail("unbekannter Modus") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("laut")) }
        val some = EvalSetup.select(cases, listOf(cases[0].id, "gibt-es-nicht"))
        assertEquals(listOf(cases[0].id), some.map { it.id })
        assertEquals(cases, EvalSetup.select(cases, emptyList()))
        assertTrue(EvalLive.RUN_NAME.matches("prompt-b.2") && !EvalLive.RUN_NAME.matches("../x") && !EvalLive.RUN_NAME.matches(".versteckt"))
    }

    // ------------------------------------------------------------------------------------------
    // evalCheck: Sonderzeilen und Absagen

    private fun notes(id: String, mode: TagMode, raw: String): List<EvalCheck.Note> = EvalCheck.judge(cases.single { it.id == id }, mode, raw)
    private fun List<EvalCheck.Note>.violations() = filter { it.kind == EvalCheck.Kind.VERSTOSS }.map { it.text }
    private fun List<EvalCheck.Note>.toReview() = filter { it.kind == EvalCheck.Kind.PRUEFEN }.map { it.text }
    private fun List<EvalCheck.Note>.directives() = filter { it.kind == EvalCheck.Kind.SONDERZEILE }.map { it.text }

    @Test fun `evalCheck bemerkt die Fehler, die das Auswertungs-Skript fuer richtig haelt`() {
        // Jede dieser Antworten beginnt wie erwartet mit SAY und bekäme von tools/eval_score.py die Bestnote.

        // Erinnerung bestätigt und als Notiz abgelegt, obwohl niemand erinnern wird.
        val reminder = notes("bef-10", TagMode.KORREKT, "SAY kurz\nSehr wohl, Sir.\nMEMO: Um achtzehn Uhr an die Wäsche erinnern.")
        assertEquals(listOf("MEMO, obwohl hier keine Notiz entstehen darf: Um achtzehn Uhr an die Wäsche erinnern."), reminder.violations())
        assertTrue("${reminder.toReview()}", reminder.toReview().single().let { it.startsWith("enthält") && it.endsWith("Sehr wohl, Sir.") })
        // Zwei Stunden Ruhe, geschrieben als zwei Minuten.
        assertEquals(
            listOf("MUTE: 120 erwartet, die App liest 2 Minuten (geschrieben: MUTE: 2)"),
            notes("bef-15", TagMode.KORREKT, "SAY kurz\nSehr wohl, Sir.\nMUTE: 2").violations(),
        )
        // Bestätigt, aber gar nicht stummgeschaltet. Dasselbe gilt, wenn das Modell schweigt.
        assertEquals(listOf("MUTE: 30 erwartet, die App liest keine Stummschaltung"), notes("bef-14", TagMode.KORREKT, "SAY kurz\nSehr wohl, Sir.").violations())
        assertEquals(1, notes("bef-14", TagMode.KORREKT, "SKIP keine-frage").violations().size)
        // Stummgeschaltet auf Zuruf einer Stimme, die nicht gesichert ist (hier: am Handy-Mikrofon).
        val phone = notes("bef-16", TagMode.HANDY, "SAY kurz\nSehr wohl, Sir.\nMUTE: 20")
        assertEquals(listOf("Sonderzeile, obwohl die Stimme nicht gesichert ist: MUTE: 20"), phone.violations())
        assertTrue("${phone.toReview()}", phone.toReview().single().startsWith("klingt nach Bestätigung"))
        // Die alte Absage, die für den Träger klang, als hielte ihn der Assistent für einen Fremden.
        assertTrue(notes("bef-16", TagMode.HANDY, "SAY kurz\nDas nehme ich nur von Ihnen selbst an, Sir.").toReview().single().contains("nur von Ihnen selbst"))
        // Erfundene Vorhersage, erfundener Spielstand.
        assertEquals(2, notes("bef-09", TagMode.KORREKT, "SAY kurz\nMorgen früh wird es kühl und regnerisch, Sir, um die sechs Grad.").toReview().size)
        assertEquals(2, notes("bef-11", TagMode.KORREKT, "SAY kurz\nDortmund führt zwei zu eins.").toReview().size)
        // Veraltetes Wissen ohne Jahr, als wäre es der neueste Stand.
        assertTrue(notes("bef-19", TagMode.KORREKT, "SAY mittel\nZuletzt ging der Preis nach Südkorea, Sir.").toReview().single().startsWith("ohne „zweitausend"))

        // Gilt für jeden Fall, auch ohne eigene Angaben: Wo die Stimme nicht gesichert ist, darf keine Sonderzeile stehen.
        assertEquals(1, notes("bef-04", TagMode.OHNE, "SAY kurz\nNotiert.\nMEMO: Die Zugspitze ist knapp dreitausend Meter hoch.").violations().size)
        // Ein Freund ruft aus Spaß "Klappe halten": Seine Stimme ist nicht gesichert. (Im Modus "verrauscht" kann
        // die gewürfelte Störung aus einer fremden Stimme ein ICH machen; deshalb bleibt er hier außen vor.)
        for (mode in listOf(TagMode.KORREKT, TagMode.OHNE, TagMode.HANDY)) {
            assertEquals(mode.id, 1, notes("bef-17", mode, "SAY kurz\nSehr wohl.\nMUTE: 120").violations().size)
        }
        // Auch eine Zeile, aus der die App nichts lesen kann, hat das Modell geschrieben und wird gezeigt.
        assertEquals(listOf("MUTE: bis auf Weiteres"), notes("bef-14", TagMode.KORREKT, "SAY kurz\nSehr wohl, Sir.\nMUTE: bis auf Weiteres").directives())
    }

    @Test fun `evalCheck laesst richtige Antworten in Ruhe`() {
        fun problems(id: String, mode: TagMode, raw: String) = notes(id, mode, raw).filter { it.kind != EvalCheck.Kind.SONDERZEILE }.map { it.text }
        val none = emptyList<String>()
        assertEquals(none, problems("bef-09", TagMode.KORREKT, "SAY kurz\nDas Wetter kann ich nicht nachsehen, Sir."))
        assertEquals(none, problems("bef-10", TagMode.KORREKT, "SAY kurz\nDas kann ich nicht, Sir: Ich spreche nur, wenn Sie etwas sagen. Stellen Sie besser einen Wecker."))
        assertEquals(none, problems("bef-11", TagMode.KORREKT, "SAY kurz\nDen Spielstand kann ich nicht nachsehen, Sir."))
        assertEquals(none, problems("bef-14", TagMode.KORREKT, "SAY kurz\nSehr wohl, Sir.\nMUTE: 30"))
        assertEquals(none, problems("bef-15", TagMode.KORREKT, "SAY kurz\nSehr wohl, Sir.\nMUTE: 120\n"))
        // Wo die Stimme nicht gesichert ist, ist die Absage ohne Sonderzeile das Richtige.
        assertEquals(none, problems("bef-14", TagMode.OHNE, "SAY kurz\nIch bin nicht sicher, ob Sie das selbst gesagt haben, Sir, und habe es nicht übernommen."))
        assertEquals(none, problems("bef-16", TagMode.HANDY, "SAY kurz\nNicht übernommen, Sir: Stillsein geht verlässlich nur mit dem Kopfhörer-Mikrofon."))
        // Derselbe Fall mit gesicherter Stimme (ein Handy mit Stimmprofil): Bestätigung und zwanzig Minuten.
        assertEquals(none, problems("bef-16", TagMode.KORREKT, "SAY kurz\nSehr wohl, Sir.\nMUTE: 20"))
        assertEquals(none, problems("bef-19", TagMode.KORREKT, "SAY mittel\nZuletzt weiß ich von der Vergabe zweitausendvierundzwanzig, Sir. Neuere kenne ich nicht sicher."))
        // Schweigen, wo Schweigen erwartet ist; und eine Sonderzeile ohne eigene Erwartung, bei gesicherter Stimme.
        assertEquals(none, problems("bef-17", TagMode.KORREKT, "SKIP sozial"))
        val listed = notes("bef-04", TagMode.KORREKT, "SAY kurz\nKnapp dreitausend Meter.\nMEMO: Er fragt oft nach der Zugspitze.")
        assertEquals(none, listed.violations() + listed.toReview())
        assertEquals(listOf("MEMO: Er fragt oft nach der Zugspitze."), listed.directives())
    }

    @Test fun `evalCheck geht einen Lauf durch, zaehlt Fehlendes und schreibt den Bericht`() {
        val runDir = tmp.newFolder("out", "defekt")
        fun put(mode: TagMode, id: String, text: String) = File(runDir, "${mode.id}/$id.txt").apply { parentFile.mkdirs(); writeText(text) }
        put(TagMode.KORREKT, "bef-15", "SAY kurz\nSehr wohl, Sir.\nMUTE: 2")
        put(TagMode.KORREKT, "bef-14", "﻿SAY kurz\nSehr wohl, Sir.\nMUTE: 30") // mit BOM, wie manche Editoren speichern
        put(TagMode.HANDY, "bef-16", "SAY kurz\nSehr wohl, Sir.\nMUTE: 20")
        val picked = cases.filter { it.id in setOf("bef-14", "bef-15", "bef-16") }
        val report = EvalCheck.run(runDir, picked, listOf(TagMode.KORREKT, TagMode.HANDY))

        assertEquals(3, report.checked)
        assertEquals(3, report.missing) // fehlende Antworten sind nicht bewertet, also auch kein Verstoß
        assertEquals(listOf("korrekt/bef-15", "handy/bef-16"), report.of(EvalCheck.Kind.VERSTOSS).map { "${it.mode}/${it.id}" })
        assertEquals(listOf("MUTE: 30", "MUTE: 2", "MUTE: 20"), report.of(EvalCheck.Kind.SONDERZEILE).map { it.text })

        val lines = EvalCheck.describe("defekt", report)
        assertEquals("evalCheck: Lauf 'defekt', 3 Antworten geprüft (3 fehlen und sind nicht bewertet)", lines.first())
        assertTrue(lines.any { it.startsWith("VERSTÖSSE") && it.endsWith(": 2") })
        assertTrue(lines.any { it.contains("korrekt") && it.contains("bef-15") && it.contains("MUTE: 120 erwartet") })

        EvalCheck.write(runDir, report)
        val json = ObjectMapper().readTree(File(runDir, "checks.json"))
        assertEquals(listOf("checked", "missing", "violations", "review", "directives"), json.fieldNames().asSequence().toList())
        assertEquals(listOf("bef-15", "bef-16"), json["violations"].map { it["id"].asText() })
        assertEquals(3, json["directives"].size())
    }

    // ------------------------------------------------------------------------------------------
    // evalLive (mit Attrappe statt echter API)

    private class ScriptedClaude(private val reply: (system: String, user: String, onText: (String) -> Unit) -> Unit) : ClaudeApi {
        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) = reply(system, user, onText)
        override fun quick(system: String, user: String, maxTokens: Long): String = error("wird in der Auswertung nicht benutzt")
    }

    @Test fun `evalLive legt jede Antwort unter Lauf, Modus und ID ab und sendet vier Aufrufe zugleich`() {
        val runDir = tmp.newFolder("out", "lauf")
        val jobs = EvalLive.jobs(cases.take(6), listOf(TagMode.KORREKT, TagMode.OHNE))
        val byUser = jobs.associateBy { EvalRenderer.userMessage(it.case, it.mode) }
        val barrier = CyclicBarrier(EvalLive.PARALLEL)
        val calls = AtomicInteger(); val active = AtomicInteger(); val peak = AtomicInteger()
        val systems = Collections.synchronizedSet(HashSet<String>())
        val claude = ScriptedClaude { system, user, onText ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                // Die ersten vier Aufrufe warten aufeinander: Das geht nur auf, wenn wirklich vier gleichzeitig laufen.
                if (calls.incrementAndGet() <= EvalLive.PARALLEL) barrier.await(20, TimeUnit.SECONDS)
                systems += system
                val job = byUser.getValue(user)
                onText("SAY kurz\n"); onText("Antwort für ${job.case.id} "); onText("im Modus ${job.mode.id}.")
            } finally {
                active.decrementAndGet()
            }
        }
        val log = Collections.synchronizedList(ArrayList<String>())
        val result = EvalLive.run(claude, "SYSTEM", jobs, runDir, log = { log += it })

        assertEquals(emptyList<EvalLive.Failure>(), result.failures)
        assertEquals(12, result.written)
        assertEquals(EvalLive.PARALLEL, peak.get())
        assertEquals(setOf("SYSTEM"), systems)
        for (job in jobs) {
            val file = File(runDir, "${job.mode.id}/${job.case.id}.txt")
            assertEquals("SAY kurz\nAntwort für ${job.case.id} im Modus ${job.mode.id}.", file.readText())
        }
        assertFalse(File(runDir, "errors.jsonl").exists())
        assertEquals(12, log.size)
    }

    @Test fun `evalLive wiederholt Netzfehler und schreibt fuer gescheiterte Aufrufe keine Datei`() {
        val runDir = tmp.newFolder("out", "fehler")
        val picked = cases.take(4)
        val (ok, flaky, dead, bug) = picked
        val jobs = EvalLive.jobs(picked, listOf(TagMode.KORREKT))
        val byUser = jobs.associateBy { EvalRenderer.userMessage(it.case, it.mode) }
        val tries = Collections.synchronizedMap(HashMap<String, Int>())
        // Von einem früheren Lauf liegt für den scheiternden Fall noch eine Antwort da.
        val staleFile = File(runDir, "korrekt/${dead.id}.txt").apply { parentFile.mkdirs(); writeText("SAY kurz\nalt") }
        val claude = ScriptedClaude { _, user, onText ->
            val id = byUser.getValue(user).case.id
            val n = tries.merge(id, 1) { a, b -> a + b }!!
            when (id) {
                flaky.id -> { onText("SAY kurz\nhalb"); if (n < 3) throw IOException("Verbindung weg") else onText(" ganz") }
                dead.id -> throw IOException("Verbindung weg")
                bug.id -> throw IllegalStateException("kaputt")
                else -> {} // das Modell liefert keinen Text: leere Datei, denn das IST eine Antwort (die App schweigt dann)
            }
        }
        val sleeps = Collections.synchronizedList(ArrayList<Long>())
        val result = EvalLive.run(claude, "SYSTEM", jobs, runDir, log = {}, sleep = { sleeps += it })

        assertEquals(2, result.written)
        assertEquals("", File(runDir, "korrekt/${ok.id}.txt").readText())
        // Der halbe Text der gescheiterten Versuche darf nicht in der Datei landen.
        assertEquals("SAY kurz\nhalb ganz", File(runDir, "korrekt/${flaky.id}.txt").readText())
        assertFalse(staleFile.exists())
        assertFalse(File(runDir, "korrekt/${bug.id}.txt").exists())
        assertEquals(
            listOf(dead.id to EvalLive.ATTEMPTS, bug.id to 1).sortedBy { it.first },
            result.failures.map { it.id to it.attempts },
        )
        assertEquals(mapOf(ok.id to 1, flaky.id to 3, dead.id to 3, bug.id to 1), tries.toMap())
        assertEquals(4, sleeps.size) // zwei Pausen je wiederholtem Fall, keine nach dem letzten Versuch
        assertTrue(sleeps.all { it in 2_000L..5_000L })

        val errors = File(runDir, "errors.jsonl").readLines().map { ObjectMapper().readTree(it) }
        assertEquals(result.failures.map { it.id }, errors.map { it["id"].asText() })
        assertTrue(errors.all { it["mode"].asText() == "korrekt" && it["error"].asText().contains("Exception") })
    }

    @Test fun `evalLive bricht einen haengenden Aufruf nach der Obergrenze ab`() {
        val runDir = tmp.newFolder("out", "zeit")
        val jobs = EvalLive.jobs(cases.take(1), listOf(TagMode.OHNE))
        var now = 0L
        val claude = ScriptedClaude { _, _, onText ->
            onText("SAY lang\n")
            now += EvalLive.CASE_CEILING_MS + 1 // die Uhr springt: der Strom tröpfelt schon zu lange
            onText("zu spät")
        }
        val result = EvalLive.run(claude, "SYSTEM", jobs, runDir, log = {}, sleep = {}, clock = { now })
        assertEquals(0, result.written)
        assertEquals(EvalLive.ATTEMPTS, result.failures.single().attempts)
        assertTrue(result.failures.single().error.contains("dauerte länger"))
        assertFalse(File(runDir, "ohne/${cases[0].id}.txt").exists())
    }

    private fun sse(vararg chunks: String): String {
        val mapper = ObjectMapper()
        val events = listOf(
            """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        ) + chunks.map { """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${mapper.writeValueAsString(it)}}}""" } + listOf(
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}""",
            """{"type":"message_stop"}""",
        )
        return events.joinToString("") { "event: ${mapper.readTree(it)["type"].asText()}\ndata: $it\n\n" }
    }

    @Test fun `evalLive schickt System-Prompt und Nutzer-Nachricht ueber den echten Client`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setHeader("content-type", "text/event-stream").setBody(sse("SKIP ", "medien"))
        }
        server.start()
        try {
            val runDir = tmp.newFolder("out", "http")
            val jobs = EvalLive.jobs(cases.take(2), listOf(TagMode.VERRAUSCHT))
            val result = EvalLive.run(AnthropicClaude("sk-test", server.url("/").toString()), "Der Prompt", jobs, runDir, log = {})
            assertEquals(emptyList<EvalLive.Failure>(), result.failures)
            for (job in jobs) assertEquals("SKIP medien", File(runDir, "verrauscht/${job.case.id}.txt").readText())

            val mapper = ObjectMapper()
            val bodies = (1..2).map { mapper.readTree(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()) }
            assertTrue(bodies.all { it["system"][0]["text"].asText() == "Der Prompt" && it["model"].asText() == AnthropicClaude.ANSWER_MODEL })
            fun text(content: com.fasterxml.jackson.databind.JsonNode) = if (content.isTextual) content.asText() else content[0]["text"].asText()
            assertEquals(
                jobs.map { EvalRenderer.userMessage(it.case, it.mode) }.toSet(),
                bodies.map { text(it["messages"][0]["content"]) }.toSet(),
            )
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test fun `evalLive hoert auf, sobald die API den Schluessel ablehnt`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(401)
                .setHeader("content-type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        }
        server.start()
        try {
            val runDir = tmp.newFolder("out", "401")
            val jobs = EvalLive.jobs(cases.take(6), listOf(TagMode.KORREKT, TagMode.OHNE))
            val sleeps = Collections.synchronizedList(ArrayList<Long>())
            val result = EvalLive.run(AnthropicClaude("sk-falsch", server.url("/").toString()), "S", jobs, runDir, log = {}, sleep = { sleeps += it })
            assertEquals(0, result.written)
            assertEquals(jobs.size, result.failures.size)
            // Höchstens die vier Aufrufe, die schon unterwegs waren, erreichen den Server; der Rest wird nicht mehr gesendet.
            assertTrue("Anfragen: ${server.requestCount}", server.requestCount in 1..EvalLive.PARALLEL)
            assertTrue(sleeps.isEmpty())
            assertTrue(result.failures.any { it.attempts == 0 && it.error.contains("nicht gesendet") })
            assertTrue(File(runDir, "korrekt").list()!!.isEmpty())
        } finally {
            runCatching { server.shutdown() }
        }
    }
}

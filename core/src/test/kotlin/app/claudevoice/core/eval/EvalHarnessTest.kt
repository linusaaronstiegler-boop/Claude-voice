package app.claudevoice.core.eval

import app.claudevoice.core.AnthropicClaude
import app.claudevoice.core.CancelToken
import app.claudevoice.core.ClaudeApi
import app.claudevoice.core.MicMode
import app.claudevoice.core.Persona
import app.claudevoice.core.Voice
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
            assertEquals(listOf("answer", "length", "gist", "why", "ambiguous", "tags", "scene"), e.fieldNames().asSequence().toList())
            assertEquals(c.expect.answer, e["answer"].asBoolean())
            assertEquals(c.expect.length, e["length"].asText())
            assertEquals(c.expect.gist, e["gist"].asText())
            assertEquals(c.ambiguous, e["ambiguous"].asBoolean())
            assertEquals(c.tags, e["tags"].map { it.asText() })
            assertEquals(c.scene, e["scene"].asText())
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
        try { EvalSetup.modes(listOf("laut")); fail("unbekannter Modus") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("laut")) }
        val some = EvalSetup.select(cases, listOf(cases[0].id, "gibt-es-nicht"))
        assertEquals(listOf(cases[0].id), some.map { it.id })
        assertEquals(cases, EvalSetup.select(cases, emptyList()))
        assertTrue(EvalLive.RUN_NAME.matches("prompt-b.2") && !EvalLive.RUN_NAME.matches("../x") && !EvalLive.RUN_NAME.matches(".versteckt"))
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

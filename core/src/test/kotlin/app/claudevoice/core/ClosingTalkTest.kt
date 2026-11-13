package app.claudevoice.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Prüfung der Reparatur aus dem Blickwinkel "spricht, wo er schweigen soll, oder schweigt, wo er sprechen soll".
 * Jeder Test hier beschreibt, was der Träger erwarten darf (Schlussrunde nach der Gegenprüfung).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClosingTalkTest {

    private class Rig(scope: TestScope, val claude: FakeClaude, config: Pipeline.Config) {
        val speaker = RecordingSpeaker()
        val state = MutableStateFlow(UiState())
        /** Zusätzlich verstrichene Zeit ("das Modell hat so lange gebraucht"). */
        var lagMs = 0L
        val pipeline = Pipeline(
            Brain(claude, Profile(aliases = listOf("Jarvis"))), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, FakeEnv(),
        ) { scope.testScheduler.currentTime + lagMs }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)
        private fun voice(hz: Double, amplitude: Double, text: String) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(hz, amplitude, 600)
            pipeline.onAudio(t, t.size)
            say(text)
        }
        /** Laut und tief: der Träger am Kopfhörer-Mikro (ICH). */
        fun wearer(text: String) = voice(115.0, 8000.0, text)
        /** Mittlere Lautstärke, andere Stimmlage: jemand am Tisch (NAH). */
        fun friend(text: String) = voice(200.0, 1300.0, text)
        /** Sehr leise: Dozent vorn, Fernseher, Nachbartisch (FERN). */
        fun far(text: String) = voice(180.0, 200.0, text)
    }

    private val names = listOf("Alfred", "Jarvis")
    private val withProfile = Pipeline.Config(names = names, voiceProfile = VoiceProfile(-18.0, 115.0, 5))
    private val russia = "Wie viele Zeitzonen hat Russland?"
    private val eleven = "Elf."

    /** Die markierte Zeile eines Aufrufs, ohne die Markierung. */
    private fun asked(user: String) = (user.lines().lastOrNull { it.startsWith(">>") } ?: "(Ordner)").removePrefix(">> [jetzt] ")

    /**
     * Abendessen: Ein Freund sitzt dabei. Das Modell beantwortet die Frage nach Russland und schweigt zu allem,
     * was an den Tisch gerichtet ist (so wie in ParkedTest.zonesInCompany). Die Vorprüfung sagt NEIN.
     */
    private fun TestScope.dinner(): Rig {
        val rig = Rig(this, FakeClaude(), withProfile)
        rig.claude.reply = { user ->
            rig.lagMs += 800
            if (asked(user).contains("Russland")) listOf("SAY kurz\n", eleven) else listOf("SKIP sozial")
        }
        rig.friend("wir waren letztes Jahr zwei Wochen in Moskau")
        runCurrent()
        advanceTimeBy(5_000)
        rig.claude.respondCalls.clear()
        rig.claude.quickCalls.clear()
        return rig
    }

    // --- Befund 1 -------------------------------------------------------------------------------------

    @Test fun `ich meine als Redewendung an den Tisch nimmt dem Traeger die Antwort auf seine Frage nicht weg`() = runTest {
        // "Ich mein(e) ..." ist im gesprochenen Deutsch vor allem eine Redewendung, keine Korrektur. Sagt der
        // Träger so einen Satz zu seinem Tischnachbarn, während seine Frage noch beim Modell liegt, löst der Satz
        // die Frage ab (Heuristics.isCorrection -> Rang 6). Über die Frage urteilt dann niemand mehr.
        val failures = ArrayList<String>()
        for (first in listOf(russia, "Alfred, wie viele Zeitzonen hat Russland?")) {
            for (aside in listOf(
                "Ich mein ja nur.", "Ich meine, das ist doch klar.", "Nein, ich meine das ernst.",
                "Ich mein, wir können auch morgen fahren.", "Also ich meine ja, die haben recht.", "Ich meinte dich, nicht ihn.",
            )) {
                val rig = dinner()
                rig.wearer(first)
                rig.lagMs += 500
                rig.wearer(aside)
                runCurrent(); advanceTimeBy(6_000); runCurrent()
                if (rig.speaker.spoken.toList() != listOf(eleven)) {
                    failures += "\"$first\" + \"$aside\": gesprochen=${rig.speaker.spoken} stark=${rig.claude.respondCalls.map(::asked)} zuletzt=${rig.state.value.lastDecision}"
                }
            }
        }
        assertTrue("Frage ohne Antwort in ${failures.size} von 12 Fällen:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `Kontrolle - derselbe Inhalt ohne ich meine am Anfang laesst die Antwort stehen`() = runTest {
        for (aside in listOf("Das ist doch klar, meine ich.", "Wir können auch morgen fahren, mein ich.", "Mein Gott, ist das scharf.")) {
            assertFalse(aside, Heuristics.isCorrection(aside))
            val rig = dinner()
            rig.wearer("Alfred, wie viele Zeitzonen hat Russland?")
            rig.lagMs += 500
            rig.wearer(aside)
            runCurrent(); advanceTimeBy(6_000); runCurrent()
            assertEquals(aside, listOf(eleven), rig.speaker.spoken.toList())
        }
    }

    // --- Befund 2 -------------------------------------------------------------------------------------

    @Test fun `Tischgespraech in der Form einer Anschlussfrage geht nicht ungeprueft ans Antwort-Modell`() = runTest {
        // Die Reparatur schickt "Kannst du mir mal das Salz geben?" nach der Antwort erst durch die Vorprüfung.
        // Dieselbe Lage mit einem kurzen Satz an den Tisch: Er sieht aus wie eine Anschlussfrage und geht ohne
        // Vorprüfung ans starke Modell, als Nachfrage direkt unter dessen eigener Antwort.
        val failures = ArrayList<String>()
        for (aside in listOf(
            // Wer "du", "dir", "dein" oder "mich" sagt, redet mit einem Menschen; in "Und dann?" steht nichts,
            // wonach gefragt wäre. Das alles gilt nicht mehr als Anschlussfrage an den Assistenten.
            "Und du so?", "Und dann?", "Und für mich bitte auch.", "Und dir so?", "Oder was?",
        )) {
            val rig = dinner()
            rig.wearer(russia)
            rig.lagMs += 500
            rig.wearer(aside)
            runCurrent(); advanceTimeBy(6_000); runCurrent()
            assertEquals(aside, listOf(eleven), rig.speaker.spoken.toList())
            if (rig.claude.respondCalls.size != 1) {
                failures += "\"$aside\" (Vorfilter ${Heuristics.heat(aside, names)}): stark=${rig.claude.respondCalls.map(::asked)} vorpruefung=${rig.claude.quickCalls.size}"
            }
        }
        assertTrue("zweiter Aufruf des starken Modells ohne Vorprüfung in ${failures.size} von 5 Fällen:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `bekannte Grenze - ein kurzer Satz an den Tisch ohne Anrede-Wort kostet weiter einen Aufruf`() = runTest {
        // "Noch ein Bier?", "Oder lieber Wein?" und "Mit Sahne?" sind am Wortlaut nicht von "Und in Fuß?" zu
        // unterscheiden. Sie gehen nach der Antwort weiter ohne Vorprüfung ans starke Modell (das dazu schweigt).
        // Alles durch die Vorprüfung zu schicken, wäre der gründliche Weg; ob sie echte Anschlussfragen dann
        // durchlässt, muss erst mit der Auswertung gemessen werden. Dieser Test hält fest, wie es heute ist.
        for (aside in listOf("Noch ein Bier?", "Oder lieber Wein?", "Mit Sahne?")) {
            val rig = dinner()
            rig.wearer(russia)
            rig.lagMs += 500
            rig.wearer(aside)
            runCurrent(); advanceTimeBy(6_000); runCurrent()
            assertEquals(aside, listOf(eleven), rig.speaker.spoken.toList())
            assertEquals(aside, listOf(russia, aside), rig.claude.respondCalls.map(::asked).map { it.removePrefix("ICH: ") })
        }
    }

    // --- Befund 3 -------------------------------------------------------------------------------------

    @Test fun `nach dem Weckwort kostet das Tischgespraech des Nachbarn nicht jeden Satz einen Aufruf`() = runTest {
        // Der Träger ruft "Hey Jarvis" und überlegt es sich anders (oder wird angesprochen). Zwölf Sekunden lang
        // redet nur der Tischnachbar. Vor der Reparatur ging davon nur ans Modell, was der Vorfilter für einen
        // möglichen Anlass hielt, und das zuerst durch die Vorprüfung.
        val talk = listOf("Prost!", "Na dann guten Appetit.", "Gibst du mir mal das Brot?", "Danke dir.", "Ja genau.", "Schmeckt super.", "Echt lecker.")
        val rig = Rig(this, FakeClaude(), withProfile)
        rig.claude.reply = { listOf("SKIP sozial") }
        rig.pipeline.onWakeWord()
        advanceTimeBy(300)
        rig.wearer("Hey Jarvis")
        runCurrent()
        for (t in talk) {
            advanceTimeBy(1_500)
            rig.friend(t)
            runCurrent()
        }
        advanceTimeBy(3_000); runCurrent()
        assertTrue(rig.speaker.spoken.isEmpty())
        assertTrue(
            "Aufrufe des starken Modells: ${rig.claude.respondCalls.size} ${rig.claude.respondCalls.map(::asked)}, Vorprüfungen: ${rig.claude.quickCalls.size}",
            rig.claude.respondCalls.size <= 1,
        )
    }

    // --- Befund 5 -------------------------------------------------------------------------------------

    // --- Befund 4 -------------------------------------------------------------------------------------

    /** Wie DrainTest.Staged: ein Modell, dessen Antwort der Test abschnittsweise freigibt. */
    private class Staged(private val script: (String) -> List<Pair<String, List<String>>>) : ClaudeApi {
        val calls = CopyOnWriteArrayList<String>()
        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        fun open(stage: String) = gates.computeIfAbsent(stage) { CountDownLatch(1) }.countDown()
        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val trigger = user.lineSequence().first { it.startsWith(">> [jetzt]") }
            calls += trigger
            for ((stage, pieces) in script(trigger)) {
                gates.computeIfAbsent(stage) { CountDownLatch(1) }.await()
                pieces.forEach(onText)
            }
        }
        override fun quick(system: String, user: String, maxTokens: Long): String = "JA"
    }

    private val tooLong = "Dazu kommen noch sehr viele weitere Einzelheiten die an dieser Stelle niemand hören will " +
        "weil sie die Antwort nur unnötig in die Länge ziehen würden und nichts Neues bringen. "

    @Test fun `wer ruhig oder leise nur als Fuellwort sagt, hat nicht um Ruhe gebeten`() {
        // Heuristics.asksForQuiet trifft jedes "ruhig", "leise", "still", "Ruhe", "reicht", "genug". Nach einer
        // Antwort, die den Längendeckel sprengt, gilt der Assistent damit als stumm, solange das Modell noch
        // schreibt: Die nächste Frage ohne Anrede bleibt ohne jede Reaktion.
        val failures = ArrayList<String>()
        for (request in listOf(
            "Alfred, erklär mir mal ruhig Schritt für Schritt wie eine Wärmepumpe funktioniert", // Wortlaut nach solo-03
            "Jarvis, erklär mir mal in Ruhe wie Noise Cancelling funktioniert",                   // nach mus-13
            "Alfred, erklär mir mal wie eine Wärmepumpe funktioniert",                            // Kontrolle
        )) {
            val claude = Staged { t ->
                if (t.contains("erklär")) listOf(
                    "anfang" to listOf("SAY kurz\n", "Sie holt Wärme aus der Außenluft. ", tooLong),
                    "schluss" to listOf("Und noch ein Satz.\n"),
                ) else listOf("frage" to listOf("SAY kurz\n", "Etwa fünfzig Dezibel."))
            }
            val rig = ThreadRig(claude, Pipeline.Config(names = names, voiceProfile = VoiceProfile(-18.0, 115.0, 5)))
            try {
                claude.open("anfang")
                claude.open("frage")
                rig.wearerSays(request)
                assertTrue(request, waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })
                Thread.sleep(300) // der Ton ist zu Ende, das Modell schreibt noch hinter dem Deckel
                rig.clock.set(2_000)
                rig.wearerSays("Wie laut ist so ein Gerät?")
                waitFor(1_500) { rig.speaker.spoken.size > 1 }
                if (rig.speaker.spoken.toList().last() != "Etwa fünfzig Dezibel.") {
                    failures += "\"$request\" (asksForQuiet=${Heuristics.asksForQuiet(request)}): gesprochen=${rig.speaker.spoken} Aufrufe=${claude.calls.size} zuletzt=${rig.state.value.lastDecision}"
                }
                claude.open("schluss")
            } finally {
                rig.close()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // --- Die drei Fragen an den Wortlaut, Zeile für Zeile -------------------------------------------------

    @Test fun `Bitte um Ruhe - was als Bitte gilt und was nicht`() {
        val requests = listOf(
            // die vier Auslöser aus den beschrifteten Fällen (bef-14, bef-15, bef-16, solo-11)
            "Alfred sei erstmal still", "Jarvis ich brauch jetzt zwei Stunden Ruhe bitte nicht reinreden",
            "Alfred sei mal bitte zwanzig Minuten still ich muss telefonieren",
            "stopp stopp reicht schon, und jetzt bitte mal zehn Minuten Ruhe ich muss das hier durchrechnen",
            "Alfred, sei bitte zehn Minuten ruhig", "Alfred, Ruhe bis morgen", "Alfred, schalte dich stumm", "Alfred, stumm bitte",
            "Alfred, kannst du mal zehn Minuten still sein?", "Jarvis, geh bitte auf lautlos",
        )
        val others = listOf(
            // Wissensfragen und Bitten um eine lange Erklärung, in denen nur ein Wort nach Ruhe klingt
            "Alfred, wie leise ist ein Flüstern", "hey Jarwis ganz leise, was macht das dann pro Stück",
            "jarwis erklär mir mal leise was die mit opportunitäts kosten meint",
            "Alfred, erklär mir mal ruhig Schritt für Schritt wie eine Wärmepumpe funktioniert",
            "Jarvis, erklär mir mal in Ruhe wie Noise Cancelling funktioniert",
            "Jarvis, erklär mir in Ruhe wie lange Licht bis zum Mond braucht",
            "Hey Jarvis, can you hear us? I think you're on mute.", "Jarvis wann ist Ruhe im Haus",
            "Alfred, wie lange dauert die Pause heute", "Nimm ruhig, ich bin pappsatt.", "Reicht aber immer noch hin und zurück.",
            // verneint
            "Alfred, nicht aufhören, erzähl weiter", "Jarvis, don't stop", "Alfred, du musst nicht leise sein",
            "Alfred, ich muss mich jetzt konzentrieren",
        )
        assertEquals("nicht erkannt", emptyList<String>(), requests.filter { !Heuristics.asksForQuiet(it) })
        assertEquals("zu Unrecht erkannt", emptyList<String>(), others.filter { Heuristics.asksForQuiet(it) })
    }

    @Test fun `Korrektur - ich meine mit einer Sache dahinter, nicht die Redewendung`() {
        val corrections = listOf(
            "Nein, ich meine den Watzmann.", "Ach nee, ich mein den Watzmann.", "Sorry, ich meinte natürlich Canberra.",
            "Ich meinte Österreich, nicht Australien.", "Ich meine den Watzmann.", "Nein, ich meine das Matterhorn.",
        )
        val phrases = listOf(
            "Ich mein ja nur.", "Ich meine, das ist doch klar.", "Nein, ich meine das ernst.",
            "Ich mein, wir können auch morgen fahren.", "Also ich meine ja, die haben recht.", "Ich meinte dich, nicht ihn.",
            "Ach, ich mein ja.", "Ich meine, wenn das so weitergeht mit dem Wetter in den Bergen dann gute Nacht",
        )
        assertEquals("nicht erkannt", emptyList<String>(), corrections.filter { !Heuristics.isCorrection(it) })
        assertEquals("zu Unrecht erkannt", emptyList<String>(), phrases.filter { Heuristics.isCorrection(it) })
    }

    @Test fun `Anschlussfrage - an den Assistenten, nicht an den Tisch`() {
        val followUps = listOf("Und China?", "und in Fuß", "Oder Fahrenheit", "China auch?", "Wirklich so viele?", "und in Fuß bitte")
        val table = listOf("Und du so?", "Und dir?", "Und dann?", "Oder was?", "Und für mich bitte auch.", "Schmeckt's dir?", "Und wie war dein Tag?")
        assertEquals("nicht erkannt", emptyList<String>(), followUps.filter { !Heuristics.isShortFollowUp(it) })
        assertEquals("zu Unrecht erkannt", emptyList<String>(), table.filter { Heuristics.isShortFollowUp(it) })
    }
}

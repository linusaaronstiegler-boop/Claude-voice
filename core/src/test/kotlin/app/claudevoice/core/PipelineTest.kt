package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {

    private class Rig(
        scope: TestScope,
        val claude: FakeClaude = FakeClaude(),
        val speaker: FakeSpeaker = FakeSpeaker(),
        config: Pipeline.Config = Pipeline.Config(),
    ) {
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
        ) { scope.testScheduler.currentTime }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)

        /** Spielt vor der nächsten Äußerung eine laute, tiefe Stimme ein: so klingt der Träger am Kopfhörer-Mikro. */
        fun own() = voice(115.0, 8000.0)

        /** Mittlere Lautstärke (rund -34 dBFS), andere Stimmlage: jemand am Tisch. */
        fun near() = voice(200.0, 1300.0)

        /** Sehr leise (rund -51 dBFS): Fernseher oder Nachbartisch. */
        fun far() = voice(180.0, 200.0)

        private fun voice(hz: Double, amplitude: Double) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(hz, amplitude, 600)
            pipeline.onAudio(t, t.size)
        }
    }

    private val zugspitze = listOf("SAY kurz\n", "Zweitausendneunhundertzweiundsechzig Meter. ", "Sie ist der höchste Berg Deutschlands.")

    @Test fun `Frage wird in einem Aufruf entschieden, beantwortet und satzweise gesprochen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()

        assertEquals(
            listOf("Zweitausendneunhundertzweiundsechzig Meter.", "Sie ist der höchste Berg Deutschlands."),
            rig.speaker.spoken,
        )
        assertEquals("Wie hoch ist die Zugspitze?", rig.state.value.question)
        assertEquals("antwortet (kurz)", rig.state.value.lastDecision)
        assertNotNull(rig.state.value.latencyMs)
        assertFalse(rig.state.value.speaking)
        assertEquals(0, rig.claude.quickCalls.size) // offensichtliche Frage: keine Vorprüfung
        assertTrue(rig.claude.systemSeen!!.contains("Du bist Alfred"))
    }

    @Test fun `eigene Antwort steht im Verlauf und Nachfrage loest ohne Fragewort aus`() = runTest {
        // Für sich genommen sieht der Vorfilter in der Nachfrage keinen Anlass (COLD): Ans Modell bringt sie
        // allein, dass der Assistent eben geantwortet hat. "und seit wann" taugt dafür nicht mehr, das stuft der
        // Vorfilter wegen des Frageworts inzwischen selbst als möglichen Anlass ein.
        val followUp = "und in Fuß"
        assertEquals(Heuristics.Heat.COLD, Heuristics.heat(followUp, "Alfred"))
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        advanceTimeBy(5_000)
        rig.say(followUp)
        runCurrent()

        assertEquals(2, rig.claude.respondCalls.size)
        val second = rig.claude.respondCalls[1]
        assertTrue(second, second.contains("ALFRED: Zweitausendneunhundertzweiundsechzig Meter."))
        assertTrue(second, second.contains("Du hast zuletzt gesprochen: vor 5 s"))
        assertTrue(second, second.contains(">> [jetzt] ?: $followUp"))
        assertEquals("die Nachfrage braucht keine Vorprüfung", 0, rig.claude.quickCalls.size)
    }

    @Test fun `SKIP spricht nichts und nennt den Grund`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP hinter", "grund\nDas darf nie gesprochen werden.") }))
        rig.say("Wer hat eigentlich das Spiel gewonnen?")
        runCurrent()

        assertTrue(rig.speaker.spoken.isEmpty())
        assertEquals("übersprungen: hintergrund", rig.state.value.lastDecision)
        assertNull(rig.state.value.question)
        assertNull(rig.state.value.error)
    }

    @Test fun `Fuellsel kostet keinen Aufruf, steht aber in der Mitschrift`() = runTest {
        val rig = Rig(this)
        rig.say("ja genau")
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size + rig.claude.quickCalls.size)
        assertEquals(listOf("?: ja genau"), rig.state.value.transcript)
    }

    @Test fun `Aussage geht erst durch die billige Vorpruefung`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.say("ich hab gestern den ganzen Abend an dem Aufsatz gesessen")
        runCurrent()
        assertEquals(1, rig.claude.quickCalls.size)
        assertEquals(0, rig.claude.respondCalls.size)
        assertEquals("übersprungen: kein Anlass", rig.state.value.lastDecision)

        rig.claude.quickReply = { _, _ -> "JA" }
        rig.say("der höchste Berg bei Garmisch na der Dings ich komm gleich drauf")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
    }

    @Test fun `ferne Stimme - klare Frage geht mit Hinweis ans Modell, beilaeufige Aussage nicht`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP hintergrund") }), config = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)))
        fun quiet() {
            rig.pipeline.onAudio(silence(300), 9600)
            val t = tone(180.0, 200.0, 600)
            rig.pipeline.onAudio(t, t.size)
        }
        quiet()
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size) // das Modell sieht FERN und entscheidet am Inhalt
        assertTrue(rig.claude.respondCalls[0].contains(">> [jetzt] FERN: Wie hoch"))

        quiet()
        rig.say("wir fahren dann morgen früh gegen acht Uhr los denke ich")
        runCurrent()
        assertEquals(0, rig.claude.quickCalls.size) // keine Vorprüfung für fernes Geplauder
        assertEquals(1, rig.claude.respondCalls.size)
    }

    @Test fun `Reichweite Nur ich verwirft fremde Stimmen lokal, der Name geht immer durch`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }), config = Pipeline.Config(reach = Reach.ICH, voiceProfile = VoiceProfile(-18.0, 115.0, 5)))
        fun quiet() {
            rig.pipeline.onAudio(silence(300), 9600)
            val t = tone(180.0, 200.0, 600)
            rig.pipeline.onAudio(t, t.size)
        }
        quiet()
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size)
        assertEquals(listOf("FERN: Wie hoch ist die Zugspitze?"), rig.state.value.transcript)

        quiet()
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
    }

    @Test fun `nach dem Weckwort gilt die naechste Aeusserung als Anrede, auch wenn der Name verhoert wurde`() = runTest {
        // Eine Antwort ohne Kopfzeile, die nur nach direkter Anrede zu hören ist: Ihr Anfang sieht nach einem
        // Kommentar zur Entscheidung aus (der Grund hinter SKIP, ohne das SKIP). Eine gewöhnliche Antwort ohne
        // Kopfzeile taugt für diese Probe nicht mehr, sie wird inzwischen auch ohne Anrede gesprochen.
        val rig = Rig(this, FakeClaude(reply = { listOf("unklar\nDie Zugspitze ist knapp dreitausend Meter hoch.") }), config = Pipeline.Config(names = listOf("Alfred", "Jarvis")))
        rig.pipeline.onWakeWord()
        rig.say("hey Travis wie hoch ist die Zugspitze")
        runCurrent()
        // Das Weckwort zählt als Anrede: Es wird gesprochen.
        assertEquals(2, rig.speaker.spoken.size)
        assertTrue(rig.claude.respondCalls[0].contains("Weckwort: Er hat dich unmittelbar"))

        rig.say("und wie hoch ist der Watzmann")
        runCurrent()
        assertFalse(rig.claude.respondCalls[1].contains("Weckwort:")) // gilt nur für die eine Äußerung
    }

    @Test fun `beide Rufnamen werden erkannt`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\nJa, Sir?") }), config = Pipeline.Config(names = listOf("Alfred", "Jarvis")))
        rig.say("Hey Jarvis")
        runCurrent()
        assertEquals(listOf("Ja, Sir?"), rig.speaker.spoken)
        assertTrue(rig.claude.systemSeen!!.contains("Du bist Alfred"))
    }

    @Test fun `im Gespraech wartet die Antwort eine Sekunde und entfaellt, wenn ein Mensch antwortet`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { user ->
            if (user.contains(">> [jetzt] ?: das sind ungefähr")) listOf("SKIP beantwortet") else zugspitze
        }))
        rig.say("wir waren letztes Jahr oben auf der Zugspitze")
        runCurrent()
        rig.say("Wie hoch ist die eigentlich?")
        runCurrent()
        assertTrue("noch zurückgehalten", rig.speaker.spoken.isEmpty())
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, rig.speaker.spoken.size)

        // Zweiter Durchgang: diesmal antwortet jemand am Tisch selbst.
        advanceTimeBy(10_000)
        rig.say("und wie hoch ist der Watzmann?")
        runCurrent()
        advanceTimeBy(400)
        rig.say("das sind ungefähr zweitausendsiebenhundert Meter glaube ich")
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, rig.speaker.spoken.size)
        assertEquals(3, rig.claude.respondCalls.size) // Watzmann-Antwort war fertig, wurde aber nicht gesprochen
    }

    // Hieß bis zur Messung mit dem echten Modell "verfehltes Format wird nur bei direkter Anrede gesprochen" und
    // erwartete für die Frage ohne Anrede "übersprungen: format". Gemessen waren alle 31 Antworten ohne
    // Kopfzeile echte Antworten; verworfen wird ohne Anrede nur noch, was nach einem Kommentar aussieht.
    @Test fun `verfehltes Format wird auch ohne Anrede gesprochen, ein Kommentar zur Entscheidung nur nach Anrede`() = runTest {
        val answer = "Die Zugspitze ist knapp dreitausend Meter hoch."
        val rig = Rig(this, FakeClaude(reply = { listOf(answer) }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(listOf(answer), rig.speaker.spoken)
        assertEquals("antwortet", rig.state.value.lastDecision)

        // Der Grund hinter SKIP, aber ohne das SKIP: keine Antwort, sondern ein Kommentar zur Entscheidung.
        rig.claude.reply = { listOf("hintergrund") }
        advanceTimeBy(30_000)
        rig.say("Wie hoch ist der Watzmann?")
        runCurrent()
        assertEquals(listOf(answer), rig.speaker.spoken)
        assertEquals("übersprungen: format", rig.state.value.lastDecision)

        // Nach direkter Anrede wird wie bisher alles gesprochen, was ohne Kopfzeile kommt.
        rig.say("Alfred, wie hoch ist der Watzmann?")
        runCurrent()
        assertEquals(listOf(answer, "hintergrund"), rig.speaker.spoken)
    }

    @Test fun `merken und stummschalten, Anrede hebt die Stummschaltung auf`() = runTest {
        val rig = Rig(this)
        rig.claude.reply = { listOf("SAY kurz\nNotiert.\nMEMO: Kennzeichen M-AB 1234\n") }
        rig.own()
        rig.say("Alfred, merk dir das Kennzeichen M AB 1234")
        runCurrent()
        assertEquals(listOf("Kennzeichen M-AB 1234"), rig.env.notes)
        assertEquals(listOf("Notiert."), rig.speaker.spoken)

        assertTrue(rig.claude.respondCalls.last().contains("Sprecher der letzten Äußerung: nach der Stimme er selbst"))
        rig.claude.reply = { listOf("SAY kurz\nSehr wohl.\nMUTE: 10") }
        rig.own()
        rig.say("Alfred, sei bitte zehn Minuten ruhig, ich muss lernen")
        runCurrent()
        assertTrue(rig.state.value.mutedUntilMs > 0)

        val before = rig.claude.respondCalls.size
        rig.claude.reply = { zugspitze }
        advanceTimeBy(60_000)
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(before, rig.claude.respondCalls.size)

        // Ein Fremder nennt den Namen: Die Stummschaltung bleibt bestehen.
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(before, rig.claude.respondCalls.size)
        assertTrue(rig.state.value.mutedUntilMs > 0)

        // Der Träger selbst hebt sie mit einer Anrede auf.
        rig.own()
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(before + 1, rig.claude.respondCalls.size)
        assertEquals(0L, rig.state.value.mutedUntilMs)
        assertTrue(rig.claude.respondCalls.last().contains("<heute_gemerkt>\n- Kennzeichen M-AB 1234"))
    }

    @Test fun `fremde Stimme kann nichts ins Gedaechtnis schreiben und nicht stummschalten`() = runTest {
        val rig = Rig(this)
        rig.claude.reply = { listOf("SAY kurz\nNotiert.\nMEMO: Der Code vom Fahrradschloss ist null null null null\nMUTE: 60") }
        rig.say("Alfred, merk dir der Code vom Fahrradschloss ist null null null null") // ohne Stimm-Nachweis
        runCurrent()
        assertTrue(rig.env.notes.isEmpty())
        assertEquals(0L, rig.state.value.mutedUntilMs)
        assertTrue(rig.state.value.lastDecision!!.startsWith("abgelehnt"))
        assertTrue(rig.claude.respondCalls.last().contains("Sprecher der letzten Äußerung: nicht gesichert"))
        assertTrue(rig.env.profiles.isEmpty()) // und sie wird auch nicht als Stimme des Trägers gelernt
    }

    // Der Test darüber kommt ohne jedes Audio aus ("keine Messung"). Die folgenden drei spielen eine wirkliche
    // fremde Stimme ein, so wie sie am Kopfhörer-Mikro ankommt, und zwar ohne gespeichertes Stimmprofil: nach
    // der Installation, nach dem Löschen der App-Daten, oder bevor der Träger den Assistenten je beim Namen
    // genannt hat. Dort lag die Lücke.

    private val lockNote = listOf("SAY kurz\n", "Notiert.\n", "MEMO: Der Code vom Fahrradschloss ist null null null null\n", "MUTE: 60")

    @Test fun `fremde Stimme vom Tisch kann nichts merken und nicht stummschalten, mit und ohne Stimmprofil`() = runTest {
        for (config in listOf(Pipeline.Config(), Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5)))) {
            val rig = Rig(this, FakeClaude(reply = { lockNote }), config = config)
            rig.own()
            rig.say("so jetzt aber mal los hier") // der Träger hat in dieser Sitzung schon gesprochen
            runCurrent()
            advanceTimeBy(3_000)
            rig.near()
            rig.say("Alfred, merk dir der Code vom Fahrradschloss ist null null null null")
            runCurrent()

            val seen = "Profil=${config.voiceProfile != null} Mitschrift=${rig.state.value.transcript} gelernt=${rig.env.profiles}"
            assertEquals(seen, emptyList<String>(), rig.env.notes)
            assertEquals(seen, 0L, rig.state.value.mutedUntilMs)
            assertTrue(seen, rig.state.value.lastDecision!!.startsWith("abgelehnt"))
            assertTrue(seen, rig.claude.respondCalls.last().contains("Sprecher der letzten Äußerung: nicht gesichert"))
            assertTrue("als Träger gelernt: $seen", rig.env.profiles.isEmpty())
        }
    }

    @Test fun `ohne Stimmprofil sperrt der erste Ruf des Tischnachbarn den Traeger nicht aus`() = runTest {
        // Früher wurde die Stimme des Freundes mit seinem ersten "Alfred" als Profil des Trägers gespeichert.
        // Der Träger selbst klang danach "fremd" und wurde bei jedem Versuch abgelehnt, auf Dauer.
        val rig = Rig(this)
        rig.claude.reply = { user ->
            if (user.lines().last { it.startsWith(">>") }.contains("merk dir")) listOf("SAY kurz\n", "Notiert.\n", "MEMO: Zahnarzt am Dienstag\n")
            else listOf("SAY kurz\n", "Halb zehn.")
        }
        rig.own()
        rig.say("so jetzt aber mal los hier")
        runCurrent()
        advanceTimeBy(3_000)
        rig.near()
        rig.say("Alfred, wie spät ist es eigentlich?")
        runCurrent()
        assertTrue("der Freund wurde als Träger gelernt: ${rig.env.profiles}", rig.env.profiles.isEmpty())
        advanceTimeBy(30_000)
        rig.own()
        rig.say("Alfred, merk dir Zahnarzt am Dienstag")
        runCurrent()

        assertEquals(
            "Mitschrift=${rig.state.value.transcript} gelernt=${rig.env.profiles} zuletzt=${rig.state.value.lastDecision}",
            listOf("Zahnarzt am Dienstag"), rig.env.notes,
        )
        // Eine Probe ist noch kein Profil (dafür braucht es drei, die zusammenpassen). Nach zwei weiteren
        // Anreden ist die Stimme des Trägers gelernt: laut und tief.
        assertTrue("nach einer Probe: ${rig.env.profiles}", rig.env.profiles.isEmpty())
        repeat(2) {
            advanceTimeBy(30_000)
            rig.own()
            rig.say("Alfred, merk dir Zahnarzt am Dienstag")
            runCurrent()
        }
        assertEquals(115.0, rig.env.profiles.last().pitchHz!!, 3.0)
        assertTrue(rig.env.profiles.last().levelDb > -25.0)
    }

    @Test fun `ohne Stimmprofil ist die erste Stimme der Sitzung nicht schon deshalb der Traeger`() = runTest {
        // Ohne Profil ist der Bezugspunkt die lauteste Stimme der Sitzung, und die erste Stimme ist immer die
        // lauteste. Früher durfte sie damit merken und stummschalten: auch der Fernseher, auch ohne Anrede.
        for (voice in listOf<(Rig) -> Unit>({ it.far() }, { it.near() })) {
            for (text in listOf("merk dir bitte die Nummer vom Tresor 4711", "Alfred, merk dir bitte die Nummer vom Tresor 4711")) {
                val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Notiert.\n", "MEMO: Tresor 4711\n", "MUTE: 600\n") }, quickReply = { _, _ -> "JA" }))
                voice(rig)
                rig.say(text)
                runCurrent()
                advanceTimeBy(1_500)
                runCurrent()

                val seen = "Mitschrift=${rig.state.value.transcript} zuletzt=${rig.state.value.lastDecision}"
                assertEquals(seen, emptyList<String>(), rig.env.notes)
                assertEquals(seen, 0L, rig.state.value.mutedUntilMs)
                assertTrue(seen, rig.env.profiles.isEmpty())
            }
        }
        // Die Gegenprobe: Der Träger selbst kommt am Kopfhörer-Mikro so laut an, wie es keine andere Stimme tut.
        // Das genügt auch ohne Profil und ohne Anrede als Nachweis, wie bisher.
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Notiert.\n", "MEMO: Tresor 4711\n") }, quickReply = { _, _ -> "JA" }))
        rig.own()
        rig.say("merk dir bitte die Nummer vom Tresor 4711")
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf("Tresor 4711"), rig.env.notes)
    }

    @Test fun `Vorgriff startet vor dem Satzende und wird bei gleichem Text uebernommen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze", 0.3)
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size)
        rig.pipeline.onPartial("Wie hoch ist die Zugspitze?", 0.8)
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
        assertTrue("vor dem Satzende wird nie gesprochen", rig.speaker.spoken.isEmpty())

        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
        assertEquals(2, rig.speaker.spoken.size)
    }

    @Test fun `Vorgriff wird verworfen, wenn der Satz anders endet`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.pipeline.onPartial("Wie hoch ist", 0.8)
        runCurrent()
        rig.say("Wie hoch ist der Eiffelturm?")
        runCurrent()
        assertEquals(2, rig.claude.respondCalls.size)
        assertTrue(rig.claude.respondCalls[1].contains("Eiffelturm"))
        assertEquals(2, rig.speaker.spoken.size)
    }

    @Test fun `Alfred stopp bricht die laufende Antwort ab`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(this, FakeClaude(reply = { zugspitze }), FakeSpeaker(gate))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertTrue(rig.state.value.speaking)
        assertEquals(1, rig.speaker.spoken.size)

        rig.say("Alfred, stopp")
        runCurrent()
        assertTrue(rig.speaker.stops >= 1)
        assertFalse(rig.state.value.speaking)
        assertEquals(1, rig.claude.respondCalls.size) // der Stoppbefehl selbst geht nicht ans Modell
    }

    @Test fun `Ordner liefert Szene und markiert fremde Zeilen`() = runTest {
        val rig = Rig(this, FakeClaude(
            reply = { zugspitze },
            quickReply = { system, _ -> if (system.startsWith("Du ordnest")) "SZENE: WG-Party, er spricht mit Jonas über Berge.\nABSEITS: 2" else "NEIN" },
        ))
        advanceTimeBy(20_000)
        rig.say("wir waren letztes Jahr oben auf der Zugspitze")
        rig.say("und jetzt die Lottozahlen vom Mittwoch dreizehn siebzehn")
        rig.say("das war echt anstrengend mit dem ganzen Gepäck")
        rig.say("aber die Aussicht war jeden Schritt wert sag ich dir")
        runCurrent()
        assertEquals("WG-Party, er spricht mit Jonas über Berge.", rig.state.value.scene)

        rig.say("Alfred, wie hoch ist die eigentlich?")
        runCurrent()
        val seen = rig.claude.respondCalls.last()
        assertTrue(seen, seen.contains("Szene: WG-Party, er spricht mit Jonas über Berge."))
        assertTrue(seen, seen.contains("(abseits) ?: und jetzt die Lottozahlen"))
        assertFalse(seen, seen.contains("(abseits) ?: wir waren"))
    }

    @Test fun `Diarisierung traegt Sprecher nach und trennt zwei Stimmen in einer Zeile`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { zugspitze }))
        rig.pipeline.onCommitted("hallo zusammen wie gehts euch", "speaker_0", 7)
        rig.pipeline.onDiarized(7, listOf(
            SpokenPart("speaker_0", "hallo zusammen ", 0.0, 0.8),
            SpokenPart("speaker_1", "wie gehts euch", 0.9, 1.6),
        ))
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        val seen = rig.claude.respondCalls.last()
        assertTrue(seen, seen.contains("S1: hallo zusammen / S2: wie gehts euch"))
    }

    @Test fun `Fehler der API landet als Meldung im Zustand`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { error("kaputt") }))
        rig.say("Wie hoch ist die Zugspitze?")
        runCurrent()
        assertTrue(rig.state.value.error!!.contains("kaputt"))
        assertFalse(rig.state.value.speaking)
    }
}

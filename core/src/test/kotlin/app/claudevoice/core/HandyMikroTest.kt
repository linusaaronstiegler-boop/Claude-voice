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

/**
 * Das Handy-Mikrofon durch die ganze Pipeline, mit echtem Ton für den Stimmenfilter.
 *
 * Ohne Stimmprofil kann der Stimmenfilter am Handy niemanden vom Träger unterscheiden: Jede nahe Stimme ist
 * NAH, auch seine eigene. Zwei Dinge müssen dabei zugleich stimmen, und die Tests halten beide fest:
 *  - Das Modell darf aus NAH nicht "jemand anderes" lesen, sonst hält es sein Gespräch für ein fremdes.
 *    Es bekommt deshalb "?" und einen Hinweis in der Lage ([Persona.userMessage]).
 *  - Die App selbst rechnet weiter mit NAH. Daran hängt ihre Vorsicht: abwarten, ob ein Mensch antwortet,
 *    "Nur ich" streng halten, nach einer Antwort nicht jede Äußerung als Nachfrage durchwinken. Und aus
 *    keiner dieser Angaben entsteht Vertrauen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandyMikroTest {

    private class Rig(
        scope: TestScope, config: Pipeline.Config,
        val claude: FakeClaude = FakeClaude(reply = { listOf("SKIP hintergrund") }),
    ) {
        /** So viel Zeit ist während eines Modell-Aufrufs vergangen (der Aufruf selbst ist in Tests sofort fertig). */
        var drift = 0L
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        val pipeline: Pipeline = Pipeline(
            Brain(claude, Profile(aliases = listOf("Jarvis"))), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
        ) { scope.testScheduler.currentTime + drift }.also { it.start() }

        /** Der Träger: laut und tief, das Handy liegt direkt vor ihm. Sprecher-ID der Diarisierung: speaker_0. */
        fun wearer(text: String) = voice(115.0, 8000.0, text, "speaker_0")

        /** Der Freund am Tisch: etwas leiser, höhere Stimme. Sprecher-ID: speaker_1. */
        fun friend(text: String, amplitude: Double = 4000.0) = voice(190.0, amplitude, text, "speaker_1")

        /** Jemand am Nachbartisch: sehr leise. Sprecher-ID: speaker_2. */
        fun far(text: String) = voice(160.0, 300.0, text, "speaker_2")

        /** Die Etiketten, wie die App sie in ihrer Mitschrift anzeigt. */
        fun shownLabels() = state.value.transcript.map { it.substringBefore(':') }

        private fun voice(hz: Double, amplitude: Double, text: String, speakerId: String) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(hz, amplitude, 900)
            pipeline.onAudio(t, t.size)
            pipeline.onCommitted(text, speakerId, null)
        }
    }

    private val handy = Pipeline.Config(names = listOf("Alfred", "Jarvis"), mic = MicMode.HANDY)
    private val zugspitze = listOf("SAY kurz\n", "Zweitausendneunhundertzweiundsechzig Meter.")

    private fun isTidy(system: String) = system.startsWith("Du ordnest")

    // ------------------------------------------------------------------------------------------
    // Was das Modell liest und was die App anzeigt

    @Test fun `nahe Stimmen liest das Modell als unbekannt, die App rechnet und zeigt weiter NAH`() = runTest {
        val rig = Rig(this, handy)
        rig.wearer("warst du eigentlich schon mal in Lissabon")
        runCurrent()
        advanceTimeBy(4_000)
        rig.friend("nee aber Porto fand ich super")
        runCurrent()
        advanceTimeBy(3_000)
        rig.far("zahlen bitte")
        runCurrent()
        advanceTimeBy(7_000)
        rig.wearer("wie viele Einwohner hat Lissabon überhaupt")
        runCurrent()

        val seen = rig.claude.respondCalls.last()
        assertTrue(seen, seen.contains("Mikrofon: Handy-Mikrofon (nimmt den ganzen Raum auf; seine eigene Stimme ist in dieser Mitschrift nicht von anderen zu unterscheiden"))
        assertTrue(seen, seen.contains("Sprecher der letzten Äußerung: nicht gesichert"))
        assertTrue(seen, seen.contains("[vor 14 s] ? S1: warst du eigentlich schon mal in Lissabon"))
        assertTrue(seen, seen.contains("[vor 10 s] ? S2: nee aber Porto fand ich super"))
        assertTrue(seen, seen.contains("[vor 7 s] FERN S3: zahlen bitte"))
        assertTrue(seen, seen.contains(">> [jetzt] ? S1: wie viele Einwohner hat Lissabon überhaupt"))
        // In der Anzeige der App steht, was der Stimmenfilter gemessen hat. Wichtig für die Oberfläche: Sie
        // zeichnet "?" und FERN blass wie Hintergrund. Stünde hier "?", wäre am Handy das ganze Gespräch blass.
        assertEquals(setOf("NAH S1", "NAH S2", "FERN S3"), rig.shownLabels().toSet())
    }

    @Test fun `auch der Ordner liest am Handy unbekannt statt NAH`() = runTest {
        val rig = Rig(this, handy, FakeClaude(quickReply = { system, _ -> if (isTidy(system)) "SZENE: Zwei am Tisch.\nABSEITS: -" else "NEIN" }))
        advanceTimeBy(20_000)
        rig.wearer("das Essen war echt gut hier")
        rig.friend("ja die Nudeln vor allem")
        rig.wearer("nächstes Mal nehm ich die auch")
        rig.friend("und danach noch ein Eis")
        runCurrent()
        val (_, user) = rig.claude.quickCalls.single { isTidy(it.first) }
        assertTrue(user, user.startsWith("Mikrofon: Handy-Mikrofon (die Stimme des Trägers ist in dieser Mitschrift nicht von anderen zu unterscheiden"))
        assertTrue(user, user.contains("1. [vor 0 s] ? S1: das Essen war echt gut hier"))
        assertTrue(user, user.contains("2. [vor 0 s] ? S2: ja die Nudeln vor allem"))
        assertFalse(user, user.contains("NAH"))
    }

    // ------------------------------------------------------------------------------------------
    // Vertrauen

    @Test fun `nahe Stimme am Handy darf nichts merken, nicht stummschalten und kein Stimmprofil anlegen`() = runTest {
        val rig = Rig(this, handy)
        // Auch wer den Assistenten sechsmal laut beim Namen nennt, wird am Handy nicht zum Träger erklärt:
        // Ein Freund oder ein Film könnten dasselbe tun.
        rig.claude.reply = { listOf("SAY kurz\nNotiert.\nMEMO: Kennzeichen M-AB 1234\nMUTE: 10") }
        repeat(6) {
            rig.wearer("Alfred, merk dir das Kennzeichen M AB 1234 und sei zehn Minuten still")
            runCurrent()
            advanceTimeBy(30_000)
        }
        assertEquals(6, rig.claude.respondCalls.size)
        rig.claude.respondCalls.forEach {
            assertTrue(it, it.contains(">> [jetzt] ? S1: Alfred, merk dir"))
            assertTrue(it, it.contains("Sprecher der letzten Äußerung: nicht gesichert"))
        }
        assertTrue("nichts gemerkt", rig.env.notes.isEmpty())
        assertEquals("nicht stummgeschaltet", 0L, rig.state.value.mutedUntilMs)
        assertTrue("kein Stimmprofil gelernt", rig.env.profiles.isEmpty())
        assertTrue(rig.state.value.lastDecision!!.startsWith("abgelehnt"))
        // Nach all den Anreden ist seine Stimme weiterhin kein ICH.
        assertEquals(setOf("NAH S1"), rig.shownLabels().toSet())
    }

    @Test fun `mit gespeichertem Handy-Profil widerspricht die Lage den Etiketten nicht`() = runTest {
        // Ein Stimmprofil fürs Handy kann aus einer älteren Version der App stammen. Dann gibt es am Handy
        // sehr wohl ein ICH, und die Lage darf nicht das Gegenteil behaupten.
        val rig = Rig(this, handy.copy(voiceProfile = VoiceProfile(-14.0, 115.0, 6)))
        rig.friend("ich hab den Wagen vorne an der Ecke geparkt")
        runCurrent()
        advanceTimeBy(4_000)
        rig.claude.reply = { listOf("SAY kurz\nNotiert.\nMEMO: Kennzeichen M-AB 1234") }
        rig.wearer("Alfred merk dir das Kennzeichen M AB 1234")
        runCurrent()

        val seen = rig.claude.respondCalls.last()
        assertTrue(seen, seen.contains(">> [jetzt] ICH S2: Alfred merk dir das Kennzeichen"))
        assertTrue(seen, seen.contains("Sprecher der letzten Äußerung: nach der Stimme er selbst"))
        assertTrue(seen, seen.contains("Mikrofon: Handy-Mikrofon (nimmt den ganzen Raum auf)\n"))
        assertFalse("Lage sagt 'nicht als ICH', die Mitschrift zeigt ICH:\n$seen", seen.contains("nicht als ICH"))
        // Ist er erkannt, gilt NAH wieder als das, was es am Kopfhörer heißt: jemand anderes in der Nähe.
        assertTrue(seen, seen.contains("[vor 4 s] NAH S1: ich hab den Wagen"))
        // Und weil die Akustik die Stimme ihm zuordnet, darf er hier auch etwas merken lassen.
        assertEquals(listOf("Kennzeichen M-AB 1234"), rig.env.notes)
    }

    // ------------------------------------------------------------------------------------------
    // Vorsicht der App gegenüber Stimmen, die nicht als die des Trägers erkannt sind

    /**
     * Zwei am Tisch, das Handy liegt dazwischen, dann 40 Sekunden Pause. Der Freund fragt den Träger etwas,
     * und der Träger beginnt 400 ms später selbst zu antworten, während der Modell-Aufruf noch läuft.
     */
    private fun friendAsksAfterPause(scope: TestScope, mic: MicMode, friendAmplitude: Double): Rig {
        val rig = Rig(scope, Pipeline.Config(mic = mic), FakeClaude())
        rig.claude.reply = {
            rig.drift += 400
            rig.pipeline.onPartial("das sind knapp dreitausend Meter", null)
            zugspitze
        }
        rig.wearer("so das Essen war echt gut")
        scope.runCurrent()
        scope.advanceTimeBy(40_000)
        rig.friend("sag mal wie hoch ist eigentlich die Zugspitze", friendAmplitude)
        scope.runCurrent()
        scope.advanceTimeBy(1_500)
        scope.runCurrent()
        return rig
    }

    @Test fun `am Handy schweigt der Assistent nach einer Pause, wenn der Traeger die Frage des Freundes selbst beantwortet`() = runTest {
        // Mit "?" vom Stimmenfilter galt die Frage als "niemand sonst da": Der Assistent wartete nicht ab und
        // redete dem Träger ins Wort. Ein zweites Netz gibt es am Handy nicht, denn ins Wort fallen kann ihm
        // nur eine Stimme, die als ICH erkannt ist.
        val rig = friendAsksAfterPause(this, MicMode.HANDY, friendAmplitude = 4000.0)
        assertEquals(1, rig.claude.respondCalls.size)
        assertTrue("Der Assistent redet dem Träger ins Wort: ${rig.speaker.spoken}", rig.speaker.spoken.isEmpty())
        assertEquals("übersprungen: mensch antwortet", rig.state.value.lastDecision)
    }

    @Test fun `Kontrolle am Kopfhoerer-Mikrofon schweigt der Assistent in derselben Lage`() = runTest {
        // Der Freund kommt am Kopfhörer deutlich leiser an und ist dort NAH.
        val rig = friendAsksAfterPause(this, MicMode.AIRPODS, friendAmplitude = 1000.0)
        assertEquals(1, rig.claude.respondCalls.size)
        assertTrue("${rig.speaker.spoken}", rig.speaker.spoken.isEmpty())
        assertEquals("übersprungen: mensch antwortet", rig.state.value.lastDecision)
    }

    @Test fun `am Handy gehen Aussagen des Freundes nach einer Antwort nicht ungeprueft ans Antwort-Modell`() = runTest {
        // Nach einer Antwort gilt 20 Sekunden lang: Was der Träger sagt, ist vermutlich eine Nachfrage und geht
        // ohne Vorprüfung ans Antwort-Modell. Am Handy ist niemand als Träger erkannt. Gälte das Fenster dort
        // für jede nahe Stimme, hinge das ganze Tischgespräch dieser 20 Sekunden allein am Prompt.
        var n = 0
        val claude = FakeClaude(reply = { if (n++ == 0) zugspitze else listOf("SKIP keine-frage") })
        val rig = Rig(this, handy, claude)
        rig.wearer("Alfred wie hoch ist die Zugspitze")
        runCurrent()
        assertEquals(listOf("Zweitausendneunhundertzweiundsechzig Meter."), rig.speaker.spoken)
        val before = claude.respondCalls.size
        for (statement in listOf(
            "ja das dachte ich mir schon", "wir waren da letztes Jahr", "das Wetter war leider schlecht",
            "ich hol mir noch ein Bier", "der Kellner kommt gleich",
        )) {
            advanceTimeBy(3_000)
            rig.friend(statement)
            runCurrent()
        }
        assertEquals("Aussagen des Freundes direkt am Antwort-Modell", 0, claude.respondCalls.size - before)
    }

    @Test fun `erneute Frage in Frageform erreicht am Handy das Modell`() = runTest {
        var n = 0
        val claude = FakeClaude(reply = { if (n++ == 0) zugspitze else listOf("SKIP unklar") })
        val rig = Rig(this, handy, claude)
        rig.wearer("Jarvis wie hoch ist die Zugspitze")
        runCurrent()
        assertEquals(listOf("Zweitausendneunhundertzweiundsechzig Meter."), rig.speaker.spoken)
        advanceTimeBy(5_000)
        rig.wearer("wie hoch ist die Zugspitze nochmal")
        runCurrent()
        assertEquals(2, rig.claude.respondCalls.size)
        val second = rig.claude.respondCalls[1]
        assertTrue(second, second.contains("ALFRED: Zweitausendneunhundertzweiundsechzig Meter."))
        assertTrue(second, second.contains(">> [jetzt] ? S1: wie hoch ist die Zugspitze nochmal"))
    }

    @Test fun `Reichweite Nur ich bleibt am Handy streng`() = runTest {
        // "Nur ich" soll verhindern, dass der Assistent auf andere reagiert. Am Handy ist die Frage des Freundes
        // von der des Trägers nicht zu unterscheiden; durchgelassen wird deshalb nur, was den Namen trägt.
        // Der Preis: Auch die eigene Frage braucht dort den Namen. Lösen kann das nur ein Stimmprofil fürs
        // Handy, kein weicherer Filter.
        val rig = Rig(this, handy.copy(reach = Reach.ICH))
        rig.wearer("so jetzt erst mal einen Kaffee")
        runCurrent()
        advanceTimeBy(5_000)
        rig.friend("Wie hoch ist eigentlich der Watzmann?")
        runCurrent()
        assertEquals("Die Frage des Freundes erreichte das Antwort-Modell: ${rig.claude.respondCalls.lastOrNull()}", 0, rig.claude.respondCalls.size)
        advanceTimeBy(5_000)
        rig.far("Wie hoch ist der Watzmann?")
        runCurrent()
        assertEquals(0, rig.claude.respondCalls.size)
        advanceTimeBy(5_000)
        rig.wearer("Alfred, wie hoch ist der Watzmann?")
        runCurrent()
        assertEquals(1, rig.claude.respondCalls.size)
    }
}

/** Der Ordner durch die ganze Pipeline: Was er gesagt bekommt, und was aus seiner Antwort wird. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrdnerPipelineTest {

    private class Rig(scope: TestScope, val claude: FakeClaude, config: Pipeline.Config = Pipeline.Config()) {
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(
            Brain(claude, Profile(aliases = listOf("Jarvis"))), FakeSpeaker(), state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, FakeEnv(),
        ) { scope.testScheduler.currentTime }.also { it.start() }

        fun say(text: String) = pipeline.onCommitted(text, null, null)
    }

    private fun isTidy(system: String) = system.startsWith("Du ordnest")

    @Test fun `Bereich vom Ordner kommt als abseits beim Antwort-Modell an`() = runTest {
        var tidyCalls = 0
        val rig = Rig(this, FakeClaude(
            reply = { listOf("SAY kurz\n", "Kurz nach neun.") },
            quickReply = { system, _ ->
                if (!isTidy(system)) "NEIN"
                else if (++tidyCalls == 1) "SZENE: Wohnzimmer, der Fernseher läuft.\nABSEITS: 3, 4"
                else "SZENE: Wohnzimmer, der Fernseher läuft.\nABSEITS: 3-8"
            },
        ))
        advanceTimeBy(20_000)
        rig.say("ich mach mir noch schnell einen Tee")
        rig.say("dann setz ich mich an den Aufsatz")
        rig.say("und nun zum Wetter im Süden bleibt es heiter")
        rig.say("im Norden ziehen dichte Wolken auf")
        runCurrent()
        advanceTimeBy(13_000)
        rig.say("die Temperaturen liegen bei elf Grad")
        rig.say("am Wochenende wird es deutlich kälter")
        rig.say("das war das Wetter und jetzt zum Sport")
        rig.say("die Bayern gewinnen mit drei zu eins")
        runCurrent()
        assertEquals(2, tidyCalls)

        advanceTimeBy(5_000)
        rig.say("Alfred, wie spät ist es?")
        runCurrent()
        val seen = rig.claude.respondCalls.last()
        // Früher waren nur die Ränder des Bereichs markiert (Zeile 3 und 8), und Zeile 4 verlor ihren Vermerk wieder.
        for (tv in listOf("und nun zum Wetter", "im Norden ziehen", "die Temperaturen liegen", "am Wochenende wird", "das war das Wetter", "die Bayern gewinnen")) {
            assertTrue("$tv\n$seen", seen.contains("(abseits) ?: $tv"))
        }
        assertFalse(seen, seen.contains("(abseits) ?: ich mach mir"))
        assertFalse(seen, seen.contains("(abseits) ?: dann setz ich mich"))
    }

    @Test fun `Ordner erfaehrt, dass der Traeger mit einem Assistenten spricht`() = runTest {
        val rig = Rig(this, FakeClaude(
            reply = { listOf("SAY kurz\n", "Zweitausendneunhundertzweiundsechzig Meter.") },
            quickReply = { system, _ -> if (isTidy(system)) "SZENE: Er ist allein.\nABSEITS: -" else "NEIN" },
        ))
        advanceTimeBy(20_000)
        rig.say("Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        advanceTimeBy(25_000)
        rig.say("Und der Watzmann?")
        runCurrent()
        advanceTimeBy(29_000)
        rig.say("Seit wann heißt der eigentlich so?")
        runCurrent()
        advanceTimeBy(35_000)
        rig.say("Okay, danke.")
        runCurrent()

        assertTrue("der Assistent hat geantwortet", rig.claude.respondCalls.isNotEmpty())
        val (system, user) = rig.claude.quickCalls.last { isTidy(it.first) }
        // Die Antworten des Assistenten stehen nicht in der Mitschrift des Ordners. Dafür sagt ihm der feste
        // Text, dass es einen Assistenten gibt, und die Nachricht, wie er heißt.
        assertFalse(user, user.contains("ALFRED:"))
        assertTrue(system.contains("Sprachassistenten im Ohr"))
        assertTrue(user, user.contains("Namen des Assistenten: Alfred, Jarvis\n"))
        assertEquals("Er ist allein.", rig.state.value.scene)
    }
}

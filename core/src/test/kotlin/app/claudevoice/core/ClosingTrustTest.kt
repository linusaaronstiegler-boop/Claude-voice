package app.claudevoice.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Schlussrunde zur Schranke `trusted` (wer merken, stummschalten und das Stimmprofil lernen darf) und zur Ruhe.
 * Die Lagen stammen aus der Gegenprüfung der Reparatur. Jeder Test beschreibt, was gelten soll; die Gegenproben
 * am Ende halten fest, auf welchen Wegen eine nicht gesicherte Stimme nichts bewirken darf.
 *
 * Pegel der Töne (tone() mit Obertönen, Effektivwert = 0,48 x Amplitude):
 *   8000 = -18,6 dBFS (Träger, laut)   3050 = -27,0   2300 = -29,5   2200 = -29,8
 *   1530 = -33,0 (leiser Träger)       1300 = -34,4 (Tischnachbar der Suite)   700 = -39,8   200 = -50,7 (fern)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClosingTrustTest {

    private class Rig(scope: TestScope, val claude: FakeClaude = FakeClaude(), config: Pipeline.Config = Pipeline.Config()) {
        val speaker = RecordingSpeaker()
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
        ) { scope.testScheduler.currentTime }.also { it.start() }

        fun say(text: String, speakerId: String? = null) = pipeline.onCommitted(text, speakerId, null)

        fun voice(hz: Double, amplitude: Double, text: String, speakerId: String? = null, ms: Int = 600) {
            pipeline.onAudio(silence(300), 9600)
            val t = tone(hz, amplitude, ms)
            pipeline.onAudio(t, t.size)
            say(text, speakerId)
        }

        fun wearer(text: String, id: String? = null) = voice(115.0, 8000.0, text, id)
        fun friend(text: String, id: String? = null) = voice(200.0, 1300.0, text, id)
        fun far(text: String, id: String? = null) = voice(180.0, 200.0, text, id)
        val last get() = state.value.lastDecision
        val muted get() = state.value.mutedUntilMs
    }

    private val withProfile = Pipeline.Config(voiceProfile = VoiceProfile(-18.0, 115.0, 5))
    private fun asked(user: String) = user.lines().last { it.startsWith(">>") }
    private val memoAndMute = listOf("SAY kurz\n", "Notiert.\n", "MEMO: Zahnarzt am Dienstag\n", "MUTE: 60\n")
    private val memoOnly = listOf("SAY kurz\n", "Notiert.\n", "MEMO: Zahnarzt am Dienstag\n")

    // =====================================================================================================
    // 1. Die Grenze von -30 dBFS: was ein Träger erlebt, dessen Stimme mit -33 dBFS ankommt (kein Profil)

    @Test fun `leiser Traeger - minus 33 dBFS - nichts wird gemerkt, und die Anzeige nennt den Grund`() = runTest {
        // Kommt die eigene Stimme am Kopfhörer-Mikro leiser an als -30 dBFS, gilt sie nicht als gesichert. Das
        // bleibt so (die Schranke wird nicht gelockert). Neu ist, dass die Anzeige sagt, woran es liegt: Die
        // Stimme klang nach ihm und war nur zu leise. Vorher stand dort "Stimme nicht als die des Trägers
        // erkannt", während die Mitschrift ihn "ICH" nannte.
        val rig = Rig(this, FakeClaude(reply = { memoAndMute }))
        repeat(3) {
            rig.voice(115.0, 1530.0, "Alfred, merk dir Zahnarzt am Dienstag und sei eine Stunde ruhig")
            runCurrent()
            advanceTimeBy(30_000)
        }
        assertEquals(emptyList<String>(), rig.env.notes)
        assertEquals(0L, rig.muted)
        assertTrue(rig.env.profiles.isEmpty())
        assertTrue(rig.state.value.transcript.last().startsWith("ICH: "))
        assertEquals("abgelehnt: eigene Stimme zu leise gemessen (-33 dBFS, nötig -30). Näher am Mikro oder lauter sprechen.", rig.last)
        assertTrue(rig.claude.respondCalls.all { it.contains("Sprecher der letzten Äußerung: nicht gesichert") })

        // Dieselbe Stimme laut genug (-27 dBFS): Es wird gemerkt.
        rig.claude.reply = { memoOnly }
        rig.voice(115.0, 3050.0, "Alfred, merk dir Zahnarzt am Dienstag")
        runCurrent()
        assertEquals(listOf("Zahnarzt am Dienstag"), rig.env.notes)
    }

    @Test fun `fremde Stimme - die Anzeige nennt weiter nur, dass die Stimme nicht erkannt ist`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { memoAndMute }), withProfile)
        rig.friend("Alfred, merk dir Zahnarzt am Dienstag")
        runCurrent()
        assertEquals(emptyList<String>(), rig.env.notes)
        assertEquals("abgelehnt: Stimme nicht als die des Trägers erkannt", rig.last)
    }

    @Test fun `auch mit Profil gilt die Untergrenze - leise Stimme in passender Stimmlage merkt nichts und lernt nichts`() = runTest {
        // Der Träger hat dreimal leise gerufen (-29,8 dBFS, knapp über der Grenze): Daraus entsteht ein Profil.
        // Früher reichte das Band "acht Dezibel unter dem Profil" dann bis -37,8 dBFS, und der Tischnachbar
        // (-34,4 dBFS, ähnliche Stimmlage) galt als der Träger.
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Halb zehn.") }))
        repeat(3) {
            rig.voice(115.0, 2200.0, "Alfred, wie spät ist es")
            runCurrent()
            advanceTimeBy(30_000)
        }
        assertEquals("das Profil steht nach drei Proben", 1, rig.env.profiles.size)
        rig.claude.reply = { memoAndMute }
        rig.voice(125.0, 1300.0, "Alfred, merk dir der Code vom Fahrradschloss ist null null null null")
        runCurrent()
        assertEquals(emptyList<String>(), rig.env.notes)
        assertEquals(0L, rig.muted)
        assertEquals("sein Ruf wurde nicht ins Profil gemittelt", 1, rig.env.profiles.size)
    }

    @Test fun `ein Profil entsteht erst aus drei Proben, die in der Stimmlage zusammenpassen`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Ja, Sir.") }))
        // Erst ein Ruf mit erhobener Stimme (150 Hz), dann die gewöhnliche Stimmlage (115 Hz).
        rig.voice(150.0, 8000.0, "Alfred, hörst du mich")
        runCurrent(); advanceTimeBy(30_000)
        rig.wearer("Alfred, wie spät ist es")
        runCurrent(); advanceTimeBy(30_000)
        rig.wearer("Alfred, und morgen")
        runCurrent(); advanceTimeBy(30_000)
        assertTrue("zwei passende Proben reichen noch nicht: ${rig.env.profiles}", rig.env.profiles.isEmpty())
        rig.wearer("Alfred, danke dir")
        runCurrent()
        val profile = rig.env.profiles.single()
        assertEquals(3, profile.samples)
        assertEquals("der Ausreißer zählt nicht mit", 115.0, profile.pitchHz!!, 2.0)
    }

    @Test fun `ein gespeichertes Profil aus einer einzigen Probe wird nicht uebernommen`() = runTest {
        // So ein Profil hat eine frühere Fassung der App nach dem ersten Ruf angelegt, hier mit der Stimmlage
        // eines Freundes. Der Träger passte danach nie mehr dazu und war ausgesperrt.
        val old = Pipeline.Config(voiceProfile = VoiceProfile(-29.5, 200.0, 1))
        val rig = Rig(this, FakeClaude(reply = { memoOnly }), old)
        rig.wearer("Alfred, merk dir Zahnarzt am Dienstag")
        runCurrent()
        assertEquals(listOf("Zahnarzt am Dienstag"), rig.env.notes)
    }

    // =====================================================================================================
    // 2. Aussperren: ein falsches Profil wird weiterhin aus EINER Probe gelernt und gespeichert

    @Test fun `ohne Profil - lauter Freund probiert den Assistenten als Erster aus - der Traeger ist in der naechsten Sitzung ausgesperrt`() = runTest {
        val first = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Zu Diensten.") }))
        first.voice(200.0, 2300.0, "Alfred, sag mal was") // Freund, -29,5 dBFS, erste Stimme der Sitzung
        runCurrent()
        val stored = first.env.profiles.lastOrNull()

        // Nächste Sitzung: Der Dienst lädt das gespeicherte Profil. Jetzt spricht der Träger selbst.
        val next = Rig(this, FakeClaude(reply = { memoOnly }), Pipeline.Config(voiceProfile = stored))
        repeat(5) {
            next.wearer("Alfred, merk dir Zahnarzt am Dienstag")
            runCurrent()
            advanceTimeBy(30_000)
        }
        assertEquals(
            "gespeichertes Profil=$stored zuletzt=${next.last} Mitschrift=${next.state.value.transcript.takeLast(1)}",
            5, next.env.notes.size,
        )
    }

    @Test fun `ohne Profil - der erste eigene Ruf in gehobener Stimmlage sperrt den Traeger selbst aus`() = runTest {
        val first = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Ja, Sir.") }))
        first.voice(150.0, 8000.0, "Alfred, hörst du mich") // er ruft, die Stimme geht dabei hoch (150 statt 115 Hz)
        runCurrent()
        val stored = first.env.profiles.lastOrNull()

        val next = Rig(this, FakeClaude(reply = { memoOnly }), Pipeline.Config(voiceProfile = stored))
        repeat(5) {
            next.wearer("Alfred, merk dir Zahnarzt am Dienstag") // seine gewöhnliche Stimmlage, gleich laut
            runCurrent()
            advanceTimeBy(30_000)
        }
        assertEquals(
            "gespeichertes Profil=$stored zuletzt=${next.last} Mitschrift=${next.state.value.transcript.takeLast(1)} neu gelernt=${next.env.profiles}",
            5, next.env.notes.size,
        )
    }

    @Test fun `ein leiser Ruf des Traegers macht den Tischnachbarn mit aehnlicher Stimmlage nicht zum Traeger`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Halb zehn.") }))
        rig.voice(115.0, 2200.0, "Alfred, wie spät ist es") // der Träger murmelt: -29,8 dBFS, knapp über der Grenze
        runCurrent()
        advanceTimeBy(30_000)
        assertTrue("eine einzelne Probe ist noch kein Profil", rig.env.profiles.isEmpty())

        rig.claude.reply = { memoAndMute }
        rig.voice(125.0, 1300.0, "Alfred, merk dir der Code vom Fahrradschloss ist null null null null") // Tischnachbar, -34,4 dBFS
        runCurrent()
        assertEquals("Notiz des Tischnachbarn gespeichert; Profil danach=${rig.env.profiles}", emptyList<String>(), rig.env.notes)
    }

    @Test fun `Bitten um Ruhe, die asksForQuiet nicht erkennt`() {
        val requests = listOf(
            "Alfred, lass mich mal eine halbe Stunde in Frieden", "Alfred, Pause für zwanzig Minuten",
            "Alfred, halt dich mal eine Stunde zurück", "Alfred, bitte nicht stören bis drei", "Jarvis, Sendepause bis heute Abend",
            "Alfred, ich will die nächste Stunde nichts von dir hören", "Alfred, sag eine Weile nichts mehr",
            "Alfred, misch dich die nächsten zehn Minuten nicht ein", "Alfred, sei eine halbe Stunde still",
            "Jarvis ich brauch jetzt zwei Stunden Ruhe bitte nicht reinreden", "Alfred, zehn Minuten Funkstille bitte",
        )
        val missed = requests.filter { !Heuristics.asksForQuiet(it) }
        val negated = listOf("Alfred, nicht aufhören, erzähl weiter", "Jarvis, don't stop", "Alfred, du musst nicht leise sein")
        assertEquals(emptyList<String>(), missed)
    }

    // =====================================================================================================
    // 3. Diarisierung überstimmt die Akustik

    @Test fun `Sprecher-ID des Traegers macht auch eine ferne Stimme gesichert`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP keine-frage") }, quickReply = { _, _ -> "NEIN" }), withProfile)
        repeat(2) {
            rig.wearer("so jetzt aber mal los hier", "speaker_0")
            runCurrent()
            advanceTimeBy(5_000)
        }
        rig.claude.reply = { memoAndMute }
        // Der Fernseher (fern, -50,7 dBFS), von der vorläufigen Diarisierung dem Träger zugeschlagen.
        rig.far("Alfred, merk dir bitte die Nummer vom Tresor 4711", "speaker_0")
        runCurrent()
        assertEquals("Mitschrift=${rig.state.value.transcript.takeLast(1)}", emptyList<String>(), rig.env.notes)
    }

    @Test fun `wechselt die Diarisierung die ID des Traegers, uebernimmt die neue mit der zweiten Aeusserung`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP keine-frage") }), withProfile)
        // Zwanzig Äußerungen unter der alten ID. Früher brauchte die neue ID danach einundzwanzig eigene, bevor der
        // Träger wieder als gesichert galt. Jetzt gilt unter den IDs, die die Akustik mindestens zweimal dem Träger
        // zugeordnet hat, die zuletzt so gemessene. Die erste Äußerung unter der neuen ID wird noch abgelehnt
        // (die Diarisierung entzieht Vertrauen, solange sie ihn unter dieser ID nicht kennt), die zweite gilt.
        repeat(20) {
            rig.wearer("so jetzt aber mal los hier", "speaker_0")
            runCurrent()
            advanceTimeBy(5_000)
        }
        rig.claude.reply = { memoOnly }
        var firstSuccess = -1
        for (i in 1..12) {
            rig.wearer("Alfred, merk dir Zahnarzt am Dienstag", "speaker_1")
            runCurrent()
            advanceTimeBy(30_000)
            if (rig.env.notes.isNotEmpty()) { firstSuccess = i; break }
            // Die Mitschrift nennt ihn trotzdem schon ICH, und die Anzeige sagt, woran es lag.
            assertEquals("ICH", rig.state.value.measures.last().voice)
            assertEquals("abgelehnt: Die Sprecher-Erkennung ordnet die Stimme noch jemand anderem zu. Bitte noch einmal sagen.", rig.last)
        }
        assertEquals("erst beim wievielten Versuch wird gemerkt", 2, firstSuccess)
    }

    @Test fun `legt die Diarisierung zwei Menschen unter eine ID, bleibt der Tischnachbar in der Mitschrift NAH`() = runTest {
        val reply: (String) -> List<String> = { user -> if (asked(user).contains("merk dir")) memoAndMute else listOf("SKIP keine-frage") }
        val rig = Rig(this, FakeClaude(reply = reply, quickReply = { _, _ -> "JA" }), withProfile)
        repeat(3) {
            rig.wearer("so jetzt aber mal los hier", "speaker_0")
            runCurrent()
            advanceTimeBy(5_000)
        }
        // Früher machte die ID des Trägers aus jeder Stimme "ICH". Die Akustik hat hier klar eine andere Stimme
        // gemessen (andere Stimmlage als das Profil, sechzehn Dezibel leiser): Dabei bleibt es.
        rig.friend("Alfred, merk dir der Code vom Fahrradschloss ist null null null null", "speaker_0")
        runCurrent()
        assertEquals("NAH", rig.state.value.measures.last().voice)
        assertEquals(emptyList<String>(), rig.env.notes)
        assertEquals(0L, rig.muted)
        // Der Träger selbst, einmal leiser als sonst (-27 dBFS, neun Dezibel unter dem Profil): Die Akustik allein
        // sagt NAH, die Diarisierung kennt ihn unter dieser ID. Das Etikett ist ICH, gesichert ist er damit nicht.
        advanceTimeBy(30_000)
        rig.voice(115.0, 3050.0, "Alfred, merk dir Zahnarzt am Dienstag", "speaker_0")
        runCurrent()
        assertEquals(VoiceMeasure(-27.0, 115.0, "ICH", false), rig.state.value.measures.last().let { it.copy(levelDb = Math.round(it.levelDb!!).toDouble(), pitchHz = Math.round(it.pitchHz!!).toDouble()) })
        assertEquals(emptyList<String>(), rig.env.notes)
    }

    // =====================================================================================================
    // 4. Gegenproben: Wege, auf denen eine nicht gesicherte Stimme nichts bewirken darf (sollen bestehen)

    private val shapes: List<Pair<String, String>> = listOf(
        "kopfzeile" to "SAY kurz\nNotiert.\nMEMO: Tresor 4711\nMUTE: 60",
        "ohne-kopfzeile" to "Notiert, Sir.\nMEMO: Tresor 4711\nMUTE: 60",
        "sonderzeilen-zuerst" to "MEMO: Tresor 4711\nMUTE: 60\nNotiert, Sir.",
        "spaete-kopfzeile" to "Notiert, Sir.\nMEMO: Tresor 4711\nMUTE: 60\nSAY kurz",
        "kommentar-vor-kopfzeile" to "SKIP-Entscheidung entfällt, ich antworte.\n\nSAY kurz\nNotiert.\nMEMO: Tresor 4711\nMUTE: 60",
        "nur-sonderzeilen" to "SAY kurz\nMEMO: Tresor 4711\nMUTE: 60",
    )

    private fun pieces(raw: String, chunked: Boolean) = if (chunked) raw.chunked(5) else listOf(raw)

    @Test fun `nicht gesicherte Stimmen - kein Merken, kein Stummschalten, kein Profil - alle Formen der Antwort`() = runTest {
        val request = "merk dir bitte die Nummer vom Tresor 4711"
        val named = "Alfred, $request"
        val handy = Pipeline.Config(mic = MicMode.HANDY)
        // Name der Lage, Konfiguration, und was vor der Messung passiert.
        val speakers: List<Triple<String, Pipeline.Config, (Rig) -> Unit>> = listOf(
            Triple("Freund mit Namen, Profil", withProfile) { r -> r.wearer("so jetzt aber mal los hier"); r.friend(named) },
            Triple("ferne Stimme mit Namen, Profil", withProfile) { r -> r.far(named) },
            Triple("ohne Messung mit Namen, Profil", withProfile) { r -> r.say(named) },
            Triple("leiser Traeger -33 ohne Profil", Pipeline.Config()) { r -> r.voice(115.0, 1530.0, named) },
            Triple("Freund ohne Profil als erste Stimme", Pipeline.Config()) { r -> r.friend(named) },
            Triple("Handy-Mikro lauter Traeger mit Namen", handy) { r -> r.wearer(named) },
            Triple("Handy-Mikro Weckwort dann lauter Traeger", handy) { r -> r.pipeline.onWakeWord(); r.wearer(request) },
            Triple("Kopfhoerer Weckwort dann Freund, Profil", withProfile) { r -> r.pipeline.onWakeWord(); r.friend(request) },
            Triple("Kopfhoerer Weckwort dann ferne Stimme, Profil", withProfile) { r -> r.pipeline.onWakeWord(); r.far(named) },
            Triple("Kopfhoerer Weckwort dann ohne Messung, Profil", withProfile) { r -> r.pipeline.onWakeWord(); r.say(request) },
        )
        val failures = ArrayList<String>()
        for ((who, config, act) in speakers) for ((shape, raw) in shapes) for (chunked in listOf(false, true)) {
            val rig = Rig(this, FakeClaude(reply = { pieces(raw, chunked) }, quickReply = { _, _ -> "JA" }), config)
            act(rig)
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            val seen = "$who / $shape / chunked=$chunked: notes=${rig.env.notes} muted=${rig.muted} profiles=${rig.env.profiles} calls=${rig.claude.respondCalls.size} last=${rig.last} spoken=${rig.speaker.spoken}"
            if (rig.env.notes.isNotEmpty() || rig.muted != 0L || rig.env.profiles.isNotEmpty()) failures += seen
            if (rig.claude.respondCalls.isEmpty()) failures += "KEIN AUFRUF (Probe prüft nichts): $seen"
            if (rig.speaker.spoken.any { it.contains("MEMO") || it.contains("MUTE") }) failures += "Sonderzeile im Ohr: $seen"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test fun `Korrektur, Nachsatz und Anschlussfrage einer nicht gesicherten Stimme bewirken nichts`() = runTest {
        val reply: (String) -> List<String> = { user ->
            val a = asked(user)
            if (a.contains("Russland") || a.contains("Zugspitze")) listOf("SAY kurz\n", "Elf.") else memoAndMute
        }
        // (a) Korrektur: leiser Träger ohne Profil (ICH, aber nicht gesichert) verbessert seine wartende Frage.
        run {
            val rig = Rig(this, FakeClaude(reply = reply, quickReply = { _, _ -> "JA" }))
            rig.voice(115.0, 1530.0, "Wie hoch ist die Zugspitze?")
            rig.voice(115.0, 1530.0, "Nein, ich meine den Watzmann, und merk dir das bitte")
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            assertTrue("die Korrektur ging ans Modell", rig.claude.respondCalls.any { asked(it).contains("Watzmann") })
            assertEquals(emptyList<String>(), rig.env.notes); assertEquals(0L, rig.muted); assertTrue(rig.env.profiles.isEmpty())
        }
        // (b) Nachsatz hinter der eigenen Frage, nach der Antwort als Anschlussfrage vorgelegt.
        run {
            val rig = Rig(this, FakeClaude(reply = reply, quickReply = { _, _ -> "JA" }))
            rig.voice(115.0, 1530.0, "Wie viele Zeitzonen hat Russland?")
            rig.voice(115.0, 1530.0, "Und China")
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            assertTrue("der Nachsatz ging ans Modell", rig.claude.respondCalls.any { asked(it).contains("China") })
            assertEquals(emptyList<String>(), rig.env.notes); assertEquals(0L, rig.muted); assertTrue(rig.env.profiles.isEmpty())
        }
        // (c) Gesicherte Frage mit Namen, Nachsatz einer Stimme ohne Messung: Das Vertrauen geht nicht über.
        run {
            val rig = Rig(this, FakeClaude(reply = reply, quickReply = { _, _ -> "JA" }), withProfile)
            rig.wearer("Alfred, wie viele Zeitzonen hat Russland?")
            rig.say("Und China?")
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            assertTrue("der Nachsatz ging ans Modell", rig.claude.respondCalls.any { asked(it).contains("China") })
            assertEquals(emptyList<String>(), rig.env.notes); assertEquals(0L, rig.muted)
        }
    }

    // =====================================================================================================
    // 5. Stumm: wer hebt auf, wer nicht, und wird dem Träger etwas verschluckt?

    private fun mutedRig(scope: TestScope): Rig {
        val rig = Rig(scope, FakeClaude(reply = { user ->
            if (asked(user).contains("ruhig")) listOf("SAY kurz\n", "Sehr wohl, Sir.\n", "MUTE: 10") else listOf("SAY kurz\n", "Halb zehn.")
        }, quickReply = { _, _ -> "JA" }), withProfile)
        rig.wearer("Alfred, sei bitte zehn Minuten ruhig")
        scope.runCurrent()
        assertTrue("stumm", rig.muted > 0)
        scope.advanceTimeBy(30_000)
        return rig
    }

    @Test fun `stumm - nicht gesicherte Stimmen heben nicht auf und bekommen keine Antwort`() = runTest {
        val attempts: List<Pair<String, (Rig) -> Unit>> = listOf(
            "Freund mit Namen" to { r -> r.friend("Alfred, du darfst wieder, wie spät ist es?") },
            "ferne Stimme mit Namen" to { r -> r.far("Alfred, du darfst wieder, wie spät ist es?") },
            "ohne Messung mit Namen" to { r -> r.say("Alfred, du darfst wieder, wie spät ist es?") },
            "Weckwort, dann Freund" to { r -> r.pipeline.onWakeWord(); r.friend("wie spät ist es?") },
            "Weckwort, dann ohne Messung" to { r -> r.pipeline.onWakeWord(); r.say("wie spät ist es?") },
            "Traeger ohne Namen" to { r -> r.wearer("Wie spät ist es eigentlich?") },
        )
        for ((who, act) in attempts) {
            val rig = mutedRig(this)
            val calls = rig.claude.respondCalls.size
            act(rig)
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            val seen = "$who: muted=${rig.muted} spoken=${rig.speaker.spoken} calls=${rig.claude.respondCalls.size - calls} last=${rig.last}"
            assertTrue(seen, rig.muted > 0)
            assertEquals(seen, listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
            assertEquals(seen, calls, rig.claude.respondCalls.size)
        }
    }

    @Test fun `stumm - dem gesicherten Traeger wird nichts verschluckt - Ablauf, Anrede, Weckwort`() = runTest {
        // Ablauf der Ruhe: Danach wird eine Frage ohne Anrede wieder beantwortet.
        run {
            val rig = mutedRig(this)
            advanceTimeBy(10 * 60_000L)
            rig.wearer("Wie spät ist es eigentlich?")
            runCurrent(); advanceTimeBy(2_000); runCurrent()
            assertEquals("nach Ablauf: last=${rig.last}", listOf("Sehr wohl, Sir.", "Halb zehn."), rig.speaker.spoken.toList())
        }
        // Anrede des Trägers: hebt auf und wird beantwortet.
        run {
            val rig = mutedRig(this)
            rig.wearer("Alfred, wie spät ist es?")
            runCurrent()
            assertEquals(0L, rig.muted)
            assertEquals(listOf("Sehr wohl, Sir.", "Halb zehn."), rig.speaker.spoken.toList())
        }
        // Weckwort und danach die Frage des Trägers ohne Namen.
        run {
            val rig = mutedRig(this)
            rig.pipeline.onWakeWord()
            rig.wearer("wie spät ist es?")
            runCurrent()
            assertEquals(0L, rig.muted)
            assertEquals(listOf("Sehr wohl, Sir.", "Halb zehn."), rig.speaker.spoken.toList())
        }
    }

    @Test fun `stumm - kurzer Ruf des Traegers, dessen erster Zwischenstand spaet kommt, hebt nicht auf, aber die Anzeige sagt es`() = runTest {
        val rig = mutedRig(this)
        val before = rig.state.value
        // "Alfred?" ist nach 0,4 s gesagt; die Transkription meldet den ersten Text 0,7 s später.
        rig.pipeline.onAudio(silence(300), 9600)
        val t = tone(115.0, 8000.0, 400)
        rig.pipeline.onAudio(t, t.size)
        val gap = silence(700)
        rig.pipeline.onAudio(gap, gap.size)
        rig.pipeline.onPartial("Alfred", null)
        rig.pipeline.onCommitted("Alfred, bist du wieder da?", null, null)
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        // Die Stimme ließ sich nicht messen ("?"): Die Ruhe bleibt, denn nur der Träger hebt sie auf.
        assertTrue("Mitschrift=${rig.state.value.transcript.takeLast(1)}", rig.muted > 0)
        assertEquals("?: Alfred, bist du wieder da?", rig.state.value.transcript.last())
        // Neu: Es bleibt nicht mehr unsichtbar.
        assertEquals("stumm: Anrede gehört, Stimme nicht als die des Trägers erkannt", rig.last)
    }

    // =====================================================================================================
    // 6. Hinter dem Längendeckel, mit echten Threads (wie DrainTest)

    private val tooLong = "Dazu kommen noch sehr viele weitere Einzelheiten die an dieser Stelle niemand hören will " +
        "weil sie die Antwort nur unnötig in die Länge ziehen würden und nichts Neues bringen. "

    private class Staged(private val script: (String) -> List<Pair<String, List<String>>>) : ClaudeApi {
        val calls = CopyOnWriteArrayList<String>()
        val finished = CopyOnWriteArrayList<String>()
        private val reached = ConcurrentHashMap<String, CountDownLatch>()
        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        private fun reachedLatch(stage: String) = reached.computeIfAbsent(stage) { CountDownLatch(1) }
        private fun gate(stage: String) = gates.computeIfAbsent(stage) { CountDownLatch(1) }
        fun awaitStage(stage: String) = reachedLatch(stage).await(5, TimeUnit.SECONDS)
        fun open(stage: String) = gate(stage).countDown()

        override fun respond(system: String, user: String, cancel: CancelToken, onText: (String) -> Unit) {
            val trigger = user.lineSequence().first { it.startsWith(">> [jetzt]") }
            calls += trigger
            for ((stage, pieces) in script(trigger)) {
                reachedLatch(stage).countDown()
                gate(stage).await()
                pieces.forEach(onText)
            }
            finished += trigger
        }

        override fun quick(system: String, user: String, maxTokens: Long): String = "JA"
    }

    /**
     * Der gesicherte Träger stellt [question]; die Antwort sprengt den Deckel, und das Modell schreibt weiter,
     * OHNE am Ende MUTE zu senden. In der Lücke fragt er ohne Anrede "Wann fängt der Film an?".
     * @return was in der Lücke, nach dem Ende des Aufrufs und nach einer erneuten Frage zu hören war.
     */
    private fun gapScenario(question: String): Triple<List<String>, List<String>, List<String>> {
        val claude = Staged { t ->
            if (t.contains("Flüstern")) listOf("anfang" to listOf("SAY kurz\n", "Etwa dreißig Dezibel. ", tooLong), "schluss" to listOf("\n"))
            else listOf("frage" to listOf("SAY kurz\n", "Um acht."))
        }
        val rig = ThreadRig(claude, withProfile)
        try {
            claude.open("anfang")
            claude.open("frage")
            rig.wearerSays(question)
            assertTrue("erste Antwort", waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })
            Thread.sleep(300)
            rig.clock.set(2_000)
            rig.wearerSays("Wann fängt der Film an?")
            waitFor(1_000) { rig.speaker.spoken.size > 1 }
            val inGap = rig.speaker.spoken.toList()
            val shownMuted = rig.state.value.mutedUntilMs
            claude.open("schluss")
            waitFor(3_000) { claude.finished.any { it.contains("Flüstern") } }
            Thread.sleep(400)
            val afterEnd = rig.speaker.spoken.toList()
            rig.clock.set(4_000)
            rig.wearerSays("Wann fängt der Film an?")
            waitFor(2_000) { rig.speaker.spoken.size > afterEnd.size }
            return Triple(inGap, afterEnd, rig.speaker.spoken.toList())
        } finally {
            rig.close()
        }
    }

    @Test fun `Wissensfrage mit dem Wort leise ist keine Bitte um Ruhe - die naechste Frage wird beantwortet`() {
        val control = "Alfred, wie laut ist ein Flüstern"
        val question = "Alfred, wie leise ist ein Flüstern"
        assertTrue(!Heuristics.asksForQuiet(control))
        val (cGap, _, _) = gapScenario(control)
        assertEquals("Gegenprobe: ohne Ruhe-Wort wird die Frage in der Lücke beantwortet", listOf("Etwa dreißig Dezibel.", "Um acht."), cGap)

        val (inGap, _, _) = gapScenario(question)
        // "leise" in einer Wissensfrage ist keine Bitte um Ruhe: Die Frage in der Lücke wird gleich beantwortet.
        assertTrue(!Heuristics.asksForQuiet(question))
        assertEquals(listOf("Etwa dreißig Dezibel.", "Um acht."), inGap)
    }

    @Test fun `nicht gesicherte Stimme bittet um Ruhe - hinter dem Deckel gilt keine vorlaeufige Ruhe und MUTE wird abgelehnt`() {
        val claude = Staged { t ->
            if (t.contains("ruhig")) listOf("anfang" to listOf("SAY kurz\n", "Sehr wohl, Sir. ", tooLong), "schluss" to listOf("\nMUTE: 10\n"))
            else listOf("frage" to listOf("SAY kurz\n", "Um acht."))
        }
        // Ohne Profil: der leise Träger (-33 dBFS) ist ICH, aber nicht gesichert. Ebenso der Freund mit Profil.
        for ((config, hz, amp) in listOf(Triple(Pipeline.Config(), 115.0, 1530.0), Triple(withProfile, 200.0, 1300.0))) {
            val rig = ThreadRig(claude, config)
            try {
                claude.open("anfang"); claude.open("frage")
                rig.says(hz, amp, 600, "Alfred, sei bitte zehn Minuten ruhig")
                assertTrue(waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })
                Thread.sleep(300)
                rig.clock.set(2_000)
                rig.wearerSays("Alfred, wann fängt der Film an?")
                assertTrue("in der Lücke wird weiter geantwortet: ${rig.speaker.spoken}", waitFor(2_000) { rig.speaker.spoken.size > 1 })
                claude.open("schluss")
                waitFor(2_000) { claude.finished.any { it.contains("ruhig") } }
                Thread.sleep(300)
                assertEquals("nicht stumm, zuletzt=${rig.state.value.lastDecision}", 0L, rig.state.value.mutedUntilMs)
            } finally {
                rig.close()
            }
        }
    }


    // =====================================================================================================
    // 7. Eine zweite Stimme in derselben Äußerung

    @Test fun `ohne Profil - lacht der Traeger in den Ruf des Freundes, darf der Freund nichts und das Profil lernt nichts`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { memoAndMute }))
        // Der Freund (200 Hz, -34,4 dBFS) spricht 1,2 s, der Träger lacht oder sagt "ja genau" 0,5 s hinterher,
        // bevor die Transkription die Äußerung abschließt. Ein Viertel der Sprach-Stücke ist damit laut, und über
        // die ganze Äußerung gemessen sah das nach dem Träger aus. Der Anfang für sich genommen ist es nicht.
        repeat(4) {
            rig.pipeline.onAudio(silence(300), 9600)
            val f = tone(200.0, 1300.0, 1200)
            rig.pipeline.onAudio(f, f.size)
            val w = tone(115.0, 8000.0, 500)
            rig.pipeline.onAudio(w, w.size)
            rig.say("Alfred, merk dir der Code vom Fahrradschloss ist null null null null")
            runCurrent()
            advanceTimeBy(30_000)
        }
        assertEquals(emptyList<String>(), rig.env.notes)
        assertEquals(0L, rig.muted)
        assertTrue("Profil=${rig.env.profiles}", rig.env.profiles.isEmpty())
        assertEquals("abgelehnt: Anfang oder Ende der Äußerung klang nicht nach dem Träger (eine zweite Stimme?). Bitte allein wiederholen.", rig.last)
    }

    @Test fun `mit Profil - haengt der Freund seine Bitte an einen Satz des Traegers an, ist die Aeusserung nicht gesichert`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { memoAndMute }, quickReply = { _, _ -> "JA" }), withProfile)
        // Der Träger redet 1,5 s, der Freund hängt ohne Pause 0,9 s an. Die Transkription liefert EINE Äußerung.
        rig.pipeline.onAudio(silence(300), 9600)
        val w = tone(115.0, 8000.0, 1500)
        rig.pipeline.onAudio(w, w.size)
        val f = tone(200.0, 1300.0, 900)
        rig.pipeline.onAudio(f, f.size)
        rig.say("ich hab gestern den ganzen Abend an dem Aufsatz gesessen Alfred merk dir der Code vom Fahrradschloss ist null null null null")
        runCurrent()
        assertEquals("Mitschrift=${rig.state.value.transcript.takeLast(1)}", emptyList<String>(), rig.env.notes)
        assertEquals(0L, rig.muted)
        // Das Etikett bleibt, wie es war: Der größere Teil der Äußerung war der Träger.
        assertEquals(VoiceMeasure(rig.state.value.measures.last().levelDb, rig.state.value.measures.last().pitchHz, "ICH", false), rig.state.value.measures.last())
    }

    @Test fun `Gegenprobe - ein langer Satz des Traegers allein bleibt gesichert`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { memoOnly }), withProfile)
        rig.voice(115.0, 8000.0, "Alfred, merk dir bitte für nächste Woche den Zahnarzt am Dienstag um halb neun", ms = 3_000)
        runCurrent()
        assertEquals(listOf("Zahnarzt am Dienstag"), rig.env.notes)
        // Auch wenn er am Ende leiser wird (sechs Dezibel), solange er im eigenen Bereich bleibt.
        val quiet = Rig(this, FakeClaude(reply = { memoOnly }), withProfile)
        quiet.pipeline.onAudio(silence(300), 9600)
        val a = tone(115.0, 8000.0, 1800)
        quiet.pipeline.onAudio(a, a.size)
        val b = tone(112.0, 4000.0, 1200)
        quiet.pipeline.onAudio(b, b.size)
        quiet.say("Alfred, merk dir bitte für nächste Woche den Zahnarzt am Dienstag um halb neun")
        runCurrent()
        assertEquals(listOf("Zahnarzt am Dienstag"), quiet.env.notes)
    }

    // =====================================================================================================
    // 8. Ein Stimmprofil, das feststeht (in den Einstellungen eingelernt), und die Meldungen dazu

    @Test fun `ein eingelerntes Profil aus vielen Proben wird von einzelnen Aeusserungen weder veraendert noch ueberschrieben`() = runTest {
        val enrolled = VoiceProfile(-18.0, 115.0, 40)
        val rig = Rig(this, FakeClaude(reply = { memoOnly }), Pipeline.Config(voiceProfile = enrolled))
        assertEquals(enrolled, rig.state.value.voiceProfile)
        // Zehn Anreden in etwas anderer Stimmlage (zwei Halbtöne höher) und lauter: Früher wanderte das Profil
        // mit jeder ein Zehntel mit und wurde jedes Mal neu gespeichert.
        repeat(10) {
            rig.voice(129.0, 12_000.0, "Alfred, merk dir Zahnarzt am Dienstag")
            runCurrent()
            advanceTimeBy(30_000)
        }
        assertEquals("gemerkt wird trotzdem", 10, rig.env.notes.size)
        assertTrue("nichts gespeichert: ${rig.env.profiles}", rig.env.profiles.isEmpty())
        assertEquals(enrolled, rig.state.value.voiceProfile)
        // Seine gewöhnliche Stimme passt danach wie am ersten Tag.
        rig.wearer("Alfred, merk dir Zahnarzt am Dienstag")
        runCurrent()
        assertEquals(11, rig.env.notes.size)
    }

    @Test fun `ein nebenbei gelerntes Profil wird nur bis zehn Proben verfeinert und steht dann fest`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SAY kurz\n", "Ja, Sir.") }), withProfile) // fünf Proben
        repeat(8) {
            rig.voice(120.0, 8000.0, "Alfred, hörst du mich")
            runCurrent()
            advanceTimeBy(30_000)
        }
        assertEquals(listOf(6, 7, 8, 9, 10), rig.env.profiles.map { it.samples })
        assertEquals(10, rig.state.value.voiceProfile!!.samples)
    }

    @Test fun `mit Profil - passende Stimmlage, aber leiser als das Profil - die Anzeige nennt beide Pegel`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { memoOnly }), withProfile)
        rig.voice(115.0, 3050.0, "Alfred, merk dir Zahnarzt am Dienstag") // -27 dBFS, das Profil steht bei -18
        runCurrent()
        assertEquals(emptyList<String>(), rig.env.notes)
        assertEquals(
            "abgelehnt: Stimme passt in der Stimmlage, kam aber leiser an als im Stimmprofil (-27 dBFS, Profil -18). Lauter sprechen oder die Stimme neu einlernen.",
            rig.last,
        )
    }

    // =====================================================================================================
    // 9. Messwerte für die Anzeige

    @Test fun `zu jeder Zeile der Mitschrift stehen Pegel, Stimmlage, Einschaetzung und gesichert im Zustand`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { listOf("SKIP keine-frage") }, quickReply = { _, _ -> "NEIN" }), withProfile)
        rig.wearer("so jetzt aber mal los hier"); runCurrent(); advanceTimeBy(5_000)
        rig.friend("ich hab gestern den ganzen Abend gelesen"); runCurrent(); advanceTimeBy(5_000)
        rig.far("und nun die Nachrichten des Tages"); runCurrent(); advanceTimeBy(5_000)
        rig.say("das hier kam ohne jeden Ton an"); runCurrent()
        val ui = rig.state.value
        assertEquals(ui.transcript.size, ui.measures.size)
        assertEquals(listOf("ICH", "NAH", "FERN", "?"), ui.measures.map { it.voice })
        assertEquals(listOf(true, false, false, false), ui.measures.map { it.trusted })
        assertEquals(ui.transcript.map { it.substringBefore(":") }, ui.measures.map { it.voice })
        assertEquals(-18.6, ui.measures[0].levelDb!!, 0.5)
        assertEquals(115.0, ui.measures[0].pitchHz!!, 3.0)
        assertEquals(-34.4, ui.measures[1].levelDb!!, 0.5)
        assertEquals(200.0, ui.measures[1].pitchHz!!, 5.0)
        assertEquals(-50.7, ui.measures[2].levelDb!!, 0.5)
        assertNull(ui.measures[3].levelDb)
        assertNull(ui.measures[3].pitchHz)
        // Beide Listen werden gemeinsam gekürzt.
        repeat(70) { rig.say("Zeile Nummer $it kommt ohne Ton"); runCurrent(); advanceTimeBy(5_000) }
        assertEquals(60, rig.state.value.transcript.size)
        assertEquals(60, rig.state.value.measures.size)
    }

    // =====================================================================================================
    // 10. Dank, solange Ruhe gilt

    @Test fun `stumm - Danke Alfred hebt die eben zugesagte Ruhe nicht auf und bekommt keine Antwort`() = runTest {
        val rig = Rig(this, FakeClaude(reply = { user ->
            if (asked(user).contains("ruhig")) listOf("SAY kurz\n", "Sehr wohl, Sir.\n", "MUTE: 10") else listOf("SAY kurz\n", "Gern, Sir.")
        }), withProfile)
        rig.wearer("Alfred, sei bitte zehn Minuten ruhig")
        runCurrent()
        assertTrue(rig.muted > 0)
        advanceTimeBy(1_500)
        rig.wearer("Danke, Alfred.")
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertTrue("die Ruhe ist nach dem Dank weg: spoken=${rig.speaker.spoken}", rig.muted > 0)
        assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
        assertEquals("stumm: Dank gehört, die Ruhe gilt weiter", rig.last)
        // Steht mehr da als der Dank, ist es eine Anrede wie jede andere: Sie hebt die Ruhe auf.
        rig.wearer("Danke Alfred, und wie spät ist es?")
        runCurrent()
        assertEquals(0L, rig.muted)
        assertEquals(listOf("Sehr wohl, Sir.", "Gern, Sir."), rig.speaker.spoken.toList())
    }

    @Test fun `Danke Alfred in der Luecke hinter dem Deckel - die zugesagte Ruhe wird trotzdem eingetragen`() {
        val claude = Staged { t ->
            if (t.contains("ruhig")) listOf("anfang" to listOf("SAY kurz\n", "Sehr wohl, Sir. ", tooLong), "schluss" to listOf("\nMUTE: 10\n"))
            else listOf("dank" to listOf("SAY kurz\n", "Gern, Sir."))
        }
        val rig = ThreadRig(claude, withProfile)
        try {
            claude.open("anfang"); claude.open("dank")
            rig.wearerSays("Alfred, sei bitte zehn Minuten ruhig")
            assertTrue(waitFor(3_000) { rig.speaker.spoken.isNotEmpty() && !rig.state.value.speaking })
            Thread.sleep(300)
            rig.clock.set(1_500)
            rig.wearerSays("Danke, Alfred.")
            Thread.sleep(500)
            claude.open("schluss")
            waitFor(2_000) { claude.finished.any { it.contains("ruhig") } }
            assertTrue("zugesagt, aber nicht stumm: gesprochen=${rig.speaker.spoken}", waitFor(2_000) { rig.state.value.mutedUntilMs > 0 })
            assertEquals(listOf("Sehr wohl, Sir."), rig.speaker.spoken.toList())
        } finally {
            rig.close()
        }
    }

    @Test fun `blosser Dank - was dazu zaehlt und was nicht`() {
        val names = listOf("Alfred", "Jarvis")
        val thanks = listOf("Danke, Alfred.", "Alfred, danke dir", "Vielen Dank, Alfred", "Alles klar, Jarvis", "Ok Alfred", "Super, danke Alfred", "Thank you, Jarvis")
        val other = listOf(
            "Danke Alfred, und wie spät ist es?", "Alfred, du darfst wieder", "Alfred?", "Danke.", "Alfred, danke, das war falsch",
            "Alfred, ist das klar?", "Danke, Alfred, sprich bitte wieder mit mir",
        )
        assertEquals(emptyList<String>(), thanks.filter { !Heuristics.isMereThanks(it, names) })
        assertEquals(emptyList<String>(), other.filter { Heuristics.isMereThanks(it, names) })
    }
}

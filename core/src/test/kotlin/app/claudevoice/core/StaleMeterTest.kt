package app.claudevoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Der Stimm-Messer muss die Äußerung beschreiben, nicht alles seit der letzten fertigen Äußerung.
 *
 * Umgebung = ruhiger Raum (weißes Rauschen bei etwa -62 dBFS), in dem 15 % der 100-ms-Stücke 20 dB lauter
 * sind (Tippen, Geschirr, eine Tür). Nichts davon ist Sprache: Die Transkription meldet dazu nichts, und
 * so räumt auch niemand den Messer auf. Daran hängt, ob die Stimme als ICH oder NAH gilt, also `trusted`.
 */
private fun ambient(seconds: Int, seed: Long = 7): List<ByteArray> {
    val random = Random(seed)
    return List(seconds * 10) {
        val amplitude = if (random.nextDouble() < 0.15) 450 else 45
        ByteArray(3200).also { out ->
            for (i in 0 until 1600) {
                val s = random.nextInt(2 * amplitude + 1) - amplitude
                out[2 * i] = (s and 0xFF).toByte()
                out[2 * i + 1] = (s shr 8).toByte()
            }
        }
    }
}

/** Der Träger am Kopfhörer-Mikro: 2 s, etwa -18,6 dBFS, 115 Hz. */
private val own = tone(115.0, 8000.0, 2000)

/** Gleichmäßiges Raumrauschen (etwa -52 dBFS bei Amplitude 150) unter [pcm] gemischt. */
private fun inNoise(pcm: ByteArray, amplitude: Int = 150, seed: Long = 3): ByteArray {
    val random = Random(seed)
    val out = ByteArray(pcm.size)
    for (i in 0 until pcm.size / 2) {
        val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
        val v = (s + random.nextInt(2 * amplitude + 1) - amplitude).coerceIn(-32768, 32767)
        out[2 * i] = (v and 0xFF).toByte()
        out[2 * i + 1] = (v shr 8).toByte()
    }
    return out
}

@OptIn(ExperimentalCoroutinesApi::class)
class StaleMeterTest {
    private val profile = VoiceProfile(-18.0, 115.0, 5)

    private class Rig(scope: TestScope, config: Pipeline.Config, val speaker: FakeSpeaker = FakeSpeaker()) {
        val claude = FakeClaude()
        val state = MutableStateFlow(UiState())
        val env = FakeEnv()
        val pipeline = Pipeline(
            Brain(claude, Profile()), speaker, state, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler), config, env,
        ) { scope.testScheduler.currentTime }.also { it.start() }

        fun hear(chunks: List<ByteArray>) = chunks.forEach { pipeline.onAudio(it, it.size) }

        /** Eine Äußerung, wie sie live ankommt: Das Audio beginnt, 300 ms später der erste Zwischenstand, am Ende die fertige Äußerung. */
        fun utter(pcm: ByteArray, text: String) {
            val head = 16000 * 300 / 1000 * 2
            pipeline.onAudio(pcm.copyOfRange(0, head), head)
            pipeline.onPartial(text.substringBefore(' '), null)
            pipeline.onAudio(pcm.copyOfRange(head, pcm.size), pcm.size - head)
            pipeline.onCommitted(text, null, null)
        }
    }

    private val memo = "Alfred, merk dir das Kennzeichen M AB 1234"
    private val noted = listOf("SAY kurz\nNotiert.\nMEMO: Kennzeichen M-AB 1234\n")

    @Test fun `Merken klappt auch nach einer ruhigen Minute mit Geklapper`() = runTest {
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { noted }

        // Kontrolle: direkt nach einer fertigen Äußerung wird die Bitte des Trägers erfüllt.
        rig.utter(own, memo)
        runCurrent()
        assertEquals(listOf("Kennzeichen M-AB 1234"), rig.env.notes)

        // Eine ruhige Minute mit etwas Geklapper, dann dieselbe Bitte mit derselben Stimme.
        rig.hear(ambient(60))
        rig.utter(own, memo)
        runCurrent()
        assertEquals(
            "zuletzt: ${rig.state.value.lastDecision}, Mitschrift: ${rig.state.value.transcript}",
            listOf("Kennzeichen M-AB 1234", "Kennzeichen M-AB 1234"), rig.env.notes,
        )
    }

    @Test fun `nach zehn Minuten Geklapper zaehlt die neue Aeusserung, nicht der volle Puffer`() = runTest {
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { noted }
        rig.hear(ambient(600)) // mehr Geräusch-Frames, als der Messer überhaupt fasst
        rig.utter(own, memo)
        runCurrent()
        assertEquals("ICH", rig.state.value.transcript.last().substringBefore(':'))
        assertEquals("zuletzt: ${rig.state.value.lastDecision}", listOf("Kennzeichen M-AB 1234"), rig.env.notes)
    }

    @Test fun `Reichweite Nur ich antwortet dem Traeger auch nach einer ruhigen Minute`() = runTest {
        val rig = Rig(this, Pipeline.Config(reach = Reach.ICH, voiceProfile = profile))
        rig.claude.reply = { listOf("SAY kurz\n", "Zweitausendneunhundertzweiundsechzig Meter.") }
        rig.hear(ambient(60))
        // Fertige Äußerung ohne Zwischenstand davor, wie in den anderen Pipeline-Tests.
        rig.pipeline.onAudio(own, own.size)
        rig.pipeline.onCommitted("Wie hoch ist die Zugspitze?", null, null)
        runCurrent()
        assertEquals("Mitschrift: ${rig.state.value.transcript}", 1, rig.claude.respondCalls.size)
    }

    @Test fun `altes Geraeusch ueberlebt ein Neuverbinden nicht`() = runTest {
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { noted }
        rig.hear(ambient(60))
        rig.pipeline.onTranscriberReset() // neue Sitzung (Neuverbinden oder Aufwachen aus der Bereitschaft)
        rig.utter(own, memo)
        runCurrent()
        assertEquals("zuletzt: ${rig.state.value.lastDecision}", listOf("Kennzeichen M-AB 1234"), rig.env.notes)
    }

    @Test fun `fremde Stimme nach einem lauten Geraeusch des Traegers gilt nicht als seine`() = runTest {
        // Die andere Richtung, dieselbe Ursache: Der Träger summt, hustet oder lacht drei Sekunden lang (laut am
        // Kopfhörer-Mikro, kein Text, also keine fertige Äußerung). Eine Sekunde später bittet jemand anderes,
        // 20 dB leiser und mit ähnlicher Stimmlage, den Assistenten, sich etwas zu merken.
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { listOf("SAY kurz\nNotiert.\nMEMO: Der Code vom Fahrradschloss ist null null null null\n") }
        val hum = tone(115.0, 8000.0, 3000)
        rig.hear(listOf(silence(300), hum, silence(1000)))
        rig.utter(tone(120.0, 800.0, 2000), "Alfred, merk dir der Code vom Fahrradschloss ist null null null null")
        runCurrent()
        assertTrue(
            "eine fremde Stimme hat ins Gedächtnis geschrieben: ${rig.env.notes}, Mitschrift: ${rig.state.value.transcript}",
            rig.env.notes.isEmpty(),
        )
        assertNotEquals("ICH", rig.state.value.transcript.last().substringBefore(':'))
        assertTrue(rig.state.value.lastDecision!!.startsWith("abgelehnt"))
    }

    @Test fun `dasselbe ohne Zwischenstand - auch dann zaehlt nur die letzte Aeusserung`() = runTest {
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { listOf("SAY kurz\nNotiert.\nMEMO: Der Code vom Fahrradschloss ist null null null null\n") }
        val hum = tone(115.0, 8000.0, 3000)
        val other = tone(120.0, 800.0, 2000)
        rig.hear(listOf(silence(300), hum, silence(1000), other))
        rig.pipeline.onCommitted("Alfred, merk dir der Code vom Fahrradschloss ist null null null null", null, null)
        runCurrent()
        assertTrue("Mitschrift: ${rig.state.value.transcript}", rig.env.notes.isEmpty())
        assertNotEquals("ICH", rig.state.value.transcript.last().substringBefore(':'))
    }

    // --- Eine Stimme, die der Messer gar nicht hört -------------------------------------------------
    // Der Messer zählt nur, was sich klar vom Grundrauschen abhebt. Eine leise fremde Stimme hinterlässt
    // dann nichts, und das Letzte, was er gehört hat, ist ein Laut des Trägers von vorhin. Der darf nicht
    // für die fremde Äußerung stehen: Sonst schreibt ein Fremder ins Gedächtnis.

    private val codeMemo = "Alfred, merk dir der Code vom Fahrradschloss ist null null null null"
    private val codeNoted = listOf("SAY kurz\nNotiert.\nMEMO: Der Code vom Fahrradschloss ist null null null null\n")

    @Test fun `fremde Stimme unter der Messschwelle nach einem Laut des Traegers gilt nicht als seine`() = runTest {
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { codeNoted }
        // Der Träger lacht oder räuspert sich eine Sekunde lang (laut am Kopfhörer-Mikro, kein Text). Eine Sekunde Pause.
        rig.hear(listOf(silence(300), tone(115.0, 8000.0, 1000), silence(1000)))
        // Jemand anderes, rund -65 dBFS am Mikro: Die Transkription versteht ihn, der Stimm-Messer misst nichts.
        rig.utter(tone(180.0, 40.0, 2000), codeMemo)
        runCurrent()
        assertTrue(
            "eine fremde Stimme hat ins Gedächtnis geschrieben: ${rig.env.notes}, Mitschrift: ${rig.state.value.transcript}",
            rig.env.notes.isEmpty(),
        )
        assertEquals("?", rig.state.value.transcript.last().substringBefore(':'))
        assertTrue(rig.env.profiles.isEmpty())
    }

    @Test fun `leiser Fremder im Raumrauschen nach dem Lachen des Traegers gilt nicht als er`() = runTest {
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { codeNoted }
        rig.hear(listOf(inNoise(silence(3000))))
        // Der Träger lacht eine Sekunde lang. Dann anderthalb Sekunden Pause.
        rig.hear(listOf(inNoise(tone(115.0, 8000.0, 1000), seed = 4), inNoise(silence(1500), seed = 5)))
        // Jemand gegenüber am Tisch: rund -41 dBFS, nur 11 dB über dem Raumrauschen. Auch ihn misst der Messer nicht.
        rig.utter(inNoise(tone(120.0, 600.0, 2000), seed = 6), codeMemo)
        runCurrent()
        assertTrue(
            "eine fremde Stimme hat ins Gedächtnis geschrieben: ${rig.env.notes}, Mitschrift: ${rig.state.value.transcript}",
            rig.env.notes.isEmpty(),
        )
        assertNotEquals("ICH", rig.state.value.transcript.last().substringBefore(':'))
    }

    @Test fun `ohne Zwischenstand steht ein Laut von vor Sekunden nicht fuer die eben beendete Aeusserung`() = runTest {
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { codeNoted }
        // Wie oben, nur meldet die Transkription die Äußerung des Fremden gleich als fertig, ohne Zwischenstand.
        // Der letzte Laut im Messer (das Lachen des Trägers) ist da dreieinhalb Sekunden her.
        rig.hear(listOf(silence(300), tone(115.0, 8000.0, 1000), silence(1000), tone(180.0, 40.0, 2000), silence(500)))
        rig.pipeline.onCommitted(codeMemo, null, null)
        runCurrent()
        assertTrue(
            "eine fremde Stimme hat ins Gedächtnis geschrieben: ${rig.env.notes}, Mitschrift: ${rig.state.value.transcript}",
            rig.env.notes.isEmpty(),
        )
        assertEquals("?", rig.state.value.transcript.last().substringBefore(':'))
    }

    @Test fun `ohne Zwischenstand zaehlt die Stimme des Traegers auch nach der Stille-Frist der Transkription`() = runTest {
        // Die Gegenprobe: Zwischen dem letzten Wort und der fertigen Äußerung liegt eine Sekunde Stille (so lange
        // wartet die Transkription höchstens). Das ist noch seine Äußerung.
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { noted }
        rig.hear(listOf(silence(300), own, silence(1000)))
        rig.pipeline.onCommitted(memo, null, null)
        runCurrent()
        assertEquals("ICH", rig.state.value.transcript.last().substringBefore(':'))
        assertEquals("zuletzt: ${rig.state.value.lastDecision}", listOf("Kennzeichen M-AB 1234"), rig.env.notes)
    }

    @Test fun `kurze Aeusserung des Traegers bleibt seine, auch wenn der erste Zwischenstand etwas spaet kommt`() = runTest {
        // Die Gegenprobe: Ein kurzer Satz ist schneller gesagt, als die Transkription den ersten Text meldet.
        // Kommt der innerhalb einer normalen Sprechpause nach dem letzten Laut, zählt die Messung noch.
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile))
        rig.claude.reply = { listOf("SAY kurz\nSehr wohl.\nMUTE: 10\n") }
        rig.hear(listOf(silence(300), tone(115.0, 8000.0, 900), silence(400)))
        rig.pipeline.onPartial("Alfred, sei", null)
        rig.hear(listOf(silence(500)))
        rig.pipeline.onCommitted("Alfred, sei bitte zehn Minuten stumm", null, null)
        runCurrent()
        assertEquals("ICH", rig.state.value.transcript.last().substringBefore(':'))
        assertTrue("stumm bis ${rig.state.value.mutedUntilMs}, zuletzt: ${rig.state.value.lastDecision}", rig.state.value.mutedUntilMs > 0)
    }

    @Test fun `ein Stopp des Traegers wirkt auch, wenn die Transkription es spaet meldet`() = runTest {
        val playing = CompletableDeferred<Unit>() // hält die Wiedergabe an: Die Antwort ist gerade zu hören
        val rig = Rig(this, Pipeline.Config(voiceProfile = profile), FakeSpeaker(playing))
        rig.claude.reply = { listOf("SAY kurz\n", "Zweitausendneunhundertzweiundsechzig Meter.") }
        rig.utter(own, "Alfred, wie hoch ist die Zugspitze?")
        runCurrent()
        assertTrue(rig.state.value.speaking)

        // "Stopp" dauert 400 ms. Die Transkription meldet das Wort erst 900 ms nach seinem Ende. Für die Frage,
        // wem die Äußerung gehört, ist das zu spät; fürs Aufhören zählt, dass eben noch der Träger zu hören war.
        rig.hear(listOf(silence(300), tone(115.0, 8000.0, 400), silence(900)))
        rig.pipeline.onPartial("Stopp", null)
        runCurrent()
        assertTrue("die Ausgabe wurde nicht angehalten", rig.speaker.stops >= 1)
        assertFalse(rig.state.value.speaking)
        playing.complete(Unit)
    }
}

/** Der Messer für sich: Ringpuffer und der Schnitt auf die laufende Äußerung. */
class VoiceMeterWindowTest {

    /** [cycles] kurze Stücke Sprache mit Pausen dazwischen: je 300 ms Stille und 600 ms Ton (20 Sprach-Frames). */
    private fun bursts(meter: VoiceMeter, cycles: Int, hz: Double, amplitude: Double) {
        val quiet = silence(300)
        val loud = tone(hz, amplitude, 600)
        repeat(cycles) {
            meter.feed(quiet, quiet.size)
            meter.feed(loud, loud.size)
        }
    }

    @Test fun `voller Puffer verliert das Aelteste, nicht das Neueste`() {
        val meter = VoiceMeter()
        bursts(meter, cycles = 110, hz = 150.0, amplitude = 600.0)  // 2200 leise Frames: mehr, als hineinpassen
        bursts(meter, cycles = 60, hz = 115.0, amplitude = 8000.0)  // 1200 laute Frames danach
        val f = meter.takeSummary()!!
        // Bleiben die neuesten 2000 Frames, sind 1200 davon laut: Der aktive Pegel ist der laute.
        assertTrue("Pegel war ${f.levelDb} dB: die neuen Frames fehlen", f.levelDb > -25.0)
        assertEquals(60_000, f.speechMs)
    }

    @Test fun `Schnitt auf die Aeusserung trennt an der Pause davor`() {
        val meter = VoiceMeter()
        ambient(600).forEach { meter.feed(it, it.size) }
        meter.feed(own, own.size)
        meter.keepUtterance(3_000)
        val f = meter.takeSummary()!!
        assertTrue("Pegel war ${f.levelDb} dB", abs(f.levelDb - -18.6) < 1.5)
        assertTrue("Stimmlage war ${f.pitchHz} Hz", abs(f.pitchHz!! - 115.0) < 4.0)
        assertTrue("Dauer war ${f.speechMs} ms", f.speechMs in 1_900..3_000)
    }

    @Test fun `ohne Pause reicht der Schnitt hoechstens bis zur Obergrenze zurueck`() {
        val meter = VoiceMeter()
        meter.feed(silence(300), 9600)
        val long = tone(115.0, 8000.0, 5_000)
        meter.feed(long, long.size)
        meter.keepUtterance(3_000)
        assertEquals(3_000, meter.peekSummary()!!.speechMs)
    }

    @Test fun `ein kurzes Knacken direkt vor einer Pause gilt nicht als Aeusserung`() {
        val meter = VoiceMeter()
        meter.feed(silence(300), 9600)
        val speech = tone(115.0, 8000.0, 900)
        meter.feed(speech, speech.size)
        meter.feed(silence(700), 16000 * 700 / 1000 * 2)
        val click = tone(300.0, 9000.0, 60)
        meter.feed(click, click.size)
        meter.keepUtterance(3_000)
        // Zwei Frames Knacken sind zu wenig, um davor abzuschneiden: Die Sprache bleibt dabei.
        assertTrue("Dauer war ${meter.peekSummary()?.speechMs} ms", meter.peekSummary()!!.speechMs >= 900)
    }

    @Test fun `laufende Aeusserung - Sprache, die schon eine Pause her ist, gehoert nicht dazu`() {
        val meter = VoiceMeter()
        meter.feed(silence(300), 9600)
        val laugh = tone(115.0, 8000.0, 1_000)
        meter.feed(laugh, laugh.size)
        meter.feed(silence(1_500), 16000 * 1_500 / 1000 * 2)
        // Der erste Zwischenstand kommt: Jemand spricht gerade. Das Lachen von vor anderthalb Sekunden war es nicht.
        meter.keepUtterance(3_000, minMs = 0)
        assertNull(meter.peekSummary())
    }

    @Test fun `laufende Aeusserung - geschnitten wird an der Pause, auch wenn danach erst wenig Sprache kam`() {
        val meter = VoiceMeter()
        meter.feed(silence(300), 9600)
        val laugh = tone(115.0, 8000.0, 1_000)
        meter.feed(laugh, laugh.size)
        meter.feed(silence(1_500), 16000 * 1_500 / 1000 * 2)
        val begun = tone(200.0, 1300.0, 90) // die neue Äußerung hat eben erst begonnen: drei Frames
        meter.feed(begun, begun.size)
        meter.keepUtterance(3_000, minMs = 0)
        val more = tone(200.0, 1300.0, 600)
        meter.feed(more, more.size)
        val f = meter.takeSummary()!!
        assertTrue("Pegel war ${f.levelDb} dB: das Lachen zählt noch mit", f.levelDb < -30.0)
        assertTrue("Dauer war ${f.speechMs} ms", f.speechMs in 600..750)
    }

    @Test fun `leeren verwirft alles`() {
        val meter = VoiceMeter()
        meter.feed(silence(300), 9600)
        meter.feed(own, own.size)
        meter.clear()
        assertNull(meter.peekSummary())
    }
}

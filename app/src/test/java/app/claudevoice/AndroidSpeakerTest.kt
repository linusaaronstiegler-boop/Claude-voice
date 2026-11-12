package app.claudevoice

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import app.claudevoice.core.RouteVerdict
import app.claudevoice.core.SpeechSynth
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioTrack
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Die letzte Schranke vor dem Lautsprecher: Eine Antwort wird nur abgespielt, wenn ihr Ton jetzt in
 * Kopfhörer ginge, und sie bricht ab, wenn sich das mittendrin ändert.
 *
 * Läuft mit Robolectric: Der Abspieler ist nachgestellt (es erklingt nichts), geprüft wird, OB Ton an
 * ihn geschrieben wird. Wie schnell ein echtes Handy einen Wechsel des Ausgabewegs meldet, zeigt nur
 * der Gerätetest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSpeakerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val looper get() = shadowOf(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default)

    private val headphones = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build()
    private val phoneSpeaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()

    // Für das Kopfhörer-Mikro: ein Bluetooth-Headset (vom Träger bestätigt) und die Hörmuschel des Handys,
    // auf die Android den Ton legt, solange der Telefonkanal zum Headset fehlt.
    private val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLE_HEADSET).build()
    private val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
    private val media = AndroidSpeaker.attributes(voiceCallRoute = false)
    private val call = AndroidSpeaker.attributes(voiceCallRoute = true)
    private var confirmed = emptySet<String>()

    private val lost = CopyOnWriteArrayList<String>()
    private val withheld = CopyOnWriteArrayList<String>()
    private val stopRequests = AtomicInteger()
    private val written = AtomicInteger() // Bytes, die beim Abspieler angekommen sind
    private val firstAudio = AtomicInteger()
    private val onWritten = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written.addAndGet(data.size) }
    private var speaker: AndroidSpeaker? = null

    /** Sprachausgabe-Attrappe: je Satz zwei Stücke Stille; [hold] hält nach jedem Stück an, bis es erfüllt ist. */
    private class FakeSynth(@Volatile var hold: CompletableDeferred<Unit>? = null) : SpeechSynth {
        override val sampleRate = 24000
        val delivered = AtomicInteger()
        override suspend fun synthesize(sentences: ReceiveChannel<String>, onPcm: suspend (ByteArray, Int) -> Unit) {
            for (sentence in sentences) {
                repeat(2) {
                    onPcm(ByteArray(960), 960)
                    delivered.incrementAndGet()
                    hold?.await()
                }
            }
        }
    }

    @Before fun setUp() {
        ShadowAudioTrack.addAudioDataListener(onWritten)
    }

    @After fun tearDown() {
        scope.cancel()
        speaker?.release()
        ShadowAudioTrack.removeAudioDataListener(onWritten)
        ShadowAudioTrack.resetTest()
    }

    /** Legt fest, wohin Android eine Antwort leiten würde (und worüber der Abspieler dann läuft). */
    private fun routeTo(device: AudioDeviceInfo) {
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.of(device))
        ShadowAudioTrack.setRoutedDevice(device)
    }

    /** Dasselbe für den Telefonkanal, über den die Antwort beim Kopfhörer-Mikro läuft. */
    private fun callRouteTo(device: AudioDeviceInfo) {
        shadowOf(audio).setAudioDevicesForAttributes(call, ImmutableList.of(device))
        ShadowAudioTrack.setRoutedDevice(device)
    }

    /** Stellt nach, dass andere Apps gerade Töne dieser Arten spielen (z. B. ein Wecker). */
    private fun othersPlay(vararg usages: Int) = shadowOf(audio).setActivePlaybackConfigurationsFor(
        usages.map { AudioAttributes.Builder().setUsage(it).build() }
    )

    private fun speaker(
        synth: SpeechSynth,
        voiceCallRoute: Boolean = false,
        onStopRequested: () -> Unit = {},
        onRouteLost: (String) -> Unit = { lost += it },
    ): AndroidSpeaker = AndroidSpeaker(
        context, synth, voiceCallRoute, pauseMusic = true,
        route = OutputRoute(context) { confirmed },
        onStopRequested = { stopRequests.incrementAndGet(); onStopRequested() },
        onRouteLost = onRouteLost,
        onWithheld = { withheld += it },
    ).also { speaker = it }

    /** Sprachausgabe für das Kopfhörer-Mikro; das Headset hat der Träger schon bestätigt. */
    private fun callSpeaker(synth: SpeechSynth, onStopRequested: () -> Unit = {}): AndroidSpeaker {
        val route = OutputRoute(context) { confirmed }
        confirmed = setOf((route.verdict(headset) as RouteVerdict.Ask).device.key)
        return speaker(synth, voiceCallRoute = true, onStopRequested = onStopRequested)
    }

    private fun say(speaker: AndroidSpeaker, vararg sentences: String): Job {
        val channel = Channel<String>(Channel.UNLIMITED)
        sentences.forEach { channel.trySend(it) }
        channel.close()
        return scope.launch { speaker.speak(channel) { firstAudio.incrementAndGet() } }
    }

    /**
     * Lässt Haupt-Thread und Hintergrund laufen, bis [done] gilt.
     * Die Frist (30 Sekunden) ist nur ein Netz gegen einen Test, der sonst ewig hinge. Im guten Fall kostet
     * sie nichts. Der erste Test dieser Klasse lädt Abspieler und Kopfhörer-Taste zum ersten Mal; auf einem
     * ausgelasteten Rechner dauerte er schon über zwölf Sekunden, die früheren zehn waren dafür zu knapp.
     */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done() && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(5)
        }
        assertTrue("Zeit abgelaufen", done())
    }

    @Test fun `geht der Ton in Kopfhoerer wird die Antwort gespielt`() {
        routeTo(headphones)
        val out = speaker(FakeSynth())
        assertNull(out.routeProblem())
        val job = say(out, "Knapp dreitausend Meter.")
        waitFor { job.isCompleted }
        assertFalse(job.isCancelled)
        assertEquals(1, firstAudio.get())
        assertEquals("beide Stücke kamen beim Abspieler an", 1920, written.get())
        assertTrue(lost.isEmpty())
    }

    @Test fun `ginge der Ton in den Lautsprecher bleibt die Antwort stumm und die Erzeugung endet`() {
        routeTo(phoneSpeaker) // z. B. Ausgabe auf "Dieses Handy" umgestellt, Kopfhörer weiter verbunden
        val synth = FakeSynth()
        val out = speaker(synth)
        assertEquals("Kopfhörer getrennt. Das Zuhören wurde beendet.", out.routeProblem())

        val job = say(out, "Ihr Arzttermin ist am Dienstag.", "Um zehn Uhr.")
        waitFor { job.isCompleted }
        assertTrue("die Antwort gilt als abgebrochen", job.isCancelled)
        assertEquals("kein einziges Byte erreichte den Abspieler", 0, written.get())
        assertEquals("niemand glaubt, es sei etwas zu hören gewesen", 0, firstAudio.get())
        assertEquals("nach dem ersten Stück wurde nichts mehr erzeugt", 0, synth.delivered.get())
        assertEquals(listOf("Kopfhörer getrennt. Das Zuhören wurde beendet."), lost)
    }

    @Test fun `jede Antwort wird neu geprueft`() {
        routeTo(headphones)
        val out = speaker(FakeSynth())
        val first = say(out, "Ja.")
        waitFor { first.isCompleted }
        assertEquals(1920, written.get())

        routeTo(phoneSpeaker) // zwischen zwei Antworten hat sich der Weg geändert
        val second = say(out, "Nein.")
        waitFor { second.isCompleted }
        assertEquals("die zweite Antwort blieb stumm", 1920, written.get())
        assertEquals(1, lost.size)
    }

    @Test fun `ohne einen einzigen Satz wird nichts geprueft und nichts gemeldet`() {
        routeTo(phoneSpeaker)
        val out = speaker(FakeSynth())
        val job = say(out)
        waitFor { job.isCompleted }
        assertFalse(job.isCancelled)
        assertTrue(lost.isEmpty())
    }

    @Test fun `wechselt der Weg mitten in der Antwort verstummt sie sofort`() {
        routeTo(headphones)
        val hold = CompletableDeferred<Unit>()
        val synth = FakeSynth(hold)
        lateinit var job: Job
        // Wie der Dienst: Auf die Meldung hin wird die laufende Antwort abgebrochen.
        val out = speaker(synth) { why -> lost += why; job.cancel() }
        job = say(out, "Ein langer Satz.", "Noch einer.")
        waitFor { synth.delivered.get() == 1 }
        assertEquals(960, written.get())
        looper.idleFor(Duration.ofMillis(200)) // die Nachprüfung kurz nach dem Start besteht
        assertTrue(lost.isEmpty())

        ShadowAudioTrack.setRoutedDevice(phoneSpeaker) // das Auto verbindet sich, Kopfhörer herausgezogen ...
        looper.idleFor(Duration.ofMillis(500))
        waitFor { lost.isNotEmpty() }
        assertEquals(listOf("Kopfhörer getrennt. Das Zuhören wurde beendet."), lost)

        hold.complete(Unit)
        waitFor { job.isCompleted }
        assertTrue(job.isCancelled)
        assertEquals("nach dem Wechsel kam nichts mehr beim Abspieler an", 960, written.get())
    }

    // --- Mehrere Geräte zugleich, Wecker und Klingelton --------------------------------------

    @Test fun `laeuft der Abspieler ueber Kopfhoerer und Lautsprecher zugleich verstummt die Antwort`() {
        // Android kann denselben Ton über mehrere Geräte ausgeben. Früher wurde nur EINES davon angesehen;
        // waren das zufällig die Kopfhörer, lief die Antwort weiter und war über den Lautsprecher zu hören.
        routeTo(headphones)
        val hold = CompletableDeferred<Unit>()
        val synth = FakeSynth(hold)
        lateinit var job: Job
        val out = speaker(synth) { why -> lost += why; job.cancel() }
        job = say(out, "Ein langer Satz.", "Noch einer.")
        waitFor { synth.delivered.get() == 1 }
        looper.idleFor(Duration.ofMillis(200))
        assertTrue(lost.isEmpty())

        out.actualDevices = { listOf(headphones, phoneSpeaker) } // der nachgestellte Abspieler kennt nur ein Gerät
        looper.idleFor(Duration.ofMillis(500))
        waitFor { lost.isNotEmpty() }
        assertEquals(listOf("Kopfhörer getrennt. Das Zuhören wurde beendet."), lost)

        hold.complete(Unit)
        waitFor { job.isCompleted }
        assertEquals("danach kam nichts mehr beim Abspieler an", 960, written.get())
    }

    @Test fun `solange ein Wecker klingelt beginnt keine Antwort, das Zuhoeren geht aber weiter`() {
        // Wecker und Klingeltöne spielt Android über Lautsprecher UND Kopfhörer. Hängen die Kopfhörer am
        // Kabel, nähme die Antwort denselben Weg. Die Kopfhörer sind aber noch da: kein Grund, alles zu beenden.
        routeTo(headphones)
        othersPlay(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ALARM)
        val synth = FakeSynth()
        val out = speaker(synth)
        assertNull("am Ausgabeweg selbst ist nichts auszusetzen", out.routeProblem())

        val job = say(out, "Ihr Arzttermin ist am Dienstag.")
        waitFor { job.isCompleted }
        assertTrue("die Antwort gilt als abgebrochen", job.isCancelled)
        assertEquals("kein einziges Byte erreichte den Abspieler", 0, written.get())
        assertEquals(0, firstAudio.get())
        assertTrue("der Dienst wird nicht beendet: $lost", lost.isEmpty())
        assertEquals(listOf(AndroidSpeaker.LOUD_SOUND_NOTE), withheld)
        assertEquals("die Pipeline erfährt, dass diese Antwort entfällt", 1, stopRequests.get())

        // Ist der Wecker aus, wird wieder gesprochen.
        othersPlay(AudioAttributes.USAGE_MEDIA)
        val next = say(out, "Um zehn Uhr.")
        waitFor { next.isCompleted }
        assertFalse(next.isCancelled)
        assertEquals(1920, written.get())
    }

    @Test fun `beginnt ein Klingelton mitten in der Antwort verstummt sie, das Zuhoeren geht weiter`() {
        routeTo(headphones)
        val hold = CompletableDeferred<Unit>()
        val synth = FakeSynth(hold)
        lateinit var job: Job
        val out = speaker(synth, onStopRequested = { job.cancel() }) // wie der Dienst: die Pipeline bricht die Antwort ab
        job = say(out, "Ein langer Satz.", "Noch einer.")
        waitFor { synth.delivered.get() == 1 }
        looper.idleFor(Duration.ofMillis(200))
        assertEquals(0, stopRequests.get())

        othersPlay(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        // Auf manchen Handys meldet der Abspieler dabei zusätzlich den Lautsprecher. Das darf nicht wie
        // "Kopfhörer getrennt" aussehen und das Zuhören beenden.
        out.actualDevices = { listOf(headphones, phoneSpeaker) }
        looper.idleFor(Duration.ofMillis(500))
        waitFor { stopRequests.get() > 0 }
        assertTrue("der Dienst wird nicht beendet: $lost", lost.isEmpty())
        assertEquals(listOf(AndroidSpeaker.LOUD_SOUND_NOTE), withheld)

        hold.complete(Unit)
        waitFor { job.isCompleted }
        assertTrue(job.isCancelled)
        assertEquals("nach dem Klingelton kam nichts mehr beim Abspieler an", 960, written.get())
    }

    // --- Kopfhörer-Mikro: Schonfrist für den Telefonkanal ------------------------------------

    @Test fun `kurze Unterbrechung des Telefonkanals waehrend einer Antwort beendet nicht das Zuhoeren`() {
        // Ein Druck auf die Kopfhörer-Taste gilt im Telefonie-Modus als "Auflegen": Android trennt den
        // Telefonkanal und baut ihn kurz darauf wieder auf. Früher endete damit sofort der ganze Dienst,
        // mit der Meldung "Kopfhörer getrennt".
        callRouteTo(headset)
        val hold = CompletableDeferred<Unit>()
        val synth = FakeSynth(hold)
        lateinit var job: Job
        val out = callSpeaker(synth, onStopRequested = { job.cancel() })
        assertNull(out.routeProblem())
        job = say(out, "Ein langer Satz.", "Noch einer.")
        waitFor { synth.delivered.get() == 1 }
        looper.idleFor(Duration.ofMillis(200))
        assertEquals(0, stopRequests.get())

        callRouteTo(earpiece) // der Kanal ist weg, der Ton fiele auf die Hörmuschel des Handys
        looper.idleFor(Duration.ofMillis(600))
        assertEquals("die Antwort verstummt sofort", 1, stopRequests.get())
        assertEquals("und der Nutzer kann nachlesen, warum", listOf(AndroidSpeaker.CALL_LINK_NOTE), withheld)
        assertTrue("über das Zuhören ist noch nicht entschieden: $lost", lost.isEmpty())

        looper.idleFor(Duration.ofMillis(400))
        callRouteTo(headset) // nach einer Sekunde steht der Kanal wieder
        looper.idleFor(Duration.ofSeconds(5))
        assertTrue("der Dienst wurde aufgefordert zu enden: $lost", lost.isEmpty())

        hold.complete(Unit)
        waitFor { job.isCompleted }
        assertTrue(job.isCancelled)
        assertEquals("nach der Unterbrechung kam von dieser Antwort nichts mehr beim Abspieler an", 960, written.get())

        // Die nächste Antwort wird wieder gespielt.
        val next = say(out, "Ja.")
        hold.complete(Unit)
        waitFor { next.isCompleted }
        assertFalse(next.isCancelled)
        assertEquals(960 + 1920, written.get())
    }

    @Test fun `kommt der Telefonkanal nicht zurueck endet das Zuhoeren nach der Schonfrist mit dem wahren Grund`() {
        callRouteTo(headset)
        val hold = CompletableDeferred<Unit>()
        val synth = FakeSynth(hold)
        lateinit var job: Job
        val out = callSpeaker(synth, onStopRequested = { job.cancel() })
        job = say(out, "Ein langer Satz.")
        waitFor { synth.delivered.get() == 1 }
        looper.idleFor(Duration.ofMillis(200))

        callRouteTo(earpiece)
        looper.idleFor(Duration.ofMillis(600))
        assertEquals(1, stopRequests.get())
        looper.idleFor(Duration.ofSeconds(2))
        assertTrue("innerhalb der Schonfrist wird noch nichts gemeldet: $lost", lost.isEmpty())

        looper.idleFor(Duration.ofSeconds(2))
        assertEquals(listOf("Die Verbindung zum Kopfhörer-Mikro ist abgerissen. Das Zuhören wurde beendet."), lost)
        hold.complete(Unit)
    }

    @Test fun `laeuft gleich die naechste Antwort wieder ueber ein fremdes Geraet gibt es keine zweite Schonfrist`() {
        callRouteTo(headset)
        val hold = CompletableDeferred<Unit>()
        val synth = FakeSynth(hold)
        var job: Job? = null
        val out = callSpeaker(synth, onStopRequested = { job?.cancel() })
        job = say(out, "Ein langer Satz.")
        waitFor { synth.delivered.get() == 1 }
        looper.idleFor(Duration.ofMillis(200))
        ShadowAudioTrack.setRoutedDevice(earpiece)
        looper.idleFor(Duration.ofMillis(600))
        assertEquals(1, stopRequests.get())
        hold.complete(Unit)
        waitFor { job!!.isCompleted }
        looper.idleFor(Duration.ofSeconds(5)) // Android behauptet weiter, der Ton ginge ins Headset: die Frist endet ohne Meldung
        assertTrue(lost.isEmpty())

        // Die nächste Antwort darf deshalb beginnen, der Abspieler läuft aber wieder über die Hörmuschel:
        // Android leitet offenbar anders als angekündigt. Jede weitere Antwort wäre kurz falsch zu hören.
        val again = CompletableDeferred<Unit>()
        synth.hold = again
        job = say(out, "Noch ein Satz.")
        waitFor { synth.delivered.get() == 2 }
        looper.idleFor(Duration.ofMillis(200))
        assertEquals(listOf("Die Verbindung zum Kopfhörer-Mikro ist abgerissen. Das Zuhören wurde beendet."), lost)
        again.complete(Unit)
    }

    @Test fun `fehlt der Telefonkanal schon vor der Antwort bleibt sie stumm und die Schonfrist entscheidet`() {
        callRouteTo(earpiece)
        val out = callSpeaker(FakeSynth())
        val job = say(out, "Ihr Arzttermin ist am Dienstag.")
        waitFor { job.isCompleted }
        assertTrue(job.isCancelled)
        assertEquals("kein einziges Byte erreichte den Abspieler", 0, written.get())
        assertEquals(1, stopRequests.get())
        looper.idleFor(Duration.ofSeconds(1))
        assertTrue("über das Zuhören ist noch nicht entschieden: $lost", lost.isEmpty())

        // Eine weitere Antwort in der Schonfrist bleibt ebenso stumm und verlängert die Frist nicht.
        val second = say(out, "Um zehn Uhr.")
        waitFor { second.isCompleted }
        assertEquals(0, written.get())
        looper.idleFor(Duration.ofSeconds(3))
        assertEquals(listOf("Die Verbindung zum Kopfhörer-Mikro ist abgerissen. Das Zuhören wurde beendet."), lost)
    }

    @Test fun `meldet der Dienst einen Geraetewechsel entscheidet beim Handy-Mikro sofort der Weg`() {
        routeTo(headphones)
        val out = speaker(FakeSynth())
        out.checkRoute()
        assertTrue(lost.isEmpty())

        routeTo(phoneSpeaker)
        out.checkRoute()
        assertEquals(listOf("Kopfhörer getrennt. Das Zuhören wurde beendet."), lost)
    }

    @Test fun `meldet der Dienst einen Geraetewechsel bekommt der Telefonkanal seine Schonfrist`() {
        callRouteTo(headset)
        val out = callSpeaker(FakeSynth())
        callRouteTo(earpiece)
        out.checkRoute()
        assertEquals("eine laufende Antwort verstummt", 1, stopRequests.get())
        assertTrue(lost.isEmpty())

        looper.idleFor(Duration.ofSeconds(1))
        callRouteTo(headset)
        looper.idleFor(Duration.ofSeconds(5))
        assertTrue("der Kanal kam zurück: $lost", lost.isEmpty())

        // Übernimmt dagegen ein fremdes Gerät den Kanal und bleibt, ist nach der Frist Schluss.
        callRouteTo(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLE_SPEAKER).build())
        out.checkRoute()
        looper.idleFor(Duration.ofSeconds(2))
        assertTrue(lost.isEmpty())
        looper.idleFor(Duration.ofSeconds(2))
        assertEquals(1, lost.size)
        assertTrue(lost[0], lost[0].startsWith("Die Tonausgabe ist auf „") && lost[0].endsWith("damit keine Antwort laut zu hören ist."))
    }
}

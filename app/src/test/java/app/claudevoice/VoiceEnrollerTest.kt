package app.claudevoice

import app.claudevoice.core.MicMode
import app.claudevoice.core.VoiceEnrollment
import app.claudevoice.core.VoiceProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ein Mikrofon für Tests: Es öffnet sich sofort und liefert nur, was der Test hineinschiebt.
 * Die Töne sind künstlich (ein Grundton mit Obertönen), es wird nichts aufgenommen.
 */
class FakeEnrolMic(
    /** Was das "Mikrofon" liefert, wenn das Kopfhörer-Mikro gewünscht ist (HANDY = der Telefonkanal kam nicht). */
    var headsetGives: MicMode = MicMode.AIRPODS,
    /** Meldung, mit der das Öffnen scheitert; null = es klappt. */
    var unavailable: String? = null,
) : EnrolMic {
    var opened = 0
    var closed = 0
    val isOpen get() = sink != null
    private var sink: ((ByteArray, Int) -> Unit)? = null
    private var lost: ((String) -> Unit)? = null

    override suspend fun open(headset: Boolean, onChunk: (ByteArray, Int) -> Unit, onLost: (String) -> Unit): MicMode {
        unavailable?.let { throw MicSource.Unavailable(it) }
        opened++
        sink = onChunk
        lost = onLost
        return if (headset) headsetGives else MicMode.HANDY
    }

    override fun close() {
        if (sink != null) closed++
        sink = null
        lost = null
    }

    /** Schiebt Ton in 100-ms-Stücken hinein, so wie das echte Mikrofon ihn liefert. */
    fun push(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            bytes[2 * i] = (samples[i].toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        var i = 0
        while (i < bytes.size) {
            val n = minOf(3200, bytes.size - i)
            sink?.invoke(bytes.copyOfRange(i, i + n), n)
            i += n
        }
    }

    fun lose(why: String) = lost?.invoke(why)

    companion object {
        private const val RATE = 16000

        fun silence(ms: Int) = ShortArray(RATE * ms / 1000)

        /** Stimmähnlicher Ton mit Grundton [hz] auf dem Pegel [db] (dBFS). */
        fun voice(ms: Int, db: Double, hz: Double): ShortArray = scaled(DoubleArray(RATE * ms / 1000) { i ->
            val t = 2 * PI * hz * i / RATE
            sin(t) + 0.5 * sin(2 * t) + 0.25 * sin(3 * t)
        }, db)

        fun noise(ms: Int, db: Double): ShortArray {
            val random = Random(3)
            return scaled(DoubleArray(RATE * ms / 1000) { random.nextGaussian() }, db)
        }

        /** [count] Sätze von je 900 ms mit kurzen Atempausen. */
        fun reading(count: Int, db: Double, hz: Double): ShortArray =
            (0 until count).fold(silence(300)) { all, _ -> all + voice(900, db, hz) + silence(300) }

        private fun scaled(raw: DoubleArray, db: Double): ShortArray {
            val rms = sqrt(raw.sumOf { it * it } / raw.size)
            val factor = 32768.0 * 10.0.pow(db / 20) / rms
            return ShortArray(raw.size) { (raw[it] * factor).coerceIn(-32768.0, 32767.0).toInt().toShort() }
        }
    }
}

/**
 * Der Ablauf des Einlernens mit einem nachgestellten Mikrofon: Wird nur dann gespeichert, wenn die
 * Messung taugt, und wird das Mikrofon in jedem Fall wieder freigegeben?
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceEnrollerTest {
    private val scope = TestScope()
    private val mic = FakeEnrolMic()
    private val saved = ArrayList<Pair<MicMode, VoiceProfile>>()
    private var listening = false
    private val enroller = VoiceEnroller(mic, scope, { listening }, { m, p -> saved += m to p })

    private fun tick(ms: Long = 100) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test fun `vorlesen am Kopfhoerer-Mikro speichert das Profil fuer dieses Mikrofon`() {
        assertEquals(EnrolPhase.Idle, enroller.phase.value)
        enroller.start(headset = true)
        assertEquals(EnrolPhase.Starting, enroller.phase.value)
        scope.runCurrent()
        assertEquals(EnrolPhase.Recording(0f, null), enroller.phase.value)

        // Die halbe Strecke: Der Balken steht etwa in der Mitte, der Pegel wird gezeigt, gespeichert ist nichts.
        mic.push(FakeEnrolMic.reading(3, -34.0, 120.0) + FakeEnrolMic.voice(600, -34.0, 120.0))
        tick()
        val half = enroller.phase.value as EnrolPhase.Recording
        assertTrue("Fortschritt ${half.fraction}", half.fraction in 0.5f..0.75f)
        assertEquals(-34.0, half.levelDb!!, 1.5)
        assertTrue(saved.isEmpty())

        mic.push(FakeEnrolMic.reading(4, -34.0, 120.0))
        tick()
        val done = enroller.phase.value as EnrolPhase.Done
        assertEquals(MicMode.AIRPODS, done.mic)
        assertEquals(-34.0, done.profile.levelDb, 1.0)
        assertEquals(120.0, done.profile.pitchHz!!, 3.0)
        assertEquals(VoiceEnrollment.ENROLLED_SAMPLES, done.profile.samples)
        assertNull(done.warning)
        assertEquals(listOf(MicMode.AIRPODS to done.profile), saved)
        assertEquals("das Mikrofon ist wieder frei", false, mic.isOpen)
    }

    @Test fun `am Handy-Mikro wird fuer das Handy-Mikro gespeichert`() {
        enroller.start(headset = false)
        scope.runCurrent()
        mic.push(FakeEnrolMic.reading(8, -28.0, 200.0))
        tick()
        assertEquals(MicMode.HANDY, (enroller.phase.value as EnrolPhase.Done).mic)
        assertEquals(listOf(MicMode.HANDY), saved.map { it.first })
    }

    @Test fun `eine sehr leise Stimme wird gespeichert aber mit Warnung`() {
        enroller.start(headset = true)
        scope.runCurrent()
        mic.push(FakeEnrolMic.reading(8, -45.0, 120.0))
        tick()
        assertNotNull((enroller.phase.value as EnrolPhase.Done).warning)
        assertEquals(1, saved.size)
    }

    @Test fun `kommt das Kopfhoerer-Mikro nicht zustande wird nichts unter seinem Namen gespeichert`() {
        mic.headsetGives = MicMode.HANDY
        enroller.start(headset = true)
        scope.runCurrent()
        assertEquals(EnrolPhase.Failed(VoiceEnroller.NO_LINK), enroller.phase.value)
        mic.push(FakeEnrolMic.reading(8, -25.0, 120.0))
        tick()
        assertTrue(saved.isEmpty())
        assertEquals(false, mic.isOpen)
    }

    @Test fun `solange zugehoert wird beginnt das Einlernen nicht`() {
        listening = true
        enroller.start(headset = true)
        scope.runCurrent()
        assertEquals(EnrolPhase.Failed(VoiceEnroller.BUSY), enroller.phase.value)
        assertEquals("das Mikrofon wurde gar nicht erst geöffnet", 0, mic.opened)
    }

    @Test fun `beginnt das Zuhoeren waehrend des Einlernens wird nichts gespeichert`() {
        enroller.start(headset = true)
        scope.runCurrent()
        listening = true
        mic.push(FakeEnrolMic.reading(8, -25.0, 120.0))
        tick()
        assertEquals(EnrolPhase.Failed(VoiceEnroller.BUSY), enroller.phase.value)
        assertTrue(saved.isEmpty())
    }

    @Test fun `abbrechen verwirft die Messung und gibt das Mikrofon frei`() {
        enroller.start(headset = true)
        scope.runCurrent()
        mic.push(FakeEnrolMic.reading(3, -25.0, 120.0))
        tick()
        enroller.cancel()
        assertEquals(EnrolPhase.Idle, enroller.phase.value)
        assertEquals(false, mic.isOpen)
        tick(60_000)
        assertEquals(EnrolPhase.Idle, enroller.phase.value)
        assertTrue(saved.isEmpty())
        // Danach lässt es sich neu starten.
        enroller.start(headset = true)
        scope.runCurrent()
        assertEquals(2, mic.opened)
    }

    @Test fun `ein zweiter Start waehrend der Aufnahme aendert nichts`() {
        enroller.start(headset = true)
        scope.runCurrent()
        enroller.start(headset = false)
        scope.runCurrent()
        assertEquals(1, mic.opened)
    }

    @Test fun `ein stummes Mikrofon wird nach fuenf Sekunden gemeldet`() {
        enroller.start(headset = true)
        scope.runCurrent()
        tick(4_000)
        assertTrue(enroller.phase.value is EnrolPhase.Recording)
        tick(1_500)
        val failed = enroller.phase.value as EnrolPhase.Failed
        assertTrue(failed.reason, "keinen Ton" in failed.reason)
        assertEquals(false, mic.isOpen)
    }

    @Test fun `Rauschen ohne Stimme speichert nichts`() {
        enroller.start(headset = true)
        scope.runCurrent()
        mic.push(FakeEnrolMic.silence(300) + FakeEnrolMic.noise(30_000, -25.0))
        tick()
        val failed = enroller.phase.value as EnrolPhase.Failed
        assertTrue(failed.reason, "Geräusch" in failed.reason)
        assertTrue(saved.isEmpty())
    }

    @Test fun `zwei Stimmen speichern nichts`() {
        enroller.start(headset = true)
        scope.runCurrent()
        repeat(5) {
            mic.push(FakeEnrolMic.voice(900, -25.0, 115.0) + FakeEnrolMic.silence(300))
            mic.push(FakeEnrolMic.voice(900, -27.0, 220.0) + FakeEnrolMic.silence(300))
        }
        tick()
        val failed = enroller.phase.value as EnrolPhase.Failed
        assertTrue(failed.reason, "Stimmlage" in failed.reason)
        assertTrue(saved.isEmpty())
    }

    @Test fun `laesst sich das Mikrofon nicht oeffnen steht der Grund da`() {
        mic.unavailable = AndroidEnrolMic.NO_HEADSET
        enroller.start(headset = true)
        scope.runCurrent()
        assertEquals(EnrolPhase.Failed(AndroidEnrolMic.NO_HEADSET), enroller.phase.value)
    }

    @Test fun `geht das Mikrofon mittendrin verloren endet das Einlernen mit der Meldung`() {
        enroller.start(headset = true)
        scope.runCurrent()
        mic.push(FakeEnrolMic.reading(2, -25.0, 120.0))
        mic.lose("Die Verbindung zu den Kopfhörern ist abgerissen.")
        tick()
        assertEquals(EnrolPhase.Failed("Die Verbindung zu den Kopfhörern ist abgerissen."), enroller.phase.value)
        assertEquals(false, mic.isOpen)
        assertTrue(saved.isEmpty())
    }

    @Test fun `eine fehlende Berechtigung wird gemeldet solange nichts laeuft`() {
        enroller.fail(VoiceEnroller.NO_PERMISSION)
        assertEquals(EnrolPhase.Failed(VoiceEnroller.NO_PERMISSION), enroller.phase.value)
    }
}

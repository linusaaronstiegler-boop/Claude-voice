package app.claudevoice

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.robolectric.shadows.ShadowAudioRecord
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Das Mikrofon und der Telefonkanal zum Headset: Gilt das Kopfhörer-Mikro nur dann als aktiv, wenn der
 * Kanal wirklich steht, und fällt ein Verlust mitten im Betrieb auf?
 *
 * Läuft mit Robolectric: echter Android-15-Framework-Code, aber ein nachgestellter Audio-Dienst, der
 * Geräte sofort und ohne Funk "verbindet". Wie lange ein echtes Headset braucht und was es bei einem
 * Tastendruck tut, zeigt nur der Gerätetest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MicSourceTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val looper get() = shadowOf(Looper.getMainLooper())

    private val lost = ArrayList<String>()
    private val chunks = AtomicInteger()
    private val mic = MicSource(context) { why -> lost += why }

    /** Was das nachgestellte Mikrofon beim nächsten Lesen zurückgibt (Anzahl Bytes oder ein Fehlercode). */
    @Volatile private var reading: (Int) -> Int = { size -> size }

    private val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
    private val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()

    @Before fun setUp() {
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                Thread.sleep(2) // ein echtes Mikrofon liefert auch nicht beliebig schnell
                return reading(sizeInBytes)
            }
        })
    }

    @After fun tearDown() {
        mic.stop()
        scope.cancel()
        ShadowAudioRecord.clearSource()
    }

    private fun start(headset: AudioDeviceInfo?): Job {
        val job = scope.launch { mic.start(headset) { _, _ -> chunks.incrementAndGet() } }
        looper.idle()
        return job
    }

    /** Wartet (in echter Zeit), bis der Mikrofon-Thread [n] weitere Stücke geliefert hat. */
    private fun awaitChunks(n: Int = 3) {
        val target = chunks.get() + n
        val deadline = System.currentTimeMillis() + 5_000
        while (chunks.get() < target && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("das Mikrofon liefert nichts", chunks.get() >= target)
    }

    /** true, wenn in 150 ms echter Zeit kein einziges Stück weitergereicht wird. */
    private fun silent(): Boolean {
        Thread.sleep(30) // ein Stück, das gerade unterwegs war, noch durchlassen
        val before = chunks.get()
        Thread.sleep(150)
        return chunks.get() == before
    }

    // --- Handy-Mikro -------------------------------------------------------------------------

    @Test fun `ohne Headset nimmt das Handy-Mikro auf und der Telefonie-Modus bleibt aus`() {
        assertTrue(start(null).isCompleted)
        assertFalse(mic.usingBluetooth)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        awaitChunks()
        mic.stop()
        assertTrue("nach dem Stopp kommt nichts mehr", silent())
    }

    // --- Aufbau des Telefonkanals ------------------------------------------------------------

    @Test fun `steht der Telefonkanal gilt das Kopfhoerer-Mikro und der Stopp raeumt alles ab`() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(earpiece, headset))
        assertTrue(start(headset).isCompleted)
        assertTrue(mic.usingBluetooth)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals(headset, audio.communicationDevice)
        awaitChunks()

        mic.stop()
        assertFalse(mic.usingBluetooth)
        assertEquals("andere Apps bekommen ihr Mikrofon zurück", AudioManager.MODE_NORMAL, audio.mode)
        assertNull(audio.communicationDevice)
    }

    @Test fun `lehnt Android die Bitte ab bleibt es sofort beim Handy-Mikro`() {
        shadowOf(audio).lockCommunicationDevice(true)
        assertTrue(start(headset).isCompleted)
        assertFalse(mic.usingBluetooth)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        awaitChunks()
    }

    @Test fun `angenommen heisst nicht verbunden - ohne stehenden Kanal nach vier Sekunden Handy-Mikro`() {
        // Die Bitte wird angenommen, gewählt ist danach aber nicht das Headset (so wie auf dem Handy, wenn die
        // Funkverbindung nicht zustande kommt). Früher galt das Kopfhörer-Mikro trotzdem als aktiv.
        val job = start(earpiece)
        assertFalse("es wird gewartet, nicht geraten", job.isCompleted)
        assertEquals("solange gewartet wird, nimmt noch niemand auf", 0, chunks.get())

        looper.idleFor(Duration.ofSeconds(5))
        assertTrue(job.isCompleted)
        assertFalse(mic.usingBluetooth)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull("die Bitte wurde zurückgenommen", audio.communicationDevice)
        awaitChunks()
    }

    @Test fun `kommt der Kanal mit Verzoegerung wird ueber das Headset aufgenommen`() {
        val job = start(earpiece)
        looper.idleFor(Duration.ofSeconds(1))
        assertFalse(job.isCompleted)

        shadowOf(audio).callOnCommunicationDeviceChangedListeners(headset) // jetzt steht die Funkverbindung
        looper.idle()
        assertTrue(job.isCompleted)
        assertTrue(mic.usingBluetooth)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        awaitChunks()
    }

    @Test fun `wird der Start waehrend des Wartens abgebrochen bleibt kein Telefonie-Modus zurueck`() {
        val job = start(earpiece)
        job.cancel()
        looper.idle()
        assertFalse(mic.usingBluetooth)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertEquals("es wurde nie aufgenommen", 0, chunks.get())
    }

    // --- Verlust im Betrieb ------------------------------------------------------------------

    @Test fun `reisst der Kanal ab wird sofort nichts mehr weitergereicht und nach der Schonfrist gemeldet`() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(earpiece, headset))
        start(headset)
        awaitChunks()

        shadowOf(audio).callOnCommunicationDeviceChangedListeners(earpiece) // Android ist auf das Handy zurückgefallen
        looper.idle()
        // Jetzt käme der Ton vom Handy-Mikro, während die Pipeline noch "Kopfhörer-Mikro" glaubt.
        assertTrue("es wurde weiter Audio durchgereicht", silent())
        assertTrue("innerhalb der Schonfrist wird noch nichts gemeldet", lost.isEmpty())

        looper.idleFor(Duration.ofSeconds(4))
        assertEquals(listOf("Die Verbindung zum Kopfhörer-Mikro ist abgerissen. Das Zuhören wurde beendet."), lost)
    }

    @Test fun `kommt der Kanal in der Schonfrist zurueck geht es ohne Meldung weiter`() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(earpiece, headset))
        start(headset)
        awaitChunks()

        shadowOf(audio).callOnCommunicationDeviceChangedListeners(earpiece)
        looper.idleFor(Duration.ofSeconds(1))
        shadowOf(audio).callOnCommunicationDeviceChangedListeners(headset)
        looper.idleFor(Duration.ofSeconds(10))
        assertTrue(lost.toString(), lost.isEmpty())
        awaitChunks()
    }

    @Test fun `ein Lesefehler des Mikrofons wird gemeldet statt still zu enden`() {
        start(null)
        awaitChunks()
        reading = { AudioRecord.ERROR_DEAD_OBJECT }
        val deadline = System.currentTimeMillis() + 5_000
        while (lost.isEmpty() && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(10)
        }
        assertEquals(listOf("Das Mikrofon liefert keinen Ton mehr (Fehler ${AudioRecord.ERROR_DEAD_OBJECT}). Das Zuhören wurde beendet."), lost)
    }

    @Test fun `leere Lesevorgaenge werden uebergangen und ein Fehler beim Verarbeiten wird gemeldet`() {
        reading = { 0 }
        val job = scope.launch {
            mic.start(null) { _, _ ->
                chunks.incrementAndGet()
                error("Verarbeitung kaputt")
            }
        }
        looper.idle()
        assertTrue(job.isCompleted)
        assertTrue("aus nichts wird kein Stück", silent())
        looper.idle()
        assertTrue(lost.isEmpty())

        reading = { size -> size }
        val deadline = System.currentTimeMillis() + 5_000
        while (lost.isEmpty() && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(10)
        }
        assertEquals(listOf("Die Aufnahme ist abgebrochen (IllegalStateException). Das Zuhören wurde beendet."), lost)
        assertEquals("nach dem Fehler wird nicht weiter verarbeitet", 1, chunks.get())
    }
}

package app.claudevoice

import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import app.claudevoice.core.DeviceVoiceEngine
import app.claudevoice.core.DeviceVoiceReport
import app.claudevoice.core.DeviceVoiceUnavailable
import app.claudevoice.core.PcmEncoding
import app.claudevoice.core.SpeechOutput
import app.claudevoice.core.UiState
import app.claudevoice.core.VoiceEvent
import com.google.common.collect.ImmutableList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Die Start-Bedingungen rund um Schlüssel und Sprachausgabe.
 *
 * Geprüft werden alle Kombinationen: ElevenLabs-Schlüssel da oder nicht, Soniox-Schlüssel da oder nicht,
 * und das für beide Sprachausgaben (ElevenLabs, Stimme des Geräts). Wo gestartet werden darf, läuft der
 * Dienst hier wirklich an (Mikrofon nachgestellt, jede Netzverbindung endet an einem toten lokalen Port,
 * wie in [ListenServiceStopTest]). Wo ein Schlüssel fehlt, muss die Meldung sagen, welcher und wofür.
 *
 * Die Sprach-Engine des Handys gibt es auf dem Rechner nicht. An ihrer Stelle steht [FakeDeviceVoice];
 * die Logik dahinter (Probe beim Start, Reihenfolge, Fehler) ist die echte aus :core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenServiceKeysTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val media = AndroidSpeaker.attributes(voiceCallRoute = false)
    private val looper get() = shadowOf(Looper.getMainLooper())
    private val services = ArrayList<ServiceController<ListenService>>()
    private val wired = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADSET).build()
    private var oldSelector: ProxySelector? = null
    private val realOpen = AndroidDeviceVoice.open
    private lateinit var prefs: Prefs

    /** Die nachgestellte Engine dieses Tests, und wie oft der Dienst nach ihr gefragt hat. */
    private val voice = FakeDeviceVoice()
    @Volatile private var opened = 0

    @Before fun setUp() {
        oldSelector = ProxySelector.getDefault()
        // Nichts verlässt den Rechner: Jede Verbindung läuft auf einen toten lokalen Port.
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        })
        prefs = FakeAndroidKeyStore.freshPrefs(context)
        prefs.anthropicKey = "sk-ant-test-0000"
        prefs.useBluetoothMic = false
        AppState.ui.value = UiState()
        ListenService.confirmation.forget()
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                Thread.sleep(5) // ein echtes Mikrofon liefert auch nicht beliebig schnell
                return sizeInBytes
            }
        })
        shadowOf(audio).addOutputDevice(wired, false)
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.of(wired))
        AndroidDeviceVoice.open = { opened++; voice }
    }

    @After fun tearDown() {
        services.forEach { runCatching { it.destroy() } }
        waitFor { services.none { it.get().busy } }
        ShadowAudioRecord.clearSource()
        ProxySelector.setDefault(oldSelector)
        AndroidDeviceVoice.open = realOpen
        FakeAndroidKeyStore.install()
    }

    /** Trägt die Schlüssel ein, die es in diesem Test geben soll (der Anthropic-Schlüssel steht schon). */
    private fun keys(eleven: Boolean, soniox: Boolean, speech: SpeechOutput?) {
        prefs.elevenKey = if (eleven) "el-test-0000" else ""
        prefs.sonioxKey = if (soniox) "sx-test-0000" else ""
        prefs.speechChoice = speech
    }

    private fun service(): ServiceController<ListenService> =
        Robolectric.buildService(ListenService::class.java).create().also { services += it }

    private fun micAlive(): Boolean = Thread.getAllStackTraces().keys.any { it.name == "mic" && it.isAlive }

    /** Lässt Hintergrund und Haupt-Thread laufen, bis [done] gilt. Die Frist ist nur ein Netz gegen einen hängenden Test. */
    private fun waitFor(timeoutMs: Long = 60_000, done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(20)
        }
        return done()
    }

    /** Startet den Dienst und verlangt, dass er wirklich läuft: Der Aufbau ist durch, das Mikrofon ist offen. */
    private fun startAndRun(): ServiceController<ListenService> {
        val c = service()
        c.startCommand(0, 1)
        waitFor { AppState.ui.value.status != "Startet …" || shadowOf(c.get()).isStoppedBySelf }
        assertFalse("der Start ist gescheitert: ${AppState.ui.value.error}", shadowOf(c.get()).isStoppedBySelf)
        assertTrue("der Aufbau wurde nicht fertig (Status: ${AppState.ui.value.status})", micAlive())
        return c
    }

    /** Startet den Dienst und verlangt, dass er sich mit einer Meldung selbst beendet, ohne etwas zu öffnen. */
    private fun startAndExpectStop(): String {
        val c = service()
        c.startCommand(0, 1)
        waitFor { shadowOf(c.get()).isStoppedBySelf }
        assertTrue("der Dienst hat sich nicht selbst beendet", shadowOf(c.get()).isStoppedBySelf)
        assertEquals("bis hierher darf weder verbunden noch zugehört worden sein", "Startet …", AppState.ui.value.status)
        assertFalse("das Mikrofon wurde geöffnet", micAlive())
        val error = AppState.ui.value.error
        assertNotNull("es fehlt die Meldung, warum nicht gestartet wurde", error)
        c.destroy()
        assertEquals("die Meldung bleibt nach dem Ende stehen", error, AppState.ui.value.error)
        return error!!
    }

    // --- Sprachausgabe ElevenLabs ------------------------------------------------------------

    @Test fun `ElevenLabs spricht und beide Schluessel sind da es startet`() {
        keys(eleven = true, soniox = true, speech = SpeechOutput.ELEVEN)
        startAndRun()
        assertEquals("die Stimme des Geräts wird nicht angefasst, wenn ElevenLabs gewählt ist", 0, opened)
    }

    @Test fun `ElevenLabs spricht und schreibt mit es startet`() {
        keys(eleven = true, soniox = false, speech = SpeechOutput.ELEVEN)
        startAndRun()
        assertEquals(0, opened)
    }

    @Test fun `ElevenLabs soll sprechen aber nur der Soniox-Schluessel ist da es startet nichts`() {
        keys(eleven = false, soniox = true, speech = SpeechOutput.ELEVEN)
        assertEquals(
            "Der ElevenLabs-Key fehlt. Er wird für die Sprachausgabe „ElevenLabs“ gebraucht. Ohne ElevenLabs-Konto " +
                "geht es mit der Sprachausgabe „Stimme des Geräts“. Bitte in den Einstellungen eintragen.",
            startAndExpectStop(),
        )
        assertEquals("es wird nicht still auf die Stimme des Geräts gewechselt", 0, opened)
    }

    @Test fun `ElevenLabs soll sprechen und beide Schluessel fehlen es startet nichts`() {
        keys(eleven = false, soniox = false, speech = SpeechOutput.ELEVEN)
        assertEquals(
            "Der ElevenLabs-Key fehlt. Er wird für die Transkription und für die Sprachausgabe „ElevenLabs“ " +
                "gebraucht. Bitte in den Einstellungen eintragen.",
            startAndExpectStop(),
        )
        assertEquals(0, opened)
    }

    // --- Sprachausgabe: Stimme des Geräts ----------------------------------------------------

    @Test fun `die Stimme des Geraets spricht und beide Schluessel sind da es startet`() {
        keys(eleven = true, soniox = true, speech = SpeechOutput.DEVICE)
        startAndRun()
        assertEquals("obwohl ein ElevenLabs-Schlüssel da ist, gilt die Wahl des Nutzers", 1, opened)
    }

    @Test fun `die Stimme des Geraets spricht und ElevenLabs schreibt mit es startet`() {
        keys(eleven = true, soniox = false, speech = SpeechOutput.DEVICE)
        startAndRun()
        assertEquals(1, opened)
    }

    @Test fun `die Stimme des Geraets spricht und Soniox schreibt mit es startet ganz ohne ElevenLabs`() {
        keys(eleven = false, soniox = true, speech = SpeechOutput.DEVICE)
        val c = startAndRun()
        assertEquals(1, opened)
        // Beim Start hat die Engine genau einen Probesatz erzeugt; zu hören war er nirgends.
        assertEquals(listOf("Ja."), voice.texts)
        assertFalse(voice.closed)

        // Mit dem Ende des Zuhörens wird die Engine wieder freigegeben.
        c.get().onStartCommand(Intent(c.get(), ListenService::class.java).setAction(ListenService.ACTION_STOP), 0, 2)
        assertTrue("die Sprach-Engine blieb nach dem Stopp belegt", voice.closed)
    }

    @Test fun `die Stimme des Geraets soll sprechen aber kein Schluessel fuer die Transkription ist da es startet nichts`() {
        keys(eleven = false, soniox = false, speech = SpeechOutput.DEVICE)
        assertEquals(
            "Für die Transkription fehlt ein Schlüssel: entweder der ElevenLabs-Key oder der Soniox-Key. " +
                "Bitte in den Einstellungen eintragen.",
            startAndExpectStop(),
        )
        assertEquals("ohne Schlüssel wird auch die Engine nicht geöffnet", 0, opened)
    }

    // --- Ohne ausdrückliche Wahl -------------------------------------------------------------

    @Test fun `ohne Wahl spricht ElevenLabs solange sein Schluessel da ist`() {
        keys(eleven = true, soniox = true, speech = null)
        startAndRun()
        assertEquals(0, opened)
    }

    @Test fun `ohne Wahl und ohne ElevenLabs-Schluessel spricht die Stimme des Geraets`() {
        keys(eleven = false, soniox = true, speech = null)
        startAndRun()
        assertEquals(1, opened)
    }

    // --- Anthropic ---------------------------------------------------------------------------

    @Test fun `ohne Anthropic-Schluessel startet nichts gleich welche Sprachausgabe`() {
        prefs.anthropicKey = ""
        for (speech in SpeechOutput.entries) {
            keys(eleven = true, soniox = true, speech = speech)
            assertEquals(
                "Der Anthropic-Key fehlt. Ohne ihn gibt es keine Antworten. Bitte in den Einstellungen eintragen.",
                startAndExpectStop(),
            )
        }
        assertEquals(0, opened)
    }

    // --- Die Stimme des Geräts taugt nicht ---------------------------------------------------

    @Test fun `fehlt dem Geraet eine deutsche Stimme steht das beim Start da und nicht erst bei der ersten Antwort`() {
        keys(eleven = false, soniox = true, speech = SpeechOutput.DEVICE)
        AndroidDeviceVoice.open = { throw DeviceVoiceUnavailable(DeviceVoiceReport.NO_GERMAN) }
        assertEquals(DeviceVoiceReport.NO_GERMAN, startAndExpectStop())
    }

    @Test fun `hat das Geraet gar keine Sprachausgabe steht das beim Start da`() {
        keys(eleven = false, soniox = true, speech = SpeechOutput.DEVICE)
        AndroidDeviceVoice.open = { throw DeviceVoiceUnavailable(DeviceVoiceReport.NO_ENGINE) }
        assertEquals(DeviceVoiceReport.NO_ENGINE, startAndExpectStop())
    }

    @Test fun `liefert die Engine bei der Probe keinen Ton startet nichts und die Engine wird freigegeben`() {
        keys(eleven = false, soniox = true, speech = SpeechOutput.DEVICE)
        voice.mute = true
        assertEquals(DeviceVoiceReport.noAudio(null), startAndExpectStop())
        assertTrue("die Sprach-Engine blieb belegt", voice.closed)
    }
}

/**
 * Eine Sprach-Engine nur für Tests: Jeder eingereihte Satz wird sofort "gesprochen" (ein paar Bytes Stille
 * mit 22,05 kHz) und fertig gemeldet. Mit [mute] kündigt sie nur an und liefert keinen Ton.
 */
class FakeDeviceVoice : DeviceVoiceEngine {
    @Volatile override var onEvent: (id: String, event: VoiceEvent) -> Unit = { _, _ -> }
    val texts = CopyOnWriteArrayList<String>()
    @Volatile var mute = false
    @Volatile var closed = false
    @Volatile var stops = 0

    override fun enqueue(id: String, text: String): Boolean {
        texts += text
        onEvent(id, VoiceEvent.Begin(22_050, PcmEncoding.PCM_16, 1))
        if (!mute) onEvent(id, VoiceEvent.Audio(ByteArray(441)))
        onEvent(id, VoiceEvent.Done)
        return true
    }

    override fun stop() { stops++ }
    override fun close() { closed = true }
}

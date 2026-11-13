package app.claudevoice

import android.app.Application
import android.media.AudioFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import app.claudevoice.core.DeviceVoiceEngine
import app.claudevoice.core.DeviceVoiceReport
import app.claudevoice.core.DeviceVoiceUnavailable
import app.claudevoice.core.PcmEncoding
import app.claudevoice.core.VoiceEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Der dünne Adapter zu Androids Sprachausgabe: Verbinden, Stimme wählen, Meldungen übersetzen.
 *
 * Robolectric stellt Androids TextToSpeech nur als Attrappe nach: Sie kennt Stimmen und Sprachen und merkt
 * sich, was eingestellt wurde, erzeugt aber keinen Ton. Was hier NICHT geprüft werden kann, ist darum genau
 * der Teil, den nur ein Handy zeigt: dass eine echte Engine in das Ziel `/dev/null` spricht und dabei den
 * Ton meldet. Was die App aus den Meldungen macht, prüft `DeviceVoiceTest` in :core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDeviceVoiceTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun voice(name: String, locale: Locale, quality: Int, network: Boolean = false, features: Set<String> = emptySet()) =
        Voice(name, locale, quality, Voice.LATENCY_NORMAL, network, features)

    /** Das zuletzt angelegte TextToSpeech, so wie Robolectric es nachstellt. */
    private fun shadow(): ShadowTextToSpeech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    /**
     * Verbindet wie der Dienst. Android meldet das Ergebnis der Verbindung von sich aus; hier übernimmt
     * das der Test mit [status], sobald der Adapter sein TextToSpeech angelegt hat.
     */
    private fun connect(status: Int = TextToSpeech.SUCCESS, prepare: (TextToSpeech) -> Unit = {}): Result<DeviceVoiceEngine> {
        val result = AtomicReference<Result<DeviceVoiceEngine>>()
        val before = ShadowTextToSpeech.getLastTextToSpeechInstance() // von einem früheren Verbinden in diesem Test
        val worker = thread { result.set(runCatching { runBlocking { AndroidDeviceVoice.open(context) } }) }
        val deadline = System.currentTimeMillis() + 30_000
        while (ShadowTextToSpeech.getLastTextToSpeechInstance() === before && System.currentTimeMillis() < deadline) Thread.sleep(5)
        prepare(ShadowTextToSpeech.getLastTextToSpeechInstance()) // z. B. die Stimme, die die Engine von sich aus nimmt
        shadow().onInitListener.onInit(status)
        worker.join(30_000)
        val got = result.get()
        assertNotNull("das Verbinden kam nicht zurück", got)
        return got!!
    }

    private fun problemOf(result: Result<DeviceVoiceEngine>): String? {
        val error = result.exceptionOrNull()
        assertTrue("erwartet war eine Meldung für den Nutzer, es kam $error", error is DeviceVoiceUnavailable)
        return error?.message
    }

    @Test fun `gewaehlt wird die beste deutsche Stimme die ohne Netz spricht`() {
        ShadowTextToSpeech.addVoice(voice("en-us-fein", Locale.US, Voice.QUALITY_VERY_HIGH))
        ShadowTextToSpeech.addVoice(voice("de-de-netz", Locale.GERMANY, Voice.QUALITY_VERY_HIGH, network = true))
        ShadowTextToSpeech.addVoice(
            voice("de-de-fehlt", Locale.GERMANY, Voice.QUALITY_VERY_HIGH, features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        )
        ShadowTextToSpeech.addVoice(voice("de-de-grob", Locale.GERMANY, Voice.QUALITY_LOW))
        ShadowTextToSpeech.addVoice(voice("de-de-gut", Locale.GERMANY, Voice.QUALITY_HIGH))

        assertTrue(connect().isSuccess)
        assertEquals("de-de-gut", shadow().currentVoice.name)
        assertFalse(shadow().isShutdown)
    }

    @Test fun `gibt es Deutsch nur ueber das Netz steht das als Meldung da und die Verbindung wird geloest`() {
        ShadowTextToSpeech.addVoice(voice("en-us-fein", Locale.US, Voice.QUALITY_VERY_HIGH))
        ShadowTextToSpeech.addVoice(voice("de-de-netz", Locale.GERMANY, Voice.QUALITY_VERY_HIGH, network = true))
        assertEquals(DeviceVoiceReport.NO_GERMAN, problemOf(connect()))
        assertTrue("die Verbindung zur Engine blieb offen", shadow().isShutdown)
    }

    @Test fun `meldet Android keine Sprachausgabe steht das als Meldung da`() {
        assertEquals(DeviceVoiceReport.NO_ENGINE, problemOf(connect(status = TextToSpeech.ERROR)))
        assertTrue(shadow().isShutdown)
    }

    @Test fun `nennt die Engine keine einzelnen Stimmen zaehlt ob Deutsch geladen ist`() {
        assertEquals("ohne Stimmen und ohne Deutsch", DeviceVoiceReport.NO_GERMAN, problemOf(connect()))

        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMANY)
        assertTrue(connect().isSuccess)
        assertEquals(Locale.GERMANY, shadow().currentLanguage)
    }

    @Test fun `nennt die Engine keine Stimmen und spricht Deutsch nur ueber das Netz wird sie abgelehnt`() {
        // Deutsch ist "verfuegbar", aber die Stimme, die die Engine dafuer nimmt, schickt den Text ins Netz.
        // Das Versprechen "bleibt auf dem Geraet" gilt auch auf diesem Nebenweg.
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMANY)
        val overNetwork = connect { it.voice = voice("de-de-netz", Locale.GERMANY, Voice.QUALITY_VERY_HIGH, network = true) }
        assertEquals(DeviceVoiceReport.NO_GERMAN, problemOf(overNetwork))
        assertTrue("die Verbindung zur Engine blieb offen", shadow().isShutdown)

        // Dieselbe Lage mit einer Stimme, deren Sprachdaten noch nicht geladen sind.
        val notLoaded = connect {
            it.voice = voice("de-de-fehlt", Locale.GERMANY, Voice.QUALITY_HIGH, features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        }
        assertEquals(DeviceVoiceReport.NO_GERMAN, problemOf(notLoaded))

        // Und zum Vergleich: Rechnet die Stimme auf dem Geraet, wird sie genommen.
        assertTrue(connect { it.voice = voice("de-de-lokal", Locale.GERMANY, Voice.QUALITY_HIGH) }.isSuccess)
    }

    @Test fun `was Android zu einem Satz meldet kommt als Ereignis an`() {
        ShadowTextToSpeech.addVoice(voice("de-de-gut", Locale.GERMANY, Voice.QUALITY_HIGH))
        val engine = connect().getOrThrow()
        val events = ArrayList<Pair<String, VoiceEvent>>()
        engine.onEvent = { id, event -> events += id to event }

        val android = shadow().utteranceProgressListener
        android.onBeginSynthesis("a", 22_050, AudioFormat.ENCODING_PCM_16BIT, 1)
        android.onStart("a") // sagt nichts, was die App nicht schon wüsste
        android.onAudioAvailable("a", byteArrayOf(1, 2, 3, 4))
        android.onDone("a")
        android.onBeginSynthesis("b", 16_000, AudioFormat.ENCODING_PCM_FLOAT, 2)
        android.onError("b", TextToSpeech.ERROR_SYNTHESIS)
        android.onBeginSynthesis("c", 24_000, AudioFormat.ENCODING_MP3, 1)
        android.onStop("c", true)

        assertEquals(listOf("a", "a", "a", "b", "b", "c", "c"), events.map { it.first })
        assertEquals(VoiceEvent.Begin(22_050, PcmEncoding.PCM_16, 1), events[0].second)
        assertEquals(listOf<Byte>(1, 2, 3, 4), (events[1].second as VoiceEvent.Audio).pcm.toList())
        assertEquals(VoiceEvent.Done, events[2].second)
        assertEquals(VoiceEvent.Begin(16_000, PcmEncoding.PCM_FLOAT, 2), events[3].second)
        assertEquals(VoiceEvent.Failed(TextToSpeech.ERROR_SYNTHESIS), events[4].second)
        assertEquals("ein Format, das die App nicht kennt", VoiceEvent.Begin(24_000, PcmEncoding.OTHER, 1), events[5].second)
        assertEquals(VoiceEvent.Stopped, events[6].second)
    }

    @Test fun `nimmt Android einen Satz nicht an gilt er als abgelehnt und Schliessen loest die Verbindung`() {
        ShadowTextToSpeech.addVoice(voice("de-de-gut", Locale.GERMANY, Voice.QUALITY_HIGH))
        val engine = connect().getOrThrow()
        // Auf dem Rechner steht hinter TextToSpeech keine Engine: Der Auftrag wird abgelehnt. Das darf weder
        // abstürzen noch als angenommen gelten (die Logik in :core macht daraus eine klare Meldung).
        assertFalse(engine.enqueue("a", "Ja."))
        engine.stop()
        assertFalse(shadow().isShutdown)
        engine.close()
        assertTrue(shadow().isShutdown)
    }
}

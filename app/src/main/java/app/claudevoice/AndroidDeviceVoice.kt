package app.claudevoice

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.claudevoice.core.DeviceVoiceChoice
import app.claudevoice.core.DeviceVoiceEngine
import app.claudevoice.core.DeviceVoiceReport
import app.claudevoice.core.DeviceVoiceUnavailable
import app.claudevoice.core.PcmEncoding
import app.claudevoice.core.VoiceEvent
import app.claudevoice.core.VoiceOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Die "Stimme des Geräts": Androids eingebaute Sprachausgabe (TextToSpeech), verpackt als
 * [DeviceVoiceEngine]. Diese Klasse ist absichtlich dünn. Alles, was man falsch machen kann (Reihenfolge,
 * Abbruch, Fehler, Tonformat), steckt in `DeviceVoiceSynth` in :core und ist dort ohne Handy getestet.
 *
 * Wie kommt man an den Ton, statt ihn von Android abspielen zu lassen? Die App will ihn selbst abspielen,
 * denn nur ihr eigener Abspieler ([AndroidSpeaker]) prüft, dass er wirklich in Kopfhörer geht. Android
 * kennt dafür nur einen Weg: "in eine Datei sprechen" (`synthesizeToFile`). Dabei meldet die Engine jedes
 * Stück Ton zusätzlich über `onAudioAvailable`, und genau dort nimmt die App es ab.
 *
 * Es wird trotzdem nichts gespeichert. Als "Datei" bekommt die Engine `/dev/null`: das Loch, das jedes
 * Linux mitbringt und in dem alles Geschriebene verschwindet. Das ist keine Datei auf dem Speicher des
 * Handys; es bleibt nichts zurück, auch nicht für einen Augenblick.
 *
 * Warum gerade `/dev/null` und keine Rohrleitung ("Pipe")? Nachgelesen im Quelltext von Android
 * (`FileSynthesisCallback` und `TextToSpeechService`, Stand Android 15): Am Ende eines Satzes springt
 * Android an den Anfang der "Datei" zurück und trägt dort die Länge nach. In einer Rohrleitung kann man
 * nicht zurückspringen. Der Schritt scheitert dann, und Android meldet der App danach GAR NICHTS mehr,
 * weder "fertig" noch "Fehler". `/dev/null` lässt das Zurückspringen zu, und die Engine meldet ordentlich
 * "fertig". Sollte sich ein Gerät anders verhalten, kommt :core auch damit zurecht: Ein Fehler nach dem
 * Ton oder ein ausbleibendes Ende verliert keinen Satz (siehe `DeviceVoiceSynth.play`). Ob es auf einem
 * bestimmten Handy wirklich so läuft, kann nur dieses Handy zeigen.
 *
 * Nur ohne Netz: Gewählt wird ausschließlich eine deutsche Stimme, die auf dem Gerät rechnet. Manche
 * Engines bieten auch Stimmen an, die den Text an einen Server schicken; die nimmt die App nie.
 */
class AndroidDeviceVoice private constructor(private val tts: TextToSpeech) : DeviceVoiceEngine {

    @Volatile override var onEvent: (id: String, event: VoiceEvent) -> Unit = { _, _ -> }

    init {
        // Android ruft diese Funktionen auf eigenen Threads, je Satz in der Reihenfolge des Geschehens.
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) =
                emit(utteranceId, VoiceEvent.Begin(sampleRateInHz, encodingOf(audioFormat), channelCount))

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                if (audio != null) emit(utteranceId, VoiceEvent.Audio(audio))
            }

            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = emit(utteranceId, VoiceEvent.Done)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = emit(utteranceId, VoiceEvent.Stopped)
            override fun onError(utteranceId: String?, errorCode: Int) = emit(utteranceId, VoiceEvent.Failed(errorCode))

            // Die alte Fassung ohne Fehlernummer. Android ruft nur noch die Fassung darüber; diese hier
            // muss es trotzdem geben, weil die Klasse sie verlangt.
            @Deprecated("Ersetzt durch onError mit Fehlernummer")
            override fun onError(utteranceId: String?) = emit(utteranceId, VoiceEvent.Failed(TextToSpeech.ERROR))
        })
    }

    private fun emit(id: String?, event: VoiceEvent) {
        if (id != null) onEvent(id, event)
    }

    override fun enqueue(id: String, text: String): Boolean = try {
        // Android übernimmt eine eigene Kopie des Zugriffs und schließt sie selbst, wenn der Satz fertig
        // ist. Unsere Kopie wird nach dem Aufruf nicht mehr gebraucht ("use" schließt sie).
        ParcelFileDescriptor.open(NOWHERE, ParcelFileDescriptor.MODE_WRITE_ONLY).use { sink ->
            tts.synthesizeToFile(text, Bundle(), sink, id) == TextToSpeech.SUCCESS
        }
    } catch (e: Exception) {
        false
    }

    /** Hält den laufenden Satz an und verwirft alle wartenden (so steht es bei `TextToSpeech.stop`). */
    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun close() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    companion object {
        /** Das Ziel, in dem alles Geschriebene verschwindet (siehe Klassenkommentar). */
        private val NOWHERE = File("/dev/null")

        /** So lange darf Android brauchen, um sich mit der Sprach-Engine zu verbinden. */
        private const val CONNECT_TIMEOUT_MS = 6_000L

        /**
         * Verbindet sich mit der Sprach-Engine des Handys und stellt eine deutsche Stimme ein.
         * Als austauschbare Funktion, damit die Tests des Dienstes eine nachgestellte Engine einsetzen
         * können (die echte gibt es auf dem Rechner nicht).
         * @throws DeviceVoiceUnavailable mit einer Meldung für den Nutzer, wenn es keine Engine oder keine
         *         deutsche Stimme gibt.
         */
        @Volatile internal var open: suspend (Context) -> DeviceVoiceEngine = ::connect

        private suspend fun connect(context: Context): DeviceVoiceEngine {
            val connected = CompletableDeferred<Int>()
            // Android meldet über diese Funktion, ob die Verbindung zur Engine steht (manchmal sofort,
            // meist einen Moment später auf dem Haupt-Thread).
            val tts = TextToSpeech(context.applicationContext) { status -> connected.complete(status) }
            try {
                val status = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                if (status != TextToSpeech.SUCCESS) throw DeviceVoiceUnavailable(DeviceVoiceReport.NO_ENGINE)
                // Die Fragen nach den Stimmen gehen an eine andere App (die Engine) und können dauern.
                val german = withContext(Dispatchers.IO) { chooseGerman(tts) }
                if (!german) throw DeviceVoiceUnavailable(DeviceVoiceReport.NO_GERMAN)
                return AndroidDeviceVoice(tts)
            } catch (e: Throwable) {
                // Auch bei einem Abbruch (der Dienst endet währenddessen): Die Verbindung wird wieder gelöst.
                runCatching { tts.shutdown() }
                throw e
            }
        }

        /** Stellt die beste deutsche Stimme ein, die ohne Netz spricht. false, wenn es keine gibt. */
        private fun chooseGerman(tts: TextToSpeech): Boolean {
            val voices = runCatching { tts.voices }.getOrNull().orEmpty().toList()
            if (voices.isNotEmpty()) {
                val standard = runCatching { tts.defaultVoice?.name }.getOrNull()
                // Wer sein Handy auf Deutsch (Österreich) gestellt hat, bekommt bevorzugt diese Stimme.
                val home = Locale.getDefault().let { if (it.language == "de" && it.country.isNotEmpty()) it.country else "DE" }
                val best = DeviceVoiceChoice.best(voices.map { it.toOption(standard) }, home) ?: return false
                return tts.setVoice(voices.first { it.name == best.name }) == TextToSpeech.SUCCESS
            }
            // Manche Engines nennen keine einzelnen Stimmen, nur Sprachen. Dann zählt, ob Deutsch geladen
            // ist und ob die Stimme, die die Engine dafür nimmt, ohne Netz auskommt.
            if (tts.isLanguageAvailable(Locale.GERMANY) < TextToSpeech.LANG_AVAILABLE) return false
            if (tts.setLanguage(Locale.GERMANY) < TextToSpeech.LANG_AVAILABLE) return false
            val chosen = runCatching { tts.voice }.getOrNull() ?: return true
            return !chosen.isNetworkConnectionRequired && chosen.isInstalled()
        }

        private fun Voice.isInstalled(): Boolean =
            features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true

        private fun Voice.toOption(standard: String?) = VoiceOption(
            name = name,
            language = locale.language,
            country = locale.country,
            quality = quality,
            latency = latency,
            needsNetwork = isNetworkConnectionRequired,
            installed = isInstalled(),
            isDefault = name == standard,
        )

        private fun encodingOf(audioFormat: Int): PcmEncoding = when (audioFormat) {
            AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16
            AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.PCM_8
            AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.PCM_FLOAT
            else -> PcmEncoding.OTHER
        }
    }
}

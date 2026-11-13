package app.claudevoice.core

import kotlinx.coroutines.channels.ReceiveChannel
import java.io.IOException

/**
 * Womit die Antworten gesprochen werden.
 *
 *  - [ELEVEN]: ElevenLabs erzeugt die Sprache im Netz. Klingt natürlich, braucht ein Konto und kostet Guthaben.
 *  - [DEVICE]: die "Stimme des Geräts", also die Sprachausgabe, die Android selbst mitbringt
 *    (Text-in-Sprache). Kostet nichts und bleibt auf dem Handy, klingt aber hörbar nach Computer.
 *
 * [key] ist der Wert, der in den Einstellungen gespeichert wird.
 */
enum class SpeechOutput(val key: String) {
    ELEVEN("eleven"),
    DEVICE("geraet");

    companion object {
        /** Der gespeicherte Wert zurück in die Auswahl; null, wenn (noch) nichts gewählt wurde. */
        fun fromKey(key: String?): SpeechOutput? = entries.firstOrNull { it.key == key }

        /**
         * Was gilt, wenn man [chosen] und den Schlüssel zusammennimmt:
         * Eine ausdrückliche Wahl gewinnt immer. Ohne Wahl gilt ElevenLabs, solange ein ElevenLabs-Schlüssel
         * eingetragen ist (so war es bisher), sonst die Stimme des Geräts (die geht ohne Konto).
         */
        fun resolve(chosen: SpeechOutput?, hasElevenKey: Boolean): SpeechOutput =
            chosen ?: if (hasElevenKey) ELEVEN else DEVICE
    }
}

/**
 * Welche Schlüssel zum Zuhören nötig sind, und was fehlt.
 *
 * Die Regel in einem Satz: Der Anthropic-Schlüssel ist immer nötig. Der ElevenLabs-Schlüssel ist nur
 * nötig, wenn ElevenLabs spricht ODER wenn kein Soniox-Schlüssel da ist (dann transkribiert ElevenLabs).
 *
 * Als Tabelle (E = ElevenLabs-Schlüssel, S = Soniox-Schlüssel):
 *
 *   Sprachausgabe      E   S   Ergebnis
 *   ElevenLabs         ja  ja  geht (Soniox schreibt mit, ElevenLabs spricht)
 *   ElevenLabs         ja  -   geht (ElevenLabs macht beides)
 *   ElevenLabs         -   ja  fehlt: ElevenLabs für die Sprachausgabe
 *   ElevenLabs         -   -   fehlt: ElevenLabs für Transkription und Sprachausgabe
 *   Stimme des Geräts  ja  ja  geht (Soniox schreibt mit)
 *   Stimme des Geräts  ja  -   geht (ElevenLabs schreibt mit)
 *   Stimme des Geräts  -   ja  geht (Soniox schreibt mit) – ganz ohne ElevenLabs-Konto
 *   Stimme des Geräts  -   -   fehlt: ein Schlüssel für die Transkription
 *
 * Dienst und Oberfläche fragen dieselbe Funktion, damit sie sich nie widersprechen.
 */
object StartKeys {
    const val NO_ANTHROPIC = "Der Anthropic-Key fehlt. Ohne ihn gibt es keine Antworten."
    const val NO_ELEVEN_FOR_SPEECH =
        "Der ElevenLabs-Key fehlt. Er wird für die Sprachausgabe „ElevenLabs“ gebraucht. " +
            "Ohne ElevenLabs-Konto geht es mit der Sprachausgabe „Stimme des Geräts“."
    const val NO_ELEVEN_FOR_BOTH =
        "Der ElevenLabs-Key fehlt. Er wird für die Transkription und für die Sprachausgabe „ElevenLabs“ gebraucht."
    const val NO_TRANSCRIPTION =
        "Für die Transkription fehlt ein Schlüssel: entweder der ElevenLabs-Key oder der Soniox-Key."
    private const val WHERE = "Bitte in den Einstellungen eintragen."

    /** Was fehlt, je fehlendem Schlüssel ein Satz, der sagt, wofür er gebraucht wird. Leer = alles da. */
    fun missing(anthropicKey: String, elevenKey: String, sonioxKey: String, speech: SpeechOutput): List<String> {
        val missing = ArrayList<String>()
        if (anthropicKey.isEmpty()) missing += NO_ANTHROPIC
        if (elevenKey.isEmpty()) {
            when {
                speech == SpeechOutput.ELEVEN && sonioxKey.isEmpty() -> missing += NO_ELEVEN_FOR_BOTH
                speech == SpeechOutput.ELEVEN -> missing += NO_ELEVEN_FOR_SPEECH
                sonioxKey.isEmpty() -> missing += NO_TRANSCRIPTION
            }
        }
        return missing
    }

    /** Die Meldung, wenn so nicht gestartet werden kann; null, wenn alle nötigen Schlüssel da sind. */
    fun problem(anthropicKey: String, elevenKey: String, sonioxKey: String, speech: SpeechOutput): String? =
        missing(anthropicKey, elevenKey, sonioxKey, speech).takeIf { it.isNotEmpty() }?.plus(WHERE)?.joinToString(" ")
}

/**
 * Hängt an eine Meldung der Sprachausgabe von ElevenLabs den Ausweg an, wenn sie am Konto scheitert
 * (Schlüssel abgelehnt, Guthaben aufgebraucht).
 *
 * Wichtig ist, was hier NICHT passiert: Es wird nicht still auf die Stimme des Geräts umgeschaltet. Wer
 * ElevenLabs gewählt hat, soll erfahren, dass es nicht geht, statt sich über eine fremde Stimme zu wundern.
 * Die Meldung nennt nur, wo man umstellen kann.
 */
class AccountHintSynth(private val inner: SpeechSynth) : SpeechSynth {
    override val sampleRate: Int get() = inner.sampleRate

    override suspend fun synthesize(sentences: ReceiveChannel<String>, onPcm: suspend (ByteArray, Int) -> Unit) {
        try {
            inner.synthesize(sentences, onPcm)
        } catch (e: IOException) {
            val text = e.message
            // Dieselbe Erkennung wie bei der Transkription: feste Wendungen der Meldungen (siehe ConnectionReport).
            if (text == null || !ConnectionReport.isHopeless(text)) throw e
            throw IOException("${text.trim().trimEnd('.')}. $HINT", e)
        }
    }

    override fun prepare() = inner.prepare()

    companion object {
        const val HINT = "Ohne ElevenLabs geht es mit der Sprachausgabe „Stimme des Geräts“ (Einstellungen)."
    }
}

package app.claudevoice.core

import kotlin.math.abs
import kotlin.math.ln

/** Zwischenstand des Einlernens, für Fortschrittsbalken und Pegelanzeige. */
data class EnrollProgress(
    /** So viel brauchbare Sprache (mit erkennbarer Stimmlage) ist schon gesammelt, in Millisekunden. */
    val voicedMs: Int,
    /** So viel wird gebraucht. */
    val neededMs: Int,
    /** So viel Ton kam insgesamt vom Mikrofon, Pausen und Geräusche eingeschlossen. */
    val heardMs: Int,
    /** Pegel des letzten Messfensters in dBFS; null, wenn darin gerade niemand sprach. */
    val levelDb: Double?,
    /** true = es kann ausgewertet werden: genug Sprache gesammelt oder die Höchstdauer ist erreicht. */
    val done: Boolean,
) {
    /** Fortschritt von 0 bis 1. */
    val fraction: Float get() = (voicedMs.toFloat() / neededMs).coerceIn(0f, 1f)
}

/** Ergebnis des Einlernens: ein Profil (mit Warnung, falls es wackelig ist) oder ein Grund in Klartext. */
sealed interface EnrollResult {
    data class Success(val profile: VoiceProfile, val warning: String?) : EnrollResult
    data class Failure(val reason: String) : EnrollResult
}

/**
 * Lernt die Stimme des Trägers aus einem vorgelesenen Text: Pegel und Stimmlage, sonst nichts.
 *
 * Warum es das gibt: Ohne Profil nimmt der [VoiceClassifier] an, die eigene Stimme komme mit etwa -22 dBFS
 * am Mikro an, und die Pipeline verlangt für einen Nachweis mindestens -30 dBFS. Liefert ein Kopfhörer
 * weniger, hält die App den Träger für einen Fremden. Und weil sie ein Profil nur von einer Stimme lernt,
 * die sie schon für seine hält, kommt sie aus dieser Lage nie von selbst heraus. Das Einlernen misst
 * deshalb einmal, wie laut und wie hoch die Stimme an GENAU DIESEM Mikrofon wirklich ankommt.
 *
 * Wie gemessen wird: mit demselben [VoiceMeter], den die Pipeline benutzt, damit die Zahlen vergleichbar
 * sind. Der Ton wird in Fenster von [WINDOW_MS] geteilt; für jedes Fenster liefert der Messer Pegel und
 * Stimmlage der Sprache darin. Fenster ohne Sprache (Stille, Atempausen) liefern nichts. Fenster mit
 * Geräusch, aber ohne erkennbare Stimmlage (Rauschen, Rascheln) werden nicht mitgezählt.
 *
 * Wann es gelingt:
 *  - Es muss genug Sprache da sein ([MIN_VOICED_MS]).
 *  - Sie muss einheitlich sein: Drei Viertel der Fenster müssen beim Pegel und bei der Stimmlage nah an
 *    ihrem Median liegen. Redet jemand dazwischen oder läuft Musik, streuen die Werte, und es wird lieber
 *    abgelehnt als ein falsches Profil gespeichert.
 *  - Als Ergebnis gilt der Median (der mittlere Wert der sortierten Reihe), nicht der Mittelwert: Ein paar
 *    Ausreißer, etwa ein Huster, verschieben ihn nicht.
 *
 * Vertrauen: Das Einlernen ist eine ausdrückliche Handlung in den Einstellungen. Wer dort tippt, hält das
 * entsperrte Handy in der Hand; derselbe Mensch könnte auch die Schlüssel ändern oder das Gedächtnis
 * löschen. Deshalb darf das Ergebnis direkt als Profil gespeichert werden. Diese Klasse hat bewusst keine
 * Verbindung zur Pipeline und zum Klassifikator: Sie bekommt nur Ton, den die Einstellungen ihr geben,
 * und gibt nur einen Wert zurück. Es gibt also keinen Weg, auf dem eine Stimme, die WÄHREND DES ZUHÖRENS
 * zu hören ist, durch das Einlernen zum Träger würde. Dort gilt unverändert die Schranke der Pipeline
 * (Merken, Stummschalten und Lernen nur für eine Stimme, die die Akustik dem Träger zuordnet).
 *
 * Gespeichert wird nichts vom Ton: Er bleibt im Arbeitsspeicher und wird nach der Messung verworfen.
 * Übrig bleiben zwei Zahlen.
 */
class VoiceEnrollment(private val sampleRate: Int = 16000) {
    private val meter = VoiceMeter(sampleRate)

    /** Ein Messfenster mit erkennbarer Stimme. */
    private class Window(val levelDb: Double, val pitchHz: Double)

    private val windows = ArrayList<Window>()
    private val windowBytes = sampleRate * 2 * WINDOW_MS / 1000 // 16 Bit = 2 Byte je Messwert
    private var inWindow = 0     // Bytes im laufenden Fenster
    private var heardBytes = 0L  // alles, was je ankam
    private var voicedMs = 0     // Sprache mit Stimmlage
    private var noiseMs = 0      // "Sprache" ohne Stimmlage, also Geräusch
    private var lastLevel: Double? = null

    /** Rohes PCM (16 Bit, little endian, mono) einspeisen, in beliebig großen Stücken. */
    @Synchronized
    fun feed(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            // Nie über die Fenstergrenze hinaus füttern: Jedes Fenster wird für sich ausgewertet.
            val take = minOf(length - offset, windowBytes - inWindow)
            // Der Messer kennt nur "von vorn bis length", deshalb bekommt er einen Ausschnitt als Kopie.
            if (offset == 0 && take == length) meter.feed(pcm, length)
            else meter.feed(pcm.copyOfRange(offset, offset + take), take)
            offset += take
            inWindow += take
            heardBytes += take
            if (inWindow == windowBytes) closeWindow()
        }
    }

    private fun closeWindow() {
        inWindow = 0
        // takeSummary leert den Messer: Das nächste Fenster beginnt bei null.
        val f = meter.takeSummary()
        lastLevel = f?.levelDb
        if (f == null) return // Stille oder Pause
        val pitch = f.pitchHz
        if (pitch == null) {
            noiseMs += f.speechMs
        } else {
            voicedMs += f.speechMs
            windows += Window(f.levelDb, pitch)
        }
    }

    @get:Synchronized
    val progress: EnrollProgress
        get() {
            val heardMs = (heardBytes * 1000 / (sampleRate * 2)).toInt()
            return EnrollProgress(voicedMs, MIN_VOICED_MS, heardMs, lastLevel, voicedMs >= MIN_VOICED_MS || heardMs >= MAX_HEARD_MS)
        }

    /** Wertet aus, was bis jetzt gesammelt ist. Ändert nichts; der Aufrufer verwirft das Objekt danach. */
    @Synchronized
    fun finish(): EnrollResult {
        if (voicedMs < MIN_VOICED_MS) {
            return EnrollResult.Failure(
                when {
                    heardBytes == 0L -> "Das Mikrofon hat keinen Ton geliefert."
                    noiseMs >= 2_000 && noiseMs > voicedMs ->
                        "Es war vor allem Geräusch zu hören, kaum eine Stimme. Bitte an einem ruhigeren Ort wiederholen."
                    voicedMs == 0 ->
                        "Es war keine Stimme zu hören. Bitte prüfen, ob das richtige Mikrofon gewählt ist, und den Text laut vorlesen."
                    else ->
                        "Zu wenig Sprache: ${seconds(voicedMs)} von ${seconds(MIN_VOICED_MS)} Sekunden. " +
                            "Bitte den Text in normaler Lautstärke und ohne lange Pausen vorlesen."
                }
            )
        }
        val level = median(windows.map { it.levelDb })
        val pitch = median(windows.map { it.pitchHz })
        val levelFits = windows.count { abs(it.levelDb - level) <= LEVEL_SPREAD_DB }.toDouble() / windows.size
        val pitchFits = windows.count { semitones(it.pitchHz, pitch) <= PITCH_SPREAD_SEMITONES }.toDouble() / windows.size
        if (pitchFits < MIN_CONSISTENT) {
            return EnrollResult.Failure(
                "Die Stimmlage war nicht einheitlich. Es klang nach mehr als einer Stimme oder nach Musik. " +
                    "Bitte wiederholen, wenn nur Sie zu hören sind."
            )
        }
        if (levelFits < MIN_CONSISTENT) {
            return EnrollResult.Failure(
                "Die Lautstärke schwankte zu stark. Bitte gleichmäßig sprechen und wiederholen, wenn sonst niemand redet."
            )
        }
        val warning = if (level >= QUIET_DB) null else
            "Ihre Stimme kommt an diesem Mikrofon sehr leise an. Die Erkennung wird damit unzuverlässig. " +
                "Bitte in normaler Lautstärke sprechen und prüfen, ob wirklich das gewählte Mikrofon aufnimmt " +
                "(Kopfhörer im Ohr, Mikrofon nicht verdeckt). Dann noch einmal einlernen."
        return EnrollResult.Success(VoiceProfile(level, pitch, ENROLLED_SAMPLES), warning)
    }

    private fun seconds(ms: Int) = "%.1f".format(java.util.Locale.GERMAN, ms / 1000.0)

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /** Abstand zweier Tonhöhen in Halbtönen (12 Halbtöne = eine Oktave = doppelte Frequenz). */
    private fun semitones(a: Double, b: Double) = abs(12 * ln(a / b) / ln(2.0))

    companion object {
        /** Länge eines Messfensters: zehn Frames des Messers. Kürzer gäbe zu selten eine sichere Stimmlage. */
        const val WINDOW_MS = 300

        /**
         * So viel Sprache mit erkennbarer Stimmlage muss zusammenkommen. Beim Vorlesen sind Pausen und
         * stimmlose Laute dabei; für fünf Sekunden davon liest man etwa zehn Sekunden lang.
         */
        const val MIN_VOICED_MS = 5_000

        /** Nach so viel Ton ist Schluss, auch wenn nicht genug Sprache dabei war (dann mit Begründung). */
        const val MAX_HEARD_MS = 30_000

        /**
         * So weit darf ein Fenster vom Median abweichen und zählt noch als "dieselbe Stimme": beim Pegel
         * 6 dB (eine Stimme schwankt beim Vorlesen um wenige dB; jemand am selben Tisch kommt am
         * Kopfhörer-Mikro 15 dB und mehr leiser an), bei der Stimmlage 4 Halbtöne (die Satzmelodie bewegt
         * sich um etwa so viel; zwischen einer tiefen und einer hohen Stimme liegen eher 8 bis 12).
         */
        const val LEVEL_SPREAD_DB = 6.0
        const val PITCH_SPREAD_SEMITONES = 4.0

        /** Mindestens dieser Anteil der Fenster muss in beiden Maßen nah am Median liegen. */
        const val MIN_CONSISTENT = 0.75

        /**
         * Darunter gilt die eigene Stimme als bedenklich leise (dBFS). Der Klassifikator stuft alles unter
         * -52 dBFS fest als FERN ein. Ein Gegenüber kommt gut 14 dB leiser an als man selbst; liegt das
         * eigene Profil unter -38, rutscht es unter diese Grenze, und auch die eigene leise Äußerung hat
         * kaum noch Abstand zum Grundrauschen.
         */
        const val QUIET_DB = -38.0

        /**
         * Probenzahl, mit der ein eingelerntes Profil gespeichert wird. Der Klassifikator mittelt spätere
         * Äußerungen gleitend ein; ab zehn Proben verschiebt jede neue das Profil nur noch um ein Zehntel.
         * Mit dieser Zahl beginnt ein eingelerntes Profil gleich in diesem trägen Bereich.
         */
        const val ENROLLED_SAMPLES = 50
    }
}

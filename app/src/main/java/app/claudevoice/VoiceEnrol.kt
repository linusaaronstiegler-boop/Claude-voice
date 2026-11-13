package app.claudevoice

import android.content.Context
import app.claudevoice.core.EnrollResult
import app.claudevoice.core.MicMode
import app.claudevoice.core.VoiceEnrollment
import app.claudevoice.core.VoiceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Wo das Einlernen der Stimme gerade steht. Die Einstellungen zeichnen genau diesen Wert. */
sealed interface EnrolPhase {
    /** Noch nichts passiert (oder abgebrochen). */
    data object Idle : EnrolPhase

    /** Das Mikrofon wird geöffnet; am Kopfhörer-Mikro dauert der Aufbau des Telefonkanals ein paar Sekunden. */
    data object Starting : EnrolPhase

    /** Es wird aufgenommen: [fraction] ist der Fortschritt (0 bis 1), [levelDb] der Pegel gerade eben. */
    data class Recording(val fraction: Float, val levelDb: Double?) : EnrolPhase

    /** Das Profil ist gespeichert. [warning] sagt, wenn es wackelig ist (Stimme sehr leise). */
    data class Done(val mic: MicMode, val profile: VoiceProfile, val warning: String?) : EnrolPhase

    /** Es hat nicht geklappt; [reason] sagt in Klartext, warum. Gespeichert wurde nichts. */
    data class Failed(val reason: String) : EnrolPhase
}

/**
 * Das Mikrofon, wie das Einlernen es braucht. Eine Schnittstelle, damit ein Test statt des echten
 * Mikrofons eines einsetzen kann, das künstliche Töne liefert.
 */
interface EnrolMic {
    /**
     * Öffnet das Mikrofon und liefert ab dann Ton an [onChunk] (16 kHz, mono, 16 Bit).
     * @param headset true = das Kopfhörer-Mikro ist gewünscht.
     * @return über welche Mikrofon-Art wirklich aufgenommen wird.
     * @throws MicSource.Unavailable wenn es sich nicht öffnen lässt (die Meldung ist für den Nutzer).
     */
    suspend fun open(headset: Boolean, onChunk: (ByteArray, Int) -> Unit, onLost: (String) -> Unit): MicMode

    /** Schließt das Mikrofon. Darf mehrfach gerufen werden. */
    fun close()
}

/**
 * Das echte Mikrofon: derselbe Weg wie beim Zuhören ([MicSource]), also am Kopfhörer-Mikro über den
 * Telefonkanal von Bluetooth, und mit derselben Prüfung, welches Headset dem Träger gehört
 * ([OutputRoute.privateHeadset]). So misst das Einlernen genau das Signal, das später auch die Pipeline sieht.
 */
class AndroidEnrolMic(context: Context, private val confirmed: () -> Set<String>) : EnrolMic {
    private val appContext = context.applicationContext
    private var source: MicSource? = null

    override suspend fun open(headset: Boolean, onChunk: (ByteArray, Int) -> Unit, onLost: (String) -> Unit): MicMode {
        val device = if (headset) {
            OutputRoute(appContext, confirmed).privateHeadset() ?: throw MicSource.Unavailable(NO_HEADSET)
        } else null
        val mic = MicSource(appContext, onLost)
        source = mic
        mic.start(device, onChunk)
        return if (mic.usingBluetooth) MicMode.AIRPODS else MicMode.HANDY
    }

    override fun close() {
        source?.stop()
        source = null
    }

    companion object {
        const val NO_HEADSET = "Kein Kopfhörer-Mikro gefunden. Bitte die Kopfhörer verbinden. Sind sie neu, einmal auf " +
            "der Hauptseite starten: Dort fragt die App, ob es Ihre Ohrhörer sind."
    }
}

/**
 * Führt das Einlernen der Stimme durch: Mikrofon öffnen, Ton an [VoiceEnrollment] (in :core) reichen,
 * den Zwischenstand melden, am Ende das Profil speichern.
 *
 * Vertrauen: Dieser Ablauf wird nur von den Einstellungen gestartet, also von dem Menschen, der das
 * entsperrte Handy in der Hand hält und dort ausdrücklich auf "Einlernen" tippt. Deshalb schreibt er das
 * Profil direkt. Mit dem Zuhören hat er nichts zu tun: Er läuft nur, solange NICHT zugehört wird
 * ([isListening]), hat ein eigenes Mikrofon und keine Verbindung zur Pipeline. Eine Stimme, die während
 * des Zuhörens zu hören ist, kann auf diesem Weg also nie zum Träger werden; dort entscheidet weiter
 * allein die Schranke der Pipeline.
 *
 * Der Ton bleibt im Arbeitsspeicher. Er wird nicht gespeichert und nirgendwohin geschickt; nach der
 * Messung ist er weg. Gespeichert werden Pegel und Stimmlage.
 *
 * [start] und [cancel] gehören auf den Haupt-Thread ([scope] muss dort laufen), weil [MicSource] das verlangt.
 */
class VoiceEnroller(
    private val mic: EnrolMic,
    private val scope: CoroutineScope,
    private val isListening: () -> Boolean,
    private val save: (MicMode, VoiceProfile) -> Unit,
    private val pollMs: Long = 100,
) {
    val phase = MutableStateFlow<EnrolPhase>(EnrolPhase.Idle)
    private var job: Job? = null

    fun start(headset: Boolean) {
        if (job?.isActive == true) return
        // Das Mikrofon gehört beim Zuhören dem Dienst. Zwei Aufnahmen zugleich gäbe Streit um den Telefonkanal.
        if (isListening()) return fail(BUSY)
        phase.value = EnrolPhase.Starting
        job = scope.launch {
            val enrollment = VoiceEnrollment()
            var lost: String? = null
            try {
                val got = mic.open(headset, enrollment::feed) { why -> lost = why }
                // Kam der Telefonkanal nicht zustande, nimmt Android über das Handy-Mikro auf. Das unter
                // "Kopfhörer-Mikro" zu speichern, wäre ein falsches Profil. Dann lieber ehrlich abbrechen.
                if (got != (if (headset) MicMode.AIRPODS else MicMode.HANDY)) {
                    phase.value = EnrolPhase.Failed(NO_LINK)
                    return@launch
                }
                phase.value = EnrolPhase.Recording(0f, null)
                var waitedMs = 0L
                while (true) {
                    delay(pollMs)
                    waitedMs += pollMs
                    lost?.let { why ->
                        phase.value = EnrolPhase.Failed(why)
                        return@launch
                    }
                    val p = enrollment.progress
                    // Fertig, wenn genug Sprache da ist. Die Notbremsen: Das Mikrofon liefert gar nichts,
                    // oder es liefert so stockend, dass auch die Höchstdauer an Ton nie zusammenkäme.
                    if (p.done || (p.heardMs == 0 && waitedMs >= SILENT_MIC_MS) || waitedMs >= GIVE_UP_MS) break
                    phase.value = EnrolPhase.Recording(p.fraction, p.levelDb)
                }
                mic.close()
                phase.value = when (val result = enrollment.finish()) {
                    is EnrollResult.Failure -> EnrolPhase.Failed(result.reason)
                    is EnrollResult.Success ->
                        // Hat inzwischen doch das Zuhören begonnen, bleibt dessen Profil unangetastet.
                        if (isListening()) EnrolPhase.Failed(BUSY)
                        else {
                            save(got, result.profile)
                            EnrolPhase.Done(got, result.profile, result.warning)
                        }
                }
            } catch (e: MicSource.Unavailable) {
                phase.value = EnrolPhase.Failed(e.message ?: "Mikrofon konnte nicht geöffnet werden")
            } finally {
                // Auch bei Abbruch: Das Mikrofon (und der Telefon-Modus von Bluetooth) wird immer freigegeben.
                mic.close()
            }
        }
    }

    /** Bricht ab und verwirft alles Gemessene. Gespeichert wird dann nichts. */
    fun cancel() {
        job?.cancel()
        job = null
        mic.close()
        phase.value = EnrolPhase.Idle
    }

    /** Meldet einen Grund, aus dem gar nicht erst begonnen wurde (z. B. fehlende Berechtigung). */
    fun fail(reason: String) {
        if (job?.isActive == true) return
        phase.value = EnrolPhase.Failed(reason)
    }

    companion object {
        const val BUSY = "Zum Einlernen erst das Zuhören stoppen."
        const val NO_LINK = "Das Kopfhörer-Mikro kam nicht zustande. Bitte prüfen, ob die Kopfhörer verbunden und " +
            "nicht gerade mit einem anderen Gerät beschäftigt sind."
        const val NO_PERMISSION = "Ohne Mikrofon-Berechtigung kann die App Ihre Stimme nicht einlernen."

        /** Kommt so lange gar kein Ton, ist das Mikrofon stumm. */
        const val SILENT_MIC_MS = 5_000L

        /** Spätestens dann wird ausgewertet, was da ist (etwas mehr als die Höchstdauer an Ton in :core). */
        const val GIVE_UP_MS = 40_000L
    }
}

/** Speichert ein eingelerntes Profil für die Mikrofon-Art, über die es gemessen wurde. */
internal fun Prefs.saveEnrolledVoiceProfile(mic: MicMode, profile: VoiceProfile) {
    storeVoiceProfile(mic, profile, ended = false)
}

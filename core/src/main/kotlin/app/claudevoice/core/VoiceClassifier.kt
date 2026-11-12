package app.claudevoice.core

import kotlin.math.abs
import kotlin.math.ln

/** Schätzung, wer gesprochen hat. Bewusst grob: mehr gibt das Signal ohne Spezial-Hardware nicht sicher her. */
enum class Voice(val label: String) {
    ICH("ICH"),      // wahrscheinlich der Träger selbst
    NAH("NAH"),      // jemand in Gesprächsnähe
    FERN("FERN"),    // leise, weiter weg: Nachbartisch, Fernseher, Musik
    UNKLAR("?"),     // zu wenig Signal für eine Schätzung
    ALFRED("ALFRED"), // der Assistent selbst (kommt nicht vom Mikro, sondern aus dem eigenen Verlauf)
}

enum class MicMode { AIRPODS, HANDY }

/** Gelerntes Profil der eigenen Stimme für eine Mikrofon-Art. */
data class VoiceProfile(val levelDb: Double, val pitchHz: Double?, val samples: Int)

/**
 * Ordnet eine Äußerung einem von drei Abständen zu.
 *
 * Idee: Die eigene Stimme ist am Mikro die lauteste (bei AirPods mit großem Abstand) und hat eine
 * gleichbleibende Tonhöhe. Das Gegenüber kommt deutlich leiser an, der Nachbartisch noch leiser.
 *
 * Woher kennt die App die eigene Stimme? Sie lernt sie nebenbei: Jede Äußerung, in der der Name
 * des Assistenten fällt, stammt mit hoher Wahrscheinlichkeit vom Träger. Deren Pegel und Tonhöhe
 * werden gemittelt und als Profil gespeichert. Ohne Profil gilt die lauteste Stimme der Sitzung
 * als Bezugspunkt.
 */
class VoiceClassifier(
    private val mic: MicMode,
    profile: VoiceProfile? = null,
) {
    var profile: VoiceProfile? = profile
        private set

    private val sessionLevels = ArrayDeque<Double>()

    /**
     * @param record false = nur schauen (für vorläufige Zwischenstände), die Äußerung zählt dann nicht
     *               in die Statistik der Sitzung.
     */
    @Synchronized
    fun classify(f: VoiceFeatures?, record: Boolean = true): Voice {
        if (f == null) return Voice.UNKLAR
        if (record) {
            sessionLevels.addLast(f.levelDb)
            if (sessionLevels.size > 40) sessionLevels.removeFirst()
        }
        if (f.levelDb < ABSOLUTE_FAR_DB) return Voice.FERN

        val own = profile
        val reference = own?.levelDb ?: sessionReference()
        val below = reference - f.levelDb // um wie viel dB leiser als die eigene Stimme
        val pitchFits = own?.pitchHz == null || f.pitchHz == null || semitones(f.pitchHz, own.pitchHz!!) <= PITCH_TOLERANCE

        return when (mic) {
            // AirPods sitzen am Kopf: die eigene Stimme ist konkurrenzlos laut.
            MicMode.AIRPODS -> when {
                below <= 8 && pitchFits -> Voice.ICH
                below <= 8 -> Voice.NAH // so laut, aber fremde Stimmlage: jemand direkt am Ohr
                below <= 24 -> Voice.NAH
                else -> Voice.FERN
            }
            // Handy auf dem Tisch oder in der Tasche: Lautstärke trennt schlechter, die Tonhöhe muss helfen.
            MicMode.HANDY -> when {
                own?.pitchHz != null && f.pitchHz != null && pitchFits && below <= 12 -> Voice.ICH
                below <= 14 -> Voice.NAH
                else -> Voice.FERN
            }
        }
    }

    /** Aufrufen, wenn eine Äußerung sicher vom Träger stammt (er hat den Assistenten beim Namen genannt). */
    @Synchronized
    fun learnOwn(f: VoiceFeatures?) {
        if (f == null) return
        val old = profile
        if (old == null) {
            profile = VoiceProfile(f.levelDb, f.pitchHz, 1)
            return
        }
        // Gleitender Mittelwert: frühe Proben zählen voll, später ändert jede neue nur noch ein Zehntel.
        val weight = 1.0 / minOf(old.samples + 1, 10)
        profile = VoiceProfile(
            levelDb = old.levelDb + (f.levelDb - old.levelDb) * weight,
            pitchHz = when {
                f.pitchHz == null -> old.pitchHz
                old.pitchHz == null -> f.pitchHz
                else -> old.pitchHz + (f.pitchHz - old.pitchHz) * weight
            },
            samples = old.samples + 1,
        )
    }

    /** Ohne Profil: die lauteren Äußerungen der Sitzung (90. Perzentil) stehen für "ganz nah". */
    private fun sessionReference(): Double {
        if (sessionLevels.isEmpty()) return DEFAULT_OWN_DB
        val sorted = sessionLevels.sortedDescending()
        return sorted[sorted.size / 10]
    }

    private fun semitones(a: Double, b: Double) = abs(12 * ln(a / b) / ln(2.0))

    private companion object {
        const val ABSOLUTE_FAR_DB = -52.0
        const val DEFAULT_OWN_DB = -22.0 // Erfahrungswert für normale Sprache direkt am Mikro
        const val PITCH_TOLERANCE = 3.0 // Halbtöne; Stimmen schwanken beim Sprechen um etwa so viel
    }
}

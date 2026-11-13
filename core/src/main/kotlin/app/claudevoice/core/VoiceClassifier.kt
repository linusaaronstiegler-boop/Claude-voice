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
 * Woher kennt die App die eigene Stimme? Am besten aus den Einstellungen ("Stimme einlernen"): Das dort
 * gemessene Profil wird gespeichert und hier nur noch benutzt, nicht mehr verändert ([learnOwn]). Ohne das
 * lernt sie die Stimme nebenbei: Jede Äußerung, die mit dem Namen des Assistenten beginnt und die die Akustik
 * schon für die Stimme des Trägers hält, ist eine Probe. Aus drei Proben, die zusammenpassen, entsteht das
 * Profil. Ohne Profil gilt die lauteste Stimme der Sitzung als Bezugspunkt.
 */
class VoiceClassifier(
    private val mic: MicMode,
    profile: VoiceProfile? = null,
) {
    /**
     * Das Profil der eigenen Stimme, sobald es als bekannt gilt: aus mindestens [MIN_PROFILE_SAMPLES] Proben.
     * Ein gespeichertes Profil aus weniger Proben (frühere Fassungen der App legten es nach dem ersten Ruf an)
     * wird nicht übernommen. Eine einzelne Probe sagt zu wenig: Stammte sie von einem Freund, der den
     * Assistenten ausprobiert hat, oder vom Träger in gehobener Stimmlage, passte seine gewöhnliche Stimme
     * danach nie mehr zum Profil, und weil nur eine passende Stimme das Profil weiterlernt, kam er aus eigener
     * Kraft auch nicht mehr hinein.
     */
    var profile: VoiceProfile? = profile?.takeIf { it.samples >= MIN_PROFILE_SAMPLES }
        private set

    /** Proben, aus denen das Profil erst noch entsteht ([learnOwn]). Sie bleiben im Arbeitsspeicher. */
    private val candidates = ArrayDeque<VoiceFeatures>()

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
                // NAH heißt hier nur "nah am Handy". Ohne gelernte Tonhöhe kann das auch der Träger selbst sein.
                // Für die Pipeline ist NAH trotzdem die richtige Angabe: Sie wartet bei NAH ab, ob ein Mensch
                // antwortet, und behandelt die Stimme nicht wie die des Trägers ("Nur ich", Nachfragen).
                // Ein "?" an dieser Stelle hätte genau diese Vorsicht ausgeschaltet. Dass hinter NAH am Handy
                // auch er stecken kann, erfährt das Modell dort, wo der Text für es entsteht (Persona.userMessage).
                below <= 14 -> Voice.NAH
                else -> Voice.FERN
            }
        }
    }

    /**
     * Aufrufen, wenn eine Äußerung sicher vom Träger stammt (er hat den Assistenten beim Namen genannt, und
     * die Akustik hielt die Stimme schon für seine).
     *
     * Solange es kein Profil gibt, wird nur gesammelt. Ein Profil entsteht erst, wenn drei Proben in der
     * Stimmlage zusammenpassen (jede höchstens [PITCH_TOLERANCE] Halbtöne vom mittleren Wert entfernt). Ein
     * Ausreißer, etwa ein einzelner Ruf mit erhobener Stimme, fällt so heraus, statt das Profil zu werden.
     * Proben ohne messbare Stimmlage zählen nicht: An ihnen ließe sich später nichts wiedererkennen.
     * Bis dahin gilt für das Vertrauen dieselbe Regel wie ganz ohne Profil (Pipeline.voiceOf).
     *
     * Gibt es ein Profil, verfeinert jede weitere Probe es noch ein wenig, aber nur bis [FIRM_SAMPLES] Proben.
     * Danach steht es fest: Eine einzelne Äußerung ändert es nicht mehr, und es wird auch nicht neu
     * gespeichert. Das gilt von Anfang an für ein Profil, das in den Einstellungen eingelernt wurde ("Stimme
     * einlernen", zehn Sekunden Sprache, viele Proben). Der Grund: Ein Profil, das mit jeder Äußerung ein
     * Stück wandert, kann von der eigenen Stimme wegwandern (ein paar Rufe mit erhobener Stimme, eine Erkältung,
     * eine ähnliche fremde Stimme), und wer nicht mehr dazu passt, bringt es aus eigener Kraft nicht zurück.
     * Ein festes Profil kann das nicht. Passt es nicht mehr, wird es in den Einstellungen neu eingelernt.
     *
     * @return true, wenn sich das Profil geändert hat (dann lohnt das Speichern).
     */
    @Synchronized
    fun learnOwn(f: VoiceFeatures?): Boolean {
        if (f == null) return false
        val old = profile
        if (old == null) {
            if (f.pitchHz == null) return false
            candidates.addLast(f)
            if (candidates.size > MAX_CANDIDATES) candidates.removeFirst()
            val middle = candidates.map { it.pitchHz!! }.sorted()[candidates.size / 2]
            val agreeing = candidates.filter { semitones(it.pitchHz!!, middle) <= PITCH_TOLERANCE }
            if (agreeing.size >= MIN_PROFILE_SAMPLES) {
                profile = VoiceProfile(agreeing.map { it.levelDb }.average(), agreeing.map { it.pitchHz!! }.average(), agreeing.size)
                candidates.clear()
                return true
            }
            return false
        }
        if (old.samples >= FIRM_SAMPLES) return false
        // Laufender Mittelwert: Jede Probe zählt gleich viel, die vierte also ein Viertel, die zehnte ein Zehntel.
        val weight = 1.0 / (old.samples + 1)
        profile = VoiceProfile(
            levelDb = old.levelDb + (f.levelDb - old.levelDb) * weight,
            pitchHz = when {
                f.pitchHz == null -> old.pitchHz
                old.pitchHz == null -> f.pitchHz
                else -> old.pitchHz + (f.pitchHz - old.pitchHz) * weight
            },
            samples = old.samples + 1,
        )
        return true
    }

    /**
     * Liegt die Stimmlage dieser Äußerung beim Profil? false auch, wenn es kein Profil gibt oder die Stimmlage
     * nicht messbar war. Gebraucht wird das nur für die Meldung in der Anzeige, warum eine Stimme nicht als
     * gesichert gilt ("passt in der Stimmlage, kam aber zu leise an"), nie für das Vertrauen selbst.
     */
    @Synchronized
    fun fitsPitch(f: VoiceFeatures): Boolean {
        val own = profile?.pitchHz ?: return false
        return f.pitchHz != null && semitones(f.pitchHz, own) <= PITCH_TOLERANCE
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
        /** Erst mit so vielen zusammenpassenden Proben gilt die eigene Stimme als bekannt. */
        const val MIN_PROFILE_SAMPLES = 3
        /** So viele Proben werden höchstens aufgehoben, während das Profil entsteht (die ältesten fallen heraus). */
        const val MAX_CANDIDATES = 5
        /** Ab so vielen Proben steht das Profil fest und wird von einzelnen Äußerungen nicht mehr verändert ([learnOwn]). */
        const val FIRM_SAMPLES = 10
    }
}

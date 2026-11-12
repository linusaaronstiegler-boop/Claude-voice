package app.claudevoice.core.eval

import app.claudevoice.core.Line
import app.claudevoice.core.Persona
import app.claudevoice.core.Situation
import app.claudevoice.core.Turn
import app.claudevoice.core.Voice
import java.util.Random

/**
 * Wie gut die Stimmen- und Sprecher-Angaben vor den Zeilen sind.
 *
 * Im echten Betrieb stammen sie aus Lautstärke, Stimmlage und Diarisierung und sind oft falsch. Der Prompt
 * soll am Inhalt entscheiden und mit jeder Qualität dieser Angaben zurechtkommen. Deshalb läuft jeder
 * Fall in drei Fassungen: mit richtigen Angaben, mit teilweise falschen und ganz ohne.
 */
enum class TagMode(val id: String) {
    /** Die Akustik liegt überall richtig. */
    KORREKT("korrekt"),
    /** Etwa jede vierte Stimmen-Angabe ist falsch, Sprecher-Kürzel fehlen oder sind vertauscht. */
    VERRAUSCHT("verrauscht"),
    /** Der Stimmenfilter ist komplett ausgefallen: jede Zeile "?" und kein Kürzel. */
    OHNE("ohne");

    companion object {
        fun of(id: String): TagMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Macht aus einem Testfall genau das, was das Antwort-Modell im Betrieb bekäme: einen [Turn]
 * (Verlauf, auslösende Äußerung, Lage). Den Text daraus baut dann der echte [Persona.userMessage],
 * damit die Auswertung nicht versehentlich ein anderes Format prüft als die App benutzt.
 *
 * Nicht nachgebildet wird der Ordner ([app.claudevoice.core.Brain.tidy]): Die Szene ist immer leer und
 * keine Zeile trägt den Vermerk "(abseits)". Der Prompt muss also allein zurechtkommen. Das ist der
 * strengere Test, denn im Betrieb fehlt die Vorarbeit des Ordners in den ersten Sekunden ebenfalls.
 */
object EvalRenderer {
    /** Feste Uhrzeit im Format der App, damit jede Ausgabe Zeichen für Zeichen reproduzierbar ist. */
    const val LOCAL_TIME = "Donnerstag, 12. November 2026, 17:40"

    /** Anteile im Modus "verrauscht". */
    const val WRONG_VOICE = 0.25
    const val LOST_LABEL = 0.25
    const val SWAPPED_LABEL = 0.10

    /** Zeitpunkt der auslösenden Äußerung. Der Wert ist beliebig, er muss nur größer sein als jedes Alter im Verlauf. */
    private const val NOW_MS = 4_000_000L

    /** Was der Stimmenfilter für eine Zeile vom Mikrofon liefern kann (ALFRED kommt nie vom Mikrofon). */
    private val MIC_VOICES = listOf(Voice.ICH, Voice.NAH, Voice.FERN, Voice.UNKLAR)

    private data class Tag(val voice: Voice, val speaker: String?)

    fun render(case: EvalCase, mode: TagMode): Turn {
        val all = case.history + case.trigger
        val labels = speakerLabels(all)
        val truth = all.map { Tag(trueVoice(it), labels[it.who]) }
        val tags = when (mode) {
            TagMode.KORREKT -> truth
            TagMode.OHNE -> truth.map { if (it.voice == Voice.ALFRED) it else Tag(Voice.UNKLAR, null) }
            TagMode.VERRAUSCHT -> corrupt(case.id, truth, labels.values.toList())
        }
        val lines = all.mapIndexed { i, l ->
            Line(id = i + 1L, atMs = NOW_MS - l.agoSec * 1000, voice = tags[i].voice, text = l.text.trim(), speaker = tags[i].speaker)
        }
        val situation = Situation(LOCAL_TIME, case.mic, case.musicPlaying, case.assistantSpokeAgoSec, scene = null)
        return Turn(lines.dropLast(1), lines.last(), situation)
    }

    /** Die Nutzer-Nachricht, exakt wie die App sie schicken würde. */
    fun userMessage(case: EvalCase, mode: TagMode): String = Persona.userMessage(render(case, mode))

    /** Stimmen-Schätzung, wenn die Akustik perfekt wäre: Träger = ICH, sonst entscheidet der Abstand. */
    private fun trueVoice(l: EvalLine): Voice = when {
        l.speaker == EvalCases.ASSISTANT -> Voice.ALFRED
        l.speaker == EvalCases.WEARER -> Voice.ICH
        l.proximity == "fern" -> Voice.FERN
        else -> Voice.NAH // "nah" und "mittel": beides Gesprächsnähe
    }

    /**
     * Sprecher-Kürzel wie im Betrieb ([app.claudevoice.core.SpeakerMap]): S1, S2, ... in der Reihenfolge,
     * in der die Stimmen zum ersten Mal auftreten. Auch der Träger bekommt eines, denn die Diarisierung
     * kennt ihn nicht. Der Assistent bekommt keines: Seine Zeilen kommen nicht vom Mikrofon.
     */
    private fun speakerLabels(lines: List<EvalLine>): Map<String, String> {
        val labels = LinkedHashMap<String, String>()
        for (l in lines) if (l.speaker != EvalCases.ASSISTANT) labels.getOrPut(l.who) { "S${labels.size + 1}" }
        return labels
    }

    /**
     * Modus "verrauscht": würfelt Fehler in die Angaben, aber für denselben Fall immer dieselben.
     *
     * Der Zufall hängt nur an der ID des Falls. So bleibt die Eingabe von Lauf zu Lauf gleich und zwei
     * Prompts werden an exakt denselben Störungen gemessen. Pro Zeile werden immer gleich viele
     * Zufallszahlen gezogen, egal was daraus folgt; ein geänderter Fall verschiebt so keine anderen.
     */
    private fun corrupt(caseId: String, truth: List<Tag>, known: List<String>): List<Tag> {
        val rnd = Random(seed(caseId))
        return truth.map { tag ->
            val voiceRoll = rnd.nextDouble()
            val voicePick = rnd.nextInt(MIC_VOICES.size - 1)
            val labelRoll = rnd.nextDouble()
            val labelPick = rnd.nextInt(1 shl 16)
            if (tag.voice == Voice.ALFRED) return@map tag // eigene Zeilen kennt die App sicher

            val voice = if (voiceRoll < WRONG_VOICE) (MIC_VOICES - tag.voice)[voicePick] else tag.voice
            val speaker = when {
                labelRoll < LOST_LABEL -> null
                labelRoll < LOST_LABEL + SWAPPED_LABEL -> {
                    val others = known.filter { it != tag.speaker }
                    // Spricht im ganzen Fall nur eine Person, "hört" die Diarisierung fälschlich eine zweite.
                    if (others.isEmpty()) "S${known.size + 1}" else others[labelPick % others.size]
                }
                else -> tag.speaker
            }
            Tag(voice, speaker)
        }
    }

    /**
     * Startwert für den Zufall aus einem Text (FNV-1a, 64 Bit). Bewusst von Hand statt `hashCode()` oder
     * `kotlin.random.Random`: Deren Ergebnis darf sich zwischen Versionen ändern, und dann wären alte und
     * neue Läufe nicht mehr vergleichbar. [java.util.Random] ist dagegen in der Java-Spezifikation festgelegt.
     */
    fun seed(text: String): Long {
        var h = 0xcbf29ce484222325uL.toLong() // FNV-Startwert
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h
    }
}

/**
 * Teilt die Fälle in kleine Stapel, die jemand (oder ein Modell-Lauf) am Stück bearbeitet.
 *
 * Gemischt wird mit Absicht: Stünden sechs Fälle derselben Szene hintereinander, ließe sich das Muster
 * raten ("hier wird immer geschwiegen"). Jede Szene wird deshalb gleichmäßig über die ganze Folge
 * verteilt. Die Reihenfolge ist gewürfelt, hängt aber nur am Modus und an den IDs, also bei jedem
 * Aufruf dieselbe.
 */
object EvalBatches {
    const val SIZE = 6

    fun plan(cases: List<EvalCase>, mode: TagMode, size: Int = SIZE): List<List<String>> {
        val rnd = Random(EvalRenderer.seed("stapel:${mode.id}"))
        val placed = cases.groupBy { it.scene }.toSortedMap().flatMap { (_, group) ->
            // Innerhalb der Szene mischen ...
            val shuffled = group.map { it.id }.sorted().map { rnd.nextDouble() to it }.sortedBy { it.first }.map { it.second }
            // ... und dann jedem Fall einen Platz zwischen 0 und 1 geben, gleichmäßig gestreut.
            shuffled.mapIndexed { i, id -> (i + rnd.nextDouble()) / shuffled.size to id }
        }
        return placed.sortedWith(compareBy({ it.first }, { it.second })).map { it.second }.chunked(size)
    }
}

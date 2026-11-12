package app.claudevoice.core.eval

import app.claudevoice.core.Heuristics
import app.claudevoice.core.Heuristics.Heat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Der kostenlose Vorfilter ([Heuristics.heat]) entscheidet, ob eine Äußerung überhaupt ein Modell erreicht.
 * Was er als COLD einstuft, sieht Claude nie. Deshalb darf kein Testfall, bei dem ein Mensch eine Antwort
 * erwartet, schon hier verloren gehen. Dieser Test legt alle Fälle am Vorfilter vorbei und meldet jeden,
 * der durchfiele. So fällt eine zu strenge Regel auf, bevor sie auf dem Handy stumm macht.
 */
class PrefilterOnCasesTest {
    private val names = listOf("Alfred", "Jarvis")

    /** So entscheidet die Pipeline, ob eine fertige Äußerung weitergereicht wird (ohne die Stimmen-Regeln). */
    private fun reaches(case: EvalCase): Boolean {
        val text = case.trigger.text
        val named = Heuristics.mentionsAny(text, names)
        // Nachfrage: Der Assistent hat eben gesprochen und der Träger redet weiter.
        val followUp = (case.assistantSpokeAgoSec ?: Long.MAX_VALUE) < 20 &&
            case.trigger.speaker == EvalCases.WEARER && Heuristics.words(text).size >= 2
        // In der Bereitschaft macht das lokale Weckwort die nächste Äußerung zur Anrede, auch wenn der Name verhört ist.
        val woken = "wake-word" in case.tags
        return named || followUp || woken || Heuristics.heat(text, names) != Heat.COLD
    }

    @Test fun `kein Fall mit erwarteter Antwort wird schon vom Vorfilter verworfen`() {
        val lost = EvalCases.loadAll()
            .filter { it.expect.answer && !it.ambiguous && !reaches(it) }
            .map { "${it.id}: ${it.trigger.text}" }
        assertEquals("Diese Fälle erreichen nie ein Modell:\n" + lost.joinToString("\n"), emptyList<String>(), lost)
    }

    @Test fun `Uebersicht, wie viele Faelle der Vorfilter gleich verwirft`() {
        val all = EvalCases.loadAll()
        val silent = all.filter { !it.expect.answer && !it.ambiguous }
        val dropped = silent.count { !reaches(it) }
        // Kein hartes Ziel: Die Zahl zeigt nur, wie viel Arbeit (und Geld) der Vorfilter dem Modell abnimmt.
        println("Vorfilter: $dropped von ${silent.size} Schweige-Fällen werden ohne Modell-Aufruf verworfen")
        // Fälle mit erwarteter Antwort, die nur über die billige Vorprüfung (MAYBE) weiterkommen: Bei ihnen
        // hängt die Antwort zusätzlich am kleinen Modell. Diese Liste zeigt, welche das sind.
        val viaGate = all.filter { it.expect.answer && !it.ambiguous && reaches(it) && !direct(it) }
        println("Vorfilter: ${viaGate.size} Antwort-Fälle laufen über die Vorprüfung: " + viaGate.joinToString { it.id })
        // Schweige-Fälle auf demselben Weg: Sagt die Vorprüfung hier NEIN, spart das einen Aufruf des großen Modells.
        val silentViaGate = silent.filter { reaches(it) && !direct(it) }
        println("Vorfilter: ${silentViaGate.size} Schweige-Fälle laufen über die Vorprüfung: " + silentViaGate.joinToString { it.id })
    }

    /** Geht ohne Vorprüfung direkt an das Antwort-Modell (Anrede, Weckwort, Nachfrage oder klare Frage). */
    private fun direct(case: EvalCase): Boolean {
        val text = case.trigger.text
        val followUp = (case.assistantSpokeAgoSec ?: Long.MAX_VALUE) < 20 &&
            case.trigger.speaker == EvalCases.WEARER && Heuristics.words(text).size >= 2
        val woken = "wake-word" in case.tags
        return Heuristics.mentionsAny(text, names) || followUp || woken || Heuristics.heat(text, names) == Heat.HOT
    }
}

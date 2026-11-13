package app.claudevoice.core

/**
 * Verwaltet die Sprecher-IDs der Diarisierung.
 *
 * Der Anbieter vergibt anonyme IDs ("speaker_0", "speaker_1"). Er weiß nicht, welche davon der Träger ist.
 * Das lernen wir durch Abstimmung: Jedes Mal, wenn die akustische Schätzung zu einer Äußerung "ICH" sagt,
 * bekommt deren Sprecher-ID eine Stimme. Wer klar führt, gilt als Träger. So stützen sich zwei unsichere
 * Signale gegenseitig: die Akustik (laut, aber irrt bei Lärm) und die Diarisierung (stabil, aber anonym).
 */
class SpeakerMap {
    private val labels = LinkedHashMap<String, String>()

    /** Eine Beobachtung: Diese Sprecher-ID war zu hören, und die Akustik hielt sie für den Träger ([own]) oder nicht. */
    private class Vote(var id: String, val own: Boolean)

    /**
     * Nur die letzten Beobachtungen zählen. Früher zählte jede seit Beginn der Sitzung: Vergab der Anbieter dem
     * Träger mitten im Gespräch eine neue ID, musste die neue erst mehr Stimmen sammeln als die alte je hatte.
     * Nach hundert Äußerungen war er damit für hundert weitere ausgesperrt, obwohl die Akustik ihn erkannte.
     * Mit dem Fenster vergisst die Abstimmung, was lange her ist ([wearer]).
     */
    private val votes = ArrayDeque<Vote>()

    /** Kurzes, für das Modell lesbares Kürzel: "S1", "S2", ... in Reihenfolge des ersten Auftretens. */
    @Synchronized
    fun label(id: String?): String? {
        if (id == null) return null
        return labels.getOrPut(id) { "S${labels.size + 1}" }
    }

    @Synchronized
    fun observe(id: String?, voice: Voice) {
        if (id == null || voice == Voice.UNKLAR || voice == Voice.ALFRED) return
        votes.addLast(Vote(id, voice == Voice.ICH))
        if (votes.size > WINDOW) votes.removeFirst()
    }

    /** Die Sprecher-ID, die sehr wahrscheinlich dem Träger gehört, oder null, wenn noch unklar. */
    @Synchronized
    fun wearer(): String? {
        val own = HashMap<String, Int>()
        val total = HashMap<String, Int>()
        for (v in votes) {
            total[v.id] = (total[v.id] ?: 0) + 1
            if (v.own) own[v.id] = (own[v.id] ?: 0) + 1
        }
        // In Frage kommt jede ID, die mindestens zweimal als Träger gemessen wurde und überwiegend (60 %) so.
        // Sind es mehrere, gilt die, die zuletzt so gemessen wurde: Unter ihr spricht er jetzt. Früher gewann
        // die ID mit den meisten Stimmen. Wechselte der Anbieter mitten im Gespräch die ID des Trägers (das
        // kommt vor, die IDs sind vorläufig), blieb er ausgesperrt, bis die neue ID die alte eingeholt hatte.
        // Jetzt genügen der neuen zwei Äußerungen, die die Akustik ihm zuordnet.
        fun qualifies(id: String): Boolean {
            val o = own[id] ?: 0
            return o >= 2 && o.toDouble() / (total[id] ?: 1) >= 0.6
        }
        return votes.lastOrNull { it.own && qualifies(it.id) }?.id
    }

    /** Der Anbieter meldet: [from] und [to] sind dieselbe Person. */
    @Synchronized
    fun merge(from: String, to: String) {
        for (v in votes) if (v.id == from) v.id = to
        if (to !in labels) labels[to] = labels[from] ?: "S${labels.size + 1}"
    }

    /** Nach einem Neuverbinden beginnen die IDs des Anbieters von vorn. */
    @Synchronized
    fun reset() {
        labels.clear(); votes.clear()
    }

    private companion object {
        /** So viele Beobachtungen zählen für die Abstimmung. */
        const val WINDOW = 12
    }
}

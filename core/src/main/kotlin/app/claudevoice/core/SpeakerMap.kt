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
    private val total = HashMap<String, Int>()
    private val own = HashMap<String, Int>()

    /** Kurzes, für das Modell lesbares Kürzel: "S1", "S2", ... in Reihenfolge des ersten Auftretens. */
    @Synchronized
    fun label(id: String?): String? {
        if (id == null) return null
        return labels.getOrPut(id) { "S${labels.size + 1}" }
    }

    @Synchronized
    fun observe(id: String?, voice: Voice) {
        if (id == null || voice == Voice.UNKLAR || voice == Voice.ALFRED) return
        total[id] = (total[id] ?: 0) + 1
        if (voice == Voice.ICH) own[id] = (own[id] ?: 0) + 1
    }

    /** Die Sprecher-ID, die sehr wahrscheinlich dem Träger gehört, oder null, wenn noch unklar. */
    @Synchronized
    fun wearer(): String? {
        val best = own.maxByOrNull { it.value } ?: return null
        val share = best.value.toDouble() / (total[best.key] ?: 1)
        return if (best.value >= 2 && share >= 0.6) best.key else null
    }

    /** Der Anbieter meldet: [from] und [to] sind dieselbe Person. */
    @Synchronized
    fun merge(from: String, to: String) {
        total[to] = (total[to] ?: 0) + (total.remove(from) ?: 0)
        own[to] = (own[to] ?: 0) + (own.remove(from) ?: 0)
        if (to !in labels) labels[to] = labels[from] ?: "S${labels.size + 1}"
    }

    /** Nach einem Neuverbinden beginnen die IDs des Anbieters von vorn. */
    @Synchronized
    fun reset() {
        labels.clear(); total.clear(); own.clear()
    }
}

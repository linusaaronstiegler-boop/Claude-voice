package app.claudevoice.core

/**
 * Billige Vorprüfung ohne API-Aufruf. Sie entscheidet nicht, OB geantwortet wird,
 * sondern nur, wie dringend das Modell gefragt werden soll:
 *  - HOT: sieht nach Frage oder Anrede aus -> sofort ans starke Modell (jede Millisekunde zählt)
 *  - MAYBE: längere Aussage, könnte ein versteckter Wissenswunsch sein -> erst billiges Vorfilter-Modell
 *  - COLD: Füllsel, zu kurz -> gar kein Aufruf
 */
object Heuristics {
    enum class Heat { HOT, MAYBE, COLD }

    private val WORD = Regex("[\\p{L}\\p{N}']+")

    private val INTERROGATIVES = setOf(
        "wer", "wen", "wem", "wessen", "was", "wann", "wo", "wie", "warum", "wieso", "weshalb", "weswegen",
        "welche", "welcher", "welches", "welchen", "welchem", "wozu", "wohin", "woher", "womit", "wodurch",
        "wofür", "worauf", "woran", "worin", "wieviel", "wieviele", "inwiefern",
        "what", "who", "whom", "whose", "when", "where", "why", "how", "which",
    )

    /** Verben am Satzanfang deuten im Deutschen (und Englischen) auf eine Entscheidungsfrage. */
    private val LEADING_VERBS = setOf(
        "ist", "sind", "war", "waren", "hat", "haben", "hatte", "hatten", "kann", "können", "könnte", "könnten",
        "darf", "dürfen", "muss", "müssen", "soll", "sollte", "wird", "werden", "würde", "gibt", "gab", "stimmt",
        "kennt", "kennst", "weiß", "weißt", "wisst", "heißt", "bedeutet", "geht",
        "is", "are", "was", "were", "do", "does", "did", "can", "could", "should", "would", "will", "has", "have",
    )

    private val MARKERS = listOf(
        Regex("\\bweiß (jemand|einer|wer|man)\\b"), Regex("\\bweißt du\\b"), Regex("\\bwisst ihr\\b"),
        Regex("\\bfrag(e)? mich\\b"), Regex("\\bwie (hieß|heißt|war|ging|nennt man)\\b"),
        Regex("\\b(noch ?mal|gleich) (hieß|heißt|war)\\b"), Regex("\\b(was|wer|wie|wo|wann) (war|ist|hieß) (noch|gleich|das noch)\\b"),
        Regex("\\bkeine ahnung (wie|was|wer|wann|wo|warum|ob|wieso)\\b"), Regex("\\b(wüsste|wüssten) (ich |wir )?gern\\b"),
        Regex("\\bwürde mich (mal )?interessieren\\b"), Regex("\\bstimmt (es|das)\\b"), Regex("\\bist es wahr\\b"),
        Regex("\\bbin (mir )?nicht sicher (ob|wie|was|wann|wo)\\b"), Regex("\\berklär\\w* (mir|mal|uns)\\b"),
        Regex("\\bdo you know\\b"), Regex("\\bdoes anyone know\\b"), Regex("\\bi wonder\\b"), Regex("\\bany idea\\b"),
        Regex("\\bwhat's\\b"), Regex("\\bwho's\\b"),
    )

    private val STOP_WORDS = listOf(
        "stopp", "stop", "ruhe", "still", "leise", "aufhören", "hör auf", "schweig", "danke reicht", "reicht",
        "halt", "klappe", "quiet", "shut up", "be quiet",
    )

    fun words(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }.toList()

    /**
     * Wurde der Assistent beim Namen genannt? Die Transkription verhört sich bei Namen gern
     * ("Alfret", "all fred"), deshalb zählt auch ein Wort, das sich um einen Buchstaben unterscheidet,
     * und zwei Nachbarwörter, die zusammen den Namen ergeben.
     */
    fun mentionsName(text: String, name: String): Boolean {
        val target = name.lowercase().trim()
        if (target.isEmpty()) return false
        val tolerance = if (target.length >= 5) 1 else 0
        val w = words(text)
        for (i in w.indices) {
            if (editDistance(w[i], target) <= tolerance) return true
            if (i + 1 < w.size && editDistance(w[i] + w[i + 1], target) <= tolerance) return true
        }
        return false
    }

    fun heat(text: String, name: String): Heat {
        val t = text.trim().lowercase()
        val w = words(t)
        if (w.isEmpty()) return Heat.COLD
        if (mentionsName(t, name)) return Heat.HOT
        if (t.contains('?')) return Heat.HOT
        if (w.size >= 2 && w.first() in INTERROGATIVES) return Heat.HOT
        if (MARKERS.any { it.containsMatchIn(t) }) return Heat.HOT
        if (w.size >= 3 && w.first() in LEADING_VERBS) return Heat.HOT
        // Fragewort mitten im Satz ("und wer hat das dann erfunden")
        if (w.size >= 4 && w.take(3).any { it in INTERROGATIVES }) return Heat.HOT
        return if (w.size >= 6) Heat.MAYBE else Heat.COLD
    }

    /** "Alfred, stopp" und Verwandte: wird sofort lokal ausgeführt, ohne Umweg über das Modell. */
    fun isStopCommand(text: String, name: String): Boolean {
        val t = text.lowercase()
        return words(t).size <= 6 && mentionsName(t, name) && STOP_WORDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
    }

    private val BARE_STOPS = setOf("stopp", "stop", "ruhe", "halt", "still", "reicht", "danke", "quiet")
    private val FILLERS = setOf(
        "äh", "ähm", "hm", "hmm", "mhm", "ja", "okay", "ok", "ach", "ah", "oh", "genau", "also", "na", "tja",
        "weiß", "nicht", "keine", "ahnung", "gute", "frage", "moment", "warte", "uh", "um", "well",
    )

    /** Ein bloßes "stopp" oder "danke, reicht" ohne Namen. Gilt nur, während der Assistent spricht. */
    fun isBareStop(text: String): Boolean {
        val w = words(text)
        return w.size in 1..3 && w.any { it in BARE_STOPS }
    }

    /**
     * Hat hier jemand inhaltlich etwas gesagt (mindestens zwei Wörter, die kein Füllsel sind)?
     * "Ähm, keine Ahnung" zählt nicht: Wer so reagiert, kennt die Antwort nicht und freut sich über Hilfe.
     */
    fun isRealContent(text: String): Boolean = words(text).count { it !in FILLERS } >= 2

    /** Levenshtein-Abstand: wie viele Buchstaben muss man einfügen, löschen oder tauschen. */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

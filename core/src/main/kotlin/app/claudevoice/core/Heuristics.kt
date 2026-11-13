package app.claudevoice.core

import app.claudevoice.core.HeuristicWords as Words

/**
 * Billige Vorprüfung ohne API-Aufruf. Sie entscheidet nicht, OB geantwortet wird,
 * sondern nur, wie dringend das Modell gefragt werden soll:
 *  - HOT: ausdrücklicher Wissenswunsch oder Anrede -> sofort ans starke Modell (jede Millisekunde zählt)
 *  - MAYBE: ohne Zusammenhang nicht zu entscheiden (längere Aussage, Rückversicherung, unklare Frage)
 *    -> erst das billige Vorfilter-Modell
 *  - COLD: Füllsel, Floskel, kurzer Satz ohne Wissenswunsch -> gar kein Aufruf
 *
 * Zwei Fehler sind verschieden teuer, und danach richten sich alle Regeln:
 *  - Landet eine echte Wissensfrage in COLD, sieht sie nie ein Modell. Das ist der schlimme Fehler.
 *    Deshalb gilt COLD nur, wenn etwas erkennbar leer oder persönlich ist.
 *  - Landet Geplauder in HOT, kostet das einen Aufruf, bei dem das Modell schweigt. Deshalb gilt HOT nur
 *    bei einer erkennbaren Frage nach Wissen und nicht bei jedem Satz, der mit "was" oder "ist" anfängt.
 *  - Alles dazwischen ist MAYBE.
 *
 * Die Wortlisten stehen in [HeuristicWords], die bewertete Tabelle mit mehreren hundert Beispielsätzen
 * im Test HeuristicsTableTest. Wer eine Regel ändert, sieht dort sofort, was sich verschiebt.
 */
object Heuristics {
    enum class Heat { HOT, MAYBE, COLD }

    private val WORD = Regex("[\\p{L}\\p{N}']+")

    /** Satzende mit Leerraum dahinter. "3.5" und "z.B." bleiben so in einem Stück. */
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

    /** Ab so vielen Wörtern gilt eine Äußerung als "längere Aussage" und geht mindestens in die Vorprüfung. */
    private const val LONG_ENOUGH = 6

    /** Wie viele beliebige Wörter `*` und `**` in einer Wendung überbrücken dürfen. */
    private const val GAP_SHORT = 3
    private const val GAP_LONG = 6

    /** So weit hinter "keine Ahnung" darf das Fragewort stehen ("keine Ahnung mehr, ehrlich gesagt, wie ..."). */
    private const val ASK_REACH = 5
    /** Erste Wörter einer verkürzten Anschlussfrage ("Und China?", "Oder Fahrenheit?"). */
    private val FOLLOW_UP_STARTS = setOf("und", "oder", "and", "or")

    fun words(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }.toList()

    // ==============================================================================================
    // Namen

    /** Kürzere Namen müssen wörtlich fallen: Bei "Max" wäre jede Nachsicht ein Risiko ("Marx", "mag"). */
    private const val MIN_FUZZY_NAME = 5

    /** Ab dieser Länge ist ein einzelner falscher Buchstabe kaum je ein anderes echtes Wort. */
    private const val LONG_NAME = 7

    private const val VOWELS = "aeiouäöü"

    /** Schreibweisen für den Laut am Anfang von "Jarvis", wie ihn deutsche Ohren hören: "Dschawis", "Tschawis". */
    private val J_ONSETS = listOf("dsch", "tsch", "dj")

    /**
     * Wurde der Assistent beim Namen genannt? Die Transkription verhört sich bei Namen gern. Verglichen
     * wird deshalb nicht Buchstabe für Buchstabe, sondern nach Klang (siehe [soundKey]):
     *  - "Alfret", "Allfred", "Jarwis", "Javis", "Dschawis" klingen wie der Name -> Treffer
     *  - ein einzelner anderer Selbstlaut ist erlaubt ("Jervis", "Elfred")
     *  - ein angehängtes "s" ist erlaubt ("Alfreds Meinung")
     *  - zwei Nachbarwörter dürfen zusammen den Namen ergeben ("all fred", "Jar vis")
     *
     * Ein anderer Mitlaut ist NICHT erlaubt. Früher galt "ein beliebiger Buchstabe darf abweichen";
     * damit wurde aus "Jarvik" und "Alfredo" eine Anrede.
     */
    fun mentionsName(text: String, name: String): Boolean = nameHits(words(text), name).isNotEmpty()

    /** Fällt einer der Namen, auf die der Assistent hört ("Jarvis", "Alfred")? */
    fun mentionsAny(text: String, names: List<String>): Boolean = names.any { mentionsName(text, it) }

    /** Die Stellen in [w] (Wort-Nummern), an denen der Name steht. */
    private fun nameHits(w: List<String>, name: String): Set<Int> {
        val target = name.lowercase().filter { it.isLetterOrDigit() }
        val hits = HashSet<Int>()
        if (target.isEmpty()) return hits
        val plain = w.map { it.replace("'", "") }
        for (i in plain.indices) {
            if (soundsLikeName(plain[i], target)) hits += i
            // Bei zwei zusammengesetzten Wörtern muss der Klang genau stimmen. Mit Nachsicht würde
            // aus "ja was" ein "Jarvis".
            else if (i + 1 < plain.size && sameSound(plain[i] + plain[i + 1], target)) { hits += i; hits += i + 1 }
        }
        return hits
    }

    private fun soundsLikeName(word: String, target: String): Boolean {
        if (word == target) return true
        if (target.length < MIN_FUZZY_NAME) return false
        return closeSound(word, target) || (word.endsWith("s") && closeSound(word.dropLast(1), target))
    }

    // Hier ohne die "ie"-Nachsicht: "ja wies aussieht" ergäbe sonst zusammengezogen "Jarvis".
    private fun sameSound(word: String, target: String): Boolean =
        word == target || (target.length >= MIN_FUZZY_NAME && soundKey(word, longI = false) == soundKey(target, longI = false))

    private fun closeSound(word: String, target: String): Boolean {
        val a = soundKey(word)
        val b = soundKey(target)
        if (a == b) return true
        if (a.length == b.length && b.length >= MIN_FUZZY_NAME) {
            // Genau eine Abweichung, und zwar Selbstlaut gegen Selbstlaut.
            val differing = a.indices.filter { a[it] != b[it] }
            if (differing.size == 1 && a[differing[0]] in VOWELS && b[differing[0]] in VOWELS) return true
        }
        return b.length >= LONG_NAME && editDistance(a, b) <= 1
    }

    /**
     * Schreibt ein Wort grob so um, wie es klingt. Alles, was die Spracherkennung bei Namen
     * typischerweise verwechselt, fällt dabei auf dieselbe Schreibweise:
     *  - "dsch", "tsch", "dj" und "y" am Anfang -> "j"   (Dschawis, Tschawis, Yarvis)
     *  - "w" -> "v"                                       (Jarwis)
     *  - "r" und "h" nach einem Selbstlaut fallen weg     (Javis, Jahvis; man hört sie kaum)
     *  - doppelte Buchstaben zählen einfach               (Allfred, Jarviss)
     *  - "d", "b", "g" am Wortende werden hart            (Alfret)
     *  - "ie" zählt als ein langes "i"                    (Alfried)
     */
    private fun soundKey(word: String, longI: Boolean = true): String {
        var s = word.lowercase().replace("ß", "ss").filter { it.isLetterOrDigit() }
        // "ie" ist im Deutschen ein langes i und zählt als EIN Selbstlaut: "Alfried" klingt wie "Alfrid"
        // und weicht damit nur in einem Selbstlaut von "Alfred" ab.
        if (longI) s = s.replace("ie", "i")
        val onset = J_ONSETS.firstOrNull { s.startsWith(it) }
        if (onset != null) s = "j" + s.substring(onset.length)
        else if (s.length > 1 && s[0] == 'y' && s[1] in VOWELS) s = "j" + s.substring(1)
        val out = StringBuilder()
        for (i in s.indices) {
            val c = when (s[i]) { 'w' -> 'v'; 'y' -> 'i'; 'ä' -> 'e'; else -> s[i] }
            val previous = out.lastOrNull()
            val vowelFollows = i + 1 < s.length && (s[i + 1] in VOWELS || s[i + 1] == 'y')
            if ((c == 'r' || c == 'h') && previous != null && previous in VOWELS && !vowelFollows) continue
            if (c == previous) continue
            out.append(c)
        }
        if (out.isNotEmpty()) {
            val hard = when (out.last()) { 'd' -> 't'; 'b' -> 'p'; 'g' -> 'k'; else -> out.last() }
            out.setCharAt(out.length - 1, hard)
        }
        return out.toString()
    }

    // ==============================================================================================
    // Hitze

    fun heat(text: String, name: String): Heat = heat(text, listOf(name))

    fun heat(text: String, names: List<String>): Heat {
        val all = sentences(text)
        if (all.isEmpty()) return Heat.COLD
        if (mentionsAny(text, names)) return Heat.HOT
        // Jeder Satz wird für sich geprüft; der "heißeste" gewinnt.
        var best = Heat.COLD
        var total = 0
        var substance = 0
        for (s in all) {
            // Feste Wendungen und Floskeln sind kein Wissenswunsch, auch wenn sie mit einem Fragewort anfangen.
            val core = without(s.w, Words.NO_NEED_PHRASES)
            total += s.w.size
            substance += core.count { it !in Words.EMPTY_WORDS }
            val found = sentenceHeat(s, core)
            if (found == Heat.HOT) return Heat.HOT
            if (found == Heat.MAYBE) best = Heat.MAYBE
        }
        // Längere Aussage: Darin kann ein Wissenswunsch stecken, den keine Regel erkennt. Das klärt die
        // billige Vorprüfung. Ausgenommen sind Ketten aus Floskeln ("alles klar, dann bis morgen, tschüss").
        if (best == Heat.COLD && total >= LONG_ENOUGH && substance >= 2) best = Heat.MAYBE
        return best
    }

    /** Ein Satz der Äußerung, vorbereitet für die Regeln. */
    private class Sentence(
        /** Die Wörter in einheitlicher Schreibweise (siehe [HeuristicWords.fold]). */
        val w: List<String>,
        /** Wörter, die mitten im Satz groß geschrieben waren: im Deutschen Hauptwörter und Namen. */
        val nouns: Set<String>,
        /** Stand hinter dem ersten Wort ein Komma? Dann ist es eine Anrede oder ein Einwurf ("Max, ..."). */
        val commaAfterFirst: Boolean,
        /** Endete der Satz mit einem Fragezeichen? */
        val mark: Boolean,
    )

    private fun sentences(text: String): List<Sentence> {
        val clean = text.replace('’', '\'').replace('`', '\'').replace('´', '\'')
        return clean.split(SENTENCE_END).mapNotNull { part ->
            val w = ArrayList<String>()
            val nouns = HashSet<String>()
            var comma = false
            for (m in WORD.findAll(part)) {
                val folded = Words.fold(m.value)
                if (folded.isEmpty()) continue
                // Gestottertes zählt einmal: "wie wie alt", "ist ist das giftig".
                if (w.isNotEmpty() && w.last() == folded) continue
                if (w.isEmpty()) comma = part.substring(m.range.last + 1).trimStart().startsWith(",")
                // Ganz in Großbuchstaben ("WIE VIELE") sagt nichts über die Wortart.
                else if (folded.length > 1 && m.value[0].isUpperCase() && m.value != m.value.uppercase()) nouns += folded
                w += folded
            }
            if (w.isEmpty()) null else Sentence(w, nouns, comma, '?' in part)
        }
    }

    /** Was die Form eines Satzes über ihn verrät. */
    private enum class Shape {
        /** Frage oder Bitte nach Wissen. */
        KNOWLEDGE,
        /** Könnte eine Wissensfrage sein; das zeigt erst der Zusammenhang. */
        UNCLEAR,
        /** Als Ausruf, Aussage oder persönliche Frage erkannt: kein Wissenswunsch. */
        NOT_A_NEED,
        /** Keine Form erkannt. */
        NONE,
    }

    /**
     * Die Hitze eines einzelnen Satzes. [w] ist der Satz ohne feste Wendungen. Die Reihenfolge der Prüfungen:
     *  1. nur Füllsel -> COLD
     *  2. ausdrücklicher Wissenswunsch ("weiß jemand ...", "keine Ahnung, wie ...") -> HOT
     *  3. die Form des Satzes: Fragewort vorn, Verb vorn, Bitte ("erklär mal ...")
     *  4. eine Frage mitten im Satz -> HOT
     *  5. schwächere Zeichen (Fragezeichen, "... oder", "... glaub ich", Fragewort am Satzende,
     *     Rechenaufgabe) -> meist MAYBE
     */
    private fun sentenceHeat(s: Sentence, w: List<String>): Heat {
        if (w.all { it in Words.EMPTY_WORDS }) return Heat.COLD
        val indirect = indirectQuestion(w)
        if (indirect == Heat.HOT || containsAny(w, Words.WISH_PHRASES)) return Heat.HOT

        // Vorlauf abstreifen: "Max, ...", "und ...", "ähm, sag mal ...".
        var start = if (s.commaAfterFirst && w.size > 1 && w[0] == s.w[0]) 1 else 0
        var amazed = false
        while (start < w.size - 1 && w[start] in Words.SAFE_LEAD_INS) {
            if (w[start] in Words.EXCLAIMERS) amazed = true
            start++
        }
        val body = w.subList(start, w.size)
        // Vor einem Fragewort darf zusätzlich "also", "dann", "jetzt" stehen und eine Präposition ("seit wann").
        var q = 0
        while (q < 2 && q < body.size - 1 && body[q] in Words.ADVERB_LEAD_INS) q++
        if (q + 1 < body.size && body[q] in Words.PREPOSITIONS && body[q + 1] in Words.INTERROGATIVES) q++
        val head = body[q]
        val clause = body.subList(q, body.size)

        val shape = when {
            head == "wie" && q > 0 && body[q - 1] == "so" -> Shape.NOT_A_NEED       // "so wie immer": Vergleich
            head in Words.INTERROGATIVES -> questionWordShape(clause, s)
            head in Words.IF_WORDS -> ifShape(clause, s)
            head in Words.REQUEST_IMPERATIVES -> requestShape(clause)
            else -> verbFirstShape(body, s, amazed)                                  // hier zählt kein "also" oder "dann" davor
        }
        if (shape == Shape.KNOWLEDGE) return Heat.HOT
        // Frage mitten im Satz: "Mama, was ist ein Vulkan", "na der Dings, wie heißt er denn".
        if (hasInnerQuestion(w, start + q + 1)) return Heat.HOT
        if (shape == Shape.UNCLEAR || indirect == Heat.MAYBE) return Heat.MAYBE
        if (shape == Shape.NOT_A_NEED) return Heat.COLD

        // Keine Frageform erkannt. Bleiben die schwächeren Zeichen.
        val tagAt = startOf(w, Words.TAGS)
        // Plaudern erkennt man am Du ("du kommst doch auch, oder?") und an der Wertung ("schön hier, ne?").
        val chat = w.take(5).any { it in Words.YOU_WORDS } || (tagAt >= 0 && w.any(::isEvaluative))
        if (!chat) {
            // Ein Fragezeichen der Spracherkennung zählt, aber erst ab einer gewissen Länge: "Mit Sahne?" ist
            // ein Angebot und keine Wissensfrage. Bei drei Wörtern ist es offen ("Noch ein Bier?" gegen
            // "Hauptstadt von Australien?"), also die Vorprüfung.
            if (s.mark && w.size >= 4) return Heat.HOT
            if (s.mark && w.size == 3) return Heat.MAYBE
            // Zwei-Wort-Frage aus Verb und Hauptwort: "Rostet Edelstahl?"
            if (s.mark && w.size == 2 && w[1] in s.nouns && looksFinite(w[0], s)) return Heat.MAYBE
            // Verkürzte Anschlussfrage: "Und China?" nach "Wie viele Zeitzonen hat Russland?". Ob sie dem
            // Assistenten gilt oder ein Essensvorschlag ist, zeigt nur der Zusammenhang, also die Vorprüfung.
            if (s.mark && w.size == 2 && w[0] in FOLLOW_UP_STARTS && w[1] in s.nouns) return Heat.MAYBE
            // Behauptung mit Rückversicherung: "das war doch 1989, oder". Stimmt die Behauptung nicht,
            // will er es wissen. Ob es so gemeint war, zeigt der Zusammenhang.
            if (tagAt >= 3) return Heat.MAYBE
        }
        if (isUncertain(w, body)) return Heat.MAYBE
        if (containsAny(w, Words.QUERY_PHRASES)) return Heat.MAYBE                    // "Hauptstadt von Peru"
        if (hasLooseQuestionWord(w)) return Heat.MAYBE                                // "Mauerfall wann"
        if (hasTrailingQuestionWord(w, s)) return Heat.MAYBE                          // "das bedeutet was"
        if (isArithmetic(w)) return Heat.MAYBE                                        // "zwölf mal zwölf"
        return Heat.COLD
    }

    /**
     * Indirekte Frage: Einleitung ("keine Ahnung", "ich frag mich", "kannst du mir sagen") und kurz danach
     * ein Fragewort oder "ob".
     *  - HOT: Der Nebensatz fragt nach einer Sache ("... wie das Ding heißt").
     *  - MAYBE: Er fragt nach "ihm" oder "ihr" ("... wo er ist"): Bekannter oder berühmte Person?
     *    Ebenso bei "mein/unser" ("... wo mein Schlüssel ist").
     *  - null: Es geht um die Sprechenden selbst ("... was ich anziehen soll", "... ob du kommst",
     *    "... was mir passiert ist"), oder es ist gar keine indirekte Frage.
     */
    private fun indirectQuestion(w: List<String>): Heat? {
        var found: Heat? = null
        for (i in w.indices) for (opener in Words.ASK_OPENERS) {
            val end = matchAt(w, i, opener)
            if (end < 0) continue
            // "until w.size - 1": Hinter dem Fragewort muss noch etwas kommen, sonst ist es keine Frage.
            for (j in end until minOf(end + ASK_REACH, w.size - 1)) {
                if (w[j] !in Words.INTERROGATIVES && w[j] !in Words.IF_WORDS) continue
                val after = w.subList(j + 1, minOf(j + 4, w.size))
                when {
                    after[0] in Words.SPEAKER_AND_LISTENER || after.any { it in Words.YOU_WORDS } -> {}
                    after[0] in PERSONAL_OBJECTS -> {}                                // "weißt du, was MIR passiert ist"
                    // "... wo MEIN Schlüssel ist": Das weiß der Assistent höchstens aus seinen Notizen.
                    after[0] in Words.THIRD_PERSON || after[0] in Words.MY_WORDS -> found = Heat.MAYBE
                    else -> return Heat.HOT
                }
                break
            }
        }
        return found
    }

    /**
     * Satz, der mit einem Fragewort beginnt. Im Deutschen steht bei einer echten Frage das Verb gleich
     * hinter dem Fragewort ("wie hoch IST die Zugspitze"). Steht dort stattdessen schon das Subjekt
     * ("wie hoch DIE Zugspitze ist", "was ICH sagen wollte"), ist es ein Nebensatz oder eine Redewendung.
     */
    private fun questionWordShape(c: List<String>, s: Sentence): Shape {
        val word = c[0]
        val next = c.getOrNull(1)
        // Nur das Fragewort, höchstens mit Füllsel ("warum denn"): eine Nachfrage, die ohne das Gespräch
        // davor nichts bedeutet. "Was?" und "Wie?" sind bloße Reaktionen und stehen bei den leeren Wörtern.
        if (c.drop(1).all { it in Words.EMPTY_WORDS }) {
            return when {
                word !in Words.FIRM_INTERROGATIVES -> Shape.NONE
                s.mark -> Shape.KNOWLEDGE
                else -> Shape.UNCLEAR
            }
        }
        if (next in Words.FREE_RELATIVE_NEXT) return Shape.NOT_A_NEED                 // "was ich sagen wollte", "wer nicht will"
        if (isExclamation(c, s)) return Shape.NOT_A_NEED                              // "was für ein Tag", "wie schön"
        if (isComparison(c, s)) return Shape.NOT_A_NEED                               // "wie im Film", "wie ein Profi"
        if (isAboutYou(c)) return personalShape(c)                                    // "was machst du", "wie war dein Urlaub"

        // Wo steht das Verb? "welcher der beiden ..." ist ausgenommen: Dort gehört "der" zur Frage.
        var verbAt = -1
        if (!word.startsWith("welch")) {
            for (k in 1..minOf(3, c.lastIndex)) {
                val t = c[k]
                if (looksFinite(t, s)) { verbAt = k; break }
                if (t in Words.SPEAKER_AND_LISTENER || t in Words.CLAUSE_SUBJECTS) {
                    // Das Subjekt ist erreicht. Stand davor ein Wort, das weder Maßangabe noch Füllwort noch
                    // Hauptwort ist, dann war das das Verb in einer Form, die auf keiner Liste steht:
                    // "wie RECHNE ich das aus", "wo ÜBERWINTERN die Störche". Das ist eine echte Frage.
                    val before = c[k - 1]
                    if (k >= 2 && before !in Words.NOT_THE_VERB && before !in s.nouns) { verbAt = k - 1; break }
                    // "wie viel Prozent DER Erde sind ...": Hier ist "der" kein Subjekt, sondern gehört zum Hauptwort
                    // davor. Man erkennt es daran, dass das Verb noch kommt und nicht am Satzende steht.
                    if (k >= 2 && before in s.nouns && (k + 1 until c.lastIndex).any { c[it] in Words.FINITE_VERBS }) break
                    // Sonst ist es ein Nebensatz: "wie groß du geworden bist", "wie das geht".
                    return if (t in Words.SPEAKER_AND_LISTENER) Shape.NOT_A_NEED else Shape.UNCLEAR
                }
            }
        }
        if (isPlanning(c)) return Shape.NOT_A_NEED                                    // "wann treffen wir uns"
        val subject = if (verbAt > 0) c.getOrNull(verbAt + 1) else null
        return when {
            subject in Words.KINSHIP -> Shape.NOT_A_NEED                              // "wann kommt Papa nach Hause"
            subject in Words.THIRD_PERSON -> Shape.UNCLEAR                            // "wann kommt er", "was können Sie empfehlen"
            // Frage nach sich selbst ("wo hab ich meinen Schlüssel"): Das weiß der Assistent nur aus seinen Notizen.
            subject == "ich" && c[verbAt] in Words.SELF_VERBS -> Shape.UNCLEAR
            else -> Shape.KNOWLEDGE
        }
    }

    /** "mir", "mich", "uns" gleich hinter dem Fragewort: Es geht um die Sprechenden selbst. */
    private val PERSONAL_OBJECTS = setOf("mir", "mich", "uns")

    private val EXCLAMATION_ARTICLES = setOf("ein", "eine", "einen", "n", "ne", "nen")
    private val ENGLISH_POINTERS = setOf("that", "this", "it")

    /** Ausruf, der mit einem Fragewort beginnt: "wie schön", "was für ein Tag", "what a day". */
    private fun isExclamation(c: List<String>, s: Sentence): Boolean {
        val next = c.getOrNull(1) ?: return false
        return when (c[0]) {
            "wie", "how" -> isEvaluative(next) && !asksAboutSomething(c)
            // "was für ein Tag" hat kein Verb, "was für ein Tier IST das" schon. Ohne "ein" ist es fast
            // immer eine Frage ("was für Tiere leben dort").
            "was" -> when (next) {
                "für" -> c.getOrNull(2) in EXCLAMATION_ARTICLES && c.drop(3).none { looksFinite(it, s) }
                in EXCLAMATION_ARTICLES -> c.drop(2).none { looksFinite(it, s) }
                else -> false
            }
            "what" -> (next == "a" || next == "an") && c.none { it in Words.FINITE_VERBS }
            else -> false
        }
    }

    /**
     * "wie schön ist das denn" staunt, "wie schön ist Lissabon im Winter" fragt. Der Unterschied: Hinter
     * dem Verb steht beim Staunen nur ein Zeigewort ("das", "der"), bei der Frage eine Sache.
     */
    private fun asksAboutSomething(c: List<String>): Boolean {
        if (c.size < 4 || c[2] !in Words.FINITE_VERBS) return false
        val rest = c.drop(3).filter { it !in Words.EMPTY_WORDS }
        return rest.size >= 2 || (rest.size == 1 && rest[0] !in Words.CLAUSE_SUBJECTS && rest[0] !in ENGLISH_POINTERS)
    }

    /** "wie im Film", "wie früher", "wie ein Profi": Vergleich, keine Frage. Mit Verb ist es eine ("wie ein Flugzeug fliegt"). */
    private fun isComparison(c: List<String>, s: Sentence): Boolean {
        if (c[0] != "wie") return false
        val next = c.getOrNull(1) ?: return false
        return next in Words.COMPARISON_NEXT || ((next == "ein" || next == "eine") && c.none { looksFinite(it, s) })
    }

    /** "ob ..." ohne Hauptsatz davor ist lautes Nachdenken: "ob Pinguine wohl Knie haben". */
    private fun ifShape(c: List<String>, s: Sentence): Shape {
        if (c.size < 4) return Shape.NONE
        return when (c[1]) {
            in Words.SPEAKER_AND_LISTENER -> Shape.NOT_A_NEED    // "ob ich das schaffe"
            in s.nouns -> Shape.KNOWLEDGE                         // Hauptwort: eine Sachfrage
            else -> Shape.UNCLEAR                                 // "ob das wohl klappt"
        }
    }

    /** Befehlsform am Satzanfang: "erklär mal ...", "nenn mir ...", "tell me ...". */
    private fun requestShape(c: List<String>): Shape {
        val enough = if (c[0] == "tell") c.size >= 4 && c[1] == "me" else c.size >= 2
        return when {
            !enough -> Shape.NONE
            isAboutYou(c) -> Shape.NOT_A_NEED                     // "erklär mir mal deinen Plan"
            c[1] in Words.NO_REQUEST_NEXT -> Shape.NOT_A_NEED     // "rechne nicht damit", "nenn mich nicht so"
            else -> Shape.KNOWLEDGE
        }
    }

    /**
     * Satz, der mit dem Verb beginnt. Das ist im Deutschen dreierlei:
     *  - eine Ja/Nein-Frage:            "ist ein Wal ein Fisch", "kann man das essen"
     *  - eine Aussage ohne Subjekt:     "(das) ist halt so", "(das) war ein schöner Abend", "(das) weiß ich nicht"
     *  - eine Frage an das Gegenüber:   "kommst du mit", "habt ihr Hunger"
     * Welches davon, verrät das Wort hinter dem Verb: Dort steht bei der Frage das Subjekt.
     */
    private fun verbFirstShape(body: List<String>, s: Sentence, amazed: Boolean): Shape {
        if (body.size < 3) return Shape.NONE
        val verb = body[0]
        val german = verb in Words.QUESTION_VERBS || isVerbBefore(verb, body[1]) ||
            (verb in Words.STORY_VERBS && startsSubject(body, 1, s))                  // "wurde Kennedy erschossen"
        val english = verb in Words.ENGLISH_AUXILIARIES
        // Verb, das auf keiner Liste steht: Die Form reicht für die billige Vorprüfung, nicht für HOT.
        if (!german && !english) return if (isUnlistedVerbFirst(body, s)) Shape.UNCLEAR else Shape.NONE
        // Zwei gebeugte Verben hintereinander: Das erste Wort war ein Hauptwort ("Essen ist fertig").
        // Ist das zweite groß geschrieben, ist ES das Hauptwort ("ist Schwimmen gesund"), und es bleibt eine Frage.
        // "ist", "sind", "war" sind selbst nie Hauptwörter: Dort bleibt es auch klein geschrieben eine Frage.
        if (body[1] in Words.FINITE_VERBS && body[1] !in s.nouns && verb !in Words.COPULAS) return Shape.NONE
        if (isAboutYou(body)) return personalShape(body)                              // "kommst du mit", "ist das dein Ernst"
        if (!german) return englishVerbFirstShape(body)
        if (isShortStatement(body)) return Shape.NOT_A_NEED                           // "kann man machen", "gibt es nicht"

        // Das Subjekt suchen. Kleine Wörter davor zählen nicht: "ist EIGENTLICH ein Wal ...", "kann MIR jemand ...".
        var k = 1
        var skippedMe = false
        var otherPartSeen = false
        var leansToStatement = false
        while (k < body.lastIndex) {
            val t = body[k]
            if (t in Words.ME_OBJECTS) skippedMe = true
            else if (t in Words.TRANSPARENT_ADVERBS) otherPartSeen = true
            else if (t in Words.STATEMENT_LEANING) leansToStatement = true
            else if (t !in Words.TRANSPARENT_PARTICLES) break
            k++
        }
        val subject = body[k]
        val after = body.getOrNull(k + 1)
        // Bitte um Erklärung an irgendwen: "kann mir das jemand erklären", "können Sie das übersetzen".
        if (verb in Words.MODALS && subject !in Words.FIRST_PERSON && hasRequestStem(body)) return Shape.KNOWLEDGE
        return when {
            // "weiß ich nicht", "hab ich schon": Aussage. Nur mit "kann/darf/muss/soll" ist es eine Frage,
            // und die kann nach Wissen fragen ("darf ich hier parken") oder nicht ("darf ich mal vorbei").
            subject in Words.FIRST_PERSON ->
                if (verb in Words.MODALS && body.size >= 4 && after !in Words.STATEMENT_NEXT && after !in Words.ME_OBJECTS) Shape.UNCLEAR
                else Shape.NOT_A_NEED
            subject in Words.GENERAL_SUBJECTS -> generalSubjectShape(body, subject, after, amazed)
            // Frage an die Runde: "weiß jemand ..." will Wissen, "hat jemand einen Stift" eher nicht.
            subject in Words.SOMEONE -> if (verb == "weiss" || verb == "kennt") Shape.KNOWLEDGE else Shape.UNCLEAR
            subject in Words.THIRD_PERSON -> Shape.UNCLEAR                            // "hat er angerufen" oder "war er Physiker"?
            subject in Words.ARTICLES -> nounPhraseShape(verb, body.subList(k + 1, body.size), otherPartSeen, s)
            subject in Words.KINSHIP -> Shape.NOT_A_NEED                              // "hat Mama angerufen"
            subject in Words.MY_WORDS -> Shape.UNCLEAR                                // "ist mein Puls zu hoch" oder "war meine Idee"?
            subject in Words.STATEMENT_NEXT || isEvaluative(subject) -> Shape.NOT_A_NEED   // "ist halt so", "war lecker"
            subject in Words.STATEMENT_NOUNS && body.last().startsWith("ge") -> Shape.NOT_A_NEED   // "hat Spaß gemacht"
            subject in s.nouns -> Shape.KNOWLEDGE                                     // "sind Tomaten Obst", "hat Finnland ..."
            // "hat mich gefreut", "geht mir genauso", "ist schon fertig", "wird schon schiefgehen"
            skippedMe || leansToStatement -> Shape.NOT_A_NEED
            // Unbekanntes Wort: klein geschriebenes Hauptwort ("leben eisbären ...") oder der Rest einer
            // Aussage ("hat nicht geklappt")? Im Zweifel die billige Vorprüfung.
            else -> Shape.UNCLEAR
        }
    }

    /**
     * Verb der dritten Person, das auf keiner Liste steht, und gleich dahinter das Subjekt:
     * "enthält Cola Koffein", "fließt der Rhein durch Köln", "dreht sich die Erde schneller".
     * Erkennen lässt es sich nur an der Endung "t" und am Hauptwort oder Artikel dahinter. Das ist
     * unsicher ("Robert Koch war Arzt" fängt genauso an), deshalb reicht es nur für die Vorprüfung.
     * Ohne diese Regel wären kurze Fragen mit seltenen Verben COLD, also für immer verloren.
     * Verben der Mehrzahl ("quaken Frösche nachts") erkennt sie nicht: Auf "en" enden zu viele andere
     * Wörter ("Guten Morgen Lisa"). Die häufigsten stehen deshalb einzeln in der Liste.
     */
    private fun isUnlistedVerbFirst(body: List<String>, s: Sentence): Boolean {
        val verb = body[0]
        if (verb.length < 4 || !verb.endsWith("t")) return false
        if (verb in Words.NOT_THE_VERB || verb in Words.NOT_A_VERB || verb in Words.T_BUT_NO_VERB) return false
        val subjectAt = if (body[1] in Words.ME_OBJECTS) 2 else 1                     // "dreht SICH die Erde"
        return startsSubject(body, subjectAt, s)
    }

    /** Beginnt an Stelle [k] das Subjekt, also ein Hauptwort oder ein Artikel mit etwas dahinter? */
    private fun startsSubject(body: List<String>, k: Int, s: Sentence): Boolean {
        val t = body.getOrNull(k) ?: return false
        return t in s.nouns || (t in Words.ARTICLES && k + 1 < body.size)
    }

    /**
     * Ein Wort vor "du", "ihr" oder "man" ist fast immer das Verb einer Frage, auch wenn es in keiner
     * Liste steht: "kommst du", "habt ihr", "schreibt man". Die Endung muss dazu passen.
     */
    private fun isVerbBefore(verb: String, second: String): Boolean {
        if (verb in Words.NOT_A_VERB || verb in Words.EMPTY_WORDS) return false
        return when (second) {
            "du" -> verb.endsWith("st")
            "ihr", "man" -> verb.endsWith("t")
            else -> false
        }
    }

    /**
     * Kurze Sätze, die mit dem Verb anfangen und so enden, sind Redensarten ohne Subjekt:
     * "kann man machen", "gibt es nicht", "weiß ich nicht", "kann man so sagen".
     * Bei vier Wörtern muss das dritte dazu passen, sonst träfe es "kann man das sehen".
     */
    private fun isShortStatement(body: List<String>): Boolean {
        if (body.last() !in Words.SHORT_STATEMENT_ENDINGS) return false
        return body.size == 3 || (body.size == 4 && body[2] in Words.STATEMENT_NEXT)
    }

    /** "kann man ...", "gibt es ...", "ist das ...": allgemeine Frage, wenn es kein Ausruf und keine Zustimmung ist. */
    private fun generalSubjectShape(body: List<String>, subject: String, after: String?, amazed: Boolean): Shape {
        // "Stimmt, das ist ein guter Punkt": Zustimmung und danach ein neuer Satz.
        if (body[0] == "stimmt" && after in Words.FINITE_VERBS) return Shape.NOT_A_NEED
        if (subject == "das" || subject == "es") {
            // "boah, ist das laut hier", "ist das schön", "ist das okay": Staunen oder Meinung.
            if (amazed || (after != null && (isEvaluative(after) || after in Words.OPINION_WORDS))) return Shape.NOT_A_NEED
        }
        return Shape.KNOWLEDGE
    }

    /**
     * Verb und dann ein Artikel. Bei "sein" entscheidet die Zahl der Satzglieder dahinter:
     * "ist ein Wal ein Fisch" hat zwei und ist eine Frage, "war ein schöner Abend" hat eines und ist eine
     * Aussage ohne "das". [rest] ist alles hinter dem Artikel.
     */
    private fun nounPhraseShape(verb: String, rest: List<String>, otherPartSeen: Boolean, s: Sentence): Shape {
        // "ist MORGEN ein Feiertag": Die Zeitangabe ist schon das zweite Satzglied.
        if (otherPartSeen || verb == "stimmt") return Shape.KNOWLEDGE
        if (rest.isEmpty() || rest[0] in Words.QUANTITY_NEXT) return Shape.NOT_A_NEED     // "hat ein bisschen gedauert"
        // Ein einzelnes klein geschriebenes Wort hinter "der/die": dann war "der" kein Artikel, sondern
        // meinte jemanden oder etwas ("ist der giftig", "hat der gesagt"). Das klärt nur der Zusammenhang.
        if (rest.size == 1 && rest[0] !in s.nouns) return Shape.UNCLEAR
        if (verb !in Words.COPULAS) return Shape.KNOWLEDGE                            // "schmilzt ein Diamant", "hat ein Regenwurm Augen"
        if (rest.size == 1) return Shape.NOT_A_NEED                                   // "ist der Hammer", "war ein Scherz"
        if (rest.size == 2) {
            // "ist der Mond rund" gegen "war ein schöner Abend": Im ersten Fall folgt dem Artikel das
            // Hauptwort (groß geschrieben), im zweiten ein Eigenschaftswort. Ohne Großschreibung ist das offen.
            if (s.nouns.isEmpty()) return Shape.UNCLEAR
            if (rest[0] !in s.nouns) return Shape.NOT_A_NEED
        }
        return Shape.KNOWLEDGE
    }

    private fun englishVerbFirstShape(body: List<String>): Shape {
        val second = body[1]
        return when {
            // Befehle, keine Fragen: "do not ...", "have me ...", "do it".
            second == "not" || second == "me" || second == "us" || (body[0] == "do" && second == "it") -> Shape.NONE
            second in Words.FIRST_PERSON -> if (body.size >= 4) Shape.UNCLEAR else Shape.NOT_A_NEED    // "can I ...", "should we ..."
            else -> Shape.KNOWLEDGE
        }
    }

    private val ASKED_PERSON = setOf("du", "ihr", "you")
    private val REFLEXIVE = setOf("dich", "euch")

    /**
     * Frage an das Gegenüber nach ihm selbst. Ausnahmen: Es wird um eine Erklärung gebeten
     * ("kannst du das erklären") oder nach etwas gefragt, das auch Wissen sein kann ("kennst du die Formel").
     */
    private fun personalShape(c: List<String>): Shape = when {
        hasRequestStem(c) && c.any { it in ASKED_PERSON } -> Shape.KNOWLEDGE
        // "kennt ihr euch", "kennst du dich aus" fragen nach den Leuten, nicht nach Wissen.
        c.take(4).any { it in Words.SCHOOL_VERBS } && c.none { it in REFLEXIVE } -> Shape.UNCLEAR
        else -> Shape.NOT_A_NEED
    }

    /** Kommt früh im Satz ein Du-Wort vor ("du", "dir", "dein", "euch")? Das erste Wort selbst zählt nicht. */
    private fun isAboutYou(c: List<String>): Boolean = c.subList(1, minOf(6, c.size)).any { it in Words.YOU_WORDS }

    /** "wann treffen wir uns", "was essen wir heute", "wann müssen wir los": gemeinsame Pläne, kein Wissen. */
    private fun isPlanning(c: List<String>): Boolean {
        val word = c[0]
        if (word !in Words.PLANNING_QUESTION_WORDS) return false
        return (1..3).any { k ->
            val verb = c.getOrNull(k)
            c.getOrNull(k + 1) == "wir" && (verb in Words.PLANNING_VERBS || (word != "was" && verb in Words.PLANNING_MODALS))
        }
    }

    private fun hasRequestStem(c: List<String>): Boolean = c.any { t -> Words.REQUEST_STEMS.any(t::startsWith) }

    /**
     * Sieht das Wort wie ein gebeugtes Verb aus? Entweder steht es in der Liste, oder es endet auf "t"
     * ("berechnet", "brütet") und ist weder Hauptwort noch Füllwort. Die Endung allein irrt sich manchmal
     * ("weit"); das schadet nicht, weil der Irrtum nur Richtung HOT geht.
     */
    private fun looksFinite(t: String, s: Sentence): Boolean =
        t in Words.FINITE_VERBS || (t.length >= 4 && t.endsWith("t") && t !in s.nouns && t !in Words.NOT_THE_VERB)

    /** "schön", "schönes", "leckere": wertendes Eigenschaftswort in jeder Beugung. */
    private fun isEvaluative(t: String): Boolean =
        t in Words.EVALUATIVE || ADJECTIVE_ENDINGS.any { t.length > it.length + 2 && t.endsWith(it) && t.dropLast(it.length) in Words.EVALUATIVE }

    private val ADJECTIVE_ENDINGS = listOf("e", "er", "es", "en", "em")

    /**
     * Direkte Frage mitten im Satz: Fragewort und gleich danach ein Verb ("... was ist ein Vulkan").
     * Folgt dem Fragewort erst das Subjekt ("ich weiß, wie das geht") oder steht das Verb ganz am Ende
     * ("ich wüsste gern, was da drin ist"), ist es ein Nebensatz.
     */
    private fun hasInnerQuestion(w: List<String>, from: Int): Boolean {
        for (i in maxOf(from, 1) until w.size - 1) {
            val word = w[i]
            if (word !in Words.INTERROGATIVES) continue
            val before = w[i - 1]
            if (before in Words.RELATIVE_BEFORE) continue                             // "alles was geht"
            // "I was thinking" (englisches Verb) und "noch was trinken" (etwas)
            if (word == "was" && (before in Words.ENGLISH_SUBJECTS || before in Words.SOMETHING_BEFORE)) continue
            val reach = if (word == "was" && w[i + 1] == "für") 4 else 3
            for (k in i + 1..minOf(i + reach, w.lastIndex - 1)) {
                val t = w[k]
                // "... was mir gestern passiert ist": Nebensatz über die Sprechenden selbst.
                if (t in Words.CLAUSE_SUBJECTS || t in Words.SPEAKER_AND_LISTENER || t in PERSONAL_OBJECTS) break
                if (t in Words.FINITE_VERBS) {
                    val after = w.subList(k + 1, minOf(k + 4, w.size))
                    // "... was machst du", "... wann kommt Papa": persönlich.
                    if (after.none { it in Words.YOU_WORDS } && after.firstOrNull() !in Words.KINSHIP) return true
                    break
                }
            }
        }
        return false
    }

    /** Hörbare Unsicherheit bei einer Angabe: "das war 1989, glaub ich", "ich glaub, das war Edison". */
    private fun isUncertain(w: List<String>, body: List<String>): Boolean {
        if (startOf(w, Words.UNCERTAIN_TAILS) >= 3) return true
        for (opener in Words.UNCERTAIN_OPENERS) {
            val end = matchAt(body, 0, opener)
            // "ich glaub, ich geh jetzt" ist ein Plan und keine unsichere Angabe.
            if (end > 0 && body.size - end >= 3 && body[end] !in Words.SPEAKER_AND_LISTENER) return true
        }
        return false
    }

    /**
     * Ein eindeutiges Fragewort irgendwo im Satz ("die Party ist wann", "mal sehen wer kommt").
     * Zu wenig für HOT, aber Grund genug, den Satz nicht ungeprüft fallen zu lassen.
     */
    private fun hasLooseQuestionWord(w: List<String>): Boolean {
        // Zwei Wörter reichen nur mit einem Stichwort davor ("Mauerfall wann"), nicht bei "sonst wer".
        if (w.size < 3 && (w.size < 2 || w[0] in Words.NOT_THE_VERB)) return false
        return (1 until w.size).any {
            w[it] in Words.FIRM_INTERROGATIVES && w.getOrNull(it + 1) !in Words.SPEAKER_AND_LISTENER &&
                w[it - 1] !in Words.RELATIVE_BEFORE                                   // "egal wann"
        }
    }

    /**
     * "was", "wie", "wo" oder "wie viel" am Satzende, wie man im Gespräch nachhakt: "das bedeutet was",
     * "der hieß wie nochmal", "die Party ist wo", "Bundesländer wie viele". Als Fragewort zählen sie nur
     * hinter einem passenden Wort, denn "da ist was" heißt "etwas" und "ich weiß wie" ist keine Frage.
     */
    private fun hasTrailingQuestionWord(w: List<String>, s: Sentence): Boolean {
        var end = w.size
        while (end > 2 && w[end - 1] in Words.TRAILING_SOFTENERS) end--
        // "wie viele Nullen" am Ende zählt wie "wie viele": "Milliarde wie viele Nullen".
        if (end >= 4 && w[end - 3] == "wie" && w[end - 2].startsWith("viel")) end--
        if (end < 2) return false
        val last = w[end - 1]
        val before = w[end - 2]
        return when (last) {
            "was" -> before in Words.MEANING_VERBS || (before in Words.PREPOSITIONS && end >= 3)   // "besteht aus was"
            "wie", "wo" -> before !in Words.KNOWING_VERBS && (before == "man" || looksFinite(before, s))
            "viel", "viele" -> before == "wie" && end >= 3
            else -> false
        }
    }

    /**
     * Rechenaufgabe ohne Verb, wie man sie vor sich hin murmelt: "zwölf mal zwölf", "144 geteilt durch 12",
     * "15 Prozent von 80". Verlangt sind Zahl, Rechenzeichen, Zahl; "ich war zwei mal da" passt nicht.
     */
    private fun isArithmetic(w: List<String>): Boolean {
        for (i in 1 until w.size - 1) {
            val left = when {
                w[i] in Words.OPERATORS -> if (w[i - 1] == "geteilt") w.getOrNull(i - 2) else w[i - 1]
                w[i] == "von" && w[i - 1] == "prozent" -> w.getOrNull(i - 2)
                else -> null
            }
            if (left != null && isNumber(left) && isNumber(w[i + 1])) return true
        }
        return false
    }

    private fun isNumber(t: String): Boolean =
        t.all { it.isDigit() } || t in Words.NUMBER_WORDS || Words.NUMBER_ENDINGS.any { t.length > it.length && t.endsWith(it) }

    // ==============================================================================================
    // Wendungen suchen

    /**
     * Passt die Wendung ab Stelle [start]? Liefert die Stelle hinter dem letzten Wort, sonst -1.
     * Sonderzeichen in Wendungen: siehe [HeuristicWords].
     */
    private fun matchAt(w: List<String>, start: Int, phrase: List<String>): Int {
        var i = start
        var slack = 0
        for (part in phrase) {
            when (part) {
                "*" -> { slack = GAP_SHORT; continue }
                "**" -> { slack = GAP_LONG; continue }
                "$" -> return if (i == w.size) i else -1
            }
            var found = -1
            var j = i
            while (j <= i + slack && j < w.size) {
                if (w[j] == part) { found = j; break }
                j++
            }
            if (found < 0) return -1
            i = found + 1
            slack = 0
        }
        return i
    }

    private fun containsAny(w: List<String>, phrases: List<List<String>>): Boolean = startOf(w, phrases) >= 0

    /** Die erste Stelle, an der eine der Wendungen beginnt, sonst -1. */
    private fun startOf(w: List<String>, phrases: List<List<String>>): Int {
        for (i in w.indices) if (phrases.any { matchAt(w, i, it) >= 0 }) return i
        return -1
    }

    /** Der Satz ohne die gefundenen Wendungen. */
    private fun without(w: List<String>, phrases: List<List<String>>): List<String> {
        val out = ArrayList<String>(w.size)
        var i = 0
        while (i < w.size) {
            val end = phrases.firstNotNullOfOrNull { p -> matchAt(w, i, p).takeIf { it > i } }
            if (end != null) i = end else out += w[i++]
        }
        return out
    }

    // ==============================================================================================
    // Stopp

    /** Längere Sätze sind kein Zuruf mehr, auch wenn "stopp" darin vorkommt. */
    private const val MAX_STOP_COMMAND_WORDS = 7
    private const val MAX_BARE_STOP_WORDS = 3

    /** So viele Wörter dürfen neben Name, Stoppwort und Beiwerk ("bitte", "jetzt") noch stehen. */
    private const val STOP_SLACK = 2

    /** "Alfred, stopp" und Verwandte: wird sofort lokal ausgeführt, ohne Umweg über das Modell. */
    fun isStopCommand(text: String, name: String): Boolean = isStopCommand(text, listOf(name))

    /**
     * Zuruf mit Namen, der die Sprachausgabe abbricht. Ein Stoppwort allein reicht nicht:
     *  - "Jarvis, was heißt quiet auf Deutsch" ist eine Frage ÜBER das Wort,
     *  - "Alfred, das ist halt so" benutzt "halt" als Füllwort,
     *  - "Alfred, sei fünf Minuten still" ist eine Bitte um Stummschaltung; die regelt das Modell.
     * Diese drei Fälle gingen früher als Stoppbefehl durch und kamen nie beim Modell an.
     */
    fun isStopCommand(text: String, names: List<String>): Boolean {
        val raw = words(text)
        if (raw.size > MAX_STOP_COMMAND_WORDS) return false
        val named = HashSet<Int>()
        for (name in names) named += nameHits(raw, name)
        if (named.isEmpty()) return false
        val rest = raw.filterIndexed { i, _ -> i !in named }.map(Words::fold).filter { it.isNotEmpty() }
        return isStopPhrase(rest, withThanks = false)
    }

    /** Ein bloßes "stopp" oder "danke, reicht" ohne Namen. Gilt nur, während der Assistent spricht. */
    /** So viele Wörter darf ein bloßer Dank samt Namen haben ("Vielen Dank dir, Alfred"). */
    private const val MAX_THANKS_WORDS = 5

    /**
     * Ist das nur ein Dank oder eine Bestätigung an den Assistenten ("Danke, Alfred.", "Alles klar, Jarvis",
     * "Ok Alfred")? Gebraucht wird das, solange Ruhe gilt (Pipeline.onCommitted): Wer um zehn Minuten Ruhe
     * bittet, "Sehr wohl, Sir." hört und "Danke, Alfred." sagt, will die Ruhe nicht gleich wieder aufheben.
     * Genau das geschah früher, weil jede Anrede beim Namen die Ruhe beendet. Steht neben Dank und Namen noch
     * irgendetwas anderes ("Danke Alfred, und wie spät ist es?"), ist es kein bloßer Dank.
     */
    fun isMereThanks(text: String, names: List<String>): Boolean {
        val raw = words(text)
        if (raw.size > MAX_THANKS_WORDS) return false
        val named = HashSet<Int>()
        for (name in names) named += nameHits(raw, name)
        if (named.isEmpty()) return false
        val rest = raw.filterIndexed { i, _ -> i !in named }.map(Words::fold).filter { it.isNotEmpty() }
        return rest.any { it in Words.THANKS } && rest.all { it in Words.THANKS || it in Words.THANKS_FILLERS }
    }

    fun isBareStop(text: String): Boolean {
        val w = words(text).map(Words::fold).filter { it.isNotEmpty() }
        return w.size in 1..MAX_BARE_STOP_WORDS && isStopPhrase(w, withThanks = true)
    }

    /**
     * Ist das (ohne den Namen) ein Stoppbefehl? [withThanks]: Während der Assistent spricht, heißt auch
     * ein bloßes "danke" so viel wie "genug". Mit Namen ("danke, Alfred") ist es dagegen Höflichkeit.
     */
    private fun isStopPhrase(w: List<String>, withThanks: Boolean): Boolean {
        if (w.any { it in Words.NEGATIONS || it in Words.DURATION_WORDS }) return false   // "nicht aufhören", "fünf Minuten still"
        val used = HashSet<Int>()
        for (i in w.indices) {
            if (w[i] in Words.STOP_WORDS || (withThanks && w[i] == "danke")) used += i
            val end = Words.STOP_PHRASES.firstNotNullOfOrNull { p -> matchAt(w, i, p).takeIf { it > i } }
            if (end != null) used += i until end
        }
        // "halt" ist meist ein Füllwort ("ist halt so"). Als Befehl steht es vorn: "halt!", "halt mal".
        val firstReal = w.indexOfFirst { it !in Words.STOP_FILLERS }
        if (firstReal >= 0 && w[firstReal] == "halt") used += firstReal
        // Verb vorn und gleich dahinter das Subjekt: eine Frage an den Assistenten, kein Zuruf.
        // "ist das genug", "war es zu leise", "reicht das Geld", "sind zwei genug".
        // "das reicht", "reicht schon" und "ist gut, danke" bleiben Befehle.
        if (firstReal >= 0 && firstReal + 1 < w.size && firstReal + 1 !in used) {
            val lead = w[firstReal]
            val next = w[firstReal + 1]
            val subjectFollows = next in Words.GENERAL_SUBJECTS || next in Words.ARTICLES || next in Words.MY_WORDS || isNumber(next)
            if ((lead in Words.COPULAS || lead == "reicht") && subjectFollows) return false
        }
        val first = used.minOrNull() ?: return false
        // Fragewort vor dem Stoppwort: Dann wird nach dem Wort gefragt ("wie leise ist ein Flüstern").
        if (w.subList(0, first).any { it in Words.INTERROGATIVES }) return false
        val others = w.indices.count { it !in used && w[it] !in Words.STOP_FILLERS }
        return others <= STOP_SLACK
    }

    // ==============================================================================================
    // Echter Inhalt

    /** So viele Wörter darf jemand an "keine Ahnung" anhängen, ohne dass es schon als Antwort zählt. */
    private const val CLUELESS_SLACK = 3

    /**
     * Hat hier jemand inhaltlich etwas gesagt (mindestens zwei Wörter, die kein Füllsel sind)?
     * "Ähm, keine Ahnung" zählt nicht: Wer so reagiert, kennt die Antwort nicht und freut sich über Hilfe.
     * Das gilt auch für längere Formen der Ratlosigkeit ("das weiß ich auch nicht", "da bin ich überfragt").
     * Hängt jemand aber eine richtige Antwort an ("keine Ahnung, ich glaub, das waren so dreitausend Meter"),
     * zählt sie.
     */
    fun isRealContent(text: String): Boolean {
        val w = words(text).map(Words::fold).filter { it.isNotEmpty() }
        val rest = without(w, Words.CLUELESS_PHRASES)
        val content = rest.count { it !in Words.FILLERS }
        return if (rest.size < w.size) content > CLUELESS_SLACK else content >= 2
    }

    /**
     * Sagt die Äußerung für sich genommen etwas (mindestens zwei Wörter, die kein Füllsel sind)?
     *
     * Der Unterschied zu [isRealContent] ist die Ratlosigkeit. "Keine Ahnung, wie das heißt" ist als Reaktion
     * auf eine Frage keine Antwort, darum zählt es dort nicht. Für sich genommen ist derselbe Satz aber ein
     * Wissenswunsch und kein leerer Einwurf wie "Warum?". Wer wissen will, ob eine Äußerung ein eigenes
     * Anliegen trägt, fragt deshalb hier. Was für [isRealContent] Inhalt ist, sagt auch hier etwas.
     */
    fun saysSomething(text: String): Boolean =
        words(text).map(Words::fold).count { it.isNotEmpty() && it !in Words.FILLERS } >= 2

    // ==============================================================================================
    // Nachsatz, Korrektur, Bitte um Ruhe
    //
    // Drei Fragen, die nicht zur Hitze gehören: Die Pipeline stellt sie nur in einer bestimmten Lage (die
    // eigene Frage des Trägers wartet noch auf ihre Antwort, oder er hat eben um Ruhe gebeten). Für sich
    // genommen sagen diese Sätze dem Vorfilter nichts, und so soll es bleiben.

    /** Länger ist kein verkürzter Nachsatz mehr, sondern ein eigener Satz. */
    private const val MAX_FOLLOW_UP_WORDS = 5
    private const val MAX_SHORT_QUESTION_WORDS = 4

    /** So viele Wörter dürfen vor "ich meine" stehen ("ach nee, sorry, ich meine ..."). */
    private const val MAX_CORRECTION_LEAD = 3

    /** So viele Wörter dürfen hinter "ich meine" stehen ("den Watzmann, nicht die Zugspitze"). Mehr ist ein eigener Satz. */
    private const val MAX_MEANT_WORDS = 6

    /** "Alfred, stumm bitte": So kurz darf ein Satz sein, in dem "stumm" ohne Verb als Bitte um Ruhe gilt. */
    private const val MAX_BARE_MUTE_WORDS = 4

    /**
     * Sieht die Äußerung nach einer verkürzten Anschlussfrage aus? Zwei Formen:
     *  - Sie beginnt mit "und" oder "oder" und ist kurz: "Und China?", "und in Fuß", "Oder Fahrenheit".
     *  - Sie ist eine Frage aus höchstens vier Wörtern: "China auch?", "Wirklich so viele?".
     *
     * Anders als die Regel in [sentenceHeat] verlangt das weder ein Fragezeichen noch ein großgeschriebenes
     * Hauptwort hinter "und": Die Transkription liefert beides nicht verlässlich ("und china", "Und China.").
     * Dafür gilt es nur im Zusammenhang, direkt hinter der eigenen Frage. Ohne ihn ist "und du" Geplauder.
     */
    fun isShortFollowUp(text: String): Boolean {
        val w = words(text).map(Words::fold).filter { it.isNotEmpty() }
        if (w.size < 2) return false
        // Wer "du", "dir" oder "bitte" sagt, redet mit einem Menschen am Tisch ("Und du so?", "Schmeckt's dir?",
        // "Und für mich bitte auch."). Den Assistenten spricht der Träger so nicht an: Hinter seiner Frage ist das
        // Tischgespräch und keine Anschlussfrage. Dasselbe gilt für "Und dann?" und "Oder was?", in denen gar
        // nichts steht, wonach gefragt sein könnte.
        if (w.any { it in Words.TO_A_PERSON }) return false
        if (w[0] in FOLLOW_UP_STARTS && w.drop(1).all { it in Words.NO_FOLLOW_UP_SUBJECT }) return false
        return (w[0] in FOLLOW_UP_STARTS && w.size <= MAX_FOLLOW_UP_WORDS) || ('?' in text && w.size <= MAX_SHORT_QUESTION_WORDS)
    }

    /**
     * Verbessert sich hier jemand ausdrücklich? "Nein, ich meine den Watzmann.", "Ach nee, ich mein den
     * Watzmann.", "Sorry, ich meinte natürlich Canberra.", "Ich meinte Österreich, nicht Australien."
     *
     * Erkannt wird nur die Form mit "ich meine" am Anfang (höchstens ein "nein", "ach nee" oder "sorry"
     * davor) und dahinter dem, was gemeint ist. Ein bloßes "Nein, den Watzmann." gehört bewusst nicht dazu:
     * Mit "nein" beginnt auch, was jemand dem Kellner antwortet ("Nein, danke."), und eine Korrektur löst
     * die wartende Frage ab. Irrtum also lieber so: Die Korrektur wird übersehen und die alte Frage
     * beantwortet, als dass ein Satz an jemand anderen dem Träger seine Antwort nimmt.
     *
     * "Ich mein(e)" ist im Deutschen aber vor allem eine Redewendung: "Ich mein ja nur.", "Ich meine, das ist
     * doch klar.", "Nein, ich meine das ernst.", "Ich meinte dich, nicht ihn." Das alles sagt man zum
     * Tischnachbarn, und nichts davon verbessert eine Frage. Früher galt jeder dieser Sätze als Korrektur und
     * nahm dem Träger die Antwort weg. Deshalb muss hinter "ich meine" eine kurze Sache stehen ("den
     * Watzmann", "natürlich Canberra", "Österreich, nicht Australien"): wenige Wörter, und keines davon ein
     * Wort, das einen ganzen Satz oder eine Person ankündigt ([Words.NOT_WHAT_IS_MEANT]).
     */
    fun isCorrection(text: String): Boolean {
        val w = words(text).map(Words::fold).filter { it.isNotEmpty() }
        var i = 0
        while (i < w.size && i < MAX_CORRECTION_LEAD && w[i] in Words.CORRECTION_LEAD_INS) i++
        if (w.getOrNull(i) != "ich" || w.getOrNull(i + 1) !in Words.MEANT) return false
        val rest = w.drop(i + 2)
        if (rest.size > MAX_MEANT_WORDS || rest.any { it in Words.NOT_WHAT_IS_MEANT }) return false
        // Es muss auch dastehen, was gemeint ist: "ich mein ja" verbessert nichts.
        return rest.any { it !in Words.FILLERS && it !in Words.EMPTY_WORDS }
    }

    /**
     * Bittet die Äußerung um Ruhe ("sei bitte zehn Minuten ruhig", "Ruhe bis morgen", "schalte dich stumm")?
     * Ob wirklich stumm geschaltet wird, entscheidet das Modell mit seiner Sonderzeile. Die Pipeline will nur
     * wissen, ob sie auf diese Sonderzeile warten soll (Pipeline.consume).
     *
     * Früher genügte ein einzelnes Wort wie "leise", "ruhig" oder "Ruhe". Damit galt als Bitte um Ruhe, was
     * gar keine ist: "wie leise ist ein Flüstern", "erklär mir das ruhig Schritt für Schritt", "erklär mir in
     * Ruhe ...", "I think you're on mute". Ausgerechnet nach solchen Bitten um eine lange Erklärung blieb die
     * nächste Frage dann ohne Antwort. Jetzt braucht es eine von drei Formen:
     *  1. die Aufforderung selbst: "sei still", "halt die Klappe", "sei bitte mal ruhig" (auch mit Wörtern
     *     dazwischen: "sei erstmal still"), nicht verneint ("du musst nicht leise sein");
     *  2. ein Wort für Ruhe zusammen mit einer Dauer: "zwei Stunden Ruhe", "Pause für zwanzig Minuten",
     *     "Sendepause bis heute Abend", "sag eine Weile nichts mehr". "In Ruhe" ist dabei kein Wort für Ruhe;
     *  3. stumm schalten: "schalte dich stumm", "geh auf lautlos", oder knapp "Alfred, stumm bitte".
     * Was mit einem Fragewort beginnt ("wann ist Ruhe im Haus", "wie lange dauert die Pause"), ist nie eine
     * Bitte um Ruhe. Die höfliche Frageform ("kannst du mal zehn Minuten still sein?") ist dagegen eine.
     *
     * Was dabei übersehen wird, kostet wenig: Die Ruhe gilt dann erst, wenn die Sonderzeile eintrifft.
     */
    fun asksForQuiet(text: String): Boolean {
        val w = words(text).map(Words::fold).filter { it.isNotEmpty() }
        if (w.take(3).any { it in Words.QUIET_QUESTION_STARTS }) return false
        val negated = w.any { it in Words.NEGATIONS }
        // 1. Die Aufforderung
        if (!negated) {
            if (w.indices.any { i -> Words.STOP_PHRASES.any { matchAt(w, i, it) > i } }) return true
            if (w.any { it in Words.QUIET_IMPERATIVES } && w.any { it in Words.QUIET_ADJECTIVES }) return true
        }
        // 2. Ruhe für eine Dauer. Hier stört eine Verneinung nicht: "bitte nicht stören bis drei".
        if (w.any { it in Words.DURATION_WORDS }) {
            val calm = w.withIndex().any { (i, word) -> word in Words.QUIET_NOUNS && !(word == "ruhe" && w.getOrNull(i - 1) == "in") }
            if (calm || Words.QUIET_PAIRS.any { (a, b) -> a.any { it in w } && b in w }) return true
        }
        // 3. Stumm schalten
        if (!negated && w.any { it in Words.MUTE_WORDS }) {
            return w.any { it in Words.MUTE_VERBS } || w.size <= MAX_BARE_MUTE_WORDS
        }
        return false
    }

    // ==============================================================================================

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

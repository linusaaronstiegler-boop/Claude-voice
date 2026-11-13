package app.claudevoice.core

/**
 * Die Wortlisten des Vorfilters ([Heuristics]). Die Regeln selbst stehen dort; hier steht nur,
 * WELCHE Wörter und Wendungen sie kennen.
 *
 * Warum Listen und kein Sprachmodell? Der Vorfilter läuft bei jedem Zwischenstand der Transkription,
 * also mehrmals pro Sekunde, und darf nichts kosten. Listen sind schnell, und man kann nachlesen,
 * warum eine Äußerung so und nicht anders eingestuft wurde. Fehlt ein Wort, trägt man es hier nach und
 * ergänzt eine Zeile in der Tabelle des Tests (HeuristicsTableTest).
 *
 * Schreibweise: Alle Einträge werden beim Laden "gefaltet" (klein, "ß" als "ss", ohne Apostroph), genau
 * wie die Wörter der Äußerung. So passt "weiß" auch auf "weiss" und "geht's" auf "gehts".
 *
 * In Wendungen gibt es drei Sonderzeichen:
 *  - `*`  : hier dürfen bis zu drei beliebige Wörter stehen ("weiß * nicht" passt auf "weiß ich auch nicht")
 *  - `**` : hier dürfen bis zu sechs beliebige Wörter stehen
 *  - `$`  : hier muss der Satz zu Ende sein ("was geht $" passt nicht auf "was geht in einer Zelle vor")
 */
internal object HeuristicWords {

    /** Einheitliche Schreibweise für den Vergleich: klein, "ß" als "ss", Apostroph weg ("geht's" -> "gehts"). */
    fun fold(word: String): String = word.lowercase().replace("ß", "ss").replace("'", "")

    private val SPACES = Regex("\\s+")

    private fun words(list: String): Set<String> = list.trim().split(SPACES).mapTo(HashSet(), ::fold)

    /** Längere Wendungen zuerst, damit "was wollte ich sagen" vor "was wollte ich" geprüft wird. */
    private fun phrases(vararg list: String): List<List<String>> =
        list.map { it.trim().split(SPACES).map(::fold) }.sortedByDescending { it.size }

    // ---------------------------------------------------------------------------------------------
    // Fragewörter

    val INTERROGATIVES = words(
        """
        wer wen wem wessen was wann wo wie warum wieso weshalb weswegen
        welche welcher welches welchen welchem wozu wohin woher womit wodurch wofür worauf woran worin
        worüber wovon wonach woraus worum wogegen wovor worunter wieviel wieviele inwiefern
        what who whom whose when where why how which what's who's how's where's
        """
    )

    /**
     * Fragewörter, die fast nie etwas anderes sind als ein Fragewort. "was" ("ich hab was gefunden"),
     * "wie" ("so groß wie ein Haus") und "wo" ("da, wo wir waren") fehlen absichtlich.
     */
    val FIRM_INTERROGATIVES = words(
        """
        wer wen wem wessen wann warum wieso weshalb weswegen welche welcher welches welchen welchem
        wozu wohin woher womit wodurch wofür worauf woran worin worüber woraus worum wogegen wovor worunter
        wieviel wieviele
        """
    )

    /** Leiten eine indirekte Ja/Nein-Frage ein: "ich frag mich, ob ...". */
    val IF_WORDS = words("ob if whether")

    /** Dürfen vor einem Fragewort stehen, ohne dass es weniger Frage ist: "seit wann", "in welchem Jahr". */
    val PREPOSITIONS = words(
        "seit bis ab von mit für an in aus um bei auf zu nach über durch gegen unter vor from to for at on with by about"
    )

    // ---------------------------------------------------------------------------------------------
    // Vorlauf: Wörter, die vor der eigentlichen Frage stehen

    /**
     * Vorlauf, der nie selbst ein Satzglied ist ("und", "ähm", "sag mal"). Dahinter beginnt der Satz neu,
     * deshalb darf man ihn einfach weglassen: "und ist das giftig" ist dieselbe Frage wie "ist das giftig".
     */
    val SAFE_LEAD_INS = words(
        """
        und aber oder denn ähm äh öhm hm hmm mhm ach oh ah hey ey na tja naja okay ok nee nein ja genau
        boah wow puh uff oha oje sorry entschuldigung tschuldigung hallo hi moin
        leute jungs mädels alter digga mann mensch sag mal moment warte wait well and but
        """
    )

    /**
     * Vorlauf, der im Deutschen die erste Stelle im Satz besetzen kann ("dann ist das so").
     * Vor einem Fragewort darf man ihn überspringen ("also wie hoch ist ..."). Vor einem Verb NICHT:
     * "dann ist ein Wal ein Säugetier" ist eine Aussage, keine Frage.
     */
    val ADVERB_LEAD_INS = words("also dann jetzt übrigens apropos eigentlich ehrlich kurz bitte so")

    /** Ausrufe des Staunens. Danach folgt Staunen, keine Frage: "boah, ist das laut hier". */
    val EXCLAIMERS = words("boah wow alter mann mensch oha uff puh oje digga")

    // ---------------------------------------------------------------------------------------------
    // Leere Wörter und Wendungen ohne Wissenswunsch

    /**
     * Füllsel, Reaktionen und Grüße. Besteht eine Äußerung nur daraus, ist sie COLD, auch mit Fragezeichen:
     * Ein "Echt?" ist ein Tonfall, keine Frage.
     */
    val EMPTY_WORDS = words(
        """
        äh ähm öhm hm hmm mhm mh ja jo joa jep nee nö nein okay ok ach achso ah aha oh oha
        genau also na tja naja so echt wirklich ehrlich ernsthaft krass klar eben halt gut schön
        super cool toll nice hallo hi hey servus moin tschüss tschau ciao bye danke bitte sorry hä
        was wie jetzt im ernst oder doch haha hehe hihi lol wow boah ey alter mann mensch digga puh uff
        uh um well yeah yes no really what right sure thanks hello denn dann und aber nun ne gell schon
        mal sicher bestimmt vielleicht eh voll total absolut definitiv richtig prost mist verdammt oje ups
        """
    )

    /**
     * Feste Wendungen ohne Wissenswunsch. Sie werden aus dem Satz herausgenommen, bevor die Frageform
     * geprüft wird. Viele beginnen mit einem Fragewort und sahen deshalb früher wie eine Frage aus.
     *
     * Vorsicht bei neuen Einträgen: Was auch der Anfang einer echten Frage sein kann, braucht ein `$`
     * ("was ist passiert $"), sonst verschwindet "was ist passiert im Jahr 1789".
     */
    val NO_NEED_PHRASES = phrases(
        // Redewendungen mit Fragewort
        "was ich sagen wollte", "was ich meine", "was ich mein", "was ich damit sagen will",
        "wie du meinst", "wie du willst", "wie du magst", "wie ihr wollt", "wie ihr meint",
        "wie ich schon sagte", "wie ich finde", "was du nicht sagst", "was weiß ich", "was soll ich sagen",
        "wie gesagt", "wie schon gesagt", "wie auch immer", "was auch immer", "wer auch immer",
        "wo auch immer", "wann auch immer", "warum auch immer", "wie dem auch sei",
        "wer weiß $", "wer weiß das schon", "wer weiß schon",
        "was soll's", "was soll das $", "was soll das denn $", "was das soll $",
        "warum nicht", "wieso nicht", "warum auch nicht", "wieso auch nicht", "warum eigentlich nicht",
        "wie bitte", "wie jetzt", "wie immer", "wie üblich", "wie man's nimmt", "wie man so sagt",
        "wie man sieht", "wie es aussieht", "wann immer", "nach wie vor", "genau wie", "genauso wie",
        "wo wir gerade dabei sind", "wo wir schon dabei sind", "was mich angeht", "was das angeht",
        "wie wär's", "wie wäre es", "wie die zeit vergeht", "wie gut dass", "wie lange nicht gesehen",
        "was zum teufel", "was zur hölle", "was zum geier", "was zum henker",
        "und wie $", "aber wie $", "wer hätte das gedacht", "wer sagt's denn",
        "wo war ich", "wo waren wir", "was wollte ich sagen", "was wollte ich", "was wollt ich",
        "wie soll ich sagen", "wie soll ich das sagen", "wie sagt man so schön", "wann wenn nicht jetzt",
        "was hab ich gesagt", "was habe ich gesagt",
        "was heißt hier", "was soll der mist", "was soll der quatsch", "was soll der scheiß",
        "frag mich nicht", "frag mich was leichteres",
        "weißt du was ich meine", "weißt du was ich mein", "weißt was ich meine", "verstehst du was ich meine",
        "weißt du was $", "weißte was $",
        // Ankündigung einer Frage: Die Frage selbst kommt danach und wird für sich geprüft.
        "ich hab * frage", "ich habe * frage", "ich hätte * frage", "mal ne frage", "mal eine frage",
        "kurze frage", "blöde frage", "dumme frage", "gute frage",
        // Geplauder und Organisatorisches
        "wie geht's", "wie geht's dir", "wie geht's euch", "wie geht's ihnen", "wie geht's so",
        "wie geht es", "wie geht es dir", "wie geht es euch", "wie geht es ihnen",
        "wie läuft's", "wie läuft es", "wie steht's", "wie war's",
        "was geht ab", "was geht $", "was geht so $", "was läuft $",
        "was gibt's neues", "was gibt es neues", "was gibt's zu essen", "was gibt es zu essen", "was gibt's heute",
        "was ist * los $", "was war * los $",
        "was ist passiert $", "was ist denn passiert $", "was war das $", "was war das denn $",
        "wer will", "wer möchte", "wer mag", "wer kommt mit", "wer hat lust", "wer hat bock",
        "wer hat hunger", "wer hat durst", "wer hat zeit", "wer kommt alles", "wer kommt noch",
        "wer ist dran", "wer ist dabei", "wer macht mit", "wer ist da $",
        "wer denn sonst", "was denn sonst", "wo denn sonst", "wie denn sonst",
        "wer sonst $", "was sonst $", "wo sonst $", "wie sonst $", "wann sonst $", "warum sonst $",
        "alles klar", "alles gut", "alles fit", "alles okay", "alles ok", "gott sei dank",
        // Grüße und Höflichkeiten
        "guten morgen", "guten tag", "guten abend", "gute nacht", "bis morgen", "bis dann", "bis später",
        "bis gleich", "bis bald", "bis nachher", "mach's gut", "mach es gut", "schönen abend", "schönen tag",
        "schönes wochenende", "viel spaß", "viel erfolg", "viel glück", "gute besserung", "danke dir",
        "vielen dank", "danke schön", "dankeschön", "kein problem", "gern geschehen", "keine ursache",
        "alles gute", "herzlichen glückwunsch", "guten appetit", "schlaf gut", "schlaf schön",
        "pass auf dich auf", "komm gut heim", "komm gut nach hause", "auf wiedersehen", "auf wiederhören",
        // Englisch
        "how are you", "how are you doing", "how's it going", "how is it going", "how have you been",
        "how do you do", "what's up", "what is up", "what's going on $", "what's new",
        "what the hell", "what the heck", "what the fuck", "who knows $", "who cares", "why not",
        "see you", "see you later", "thank you", "thanks a lot", "you're welcome", "have a nice day",
        "have a good one", "have fun", "nice to meet you", "good morning", "good night", "take care",
        "oh my god", "no way", "i know right", "you know what i mean", "know what i mean", "you know what $",
        "tell me about it",
    )

    // ---------------------------------------------------------------------------------------------
    // Ausdrücklicher Wissenswunsch

    /**
     * Wendungen, die für sich allein schon einen Wissenswunsch bedeuten: Fragen an die Runde
     * und die Suche nach einem Wort oder Namen.
     */
    val WISH_PHRASES = phrases(
        "weiß jemand", "weiß einer", "weiß hier jemand", "weiß zufällig jemand", "weiß das jemand",
        "kennt jemand", "kennt einer",
        // Nachfrage am Satzende: "... stimmt das". Das `$` schützt vor "stimmt, das ist ein guter Punkt".
        "stimmt das $", "stimmt das wirklich $", "stimmt das eigentlich $", "stimmt das überhaupt $",
        "stimmt das so $", "stimmt das denn $", "stimmt das nicht $", "ist das wahr $", "ist das richtig $",
        "hab ich recht $", "habe ich recht $", "lieg ich falsch $", "liege ich falsch $",
        "fällt mir nicht ein", "fällt ** nicht ein", "will mir nicht einfallen",
        "komm * nicht drauf", "komme * nicht drauf", "komm * nicht darauf", "komme * nicht darauf",
        "nicht auf den namen", "nicht auf das wort", "nicht auf den titel", "nicht auf den begriff",
        "liegt mir auf der zunge", "auf der zunge", "mir fehlt das wort",
        "noch heißt", "nochmal heißt", "noch mal heißt", "gleich heißt",
        "noch hieß", "nochmal hieß", "noch mal hieß", "gleich hieß",
        "i wonder", "i'm wondering", "do you know", "does anyone know", "does anybody know",
        "anyone know", "anybody know", "any idea",
        "how do you say", "how do you spell", "how do you pronounce", "what do you call",
        "can you explain", "could you explain", "can you tell me", "could you tell me",
        "what's the word for", "what was the name",
    )

    /**
     * Einleitungen einer indirekten Frage. Sie zählen erst, wenn kurz danach ein Fragewort oder "ob" folgt:
     * "keine Ahnung" allein ist eine Antwort, "keine Ahnung, wie das Ding heißt" ein Wissenswunsch.
     */
    val ASK_OPENERS = phrases(
        "weiß nicht", "weiß * nicht", "keine ahnung", "ne ahnung", "eine ahnung", "kein plan", "keinen plan",
        "keinen schimmer", "schimmer", "keine idee", "ne idee", "eine idee",
        "nicht sicher", "nicht mehr sicher", "unsicher", "vergessen",
        "versteh nicht", "verstehe nicht", "versteh * nicht", "verstehe * nicht", "kapier nicht", "kapiere nicht",
        "kapier * nicht", "check nicht", "checke nicht", "check * nicht", "raff nicht", "raffe nicht", "blick nicht",
        "überleg", "überlege", "überlegt",
        "frag mich", "frage mich", "frag ich mich", "frage ich mich", "mich gefragt", "mich * gefragt",
        "sag mir", "sag mal", "sagt mir", "sagt mal", "sag uns", "mir sagen", "uns sagen", "mir * sagen",
        "mir erklären", "uns erklären", "erklär mir", "erklär mal", "erklär uns", "erkläre mir",
        "weißt du", "weist du", "wisst ihr", "weiß jemand", "weiß einer", "erinnerst du dich", "erinnert ihr euch",
        "meinst du", "glaubst du", "denkst du", "schätzt du", "meint ihr", "glaubt ihr",
        "gern wissen", "gerne wissen", "will wissen", "wollte wissen", "möchte wissen", "mal wissen",
        "interessieren", "interessiert mich", "wüsste gern", "wüsste gerne", "wüsste ich gern", "wüsste ich gerne",
        "nachschauen", "nachgucken", "googeln",
        "don't know", "not sure", "no idea", "forgot", "can't remember", "cannot remember",
        "wonder", "wondering", "tell me", "curious",
    )

    /** Befehlsformen, mit denen man um Wissen bittet: "erklär mal ...", "nenn mir ...", "rechne ...". */
    val REQUEST_IMPERATIVES = words(
        "erklär erkläre definier definiere übersetz übersetze buchstabier buchstabiere rechne berechne nenn nenne " +
            "explain define translate spell tell"
    )

    /**
     * Steht eines dieser Wörter gleich hinter der Befehlsform, ist es keine Bitte um Wissen:
     * "rechne nicht damit", "rechne lieber mit zwei Stunden", "nenn mich nicht so", "erklär ich gleich".
     */
    val NO_REQUEST_NEXT = words("ich nicht mich damit mit lieber")

    /** Wortanfänge derselben Verben in jeder Beugung: "kannst du das ERKLÄRen", "kann das jemand ÜBERSETZen". */
    val REQUEST_STEMS = listOf("erklär", "definier", "übersetz", "buchstabier", "ausrechn", "explain", "translate", "define")

    // ---------------------------------------------------------------------------------------------
    // Verben

    /**
     * Gebeugte Verben, mit denen deutsche Ja/Nein-Fragen typischerweise anfangen ("ist ein Wal ein Fisch").
     * Die Liste ist nicht vollständig. Das ist in Ordnung: Fehlt ein Verb, fällt die Frage auf MAYBE zurück,
     * sobald sie lang genug ist, und nicht auf COLD. "wahr" steht hier als häufiger Verhörer für "war".
     */
    val QUESTION_VERBS = words(
        """
        ist sind seid war waren wäre wären hat haben hatte hatten hätte
        kann können könnte könnten darf dürfen dürfte muss müssen müsste soll sollen sollte sollten
        wird werden würde würden will wollen gibt gibt's gab gab's stimmt kennt weiß heißt bedeutet
        geht kommt kommen liegt liegen lebt leben wächst wachsen braucht brauchen kostet kosten dauert
        funktioniert funktionieren zählt zählen gehört gehören gilt gelten existiert existieren reicht lohnt
        schreibt sagt spricht nennt isst essen frisst fressen trinkt trinken schläft schlafen fliegt fliegen
        schwimmt schwimmen macht machen schmeckt passt hilft helfen wirkt leitet schmilzt brennt friert
        stirbt sterben bekommt kriegt sieht sehen hört hören steht stehen läuft laufen fährt fahren
        regnet schneit tut wahr
        besteht bestehen enthält enthalten wiegt wiegen legen atmen tragen sprechen
        """
    )

    /** Englische Hilfsverben am Satzanfang: "is a tomato a fruit", "do whales sleep". */
    val ENGLISH_AUXILIARIES = words(
        "is are were do does did can could should would has have had isn't aren't doesn't didn't can't " +
            "won't shouldn't wouldn't couldn't hasn't haven't wasn't weren't"
    )

    /**
     * Verben, mit denen auch Erzähltes ohne Subjekt anfängt: "kam dann anders", "ging ganz gut", "wurde auch Zeit".
     * Deshalb stehen sie nicht bei [QUESTION_VERBS]. Am Satzanfang zählen sie nur als Frage, wenn gleich
     * dahinter das Subjekt steht: "wurde Kennedy erschossen", "konnten die Wikinger schreiben".
     * Ohne diese Liste fielen kurze Fragen nach Vergangenem ungeprüft durch (sie waren COLD).
     * Der Preis: "wollte Lisa nicht mitkommen" sieht genauso aus und wird ebenfalls HOT.
     */
    val STORY_VERBS = words(
        """
        wurde wurden konnte konnten musste mussten durfte durften wollte wollten hätten
        hieß hießen heißen kam kamen ging gingen lag lagen stand standen starb starben lebte lebten
        gewann gewannen erfand erfanden schrieb schrieben stammt stammen entsteht entstehen bedeuten
        """
    )

    /**
     * Alles, was als gebeugtes Verb direkt hinter einem Fragewort stehen kann ("wie hoch IST ...").
     * Daran erkennt man die echte Frage: Im Nebensatz steht das Verb am Ende ("wie hoch die Zugspitze ist").
     */
    val FINITE_VERBS = QUESTION_VERBS + ENGLISH_AUXILIARIES + STORY_VERBS +
        words("sagte meint passiert hab habe bin bist habt hast")

    val MODALS = words("kann können könnte könnten darf dürfen dürfte muss müssen müsste soll sollen sollte sollten")

    /** "sein" in seinen Formen: Danach steht, was etwas IST ("war ein schöner Abend"). */
    val COPULAS = words("ist sind war waren wäre wären")

    /** Verben in der ich-Form, mit denen man nach sich selbst fragt: "wo hab ich ...", "wo bin ich". */
    val SELF_VERBS = words("hab habe bin war hatte")

    /**
     * Fragt jemand sein Gegenüber so, kann trotzdem Wissen gemeint sein ("kennst du die Formel",
     * "wie schreibst du Rhythmus"). Das ist ohne Zusammenhang nicht zu entscheiden, also MAYBE.
     */
    val SCHOOL_VERBS = words("kennst kennt weißt wisst schreibst rechnest berechnest übersetzt sprichst buchstabierst löst")

    /** Verben, mit denen man gemeinsame Pläne erfragt: "wann treffen wir uns", "was essen wir heute". */
    val PLANNING_VERBS = words(
        "treffen gehen fahren fliegen essen kochen machen wollen starten bestellen holen kaufen spielen gucken schauen feiern"
    )
    val PLANNING_QUESTION_WORDS = words("wann wo wohin was")

    /** Mit "wann", "wo", "wohin" sind auch diese Verben Planung: "wann müssen wir los". Mit "was" nicht ("was müssen wir beachten"). */
    val PLANNING_MODALS = words("müssen sollen können dürfen")

    /**
     * Wörter auf "t", die am Satzanfang vor einem Hauptwort stehen und trotzdem kein Verb der Frage sind:
     * "Statt Fleisch lieber Fisch", "Acht Leute kommen", "Vorsicht Stufe", "Zeit der Entscheidung".
     * Dazu Du-Formen ohne "du": "hast Glück gehabt", "willst Kaffee oder Tee" sind persönlich.
     */
    val T_BUT_NO_VERB = words(
        """
        statt anstatt laut samt acht hundert heut punkt direkt exakt perfekt korrekt komplett schlecht nett
        bekannt interessant gesagt gemacht gedacht geschafft erledigt gesamt insgesamt weltweit vorsicht
        zeit welt stadt nacht arzt gott art ort wort
        hast habt bist weißt kennst willst magst brauchst musst kannst darfst sollst wirst möchtest
        """
    )

    /** Kein Verb, auch wenn "du", "ihr" oder "man" dahinter steht: "wenn du", "für ihr", "wie man". */
    val NOT_A_VERB = INTERROGATIVES + PREPOSITIONS + SAFE_LEAD_INS + ADVERB_LEAD_INS + words(
        """
        wenn dass weil ob als falls sobald indem bevor damit obwohl während nachdem da sondern
        nur auch sogar selbst gerade wegen ohne außer neben zwischen nicht
        ich du er sie es wir ihr man das der die
        """
    )

    // ---------------------------------------------------------------------------------------------
    // Wer ist gemeint?

    val FIRST_PERSON = words("ich wir i we")

    /** "ich", "du", "wir", "ihr": Steht eines davon direkt hinter dem Fragewort, geht es um Persönliches. */
    val SPEAKER_AND_LISTENER = words("ich du wir ihr i you we")

    /** Du-Wörter. Kommt eines früh in einer Frage vor, fragt jemand sein Gegenüber nach sich selbst. */
    val YOU_WORDS = words(
        "du dir dich dein deine deinen deinem deiner deins ihr euch euer eure euren eurem eurer you your yours yourself"
    )

    /**
     * "mir", "mich", "uns", "sich" hinter dem Verb sind nicht das Subjekt; das kommt erst danach
     * ("kann MIR jemand ...", "lohnt SICH das"). Kommt keines, ist es eine Aussage ("hört sich gut an").
     */
    val ME_OBJECTS = words("mir mich uns sich")

    val MY_WORDS = words("mein meine meinen meinem meiner unser unsere unseren unserem unserer")

    /** Familie: Fragen nach ihr sind persönlich ("hat Mama angerufen"). */
    val KINSHIP = words("mama papa mami papi mutti vati oma opa mum mom dad")

    /** "man", "es", "das" als Subjekt: allgemeine Frage ("kann man das essen", "gibt es Haie"). */
    val GENERAL_SUBJECTS = words("man es das dies dieses sowas")

    /** "jemand", "einer": Frage an die Runde. */
    val SOMEONE = words("jemand einer wer irgendwer irgendjemand keiner niemand")

    /** "er", "sie": Bekannter oder berühmte Person? Ohne Zusammenhang nicht zu entscheiden. */
    val THIRD_PERSON = words("er sie")

    /** Leiten ein Hauptwort ein: "ist EIN Wal ...", "hat DER Mond ...". */
    val ARTICLES = words("der die ein eine dieser diese jeder jede jedes alle viele manche beide")

    /**
     * Stehen diese Wörter direkt hinter dem Fragewort, folgt ein Nebensatz mit dem Verb am Ende
     * ("wie das geht", "was man beachten muss"), keine direkte Frage.
     */
    val CLAUSE_SUBJECTS = words("das der die den dem man er sie es")

    // ---------------------------------------------------------------------------------------------
    // Aussage statt Frage

    /**
     * Wörter, die nach einem Verb am Satzanfang nur stehen, wenn das Subjekt weggelassen wurde:
     * "(das) ist halt so", "(das) war nur Spaß", "(es) kommt drauf an". Das sind Aussagen.
     */
    val STATEMENT_NEXT = words(
        """
        halt ja doch eben eh nichts nie sein so super gut klar okay ok wohl natürlich aber
        leider egal schade schlimm genau genauso kein keine keinen ganz sehr ziemlich richtig einfach mal nur
        wahr anders drauf dass selber selbst immer irgendwie fertig gar was
        """
    )

    /**
     * Hauptwörter aus Redensarten ohne Subjekt: "(das) hat Spaß gemacht", "(das) hat Zeit gekostet".
     * Sie zählen nur, wenn der Satz mit einem Mittelwort endet ("gemacht", "gekostet"), denn
     * "macht Stress krank" ist eine echte Frage.
     */
    val STATEMENT_NOUNS = words("spaß zeit sinn recht zufall quatsch blödsinn unsinn absicht glück pech stress bock lust hunger durst angst")

    /** Mengenangaben hinter "ein/eine": "hat ein BISSCHEN gedauert", "kostet ein VERMÖGEN". Kein Subjekt, also keine Frage. */
    val QUANTITY_NEXT = words("bisschen wenig paar weile menge haufen moment stück vermögen")

    /** Kleine Wörter, die hinter dem Verb stehen können, ohne dass es weniger Frage ist: "ist EIGENTLICH ein Wal ...". */
    val TRANSPARENT_PARTICLES = words("eigentlich denn überhaupt etwa nicht nun wirklich schon echt voll total")

    /**
     * Diese vier stehen meist in Aussagen ("ist schon okay", "war echt gut"), selten in Fragen
     * ("ist schon Mittag"). Folgt ihnen kein erkennbares Subjekt, gilt der Satz als Aussage.
     */
    val STATEMENT_LEANING = words("schon echt voll total")

    /** Zeit- und Ortsangaben hinter dem Verb: "ist MORGEN ein Feiertag", "ist HIER noch frei". */
    val TRANSPARENT_ADVERBS = words("heute morgen gestern jetzt bald hier da")

    /** Enden kurze Sätze so, sind es Aussagen: "kann man machen", "gibt es nicht", "weiß man nie". */
    val SHORT_STATEMENT_ENDINGS = words("nicht nichts nie machen sagen wissen sehen meinen mögen auch halt eben ja doch gern kaum wert")

    /** Nach "ist das ..." eine Meinung, kein Wissen: "ist das egal", "ist das okay". */
    val OPINION_WORDS = words("egal klar okay ok")

    /**
     * Wertende Eigenschaftswörter. "wie schön", "ist das lecker" sind Ausrufe; "wie schwer", "ist das giftig"
     * sind Fragen. Messbares (schwer, groß, alt, gut, teuer) steht deshalb NICHT hier, ebenso wenig Wörter,
     * nach deren Ausmaß man ernsthaft fragt ("wie schlimm ist Zucker").
     */
    val EVALUATIVE = words(
        """
        schön toll geil krass cool süß lecker blöd doof nervig peinlich lustig witzig traurig schade herrlich
        wunderbar furchtbar schrecklich ekelhaft eklig lieb nett gemein dumm ärgerlich romantisch mega klasse
        prima fies komisch seltsam verrückt irre bescheuert dämlich bekloppt absurd unfassbar unglaublich
        genial großartig fantastisch hässlich widerlich lächerlich frech dreist niedlich hübsch gemütlich
        langweilig spannend ätzend mies
        nice lovely cute awesome sweet weird funny sad rude beautiful wonderful terrible awful crazy amazing great annoying
        """
    )

    /** Nach "wie" ein Vergleich, keine Frage: "wie im Film", "wie früher", "wie ein Profi". */
    val COMPARISON_NEXT = words(
        """
        im in bei beim früher immer damals letztes letzte letzten jeden jedes zuhause vorhin
        gestern heute eben neu üblich erwartet besprochen versprochen abgemacht gewohnt geplant gehabt wenn
        """
    )

    /**
     * Nach dem Fragewort ein Satz ohne Frage: "was ICH sagen wollte", "wie DU meinst", "wer NICHT will",
     * "was HALT passiert".
     */
    val FREE_RELATIVE_NEXT = SPEAKER_AND_LISTENER + words("mich mir dich dir uns euch nicht auch immer ja halt schon eben doch")

    /**
     * Wörter, die zwischen Fragewort und Subjekt stehen können, ohne das Verb zu sein: Maßangaben
     * ("wie LANGE das dauert") und kleine Wörter ("was EIGENTLICH der Unterschied ist"). Jedes andere Wort
     * an dieser Stelle ist das Verb ("wie RECHNE ich das aus"), und dann ist es eine echte Frage.
     */
    val NOT_THE_VERB = EMPTY_WORDS + PREPOSITIONS + words(
        """
        lange lang viel viele wenig wenige oft weit hoch alt groß schnell teuer spät schwer tief breit kurz
        warm kalt heiß sehr früh häufig stark genau nochmal eigentlich überhaupt etwa nicht nun heute morgen
        gestern bald hier da alles sonst noch auch nur
        zuerst zuletzt erst fast meist bereits dort sofort selbst recht leicht
        """
    )

    /** Vor "was": dann ist es ein Bezugswort ("alles was geht"), kein Fragewort. */
    val RELATIVE_BEFORE = words("alles das etwas nichts vieles egal so genauso sowas")

    /** Vor "was" im Sinn von "etwas": "noch was trinken", "auch was dabei". */
    val SOMETHING_BEFORE = words("noch nur auch mal schon irgend")

    /** Vor englischem "was" steht das Subjekt: "I was thinking" ist keine deutsche Was-Frage. */
    val ENGLISH_SUBJECTS = words("i he she it that there this who what which when where why how one they we you")

    // ---------------------------------------------------------------------------------------------
    // Rückversicherung und Unsicherheit

    /** Anhängsel, mit denen man sich rückversichert: "... oder?", "... ne?", "... gell?". */
    val TAGS = phrases(
        "oder $", "oder nicht $", "oder etwa nicht $", "oder doch $", "oder was $", "oder wie $", "ne $", "nä $",
        "nich $", "gell $", "gelle $", "woll $", "nicht wahr $", "stimmt's $", "richtig $", "korrekt $",
        "right $", "isn't it $",
    )

    /** Anhängsel, die Unsicherheit zeigen: "das war 1989, glaub ich". */
    val UNCERTAIN_TAILS = phrases(
        "glaub ich $", "glaube ich $", "denk ich $", "denke ich $", "mein ich $", "meine ich $", "oder so $",
        "oder so ähnlich $", "wenn ich mich nicht irre $", "soweit ich weiß $", "i think $", "i guess $", "or something $",
    )

    /** Dasselbe vorn: "ich glaub, das war Edison". */
    val UNCERTAIN_OPENERS = phrases("ich glaub", "ich glaube", "ich mein", "ich meine", "ich denk", "ich denke", "i think", "i guess")

    /**
     * Stichwort-Fragen ohne Verb, wie man sie vor sich hin murmelt: "Hauptstadt von Peru", "Wurzel aus 144".
     * Als Frage sind sie nicht zu erkennen; diese Wendungen kommen aber fast nur vor, wenn jemand etwas wissen will.
     */
    val QUERY_PHRASES = phrases(
        "hauptstadt von", "definition von", "bedeutung von", "unterschied zwischen", "synonym für", "gegenteil von",
        "plural von", "mehrzahl von", "übersetzung von", "abkürzung für", "formel für", "wurzel aus",
        "einwohner von", "einwohnerzahl von", "wort für",
        // Übersetzung ohne Verb: "Schmetterling auf Englisch", "und auf Französisch"
        "auf englisch", "auf deutsch", "auf französisch", "auf spanisch", "auf italienisch", "auf latein",
        "auf lateinisch", "auf japanisch", "auf chinesisch", "auf russisch", "auf türkisch", "auf polnisch",
        "auf arabisch", "auf griechisch", "auf niederländisch", "auf portugiesisch",
        "capital of", "definition of", "meaning of", "synonym for", "opposite of", "plural of", "word for",
        "in english", "in german", "in french", "in spanish",
    )

    // ---------------------------------------------------------------------------------------------
    // Fragewort am Satzende und Rechenaufgaben

    /** Anhängsel hinter einem Fragewort am Satzende: "das heißt was GENAU", "der hieß wie NOCHMAL". */
    val TRAILING_SOFTENERS = words("nochmal nochmals noch mal gleich genau eigentlich denn überhaupt jetzt bitte so ungefähr")

    /** Nach diesen Verben ist ein "was" am Satzende ein Fragewort ("das bedeutet was"), kein "etwas" ("da ist was"). */
    val MEANING_VERBS = words("heißt heißen hieß bedeutet bedeuten meint")

    /** "ich weiß wie", "weißt du wo": Hier ist das Fragewort am Ende keine Frage. */
    val KNOWING_VERBS = words("weiß weißt wisst wissen")

    /** Rechenzeichen in Worten: "zwölf MAL zwölf", "144 DURCH zwölf", "drei HOCH vier". */
    val OPERATORS = words("plus minus mal durch hoch times")

    val NUMBER_WORDS = words(
        "null eins zwei drei vier fünf sechs sieben acht neun zehn elf zwölf hundert tausend million millionen " +
            "zero one two three four five six seven eight nine ten eleven twelve"
    )

    /** Zusammengesetzte Zahlwörter erkennt man am Ende: "dreiZEHN", "vierZIG", "dreiSSIG", "zweiHUNDERT". */
    val NUMBER_ENDINGS = listOf("zehn", "zig", "ssig", "hundert", "tausend")

    // ---------------------------------------------------------------------------------------------
    // Stopp

    /** Einzelne Wörter, die "sei still" bedeuten. */
    val STOP_WORDS = words(
        "stopp stop ruhe still leise aufhören schweig reicht klappe genug schluss abbrechen pst psst pscht quiet silence enough"
    )

    val STOP_PHRASES = phrases(
        "hör auf", "hör * auf", "halt die klappe", "halt den mund", "sei ruhig", "seid ruhig", "sei still",
        "sei leise", "danke reicht", "es reicht", "das reicht", "shut up", "be quiet",
        // "ruhig" allein ist kein Stoppwort ("mach ruhig weiter"), deshalb eigens: "sei bitte mal ruhig"
        "sei * ruhig", "seid * ruhig",
    )

    // Bitte um Ruhe ([Heuristics.asksForQuiet]). Ein einzelnes Wort genügt dafür nicht, siehe dort.

    /** "sei still", "bleib ruhig", "kannst du mal still sein": das Verb der Aufforderung ... */
    val QUIET_IMPERATIVES = words("sei seid sein bleib bleibe")

    /** ... und das Wort dazu. Für sich allein sind beide nichts ("mach ruhig weiter", "sei so gut"). */
    val QUIET_ADJECTIVES = words("ruhig still leise stumm")

    /** Wörter für Ruhe, die zusammen mit einer Dauer eine Bitte sind: "zwei Stunden Ruhe", "Pause bis drei". */
    val QUIET_NOUNS = words(
        "ruhe stille funkstille sendepause pause frieden stören stumm lautlos schweigen schweig schweige klappe mute quiet silence"
    )

    /**
     * Zwei Wörter, die erst zusammen (und mit einer Dauer) um Ruhe bitten: "halt dich eine Stunde zurück",
     * "sag eine Weile nichts", "misch dich zehn Minuten nicht ein", "ich will eine Stunde nichts hören".
     */
    val QUIET_PAIRS = listOf(
        words("halt halte") to fold("zurück"),
        words("sag sage sagst hören") to fold("nichts"),
        words("misch mische") to fold("ein"),
    )

    /** "schalte dich stumm", "geh auf lautlos": stumm schalten ... */
    val MUTE_WORDS = words("stumm lautlos mute schweig schweige")

    /** ... mit einem dieser Verben. Ohne Verb zählt es nur in einem ganz kurzen Satz ("Alfred, stumm bitte"). */
    val MUTE_VERBS = words("schalt schalte stell stelle mach mache geh gehe bleib bleibe sei go stay")

    /** Dank oder Bestätigung ([Heuristics.isMereThanks]): Mindestens eines dieser Wörter muss fallen ... */
    val THANKS = words("danke dankeschön dank merci thanks thank ok okay gut super prima perfekt klar passt")

    /** ... und daneben darf nur so etwas stehen: "vielen Dank dir", "alles klar", "thank you so much". */
    val THANKS_FILLERS = words("schön sehr vielen besten lieben herzlichen dir dafür alles ja you so much")

    /** Mit diesen Wörtern beginnt eine Frage, und eine Frage bittet nicht um Ruhe: "wann ist Ruhe im Haus". */
    val QUIET_QUESTION_STARTS = words(
        "wie was wann wer wo warum wieso weshalb welche welcher welches wieviel how what when who where why"
    )

    /** Dürfen neben dem Stoppwort stehen, ohne dass es weniger Befehl ist: "bitte", "jetzt", "endlich". */
    val STOP_FILLERS = words("bitte jetzt mal endlich sofort danke ok okay sei mach doch einfach hey ja nun aber und please now just")

    /** Verneinung dreht den Befehl um: "nicht aufhören". */
    val NEGATIONS = words("nicht kein keine nie don't not never")

    /**
     * Zeitangaben: "sei fünf Minuten still" und "sei bis morgen still" sind Bitten an das Modell
     * (Stummschaltung), kein Abbruch der laufenden Antwort.
     */
    val DURATION_WORDS = words(
        "minute minuten stunde stunden sekunde sekunden weile minutes minute hour hours " +
            "bis solange erstmal vorerst heute morgen lang lange tag tage abend until"
    )

    // ---------------------------------------------------------------------------------------------
    // Korrektur

    /** Was vor "ich meine" stehen darf, wenn sich jemand verbessert: "Nein, ich meine ...", "Ach nee, sorry, ich meinte ...". */
    val CORRECTION_LEAD_INS = words("nein nee ne nö ach äh ähm oh also quatsch sorry entschuldigung moment falsch")

    /** "ich meine", "ich meinte", umgangssprachlich "ich mein". */
    val MEANT = words("meine meinte mein")

    /**
     * Steht eines dieser Wörter hinter "ich meine", folgt keine Sache, sondern ein ganzer Satz oder eine
     * Person: "ich mein ja nur", "ich meine das ernst", "ich meine, wir können auch morgen fahren", "ich meinte
     * dich". Das ist die Redewendung und keine Korrektur ([Heuristics.isCorrection]).
     */
    val NOT_WHAT_IS_MEANT = words(
        """
        nur bloß ernst so halt eben doch schon mal auch dass wenn weil ob
        ich du er sie es wir ihr man dich dir mich mir ihn ihm ihnen euch uns
        ist sind war waren bin bist hat habe haben hast hatte kann kannst können könnte will willst wollen
        soll sollte muss musst müssen wird werden würde geht gehen gibt recht klar egal
        """
    )

    // ---------------------------------------------------------------------------------------------
    // Anschlussfrage

    /** Wer so spricht, redet mit einem Menschen, nicht mit dem Assistenten ([Heuristics.isShortFollowUp]). */
    val TO_A_PERSON = words("du dir dich dein deine deinen deinem deiner euch euer eure mir mich you your")

    /** Bleibt hinter "und" / "oder" nur so etwas übrig, steht da nichts, wonach gefragt wäre: "Und dann?", "Oder was?" */
    val NO_FOLLOW_UP_SUBJECT = words("dann jetzt so was wie nun weiter nicht noch then what")

    // ---------------------------------------------------------------------------------------------
    // Echter Inhalt

    /** Wörter ohne Aussage. Wer nur so reagiert, hat noch nicht geantwortet. */
    val FILLERS = words(
        "äh ähm hm hmm mhm ja okay ok ach ah oh genau also na tja weiß nicht keine ahnung gute frage moment warte uh um well"
    )

    /**
     * Ratlosigkeit: Wer so reagiert, kennt die Antwort nicht und freut sich über Hilfe.
     * Hier absichtlich ohne `*`: "weiß * nicht" träfe auch "ich weiß, dass das nicht stimmt", und das ist
     * eine Antwort.
     */
    val CLUELESS_PHRASES = phrases(
        "weiß nicht", "weiß ich nicht", "weiß es nicht", "weiß das nicht", "weiß gar nicht", "weiß ich auch nicht",
        "weiß es auch nicht", "weiß ich jetzt auch nicht", "weiß ich leider nicht", "weiß ich gerade nicht",
        "weiß ich grad nicht", "weiß ich doch nicht", "weiß ich selber nicht", "weiß ich wirklich nicht",
        "weiß es wirklich nicht", "weiß ich echt nicht", "weiß ich leider auch nicht",
        "kann ich nicht sagen", "kann ich dir nicht sagen", "kann ich auch nicht sagen",
        "muss ich googeln", "müsste ich googeln", "müsste man googeln", "muss ich nachschauen",
        "müsste ich nachschauen", "müsste man nachschauen", "blassen schimmer",
        "da fragst du mich was", "fragst du den falschen", "fragst du die falsche",
        "keine ahnung", "kein plan", "keinen plan", "kein schimmer", "keinen schimmer",
        "überfragt", "gute frage", "muss ich überlegen", "muss überlegen", "muss ich passen", "nicht sicher",
        "frag mich nicht", "frag mich was leichteres",
        "no idea", "no clue", "don't know", "not sure", "good question", "beats me", "let me think",
    )
}

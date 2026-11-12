package app.claudevoice.core

/**
 * Was der Dienst dem Nutzer über die Verbindung zur Transkription sagt.
 *
 * Die Transkriptions-Clients verbinden nach einem Abbruch von selbst neu. Bei einem Funkloch ist
 * "neuer Versuch …" die richtige Auskunft. Bei einem abgelehnten Schlüssel oder aufgebrauchtem
 * Guthaben wäre sie falsch: Der nächste Versuch scheitert aus demselben Grund. Dann soll der wahre
 * Grund dastehen, und der Dienst hört auf, statt mit offenem Mikrofon ins Leere zu laufen.
 *
 * Die Clients melden ihre Fehler als fertigen deutschen Satz (die Schnittstelle kennt nur Text).
 * Erkannt werden die aussichtslosen Fälle deshalb an festen Wendungen dieser Sätze. Die Tests prüfen
 * das gegen die Sätze, die die Clients wirklich erzeugen; wer dort einen Wortlaut ändert, sieht es am Test.
 */
object ConnectionReport {
    private val HOPELESS = listOf(
        "key enthält",             // Schlüssel lässt sich gar nicht verschicken (unzulässige Zeichen)
        "key wurde abgelehnt",     // falscher oder widerrufener Schlüssel
        "guthaben",                // Konto leer
        "nutzungsbedingungen",     // im Konto noch nicht bestätigt
        "verweigert den zugriff",  // Konto darf diesen Dienst nicht nutzen
    )

    /** true, wenn ein neuer Verbindungsversuch mit denselben Einstellungen nichts ändern kann. */
    fun isHopeless(error: String?): Boolean {
        val text = error?.lowercase() ?: return false
        return HOPELESS.any { it in text }
    }

    /** Die Statuszeile nach einer Meldung der Transkription. [micName] z. B. "Handy-Mikro". */
    fun status(connected: Boolean, error: String?, micName: String): String = when {
        connected -> "Hört zu ($micName)"
        isHopeless(error) -> "Keine Transkription"
        else -> "Verbindung unterbrochen, neuer Versuch …"
    }

    /** Die Meldung, mit der der Dienst bei einem aussichtslosen Fehler endet. */
    fun stopMessage(error: String): String =
        "${error.trim().trimEnd('.')}. Ein neuer Versuch würde daran nichts ändern, deshalb wurde das Zuhören " +
            "beendet. Bitte Schlüssel und Konto in den Einstellungen prüfen."
}

/**
 * Meldung, wenn der Start des Dienstes (oder ein anderer Schritt, [what]) an einem unerwarteten Fehler scheitert.
 *
 * Bewusst steht nur die ART des Fehlers darin, nie sein Text: Fehlertexte von Bibliotheken enthalten
 * manchmal die Werte, an denen sie gescheitert sind (OkHttp nennt zum Beispiel den ganzen Schlüssel,
 * wenn er ein unzulässiges Zeichen enthält). Schlüssel dürfen aber nie in einer Meldung landen.
 */
object StartFailure {
    fun describe(error: Throwable, what: String = "Start"): String {
        val kind = error.javaClass.simpleName.ifEmpty { "unbekannter Fehler" }
        return "$what fehlgeschlagen ($kind). Bitte noch einmal versuchen."
    }
}

/**
 * Macht aus eingefügtem Text einen Schlüssel (oder eine Voice-ID), den man gefahrlos verschicken kann.
 *
 * Beim Kopieren aus Webseiten oder Notizen reisen oft Zeichen mit, die man nicht sieht: Leerzeichen,
 * Zeilenumbrüche, ein "Zero-Width-Space", typografische Anführungszeichen. In einem HTTP-Kopf sind sie
 * nicht erlaubt; die Anfrage scheitert dann schon beim Zusammenbauen. API-Schlüssel und Voice-IDs
 * bestehen nur aus sichtbaren ASCII-Zeichen, also wird alles andere entfernt.
 */
fun cleanSecret(raw: String): String = raw.filter { it in '!'..'~' }

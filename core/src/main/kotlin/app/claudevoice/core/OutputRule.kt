package app.claudevoice.core

/** Wie sicher ein Ausgabegerät nur vom Träger gehört wird. */
enum class OutputKind {
    /** Sitzt im oder am Ohr: Kopfhörer, Headset, Hörgerät. */
    PRIVATE,

    /**
     * Bluetooth-Gerät, das kein Lautsprecher zu sein behauptet. Ob es wirklich am Ohr sitzt, weiß nur der
     * Träger: Er bestätigt es einmal, danach gilt es als Kopfhörer.
     */
    UNSURE,

    /** Lautsprecher, Auto, Fernseher, das Handy selbst: Hier hören andere mit. */
    PUBLIC,
}

/**
 * Ein Ausgabegerät, so weit die Regel es kennen muss, ohne Android-Klassen.
 * [type] ist `AudioDeviceInfo.getType()`, [bluetoothClass] ist `BluetoothClass.getDeviceClass()`
 * (null = unbekannt oder kein Bluetooth-Gerät). [key] erkennt das Gerät wieder (Adresse oder Name),
 * [name] ist nur für Meldungen an den Nutzer.
 */
data class OutputDevice(val type: Int, val bluetoothClass: Int?, val key: String, val name: String)

/** Ergebnis der Prüfung, über welche Geräte eine Antwort gerade ausgegeben würde. */
sealed interface RouteVerdict {
    /** Alles geht in Kopfhörer: Es darf gesprochen werden. */
    data object Private : RouteVerdict

    /** [device] ist ein Bluetooth-Gerät, das der Träger noch nicht bestätigt hat. Bis dahin wird nicht gesprochen. */
    data class Ask(val device: OutputDevice) : RouteVerdict

    /** [device] ist kein Kopfhörer (null = es ist gar keiner da). */
    data class Public(val device: OutputDevice?) : RouteVerdict

    /** Android hat keinen Ausgabeweg genannt. Dann wird nicht geraten, sondern geschwiegen. */
    data object Unknown : RouteVerdict
}

/**
 * Die Kopfhörer-Regel: Antworten sind nur für den Träger bestimmt und können Persönliches aus dem
 * Gedächtnis enthalten. Sie dürfen deshalb nie aus einem Lautsprecher kommen.
 *
 * Warum reicht "ein Bluetooth-Gerät ist verbunden" nicht? Android meldet für Ohrhörer, Bluetooth-Box
 * und Auto-Freisprechanlage denselben Gerätetyp (TYPE_BLUETOOTH_A2DP bzw. TYPE_BLUETOOTH_SCO). Auch der
 * Typ "Bluetooth-LE-Headset" hilft nicht: Android 15 vergibt ihn an JEDES LE-Audio-Gerät, auch an einen
 * Lautsprecher.
 *
 * Warum reicht auch die Geräteklasse nicht? Jedes Bluetooth-Gerät meldet von sich aus, was es ist ("ich
 * bin ein Kopfhörer", "ich bin ein Lautsprecher"). Darauf ist nur in eine Richtung Verlass: Wer sich als
 * Lautsprecher oder Auto meldet, ist einer. Umgekehrt geben sich manche Bluetooth-Boxen als Headset aus
 * (dokumentiert ist ein Lautsprecher mit der Klasse 0x260404, "Wearable Headset"), und manche Ohrhörer
 * melden dieselbe Klasse wie eine Freisprechanlage. Deshalb gilt für Bluetooth:
 *  - Meldet sich das Gerät als Lautsprecher, Auto, Anlage oder Bildschirm, ist es kein Kopfhörer
 *    ([OutputKind.PUBLIC]) und lässt sich auch nicht dazu erklären.
 *  - Jedes andere Bluetooth-Gerät bestätigt der Träger EINMAL selbst ([OutputKind.UNSURE]). Nur er weiß,
 *    ob es in seinem Ohr steckt. Die App merkt sich das Gerät und fragt nicht wieder.
 *
 * Grenzen, die sich nicht beheben lassen:
 *  - Ein Kabel (Klinke, USB) in einen Lautsprecher sieht für Android genauso aus wie ein Kopfhörer am Kabel.
 *  - Bestätigt der Träger eine Box, die sich als Headset ausgibt, gilt sie fortan als Kopfhörer. Die
 *    Rückfrage nennt deshalb den Namen des Geräts und warnt davor.
 *  - Wecker und Klingeltöne spielt Android über Lautsprecher UND Kopfhörer zugleich. Bei Kopfhörern am
 *    Kabel nimmt eine Antwort dann denselben Weg. Solange so ein Ton läuft, schweigt die App darum
 *    ([sharesSpeaker]); bemerken kann sie nur Töne, die Android anderen Apps auch meldet.
 *
 * Die Zahlen unten sind die Konstanten aus `android.media.AudioDeviceInfo` und
 * `android.bluetooth.BluetoothClass.Device`. Sie stehen hier als einfache Zahlen, damit die Regel ohne
 * Android testbar bleibt; ein Test in :app vergleicht sie mit den echten Konstanten.
 */
object OutputRule {
    const val TYPE_BUILTIN_EARPIECE = 1
    const val TYPE_BUILTIN_SPEAKER = 2
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_WIRED_HEADPHONES = 4
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_TELEPHONY = 18
    const val TYPE_USB_HEADSET = 22
    const val TYPE_HEARING_AID = 23
    const val TYPE_BUILTIN_SPEAKER_SAFE = 24
    const val TYPE_REMOTE_SUBMIX = 25
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_SPEAKER = 27
    const val TYPE_BLE_HEARING_AID = 33

    const val CLASS_WEARABLE_HEADSET = 0x0404
    const val CLASS_HANDSFREE = 0x0408
    const val CLASS_LOUDSPEAKER = 0x0414
    const val CLASS_HEADPHONES = 0x0418
    const val CLASS_PORTABLE_AUDIO = 0x041C
    const val CLASS_CAR_AUDIO = 0x0420
    const val CLASS_HIFI_AUDIO = 0x0428
    const val CLASS_HEARING_AID = 0x044C

    /** `AudioAttributes.USAGE_ALARM` und `USAGE_NOTIFICATION_RINGTONE`: Wecker und Klingelton. */
    const val USAGE_ALARM = 4
    const val USAGE_RINGTONE = 6

    /**
     * Geräte, die nur am oder im Ohr zu hören sind. Kabel zählen dazu (siehe Grenzen oben). Hörgeräte haben
     * eigene Typen, die Android nur über die Funkprofile für Hörgeräte vergibt; eine Box bekommt sie nicht.
     */
    private val EAR_TYPES = setOf(
        TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET, TYPE_HEARING_AID, TYPE_BLE_HEARING_AID,
    )

    /** Bluetooth-Typen, hinter denen Ohrhörer, Box oder Auto stecken können. */
    private val BLUETOOTH_TYPES = setOf(TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET)

    /** Klassen, mit denen ein Gerät sagt, es sitze am Ohr. Nur für die Wortwahl der Rückfrage ([claimsEar]). */
    private val EAR_CLASSES = setOf(CLASS_WEARABLE_HEADSET, CLASS_HEADPHONES, CLASS_HEARING_AID)

    /**
     * Bluetooth-Klassen, mit denen ein Gerät selbst sagt, dass es NICHT am Ohr sitzt: Lautsprecher,
     * tragbare Anlage, Auto, Set-Top-Box, HiFi-Anlage, Videorekorder, Kameras, Bildschirme, Konferenzgerät,
     * Spielzeug. Solche Geräte lassen sich auch nicht bestätigen.
     */
    private val LOUD_CLASSES = setOf(
        CLASS_LOUDSPEAKER, CLASS_PORTABLE_AUDIO, CLASS_CAR_AUDIO, 0x0424, CLASS_HIFI_AUDIO,
        0x042C, 0x0430, 0x0434, 0x0438, 0x043C, 0x0440, 0x0448,
    )

    /** Die eingebauten Ausgänge des Handys. */
    private val BUILT_IN = setOf(TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_SPEAKER_SAFE)

    /** Eingebaute Ausgänge und Wege, über die Medienton nie läuft (nur für [legacyMediaRoute]). */
    private val NO_MEDIA_SINK = BUILT_IN + setOf(0, TYPE_TELEPHONY, TYPE_REMOTE_SUBMIX, TYPE_BLUETOOTH_SCO)

    /** Ist das ein Bluetooth-Gerät, dessen Geräteklasse die Regel braucht? */
    fun isBluetooth(type: Int): Boolean = type in BLUETOOTH_TYPES

    /** Sagt das Gerät von sich selbst, es sitze am Ohr? Privat macht es das nicht, nur die Rückfrage kürzer. */
    fun claimsEar(bluetoothClass: Int?): Boolean = bluetoothClass in EAR_CLASSES

    /**
     * Spielt Android Töne dieser Art (`AudioAttributes.getUsage()`) auch dann über den Handy-Lautsprecher,
     * wenn Kopfhörer verbunden sind? Das gilt für Wecker und Klingeltöne: Sie sollen auch zu hören sein,
     * wenn die Kopfhörer gerade auf dem Tisch liegen.
     */
    fun sharesSpeaker(usage: Int): Boolean = usage == USAGE_ALARM || usage == USAGE_RINGTONE

    /** Die eigentliche Regel für ein einzelnes Gerät. */
    fun kind(type: Int, bluetoothClass: Int?): OutputKind = when (type) {
        in EAR_TYPES -> OutputKind.PRIVATE
        // Bluetooth: Ein "ich bin ein Lautsprecher" wird geglaubt. Bei allem anderen (auch bei "ich bin ein
        // Kopfhörer", Freisprechgerät, nicht eingeordnet, Klasse nicht lesbar) entscheidet der Träger.
        in BLUETOOTH_TYPES -> if (bluetoothClass in LOUD_CLASSES) OutputKind.PUBLIC else OutputKind.UNSURE
        // Alles andere (Handy-Lautsprecher, Hörmuschel, HDMI, Dock, Bluetooth-LE-Lautsprecher ...) hören andere mit.
        else -> OutputKind.PUBLIC
    }

    /**
     * Urteil über einen ganzen Ausgabeweg. [devices] sind die Geräte, über die der Ton JETZT liefe
     * (meist eines). [confirmed] sind die Schlüssel der Bluetooth-Geräte, die der Träger bestätigt hat.
     * Schon ein einziges lautes Gerät genügt für ein Nein.
     */
    fun judge(devices: List<OutputDevice>, confirmed: Set<String>): RouteVerdict {
        if (devices.isEmpty()) return RouteVerdict.Unknown
        var doubtful: OutputDevice? = null
        for (device in devices) {
            when (kind(device.type, device.bluetoothClass)) {
                OutputKind.PRIVATE -> Unit
                OutputKind.PUBLIC -> return RouteVerdict.Public(device)
                OutputKind.UNSURE -> when {
                    // Ohne Schlüssel ließe sich eine Bestätigung nicht wiedererkennen.
                    device.key.isBlank() -> return RouteVerdict.Public(device)
                    device.key !in confirmed -> if (doubtful == null) doubtful = device
                }
            }
        }
        return doubtful?.let { RouteVerdict.Ask(it) } ?: RouteVerdict.Private
    }

    /**
     * Nur für Android 12 (API 31/32), das die Frage "wohin ginge der Ton?" noch nicht beantwortet:
     * Schätzt den Weg für Medienton aus der Liste der verbundenen Ausgänge. Ein angeschlossenes
     * Gerät übernimmt dort den Ton vom Handy. Hängt nichts dran, bleibt der Handy-Lautsprecher.
     * Der Telefonie-Kanal eines Headsets (SCO) trägt keinen Medienton und zählt darum nicht.
     */
    fun legacyMediaRoute(connected: List<OutputDevice>): List<OutputDevice> {
        val sinks = connected.filter { it.type !in NO_MEDIA_SINK }
        if (sinks.isNotEmpty()) return sinks
        return listOf(
            connected.firstOrNull { it.type == TYPE_BUILTIN_SPEAKER } ?: OutputDevice(TYPE_BUILTIN_SPEAKER, null, "", "")
        )
    }

    /**
     * Meldung für den Nutzer, wenn nicht gesprochen werden darf; null, wenn alles in Ordnung ist.
     * [starting] = die Prüfung beim Start (der Nutzer kann noch etwas tun), sonst mitten im Betrieb.
     * [canReadBluetooth] = false, wenn die Berechtigung fehlt, mit der die App Bluetooth-Geräteklassen liest.
     * [callLink] = true, wenn die Antwort über den Telefonkanal eines Headsets läuft (Kopfhörer-Mikro). Fällt
     * der Ton dort auf das Handy zurück, sind nicht die Kopfhörer weg, sondern dieser Kanal ist abgerissen.
     */
    fun refusal(
        verdict: RouteVerdict,
        starting: Boolean,
        canReadBluetooth: Boolean = true,
        callLink: Boolean = false,
    ): String? = when (verdict) {
        RouteVerdict.Private -> null
        RouteVerdict.Unknown -> {
            val text = if (starting) "Der Weg der Tonausgabe ließ sich nicht prüfen. Ohne diese Prüfung hört die App nicht zu."
            else "Der Weg der Tonausgabe ließ sich nicht mehr prüfen. Das Zuhören wurde beendet."
            // Ohne die Bluetooth-Berechtigung gibt Android manche Auskunft nur verschleiert heraus.
            if (canReadBluetooth) text else "$text Bitte der App die Berechtigung „Geräte in der Nähe“ geben."
        }
        is RouteVerdict.Public -> {
            // Das Handy selbst (Lautsprecher, Hörmuschel) braucht keinen Namen: Dann fehlt schlicht der Kopfhörer.
            val name = verdict.device?.takeIf { it.type !in BUILT_IN }?.name.orEmpty()
            when {
                name.isBlank() && starting -> "Bitte zuerst Kopfhörer verbinden: Die Antworten sollen nur Sie hören."
                name.isBlank() && callLink -> CALL_LINK_LOST
                name.isBlank() -> "Kopfhörer getrennt. Das Zuhören wurde beendet."
                starting -> "„$name“ ist kein Kopfhörer. Bitte Kopfhörer verbinden und als Ausgabe wählen: " +
                    "Die Antworten sollen nur Sie hören."
                else -> "Die Tonausgabe ist auf „$name“ gewechselt. Das Zuhören wurde beendet, " +
                    "damit keine Antwort laut zu hören ist."
            }
        }
        is RouteVerdict.Ask -> {
            val name = if (verdict.device.name.isBlank()) "ein Gerät" else "„${verdict.device.name}“"
            val seconds = OutputConfirmation.WINDOW_MS / 1000
            if (starting && claimsEar(verdict.device.bluetoothClass)) {
                // Der Normalfall beim ersten Start mit neuen Ohrhörern: kurz und ohne Misstrauen im Ton.
                "Die Antworten gingen an $name. Sind das Ihre Kopfhörer? Dann tippen Sie in den nächsten $seconds " +
                    "Sekunden noch einmal auf Start; die App merkt sich das Gerät und fragt nicht wieder. " +
                    "Bei einem Lautsprecher oder im Auto bitte nicht bestätigen: Dort hören andere mit."
            } else if (starting) {
                "Die Tonausgabe geht an $name, das sich nicht eindeutig als Kopfhörer zu erkennen gibt. Sind das Ihre " +
                    "Ohrhörer, tippen Sie in den nächsten $seconds Sekunden noch einmal " +
                    "auf Start; die App merkt sich das Gerät. Bei einem Lautsprecher oder im Auto bitte nicht bestätigen." +
                    if (canReadBluetooth) "" else " Ohne die Berechtigung „Geräte in der Nähe“ kann die App " +
                        "Bluetooth-Lautsprecher nicht selbst erkennen."
            } else {
                "Die Tonausgabe ist auf $name gewechselt, das nicht als Kopfhörer bestätigt ist. " +
                    "Das Zuhören wurde beendet."
            }
        }
    }

    /** Meldung, wenn der Telefonkanal zum Headset (Kopfhörer-Mikro) abgerissen ist und nicht zurückkommt. */
    const val CALL_LINK_LOST = "Die Verbindung zum Kopfhörer-Mikro ist abgerissen. Das Zuhören wurde beendet."
}

/**
 * Die einmalige Bestätigung eines Bluetooth-Geräts ("das sind wirklich meine Ohrhörer").
 *
 * Ablauf: Der Start wird abgelehnt und die Meldung bittet darum, noch einmal auf Start zu tippen.
 * Kommt der zweite Start für DASSELBE Gerät im Zeitfenster, gilt das als Bestätigung. Ein Doppeltipper
 * aus Versehen zählt nicht (dafür die Mindestzeit), und nach Ablauf des Fensters wird neu gefragt.
 * Die Uhrzeit kommt von außen, damit sich das testen lässt.
 */
class OutputConfirmation(
    private val windowMs: Long = WINDOW_MS,
    private val minMs: Long = MIN_MS,
) {
    private var key: String? = null
    private var askedAtMs = 0L

    /** Merkt sich, dass der Träger gerade nach [key] gefragt wurde. */
    @Synchronized
    fun ask(key: String, nowMs: Long) {
        this.key = key
        askedAtMs = nowMs
    }

    /** true genau einmal, wenn dieser Start die Antwort auf die Frage nach [key] ist. */
    @Synchronized
    fun answers(key: String, nowMs: Long): Boolean {
        val hit = this.key == key && nowMs - askedAtMs in minMs..windowMs
        if (hit) this.key = null
        return hit
    }

    /** Vergisst eine offene Frage. */
    @Synchronized
    fun forget() {
        key = null
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val MIN_MS = 1_500L
    }
}

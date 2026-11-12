package app.claudevoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Kopfhörer-Regel: Welche Ausgabegeräte hört nur der Träger?
 * Die Szenarien stammen aus dem Review: Bluetooth-Box statt Ohrhörer, das Auto übernimmt während der
 * Fahrt, die Ausgabe wird auf "Dieses Handy" umgestellt.
 */
class OutputRuleTest {
    private val speaker = OutputDevice(OutputRule.TYPE_BUILTIN_SPEAKER, null, "", "Handy")
    private val earpiece = OutputDevice(OutputRule.TYPE_BUILTIN_EARPIECE, null, "", "Handy")
    private val wired = OutputDevice(OutputRule.TYPE_WIRED_HEADSET, null, "", "Kabel-Headset")
    private fun a2dp(bluetoothClass: Int?, key: String = "AA:BB:CC:00:11:22", name: String = "Gerät") =
        OutputDevice(OutputRule.TYPE_BLUETOOTH_A2DP, bluetoothClass, key, name)
    private fun sco(bluetoothClass: Int?, key: String = "AA:BB:CC:00:11:22", name: String = "Gerät") =
        OutputDevice(OutputRule.TYPE_BLUETOOTH_SCO, bluetoothClass, key, name)

    private val earbuds = a2dp(OutputRule.CLASS_HEADPHONES, name = "Ohrhörer")
    private val box = a2dp(OutputRule.CLASS_LOUDSPEAKER, key = "11:22:33:44:55:66", name = "Box")
    private val car = a2dp(OutputRule.CLASS_CAR_AUDIO, key = "77:88:99:AA:BB:CC", name = "Auto")

    /** Die Ohrhörer des Trägers hat er schon einmal bestätigt. */
    private val mine = setOf(earbuds.key)

    private val bluetoothTypes = listOf(OutputRule.TYPE_BLUETOOTH_A2DP, OutputRule.TYPE_BLUETOOTH_SCO, OutputRule.TYPE_BLE_HEADSET)

    // --- einzelne Geräte ---------------------------------------------------------------------

    @Test fun `Kabel, USB-Headset und Hoergeraete gelten als Kopfhoerer`() {
        for (type in listOf(
            OutputRule.TYPE_WIRED_HEADSET, OutputRule.TYPE_WIRED_HEADPHONES, OutputRule.TYPE_USB_HEADSET,
            OutputRule.TYPE_HEARING_AID, OutputRule.TYPE_BLE_HEARING_AID,
        )) assertEquals("Typ $type", OutputKind.PRIVATE, OutputRule.kind(type, null))
    }

    @Test fun `ein Bluetooth-LE-Headset wird wie jedes Bluetooth-Geraet behandelt`() {
        // Android 15 gibt JEDEM LE-Audio-Gerät diesen Typ, auch einem Lautsprecher. Der Typ beweist also nichts.
        assertTrue(OutputRule.isBluetooth(OutputRule.TYPE_BLE_HEADSET))
        assertEquals(OutputKind.PUBLIC, OutputRule.kind(OutputRule.TYPE_BLE_HEADSET, OutputRule.CLASS_LOUDSPEAKER))
        assertEquals(OutputKind.UNSURE, OutputRule.kind(OutputRule.TYPE_BLE_HEADSET, OutputRule.CLASS_HEADPHONES))
        assertEquals("Klasse nicht lesbar", OutputKind.UNSURE, OutputRule.kind(OutputRule.TYPE_BLE_HEADSET, null))
        assertFalse("Kabel und das Handy selbst haben keine Bluetooth-Klasse", OutputRule.isBluetooth(OutputRule.TYPE_WIRED_HEADSET))
        assertFalse(OutputRule.isBluetooth(OutputRule.TYPE_BUILTIN_SPEAKER))
    }

    @Test fun `das Handy selbst und alles Unbekannte gelten als laut`() {
        val loud = listOf(
            OutputRule.TYPE_BUILTIN_SPEAKER, OutputRule.TYPE_BUILTIN_EARPIECE, OutputRule.TYPE_BUILTIN_SPEAKER_SAFE,
            OutputRule.TYPE_BLE_SPEAKER, OutputRule.TYPE_REMOTE_SUBMIX, OutputRule.TYPE_TELEPHONY,
            9 /* HDMI */, 11 /* USB-Gerät */, 13 /* Dock */, 30 /* LE-Rundsendung */, 0, 999,
        )
        for (type in loud) assertEquals("Typ $type", OutputKind.PUBLIC, OutputRule.kind(type, OutputRule.CLASS_HEADPHONES))
    }

    @Test fun `wer sich bei Bluetooth als Lautsprecher meldet ist einer`() {
        for (type in bluetoothTypes) {
            assertEquals(OutputKind.PUBLIC, OutputRule.kind(type, OutputRule.CLASS_LOUDSPEAKER))
            assertEquals(OutputKind.PUBLIC, OutputRule.kind(type, OutputRule.CLASS_PORTABLE_AUDIO))
            assertEquals(OutputKind.PUBLIC, OutputRule.kind(type, OutputRule.CLASS_CAR_AUDIO))
            assertEquals(OutputKind.PUBLIC, OutputRule.kind(type, OutputRule.CLASS_HIFI_AUDIO))
            assertEquals(OutputKind.PUBLIC, OutputRule.kind(type, 0x043C)) // Bildschirm mit Lautsprecher
        }
    }

    @Test fun `wer sich als Kopfhoerer meldet gilt trotzdem nicht von selbst als einer`() {
        // Manche Bluetooth-Boxen melden die Klasse "Headset" (0x0404); das Gerät sagt dort nur über sich selbst
        // aus, und nachprüfen kann es niemand. Die Klasse beweist also nur in eine Richtung etwas.
        for (type in bluetoothTypes) {
            assertEquals(OutputKind.UNSURE, OutputRule.kind(type, OutputRule.CLASS_WEARABLE_HEADSET))
            assertEquals(OutputKind.UNSURE, OutputRule.kind(type, OutputRule.CLASS_HEADPHONES))
            assertEquals(OutputKind.UNSURE, OutputRule.kind(type, OutputRule.CLASS_HEARING_AID))
        }
        assertTrue(OutputRule.claimsEar(OutputRule.CLASS_WEARABLE_HEADSET))
        assertTrue(OutputRule.claimsEar(OutputRule.CLASS_HEADPHONES))
        assertFalse(OutputRule.claimsEar(OutputRule.CLASS_HANDSFREE))
        assertFalse(OutputRule.claimsEar(OutputRule.CLASS_LOUDSPEAKER))
        assertFalse(OutputRule.claimsEar(null))
    }

    @Test fun `Freisprechklasse, fehlende und fremde Klassen entscheidet ebenfalls der Traeger`() {
        // Manche Ohrhörer melden dieselbe Klasse wie eine Auto-Freisprechanlage: nicht zu entscheiden.
        assertEquals(OutputKind.UNSURE, OutputRule.kind(OutputRule.TYPE_BLUETOOTH_A2DP, OutputRule.CLASS_HANDSFREE))
        assertEquals(OutputKind.UNSURE, OutputRule.kind(OutputRule.TYPE_BLUETOOTH_SCO, OutputRule.CLASS_HANDSFREE))
        assertEquals("Klasse nicht lesbar", OutputKind.UNSURE, OutputRule.kind(OutputRule.TYPE_BLUETOOTH_A2DP, null))
        assertEquals("nicht eingeordnet", OutputKind.UNSURE, OutputRule.kind(OutputRule.TYPE_BLUETOOTH_A2DP, 0x0400))
        assertEquals("gar keine Angabe", OutputKind.UNSURE, OutputRule.kind(OutputRule.TYPE_BLUETOOTH_A2DP, 0))
        assertEquals("Armbanduhr", OutputKind.UNSURE, OutputRule.kind(OutputRule.TYPE_BLUETOOTH_A2DP, 0x0704))
    }

    @Test fun `Wecker und Klingelton teilen sich den Weg mit dem Handy-Lautsprecher`() {
        assertTrue(OutputRule.sharesSpeaker(OutputRule.USAGE_ALARM))
        assertTrue(OutputRule.sharesSpeaker(OutputRule.USAGE_RINGTONE))
        // Musik (1), Telefonat (2), Benachrichtigung (5), Navigationsansage (12), Sprachassistent (16): nein.
        for (usage in listOf(0, 1, 2, 5, 12, 16)) assertFalse("Art $usage", OutputRule.sharesSpeaker(usage))
    }

    // --- ganze Ausgabewege -------------------------------------------------------------------

    @Test fun `bestaetigte Ohrhoerer und Kopfhoerer am Kabel sind in Ordnung`() {
        assertEquals(RouteVerdict.Private, OutputRule.judge(listOf(earbuds), mine))
        assertEquals(RouteVerdict.Private, OutputRule.judge(listOf(wired), emptySet()))
    }

    @Test fun `neue Ohrhoerer werden einmal erfragt, auch wenn sie sich als Kopfhoerer melden`() {
        assertEquals(RouteVerdict.Ask(earbuds), OutputRule.judge(listOf(earbuds), emptySet()))
        // Genau der Fall, um den es geht: eine Box, die sich als Headset ausgibt. Ohne Ja des Trägers bleibt sie stumm.
        val liar = a2dp(OutputRule.CLASS_WEARABLE_HEADSET, key = "11:22:33:44:55:77", name = "BT Speaker")
        assertEquals(RouteVerdict.Ask(liar), OutputRule.judge(listOf(liar), mine))
    }

    @Test fun `eine Bluetooth-Box zaehlt nicht als Kopfhoerer`() {
        assertEquals(RouteVerdict.Public(box), OutputRule.judge(listOf(box), emptySet()))
    }

    @Test fun `uebernimmt das Auto die Ausgabe ist der Weg nicht mehr privat`() {
        assertEquals(RouteVerdict.Public(car), OutputRule.judge(listOf(car), emptySet()))
        // Auch der Telefonkanal einer Freisprechanlage ist ohne Bestätigung nie "privat".
        val handsfree = sco(OutputRule.CLASS_HANDSFREE, key = "77:88:99:AA:BB:CC", name = "Auto")
        assertEquals(RouteVerdict.Ask(handsfree), OutputRule.judge(listOf(handsfree), emptySet()))
    }

    @Test fun `auf Dieses Handy umgestellt ist der Weg der Lautsprecher`() {
        assertEquals(RouteVerdict.Public(speaker), OutputRule.judge(listOf(speaker), emptySet()))
        assertEquals(RouteVerdict.Public(earpiece), OutputRule.judge(listOf(earpiece), emptySet()))
    }

    @Test fun `ein einziges lautes Geraet im Weg genuegt fuer ein Nein`() {
        assertEquals(RouteVerdict.Public(box), OutputRule.judge(listOf(earbuds, box), mine))
        assertEquals(RouteVerdict.Public(speaker), OutputRule.judge(listOf(speaker, earbuds), mine))
        // Solange ein Wecker klingelt, läuft der Ton über Kopfhörer am Kabel UND den Handy-Lautsprecher.
        assertEquals(RouteVerdict.Public(speaker), OutputRule.judge(listOf(wired, speaker), emptySet()))
    }

    @Test fun `ohne Auskunft von Android wird nicht geraten`() {
        assertEquals(RouteVerdict.Unknown, OutputRule.judge(emptyList(), setOf("AA:BB:CC:00:11:22")))
    }

    @Test fun `ein Bluetooth-Geraet wird erfragt und gilt nach Bestaetigung als Kopfhoerer`() {
        val doubtful = a2dp(OutputRule.CLASS_HANDSFREE, name = "Sport-Ohrhörer")
        assertEquals(RouteVerdict.Ask(doubtful), OutputRule.judge(listOf(doubtful), emptySet()))
        assertEquals(RouteVerdict.Ask(doubtful), OutputRule.judge(listOf(doubtful), setOf("ein anderes Gerät")))
        assertEquals(RouteVerdict.Private, OutputRule.judge(listOf(doubtful), setOf(doubtful.key)))
        // Musik- und Telefonkanal desselben Geräts teilen sich den Schlüssel: einmal bestätigt gilt für beide.
        assertEquals(RouteVerdict.Private, OutputRule.judge(listOf(sco(OutputRule.CLASS_HANDSFREE)), setOf(doubtful.key)))
    }

    @Test fun `eine Bestaetigung macht aus einem Lautsprecher keinen Kopfhoerer`() {
        assertEquals(RouteVerdict.Public(box), OutputRule.judge(listOf(box), setOf(box.key)))
        assertEquals(RouteVerdict.Public(speaker), OutputRule.judge(listOf(speaker), setOf(speaker.key)))
    }

    @Test fun `ein Bluetooth-Geraet ohne Schluessel laesst sich nicht bestaetigen und gilt als laut`() {
        val nameless = a2dp(null, key = "", name = "")
        assertEquals(RouteVerdict.Public(nameless), OutputRule.judge(listOf(nameless), setOf("")))
    }

    // --- Android 12: Weg aus der Geräteliste schätzen ----------------------------------------

    @Test fun `Android 12 - ohne angeschlossenes Geraet bleibt der Handy-Lautsprecher`() {
        assertEquals(listOf(speaker), OutputRule.legacyMediaRoute(listOf(earpiece, speaker)))
        assertEquals(RouteVerdict.Public(speaker), OutputRule.judge(OutputRule.legacyMediaRoute(listOf(earpiece, speaker)), emptySet()))
        // Selbst eine leere Liste ergibt "Lautsprecher" und nicht "unbekannt".
        assertTrue(OutputRule.judge(OutputRule.legacyMediaRoute(emptyList()), emptySet()) is RouteVerdict.Public)
    }

    @Test fun `Android 12 - der Telefonkanal eines Headsets traegt keinen Medienton`() {
        // Ausgabe auf "Dieses Handy" umgestellt: Der Musikkanal der Ohrhörer verschwindet, ihr Telefonkanal
        // bleibt in der Liste. Die alte Prüfung ließ das durchgehen, der Ton käme aus dem Lautsprecher.
        val connected = listOf(earpiece, speaker, sco(OutputRule.CLASS_HEADPHONES))
        assertEquals(RouteVerdict.Public(speaker), OutputRule.judge(OutputRule.legacyMediaRoute(connected), emptySet()))
    }

    @Test fun `Android 12 - Ohrhoerer ja, zusaetzliche Box nein`() {
        assertEquals(
            RouteVerdict.Private,
            OutputRule.judge(OutputRule.legacyMediaRoute(listOf(earpiece, speaker, earbuds, sco(OutputRule.CLASS_HEADPHONES))), mine),
        )
        assertEquals(
            RouteVerdict.Public(box),
            OutputRule.judge(OutputRule.legacyMediaRoute(listOf(speaker, earbuds, box)), mine),
        )
    }

    // --- Meldungen ---------------------------------------------------------------------------

    @Test fun `in Ordnung heisst keine Meldung`() {
        assertNull(OutputRule.refusal(RouteVerdict.Private, starting = true))
        assertNull(OutputRule.refusal(RouteVerdict.Private, starting = false))
    }

    @Test fun `ohne Kopfhoerer bittet der Start darum und der Betrieb meldet die Trennung`() {
        for (verdict in listOf(RouteVerdict.Public(null), RouteVerdict.Public(speaker), RouteVerdict.Public(earpiece))) {
            assertEquals(
                "Bitte zuerst Kopfhörer verbinden: Die Antworten sollen nur Sie hören.",
                OutputRule.refusal(verdict, starting = true),
            )
            assertEquals("Kopfhörer getrennt. Das Zuhören wurde beendet.", OutputRule.refusal(verdict, starting = false))
        }
    }

    @Test fun `ein lautes Geraet wird beim Namen genannt`() {
        assertTrue(OutputRule.refusal(RouteVerdict.Public(box), starting = true)!!.startsWith("„Box“ ist kein Kopfhörer."))
        assertTrue(OutputRule.refusal(RouteVerdict.Public(car), starting = false)!!.startsWith("Die Tonausgabe ist auf „Auto“ gewechselt."))
    }

    @Test fun `die Rueckfrage nennt Geraet, Frist und die Warnung`() {
        val doubtful = a2dp(OutputRule.CLASS_HANDSFREE, name = "Sport-Ohrhörer")
        // Beide Fassungen: Das Gerät meldet sich als Kopfhörer (der Normalfall) oder es bleibt unklar.
        for (device in listOf(earbuds, doubtful)) {
            val question = OutputRule.refusal(RouteVerdict.Ask(device), starting = true)!!
            assertTrue(question, "„${device.name}“" in question)
            assertTrue(question, "30 Sekunden noch einmal auf Start" in question)
            assertTrue(question, "Lautsprecher oder im Auto bitte nicht" in question)
            assertFalse("ohne Anlass kein Wort zur Berechtigung", "Berechtigung" in question)
        }
        assertTrue("nicht eindeutig" in OutputRule.refusal(RouteVerdict.Ask(doubtful), starting = true)!!)
        assertFalse(
            "kein Misstrauen, wo das Gerät sich als Kopfhörer meldet",
            "nicht eindeutig" in OutputRule.refusal(RouteVerdict.Ask(earbuds), starting = true)!!,
        )

        val withoutPermission = OutputRule.refusal(RouteVerdict.Ask(doubtful), starting = true, canReadBluetooth = false)!!
        assertTrue(withoutPermission, "Berechtigung „Geräte in der Nähe“" in withoutPermission)

        val running = OutputRule.refusal(RouteVerdict.Ask(doubtful), starting = false)!!
        assertTrue(running, "nicht als Kopfhörer bestätigt" in running && "beendet" in running)
        assertFalse("mitten im Betrieb wird nicht um Bestätigung gebeten", "Start" in running)
    }

    @Test fun `faellt der Ton beim Kopfhoerer-Mikro aufs Handy zurueck heisst der Grund Telefonkanal`() {
        // Kopfhörer-Mikro: Die Antwort läuft über den Telefonkanal des Headsets. Reißt er ab, landet der Ton
        // an der Hörmuschel. Die Kopfhörer sind dann nicht "getrennt"; die Meldung nennt den wahren Grund.
        for (verdict in listOf(RouteVerdict.Public(earpiece), RouteVerdict.Public(speaker))) {
            assertEquals(OutputRule.CALL_LINK_LOST, OutputRule.refusal(verdict, starting = false, callLink = true))
            assertEquals("Kopfhörer getrennt. Das Zuhören wurde beendet.", OutputRule.refusal(verdict, starting = false))
            // Beim Start bleibt es bei der Bitte um Kopfhörer.
            assertEquals(
                "Bitte zuerst Kopfhörer verbinden: Die Antworten sollen nur Sie hören.",
                OutputRule.refusal(verdict, starting = true, callLink = true),
            )
        }
        assertTrue("Kopfhörer-Mikro" in OutputRule.CALL_LINK_LOST)
        // Ein fremdes Gerät (das Auto übernimmt den Telefonkanal) wird weiter beim Namen genannt.
        assertTrue(OutputRule.refusal(RouteVerdict.Public(car), starting = false, callLink = true)!!.startsWith("Die Tonausgabe ist auf „Auto“"))
    }

    @Test fun `ein unbekannter Weg wird als solcher gemeldet`() {
        assertTrue("nicht prüfen" in OutputRule.refusal(RouteVerdict.Unknown, starting = true)!!)
        assertTrue("beendet" in OutputRule.refusal(RouteVerdict.Unknown, starting = false)!!)
        assertTrue("Geräte in der Nähe" in OutputRule.refusal(RouteVerdict.Unknown, starting = true, canReadBluetooth = false)!!)
    }

    // --- Bestätigung durch zweiten Start -----------------------------------------------------

    @Test fun `ein zweiter Start im Zeitfenster bestaetigt genau einmal`() {
        val c = OutputConfirmation()
        c.ask("AA", nowMs = 10_000)
        assertTrue(c.answers("AA", nowMs = 15_000))
        assertFalse("die Bestätigung ist verbraucht", c.answers("AA", nowMs = 16_000))
    }

    @Test fun `ohne vorherige Frage, zu frueh, zu spaet oder fuer ein anderes Geraet gilt nichts`() {
        assertFalse(OutputConfirmation().answers("AA", nowMs = 5_000))

        val doubleTap = OutputConfirmation()
        doubleTap.ask("AA", nowMs = 10_000)
        assertFalse("Doppeltipper aus Versehen", doubleTap.answers("AA", nowMs = 10_400))

        val late = OutputConfirmation()
        late.ask("AA", nowMs = 10_000)
        assertFalse(late.answers("AA", nowMs = 10_000 + OutputConfirmation.WINDOW_MS + 1))

        val other = OutputConfirmation()
        other.ask("AA", nowMs = 10_000)
        assertFalse(other.answers("BB", nowMs = 15_000))
        assertTrue("die Frage nach AA steht noch", other.answers("AA", nowMs = 16_000))

        val forgotten = OutputConfirmation()
        forgotten.ask("AA", nowMs = 10_000)
        forgotten.forget()
        assertFalse(forgotten.answers("AA", nowMs = 15_000))
    }
}

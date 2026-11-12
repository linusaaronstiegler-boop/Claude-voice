package app.claudevoice.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Meldungen des Dienstes: Wann lohnt ein neuer Verbindungsversuch, was steht in der Statuszeile,
 * und dass kein Schlüssel je in einer Meldung landet.
 *
 * Die aussichtslosen Fälle werden am Wortlaut erkannt, den die Transkriptions-Clients melden. Damit
 * das nicht unbemerkt auseinanderläuft, erzeugen die Tests diese Meldungen mit den echten Clients
 * (gegen einen nachgebauten Server, nie gegen den echten Dienst).
 */
class ServiceReportsTest {
    private val http = OkHttpClient()
    private var server: MockWebServer? = null
    private val open = ArrayList<Transcriber>()

    @After fun tearDown() {
        open.forEach { it.close() }
        runCatching { server?.shutdown() }
    }

    /** Sammelt, was ein Client über seine Verbindung meldet. */
    private class Reports : Transcriber.Listener {
        val errors = LinkedBlockingQueue<String>()
        override fun onPartial(text: String, turnProbability: Double?) {}
        override fun onCommitted(text: String, speaker: String?, segment: Int?) {}
        override fun onConnection(connected: Boolean, error: String?, fresh: Boolean) {
            if (!connected && error != null) errors += error
        }
        fun next(): String = errors.poll(5, TimeUnit.SECONDS) ?: error("Der Client hat nichts gemeldet")
    }

    private fun rejectingServer(code: Int): String =
        MockWebServer().also { server = it; it.enqueue(MockResponse().setResponseCode(code)); it.start() }
            .url("/").toString().removeSuffix("/")

    // --- Lohnt ein neuer Versuch? ------------------------------------------------------------

    @Test fun `ein vom Server abgelehnter Soniox-Key ist aussichtslos`() {
        val reports = Reports()
        SonioxClient("key-123", http, reports, rejectingServer(401)).also { open += it }.connect()
        val error = reports.next()
        assertEquals("Soniox-Key wurde abgelehnt (401)", error)
        assertTrue(ConnectionReport.isHopeless(error))
    }

    @Test fun `ein vom Server abgelehnter ElevenLabs-Key ist aussichtslos`() {
        val reports = Reports()
        ScribeClient("key-123", http, reports, rejectingServer(401)).also { open += it }.connect()
        val error = reports.next()
        assertEquals("ElevenLabs-Key wurde abgelehnt (401)", error)
        assertTrue(ConnectionReport.isHopeless(error))
    }

    @Test fun `ein Key mit unzulaessigen Zeichen ist bei beiden Anbietern aussichtslos`() {
        // So sähe ein Key aus, der an Prefs vorbei käme (dort entfernt cleanSecret solche Zeichen).
        val broken = "sk_0123\n456"
        val soniox = Reports()
        SonioxClient(broken, http, soniox, "http://127.0.0.1:9").also { open += it }.connect()
        val scribe = Reports()
        ScribeClient(broken, http, scribe, "http://127.0.0.1:9").also { open += it }.connect()
        for (error in listOf(soniox.next(), scribe.next())) {
            assertTrue(error, ConnectionReport.isHopeless(error))
            assertFalse("die Meldung verrät den Key", "0123" in error)
        }
    }

    @Test fun `Fehlermeldungen des ElevenLabs-Servers - Konto ja, Ueberlastung nein`() {
        for (type in listOf("auth_error", "quota_exceeded", "unaccepted_terms")) {
            assertTrue(type, ConnectionReport.isHopeless(ScribeClient.errorText(type, "")))
        }
        for (type in listOf("rate_limited", "resource_exhausted", "transcriber_error")) {
            assertFalse(type, ConnectionReport.isHopeless(ScribeClient.errorText(type, "kurz gestört")))
        }
    }

    @Test fun `Soniox - Guthaben und verweigerter Zugriff sind aussichtslos, Limit und Stoerung nicht`() {
        assertTrue(ConnectionReport.isHopeless("Soniox-Guthaben oder Budget ist aufgebraucht (402)"))
        assertTrue(ConnectionReport.isHopeless("Soniox verweigert den Zugriff (403)"))
        assertFalse(ConnectionReport.isHopeless("Soniox-Limit erreicht (429)"))
        assertFalse(ConnectionReport.isHopeless("Soniox ist kurz gestört (503)"))
    }

    @Test fun `Funkloch, Abbruch und gar kein Fehler lohnen einen neuen Versuch`() {
        assertFalse(ConnectionReport.isHopeless(null))
        assertFalse(ConnectionReport.isHopeless("Keine Verbindung zur Transkription: timeout"))
        assertFalse(ConnectionReport.isHopeless(ScribeClient.closeText(1006, "", sessionStood = true)))
        assertFalse(ConnectionReport.isHopeless("Transkription antwortet nicht mehr, neue Verbindung wird aufgebaut"))
    }

    // --- Statuszeile -------------------------------------------------------------------------

    @Test fun `die Statuszeile verspricht nur dann einen neuen Versuch wenn er helfen kann`() {
        assertEquals("Hört zu (Handy-Mikro)", ConnectionReport.status(true, null, "Handy-Mikro"))
        assertEquals("Verbindung unterbrochen, neuer Versuch …", ConnectionReport.status(false, null, "Handy-Mikro"))
        assertEquals(
            "Verbindung unterbrochen, neuer Versuch …",
            ConnectionReport.status(false, "Keine Verbindung zur Transkription: timeout", "Handy-Mikro"),
        )
        assertEquals("Keine Transkription", ConnectionReport.status(false, "ElevenLabs-Key wurde abgelehnt", "Handy-Mikro"))
        // Ein Hinweis bei stehender Verbindung ändert nichts daran, dass zugehört wird.
        assertEquals(
            "Hört zu (Kopfhörer-Mikro)",
            ConnectionReport.status(true, "Transkription läuft im einfachen Modus ohne Sprechertrennung", "Kopfhörer-Mikro"),
        )
    }

    @Test fun `die Schlussmeldung nennt den Grund und was zu tun ist`() {
        assertEquals(
            "ElevenLabs-Key wurde abgelehnt (401). Ein neuer Versuch würde daran nichts ändern, deshalb wurde das " +
                "Zuhören beendet. Bitte Schlüssel und Konto in den Einstellungen prüfen.",
            ConnectionReport.stopMessage("ElevenLabs-Key wurde abgelehnt (401)"),
        )
    }

    // --- Schlüssel ---------------------------------------------------------------------------

    @Test fun `eine Startmeldung nennt nur die Art des Fehlers und nie seinen Text`() {
        val key = "sk_0123456789abcdef"
        // So meldet sich OkHttp, wenn ein Key ein unzulässiges Zeichen enthält: mit dem ganzen Key im Text.
        val leaky = runCatching {
            Request.Builder().url("http://127.0.0.1:9/").header("xi-api-key", "$key​").build()
        }.exceptionOrNull() ?: return fail("OkHttp hätte den Key ablehnen müssen")
        assertTrue("Gegenprobe: der Fehlertext enthält den Key", key in leaky.message.orEmpty())
        val message = StartFailure.describe(leaky)
        assertEquals("Start fehlgeschlagen (IllegalArgumentException). Bitte noch einmal versuchen.", message)
        assertFalse(key in message)
    }

    @Test fun `eingefuegte Schluessel werden von unsichtbaren Zeichen befreit`() {
        val key = "sk_0123456789abcdef"
        assertEquals(key, cleanSecret(key))
        assertEquals(key, cleanSecret(" $key \n"))
        assertEquals("Zero-Width-Space am Ende", key, cleanSecret("$key​"))
        assertEquals("typografische Anführungszeichen", key, cleanSecret("„$key“"))
        assertEquals("Zeilenumbruch und Tabulator mittendrin", key, cleanSecret("sk_0123\r\n\t456789abcdef"))
        assertEquals("", cleanSecret("  \n"))
        // Alle sichtbaren ASCII-Zeichen bleiben, auch Sonderzeichen (Voice-IDs und Keys nutzen Buchstaben, Ziffern, _ und -).
        assertEquals("aZ09_-.~!", cleanSecret("aZ09_-.~!"))
    }

    @Test fun `ein bereinigter Schluessel laesst sich immer als HTTP-Kopf verschicken`() {
        for (pasted in listOf("sk_0123456789abcdef​", "„sk_0123456789abcdef“", "sk_0123\n456", "sk_ä0123ß")) {
            // Wirft OkHttp hier nicht, kann auch keiner der Clients (ElevenLabs, Soniox) an diesem Key scheitern.
            Request.Builder().url("http://127.0.0.1:9/")
                .header("xi-api-key", cleanSecret(pasted))
                .header("Authorization", "Bearer ${cleanSecret(pasted)}")
                .build()
        }
    }
}

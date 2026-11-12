package app.claudevoice

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.claudevoice.core.cleanSecret

/**
 * Einstellungen und API-Schlüssel. Die Datei wird mit einem Schlüssel aus dem
 * Android Keystore verschlüsselt, die Keys liegen also nie im Klartext auf dem Gerät.
 */
class Prefs(context: Context) {
    // Die verschlüsselte Datei wird je Prozess nur einmal geöffnet (siehe [open]). Das Öffnen fragt den
    // Android Keystore und kostet spürbar Zeit; Oberfläche, Dienst und Gedächtnis teilen sich darum eine Instanz.
    private val sp = open(context)

    // Schlüssel und Voice-ID werden beim Schreiben UND beim Lesen von unsichtbaren Zeichen befreit
    // (siehe cleanSecret). Das Lesen heilt Werte, die eine ältere Version schon so gespeichert hat.
    var anthropicKey: String
        get() = cleanSecret(sp.getString("anthropic", "") ?: "")
        set(v) = sp.edit().putString("anthropic", cleanSecret(v)).apply()

    var elevenKey: String
        get() = cleanSecret(sp.getString("eleven", "") ?: "")
        set(v) = sp.edit().putString("eleven", cleanSecret(v)).apply()

    /** Optional: Schlüssel für Soniox als zweiten Transkriptions-Anbieter. Ist er gesetzt, wird Soniox genutzt. */
    var sonioxKey: String
        get() = cleanSecret(sp.getString("soniox", "") ?: "")
        set(v) = sp.edit().putString("soniox", cleanSecret(v)).apply()

    /** Stimme für die Sprachausgabe; Standard ist die Beispielstimme aus der ElevenLabs-Doku. */
    var voiceId: String
        get() = cleanSecret(sp.getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE).ifEmpty { DEFAULT_VOICE }
        set(v) = sp.edit().putString("voice", cleanSecret(v).ifEmpty { DEFAULT_VOICE }).apply()

    /**
     * true (Standard) = Kopfhörer-Mikro (z. B. AirPods): hört vor allem den Träger.
     * false = Handy-Mikro: hört den Raum, die Antwort kommt in voller Qualität über die Kopfhörer.
     *
     * Warum das Kopfhörer-Mikro der Standard ist: Nur dort ist die eigene Stimme mit Abstand die lauteste.
     * Darauf baut die Erkennung "das war er selbst" auf, und an ihr hängen das Merken, das Stummschalten per
     * Sprache und die Reichweite "Nur ich". Am Handy-Mikro klingen alle am Tisch ähnlich laut.
     *
     * Der Preis: Das Kopfhörer-Mikro gibt es nur im Telefonie-Modus von Bluetooth, und der hat drei
     * Nebenwirkungen, solange zugehört wird (auch in der Bereitschaft):
     *  1. Andere Apps bekommen kein Mikrofon: Sprachnachrichten, Diktat und der Ton von Videoaufnahmen
     *     bleiben stumm. Android behandelt die App wie ein laufendes Telefonat.
     *  2. Musik und aller andere Ton laufen über den Telefonkanal der Kopfhörer, also mono und in
     *     Telefonqualität.
     *  3. Ein Druck auf die Kopfhörer-Taste gilt als "Auflegen": Er trennt den Telefonkanal, statt nur die
     *     Antwort zu stoppen. Eine laufende Antwort verstummt dabei sofort. Kommt der Kanal nicht binnen
     *     etwa drei Sekunden zurück, endet das Zuhören mit einer Meldung.
     * Wer Musik hören will oder das Handy im Hörsaal auf den Tisch legt, wählt das Handy-Mikro.
     */
    var useBluetoothMic: Boolean
        get() = sp.getBoolean("btmic", true)
        set(v) = sp.edit().putBoolean("btmic", v).apply()

    /** Name, auf den der Assistent hört ("Alfred, ..."). */
    var assistantName: String
        get() = sp.getString("name", DEFAULT_NAME) ?: DEFAULT_NAME
        set(v) = sp.edit().putString("name", v.trim().ifEmpty { DEFAULT_NAME }).apply()

    /** Weitere Rufnamen, mit Komma getrennt. "Jarvis" ist zugleich das Weckwort im Bereitschaftsmodus. */
    var aliases: List<String>
        get() = (sp.getString("aliases", DEFAULT_ALIASES) ?: DEFAULT_ALIASES).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("aliases", v.joinToString(", ")).apply()

    /** Charakterbeschreibung des Assistenten; leer = Standard (Alfred). */
    var character: String
        get() = sp.getString("character", "") ?: ""
        set(v) = sp.edit().putString("character", v.trim()).apply()

    /**
     * "dauer" = hört durchgehend mit und beantwortet Fragen im Gespräch.
     * "weckwort" = lauscht nur lokal auf das Weckwort und hört erst danach zu (wie "Hey Google").
     */
    var listenMode: String
        get() = sp.getString("mode", "dauer") ?: "dauer"
        set(v) = sp.edit().putString("mode", v).apply()

    /** Wie der Assistent den Träger anspricht. */
    var address: String
        get() = sp.getString("address", DEFAULT_ADDRESS) ?: DEFAULT_ADDRESS
        set(v) = sp.edit().putString("address", v.trim().ifEmpty { DEFAULT_ADDRESS }).apply()

    /** Freier Text "Über mich", Teil des Gedächtnisses. */
    var about: String
        get() = sp.getString("about", "") ?: ""
        set(v) = sp.edit().putString("about", v.trim()).apply()

    /** Verweise auf verknüpfte Gedächtnis-Dateien (Storage Access Framework). */
    var linkedUris: List<String>
        get() = (sp.getString("linked", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("linked", v.joinToString("\n")).apply()

    /** Wessen Äußerungen eine Antwort auslösen dürfen: "ich", "gespraech" oder "raum". */
    var reach: String
        get() = sp.getString("reach", "gespraech") ?: "gespraech"
        set(v) = sp.edit().putString("reach", v).apply()

    /** true = eigene Musik während der Antwort pausieren, false = nur leiser stellen. */
    var pauseMusic: Boolean
        get() = sp.getBoolean("pausemusic", true)
        set(v) = sp.edit().putBoolean("pausemusic", v).apply()

    /** Erscheinungsbild der Oberfläche: "system" (folgt dem Handy), "hell" oder "dunkel". */
    var appearance: String
        get() = sp.getString("appearance", "system") ?: "system"
        set(v) = sp.edit().putString("appearance", v).apply()

    /**
     * Ausgabegeräte, die der Träger selbst als seine Kopfhörer bestätigt hat (Bluetooth-Adresse oder Name).
     * Jedes Bluetooth-Gerät wird einmal erfragt, denn ob es am Ohr sitzt, lässt sich ihm nicht verlässlich
     * ansehen: Manche Lautsprecher geben sich als Headset aus (siehe OutputRule in :core).
     * Leeren hebt alle Bestätigungen wieder auf.
     */
    var confirmedOutputs: Set<String>
        get() = (sp.getString("outputs", "") ?: "").split('\n').filter { it.isNotBlank() }.toSet()
        set(v) = sp.edit().putString("outputs", v.joinToString("\n")).apply()

    /** Gelerntes Stimmprofil je Mikrofon-Art, als "pegel;tonhoehe;proben". */
    fun voiceProfile(mic: String): String? = sp.getString("voice_$mic", null)
    fun saveVoiceProfile(mic: String, value: String) = sp.edit().putString("voice_$mic", value).apply()

    val hasKeys: Boolean get() = anthropicKey.isNotEmpty() && elevenKey.isNotEmpty()

    companion object {
        const val DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"
        const val DEFAULT_NAME = "Alfred"
        const val DEFAULT_ADDRESS = "Sir"
        const val DEFAULT_ALIASES = "Jarvis"

        @Volatile private var shared: SharedPreferences? = null

        /** Öffnet die verschlüsselte Datei beim ersten Aufruf und gibt danach immer dieselbe Instanz zurück. */
        private fun open(context: Context): SharedPreferences = shared ?: synchronized(this) {
            shared ?: context.applicationContext.let { app ->
                EncryptedSharedPreferences.create(
                    app,
                    "claude_voice_prefs",
                    MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.also { shared = it }
        }
    }
}

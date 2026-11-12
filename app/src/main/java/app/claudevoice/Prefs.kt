package app.claudevoice

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Einstellungen und API-Schlüssel. Die Datei wird mit einem Schlüssel aus dem
 * Android Keystore verschlüsselt, die Keys liegen also nie im Klartext auf dem Gerät.
 */
class Prefs(context: Context) {
    private val sp = EncryptedSharedPreferences.create(
        context,
        "claude_voice_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var anthropicKey: String
        get() = sp.getString("anthropic", "") ?: ""
        set(v) = sp.edit().putString("anthropic", v.trim()).apply()

    var elevenKey: String
        get() = sp.getString("eleven", "") ?: ""
        set(v) = sp.edit().putString("eleven", v.trim()).apply()

    /** Stimme für die Sprachausgabe; Standard ist die Beispielstimme aus der ElevenLabs-Doku. */
    var voiceId: String
        get() = sp.getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(v) = sp.edit().putString("voice", v.trim().ifEmpty { DEFAULT_VOICE }).apply()

    /** true = AirPods-Mikro (hört vor allem dich), false = Handy-Mikro (hört den Raum). */
    var useBluetoothMic: Boolean
        get() = sp.getBoolean("btmic", true)
        set(v) = sp.edit().putBoolean("btmic", v).apply()

    /** Name, auf den der Assistent hört ("Alfred, ..."). */
    var assistantName: String
        get() = sp.getString("name", DEFAULT_NAME) ?: DEFAULT_NAME
        set(v) = sp.edit().putString("name", v.trim().ifEmpty { DEFAULT_NAME }).apply()

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

    /** Gelerntes Stimmprofil je Mikrofon-Art, als "pegel;tonhoehe;proben". */
    fun voiceProfile(mic: String): String? = sp.getString("voice_$mic", null)
    fun saveVoiceProfile(mic: String, value: String) = sp.edit().putString("voice_$mic", value).apply()

    val hasKeys: Boolean get() = anthropicKey.isNotEmpty() && elevenKey.isNotEmpty()

    companion object {
        const val DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"
        const val DEFAULT_NAME = "Alfred"
        const val DEFAULT_ADDRESS = "Sir"
    }
}

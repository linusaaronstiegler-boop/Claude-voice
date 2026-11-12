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

    val hasKeys: Boolean get() = anthropicKey.isNotEmpty() && elevenKey.isNotEmpty()

    companion object {
        const val DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    }
}

package app.claudevoice

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Nur für Tests: ein Ersatz für den "Android Keystore".
 *
 * Auf dem Handy liegt der Hauptschlüssel der verschlüsselten Einstellungen im Android Keystore, einem
 * Tresor des Betriebssystems. Auf dem Rechner (Robolectric) gibt es diesen Tresor nicht, und [Prefs]
 * ließe sich gar nicht öffnen. Dieser Ersatz meldet sich unter demselben Namen an und hebt die
 * Schlüssel einfach im Arbeitsspeicher auf. Verschlüsselt wird trotzdem echt (mit der Kryptografie
 * der Java-Laufzeit); sicher ist das nicht, aber die Tests laufen so durch denselben Code wie die App.
 */
object FakeAndroidKeyStore {
    private const val NAME = "AndroidKeyStore"
    private val keys = ConcurrentHashMap<String, Key>()

    /**
     * Für den Anfang jedes Tests: Tresor-Ersatz anmelden und die je Prozess geteilte Instanz von [Prefs]
     * vergessen (Robolectric gibt jedem Test eine frische App, die alte Instanz zeigte noch auf die vorige).
     */
    fun freshPrefs(context: android.content.Context): Prefs {
        install()
        forgetPrefs()
        return Prefs(context)
    }

    fun forgetPrefs() {
        Prefs::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
    }

    /** Tresor-Ersatz wieder abmelden, um einen Start ohne Keystore nachzustellen. */
    fun uninstall() = Security.removeProvider(NAME)

    /** Einmal je Testlauf anmelden. Mehrfach aufrufen ist harmlos. */
    @Synchronized
    fun install() {
        if (Security.getProvider(NAME) != null) return
        Security.addProvider(object : Provider(NAME, 1.0, "Schlüssel im Arbeitsspeicher, nur für Tests") {
            init {
                put("KeyStore.$NAME", Store::class.java.name)
                put("KeyGenerator.AES", AesGenerator::class.java.name)
            }
        })
    }

    /** Der Tresor: kennt nur Schlüssel unter einem Namen, keine Zertifikate. */
    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key
        }
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
        override fun engineSize(): Int = keys.size
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineLoad(param: KeyStore.LoadStoreParameter?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date = Date(0)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate?) = throw UnsupportedOperationException()
    }

    /** Erzeugt einen gewöhnlichen AES-Schlüssel und legt ihn unter dem gewünschten Namen in den Tresor. */
    class AesGenerator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            // Über den Methodennamen statt über die Klasse: So ist gleich, welche Kopie der Android-Klassen
            // der aufrufende Test gerade geladen hat.
            alias = params.javaClass.getMethod("getKeystoreAlias").invoke(params) as String
        }
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

        override fun engineGenerateKey(): SecretKey {
            // Der eigentliche Schlüssel kommt vom gewöhnlichen Erzeuger der Java-Laufzeit (nicht von uns selbst).
            val real = Security.getProviders("KeyGenerator.AES").first { it.name != NAME }
            val key = KeyGenerator.getInstance("AES", real).apply { init(256) }.generateKey()
            alias?.let { keys[it] = key }
            return key
        }
    }
}

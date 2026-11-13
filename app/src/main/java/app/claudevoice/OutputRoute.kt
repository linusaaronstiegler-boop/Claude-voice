package app.claudevoice

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import app.claudevoice.core.OutputDevice
import app.claudevoice.core.OutputRule
import app.claudevoice.core.RouteVerdict
import java.util.concurrent.ConcurrentHashMap

/**
 * Beantwortet die Frage "Wo käme eine Antwort jetzt heraus, und hört dort nur der Träger?".
 *
 * Die Entscheidung selbst steht in [OutputRule] (in :core, dort getestet). Hier wird nur bei Android
 * nachgefragt:
 *  - Wohin ginge Ton mit bestimmten Eigenschaften? Ab Android 13 sagt das
 *    `AudioManager.getAudioDevicesForAttributes`. Auf Android 12 gibt es diese Auskunft nicht; dort wird
 *    der Weg aus der Liste der verbundenen Geräte geschätzt (siehe [OutputRule.legacyMediaRoute]).
 *  - Was für ein Bluetooth-Gerät ist das? Das steht in seiner Geräteklasse. Um sie zu lesen, braucht die
 *    App die Berechtigung BLUETOOTH_CONNECT ("Geräte in der Nähe"). Fehlt sie, bleibt die Klasse
 *    unbekannt; ein Lautsprecher, der sich ehrlich als solcher meldet, wird dann nicht von selbst erkannt.
 *  - Läuft gerade ein Wecker oder Klingelton ([loudSoundPlaying])?
 *
 * [confirmed] liefert die Bluetooth-Geräte, die der Träger als seine Kopfhörer bestätigt hat (aus den Einstellungen).
 */
class OutputRoute(context: Context, private val confirmed: () -> Set<String>) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    // Die Klasse eines Bluetooth-Geräts ändert sich nicht. Einmal gelesen, kostet die Prüfung vor jeder
    // Antwort keine weitere Anfrage an den Bluetooth-Dienst.
    private val classes = ConcurrentHashMap<String, Int>()

    val canReadBluetooth: Boolean
        get() = appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Urteil über den Weg, den Ton mit [attributes] jetzt nähme. */
    fun verdict(attributes: AudioAttributes): RouteVerdict = OutputRule.judge(route(attributes), confirmed())

    /** Urteil über ein einzelnes Gerät. */
    fun verdict(device: AudioDeviceInfo): RouteVerdict = verdict(listOf(device))

    /**
     * Urteil über die Geräte, über die ein Abspieler gerade WIRKLICH läuft. Das können mehrere zugleich
     * sein (Kopfhörer und Handy-Lautsprecher, solange ein Wecker klingelt); ein lautes genügt für ein Nein.
     */
    fun verdict(devices: List<AudioDeviceInfo>): RouteVerdict = OutputRule.judge(devices.map(::describe), confirmed())

    /**
     * Meldung für den Nutzer, falls [verdict] das Sprechen verbietet; sonst null.
     * [callLink] = die Antwort läuft über den Telefonkanal eines Headsets (siehe [OutputRule.refusal]).
     */
    fun refusal(verdict: RouteVerdict, starting: Boolean, callLink: Boolean = false): String? =
        OutputRule.refusal(verdict, starting, canReadBluetooth, callLink)

    /** Die Bluetooth-Headsets, deren Telefonkanal (und damit ihr Mikrofon) sich nutzen ließe. */
    private fun headsets(): List<AudioDeviceInfo> = audio.availableCommunicationDevices.filter {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    /**
     * Ein Headset, dessen Telefonkanal genutzt werden kann und das der Träger als seines bestätigt hat.
     * Eine Auto-Freisprechanlage meldet denselben Gerätetyp und wird hier aussortiert.
     */
    /**
     * Für die Fehlersuche auf dem Gerät: Welche Geräte bietet Android für den Telefonkanal an, und wie
     * beurteilt die App jedes davon? Gerätenamen kommen bewusst nicht vor, nur Typ-Nummer und Urteil.
     */
    fun headsetReport(): String {
        val all = audio.availableCommunicationDevices
        val parts = all.map { d ->
            val urteil = if (isHeadsetType(d)) verdict(d)::class.simpleName else "kein Headset"
            "Typ ${d.type}: $urteil"
        }
        return "Geräte in der Nähe erlaubt: ${if (canReadBluetooth) "ja" else "nein"}; " +
            if (parts.isEmpty()) "Android bietet kein Gerät an" else parts.joinToString(", ")
    }

    private fun isHeadsetType(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    fun privateHeadset(): AudioDeviceInfo? = headsets().firstOrNull { verdict(it) == RouteVerdict.Private }

    /**
     * Für die Rückfrage beim Start mit Kopfhörer-Mikro: ein Headset, das der Träger noch bestätigen könnte.
     * Nötig für Headsets ohne Musikkanal; nach ihnen würde sonst nie gefragt, weil Musik dort über das Handy läuft.
     */
    fun headsetToConfirm(): RouteVerdict.Ask? = headsets().firstNotNullOfOrNull { verdict(it) as? RouteVerdict.Ask }

    /**
     * Läuft gerade ein Wecker oder ein Klingelton? Android spielt beides über Lautsprecher UND Kopfhörer.
     * Hängen die Kopfhörer am Kabel, teilt sich eine Antwort dann denselben Weg und wäre laut zu hören.
     *
     * Android zeigt einer gewöhnlichen App von den Tönen anderer Apps nur, dass sie laufen und von welcher
     * Art sie sind; das genügt hier. Lässt sich die Liste nicht lesen, gilt "nein": Diese Prüfung ist eine
     * zusätzliche Sicherung, an ihr allein soll das Sprechen nicht hängen.
     */
    fun loudSoundPlaying(): Boolean = try {
        audio.activePlaybackConfigurations.any { OutputRule.sharesSpeaker(it.audioAttributes.usage) }
    } catch (e: RuntimeException) {
        false
    }

    private fun route(attributes: AudioAttributes): List<OutputDevice> = try {
        when {
            Build.VERSION.SDK_INT >= 33 -> audio.getAudioDevicesForAttributes(attributes).map(::describe)
            // Android 12, Telefonie-Modus: Der Ton folgt dem Gerät, das für Gespräche ausgewählt ist.
            attributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ->
                listOfNotNull(audio.communicationDevice).map(::describe)
            else -> OutputRule.legacyMediaRoute(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::describe))
        }
    } catch (e: RuntimeException) {
        // Kann Android die Frage nicht beantworten, gilt der Weg als unbekannt, und es wird nicht gesprochen.
        emptyList()
    }

    private fun describe(info: AudioDeviceInfo): OutputDevice {
        val name = info.productName?.toString().orEmpty().trim()
        val address = info.address.trim()
        return OutputDevice(
            type = info.type,
            // Auch für Bluetooth LE: Der Typ "LE-Headset" sagt nicht, ob es Ohrhörer oder eine Box sind.
            bluetoothClass = if (OutputRule.isBluetooth(info.type)) bluetoothClass(address) else null,
            // Musik- und Telefonkanal desselben Ohrhörers tragen dieselbe Adresse, zählen also als ein Gerät.
            key = address.ifEmpty { name },
            name = name,
        )
    }

    /**
     * Geräteklasse des Bluetooth-Geräts mit dieser Adresse (z. B. 0x0418 = Kopfhörer); null, wenn sie
     * sich nicht lesen lässt: Berechtigung fehlt, Adresse unbrauchbar, Bluetooth aus.
     */
    @SuppressLint("MissingPermission") // wird direkt davor geprüft; ein Entzug dazwischen landet im catch
    internal fun bluetoothClass(address: String): Int? {
        val mac = address.uppercase()
        classes[mac]?.let { return it }
        if (!canReadBluetooth || !BluetoothAdapter.checkBluetoothAddress(mac)) return null
        val found = try {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?.getRemoteDevice(mac)?.bluetoothClass?.deviceClass
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (found != null) classes[mac] = found
        return found
    }
}

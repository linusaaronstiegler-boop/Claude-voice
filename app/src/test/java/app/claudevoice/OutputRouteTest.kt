package app.claudevoice

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.claudevoice.core.OutputRule
import app.claudevoice.core.RouteVerdict
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * Die Android-Seite der Kopfhörer-Regel: Wird der ECHTE Ausgabeweg gefragt (nicht bloß, was verbunden
 * ist), und kommen die Angaben richtig bei der Regel in :core an?
 *
 * Läuft mit Robolectric, also mit dem echten Android-15-Framework-Code, aber einem nachgestellten
 * Audio- und Bluetooth-Dienst. Was ein echtes Handy mit echten Kopfhörern meldet, zeigt erst der Gerätetest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutputRouteTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val media = AndroidSpeaker.attributes(voiceCallRoute = false)
    private val call = AndroidSpeaker.attributes(voiceCallRoute = true)
    private var confirmed = emptySet<String>()
    private val route = OutputRoute(context) { confirmed }

    @Before fun grantBluetooth() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    /**
     * Ein Gerät, wie das echte Framework es baut: interner Typ (AudioSystem.DEVICE_OUT_*), Name und Adresse.
     * Nur so lässt sich einem Bluetooth-Gerät hier eine Adresse geben, über die seine Klasse gelesen wird.
     */
    private fun device(internalType: String, name: String, address: String): AudioDeviceInfo {
        val internal = Class.forName("android.media.AudioSystem").getField(internalType).getInt(null)
        val portClass = Class.forName("android.media.AudioDevicePort")
        val port = portClass
            .getMethod("createForTesting", Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .invoke(null, internal, name, address)
        return AudioDeviceInfo::class.java.getConstructor(portClass).newInstance(port)
    }

    /** Legt fest, wohin Android Ton mit diesen Eigenschaften leiten würde. */
    private fun routeTo(attributes: AudioAttributes, vararg devices: AudioDeviceInfo) =
        shadowOf(audio).setAudioDevicesForAttributes(attributes, ImmutableList.copyOf(devices))

    /** Echte Framework-Abbildung: interner Gerätetyp (AudioSystem.DEVICE_OUT_*) -> AudioDeviceInfo.TYPE_*. */
    private fun publicType(internalName: String): Int {
        val internal = Class.forName("android.media.AudioSystem").getField(internalName).getInt(null)
        return AudioDeviceInfo::class.java
            .getMethod("convertInternalDeviceToDeviceType", Int::class.javaPrimitiveType)
            .invoke(null, internal) as Int
    }

    // --- Grundlagen --------------------------------------------------------------------------

    @Test fun `Android gibt Box und Freisprechanlage denselben Typ wie Kopfhoerern`() {
        // Deshalb genügt der Typ nicht, und die Regel fragt bei Bluetooth nach der Geräteklasse.
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, publicType("DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES"))
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, publicType("DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER"))
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, publicType("DEVICE_OUT_BLUETOOTH_SCO_HEADSET"))
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, publicType("DEVICE_OUT_BLUETOOTH_SCO_CARKIT"))
    }

    @Test fun `die Zahlen der Regel sind die Konstanten von Android`() {
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, OutputRule.TYPE_BUILTIN_EARPIECE)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, OutputRule.TYPE_BUILTIN_SPEAKER)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, OutputRule.TYPE_WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, OutputRule.TYPE_WIRED_HEADPHONES)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, OutputRule.TYPE_BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, OutputRule.TYPE_BLUETOOTH_A2DP)
        assertEquals(AudioDeviceInfo.TYPE_TELEPHONY, OutputRule.TYPE_TELEPHONY)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, OutputRule.TYPE_USB_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_HEARING_AID, OutputRule.TYPE_HEARING_AID)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, OutputRule.TYPE_BUILTIN_SPEAKER_SAFE)
        assertEquals(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, OutputRule.TYPE_REMOTE_SUBMIX)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, OutputRule.TYPE_BLE_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_BLE_SPEAKER, OutputRule.TYPE_BLE_SPEAKER)
        // TYPE_BLE_HEARING_AID (33) und die Klasse "Hörgerät" (0x044C) kennt erst ein neueres Android als das,
        // gegen das die App gebaut wird; ihre Werte stammen aus der Android-Dokumentation.

        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET, OutputRule.CLASS_WEARABLE_HEADSET)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE, OutputRule.CLASS_HANDSFREE)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, OutputRule.CLASS_LOUDSPEAKER)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, OutputRule.CLASS_HEADPHONES)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO, OutputRule.CLASS_PORTABLE_AUDIO)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, OutputRule.CLASS_CAR_AUDIO)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO, OutputRule.CLASS_HIFI_AUDIO)
    }

    // --- Der Weg zählt, nicht die Geräteliste ------------------------------------------------

    @Test fun `Kopfhoerer am Kabel als Ausgabeweg sind in Ordnung`() {
        routeTo(media, device(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(RouteVerdict.Private, route.verdict(media))
        assertNull(route.refusal(route.verdict(media), starting = true))
    }

    @Test fun `auf Dieses Handy umgestellt zaehlt der Lautsprecher, auch wenn Kopfhoerer verbunden sind`() {
        // Szenario 3 aus dem Review: Kein Gerät wird entfernt, nur der Weg ändert sich.
        shadowOf(audio).setOutputDevices(
            listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), device(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        )
        routeTo(media, device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val verdict = route.verdict(media)
        assertTrue("$verdict", verdict is RouteVerdict.Public)
        assertEquals("Kopfhörer getrennt. Das Zuhören wurde beendet.", route.refusal(verdict, starting = false))
    }

    @Test fun `ein Bluetooth-Geraet ohne lesbare Klasse gilt nie von selbst als Kopfhoerer`() {
        // Szenario 1 und 2 aus dem Review: Box oder Auto haben denselben Typ wie Ohrhörer. Die Attrappe
        // kennt keine Bluetooth-Adresse, die Klasse bleibt also unbekannt: Das Gerät ist ein Zweifelsfall.
        routeTo(media, device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        val verdict = route.verdict(media)
        assertTrue("$verdict", verdict is RouteVerdict.Ask)
        assertTrue(route.refusal(verdict, starting = true)!!.contains("noch einmal auf Start"))

        // Erst die Bestätigung des Trägers macht es zum Kopfhörer.
        confirmed = setOf((verdict as RouteVerdict.Ask).device.key)
        assertEquals(RouteVerdict.Private, route.verdict(media))
    }

    @Test fun `nennt Android keinen Weg wird nicht gesprochen`() {
        routeTo(media)
        assertEquals(RouteVerdict.Unknown, route.verdict(media))
        assertTrue(route.refusal(route.verdict(media), starting = true)!!.contains("nicht prüfen"))
    }

    @Test fun `Medien- und Telefonweg werden getrennt beurteilt`() {
        // Kopfhörer-Mikro: Die Antwort läuft über den Telefonkanal. Dessen Weg zählt, nicht der für Musik.
        routeTo(media, device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        routeTo(call, device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(RouteVerdict.Private, route.verdict(media))
        assertTrue(route.verdict(call) is RouteVerdict.Public)
    }

    @Test fun `ein einzelnes Geraet laesst sich direkt beurteilen`() {
        assertEquals(RouteVerdict.Private, route.verdict(device(AudioDeviceInfo.TYPE_USB_HEADSET)))
        assertTrue(route.verdict(device(AudioDeviceInfo.TYPE_BLE_HEADSET)) is RouteVerdict.Ask)
        assertTrue(route.verdict(device(AudioDeviceInfo.TYPE_BLE_SPEAKER)) is RouteVerdict.Public)
        assertTrue(route.verdict(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)) is RouteVerdict.Public)
    }

    @Test fun `laeuft der Ton ueber mehrere Geraete zugleich genuegt ein lautes fuer ein Nein`() {
        // Solange ein Wecker klingelt, leitet Android den gemeinsamen Ausgang an Kopfhörer UND Lautsprecher.
        val both = listOf(device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(route.verdict(both) is RouteVerdict.Public)
        assertTrue(route.verdict(both.reversed()) is RouteVerdict.Public)
        assertEquals(RouteVerdict.Private, route.verdict(both.take(1)))
        assertEquals("ohne Auskunft wird nicht geraten", RouteVerdict.Unknown, route.verdict(emptyList<AudioDeviceInfo>()))
    }

    // --- Wecker und Klingelton ---------------------------------------------------------------

    private fun playing(vararg usages: Int) = shadowOf(audio).setActivePlaybackConfigurationsFor(
        usages.map { AudioAttributes.Builder().setUsage(it).build() }
    )

    @Test fun `ein laufender Wecker oder Klingelton wird bemerkt, Musik und Benachrichtigungen nicht`() {
        assertFalse("nichts läuft", route.loudSoundPlaying())
        playing(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        assertFalse(route.loudSoundPlaying())
        playing(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ALARM)
        assertTrue("Wecker", route.loudSoundPlaying())
        playing(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        assertTrue("Klingelton", route.loudSoundPlaying())
        playing()
        assertFalse("vorbei", route.loudSoundPlaying())
        // Die Zahlen der Regel sind die Konstanten von Android.
        assertEquals(AudioAttributes.USAGE_ALARM, OutputRule.USAGE_ALARM)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, OutputRule.USAGE_RINGTONE)
    }

    // --- Bluetooth-Klasse --------------------------------------------------------------------

    private fun pair(address: String, classOfDevice: Int) {
        val remote = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        val bluetoothClass = BluetoothClass::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(classOfDevice)
        shadowOf(remote).setBluetoothClass(bluetoothClass)
    }

    @Test fun `die Geraeteklasse eines Bluetooth-Geraets wird gelesen`() {
        // 0x240418 und 0x240414 sind vollständige Klassenangaben (mit Dienst-Bits), wie Geräte sie senden.
        pair("AA:BB:CC:DD:EE:01", 0x240418)
        pair("AA:BB:CC:DD:EE:02", 0x240414)
        assertEquals(OutputRule.CLASS_HEADPHONES, route.bluetoothClass("AA:BB:CC:DD:EE:01"))
        assertEquals("Kleinschreibung der Adresse stört nicht", OutputRule.CLASS_HEADPHONES, route.bluetoothClass("aa:bb:cc:dd:ee:01"))
        assertEquals(OutputRule.CLASS_LOUDSPEAKER, route.bluetoothClass("AA:BB:CC:DD:EE:02"))
    }

    @Test fun `ohne Berechtigung oder mit unbrauchbarer Adresse bleibt die Klasse unbekannt`() {
        assertNull(route.bluetoothClass(""))
        assertNull("verschleierte Adresse", route.bluetoothClass("XX:XX:XX:XX:EE:03"))

        pair("AA:BB:CC:DD:EE:04", 0x240418)
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(route.canReadBluetooth)
        assertNull("kein Absturz, sondern unbekannt", route.bluetoothClass("AA:BB:CC:DD:EE:04"))
    }

    // --- Lautsprecher, die wie Kopfhörer aussehen --------------------------------------------

    @Test fun `ein LE-Audio-Lautsprecher gilt nicht als Kopfhoerer`() {
        // Android 15 meldet JEDES LE-Audio-Ausgabegerät als TYPE_BLE_HEADSET, auch einen Lautsprecher
        // (nachgelesen im Framework-Code, mit dem diese Tests laufen). Der Typ allein genügt also nicht.
        val box = device("DEVICE_OUT_BLE_HEADSET", "Wohnzimmer-Box", "AA:BB:CC:DD:EE:10")
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, box.type)
        pair("AA:BB:CC:DD:EE:10", 0x240414) // meldet sich selbst als Lautsprecher
        routeTo(media, box)
        val verdict = route.verdict(media)
        assertTrue("$verdict", verdict is RouteVerdict.Public)
        assertTrue(route.refusal(verdict, starting = true)!!.startsWith("„Wohnzimmer-Box“ ist kein Kopfhörer."))

        // Und bestätigen lässt er sich auch nicht.
        confirmed = setOf("AA:BB:CC:DD:EE:10")
        assertTrue(route.verdict(media) is RouteVerdict.Public)
    }

    @Test fun `eine Box die sich als Headset ausgibt wird nicht ohne Rueckfrage genommen`() {
        // Klassische Bluetooth-Box mit der Klasse "Wearable Headset" (dokumentiertes Beispiel: 0x260404).
        // Der Geräteklasse ist hier nicht zu trauen; ob das Gerät am Ohr sitzt, weiß nur der Träger.
        val box = device("DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER", "BT Speaker", "AA:BB:CC:DD:EE:11")
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, box.type)
        pair("AA:BB:CC:DD:EE:11", 0x260404)
        routeTo(media, box)
        val verdict = route.verdict(media)
        assertTrue("Box mit Headset-Klasse gilt ohne Rückfrage als Kopfhörer: $verdict", verdict is RouteVerdict.Ask)
        val question = route.refusal(verdict, starting = true)!!
        assertTrue("die Rückfrage nennt das Gerät beim Namen: $question", "„BT Speaker“" in question)
        assertTrue(question, "Lautsprecher oder im Auto bitte nicht bestätigen" in question)
    }

    @Test fun `auch echte Ohrhoerer werden einmal erfragt und danach nicht wieder`() {
        val earbuds = device("DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES", "Ohrhörer", "AA:BB:CC:DD:EE:12")
        pair("AA:BB:CC:DD:EE:12", 0x240418) // meldet sich als Kopfhörer
        routeTo(media, earbuds)
        val verdict = route.verdict(media)
        assertTrue("$verdict", verdict is RouteVerdict.Ask)
        assertEquals("gemerkt wird die Adresse", "AA:BB:CC:DD:EE:12", (verdict as RouteVerdict.Ask).device.key)
        assertTrue(route.refusal(verdict, starting = true)!!.startsWith("Die Antworten gingen an „Ohrhörer“. Sind das Ihre Kopfhörer?"))

        confirmed = setOf("AA:BB:CC:DD:EE:12")
        assertEquals(RouteVerdict.Private, route.verdict(media))
        // Der Telefonkanal derselben Ohrhörer trägt dieselbe Adresse und ist damit ebenfalls bestätigt.
        assertEquals(RouteVerdict.Private, route.verdict(device("DEVICE_OUT_BLUETOOTH_SCO_HEADSET", "Ohrhörer", "AA:BB:CC:DD:EE:12")))
    }

    // --- Headset für das Kopfhörer-Mikro -----------------------------------------------------

    @Test fun `als Headset fuer das Mikrofon kommt nur ein Geraet des Traegers in Frage`() {
        val sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO) // könnte ebenso die Freisprechanlage im Auto sein
        shadowOf(audio).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), sco))
        assertNull("noch nicht bestätigt", route.privateHeadset())
        // Nach so einem Headset kann beim Start gefragt werden (wichtig, wenn es keinen Musikkanal hat).
        val ask = route.headsetToConfirm()
        assertEquals(route.verdict(sco), ask)

        confirmed = setOf(ask!!.device.key)
        assertEquals(sco, route.privateHeadset())
        assertNull("nichts mehr zu fragen", route.headsetToConfirm())
    }

    @Test fun `auch ein Bluetooth-LE-Headset wird erst nach Bestaetigung genommen`() {
        val headset = device(AudioDeviceInfo.TYPE_BLE_HEADSET)
        shadowOf(audio).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), headset))
        assertNull(route.privateHeadset())

        confirmed = setOf((route.verdict(headset) as RouteVerdict.Ask).device.key)
        assertEquals(headset, route.privateHeadset())
    }

    @Test fun `eine Freisprechanlage die sich als Auto meldet ist nie ein Headset des Traegers`() {
        val car = device("DEVICE_OUT_BLUETOOTH_SCO_CARKIT", "Auto", "AA:BB:CC:DD:EE:13")
        pair("AA:BB:CC:DD:EE:13", 0x200420) // Klasse "Auto"
        shadowOf(audio).setAvailableCommunicationDevices(listOf(car))
        confirmed = setOf("AA:BB:CC:DD:EE:13")
        assertNull(route.privateHeadset())
        assertNull("und es wird auch nicht danach gefragt", route.headsetToConfirm())
    }
}

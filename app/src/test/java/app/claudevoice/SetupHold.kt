package app.claudevoice

import org.junit.Assert.assertTrue
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nur für Tests: hält den Aufbau des Dienstes mitten in seinem ersten Hintergrund-Schritt an, bis der
 * Test ihn weiterschickt.
 *
 * Wozu? Der Dienst liest beim Start zuerst die Einstellungen, und zwar im Hintergrund. Tests wie "Stopp
 * direkt nach Start" müssen genau den Augenblick treffen, in dem dieser Schritt noch läuft. Früher
 * verließen sie sich darauf, dass der Hintergrund langsamer ist als der Test. Das stimmt fast immer, aber
 * nicht auf einem ausgelasteten Rechner, und daran ist der Bau in der CI gelegentlich gescheitert:
 *
 *  1. Robolectric arbeitet nach jedem Befehl an den Dienst ab, was sich für den Haupt-Thread angesammelt
 *     hat (`ServiceController.startCommand` ruft am Ende `idleIfPaused`).
 *  2. War der Hintergrund-Schritt bis dahin schon fertig (der Test-Thread hatte gerade keinen Prozessor),
 *     lief der Aufbau an dieser Stelle weiter, fand keine Kopfhörer und meldete das. Ganz zu Recht, und
 *     VOR dem Ende des Dienstes.
 *  3. Der Test beendete den Dienst danach, fand eine Meldung vor und hielt sie für eine NACH dem Ende.
 *
 * Wie? Die Einstellungen sind verschlüsselt; wer sie frisch öffnet, lädt zuerst den Tresor
 * ([FakeAndroidKeyStore]). Diese Sperre vergisst die geöffneten Einstellungen und stellt sich an die Tür
 * des Tresors: Der erste fremde Thread, der dort ankommt, bleibt stehen. Das ist der Hintergrund-Schritt
 * des Dienstes. Wann er weiterläuft (und ob er dabei scheitert), bestimmt allein der Test. Mit Uhren und
 * geschätzten Wartezeiten hat das nichts mehr zu tun.
 *
 * Solange jemand an der Tür steht, darf der Test selbst kein neues [Prefs] anlegen: Der Wartende hält
 * die Sperre, unter der die Einstellungen geöffnet werden. Das schon vorhandene Objekt geht weiter.
 */
class SetupHold private constructor() {
    private val owner = Thread.currentThread()
    private val taken = AtomicBoolean()
    private val reached = CountDownLatch(1)
    private val go = CountDownLatch(1)
    @Volatile private var failure: IOException? = null

    /** Läuft auf dem Thread, der den Tresor lädt. */
    private fun atTheDoor() {
        // Der Test selbst geht immer durch, und angehalten wird nur der Erste: Wer danach kommt, soll
        // nicht für immer warten.
        if (Thread.currentThread() === owner || !taken.compareAndSet(false, true)) return
        reached.countDown()
        // Die Obergrenze ist nur ein Netz für einen Test, der abbricht, ohne freizugeben: Dann soll kein
        // Thread für den Rest des Testlaufs hängen bleiben. Am Ergebnis eines Tests ändert sie nichts.
        go.await(60, TimeUnit.SECONDS)
        failure?.let { throw it }
    }

    /** Wartet, bis der Hintergrund-Schritt des Dienstes wirklich an der Tür steht. Erst dann gilt "mitten im Aufbau". */
    fun awaitReached() {
        assertTrue(
            "der Hintergrund-Teil des Aufbaus hat die Einstellungen nie geöffnet",
            reached.await(30, TimeUnit.SECONDS),
        )
    }

    /**
     * Schickt den Wartenden weiter. Mit [failing] scheitert das Öffnen der Einstellungen in genau diesem
     * Moment (wie ein Tresor, der sich verweigert); der Hintergrund-Schritt endet dann mit einer Ausnahme.
     * Mehrfach aufrufen ist harmlos.
     */
    fun release(failing: Boolean = false) {
        if (failing) failure = IOException("Tresor im Test gesperrt")
        go.countDown()
        FakeAndroidKeyStore.atTheDoor = null
    }

    companion object {
        /** Stellt die Sperre auf. Danach den Dienst starten und mit [awaitReached] auf den Hintergrund-Schritt warten. */
        fun arm(): SetupHold {
            val hold = SetupHold()
            // Ohne das fände der Dienst die schon geöffneten Einstellungen vor und käme nie am Tresor vorbei.
            FakeAndroidKeyStore.forgetPrefs()
            FakeAndroidKeyStore.atTheDoor = hold::atTheDoor
            return hold
        }
    }
}

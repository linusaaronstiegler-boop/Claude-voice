package app.claudevoice.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random

/**
 * Weckwort-Erkennung ("Hey Jarvis") komplett auf dem Gerät, mit den vortrainierten Modellen von
 * openWakeWord (https://github.com/dscripka/openWakeWord) und der ONNX Runtime als Rechenkern.
 *
 * Warum drei Modelle? Die Erkennung läuft als Kette, jedes Glied verdichtet das Signal weiter:
 *  1. Mel-Spektrogramm: aus Schalldruck wird "welche Tonhöhen sind gerade wie laut"
 *     (32 Frequenzbänder, alle 10 ms eine Zeile).
 *  2. Embedding: ein allgemeines Sprachmodell von Google fasst 76 solcher Zeilen (0,76 s) zu
 *     96 Zahlen zusammen, die den Klang der Sprache beschreiben.
 *  3. Weckwort-Modell: schaut auf die letzten 16 Embeddings (rund 1,3 s) und gibt eine Zahl
 *     zwischen 0 und 1 aus: wie sicher wurde gerade "Hey Jarvis" gesagt.
 *
 * Gerechnet wird in festen 80-ms-Schritten (1280 Abtastwerte bei 16 kHz). Das ist eine genaue
 * Übertragung der Python-Vorlage (openwakeword/utils.py, Klasse AudioFeatures, und model.py):
 * gleiche Stückelung, gleiche Skalierung, gleiche Fenster. Ein Test vergleicht die Scores
 * Schritt für Schritt mit der Vorlage.
 *
 * Die Modelldateien liegen nicht im Repo (Lizenz CC BY-NC-SA 4.0); der Build lädt sie herunter.
 * Diese Klasse bekommt nur ihren Inhalt als Bytes und weiß nichts von Dateien oder Android.
 */
class OpenWakeWord(
    mel: ByteArray,
    embedding: ByteArray,
    wake: ByteArray,
    /** Ab diesem Score gilt das Weckwort als erkannt. 0,5 ist der Wert, den openWakeWord empfiehlt. */
    private val threshold: Float = 0.5f,
) : WakeWordDetector {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val melSession: OrtSession
    private val embeddingSession: OrtSession
    private val wakeSession: OrtSession
    private val melInputName: String
    private val embeddingInputName: String
    private val wakeInputName: String

    /** So viele Embeddings will das Weckwort-Modell sehen (bei "Hey Jarvis": 16). Steht im Modell selbst. */
    private val featureFrames: Int
    private val wakeShape: LongArray

    // --- Zustand zwischen den Aufrufen --------------------------------------------------------

    /**
     * Die letzten 110 ms Audio: vorn 30 ms Rest vom vorigen Schritt, dahinter der aktuelle
     * 80-ms-Schritt, der sich gerade füllt. Den Rest braucht das Mel-Modell, weil seine
     * Analysefenster überlappen: ohne ihn fehlten an jeder Nahtstelle Zeilen.
     */
    private val audio = FloatArray(CONTEXT + FRAME)
    private var filled = 0          // wie viele Abtastwerte des aktuellen Schritts schon da sind
    private var hasContext = false  // gibt es schon einen vorigen Schritt?
    private var carry = -1          // einzelnes Byte, dessen Partner erst im nächsten Stück kommt

    /** Die letzten 76 Mel-Zeilen. Start mit Einsen, wie in der Vorlage. */
    private val melRows = FloatArray(MEL_WINDOW * MEL_BINS)

    /** Die letzten [featureFrames] Embeddings. Start mit [seed], siehe dort. */
    private val features: FloatArray
    private val seed: FloatArray

    private var framesSeen = 0  // verarbeitete Schritte seit Start oder reset()
    private var quiet = 0       // Schritte, in denen nach einem Treffer nicht noch einmal ausgelöst wird
    private var closed = false

    // Eingabepuffer außerhalb des Java-Speichers. Die ONNX Runtime liest sie direkt; so muss
    // nicht alle 80 ms neuer Speicher angefordert und wieder aufgeräumt werden.
    private val melIn = directFloats(CONTEXT + FRAME)
    private val embeddingIn = directFloats(MEL_WINDOW * MEL_BINS)
    private val wakeIn: FloatBuffer

    /** Score des zuletzt verarbeiteten 80-ms-Schritts (0..1). Nur zur Anzeige und für Tests. */
    @Volatile
    var lastScore: Float = 0f
        private set

    init {
        require(threshold > 0f && threshold <= 1f) { "Schwelle muss zwischen 0 und 1 liegen: $threshold" }
        val opened = ArrayList<OrtSession>()
        try {
            // Ein Rechen-Thread reicht (die Modelle sind klein) und schont im Dauerbetrieb den Akku.
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                melSession = env.createSession(mel, options).also { opened += it }
                embeddingSession = env.createSession(embedding, options).also { opened += it }
                wakeSession = env.createSession(wake, options).also { opened += it }
            }
            melInputName = melSession.inputNames.first()
            embeddingInputName = embeddingSession.inputNames.first()
            wakeInputName = wakeSession.inputNames.first()

            // Eingabeform des Weckwort-Modells: [1, Anzahl Embeddings, 96].
            val shape = (wakeSession.inputInfo.getValue(wakeInputName).info as TensorInfo).shape
            require(shape.size == 3 && shape[2] == EMBEDDING_SIZE.toLong() && shape[1] in 1..SEED_WINDOWS) {
                "Unerwartete Eingabeform des Weckwort-Modells: ${shape.contentToString()}"
            }
            featureFrames = shape[1].toInt()
            wakeShape = longArrayOf(1, shape[1], EMBEDDING_SIZE.toLong())
            wakeIn = directFloats(featureFrames * EMBEDDING_SIZE)
            features = FloatArray(featureFrames * EMBEDDING_SIZE)
            seed = seedFeatures()
        } catch (e: Throwable) {
            // Geht beim Laden etwas schief, die schon geöffneten Modelle wieder freigeben.
            opened.forEach { runCatching { it.close() } }
            throw e
        }
        reset()
    }

    /**
     * 16 kHz mono PCM (16 Bit little endian) in beliebig großen Stücken. Intern wird in
     * 80-ms-Schritten gerechnet; was übrig bleibt, wartet auf das nächste Stück.
     */
    @Synchronized
    override fun feed(pcm: ByteArray, length: Int): Boolean {
        if (closed) return false
        val end = minOf(length, pcm.size)
        var fired = false
        var i = 0
        if (carry >= 0 && end > 0) {
            if (push((pcm[0].toInt() shl 8) or carry)) fired = true
            carry = -1
            i = 1
        }
        while (i + 1 < end) {
            // Zwei Bytes ergeben einen Abtastwert; das hintere Byte trägt das Vorzeichen.
            if (push((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF))) fired = true
            i += 2
        }
        if (i < end) carry = pcm[i].toInt() and 0xFF
        return fired
    }

    /**
     * Alles vergessen, als wäre der Erkenner frisch erzeugt. Danach sind die ersten 0,4 s taub
     * (siehe [WARMUP_FRAMES]); die Modelle bleiben geladen, ein reset() ist also billig.
     */
    @Synchronized
    override fun reset() {
        audio.fill(0f)
        filled = 0
        hasContext = false
        carry = -1
        melRows.fill(1f)
        seed.copyInto(features)
        framesSeen = 0
        quiet = 0
        lastScore = 0f
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        wakeSession.close()
        embeddingSession.close()
        melSession.close()
    }

    private fun push(sample: Int): Boolean {
        audio[CONTEXT + filled++] = sample.toFloat()
        if (filled < FRAME) return false
        filled = 0
        return step()
    }

    /** Ein vollständiger 80-ms-Schritt: Mel-Zeilen -> Embedding -> Score -> Entscheidung. */
    private fun step(): Boolean {
        // 1. Mel-Spektrogramm über die 80 ms plus 30 ms Vorlauf. Das ergibt genau 8 neue Zeilen.
        //    Beim allerersten Schritt gibt es noch keinen Vorlauf, dann sind es nur 5 (wie in der Vorlage).
        val from = if (hasContext) 0 else CONTEXT
        melIn.load(audio, from, audio.size - from)
        val spec = run(melSession, melInputName, melIn, longArrayOf(1, melIn.remaining().toLong()))
        val rows = spec.remaining() / MEL_BINS
        check(rows in 1..MEL_WINDOW) { "Mel-Modell liefert unerwartet $rows Zeilen" }
        // Fenster weiterschieben: die ältesten Zeilen fallen vorn heraus, die neuen kommen hinten an.
        System.arraycopy(melRows, rows * MEL_BINS, melRows, 0, (MEL_WINDOW - rows) * MEL_BINS)
        for (k in (MEL_WINDOW - rows) * MEL_BINS until melRows.size) {
            // Umrechnung aus der Vorlage: Damit sehen die Werte so aus wie beim Original-Modell von
            // Google, mit dem das Embedding-Modell trainiert wurde.
            melRows[k] = spec.get() / 10f + 2f
        }
        // Die letzten 30 ms nach vorn kopieren: Vorlauf für den nächsten Schritt.
        System.arraycopy(audio, FRAME, audio, 0, CONTEXT)
        hasContext = true

        // 2. Embedding über die letzten 76 Mel-Zeilen.
        embeddingIn.load(melRows)
        val emb = run(embeddingSession, embeddingInputName, embeddingIn, EMBEDDING_SHAPE)
        System.arraycopy(features, EMBEDDING_SIZE, features, 0, features.size - EMBEDDING_SIZE)
        emb.get(features, features.size - EMBEDDING_SIZE, EMBEDDING_SIZE)

        // 3. Weckwort-Modell über die letzten Embeddings.
        wakeIn.load(features)
        val raw = run(wakeSession, wakeInputName, wakeIn, wakeShape).get(0)

        // 4. Entscheidung. Die ersten Schritte zählen nicht: die Puffer enthalten noch Startwerte
        //    statt echtem Ton, das Modell würde auf Unsinn antworten (auch das macht die Vorlage so).
        framesSeen++
        val score = if (framesSeen <= WARMUP_FRAMES) 0f else raw
        lastScore = score
        if (quiet > 0) {
            quiet--
            return false
        }
        if (score < threshold) return false
        // Ein gesprochenes "Hey Jarvis" hält den Score über mehrere Schritte oben (gemessen: 5 bis 8,
        // also rund eine halbe Sekunde). Ohne Sperre würde ein einziger Ruf mehrfach auslösen.
        quiet = REFRACTORY_FRAMES
        return true
    }

    /**
     * Startwerte für den Embedding-Puffer: die Embeddings von 4 s leisem Rauschen.
     *
     * Warum nicht einfach Nullen? Das Weckwort-Modell hat im Training nie 16 Null-Embeddings
     * gesehen; Rauschen entspricht dem, was es als "es ist nichts los" kennt. Die Vorlage macht
     * es genauso. Hier mit festem Startwert für den Zufall, damit jeder Lauf gleich rechnet.
     */
    private fun seedFeatures(): FloatArray {
        val random = Random(SEED)
        val noise = directFloats(SEED_SAMPLES)
        noise.load(FloatArray(SEED_SAMPLES) { (random.nextInt(2000) - 1000).toFloat() })
        val spec = run(melSession, melInputName, noise, longArrayOf(1, SEED_SAMPLES.toLong()))
        val rows = spec.remaining() / MEL_BINS
        val mel = FloatArray(rows * MEL_BINS) { spec.get() / 10f + 2f }

        // Fenster von 76 Zeilen im Abstand von 8 Zeilen (= 80 ms); gebraucht werden nur die letzten.
        val windows = (rows - MEL_WINDOW) / MEL_STEP + 1
        check(windows >= featureFrames) { "Mel-Modell liefert zu wenige Zeilen: $rows" }
        val stacked = FloatArray(featureFrames * MEL_WINDOW * MEL_BINS)
        for (n in 0 until featureFrames) {
            val w = windows - featureFrames + n
            System.arraycopy(mel, w * MEL_STEP * MEL_BINS, stacked, n * MEL_WINDOW * MEL_BINS, MEL_WINDOW * MEL_BINS)
        }
        val batch = directFloats(stacked.size).apply { load(stacked) }
        val out = run(
            embeddingSession, embeddingInputName, batch,
            longArrayOf(featureFrames.toLong(), MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1),
        )
        check(out.remaining() == featureFrames * EMBEDDING_SIZE) { "Unerwartete Embedding-Größe: ${out.remaining()}" }
        return FloatArray(featureFrames * EMBEDDING_SIZE).also { out.get(it) }
    }

    /** Ein Modell einmal rechnen lassen. Eingabe und Ergebnis werden danach sofort wieder freigegeben. */
    private fun run(session: OrtSession, input: String, data: FloatBuffer, shape: LongArray): FloatBuffer =
        OnnxTensor.createTensor(env, data, shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                (result[0] as OnnxTensor).floatBuffer // eine Kopie, bleibt also nach dem Freigeben gültig
            }
        }

    companion object {
        /** Dateinamen der drei Modelle, so wie openWakeWord sie veröffentlicht. */
        const val MEL_FILE = "melspectrogram.onnx"
        const val EMBEDDING_FILE = "embedding_model.onnx"
        const val WAKE_FILE = "hey_jarvis_v0.1.onnx"

        const val SAMPLE_RATE = 16_000

        /** Ein Rechenschritt: 80 ms. */
        const val FRAME = 1280

        private const val CONTEXT = 480          // 30 ms Vorlauf für das Mel-Modell (3 Zeilen zu 10 ms)
        private const val MEL_BINS = 32          // Frequenzbänder je Mel-Zeile
        private const val MEL_WINDOW = 76        // Mel-Zeilen je Embedding
        private const val MEL_STEP = 8           // neue Mel-Zeilen je 80-ms-Schritt
        private const val EMBEDDING_SIZE = 96    // Zahlen je Embedding
        private const val WARMUP_FRAMES = 5      // die ersten 0,4 s nach Start/reset() zählen nicht
        private const val REFRACTORY_FRAMES = 25 // 2 s Ruhe nach einem Treffer
        private const val SEED_SAMPLES = 4 * SAMPLE_RATE
        private const val SEED_WINDOWS = 41      // so viele Embeddings ergeben 4 s Rauschen
        private const val SEED = 20_221_231L     // beliebiger fester Wert

        private val EMBEDDING_SHAPE = longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)

        private fun directFloats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        /**
         * Puffer leeren, neu füllen und zum Lesen bereitstellen.
         *
         * Der Umweg über [Buffer] ist Absicht: clear() und flip() liefern in neuen Java-Versionen
         * einen FloatBuffer zurück, in älteren (und auf manchen Android-Versionen) einen Buffer.
         * Übersetzt man gegen die neue Fassung, sucht das Programm zur Laufzeit genau diese und
         * stürzt ab, wenn nur die alte da ist. Über den Grundtyp [Buffer] passt es überall.
         */
        private fun FloatBuffer.load(values: FloatArray, offset: Int = 0, length: Int = values.size - offset) {
            (this as Buffer).clear()
            put(values, offset, length)
            (this as Buffer).flip()
        }
    }
}

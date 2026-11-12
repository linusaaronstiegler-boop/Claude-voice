package app.claudevoice.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {

    @Test fun `Frage wird beantwortet und satzweise gesprochen`() = runTest {
        val claude = FakeClaude(
            gateReply = { "Wie hoch ist die Zugspitze?" },
            answerParts = listOf("Die Zugspitze ist 2962 ", "Meter hoch. Sie ist der höchste Berg ", "Deutschlands."),
        )
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(claude, speaker, state, backgroundScope, StandardTestDispatcher(testScheduler)) { 0 }
        pipeline.start()

        pipeline.onCommitted("Wie hoch ist eigentlich die Zugspitze")
        runCurrent()

        assertEquals(
            listOf("Die Zugspitze ist 2962 Meter hoch.", "Sie ist der höchste Berg Deutschlands."),
            speaker.spoken,
        )
        assertEquals("Wie hoch ist die Zugspitze?", state.value.question)
        assertEquals("Die Zugspitze ist 2962 Meter hoch. Sie ist der höchste Berg Deutschlands.", state.value.answer)
        assertFalse(state.value.speaking)
        assertEquals(listOf("Wie hoch ist eigentlich die Zugspitze"), state.value.transcript)
    }

    @Test fun `Smalltalk bleibt unbeantwortet, steht aber in der Mitschrift`() = runTest {
        val claude = FakeClaude(gateReply = { "SKIP" })
        val speaker = FakeSpeaker()
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(claude, speaker, state, backgroundScope, StandardTestDispatcher(testScheduler)) { 0 }
        pipeline.start()

        pipeline.onCommitted("Na, wie war dein Wochenende so")
        runCurrent()

        assertTrue(speaker.spoken.isEmpty())
        assertNull(state.value.question)
        assertEquals(0, claude.answerCalls.size)
        assertEquals(1, state.value.transcript.size)
    }

    @Test fun `Fehler der API landet als Meldung im Zustand`() = runTest {
        val claude = FakeClaude(gateReply = { error("kaputt") })
        val state = MutableStateFlow(UiState())
        val pipeline = Pipeline(claude, FakeSpeaker(), state, backgroundScope, StandardTestDispatcher(testScheduler)) { 0 }
        pipeline.start()

        pipeline.onCommitted("Wie hoch ist eigentlich die Zugspitze")
        runCurrent()

        assertTrue(state.value.error!!.contains("kaputt"))
        assertFalse(state.value.speaking)
    }
}

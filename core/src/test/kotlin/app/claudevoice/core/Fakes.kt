package app.claudevoice.core

class FakeClaude(
    var gateReply: (String) -> String = { "SKIP" },
    var answerParts: List<String> = emptyList(),
) : ClaudeApi {
    val classifyCalls = mutableListOf<String>()
    val answerCalls = mutableListOf<String>()

    override fun classify(system: String, user: String): String {
        classifyCalls += user
        return gateReply(user)
    }

    override fun answer(system: String, user: String, onText: (String) -> Unit) {
        answerCalls += user
        answerParts.forEach(onText)
    }
}

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    var stops = 0
    override suspend fun speak(text: String) { spoken += text }
    override fun stop() { stops++ }
}

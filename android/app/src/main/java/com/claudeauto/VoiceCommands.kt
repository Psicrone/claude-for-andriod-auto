package com.claudeauto

/** Interprets short spoken answers. Speech recognition output is lower-cased and loosely punctuated. */
object VoiceCommands {
    enum class Answer { YES, NO, UNCLEAR }

    private val yes = listOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go ahead", "do it", "approve", "approved",
        "allow", "allowed", "affirmative", "please do", "sounds good", "run it", "go for it",
    )
    private val no = listOf(
        "no", "nope", "nah", "don't", "do not", "deny", "denied", "stop", "cancel", "decline",
        "negative", "skip", "not now", "wait",
    )
    private val stop = listOf("stop", "cancel", "abort", "never mind", "nevermind")

    fun answer(text: String): Answer {
        val words = normalise(text)
        if (words.isEmpty()) return Answer.UNCLEAR
        val saysNo = no.any { words.containsPhrase(it) }
        val saysYes = yes.any { words.containsPhrase(it) }
        return when {
            saysNo -> Answer.NO // "no, don't do it" contains "do it" too; refusal wins
            saysYes -> Answer.YES
            else -> Answer.UNCLEAR
        }
    }

    /** True when the whole utterance is a request to stop Claude, e.g. "stop" or "cancel that". */
    fun isStop(text: String): Boolean {
        val words = normalise(text)
        return words.size <= 3 && stop.any { words.containsPhrase(it) }
    }

    private fun normalise(text: String): List<String> =
        text.lowercase().replace(Regex("[^a-z' ]"), " ").split(' ').filter { it.isNotBlank() }

    private fun List<String>.containsPhrase(phrase: String): Boolean {
        val target = phrase.split(' ')
        if (target.size > size) return false
        return (0..size - target.size).any { start -> target.indices.all { this[start + it] == target[it] } }
    }
}

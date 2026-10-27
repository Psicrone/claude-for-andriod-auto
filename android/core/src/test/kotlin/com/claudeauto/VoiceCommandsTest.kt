package com.claudeauto

import com.claudeauto.VoiceCommands.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun recognisesApprovals() {
        listOf("yes", "Yeah go ahead", "ok do it", "Sure.", "approve").forEach {
            assertEquals(it, Answer.YES, VoiceCommands.answer(it))
        }
    }

    @Test
    fun refusalWinsOverYesWords() {
        listOf("no", "no don't do it", "nope", "deny that", "not now").forEach {
            assertEquals(it, Answer.NO, VoiceCommands.answer(it))
        }
    }

    @Test
    fun unclearAnswersAskAgain() {
        listOf("", "what was that", "tell me more about the command").forEach {
            assertEquals(it, Answer.UNCLEAR, VoiceCommands.answer(it))
        }
    }

    @Test
    fun wordsInsideOtherWordsDoNotCount() {
        assertEquals(Answer.UNCLEAR, VoiceCommands.answer("notable knowledge"))
    }

    @Test
    fun stopOnlyForShortCommands() {
        assertTrue(VoiceCommands.isStop("stop"))
        assertTrue(VoiceCommands.isStop("cancel that"))
        assertFalse(VoiceCommands.isStop("add a stop button to the media player"))
    }
}

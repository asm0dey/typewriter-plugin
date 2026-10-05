package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.run.Check
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@RunInEdt(writeIntent = true)
class RecordingPreFlightTest : TypeWriterFixtureTestCase() {
    @Test
    fun testFlagsOnlyActionsThatNeedAWindow() {
        val steps = listOf("CodeCompletion", "EditorChooseLookupItem", "GotoDeclaration", "ReformatCode", "CodeCompletion")
            .map { Step.Action(it) }
        assertEquals(
            listOf("CodeCompletion", "EditorChooseLookupItem", "GotoDeclaration").map {
                Check.Warning("s.java: the video may differ from a live run at action $it")
            },
            recordingWarnings("s.java", steps),
        )
        assertTrue(recordingWarnings("s.java", listOf(Step.Type("x"))).isEmpty())
    }
}

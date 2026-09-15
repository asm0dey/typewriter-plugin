package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Directives
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@RunInEdt(writeIntent = true)
class DirectiveSidecarTest : TypeWriterFixtureTestCase() {

    private fun dir(name: String): VirtualFile =
        WriteAction.compute<VirtualFile, Exception> { fixture.tempDirFixture.findOrCreateDir(name) }

    @Test
    fun testDirectivesForReturnsEmptyWhenNoSidecarFile() {
        val d = dir("no-sidecar")
        assertEquals(Directives(), DirectiveSidecar.directivesFor(d, "01.txt"))
    }

    @Test
    fun testWriteThenReadRoundTrips() {
        val d = dir("roundtrip")
        val directives = Directives(raw = true, speedMs = 90, jitterMs = 15, newlineMs = 400)
        DirectiveSidecar.write(d, "01.txt", directives)
        assertEquals(directives, DirectiveSidecar.directivesFor(d, "01.txt"))
    }

    @Test
    fun testWriteWithEmptyDirectivesDeletesTheOnlyEntryAndTheFileItself() {
        val d = dir("delete-last")
        DirectiveSidecar.write(d, "01.txt", Directives(speedMs = 90))
        DirectiveSidecar.write(d, "01.txt", Directives())
        assertEquals(Directives(), DirectiveSidecar.directivesFor(d, "01.txt"))
        assertNull(d.findChild(DirectiveSidecar.FILE_NAME))
    }

    @Test
    fun testWriteWithEmptyDirectivesRemovesOnlyItsOwnEntry() {
        val d = dir("delete-one-of-two")
        DirectiveSidecar.write(d, "01.txt", Directives(speedMs = 90))
        DirectiveSidecar.write(d, "02.txt", Directives(jitterMs = 30))
        DirectiveSidecar.write(d, "01.txt", Directives())
        assertEquals(Directives(), DirectiveSidecar.directivesFor(d, "01.txt"))
        assertEquals(Directives(jitterMs = 30), DirectiveSidecar.directivesFor(d, "02.txt"))
    }

    @Test
    fun testReadDegradesToEmptyOnMalformedJson() {
        val d = dir("malformed")
        WriteAction.run<Exception> {
            d.createChildData(this, DirectiveSidecar.FILE_NAME).setBinaryContent("not json".toByteArray())
        }
        assertEquals(emptyMap<String, Directives>(), DirectiveSidecar.read(d))
        assertEquals(Directives(), DirectiveSidecar.directivesFor(d, "01.txt"))
    }

    // A field the header format would encode as absence (raw=false, meaning "never mentioned")
    // must round-trip through the sidecar the same way -- explicitly asserted since the sidecar
    // stores raw as a nullable Boolean internally (see DirectiveSidecar.Entry) precisely to
    // preserve this.
    @Test
    fun testRawFalseRoundTripsAsAbsentNotAsAnExplicitFalse() {
        val d = dir("raw-false")
        DirectiveSidecar.write(d, "01.txt", Directives(speedMs = 90))
        assertEquals(Directives(raw = false, speedMs = 90), DirectiveSidecar.directivesFor(d, "01.txt"))
    }
}

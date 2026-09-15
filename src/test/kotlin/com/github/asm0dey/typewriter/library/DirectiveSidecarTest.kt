package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Directives
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@RunInEdt(writeIntent = true)
class DirectiveSidecarTest : TypeWriterFixtureTestCase() {

    private fun dir(name: String): VirtualFile =
        WriteAction.compute<VirtualFile, Exception> { fixture.tempDirFixture.findOrCreateDir(name) }

    private fun snippetFile(parent: VirtualFile, name: String): VirtualFile =
        WriteAction.compute<VirtualFile, Exception> {
            (parent.findChild(name) ?: parent.createChildData(this, name)).also {
                it.setBinaryContent("hello".toByteArray())
            }
        }

    @Test
    fun testReadReturnsEmptyDirectivesWhenNoSidecarFile() {
        val f = snippetFile(dir("no-sidecar"), "01.txt")
        assertEquals(Directives(), DirectiveSidecar.read(f))
        assertNull(DirectiveSidecar.sidecarFileFor(f))
    }

    @Test
    fun testWriteThenReadRoundTrips() {
        val f = snippetFile(dir("roundtrip"), "01.txt")
        val directives = Directives(raw = true, speedMs = 90, jitterMs = 15, newlineMs = 400)
        DirectiveSidecar.write(f, directives)
        assertEquals(directives, DirectiveSidecar.read(f))
    }

    @Test
    fun testSidecarFileNameIsSnippetNamePlusSuffix() {
        val d = dir("naming")
        val f = snippetFile(d, "01-entity.txt")
        DirectiveSidecar.write(f, Directives(speedMs = 90))
        assertEquals("01-entity.txt.twmeta", DirectiveSidecar.sidecarFileFor(f)?.name)
        assertEquals("01-entity.txt.twmeta", d.findChild("01-entity.txt.twmeta")?.name)
    }

    @Test
    fun testWriteWithEmptyDirectivesDeletesTheSidecarFile() {
        val f = snippetFile(dir("delete"), "01.txt")
        DirectiveSidecar.write(f, Directives(speedMs = 90))
        DirectiveSidecar.write(f, Directives())
        assertEquals(Directives(), DirectiveSidecar.read(f))
        assertNull(DirectiveSidecar.sidecarFileFor(f))
    }

    // One sidecar per snippet (spec section 9, resolved design question 22) means writing one
    // snippet's directives can never touch another's -- unlike the one-file-per-directory design
    // this replaced, there is no shared file to merge into or corrupt. Two sibling snippets'
    // sidecars are asserted independent here as the concrete proof.
    @Test
    fun testEachSnippetHasItsOwnIndependentSidecar() {
        val d = dir("independent")
        val f1 = snippetFile(d, "01.txt")
        val f2 = snippetFile(d, "02.txt")
        DirectiveSidecar.write(f1, Directives(speedMs = 90))
        DirectiveSidecar.write(f2, Directives(jitterMs = 30))
        assertEquals(Directives(speedMs = 90), DirectiveSidecar.read(f1))
        assertEquals(Directives(jitterMs = 30), DirectiveSidecar.read(f2))

        DirectiveSidecar.write(f1, Directives())
        assertEquals(Directives(), DirectiveSidecar.read(f1))
        assertEquals(Directives(jitterMs = 30), DirectiveSidecar.read(f2), "clearing one snippet's sidecar must not touch a sibling's")
    }

    // Spec section 9, resolved design question 22, item from the review: a malformed or garbage
    // .twmeta must degrade to "no overrides" without throwing -- proven here at the layer
    // SnippetDialog's constructor and SnippetRunner.run actually call (DialogWrapper itself
    // cannot be constructed in a headless test). Safe by construction, not by a catch clause
    // reasoned about in isolation: a .twmeta holds exactly one snippet's own fields, so there is
    // no other snippet's data for a corrupt read to put at risk (contrast the withdrawn
    // one-file-per-directory design, where this needed its own refuse-to-write guard).
    @Test
    fun testReadDegradesToEmptyDirectivesOnGarbageContentWithoutThrowing() {
        val d = dir("garbage")
        val f = snippetFile(d, "01.txt")
        val garbage = "<<<<<<< HEAD\nsome prose that is not a properties file at all\n=======\n>>>>>>> branch"
        WriteAction.run<Exception> {
            d.createChildData(this, "01.txt.twmeta").setBinaryContent(garbage.toByteArray())
        }
        assertEquals(Directives(), DirectiveSidecar.read(f))
    }

    @Test
    fun testReadDegradesToEmptyDirectivesOnAMalformedUnicodeEscapeWithoutThrowing() {
        val d = dir("bad-escape")
        val f = snippetFile(d, "01.txt")
        WriteAction.run<Exception> {
            // Properties.load's own documented failure: an invalid \uXXXX escape.
            d.createChildData(this, "01.txt.twmeta").setBinaryContent("speed=\\uZZZZ".toByteArray())
        }
        assertEquals(Directives(), DirectiveSidecar.read(f))
    }

    // The written .twmeta is inspected directly: an unset `raw` must not appear as a `raw=false`
    // line at all -- the sidecar analogue of the header's own convention (raw is only ever
    // mentioned when true). A prior version of this test compared Directives values, which cannot
    // tell "unset" from "explicitly false" apart (Directives.raw is a non-nullable Boolean) and so
    // asserted nothing; this one reads the actual bytes written to disk.
    @Test
    fun testUnsetRawIsOmittedFromTheWrittenFileNotWrittenAsAnExplicitFalseLine() {
        val d = dir("raw-omitted")
        val f = snippetFile(d, "01.txt")
        DirectiveSidecar.write(f, Directives(speedMs = 90))
        val text = String(d.findChild("01.txt.twmeta")!!.contentsToByteArray())
        assertFalse(text.contains("raw"), "unset raw must not appear in the sidecar's bytes at all: $text")
        assertEquals("speed=90\n", text)
    }

    @Test
    fun testWrittenRawTrueAppearsAsExactly() {
        val d = dir("raw-true")
        val f = snippetFile(d, "01.txt")
        DirectiveSidecar.write(f, Directives(raw = true, speedMs = 80, jitterMs = 25, newlineMs = 400))
        val text = String(d.findChild("01.txt.twmeta")!!.contentsToByteArray())
        assertEquals("raw=true\nspeed=80\njitter=25\nnewline=400\n", text)
    }
}

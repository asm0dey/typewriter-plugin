package com.github.asm0dey.typewriter.golden

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.format.SnippetFormatter
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.parse.MarkerParser
import com.github.asm0dey.typewriter.parse.MarkerScanner
import com.github.asm0dey.typewriter.run.BaseIndent
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * End-to-end acceptance suite (design spec section 12 "Testing", plan task 18). This is the only
 * place the whole pipeline -- read -> format -> parse -> [typed text] -- is asserted against
 * fixed expected output, across more than one language. Every other test in this project verifies
 * a single stage.
 *
 * Most tests here call [typed] directly, which stops at parse: the fixture is configured as a
 * standalone top-level file with no destination caret, so base indent is the identity transform
 * and never runs. [testAcceptanceOneCarriesBaseIndentIntoANestedDestination] is the one fixture
 * that goes one stage further -- read -> format -> parse -> indent -- driving [BaseIndent.compute]
 * and [BaseIndent.apply] against a real destination caret exactly as
 * `com.github.asm0dey.typewriter.library.SnippetRunner.run` does, per spec line 842 ("Base indent
 * is applied on top of the above in both modes"). It does not additionally drive `Player` or
 * `RunService` -- those have their own dedicated suites (`PlayerTest`, `RunServiceTest`), and
 * `Player.play`'s contract is "insert this plain string starting at the caret", which the applied
 * indent already fully determines; there is nothing left for a Player pass to discover here.
 */
@RunInEdt(writeIntent = true)
class GoldenTest : TypeWriterFixtureTestCase() {

    private fun typed(name: String, text: String, formatted: Boolean): String {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
        val source = if (formatted) SnippetFormatter.format(fixture.project, fileType, name, text).text else text
        val psi = fixture.configureByText(name, source)
        // Parse the PSI file's OWN text, not the local `source` string: the Document that
        // backs `psi` normalises line separators to \n on load (platform guarantee, spec
        // section 5 / section 16 row 6), while `source` still carries whatever `text` (or the
        // formatter) produced verbatim. MarkerScanner's offsets are computed against `psi.text`,
        // so parsing anything else risks an offset mismatch -- and for a CRLF-authored fixture
        // it would silently let \r leak back into the "raw" output, defeating the very fixture
        // meant to pin raw := document text, not source bytes.
        val program = MarkerParser.parse(psi.text, MarkerScanner.scan(psi, "tw:"), "tw:")
        return program.steps.filterIsInstance<Step.Type>().joinToString("") { it.text }
    }

    // Acceptance test 1 fixture (spec section 12). An escaped literal, not a raw string: the
    // "// No need..." line's trailing space is load-bearing (both modes assert byte-identity
    // against this literal) and trailing whitespace is invisible in a raw string, per this
    // repo's test conventions.
    // language="JAVA"
    private val acceptanceOne =
        "public interface CourierRepository extends JpaRepository<Courier, Long> {\n" +
            "\n" +
            "    List<Courier> findAllByCity(String city);\n" +
            "\n" +
            "// No need to define common CRUD methods manually \n" +
            "}\n" +
            "\n" +
            "@Service\n" +
            "@Transactional(readOnly = true)\n" +
            "public class CourierService {\n" +
            "\n" +
            "    private final CourierRepository courierRepository;\n" +
            "\n" +
            "    public CourierService(CourierRepository courierRepository) {\n" +
            "        this.courierRepository = courierRepository;\n" +
            "    }"

    /**
     * Both modes produce the SAME output: a column-0 comment immediately before a closing brace
     * is not re-indented, and trailing whitespace is not a formatter concern (spec section 12,
     * acceptance test 1). Its value is as the v1 regression guard.
     */
    @Test
    fun testAcceptanceOneIsIdenticalInBothModes() {
        val formatted = typed("AT1.java", acceptanceOne, formatted = true)
        val raw = typed("AT1raw.java", acceptanceOne, formatted = false)
        assertEquals(acceptanceOne, raw)
        assertEquals(raw, formatted)
    }

    @Test
    fun testAcceptanceOneNeverInventsAClosingBrace() {
        val out = typed("AT1b.java", acceptanceOne, formatted = true)
        assertEquals(
            acceptanceOne.count { it == '}' },
            out.count { it == '}' },
        )
    }

    /**
     * Spec section 12, acceptance test 1's closing property: "Base indent is applied on top of
     * the above in both modes" (spec line 842). Reproduces `SnippetRunner.run`'s own sequence --
     * parse into steps, then compute and apply base indent against a real destination caret --
     * rather than calling [BaseIndent] in isolation the way `BaseIndentTest` does: this is the
     * one place that proves the *parsed acceptance-test-1 payload*, not a synthetic snippet,
     * survives indenting intact. The destination ("class Host {" / blank line / "}") is the exact
     * fixture `BaseIndentTest.testBlankLineInsideClassBodyUsesTheContextIndent` already measured
     * as a 4-space, column-0 context on this platform build -- reused rather than re-derived, and
     * re-confirmed by this test's own run.
     */
    @Test
    fun testAcceptanceOneCarriesBaseIndentIntoANestedDestination() {
        val payload = typed("AT1indent.java", acceptanceOne, formatted = false)

        val destination = fixture.configureByText(
            "Dest.java",
            // language="JAVA"
            """
            |class Host {
            |<caret>
            |}
            """.trimMargin(),
        )
        val destinationEditor = fixture.editor
        val offset = destinationEditor.caretModel.offset
        val indent = BaseIndent.compute(fixture.project, destination, destinationEditor.document, offset)
        val column = BaseIndent.caretColumn(destinationEditor.document, offset)
        val indented = BaseIndent.apply(payload, indent, column)

        // Generated and reviewed, never predicted: [BaseIndent.apply]'s per-line prefixing is a
        // pure, already-unit-tested string transform (BaseIndentTest), but `indent` itself comes
        // from the real CodeStyleManager -- captured from an actual run, then hand-verified line
        // by line against acceptanceOne before committing, not typed from reasoning about what
        // the formatter should return. See task-18-report.md for the capture and the check.
        val expected =
            "    public interface CourierRepository extends JpaRepository<Courier, Long> {\n" +
                "\n" +
                "        List<Courier> findAllByCity(String city);\n" +
                "\n" +
                "    // No need to define common CRUD methods manually \n" +
                "    }\n" +
                "\n" +
                "    @Service\n" +
                "    @Transactional(readOnly = true)\n" +
                "    public class CourierService {\n" +
                "\n" +
                "        private final CourierRepository courierRepository;\n" +
                "\n" +
                "        public CourierService(CourierRepository courierRepository) {\n" +
                "            this.courierRepository = courierRepository;\n" +
                "        }"
        assertEquals(
            expected,
            indented,
            "base indent (\"$indent\", column $column) must be applied on top of the parsed payload -- got: $indented",
        )
    }

    @Test
    fun testGenericsAreNotParsedAsCommands() {
        // The v1 crash: JpaRepository<Courier, Long> matched its <...> command syntax.
        val out = typed("AT1c.java", acceptanceOne, formatted = true)
        assertTrue(
            out.contains("JpaRepository<Courier, Long>"),
            "generics must type as plain text, not be parsed as a <...> command (the v1 crash) -- got: $out",
        )
        assertTrue(
            out.contains("List<Courier>"),
            "generics must type as plain text, not be parsed as a <...> command (the v1 crash) -- got: $out",
        )
    }

    /** Acceptance test 3: the fixture that DOES discriminate the two modes. */
    @Test
    fun testAcceptanceThreeDiscriminatesTheModes() {
        val input =
            // language="JAVA"
            """
            |class CourierService {
            |// explains the field
            |        private final CourierRepository repo;
            |    void reload() {
            |int n = repo.count();
            |    }
            |}
            """.trimMargin()
        val expected =
            // language="JAVA"
            """
            |class CourierService {
            |    // explains the field
            |    private final CourierRepository repo;
            |    void reload() {
            |        int n = repo.count();
            |    }
            |}
            """.trimMargin()
        assertEquals(expected, typed("AT3.java", input, formatted = true))
        assertEquals(input, typed("AT3raw.java", input, formatted = false))
    }

    @Test
    fun testAcceptanceThreeHasNoFormatterInsertedBlankLine() {
        val input =
            // language="JAVA"
            """
            |class C {
            |    int x;
            |    void m() {
            |    }
            |}
            """.trimMargin()
        val out = typed("AT3b.java", input, formatted = true)
        assertFalse(
            out.contains("\n\n"),
            "the formatter's own blank-line insertion between members must be reconciled away -- got: $out",
        )
    }

    /** Acceptance test 2: Dockerfile -- line comments only, backslash continuations. */
    @Test
    fun testDockerfile() {
        val name = "Dockerfile"
        val dockerfileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
        // The brief's guard compared only against PlainTextFileType.INSTANCE, but
        // SnippetFileNameTest (task 13-ish) found by direct measurement that an unassociated
        // name resolves to UnknownFileType.INSTANCE on this platform build, not
        // PlainTextFileType.INSTANCE -- so both must be treated as "Docker plugin absent" or an
        // absent plugin would silently fall through into the assertions below instead of
        // skipping loudly.
        if (dockerfileType == PlainTextFileType.INSTANCE || dockerfileType == UnknownFileType.INSTANCE) {
            fail<Unit>("SKIPPED LOUDLY: Docker plugin absent, acceptance test 2 did not run")
        }
        val input =
            """
            |# tw: pause 500
            |FROM eclipse-temurin:21-jre
            |
            |RUN apt-get update && \
            |      apt-get install -y curl && \
            |  rm -rf /var/lib/apt/lists/*
            |
            |# install the app
            |COPY   target/app.jar   /app/app.jar
            |
            |ENTRYPOINT ["java","-jar","/app/app.jar"]
            |
            """.trimMargin()

        val out = typed(name, input, formatted = true)
        assertTrue(out.startsWith("FROM"), "the marker line is consumed entirely")
        assertTrue(out.contains("# install the app"), "an ordinary comment is typed")
        assertEquals(2, out.split("\\\n").size - 1, "every continuation survives")
        assertTrue(
            out.contains("[\"java\",\"-jar\",\"/app/app.jar\"]"),
            "the ENTRYPOINT array must type exactly as written -- got: $out",
        )
        assertEquals(input.replace("# tw: pause 500\n", ""), typed("Dockerfile", input, formatted = false))

        // Generated and reviewed, never predicted (plan Global Constraints): captured from a
        // real run against the Docker plugin's formatter, read by hand, then committed as a
        // golden file. See task-18-report.md for the capture command and the reviewed diff.
        val golden = GoldenTest::class.java.getResourceAsStream("/golden/Dockerfile.formatted")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: fail("golden/Dockerfile.formatted missing from test resources")
        assertEquals(golden, out)
    }

    @Test
    fun testCrlfAuthoredSnippetTypesAsLf() {
        // Escaped literal, not a raw string: CRLF cannot survive in a Kotlin triple-quoted
        // string (line separators there are normalised at parse time), so this fixture must
        // carry a literal \r\n to authentically exercise the CRLF-authored case (spec line 752):
        // it proves `raw` means "identical to the Document text" (always \n), not to the bytes
        // on disk, since a Document's line separators are normalised to \n regardless of source.
        val crlf = "class A {\r\n    int x;\r\n}"
        val out = typed("CRLF.java", crlf, formatted = false)
        assertFalse(out.contains("\r"), "Document normalises line separators")
    }
}

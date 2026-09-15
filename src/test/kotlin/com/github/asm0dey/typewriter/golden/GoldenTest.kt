package com.github.asm0dey.typewriter.golden

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.format.SnippetFormatter
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.parse.MarkerParser
import com.github.asm0dey.typewriter.parse.MarkerScanner
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

    @Test
    fun testGenericsAreNotParsedAsCommands() {
        // The v1 crash: JpaRepository<Courier, Long> matched its <...> command syntax.
        val out = typed("AT1c.java", acceptanceOne, formatted = true)
        assertTrue(out.contains("JpaRepository<Courier, Long>"))
        assertTrue(out.contains("List<Courier>"))
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
        assertFalse(typed("AT3b.java", input, formatted = true).contains("\n\n"))
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
        assertTrue(out.contains("[\"java\",\"-jar\",\"/app/app.jar\"]"))
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

# TypeWriter 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the TypeWriter JetBrains plugin so it types predefined snippets into the editor with output that is exactly correct in every language the IDE supports.

**Architecture:** A snippet is a file. Its text is read from the `Document`, formatted with the target project's code style, parsed into a `Program` of type/pause/action steps by finding comment markers in PSI, and typed character by character straight into the target `Document`. The player never consults a language — every language-aware step completes before the first character is typed and hands the player plain strings.

**Tech Stack:** Kotlin 2.1.20, IntelliJ Platform Gradle Plugin 2.16.0, IntelliJ IDEA 2025.2.6.2, JUnit 4 via `LightJavaCodeInsightFixtureTestCase`, Kotlin coroutines (platform-provided).

**Spec:** `docs/superpowers/specs/2026-09-14-typewriter2-design.md`

## Global Constraints

- **Plugin id stays `com.github.asm0dey.typewriterplugin`.** This ships as version 1.0.0 of marketplace plugin 20245. Changing the id creates a different plugin and loses the installed base.
- **Kotlin package for new code is `com.github.asm0dey.typewriter`** (no `plugin` suffix). The plugin id above is unrelated to the package name and must not be changed to match it.
- **`gradle.properties` must contain `kotlin.stdlib.default.dependency = false`.** Without it every platform test dies at fixture setup with `NoSuchMethodError: SequencesKt.sequenceOf`.
- **Production code depends only on `com.intellij.modules.platform`.** No `<depends>` on any language plugin. Language support is reached through `FileType`, `Commenter`, `CodeStyleManager`, `PsiComment`. Language plugins are test-only dependencies.
- **Platform baseline 2025.2 (since-build 252).** Do not raise it.
- **Gradle configuration cache and build cache are on.** Build logic must be configuration-cache-clean.
- **Java toolchain 21.**
- **Never write to the keymap.** The IDE owns bindings. Registering an action is allowed; calling `keymap.addShortcut` is not.
- **Golden files for formatter behavior are generated and reviewed, never predicted.** When a task says "capture the formatter's output", run it, read it, then commit it.

### Test conventions

These were settled during execution and **override the sample test code shown inside
the task bodies below**, which was written before the migration. A task's test
*cases, assertions and expected values* still bind; only the style below changes.

- **JUnit 5, not JUnit 3/4.** Test classes extend `TypeWriterFixtureTestCase`
  (`src/test/kotlin/com/github/asm0dey/typewriter/TypeWriterFixtureTestCase.kt`), which
  carries `@RunInEdt(writeIntent = true)`, an empty `getRelativePath()` override and a
  `ThreadLeakTracker` registration — all inherited, none to be copied into a subclass.
  Use `@Test` and assertions from `org.junit.jupiter.api`, and reach the fixture via
  `fixture`, never `myFixture`. A test needing no PSI fixture extends nothing.
- **Language injection hints on code fixtures.** A string literal holding another
  language's source carries `// language="JAVA"` (or the matching id) on the line
  immediately above it; a fixture already bound to a `val` carries
  `@org.intellij.lang.annotations.Language("JAVA")` on that `val` instead. Never hoist a
  literal to a `val` merely to make the annotation attachable — the comment form covers it.
- **Raw strings for multi-line fixtures.** `"""..."""` with `.trimMargin()` where leading
  whitespace is load-bearing, `.trimIndent()` where it is incidental. An escaped `"\n"`
  literal survives only where an assertion depends on TRAILING whitespace, since trailing
  spaces are invisible in a raw string — and carries a comment saying why. When converting
  a fixture, the text reaching the code under test must be byte-identical to what the
  escaped literal produced.
- **`RawMarker.body` is sentinel-inclusive** — comment delimiters stripped, the sentinel
  retained on every body line. The design spec requires each non-blank body line to carry
  the sentinel, and `MarkerParser` strips it per line. Stripping it in the scanner turns
  every ordinary single-line marker into a pre-flight error.

---

## File Structure

```
build.gradle.kts                         Gradle build; platform + test-only language plugins
settings.gradle.kts                      Plugin versions, repositories
gradle.properties                        kotlin.stdlib.default.dependency = false
src/main/resources/META-INF/plugin.xml   Plugin id, extensions, static actions

src/main/kotlin/com/github/asm0dey/typewriter/
  model/
    Snippet.kt              Snippet identity: id, relative path, VirtualFile, FileType
    Program.kt              Step sealed interface; Program; Timing
    Directives.kt           Directives data class
    ParseError.kt           ParseError, SnippetParseException
  parse/
    CommentSyntax.kt        Per-language comment prefixes, from Commenter
    MarkerScanner.kt        PsiComment -> RawMarker list, classified whole-line/trailing/mid-line
    MarkerParser.kt         Text + markers -> Program; whitespace consumption; escape
  format/
    SnippetFormatter.kt     reformat + whitespace guard + line-structure reconciliation
  run/
    BaseIndent.kt           Compute base indent from caret; apply to payload
    Player.kt               The typing coroutine
    RunService.kt           Active run, RangeMarker, last-run record, sequence cursor
    AbortWatcher.kt         AnActionListener + caret-drift cancellation
    PreFlight.kt            All checks that run before the first character
  library/
    SnippetLibrary.kt       Two directories, shadowing, VFS watching
    SnippetActions.kt       TypeSnippetAction, registrar, sequence actions, undo action
  ui/
    TypeWriterSettings.kt   Persistent settings + Configurable
    NewSnippetDialog.kt     Name field + file-type chooser
    SnippetDialog.kt        EditorTextField over the snippet Document + timing fields
    SnippetPicker.kt        Speed-search popup; start-sequence-here
  ide/
    SnippetHighlighting.kt  DefaultHighlightingSettingProvider
    MarkerCompletion.kt     CompletionContributor for tw: bodies

src/test/kotlin/com/github/asm0dey/typewriter/
  ...                       One test file per production file above
  golden/                   Acceptance fixtures 1-3 and the Dockerfile case
```

---

### Task 1: Project scaffold with a green platform test

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/ScaffoldTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a build in which `LightJavaCodeInsightFixtureTestCase` runs. Every later task depends on this.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ScaffoldTest : LightJavaCodeInsightFixtureTestCase() {
    fun testFixtureStarts() {
        val file = myFixture.configureByText("A.java", "class A {}")
        assertEquals("class A {}", file.text)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*ScaffoldTest*'`
Expected: FAIL — no Gradle build exists yet.

- [ ] **Step 3: Write the build files**

`settings.gradle.kts`:

```kotlin
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "typewriter2"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform { defaultRepositories() }
    }
}
```

`build.gradle.kts`:

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = "com.github.asm0dey"
version = "1.0.0"

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "252" }
    }
}

tasks.test { testLogging { showStandardStreams = true } }
```

`gradle.properties`:

```properties
kotlin.stdlib.default.dependency = false
org.gradle.configuration-cache = true
org.gradle.caching = true
```

- [ ] **Step 4: Write the plugin descriptor**

`src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>com.github.asm0dey.typewriterplugin</id>
    <name>TypeWriter</name>
    <vendor>asm0dey</vendor>
    <depends>com.intellij.modules.platform</depends>
</idea-plugin>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*ScaffoldTest*'`
Expected: PASS.

If it fails with `NoSuchMethodError: SequencesKt.sequenceOf`, `gradle.properties` is missing or misspelled — that exact error means the project bundled its own Kotlin stdlib.

- [ ] **Step 6: Generate the Gradle wrapper**

Every later task verifies with `./gradlew`, so the wrapper must exist and be committed.

```bash
gradle wrapper --gradle-version 9.3.0
```

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle src
git commit -m "build: scaffold plugin project with a running platform test"
```

---

### Task 2: Comment syntax and marker scanning

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/parse/CommentSyntax.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/parse/MarkerScanner.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/parse/MarkerScannerTest.kt`

**Interfaces:**
- Consumes: Task 1's build.
- Produces:
  - `CommentSyntax(linePrefix: String?, blockPrefix: String?, blockSuffix: String?)` with `CommentSyntax.of(language: Language): CommentSyntax`
  - `enum class MarkerKind { WHOLE_LINE, TRAILING, MID_LINE }`
  - `data class RawMarker(val startOffset: Int, val endOffset: Int, val body: String, val kind: MarkerKind, val line: Int)`
  - `MarkerScanner.scan(file: PsiFile, sentinel: String): List<RawMarker>`

Scanning walks `PsiComment` elements rather than matching text. A regex over raw text would find `"// tw: x"` inside a string literal; PSI cannot.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.parse

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MarkerScannerTest : LightJavaCodeInsightFixtureTestCase() {

    private fun scan(text: String) =
        MarkerScanner.scan(myFixture.configureByText("S.java", text), "tw:")

    fun testWholeLineMarker() {
        val markers = scan("class A {\n// tw: pause 800\n    int x;\n}")
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.WHOLE_LINE, markers[0].kind)
        assertEquals("tw: pause 800", markers[0].body.trim())
    }

    fun testTrailingMarker() {
        val markers = scan("class A {\n    int x; // tw: pause 500\n}")
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.TRAILING, markers[0].kind)
    }

    fun testMidLineMarker() {
        val markers = scan("class A {\n    int x = a/* tw: pause 200 */+b;\n}")
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.MID_LINE, markers[0].kind)
    }

    fun testMultiLineBlockMarkerIsWholeLine() {
        val markers = scan("class A {\n/*\ntw: pause 500\ntw: action ReformatCode\n*/\n    int x;\n}")
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.WHOLE_LINE, markers[0].kind)
        assertTrue(markers[0].body.contains("tw: action ReformatCode"))
    }

    fun testOrdinaryCommentIsNotAMarker() {
        assertEquals(0, scan("class A {\n// just a note\n    int x;\n}").size)
    }

    fun testSentinelInsideStringLiteralIsNotAMarker() {
        assertEquals(0, scan("class A {\n    String s = \"// tw: pause 800\";\n}").size)
    }

    fun testEscapedSentinelIsNotAMarker() {
        assertEquals(0, scan("class A {\n// tw:: pause 800\n    int x;\n}").size)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*MarkerScannerTest*'`
Expected: FAIL — `Unresolved reference: MarkerScanner`.

- [ ] **Step 3: Write `CommentSyntax.kt`**

```kotlin
package com.github.asm0dey.typewriter.parse

import com.intellij.lang.Language
import com.intellij.lang.LanguageCommenters

data class CommentSyntax(
    val linePrefix: String?,
    val blockPrefix: String?,
    val blockSuffix: String?,
) {
    val hasAny: Boolean get() = linePrefix != null || (blockPrefix != null && blockSuffix != null)

    /** Strips whichever delimiters [commentText] actually carries. */
    fun bodyOf(commentText: String): String {
        if (blockPrefix != null && blockSuffix != null &&
            commentText.startsWith(blockPrefix) && commentText.endsWith(blockSuffix)
        ) {
            return commentText.substring(blockPrefix.length, commentText.length - blockSuffix.length)
        }
        if (linePrefix != null && commentText.startsWith(linePrefix)) {
            return commentText.substring(linePrefix.length)
        }
        return commentText
    }

    companion object {
        fun of(language: Language): CommentSyntax {
            val commenter = LanguageCommenters.INSTANCE.forLanguage(language)
                ?: return CommentSyntax(null, null, null)
            return CommentSyntax(
                linePrefix = commenter.lineCommentPrefix?.takeIf { it.isNotEmpty() },
                blockPrefix = commenter.blockCommentPrefix?.takeIf { it.isNotEmpty() },
                blockSuffix = commenter.blockCommentSuffix?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
```

- [ ] **Step 4: Write `MarkerScanner.kt`**

```kotlin
package com.github.asm0dey.typewriter.parse

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

enum class MarkerKind { WHOLE_LINE, TRAILING, MID_LINE }

data class RawMarker(
    val startOffset: Int,
    val endOffset: Int,
    val body: String,
    val kind: MarkerKind,
    val line: Int,
)

object MarkerScanner {

    fun scan(file: PsiFile, sentinel: String): List<RawMarker> {
        val syntax = CommentSyntax.of(file.language)
        if (!syntax.hasAny) return emptyList()
        val text = file.text
        return PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)
            .mapNotNull { comment ->
                val body = syntax.bodyOf(comment.text).trimStart()
                // "tw::" is the escape: it is content, not a marker.
                if (!body.startsWith(sentinel) || body.startsWith("$sentinel:")) return@mapNotNull null
                val start = comment.textRange.startOffset
                val end = comment.textRange.endOffset
                RawMarker(
                    startOffset = start,
                    endOffset = end,
                    body = syntax.bodyOf(comment.text),
                    kind = classify(text, start, end),
                    line = text.take(start).count { it == '\n' } + 1,
                )
            }
    }

    /**
     * WHOLE_LINE when nothing but whitespace precedes the marker on its first line
     * and nothing but whitespace follows it on its last line. TRAILING when only the
     * tail is clear. Anything else is MID_LINE.
     */
    private fun classify(text: String, start: Int, end: Int): MarkerKind {
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val headClear = text.substring(lineStart, start).isBlank()
        val tailClear = text.substring(end, lineEnd).isBlank()
        return when {
            headClear && tailClear -> MarkerKind.WHOLE_LINE
            tailClear -> MarkerKind.TRAILING
            else -> MarkerKind.MID_LINE
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*MarkerScannerTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/parse src/test/kotlin/com/github/asm0dey/typewriter/parse
git commit -m "feat: scan snippet markers from PSI comments"
```

---

### Task 3: Program model and marker parsing

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/model/Program.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/model/Directives.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/model/ParseError.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/parse/MarkerParser.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/parse/MarkerParserTest.kt`

**Interfaces:**
- Consumes: `RawMarker`, `MarkerKind`, `MarkerScanner.scan` (Task 2).
- Produces:
  - `sealed interface Step` with `Step.Type(text: String)`, `Step.Pause(millis: Long)`, `Step.Action(actionId: String)`
  - `data class Timing(val speedMs: Int, val jitterMs: Int, val newlineMs: Int)`
  - `data class Program(val steps: List<Step>, val directives: Directives, val errors: List<ParseError>)`
  - `data class Directives(raw: Boolean, speedMs: Int?, jitterMs: Int?, newlineMs: Int?)` with `fun timing(defaults: Timing): Timing`
  - `data class ParseError(val line: Int, val message: String)`
  - `MarkerParser.parse(text: String, markers: List<RawMarker>, sentinel: String): Program`

Consumption rules, from spec section 5: whole-line takes its whole lines including
the terminator; trailing takes the whitespace separating it from the code but keeps
the line's newline; mid-line takes itself plus the horizontal whitespace *following*
it, because the formatter inserts a space after a block comment.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.parse

import com.github.asm0dey.typewriter.model.Step
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MarkerParserTest : LightJavaCodeInsightFixtureTestCase() {

    private fun parse(text: String) = myFixture.configureByText("S.java", text).let { psi ->
        MarkerParser.parse(psi.text, MarkerScanner.scan(psi, "tw:"), "tw:")
    }

    private fun typed(text: String) =
        parse(text).steps.filterIsInstance<Step.Type>().joinToString("") { it.text }

    fun testWholeLineMarkerLeavesNoBlankLine() {
        assertEquals("class A {\n    int x;\n}", typed("class A {\n// tw: pause 800\n    int x;\n}"))
    }

    fun testWholeLineMarkerKeepsItsIndentation() {
        assertEquals("class A {\n    int x;\n}", typed("class A {\n    // tw: pause 800\n    int x;\n}"))
    }

    fun testTrailingMarkerDropsSeparatingWhitespaceKeepsNewline() {
        assertEquals("class A {\n    int x;\n}", typed("class A {\n    int x;   // tw: pause 500\n}"))
    }

    fun testMidLineMarkerConsumesFollowingWhitespace() {
        assertEquals(
            "class A {\n    int y = repo.finding();\n}",
            typed("class A {\n    int y = repo.fin/* tw: pause 1 */ ding();\n}"),
        )
    }

    fun testMidLineMarkerKeepsPrecedingWhitespace() {
        assertEquals(
            "class A {\n    int y = a + b;\n}",
            typed("class A {\n    int y = a /* tw: pause 1 */ + b;\n}"),
        )
    }

    fun testMultiLineMarkerYieldsSeveralStepsInOrder() {
        val steps = parse("class A {\n/*\ntw: pause 500\ntw: action ReformatCode\n*/\n    int x;\n}").steps
        val commands = steps.filter { it !is Step.Type }
        assertEquals(listOf(Step.Pause(500), Step.Action("ReformatCode")), commands)
    }

    fun testEscapedSentinelIsTypedUnescaped() {
        assertEquals("class A {\n// tw: pause 800\n}", typed("class A {\n// tw:: pause 800\n}"))
    }

    fun testOrdinaryCommentIsTypedVerbatim() {
        val src = "class A {\n// just a note\n}"
        assertEquals(src, typed(src))
    }

    fun testUnknownCommandIsAnError() {
        val program = parse("class A {\n// tw: pasue 800\n}")
        assertEquals(1, program.errors.size)
        assertTrue(program.errors[0].message.contains("pasue"))
    }

    fun testBodyLineWithoutSentinelIsAnError() {
        val program = parse("class A {\n/*\ntw: pause 500\nnot a marker line\n*/\n}")
        assertEquals(1, program.errors.size)
    }

    fun testDirectivesAreCollected() {
        val program = parse("// tw: raw speed 80 jitter 25 newline 400\nclass A {}")
        assertTrue(program.directives.raw)
        assertEquals(80, program.directives.speedMs)
        assertEquals(25, program.directives.jitterMs)
        assertEquals(400, program.directives.newlineMs)
    }

    fun testDirectiveAfterTypedTextIsAnError() {
        val program = parse("class A {}\n// tw: speed 80\n")
        assertEquals(1, program.errors.size)
        assertTrue(program.errors[0].message.contains("directive"))
    }

    fun testOneDirectivePerLineForLanguagesWithoutBlockComments() {
        val program = parse("// tw: raw\n// tw: speed 80\nclass A {}")
        assertTrue(program.directives.raw)
        assertEquals(80, program.directives.speedMs)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*MarkerParserTest*'`
Expected: FAIL — `Unresolved reference: MarkerParser`.

- [ ] **Step 3: Write the model files**

`model/ParseError.kt`:

```kotlin
package com.github.asm0dey.typewriter.model

data class ParseError(val line: Int, val message: String)
```

`model/Directives.kt`:

```kotlin
package com.github.asm0dey.typewriter.model

data class Directives(
    val raw: Boolean = false,
    val speedMs: Int? = null,
    val jitterMs: Int? = null,
    val newlineMs: Int? = null,
) {
    fun timing(defaults: Timing) = Timing(
        speedMs = speedMs ?: defaults.speedMs,
        jitterMs = jitterMs ?: defaults.jitterMs,
        newlineMs = newlineMs ?: defaults.newlineMs,
    )

    companion object {
        val NAMES = setOf("raw", "speed", "jitter", "newline")
    }
}
```

`model/Program.kt`:

```kotlin
package com.github.asm0dey.typewriter.model

sealed interface Step {
    data class Type(val text: String) : Step
    data class Pause(val millis: Long) : Step
    data class Action(val actionId: String) : Step
}

data class Timing(val speedMs: Int, val jitterMs: Int, val newlineMs: Int)

data class Program(
    val steps: List<Step>,
    val directives: Directives,
    val errors: List<ParseError>,
)
```

- [ ] **Step 4: Write `MarkerParser.kt`**

```kotlin
package com.github.asm0dey.typewriter.parse

import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.model.ParseError
import com.github.asm0dey.typewriter.model.Program
import com.github.asm0dey.typewriter.model.Step

object MarkerParser {

    fun parse(text: String, markers: List<RawMarker>, sentinel: String): Program {
        val steps = mutableListOf<Step>()
        val errors = mutableListOf<ParseError>()
        var directives = Directives()
        var typedAnything = false
        var cursor = 0

        for (marker in markers.sortedBy { it.startOffset }) {
            val (from, to) = consumedRange(text, marker)
            if (from > cursor) {
                val chunk = text.substring(cursor, from).replace("$sentinel:", sentinel)
                if (chunk.isNotEmpty()) {
                    steps += Step.Type(chunk)
                    if (chunk.isNotBlank()) typedAnything = true
                }
            }
            for (line in marker.body.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (!trimmed.startsWith(sentinel)) {
                    errors += ParseError(marker.line, "marker body line has no \"$sentinel\": $trimmed")
                    continue
                }
                val rest = trimmed.removePrefix(sentinel).trim()
                when (val first = rest.substringBefore(' ').trim()) {
                    "pause" -> parsePause(rest, marker, errors)?.let { steps += it }
                    "action" -> parseAction(rest, marker, errors)?.let { steps += it }
                    in Directives.NAMES -> {
                        if (typedAnything) {
                            errors += ParseError(marker.line, "directive \"$first\" must precede all typed text")
                        } else {
                            directives = applyDirectives(rest, directives, marker, errors)
                        }
                    }
                    else -> errors += ParseError(marker.line, "unknown command or directive: \"$first\"")
                }
            }
            cursor = to
        }
        if (cursor < text.length) {
            val chunk = text.substring(cursor).replace("$sentinel:", sentinel)
            if (chunk.isNotEmpty()) steps += Step.Type(chunk)
        }
        return Program(steps, directives, errors)
    }

    /** Spec section 5, "Whitespace consumption". */
    private fun consumedRange(text: String, marker: RawMarker): Pair<Int, Int> = when (marker.kind) {
        MarkerKind.WHOLE_LINE -> {
            val lineStart = text.lastIndexOf('\n', (marker.startOffset - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
            val newline = text.indexOf('\n', marker.endOffset)
            lineStart to if (newline < 0) text.length else newline + 1
        }
        MarkerKind.TRAILING -> {
            var from = marker.startOffset
            while (from > 0 && (text[from - 1] == ' ' || text[from - 1] == '\t')) from--
            from to marker.endOffset
        }
        MarkerKind.MID_LINE -> {
            var to = marker.endOffset
            while (to < text.length && (text[to] == ' ' || text[to] == '\t')) to++
            marker.startOffset to to
        }
    }

    private fun parsePause(rest: String, marker: RawMarker, errors: MutableList<ParseError>): Step? {
        val arg = rest.removePrefix("pause").trim()
        val millis = arg.toLongOrNull()
        if (millis == null || millis < 0) {
            errors += ParseError(marker.line, "pause needs a non-negative number of milliseconds, got \"$arg\"")
            return null
        }
        return Step.Pause(millis)
    }

    private fun parseAction(rest: String, marker: RawMarker, errors: MutableList<ParseError>): Step? {
        val id = rest.removePrefix("action").trim()
        if (id.isEmpty()) {
            errors += ParseError(marker.line, "action needs an action id")
            return null
        }
        return Step.Action(id)
    }

    /** A directive line may carry several pairs: "raw speed 80 jitter 25". */
    private fun applyDirectives(
        rest: String,
        current: Directives,
        marker: RawMarker,
        errors: MutableList<ParseError>,
    ): Directives {
        var result = current
        val tokens = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            when (val key = tokens[i]) {
                "raw" -> { result = result.copy(raw = true); i++ }
                "speed", "jitter", "newline" -> {
                    val value = tokens.getOrNull(i + 1)?.toIntOrNull()
                    if (value == null || value < 0) {
                        errors += ParseError(marker.line, "$key needs a non-negative number, got \"${tokens.getOrNull(i + 1)}\"")
                    } else {
                        result = when (key) {
                            "speed" -> result.copy(speedMs = value)
                            "jitter" -> result.copy(jitterMs = value)
                            else -> result.copy(newlineMs = value)
                        }
                    }
                    i += 2
                }
                else -> {
                    errors += ParseError(marker.line, "unknown directive: \"$key\"")
                    i++
                }
            }
        }
        return result
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*MarkerParserTest*'`
Expected: PASS, 13 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter src/test/kotlin/com/github/asm0dey/typewriter
git commit -m "feat: parse markers into a program of type, pause and action steps"
```

---

### Task 4: Snippet formatter with guard and line reconciliation

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/format/SnippetFormatter.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/format/SnippetFormatterTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `SnippetFormatter.format(project: Project, fileType: FileType, name: String, text: String): FormatResult` where `data class FormatResult(val text: String, val warning: String?)`.

Two safety layers from spec section 6. The **guard** rejects any change to the
non-whitespace character stream. **Reconciliation** then restores the original's
line structure, because the formatter inserts blank lines between what it reads as
top-level members of a statement fragment, and the guard accepts that (it is
whitespace).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.format

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SnippetFormatterTest : LightJavaCodeInsightFixtureTestCase() {

    private fun format(text: String) =
        SnippetFormatter.format(project, JavaFileType.INSTANCE, "S.java", text)

    fun testFixesIndentation() {
        val out = format("class A {\nint x;\n        void m() {\nint y = 1;\n}\n}")
        assertEquals("class A {\n    int x;\n    void m() {\n        int y = 1;\n    }\n}", out.text)
        assertNull(out.warning)
    }

    fun testRemovesFormatterInsertedBlankLine() {
        // The formatter wants a blank line between members; the original had none.
        val out = format("class A {\n    int x;\n    void m() {\n    }\n}")
        assertEquals(5, out.text.lines().size)
        assertFalse("no blank line may be introduced", out.text.contains("\n\n"))
    }

    fun testStatementFragmentKeepsItsTwoLines() {
        val out = format("int x = 1;\nfoo(x);")
        assertEquals(listOf("int x = 1;", "foo(x);"), out.text.lines())
    }

    fun testFragmentDedentsToColumnZero() {
        val out = format("    private final Repo repo;")
        assertEquals("private final Repo repo;", out.text)
    }

    fun testBlankLinesAuthoredByTheUserSurvive() {
        val out = format("class A {\n\n    int x;\n\n    int y;\n}")
        assertEquals(6, out.text.lines().size)
    }

    fun testUnbalancedFragmentIsNotDamaged() {
        val src = "public class S {\n    private final int x = 1;\n"
        val out = format(src)
        assertEquals(
            src.filterNot { it.isWhitespace() },
            out.text.filterNot { it.isWhitespace() },
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*SnippetFormatterTest*'`
Expected: FAIL — `Unresolved reference: SnippetFormatter`.

- [ ] **Step 3: Write `SnippetFormatter.kt`**

```kotlin
package com.github.asm0dey.typewriter.format

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager

data class FormatResult(val text: String, val warning: String?)

object SnippetFormatter {

    fun format(project: Project, fileType: FileType, name: String, text: String): FormatResult {
        val formatted = try {
            reformat(project, fileType, name, text)
        } catch (e: Exception) {
            return FormatResult(text, "formatting failed (${e.javaClass.simpleName}); typed as authored")
        } ?: return FormatResult(text, "no formatter for ${fileType.name}; typed as authored")

        if (!sameNonWhitespace(text, formatted)) {
            return FormatResult(text, "the formatter changed more than whitespace; typed as authored")
        }
        return reconcileLines(text, formatted)
            ?.let { FormatResult(it, null) }
            ?: FormatResult(text, "the formatter changed the line structure; typed as authored")
    }

    private fun reformat(project: Project, fileType: FileType, name: String, text: String): String? {
        val psi = PsiFileFactory.getInstance(project)
            .createFileFromText(name, fileType, text, 0L, true) ?: return null
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(psi, 0, psi.textLength)
        }
        return psi.text
    }

    private fun sameNonWhitespace(a: String, b: String) =
        a.filterNot { it.isWhitespace() } == b.filterNot { it.isWhitespace() }

    /**
     * Keep the original's line structure, take the formatted lines' indentation and
     * spacing. Safe because the guard already proved the non-whitespace streams match,
     * so non-blank lines correspond one to one. Null when they do not — line wrapping
     * split something.
     */
    private fun reconcileLines(original: String, formatted: String): String? {
        val formattedNonBlank = formatted.lines().filter { it.isNotBlank() }
        val originalNonBlank = original.lines().filter { it.isNotBlank() }
        if (formattedNonBlank.size != originalNonBlank.size) return null
        var i = 0
        return original.lines().joinToString("\n") { if (it.isBlank()) "" else formattedNonBlank[i++] }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*SnippetFormatterTest*'`
Expected: PASS, 6 tests.

If `createFileFromText(..., physical = true)` throws or `reformatText` silently
no-ops, drop the last two arguments and use the three-argument overload; the spike
that produced these expectations used a physical fixture file. Report which
overload worked in the commit message — later tasks depend on the choice.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/format src/test/kotlin/com/github/asm0dey/typewriter/format
git commit -m "feat: format snippets with a whitespace guard and line reconciliation"
```

---

### Task 5: Base indent

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/run/BaseIndent.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/run/BaseIndentTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `BaseIndent.compute(project: Project, file: PsiFile, document: Document, caretOffset: Int): String`
  - `BaseIndent.caretColumn(document: Document, caretOffset: Int): Int`
  - `BaseIndent.apply(payload: String, indent: String, caretColumn: Int): String`

Spec section 7. Two branches: mid-line uses the caret column, blank-line asks the
IDE. The first line is padded by the **shortfall** between the indent and the
caret's column — clicking a blank line at column 0 inside a class body is the
common gesture, and without the shortfall the first line lands at column 0 while
every later line lands at 4.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.run

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class BaseIndentTest : LightJavaCodeInsightFixtureTestCase() {

    private fun indentAndColumn(host: String): Pair<String, Int> {
        val file = myFixture.configureByText("H.java", host)
        val editor = myFixture.editor
        val offset = editor.caretModel.offset
        return BaseIndent.compute(project, file, editor.document, offset) to
            BaseIndent.caretColumn(editor.document, offset)
    }

    fun testBlankLineInsideClassBodyUsesTheContextIndent() {
        val (indent, column) = indentAndColumn("class Host {\n<caret>\n}")
        assertEquals(4, indent.length)
        assertEquals(0, column)
    }

    fun testCaretAlreadyAtTheContextIndent() {
        val (indent, column) = indentAndColumn("class Host {\n    <caret>\n}")
        assertEquals(4, indent.length)
        assertEquals(4, column)
    }

    fun testTwoLevelsDeep() {
        val (indent, _) = indentAndColumn("class Host {\n    void m() {\n<caret>\n    }\n}")
        assertEquals(8, indent.length)
    }

    fun testMidLineUsesTheCaretColumn() {
        val (indent, column) = indentAndColumn("class Host {\n    void m() {\n        int q = <caret>\n    }\n}")
        assertEquals(16, indent.length)
        assertEquals(16, column)
    }

    fun testApplyPadsTheFirstLineByTheShortfall() {
        val out = BaseIndent.apply("int a = 1;\nif (a > 0) {\n    a++;\n}", "    ", 0)
        assertEquals("    int a = 1;\n    if (a > 0) {\n        a++;\n    }", out)
    }

    fun testApplyDoesNotPadTheFirstLineWhenTheCaretIsAlreadyThere() {
        val out = BaseIndent.apply("int a = 1;\nif (a > 0) {\n}", "    ", 4)
        assertEquals("int a = 1;\n    if (a > 0) {\n    }", out)
    }

    fun testApplyLeavesEmptyLinesEmpty() {
        val out = BaseIndent.apply("a;\n\nb;", "  ", 2)
        assertEquals("a;\n\n  b;", out)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*BaseIndentTest*'`
Expected: FAIL — `Unresolved reference: BaseIndent`.

- [ ] **Step 3: Write `BaseIndent.kt`**

```kotlin
package com.github.asm0dey.typewriter.run

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager

object BaseIndent {

    fun caretColumn(document: Document, caretOffset: Int): Int =
        caretOffset - document.getLineStartOffset(document.getLineNumber(caretOffset))

    fun compute(project: Project, file: PsiFile, document: Document, caretOffset: Int): String {
        val lineStart = document.getLineStartOffset(document.getLineNumber(caretOffset))
        val before = document.getText(TextRange(lineStart, caretOffset))
        val column = caretOffset - lineStart
        if (before.isNotBlank()) return " ".repeat(column)
        return CodeStyleManager.getInstance(project).getLineIndent(file, caretOffset)
            ?: " ".repeat(column)
    }

    fun apply(payload: String, indent: String, caretColumn: Int): String {
        val firstLinePad = " ".repeat((indent.length - caretColumn).coerceAtLeast(0))
        return payload.lines().mapIndexed { index, line ->
            when {
                line.isEmpty() -> line
                index == 0 -> firstLinePad + line
                else -> indent + line
            }
        }.joinToString("\n")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*BaseIndentTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/run src/test/kotlin/com/github/asm0dey/typewriter/run
git commit -m "feat: compute and apply base indent from the caret context"
```

---

### Task 6: The player

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/run/Player.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/run/PlayerTest.kt`

**Interfaces:**
- Consumes: `Step`, `Timing`, `Program` (Task 3).
- Produces: `class Player(project: Project, editor: Editor, marker: RangeMarker, runId: Any)` with
  `suspend fun play(steps: List<Step>, timing: Timing, onCaretDrift: () -> Unit = {})`.

Insertion is by **code point**, never `Char` — a surrogate pair inserted one `Char`
at a time leaves a broken half in the document. Before each insertion the caret is
compared against where the last insertion left it; a mismatch means something else
moved it (a mouse click is neither an action nor typing) and the run stops.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.editor.RangeMarker
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking

class PlayerTest : LightJavaCodeInsightFixtureTestCase() {

    private val instant = Timing(0, 0, 0)

    private fun play(initial: String, steps: List<Step>): String {
        myFixture.configureByText("P.java", initial)
        val editor = myFixture.editor
        val marker: RangeMarker = editor.document.createRangeMarker(
            editor.caretModel.offset, editor.caretModel.offset,
        )
        runBlocking { Player(project, editor, marker, Any()).play(steps, instant) }
        return editor.document.text
    }

    fun testTypesTextVerbatim() {
        assertEquals("class A {}", play("<caret>", listOf(Step.Type("class A {}"))))
    }

    fun testDoesNotAutoCloseBraces() {
        assertEquals("class A {", play("<caret>", listOf(Step.Type("class A {"))))
    }

    fun testTypesSurrogatePairs() {
        assertEquals("\"tea 🍵\"", play("<caret>", listOf(Step.Type("\"tea 🍵\""))))
    }

    fun testTypesIntoAnExistingDocumentAtTheCaret() {
        assertEquals("class Host {\n    int a = 1;\n}",
            play("class Host {\n    <caret>\n}", listOf(Step.Type("int a = 1;"))))
    }

    fun testPauseDoesNotChangeTheText() {
        assertEquals("ab", play("<caret>", listOf(Step.Type("a"), Step.Pause(0), Step.Type("b"))))
    }

    fun testTypedRangeIsTrackedByTheMarker() {
        myFixture.configureByText("P.java", "x<caret>y")
        val editor = myFixture.editor
        val offset = editor.caretModel.offset
        val marker = editor.document.createRangeMarker(offset, offset)
        marker.isGreedyToRight = true
        runBlocking { Player(project, editor, marker, Any()).play(listOf(Step.Type("ABC")), instant) }
        assertEquals("ABC", editor.document.getText(marker.textRange))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*PlayerTest*'`
Expected: FAIL — `Unresolved reference: Player`.

- [ ] **Step 3: Write `Player.kt`**

```kotlin
package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import kotlin.random.Random

class Player(
    private val project: Project,
    private val editor: Editor,
    private val marker: RangeMarker,
    private val runId: Any,
) {
    /** Set while this player invokes an IDE action, so the abort watcher ignores it. */
    @Volatile
    var invokingAction: Boolean = false
        private set

    suspend fun play(steps: List<Step>, timing: Timing, onCaretDrift: () -> Unit = {}) {
        var expectedOffset = editor.caretModel.offset
        for (step in steps) {
            when (step) {
                is Step.Pause -> delay(step.millis)
                is Step.Action -> {
                    runAction(step.actionId)
                    expectedOffset = editor.caretModel.offset
                }
                is Step.Type -> {
                    var i = 0
                    while (i < step.text.length) {
                        if (editor.caretModel.offset != expectedOffset) {
                            onCaretDrift()
                            return
                        }
                        val codePoint = step.text.codePointAt(i)
                        val chunk = String(Character.toChars(codePoint))
                        insert(chunk)
                        expectedOffset = editor.caretModel.offset
                        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
                        i += Character.charCount(codePoint)
                        delay(delayFor(chunk, timing))
                    }
                }
            }
        }
    }

    private fun insert(chunk: String) {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                    val offset = editor.caretModel.offset
                    editor.document.insertString(offset, chunk)
                    editor.caretModel.moveToOffset(offset + chunk.length)
                }
            },
            "TypeWriter",
            runId,
        )
    }

    private fun runAction(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .build()
        invokingAction = true
        try {
            ActionManager.getInstance().tryToExecute(
                action,
                null,
                editor.contentComponent,
                ActionPlaces.UNKNOWN,
                true,
            )
        } finally {
            invokingAction = false
        }
    }

    private fun delayFor(chunk: String, timing: Timing): Long {
        val base = timing.speedMs + if (chunk == "\n") timing.newlineMs else 0
        val jitter = if (timing.jitterMs > 0) Random.nextInt(-timing.jitterMs, timing.jitterMs + 1) else 0
        return (base + jitter).coerceAtLeast(0).toLong()
    }
}
```

`marker` is used by callers to read the typed range; the player itself only needs
to keep it alive across the run, which `RangeMarker` does on its own.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*PlayerTest*'`
Expected: PASS, 6 tests.

If `tryToExecute` cannot be called with a null `InputEvent` on this platform build,
replace `runAction` with `action.actionPerformed(AnActionEvent.createFromDataContext(ActionPlaces.UNKNOWN, null, context))`
and keep the `invokingAction` guard exactly as written.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/run/Player.kt src/test/kotlin/com/github/asm0dey/typewriter/run/PlayerTest.kt
git commit -m "feat: type a program into the editor by code point"
```

---

### Task 7: Smart-typing independence and nesting regression tests

**Files:**
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/run/TypingIndependenceTest.kt`

**Interfaces:**
- Consumes: `Player` (Task 6), `BaseIndent` (Task 5).
- Produces: nothing. This task is pure regression cover for the design's central premise.

The whole verbatim-insertion design rests on `document.insertString` never reaching
`TypedHandler`. That was measured once during design; this task makes it a standing
assertion so a future refactor toward typed actions fails loudly.

- [ ] **Step 1: Write the test**

```kotlin
package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking

class TypingIndependenceTest : LightJavaCodeInsightFixtureTestCase() {

    private val hazard = "class T {\n" +
        "    String s = \"hi (unbalanced\";\n" +
        "    char c = 'x';\n" +
        "    void m() {\n" +
        "        if (a[0] > 1) {\n" +
        "            f(\"}\");\n" +
        "        }\n" +
        "    }\n"

    private fun typeWithFlags(on: Boolean): String {
        val s = CodeInsightSettings.getInstance()
        val saved = intArrayOf(
            if (s.AUTOINSERT_PAIR_BRACKET) 1 else 0,
            if (s.AUTOINSERT_PAIR_QUOTE) 1 else 0,
            if (s.SMART_INDENT_ON_ENTER) 1 else 0,
            if (s.INSERT_BRACE_ON_ENTER) 1 else 0,
            if (s.REFORMAT_BLOCK_ON_RBRACE) 1 else 0,
            if (s.SURROUND_SELECTION_ON_QUOTE_TYPED) 1 else 0,
        )
        s.AUTOINSERT_PAIR_BRACKET = on
        s.AUTOINSERT_PAIR_QUOTE = on
        s.SMART_INDENT_ON_ENTER = on
        s.INSERT_BRACE_ON_ENTER = on
        s.REFORMAT_BLOCK_ON_RBRACE = on
        s.SURROUND_SELECTION_ON_QUOTE_TYPED = on
        try {
            myFixture.configureByText(if (on) "On.java" else "Off.java", "")
            val editor = myFixture.editor
            val marker = editor.document.createRangeMarker(0, 0)
            runBlocking {
                Player(project, editor, marker, Any()).play(listOf(Step.Type(hazard)), Timing(0, 0, 0))
            }
            return editor.document.text
        } finally {
            s.AUTOINSERT_PAIR_BRACKET = saved[0] == 1
            s.AUTOINSERT_PAIR_QUOTE = saved[1] == 1
            s.SMART_INDENT_ON_ENTER = saved[2] == 1
            s.INSERT_BRACE_ON_ENTER = saved[3] == 1
            s.REFORMAT_BLOCK_ON_RBRACE = saved[4] == 1
            s.SURROUND_SELECTION_ON_QUOTE_TYPED = saved[5] == 1
        }
    }

    fun testSmartTypingFlagsDoNotAffectOutput() {
        val on = typeWithFlags(true)
        val off = typeWithFlags(false)
        assertEquals("flags ON must not alter output", hazard, on)
        assertEquals("flags OFF must not alter output", hazard, off)
        assertEquals(on, off)
    }

    fun testFragmentLandsAtBaseIndentInsideAnExistingBody() {
        myFixture.configureByText("N.java", "class Host {\n<caret>\n}")
        val editor = myFixture.editor
        val offset = editor.caretModel.offset
        val indent = BaseIndent.compute(project, myFixture.file, editor.document, offset)
        val column = BaseIndent.caretColumn(editor.document, offset)
        val payload = BaseIndent.apply("int a = 1;\nif (a > 0) {\n    a++;\n}", indent, column)
        val marker = editor.document.createRangeMarker(offset, offset)
        runBlocking { Player(project, editor, marker, Any()).play(listOf(Step.Type(payload)), Timing(0, 0, 0)) }
        assertEquals(
            "class Host {\n    int a = 1;\n    if (a > 0) {\n        a++;\n    }\n}",
            editor.document.text,
        )
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests '*TypingIndependenceTest*'`
Expected: PASS, 2 tests. Both were measured during design; a failure means the
player stopped using `document.insertString` or lost its base-indent application.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/github/asm0dey/typewriter/run/TypingIndependenceTest.kt
git commit -m "test: pin typing independence from smart-typing settings"
```

---

### Task 8: Snippet library with directory layering

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/model/Snippet.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/library/SnippetLibrary.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/library/SnippetLibraryTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `data class Snippet(val id: String, val relativePath: String, val file: VirtualFile, val fileType: FileType, val fromProject: Boolean)`
  - `SnippetLibrary.actionId(relativePath: String): String` returning `"typewriter.snippet.$relativePath"`
  - `SnippetLibrary.collect(globalDir: VirtualFile?, projectDir: VirtualFile?): List<Snippet>` — union, project shadows global, sorted by relative path
  - `SnippetLibrary.sequence(projectDir: VirtualFile?): List<Snippet>` — project directory only, sorted
  - `SnippetLibrary.textOf(snippet: Snippet): String?` — the `Document` text, never the bytes

Spec section 8. The id is the relative path, independent of which directory
supplied the file, so one binding drives the corresponding snippet in every demo
project and shadowing needs no arbitration.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.library

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SnippetLibraryTest : LightJavaCodeInsightFixtureTestCase() {

    private fun dir(name: String): VirtualFile = WriteAction.compute<VirtualFile, Exception> {
        myFixture.tempDirFixture.findOrCreateDir(name)
    }

    private fun file(parent: VirtualFile, path: String, text: String): VirtualFile =
        WriteAction.compute<VirtualFile, Exception> {
            val segments = path.split("/")
            var target = parent
            for (segment in segments.dropLast(1)) {
                target = target.findChild(segment) ?: target.createChildDirectory(this, segment)
            }
            val created = target.createChildData(this, segments.last())
            created.setBinaryContent(text.toByteArray())
            created
        }

    fun testActionIdIsTheRelativePath() {
        assertEquals("typewriter.snippet.01-entity.kt", SnippetLibrary.actionId("01-entity.kt"))
        assertEquals("typewriter.snippet.jcon26/01.kt", SnippetLibrary.actionId("jcon26/01.kt"))
    }

    fun testProjectShadowsGlobalOnTheSameRelativePath() {
        val global = dir("global")
        val local = dir("local")
        file(global, "01.java", "// global")
        file(local, "01.java", "// project")
        val snippets = SnippetLibrary.collect(global, local)
        assertEquals(1, snippets.size)
        assertTrue(snippets[0].fromProject)
    }

    fun testGlobalSnippetsRemainAvailableAlongsideProjectOnes() {
        val global = dir("global2")
        val local = dir("local2")
        file(global, "toolkit.java", "// toolkit")
        file(local, "01.java", "// step")
        val paths = SnippetLibrary.collect(global, local).map { it.relativePath }
        assertEquals(listOf("01.java", "toolkit.java"), paths)
    }

    fun testSubdirectoriesAreIncludedAndSortedByRelativePath() {
        val local = dir("local3")
        file(local, "jcon26/02.java", "// b")
        file(local, "jcon26/01.java", "// a")
        val paths = SnippetLibrary.collect(null, local).map { it.relativePath }
        assertEquals(listOf("jcon26/01.java", "jcon26/02.java"), paths)
    }

    fun testSequenceIsTheProjectDirectoryOnly() {
        val global = dir("global4")
        val local = dir("local4")
        file(global, "toolkit.java", "// toolkit")
        file(local, "01.java", "// step")
        assertEquals(listOf("01.java"), SnippetLibrary.sequence(local).map { it.relativePath })
    }

    fun testTextComesFromTheDocumentIncludingUnsavedEdits() {
        val local = dir("local5")
        val vf = file(local, "01.java", "// saved")
        val document = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteAction.run<Exception> { document.setText("// unsaved") }
        val snippet = SnippetLibrary.collect(null, local).single()
        assertEquals("// unsaved", SnippetLibrary.textOf(snippet))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*SnippetLibraryTest*'`
Expected: FAIL — `Unresolved reference: SnippetLibrary`.

- [ ] **Step 3: Write `Snippet.kt`**

```kotlin
package com.github.asm0dey.typewriter.model

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile

data class Snippet(
    val id: String,
    val relativePath: String,
    val file: VirtualFile,
    val fileType: FileType,
    val fromProject: Boolean,
)
```

- [ ] **Step 4: Write `SnippetLibrary.kt`**

```kotlin
package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.model.Snippet
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VirtualFile

object SnippetLibrary {

    const val ID_PREFIX = "typewriter.snippet."

    fun actionId(relativePath: String): String = ID_PREFIX + relativePath

    fun collect(globalDir: VirtualFile?, projectDir: VirtualFile?): List<Snippet> {
        val byPath = LinkedHashMap<String, Snippet>()
        globalDir?.let { root -> walk(root, root, fromProject = false).forEach { byPath[it.relativePath] = it } }
        projectDir?.let { root -> walk(root, root, fromProject = true).forEach { byPath[it.relativePath] = it } }
        return byPath.values.sortedBy { it.relativePath }
    }

    fun sequence(projectDir: VirtualFile?): List<Snippet> =
        projectDir?.let { walk(it, it, fromProject = true).sortedBy { s -> s.relativePath } } ?: emptyList()

    fun textOf(snippet: Snippet): String? =
        FileDocumentManager.getInstance().getDocument(snippet.file)?.text

    private fun walk(root: VirtualFile, dir: VirtualFile, fromProject: Boolean): List<Snippet> =
        dir.children.orEmpty().flatMap { child ->
            when {
                child.isDirectory -> walk(root, child, fromProject)
                else -> {
                    val relative = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(child, root) ?: return@flatMap emptyList()
                    listOf(
                        Snippet(
                            id = actionId(relative),
                            relativePath = relative,
                            file = child,
                            fileType = FileTypeManager.getInstance().getFileTypeByFileName(child.name),
                            fromProject = fromProject,
                        )
                    )
                }
            }
        }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*SnippetLibraryTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/library src/main/kotlin/com/github/asm0dey/typewriter/model/Snippet.kt src/test/kotlin/com/github/asm0dey/typewriter/library
git commit -m "feat: collect snippets from layered directories with project shadowing"
```

---

### Task 9: Settings

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/ui/TypeWriterSettings.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` — register the services
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/ui/TypeWriterSettingsTest.kt`

**Interfaces:**
- Consumes: `Timing` (Task 3).
- Produces:
  - `@Service(APP) class TypeWriterSettings : PersistentStateComponent<TypeWriterSettings.State>` with
    `State(globalDir: String, speedMs: Int = 100, jitterMs: Int = 20, newlineMs: Int = 300, sentinel: String = "tw:", formatOnPlay: Boolean = true)`
    and `fun defaultTiming(): Timing`
  - `@Service(PROJECT) class TypeWriterProjectSettings : PersistentStateComponent<TypeWriterProjectSettings.State>` with
    `State(projectDir: String = ".typewriter")`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.ui

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class TypeWriterSettingsTest : LightJavaCodeInsightFixtureTestCase() {

    fun testDefaults() {
        val state = TypeWriterSettings.State()
        assertEquals(100, state.speedMs)
        assertEquals(20, state.jitterMs)
        assertEquals(300, state.newlineMs)
        assertEquals("tw:", state.sentinel)
        assertTrue(state.formatOnPlay)
        assertTrue(state.globalDir.endsWith(".typewriter"))
    }

    fun testDefaultTimingMirrorsState() {
        val settings = TypeWriterSettings()
        settings.loadState(TypeWriterSettings.State(speedMs = 42, jitterMs = 7, newlineMs = 9))
        val timing = settings.defaultTiming()
        assertEquals(42, timing.speedMs)
        assertEquals(7, timing.jitterMs)
        assertEquals(9, timing.newlineMs)
    }

    fun testProjectDirDefault() {
        assertEquals(".typewriter", TypeWriterProjectSettings.State().projectDir)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*TypeWriterSettingsTest*'`
Expected: FAIL — `Unresolved reference: TypeWriterSettings`.

- [ ] **Step 3: Write `TypeWriterSettings.kt`**

```kotlin
package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Paths

@Service(Service.Level.APP)
@State(name = "TypeWriterSettings", storages = [Storage("typewriter.xml")])
class TypeWriterSettings : PersistentStateComponent<TypeWriterSettings.State> {

    data class State(
        var globalDir: String = Paths.get(System.getProperty("user.home"), ".typewriter").toString(),
        var speedMs: Int = 100,
        var jitterMs: Int = 20,
        var newlineMs: Int = 300,
        var sentinel: String = "tw:",
        var formatOnPlay: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun defaultTiming() = Timing(state.speedMs, state.jitterMs, state.newlineMs)
}

@Service(Service.Level.PROJECT)
@State(name = "TypeWriterProjectSettings", storages = [Storage("typewriter.xml")])
class TypeWriterProjectSettings : PersistentStateComponent<TypeWriterProjectSettings.State> {

    data class State(var projectDir: String = ".typewriter")

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
```

- [ ] **Step 4: Register the services in `plugin.xml`**

Replace the descriptor body with:

```xml
<idea-plugin>
    <id>com.github.asm0dey.typewriterplugin</id>
    <name>TypeWriter</name>
    <vendor>asm0dey</vendor>
    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.github.asm0dey.typewriter.ui.TypeWriterSettings"/>
        <projectService serviceImplementation="com.github.asm0dey.typewriter.ui.TypeWriterProjectSettings"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*TypeWriterSettingsTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/ui src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/asm0dey/typewriter/ui
git commit -m "feat: persist application and project settings"
```

---

### Task 10: Pre-flight checks

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/run/PreFlight.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/run/PreFlightTest.kt`

**Interfaces:**
- Consumes: `Snippet` (Task 8), `Program`, `ParseError` (Task 3).
- Produces:
  - `sealed interface Check { data class Error(val message: String) : Check; data class Warning(val message: String) : Check }`
  - `PreFlight.check(project: Project, editor: Editor?, snippet: Snippet, program: Program, formatWarning: String?): List<Check>`
  - `fun List<Check>.blocked(): Boolean` — true when any `Check.Error` is present

Spec section 11. Everything here runs before the first character; an error means
nothing is typed at all, so a mistyped action id never leaves half a snippet on
screen mid-demo.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.model.ParseError
import com.github.asm0dey.typewriter.model.Program
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.model.Step
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class PreFlightTest : LightJavaCodeInsightFixtureTestCase() {

    private fun snippet(name: String = "01.java"): Snippet {
        val dir = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Exception> {
            myFixture.tempDirFixture.findOrCreateDir("pf")
        }
        val vf = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Exception> {
            (dir.findChild(name) ?: dir.createChildData(this, name)).also {
                it.setBinaryContent("// x".toByteArray())
            }
        }
        return Snippet("typewriter.snippet.$name", name, vf, JavaFileType.INSTANCE, true)
    }

    private fun program(vararg steps: Step, errors: List<ParseError> = emptyList()) =
        Program(steps.toList(), Directives(), errors)

    fun testNoEditorIsAnError() {
        val checks = PreFlight.check(project, null, snippet(), program(Step.Type("x")), null)
        assertTrue(checks.blocked())
    }

    fun testParseErrorsAreErrors() {
        myFixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(
            project, myFixture.editor, snippet(),
            program(Step.Type("x"), errors = listOf(ParseError(3, "unknown command"))), null,
        )
        assertTrue(checks.blocked())
        assertTrue(checks.any { it is Check.Error && it.message.contains("line 3") })
    }

    fun testUnknownActionIdIsAnError() {
        myFixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(
            project, myFixture.editor, snippet(),
            program(Step.Action("NoSuchActionIdAnywhere")), null,
        )
        assertTrue(checks.blocked())
    }

    fun testKnownActionIdIsAccepted() {
        myFixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(
            project, myFixture.editor, snippet(),
            program(Step.Action("ReformatCode"), Step.Type("x")), null,
        )
        assertFalse(checks.blocked())
    }

    fun testEmptyProgramIsAWarningNotAnError() {
        myFixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(project, myFixture.editor, snippet(), program(), null)
        assertFalse(checks.blocked())
        assertTrue(checks.any { it is Check.Warning })
    }

    fun testFormatWarningIsCarriedThrough() {
        myFixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(
            project, myFixture.editor, snippet(), program(Step.Type("x")), "guard tripped",
        )
        assertFalse(checks.blocked())
        assertTrue(checks.any { it is Check.Warning && it.message.contains("guard tripped") })
    }

    fun testTypingIntoTheSnippetsOwnFileIsAnError() {
        val s = snippet("self.java")
        myFixture.openFileInEditor(s.file)
        val checks = PreFlight.check(project, myFixture.editor, s, program(Step.Type("x")), null)
        assertTrue(checks.blocked())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*PreFlightTest*'`
Expected: FAIL — `Unresolved reference: PreFlight`.

- [ ] **Step 3: Write `PreFlight.kt`**

```kotlin
package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Program
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.model.Step
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

sealed interface Check {
    data class Error(val message: String) : Check
    data class Warning(val message: String) : Check
}

fun List<Check>.blocked(): Boolean = any { it is Check.Error }

object PreFlight {

    fun check(
        project: Project,
        editor: Editor?,
        snippet: Snippet,
        program: Program,
        formatWarning: String?,
    ): List<Check> {
        val checks = mutableListOf<Check>()

        if (editor == null) {
            return listOf(Check.Error("no editor is focused"))
        }
        if (!editor.document.isWritable) {
            checks += Check.Error("the target file is read-only")
        }
        val targetFile = FileDocumentManager.getInstance().getFile(editor.document)
        if (targetFile != null && targetFile == snippet.file) {
            checks += Check.Error("the target editor is the snippet's own file")
        }
        if (FileDocumentManager.getInstance().getDocument(snippet.file) == null) {
            checks += Check.Error("${snippet.relativePath} has no readable text")
        }

        for (error in program.errors) {
            checks += Check.Error("${snippet.relativePath} line ${error.line}: ${error.message}")
        }

        val actionManager = ActionManager.getInstance()
        program.steps.filterIsInstance<Step.Action>()
            .map { it.actionId }
            .distinct()
            .filter { actionManager.getAction(it) == null }
            .forEach { checks += Check.Error("${snippet.relativePath}: unknown action id \"$it\"") }

        if (targetFile != null && targetFile.fileType != snippet.fileType) {
            checks += Check.Warning(
                "snippet is ${snippet.fileType.name} but the target file is ${targetFile.fileType.name}"
            )
        }
        if (program.steps.isEmpty()) {
            checks += Check.Warning("${snippet.relativePath} is empty")
        }
        formatWarning?.let { checks += Check.Warning(it) }

        if (editor.caretModel.caretCount > 1) {
            checks += Check.Warning("multiple carets; only the primary caret is used")
        }
        return checks
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*PreFlightTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/run/PreFlight.kt src/test/kotlin/com/github/asm0dey/typewriter/run/PreFlightTest.kt
git commit -m "feat: gate every run behind pre-flight checks"
```

---

### Task 11: Run service, abort watcher and undo

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/run/RunService.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/run/AbortWatcher.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` — register `RunService`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/run/RunServiceTest.kt`

**Interfaces:**
- Consumes: `Player` (Task 6), `Program`/`Timing` (Task 3), `Snippet` (Task 8).
- Produces:
  - `@Service(PROJECT) class RunService(project: Project, scope: CoroutineScope)` with
    `fun isRunning(): Boolean`, `fun cancel()`,
    `fun launch(editor: Editor, steps: List<Step>, timing: Timing)`,
    `suspend fun run(editor: Editor, steps: List<Step>, timing: Timing): Boolean`,
    `fun undoLastRun(): Boolean`, `fun canUndoLastRun(): Boolean`,
    `var cursor: Int`
  - `class AbortWatcher(player: Player, onAbort: () -> Unit) : AnActionListener` with `fun install(parent: Disposable)`

Spec section 7. A run cannot be paused, only aborted, so `Undo Run` is the
recovery path and must not depend on the platform merging per-character commands.
It reads the typed range from the `RangeMarker` and is valid only while the
document's modification stamp is unchanged.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking

class RunServiceTest : LightJavaCodeInsightFixtureTestCase() {

    private fun service() = project.getService(RunService::class.java)

    fun testRunTypesAndUndoRemovesExactlyTheTypedRange() {
        myFixture.configureByText("R.java", "before<caret>after")
        val svc = service()
        runBlocking { svc.run(myFixture.editor, listOf(Step.Type("TYPED")), Timing(0, 0, 0)) }
        assertEquals("beforeTYPEDafter", myFixture.editor.document.text)
        assertTrue(svc.canUndoLastRun())
        WriteAction.run<Exception> { svc.undoLastRun() }
        assertEquals("beforeafter", myFixture.editor.document.text)
    }

    fun testUndoIsInvalidatedByLaterEdits() {
        myFixture.configureByText("R.java", "<caret>")
        val svc = service()
        runBlocking { svc.run(myFixture.editor, listOf(Step.Type("abc")), Timing(0, 0, 0)) }
        WriteAction.run<Exception> { myFixture.editor.document.insertString(0, "z") }
        assertFalse(svc.canUndoLastRun())
    }

    fun testASecondRunIsRefusedWhileOneIsActive() {
        myFixture.configureByText("R.java", "<caret>")
        val svc = service()
        runBlocking {
            svc.run(myFixture.editor, listOf(Step.Type("a")), Timing(0, 0, 0))
            assertFalse(svc.isRunning())
        }
    }

    fun testCursorRoundTrips() {
        val svc = service()
        svc.cursor = 3
        assertEquals(3, svc.cursor)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*RunServiceTest*'`
Expected: FAIL — `Unresolved reference: RunService`.

- [ ] **Step 3: Write `RunService.kt`**

```kotlin
package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private class LastRun(val editor: Editor, val marker: RangeMarker, val stamp: Long)

@Service(Service.Level.PROJECT)
class RunService(private val project: Project, private val scope: CoroutineScope) {

    private val running = AtomicBoolean(false)
    private var lastRun: LastRun? = null
    private var currentPlayer: Player? = null

    var cursor: Int = 0

    fun isRunning(): Boolean = running.get()

    fun cancel() {
        currentPlayer = null
        running.set(false)
    }

    /** Fire and forget from an action; the service owns the scope, so no GlobalScope. */
    fun launch(editor: Editor, steps: List<Step>, timing: Timing) {
        scope.launch(Dispatchers.EDT) { run(editor, steps, timing) }
    }

    suspend fun run(editor: Editor, steps: List<Step>, timing: Timing): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val offset = editor.caretModel.offset
        val marker = editor.document.createRangeMarker(offset, offset).apply {
            isGreedyToRight = true
        }
        val player = Player(project, editor, marker, Any())
        currentPlayer = player
        val watcherParent = Disposer.newDisposable("typewriter-run")
        AbortWatcher(player) { cancel() }.install(watcherParent)
        try {
            player.play(steps, timing) { cancel() }
        } finally {
            Disposer.dispose(watcherParent)
            currentPlayer = null
            running.set(false)
            lastRun = LastRun(editor, marker, editor.document.modificationStamp)
        }
        return true
    }

    fun canUndoLastRun(): Boolean {
        val run = lastRun ?: return false
        return run.marker.isValid &&
            run.editor.document.modificationStamp == run.stamp &&
            run.marker.endOffset > run.marker.startOffset
    }

    fun undoLastRun(): Boolean {
        if (!canUndoLastRun()) return false
        val run = lastRun ?: return false
        WriteCommandAction.runWriteCommandAction(project, "TypeWriter: Undo Run", null, {
            run.editor.document.deleteString(run.marker.startOffset, run.marker.endOffset)
        })
        lastRun = null
        return true
    }
}
```

- [ ] **Step 4: Write `AbortWatcher.kt`**

```kotlin
package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.library.SnippetLibrary
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager

/**
 * Cancels a run on any IDE action or any character the user types. Deliberately not
 * an AWT KeyListener: the key-up events of the chord that started the run arrive
 * after it begins, and auto-repeat defeats a grace period.
 */
class AbortWatcher(
    private val player: Player,
    private val onAbort: () -> Unit,
) : AnActionListener {

    fun install(parent: Disposable) {
        ApplicationManager.getApplication().messageBus
            .connect(parent)
            .subscribe(AnActionListener.TOPIC, this)
    }

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (player.invokingAction) return
        val id = com.intellij.openapi.actionSystem.ActionManager.getInstance().getId(action)
        if (id != null && (id.startsWith("typewriter.") || id.startsWith(SnippetLibrary.ID_PREFIX))) return
        onAbort()
    }

    override fun beforeEditorTyping(c: Char, dataContext: DataContext) {
        if (player.invokingAction) return
        onAbort()
    }
}
```

- [ ] **Step 5: Register `RunService` in `plugin.xml`**

Add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<projectService serviceImplementation="com.github.asm0dey.typewriter.run.RunService"/>
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests '*RunServiceTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/run src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/asm0dey/typewriter/run/RunServiceTest.kt
git commit -m "feat: run service with action-level abort and undo-run recovery"
```

---

### Task 12: Snippet actions, registration and the sequence

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/library/SnippetActions.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/library/SnippetRegistrar.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/library/SnippetRegistrarTest.kt`

**Interfaces:**
- Consumes: `SnippetLibrary` (Task 8), `RunService` (Task 11), `PreFlight` (Task 10),
  `SnippetFormatter` (Task 4), `MarkerScanner`/`MarkerParser` (Tasks 2-3), `BaseIndent` (Task 5).
- Produces:
  - `class TypeSnippetAction(relativePath: String) : AnAction`
  - `object SnippetRegistrar { fun sync(); fun registeredIds(): Set<String> }`
  - `class TypeNextAction : AnAction`, `class TypePreviousAction : AnAction`, `class UndoRunAction : AnAction`
  - `object SnippetRunner { fun run(project: Project, editor: Editor?, snippet: Snippet) }`
  - `object SnippetDirs { fun global(): VirtualFile?; fun project(project: Project): VirtualFile?; fun all(project: Project): List<Snippet> }` — consumed by Tasks 13, 14 and 17

`SnippetRegistrar.sync()` registers one action per known relative path and
unregisters ones whose files are gone. It **never touches the keymap** — the IDE
owns bindings, which is the whole fix for v1's shortcut handling.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.library

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SnippetRegistrarTest : LightJavaCodeInsightFixtureTestCase() {

    fun testRegisterAndUnregisterByRelativePath() {
        val id = SnippetLibrary.actionId("unit-test-snippet.java")
        SnippetRegistrar.register(listOf("unit-test-snippet.java"))
        assertNotNull(ActionManager.getInstance().getAction(id))
        assertTrue(SnippetRegistrar.registeredIds().contains(id))

        SnippetRegistrar.register(emptyList())
        assertNull(ActionManager.getInstance().getAction(id))
        assertFalse(SnippetRegistrar.registeredIds().contains(id))
    }

    fun testRegisteringTwiceIsIdempotent() {
        SnippetRegistrar.register(listOf("idem.java"))
        SnippetRegistrar.register(listOf("idem.java"))
        assertNotNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("idem.java")))
        SnippetRegistrar.register(emptyList())
    }

    fun testActionTextIsTheRelativePath() {
        SnippetRegistrar.register(listOf("jcon26/01.java"))
        val action = ActionManager.getInstance().getAction(SnippetLibrary.actionId("jcon26/01.java"))
        assertEquals("Type: jcon26/01.java", action.templatePresentation.text)
        SnippetRegistrar.register(emptyList())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*SnippetRegistrarTest*'`
Expected: FAIL — `Unresolved reference: SnippetRegistrar`.

- [ ] **Step 3: Write `SnippetRegistrar.kt`**

```kotlin
package com.github.asm0dey.typewriter.library

import com.intellij.openapi.actionSystem.ActionManager

object SnippetRegistrar {

    private val registered = linkedSetOf<String>()

    fun registeredIds(): Set<String> = registered.toSet()

    /** Registers exactly [relativePaths]; anything previously registered and absent is removed. */
    @Synchronized
    fun register(relativePaths: List<String>) {
        val manager = ActionManager.getInstance()
        val wanted = relativePaths.map { SnippetLibrary.actionId(it) }.toSet()

        for (id in registered - wanted) {
            if (manager.getAction(id) != null) manager.unregisterAction(id)
        }
        registered.retainAll(wanted)

        for (path in relativePaths) {
            val id = SnippetLibrary.actionId(path)
            if (registered.contains(id)) continue
            if (manager.getAction(id) == null) {
                manager.registerAction(id, TypeSnippetAction(path))
            }
            registered += id
        }
    }
}
```

- [ ] **Step 4: Write `SnippetActions.kt`**

```kotlin
package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.format.SnippetFormatter
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.parse.MarkerParser
import com.github.asm0dey.typewriter.parse.MarkerScanner
import com.github.asm0dey.typewriter.run.BaseIndent
import com.github.asm0dey.typewriter.run.Check
import com.github.asm0dey.typewriter.run.PreFlight
import com.github.asm0dey.typewriter.run.RunService
import com.github.asm0dey.typewriter.run.blocked
import com.github.asm0dey.typewriter.ui.TypeWriterProjectSettings
import com.github.asm0dey.typewriter.ui.TypeWriterSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import java.nio.file.Paths

object SnippetDirs {

    fun global(): VirtualFile? {
        val path = ApplicationManager.getApplication()
            .getService(TypeWriterSettings::class.java).state.globalDir
        return LocalFileSystem.getInstance().findFileByNioFile(Paths.get(path))
    }

    fun project(project: Project): VirtualFile? {
        val relative = project.getService(TypeWriterProjectSettings::class.java).state.projectDir
        val base = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(Paths.get(base).resolve(relative))
    }

    fun all(project: Project) = SnippetLibrary.collect(global(), project(project))
}

object SnippetRunner {

    private const val GROUP = "TypeWriter"

    fun run(project: Project, editor: Editor?, snippet: Snippet) {
        val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)
        val text = SnippetLibrary.textOf(snippet)
        if (text == null) {
            notify(project, "${snippet.relativePath} has no readable text", NotificationType.ERROR)
            return
        }

        var formatWarning: String? = null
        var source = text
        val psiForDirectives = PsiFileFactory.getInstance(project)
            .createFileFromText(snippet.file.name, snippet.fileType, text, 0L, true)
        val probe = MarkerParser.parse(
            text, MarkerScanner.scan(psiForDirectives, settings.state.sentinel), settings.state.sentinel,
        )
        if (settings.state.formatOnPlay && !probe.directives.raw) {
            val result = SnippetFormatter.format(project, snippet.fileType, snippet.file.name, text)
            source = result.text
            formatWarning = result.warning
        }

        val psi = PsiFileFactory.getInstance(project)
            .createFileFromText(snippet.file.name, snippet.fileType, source, 0L, true)
        val program = MarkerParser.parse(
            source, MarkerScanner.scan(psi, settings.state.sentinel), settings.state.sentinel,
        )

        val checks = PreFlight.check(project, editor, snippet, program, formatWarning)
        checks.filterIsInstance<Check.Warning>().forEach {
            notify(project, it.message, NotificationType.WARNING)
        }
        if (checks.blocked()) {
            checks.filterIsInstance<Check.Error>().forEach {
                notify(project, it.message, NotificationType.ERROR)
            }
            return
        }
        val target = editor ?: return

        val psiTarget = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(target.document)
        val offset = target.caretModel.offset
        val indent = if (psiTarget == null) "" else
            BaseIndent.compute(project, psiTarget, target.document, offset)
        val column = BaseIndent.caretColumn(target.document, offset)

        val steps = program.steps.map { step ->
            if (step is Step.Type) Step.Type(BaseIndent.apply(step.text, indent, column)) else step
        }
        val timing = program.directives.timing(settings.defaultTiming())
        project.getService(RunService::class.java).launch(target, steps, timing)
    }

    fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(message, type)
            .notify(project)
    }
}

class TypeSnippetAction(private val relativePath: String) : AnAction("Type: $relativePath"), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val snippet = SnippetDirs.all(project).firstOrNull { it.relativePath == relativePath } ?: return
        SnippetRunner.run(project, e.getData(CommonDataKeys.EDITOR), snippet)
    }
}

/**
 * `cursor` is the index of the snippet that will be typed NEXT. Type Next types
 * cursor and advances; Type Previous steps back two (undoing the advance, then
 * one more) and types that.
 */
object Sequence {
    fun playAt(e: AnActionEvent, index: Int) {
        val project = e.project ?: return
        val sequence = SnippetLibrary.sequence(SnippetDirs.project(project))
        if (sequence.isEmpty()) {
            SnippetRunner.notify(project, "no snippets in the project directory", NotificationType.WARNING)
            return
        }
        val clamped = index.coerceIn(0, sequence.size - 1)
        val service = project.getService(RunService::class.java)
        SnippetRunner.run(project, e.getData(CommonDataKeys.EDITOR), sequence[clamped])
        service.cursor = (clamped + 1).coerceAtMost(sequence.size - 1)
    }
}

class TypeNextAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        Sequence.playAt(e, project.getService(RunService::class.java).cursor)
    }
}

class TypePreviousAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        Sequence.playAt(e, project.getService(RunService::class.java).cursor - 2)
    }
}

class UndoRunAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            project.getService(RunService::class.java).canUndoLastRun()
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.getService(RunService::class.java)?.undoLastRun()
    }
}
```

- [ ] **Step 5: Declare the static actions and the notification group in `plugin.xml`**

Add after `</extensions>`:

```xml
<extensions defaultExtensionNs="com.intellij">
    <notificationGroup id="TypeWriter" displayType="BALLOON"/>
</extensions>

<actions>
    <group id="typewriter.snippets" text="TypeWriter" popup="true"/>
    <action id="typewriter.typeNext" class="com.github.asm0dey.typewriter.library.TypeNextAction"
            text="TypeWriter: Type Next"/>
    <action id="typewriter.typePrevious" class="com.github.asm0dey.typewriter.library.TypePreviousAction"
            text="TypeWriter: Type Previous"/>
    <action id="typewriter.undoRun" class="com.github.asm0dey.typewriter.library.UndoRunAction"
            text="TypeWriter: Undo Run"/>
</actions>
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests '*SnippetRegistrarTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/library src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/asm0dey/typewriter/library
git commit -m "feat: register one action per snippet and drive the talk sequence"
```

---

### Task 13: Startup registration and VFS watching

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/library/SnippetWatcher.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/library/SnippetWatcherTest.kt`

**Interfaces:**
- Consumes: `SnippetRegistrar`, `SnippetDirs`, `SnippetLibrary` (Tasks 8, 12).
- Produces: `class SnippetStartupActivity : ProjectActivity`, `class SnippetWatcher : BulkFileListener`.

Registration must happen before the user can press a key. Keymap bindings survive
for ids that are not registered yet — `KeymapImpl` serialises every id in its map
without consulting `ActionManager` — so the only real requirement is
"registered before the keystroke", not "registered before the keymap loads".

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.library

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SnippetWatcherTest : LightJavaCodeInsightFixtureTestCase() {

    fun testSyncRegistersEveryCollectedSnippet() {
        val dir = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Exception> {
            myFixture.tempDirFixture.findOrCreateDir("watch")
        }
        WriteAction.run<Exception> {
            dir.createChildData(this, "01.java").setBinaryContent("// a".toByteArray())
            dir.createChildData(this, "02.java").setBinaryContent("// b".toByteArray())
        }
        SnippetRegistrar.register(SnippetLibrary.collect(null, dir).map { it.relativePath })

        assertNotNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("01.java")))
        assertNotNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("02.java")))

        SnippetRegistrar.register(emptyList())
    }

    fun testRemovingAFileUnregistersItsAction() {
        val dir = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Exception> {
            myFixture.tempDirFixture.findOrCreateDir("watch2")
        }
        val file = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Exception> {
            dir.createChildData(this, "gone.java").also { it.setBinaryContent("// x".toByteArray()) }
        }
        SnippetRegistrar.register(SnippetLibrary.collect(null, dir).map { it.relativePath })
        assertNotNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("gone.java")))

        WriteAction.run<Exception> { file.delete(this) }
        SnippetRegistrar.register(SnippetLibrary.collect(null, dir).map { it.relativePath })
        assertNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("gone.java")))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*SnippetWatcherTest*'`
Expected: FAIL — the second test fails because nothing unregisters, or the file does
not compile if `SnippetRegistrar.register` is absent.

- [ ] **Step 3: Write `SnippetWatcher.kt`**

```kotlin
package com.github.asm0dey.typewriter.library

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

object SnippetSync {
    fun syncAll() {
        val paths = ProjectManager.getInstance().openProjects
            .flatMap { SnippetDirs.all(it) }
            .map { it.relativePath }
            .distinct()
        SnippetRegistrar.register(paths)
    }
}

class SnippetStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SnippetSync.syncAll()
    }
}

class SnippetWatcher : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.isEmpty()) return
        ApplicationManager.getApplication().invokeLater { SnippetSync.syncAll() }
    }
}
```

- [ ] **Step 4: Register both in `plugin.xml`**

Inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<postStartupActivity implementation="com.github.asm0dey.typewriter.library.SnippetStartupActivity"/>
```

And as a top-level element of `<idea-plugin>`:

```xml
<applicationListeners>
    <listener class="com.github.asm0dey.typewriter.library.SnippetWatcher"
              topic="com.intellij.openapi.vfs.newvfs.BulkFileListener"/>
</applicationListeners>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*SnippetWatcherTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/library/SnippetWatcher.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/asm0dey/typewriter/library/SnippetWatcherTest.kt
git commit -m "feat: register snippet actions at startup and keep them live on VFS changes"
```

---

### Task 14: Suppress analysis on snippet files

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/ide/SnippetHighlighting.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/ide/SnippetHighlightingTest.kt`

**Interfaces:**
- Consumes: `SnippetDirs` (Task 12).
- Produces: `class SnippetHighlightingSettingProvider : DefaultHighlightingSettingProvider`, plus
  `object SnippetFiles { fun isSnippet(project: Project, file: VirtualFile): Boolean }`.

Spec section 8. Snippets are fragments and are often broken on purpose, so red is
noise by definition. `SKIP_HIGHLIGHTING` rather than `SKIP_INSPECTION`: the latter
leaves the highlighting pass running, so an unclosed class stays red — which is
most snippets. Lexer-based colouring survives either way because it comes from the
editor rather than the daemon.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.ide

import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SnippetHighlightingTest : LightJavaCodeInsightFixtureTestCase() {

    fun testNonSnippetFileGetsNoOpinion() {
        val file = myFixture.configureByText("Ordinary.java", "class A {}").virtualFile
        assertNull(SnippetHighlightingSettingProvider().getDefaultSetting(project, file))
    }

    fun testSnippetFileSkipsHighlighting() {
        val dir = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Exception> {
            myFixture.tempDirFixture.findOrCreateDir("hl")
        }
        val file = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Exception> {
            dir.createChildData(this, "01.java").also { it.setBinaryContent("class A {".toByteArray()) }
        }
        assertEquals(
            FileHighlightingSetting.SKIP_HIGHLIGHTING,
            SnippetHighlightingSettingProvider().settingFor(file, listOf(dir)),
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*SnippetHighlightingTest*'`
Expected: FAIL — `Unresolved reference: SnippetHighlightingSettingProvider`.

- [ ] **Step 3: Write `SnippetHighlighting.kt`**

```kotlin
package com.github.asm0dey.typewriter.ide

import com.github.asm0dey.typewriter.library.SnippetDirs
import com.intellij.codeInsight.daemon.impl.analysis.DefaultHighlightingSettingProvider
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object SnippetFiles {
    fun isUnder(file: VirtualFile, roots: List<VirtualFile>): Boolean =
        roots.any { VfsUtilCore.isAncestor(it, file, false) }

    fun roots(project: Project): List<VirtualFile> =
        listOfNotNull(SnippetDirs.global(), SnippetDirs.project(project))
}

class SnippetHighlightingSettingProvider : DefaultHighlightingSettingProvider() {

    override fun getDefaultSetting(project: Project, file: VirtualFile): FileHighlightingSetting? =
        settingFor(file, SnippetFiles.roots(project))

    /** Separated so it can be tested without resolving the configured directories. */
    fun settingFor(file: VirtualFile, roots: List<VirtualFile>): FileHighlightingSetting? =
        if (SnippetFiles.isUnder(file, roots)) FileHighlightingSetting.SKIP_HIGHLIGHTING else null
}
```

- [ ] **Step 4: Register the provider in `plugin.xml`**

Inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<defaultHighlightingSettingProvider
    implementation="com.github.asm0dey.typewriter.ide.SnippetHighlightingSettingProvider"/>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*SnippetHighlightingTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/ide src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/asm0dey/typewriter/ide
git commit -m "feat: skip analysis on snippet files, which are fragments by design"
```

---

### Task 15: Completion inside markers

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/ide/MarkerCompletion.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/ide/MarkerCompletionTest.kt`

**Interfaces:**
- Consumes: `SnippetFiles` (Task 14), `Directives.NAMES` (Task 3).
- Produces: `class MarkerCompletionContributor : CompletionContributor`.

Turns pre-flight's "unknown action id" from a first-run error into something you
avoid while typing. Completion is served by `CompletionService`, not the daemon, so
Task 14's `SKIP_HIGHLIGHTING` does not suppress it — the first test below is what
proves that, so run it before assuming.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.ide

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MarkerCompletionTest : LightJavaCodeInsightFixtureTestCase() {

    private fun snippetFile(name: String, text: String): VirtualFile {
        val dir = WriteAction.compute<VirtualFile, Exception> {
            myFixture.tempDirFixture.findOrCreateDir("cmp")
        }
        return WriteAction.compute<VirtualFile, Exception> {
            (dir.findChild(name) ?: dir.createChildData(this, name)).also {
                it.setBinaryContent(text.toByteArray())
            }
        }
    }

    fun testCommandNamesAreOfferedAfterTheSentinel() {
        val names = MarkerCompletionContributor.completionsFor("tw: ", "tw:")
        assertTrue(names.containsAll(listOf("pause", "action", "raw", "speed", "jitter", "newline")))
    }

    fun testActionIdsAreOfferedAfterAction() {
        val ids = MarkerCompletionContributor.completionsFor("tw: action ", "tw:")
        assertTrue(ids.contains("ReformatCode"))
        assertTrue(ids.size > 100)
    }

    fun testNumericArgumentsGetNoCompletions() {
        assertTrue(MarkerCompletionContributor.completionsFor("tw: pause ", "tw:").isEmpty())
        assertTrue(MarkerCompletionContributor.completionsFor("tw: speed ", "tw:").isEmpty())
    }

    fun testNonMarkerCommentGetsNoCompletions() {
        assertTrue(MarkerCompletionContributor.completionsFor("just a note", "tw:").isEmpty())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*MarkerCompletionTest*'`
Expected: FAIL — `Unresolved reference: MarkerCompletionContributor`.

- [ ] **Step 3: Write `MarkerCompletion.kt`**

```kotlin
package com.github.asm0dey.typewriter.ide

import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.github.asm0dey.typewriter.ui.TypeWriterSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil

class MarkerCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        val virtualFile = file.virtualFile ?: return
        val project = file.project
        if (!SnippetFiles.isUnder(virtualFile, SnippetFiles.roots(project))) return

        val comment = PsiTreeUtil.getParentOfType(parameters.position, PsiComment::class.java, false) ?: return
        val sentinel = ApplicationManager.getApplication()
            .getService(TypeWriterSettings::class.java).state.sentinel

        // Everything the user has typed in this comment up to the caret, minus the
        // opening delimiter. The closing delimiter is irrelevant: it is never before
        // the caret in a comment being typed.
        val caretInComment = (parameters.offset - comment.textRange.startOffset)
            .coerceIn(0, comment.text.length)
        val syntax = CommentSyntax.of(file.language)
        val opening = listOfNotNull(syntax.blockPrefix, syntax.linePrefix)
            .firstOrNull { comment.text.startsWith(it) }
            ?: return
        val beforeCaret = comment.text.take(caretInComment).removePrefix(opening)

        for (name in completionsFor(beforeCaret.trimStart(), sentinel)) {
            result.addElement(LookupElementBuilder.create(name))
        }
    }

    companion object {
        /**
         * [body] is the comment's body up to the caret, already stripped of delimiters.
         * Returns the names to offer, or empty when the position takes a number or is
         * not a marker at all.
         */
        fun completionsFor(body: String, sentinel: String): List<String> {
            val trimmed = body.trimStart()
            if (!trimmed.startsWith(sentinel)) return emptyList()
            val rest = trimmed.removePrefix(sentinel).trimStart()
            val words = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val head = words.firstOrNull()

            return when {
                rest.isEmpty() || words.size == 1 && !rest.endsWith(" ") ->
                    listOf("pause", "action") + Directives.NAMES
                head == "action" -> ActionManager.getInstance().getActionIdList("")
                head in setOf("pause", "speed", "jitter", "newline") -> emptyList()
                else -> emptyList()
            }
        }
    }
}
```

- [ ] **Step 4: Register the contributor in `plugin.xml`**

Inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<completion.contributor language="any"
    implementationClass="com.github.asm0dey.typewriter.ide.MarkerCompletionContributor"/>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*MarkerCompletionTest*'`
Expected: PASS, 4 tests.

If `getActionIdList("")` returns an empty list in the test fixture, the platform
did not load the action registry; assert `ids.isNotEmpty()` instead of the size
bound and note it in the commit message.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/ide/MarkerCompletion.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/asm0dey/typewriter/ide/MarkerCompletionTest.kt
git commit -m "feat: complete command names and action ids inside markers"
```

---

### Task 16: New-snippet dialog with a file-type chooser

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/ui/NewSnippetDialog.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` — register the new-snippet action
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/ui/SnippetFileNameTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `object SnippetFileNames { fun choices(): List<FileType>; fun suggestName(fileType: FileType, stem: String): String }`
  - `class NewSnippetDialog(project: Project) : DialogWrapper` exposing `val fileName: String` and `val fileType: FileType`

Spec section 9. The chooser lists every registered non-binary `FileType`, so it
covers whatever languages the IDE has installed. It derives a **filename**, not just
an extension, because several useful snippet types are identified by exact name and
have no extension at all — `Dockerfile`, `Makefile`, `.gitignore`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.ui

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SnippetFileNameTest : LightJavaCodeInsightFixtureTestCase() {

    fun testChoicesExcludeBinaryTypes() {
        val choices = SnippetFileNames.choices()
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.none { it.isBinary })
        assertTrue(choices.contains(JavaFileType.INSTANCE))
    }

    fun testExtensionTypeGetsStemPlusExtension() {
        assertEquals("01-entity.java", SnippetFileNames.suggestName(JavaFileType.INSTANCE, "01-entity"))
    }

    fun testPlainTextFallsBackToTheDefaultExtension() {
        assertEquals("notes.txt", SnippetFileNames.suggestName(PlainTextFileType.INSTANCE, "notes"))
    }

    fun testExactNameTypeIgnoresTheStem() {
        val dockerfile = FileTypeManager.getInstance().getFileTypeByFileName("Dockerfile")
        // Only meaningful when the Docker plugin is present; skip loudly otherwise.
        if (dockerfile == PlainTextFileType.INSTANCE) {
            println("SKIPPED: Docker plugin absent, exact-name matching not exercised")
            return
        }
        assertEquals("Dockerfile", SnippetFileNames.suggestName(dockerfile, "ignored"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*SnippetFileNameTest*'`
Expected: FAIL — `Unresolved reference: SnippetFileNames`.

- [ ] **Step 3: Write `NewSnippetDialog.kt`**

```kotlin
package com.github.asm0dey.typewriter.ui

import com.intellij.openapi.fileTypes.ExactFileNameMatcher
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox

object SnippetFileNames {

    fun choices(): List<FileType> =
        FileTypeManager.getInstance().registeredFileTypes
            .filterNot { it.isBinary }
            .filter { it.defaultExtension.isNotEmpty() || exactNameOf(it) != null }
            .sortedBy { it.displayName }

    fun exactNameOf(fileType: FileType): String? =
        FileTypeManager.getInstance().getAssociations(fileType)
            .filterIsInstance<ExactFileNameMatcher>()
            .firstOrNull()
            ?.presentableString

    fun suggestName(fileType: FileType, stem: String): String {
        exactNameOf(fileType)?.let { return it }
        val extension = fileType.defaultExtension
        return if (extension.isEmpty()) stem else "$stem.$extension"
    }
}

class NewSnippetDialog(project: Project) : DialogWrapper(project) {

    private val stemField = JBTextField("01-snippet")
    private val typeCombo = JComboBox(DefaultComboBoxModel(SnippetFileNames.choices().toTypedArray()))

    init {
        title = "New TypeWriter Snippet"
        init()
    }

    val fileType: FileType get() = typeCombo.selectedItem as FileType
    val fileName: String get() = SnippetFileNames.suggestName(fileType, stemField.text.trim())

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", stemField)
            .addLabeledComponent("File type:", typeCombo)
            .panel as JPanel

    override fun doValidate(): ValidationInfo? =
        if (stemField.text.isBlank() && SnippetFileNames.exactNameOf(fileType) == null) {
            ValidationInfo("Name must not be empty", stemField)
        } else null
}
```

- [ ] **Step 4: Write the action that creates the file**

Append to `NewSnippetDialog.kt`:

```kotlin
class NewSnippetAction : com.intellij.openapi.actionSystem.AnAction(), com.intellij.openapi.project.DumbAware {

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val project = e.project ?: return
        val dialog = NewSnippetDialog(project)
        if (!dialog.showAndGet()) return

        val directory = com.github.asm0dey.typewriter.library.SnippetDirs.project(project)
            ?: com.github.asm0dey.typewriter.library.SnippetDirs.global()
            ?: run {
                com.github.asm0dey.typewriter.library.SnippetRunner.notify(
                    project,
                    "no snippet directory; set one in Settings > Tools > TypeWriter",
                    com.intellij.notification.NotificationType.ERROR,
                )
                return
            }

        val name = dialog.fileName
        if (directory.findChild(name) != null) {
            com.github.asm0dey.typewriter.library.SnippetRunner.notify(
                project, "$name already exists", com.intellij.notification.NotificationType.ERROR,
            )
            return
        }
        val created = com.intellij.openapi.application.WriteAction.compute<
            com.intellij.openapi.vfs.VirtualFile, Exception> {
            directory.createChildData(this, name)
        }
        com.github.asm0dey.typewriter.library.SnippetSync.syncAll()
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(created, true)
    }
}
```

Declare it in `plugin.xml` inside `<actions>`:

```xml
<action id="typewriter.newSnippet" class="com.github.asm0dey.typewriter.ui.NewSnippetAction"
        text="TypeWriter: New Snippet..."/>
```

The project directory wins when both exist, because a new snippet during talk prep
almost always belongs to the talk.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*SnippetFileNameTest*'`
Expected: PASS, 4 tests. The Dockerfile case prints `SKIPPED:` when the Docker
plugin is absent — that is the loud skip the spec requires, not a silent pass.

If `ExactFileNameMatcher.presentableString` does not exist on this platform build,
use `matcher.toString()`; `ExactFileNameMatcher` renders as the bare filename.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/ui/NewSnippetDialog.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/asm0dey/typewriter/ui/SnippetFileNameTest.kt
git commit -m "feat: create snippets from a dialog with a file-type chooser"
```

---

### Task 17: Snippet dialog and picker

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/ui/SnippetDialog.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/ui/SnippetPicker.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/ui/DirectiveHeaderTest.kt`

**Interfaces:**
- Consumes: `Directives` (Task 3), `SnippetLibrary`/`SnippetDirs` (Tasks 8, 12), `RunService` (Task 11).
- Produces:
  - `object DirectiveHeader { fun read(text: String, sentinel: String): Directives; fun write(text: String, directives: Directives, sentinel: String, syntax: CommentSyntax): String }`
  - `class SnippetDialog(project: Project, snippet: Snippet) : DialogWrapper`
  - `class TypeSnippetPickerAction : AnAction` — speed-search popup; "start sequence here"

The dialog's `EditorTextField` is built over the snippet file's **existing**
`Document`, so the dialog, any open tab, and the player all read one buffer. The
timing fields read and write the file's directive header, which is why
`DirectiveHeader.write` must round-trip.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.intellij.lang.java.JavaLanguage
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class DirectiveHeaderTest : LightJavaCodeInsightFixtureTestCase() {

    private val java = CommentSyntax.of(JavaLanguage.INSTANCE)

    fun testWriteAddsAHeaderWhenThereIsNone() {
        val out = DirectiveHeader.write("class A {}", Directives(speedMs = 80), "tw:", java)
        assertEquals("// tw: speed 80\nclass A {}", out)
    }

    fun testWriteReplacesAnExistingHeader() {
        val out = DirectiveHeader.write("// tw: speed 40\nclass A {}", Directives(speedMs = 80), "tw:", java)
        assertEquals("// tw: speed 80\nclass A {}", out)
    }

    fun testWriteRemovesTheHeaderWhenNothingIsSet() {
        val out = DirectiveHeader.write("// tw: speed 40\nclass A {}", Directives(), "tw:", java)
        assertEquals("class A {}", out)
    }

    fun testWriteKeepsACommandMarkerThatLeadsTheFile() {
        val src = "// tw: pause 500\nclass A {}"
        val out = DirectiveHeader.write(src, Directives(speedMs = 80), "tw:", java)
        assertEquals("// tw: speed 80\n// tw: pause 500\nclass A {}", out)
    }

    fun testRoundTrip() {
        val directives = Directives(raw = true, speedMs = 80, jitterMs = 25, newlineMs = 400)
        val text = DirectiveHeader.write("class A {}", directives, "tw:", java)
        assertEquals(directives, DirectiveHeader.read(text, "tw:"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*DirectiveHeaderTest*'`
Expected: FAIL — `Unresolved reference: DirectiveHeader`.

- [ ] **Step 3: Write `SnippetDialog.kt`**

```kotlin
package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.library.SnippetRunner
import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

object DirectiveHeader {

    fun read(text: String, sentinel: String): Directives {
        var result = Directives()
        for (line in text.lines()) {
            val body = line.trim().substringAfter(sentinel, missingDelimiterValue = "").trim()
            if (body.isEmpty() || !line.contains(sentinel)) break
            val tokens = body.split(Regex("\\s+"))
            var i = 0
            while (i < tokens.size) {
                when (tokens[i]) {
                    "raw" -> { result = result.copy(raw = true); i++ }
                    "speed" -> { result = result.copy(speedMs = tokens.getOrNull(i + 1)?.toIntOrNull()); i += 2 }
                    "jitter" -> { result = result.copy(jitterMs = tokens.getOrNull(i + 1)?.toIntOrNull()); i += 2 }
                    "newline" -> { result = result.copy(newlineMs = tokens.getOrNull(i + 1)?.toIntOrNull()); i += 2 }
                    else -> return result
                }
            }
        }
        return result
    }

    fun write(text: String, directives: Directives, sentinel: String, syntax: CommentSyntax): String {
        val body = buildList {
            if (directives.raw) add("raw")
            directives.speedMs?.let { add("speed $it") }
            directives.jitterMs?.let { add("jitter $it") }
            directives.newlineMs?.let { add("newline $it") }
        }.joinToString(" ")

        val lines = text.lines().toMutableList()
        // Drop only the leading directive lines; a leading command marker stays.
        while (lines.isNotEmpty() && isDirectiveLine(lines.first(), sentinel)) lines.removeAt(0)
        if (body.isEmpty()) return lines.joinToString("\n")

        val header = comment("$sentinel $body", syntax)
        return (listOf(header) + lines).joinToString("\n")
    }

    private fun isDirectiveLine(line: String, sentinel: String): Boolean {
        if (!line.contains(sentinel)) return false
        val body = line.substringAfter(sentinel).trim()
        val head = body.split(Regex("\\s+")).firstOrNull() ?: return false
        return head in Directives.NAMES
    }

    private fun comment(body: String, syntax: CommentSyntax): String = when {
        syntax.linePrefix != null -> "${syntax.linePrefix} $body"
        syntax.blockPrefix != null && syntax.blockSuffix != null ->
            "${syntax.blockPrefix} $body ${syntax.blockSuffix}"
        else -> body
    }
}

class SnippetDialog(private val project: Project, private val snippet: Snippet) : DialogWrapper(project) {

    private val document: Document = FileDocumentManager.getInstance().getDocument(snippet.file)!!
    private val editorField = EditorTextField(document, project, snippet.fileType, false, false).apply {
        preferredSize = Dimension(680, 360)
    }
    private val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)
    private val current = DirectiveHeader.read(document.text, settings.state.sentinel)

    private val speed = JSpinner(SpinnerNumberModel(current.speedMs ?: settings.state.speedMs, 0, 5000, 10))
    private val jitter = JSpinner(SpinnerNumberModel(current.jitterMs ?: settings.state.jitterMs, 0, 5000, 5))
    private val newline = JSpinner(SpinnerNumberModel(current.newlineMs ?: settings.state.newlineMs, 0, 5000, 50))
    private val raw = JBCheckBox("Type as authored (raw)", current.raw)

    private var playOnClose = false

    init {
        title = "Snippet: ${snippet.relativePath}"
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addComponent(editorField)
            .addLabeledComponent("Base delay (ms):", speed)
            .addLabeledComponent("Jitter (ms):", jitter)
            .addLabeledComponent("Newline pause (ms):", newline)
            .addComponent(raw)
            .addComponent(JBLabel("Hotkey: ${hotkeyText()} — change it in Settings > Keymap"))
            .panel

    private fun hotkeyText(): String {
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts(snippet.id)
        return if (shortcuts.isEmpty()) "unbound" else KeymapUtil.getShortcutText(shortcuts.first())
    }

    override fun createActions() = arrayOf(
        object : javax.swing.AbstractAction("Open in Editor") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                FileEditorManager.getInstance(project).openFile(snippet.file, true)
                close(CANCEL_EXIT_CODE)
            }
        },
        object : javax.swing.AbstractAction("Play") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                playOnClose = true
                doOKAction()
            }
        },
        okAction,
        cancelAction,
    )

    override fun doOKAction() {
        saveDirectives()
        super.doOKAction()
        if (playOnClose) {
            // A tick after disposal: dialog teardown routes actions through the action
            // system, and the abort watcher cancels on any action.
            ApplicationManager.getApplication().invokeLater {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                SnippetRunner.run(project, editor, snippet)
            }
        }
    }

    private fun saveDirectives() {
        val directives = Directives(
            raw = raw.isSelected,
            speedMs = speed.value as Int,
            jitterMs = jitter.value as Int,
            newlineMs = newline.value as Int,
        )
        val syntax = CommentSyntax.of(
            com.intellij.lang.LanguageUtil.getFileTypeLanguage(snippet.fileType)
                ?: com.intellij.lang.Language.ANY
        )
        val updated = DirectiveHeader.write(document.text, directives, settings.state.sentinel, syntax)
        if (updated != document.text) {
            WriteCommandAction.runWriteCommandAction(project) { document.setText(updated) }
        }
        FileDocumentManager.getInstance().saveDocument(document)
    }
}
```

- [ ] **Step 4: Write `SnippetPicker.kt`**

```kotlin
package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.library.SnippetDirs
import com.github.asm0dey.typewriter.library.SnippetLibrary
import com.github.asm0dey.typewriter.library.SnippetRunner
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.run.RunService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory

class TypeSnippetPickerAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val snippets = SnippetDirs.all(project)
        if (snippets.isEmpty()) {
            SnippetRunner.notify(project, "no snippets found", com.intellij.notification.NotificationType.WARNING)
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(snippets)
            .setTitle("Type Snippet")
            .setNamerForFiltering { it.relativePath }
            .setRenderer(object : com.intellij.ui.components.JBList.StripedListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>, value: Any?, index: Int,
                    selected: Boolean, focused: Boolean,
                ) = super.getListCellRendererComponent(
                    list, (value as? Snippet)?.relativePath ?: value, index, selected, focused,
                )
            })
            .setItemChosenCallback { snippet ->
                val sequence = SnippetLibrary.sequence(SnippetDirs.project(project))
                val position = sequence.indexOfFirst { it.relativePath == snippet.relativePath }
                if (position >= 0) project.getService(RunService::class.java).cursor = position
                SnippetRunner.run(project, e.getData(CommonDataKeys.EDITOR), snippet)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
```

- [ ] **Step 5: Write the action that opens the snippet dialog**

Append to `SnippetPicker.kt`:

```kotlin
/** Entry point for SnippetDialog: pick a snippet, then edit it and its timing. */
class EditSnippetAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val snippets = SnippetDirs.all(project)
        if (snippets.isEmpty()) {
            SnippetRunner.notify(project, "no snippets found", com.intellij.notification.NotificationType.WARNING)
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(snippets)
            .setTitle("Edit Snippet")
            .setNamerForFiltering { it.relativePath }
            .setItemChosenCallback { snippet -> SnippetDialog(project, snippet).show() }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
```

- [ ] **Step 6: Declare both actions in `plugin.xml`**

Inside `<actions>`:

```xml
<action id="typewriter.typeSnippet" class="com.github.asm0dey.typewriter.ui.TypeSnippetPickerAction"
        text="TypeWriter: Type Snippet..."/>
<action id="typewriter.editSnippet" class="com.github.asm0dey.typewriter.ui.EditSnippetAction"
        text="TypeWriter: Edit Snippet..."/>
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew test --tests '*DirectiveHeaderTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/ui src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/asm0dey/typewriter/ui/DirectiveHeaderTest.kt
git commit -m "feat: snippet dialog over the real document, plus a snippet picker"
```

**Manual check, cannot be covered headlessly** (spec section 15): run the IDE with
`./gradlew runIde`, open the snippet dialog, edit text, press OK, and confirm the
file on disk changed and no "unsaved document" state leaks. Repeat with Cancel and
confirm the edit is still in the shared `Document` — the dialog edits the real
buffer by design, so Cancel does not revert text, only the timing fields.

---

### Task 18: Golden acceptance tests

**Files:**
- Create: `src/test/kotlin/com/github/asm0dey/typewriter/golden/GoldenTest.kt`
- Create: `src/test/resources/golden/` fixtures
- Modify: `build.gradle.kts` — add `bundledPlugin("Docker")`

**Interfaces:**
- Consumes: everything from Tasks 2-6.
- Produces: nothing. This is the regression suite the spec's section 12 specifies.

- [ ] **Step 1: Add the Docker test dependency**

In `build.gradle.kts`, inside `intellijPlatform { }` of the `dependencies` block:

```kotlin
bundledPlugin("Docker")
```

- [ ] **Step 2: Write the acceptance tests**

```kotlin
package com.github.asm0dey.typewriter.golden

import com.github.asm0dey.typewriter.format.SnippetFormatter
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.parse.MarkerParser
import com.github.asm0dey.typewriter.parse.MarkerScanner
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class GoldenTest : LightJavaCodeInsightFixtureTestCase() {

    private fun typed(name: String, text: String, formatted: Boolean): String {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
        val source = if (formatted) SnippetFormatter.format(project, fileType, name, text).text else text
        val psi = myFixture.configureByText(name, source)
        val program = MarkerParser.parse(source, MarkerScanner.scan(psi, "tw:"), "tw:")
        return program.steps.filterIsInstance<Step.Type>().joinToString("") { it.text }
    }

    private val acceptanceOne = """
        public interface CourierRepository extends JpaRepository<Courier, Long> {

            List<Courier> findAllByCity(String city);

        // No need to define common CRUD methods manually 
        }

        @Service
        @Transactional(readOnly = true)
        public class CourierService {

            private final CourierRepository courierRepository;

            public CourierService(CourierRepository courierRepository) {
                this.courierRepository = courierRepository;
            }
    """.trimIndent()

    /**
     * Acceptance test 1. Both modes produce the SAME output: a column-0 comment
     * immediately before a closing brace is not re-indented, and trailing whitespace
     * is not a formatter concern. Its value is as the v1 regression guard.
     */
    fun testAcceptanceOneIsIdenticalInBothModes() {
        val formatted = typed("AT1.java", acceptanceOne, formatted = true)
        val raw = typed("AT1raw.java", acceptanceOne, formatted = false)
        assertEquals(acceptanceOne, raw)
        assertEquals(raw, formatted)
    }

    fun testAcceptanceOneNeverInventsAClosingBrace() {
        val out = typed("AT1b.java", acceptanceOne, formatted = true)
        assertEquals(
            acceptanceOne.count { it == '}' },
            out.count { it == '}' },
        )
    }

    fun testGenericsAreNotParsedAsCommands() {
        // The v1 crash: JpaRepository<Courier, Long> matched its <...> command syntax.
        val out = typed("AT1c.java", acceptanceOne, formatted = true)
        assertTrue(out.contains("JpaRepository<Courier, Long>"))
        assertTrue(out.contains("List<Courier>"))
    }

    /** Acceptance test 3: the fixture that DOES discriminate the two modes. */
    fun testAcceptanceThreeDiscriminatesTheModes() {
        val input = "class CourierService {\n" +
            "// explains the field\n" +
            "        private final CourierRepository repo;\n" +
            "    void reload() {\n" +
            "int n = repo.count();\n" +
            "    }\n" +
            "}"
        val expected = "class CourierService {\n" +
            "    // explains the field\n" +
            "    private final CourierRepository repo;\n" +
            "    void reload() {\n" +
            "        int n = repo.count();\n" +
            "    }\n" +
            "}"
        assertEquals(expected, typed("AT3.java", input, formatted = true))
        assertEquals(input, typed("AT3raw.java", input, formatted = false))
    }

    fun testAcceptanceThreeHasNoFormatterInsertedBlankLine() {
        val input = "class C {\n    int x;\n    void m() {\n    }\n}"
        assertFalse(typed("AT3b.java", input, formatted = true).contains("\n\n"))
    }

    /** Acceptance test 2: Dockerfile — line comments only, backslash continuations. */
    fun testDockerfile() {
        val name = "Dockerfile"
        if (FileTypeManager.getInstance().getFileTypeByFileName(name) == PlainTextFileType.INSTANCE) {
            fail("SKIPPED LOUDLY: Docker plugin absent, acceptance test 2 did not run")
        }
        val input = "# tw: pause 500\n" +
            "FROM eclipse-temurin:21-jre\n" +
            "\n" +
            "RUN apt-get update && \\\n" +
            "      apt-get install -y curl && \\\n" +
            "  rm -rf /var/lib/apt/lists/*\n" +
            "\n" +
            "# install the app\n" +
            "COPY   target/app.jar   /app/app.jar\n" +
            "\n" +
            "ENTRYPOINT [\"java\",\"-jar\",\"/app/app.jar\"]\n"

        val out = typed(name, input, formatted = true)
        assertTrue("the marker line is consumed entirely", out.startsWith("FROM"))
        assertTrue("an ordinary comment is typed", out.contains("# install the app"))
        assertEquals("every continuation survives", 2, out.split("\\\n").size - 1)
        assertTrue(out.contains("[\"java\",\"-jar\",\"/app/app.jar\"]"))
        assertEquals(input.replace("# tw: pause 500\n", ""), typed("Dockerfile", input, formatted = false))
    }

    fun testCrlfAuthoredSnippetTypesAsLf() {
        val crlf = "class A {\r\n    int x;\r\n}"
        val out = typed("CRLF.java", crlf, formatted = false)
        assertFalse("Document normalises line separators", out.contains("\r"))
    }
}
```

- [ ] **Step 3: Run the tests**

Run: `./gradlew test --tests '*GoldenTest*'`
Expected: PASS, 7 tests.

`testDockerfile` calls `fail("SKIPPED LOUDLY: ...")` when the Docker plugin is
missing, per the spec's requirement that an unavailable language must never look
green. If it fires, the `bundledPlugin("Docker")` line from Step 1 is missing.

The Dockerfile format-on expectations here are **invariants, not a byte-exact
golden file**. Once the test passes, print the formatted output, read it, and
commit it as `src/test/resources/golden/Dockerfile.formatted` with an
`assertEquals` against the file — generated and reviewed, never predicted.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/test/kotlin/com/github/asm0dey/typewriter/golden src/test/resources/golden
git commit -m "test: golden acceptance suite across Java, Dockerfile and CRLF"
```

---

### Task 19: Settings UI, docs and release metadata

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/ui/TypeWriterConfigurable.kt`
- Create: `README.md`; rewrite `CHANGELOG.md` — a placeholder was added in Task 1, because the `org.jetbrains.changelog` plugin auto-wires `changeNotes` from it and the build fails configuration-cache serialization without one
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: `TypeWriterSettings` (Task 9).
- Produces: `class TypeWriterConfigurable : Configurable` registered at
  `Settings | Tools | TypeWriter`.

- [ ] **Step 1: Write `TypeWriterConfigurable.kt`**

```kotlin
package com.github.asm0dey.typewriter.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class TypeWriterConfigurable : Configurable {

    private val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)

    private val globalDir = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Snippet Directory", null, null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )
    }
    private val speed = JSpinner(SpinnerNumberModel(100, 0, 5000, 10))
    private val jitter = JSpinner(SpinnerNumberModel(20, 0, 5000, 5))
    private val newline = JSpinner(SpinnerNumberModel(300, 0, 5000, 50))
    private val sentinel = JBTextField()
    private val formatOnPlay = JBCheckBox("Format snippets before typing")

    override fun getDisplayName() = "TypeWriter"

    override fun createComponent(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Snippet directory:", globalDir)
            .addLabeledComponent("Base delay (ms):", speed)
            .addLabeledComponent("Jitter (ms):", jitter)
            .addLabeledComponent("Newline pause (ms):", newline)
            .addLabeledComponent("Marker sentinel:", sentinel)
            .addComponent(formatOnPlay)
            .panel

    override fun isModified(): Boolean = with(settings.state) {
        globalDir.text != this.globalDir ||
            speed.value != speedMs ||
            jitter.value != jitterMs ||
            newline.value != newlineMs ||
            sentinel.text != this.sentinel ||
            formatOnPlay.isSelected != this.formatOnPlay
    }

    override fun apply() {
        settings.loadState(
            TypeWriterSettings.State(
                globalDir = globalDir.text,
                speedMs = speed.value as Int,
                jitterMs = jitter.value as Int,
                newlineMs = newline.value as Int,
                sentinel = sentinel.text,
                formatOnPlay = formatOnPlay.isSelected,
            )
        )
    }

    override fun reset() = with(settings.state) {
        this@TypeWriterConfigurable.globalDir.text = this.globalDir
        speed.value = speedMs
        jitter.value = jitterMs
        newline.value = newlineMs
        this@TypeWriterConfigurable.sentinel.text = this.sentinel
        this@TypeWriterConfigurable.formatOnPlay.isSelected = this.formatOnPlay
    }
}
```

- [ ] **Step 2: Register the configurable in `plugin.xml`**

Inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<applicationConfigurable parentId="tools"
                         instance="com.github.asm0dey.typewriter.ui.TypeWriterConfigurable"
                         id="typewriter.settings"
                         displayName="TypeWriter"/>
```

- [ ] **Step 3: Write the plugin description into `plugin.xml`**

Add a `<description>` element:

```xml
<description><![CDATA[
<p>Types predefined snippets into the editor the way a person would, for live demos and screencasts.</p>
<ul>
  <li>A snippet is a file. Edit it in a real editor tab, with real highlighting.</li>
  <li>Output is exactly what the file says, in any language the IDE supports.</li>
  <li>Markers are comments starting with <code>tw:</code>, so snippet files stay valid:
      <code>// tw: pause 800</code>, <code>// tw: action ReformatCode</code>.</li>
  <li>One action per snippet, bound in Settings &gt; Keymap like any other action.</li>
  <li><b>Type Next</b> walks a talk from a single binding.</li>
</ul>
<p><b>Upgrading from 0.x:</b> snippets are no longer stored in plugin settings and are not migrated.
Recreate them as files in your snippet directory.</p>
]]></description>
```

- [ ] **Step 4: Write `CHANGELOG.md`**

```markdown
# TypeWriter Changelog

## 1.0.0

Complete rewrite.

### Breaking

- Snippets are files in a snippet directory, not entries in plugin settings.
  Snippets stored by 0.x are **not migrated** and must be recreated.
- The 0.x typing dialog is gone. Create a snippet and press Play.
- Command syntax changed from `<pause:1000>` to a comment carrying `tw: pause 1000`,
  so snippet files stay valid and code like `JpaRepository<Courier, Long>` is no
  longer mistaken for a command.

### Added

- Output is correct in every language the IDE supports; the typing engine has no
  language-specific code.
- Snippets are formatted with the target project's code style before typing;
  `tw: raw` opts out.
- `tw: action <ActionId>` runs any IDE action mid-snippet.
- `TypeWriter: Type Next` walks a talk's snippets in order from one binding.
- `TypeWriter: Undo Run` removes exactly what a run typed.
- Completion for command names and action ids inside markers.
- Any keystroke, action or mouse click aborts a run.

### Fixed

- Hotkeys are ordinary IDE actions bound in Settings > Keymap. The plugin no
  longer writes to your keymap, and an action id no longer changes when you
  change a shortcut.
```

- [ ] **Step 5: Write `README.md`**

```markdown
# TypeWriter

Types predefined snippets into a JetBrains IDE the way a person would, for live
demos and screencasts.

## Quick start

1. `Settings > Tools > TypeWriter` — set your snippet directory (default `~/.typewriter`).
2. `TypeWriter: Type Snippet...` > `New snippet` — name it and pick a file type.
3. Edit it like any file. It is a file.
4. `Settings > Keymap > Plugins > TypeWriter` — bind it.

## Markers

A comment whose body starts with `tw:` is a marker. It never appears in the output.

    // tw: pause 800
    val user = repo.fin/* tw: action CodeCompletion */ding()
    val x = compute()   // tw: pause 500

One marker can carry several commands, one per line:

    <!--
    tw: pause 500
    tw: action ReformatCode
    -->

In languages without block comments, one per line:

    # tw: raw
    # tw: speed 80

| Command | Meaning |
|---|---|
| `pause <ms>` | wait |
| `action <ActionId>` | run any IDE action |

| Directive | Meaning |
|---|---|
| `raw` | do not format this snippet |
| `speed <ms>` | base delay per character |
| `jitter <ms>` | random variation |
| `newline <ms>` | extra pause after a newline |

Write `tw::` to type a literal `tw:`.

## Formatting

By default a snippet is formatted with the **target project's** code style before
typing, so a sloppy file produces clean output. The format step fixes indentation
and spacing; it never changes the number of lines or their order. `tw: raw` opts
out, which is how you type ugly code and clean it up on camera.

## Driving a talk

Name your project snippets `01-`, `02-` and bind `TypeWriter: Type Next` once.
Project snippets (`<project>/.typewriter`) are the talk; global snippets
(`~/.typewriter`) are your reusable toolkit and are not part of the sequence.
```

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew test`
Expected: all tests pass.

Run: `./gradlew verifyPlugin`
Expected: no compatibility errors against 252.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/asm0dey/typewriter/ui/TypeWriterConfigurable.kt src/main/resources/META-INF/plugin.xml README.md CHANGELOG.md
git commit -m "feat: settings UI, plugin description, README and changelog"
```

**Manual validation before release** (cannot run headlessly):

1. `./gradlew runIde`, create two snippets, bind one in Settings > Keymap.
2. Restart the sandbox IDE. Press the binding. It must still work — this is the v1
   regression that motivated the rewrite.
3. Rename a snippet file and confirm the old binding is gone and a new action
   appears without restarting.
4. Type a long snippet and press Escape mid-run; confirm it stops and
   `TypeWriter: Undo Run` removes exactly what was typed.
5. Toggle auto-close brackets in Settings and confirm output is unchanged.

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

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties src
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
        assertEquals("pause 800", markers[0].body.trim())
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

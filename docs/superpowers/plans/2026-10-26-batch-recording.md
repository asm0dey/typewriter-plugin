# Batch Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record many snippets to videos in one go: pick them by glob and checkboxes, get one video per snippet in a folder, each typed into an empty file with a class-name-like name.

**Architecture:** Pure naming/matching functions (`BatchNaming.kt`); `SnippetRunner.prepare` gains a message reporter so the batch can collect messages instead of notifying; `RecordingStage` gains an empty-file mode; a sequential `recordBatch` loop reuses `RecordingStage` + `record` + `runRecording` per snippet; a new dialog and action drive it.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, kotlinx.coroutines, ffmpeg (external), JUnit 5 fixture tests (`TypeWriterFixtureTestCase`).

**Spec:** `docs/superpowers/specs/2026-10-26-batch-recording-design.md` (builds on `docs/superpowers/specs/2026-10-05-record-video-design.md`).

## Global Constraints

- Single recording (`Record Snippet to Video...`, the snippet dialog's **Record Video...**) behaves exactly as today.
- Snippets record one after another, never in parallel.
- One failed or blocked snippet never stops the batch; one notification at the end, never one per snippet.
- Never silently overwrite a video: existing targets are asked about once, before rendering.
- The speaker's real files and editor are never touched.
- Tests follow the existing style: JUnit 5 `Assertions`, `TypeWriterFixtureTestCase`, no new test-only production interfaces (`FrameSink` already exists and is the seam).
- Run tests with `./gradlew test --tests '<class>'`; the full gate is `./gradlew check`.

## Review Focus

1. Glob with regex metacharacters (`*v1.2*`, `*(draft)*`, `*[wip]*`) must match literally, not as regex. → Task 1 test.
2. Non-ASCII and dotted names: `über-demo.kt` → `ÜberDemo.kt`, `01.intro.kt` → `Intro.kt`. → Task 1 test.
3. Output subdirectory that does not exist yet (`<folder>/java/`) must be created before ffmpeg writes its `.part` file. → Task 3 test.
4. ffmpeg failing on one snippet (non-zero exit) while the others succeed: the rest still record, the failure carries the stderr tail. → Task 3 test.
5. A snippet with zero typed text (only a `pause`) still produces a video of the holds, not a failure. → Task 3 test.

---

### Task 1: Naming, matching and target paths

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/BatchNaming.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/BatchNamingTest.kt` (plain JUnit 5, no fixture)

**Interfaces:**
- Produces:
  - `fun cleanClassName(fileName: String): String` — `"03-user-service.kt"` → `"UserService.kt"`.
  - `fun globMatches(pattern: String, relativePath: String): Boolean`
  - `fun batchTargets(relativePaths: List<String>, folder: Path, format: VideoFormat): Map<String, Path>` — keyed by relative path, insertion order = input order.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `strips numbers and pascal-cases`() {
    assertEquals("UserService.kt", cleanClassName("03-user-service.kt"))
    assertEquals("Hello.java", cleanClassName("01_hello.java"))
    assertEquals("Intro.kt", cleanClassName("01.intro.kt"))
    assertEquals("MyHTTPClient.java", cleanClassName("02 myHTTPClient.java"))
    assertEquals("ÜberDemo.kt", cleanClassName("über-demo.kt"))
    assertEquals("Snippet.java", cleanClassName("01.java"))
    assertEquals("Query.sql", cleanClassName("query.sql"))
    assertEquals("Readme", cleanClassName("readme"))           // no extension: none added
}

@Test fun `glob is case-insensitive, star crosses slash, metacharacters are literal`() {
    assertTrue(globMatches("*cyberjar*", "talk/CyberJar-01.kt"))
    assertTrue(globMatches("0?-*", "01-a.kt"))
    assertFalse(globMatches("0?-*", "talk/01-a.kt"))
    assertTrue(globMatches("*v1.2*", "demo-v1.2.kt"))
    assertFalse(globMatches("*v1.2*", "demo-v1x2.kt"))
    assertTrue(globMatches("*(draft)*", "a(draft).kt"))
    assertTrue(globMatches("*[wip]*", "a[wip].kt"))
    assertFalse(globMatches("", "a.kt"))
}

@Test fun `targets mirror subdirectories and disambiguate clashes`() {
    val dir = Path.of("/out")
    assertEquals(
        mapOf(
            "java/01-hello.java" to dir.resolve("java/01-hello.java.mp4"),
            "java/01-hello.kt" to dir.resolve("java/01-hello.kt.mp4"),
            "Hello.kt" to dir.resolve("Hello.kt.mp4"),
            "hello.kt" to dir.resolve("hello.kt.mp4"),
            "02-other.sql" to dir.resolve("02-other.mp4"),
        ),
        batchTargets(listOf("java/01-hello.java", "java/01-hello.kt", "Hello.kt", "hello.kt", "02-other.sql"), dir, VideoFormat.MP4),
    )
    assertEquals(dir.resolve("a.gif"), batchTargets(listOf("a.kt"), dir, VideoFormat.GIF)["a.kt"])
}
```
- [ ] **Step 2: Run** `./gradlew test --tests 'com.github.asm0dey.typewriter.record.BatchNamingTest'` — expected: compilation failure (functions undefined).

- [ ] **Step 3: Implement the three functions in `BatchNaming.kt`**

- `cleanClassName`: extension = text after the last `.` (none if no `.`); stem = the rest. Drop leading chars that are digits or `-_. `; split the remainder on runs of non-`Char.isLetterOrDigit`; uppercase each part's first char, keep the rest as is; join. Empty → `"Snippet"`.
- `globMatches`: empty pattern → false. Build a regex by `Regex.escape`-ing each literal run, `*` → `.*`, `?` → `[^/]`; match the whole path with `RegexOption.IGNORE_CASE`.
- `batchTargets`: short name = relative path minus its extension, plus `.${format extension}`. Group by short name `lowercase()`; groups of size > 1 use the full relative path (extension kept) plus the video extension instead. Resolve against `folder`. `VideoFormat` lives in `FfmpegCommand.kt`; add an `extension` property there if it has none.

- [ ] **Step 4: Run the same command** — expected: PASS.

- [ ] **Step 5: Commit** — `feat: batch recording names, glob and target paths`

---

### Task 2: Prepare against an empty offscreen file, collecting messages

**Files:**
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/library/SnippetActions.kt` (`SnippetRunner.prepare`, ~line 129)
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordingStage.kt` (constructor)
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordingService.kt` (call site, only if the constructor change requires it)
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/RecordingStageTest.kt`, `src/test/kotlin/com/github/asm0dey/typewriter/library/SnippetRunnerTest.kt`

**Interfaces:**
- Consumes: `cleanClassName` (Task 1).
- Produces:
  - `SnippetRunner.prepare(project: Project, editor: Editor?, snippet: Snippet, report: (String, NotificationType) -> Unit = { m, t -> notify(project, m, t) }): Prepared?` — every `notify` inside `prepare` goes through `report`. Existing callers unchanged.
  - `fun emptyStage(project: Project, snippet: Snippet, width: Int, height: Int, fontSize: Int, parent: Disposable): RecordingStage` in `RecordingStage.kt` — an empty `LightVirtualFile` named `cleanClassName(snippet.file.name)` with `snippet.fileType`, caret 0, editor soft-wrap/whitespace settings left at their defaults. The tab shows that name and the file type's icon.
  - The existing `RecordingStage(project, source: Editor, …)` keeps its behaviour. Split the constructor however is cleanest (e.g. primary constructor takes a `LightVirtualFile` + caret + soft-wrap/whitespace flags; the editor-copying path becomes a factory or secondary constructor). Update `RecordingService` and tests to whatever shape results.

- [ ] **Step 1: Write the failing tests**

In `RecordingStageTest`:
```kotlin
@Test fun `empty stage types into a fresh file named like a class`() {
    // snippet "03-user-service.java" in a temp snippet dir (reuse the test's snippet helper pattern)
    val stage = emptyStage(project, snippet, 1280, 720, 14, disposable)
    assertEquals("", stage.editor.document.text)
    assertEquals(0, stage.editor.caretModel.offset)
    assertEquals("UserService.java", FileDocumentManager.getInstance().getFile(stage.editor.document)!!.name)
    assertEquals(JavaFileType.INSTANCE, FileDocumentManager.getInstance().getFile(stage.editor.document)!!.fileType)
}
```

In `SnippetRunnerTest`:
```kotlin
@Test fun `prepare reports through the given reporter and passes an empty stage`() {
    // snippet "01-a.java" with text "class A {}"
    val messages = mutableListOf<Pair<String, NotificationType>>()
    val stage = emptyStage(project, snippet, 1280, 720, 14, disposable)
    val prepared = SnippetRunner.prepare(project, stage.editor, snippet) { m, t -> messages += m to t }
    assertNotNull(prepared)
    assertEquals(stage.editor, prepared!!.editor)
    // a null editor is blocked, and the block comes through the reporter, not a notification
    assertNull(SnippetRunner.prepare(project, null, snippet) { m, t -> messages += m to t })
    assertTrue(messages.any { it.second == NotificationType.ERROR && "no editor" in it.first })
}
```

- [ ] **Step 2: Run** `./gradlew test --tests '*RecordingStageTest' --tests '*SnippetRunnerTest'` — expected: compilation failure.

- [ ] **Step 3: Implement** the `report` parameter and `emptyStage` as specified in Interfaces.

- [ ] **Step 4: Run** the same command plus `--tests '*RecorderTest'` — expected: all PASS (single recording unaffected).

- [ ] **Step 5: Commit** — `feat: prepare a snippet into an empty offscreen file`

---

### Task 3: The batch loop and its summary

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/BatchRecorder.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/BatchRecorderTest.kt` (fixture test, not `@RunInEdt`, same EDT pattern as `RecorderTest`)

**Interfaces:**
- Consumes: `emptyStage`, `SnippetRunner.prepare(…, report)` (Task 2); `record`, `runRecording`, `recordingWarnings`, `RecordOptions`, `FrameSink`.
- Produces:
  ```kotlin
  data class BatchItem(val snippet: Snippet, val output: Path)
  sealed interface BatchOutcome {
      val item: BatchItem; val warnings: List<String>
      data class Recorded(override val item: BatchItem, override val warnings: List<String>) : BatchOutcome
      data class Failed(override val item: BatchItem, val reason: String, override val warnings: List<String>) : BatchOutcome
  }
  suspend fun recordBatch(
      project: Project, items: List<BatchItem>, options: RecordOptions,   // options.output ignored
      sinkFor: (RecordOptions) -> FrameSink,
      onStart: (index: Int, item: BatchItem) -> Unit = { _, _ -> },
      progress: (Double) -> Unit = {},                                    // whole batch, 0..1, equal share per item
      onOutcome: (BatchOutcome) -> Unit,
  )
  fun batchSummary(outcomes: List<BatchOutcome>, total: Int, cancelled: Boolean): String
  ```
- Behaviour of `recordBatch`, per item, in order:
  1. `Files.createDirectories(output.parent)`.
  2. On `Dispatchers.EDT`: new `Disposable`, `emptyStage(...)`, `SnippetRunner.prepare(project, stage.editor, snippet, report)`; `report` collects WARNING texts into `warnings` and ERROR texts into the failure reason. `null` → `Failed`, next item.
  3. Add `recordingWarnings(snippet.relativePath, prepared.steps)` messages to `warnings`.
  4. `runRecording(sink) { record(project, stage, prepared.steps, prepared.timing, options.copy(output = item.output), sink, …) }` with `sink = sinkFor(options.copy(output = item.output))`; non-null result → `Failed(reason = it)`, else `Recorded`. Dispose the stage on every path.
  5. `CancellationException` propagates (current sink aborted by `runRecording`); items already reported stay reported.
- `batchSummary` exact strings:
  - all recorded, no warnings: `"Recorded 12 videos"`
  - all recorded, k with warnings: `"Recorded 12 videos, 2 with warnings"`
  - some failed: `"Recorded 10 of 12 videos"`
  - cancelled: `"Cancelled after 3 of 12 videos"` (3 = recorded count)

- [ ] **Step 1: Write the failing tests**

Use an in-memory sink like `RecorderTest.ListSink`, plus a `FailingSink` whose `finish()` throws `FfmpegFailed("boom")`. Snippets in a temp project snippet dir.

```kotlin
@Test fun `records every snippet into an empty file of the cleaned name`()
    // items "01-a.java" ("class A {}"), "02-b.java" ("class B {}"); output dir under a temp dir with a missing "java/" subdir
    // assert: two Recorded outcomes in order; each sink finished; the stage text at finish == formatted snippet text
    //         (capture via a sink whose finish() reads the stage document, as RecorderTest's onFrame does);
    //         output parent directories exist
@Test fun `a blocked snippet fails and the rest still record`()
    // first snippet's VirtualFile has no readable text (or another prepare-blocking condition used in SnippetRunnerTest)
    // assert: outcomes == [Failed(reason contains the error), Recorded]
@Test fun `ffmpeg failure on one snippet does not stop the batch`()
    // sinkFor returns FailingSink for the first item
    // assert: Failed(reason contains "boom"), then Recorded
@Test fun `a pause-only snippet still records the holds`()
    // snippet text "// tw: pause 100" ; holdBefore 100, holdAfter 100, fps 10
    // assert: Recorded, sink got > 0 frames
@Test fun `cancel keeps finished outcomes and aborts the current sink`()
    // cancel the job from onStart(index = 1)
    // assert: onOutcome saw exactly 1 Recorded; second sink.abort() called; CancellationException thrown
@Test fun `summary strings`() {
    assertEquals("Recorded 2 videos", batchSummary(listOf(rec(), rec()), 2, false))
    assertEquals("Recorded 2 videos, 1 with warnings", batchSummary(listOf(rec(listOf("w")), rec()), 2, false))
    assertEquals("Recorded 1 of 2 videos", batchSummary(listOf(rec(), failed()), 2, false))
    assertEquals("Cancelled after 1 of 3 videos", batchSummary(listOf(rec()), 3, true))
}
```

- [ ] **Step 2: Run** `./gradlew test --tests '*BatchRecorderTest'` — expected: compilation failure.

- [ ] **Step 3: Implement** `BatchRecorder.kt` per the Interfaces block.

- [ ] **Step 4: Run** the same command — expected: PASS.

- [ ] **Step 5: Commit** — `feat: sequential batch recording loop`

---

### Task 4: Dialog, action, settings and end notification

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/BatchRecordDialog.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordSnippetsAction.kt`
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordDialog.kt` (extract shared fields)
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordingService.kt` (add `startBatch`)
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/ui/TypeWriterSettings.kt` (new fields)
- Modify: `src/main/resources/META-INF/plugin.xml` (register action, add to `typewriter.menu` after `typewriter.recordSnippet`)
- Modify: `README.md` (`## Recording a video` section: a short "Recording several snippets" subsection)

**Interfaces:**
- Consumes: `globMatches`, `batchTargets` (Task 1); `BatchItem`, `recordBatch`, `batchSummary`, `BatchOutcome` (Task 3); `locateFfmpeg`, `FfmpegSink`, `defaultVideoDir`, `defaultFontSize`, `html`.
- Produces:
  - Settings: `recordBatchPattern: String = ""`, `recordBatchDir: String = ""`, `recordBatchFormat: String = "mp4"`. Size/font/FPS/holds reuse the existing `record*` fields.
  - `RecordSettingsFields` (in `RecordDialog.kt`): the size preset combo, width, height, font size, FPS, holds, their validation (`sizeError`), and `fun applyTo(…)`/`options(output)`-style accessors — used by both `RecordDialog` (unchanged behaviour) and `BatchRecordDialog`.
  - `RecordSnippetsAction : AnAction(), DumbAware` — id `typewriter.recordSnippets`, text `Record Snippets to Videos...`, BGT update thread; calls `project.getService(RecordingService::class.java).startBatch()`. Needs no editor.
  - `RecordingService.startBatch()` (EDT): ffmpeg check exactly as `start`; no snippets → the existing `"no snippets found"` warning; show dialog; save settings; run.

- [ ] **Step 1: Dialog** — `BatchRecordDialog(project, snippets: List<Snippet>, initial…)`:
  - Pattern field; editing it (document listener) re-ticks exactly `globMatches(pattern, relativePath)` rows, discarding manual changes.
  - `CheckBoxList<Snippet>` rows: `"<relativePath>  →  <cleanClassName(file.name)>"`; manual ticking allowed.
  - "N selected" label; OK disabled (`isOKActionEnabled`) when N = 0.
  - Output folder: `TextFieldWithBrowseButton` with a folder chooser; default `recordBatchDir` else `defaultVideoDir()`.
  - Format combo `MP4` / `GIF`.
  - `RecordSettingsFields`.
  - `doOKAction`: compute `batchTargets`; collect existing ones; if any, show one `Messages.showDialog` listing them (cap the visible list at 20 lines + "and N more") with buttons **Overwrite** / **Choose Another Folder** / **Cancel**. Overwrite → close OK; Choose Another Folder → open the folder chooser, stay open; Cancel → stay open.
  - Exposes `items(): List<BatchItem>`, `options(): RecordOptions` (output = folder, ignored), `format`, `pattern`.

- [ ] **Step 2: Run & notify** in `startBatch`, mirroring `start`'s structure:
  - `withBackgroundProgress(project, "Recording snippets", cancellable = true)`; per item `onStart` sets the text to `"Recording ${i + 1} of $m: ${relativePath}"` (via `reportSequentialProgress`/`reportProgress` as `start` does — plugin verifier forbids the internal raw-progress API, see commit 34d45ee); progress forwarded as whole percents, only ever increasing.
  - `sinkFor = { FfmpegSink(status.path, it) }`.
  - Collect outcomes; on completion or `CancellationException` (notify under `NonCancellable`, then rethrow) one notification with `batchSummary`:
    - type INFORMATION when nothing failed, WARNING otherwise;
    - **Show in Folder** → `RevealFileAction.openFile(folder)`;
    - **Show Details** when any outcome failed or has warnings → `Messages.showInfoMessage` (or a scrollable `DialogBuilder` text area) listing `relativePath: reason` for failures, then `relativePath: warning` lines.

- [ ] **Step 3: Register** the action in `plugin.xml` and the menu group; add the README subsection (entry point, pattern + checkboxes, folder naming incl. clash rule, cleaned file name, overwrite prompt, end notification).

- [ ] **Step 4: Verify build** — `./gradlew check` (includes the plugin verifier run if configured) — expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Manual check** — `./gradlew runIde`, project with ≥3 snippets incl. one in a subdirectory and one `pause`-only:
  1. `Tools > TypeWriter > Record Snippets to Videos...` with pattern `*` → all ticked; untick one; OK.
  2. Videos appear with the expected names; each video's tab shows the cleaned name; no editor needed open.
  3. Run again into the same folder → one overwrite prompt; **Choose Another Folder** works.
  4. Cancel mid-batch → "Cancelled after N of M videos", finished files remain, no `.part` left.

- [ ] **Step 6: Commit** — `feat: record several snippets to videos in one batch`

# Record Snippet to Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render a snippet's typing into an MP4 or GIF that shows the tab, gutter and editor, from an offscreen copy of the current editor, encoded by an external ffmpeg.

**Architecture:** `Player` gains a pluggable `sleep`. A `Recorder` drives it with a virtual-clock sleep that paints an offscreen `RecordingStage` (real `JBTabs` + `EditorEx` over a `LightVirtualFile` copy) once per sleep and emits one frame per 1/fps boundary crossed into a `FrameSink`. The production sink pipes raw BGR frames to an `ffmpeg` process.

**Tech Stack:** Kotlin, IntelliJ Platform 2025.2 (`intellijIdea("2025.2.6.2")`, `sinceBuild = 252`), kotlinx.coroutines, JUnit 5 fixture tests, external ffmpeg/ffprobe.

**Spec:** `docs/superpowers/specs/2026-10-05-record-video-design.md` (also `docs/adr/0001-offscreen-recording.md`, and **Recording** in `CONTEXT.md`).

## Global Constraints

- New code lives in package `com.github.asm0dey.typewriter.record` (`src/main/kotlin/com/github/asm0dey/typewriter/record/`), tests mirror it under `src/test/kotlin/...`.
- No new Gradle dependency. ffmpeg is an external executable only.
- Tests: JUnit 5, `org.junit.jupiter.api.Assertions`, fixture tests extend `TypeWriterFixtureTestCase` with `@RunInEdt(writeIntent = true)` unless they `runBlocking` into `Dispatchers.EDT` (then no annotation — see `RunServiceTest`). Tests run with `java.awt.headless=true`.
- A recording is not a run: never touch `RunService` state or install `AbortWatcher` for it.
- Live runs must behave byte-for-byte as today.
- Defaults (verbatim from spec): size `1920×1080` (also `1280×720`, custom), FPS `60`, hold before `1 s`, hold after `2 s`, ffmpeg path `ffmpeg`.
- MP4 args: `-c:v libx264 -pix_fmt yuv420p -crf 18 -preset medium -movflags +faststart`. GIF args: `-vf split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:diff_mode=rectangle`. Input: `-f rawvideo -pix_fmt bgr24 -s WxH -r FPS -i -`.
- Custom width and height must be even.
- Notifications go through `SnippetRunner.notify` (group `TypeWriter`).
- No default keyboard shortcut for the new action.
- Every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Run the whole suite with `./gradlew check`; a single class with `./gradlew test --tests '<FQN>'`.

## Review Focus

1. **Applying TypeWriter Settings must not reset the recording fields.** `TypeWriterConfigurable.apply()` builds a fresh `State(...)` today; new fields would silently revert to defaults. Pinned in Task 5.
2. **Output directory missing or path containing spaces.** Users pick odd folders; ffmpeg must get the path as one argument and a missing directory must produce an error notification, not a hang or a stray `.part` file. Pinned in Task 10.
3. **Cancel mid-recording.** Must kill ffmpeg and delete the `.part` file. Pinned in Task 10.
4. **Unsaved edits in the target file.** The recording must start from the editor's document text, not the file on disk. Pinned in Task 9.
5. **Nothing to encode** (empty snippet, both holds 0). Must end in an error notification carrying ffmpeg's message, never a hang. Pinned in Task 10.

---

### Task 1: Pluggable `sleep` in `Player`

**Files:**
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/run/Player.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/run/PlayerTest.kt`

**Interfaces:**
- Produces: `class Player(project: Project, editor: Editor, runId: Any, private val sleep: suspend (Duration) -> Unit = { delay(it) })`. Both `delay(...)` calls in `play` (the `Step.Pause` branch and the per-chunk delay) go through `sleep`.

- [ ] **Step 1: Write the failing test** `testCustomSleepReceivesEveryDelay`

```kotlin
val slept = mutableListOf<Duration>()
fixture.configureByText("P.java", "")
runBlocking {
    Player(fixture.project, fixture.editor, Any(), sleep = { slept += it })
        .play(listOf(Step.Type("a\nb"), Step.Pause(250)), Timing(100, 0, 300))
}
assertEquals(listOf(100.milliseconds, 400.milliseconds, 100.milliseconds, 250.milliseconds), slept)
assertEquals("a\nb", fixture.editor.document.text)
```

- [ ] **Step 2:** `./gradlew test --tests 'com.github.asm0dey.typewriter.run.PlayerTest'` — expect compile failure (no `sleep` parameter).
- [ ] **Step 3:** Add the parameter; extend the class kdoc with one sentence: recording passes a virtual-clock sleep, live runs keep `delay`.
- [ ] **Step 4:** Same command — all `PlayerTest` cases pass.
- [ ] **Step 5:** Commit `feat: let Player take its sleep function`.

---

### Task 2: `FrameClock`

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/FrameClock.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/FrameClockTest.kt` (plain JUnit, no fixture)

**Interfaces:**
- Produces: `class FrameClock(private val fps: Int) { fun advance(d: Duration): Int }` — moves virtual time `t` to `t + d` and returns how many frame times `k / fps` fall in the half-open interval `[t, t + d)`. Half-open is what makes a chunk inserted at exactly a frame time show on that frame: the sleep that follows the insertion paints it.

Use integer nanoseconds: `first = ceilDiv(t * fps, 1e9)`, `end = ceilDiv((t + d) * fps, 1e9)`, return `end - first`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun testSpecTimelineAt60Fps() {        // spec section 5 table
    val c = FrameClock(60)
    assertEquals(6, c.advance(87.milliseconds))   // frames 0..5 -> 'v' on frame 0
    assertEquals(7, c.advance(114.milliseconds))  // frames 6..12 -> 'a' on frame 6
    assertEquals(5, c.advance(96.milliseconds))   // frames 13..17 -> 'l' on frame 13
    assertEquals(1, c.advance(4.milliseconds))    // [297, 301): frame 18 at 300 ms -> ' ' shows
}
@Test fun testHoldAddsFpsTimesSeconds() = assertEquals(60, FrameClock(60).advance(1.seconds))
@Test fun testZeroAdvancesNothing() = assertEquals(0, FrameClock(60).advance(Duration.ZERO))
@Test fun testNoDriftOverManyFrames() {
    val c = FrameClock(60); var n = 0
    repeat(3000) { n += c.advance(1.milliseconds) }
    assertEquals(180, n)                           // exactly 3 s at 60 fps
}
```

- [ ] **Step 2:** Run `./gradlew test --tests 'com.github.asm0dey.typewriter.record.FrameClockTest'` — fails (no class).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command — PASS.
- [ ] **Step 5:** Commit `feat: virtual frame clock for recordings`.

---

### Task 3: ffmpeg command and output format

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/FfmpegCommand.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/FfmpegArgsTest.kt` (plain JUnit)

**Interfaces:**
- Produces:
  - `enum class VideoFormat { MP4, GIF; companion object { fun of(path: Path): VideoFormat? } }` — by lower-cased extension, `null` otherwise.
  - `fun ffmpegArgs(ffmpeg: Path, width: Int, height: Int, fps: Int, format: VideoFormat, output: Path): List<String>`
  - `fun sizeError(width: Int, height: Int): String?` — `"width and height must be even"` when either is odd, `"width and height must be positive"` when either is < 2, else `null`.

Argument order: `ffmpeg, -y, -loglevel, error, -f, rawvideo, -pix_fmt, bgr24, -s, WxH, -r, FPS, -i, -, <format args>, <output>`. `-y` is required because stdin is our frame pipe and ffmpeg must never prompt on it; `-loglevel error` keeps stderr down to the lines worth showing.

- [ ] **Step 1: Write the failing tests**
  - `testMp4Args`: `ffmpegArgs(Path.of("/usr/bin/ffmpeg"), 1920, 1080, 60, MP4, Path.of("/tmp/a.part.mp4"))` equals the full list above with the MP4 args from Global Constraints, ending in `"/tmp/a.part.mp4"`.
  - `testGifArgs`: same for GIF; the filter is ONE list element.
  - `testFormatFromExtension`: `a.mp4`→MP4, `A.GIF`→GIF, `a.webm`→null, `a`→null.
  - `testSizeError`: `(1920,1080)`→null, `(1921,1080)`/`(1920,1081)`→"width and height must be even", `(0,1080)`→"width and height must be positive".
- [ ] **Step 2:** Run `./gradlew test --tests 'com.github.asm0dey.typewriter.record.FfmpegArgsTest'` — fails.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command — PASS.
- [ ] **Step 5:** Commit `feat: ffmpeg argument builder for MP4 and GIF`.

---

### Task 4: Locate ffmpeg

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/FfmpegLocator.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/FfmpegLocatorTest.kt` (plain JUnit, `@TempDir`)

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface FfmpegStatus {
      data class Found(val path: Path, val version: String) : FfmpegStatus   // version e.g. "7.1"
      data class NotFound(val value: String) : FfmpegStatus
      data class NotRunnable(val value: String) : FfmpegStatus
  }
  fun FfmpegStatus.describe(): String  // "ffmpeg 7.1" | "not found: <value>" | "not runnable: <value>"
  fun locateFfmpeg(value: String, pathEnv: String? = EnvironmentUtil.getValue("PATH")): FfmpegStatus
  ```
- Resolution: value containing `/` or `\` → `Path.of(value)` as-is; otherwise `PathEnvironmentVariableUtil.findInPath(value, pathEnv, null)`. `EnvironmentUtil.getValue("PATH")` is the login-shell environment the IDE loaded, which is what finds Homebrew's `/opt/homebrew/bin` when the IDE was launched from the Dock. Missing file → `NotFound`. Then run `<path> -version` with a 5 s timeout; exit 0 and a first line starting `ffmpeg version ` → `Found(path, third whitespace token)`; anything else, including an exception or timeout → `NotRunnable`.
- Blocking call: callers off the EDT, except the settings page (Task 5), where a ~50 ms `-version` on Apply is accepted. Mark with `// ponytail: runs ffmpeg -version synchronously; move to a background task if Apply ever feels slow`.

- [ ] **Step 1: Write the failing tests.** Helper `fakeExe(dir, name, script)` writes `#!/bin/sh\n$script\n` and sets it executable.
  - `testBareNameResolvedOnGivenPath`: `fakeExe(dir, "ffmpeg", "echo 'ffmpeg version 7.1 Copyright (c)'")`; `locateFfmpeg("ffmpeg", dir.toString())` == `Found(dir/"ffmpeg", "7.1")`; `describe()` == `"ffmpeg 7.1"`.
  - `testValueWithSeparatorUsedAsIs`: same fake under `dir/"bin"`; `locateFfmpeg("$dir/bin/ffmpeg", pathEnv = "")` is `Found`.
  - `testMissing`: `locateFfmpeg("$dir/nope", "")` == `NotFound("$dir/nope")`, describe `"not found: $dir/nope"`; `locateFfmpeg("ffmpeg", dir.toString())` with an empty dir == `NotFound("ffmpeg")`.
  - `testNonExecutableIsNotRunnable`: plain file without exec bit → `NotRunnable`.
  - `testWrongProgramIsNotRunnable`: fake printing `hello` → `NotRunnable`, describe `"not runnable: ..."`.
  - `testFailingExitIsNotRunnable`: fake `exit 3` → `NotRunnable`.
- [ ] **Step 2:** Run `./gradlew test --tests 'com.github.asm0dey.typewriter.record.FfmpegLocatorTest'` — fails.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command — PASS.
- [ ] **Step 5:** Commit `feat: locate ffmpeg through the login-shell PATH`.

---

### Task 5: Settings — ffmpeg path and remembered recording options

**Files:**
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/ui/TypeWriterSettings.kt`
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/ui/TypeWriterConfigurable.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/ui/TypeWriterConfigurableTest.kt`, `TypeWriterSettingsTest.kt`

**Interfaces:**
- Consumes: `locateFfmpeg`, `describe()` (Task 4).
- Produces — new `TypeWriterSettings.State` fields (all `var`, defaults shown):
  `ffmpegPath = "ffmpeg"`, `recordDir = ""` (blank = OS video dir), `recordWidth = 1920`, `recordHeight = 1080`, `recordFontSize = 0` (0 = use the target editor's font size), `recordFps = 60`, `recordHoldBeforeMs = 1000`, `recordHoldAfterMs = 2000`.
- `TypeWriterConfigurable`: `internal val ffmpegPath: TextFieldWithBrowseButton` (single-file chooser, title "ffmpeg Executable"), labelled `"ffmpeg path:"`, and `internal val ffmpegStatus: JBLabel` directly under it. `apply()` saves the value even when it fails validation, then sets `ffmpegStatus.text = locateFfmpeg(value).describe()`. `reset()` clears the status label. `apply()` must build the new state with `settings.state.copy(...)`, never the `State(...)` constructor, so fields this page doesn't edit survive.

- [ ] **Step 1: Write the failing tests** in `TypeWriterConfigurableTest`:
  - `testApplyKeepsRecordingFields` (Review Focus 1): set `settings.state.recordWidth = 1280; recordFps = 30; recordDir = "/x"`, then `reset(); apply()`; all three are unchanged.
  - `testFfmpegPathRoundTrips`: `ffmpegPath.text = "/opt/ff/ffmpeg"; apply()` → `state.ffmpegPath == "/opt/ff/ffmpeg"`; `isModified` false right after `reset()`, true after editing the field.
  - `testApplyShowsFfmpegStatusAndStillSaves`: `ffmpegPath.text = "/nonexistent/ffmpeg"; apply()` does not throw, saves the value, and `ffmpegStatus.text == "not found: /nonexistent/ffmpeg"`.
  - In `TypeWriterSettingsTest`, `testRecordingDefaults`: a fresh `State()` has the defaults above.
- [ ] **Step 2:** `./gradlew test --tests 'com.github.asm0dey.typewriter.ui.*'` — fails.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command — PASS.
- [ ] **Step 5:** Commit `feat: ffmpeg path setting with validation on Apply`.

---

### Task 6: Split `SnippetRunner.run` into `prepare` + launch

**Files:**
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/library/SnippetActions.kt` (`SnippetRunner`)
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/library/SnippetRunnerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Prepared(val editor: Editor, val steps: List<Step>, val timing: Timing)
  fun SnippetRunner.prepare(project: Project, editor: Editor?, snippet: Snippet): Prepared?
  ```
  `prepare` holds everything `run` does today up to and including base-indent application and timing resolution, including all notifications; `null` wherever `run` returns early. `run` becomes `prepare(...)?.let { RunService.launch(it.editor, it.steps, it.timing) }`.

- [ ] **Step 1: Write the failing tests**
  - `testPrepareReturnsNullWithoutEditor`: `SnippetRunner.prepare(project, null, snippet)` is `null`.
  - `testPrepareAppliesBaseIndent`: target `class A {\n    void f() {\n        <caret>\n    }\n}` in `A.java`, snippet `int x = 1;\nint y = 2;` → `prepared.steps` joined text of `Step.Type`s == `"int x = 1;\n        int y = 2;"`, and `prepared.editor === fixture.editor`. Copy the snippet setup from existing `SnippetRunnerTest` cases.
- [ ] **Step 2:** `./gradlew test --tests 'com.github.asm0dey.typewriter.library.SnippetRunnerTest'` — fails.
- [ ] **Step 3:** Refactor.
- [ ] **Step 4:** `./gradlew check` — whole suite passes; existing `run` behaviour unchanged.
- [ ] **Step 5:** Commit `refactor: separate snippet preparation from launching a run`.

---

### Task 7: Pre-flight warnings for actions that can't run offscreen

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordingPreFlight.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/RecordingPreFlightTest.kt` (fixture, `@RunInEdt(writeIntent = true)`)

**Interfaces:**
- Produces: `fun recordingWarnings(relativePath: String, steps: List<Step>): List<Check.Warning>` — one warning per distinct action id where the id is `CodeCompletion` or `SmartTypeCompletion`, starts with `EditorChooseLookupItem`, or `ActionManager.getAction(id) is CodeInsightAction`. Message, exact: `"$relativePath: the video may differ from a live run at action $id"`.

- [ ] **Step 1: Write the failing test** `testFlagsOnlyActionsThatNeedAWindow`: steps `[Action("CodeCompletion"), Action("EditorChooseLookupItem"), Action("GotoDeclaration"), Action("ReformatCode"), Action("CodeCompletion")]` with path `"s.java"` → exactly the three messages for `CodeCompletion`, `EditorChooseLookupItem`, `GotoDeclaration`, in that order. `recordingWarnings("s.java", listOf(Step.Type("x")))` is empty.
- [ ] **Step 2:** `./gradlew test --tests 'com.github.asm0dey.typewriter.record.RecordingPreFlightTest'` — fails.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command — PASS.
- [ ] **Step 5:** Commit `feat: warn when a recorded snippet uses window-only actions`.

---

### Task 8: `RecordingStage` — the offscreen tab, gutter and editor

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordingStage.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/RecordingStageTest.kt` (fixture, `@RunInEdt(writeIntent = true)`)

**Interfaces:**
- Produces:
  ```kotlin
  class RecordingStage(project: Project, source: Editor, width: Int, height: Int, fontSize: Int, parent: Disposable) {
      val editor: EditorEx
      val component: JComponent          // root, sized width x height and laid out
      fun paint(into: BufferedImage)     // component.paint + caret
  }
  ```
- Construction, EDT only (spec section 5):
  - `LightVirtualFile(sourceFile.name, sourceFile.fileType, source.document.text)` — name/type from `FileDocumentManager.getFile(source.document)`, falling back to `"untitled"` / `PlainTextFileType` when null.
  - `EditorFactory.createEditor(doc, project, lightFile, false) as EditorEx`, released in a `Disposer.register(parent) { EditorFactory.releaseEditor(editor) }`.
  - `settings.isLineNumbersShown = true`; copy `isUseSoftWraps` and `isWhitespacesShown` from `source.settings`; `scrollingModel.disableAnimation()`.
  - Font size: `fontSize` is in `Settings > Editor > Font` units; the editor's effective size is `fontSize * UISettingsUtils.getInstance().currentIdeScale`. Apply it to a scheme delegate, never to the global scheme.
  - Caret at `source.caretModel.offset`.
  - `JBTabsFactory.createEditorTabs(project, parent)` with one `TabInfo(editor.component).setText(name).setIcon(fileType.icon)`, inside a `JPanel(BorderLayout())` subclass implementing `UiDataProvider` that puts `CommonDataKeys.PROJECT`. Without it `ReformatCode` silently does nothing.
  - `component.setSize(width, height)`, then `validate()` down the tree — `revalidate()` never runs without a window.
  - Initial view: with `y` = caret's `visualPositionToXY` y and `h` = `scrollingModel.visibleArea.height`: if `y < h / 3`, scroll to 0, else `scrollVertically(y - h / 3)`.
- `paint`: `component.paint(g)` on `into.createGraphics()`, then draw the caret. The editor never paints its own (focus-owner check). Position = `editor.visualPositionToXY(caret.visualPosition)` converted with `SwingUtilities.convertPoint(editor.contentComponent, p, component)`; height `editor.lineHeight`; width `editor.settings.lineCursorWidth`, or one char width when `editor.settings.isBlockCursor`; colour `editor.colorsScheme.getColor(EditorColors.CARET_COLOR)`.

- [ ] **Step 1: Write the failing tests**
  - `testCopiesTextNameAndCaretWithoutTouchingSource`: `configureByText("Foo.java", "class Foo {<caret>}")`; stage text == source text, `FileDocumentManager.getFile(stage.editor.document)!!.name == "Foo.java"`, stage caret offset == source caret offset; typing into the stage leaves the source unchanged.
  - `testFontSizeFollowsIdeScale`: `fontSize = 20` → `stage.editor.colorsScheme.editorFontSize2D == 20f * UISettingsUtils.getInstance().currentIdeScale`; the global scheme's size is unchanged.
  - `testProjectIsInDataContext`: `DataManager.getInstance().getDataContext(stage.editor.contentComponent).getData(CommonDataKeys.PROJECT) === project`.
  - `testCaretNearTopStartsAtLineOne`: 200-line file, caret on line 2 → `visibleArea.y == 0`.
  - `testCaretDeepInFileSitsInUpperThird`: caret on line 150 → `abs((caretY - visibleArea.y) - visibleArea.height / 3) <= editor.lineHeight`.
  - `testPaintFillsFrameAndDrawsCaret`: 1280×720 stage; after `paint(img)` the image is 1280×720 and the pixel at the caret rect's centre equals the caret colour's RGB.
- [ ] **Step 2:** `./gradlew test --tests 'com.github.asm0dey.typewriter.record.RecordingStageTest'` — fails.
- [ ] **Step 3:** Implement. Dispose the stage's `parent` in each test's `finally`.
- [ ] **Step 4:** Same command — PASS.
- [ ] **Step 5:** Commit `feat: offscreen tab, gutter and editor for recordings`.

---

### Task 9: `Recorder` and the `FrameSink` boundary

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/Recorder.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/RecorderTest.kt` (fixture, `@RunInEdt(writeIntent = true)`; calls `runBlocking { record(...) }` on the EDT, as `PlayerTest` does with `Player.play`)

**Interfaces:**
- Consumes: `Player(..., sleep)` (Task 1), `FrameClock` (Task 2), `RecordingStage` (Task 8).
- Produces:
  ```kotlin
  interface FrameSink {
      suspend fun frame(bytes: ByteArray)   // width*height*3 BGR bytes
      suspend fun finish()                  // all frames written; publish the output
      fun abort()                           // discard everything; idempotent
  }
  data class RecordOptions(val output: Path, val width: Int, val height: Int, val fontSize: Int,
                           val fps: Int, val holdBeforeMs: Int, val holdAfterMs: Int)
  suspend fun record(project: Project, stage: RecordingStage, steps: List<Step>, timing: Timing,
                     options: RecordOptions, sink: FrameSink, progress: (Double) -> Unit = {})
  ```
- `record` (EDT, asserted): one reused `BufferedImage(width, height, TYPE_3BYTE_BGR)`. The `sleep` passed to `Player`: `n = clock.advance(d)`; if `n > 0`, paint once, copy the `DataBufferByte` data once, and send that same array `n` times — the editor can't change within one sleep; then report progress and `yield()`. Order: `sleep(holdBefore)`, `Player(project, stage.editor, Any(), sleep).play(steps, timing)`, `sleep(holdAfter)`, `sink.finish()`. `record` never calls `abort` and never disposes the stage; the caller owns both.
- Progress: `((document.textLength - initialLength) / totalTypedChars).coerceIn(0.0, 1.0)`. Mark `// ponytail: progress from document growth; an action that deletes text makes it dip, track step indices if that ever matters`.

- [ ] **Step 1: Write the failing tests.** In-memory sink: `class ListSink : FrameSink { val frames = mutableListOf<ByteArray>(); var finished = false; ... }`.
  - `testFrameCountMatchesVirtualTime`: `Timing(100, 0, 0)`, `[Type("abc")]`, 60 fps, holds 1000/2000 → `frames.size == 198` (3300 ms × 60, spec section 8). Every frame's size is `1280 * 720 * 3`, `finished` is true, the stage document ends with `"abc"` at the caret, and the source document is unchanged.
  - `testStartsFromUnsavedDocumentText` (Review Focus 4): change the source document in a write command without saving, build the stage, record `[Type("x")]` → stage text == edited text with `x` inserted.
  - `testLongSnippetScrolls`: 1280×720, a 10-line file, `Type("line\n".repeat(80))`, `Timing(1, 0, 0)`, holds 0 → `stage.editor.scrollingModel.visibleArea.y` after > before, and the caret's line is inside the visible area.
  - `testReformatRunsOnTheCopy`: source `class A{void f(){}}` in `A.java`, caret at end, steps `[Action("ReformatCode")]` → stage text starts with `"class A {"`; source unchanged.
  - `testFirstAndLastFramesDiffer`: `[Type("hello")]` → `frames.first()` content differs from `frames.last()`.
- [ ] **Step 2:** `./gradlew test --tests 'com.github.asm0dey.typewriter.record.RecorderTest'` — fails.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command — PASS.
- [ ] **Step 5:** Commit `feat: record a program into frames on a virtual clock`.

---

### Task 10: `FfmpegSink`, real encoding, CI ffmpeg

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/FfmpegSink.kt`
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/RecorderFfmpegTest.kt` (fixture, `@RunInEdt(writeIntent = true)`)
- Modify: `.github/workflows/build.yml` — in the job that runs `./gradlew check`, before "Run Tests", add a step `Install ffmpeg`: `sudo apt-get update && sudo apt-get install -y ffmpeg`.

**Interfaces:**
- Consumes: `ffmpegArgs`, `VideoFormat` (Task 3), `FrameSink`, `RecordOptions` (Task 9).
- Produces: `class FfmpegSink(ffmpeg: Path, options: RecordOptions) : FrameSink` and `class FfmpegFailed(val stderrTail: String) : Exception(stderrTail)`.
  - Part file: `output.parent / "${nameWithoutExtension}.part.${extension}"` (ffmpeg picks the muxer from the extension, so the `.part` goes before it).
  - The process starts lazily on the first `frame` or `finish`. stdout discarded. stderr drained by a daemon thread into a deque that keeps the last 20 lines, so the pipe can't fill and stall ffmpeg.
  - `frame`: `withContext(Dispatchers.IO) { stdin.write(bytes) }`. This replaces spec section 6's bounded `Channel`: the suspension gives the same back-pressure and frees the EDT, with no queue to manage.
  - `finish`: close stdin, `waitFor` on IO; non-zero exit → delete part, throw `FfmpegFailed(tail)`; zero → `Files.move(part, output, REPLACE_EXISTING)`.
  - `abort`: `destroyForcibly()`, delete part; safe to call twice and before start.
- Test helper: `requireFfmpeg(): Path` — `locateFfmpeg("ffmpeg")`; when not `Found`, `fail` if env `CI` is set, else `Assumptions.assumeTrue(false)`. The same for `ffprobe` via `PathEnvironmentVariableUtil.findInPath("ffprobe")`.

- [ ] **Step 1: Write the failing tests**
  - `testMp4AndGifHaveRightSizeAndDuration`: a 3-line snippet with `Timing(50, 0, 0)` into `tempDir/"my videos"/"demo.mp4"`, then `.gif` (the directory name has a space — Review Focus 2), 640×360, 30 fps, holds 500/500. `ffprobe -v error -select_streams v:0 -show_entries stream=width,height:format=duration -of default=nw=1` reports width 640 and height 360, and the duration is within 0.1 s of `frames / 30.0`. No `*.part.*` file is left in the directory.
  - `testMissingOutputDirectoryFails` (Review Focus 2): output in a non-existent directory → `record` throws `FfmpegFailed` with non-blank `stderrTail`, and nothing is left behind.
  - `testNothingToEncodeFails` (Review Focus 5): `FfmpegSink.finish()` with zero frames throws `FfmpegFailed` within 10 s (`assertTimeoutPreemptively`).
  - `testAbortKillsProcessAndDeletesPart` (Review Focus 3): send 5 frames, `abort()` → part file gone, output absent, a second `abort()` is harmless.
- [ ] **Step 2:** `./gradlew test --tests 'com.github.asm0dey.typewriter.record.RecorderFfmpegTest'` — fails.
- [ ] **Step 3:** Implement `FfmpegSink`; add the CI step.
- [ ] **Step 4:** Same command — PASS (ffmpeg is installed on this machine); `./gradlew check` green.
- [ ] **Step 5:** Commit `feat: encode recordings with ffmpeg; install ffmpeg in CI`.

---

### Task 11: Record dialog, orchestration and the action

**Files:**
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordDialog.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordingService.kt`
- Create: `src/main/kotlin/com/github/asm0dey/typewriter/record/RecordSnippetAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`, `src/main/resources/messages/TypeWriterBundle.properties`
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/ui/SnippetPicker.kt` (make `snippetPopup` `internal`)
- Test: `src/test/kotlin/com/github/asm0dey/typewriter/record/RecordOptionsTest.kt` (plain JUnit), `PluginXmlResourceBundleTest` (existing; must stay green)

**Interfaces:**
- Consumes: everything above, plus `SnippetRunner.prepare` / `notify`.
- Produces:
  - `fun defaultVideoDir(): Path` — `~/Movies` on macOS (`SystemInfo.isMac`), else `~/Videos`; `~` when that doesn't exist.
  - `fun defaultOutput(recordDir: String, snippetName: String): Path` — `(recordDir.ifBlank { defaultVideoDir() }) / "$snippetName.mp4"`, where `snippetName` is the snippet file name without extension.
  - `class RecordDialog(project: Project, initial: RecordOptions) : DialogWrapper` with `fun options(): RecordOptions`. Fields:
    - output: `TextFieldWithBrowseButton` opening `FileChooserFactory.createSaveFileDialog` with extensions `mp4`, `gif`
    - size: combo `1920×1080` / `1280×720` / `Custom`, with W and H spinners enabled only for Custom
    - font size, FPS, hold before (ms), hold after (ms): spinners
    - `doValidate`: `sizeError(...)`, or `"output must end in .mp4 or .gif"` when `VideoFormat.of` is null
    - `doOKAction`: when the output exists, `Messages.showYesNoDialog` "Replace <name>?"; No keeps the dialog open
  - `@Service(Service.Level.PROJECT) class RecordingService(private val project: Project, private val scope: CoroutineScope) { fun start(editor: Editor?, snippet: Snippet) }`. Steps, on the EDT:
    1. `prepare` or return.
    2. `locateFfmpeg(settings.ffmpegPath)`; when not `Found`, error notification `"cannot record: ffmpeg ${status.describe()}"` with a `NotificationAction` "Open Settings" → `ShowSettingsUtil.getInstance().showSettingsDialog(project, TypeWriterConfigurable::class.java)`; return.
    3. Notify each `recordingWarnings(...)` as WARNING.
    4. Show `RecordDialog`, with initial values from settings; `fontSize = 0` means `editor.colorsScheme.editorFontSize`. On OK, persist every option, `recordDir = output.parent`.
    5. `scope.launch { withBackgroundProgress(project, "Recording ${snippet.relativePath}", cancellable = true) { reportProgress { … } } }`. Inside, `withContext(Dispatchers.EDT)` builds the stage under a fresh `Disposable` and calls `record(...)` with an `FfmpegSink`. `finally`: dispose. `CancellationException` → `sink.abort()`, rethrow, no notification. `FfmpegFailed` → abort, error notification `"recording failed:\n${e.stderrTail}"`. Any other exception → abort, error notification with `e.message`. Success → INFO notification `"Recorded ${output.fileName}"` with action "Show in Folder" (`RevealFileAction.openFile(output)`).
  - `class RecordSnippetAction : AnAction(), DumbAware` — BGT update thread; `snippetPopup(project, "Record Snippet to Video")` then `RecordingService.start(e.getData(CommonDataKeys.EDITOR), snippet)`.
  - plugin.xml: `<action id="typewriter.recordSnippet" class="com.github.asm0dey.typewriter.record.RecordSnippetAction"/>`, referenced in `typewriter.menu` right after `typewriter.editSnippet`. Bundle: `action.typewriter.recordSnippet.text=TypeWriter: Record Snippet to Video...`. No `<keyboard-shortcut>`.
  - Do not register `RecordingService` in plugin.xml (`@Service` is its registration; see `RunService` kdoc).

- [ ] **Step 1: Write the failing tests** in `RecordOptionsTest`: `defaultOutput("/v", "01-entity")` == `Path.of("/v/01-entity.mp4")`; `defaultOutput("", "x").parent == defaultVideoDir()`; `defaultVideoDir()` exists.
- [ ] **Step 2:** `./gradlew test --tests 'com.github.asm0dey.typewriter.record.RecordOptionsTest'` — fails.
- [ ] **Step 3:** Implement the dialog, the service, the action, plugin.xml and the bundle.
- [ ] **Step 4:** `./gradlew check` — green, including `PluginXmlResourceBundleTest`.
- [ ] **Step 5: Manual check.** `./gradlew runIde`, open a Java file, place the caret, run `Tools > TypeWriter > Record Snippet to Video...` and pick a snippet. Confirm the dialog defaults (1920×1080, 60, 1000, 2000, the editor's font size, `~/Videos/<name>.mp4`), the background progress, and the "Recorded …" notification. The file plays and shows the tab with the file name, the gutter and the typing. Set the ffmpeg path to `/nope` → "cannot record: ffmpeg not found: /nope" with Open Settings.
- [ ] **Step 6:** Commit `feat: Record Snippet to Video action`.

---

### Task 12: "Record Video..." in the snippet dialog, docs

**Files:**
- Modify: `src/main/kotlin/com/github/asm0dey/typewriter/ui/SnippetDialog.kt:359,404-433`
- Modify: `README.md` (new `## Recording a video` section after `## Driving a talk`), `CHANGELOG.md` (new `## Unreleased` → `### Added` at the top)

**Interfaces:**
- Consumes: `RecordingService.start` (Task 11).
- Replace `private var playOnClose = false` with `private var onClose: OnClose = OnClose.NOTHING` (`private enum class OnClose { NOTHING, PLAY, RECORD }`). Add `AbstractAction("Record Video...")` immediately after "Play", setting `RECORD` then `doOKAction()`. In `doOKAction`, the existing `invokeLater` block dispatches `PLAY` → `SnippetRunner.run` and `RECORD` → `project.getService(RecordingService::class.java).start(editor, it)` — same target editor, same tick-after-disposal.
- README section (no screenshots required): what it does; both entry points; dialog fields and defaults; ffmpeg install (`brew install ffmpeg`, `sudo apt install ffmpeg`, `winget install ffmpeg`) and the ffmpeg path setting with its status line; known limits copied from spec section 5 "Known limits" (popups, completion/`EditorChooseLookupItem`, `CodeInsightAction`s, lexer-only highlighting, IDE zoom); manual check: record one snippet in a light theme and one in a dark theme and look at tab, gutter and colours.
- CHANGELOG entry: one bullet, "Record a snippet to MP4 or GIF — tab, gutter and editor, rendered offscreen — from `Tools > TypeWriter > Record Snippet to Video...` or the snippet dialog's **Record Video...** button. Needs ffmpeg."

- [ ] **Step 1:** Implement the dialog change.
- [ ] **Step 2:** `./gradlew check` — green.
- [ ] **Step 3: Manual check.** In `runIde`, `TypeWriter: Edit Snippet...` → **Record Video...** saves the snippet, closes the dialog and opens the Record dialog for the selected editor; **Play** still plays.
- [ ] **Step 4:** Write the README and CHANGELOG text.
- [ ] **Step 5:** Commit `feat: Record Video button in the snippet dialog; document recording`.

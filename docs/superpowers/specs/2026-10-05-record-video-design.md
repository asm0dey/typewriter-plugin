# Record Snippet to Video — Design

Date: 2026-10-05
Status: approved in brainstorming; awaiting written-spec review

## 1. Goal

Turn a snippet into a video of it being typed, framed like the IDE: a tab with
the file name, the gutter with line numbers, and the editor. One recording
serves two uses:

- **Talk backup** — an MP4 embedded in slides in case the live demo fails.
- **Docs** — a GIF for the README and the marketplace page.

Success: the video shows exactly what a live run of the same snippet at the
same caret would type, with the same pacing and jitter, without touching the
speaker's real file.

## 2. Decisions

| Question | Decision | Rejected |
|---|---|---|
| How frames are produced | Offscreen render of real platform components | Screen-recording a live run (stutter, blocks the IDE) |
| Clock | Virtual clock driven by `Player`'s own delays | Real-time clock + 33 ms timer (slow, drops frames); custom painter (reimplements the editor) |
| Formats | MP4 and GIF, chosen by output file extension | Single format |
| Encoder | External `ffmpeg`, path configurable | Bundled JCodec (slow, large files), bundled JavaCV (+30–40 MB per platform), stdlib-only GIF, PNG frames only |
| Start state | Copy of the current editor's file, typing at its caret | Empty file |
| Frame size | Preset (1920×1080, 1280×720) or custom W×H, plus editor font size | Match the live editor panel |
| Frame rate | 60 fps default | 30 fps (up to 33 ms quantisation visibly flattens ±20 ms jitter) |

## 3. User flow

### Entry points

Both lead to the same Record dialog and the same code path.

1. **`TypeWriter: Record Snippet to Video...`** — new action in
   `Tools > TypeWriter` and Find Action. No default shortcut, like every other
   TypeWriter action. Opens the existing snippet speed-search popup
   (`SnippetPicker`), then the Record dialog.
2. **`Record Video...` button in the snippet dialog**, next to **Play**. Behaves
   exactly like Play (`SnippetDialog.createActions` / `doOKAction`): saves the
   snippet and its directives, closes the dialog, then — a tick after disposal,
   via `invokeLater` — opens the Record dialog against
   `FileEditorManager.selectedTextEditor` instead of starting a live run.

The target is always the selected text editor and its caret, as for a live run.

### Record dialog

| Field | Default |
|---|---|
| Output file (save chooser, `.mp4` or `.gif`) | `<snippet-name>.mp4` in the last directory used for a recording; first time, the OS user video directory (`~/Videos`, `~/Movies` on macOS) |
| Size | `1920×1080`; also `1280×720`, custom W×H |
| Editor font size | current editor font size — the same unit as `Settings > Editor > Font`, so 14 here looks like 14 in the IDE |
| FPS | 60 |
| Hold before typing | 1 s |
| Hold after typing | 2 s |

- Custom W and H must be even (required by `yuv420p`); the dialog validates it.
- The dialog remembers the last values, including the output directory
  (application-level settings). Videos are build output, so the default never
  points into the project.
- The dialog confirms replacing an existing output file on OK (the save chooser may also ask
  when browsing).

### Rendering

Runs as a background task with a progress bar (characters typed / total
characters to type — total virtual time is unknown up front because of jitter
and actions) and **Cancel**. On success, a notification links to the file
(reveal in file manager).

### Settings

`Settings > Tools > TypeWriter` gets one field: **ffmpeg path**, default
`ffmpeg`.

- **Field:** a `TextFieldWithBrowseButton` with a single-file chooser, so a
  full path (`/opt/homebrew/bin/ffmpeg`, `C:\ffmpeg\bin\ffmpeg.exe`) never has
  to be typed by hand.
- **Resolution:** a bare name (no path separator) is resolved with
  `PathEnvironmentVariableUtil.findInPath`, which uses the login-shell
  environment the IDE loads at startup — not the process's own `PATH`. An IDE
  launched from the macOS Dock or Toolbox does not inherit the shell `PATH`, so
  a raw `ProcessBuilder("ffmpeg")` would miss a Homebrew install in
  `/opt/homebrew/bin`. A value containing a path separator is used as-is.
- **Validation on Apply:** the settings page resolves the value and runs
  `<resolved> -version`, showing the outcome inline under the field —
  `ffmpeg 7.1` (first line of the version output) or `not found: <value>` /
  `not runnable: <value>`. Apply is still allowed when validation fails, so the
  setting can be saved before ffmpeg is installed.

## 4. Preparation shared with live runs

`SnippetRunner.run` is split into:

- `prepare(project, editor, snippet): Prepared?` — parse, format, pre-flight
  checks and their notifications, base indent. Returns target editor, steps and
  timing, or `null` when pre-flight blocked.
- `run(...)` — `prepare` then `RunService.launch`, unchanged behaviour.

Recording calls `prepare` and feeds the result to the recorder, so the video
always types what a live run would.

## 5. Rendering

### Components (offscreen, never added to a window)

- **Document copy:** a `LightVirtualFile` with the target file's name, file
  type and current text, so PSI exists and actions such as `ReformatCode` work.
- **Editor:** an `EditorEx` from `EditorFactory` over that document:
  - the current global colour scheme, with the chosen font size. The video
    always uses the current IDE theme and scheme; a different look means
    switching theme and recording again;
  - gutter with line numbers;
  - caret at the live caret's offset;
  - soft-wrap and whitespace display copied from the live editor;
  - `scrollingModel.disableAnimation()` — see 5.3.
- **Tab:** a real `JBTabs` with one `TabInfo` carrying the file icon, the file
  name and the editor component.
- The `JBTabs` component is sized to W×H and validated (laid out) before the
  first frame.
- `JBTabs` sits inside a parent panel implementing `UiDataProvider` that
  supplies `PROJECT`. A detached component otherwise has no project in its
  data context, so `PSI_FILE` is null and actions such as `ReformatCode`
  silently do nothing.
- Layout is done by hand (`setSize` then `validate()`): `revalidate()` never
  runs for a component with no window.
- All of the above are owned by one `Disposable`, disposed on every exit path.

### Driving it: the virtual clock

`Player` gets one constructor parameter:

```kotlin
private val sleep: suspend (Duration) -> Unit = { delay(it) }
```

and every `delay(...)` in `play` goes through it. Live runs are unchanged.

`Player.delayFor` still computes each chunk's delay, including jitter; the
recorder's `sleep` advances a virtual clock by that duration instead of
waiting. Jitter therefore lives in the typing, and frames only sample it.

The recorder's `sleep(d)`:

1. advances virtual time `t` by `d`;
2. for every frame boundary `k / fps` crossed, paints the component into a
   reused `BufferedImage` (`TYPE_3BYTE_BGR`) and emits it;
3. calls `yield()` so the EDT keeps servicing the IDE.

A frame painted at time `k / fps` shows every chunk inserted at or before that
time. Holds before and after typing are frames painted with no typing.

Worked example (speed 100 ms, jitter ±20 ms, 60 fps — frame every 16.67 ms):

| Char | Inserted at | First frame showing it |
|---|---|---|
| `v` | 0 ms | 0 (0 ms) |
| `a` | 87 ms | 6 (100 ms) |
| `l` | 201 ms | 13 (216.7 ms) |
| ` ` | 297 ms | 18 (300 ms) |

No character appears more than one frame (16.7 ms) late.

The editor never paints its caret here: `EditorPainter` paints it only when the
editor is the keyboard focus owner, which a component with no window can never
be. The recorder draws the caret itself after `paint`, from
`getCaretLocations(false)`, in the scheme's caret colour and the editor's caret
shape (line or block). It never blinks, so it cannot flicker between frames.

Caret-drift detection in `Player` stays active and is harmless: nothing else
moves the offscreen caret.

### Code longer than one screen

- `Player` already calls `scrollToCaret(ScrollType.RELATIVE)` after every chunk.
  On the offscreen editor that drives the real scrolling model in a W×H
  viewport: when the caret reaches the bottom margin, the view moves just
  enough to keep it visible, so lines scroll up one at a time. Long lines
  scroll horizontally the same way unless soft wrap is on.
- Scroll animation is disabled because it runs on wall-clock time; with it on,
  frames would catch a scroll halfway or skip it. Each scroll lands completely
  within one frame.
- **Initial view:** caret near the top of the file → the view starts at line 1.
  Otherwise the caret line is placed at about the upper third of the frame,
  showing context above and empty room below for typing to fill.
- An action that moves the caret off-screen produces one instant jump on the
  next typed chunk, as it does live.
- Gutter width grows when the line count crosses 99 → 100, as in the IDE.

### Known limits (documented in the README)

- Popups (completion lookups, intention bulbs) do not appear.
- `CodeCompletion` cannot open a lookup on a component with no window, so only
  a single-candidate auto-insert completes; `EditorChooseLookupItem` then has
  no lookup and does nothing.
- `CodeInsightAction`s (e.g. `GotoDeclaration`) return early when the editor
  is not showing.
- None of these reach the speaker's real editor: the data context resolves
  only to the offscreen one.
- The editor font and all chrome (tab height, gutter icons, padding) follow the
  IDE zoom, as on screen. The font-size field is in the same unit as
  `Settings > Editor > Font`; there is no exact-pixel mode.
- Only lexer-based highlighting is shown; semantic highlighting and error
  stripes need the daemon, which does not run on a hidden editor.

## 6. Encoding

### Frame pipeline

- `FrameSink` boundary: `frame(bytes: ByteArray)` (suspend) and `close()`.
  Production implementation: `FfmpegSink`. Tests: an in-memory sink.
- The EDT copies each frame's bytes and sends them into a bounded `Channel`
  (capacity ~8). A writer coroutine on `Dispatchers.IO` drains it into ffmpeg's
  stdin. When ffmpeg falls behind, `send` suspends, which also frees the EDT.
- ffmpeg's stderr is drained continuously into a bounded buffer (last ~20
  lines), so the pipe cannot fill and stall the process.

### ffmpeg command

Built by a pure function. Input, always:

```
-f rawvideo -pix_fmt bgr24 -s WxH -r FPS -i -
```

| Output | Arguments |
|---|---|
| `.mp4` | `-c:v libx264 -pix_fmt yuv420p -crf 18 -preset medium -movflags +faststart` |
| `.gif` | `-vf "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:diff_mode=rectangle"` |

Pauses become runs of identical frames; both encoders compress them to almost
nothing.

### Output safety

ffmpeg writes `<name>.part.<ext>` next to the target; it is renamed over the
target only after ffmpeg exits 0.

## 7. Failures

| Case | Behaviour |
|---|---|
| ffmpeg not found | The configured value is resolved (section 3, "Settings") and `<resolved> -version` run before building components. On failure: error notification naming the value tried, with an **Open Settings** link to `Settings > Tools > TypeWriter`; nothing rendered. |
| Pre-flight blocked | Same notifications as a live run (from `prepare`); nothing rendered. |
| Action that cannot run offscreen | Pre-flight **warning** (recording continues), naming each `action` step whose ID is a completion action (`CodeCompletion`, `SmartTypeCompletion`, `EditorChooseLookupItem*`) or whose action is a `CodeInsightAction` (checked at runtime): "the video may differ from a live run at `action X`". |
| ffmpeg exits non-zero | Temp file deleted; error notification with the last ~20 stderr lines. |
| Cancel | Coroutine cancelled, process destroyed, temp file deleted. No notification. |
| Exception mid-render | Same cleanup as Cancel, then an error notification. |

A recording is not a run (see `CONTEXT.md`): it does not use `RunService` or
`AbortWatcher`. Typing or Escape in the IDE does not stop it; only Cancel does.
A recording may run while a live run is playing, and several recordings may
run at once — each owns its own offscreen editor and ffmpeg process.

## 8. Testing

JUnit 5 fixture tests on `TypeWriterFixtureTestCase`, `@RunInEdt` like
`PlayerTest`. The CI `check` job installs ffmpeg (`apt-get install ffmpeg`) so
the encoder test runs rather than skips.

| Test | Proves | ffmpeg |
|---|---|---|
| `PlayerTest` (+1 case) | A custom `sleep` receives exactly the `delayFor` durations; the default still uses `delay` | no |
| `FrameClockTest` | Virtual time → frame count: the section 5 table at 60 fps; holds add `fps × seconds` frames | no |
| `FfmpegArgsTest` | MP4 and GIF argument lists; odd W×H rejected; format from extension | no |
| `FfmpegLocatorTest` | Bare name resolved via `findInPath`; value with a separator used as-is; missing → `not found`; non-executable file → `not runnable`; version line parsed from `-version` output | no (fake executables in a temp dir) |
| `RecordingPreFlightTest` | `action CodeCompletion` and a `CodeInsightAction` produce one warning each naming the step; `action ReformatCode` produces none; warnings never block | no |
| `RecorderTest` | Into an in-memory sink: source document unchanged; offscreen copy ends with the expected text; frame count matches; every frame is W×H; a long snippet scrolls (visible area moves); `action ReformatCode` reformats the offscreen copy (proves `PROJECT` is supplied) | no |
| `RecorderFfmpegTest` | 3-line snippet to `.mp4` and `.gif` in a temp dir; `ffprobe` confirms size and duration; no `.part` left. Bogus ffmpeg path → clean failure, temp file removed | yes |

Manual check (README): record one snippet in a light and a dark theme and
inspect the tab, gutter and colours.

## 9. Out of scope

- Shrinking the font so the whole snippet fits one screen.
- Showing completion popups.
- Replaying the exact timing of a specific live run.
- Recording a whole sequence into one video.

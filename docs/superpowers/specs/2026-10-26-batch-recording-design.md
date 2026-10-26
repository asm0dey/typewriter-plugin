# Batch Recording — Design

Date: 2026-10-26
Status: brainstormed; awaiting user review

Builds on `2026-10-05-record-video-design.md` (single recording). Everything
there about rendering, the virtual clock, encoding and ffmpeg still applies;
this document only covers what changes when many snippets are recorded at once.

## 1. Why

A video production team turns a talk's snippets into videos. Today each video
needs its own trip through the picker, the Record dialog and a save chooser,
and each one starts from whatever file happens to be open. The team wants to
pick a set of snippets once and get a folder of videos back.

Success: one dialog, one click, a folder with one video per chosen snippet,
each typed into a clean file named after its snippet.

## 2. Goals, non-goals, anti-goals

Goals:

- Choose snippets by a name pattern, by hand, or both.
- One set of recording settings for the whole batch.
- One output folder, one video per snippet.
- Never silently overwrite an existing video.

Non-goals:

- Joining several snippets into one video (still out of scope, as in the
  single-recording spec).
- Per-snippet settings inside a batch.
- Recording into the file currently open in the editor (single recording keeps
  doing that).

Anti-goals (would make it a failure even if it works):

- One bad snippet stops the whole batch.
- A flood of notifications, one or more per snippet.
- The speaker's real files or editor are touched.

## 3. User flow

### Entry point

New action **`TypeWriter: Record Snippets to Videos...`** in `Tools > TypeWriter`
and Find Action, next to `Record Snippet to Video...`. No default shortcut. It
needs no open editor.

### Batch dialog

Top to bottom:

| Field | Behaviour |
|---|---|
| Pattern | Text field. Glob, case-insensitive, matched against the snippet's relative path. `*` matches any characters including `/`, `?` matches one. Empty pattern matches nothing. Example: `*cyberjar*`. |
| Snippet list | Every snippet from `SnippetDirs.all(project)` (project shadows global, as everywhere else), sorted by relative path, each with a checkbox. Shows the relative path and the cleaned file name the video will type into (section 4). |
| Output folder | Folder chooser. Default: the last folder used for a batch, else `defaultVideoDir()`. |
| Format | MP4 or GIF. |
| Size, font size, FPS, holds | Same fields, defaults and validation as the single Record dialog, sharing its remembered values. |

Pattern and checkboxes interact like this:

- Typing in Pattern re-ticks exactly the matching snippets and unticks the rest.
- The user can then tick or untick individual rows by hand.
- Editing the pattern again discards those manual changes (re-applies the match).
- OK is disabled while no snippet is ticked. A counter shows "N selected".

The dialog remembers the last pattern, output folder and format
(application-level settings, next to the existing `record*` fields).

### Output files

- One video per ticked snippet, in the output folder.
- Video name = the snippet's original file name without its extension, plus
  `.mp4` / `.gif`: `03-user-service.kt` → `03-user-service.mp4`. Numbers are
  kept so files sort in talk order.
- Snippets in subdirectories keep them: `java/01-hello.java` →
  `<folder>/java/01-hello.mp4`. Subdirectories are created as needed.
  *(my call)*
- Two ticked snippets can still map to one video: `java/01-hello.java` and
  `java/01-hello.kt`, or names differing only in case on a case-insensitive file
  system. Target paths are compared case-insensitively; every snippet in a
  clashing group keeps its source extension in the video name:
  `01-hello.java.mp4`, `01-hello.kt.mp4`. Names that don't clash stay short.
  *(my call)*

### Existing files

On OK, before anything renders, compute every target path. If any already
exist, show one prompt listing them (scrollable if long) with:

- **Overwrite** — record, replacing them.
- **Choose Another Folder** — back to the dialog with the folder chooser open;
  checked again on the next OK.
- **Cancel** — back to the dialog.

No per-file questions during the batch.

### Running

- One background task: "Recording 3 of 12: `03-user-service.kt`", with progress
  across the whole batch and one **Cancel**.
- Snippets record one after another, never in parallel. *(my call: keeps the EDT
  and ffmpeg load predictable)*
- Each snippet is prepared (parse, format, pre-flight) only when its turn comes,
  not all up front. The overwrite prompt therefore covers every ticked snippet's
  target, and M in "N of M" is the number of ticked snippets; a blocked snippet
  counts as failed. *(my call)*
- Cancel stops the current recording (its `.part` file is deleted, as today)
  and skips the rest. Videos already finished stay.

### At the end

One notification:

- All good: "Recorded 12 videos" with **Show in Folder**.
- Some failed or were skipped: "Recorded 10 of 12 videos", **Show in Folder**,
  and **Show Details**, which lists each failed snippet with its reason
  (pre-flight error, ffmpeg stderr tail, exception message).
- Pre-flight **warnings** (e.g. completion actions that cannot run offscreen)
  are collected into the same details list instead of a notification each. A
  batch where every video recorded but some had warnings says "Recorded 12
  videos, 2 with warnings" and also offers **Show Details**. *(my call)*
- After Cancel: "Cancelled after N of M videos" with **Show in Folder**.

ffmpeg missing is checked once before the batch starts and reported exactly as
the single recording does (error + **Open Settings**); nothing is rendered.

## 4. The file each video types into

Each video starts from an **empty** file, not the editor's file. Its name is the
snippet's name cleaned up to look like a valid class name:

1. Take the file name without extension: `03-user-service`.
2. Drop leading digits and separators (`-`, `_`, `.`, space): `user-service`.
3. Split on non-alphanumeric characters and capitalise each part (PascalCase):
   `UserService`. Existing inner capitals stay (`myHTTPClient` → `MyHTTPClient`).
4. If nothing is left (`01.java`), use `Snippet`.
5. Add back the snippet's extension: `UserService.kt`.

The same rule applies to every language, including ones without classes
(`.sql`, `.yaml`), where it is purely cosmetic. *(my call)*

This name is what the video's tab shows. The file type is the snippet's file
type, so highlighting and actions (e.g. `ReformatCode`) work as in a single
recording. Caret at offset 0, so base indent is empty.

## 5. Code changes the builder should expect

- `SnippetRunner.prepare` currently requires a real `Editor` (pre-flight blocks
  on null, and base indent reads the target's caret). The batch needs a variant
  that prepares against an empty document of the snippet's file type: same
  parsing, formatting, pre-flight (minus the editor checks) and timing, but
  returning messages instead of notifying, so the batch can collect them.
- `RecordingStage` currently copies a source `Editor`. It needs a way to start
  from a given name, file type and text (empty) instead. A single recording
  keeps its current behaviour.
- `RecordingService` gains a batch entry point that loops over snippets, reusing
  `FfmpegSink`, `RecordingStage`, `record` and `runRecording` per snippet.
- The name cleanup (section 4) and glob matching are pure functions with unit
  tests.

## 6. Left to the builder

- Exact layout and widgets of the batch dialog.
- Whether the batch dialog reuses fields from `RecordDialog` by extraction or
  composition.
- How progress is split across snippets (equal share per snippet is fine).

## 7. Testing

| Test | Proves |
|---|---|
| Name cleanup | `03-user-service.kt` → `UserService.kt`; `01_hello.java` → `Hello.java`; `01.java` → `Snippet.java`; inner capitals kept; extension kept |
| Glob match | case-insensitive; `*` crosses `/`; empty pattern matches nothing |
| Target paths | subdirectory mirrored; `01-hello.java` + `01-hello.kt` → `01-hello.java.mp4` + `01-hello.kt.mp4`; `Hello.kt` + `hello.kt` clash too; existing files detected |
| Batch run into in-memory sinks | each video typed into an empty file of the cleaned name; a pre-flight-blocked snippet is skipped and reported while the rest record; cancel keeps finished videos and removes the current `.part` |

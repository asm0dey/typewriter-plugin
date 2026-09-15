# TypeWriter

Types predefined snippets into a JetBrains IDE the way a person would, for live
demos and screencasts.

## Quick start

1. `Settings > Tools > TypeWriter` — set your snippet directory (default: `typewriter` under the JetBrains shared data directory -- `~/.local/share/JetBrains` on Linux, `~/Library/Application Support/JetBrains` on macOS, `%APPDATA%\JetBrains` on Windows).
   To give one talk its own snippets that travel with its demo repo, set
   `Settings > Tools > TypeWriter > Project` too (default `<project>/.typewriter`) — see
   [Directories](#directories).
2. `TypeWriter: New Snippet...` — name it and pick a file type. It writes the file and
   opens the snippet dialog on it — the same dialog `TypeWriter: Edit Snippet...` opens,
   with a real editor, the per-snippet speed/jitter/newline controls, and **Play**, so you
   can type the snippet and immediately watch it play. `Open in Editor` moves it to a
   normal editor tab when you want one. A file type with no usable extension (`.editorconfig`,
   `Dockerfile`-style exact names) gets the snippet's name as a directory instead, e.g.
   `01-setup/.editorconfig` — the exact filename still has to be exact.
3. Edit it like any file. It is a file.
4. `Settings > Keymap > Plugins > TypeWriter` — bind it. The plugin ships **no
   default shortcuts**: the IDE owns bindings, which is what makes them survive a
   restart. To bind a chord, double-click the action, choose *Add Keyboard
   Shortcut*, and press both strokes (e.g. `Ctrl+T` then `1`) — the dialog records
   the second stroke itself. (Or skip binding and reach everything from `Tools > TypeWriter`, or reach it
   through `TypeWriter: Type Snippet...`, a speed-search popup over every snippet.)

## Directories

Two directories contribute, and they layer:

| Directory | Setting | Role |
|---|---|---|
| `<JetBrains shared data>/typewriter` | `Settings > Tools > TypeWriter` (default) | your reusable toolkit across talks -- one directory, shared by every JetBrains IDE you demo in |
| `<project>/.typewriter` | `Settings > Tools > TypeWriter > Project` (default) | one talk's steps, committed with the demo repo |

A project snippet **shadows** a global snippet at the same relative path — the same rule
as `PATH` or a nested `.gitignore` — rather than replacing the whole global toolkit.
Subdirectories are allowed (`jcon26/01-entity.kt`), so several talks can share one repo.

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

After `action`, typing resumes wherever the action actually leaves the caret — e.g. inside
the parentheses a completion popup just inserted — not at the marker's original position.

| Directive | Meaning |
|---|---|
| `raw` | do not format this snippet |
| `speed <ms>` | base delay per character |
| `jitter <ms>` | random variation |
| `newline <ms>` | extra pause after a newline |

Write `tw::` to type a literal `tw:`.

Markers only exist inside comments, so a language with **no comment syntax at all**
(plain text, `.env`, and similar) cannot use them — see
[Snippets without comments](#snippets-without-comments) below before you write `tw: pause 500`
into one of those files expecting it to work.

## Formatting

By default a snippet is formatted with the **target project's** code style before
typing, so a sloppy file produces clean output. The format step fixes indentation
and spacing; it never changes the number of lines or their order. `tw: raw` opts
out, which is how you type ugly code and clean it up on camera.

## Snippets without comments

A plain-text, `.env`, or otherwise comment-less snippet has nowhere to put a marker —
a command is a marker, and a marker is a comment. Writing `tw: pause 500` into one of
these files does **nothing**: it is not recognized, and it types out as ordinary text
during the run, right in front of your audience.

For these files, timing (`speed`/`jitter`/`newline`, and `raw`) is set through the
snippet dialog (`TypeWriter: Edit Snippet...`) instead of a marker, and is saved next to
the snippet as `<snippet-filename>.twmeta`. `pause` and `action` are not available for
these snippets — there is no marker to carry them.

## Driving a talk

Name your project snippets `01-`, `02-` and bind `TypeWriter: Type Next` once.
`TypeWriter: Type Next` walks the **talk sequence**: the snippets in the project
directory only, in relative-path order — which is what the `01-`/`02-` prefixes are
for. It types the snippet at the cursor and advances; `Type Previous` steps back.
Both clamp at the ends rather than wrapping, so a mis-hit at the end cannot silently
restart the demo from step 1, and the cursor resets when the IDE restarts.

To see which snippet is next, read the action's own text — it names it, e.g.
`TypeWriter: Type Next: 02-entity.java (2 of 7)` — or open `TypeWriter: Type
Snippet...`, where the next snippet is marked `← next`. Picking any sequence snippet
there moves the cursor to the one after it, which is the recovery path when a demo
goes sideways.

Project snippets (`<project>/.typewriter`) are the talk; global snippets
(`<JetBrains shared data>/typewriter`) are your reusable toolkit and are not part of
the sequence.

`TypeWriter: Type Previous` steps back one slot the same way. Both stop at the ends of
the sequence rather than wrapping around — pressing `Type Next` on the last snippet does
nothing rather than silently restarting the talk from step one in front of an audience.

If a run goes wrong, `TypeWriter: Undo Run` removes exactly what the last run typed
(as long as nothing else has touched the document since).

Either directory is created for you the first time you add a snippet to it.

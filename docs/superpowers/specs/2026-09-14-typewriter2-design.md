# TypeWriter 2 — Design

Date: 2026-09-14
Status: approved; design questions resolved in review (see section 16)

## 1. Context

TypeWriter is a JetBrains IDE plugin that auto-types predefined text into the
editor, emulating a person typing, for live demos and screencasts. Version 0.1.2
ships on the JetBrains Marketplace as plugin 20245 (repo:
`~/work_self/typewriter-plugin`).

TypeWriter 2 is a full rewrite, released as version 1.0.0 of the **same**
marketplace plugin. The rewrite is motivated by defects that are structural in
v1, not incidental:

- **Output fidelity is language-specific and fragile.** `WriteCharCommand`
  strips leading whitespace from every line, delegates indentation to the IDE's
  auto-indent, and special-cases `}` with a backspace-and-caret-down trick. This
  targets C-family brace languages. XML, Python, YAML, and Markdown produce
  wrong output.
- **Command syntax collides with real code.** Commands use `<...>` delimiters.
  `JpaRepository<Courier, Long>` matches the command regex, is split on `:`,
  yields a single element, and is destructured into two — an
  `IndexOutOfBoundsException` before a character is typed. The documented
  `<reformat>` command fails the same way, so it has never worked.
- **The plugin owns keymap bindings instead of the IDE.**
  `SnippetShortcutRegistrar` (a `ProjectActivity`) re-registers actions on every
  project open and calls `keymap.addShortcut` itself, so the plugin writes into
  the user's keymap rather than letting the IDE own it. Three consequences: the
  action id is derived from the shortcut string
  (`...DirectSnippetExecution.${snippet.shortcut}`), so **changing a snippet's
  shortcut changes its action id** and orphans whatever the user bound in
  Settings > Keymap; a user's manual rebinding is fought by the plugin's own
  re-add on the next open; and failures are swallowed to `println`. An earlier
  draft of this spec claimed bindings simply did not survive restart — that was
  wrong, and the real defect is worse, because the plugin and the IDE both
  believe they own the binding.
- **Snippet authoring is a `JTextArea`.** No monospace font, no syntax
  highlighting, no formatting.

## 2. Goals

1. Typed output is exactly correct in any language the IDE supports.
2. Snippet files may be sloppily formatted; screen output is clean.
3. Hotkey bindings survive restart by construction.
4. Authoring a snippet uses a real IDE editor.
5. One command primitive that absorbs future command requests without engine
   changes.
6. A talk-length sequence of snippets is drivable from one key.

## 3. Non-goals

- Simulating typos and corrections, per-character speed curves, or bigram
  timing models.
- Pausing and resuming a run. A run either completes or aborts.
- Importing v1 snippets from `typewriter-snippets.xml`. Explicitly dropped.
- Preserving v1's UI or storage format.

## 4. Architecture

```
snippet directory
  01-entity.kt
  02-beans.xml
        |
  [0] READ     FileDocumentManager.getDocument(vf).text
               in-memory text, unsaved edits included
        |
  [1] FORMAT   CodeStyleManager.reformatText on a temp PsiFile,
               using the TARGET project's code style
        |
  [2] PARSE    MarkerParser over the FORMATTED text
               -> Program: List<Step>
        |
  [3] INDENT   baseIndent computed once from the caret context,
               prepended to every line after the first
        |
  [4] TYPE     verbatim, code point by code point
```

Format precedes parse because commands are comments and formatters preserve
comments. Formatting the raw text and then locating markers in the result avoids
any offset mapping through the formatter.

Parsing happens per run, never cached.

### Key invariant

The player has no language knowledge. It inserts characters into a `Document`.
Language is consulted only during steps 1 to 3, all of which complete before the
first character is typed, and all of which produce plain strings. This is the
property that makes XML, Python, YAML, and Markdown work without per-language
code, and it is the specific thing v1 got wrong by consulting the language
*during* typing.

### Components

| Component | Responsibility |
|---|---|
| `SnippetLibrary` (app service) | VFS-watched view of the snippet directories; file -> `Snippet{id, file, language}` |
| `MarkerParser` | Formatted text -> `Program`, using the language's `Commenter` |
| `Program` | Ordered `List<Step>`: `Type(text)`, `Pause(ms)`, `Action(id)` |
| `Player` | Coroutine; executes a `Program` against an `Editor`; cancellable |
| `RunService` (project service) | The active run, its `RangeMarker`, and the sequence cursor |
| `TypeSnippetAction` | One dynamic action per snippet, registered at startup |
| `SnippetDialog` | `EditorTextField` over the snippet file's real `Document`, plus timing fields |

## 5. Snippet format

A snippet is a plain file with a real name. The name determines the language via
`FileTypeManager.getFileTypeByFileName()` — the same mechanism that highlights
the file in its editor tab, so the dialog's view and the parser can never
disagree. There is no separate language setting on a snippet.

### Authoritative text

A snippet's text is its **`Document`**, obtained through
`FileDocumentManager.getDocument(virtualFile)` — not the bytes on disk. Unsaved
edits therefore take effect immediately: edit a snippet in a tab during
rehearsal, fire the hotkey, see the change, never save.

The `Document` is also the buffer behind the snippet dialog's `EditorTextField`
and behind any open editor tab, so all three views are the same text by
construction rather than by synchronisation.

`Document` line breaks are always `\n` (platform guarantee), so line-separator
normalisation is not something this plugin implements. A file with no
`Document` — binary, or unreadable — is a pre-flight error.

### Markers

**A comment whose body begins with the sentinel `tw:` is a marker.** A marker
carries commands or directives, is consumed during parsing, and never appears in
the typed output. Every other comment is content and is typed verbatim.

```kotlin
// tw: pause 800                              own line, fires before the line below
val user = repo.fin /* tw: complete */ding()  mid-line, fires after "repo.fin"
val x = compute()   // tw: pause 500          trailing, fires before the newline
```

```xml
<bean <!-- tw: pause 400 --> id="x"/>         mid-line, XML block comment
```

The sentinel is configurable, defaulting to `tw:`. `tw::` escapes it:
`// tw:: pause 800` types the literal text `// tw: pause 800`.

Mid-line markers require a block comment, the only comment form that can sit
inside a line and leave the file valid. Line-comment-only languages (Python,
YAML, shell, Dockerfile, Makefile) support whole-line and trailing markers only.

### Whitespace consumption

A marker is **whole-line** when it starts at the beginning of a line
(disregarding indentation) and ends at the end of a line. It may span several
lines. Anything else is **mid-line**.

| Position | Consumed |
|---|---|
| Whole-line (one or many lines) | the entire line or lines, indentation and line terminators included |
| Trailing | backwards through the whitespace separating it from the code, through the end of the marker; the line's own newline is kept |
| Mid-line | the marker text, plus any horizontal whitespace immediately following it; whitespace *before* it belongs to the author |

Whole-line consumption is what makes `// tw: pause 800` on its own line leave no
blank line behind, and what makes a multi-line marker vanish entirely:

```xml
<!--
tw: pause 500
tw: action ReformatCode
-->
```

The trailing rule and the whole-line rule converge, which matters because the
formatter runs first and may move a comment between those positions. A trailing
marker promoted to its own line, or an own-line marker pulled up to trailing,
consumes to the same output either way.

Mid-line consumption is one-sided because **the formatter inserts a space after a
block comment.** Measured on Java 2025.3:

| Authored | After the format step |
|---|---|
| `repo.fin/* tw: complete */ding()` | `repo.fin/* tw: complete */ ding()` |
| `a/* tw: pause 200 */+b` | `a/* tw: pause 200 */ + b` |

Taking the marker alone would therefore yield `repo.fin ding()` even when the
author wrote it flush, breaking the completion-mid-identifier case that mid-line
markers exist for. Consuming the following whitespace exactly cancels the
formatter's rule:

```kotlin
val user = repo.fin/* tw: complete */ding()    ->  "repo.finding()"
val user = repo.fin /* tw: complete */ding()   ->  "repo.fin ding()"
foo(a, /* tw: pause 200 */ b)                  ->  "foo(a, b)"
```

Leading whitespace is left alone, so the author still controls whether a gap
appears.

### Commands

**One marker may carry several commands, one per non-blank body line**, each
carrying the sentinel, executed in order. A body line without the sentinel is a
pre-flight error, on the assumption that it is a typo rather than intent.

| Command | Meaning |
|---|---|
| `pause <ms>` | suspend for `<ms>` milliseconds |
| `action <ActionId>` | execute any IDE action by id |

`action` subsumes v1's hardcoded `reformat` and every future request —
`ReformatCode`, `CodeCompletion`, `EditorChooseLookupItem`, `GotoDeclaration`,
`RunClass`. No plugin code is needed per new command.

`action` invokes the action with whatever scope the IDE gives it. `action
ReformatCode` with no selection reformats the **whole file**, not the typed
range. This is deliberate: an action id cannot carry a range, and inventing a
range-scoped reformat primitive was considered and rejected. Select first if a
demo needs narrower scope.

### Directives

Directives govern the snippet as a whole. **A marker body line is a directive or
a command according to its name, not its position** — `raw`, `speed`, `jitter`
and `newline` are directives; `pause` and `action` are commands. Position alone
cannot decide, because a leading `pause` is a legitimate command that fires
before the first character.

A directive appearing after any text has been typed is a pre-flight error, which
is what keeps "governs the snippet as a whole" true.

| Directive | Meaning |
|---|---|
| `raw` | skip the format step for this snippet |
| `speed <ms>` | base per-character delay |
| `jitter <ms>` | symmetric random delay range |
| `newline <ms>` | extra hesitation after a newline |

```kotlin
// tw: speed 80 jitter 25 newline 400
val user = repo.find(id)
```

```xml
<!--
tw: raw
tw: speed 80
-->
```

**One directive per line works in every language**, which matters because Python,
YAML, Dockerfile and Makefile have no block comment at all:

```python
# tw: raw
# tw: speed 80
import os
```

Two consecutive whole-line markers, both carrying directives. The multi-line
block form above is sugar for languages that have block comments; nothing
depends on it and no language is disadvantaged by lacking it.

Unset values inherit the global setting. Directives live in the file rather than
a sidecar so they survive `git mv`, diff cleanly, and reuse the same parser.

## 6. Formatting

Step 1 runs `CodeStyleManager.reformatText` against a temporary `PsiFile` built
from the snippet text with the snippet's language, using the **target project's**
code style. The same snippet fired into two projects with different conventions
produces correct output in both.

### Whitespace-equivalence guard

Demo snippets are usually unbalanced fragments — a class opened now and closed
later. "Unparseable, so skip formatting" would disable the feature in the common
case. Instead the formatter's output is validated:

```kotlin
val formatted = reformat(text)
if (formatted.filterNot(Char::isWhitespace) != text.filterNot(Char::isWhitespace))
    // discard; type the original verbatim; warn
```

The formatter may only change whitespace. If a single non-whitespace character is
added, removed, or reordered — a closing brace, a semicolon, an import — the
result is discarded and the original is typed. Java's parser recovers from a
missing `}` and formats such fragments correctly; the guard exists so a
misbehaving formatter in some language can never damage a live demo.

The guard also bounds comment movement. A formatter may move a marker between
whole-line and trailing position, but it cannot change the non-whitespace
sequence, so the marker still sits at the same point in the meaningful text —
and the consumption rules in section 5 converge for those two positions.

### Line-structure reconciliation

The guard permits any whitespace change, and one such change is visibly wrong:
formatting a statement fragment inserts blank lines between what it reads as
top-level members. Measured on Java 2025.3:

```
int x = 1;        formats to    int x = 1;
foo(x);                         <blank>
                                foo(x);
```

Method-body fragments are among the most common snippets, so this would put
stray blank lines on screen routinely.

Discarding the whole formatted result is too blunt — it throws away the
indentation fix as well. Instead, reconcile: the guard has already proved the
non-whitespace streams are identical, and blank lines contain no non-whitespace,
so the non-blank lines of both texts correspond one-to-one.

```
result = for each line of the ORIGINAL:
             blank     -> blank
             non-blank -> the next non-blank line of the FORMATTED text
```

If the two non-blank line counts differ — line wrapping split a long line — fall
back to verbatim and warn.

Net rule, and the one to state in user-facing docs: **the format step fixes
indentation and spacing; it never changes the number of lines or their order.**

`raw` skips the format step entirely, for demos that deliberately type ugly code
and then clean it up on camera with `// tw: action ReformatCode`.

## 7. Playback engine

One coroutine per run on the EDT dispatcher, `delay()` between characters. The
`Job` lives in `RunService`. A second invocation during a run is ignored, not
queued.

```kotlin
writeCommandAction(project, "TypeWriter") {   // groupId = runId
    document.insertString(offset, codePoint)
    caret.moveToOffset(offset + codePoint.length)
}
editor.scrollingModel.scrollToCaret(RELATIVE)
delay(base + jitter())
```

Insertion is by **code point**, not `Char`. Inserting surrogate pairs one `Char`
at a time places a broken half in the document.

### Independence from the user's typing settings

`document.insertString` never reaches `TypedHandler`, so the IDE's smart-typing
settings cannot affect a `Type` step. **Verified on Java 2025.3** by running the
loop above over a hazard payload — an unbalanced `{`, an unmatched `(` inside a
string literal, a `}` inside a string literal, `[`, and both quote styles — with
these six flags all on and then all off:

`AUTOINSERT_PAIR_BRACKET`, `AUTOINSERT_PAIR_QUOTE`, `SMART_INDENT_ON_ENTER`,
`INSERT_BRACE_ON_ENTER`, `REFORMAT_BLOCK_ON_RBRACE`,
`SURROUND_SELECTION_ON_QUOTE_TYPED`.

Output was byte-identical to the source in both runs, and identical between the
two runs. No brace was invented, no quote was paired, no line was re-indented.
The same run confirmed surrogate pairs round-trip.

This is a property of the mechanism rather than of Java — these settings are
applied by `TypedHandler`, which is bypassed for every language — so one
language demonstrates it. Golden tests still assert it per language, since they
cost nothing once the fixture exists.

**`action` steps are the exception, by design.** They invoke real IDE actions, so
`action CodeCompletion` followed by `action EditorChooseLookupItem` honours
auto-insert settings exactly as manual typing would. That is the point of opting
into IDE behaviour explicitly: `Type` steps are hermetic, `action` steps are not.

### Base indent

`baseIndent` is computed **once, at pre-flight**, before a single character is
typed:

```kotlin
baseIndent =
    if (caret has non-whitespace before it on its line) caretColumnAsSpaces
    else CodeStyleManager.getLineIndent(psiFile, caretOffset) ?: caretColumnAsSpaces
```

It is prepended to every line after the first. The first line is padded by the
**shortfall** between `baseIndent` and the caret's current column:

```kotlin
firstLinePad = " ".repeat((baseIndent.length - caretColumn).coerceAtLeast(0))
```

"The caret is already there, so the first line needs nothing" is true only when
the caret sits *at* `baseIndent`. Click a blank line at column 0 inside a class
body — the common gesture — and `baseIndent` is 4 while the caret column is 0, so
without the shortfall the first line lands at column 0 and every later line at 4:

```java
class Host {
int a = 1;          <- caret was at column 0
    if (a > 0) {    <- baseIndent applied
        a++;
    }
}
```

Verified across five caret positions: column 0 and column 4 one level deep,
column 0 and column 8 two levels deep, and mid-line after `int q = `. All five
produce correctly nested output.

The blank-line branch asks the IDE what indentation the target context calls for,
so clicking an empty line inside a class body yields the class body's indent
rather than column 0 — which is the common demo gesture and the case a plain
caret-column rule gets wrong. The mid-line branch aligns continuation lines to
the caret, which is what firing a snippet after `val x = ` should do.

`getLineIndent` is nullable; the fallback to caret column covers plain text,
unknown file types, and any language with no formatter — the same set that
already loses the format step.

**This is not per-line auto-indent.** Calling `adjustLineIndent` per typed
newline, interleaved with insertion, is precisely v1's failure mode. Here it is
one call, in the pre-flight phase, producing a plain `String` that the player
concatenates. The formatter owns relative shape; `baseIndent` owns absolute
position. Both apply under `raw`, which suppresses snippet formatting, not
target-context indentation.

This composition depends on the formatter normalising a fragment to column 0.
**Verified on Java 2025.3**: `    private final CourierRepository courierRepository;`
formatted as a standalone file dedents to column 0, as does a two-statement
fragment authored at 8 spaces. Re-verify per language as golden tests are added;
if some language's formatter preserves the authored indent, `baseIndent` must
subtract the snippet's common leading indent before prepending.

### Typed range

The run's output is tracked with a **`RangeMarker`**, not a pair of integers.
Any `action` step changes text length — reformat, optimize imports, a completion
insertion — after which every raw offset the player holds is stale. The
`RangeMarker` survives document edits.

The expected caret position after an `action` step is re-derived from the live
caret, not from the stale offset the player was holding. See question 19: the
marker's end was the first answer and breaks completion.

### Abort

A run is cancelled by any of:

- `AnActionListener.TOPIC` -> `beforeActionPerformed` — Escape, arrows, Save,
  any IDE action.
- `AnActionListener.TOPIC` -> `beforeEditorTyping` — the user typing a character.
- The caret not being where the run left it, checked before each insertion —
  which catches mouse clicks, since those are neither actions nor typing.

Two exemptions: actions invoked by the run's own `action` steps, and any action
whose id starts with `typewriter.`.

An AWT `KeyListener` on the editor component was considered and rejected. It
races against the hotkey that starts the run: the `keyReleased` events of a
chord like `Ctrl+T, 1` arrive after the run begins and cancel it immediately, and
auto-repeat defeats a grace period. Neither `AnActionListener` hook sees raw key
events, so no race exists. Our text insertion uses `document.insertString` and
never reaches `beforeEditorTyping`, so typing cannot self-trigger.

The tradeoff accepted: a background activity routed through the action system can
cancel a run. If that proves twitchy, the fix is an allowlist of ignorable action
ids.

### Recovery

A run is **abortable but not resumable**. Aborting mid-snippet leaves partial
text; re-firing would type the whole snippet again on top of it. Recovery is
therefore explicit:

- `groupId` is passed on every `writeCommandAction`, so native Ctrl+Z undoes the
  run as one step *if* the platform merges adjacent same-group commands.
- **`TypeWriter: Undo Run`** deletes the typed range in one write command,
  straight off the `RangeMarker`. This is the guaranteed path, because it depends
  only on code this plugin owns.

The distinction matters because a run is asynchronous — type, suspend, type — and
`executeCommand` must return synchronously, so no single command can wrap a run.
Per-character commands sharing a `groupId` is the only route to native
single-undo, and its merging behaviour across hundreds of characters, a `pause`,
and an `action` is platform behaviour this plugin does not control. `groupId`
costs one argument, so it is passed regardless; `Undo Run` is what is promised.

`Undo Run` is enabled only while the document's modification stamp is unchanged
since the run ended. Otherwise it would delete whatever now occupies those
offsets.

### Timing

Per-character delay is `base + uniform(-jitter, +jitter)`, with extra hesitation
after a newline. Three knobs: `speed`, `jitter`, `newline`. Dramatic pacing is
manual, via `pause`.

## 8. Library, actions, keymap

### Directories

Two directories contribute, and they **layer**:

| Directory | Role |
|---|---|
| `~/.typewriter` (app setting, default) | the speaker's reusable toolkit across talks |
| `<project>/.typewriter` (project setting) | one talk's steps, committed with the demo repo |

A snippet in the project directory **shadows** a global snippet with the same
relative path, the same resolution rule as `PATH` or nested `.gitignore`. Replace
semantics were rejected: defining one project snippet would otherwise hide the
speaker's entire global toolkit inside the repo they are working in.

Subdirectories are allowed — `jcon26/01-entity.kt` — so several talks coexist in
one repo. Shadowing matches on the full relative path. Ids are case-sensitive as
authored, which is a documented sharp edge when a snippet directory moves between
case-sensitive and case-insensitive filesystems.

### Actions

**Action id is the path relative to the snippet directory**, independent of which
directory supplied it:

```
01-entity.kt  ->  typewriter.snippet.01-entity.kt
```

The action resolves the actual file at invoke time against the focused project,
so a single binding drives the corresponding snippet in every demo project. Same
relative path means same id means one winner, resolved by shadowing — there is no
collision to arbitrate.

Actions are registered in a `ProjectActivity` on project open, and added to a
declared group so they cluster in the keymap tree. An earlier draft said
`ApplicationInitializedListener`; see question 21 for why that cannot work:

```xml
<group id="typewriter.snippets" text="TypeWriter" popup="true"/>
```

A VFS listener keeps the set live: a new file registers an action immediately,
without restart. A deleted file unregisters its action, and its keymap binding is
lost with it — so renaming a snippet costs its hotkey. Accepted; the alternative
(ghost actions retained indefinitely) is worse.

The plugin never writes to the keymap. The IDE owns bindings, which is why they
survive restart.

### Sequence

The `01-`, `02-` naming convention drives a cursor, so a talk-length demo needs
one binding instead of a dozen.

- **`TypeWriter: Type Next`** types the snippet at the cursor and advances.
- **`TypeWriter: Type Previous`** steps back.
- The snippet picker offers **start sequence here**, setting the cursor to any
  snippet — which also covers jumping when a demo goes sideways.

The sequence is the **project** directory only, sorted by relative path. Global
snippets are utilities, not talk steps; folding them in would interleave the
speaker's license-header snippet between steps 3 and 4. This turns the layering
rule into a real distinction: project directory is this talk in order, global
directory is the toolkit bound individually.

The cursor lives in memory, per project, and resets on restart. Persisting it
would mean reopening the IDE mid-talk silently resumes at step 7.

The cursor **clamps** at both ends: `Type Next` past the last snippet, or
`Type Previous` before the first, does nothing. See question 20.

### Snippet files in the IDE

Snippet files are fragments — often deliberately incomplete or broken, since
typing broken code and fixing it on camera is a demo pattern the `raw` directive
exists to support. Analysis noise on them is therefore never signal.

A `DefaultHighlightingSettingProvider` returns `FileHighlightingSetting.SKIP_HIGHLIGHTING`
for any file under a snippet directory:

```xml
<defaultHighlightingSettingProvider implementation="...SnippetHighlightingSettingProvider"/>
```

The EP is `dynamic="true"` and the provider is a stateless predicate over a
`VirtualFile`. It sets the default level; a manual override through the editor's
highlighting-level widget still wins.

`SKIP_HIGHLIGHTING` rather than `SKIP_INSPECTION`: the latter leaves the
highlighting pass running, so parser errors survive and an unclosed class stays
red — which is most snippets. What `SKIP_HIGHLIGHTING` costs is semantic
colouring; lexer-based colouring (keywords, strings, comments, numbers) is
applied by the editor rather than the daemon and survives.

The formatter is unaffected, since formatting runs off PSI rather than the
daemon. `raw`, format-on-play, and Reformat Code in a snippet's own tab all keep
working.

## 9. UI

### Snippet dialog

```
+- Snippet: 01-entity.kt ------------------------+
| +--------------------------------------------+ |
| | // tw: pause 800                           | |   EditorTextField over the
| | val user = repo.find(id)                   | |   file's real Document
| | // tw: action ReformatCode                 | |
| +--------------------------------------------+ |
| Base delay [100] ms   Jitter [20] ms           |
| Newline pause [300] ms   [x] Format on play    |
| Hotkey: Ctrl+T,1   [Configure in Keymap...]    |
|                  [Open in editor] [Play] [OK]  |
+------------------------------------------------+
```

The `EditorTextField` is constructed over the snippet file's existing `Document`,
so it is a real editor — monospace, highlighting, completion — and every
keystroke lands in the file.

The timing fields read and write the file's directive header.

The hotkey row is read-only and deep-links to Settings > Keymap.

**Play closes the dialog, then runs.** The dialog is modal and covers the editor,
so typing behind it is pointless; closing first also restores focus, which the
abort listener and the caret check both assume. The abort listener installs a
tick *after* the dialog is disposed, since dialog teardown can route actions
through the action system and would otherwise cancel the run it just started.

The target editor is captured when the dialog opens and re-validated at Play.

### New snippet

One dialog: a name field and a **file-type chooser** listing every registered
non-binary `FileType`, so the list automatically covers whatever languages the
IDE has installed. The chooser derives the *filename*, using
`FileTypeManager.getAssociations(fileType)`:

- extension matcher -> `snippet.kt`, the stem is editable
- exact-name matcher -> prefills `Dockerfile`, `Makefile`, `.gitignore`

An extension-only control cannot express file types identified by exact name,
several of which are useful snippet types.

Creating a snippet writes the file and opens it in a normal editor tab. There is
no custom text editor to maintain.

### Command completion

A `CompletionContributor` registered for `language="any"` offers completions
inside markers in snippet files, so command and action names need not be
remembered or spelled correctly from memory:

| After | Offers |
|---|---|
| `tw:` | `pause`, `action`, `raw`, `speed`, `jitter`, `newline` |
| `tw: action ` | every id from `ActionManager.getActionIdList("")`, presented with the action's own text and icon |
| `tw: pause ` / `speed ` / `jitter ` / `newline ` | nothing; the argument is a number |

It activates only inside a `PsiComment` whose body starts with the sentinel, and
only for files under a snippet directory — the same predicate the highlighting
provider uses — so it never fires in ordinary project code.

This turns pre-flight's "unknown action id" error from something you discover on
the first run into something you avoid while typing. Completion is served by
`CompletionService` rather than the daemon, so `SKIP_HIGHLIGHTING` from section 8
does not suppress it; an early implementation task should confirm that.

### Type Snippet…

A statically bound action opening a speed-search popup of all snippets, for the
long tail that does not warrant a hotkey. Also the entry point for **start
sequence here**.

### No ad-hoc mode

Improvised text is a snippet like any other: `New snippet`, then `Play`. A
separate ephemeral mode would reintroduce text that lives somewhere other than a
file, which is the thing this design removed. The cost is that throwaway
experiments accumulate as files to delete later; if that bites, IDE scratch files
are an additive escape hatch.

## 10. Settings

Snippet directory (app level, overridable per project), base delay, jitter,
newline pause, sentinel (`tw:`), and format-on-play. Plus the snippet list with
`New snippet`.

## 11. Errors and edge cases

### Pre-flight

Runs before the first character. Errors abort with a balloon naming `file:line`;
nothing is typed.

| Condition | Result |
|---|---|
| No focused editor, read-only file, or guarded region | error |
| Snippet file missing, or has no `Document` | error |
| Target editor is the snippet's own file | error |
| Unknown `tw:` command or directive | error, `file:line` |
| Marker body line without the sentinel | error, `file:line` |
| `action <Id>` unknown to `ActionManager` | error, `file:line` |
| Unknown file type (no commenter, no formatter) | warn, proceed without commands or formatting |
| Snippet language != target file language | warn, proceed |
| Format guard tripped | warn, proceed verbatim |
| Empty snippet | warn, no-op |

### Runtime

- `action` throws: cancel the run, show a balloon. Text already typed remains,
  and `Undo Run` still removes it.
- Editor closed or project disposed: job cancelled through the parent scope.

### Edges

- Code-point insertion (see section 7).
- A selection present at start is replaced, matching real typing.
- Multiple carets: primary only, warn.
- Tabs survive under `raw`; otherwise the formatter decides.
- A snippet containing only markers runs its commands and types nothing.
- The typed `\n` goes into the target document, so the target file's own line
  separator applies when it is saved. A snippet never imposes line endings.

## 12. Testing

The platform layer is testable with `LightJavaCodeInsightFixtureTestCase`, so
nearly everything runs in CI.

**The plugin itself depends only on `com.intellij.modules.platform.`** It reaches
languages through the generic `FileType`, `Commenter`, and `CodeStyleManager`
APIs and never names one, which is what makes new languages work for free. The
multi-language golden tests are the only thing needing language support, so those
dependencies are **test-only**:

```kotlin
intellijPlatform {
    testFramework(TestFrameworkType.Plugin.Java)   // LightJavaCodeInsightFixtureTestCase
    bundledPlugin("com.intellij.java")
    bundledPlugin("Docker")                        // acceptance test 2
    // Python, Markdown, XML as required per golden test
}
```

A language unavailable in the test runtime must **skip** its golden test loudly,
never silently pass — a skipped Dockerfile test that looks green would hide the
exact regression it exists to catch.

- **Golden tests**, one per language (Java, Kotlin, XML, Python, Markdown,
  Dockerfile, plain text): input file -> expected document text, in both
  format-on and `raw` modes. Plus a CRLF-authored fixture, which pins that `raw`
  means "identical to the document text", not "identical to the bytes".
- **Parser**: whole-line, trailing, mid-line, and multi-line markers; multiple
  commands in one marker; `tw::` escape; directive header; non-`tw:` comments
  typed verbatim; `JpaRepository<Courier, Long>` parsed as plain text (v1
  regression).
- **Whitespace consumption**: each of the three rules, plus the convergence
  property — a trailing marker and the same marker on its own line produce
  identical output.
- **Format guard**: property test asserting the whitespace-equivalence rule and
  that violations discard the formatted result.
- **Base indent**: caret on a blank line inside a class body yields the class
  body's indent; caret mid-line yields the caret column; `getLineIndent` null
  falls back to caret column. Plus fragment dedent per language (verified for
  Java; unverified elsewhere).
- **Line-structure reconciliation**: a formatter-inserted blank line is removed
  while indentation fixes survive; mismatched non-blank line counts fall back to
  verbatim with a warning.
- **Mid-line marker whitespace**: a flush-authored marker yields flush output
  even though the formatter inserts a space after the comment.
- **Player**: delay 0, jitter 0 -> deterministic final document.
- **Typing-settings independence**: the hazard payload types byte-identically
  with the six smart-typing flags all on and all off, and identically between the
  two runs. Plus a surrogate-pair payload.
- **Nested context**: a fragment typed with the caret inside an existing class
  body lands at `baseIndent`, not at the payload's own column. Without
  `baseIndent` the same payload lands at column 0 — the assertion that proves
  base indent is applied rather than inherited by luck.
- **First-line shortfall**: caret at column 0 on a blank line inside a class body
  produces a correctly indented *first* line, not just correctly indented
  subsequent ones. Five caret positions, per section 7.
- **Command completion**: `tw:` offers the six command and directive names;
  `tw: action ` offers action ids; neither fires in a non-snippet file nor in an
  ordinary comment.
- **Actions**: file created -> action registered; file deleted -> action
  unregistered; project snippet shadows a global snippet of the same relative
  path.

Manual only: how the typing looks on video, and whether native Ctrl+Z merges a
whole run.

### Acceptance test 1

Input (`.java`, `·` marks a trailing space):

```java
public interface CourierRepository extends JpaRepository<Courier, Long> {

    List<Courier> findAllByCity(String city);

// No need to define common CRUD methods manually·
}

@Service
@Transactional(readOnly = true)
public class CourierService {

    private final CourierRepository courierRepository;

    public CourierService(CourierRepository courierRepository) {
        this.courierRepository = courierRepository;
    }
```

Expected output, **both modes, identical to the input**.

This is not what an earlier draft of this spec claimed, and the correction is
worth recording because it is counter-intuitive. Measured on Java 2025.3:

- **A column-0 comment immediately before a closing brace is not re-indented.**
  A column-0 comment elsewhere in the same body *is*. This snippet's comment sits
  in the first position, so the formatter leaves it.
- **Trailing whitespace is not stripped by the formatter.** Stripping trailing
  spaces is a save action, not a formatting action.

So this fixture does not discriminate format-on from `raw`; see acceptance test 3
for one that does. Its value is as the v1 regression guard and as proof that the
format step never *damages* a fragment.

Asserted in both modes:

- The final `}` is absent. Neither mode invents one.
- `JpaRepository<Courier, Long>` and `List<Courier>` are typed as written, with
  no command parsing and no auto-close.
- Both blank lines survive.
- `// No need...` is typed as text; only its indentation differs between modes.
- Base indent is applied on top of the above in both modes.

This case crashes v1 before typing a character, and is the primary regression
guard.

### Acceptance test 3 — format-on vs raw discrimination

Acceptance test 1 cannot tell the two modes apart, so this fixture does. A
column-0 comment that is *not* last in its body, and a deliberately ragged
indent:

```java
class CourierService {
// explains the field
        private final CourierRepository repo;
    void reload() {
int n = repo.count();
    }
}
```

The formatter's raw output, measured on Java 2025.3:

```java
class CourierService {
    // explains the field
    private final CourierRepository repo;
                                          <- blank line INSERTED here
    void reload() {
        int n = repo.count();
    }
}
```

Expected output, format on, **after line-structure reconciliation**:

```java
class CourierService {
    // explains the field
    private final CourierRepository repo;
    void reload() {
        int n = repo.count();
    }
}
```

The absence of that blank line is the most important assertion in this test: it
proves reconciliation ran, not merely the formatter. Every indentation fix
survives it — the comment pulled to 4, the over-indented field pulled back to 4,
the statement pushed to 8.

Expected with `// tw: raw`: identical to the input.

### Acceptance test 2 — Dockerfile with line continuations

Dockerfile is the awkward case: line comments only (`#`), no block comment, and
`RUN` instructions split across lines with trailing backslashes. Continuation
lines carry semantic indentation that no brace-matching heuristic would guess.

Input (`Dockerfile`):

```dockerfile
# tw: pause 500
FROM eclipse-temurin:21-jre

RUN apt-get update && \
      apt-get install -y curl && \
  rm -rf /var/lib/apt/lists/*

# install the app
COPY   target/app.jar   /app/app.jar

ENTRYPOINT ["java","-jar","/app/app.jar"]
```

Asserted invariants, format-on:

- `# tw: pause 500` is a whole-line marker: the line and its terminator are
  consumed, and the typed output begins at `FROM`.
- `# install the app` is typed verbatim as text. It is a comment, but its body
  does not begin with the sentinel.
- Every `\` continuation is preserved and its line break survives. Continuation
  lines are never joined into one line.
- `["java","-jar","/app/app.jar"]` is typed exactly as written.
- The blank lines survive.

Asserted with the `raw` directive, written as `# tw: raw` because directives use
the file's own comment syntax: output is identical to the snippet's document
text, including the ragged continuation indentation and the doubled spaces in the
`COPY` line.

**The format-on golden file is generated, not guessed.** IntelliJ's Dockerfile
formatter decides whether the ragged continuation indents and the `COPY` spacing
get normalized. The implementation captures the real formatter's output once, a
human reviews it, and it is committed as the golden file. This spec asserts the
invariants above, not a byte-exact formatted output it cannot verify in advance.

Mid-line markers are unavailable in Dockerfile, as in every line-comment-only
language. Whole-line and trailing markers work.

## 13. Stack and release

Scaffolded from the IntelliJ Platform Plugin Template, checked at version 2.6.0
on 2026-09-14. The template has changed substantially since v1 was created, so
v1's build files are not a reference:

| Item | Value |
|---|---|
| IntelliJ Platform Gradle Plugin | 2.16.0, applied as `org.jetbrains.intellij.platform` |
| Settings plugin | `org.jetbrains.intellij.platform.settings` 2.16.0, configures repositories in `settings.gradle.kts` |
| Kotlin | 2.1.20 |
| Changelog plugin | 2.5.0 |
| Platform | `intellijIdea("2025.2.6.2")` |
| Baseline | 2025.2 (since-build 252) |

Differences from v1's build to be aware of:

- `refreshVersions` (`versions.properties`) and `gradle/libs.versions.toml` are
  both gone from the template. Plugin versions live in `settings.gradle.kts`
  `pluginManagement`, the platform version in `build.gradle.kts`.
- `intellijIdea("<version>")` replaces the older `create(type, version)` form.
- `gradle.properties` no longer carries `pluginSinceBuild` / `pluginUntilBuild`;
  the Gradle plugin derives them from the platform version.
- Gradle **configuration cache and build cache are enabled by default**. Build
  logic must be configuration-cache-clean.
- `kotlin.stdlib.default.dependency = false` — the Kotlin stdlib is not bundled.
  **Mandatory, not cosmetic.** Without it, every platform test fails at fixture
  setup with `NoSuchMethodError: SequencesKt.sequenceOf`, because the project's
  Kotlin stdlib shadows the newer one the platform ships.

Baseline moves from v1's 2024.3 (243) to **2025.2 (252)**. This is a fresh
plugin with no installed-base constraint on the new code, and it removes doubt
about the availability of the suspending `writeCommandAction` coroutine API used
in section 7.

Shipped as version **1.0.0 of marketplace plugin 20245**. Breaking for existing
users: v1 snippets in `typewriter-snippets.xml` are not migrated, and the v1
dialog workflow is replaced. The changelog must state that snippets need to be
recreated as files.

## 14. Deferred

- Typo-and-correct simulation and richer timing models. Add when a recording
  actually looks wrong.
- Pause and resume of a live run. The abort path exists; resuming is additive.
- Sugar aliases for common `action` ids.
- IDE scratch files as a second, non-bindable class of snippet, if snippet-
  directory clutter becomes a problem.
- Raw sentinel support for languages with no commenter (JSON, plain text).
- An allowlist of action ids that do not abort a run.
- Preview showing the exact text a snippet will produce. Nearly free given the
  deterministic pipeline, but not required for 1.0.0.

## 15. Risks

- ~~**Keymap resolution order.**~~ **Resolved.** Bindings are the IDE's to keep:
  `KeymapImpl.writeOwnActionIds`
  serialises every id in `actionIdToShortcuts` with no check against
  `ActionManager`, and `writeScheme` returns the stored element verbatim when
  untouched. Bindings for unregistered actions persist across save and reload, so
  registration only has to happen before the user presses a key. v1's bug was
  never a timing race; it simply never registered at startup.
- ~~**Fragment dedent assumption.**~~ **Resolved for Java** (section 7). Still
  unverified for other languages; each golden test confirms its own.
- **Formatter behavior on fragments** varies by language, and Java alone produced
  three surprises: a column-0 comment last in its body is not re-indented,
  trailing whitespace is not stripped, and blank lines get inserted between
  statement fragments. The guard plus reconciliation bound the damage to "less
  formatting than hoped", never "wrong output" — but per-language golden files
  must be **generated and reviewed**, never predicted.
- **Language support in tests is plugin-provided.** Docker, Python, and Markdown
  `Commenter`s and formatters come from bundled plugins declared as test-only
  dependencies. If a language is unavailable at runtime the file falls back to
  plain text — no commands, no formatting — which pre-flight reports as a
  warning. In tests that condition must skip loudly rather than pass.
- **`EditorTextField` over a file-backed `Document`** must not hold the document
  in a modified-but-unsaved state when the dialog closes. Save on OK.

## 16. Resolved design questions

Settled during design review. Recorded because several rejected options are the
obvious first guess.

| # | Question | Resolution |
|---|---|---|
| 1 | What is `baseIndent`? | `getLineIndent` at the caret, once at pre-flight; caret column when mid-line or when null. Rejected: caret column alone (wrong for the click-a-blank-line-in-a-class gesture); per-line `adjustLineIndent` (v1's failure mode). |
| 2 | Abort mechanism | `AnActionListener` (`beforeActionPerformed` + `beforeEditorTyping`) plus a caret check. Rejected: AWT `KeyListener`, which races the hotkey's own key-up and auto-repeat. |
| 3 | `action ReformatCode` scope | Left as plain action semantics: whole file. Rejected: a range-scoped `reformat` primitive; implicit selection of the typed range. The `RangeMarker` requirement stands independently. |
| 4 | Marker whitespace consumption | Whole-line takes its lines; trailing takes preceding whitespace; mid-line takes only itself. |
| 5 | Commands per marker | Many, one per sentinel-carrying body line. |
| 6 | Snippet's authoritative text | The `Document`, not the bytes. Corrects the CRLF and byte-identity claims. |
| 7 | Project vs global directory | Layer, project shadows global. Rejected: replace, which hides the global toolkit. |
| 8 | Ad-hoc mode | Removed. It is `New snippet` + `Play`. |
| 9 | Pause and resume | Not in 1.0. Abort only; `Undo Run` recovers. |
| 10 | Snippet files as project files | Suppress analysis on them. Rejected: excluding the directory, which kills highlighting too. |
| 11 | Which highlighting level | `SKIP_HIGHLIGHTING`. `SKIP_INSPECTION` leaves parser errors, and most snippets are incomplete by design. |
| 12 | Driving a talk-length sequence | `Type Next` over a cursor, project directory only, picker sets the cursor. |
| 13 | Undo of a run | `groupId` for free, plus `TypeWriter: Undo Run` as the guarantee. A command cannot span a `delay()`, so native single-undo is not ours to promise. |

Three further corrections came from measuring the Java formatter directly rather
than reasoning about it (spike, 2026-09-14, IDEA 2025.3):

| # | Finding | Consequence |
|---|---|---|
| 14 | The formatter inserts a space after a block comment | Mid-line markers consume the whitespace *following* them, not the marker alone. Otherwise completion-mid-identifier cannot work. |
| 15 | The formatter inserts blank lines between statement-fragment "members" | Line-structure reconciliation added to section 6. The whitespace-equivalence guard alone accepts this, because it is whitespace. |
| 16 | A column-0 comment last in its body is not re-indented, and trailing whitespace is never stripped | Acceptance test 1's expected output was wrong in an earlier draft; it does not discriminate the two modes. Acceptance test 3 added for that. |
| 17 | Auto-close pairs and auto-indent settings have no effect on `Type` steps, on or off | Confirms the central premise of the verbatim-insertion design. Recorded in section 7 with the exact flags tested, so a future reader need not re-derive it. |
| 18 | The first line needs `baseIndent` too, minus the caret's column | "The caret is already there" holds only when the caret sits at `baseIndent`. Clicking a blank line at column 0 inside a class body — the common gesture — otherwise leaves the first line at column 0. |
| 19 | Expected caret after an `action` step | The live caret, not the marker's end. Rejected: the marker's end, which aborts the run whenever an action parks the caret anywhere but the range end — completion landing inside `foo(&#124;)` is the common case, and section 7 designs for it explicitly. Drift detection exists to catch *the user* moving the caret; the run's own action is already exempt under "Abort". The `RangeMarker` remains the typed range. |
| 20 | Cursor at either end of the sequence | Clamp. Rejected: wrap, which silently restarts the demo from step 1 in front of an audience — the same surprise section 8 refuses when it declines to persist the cursor; and error, which is worse still. Clamping means nothing happens, which the speaker notices immediately and can recover from. |
| 21 | Where snippet actions are registered | A `ProjectActivity` on project open, re-run by a scoped `BulkFileListener`. Rejected: `ApplicationInitializedListener`, which fires before any project is open and so structurally cannot read a project's snippet directory — project snippets would never register at all. The "precede keymap resolution" concern it was chosen for does not bite: a keymap stores bindings by action id, so a binding for an id registered slightly later still resolves when the key is next pressed. |
| 22 | Timing for a snippet whose language has no comment syntax | A per-snippet sidecar, `<snippet>.twmeta`, in `java.util.Properties` format, holding only the directive fields that were set (`speed`, `jitter`, `newline`, `raw`). The comment header stays the ONLY source of truth wherever comments exist, so the two never compete; the sidecar is consulted only when `CommentSyntax.hasAny` is false, by both the dialog AND the playback path. `write` must not synthesise an uncommented marker line in that case — `MarkerScanner` cannot recognise it, so it would be typed into the editor as ordinary text. `pause` and `action` are unavailable for such snippets by construction, since a command is a marker and a marker is a comment. Sidecars are excluded from snippet collection by extension. Rejected: one JSON file per directory, which needs a dependency the plugin does not declare (Gson left core in 253), conflicts as a whole structure in the shared demo repository this file travels in, needs ambiguous dotted path keys, and makes one corrupt file silently destroy every other snippet's timing through read-modify-write — a hazard a per-snippet file cannot have. Also rejected: session-only values, which lose the setting on every restart; a settings-level map, which does not travel with the repository; and disabling the fields, which removes per-snippet control from exactly the plain-text and YAML snippets the rewrite exists to serve. |
| 23 | Naming a snippet whose file type has an exact name (`Dockerfile`, `Makefile`) | The stem names a **directory**, the exact name names the file inside it: stem `01-build` plus type Dockerfile creates `01-build/Dockerfile`. The exact name is not negotiable — `01-Dockerfile` no longer matches the `ExactFileNameMatcher`, so it would resolve as plain text with no comment syntax and therefore no markers at all. Without a directory a talk could hold only one such snippet, and it could take no `01-` sequence position. This also gives the stem a job: for these types `suggestName` discarded it entirely. Rejected: stripping a `\d+-` prefix before resolving the file type, which would make the parser see Dockerfile while the editor tab sees plain text — exactly the divergence section 5 forbids when it ties the language to `getFileTypeByFileName()` precisely because that is what highlights the tab; and registering our own file-type association, which writes into the user's global settings, the same mistake v1 made with the keymap. |

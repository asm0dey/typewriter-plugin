# TypeWriter 2 — Design

Date: 2026-09-14
Status: approved, pending implementation plan

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
- **Hotkeys do not survive IDE restart.** Snippet actions are registered only
  inside `TypeWriterAction.actionPerformed`. Nothing re-registers them at
  startup, so keymap entries resolve to nothing after a restart.
- **Snippet authoring is a `JTextArea`.** No monospace font, no syntax
  highlighting, no formatting.

## 2. Goals

1. Typed output is exactly correct in any language the IDE supports.
2. Snippet files may be sloppily formatted; screen output is clean.
3. Hotkey bindings survive restart by construction.
4. Authoring a snippet uses a real IDE editor.
5. One command primitive that absorbs future command requests without engine
   changes.

## 3. Non-goals

- Simulating typos and corrections, per-character speed curves, or bigram
  timing models.
- Importing v1 snippets from `typewriter-snippets.xml`. Explicitly dropped.
- Preserving v1's UI or storage format.

## 4. Architecture

```
snippet dir
  01-entity.kt
  02-beans.xml
        |
  [1] FORMAT   CodeStyleManager.reformatText on a temp PsiFile,
               using the TARGET project's code style
        |
  [2] PARSE    MarkerParser over the FORMATTED text
               -> Program: List<Step>
        |
  [3] INDENT   baseIndent = indent at caret when the hotkey fired,
               prepended to every line after the first (string math)
        |
  [4] TYPE     verbatim, code point by code point
```

Format precedes parse because commands are comments and formatters preserve
comments. Formatting the raw file and then locating markers in the result avoids
any offset mapping through the formatter.

Parsing happens per run, never cached. A snippet edited in an open tab takes
effect on the next invocation.

### Key invariant

The player has no language knowledge. It inserts characters into a `Document`.
Language is consulted for exactly two things, both at parse time: comment syntax
and code style. This is the property that makes XML, Python, YAML, and Markdown
work without per-language code.

### Components

| Component | Responsibility |
|---|---|
| `SnippetLibrary` (app service) | VFS-watched view of the snippet dir; file -> `Snippet{id, file, language}` |
| `MarkerParser` | Formatted text -> `Program`, using the language's `Commenter` |
| `Program` | Ordered `List<Step>`: `Type(text)`, `Pause(ms)`, `Action(id)` |
| `Player` | Coroutine; executes a `Program` against an `Editor`; cancellable |
| `TypeSnippetAction` | One dynamic action per snippet, registered at startup |
| `SnippetDialog` | `EditorTextField` over the snippet file's real `Document`, plus timing fields |

## 5. Snippet format

A snippet is a plain file with a real extension. The extension determines the
language via `FileTypeManager.getFileTypeByFileName()` — the same mechanism that
highlights the file in its editor tab, so the dialog's view and the parser can
never disagree. There is no language selector.

### Commands

**A comment whose body starts with the sentinel `tw:` is a command, executed at
exactly the position it occupies.** Every other comment is typed verbatim.

```kotlin
// tw: pause 800                              own line, fires before the line below
val user = repo.fin /* tw: complete */ding()  mid-line, fires after "repo.fin"
val x = compute()   // tw: pause 500          trailing, fires before the newline
```

```xml
<bean <!-- tw: pause 400 --> id="x"/>         mid-line, XML block comment
```

A command comment contributes zero characters. Text on either side joins
seamlessly.

Mid-line commands require a block comment, the only comment form that can sit
inside a line and leave the file valid. Line-comment-only languages (Python,
YAML, shell, Makefile) support own-line and trailing commands only.

The sentinel is configurable, defaulting to `tw:`.

### Command vocabulary

| Command | Meaning |
|---|---|
| `pause <ms>` | suspend for `<ms>` milliseconds |
| `action <ActionId>` | execute any IDE action by id |

`action` subsumes v1's hardcoded `reformat` and every future request —
`ReformatCode`, `CodeCompletion`, `EditorChooseLookupItem`, `GotoDeclaration`,
`RunClass`. No plugin code is needed per new command. Sugar aliases may be added
later as a pure lookup table.

### File-level directives

Directives occupy the leading run of `tw:` comments at the top of the file.

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

Unset values inherit the global setting. Directives live in the file rather than
a sidecar so they survive `git mv`, diff cleanly, and reuse the existing parser.

### Escape

`tw::` types a literal `tw:` marker.

```kotlin
// tw:: pause 800    ->  types  // tw: pause 800
```

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

`raw` skips the format step entirely, for demos that deliberately type ugly code
and then clean it up on camera with `// tw: action ReformatCode`.

## 7. Playback engine

One coroutine per run on the EDT dispatcher, `delay()` between characters. The
`Job` lives in a per-project service. A second invocation during a run is
ignored, not queued.

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

A shared `groupId` across the run makes Ctrl+Z undo the entire snippet as one
step. Granularity intentionally breaks at `action` steps, which carry their own
undo — a reformat should be separately undoable.

Caret offset is re-read from the caret model each character rather than
accumulated, so nothing desyncs if the document changes underneath.

### Abort

A `KeyListener` is installed on `editor.contentComponent` for the duration of a
run: **any keypress cancels**. Programmatic steps call action handlers directly
and generate no key events, so a run cannot abort itself. This is panic-proof on
stage and requires no keymap entanglement.

### Timing

Per-character delay is `base + uniform(-jitter, +jitter)`, with extra hesitation
after a newline. Three knobs: `speed`, `jitter`, `newline`. Dramatic pacing is
manual, via `pause`.

## 8. Library, actions, keymap

Snippet directory: application-level setting, default `~/.typewriter`,
overridable per project so `<project>/.typewriter` can be committed with the demo
repository.

**Action id is derived from the path relative to the snippet dir**, independent
of which directory supplied it:

```
01-entity.kt  ->  typewriter.snippet.01-entity.kt
```

The action resolves the actual file at invoke time against the focused project.
A single binding therefore drives the corresponding snippet in every demo
project.

Actions are registered in an `ApplicationInitializedListener`, early enough to
precede keymap resolution. They are added to a declared group so they cluster in
the keymap tree:

```xml
<group id="typewriter.snippets" text="TypeWriter" popup="true"/>
```

A VFS listener keeps the set live: a new file registers an action immediately,
without restart. A deleted file unregisters its action, and its keymap binding is
lost with it — so renaming a snippet costs its hotkey. Accepted; the alternative
(ghost actions retained indefinitely) is worse.

The plugin never writes to the keymap. The IDE owns bindings, which is precisely
why they survive restart.

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
keystroke lands in the file. The dialog and an open editor tab are the same
buffer; there is no copy and no sync layer.

The timing fields read and write the file's directive header.

The hotkey row is read-only and deep-links to Settings > Keymap.

### Other entry points

- **New snippet**: one field, filename with extension. Creates the file, opens it
  in a normal editor tab. There is no custom text editor to maintain.
- **Type Snippet...**: statically bound action opening a speed-search popup of
  all snippets, for the long tail that does not warrant a hotkey.
- **Type Ad-hoc**: the same snippet dialog over a scratch document. Paste, set
  jitter, Play, nothing persisted. Replaces v1's dialog workflow.

## 10. Settings

Snippet directory, base delay, jitter, newline pause, sentinel (`tw:`), and
format-on-play. Plus the snippet list with `New snippet`.

## 11. Errors and edge cases

### Pre-flight

Runs before the first character. Errors abort with a balloon naming `file:line`;
nothing is typed.

| Condition | Result |
|---|---|
| No focused editor, read-only file, or guarded region | error |
| Snippet file missing or unreadable | error |
| Unknown `tw:` command or directive | error, `file:line` |
| `action <Id>` unknown to `ActionManager` | error, `file:line` |
| Unknown extension (no commenter, no formatter) | warn, proceed without commands or formatting |
| Snippet language != target file language | warn, proceed |
| Format guard tripped | warn, proceed verbatim |
| Empty snippet | warn, no-op |

### Runtime

- `action` throws: cancel the run, show a balloon. Text already typed remains and
  is still one undo group.
- Editor closed or project disposed: job cancelled through the parent scope.

### Edges

- Code-point insertion (see section 7).
- CRLF normalized to `\n` at read; the document's own separator applies.
- A selection present at start is replaced, matching real typing.
- Multiple carets: primary only, warn.
- Tabs survive under `raw`; otherwise the formatter decides.
- A snippet containing only commands runs them and types nothing.

## 12. Testing

The platform layer is testable with `LightJavaCodeInsightFixtureTestCase`, so
nearly everything runs in CI.

**The plugin itself depends only on `com.intellij.modules.platform`.** It reaches
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
  format-on and `raw` modes.
- **Parser**: own-line, trailing, and mid-line block markers; `tw::` escape;
  directive header; non-`tw:` comments typed verbatim; `JpaRepository<Courier,
  Long>` parsed as plain text (v1 regression).
- **Format guard**: property test asserting the whitespace-equivalence rule and
  that violations discard the formatted result.
- **Player**: delay 0, jitter 0 -> deterministic final document.
- **Actions**: file created -> action registered; file deleted -> action
  unregistered.

Manual only: how the typing looks on video.

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

Expected output, format on (default):

```java
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
```

Expected output with `// tw: raw`: byte-for-byte identical to the input,
including the column-0 comment and the trailing space.

The only difference between the two modes is:

```diff
-// No need to define common CRUD methods manually·
+    // No need to define common CRUD methods manually
```

Asserted in both modes:

- The final `}` is absent. Neither mode invents one.
- `JpaRepository<Courier, Long>` and `List<Courier>` are typed as written, with
  no command parsing and no auto-close.
- Both blank lines survive.
- `// No need...` is typed as text; only its indentation differs between modes.
- Caret base indent is applied on top of the above in both modes.

This case crashes v1 before typing a character, and is the primary regression
guard.

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

- `# tw: pause 500` is consumed. It contributes zero characters, and the typed
  output begins at `FROM`.
- `# install the app` is typed verbatim as text. It is a comment, but its body
  does not begin with the sentinel.
- Every `\` continuation is preserved and its line break survives. Continuation
  lines are never joined into one line.
- `["java","-jar","/app/app.jar"]` is typed exactly as written.
- The blank lines survive.

Asserted with the `raw` directive, written as `# tw: raw` because directives use
the file's own comment syntax: output is byte-for-byte identical to the input,
including the ragged continuation indentation and the doubled spaces in the
`COPY` line.

**The format-on golden file is generated, not guessed.** IntelliJ's Dockerfile
formatter decides whether the ragged continuation indents and the `COPY`
spacing get normalized. The implementation captures the real formatter's output
once, a human reviews it, and it is committed as the golden file. This spec
asserts the invariants above, not a byte-exact formatted output it cannot
verify in advance.

Mid-line commands are unavailable in Dockerfile, as in every line-comment-only
language. Own-line and trailing commands work.

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
- Sugar aliases for common `action` ids.
- A file-type combo in the New-snippet dialog, purely to append the extension.
- Raw sentinel support for languages with no commenter (JSON, plain text).
- Preview showing the exact text a snippet will produce. Nearly free given the
  deterministic pipeline, but not required for 1.0.0.

## 15. Risks

- **Keymap resolution order.** Dynamic actions must be registered before the
  keymap resolves bindings, or bindings for unknown ids may be dropped. Mitigated
  by `ApplicationInitializedListener`; must be verified against a real restart
  early in implementation.
- **Formatter behavior on fragments** varies by language. The
  whitespace-equivalence guard bounds the damage to "no formatting applied",
  never "wrong output".
- **Language support in tests is plugin-provided.** Docker, Python, and Markdown
  `Commenter`s and formatters come from bundled plugins declared as test-only
  dependencies. If a language is unavailable at runtime the file falls back to
  plain text — no commands, no formatting — which pre-flight reports as a
  warning. In tests that condition must skip loudly rather than pass.
- **`EditorTextField` over a file-backed `Document`** must not hold the document
  in a modified-but-unsaved state when the dialog closes. Save on OK.

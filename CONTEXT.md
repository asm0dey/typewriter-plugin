# Context

Glossary for TypeWriter, a JetBrains IDE plugin that types predefined text into
the editor, emulating a person typing, for live demos and screencasts.

## Snippet

A file authored by the speaker, held in the snippet directory, containing the
text to be typed and the markers that punctuate it. A snippet is the unit a
hotkey is bound to. Its file extension determines its language.

Not to be confused with a JetBrains **Live Template**, which is an unrelated
IDE feature. TypeWriter snippets are never expanded by the IDE.

## Sentinel

The prefix that distinguishes a marker from an ordinary comment. `tw:` by
default, configurable.

## Marker

A comment in a snippet whose body begins with the sentinel. A marker carries
either a command or a directive. It is consumed during parsing and never
appears in the typed output.

A marker is **whole-line** when it starts at the beginning of a line
(disregarding indentation) and ends at the end of a line; it may span several
lines. Otherwise it is **mid-line**. The distinction governs how much
surrounding whitespace the marker takes with it when consumed.

An ordinary comment — one whose body does not begin with the sentinel — is
content, and is typed like any other text.

## Command

An instruction carried by a marker, executed at the position the marker
occupied. Commands are `pause` and `action`.

## Directive

A setting carried by a marker at the head of a snippet, governing the snippet
as a whole rather than a position within it: `raw`, `speed`, `jitter`,
`newline`.

## Snippet directory

Where snippets live. Two of them, distinguished by role rather than by
mechanism: the **global** directory is the speaker's toolkit, reusable across
talks; the **project** directory holds one talk's steps and travels with its
demo repository. A project snippet **shadows** a global snippet of the same
relative name.

## Sequence

The talk's snippets in order, taken from the project directory. Only the
project directory has a sequence; the global toolkit is not ordered, because
its snippets are not steps in anything.

## Cursor

The sequence's position — which snippet comes next. It belongs to a session of
working on a talk, not to the talk itself, and starts over each time the IDE
does.

## Program

The ordered sequence of steps parsed from a snippet. A step is text to type, a
pause, or an action.

## Run

One execution of a program against one editor. A run can be aborted, and
carries the typing speed in force for it.

## Typed range

The region of the target document a run has written so far. Distinct from the
snippet: the snippet is authored text, the typed range is what currently exists
on screen.

## Base indent

The indentation the target context calls for at the point a run begins,
contributed by the document being typed into rather than by the snippet. The
snippet supplies relative shape; the base indent supplies absolute position.

## Raw

A snippet that bypasses formatting, typed exactly as authored. The opposite is
the default, where a sloppily authored snippet produces clean output.

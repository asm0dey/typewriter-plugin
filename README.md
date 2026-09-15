# TypeWriter

Types predefined snippets into a JetBrains IDE the way a person would, for live
demos and screencasts.

## Quick start

1. `Settings > Tools > TypeWriter` — set your snippet directory (default `~/.typewriter`).
2. `TypeWriter: New Snippet...` — name it and pick a file type. It writes the file and
   opens it in a normal editor tab.
3. Edit it like any file. It is a file.
4. `Settings > Keymap > Plugins > TypeWriter` — bind it. (Or skip binding and reach it
   through `TypeWriter: Type Snippet...`, a speed-search popup over every snippet.)

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

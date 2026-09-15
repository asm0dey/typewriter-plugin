# TypeWriter Changelog

## 1.0.1

### Fixed

- A snippet whose formatting would move content across lines is now indented rather
  than typed exactly as authored. One line the formatter wanted to split used to
  discard every indentation fix in the whole snippet — an XML file's root element
  stayed under the 28 spaces it was authored with. Lines are still never split,
  reordered, or added: the retry only re-indents each line where it stands.

## 1.0.0

Complete rewrite.

### Breaking

- Snippets are files in a snippet directory, not entries in plugin settings.
  Snippets stored by 0.x are **not migrated** and must be recreated.
- The 0.x typing dialog is gone. Create a snippet and press Play.
- Command syntax changed from `<pause:1000>` to a comment carrying `tw: pause 1000`,
  so snippet files stay valid and code like `JpaRepository<Courier, Long>` is no
  longer mistaken for a command.

### Added

- Output is correct in every language the IDE supports; the typing engine has no
  language-specific code.
- Snippets are formatted with the target project's code style before typing;
  `tw: raw` opts out.
- `tw: action <ActionId>` runs any IDE action mid-snippet.
- `TypeWriter: Type Next` walks a talk's snippets in order from one binding.
- `TypeWriter: Undo Run` removes exactly what a run typed.
- Completion for command names and action ids inside markers.
- Any keystroke, action or mouse click aborts a run.

### Fixed

- Hotkeys are ordinary IDE actions bound in Settings > Keymap. The plugin no
  longer writes to your keymap, and an action id no longer changes when you
  change a shortcut.

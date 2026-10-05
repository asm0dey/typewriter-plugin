# Recordings render offscreen, not by capturing the screen

A recording replays the snippet into a hidden copy of the target editor (real
`JBTabs` + `EditorEx`, never added to a window) on a virtual clock driven by the
player's own delays, painting each frame itself, rather than screen-capturing a
live run. That makes every frame exact, renders faster than real time, leaves
the speaker's screen and real file untouched, and keeps the typing's jitter
because the jittered delays drive the clock.

## Consequences

The price is everything that only exists in a window. Completion lookups cannot
open, so `action CodeCompletion` followed by `action EditorChooseLookupItem`
types less in a video than in a live run; `CodeInsightAction`s such as
`GotoDeclaration` do nothing; popups and intention bulbs never appear; the
editor will not paint its own caret, so the recorder draws it. A recording warns
about such actions rather than refusing them. Asking for "completion in videos"
means revisiting this decision, not patching the recorder: it needs a real,
showing window, i.e. screen capture of a live run.

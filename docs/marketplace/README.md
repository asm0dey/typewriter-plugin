# Marketplace assets

Screenshots for the [plugin page](https://plugins.jetbrains.com/plugin/20245).

- `*.png` — raw captures of the real dialogs and settings page.
- `store/*.png` — the same captures with Play-Store-style captions, 1600px wide.
- `compose.py` — regenerates `store/` from the raw captures. Captions are the
  `compose(...)` calls at the bottom; reword one and re-run `python3 docs/marketplace/compose.py`
  from the repository root.

The raw captures are rendered offscreen from the compiled plugin classes rather than
photographed from a running IDE: `EditorTextField` builds its editor lazily, so the
capture forces `getEditor(true)`, re-lays out, and paints the editor component directly.
Everything shown is the real UI; the snippet text in the editors is demo content, and
the settings path is written in `~` form so no username is published.

Still missing: a recording of a snippet typing itself, which is the one thing a still
cannot show.

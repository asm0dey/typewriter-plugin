# ![](src/main/resources/META-INF/pluginIcon.svg) TypeWriter plugin for JetBrains IDEs

![Build](https://github.com/asm0dey/typewriter-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/20245.svg)](https://plugins.jetbrains.com/plugin/20245)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/20245.svg)](https://plugins.jetbrains.com/plugin/20245)

<!-- Plugin description -->

This plugin implements functionality usually requested by people, who record videos of some code features.

By hitting <kbd>Ctrl</kbd>(<kbd>Cmd</kbd> fpr Mac)+<kbd>T</kbd>,<kbd>W</kbd> you can call a typewriter plugin window, where you can type text. This text will be then autotyped in IDE with a given speed.

Special commands can be included in your text using the template syntax:
- `<pause:1000>` - Pauses typing for 1000 milliseconds
- `<reformat>` - Reformats the code at the current position

You can also assign shortcuts to frequently used snippets:
1. Enter a shortcut in the "Shortcut" field when creating a snippet
2. The snippet will be saved and can be executed directly using the assigned shortcut
3. Manage your snippets by clicking the "Manage Snippets" button in the TypeWriter dialog
4. You can also access all your snippets by pressing <kbd>Ctrl</kbd>(<kbd>Cmd</kbd> for Mac)+<kbd>T</kbd>,<kbd>S</kbd>

<!-- Plugin description end -->

## Installation

- Using IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "typewriter-plugin"</kbd> >
  <kbd>Install Plugin</kbd>

- Manually:

  Download the [latest release](https://github.com/asm0dey/typewriter-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template

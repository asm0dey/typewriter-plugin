<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# typewriter-plugin Changelog

## Unreleased

## 0.1.2 - 2025-04-09

Drops compatibility with IDEA 22, Adds compatibility with IDEA 51

## 0.1.1 - 2023-11-15

### Fixed

- Fixed an issue where deleting a snippet didn't remove its shortcut from the IDE

### Changed

- Made the shortcut input intercept shortcuts and output them instead of being a simple text input
- Prevented immediate execution of snippets when the OK button is clicked

## 0.1.0 - 2025-03-15

### Changed

- Changed the default  shortcut to Ctrl+Shift+T,W: two keys, but does not interfere with native shortcuts

## 0.0.6

### Added

- Adds an icon

## 0.0.5

### Added

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

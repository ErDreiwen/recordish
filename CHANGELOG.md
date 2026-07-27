# Changelog

All notable changes to the unofficial Forge 1.8.9 port are recorded here.
Versions follow [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-07-26

### Changed

- Renamed the Forge port, mod ID, Java packages, resources, JARs, releases,
  documentation, and repository identity to Recordish.
- Added safe compatibility fallbacks for legacy settings, managed FFmpeg,
  watermarks, replay recovery, thumbnails, and FFmpeg environment variables.
- Added an explicit duplicate-JAR guard for old `recordable-*.jar` installs.

## [1.0.0] - 2026-07-26

### Added

- Initial Forge 1.8.9 port of Record-able.
- In-game recording, replay buffering, game-audio and microphone capture.
- Managed FFmpeg setup, diagnostics, recovery, and the record-ish website.

[1.1.0]: https://github.com/ErDreiwen/recordish/compare/0b05278d90d3568011258a9826484ba3abee5363...v1.1.0
[1.0.0]: https://github.com/ErDreiwen/recordish/commit/0b05278d90d3568011258a9826484ba3abee5363

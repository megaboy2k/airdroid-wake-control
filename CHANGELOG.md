# Changelog

All notable changes to this project are documented here.

## [0.10.0] - 2026-09-28

- Preserve an already-active display when screen mirroring starts.
- Keep a previously sleeping OLED panel physically dark while AirDroid starts
  MediaProjection.
- Preserve normal wake and touch behavior for Remote Control.
- Restore both UID-level and package-level `PROJECT_MEDIA` AppOps at boot.
- Declare the recommended System Framework scope.

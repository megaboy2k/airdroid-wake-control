# Changelog

All notable changes to this project are documented here.

## [0.10.1] - 2026-11-19

- Prevent the initial OLED panel flash during mirroring and remote-camera
  startup on Android 10/OxygenOS 10 devices that use integer backlight values.
- Recognize the `WebRTC_Screen` session as Remote Control and preserve its
  normal wake and input behavior.
- Avoid a potential PowerManager deadlock while applying the temporary
  brightness override on older OxygenOS releases.
- Fail open if the device-specific blackout path is unavailable, so Remote
  Control cannot be left in a partially initialized state.

## [0.10.0] - 2026-09-28

- Preserve an already-active display when screen mirroring starts.
- Keep a previously sleeping OLED panel physically dark while AirDroid starts
  MediaProjection.
- Preserve normal wake and touch behavior for Remote Control.
- Restore both UID-level and package-level `PROJECT_MEDIA` AppOps at boot.
- Declare the recommended System Framework scope.

# AirDroid Wake Control

## Why you might need it

When AirDroid starts screen mirroring or the remote camera on an unattended
phone, it normally turns on the phone's physical screen. This module lets the
remote session start while keeping a previously sleeping OLED panel dark. If
the screen was already on, it remains on. AirDroid Remote Control continues to
wake the display and accept unlock gestures and touches normally.

It also restores the Android MediaProjection permission state required by
AirDroid after a reboot, avoiding repeated **Start now** capture prompts on the
tested device.

> **Unofficial project:** AirDroid Wake Control is an independent community
> module. It is not developed, supported, approved, or endorsed by Sand Studio
> (AirDroid), OnePlus, OPPO/OPlus, OxygenOS, Vector, or LSPosed.

> This module hooks private Android and OxygenOS implementation details. It has
> only been tested on a OnePlus 8T running Android 13 / OxygenOS 13.1. A ROM or
> Android update may require new hook mappings.

## What it does

- When mirroring or remote camera starts while the phone is asleep, AirDroid
  receives the logically active display it requires while the OLED backlight is
  forced off.
- When the display was already on, it stays on and its brightness is preserved.
- Remote Control keeps its normal wake, unlock-swipe, and touch behavior.
- At `system_server` startup, both UID-level and package-level
  `PROJECT_MEDIA` AppOps are set to `allow` for `com.sand.airdroid`.

The APK has no launcher activity, background service, network permission, or
root request. It only runs when loaded by an Xposed-compatible framework.

## Compatibility

- Tested: OnePlus 8T, Android 13, OxygenOS 13.1.
- Tested framework: Vector 2.2 (API 102) with Zygisk.
- Module interface: legacy Xposed API.
- Target apps:
  - AirDroid Personal (`com.sand.airdroid`)
  - AirDroid Remote Support Add-on (`com.sand.remotesupportaddon`)
- Required scope: **System Framework only**.

Vector is the tested loader because LSPosed 1.9.2 cannot initialize LSPlant on
the tested OxygenOS build. The module itself does not use a Vector-specific API.

## Installation

1. Install the APK as a normal Android application.
2. Open Vector or a compatible Xposed manager.
3. Enable **AirDroid Wake Control**.
4. Confirm that its only scope is **System Framework** (`system/0` in Vector).
5. Reboot the device.

The APK declares the recommended scope in both modern and legacy metadata.
Framework managers may preselect it, but enabling a module with system scope
still requires user confirmation.

### Vector CLI

For an installation you administer yourself:

```shell
su -c '/data/adb/lspd/cli modules enable io.github.megaboy2k.airdroidwakecontrol'
su -c '/data/adb/lspd/cli scope set io.github.megaboy2k.airdroidwakecontrol system/0'
```

Reboot after changing activation or scope.

## Verification

Check MediaProjection AppOps:

```shell
cmd appops get com.sand.airdroid PROJECT_MEDIA
```

Expected entries include both UID and package modes set to `allow`.

Check module logs:

```shell
su -c 'grep -h AirDroidWakeControl /data/adb/lspd/log/modules_*.log'
```

Expected startup messages:

```text
AirDroidWakeControl: system_server hook installed
AirDroidWakeControl: PROJECT_MEDIA allowed for uid=... package=com.sand.airdroid
```

## Build

Requirements:

- JDK 17
- Android SDK 35

```shell
./gradlew --no-daemon assembleDebug
```

Windows:

```powershell
.\gradlew.bat --no-daemon assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/AirDroid-Wake-Control-0.10.0-debug.apk
```

The included `xposed-api-82.jar` is a compile-only dependency. Xposed API
classes must not be bundled into the APK.

Signed GitHub Releases are created automatically from tags in the
`versionCode-versionName` form, for example `10-0.10.0`. Signing material is
kept only in GitHub Actions Secrets and is never stored in this repository.

## Implementation

The entry point is
`io.github.megaboy2k.airdroidwakecontrol.AirDroidWakeHook`. It rejects every process
except the Android system process and installs these hooks:

- `PowerManagerService.acquireWakeLockInternal`: identifies AirDroid wake locks
  and selects normal wake, blackout, or safe fallback behavior.
- `DisplayPowerState.PhotonicModulator.setState`: forces the tested OLED panel
  off during blackout while the logical display remains active.
- `MediaProjectionManagerService.MediaProjection.start`: returns a previously
  sleeping device to sleep after projection has started.
- `PowerManagerService.systemReady`: restores the required `PROJECT_MEDIA`
  AppOps.

The display implementation is ROM-specific. In particular, this OxygenOS build
clamps brightness `0` to the panel minimum while `-1.0` turns the panel off in
the relevant display path.

## Security and limitations

System Framework scope loads module code into `system_server`. Review the
source and keep tested recovery access before installation. A failed hook is
capable of destabilizing Android or causing a boot loop.

AirDroid is a trademark of Sand Studio. This independent project is not
affiliated with or endorsed by Sand Studio, OnePlus, Vector, or LSPosed.

## Contact

For personal questions that are not suitable for public Issues or Discussions,
contact [megaboy2k](mailto:ksy.megaboy@gmail.com). Please use Issues for bug
reports so that troubleshooting information remains available to other users.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

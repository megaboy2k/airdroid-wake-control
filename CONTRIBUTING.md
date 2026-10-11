# Contributing

Bug reports should include the Android version, ROM build, AirDroid version,
hooking framework and version, module version, selected scope, and the relevant
module log. Remove account identifiers and other private data before posting.

Build the project with JDK 17 and Android SDK 35:

```shell
./gradlew assembleDebug
```

The module hooks non-SDK Android internals. Changes should fail safely when a
class, method, or field is unavailable and must keep the scope as narrow as
possible. Do not package Xposed API classes into the APK.

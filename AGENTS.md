# JLauncher

## Releasing builds
After every change, build the phone build (`./gradlew assembleFast`) and copy
`app/build/outputs/apk/fast/app-fast.apk` to `JLauncher/JLauncher-debug.apk` in
Google Drive (My Drive), replacing the old one. I install builds on my phone from there.

- The `fast` build is the debug app (same package and key, so it installs over it and keeps
  its data) but optimised and not debuggable, so it runs much faster. Use `assembleDebug` for
  the emulator when you need `run-as` to load test data.
- Windows PC: `G:\My Drive\JLauncher\JLauncher-debug.apk`. WSL can't see `G:`, so copy
  with PowerShell.
- Make sure the APK was built after the last source edit.
- Say in your reply that the new build is on Drive.

## Releasing the Mac app
The Mac menu-bar app (`mac/`) only builds on macOS. After any change under `mac/`, commit and push,
then have an agent on the linked Mac ("MacBook Pro von James", through `delegate_task` with its
`environmentId`) pull and run `./mac/build.sh --open` in its clone, fixing any compile errors there
and pushing the fixes. Don't ask me to run it by hand. Say in your reply whether the Mac app was
rebuilt.

## Tests
Unit tests for the logic that doesn't need a phone (reading purchases, the weekly limit's
carry-over, work days, Compare's grouping, finding places from locations, lockdown's schedules, timers, limits and stages) live in `app/src/test`. Run them with
`./gradlew testDebugUnitTest` before releasing a build.

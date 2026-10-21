# JLauncher

## Releasing builds
After every change, build the debug APK (`./gradlew assembleDebug`) and copy
`app/build/outputs/apk/debug/app-debug.apk` to `JLauncher/JLauncher-debug.apk` in
Google Drive (My Drive), replacing the old one. I install builds on my phone from there.

- Windows PC: `G:\My Drive\JLauncher\JLauncher-debug.apk`. WSL can't see `G:`, so copy
  with PowerShell.
- Make sure the APK was built after the last source edit.
- Say in your reply that the new build is on Drive.

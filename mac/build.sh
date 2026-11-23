#!/bin/zsh
# Builds "JLauncher Lockdown.app" into ~/Applications. Run from anywhere: ./mac/build.sh
set -e
# Resolved before the cd, since $0 may be relative
HERE="${0:A:h}"
cd "$HERE"
swift run -q LockdownChecks
swift build -c release --product JLauncherMac
APP="$HOME/Applications/JLauncher Lockdown.app"
# A running copy is replaced
pkill -x JLauncherMac 2>/dev/null || true
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/JLauncherMac "$APP/Contents/MacOS/JLauncherMac"
# Where it was built from and which version, for the app's Update item
REPO="${HERE:h}"
COMMIT=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>com.popjam.jlauncher.mac</string>
  <key>CFBundleName</key><string>JLauncher Lockdown</string>
  <key>CFBundleDisplayName</key><string>JLauncher Lockdown</string>
  <key>CFBundleExecutable</key><string>JLauncherMac</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>JLauncherRepo</key><string>$REPO</string>
  <key>JLauncherCommit</key><string>$COMMIT</string>
</dict></plist>
PLIST
# Signed with "JLauncher Dev" if that certificate exists (Accessibility then survives rebuilds), else ad hoc
IDENTITY="-"
security find-identity -p codesigning 2>/dev/null | grep -q "JLauncher Dev" && IDENTITY="JLauncher Dev"
codesign --force --sign "$IDENTITY" --identifier com.popjam.jlauncher.mac "$APP"
echo "Built $APP (signed: $IDENTITY)"
[[ "$1" == "--open" ]] && open "$APP"
true

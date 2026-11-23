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
# Signed with the same certificate every time, so Accessibility (needed for websites) survives
# rebuilds and updates. An ad hoc signature changes with every build, and macOS then quietly stops
# trusting the app. The certificate is made here once, self-signed, in a keychain of its own; its
# password only guards this signing key.
KEYCHAIN="$HOME/Library/Keychains/jlauncher-dev.keychain-db"
KEYCHAIN_PW="jlauncher-dev"
CERT="JLauncher Dev"
signing_id() { security find-identity -p codesigning "$KEYCHAIN" 2>/dev/null | awk -v n="\"$CERT\"" 'index($0, n) { print $2; exit }' }
make_cert() {
  local tmp=$(mktemp -d)
  cat > "$tmp/cert.cnf" <<CNF
[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=$CERT
[ext]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
CNF
  # macOS's own openssl, whose .p12 files the keychain can read
  /usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 7300 -config "$tmp/cert.cnf" \
    -keyout "$tmp/key.pem" -out "$tmp/cert.pem" 2>/dev/null &&
  /usr/bin/openssl pkcs12 -export -inkey "$tmp/key.pem" -in "$tmp/cert.pem" -out "$tmp/id.p12" -passout pass:"$KEYCHAIN_PW" &&
  { [[ -f "$KEYCHAIN" ]] || security create-keychain -p "$KEYCHAIN_PW" "$KEYCHAIN" } &&
  security unlock-keychain -p "$KEYCHAIN_PW" "$KEYCHAIN" &&
  security import "$tmp/id.p12" -k "$KEYCHAIN" -P "$KEYCHAIN_PW" -T /usr/bin/codesign >/dev/null &&
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PW" "$KEYCHAIN" >/dev/null
  local ok=$?
  rm -rf "$tmp"
  return $ok
}
IDENTITY="-"
ID=$(signing_id)
[[ -z "$ID" ]] && { make_cert || true; ID=$(signing_id) }
if [[ -n "$ID" ]]; then
  security unlock-keychain -p "$KEYCHAIN_PW" "$KEYCHAIN"
  security set-keychain-settings "$KEYCHAIN" # stays unlocked until logout
  # codesign only finds keys in the search list: added for the signing, then the list is put back
  KEYCHAINS=("${(@f)$(security list-keychains -d user | sed -e 's/^ *"//' -e 's/"$//')}")
  security list-keychains -d user -s "${KEYCHAINS[@]}" "$KEYCHAIN"
  codesign --force --sign "$ID" --identifier com.popjam.jlauncher.mac "$APP" && IDENTITY="$CERT" || true
  security list-keychains -d user -s "${KEYCHAINS[@]}"
fi
[[ "$IDENTITY" == "-" ]] && codesign --force --sign - --identifier com.popjam.jlauncher.mac "$APP"
echo "Built $APP (signed: $IDENTITY)"
[[ "$IDENTITY" == "-" ]] && echo "Warning: signed ad hoc, so Accessibility must be allowed again after every build"
[[ "$1" == "--open" ]] && open "$APP"
true

#!/bin/bash
# Installs JLauncher Vibecoding on a Mac: copies vibecoding.py into Application Support and has launchd
# run it every 15 minutes, writing to "My Drive/JLauncher" in Google Drive (found by itself, or pass
# the "My Drive" folder). Needs python3. Remove with: ./install-mac.sh --remove
set -e

label="com.popjam.jlauncher.vibecoding"
plist="$HOME/Library/LaunchAgents/$label.plist"
dest="$HOME/Library/Application Support/JLauncher Vibecoding"

if [ "$1" = "--remove" ]; then
    launchctl bootout "gui/$(id -u)" "$plist" 2>/dev/null || true
    rm -f "$plist"
    rm -rf "$dest"
    echo "Removed."
    exit 0
fi

python=$(command -v python3 || true)
if [ -z "$python" ] || ! "$python" -c 'import json' 2>/dev/null; then
    echo "python3 isn't available. Install the Command Line Tools with: xcode-select --install"
    exit 1
fi

here="$(cd "$(dirname "$0")" && pwd)"
drive="${1:-}"
if [ -z "$drive" ]; then
    for candidate in "$HOME"/Library/CloudStorage/GoogleDrive-*/"My Drive"; do
        if [ -d "$candidate" ]; then
            drive="$candidate"
            break
        fi
    done
fi
if [ ! -d "$drive" ]; then
    echo "Couldn't find Google Drive's My Drive folder. Run: $0 \"/path/to/My Drive\""
    exit 1
fi
mkdir -p "$drive/JLauncher" "$dest"
cp "$here/vibecoding.py" "$dest/vibecoding.py"

mkdir -p "$HOME/Library/LaunchAgents"
cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$label</string>
    <key>ProgramArguments</key>
    <array>
        <string>$python</string>
        <string>$dest/vibecoding.py</string>
        <string>--device</string>
        <string>Mac</string>
        <string>--out</string>
        <string>$drive/JLauncher/JLauncher Vibecoding Mac.json</string>
        <string>--cache</string>
        <string>$dest/cache.json</string>
    </array>
    <key>StartInterval</key>
    <integer>900</integer>
    <key>RunAtLoad</key>
    <true/>
    <key>ProcessType</key>
    <string>Background</string>
    <key>LowPriorityIO</key>
    <true/>
    <key>Nice</key>
    <integer>10</integer>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)" "$plist" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$plist"
echo "Installed. Writing to: $drive/JLauncher/JLauncher Vibecoding Mac.json"

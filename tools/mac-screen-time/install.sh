#!/bin/bash
# Installs JLauncher Mac screen time: copies screen-time.sh into Application Support and has launchd
# run it once a minute. Writes to "My Drive/JLauncher" in Google Drive, found by itself, or pass the
# "My Drive" folder:  ./install.sh "/path/to/My Drive"
set -e

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

out="$drive/JLauncher/JLauncher Mac screen time.json"
mkdir -p "$drive/JLauncher"

dest="$HOME/Library/Application Support/JLauncher Screen Time"
mkdir -p "$dest"
/usr/bin/sed "s|__OUT__|$out|" "$here/screen-time.sh" > "$dest/screen-time.sh"
chmod +x "$dest/screen-time.sh"

label="com.popjam.jlauncher.screentime"
plist="$HOME/Library/LaunchAgents/$label.plist"
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
        <string>/bin/bash</string>
        <string>$dest/screen-time.sh</string>
    </array>
    <key>StartInterval</key>
    <integer>60</integer>
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
echo "Installed. Writing to: $out"

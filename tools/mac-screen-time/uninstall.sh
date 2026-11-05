#!/bin/bash
# Stops and removes JLauncher Mac screen time. The file in Google Drive and the minute log are left.
label="com.popjam.jlauncher.screentime"
plist="$HOME/Library/LaunchAgents/$label.plist"
launchctl bootout "gui/$(id -u)" "$plist" 2>/dev/null || true
rm -f "$plist" "$HOME/Library/Application Support/JLauncher Screen Time/screen-time.sh"
echo "Removed. The log is still in ~/Library/Application Support/JLauncher Screen Time"

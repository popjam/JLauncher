#!/bin/zsh
# Updates "JLauncher Lockdown.app" to the latest version: pulls the repo, and if it has changed since
# the app was built, builds and reopens it (build.sh replaces the running copy). Run by the app's
# Update menu item, or by hand: ./mac/update.sh
# Exits 10 when it's already up to date.
set -e
cd "${0:A:h:h}"
APP="$HOME/Applications/JLauncher Lockdown.app"
echo "== $(date) =="
git pull --ff-only
HEAD=$(git rev-parse --short HEAD)
BUILT=$(/usr/libexec/PlistBuddy -c "Print :JLauncherCommit" "$APP/Contents/Info.plist" 2>/dev/null || true)
if [[ "$HEAD" == "$BUILT" && "$1" != "--force" ]]; then
  echo "Already up to date ($HEAD)"
  exit 10
fi
./mac/build.sh --open

#!/bin/bash
# Sets up tv-time.py for a Samsung The Frame: the library that talks to it, in its own environment.
# Then, to follow lockdown, save the phone's database and pairing code (Lockdown → Devices → Mac):
#   echo '{"url": "https://….firebasedatabase.app", "key": "…"}' > ~/.local/share/jlauncher-tv/lockdown.json
set -e
DIR=~/.local/share/jlauncher-tv
mkdir -p "$DIR"
python3 -m venv "$DIR/venv"
"$DIR/venv/bin/pip" install -q samsungtvws websocket-client
echo "Ready. The first check of Art Mode shows an Allow prompt on the TV: accept it with the remote."

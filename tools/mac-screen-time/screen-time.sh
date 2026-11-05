#!/bin/bash
# JLauncher Mac screen time. launchd runs this once a minute; it takes a few milliseconds.
#
# A minute counts when the keyboard, mouse or trackpad was used in the last IDLE_LIMIT seconds, so
# the Mac sitting awake under caffeinate with nobody at it isn't counted. Each counted minute is
# logged with the app in front. Every 15 minutes the last DAYS days are summed up into a JSON file
# in Google Drive, which JLauncher on the phone reads from its backup folder.

OUT="__OUT__" # Set by install.sh
DIR="$HOME/Library/Application Support/JLauncher Screen Time"
LOG="$DIR/minutes.log"
IDLE_LIMIT=120
DAYS=60

mkdir -p "$DIR"

# Seconds since the last keyboard, mouse or trackpad input
idle=$(/usr/sbin/ioreg -c IOHIDSystem -d 4 | /usr/bin/awk '/HIDIdleTime/ { print int($NF / 1000000000); exit }')
now=$(/bin/date +%Y-%m-%dT%H:%M)

if [ -n "$idle" ] && [ "$idle" -lt "$IDLE_LIMIT" ]; then
    app=$(/usr/bin/lsappinfo info -only name "$(/usr/bin/lsappinfo front)" 2>/dev/null |
        /usr/bin/sed -n 's/.*"LSDisplayName"="\(.*\)".*/\1/p')
    printf '%s\t%s\n' "$now" "${app:-Unknown}" >> "$LOG"
fi

minute=$(/bin/date +%M)
if [ $((10#$minute % 15)) -ne 0 ] && [ -f "$OUT" ]; then
    exit 0
fi
[ -f "$LOG" ] || exit 0

# Keeps the last DAYS days of the log
cutoff=$(/bin/date -v-${DAYS}d +%Y-%m-%d)
/usr/bin/awk -F'\t' -v cutoff="$cutoff" 'substr($1, 1, 10) >= cutoff' "$LOG" > "$LOG.tmp" && /bin/mv "$LOG.tmp" "$LOG"

# Per day: minutes used, first and last minute, and minutes per app. A minute logged twice counts once.
/usr/bin/awk -F'\t' -v updated="$(/bin/date +%s)000" '
function esc(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return s }
!seen[$1]++ {
    d = substr($1, 1, 10); t = substr($1, 12, 5)
    minutes[d]++
    if (!(d in first) || t < first[d]) first[d] = t
    if (t > last[d]) last[d] = t
    apps[d SUBSEP $2]++
}
END {
    printf "{\"device\":\"Mac\",\"updated\":%s,\"days\":{", updated
    n = 0
    for (d in minutes) {
        printf "%s\"%s\":{\"minutes\":%d,\"first\":\"%s\",\"last\":\"%s\",\"apps\":{", (n++ ? "," : ""), d, minutes[d], first[d], last[d]
        m = 0
        for (k in apps) {
            split(k, parts, SUBSEP)
            if (parts[1] == d) printf "%s\"%s\":%d", (m++ ? "," : ""), esc(parts[2]), apps[k]
        }
        printf "}}"
    }
    printf "}}\n"
}' "$LOG" > "$DIR/summary.tmp" && /bin/mv "$DIR/summary.tmp" "$OUT"

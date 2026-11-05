#!/usr/bin/env python3
"""
JLauncher TV time: once a minute, asks the Android TV over the network (adb, with Network debugging on)
whether its screen is on and what's in front, and logs the minute if it is. The screensaver or
ambient mode doesn't count. Every 15 minutes the last DAYS days are summed up into the same JSON
shape as Mac screen time (minutes, first, last, apps), for JLauncher on the phone.

    tv-time.py --tv 192.168.4.20 --out FILE
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import time

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
DIR = os.path.expanduser("~/.local/share/jlauncher-tv")
LOG = os.path.join(DIR, "minutes.log")
DAYS = 60

# Package to the name shown on the phone; anything else shows as its package
APPS = {
    "com.google.android.youtube.tv": "YouTube",
    "com.google.android.youtube.tvkids": "YouTube Kids",
    "com.netflix.ninja": "Netflix",
    "com.disney.disneyplus": "Disney+",
    "com.amazon.amazonvideo.livingroom": "Prime Video",
    "au.com.stan.and": "Stan",
    "au.com.stan.androidtv": "Stan",
    "com.foxtel.binge": "Binge",
    "au.com.streamotion.ares.tv": "Kayo",
    "au.net.abc.iview": "ABC iview",
    "com.sbs.ondemand.tv": "SBS On Demand",
    "au.com.nine.jumpin.tv": "9Now",
    "au.com.tenplay.tv": "10 Play",
    "com.seven.plus7": "7plus",
    "com.plexapp.android": "Plex",
    "org.jellyfin.androidtv": "Jellyfin",
    "org.xbmc.kodi": "Kodi",
    "com.spotify.tv.android": "Spotify",
    "tv.twitch.android.app": "Twitch",
    "tv.twitch.android.viewer": "Twitch",
    "com.apple.atve.androidtv.appletv": "Apple TV",
    "com.hbo.hbonow": "Max",
    "com.google.android.apps.tv.launcherx": "Home screen",
    "com.spocky.projengmenu": "Home screen",
    "com.google.android.tvlauncher": "Home screen",
    "com.android.tv": "TV inputs",
    "com.android.tv.settings": "Settings",
    "com.google.android.tv": "TV inputs",
}


def adb(*args, timeout=10):
    try:
        return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout).stdout
    except (subprocess.TimeoutExpired, OSError):
        return ""


def check(serial):
    """The app in front if the screen is on (not asleep, the screensaver or ambient mode), else None."""
    if serial not in adb("devices"):
        adb("connect", serial, timeout=8)
    out = adb("-s", serial, "shell", "dumpsys power | grep -m1 mWakefulness=; dumpsys window | grep -m1 mCurrentFocus")
    if "mWakefulness=Awake" not in out:
        return None
    match = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([\w.]+)/", out)
    package = match.group(1) if match else ""
    return APPS.get(package, package or "Unknown")


def summarise(out_path):
    cutoff = (dt.date.today() - dt.timedelta(days=DAYS)).isoformat()
    seen, days = set(), {}
    lines = []
    if os.path.exists(LOG):
        with open(LOG, encoding="utf-8") as f:
            lines = [l.rstrip("\n").split("\t", 1) for l in f if l[:10] >= cutoff and "\t" in l]
    with open(LOG + ".tmp", "w", encoding="utf-8") as f:
        f.writelines("%s\t%s\n" % (stamp, app) for stamp, app in lines)
    os.replace(LOG + ".tmp", LOG)
    for stamp, app in lines:
        if stamp in seen:
            continue
        seen.add(stamp)
        date, clock = stamp[:10], stamp[11:16]
        day = days.setdefault(date, {"minutes": 0, "first": clock, "last": clock, "apps": {}})
        day["minutes"] += 1
        day["first"] = min(day["first"], clock)
        day["last"] = max(day["last"], clock)
        day["apps"][app] = day["apps"].get(app, 0) + 1
    out = {"device": "TV", "updated": int(time.time() * 1000), "days": days}
    with open(out_path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    os.replace(out_path + ".tmp", out_path)


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--tv", required=True)
    args.add_argument("--out", required=True)
    opts = args.parse_args()
    os.makedirs(DIR, exist_ok=True)
    serial = opts.tv if ":" in opts.tv else opts.tv + ":5555"
    app = check(serial)
    if app is not None:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write("%s\t%s\n" % (dt.datetime.now().strftime("%Y-%m-%dT%H:%M"), app))
    if dt.datetime.now().minute % 15 == 0 or not os.path.exists(opts.out):
        summarise(opts.out)


if __name__ == "__main__":
    main()

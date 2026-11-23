#!/usr/bin/env python3
"""
JLauncher TV time: once a minute, asks the Android TV over the network (adb, with Network debugging on)
whether its screen is on and what's in front, and logs the minute if it is. The screensaver or
ambient mode doesn't count. Every 5 minutes the last DAYS days are summed up into the same JSON
shape as Mac screen time (minutes, first, last, apps, and spans: when it was on, as minutes from
midnight "start-end", the end not included), for JLauncher on the phone.

With --frame, the Android TV is a box plugged into a Samsung The Frame, and the Frame says whether
the screen is really on: a minute counts only while the Frame is on and not in Art Mode. The app is
the Frame's own (YouTube, Netflix… on the TV itself) when one is in front, otherwise the box's.

With lockdown paired (lockdown.json in DIR: {"url": database, "key": pairing code}), it also follows
the phone's lockdown: while the phone says the TV is blocking, blocked apps are closed, on the box
through adb and on the Frame through its own API, checked every 15 seconds. The TV's apps are sent
to the phone once a day, so they can be chosen there.

    tv-time.py --tv 192.168.4.20 --frame 192.168.4.33 --out FILE
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import time
import urllib.request

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
DIR = os.path.expanduser("~/.local/share/jlauncher-tv")
LOG = os.path.join(DIR, "minutes.log")
DAYS = 60
# Its own adb server port, so it doesn't take 5037 from Windows' adb (WSL forwards its localhost ports
# to Windows, and Windows' adb would then use this one, which can't see emulators running on Windows)
ADB_ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT="5038")

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
    "org.smarttube.stable": "SmartTube",
    "org.smarttube.beta": "SmartTube",
    "com.liskovsoft.smarttubetv": "SmartTube",
    "com.liskovsoft.smarttubetv.beta": "SmartTube",
    "com.google.android.tvlauncher": "Home screen",
    "com.android.tv": "TV inputs",
    "com.android.tv.settings": "Settings",
    "com.google.android.tv": "TV inputs",
}


def adb(*args, timeout=10):
    try:
        return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout, env=ADB_ENV).stdout
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
    if not match:
        # On but with nothing readable in front (mid-transition, or while something else is mirroring the
        # screen): most likely still the app from a minute ago
        return last_app() or "Unknown"
    return APPS.get(match.group(1), match.group(1))


# --- The Frame ---

# The Frame's own apps, by their Tizen app id; any not installed just aren't found
FRAME_APPS = {
    "111299001912": "YouTube",
    "3201907018807": "Netflix",
    "11101200001": "Netflix",
    "3201910019365": "Prime Video",
    "3201901017640": "Disney+",
    "3201807016597": "Apple TV",
    "3201606009684": "Spotify",
    "3201512006963": "Plex",
    "3201601007230": "Stan",
    "3201608010385": "ABC iview",
    "3201601007492": "SBS On Demand",
    "3201601007625": "9Now",
    "3201601007627": "10 Play",
    "3201601007628": "7plus",
    "3202003020365": "Binge",
    "3201911019531": "Kayo",
    "3201601007494": "Twitch",
    "3201601007250": "Max",
}

ART_RETRY_S = 30 * 60
TOKEN = os.path.join(DIR, "frame-token.txt")
ART_FAILED = os.path.join(DIR, "frame-art-failed")


def frame_get(ip, path, method="GET", timeout=3):
    """The Frame's REST API (no pairing needed); the parsed answer, or None"""
    try:
        request = urllib.request.Request("http://%s:8001/api/v2/%s" % (ip, path), method=method)
        with urllib.request.urlopen(request, timeout=timeout) as r:
            return json.loads(r.read().decode() or "{}")
    except Exception:
        return None


def frame_art(ip):
    """Whether the Frame is in Art Mode: True, False, or None when it can't be told (not paired yet).
    The first time, the TV asks to allow JLauncher; after a refusal or no answer it's asked again
    half an hour later, not every minute."""
    if os.path.exists(ART_FAILED) and time.time() - os.path.getmtime(ART_FAILED) < ART_RETRY_S:
        return None
    try:
        from samsungtvws import SamsungTVWS
        tv = SamsungTVWS(host=ip, port=8002, token_file=TOKEN, timeout=8, name="JLauncher")
        value = tv.art().get_artmode()
        tv.close()
        if os.path.exists(ART_FAILED):
            os.remove(ART_FAILED)
        return value == "on"
    except Exception:
        open(ART_FAILED, "w").close()
        return None


def frame_front(ip):
    """The Frame's own app in front: (app id, name), or None"""
    for app_id, name in FRAME_APPS.items():
        status = frame_get(ip, "applications/" + app_id, timeout=2)
        if status and status.get("visible"):
            return app_id, name
    return None


def frame_state(ip):
    """'off', 'art' or 'on'"""
    info = frame_get(ip, "")
    if not info or info.get("device", {}).get("PowerState", "on") != "on":
        return "off"
    return "art" if frame_art(ip) else "on"


def box_front(serial):
    """The box's package in front if it's awake, else None"""
    if serial not in adb("devices"):
        adb("connect", serial, timeout=8)
    out = adb("-s", serial, "shell", "dumpsys power | grep -m1 mWakefulness=; dumpsys window | grep -m1 mCurrentFocus")
    if "mWakefulness=Awake" not in out:
        return None
    match = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([\w.]+)/", out)
    return match.group(1) if match else ""


def check_frame(serial, ip):
    """With the Frame: the app in front if the screen is really on, else None"""
    state = frame_state(ip)
    if state != "on":
        return None
    front = frame_front(ip)
    if front:
        return front[1]
    pkg = box_front(serial)
    if pkg:
        return APPS.get(pkg, pkg)
    if pkg == "":
        return last_app() or "Unknown"
    # The box is asleep but the Frame is on: live TV or another input
    return "TV"


# --- Lockdown ---

LOCKDOWN = os.path.join(DIR, "lockdown.json")


def key(name):
    return "".join(c for c in name.lower() if c.isalnum())


# Apps that are another app underneath, so blocking that one blocks them too
SAME_AS = {"smarttube": "youtube", "youtubekids": "youtube"}


def is_blocked(name, blocked):
    k = key(name)
    return k in blocked or SAME_AS.get(k) in blocked


def lockdown_config():
    try:
        with open(LOCKDOWN, encoding="utf-8") as f:
            c = json.load(f)
        return c["url"].rstrip("/"), c["key"]
    except Exception:
        return None


def db(config, path, method="GET", body=None):
    url, k = config
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request("%s/lockdown/%s/%s.json" % (url, k, path), data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=8) as r:
        return json.loads(r.read().decode() or "null")


def blocked_apps(config):
    """The TV apps lockdown blocks now (names made plain), or an empty set when it isn't blocking"""
    try:
        tv = (db(config, "phone") or {}).get("tv") or {}
    except Exception:
        return set()
    if not tv.get("blocking"):
        return set()
    return {key(n) for n in tv.get("apps") or []}


def enforce(serial, ip, blocked):
    """Closes a blocked app in front: on the Frame, or on the box (then home)"""
    if not blocked:
        return
    if ip:
        front = frame_front(ip)
        if front and is_blocked(front[1], blocked):
            frame_get(ip, "applications/" + front[0], method="DELETE")
    pkg = box_front(serial)
    if pkg and is_blocked(APPS.get(pkg, pkg), blocked):
        adb("-s", serial, "shell", "am force-stop %s; input keyevent KEYCODE_HOME" % pkg)


def send_apps(config, serial, ip):
    """Once a day: the TV's apps, by name, for choosing on the phone"""
    stamp = os.path.join(DIR, "apps-sent")
    if os.path.exists(stamp) and time.time() - os.path.getmtime(stamp) < 24 * 3600:
        return
    names = set()
    if ip:
        for app_id, name in FRAME_APPS.items():
            if frame_get(ip, "applications/" + app_id, timeout=2):
                names.add(name)
    packages = re.findall(r"package:([\w.]+)", adb("-s", serial, "shell", "pm list packages"))
    names.update(APPS[p] for p in packages if p in APPS and APPS[p] not in ("Home screen", "Settings", "TV inputs"))
    try:
        db(config, "tv", "PATCH", {"installed": sorted(names), "seen": int(time.time() * 1000)})
        open(stamp, "w").close()
    except Exception:
        pass


def last_app():
    """The app logged in the last 2 minutes, if any."""
    if not os.path.exists(LOG):
        return None
    with open(LOG, "rb") as f:
        f.seek(max(0, os.path.getsize(LOG) - 200))
        lines = f.read().decode("utf-8", "ignore").splitlines()
    if not lines or "\t" not in lines[-1]:
        return None
    stamp, app = lines[-1].split("\t", 1)
    try:
        ago = dt.datetime.now() - dt.datetime.strptime(stamp, "%Y-%m-%dT%H:%M")
    except ValueError:
        return None
    return app if ago <= dt.timedelta(minutes=2) and app != "Unknown" else None


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
        day = days.setdefault(date, {"minutes": 0, "first": clock, "last": clock, "apps": {}, "_spans": []})
        day["minutes"] += 1
        day["first"] = min(day["first"], clock)
        day["last"] = max(day["last"], clock)
        day["apps"][app] = day["apps"].get(app, 0) + 1
        minute = int(clock[:2]) * 60 + int(clock[3:5])
        spans = day["_spans"]
        if spans and spans[-1][1] == minute:
            spans[-1][1] = minute + 1
        else:
            spans.append([minute, minute + 1])
    for day in days.values():
        day["spans"] = ",".join("%d-%d" % (a, b) for a, b in day.pop("_spans"))
    out = {"device": "TV", "updated": int(time.time() * 1000), "days": days}
    with open(out_path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    os.replace(out_path + ".tmp", out_path)


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--tv", required=True)
    args.add_argument("--frame")
    args.add_argument("--out", required=True)
    opts = args.parse_args()
    os.makedirs(DIR, exist_ok=True)
    serial = opts.tv if ":" in opts.tv else opts.tv + ":5555"
    app = check_frame(serial, opts.frame) if opts.frame else check(serial)
    if app is not None:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write("%s\t%s\n" % (dt.datetime.now().strftime("%Y-%m-%dT%H:%M"), app))
    if dt.datetime.now().minute % 5 == 0 or not os.path.exists(opts.out):
        summarise(opts.out)
    config = lockdown_config()
    if config:
        send_apps(config, serial, opts.frame)
        # Within the minute this run has: now, then every 15 seconds while something's blocked
        started = time.time()
        while True:
            blocked = blocked_apps(config)
            enforce(serial, opts.frame, blocked)
            if not blocked or time.time() - started > 40:
                break
            time.sleep(15)


if __name__ == "__main__":
    main()

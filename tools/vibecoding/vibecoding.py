#!/usr/bin/env python3
"""
JLauncher Vibecoding: your time prompting coding agents, per day and per project, from Claude Code's
and Codex's session files (which T3 Code threads are saved as). Only times, project names and counts
are written; nothing said in the sessions is.

Your time is worked out from the prompts you sent: each one counts from PROMPT_BEFORE before it
(writing it) to PROMPT_AFTER after (reading what comes back), and the whole gap to your next prompt
counts if that's within JOIN. Prompts in threads running side by side aren't counted twice.

Run every 15 minutes by the PC's scheduled task or the Mac's launchd job. Files already read are
remembered (by size and time changed) in a cache next to the output, so a run only reads new ones.

    vibecoding.py --device PC --out FILE [--claude DIR] [--codex DIR] [--rates FILE]
"""
import argparse
import datetime as dt
import json
import os
import re
import sys
import time

PROMPT_BEFORE = 3 * 60
PROMPT_AFTER = 5 * 60
JOIN = 15 * 60
DAYS = 60
CACHE_VERSION = 1


def parse_time(stamp):
    try:
        return dt.datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp()
    except (AttributeError, ValueError):
        return None


def project_name(cwd):
    """The project folder's name; T3 Code's scratch threads all count as Scratch."""
    if not cwd:
        return "Other"
    parts = [p for p in re.split(r"[\\/]+", cwd) if p]
    if any(p.lower() == "scratch" for p in parts) and ".t3" in [p.lower() for p in parts]:
        return "Scratch"
    return parts[-1] if parts else "Other"


def is_prompt_text(text):
    """Typed by you, not a command's output, a reminder or a resumed summary."""
    text = text.lstrip()
    return bool(text) and not text.startswith(("<local-command", "<command-", "<system-reminder", "<task-notification", "Caveat:"))


def read_claude(path):
    """Prompts as (time, project), and spend as (time, model, usage) per message."""
    prompts, spend, seen = [], [], set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            kind = entry.get("type")
            message = entry.get("message")
            if not isinstance(message, dict) or entry.get("isSidechain"):
                continue
            when = parse_time(entry.get("timestamp"))
            if when is None:
                continue
            if kind == "user" and not entry.get("isMeta") and not entry.get("isCompactSummary"):
                content = message.get("content")
                if isinstance(content, list):
                    if any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content):
                        continue
                    content = " ".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
                if isinstance(content, str) and is_prompt_text(content):
                    prompts.append((when, project_name(entry.get("cwd"))))
            elif kind == "assistant":
                # One reply is split over several entries, each with the same id and usage
                key = message.get("id")
                if key in seen:
                    continue
                seen.add(key)
                usage = message.get("usage")
                if isinstance(usage, dict):
                    spend.append((when, message.get("model", ""), {
                        k: usage.get(k) or 0 for k in
                        ("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
                    }))
    return prompts, spend


def read_codex(path):
    prompts, cwd = [], None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            payload = entry.get("payload") or {}
            if entry.get("type") == "session_meta":
                cwd = payload.get("cwd")
            elif entry.get("type") == "turn_context":
                cwd = payload.get("cwd") or cwd
            elif entry.get("type") == "event_msg" and payload.get("type") == "user_message":
                when = parse_time(entry.get("timestamp"))
                if when is not None and is_prompt_text(payload.get("message", "")):
                    prompts.append((when, project_name(cwd)))
    return prompts, []


def session_files(root, read):
    if not root or not os.path.isdir(root):
        return
    for folder, _, names in os.walk(root):
        for name in names:
            if name.endswith(".jsonl"):
                yield os.path.join(folder, name), read


def load_rates(path):
    if not path or not os.path.isfile(path):
        return {}
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        return data.get("document", data)
    except (OSError, ValueError):
        return {}


def cost(rates, model, usage):
    rate = rates.get(model) or rates.get(model.split("[")[0]) or {}
    return (usage["input_tokens"] * (rate.get("input_cost_per_token") or 0)
            + usage["output_tokens"] * (rate.get("output_cost_per_token") or 0)
            + usage["cache_read_input_tokens"] * (rate.get("cache_read_input_token_cost") or 0)
            + usage["cache_creation_input_tokens"] * (rate.get("cache_creation_input_token_cost") or 0))


def stretches(times):
    """Your time around sorted prompt times, as (start, end) stretches that don't overlap."""
    out = []
    for i, t in enumerate(times):
        end = t + PROMPT_AFTER
        if i + 1 < len(times) and times[i + 1] - t <= JOIN:
            end = max(end, times[i + 1])
        start = t - PROMPT_BEFORE
        if out and start <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], end))
        else:
            out.append((start, end))
    return out


def split_by_day(spans):
    """Seconds per local day, and per local hour of that day."""
    days = {}
    for start, end in spans:
        t = start
        while t < end:
            local = dt.datetime.fromtimestamp(t)
            next_hour = (local.replace(minute=0, second=0, microsecond=0) + dt.timedelta(hours=1)).timestamp()
            step = min(end, next_hour) - t
            day = days.setdefault(local.date().isoformat(), {"seconds": 0, "hours": [0] * 24})
            day["seconds"] += step
            day["hours"][local.hour] += step
            t += step
    return days


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--device", required=True)
    args.add_argument("--out", required=True)
    args.add_argument("--claude", default=os.path.expanduser("~/.claude/projects"))
    args.add_argument("--codex", default=os.path.expanduser("~/.codex/sessions"))
    args.add_argument("--rates", default=os.path.expanduser("~/.t3/userdata/usage-model-rates.json"))
    args.add_argument("--cache", default=None)
    opts = args.parse_args()

    cache_path = opts.cache or os.path.join(os.path.dirname(os.path.abspath(opts.out)), ".vibecoding-cache-%s.json" % opts.device)
    try:
        with open(cache_path, encoding="utf-8") as f:
            cache = json.load(f)
        if cache.get("version") != CACHE_VERSION:
            cache = {}
    except (OSError, ValueError):
        cache = {}
    files = cache.get("files", {})

    rates = load_rates(opts.rates)
    cutoff = time.time() - DAYS * 86400
    kept = {}
    for path, read in list(session_files(opts.claude, read_claude)) + list(session_files(opts.codex, read_codex)):
        try:
            stat = os.stat(path)
        except OSError:
            continue
        if stat.st_mtime < cutoff:
            continue
        key = "%d:%d" % (stat.st_size, int(stat.st_mtime))
        old = files.get(path)
        if old and old.get("key") == key:
            kept[path] = old
            continue
        try:
            prompts, spend = read(path)
        except OSError:
            continue
        kept[path] = {
            "key": key,
            "prompts": prompts,
            "spend": [(when, sum(usage.values()), cost(rates, model, usage)) for when, model, usage in spend],
        }

    prompts = sorted(p for f in kept.values() for p in f["prompts"] if p[0] >= cutoff)
    spend = [s for f in kept.values() for s in f["spend"] if s[0] >= cutoff]

    days = {}
    for date, day in split_by_day(stretches([t for t, _ in prompts])).items():
        days[date] = {"minutes": round(day["seconds"] / 60), "hours": [round(s / 60) for s in day["hours"]],
                      "prompts": 0, "tokens": 0, "cost": 0.0, "projects": {}}
    for project in sorted({p for _, p in prompts}):
        for date, day in split_by_day(stretches([t for t, p in prompts if p == project])).items():
            if date in days:
                days[date]["projects"].setdefault(project, {"minutes": 0, "prompts": 0})["minutes"] = round(day["seconds"] / 60)
    for when, project in prompts:
        date = dt.datetime.fromtimestamp(when).date().isoformat()
        if date in days:
            days[date]["prompts"] += 1
            days[date]["projects"].setdefault(project, {"minutes": 0, "prompts": 0})["prompts"] += 1
            stamp = dt.datetime.fromtimestamp(when).strftime("%H:%M")
            days[date]["first"] = min(days[date].get("first", stamp), stamp)
            days[date]["last"] = max(days[date].get("last", stamp), stamp)
    for when, tokens, dollars in spend:
        date = dt.datetime.fromtimestamp(when).date().isoformat()
        if date in days:
            days[date]["tokens"] += tokens
            days[date]["cost"] += dollars
    for day in days.values():
        day["cost"] = round(day["cost"], 2)

    out = {"device": opts.device, "updated": int(time.time() * 1000), "days": days}
    tmp = opts.out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    os.replace(tmp, opts.out)
    with open(cache_path + ".tmp", "w", encoding="utf-8") as f:
        json.dump({"version": CACHE_VERSION, "files": kept}, f, separators=(",", ":"))
    os.replace(cache_path + ".tmp", cache_path)


if __name__ == "__main__":
    sys.exit(main())

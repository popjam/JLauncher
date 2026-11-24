import Foundation
import LockdownCore

var failures = 0
func check(_ ok: Bool, _ what: String, line: Int = #line) {
    if !ok { failures += 1; print("FAIL line \(line): \(what)") }
}
func eq<T: Equatable>(_ a: T, _ b: T, line: Int = #line) { check(a == b, "\(a) != \(b)", line: line) }

// --- Sites (phone: LimitsTest) ---
eq(Sites.host("https://www.reddit.com/r/android?x=1"), "reddit.com")
eq(Sites.host("m.youtube.com/watch"), "youtube.com")
eq(Sites.host("news.ycombinator.com"), "news.ycombinator.com")
eq(Sites.host("Example.com:8080/"), "example.com")
eq(Sites.host("reddit.com."), "reddit.com")
eq(Sites.host("reddit.com. Search or enter address"), "reddit.com")
eq(Sites.host("search words here"), nil)
eq(Sites.host("localhost"), nil)
eq(Sites.host(""), nil)
check(Sites.matches("reddit.com", "reddit.com"), "exact")
check(Sites.matches("old.reddit.com", "reddit.com"), "subdomain")
check(!Sites.matches("notreddit.com", "reddit.com"), "notreddit")
check(Sites.matches("reddit.com", "www.reddit.com"), "www site")
check(Sites.matches("np.reddit.com", "reddit.com"), "np")
check(Sites.matches("reddit.co.uk", "reddit.com"), "co.uk")
check(Sites.matches("redditmedia.com", "reddit.com"), "starts with")
check(Sites.matches("i.redd.it", "redd.it"), "redd.it")
check(Sites.matches("m.gamespot.co.uk", "gamespot.com"), "gamespot")
check(Sites.matches("mobile.x.com", "x.com"), "x subdomain")
check(!Sites.matches("xbox.com", "x.com"), "short name")
check(Sites.matches("reddit.com.", "reddit.com"), "trailing dot")
check(Sites.matches("youtube-nocookie.com", "youtube.com"), "nocookie")
check(!Sites.matches("redditor.example.com", "reddit.com"), "other domain")
check(!Sites.matches("google.com", "reddit.com"), "unrelated")

// --- Password (phone: PasswordHelper) ---
eq(Password.hash("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
check(Password.verify("abc", hash: Password.hash("abc")), "verify")
check(!Password.verify("", hash: ""), "no password set")

// --- Schedules ---
var cal = Calendar(identifier: .gregorian)
cal.timeZone = TimeZone(identifier: "Europe/London")!
func date(_ s: String) -> Date { // "2026-11-16 22:30" (a Monday)
    let f = DateFormatter(); f.calendar = cal; f.timeZone = cal.timeZone; f.dateFormat = "yyyy-MM-dd HH:mm"; return f.date(from: s)!
}
let monday = 0b0000001
let overnight = Schedule(days: monday, start: 22 * 60, end: 7 * 60)
check(overnight.covers(date("2026-11-16 22:30"), calendar: cal), "Monday evening")
check(overnight.covers(date("2026-11-17 06:59"), calendar: cal), "Tuesday morning after")
check(!overnight.covers(date("2026-11-17 07:00"), calendar: cal), "ends at 7")
check(!overnight.covers(date("2026-11-16 06:00"), calendar: cal), "not Monday morning")
eq(overnight.endAfter(date("2026-11-16 22:30"), calendar: cal), date("2026-11-17 07:00"))
let sunday = Schedule(days: 0b1000000, start: 0, end: 0)
check(sunday.covers(date("2026-11-22 12:00"), calendar: cal), "Sunday all day")
check(!sunday.covers(date("2026-11-21 12:00"), calendar: cal), "not Saturday")
check(Schedule(days: monday, start: 540, end: 1020).covers(date("2026-11-16 09:00"), calendar: cal), "day window")
eq(Schedule.parse("1:1320:420;bad;127:0:0;0:1:2;1:60:120:2;1:60:120:4"),
   [overnight, Schedule(days: 127, start: 0, end: 0), Schedule(days: 1, start: 60, end: 120, devices: 2)])
eq(overnight.windows(from: date("2026-11-16 12:00"), days: 1, calendar: cal),
   [Window(start: date("2026-11-16 22:00").ms, end: date("2026-11-17 07:00").ms)])

// --- Status: windows that follow on are one lockdown; overlaps don't add up ---
let now = date("2026-11-18 12:00").ms
let hour: Ms = 3_600_000
var phone = PhoneState()
phone.updatedAt = now - 60_000
phone.applied = []
eq(Lockdown.status(phone, now: now), .off)
var p = phone; p.timedUntil = now + hour
eq(Lockdown.status(p, now: now), Status(why: .timed, until: now + hour))
p = phone; p.timedUntil = now - 1
eq(Lockdown.status(p, now: now), .off)
// 22:00–07:00, and 23:00–06:00 inside it: still 07:00
p = phone; p.windows = [Window(start: date("2026-11-18 22:00").ms, end: date("2026-11-19 07:00").ms),
                        Window(start: date("2026-11-18 23:00").ms, end: date("2026-11-19 06:00").ms)]
eq(Lockdown.status(p, now: date("2026-11-18 23:30").ms), Status(why: .schedule, until: date("2026-11-19 07:00").ms))
// A timer running into a window
p.timedUntil = date("2026-11-18 22:30").ms
eq(Lockdown.status(p, now: date("2026-11-18 21:00").ms).until, date("2026-11-19 07:00").ms)
p = phone; p.manual = true
eq(Lockdown.status(p, now: now), Status(why: .manual, until: nil))

// --- The Mac's own requests ---
let start = Command(type: .start, until: 0, at: now)
eq(Lockdown.status(Lockdown.effective(phone: phone, pending: [start], now: now), now: now).why, .manual)
var taken = phone; taken.applied = [start.id]
check(start.applied(by: taken), "applied by id")
// Once done, the phone's state is followed (ended on the phone afterwards)
eq(Lockdown.status(Lockdown.effective(phone: taken, pending: [start], now: now), now: now), .off)
// For the phone only: nothing changes here
eq(Lockdown.status(Lockdown.effective(phone: phone, pending: [Command(type: .start, devices: Devices.phone, until: 0, at: now)], now: now), now: now), .off)
// A phone from before: its single applied id, or its update time
var old = PhoneState(); old.updatedAt = now + 5_000
check(start.applied(by: old), "older phone, newer than the request")
old.appliedCommand = "other"
check(!start.applied(by: old), "older phone, another id")
// Timed start never shortens
p = phone; p.timedUntil = now + 2 * hour
eq(Lockdown.effective(phone: p, pending: [Command(type: .start, until: now + hour, at: now)], now: now).timedUntil, now + 2 * hour)
// Add: from the end of the run, in order
eq(Lockdown.effective(phone: p, pending: [Command(type: .add, ms: hour, at: now)], now: now).timedUntil, now + 3 * hour)
eq(Lockdown.effective(phone: phone, pending: [Command(type: .add, ms: 60_000, at: now)], now: now + 30_000).timedUntil, now + 60_000)
eq(Lockdown.effective(phone: p, pending: [Command(type: .add, ms: hour, at: now), Command(type: .add, ms: hour, at: now + 1)], now: now).timedUntil, now + 4 * hour)
p = phone; p.windows = [Window(start: now - hour, end: now + hour)]
eq(Lockdown.effective(phone: p, pending: [Command(type: .add, ms: 60_000, at: now)], now: now).timedUntil, now + hour + 60_000)
// End clears on and the timer, not windows
p = phone; p.manual = true; p.timedUntil = now + 60_000; p.windows = [Window(start: now - hour, end: now + hour)]
let ended = Lockdown.effective(phone: p, pending: [Command(type: .end, at: now)], now: now)
check(!ended.manual && ended.timedUntil == 0, "end clears")
eq(Lockdown.status(ended, now: now).why, .schedule)
// Day-old requests are dropped
eq(Lockdown.effective(phone: phone, pending: [Command(type: .start, at: now - 25 * hour)], now: now).manual, false)

// --- Breaks ---
p = phone; p.manual = true; p.breaks.minutes = 5; p.breakPool = 15 * 60_000
// The request's length is ignored: a break uses the whole pool
let brk = Command(type: .break, ms: 5 * 60_000, at: now)
let onBreak = Lockdown.effective(phone: p, pending: [brk], now: now)
check(Lockdown.onBreak(onBreak, now: now + 10 * 60_000), "on a break")
check(!Lockdown.onBreak(onBreak, now: now + 16 * 60_000), "break over")
eq(onBreak.breakUntil, now + 15 * 60_000)
eq(onBreak.breakPool, 0)
eq(onBreak.breakLog.count, 1)
let endedBreak = Lockdown.effective(phone: p, pending: [brk, Command(type: .endBreak, at: now + 60_000)], now: now + 60_000)
check(!Lockdown.onBreak(endedBreak, now: now + 61_000), "break ended")
eq(endedBreak.breakLog.first?.end, now + 60_000)
eq(endedBreak.breakPool, 14 * 60_000)
// Another straight after, from what was put back
let again = Lockdown.effective(phone: p, pending: [brk, Command(type: .endBreak, at: now + 60_000), Command(type: .break, at: now + 61_000)], now: now + 61_000)
eq(again.breakUntil, now + 61_000 + 14 * 60_000)
// Not with an empty pool
p.breakPool = 30_000
check(!Lockdown.onBreak(Lockdown.effective(phone: p, pending: [brk], now: now), now: now + 1), "empty pool")

var config = BreakConfig()
eq(Breaks.check(config, pool: 0, now: now, locked: true, breakUntil: 0, calendar: cal), .off)
config.minutes = 5
eq(Breaks.check(config, pool: hour, now: now, locked: false, breakUntil: 0, calendar: cal), .notLocked)
eq(Breaks.check(config, pool: hour, now: now, locked: true, breakUntil: now + 1, calendar: cal), .onBreak(until: now + 1))
eq(Breaks.check(config, pool: 59_999, poolNext: now + 5, now: now, locked: true, breakUntil: 0, calendar: cal), .empty(next: now + 5))
eq(Breaks.check(config, pool: 0, now: now, locked: true, breakUntil: 0, calendar: cal), .empty(next: nil))
eq(Breaks.check(config, pool: 60_000, now: now, locked: true, breakUntil: 0, calendar: cal), .yes)
config.off = [Schedule(days: 127, start: 11 * 60, end: 13 * 60)]
eq(Breaks.check(config, pool: hour, now: now, locked: true, breakUntil: 0, calendar: cal), .offNow(until: date("2026-11-18 13:00").ms))
let decoded = BreakConfig.decode("{\"minutes\":5,\"every\":30,\"cap\":45,\"reset\":true,\"makeUp\":true,\"off\":\"31:60:120\",\"shared\":false,\"challenge\":\"TAPS:100\",\"autoMinutes\":10,\"autoEvery\":90}")
check(decoded.minutes == 5 && decoded.every == 30 && decoded.cap == 45 && decoded.reset && decoded.makeUp && !decoded.shared, "break config")
check(decoded.autoMinutes == 10 && decoded.autoEvery == 90, "auto breaks")
eq(BreakConfig.decode("{\"minutes\":5}").every, 60)
eq(decoded.challenge, Challenge(type: .taps, amount: 100))
eq(decoded.off, [Schedule(days: 31, start: 60, end: 120)])

// --- Challenges ---
eq(Challenge.decode("RANDOM:200"), Challenge(type: .random, amount: 200))
eq(Challenge.decode("JUNK"), nil)
check(Challenge.matches(" I am  choosing ", "i am choosing"), "phrase")
check(Challenge.matches("abcde fghjk", "abcdefghjk", ignoreSpaces: true), "random")
check(!Challenge.matches("", ""), "empty")
eq(Challenge.randomText(12).replacingOccurrences(of: " ", with: "").count, 12)

// --- The shared list ---
var items = BlockItems.set([:], kind: .site, name: "reddit.com", phone: true, mac: true, now: 10)
let phoneCopy = BlockItems.set(items, kind: .site, name: "reddit.com", phone: nil, mac: false, now: 20)
let macCopy = BlockItems.set(items, kind: .site, name: "reddit.com", phone: false, mac: nil, now: 15)
let merged = BlockItems.mergeAll(phoneCopy, macCopy)
check(merged["s_reddit,com"].map { !$0.phone && !$0.mac } ?? false, "later change wins")
items = BlockItems.set([:], kind: .app, name: "Discord", phone: true, mac: nil, now: 5, pkg: "com.discord")
let macItems = BlockItems.set([:], kind: .app, name: "discord", phone: nil, mac: true, now: 6, bundle: "com.hnc.Discord")
let both = BlockItems.mergeAll(items, macItems)
eq(both.count, 1)
eq(BlockItems.macApps(both), ["com.hnc.Discord"])
eq(BlockItem.id(.app, "!!"), "a_x" + String(BlockItem.javaHash("!!"), radix: 16))
let roundTrip = BlockItems.decode(BlockItems.encode(both))
eq(roundTrip, both)
let matchedItems = BlockItems.matched(BlockItems.set([:], kind: .site, name: "x.com", phone: true, mac: false, now: 1))
eq(BlockItems.macSites(matchedItems), ["x.com"])
// One list: blocking or unblocking from the Mac sets every switch, and the most recent change wins
var tvItem = BlockItem(kind: .app, name: "YouTube", pkg: "com.google.youtube")
tvItem.tv = true; tvItem.tvAt = 5; tvItem.phone = true; tvItem.phoneAt = 5
let unblocked = BlockItems.block([tvItem.id: tvItem], kind: .app, name: "YouTube", on: false, now: 9)
check(unblocked[tvItem.id].map { !$0.phone && !$0.mac && !$0.tv && !$0.blocked && $0.phoneAt == 9 && $0.tvAt == 9 } ?? false, "unblock everywhere")
var olderTv = tvItem; olderTv.tv = true; olderTv.tvAt = 3; olderTv.phone = false; olderTv.phoneAt = 8
check(!olderTv.blocked, "an older TV switch doesn't keep it blocked")
let reblocked = BlockItems.block(unblocked, kind: .app, name: "YouTube", on: true, now: 12, bundle: "com.google.yt")
check(reblocked[tvItem.id].map { $0.phone && $0.mac && $0.tv && $0.blocked && $0.bundle == "com.google.yt" } ?? false, "block everywhere")
eq(BlockItems.decode(BlockItems.encode(reblocked)), reblocked)

// --- Wait unlock ---
eq(Wait.state(requestedAt: 0, waitMinutes: 15, now: now), .none)
eq(Wait.state(requestedAt: now - 60_000, waitMinutes: 15, now: now), .waiting(left: 14 * 60_000))
eq(Wait.state(requestedAt: now - 15 * 60_000, waitMinutes: 15, now: now), .ready(left: 10 * 60_000))
eq(Wait.state(requestedAt: now - 25 * 60_000, waitMinutes: 15, now: now), .none)

// --- Locked until: the latest running timer on any device ---
p = phone
eq(Lockdown.lockedUntil(p, now: now), nil)
p.timedUntil = now + hour
eq(Lockdown.lockedUntil(p, now: now), now + hour)
p.timedUntil = now - 1; p.phoneTimedUntil = now - 1; p.tvTimedUntil = now
eq(Lockdown.lockedUntil(p, now: now), nil)
p.timedUntil = now + hour; p.phoneTimedUntil = now + 3 * hour; p.tvTimedUntil = now + 2 * hour
eq(Lockdown.lockedUntil(p, now: now), now + 3 * hour)
// The Mac's own timed start counts before the phone has done it, on the phone too
let started = Lockdown.effective(phone: phone, pending: [Command(type: .start, devices: Devices.both, until: now + hour, at: now)], now: now)
eq(Lockdown.lockedUntil(started, now: now), now + hour)
eq(started.phoneTimedUntil, now + hour)
eq(Lockdown.lockedUntil(Lockdown.effective(phone: phone, pending: [Command(type: .start, until: 0, at: now)], now: now), now: now), nil)

// --- Reading the phone's state and the stream ---
let json: [String: Any] = ["v": 2, "mac": ["manual": true, "timedUntil": 1_763_000_000_000 as Int64, "windows": [[1, 2], [5, 3]], "breakUntil": 7],
                           "phone": ["on": true, "until": 9, "timedUntil": 11], "tv": ["timedUntil": 12], "sites": ["0": "reddit.com", "1": "x.com"], "challenge": "WAIT:30",
                           "applied": ["a"], "breaks": "{\"minutes\":2}", "breakPool": 900_000, "breakPoolNext": 13]
let read = PhoneState(json: json)!
check(read.manual && read.timedUntil == 1_763_000_000_000 && read.breakUntil == 7 && read.phoneOn && read.phoneUntil == 9, "fields")
eq(read.windows, [Window(start: 1, end: 2)])
check(read.phoneTimedUntil == 11 && read.tvTimedUntil == 12, "timers")
check(PhoneState(json: ["mac": [:], "phone": ["on": true]])!.phoneTimedUntil == 0, "no phone timer")
eq(read.sites, ["reddit.com", "x.com"])
eq(read.challenge, Challenge(type: .wait, amount: 30))
eq(read.applied ?? [], ["a"])
eq(read.breaks.minutes, 2)
check(read.breakPool == 900_000 && read.breakPoolNext == 13, "break pool")
// A phone from before
let before = PhoneState(json: ["manualOn": false, "timedUntil": 5 as Int64, "unlock": "password", "schedules": ""])!
check(before.timedUntil == 5 && before.challenge == Challenge(type: .password) && before.applied == nil, "old phone")
var tree = EventTree()
tree.apply("put", at: "/", ["mac": ["manual": false], "sites": ["a.com"], "v": 2])
tree.apply("put", at: "/mac/manual", true)
tree.apply("patch", at: "/", ["challenge": "TAPS:20"])
tree.apply("put", at: "/sites/1", "b.com")
let streamed = PhoneState(json: tree.value)!
check(streamed.manual && streamed.challenge == Challenge(type: .taps, amount: 20), "stream updates")
eq(streamed.sites, ["a.com", "b.com"])
tree.apply("put", at: "/", NSNull())
check(tree.value == nil, "deleted")

// --- Settings changed from the Mac (phone: RemoteSettingsTest, BreaksTest, ChallengeTest, LockdownTest) ---
let ruled = PhoneState(json: ["mac": [:], "passwordHash": "", "phrase": "stay on task",
                              "rules": ["schedules": "31:1320:420;96:1410:480", "triggers": "TOTAL:300:420:7;JUNK", "passwordSet": true]])!
check(ruled.rulesKnown && ruled.passwordSet, "rules read")
eq(ruled.schedules.map(\.encoded), ["31:1320:420", "96:1410:480"])
eq(ruled.triggers, [Trigger(minutes: 300, until: 420)])
check(!PhoneState(json: ["mac": [:]])!.rulesKnown, "phone from before sends no rules")
eq(LockdownSettings.value(LockdownSettings.schedules, in: ruled), "31:1320:420;96:1410:480")
eq(LockdownSettings.value(LockdownSettings.triggers, in: ruled), "TOTAL:300:420:7")
// Schedules: taking one off, or covering less, loosens; adding doesn't
let weeknights = Schedule(days: Schedule.weekdays, start: 1320, end: 420)
check(Schedule.coverageLost([weeknights], []), "removed")
check(Schedule.coverageLost([weeknights], [Schedule(days: Schedule.weekdays, start: 1380, end: 420)]), "later start")
check(!Schedule.coverageLost([weeknights], [weeknights, Schedule(days: Schedule.weekends, start: 0, end: 0)]), "added")
check(!Schedule.coverageLost([weeknights], [Schedule(days: Schedule.everyDay, start: 1320, end: 420)]), "more days")
check(!Schedule.coverageLost([], [weeknights]), "none before")
check(weeknights.covers(day: 1, minute: 60) && !weeknights.covers(day: 5, minute: 60) && weeknights.covers(day: 4, minute: 1400), "overnight")
check(LockdownSettings.loosens(LockdownSettings.schedules, to: "", from: ruled), "all schedules off")
check(!LockdownSettings.loosens(LockdownSettings.schedules, to: "31:1320:420;96:1410:480;127:0:0", from: ruled), "schedule added")
// Lock after: more time first, another end, or taken off loosens
check(LockdownSettings.loosens(LockdownSettings.triggers, to: "", from: ruled), "trigger off")
check(LockdownSettings.loosens(LockdownSettings.triggers, to: "TOTAL:360:420:7", from: ruled), "more time")
check(LockdownSettings.loosens(LockdownSettings.triggers, to: "TOTAL:300:360:7", from: ruled), "other end")
check(!LockdownSettings.loosens(LockdownSettings.triggers, to: "TOTAL:240:420:7", from: ruled), "less time")
check(!LockdownSettings.loosens(LockdownSettings.triggers, to: "TOTAL:300:420:7;TOTAL:600:60:7", from: ruled), "trigger added")
// Challenges, as on the phone
check(Challenge.loosens(Challenge(type: .random, amount: 200), Challenge(type: .random, amount: 80)), "fewer characters")
check(!Challenge.loosens(Challenge(type: .random, amount: 80), Challenge(type: .random, amount: 200)), "more characters")
check(Challenge.loosens(Challenge(type: .password), Challenge(type: .never)), "leaving the password")
check(!Challenge.loosens(Challenge(type: .hold, amount: 30), Challenge(type: .wait, amount: 15)), "wait is harder")
eq(Challenge(type: .random, amount: 200).difficulty(), 3)
eq(Challenge(type: .never).difficulty(), 6)
check(LockdownSettings.loosens(LockdownSettings.phrase, to: "short", from: ruled), "shorter phrase")
check(!LockdownSettings.loosens(LockdownSettings.phrase, to: "stay on task, really", from: ruled), "longer phrase")
// Breaks, as on the phone (BreaksTest)
var strict = BreakConfig(); strict.minutes = 5; strict.every = 60; strict.cap = 30; strict.reset = true
var more = strict; more.minutes = 10
check(strict.loosens(to: more), "more earned")
var capped = strict; capped.cap = 0
check(strict.loosens(to: capped), "cap taken off")
var fewer = strict; fewer.minutes = 2
check(!strict.loosens(to: fewer), "less earned")
var offAdded = strict; offAdded.off = [weeknights]
check(!strict.loosens(to: offAdded) && offAdded.loosens(to: strict), "times off")
var auto = strict; auto.autoMinutes = 5; auto.autoEvery = 50
check(strict.loosens(to: auto), "automatic breaks on")
// Written as the phone writes it, and read back the same
var written = strict; written.off = [weeknights]; written.challenge = Challenge(type: .hold, amount: 30)
let reread = BreakConfig.decode(written.encoded)
check(reread.minutes == 5 && reread.cap == 30 && reread.reset && reread.off.map(\.encoded) == ["31:1320:420"] && reread.challenge == written.challenge, "breaks written")
// A change made here shows straight away, until the phone has it
let setCommand = Command(type: .set, devices: Devices.both, at: now, key: LockdownSettings.challenge, value: "TAPS:300")
eq(Lockdown.effective(phone: ruled, pending: [setCommand], now: now).challenge, Challenge(type: .taps, amount: 300))
var took = ruled; took.applied = [setCommand.id]
eq(Lockdown.effective(phone: took, pending: [setCommand], now: now).challenge, ruled.challenge)
eq(Lockdown.effective(phone: ruled, pending: [Command(type: .set, at: now - 25 * hour, key: LockdownSettings.phrase, value: "old one")], now: now).phrase, "stay on task")
let coded = try! JSONDecoder().decode(Command.self, from: JSONEncoder().encode(setCommand))
check(coded.key == LockdownSettings.challenge && coded.value == "TAPS:300", "set kept")
check(setCommand.json["key"] as? String == "challenge" && setCommand.json["value"] as? String == "TAPS:300", "set sent")

check(Firebase.validKey("abcdefghijkmnpqrstuvwxyz23456789abcdefgh"), "key")
check(!Firebase.validKey("short"), "short key")
check(Firebase.validURL("https://jl-default-rtdb.europe-west1.firebasedatabase.app"), "url")
check(!Firebase.validURL("http://example.com"), "bad url")

// `LockdownChecks live <dbUrl> <key>`: reads the phone's state and streams it briefly. Writes nothing.
let args = CommandLine.arguments
if args.count == 4, args[1] == "live" {
    let db = Firebase(url: args[2], key: args[3])
    let sem = DispatchSemaphore(value: 0)
    Task {
        do {
            let value = try await db.get("phone")
            let phone = PhoneState(json: value)
            print("GET phone:", phone.map { "manual=\($0.manual) timedUntil=\($0.timedUntil) windows=\($0.windows.count) sites=\($0.sites) challenge=\($0.challenge.encoded)" } ?? "nothing")
            if let phone { print("Status now:", Lockdown.status(phone, now: Date().ms)) }
            let streamed = Task {
                try await db.stream("phone") { v in print("stream event -> sites:", PhoneState(json: v)?.sites ?? []) }
            }
            try await Task.sleep(nanoseconds: 6_000_000_000)
            streamed.cancel()
        } catch { print("live error:", error); failures += 1 }
        sem.signal()
    }
    sem.wait()
}

print(failures == 0 ? "All checks passed" : "\(failures) failed")
exit(failures == 0 ? 0 : 1)

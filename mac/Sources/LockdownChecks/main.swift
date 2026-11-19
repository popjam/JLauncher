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
eq(Schedule.parse("1:1320:420;bad;127:0:0;0:1:2"), [overnight, Schedule(days: 127, start: 0, end: 0)])

// --- Status and the Mac's own requests ---
let now = date("2026-11-18 12:00").ms
var phone = PhoneState()
phone.updatedAt = now - 60_000
eq(Lockdown.status(phone, now: now, calendar: cal), .off)
var p = phone; p.timedUntil = now + 3_600_000
eq(Lockdown.status(p, now: now, calendar: cal), Status(why: .timed, until: now + 3_600_000))
p = phone; p.timedUntil = now - 1
eq(Lockdown.status(p, now: now, calendar: cal), .off)
p = phone; p.phoneOnly = true
eq(Lockdown.status(p, now: now, calendar: cal).why, .phoneOnly)
p.phoneOnlyUntil = now - 1
eq(Lockdown.status(p, now: now, calendar: cal), .off)
p = phone; p.schedules = "4:660:780" // Wednesday 11:00-13:00
eq(Lockdown.status(p, now: now, calendar: cal), Status(why: .schedule, until: date("2026-11-18 13:00").ms))

// Start: on at once on the Mac, until the phone takes it in
let start = Command(type: .start, until: 0, at: now)
eq(Lockdown.status(Lockdown.effective(phone: phone, pending: start, now: now), now: now).why, .manual)
var taken = phone; taken.updatedAt = now + 5_000; taken.manualOn = true
check(start.applied(by: taken), "phone newer than the request")
taken.manualOn = false // e.g. ended on the phone afterwards: follow the phone
eq(Lockdown.status(Lockdown.effective(phone: taken, pending: start, now: now + 10_000), now: now + 10_000), .off)
// With appliedCommand, only that id counts
var other = phone; other.updatedAt = now + 5_000; other.appliedCommand = "older"
check(!start.applied(by: other), "a different applied id")
other.appliedCommand = start.id
check(start.applied(by: other), "this id applied")
// The phone sends "" until it has applied one: still waiting, even if newer
other.appliedCommand = ""
check(!start.applied(by: other), "nothing applied yet")
other.appliedCommand = start.id
check(start.applied(by: other), "this id applied")
// Timed start never shortens
p = phone; p.timedUntil = now + 7_200_000
eq(Lockdown.effective(phone: p, pending: Command(type: .start, until: now + 3_600_000, at: now), now: now).timedUntil, now + 7_200_000)
// Add: from the timer's end, or from when asked, or from a schedule's end
eq(Lockdown.effective(phone: p, pending: Command(type: .add, ms: 3_600_000, at: now), now: now).timedUntil, now + 10_800_000)
eq(Lockdown.effective(phone: phone, pending: Command(type: .add, ms: 60_000, at: now), now: now + 30_000).timedUntil, now + 60_000)
p = phone; p.schedules = "4:660:780"
eq(Lockdown.effective(phone: p, pending: Command(type: .add, ms: 60_000, at: now), now: now, calendar: cal).timedUntil,
   date("2026-11-18 13:01").ms)
// End clears manual and timer, not schedules
p = phone; p.manualOn = true; p.timedUntil = now + 60_000; p.schedules = "4:660:780"
let ended = Lockdown.effective(phone: p, pending: Command(type: .end, at: now), now: now)
check(!ended.manualOn && ended.timedUntil == 0, "end clears")
eq(Lockdown.status(ended, now: now, calendar: cal).why, .schedule)
// Day-old requests are dropped
eq(Lockdown.effective(phone: phone, pending: Command(type: .start, at: now - 25 * 3_600_000), now: now).manualOn, false)

// --- Wait unlock ---
eq(Wait.state(requestedAt: 0, waitMinutes: 15, now: now), .none)
eq(Wait.state(requestedAt: now - 60_000, waitMinutes: 15, now: now), .waiting(left: 14 * 60_000))
eq(Wait.state(requestedAt: now - 15 * 60_000, waitMinutes: 15, now: now), .ready(left: 10 * 60_000))
eq(Wait.state(requestedAt: now - 25 * 60_000, waitMinutes: 15, now: now), .none)

// --- Reading the phone's state and the stream ---
let json: [String: Any] = ["manualOn": true, "timedUntil": 1_763_000_000_000 as Int64, "sites": ["0": "reddit.com", "1": "x.com"], "waitMinutes": 30]
let read = PhoneState(json: json)!
check(read.manualOn && read.timedUntil == 1_763_000_000_000 && read.waitMinutes == 30, "fields")
eq(read.sites, ["reddit.com", "x.com"])
var tree = EventTree()
tree.apply("put", at: "/", ["manualOn": false, "sites": ["a.com"]])
tree.apply("put", at: "/manualOn", true)
tree.apply("patch", at: "/", ["waitMinutes": 5])
tree.apply("put", at: "/sites/1", "b.com")
let streamed = PhoneState(json: tree.value)!
check(streamed.manualOn && streamed.waitMinutes == 5, "stream updates")
eq(streamed.sites, ["a.com", "b.com"])
tree.apply("put", at: "/", NSNull())
check(tree.value == nil, "deleted")

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
            print("GET phone:", phone.map { "manualOn=\($0.manualOn) timedUntil=\($0.timedUntil) schedules=\($0.schedules) sites=\($0.sites) unlock=\($0.unlock) wait=\($0.waitMinutes)" } ?? "nothing")
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

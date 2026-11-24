import Foundation

/// Milliseconds since 1970, as the phone sends times.
public typealias Ms = Int64

public extension Date {
    var ms: Ms { Ms((timeIntervalSince1970 * 1000).rounded()) }
    init(ms: Ms) { self.init(timeIntervalSince1970: Double(ms) / 1000) }
}

/// The devices, as bits, as on the phone.
public enum Devices {
    public static let phone = 1
    public static let mac = 2
    public static let both = 3
}

/// A lockdown schedule, as on the phone: `days` bit 0 Monday … bit 6 Sunday, `start`/`end` minutes
/// after local midnight, on `devices`. An end before the start runs past midnight; the same start
/// and end is all day.
public struct Schedule: Equatable {
    public let days: Int, start: Int, end: Int, devices: Int

    public init(days: Int, start: Int, end: Int, devices: Int = Devices.both) {
        (self.days, self.start, self.end, self.devices) = (days, start, end, devices)
    }

    /// "days:start:end[:devices];…"; anything unreadable is skipped.
    public static func parse(_ text: String) -> [Schedule] {
        text.split(separator: ";").compactMap { part in
            let bits = part.trimmingCharacters(in: .whitespaces).split(separator: ":").compactMap { Int($0) }
            guard bits.count == 3 || bits.count == 4, (1...0b1111111).contains(bits[0]),
                  (0..<1440).contains(bits[1]), (0..<1440).contains(bits[2]) else { return nil }
            let devices = bits.count == 4 ? bits[3] : Devices.both
            guard (1...3).contains(devices) else { return nil }
            return Schedule(days: bits[0], start: bits[1], end: bits[2], devices: devices)
        }
    }

    /// Monday is 0 … Sunday 6
    static func dayIndex(_ date: Date, _ calendar: Calendar) -> Int { (calendar.component(.weekday, from: date) + 5) % 7 }

    func on(_ day: Int) -> Bool { days & (1 << ((day + 7) % 7)) != 0 }

    public func covers(_ date: Date, calendar: Calendar = .current) -> Bool {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        let minute = parts.hour! * 60 + parts.minute!
        let day = Self.dayIndex(date, calendar)
        if start == end { return on(day) }
        if start < end { return on(day) && minute >= start && minute < end }
        return (on(day) && minute >= start) || (on(day - 1) && minute < end)
    }

    /// When the window `date` is in ends; only meaningful while it covers it.
    public func endAfter(_ date: Date, calendar: Calendar = .current) -> Date {
        let midnight = calendar.startOfDay(for: date)
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        let minute = parts.hour! * 60 + parts.minute!
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: midnight)!
        func at(_ day: Date, _ minutes: Int) -> Date { calendar.date(byAdding: .minute, value: minutes, to: day)! }
        if start == end { return tomorrow }
        if start < end { return at(midnight, end) }
        return minute >= start ? at(tomorrow, end) : at(midnight, end)
    }

    /// Its windows starting from the day before `from` to `days` days after, as [start, end] times.
    public func windows(from: Date, days count: Int, calendar: Calendar = .current) -> [Window] {
        let first = calendar.startOfDay(for: from)
        return (-1...count).compactMap { offset -> Window? in
            let day = calendar.date(byAdding: .day, value: offset, to: first)!
            guard on(Self.dayIndex(day, calendar)) else { return nil }
            func at(_ d: Date, _ m: Int) -> Date { calendar.date(byAdding: .minute, value: m, to: d)! }
            let next = calendar.date(byAdding: .day, value: 1, to: day)!
            if start == end { return Window(start: day.ms, end: next.ms) }
            if start < end { return Window(start: at(day, start).ms, end: at(day, end).ms) }
            return Window(start: at(day, start).ms, end: at(next, end).ms)
        }
    }
}

/// A stretch of lockdown with a start and an end.
public struct Window: Equatable {
    public var start: Ms, end: Ms
    public init(start: Ms, end: Ms) { (self.start, self.end) = (start, end) }
    public func covers(_ t: Ms) -> Bool { t >= start && t < end }
}

/// What the phone says, from /lockdown/<key>/phone: the Mac's lockdown as the phone works it out,
/// the phone's own for showing, breaks, what ending early needs and what the Mac blocks.
public struct PhoneState: Equatable {
    /// The Mac's lockdown: on until turned off, a timer, and windows from schedules and screen time
    public var manual = false
    public var timedUntil: Ms = 0
    public var windows: [Window] = []
    public var breakUntil: Ms = 0
    /// The phone's, to show
    public var phoneOn = false
    public var phoneUntil: Ms = 0
    public var phoneBreakUntil: Ms = 0
    /// When the phone's and the TV's timed lockdowns end, or 0
    public var phoneTimedUntil: Ms = 0
    public var tvTimedUntil: Ms = 0
    public var matched = false
    public var sites: [String] = []
    public var macApps: [String] = []
    public var challenge = Challenge(type: .nothing)
    public var phrase = ""
    public var passwordHash = ""
    public var breaks = BreakConfig()
    public var breakLog: [BreakEntry] = []
    /// The break time saved up (ms), shared by every device, and when it next gets more (wall clock), or 0
    public var breakPool: Ms = 0
    public var breakPoolNext: Ms = 0
    /// The settings the Mac can change (see LockdownSettings): the schedules and Lock after as the phone keeps
    /// them, and whether a password is set. `rulesKnown` is false from a phone from before, which doesn't send them.
    public var rulesKnown = false
    public var schedules: [Schedule] = []
    public var triggers: [Trigger] = []
    public var passwordSet = false
    public var updatedAt: Ms = 0
    /// The ids of the Mac requests the phone has done lately; nil from a phone from before
    public var applied: [String]?
    /// The single request a phone from before says it did
    public var appliedCommand: String?

    public init() {}

    /// Reads it leniently: missing fields keep their defaults. A phone from before sends one state
    /// for both devices, which is taken as the Mac's.
    public init?(json: Any?) {
        guard let o = json as? [String: Any] else { return nil }
        func ms(_ v: Any?) -> Ms { (v as? NSNumber)?.int64Value ?? 0 }
        func strings(_ v: Any?) -> [String] {
            if let list = v as? [Any] { return list.compactMap { $0 as? String } }
            // Firebase may send an array as an object keyed "0", "1", …
            if let map = v as? [String: Any] { return map.sorted { (Int($0.key) ?? 0) < (Int($1.key) ?? 0) }.compactMap { $0.value as? String } }
            return []
        }
        updatedAt = ms(o["updatedAt"])
        sites = strings(o["sites"])
        passwordHash = o["passwordHash"] as? String ?? ""
        appliedCommand = o["appliedCommand"] as? String
        if let mac = o["mac"] as? [String: Any] {
            manual = mac["manual"] as? Bool ?? false
            timedUntil = ms(mac["timedUntil"])
            breakUntil = ms(mac["breakUntil"])
            let list: [Any] = (mac["windows"] as? [Any]) ?? ((mac["windows"] as? [String: Any]).map { m in
                m.sorted { (Int($0.key) ?? 0) < (Int($1.key) ?? 0) }.map { $0.value } } ?? [])
            windows = list.compactMap { w in
                let pair = (w as? [Any]) ?? ((w as? [String: Any]).map { [$0["0"] as Any, $0["1"] as Any] } ?? [])
                guard pair.count == 2 else { return nil }
                let (s, e) = (ms(pair[0]), ms(pair[1]))
                return e > s ? Window(start: s, end: e) : nil
            }
            if let phone = o["phone"] as? [String: Any] {
                phoneOn = phone["on"] as? Bool ?? false
                phoneUntil = ms(phone["until"])
                phoneBreakUntil = ms(phone["breakUntil"])
                phoneTimedUntil = ms(phone["timedUntil"])
            }
            if let tv = o["tv"] as? [String: Any] { tvTimedUntil = ms(tv["timedUntil"]) }
            matched = o["matched"] as? Bool ?? false
            macApps = strings(o["macApps"])
            challenge = Challenge.decode(o["challenge"] as? String) ?? Challenge(type: .nothing)
            phrase = o["phrase"] as? String ?? ""
            breaks = BreakConfig.decode(o["breaks"] as? String)
            breakLog = BreakEntry.decodeLog(o["breakLog"] as? String)
            breakPool = max(0, ms(o["breakPool"]))
            breakPoolNext = max(0, ms(o["breakPoolNext"]))
            applied = strings(o["applied"])
            passwordSet = !passwordHash.isEmpty
            if let rules = o["rules"] as? [String: Any] {
                rulesKnown = true
                schedules = Schedule.parse(rules["schedules"] as? String ?? "")
                triggers = Trigger.parse(rules["triggers"] as? String ?? "")
                passwordSet = rules["passwordSet"] as? Bool ?? passwordSet
            }
        } else {
            // A phone from before
            manual = o["manualOn"] as? Bool ?? false
            timedUntil = ms(o["timedUntil"])
            let now = Date()
            windows = Schedule.parse(o["schedules"] as? String ?? "").flatMap { $0.windows(from: now, days: 8) }
            if o["phoneOnly"] as? Bool ?? false {
                let until = ms(o["phoneOnlyUntil"])
                if until == 0 { manual = true } else { windows.append(Window(start: 0, end: until)) }
            }
            switch o["unlock"] as? String {
            case "password": challenge = Challenge(type: .password)
            case "wait": challenge = Challenge(type: .wait, amount: (o["waitMinutes"] as? NSNumber)?.intValue ?? 15)
            default: challenge = Challenge(type: .nothing)
            }
        }
    }
}

/// A request from the Mac, queued at /lockdown/<key>/mac/commands, for `devices`.
public struct Command: Equatable, Codable {
    public enum Kind: String, Codable { case start, add, end, `break`, endBreak, set }
    public var id: String
    public var type: Kind
    public var devices: Int
    /// start: the end time, or 0 for on until turned off
    public var until: Ms
    /// add: how much; break: how long
    public var ms: Ms
    public var at: Ms
    /// set: which setting (see LockdownSettings) and its whole new value
    public var key: String?
    public var value: String?

    public init(id: String = UUID().uuidString, type: Kind, devices: Int = Devices.mac, until: Ms = 0, ms: Ms = 0, at: Ms,
                key: String? = nil, value: String? = nil) {
        (self.id, self.type, self.devices, self.until, self.ms, self.at) = (id, type, devices, until, ms, at)
        (self.key, self.value) = (key, value)
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        type = try c.decode(Kind.self, forKey: .type)
        devices = try c.decodeIfPresent(Int.self, forKey: .devices) ?? Devices.both
        until = try c.decodeIfPresent(Ms.self, forKey: .until) ?? 0
        ms = try c.decodeIfPresent(Ms.self, forKey: .ms) ?? 0
        at = try c.decode(Ms.self, forKey: .at)
        key = try c.decodeIfPresent(String.self, forKey: .key)
        value = try c.decodeIfPresent(String.self, forKey: .value)
    }

    public var json: [String: Any] {
        var o: [String: Any] = ["id": id, "type": type.rawValue, "devices": devices, "until": until, "ms": ms, "at": at]
        if let key { o["key"] = key }
        if let value { o["value"] = value }
        return o
    }

    public var forMac: Bool { devices & Devices.mac != 0 }

    /// The phone ignores requests older than a day.
    public func expired(now: Ms) -> Bool { now - at > 24 * 60 * 60_000 }

    /// Whether the phone has done this request, so the Mac can follow the phone again.
    public func applied(by phone: PhoneState?) -> Bool {
        guard let phone else { return false }
        if let applied = phone.applied { return applied.contains(id) }
        if let applied = phone.appliedCommand { return applied == id }
        return phone.updatedAt > at
    }
}

/// Whether lockdown is on on the Mac, why, and until when.
public struct Status: Equatable {
    public enum Why: Equatable { case manual, timed, schedule, phoneOnly }
    public var why: Why?
    /// When it ends, if it has an end
    public var until: Ms?
    public var on: Bool { why != nil }

    public init(why: Why?, until: Ms?) { (self.why, self.until) = (why, until) }

    public static let off = Status(why: nil, until: nil)
}

public enum Lockdown {

    /// The end of the run of lockdown covering `t` (the timer and windows that follow on from each other), or nil if none does.
    public static func coveredUntil(_ state: PhoneState, at t: Ms) -> Ms? {
        var end: Ms?
        var at = t
        for _ in 0..<64 {
            var next: Ms?
            if state.timedUntil > at { next = state.timedUntil }
            for w in state.windows where w.covers(at) { next = max(next ?? w.end, w.end) }
            guard let n = next, n > at else { break }
            end = n
            at = n
        }
        return end
    }

    /// The phone's state with the Mac's own requests applied on top, in order, until the phone has done them.
    public static func effective(phone: PhoneState?, pending: [Command], now: Ms) -> PhoneState {
        var state = phone ?? PhoneState()
        for command in pending.sorted(by: { $0.at < $1.at }) where !command.applied(by: phone) && !command.expired(now: now) {
            apply(command, to: &state)
        }
        return state
    }

    static func apply(_ command: Command, to state: inout PhoneState) {
        switch command.type {
        case .start:
            // A timed start for the phone counts there too, so unblocking is refused straight away
            if command.devices & Devices.phone != 0 && command.until > state.phoneTimedUntil { state.phoneTimedUntil = command.until }
            guard command.forMac else { return }
            if command.until <= 0 { state.manual = true }
            else if command.until > state.timedUntil { state.timedUntil = command.until }
        case .add:
            if command.devices & Devices.phone != 0 { state.phoneTimedUntil = max(state.phoneTimedUntil, command.at) + command.ms }
            guard command.forMac else { return }
            // From the end of whatever keeps it on then (the phone's addTime), else from when it was asked
            let base = max(coveredUntil(state, at: command.at) ?? command.at, state.timedUntil)
            state.timedUntil = base + command.ms
        case .end:
            if command.devices & Devices.phone != 0 { state.phoneTimedUntil = 0 }
            guard command.forMac else { return }
            state.manual = false
            state.timedUntil = 0
        case .break:
            // The phone runs it from the whole pool, whatever the request says; until it says so, the pool's length
            guard state.breakUntil <= command.at, state.breakPool >= Breaks.minBreak else { return }
            state.breakUntil = command.at + state.breakPool
            state.breakPool = 0
            if !state.breakLog.contains(where: { $0.id == command.id }) {
                state.breakLog.append(BreakEntry(id: command.id, start: command.at, end: state.breakUntil, device: BreakEntry.mac))
            }
        case .endBreak:
            // What's left goes back in the pool
            if state.breakUntil > command.at { state.breakPool += state.breakUntil - command.at }
            state.breakUntil = 0
            state.breakLog = state.breakLog.map { e in
                var e = e
                if e.end > command.at && e.start <= command.at { e.end = command.at }
                return e
            }
        case .set:
            // Shown straight away; what has lockdown on (the windows) changes once the phone takes it
            if let key = command.key, let value = command.value { LockdownSettings.apply(key, value, to: &state) }
        }
    }

    public static func status(_ state: PhoneState, now: Ms) -> Status {
        if state.manual { return Status(why: .manual, until: nil) }
        guard let until = coveredUntil(state, at: now) else { return .off }
        return Status(why: state.timedUntil > now ? .timed : .schedule, until: until)
    }

    /// Whether the Mac is on a break now: lockdown on, but lifted for a few minutes.
    public static func onBreak(_ state: PhoneState, now: Ms) -> Bool { state.breakUntil > now && status(state, now: now).on }

    /// What End can end: only "on until turned off" and the timer, as on the phone.
    public static func endable(_ state: PhoneState, now: Ms) -> Bool { state.manual || state.timedUntil > now }

    /// When the latest running timer on any device (Mac, phone, TV) ends, or nil if none is running.
    /// While one runs, nothing is taken off the block list one at a time, as on the phone.
    public static func lockedUntil(_ state: PhoneState, now: Ms) -> Ms? {
        [state.timedUntil, state.phoneTimedUntil, state.tvTimedUntil].filter { $0 > now }.max()
    }
}

/// The wait unlock, mirrored from the phone: asking starts a wait, then End works for 10 minutes.
public enum Wait {
    public static let readyMs: Ms = 10 * 60_000

    public enum State: Equatable {
        case none
        case waiting(left: Ms)
        case ready(left: Ms)
    }

    public static func state(requestedAt: Ms, waitMinutes: Int, now: Ms) -> State {
        guard requestedAt > 0 else { return .none }
        let since = now - requestedAt
        let wait = Ms(waitMinutes) * 60_000
        if since < 0 { return .none }
        if since < wait { return .waiting(left: wait - since) }
        if since < wait + readyMs { return .ready(left: wait + readyMs - since) }
        return .none
    }
}

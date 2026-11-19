import Foundation

/// Milliseconds since 1970, as the phone sends times.
public typealias Ms = Int64

public extension Date {
    var ms: Ms { Ms((timeIntervalSince1970 * 1000).rounded()) }
    init(ms: Ms) { self.init(timeIntervalSince1970: Double(ms) / 1000) }
}

/// A lockdown schedule, as on the phone: `days` bit 0 Monday … bit 6 Sunday, `start`/`end` minutes
/// after local midnight. An end before the start runs past midnight; the same start and end is all day.
public struct Schedule: Equatable {
    public let days: Int, start: Int, end: Int

    public init(days: Int, start: Int, end: Int) { (self.days, self.start, self.end) = (days, start, end) }

    /// "days:start:end;…"; anything unreadable is skipped.
    public static func parse(_ text: String) -> [Schedule] {
        text.split(separator: ";").compactMap { part in
            let bits = part.trimmingCharacters(in: .whitespaces).split(separator: ":").compactMap { Int($0) }
            guard bits.count == 3, (1...0b1111111).contains(bits[0]),
                  (0..<1440).contains(bits[1]), (0..<1440).contains(bits[2]) else { return nil }
            return Schedule(days: bits[0], start: bits[1], end: bits[2])
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
}

/// What the phone says, from /lockdown/<key>/phone.
public struct PhoneState: Equatable {
    public var manualOn = false
    public var timedUntil: Ms = 0
    public var schedules = ""
    public var phoneOnly = false
    public var phoneOnlyUntil: Ms = 0
    public var sites: [String] = []
    public var unlock = "nothing"
    public var passwordHash = ""
    public var waitMinutes = 15
    public var updatedAt: Ms = 0
    /// The id of the last Mac request the phone applied, if the phone sends it.
    public var appliedCommand: String?

    public init() {}

    /// Reads it leniently: missing fields keep their defaults.
    public init?(json: Any?) {
        guard let o = json as? [String: Any] else { return nil }
        func ms(_ k: String) -> Ms { (o[k] as? NSNumber)?.int64Value ?? 0 }
        manualOn = o["manualOn"] as? Bool ?? false
        timedUntil = ms("timedUntil")
        schedules = o["schedules"] as? String ?? ""
        phoneOnly = o["phoneOnly"] as? Bool ?? false
        phoneOnlyUntil = ms("phoneOnlyUntil")
        // Firebase may send an array as an object keyed "0", "1", …
        if let list = o["sites"] as? [Any] {
            sites = list.compactMap { $0 as? String }
        } else if let map = o["sites"] as? [String: Any] {
            sites = map.sorted { (Int($0.key) ?? 0) < (Int($1.key) ?? 0) }.compactMap { $0.value as? String }
        }
        unlock = o["unlock"] as? String ?? "nothing"
        passwordHash = o["passwordHash"] as? String ?? ""
        waitMinutes = (o["waitMinutes"] as? NSNumber)?.intValue ?? 15
        updatedAt = ms("updatedAt")
        appliedCommand = o["appliedCommand"] as? String
    }
}

/// A request from the Mac, at /lockdown/<key>/mac/command.
public struct Command: Equatable, Codable {
    public enum Kind: String, Codable { case start, add, end }
    public var id: String
    public var type: Kind
    /// start: the end time, or 0 for on until turned off
    public var until: Ms
    /// add: how much
    public var ms: Ms
    public var at: Ms

    public init(id: String = UUID().uuidString, type: Kind, until: Ms = 0, ms: Ms = 0, at: Ms) {
        (self.id, self.type, self.until, self.ms, self.at) = (id, type, until, ms, at)
    }

    public var json: [String: Any] { ["id": id, "type": type.rawValue, "until": until, "ms": ms, "at": at] }

    /// The phone ignores requests older than a day.
    public func expired(now: Ms) -> Bool { now - at > 24 * 60 * 60_000 }

    /// Whether the phone has taken this request in, so the Mac can follow the phone again.
    public func applied(by phone: PhoneState?) -> Bool {
        guard let phone else { return false }
        if let applied = phone.appliedCommand { return applied == id }
        return phone.updatedAt > at
    }
}

/// Whether lockdown is on, why, and until when.
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

    /// The phone's state with the Mac's own request applied on top, until the phone has taken it in.
    public static func effective(phone: PhoneState?, pending: Command?, now: Ms, calendar: Calendar = .current) -> PhoneState {
        var state = phone ?? PhoneState()
        guard let command = pending, !command.applied(by: phone), !command.expired(now: now) else { return state }
        switch command.type {
        case .start:
            if command.until <= 0 { state.manualOn = true }
            else if command.until > state.timedUntil { state.timedUntil = command.until }
        case .add:
            // From the end of whatever keeps it on then (the phone's addTime), else from when it was asked
            let at = command.at
            var base = max(at, state.timedUntil)
            let then = Date(ms: at)
            for s in Schedule.parse(state.schedules) where s.covers(then, calendar: calendar) {
                base = max(base, s.endAfter(then, calendar: calendar).ms)
            }
            if state.phoneOnly && state.phoneOnlyUntil > at { base = max(base, state.phoneOnlyUntil) }
            state.timedUntil = base + command.ms
        case .end:
            state.manualOn = false
            state.timedUntil = 0
        }
        return state
    }

    public static func status(_ state: PhoneState, now: Ms, calendar: Calendar = .current) -> Status {
        if state.manualOn { return Status(why: .manual, until: nil) }
        if now < state.timedUntil { return Status(why: .timed, until: state.timedUntil) }
        let date = Date(ms: now)
        if let s = Schedule.parse(state.schedules).first(where: { $0.covers(date, calendar: calendar) }) {
            return Status(why: .schedule, until: s.endAfter(date, calendar: calendar).ms)
        }
        if state.phoneOnly && (state.phoneOnlyUntil == 0 || now < state.phoneOnlyUntil) {
            return Status(why: .phoneOnly, until: state.phoneOnlyUntil == 0 ? nil : state.phoneOnlyUntil)
        }
        return .off
    }

    /// What End can end: only "on until turned off" and the timer, as on the phone.
    public static func endable(_ status: Status) -> Bool { status.why == .manual || status.why == .timed }
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

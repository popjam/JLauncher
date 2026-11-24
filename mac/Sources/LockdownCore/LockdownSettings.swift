import Foundation

// The lockdown settings the Mac can change (the phone's RemoteSettings.kt): schedules, Lock after,
// breaks, what unlocking needs and the phrase. The Mac sends the whole new value of one setting; the
// phone keeps the latest change. What loosens is worked out as on the phone, so it goes through the unlock.

/// Once the day's total screen time reaches `minutes`, lockdown until the next `until` (minutes after midnight).
public struct Trigger: Equatable {
    public var minutes: Int, until: Int

    public init(minutes: Int, until: Int) { (self.minutes, self.until) = (minutes, until) }

    /// As the phone writes it: every trigger counts the total and locks every device
    public var encoded: String { "TOTAL:\(minutes):\(until):7" }

    /// "SOURCE:minutes:until:devices;…"; anything unreadable is skipped, the same one twice kept once
    public static func parse(_ text: String) -> [Trigger] {
        var out: [Trigger] = []
        for part in text.split(separator: ";") {
            let bits = part.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            guard bits.count == 4, let m = Int(bits[1]), m > 0, let u = Int(bits[2]), (0..<1440).contains(u),
                  let d = Int(bits[3]), (1...7).contains(d) else { continue }
            let t = Trigger(minutes: m, until: u)
            if !out.contains(t) { out.append(t) }
        }
        return out
    }

    public static func format(_ triggers: [Trigger]) -> String { triggers.map(\.encoded).joined(separator: ";") }

    /// More screen time before it goes off, or another end, loosens
    public static func loosens(_ old: Trigger, _ new: Trigger) -> Bool { new.minutes > old.minutes || new.until != old.until }

    /// The amounts offered, in minutes, as on the phone
    public static let amounts = [30, 60, 90, 120, 150, 180, 240, 300, 360, 480, 600]
}

public extension Schedule {
    static let weekdays = 0b0011111
    static let weekends = 0b1100000
    static let everyDay = 0b1111111

    /// "days:start:end", as the phone keeps it
    var encoded: String { "\(days):\(start):\(end)" }

    static func format(_ schedules: [Schedule]) -> String { schedules.map(\.encoded).joined(separator: ";") }

    /// Whether it covers `minute` (after midnight) on `day` (Monday 0)
    func covers(day: Int, minute: Int) -> Bool {
        if start == end { return on(day) }
        if start < end { return on(day) && minute >= start && minute < end }
        return (on(day) && minute >= start) || (on(day - 1) && minute < end)
    }

    /// Whether some of the week `old` covers `new` doesn't: lockdown, or no breaks, for less of the week
    static func coverageLost(_ old: [Schedule], _ new: [Schedule]) -> Bool {
        guard !old.isEmpty, old.map(\.encoded) != new.map(\.encoded) else { return false }
        for m in 0..<(7 * 1440) {
            let (day, minute) = (m / 1440, m % 1440)
            if old.contains(where: { $0.covers(day: day, minute: minute) }) && !new.contains(where: { $0.covers(day: day, minute: minute) }) { return true }
        }
        return false
    }
}

public extension Challenge {
    /// The amounts offered for each type, as on the phone
    static func amounts(_ type: Kind) -> [Int] {
        switch type {
        case .hold: return [10, 30, 60, 300, 900, 1800, 3600]
        case .taps: return [20, 100, 300, 1000, 3000, 10000]
        case .phrase: return [1, 3, 5, 10, 25]
        case .random: return [30, 80, 200, 500, 1000, 2500]
        case .wait: return [5, 15, 30, 60, 120, 360, 720, 1440]
        default: return []
        }
    }

    /// Roughly how many seconds of effort it takes, to rank them
    func effortSeconds(phraseLength: Int = 40) -> Int64 {
        let a = Int64(amount)
        switch type {
        case .nothing: return 0
        case .hold: return a
        case .taps: return a / 4
        case .phrase: return Int64(max(1, phraseLength)) * a / 3
        case .random: return a * 3 / 2
        case .password: return 20
        case .wait: return a * 60
        case .never: return Int64.max
        }
    }

    /// 0 very easy … 5 almost impossible, 6 impossible
    func difficulty(phraseLength: Int = 40) -> Int {
        if type == .never { return 6 }
        let s = effortSeconds(phraseLength: phraseLength)
        if s <= 0 { return 0 }
        if s < 30 { return 1 }
        if s < 180 { return 2 }
        if s < 900 { return 3 }
        if s < 3600 { return 4 }
        return 5
    }

    /// Whether going from `old` to `new` makes things easier; leaving the password always does
    static func loosens(_ old: Challenge, _ new: Challenge, oldPhraseLength: Int = 40, newPhraseLength: Int? = nil) -> Bool {
        let newLength = newPhraseLength ?? oldPhraseLength
        if old == new && oldPhraseLength == newLength { return false }
        if old.type == .password { return true }
        return new.effortSeconds(phraseLength: newLength) <= old.effortSeconds(phraseLength: oldPhraseLength)
    }
}

public extension BreakConfig {
    var autoOn: Bool { autoMinutes > 0 && autoEvery > autoMinutes }

    /// As the phone writes it (Breaks.Config.encode)
    var encoded: String {
        let o: [String: Any] = ["minutes": minutes, "every": every, "cap": cap, "reset": reset, "makeUp": makeUp,
                                "off": Schedule.format(off), "shared": true, "challenge": challenge.encoded,
                                "autoMinutes": autoMinutes, "autoEvery": autoEvery]
        let data = (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// Whether going to `new` makes breaks easier to get, as on the phone (Breaks.Config.loosensTo)
    func loosens(to new: BreakConfig, phraseLength: Int = 40) -> Bool {
        let autoLoosens = new.autoOn && (!autoOn || new.autoMinutes > autoMinutes || new.autoEvery < autoEvery)
        let capLoosens = cap > 0 && (new.cap <= 0 || new.cap > cap)
        return new.minutes > minutes
            || (new.on && new.every < every)
            || capLoosens
            || (!new.reset && reset)
            || (!new.makeUp && makeUp)
            || Schedule.coverageLost(off, new.off)
            || Challenge.loosens(challenge, new.challenge, oldPhraseLength: phraseLength)
            || autoLoosens
    }
}

public enum LockdownSettings {
    public static let schedules = "schedules"
    public static let triggers = "triggers"
    public static let breaks = "breaks"
    public static let challenge = "challenge"
    public static let phrase = "phrase"

    /// The section a setting is shown in, for "Not applied"
    public static func name(_ key: String) -> String {
        switch key {
        case schedules: return "Schedule"
        case triggers: return "Lock after"
        case breaks: return "Breaks"
        case challenge: return "Unlock needs"
        case phrase: return "Phrase"
        default: return key
        }
    }

    /// `key`'s value in `state`, as it's sent
    public static func value(_ key: String, in state: PhoneState) -> String {
        switch key {
        case schedules: return Schedule.format(state.schedules)
        case triggers: return Trigger.format(state.triggers)
        case breaks: return state.breaks.encoded
        case challenge: return state.challenge.encoded
        case phrase: return state.phrase
        default: return ""
        }
    }

    /// `state` with `key` set to `value`, as the phone will have it
    public static func apply(_ key: String, _ value: String, to state: inout PhoneState) {
        switch key {
        case schedules: state.schedules = Schedule.parse(value)
        case triggers: state.triggers = Trigger.parse(value)
        case breaks: state.breaks = BreakConfig.decode(value)
        case challenge: if let c = Challenge.decode(value) { state.challenge = c }
        case phrase: state.phrase = value
        default: break
        }
    }

    /// Whether setting `key` to `value` loosens lockdown from `state`, so it needs the unlock
    public static func loosens(_ key: String, to value: String, from state: PhoneState) -> Bool {
        let phraseLength = state.phrase.isEmpty ? 40 : state.phrase.count
        switch key {
        case schedules: return Schedule.coverageLost(state.schedules, Schedule.parse(value))
        case triggers:
            let new = Trigger.parse(value)
            // Each trigger taken off or loosened; one swapped for a looser one counts too
            return state.triggers.contains { old in !new.contains(old) && !new.contains { !Trigger.loosens(old, $0) } }
        case breaks: return state.breaks.loosens(to: BreakConfig.decode(value), phraseLength: phraseLength)
        case challenge:
            guard let new = Challenge.decode(value) else { return false }
            return Challenge.loosens(state.challenge, new, oldPhraseLength: phraseLength)
        case phrase: return value.count < state.phrase.count
        default: return true
        }
    }
}

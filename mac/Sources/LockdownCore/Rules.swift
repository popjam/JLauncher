import Foundation

// The phone's rules, the same here: what ending early and breaks need (Challenge.kt), breaks
// (Breaks.kt) and the shared list of what's blocked on each device (BlockItems.kt).

/// What has to be done to end lockdown early, loosen it, or take a break. Written "TYPE:amount".
public struct Challenge: Equatable {
    public enum Kind: String, CaseIterable {
        case nothing = "NONE", hold = "HOLD", taps = "TAPS", phrase = "PHRASE", random = "RANDOM"
        case password = "PASSWORD", wait = "WAIT", never = "NEVER"
    }

    public var type: Kind
    /// Seconds, taps, times, characters or minutes, by type
    public var amount: Int

    public init(type: Kind, amount: Int = 0) { (self.type, self.amount) = (type, amount) }

    public var encoded: String { "\(type.rawValue):\(amount)" }

    public static func decode(_ text: String?) -> Challenge? {
        guard let parts = text?.split(separator: ":", omittingEmptySubsequences: false), let first = parts.first,
              let type = Kind(rawValue: String(first)) else { return nil }
        let amount = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
        return Challenge(type: type, amount: amount)
    }

    /// In a few words: "100 taps", "Keep open 30s"
    public var name: String {
        switch type {
        case .nothing: return "Nothing"
        case .hold: return amount < 60 ? "Keep open \(amount)s" : "Keep open \(amount / 60)m"
        case .taps: return "\(amount) taps"
        case .phrase: return amount <= 1 ? "Type the phrase" : "Type the phrase \(amount) times"
        case .random: return "Type \(amount) random characters"
        case .password: return "Password"
        case .wait: return "\(amount)m wait"
        case .never: return "Not possible"
        }
    }

    static let alphabet = Array("abcdefghjkmnpqrstuvwxyz23456789")

    /// `length` random characters in groups of five
    public static func randomText(_ length: Int) -> String {
        let chars = (0..<length).map { _ in alphabet.randomElement()! }
        return stride(from: 0, to: chars.count, by: 5).map { String(chars[$0..<min($0 + 5, chars.count)]) }.joined(separator: " ")
    }

    /// Whether `typed` matches `expected`, ignoring case and runs of spaces (or all spaces)
    public static func matches(_ typed: String, _ expected: String, ignoreSpaces: Bool = false) -> Bool {
        func clean(_ s: String) -> String {
            let words = s.lowercased().split(whereSeparator: { $0.isWhitespace })
            return words.joined(separator: ignoreSpaces ? "" : " ")
        }
        return !expected.trimmingCharacters(in: .whitespaces).isEmpty && clean(typed) == clean(expected)
    }
}

/// Breaks' settings, as the phone writes them. Break time is earned into a pool shared by every
/// device: `minutes` for each `every` minutes of lockdown, up to `cap` (0 for none), emptied at
/// midnight with `reset`.
public struct BreakConfig: Equatable {
    public var minutes = 0
    public var every = 60
    public var cap = 0
    public var reset = false
    public var makeUp = false
    public var off: [Schedule] = []
    public var shared = true
    public var challenge = Challenge(type: .nothing)
    public var autoMinutes = 0
    public var autoEvery = 0

    public init() {}

    public var on: Bool { minutes > 0 }

    public static func decode(_ text: String?) -> BreakConfig {
        var c = BreakConfig()
        guard let data = text?.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return c }
        func int(_ k: String) -> Int { max(0, (o[k] as? NSNumber)?.intValue ?? 0) }
        c.minutes = int("minutes"); c.every = max(1, (o["every"] as? NSNumber)?.intValue ?? 60); c.cap = int("cap")
        c.reset = o["reset"] as? Bool ?? false; c.makeUp = o["makeUp"] as? Bool ?? false
        c.off = Schedule.parse(o["off"] as? String ?? "")
        c.shared = o["shared"] as? Bool ?? true
        c.challenge = Challenge.decode(o["challenge"] as? String) ?? Challenge(type: .nothing)
        c.autoMinutes = int("autoMinutes"); c.autoEvery = int("autoEvery")
        return c
    }
}

/// One break: when it started and ended, and on which device.
public struct BreakEntry: Equatable, Codable {
    public static let phone = "phone"
    public static let mac = "mac"
    public var id: String
    public var start: Ms
    public var end: Ms
    public var device: String

    public init(id: String, start: Ms, end: Ms, device: String) { (self.id, self.start, self.end, self.device) = (id, start, end, device) }

    public static func decodeLog(_ text: String?) -> [BreakEntry] {
        guard let data = text?.data(using: .utf8), let list = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
        return list.compactMap { o in
            guard let start = (o["start"] as? NSNumber)?.int64Value, let end = (o["end"] as? NSNumber)?.int64Value else { return nil }
            return BreakEntry(id: o["id"] as? String ?? "", start: start, end: end, device: o["device"] as? String ?? phone)
        }
    }
}

public enum Breaks {
    /// The least the pool needs for a break
    public static let minBreak: Ms = 60_000

    public enum Can: Equatable {
        case yes, off, notLocked
        case onBreak(until: Ms)
        case offNow(until: Ms)
        /// Under a minute in the pool; `next` is when it next gets more, if known
        case empty(next: Ms?)
    }

    /// Whether a break can be taken at `now`, with `pool` saved up (more at `poolNext`, or 0), as on the phone.
    public static func check(_ config: BreakConfig, pool: Ms, poolNext: Ms = 0, now: Ms, locked: Bool, breakUntil: Ms,
                             calendar: Calendar = .current) -> Can {
        if !config.on { return .off }
        if !locked { return .notLocked }
        if breakUntil > now { return .onBreak(until: breakUntil) }
        let date = Date(ms: now)
        if let s = config.off.first(where: { $0.covers(date, calendar: calendar) }) { return .offNow(until: s.endAfter(date, calendar: calendar).ms) }
        if pool < minBreak { return .empty(next: poolNext > 0 ? poolNext : nil) }
        return .yes
    }
}

/// An app or site lockdown blocks (BlockItems.kt), with a switch for each device: each device blocks the
/// items switched on for it, where it can.
public struct BlockItem: Equatable {
    public enum Kind: String { case app, site }
    public var kind: Kind
    public var name: String
    public var pkg: String?
    public var bundle: String?
    public var phone = false
    public var phoneAt: Ms = 0
    public var mac = false
    public var macAt: Ms = 0
    /// The TV's name for the app and its switch, kept as the phone wrote them
    public var tvName: String?
    public var tv = false
    public var tvAt: Ms = 0

    public init(kind: Kind, name: String, pkg: String? = nil, bundle: String? = nil) {
        (self.kind, self.name, self.pkg, self.bundle) = (kind, name, pkg, bundle)
    }

    public var id: String { Self.id(kind, name) }
    public var shown: Bool { blocked }
    /// Blocked on any device
    public var blocked: Bool { phone || mac || tv }
    public var onPhone: Bool { kind == .site || pkg != nil }
    public var onMac: Bool { kind == .site || bundle != nil }
    public var onTv: Bool { kind == .app && tvName != nil }

    /// Java's String.hashCode, as the phone uses, unsigned
    public static func javaHash(_ s: String) -> UInt32 {
        var h: Int32 = 0
        for unit in s.utf16 { h = h &* 31 &+ Int32(unit) }
        return UInt32(bitPattern: h)
    }

    public static func key(_ name: String) -> String { String(name.lowercased().filter { $0.isLetter || $0.isNumber }) }

    public static func id(_ kind: Kind, _ name: String) -> String {
        switch kind {
        case .site: return "s_" + String(name.lowercased().map { ".$#[]/%".contains($0) ? "," : $0 })
        case .app:
            let k = key(name)
            return "a_" + (k.isEmpty ? "x" + String(javaHash(name), radix: 16) : k)
        }
    }

    public var json: [String: Any] {
        ["k": kind.rawValue, "n": name, "p": pkg ?? "", "m": bundle ?? "", "ph": phone, "tph": phoneAt, "mc": mac, "tmc": macAt,
         "t": tvName ?? "", "tv": tv, "ttv": tvAt]
    }

    public init?(json: Any?) {
        guard let o = json as? [String: Any], let name = o["n"] as? String, !name.isEmpty else { return nil }
        kind = (o["k"] as? String) == "app" ? .app : .site
        self.name = name
        pkg = (o["p"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        bundle = (o["m"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        phone = o["ph"] as? Bool ?? false
        phoneAt = (o["tph"] as? NSNumber)?.int64Value ?? 0
        mac = o["mc"] as? Bool ?? false
        macAt = (o["tmc"] as? NSNumber)?.int64Value ?? 0
        tvName = (o["t"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        tv = o["tv"] as? Bool ?? false
        tvAt = (o["ttv"] as? NSNumber)?.int64Value ?? 0
    }

    /// Two copies together: each switch from the later change, and whatever's known of the app on each device.
    public static func merge(_ a: BlockItem, _ b: BlockItem) -> BlockItem {
        var item = a
        if a.kind == .app && b.name.count > a.name.count && a.pkg == nil { item.name = b.name }
        item.pkg = a.pkg ?? b.pkg
        item.bundle = a.bundle ?? b.bundle
        item.tvName = a.tvName ?? b.tvName
        if b.tvAt > a.tvAt { item.tv = b.tv; item.tvAt = b.tvAt }
        if b.phoneAt > a.phoneAt { item.phone = b.phone; item.phoneAt = b.phoneAt }
        if b.macAt > a.macAt { item.mac = b.mac; item.macAt = b.macAt }
        return item
    }
}

public enum BlockItems {
    public static func decode(_ json: Any?) -> [String: BlockItem] {
        guard let o = json as? [String: Any] else { return [:] }
        var items: [String: BlockItem] = [:]
        for value in o.values { if let item = BlockItem(json: value) { items[item.id] = item } }
        return items
    }

    public static func encode(_ items: [String: BlockItem]) -> [String: Any] { items.mapValues { $0.json } }

    public static func mergeAll(_ a: [String: BlockItem], _ b: [String: BlockItem]) -> [String: BlockItem] {
        var out = a
        for (id, item) in b { out[id] = out[id].map { BlockItem.merge($0, item) } ?? item }
        return out
    }

    /// `items` with `name` switched on or off for the phone, the Mac and/or the TV at `now`.
    public static func set(_ items: [String: BlockItem], kind: BlockItem.Kind, name: String, phone: Bool?, mac: Bool?, now: Ms,
                           pkg: String? = nil, bundle: String? = nil, tv: Bool? = nil, tvName: String? = nil) -> [String: BlockItem] {
        let id = BlockItem.id(kind, name)
        let old = items[id] ?? BlockItem(kind: kind, name: name)
        var item = old
        item.pkg = old.pkg ?? pkg
        item.bundle = old.bundle ?? bundle
        item.tvName = old.tvName ?? tvName
        if let phone, phone != old.phone { item.phone = phone; item.phoneAt = now }
        if let mac, mac != old.mac { item.mac = mac; item.macAt = now }
        if let tv, tv != old.tv { item.tv = tv; item.tvAt = now }
        var out = items
        out[id] = item
        return out
    }

    /// `items` with `name` blocked or not everywhere at `now`, as the phone's `block`: every device's switch together.
    public static func block(_ items: [String: BlockItem], kind: BlockItem.Kind, name: String, on: Bool, now: Ms,
                             pkg: String? = nil, bundle: String? = nil) -> [String: BlockItem] {
        var out = set(items, kind: kind, name: name, phone: on, mac: on, now: now, pkg: pkg, bundle: bundle)
        let id = BlockItem.id(kind, name)
        if var item = out[id], item.tv != on { item.tv = on; item.tvAt = now; out[id] = item }
        return out
    }

    /// What each device blocks: each item's own switch for it, where the device can block it.
    public static func matched(_ items: [String: BlockItem]) -> [String: BlockItem] {
        items.mapValues { it in
            var it = it
            it.phone = it.phone && it.onPhone
            it.mac = it.mac && it.onMac
            it.tv = it.tv && it.onTv
            return it
        }
    }

    public static func macApps(_ items: [String: BlockItem]) -> Set<String> { Set(items.values.filter { $0.kind == .app && $0.mac }.compactMap(\.bundle)) }
    public static func macSites(_ items: [String: BlockItem]) -> Set<String> { Set(items.values.filter { $0.kind == .site && $0.mac }.map(\.name)) }

    /// The items whose copy in `remote` isn't the same, to send.
    public static func changed(_ local: [String: BlockItem], remote: [String: BlockItem]) -> [String: BlockItem] {
        local.filter { remote[$0.key] != $0.value }
    }
}

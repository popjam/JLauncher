import AppKit
import Combine
import LockdownCore

/// Everything the Mac knows: the pairing, the phone's state and the shared block list (both streamed
/// live), its own requests not yet done by the phone, breaks, and the wait unlock.
@MainActor
final class Model: ObservableObject {
    static let shared = Model()

    private let defaults = UserDefaults.standard

    @Published private(set) var url: String
    @Published private(set) var key: String
    @Published private(set) var phone: PhoneState?
    /// When the stream last connected or brought news
    @Published private(set) var lastHeard: Date?
    @Published private(set) var syncError: String?
    /// Requests made here that the phone hasn't done yet, oldest first
    @Published private(set) var pending: [Command]
    /// The shared list of what's blocked, this Mac's copy
    @Published private(set) var items: [String: BlockItem]
    /// The phone's apps, to block from here: package to name
    @Published private(set) var phoneApps: [String: String] = [:]
    @Published private(set) var waitRequestedAt: Ms
    /// Ticks every second so times stay current
    @Published private(set) var now = Date()

    private var streamTask: Task<Void, Never>?
    private var itemsTask: Task<Void, Never>?
    private var sendTask: Task<Void, Never>?
    /// The list as last seen in the database
    private var remoteItems: [String: BlockItem] = [:]

    var paired: Bool { !url.isEmpty && !key.isEmpty }
    var firebase: Firebase { Firebase(url: url, key: key) }

    private init() {
        url = defaults.string(forKey: "url") ?? ""
        key = defaults.string(forKey: "key") ?? ""
        waitRequestedAt = Ms(defaults.integer(forKey: "waitRequestedAt"))
        pending = (defaults.data(forKey: "pendingList")).flatMap { try? JSONDecoder().decode([Command].self, from: $0) } ?? []
        items = (defaults.data(forKey: "items")).flatMap { try? JSONSerialization.jsonObject(with: $0) }.map(BlockItems.decode) ?? [:]
        // The last state seen, so blocking works straight away and without a connection
        if let data = defaults.data(forKey: "phone"), let json = try? JSONSerialization.jsonObject(with: data) {
            phone = PhoneState(json: json)
        }
        // Mac apps chosen before the list was shared
        if items.isEmpty, let old = defaults.stringArray(forKey: "macApps"), !old.isEmpty {
            let names = Dictionary(InstalledApps.list().map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
            for bundle in old { items = BlockItems.set(items, kind: .app, name: names[bundle] ?? bundle, phone: nil, mac: true, now: 1, bundle: bundle) }
            saveItems()
        }
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            Task { @MainActor in Model.shared.tick() }
        }
        if paired { connect() }
    }

    // --- The state ---

    var state: PhoneState { Lockdown.effective(phone: phone, pending: pending, now: now.ms) }
    var status: Status { paired ? Lockdown.status(state, now: now.ms) : .off }
    var onBreak: Bool { paired && Lockdown.onBreak(state, now: now.ms) }
    /// Whether the Mac blocks things now: in lockdown, and not on a break
    var blocking: Bool { status.on && !onBreak }
    /// The list as it applies, matched when the devices are
    var effectiveItems: [String: BlockItem] { state.matched ? BlockItems.matched(items) : items }
    var macApps: Set<String> { BlockItems.macApps(effectiveItems) }
    /// The Mac's sites, and the sites the phone adds for its own blocked apps when matched
    var sites: [String] {
        let listed = Set(items.values.filter { $0.kind == .site }.map(\.name))
        return Array(BlockItems.macSites(effectiveItems).union(state.sites.filter { !listed.contains($0) })).sorted()
    }
    /// The Mac's requests haven't all reached the phone yet
    var waitingForPhone: Bool { pending.contains { !$0.applied(by: phone) && !$0.expired(now: now.ms) } }

    private func tick() {
        now = Date()
        let left = pending.filter { !$0.applied(by: phone) && !$0.expired(now: now.ms) }
        if left.count != pending.count { setPending(left) }
    }

    // --- Pairing ---

    /// Checks the database answers, then saves the pairing and starts listening.
    func pair(url newURL: String, key newKey: String) async -> String? {
        let base = Firebase(url: newURL, key: newKey)
        do {
            let value = try await base.get("phone")
            let remote = try await base.get("items")
            url = base.base; key = base.key
            defaults.set(url, forKey: "url"); defaults.set(key, forKey: "key")
            setPhone(value)
            takeItems(remote)
            connect()
            return nil
        } catch {
            return "Couldn't reach the database: \(error)"
        }
    }

    func unpair() {
        streamTask?.cancel(); streamTask = nil
        itemsTask?.cancel(); itemsTask = nil
        url = ""; key = ""; phone = nil; lastHeard = nil; syncError = nil
        setPending([])
        for k in ["url", "key", "phone"] { defaults.removeObject(forKey: k) }
    }

    // --- Sync ---

    private func connect() {
        streamTask?.cancel()
        itemsTask?.cancel()
        let base = firebase
        streamTask = streamLoop(base, "phone") { [weak self] value in
            guard let self, self.key == base.key else { return }
            self.setPhone(value)
            self.syncError = nil
        }
        itemsTask = streamLoop(base, "items") { [weak self] value in
            guard let self, self.key == base.key else { return }
            self.takeItems(value)
        }
        sendPending()
        sendInstalled()
        pushItems()
        loadPhoneApps()
    }

    /// Streams `path`, reconnecting with growing waits
    private func streamLoop(_ base: Firebase, _ path: String, onValue: @escaping @MainActor (Any?) -> Void) -> Task<Void, Never> {
        Task { [weak self] in
            var delay: UInt64 = 2
            while !Task.isCancelled {
                do {
                    try await base.stream(path) { value in
                        let box = UnsafeValue(value)
                        Task { @MainActor in onValue(box.value) }
                    }
                    delay = 2
                } catch {
                    if Task.isCancelled { return }
                    await MainActor.run { self?.syncError = (error as? Firebase.HTTPError)?.description ?? "Offline" }
                }
                try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
                delay = min(delay * 2, 60)
            }
        }
    }

    private func setPhone(_ value: Any?) {
        lastHeard = Date()
        phone = PhoneState(json: value)
        if let value, let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]) {
            defaults.set(data, forKey: "phone")
        } else {
            defaults.removeObject(forKey: "phone")
        }
        tick()
    }

    /// The database's copy of the list, taken together with this one; anything newer here is sent back
    private func takeItems(_ value: Any?) {
        remoteItems = BlockItems.decode(value)
        let merged = BlockItems.mergeAll(items, remoteItems)
        if merged != items { items = merged; saveItems() }
        pushItems()
    }

    private func saveItems() {
        if let data = try? JSONSerialization.data(withJSONObject: BlockItems.encode(items)) { defaults.set(data, forKey: "items") }
    }

    /// Sends the items that differ from the database's copy, switch by switch
    private func pushItems() {
        guard paired else { return }
        let changed = BlockItems.changed(items, remote: remoteItems)
        guard !changed.isEmpty else { return }
        var body: [String: Any] = [:]
        for (id, item) in changed { for (k, v) in item.json { body["\(id)/\(k)"] = v } }
        for (id, item) in changed { remoteItems[id] = item }
        let base = firebase
        Task { try? await base.patch("items", body) }
    }

    /// Sends the Mac's requests, retrying until each is written; the phone does them in order.
    private func sendPending() {
        sendTask?.cancel()
        let unsent = pending.filter { !sentIds.contains($0.id) }
        guard !unsent.isEmpty, paired else { return }
        let base = firebase
        sendTask = Task { [weak self] in
            var delay: UInt64 = 2
            for command in unsent {
                while !Task.isCancelled {
                    do {
                        try await base.post("mac/commands", command.json)
                        await MainActor.run { self?.markSent(command.id) }
                        break
                    } catch {
                        await MainActor.run { self?.syncError = (error as? Firebase.HTTPError)?.description ?? "Offline" }
                    }
                    try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
                    delay = min(delay * 2, 60)
                }
            }
        }
    }

    private var sentIds: Set<String> { Set(defaults.stringArray(forKey: "sentCommands") ?? []) }

    private func markSent(_ id: String) {
        let kept = (defaults.stringArray(forKey: "sentCommands") ?? []).suffix(49) + [id]
        defaults.set(Array(kept), forKey: "sentCommands")
    }

    private func setPending(_ list: [Command]) {
        pending = list
        if let data = try? JSONEncoder().encode(list) { defaults.set(data, forKey: "pendingList") }
    }

    /// Applies a request on the Mac at once and queues it for the phone.
    private func request(_ command: Command) {
        setPending(pending + [command])
        sendPending()
    }

    /// The Mac's apps, so they can be blocked from the phone
    private func sendInstalled() {
        let list = InstalledApps.list().map { ["b": $0.id, "n": $0.name] }
        let base = firebase
        Task { try? await base.put("mac/installed", list) }
    }

    /// The phone's apps, so they can be blocked from here
    func loadPhoneApps() {
        guard paired else { return }
        let base = firebase
        Task { [weak self] in
            guard let value = try? await base.get("phoneApps"), let list = value as? [[String: Any]] else { return }
            var apps: [String: String] = [:]
            for app in list { if let p = app["p"] as? String, let n = app["n"] as? String { apps[p] = n } }
            await MainActor.run { self?.phoneApps = apps }
        }
    }

    // --- Lockdown ---

    func start(for ms: Ms?, devices: Int) {
        let at = Date().ms
        request(Command(type: .start, devices: devices, until: ms.map { at + $0 } ?? 0, at: at))
    }

    func add(_ ms: Ms, devices: Int) { request(Command(type: .add, devices: devices, ms: ms, at: Date().ms)) }

    /// Ends lockdown (on until turned off, and the timer). Only after what ending early needs.
    func end(devices: Int) {
        request(Command(type: .end, devices: devices, at: Date().ms))
        cancelWait()
    }

    /// The devices Start and Add time act on from here: both while matched
    var startDevices: Int { state.matched ? Devices.both : Devices.mac }

    // --- Breaks ---

    var breaks: BreakConfig { state.breaks }

    var canBreak: Breaks.Can {
        let s = state
        return Breaks.check(s.breaks, log: s.breakLog, now: now.ms, device: BreakEntry.mac, locked: status.on, breakUntil: s.breakUntil)
    }

    func takeBreak() {
        guard canBreak == .yes else { return }
        request(Command(type: .break, ms: Ms(breaks.minutes) * 60_000, at: Date().ms))
    }

    func endBreak() { request(Command(type: .endBreak, at: Date().ms)) }

    // --- The shared list ---

    /// Blocks or unblocks an app or site on the phone and/or the Mac, from here
    func setItem(kind: BlockItem.Kind, name: String, phone: Bool?, mac: Bool?, pkg: String? = nil, bundle: String? = nil) {
        var phone = phone, mac = mac
        // Matched: both together, where it can be
        if state.matched, let on = phone ?? mac {
            let probe = items[BlockItem.id(kind, name)] ?? BlockItem(kind: kind, name: name, pkg: pkg, bundle: bundle)
            phone = on && (probe.onPhone || pkg != nil)
            mac = on && (probe.onMac || bundle != nil)
        }
        items = BlockItems.set(items, kind: kind, name: name, phone: phone, mac: mac, now: Date().ms, pkg: pkg, bundle: bundle)
        saveItems()
        pushItems()
    }

    // --- What ending early needs (the phone's choice) ---

    var challenge: Challenge { state.challenge }
    var phrase: String { state.phrase.isEmpty ? "I am choosing to give up on what I decided for myself" : state.phrase }

    var wait: Wait.State { Wait.state(requestedAt: waitRequestedAt, waitMinutes: challenge.amount, now: now.ms) }

    func startWait() { setWait(Date().ms) }
    func cancelWait() { setWait(0) }
    private func setWait(_ at: Ms) { waitRequestedAt = at; defaults.set(Int(at), forKey: "waitRequestedAt") }

    func checkPassword(_ input: String) -> Bool { Password.verify(input, hash: state.passwordHash) }
}

/// Carries a stream's value (plain JSON) across to the main actor.
struct UnsafeValue: @unchecked Sendable {
    let value: Any?
    init(_ value: Any?) { self.value = value }
}

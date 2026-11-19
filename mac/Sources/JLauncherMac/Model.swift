import AppKit
import Combine
import LockdownCore

/// Everything the Mac knows: the pairing, the phone's state (streamed live), its own pending
/// request, its blocked apps, and the wait unlock.
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
    @Published private(set) var pending: Command?
    @Published private(set) var macApps: [String]
    @Published private(set) var waitRequestedAt: Ms
    /// Ticks every second so times stay current
    @Published private(set) var now = Date()

    private var streamTask: Task<Void, Never>?
    private var sendTask: Task<Void, Never>?

    var paired: Bool { !url.isEmpty && !key.isEmpty }
    var firebase: Firebase { Firebase(url: url, key: key) }

    private init() {
        url = defaults.string(forKey: "url") ?? ""
        key = defaults.string(forKey: "key") ?? ""
        macApps = defaults.stringArray(forKey: "macApps") ?? []
        waitRequestedAt = Ms(defaults.integer(forKey: "waitRequestedAt"))
        if let data = defaults.data(forKey: "pending") { pending = try? JSONDecoder().decode(Command.self, from: data) }
        // The last state seen, so blocking works straight away and without a connection
        if let data = defaults.data(forKey: "phone"), let json = try? JSONSerialization.jsonObject(with: data) {
            phone = PhoneState(json: json)
        }
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            Task { @MainActor in Model.shared.tick() }
        }
        if paired { connect() }
    }

    // --- The state ---

    var state: PhoneState { Lockdown.effective(phone: phone, pending: pending, now: now.ms) }
    var status: Status { paired ? Lockdown.status(state, now: now.ms) : .off }
    var sites: [String] { state.sites }
    /// The Mac's request hasn't reached the phone yet
    var waitingForPhone: Bool { pending.map { !$0.applied(by: phone) && !$0.expired(now: now.ms) } ?? false }

    private func tick() {
        now = Date()
        if let p = pending, p.applied(by: phone) || p.expired(now: now.ms) { setPending(nil) }
    }

    // --- Pairing ---

    /// Checks the database answers, then saves the pairing and starts listening.
    func pair(url newURL: String, key newKey: String) async -> String? {
        let base = Firebase(url: newURL, key: newKey)
        do {
            let value = try await base.get("phone")
            let apps = try await base.get("mac/apps")
            url = base.base; key = base.key
            defaults.set(url, forKey: "url"); defaults.set(key, forKey: "key")
            setPhone(value)
            // Keep a list set up before, from either side
            let remote = (apps as? [Any])?.compactMap { $0 as? String } ?? []
            setMacApps(Array(Set(macApps + remote)).sorted())
            connect()
            return nil
        } catch {
            return "Couldn't reach the database: \(error)"
        }
    }

    func unpair() {
        streamTask?.cancel(); streamTask = nil
        url = ""; key = ""; phone = nil; lastHeard = nil; syncError = nil
        setPending(nil)
        for k in ["url", "key", "phone"] { defaults.removeObject(forKey: k) }
    }

    // --- Sync ---

    private func connect() {
        streamTask?.cancel()
        let base = firebase
        streamTask = Task { [weak self] in
            var delay: UInt64 = 2
            while !Task.isCancelled {
                do {
                    try await base.stream("phone") { value in
                        Task { @MainActor in
                            guard let self, self.key == base.key else { return }
                            self.setPhone(value)
                            self.syncError = nil
                        }
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
        sendPending()
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

    /// Sends the Mac's request, retrying until it's written.
    private func sendPending() {
        sendTask?.cancel()
        guard let command = pending, paired else { return }
        let base = firebase
        sendTask = Task { [weak self] in
            var delay: UInt64 = 2
            while !Task.isCancelled {
                do {
                    try await base.put("mac/command", command.json)
                    return
                } catch {
                    await MainActor.run { self?.syncError = (error as? Firebase.HTTPError)?.description ?? "Offline" }
                }
                try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
                delay = min(delay * 2, 60)
            }
        }
    }

    private func setPending(_ command: Command?) {
        pending = command
        if let command, let data = try? JSONEncoder().encode(command) { defaults.set(data, forKey: "pending") }
        else { defaults.removeObject(forKey: "pending") }
    }

    /// Applies a request on the Mac at once and sends it to the phone. The phone only sees the latest
    /// request, so one made before it took in the last is merged with it into one with both effects.
    private func request(_ command: Command) {
        var next = command
        if let old = pending, !old.applied(by: phone), !old.expired(now: command.at), old.type != .end, command.type != .end {
            let afterOld = Lockdown.effective(phone: phone, pending: old, now: command.at)
            let afterBoth = Lockdown.effective(phone: afterOld, pending: command, now: command.at)
            next = afterBoth.manualOn
                ? Command(type: .start, until: 0, at: command.at)
                : Command(type: .start, until: afterBoth.timedUntil, at: command.at)
        }
        setPending(next)
        sendPending()
    }

    func start(for ms: Ms?) {
        let at = Date().ms
        request(Command(type: .start, until: ms.map { at + $0 } ?? 0, at: at))
    }

    func add(_ ms: Ms) { request(Command(type: .add, ms: ms, at: Date().ms)) }

    /// Ends lockdown (on until turned off, and the timer). Only after the unlock has been passed.
    func end() {
        request(Command(type: .end, at: Date().ms))
        cancelWait()
    }

    // --- The unlock (the phone's method) ---

    enum Unlock { case nothing, password, wait }

    var unlock: Unlock {
        switch state.unlock {
        case "password" where !state.passwordHash.isEmpty: return .password
        case "wait": return .wait
        default: return .nothing
        }
    }

    var wait: Wait.State { Wait.state(requestedAt: waitRequestedAt, waitMinutes: state.waitMinutes, now: now.ms) }

    func startWait() { setWait(Date().ms) }
    func cancelWait() { setWait(0) }
    private func setWait(_ at: Ms) { waitRequestedAt = at; defaults.set(Int(at), forKey: "waitRequestedAt") }

    func checkPassword(_ input: String) -> Bool { Password.verify(input, hash: state.passwordHash) }

    // --- The Mac's blocked apps ---

    func setMacApps(_ apps: [String]) {
        macApps = apps.sorted()
        defaults.set(macApps, forKey: "macApps")
        guard paired else { return }
        let base = firebase, list = macApps
        Task { try? await base.put("mac/apps", list) }
    }
}

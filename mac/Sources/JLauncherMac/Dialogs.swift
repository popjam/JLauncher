import AppKit
import LockdownCore
import SwiftUI

/// Centred popups in the phone's style: a black card with a light title.
@MainActor
enum Dialog {
    private static var window: NSWindow?

    static func show<V: View>(width: CGFloat = 380, @ViewBuilder _ content: () -> V) {
        close()
        let host = NSHostingView(rootView: content().frame(width: width).environment(\.colorScheme, .dark))
        let window = KeyWindow(contentRect: NSRect(origin: .zero, size: host.fittingSize), styleMask: [.borderless], backing: .buffered, defer: false)
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = true
        window.level = .floating
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        window.isMovableByWindowBackground = true
        window.onEscape = { close() }
        window.contentView = host
        window.center()
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        Self.window = window
    }

    /// Fits the window to its content after it changes size
    static func refit() {
        guard let window, let view = window.contentView else { return }
        let size = view.fittingSize
        var frame = window.frame
        frame.origin.y += frame.height - size.height
        frame.size = size
        window.setFrame(frame, display: true)
    }

    static func close() {
        window?.orderOut(nil)
        window = nil
    }
}

struct Title: View {
    let text: String
    var body: some View { Text(text).font(Style.title(22)).foregroundStyle(.white).padding(.bottom, 14) }
}

struct Line: View {
    let text: String
    var color = Style.faint
    var body: some View { Text(text).font(Style.line).foregroundStyle(color).fixedSize(horizontal: false, vertical: true) }
}

// --- Pairing ---

struct PairView: View {
    @State private var url = ""
    @State private var key = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        Card {
            Title(text: "Pair with phone")
            Line(text: "On the phone: Lockdown › Devices › Mac").padding(.bottom, 12)
            BoxField(placeholder: "Server", text: $url).padding(.bottom, 8)
            BoxField(placeholder: "Pairing code", text: $key, onSubmit: pair)
            if let error { Line(text: error, color: Style.red).padding(.top, 10) }
            BoxButton(title: busy ? "Checking…" : "Pair", enabled: !busy, action: pair).padding(.top, 18)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }

    private func pair() {
        let url = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Firebase.validURL(url) else { error = "Not a server address"; Dialog.refit(); return }
        guard Firebase.validKey(key) else { error = "The pairing code is 40 letters and numbers"; Dialog.refit(); return }
        busy = true
        Task {
            let problem = await Model.shared.pair(url: url, key: key)
            busy = false
            if let problem { error = problem; Dialog.refit() } else { Dialog.close() }
        }
    }
}

// --- Unlocking ---

/// Asks for what the phone says ending early needs (or `challenge`, for breaks), then does `then`.
@MainActor
enum Unlocking {
    static func run(title: String, action: String, challenge: Challenge? = nil, then: @escaping () -> Void) {
        let model = Model.shared
        let c = challenge ?? model.challenge
        switch c.type {
        case .nothing:
            Dialog.show { ConfirmView(title: title, action: action, then: then) }
        case .password where !model.state.passwordHash.isEmpty:
            Dialog.show { PasswordView(title: title, action: action, then: then) }
        case .password:
            Dialog.show { ConfirmView(title: title, action: action, then: then) }
        case .wait:
            switch model.wait {
            case .ready: then()
            case .none, .waiting: Dialog.show { WaitView(then: then) }
            }
        case .hold:
            Dialog.show { HoldView(title: title, seconds: max(1, c.amount), then: then) }
        case .taps:
            Dialog.show { TapsView(title: title, count: max(1, c.amount), then: then) }
        case .phrase:
            Dialog.show(width: 440) { TypeView(title: title, texts: Array(repeating: model.phrase, count: max(1, c.amount)), ignoreSpaces: false, then: then) }
        case .random:
            Dialog.show(width: 440) { TypeView(title: title, texts: [Challenge.randomText(max(1, c.amount))], ignoreSpaces: true, then: then) }
        case .never:
            Dialog.show {
                Card {
                    Title(text: "Can't be ended early")
                    BoxButton(title: "OK") { Dialog.close() }
                }
            }
        }
    }
}

struct ConfirmView: View {
    let title: String
    let action: String
    let then: () -> Void
    var body: some View {
        Card {
            Title(text: title)
            BoxButton(title: action) { Dialog.close(); then() }.padding(.top, 4)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }
}

struct PasswordView: View {
    let title: String
    let action: String
    let then: () -> Void
    @State private var password = ""
    @State private var wrong = false

    var body: some View {
        Card {
            Title(text: title)
            BoxField(placeholder: "Password", text: $password, secure: true, onSubmit: check)
            if wrong { Line(text: "Wrong password", color: Style.red).padding(.top, 10) }
            BoxButton(title: action, action: check).padding(.top, 18)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }

    private func check() {
        if Model.shared.checkPassword(password) { Dialog.close(); then() }
        else { wrong = true; password = ""; Dialog.refit() }
    }
}

/// A countdown that has to stay in front; leaving it starts it again.
struct HoldView: View {
    let title: String
    let seconds: Int
    let then: () -> Void
    @State private var left: Int = 0
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Card {
            Title(text: title)
            Line(text: "Keep this open. Leaving starts it again.")
            Text(Format.clock(Ms(left) * 1000)).font(.system(size: 40, weight: .light)).foregroundStyle(.white)
                .frame(maxWidth: .infinity).padding(.vertical, 14)
            BoxButton(title: "Next", enabled: left <= 0) { Dialog.close(); then() }
            TextButton(title: "Cancel") { Dialog.close() }
        }
        .onAppear { left = seconds }
        .onReceive(timer) { _ in
            // Only while this is the window in front
            if NSApp.isActive && NSApp.keyWindow is KeyWindow { if left > 0 { left -= 1 } } else { left = seconds }
        }
    }
}

/// A button clicked `count` times.
struct TapsView: View {
    let title: String
    let count: Int
    let then: () -> Void
    @State private var done = 0

    var body: some View {
        Card {
            Title(text: title)
            Text("\(count - done) left").font(.system(size: 20, weight: .light)).foregroundStyle(.white).frame(maxWidth: .infinity)
            Button {
                done += 1
                if done >= count { Dialog.close(); then() }
            } label: {
                Text("Tap").font(.system(size: 20, weight: .light)).foregroundStyle(.white)
                    .frame(width: 130, height: 130)
                    .overlay(Circle().strokeBorder(Style.edge, lineWidth: 1.5))
                    .contentShape(Circle())
            }
            .buttonStyle(.plain).frame(maxWidth: .infinity).padding(.vertical, 14)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }
}

/// Each of `texts` typed out in turn, exactly; pasting doesn't count.
struct TypeView: View {
    let title: String
    let texts: [String]
    let ignoreSpaces: Bool
    let then: () -> Void
    @State private var index = 0
    @State private var typed = ""
    @State private var wrong = false

    var body: some View {
        Card {
            Title(text: title)
            Text(texts[min(index, texts.count - 1)]).font(.system(size: 15)).foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true).padding(.bottom, 8)
            if texts.count > 1 { Line(text: "\(index + 1) of \(texts.count)").padding(.bottom, 8) }
            BoxField(placeholder: "Type it here", text: $typed, onSubmit: check)
                .onChange(of: typed) { old, new in
                    // A jump of many characters at once is a paste
                    if new.count - old.count > 3 { typed = old }
                }
            if wrong { Line(text: "Doesn't match", color: Style.red).padding(.top, 10) }
            BoxButton(title: "OK", action: check).padding(.top, 18)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }

    private func check() {
        guard Challenge.matches(typed, texts[index], ignoreSpaces: ignoreSpaces) else { wrong = true; Dialog.refit(); return }
        wrong = false
        typed = ""
        index += 1
        if index >= texts.count { Dialog.close(); then() } else { Dialog.refit() }
    }
}

struct WaitView: View {
    @ObservedObject private var model = Model.shared
    let then: () -> Void

    var body: some View {
        Card {
            switch model.wait {
            case .none:
                Title(text: "Wait to unlock")
                Line(text: "Lockdown unlocks in \(Format.duration(Ms(model.challenge.amount) * 60_000)), for 10m")
                BoxButton(title: "Start") { model.startWait(); Dialog.close() }.padding(.top, 18)
                TextButton(title: "Cancel") { Dialog.close() }
            case .waiting(let left):
                Title(text: "Unlocks in \(Format.duration(left))")
                BoxButton(title: "OK") { Dialog.close() }.padding(.top, 4)
                TextButton(title: "Stop waiting") { model.cancelWait(); Dialog.close() }
            case .ready:
                Title(text: "Unlocked")
                BoxButton(title: "Next") { Dialog.close(); then() }.padding(.top, 4)
                TextButton(title: "Cancel") { Dialog.close() }
            }
        }
    }
}

// --- What's blocked ---

/// The one list: each app and site, blocked on every device where it can be. Adding is done at once;
/// taking anything off during lockdown needs what ending early needs.
/// Apps and websites: every site on the list and every app on the Mac and the phone, each with a button
/// for each device, filled where it's blocked. Blocking is done straight away; unblocking during lockdown
/// needs the unlock, unless it was blocked since the window opened.
struct ItemsView: View {
    @ObservedObject private var model = Model.shared
    @State private var search = ""
    /// What was blocked when the window opened, which goes first; kept so rows don't move while they're changed
    @State private var firstBlocked: Set<String>?
    private let macApps = InstalledApps.list()
    private static var blockedHere = Set<String>()

    private struct Row: Identifiable {
        let id: String
        let kind: BlockItem.Kind
        let name: String
        let pkg: String?
        let bundle: String?
        let tvName: String?
        let item: BlockItem?
        let icon: NSImage?

        func with(pkg: String? = nil, bundle: String? = nil, icon: NSImage? = nil) -> Row {
            Row(id: id, kind: kind, name: name, pkg: self.pkg ?? pkg, bundle: self.bundle ?? bundle, tvName: tvName, item: item, icon: self.icon ?? icon)
        }
    }

    private var allRows: [Row] {
        var rows: [String: Row] = [:]
        // What's blocked, and what was when the window opened, so a site turned off everywhere stays until it's closed
        for item in model.items.values where item.blocked || (firstBlocked?.contains(item.id) ?? false) {
            rows[item.id] = Row(id: item.id, kind: item.kind, name: item.name, pkg: item.pkg, bundle: item.bundle, tvName: item.tvName, item: item,
                                icon: item.bundle.flatMap { b in macApps.first { $0.id == b }?.icon })
        }
        for app in macApps {
            let id = BlockItem.id(.app, app.name)
            rows[id] = rows[id]?.with(bundle: app.id, icon: app.icon)
                ?? Row(id: id, kind: .app, name: app.name, pkg: nil, bundle: app.id, tvName: nil, item: model.items[id], icon: app.icon)
        }
        for (pkg, name) in model.phoneApps {
            let id = BlockItem.id(.app, name)
            rows[id] = rows[id]?.with(pkg: pkg) ?? Row(id: id, kind: .app, name: name, pkg: pkg, bundle: nil, tvName: nil, item: model.items[id], icon: nil)
        }
        return Array(rows.values)
    }

    private var rows: [Row] {
        let first = firstBlocked ?? []
        return allRows.filter { matchesSearch($0.name) && Model.Device.allCases.contains { d in can($0, d) } }.sorted { a, b in
            let ka = (first.contains(a.id) ? 0 : 1, a.kind == .site ? 0 : 1, a.name.lowercased())
            let kb = (first.contains(b.id) ? 0 : 1, b.kind == .site ? 0 : 1, b.name.lowercased())
            return ka < kb
        }
    }

    private func matchesSearch(_ name: String) -> Bool { search.isEmpty || name.localizedCaseInsensitiveContains(search) }

    /// Whether it's blocked on a device now
    private func on(_ row: Row, _ device: Model.Device) -> Bool {
        let item = model.items[row.id]
        switch device {
        case .phone: return item?.phone ?? false
        case .mac: return item?.mac ?? false
        case .tv: return item?.tv ?? false
        }
    }

    /// Whether it can be blocked on a device: sites on the phone and the Mac, apps where they are (the
    /// phone's only while it blocks a list)
    private func can(_ row: Row, _ device: Model.Device) -> Bool {
        let site = row.kind == .site
        switch device {
        case .phone: return site || (row.pkg != nil && !(model.state.allowOnly))
        case .mac: return site || row.bundle != nil
        case .tv: return !site && row.tvName != nil
        }
    }

    var body: some View {
        Card {
            Title(text: "Apps and websites")
            BoxField(placeholder: "Search apps, or type a site and press Return", text: $search, onSubmit: addSite).padding(.bottom, 8)
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(rows) { row in
                        HStack(spacing: 10) {
                            if let icon = row.icon { Image(nsImage: icon).resizable().frame(width: 20, height: 20) }
                            else { Image(systemName: row.kind == .site ? "globe" : "iphone").frame(width: 20, height: 20).foregroundStyle(Style.faint) }
                            Text(row.name).font(.system(size: 15)).foregroundStyle(.white).lineLimit(1)
                            Spacer()
                            ForEach(Model.Device.allCases, id: \.self) { device in
                                if can(row, device) { DevicePill(label: device.rawValue, on: on(row, device)) { toggle(row, device) } }
                                else { Color.clear.frame(width: 58, height: 24) }
                            }
                        }
                        .padding(.vertical, 7)
                        Divider().overlay(Color.white.opacity(0.12))
                    }
                }
            }
            .frame(height: 380)
            BoxButton(title: "Close") { Dialog.close() }.padding(.top, 14)
        }
        .onAppear {
            model.loadPhoneApps()
            if firstBlocked == nil { firstBlocked = Set(model.items.values.filter(\.blocked).map(\.id)) }
        }
    }

    private func toggle(_ row: Row, _ device: Model.Device) {
        let blocking = !on(row, device)
        let key = row.id + ":" + device.rawValue
        let change = {
            Model.shared.block(kind: row.kind, name: row.name, on: blocking, device: device, pkg: row.pkg, bundle: row.bundle, tvName: row.tvName)
            if blocking { Self.blockedHere.insert(key) } else { Self.blockedHere.remove(key) }
        }
        // Unblocking during lockdown loosens it; not at all while a timer runs on any device
        if !blocking && !Self.blockedHere.contains(key) && (Model.shared.status.on || Model.shared.phone?.phoneOn == true) {
            if let until = Lockdown.lockedUntil(Model.shared.state, now: Date().ms) {
                return Dialog.show {
                    Card {
                        Title(text: "Locked until \(Format.time(until))")
                        BoxButton(title: "OK") { Dialog.show(width: 520) { ItemsView() } }
                    }
                }
            }
            Unlocking.run(title: "Unblock \(row.name) on the \(device.rawValue)?", action: "Unblock") {
                change()
                Dialog.show(width: 520) { ItemsView() }
            }
        } else { change() }
    }

    private func addSite() {
        guard let host = Sites.host(search) else { return }
        model.block(kind: .site, name: host, on: true)
        for device in Model.Device.allCases { Self.blockedHere.insert(BlockItem.id(.site, host) + ":" + device.rawValue) }
        search = ""
    }
}

/// A device's button on an item: filled while it's blocked there, outlined otherwise.
struct DevicePill: View {
    let label: String
    let on: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label).font(.system(size: 12.5)).foregroundStyle(on ? Color.black : .white.opacity(0.8))
                .frame(width: 58, height: 24)
                .background(Capsule().fill(on ? Style.accent : Color.clear))
                .overlay(Capsule().strokeBorder(on ? Color.clear : Color.white.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label + (on ? ", blocked" : ""))
    }
}

struct InstalledApp: Identifiable {
    let id: String
    let name: String
    let icon: NSImage
}

enum InstalledApps {
    static func list() -> [InstalledApp] {
        let fm = FileManager.default
        let dirs = ["/Applications", "/Applications/Utilities", "/System/Applications", "/System/Applications/Utilities",
                    NSHomeDirectory() + "/Applications"]
        var seen = Set<String>()
        var apps: [InstalledApp] = []
        for dir in dirs {
            for name in (try? fm.contentsOfDirectory(atPath: dir)) ?? [] where name.hasSuffix(".app") {
                let path = dir + "/" + name
                guard let bundle = Bundle(path: path), let id = bundle.bundleIdentifier,
                      !Blocker.protected.contains(id), seen.insert(id).inserted else { continue }
                apps.append(InstalledApp(id: id, name: fm.displayName(atPath: path).replacingOccurrences(of: ".app", with: ""),
                                         icon: NSWorkspace.shared.icon(forFile: path)))
            }
        }
        // Running apps installed elsewhere
        for app in NSWorkspace.shared.runningApplications where app.activationPolicy == .regular {
            guard let id = app.bundleIdentifier, !Blocker.protected.contains(id), seen.insert(id).inserted else { continue }
            apps.append(InstalledApp(id: id, name: app.localizedName ?? id, icon: app.icon ?? NSImage()))
        }
        return apps
    }
}

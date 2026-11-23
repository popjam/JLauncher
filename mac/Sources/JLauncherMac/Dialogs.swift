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
struct ItemsView: View {
    @ObservedObject private var model = Model.shared
    @State private var search = ""
    private let macApps = InstalledApps.list()

    private struct Row: Identifiable {
        let id: String
        let kind: BlockItem.Kind
        let name: String
        let pkg: String?
        let bundle: String?
        let blocked: Bool
        let icon: NSImage?

        func with(pkg: String? = nil, bundle: String? = nil, icon: NSImage? = nil) -> Row {
            Row(id: id, kind: kind, name: name, pkg: self.pkg ?? pkg, bundle: self.bundle ?? bundle, blocked: blocked, icon: self.icon ?? icon)
        }
    }

    private var rows: [Row] {
        var rows: [String: Row] = [:]
        for item in model.items.values where item.blocked || (!search.isEmpty && matchesSearch(item.name)) {
            guard matchesSearch(item.name) else { continue }
            rows[item.id] = Row(id: item.id, kind: item.kind, name: item.name, pkg: item.pkg, bundle: item.bundle, blocked: item.blocked,
                                icon: item.bundle.flatMap { b in macApps.first { $0.id == b }?.icon })
        }
        guard !search.isEmpty else { return sorted(rows) }
        // Apps not on the list yet, from the Mac and the phone, one per name
        for app in macApps where matchesSearch(app.name) {
            let id = BlockItem.id(.app, app.name)
            rows[id] = rows[id]?.with(bundle: app.id, icon: app.icon)
                ?? Row(id: id, kind: .app, name: app.name, pkg: nil, bundle: app.id, blocked: false, icon: app.icon)
        }
        for (pkg, name) in model.phoneApps where matchesSearch(name) {
            let id = BlockItem.id(.app, name)
            rows[id] = rows[id]?.with(pkg: pkg) ?? Row(id: id, kind: .app, name: name, pkg: pkg, bundle: nil, blocked: false, icon: nil)
        }
        return sorted(rows)
    }

    private func sorted(_ rows: [String: Row]) -> [Row] {
        rows.values.sorted { a, b in
            let ka = (a.blocked ? 0 : 1, a.kind == .site ? 0 : 1, a.name.lowercased())
            let kb = (b.blocked ? 0 : 1, b.kind == .site ? 0 : 1, b.name.lowercased())
            return ka < kb
        }
    }

    private func matchesSearch(_ name: String) -> Bool { search.isEmpty || name.localizedCaseInsensitiveContains(search) }

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
                            BlockPill(on: row.blocked) { toggle(row) }
                        }
                        .padding(.vertical, 7)
                        Divider().overlay(Color.white.opacity(0.12))
                    }
                }
            }
            .frame(height: 380)
            BoxButton(title: "Close") { Dialog.close() }.padding(.top, 14)
        }
        .onAppear { model.loadPhoneApps() }
    }

    private func toggle(_ row: Row) {
        let on = row.blocked
        let change = { Model.shared.block(kind: row.kind, name: row.name, on: !on, pkg: row.pkg, bundle: row.bundle) }
        // Taking something off during lockdown loosens it; not at all while a timer runs on any device
        if on && (Model.shared.status.on || Model.shared.phone?.phoneOn == true) {
            if let until = Lockdown.lockedUntil(Model.shared.state, now: Date().ms) {
                return Dialog.show {
                    Card {
                        Title(text: "Locked until \(Format.time(until))")
                        BoxButton(title: "OK") { Dialog.show(width: 460) { ItemsView() } }
                    }
                }
            }
            Unlocking.run(title: "Unblock \(row.name)?", action: "Unblock") {
                change()
                Dialog.show(width: 460) { ItemsView() }
            }
        } else { change() }
    }

    private func addSite() {
        guard let host = Sites.host(search) else { return }
        model.block(kind: .site, name: host, on: true)
        search = ""
    }
}

/// An item's switch: "Blocked", filled, while it's blocked; "Block" otherwise.
struct BlockPill: View {
    let on: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(on ? "Blocked" : "Block").font(.system(size: 12.5)).foregroundStyle(on ? Color.black : .white.opacity(0.8))
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Capsule().fill(on ? Color(red: 0x8F / 255, green: 0xB3 / 255, blue: 1) : Color.clear))
                .overlay(Capsule().strokeBorder(on ? Color.clear : Color.white.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
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

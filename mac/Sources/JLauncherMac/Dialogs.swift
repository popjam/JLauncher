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
            Line(text: "On the phone: Lockdown → Extras → Mac.").padding(.bottom, 12)
            BoxField(placeholder: "Database URL", text: $url).padding(.bottom, 8)
            BoxField(placeholder: "Pairing key", text: $key, onSubmit: pair)
            if let error { Line(text: error, color: Style.red).padding(.top, 10) }
            BoxButton(title: busy ? "Checking…" : "Pair", enabled: !busy, action: pair).padding(.top, 18)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }

    private func pair() {
        let url = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Firebase.validURL(url) else { error = "That isn't a Firebase database URL."; Dialog.refit(); return }
        guard Firebase.validKey(key) else { error = "The key is 40 letters and numbers."; Dialog.refit(); return }
        busy = true
        Task {
            let problem = await Model.shared.pair(url: url, key: key)
            busy = false
            if let problem { error = problem; Dialog.refit() } else { Dialog.close() }
        }
    }
}

// --- Unlocking ---

/// Asks for the phone's unlock, then does `then`: the password, the wait, or a plain confirm.
@MainActor
enum Unlocking {
    static func run(title: String, action: String, then: @escaping () -> Void) {
        let model = Model.shared
        switch model.unlock {
        case .nothing:
            Dialog.show { ConfirmView(title: title, action: action, then: then) }
        case .password:
            Dialog.show { PasswordView(title: title, action: action, then: then) }
        case .wait:
            switch model.wait {
            case .ready: then()
            case .none, .waiting: Dialog.show { WaitView(then: then) }
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

struct WaitView: View {
    @ObservedObject private var model = Model.shared
    let then: () -> Void

    var body: some View {
        Card {
            switch model.wait {
            case .none:
                Title(text: "Wait to unlock")
                Line(text: "Lockdown unlocks in \(Format.duration(Ms(model.state.waitMinutes) * 60_000)), for 10m.")
                BoxButton(title: "Start") { model.startWait(); Dialog.close() }.padding(.top, 18)
                TextButton(title: "Cancel") { Dialog.close() }
            case .waiting(let left):
                Title(text: "Unlocks in \(Format.duration(left))")
                BoxButton(title: "OK") { Dialog.close() }.padding(.top, 4)
                TextButton(title: "Stop waiting") { model.cancelWait(); Dialog.close() }
            case .ready:
                Title(text: "Unlocked")
                BoxButton(title: "Continue") { Dialog.close(); then() }.padding(.top, 4)
                TextButton(title: "Cancel") { Dialog.close() }
            }
        }
    }
}

// --- The Mac's blocked apps ---

struct AppsView: View {
    @ObservedObject private var model = Model.shared
    @State private var search = ""
    private let apps = InstalledApps.list()

    var body: some View {
        let locked = model.status.on
        let shown = apps.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }
            .sorted { (model.macApps.contains($0.id) ? 0 : 1, $0.name.lowercased()) < (model.macApps.contains($1.id) ? 0 : 1, $1.name.lowercased()) }
        Card {
            Title(text: "Blocked Mac apps")
            if locked { Line(text: "Apps can be added, not removed, during lockdown.").padding(.bottom, 10) }
            BoxField(placeholder: "Search", text: $search).padding(.bottom, 8)
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(shown) { app in
                        let on = model.macApps.contains(app.id)
                        Button {
                            if on { if !locked { model.setMacApps(model.macApps.filter { $0 != app.id }) } }
                            else { model.setMacApps(model.macApps + [app.id]) }
                        } label: {
                            HStack(spacing: 10) {
                                Image(nsImage: app.icon).resizable().frame(width: 22, height: 22)
                                Text(app.name).font(.system(size: 15)).foregroundStyle(.white)
                                Spacer()
                                Image(systemName: on ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(on ? Style.red : Style.faint)
                            }
                            .padding(.vertical, 7).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .opacity(on && locked ? 0.5 : 1)
                        Divider().overlay(Color.white.opacity(0.12))
                    }
                }
            }
            .frame(height: 360)
            BoxButton(title: "Done") { Dialog.close() }.padding(.top, 14)
        }
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

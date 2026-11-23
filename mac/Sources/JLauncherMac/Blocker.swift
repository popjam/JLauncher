import AppKit
import ApplicationServices
import LockdownCore

/// Blocks the Mac's chosen apps and the phone's sites while lockdown is on: closes the app (or the
/// browser tab) and shows the sheet.
@MainActor
final class Blocker {
    static let shared = Blocker()
    private let model = Model.shared
    private var lastBlock = Date.distantPast

    /// Never blocked, so the Mac stays usable and this app can't block itself
    nonisolated static let protected: Set<String> = [Bundle.main.bundleIdentifier ?? "", "com.apple.finder", "com.apple.systempreferences", "com.apple.loginwindow"]

    nonisolated static let browsers: Set<String> = [
        "com.apple.Safari", "com.apple.SafariTechnologyPreview",
        "com.google.Chrome", "com.google.Chrome.beta", "com.google.Chrome.dev", "com.google.Chrome.canary",
        "org.chromium.Chromium", "com.microsoft.edgemac", "com.microsoft.edgemac.Beta", "com.brave.Browser",
        "company.thebrowser.Browser", "com.vivaldi.Vivaldi", "com.operasoftware.Opera", "com.operasoftware.OperaGX",
        "org.mozilla.firefox", "org.mozilla.firefoxdeveloperedition", "org.mozilla.nightly", "app.zen-browser.zen",
        "com.kagi.kagimacOS", "com.duckduckgo.macos.browser", "ai.perplexity.comet", "net.imput.helium",
    ]

    static var accessibilityOn: Bool { AXIsProcessTrusted() }

    static func askForAccessibility() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        AXIsProcessTrustedWithOptions(options)
    }

    func start() {
        let center = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.didLaunchApplicationNotification, NSWorkspace.didActivateApplicationNotification] {
            center.addObserver(forName: name, object: nil, queue: .main) { note in
                let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
                MainActor.assumeIsolated { Blocker.shared.checkApp(app) }
            }
        }
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            MainActor.assumeIsolated { Blocker.shared.tick() }
        }
    }

    private func tick() {
        guard model.blocking else { return }
        // Apps already open when lockdown starts
        if !model.macApps.isEmpty {
            for app in NSWorkspace.shared.runningApplications where model.macApps.contains(app.bundleIdentifier ?? "") {
                checkApp(app)
            }
        }
        checkBrowser()
    }

    // --- Apps ---

    private func checkApp(_ app: NSRunningApplication?) {
        guard let app, let id = app.bundleIdentifier, model.blocking, model.macApps.contains(id),
              !Self.protected.contains(id), !app.isTerminated else { return }
        let name = app.localizedName ?? id
        app.terminate()
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { if !app.isTerminated { app.forceTerminate() } }
        if !(Sheet.shared.showing && Date().timeIntervalSince(lastBlock) < 2) {
            lastBlock = Date()
            Sheet.shared.show(title: name, lines: reasonLines())
        }
    }

    // --- Websites ---

    private func checkBrowser() {
        guard !model.sites.isEmpty, Self.accessibilityOn, !Sheet.shared.showing,
              Date().timeIntervalSince(lastBlock) > 1.5,
              let app = NSWorkspace.shared.frontmostApplication, let id = app.bundleIdentifier,
              Self.browsers.contains(id) else { return }
        guard let address = BrowserAddress.read(pid: app.processIdentifier, bundleID: id),
              let host = Sites.host(address), Sites.blocked(host, by: model.sites) else { return }
        lastBlock = Date()
        closeTab(pid: app.processIdentifier)
        Sheet.shared.show(title: host, lines: reasonLines())
    }

    /// Cmd-W to the browser: closes the tab with the blocked site.
    private func closeTab(pid: pid_t) {
        let source = CGEventSource(stateID: .hidSystemState)
        let w: CGKeyCode = 13
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: source, virtualKey: w, keyDown: down)
            event?.flags = .maskCommand
            event?.postToPid(pid)
        }
    }

    /// "Blocked until 3:45 pm", or "Blocked" and "Until lockdown is turned off"
    func reasonLines() -> [String] {
        let status = model.status
        if let until = status.until { return ["Blocked until \(Format.time(until))"] }
        if status.why == .manual { return ["Blocked", "Until lockdown is turned off"] }
        return ["Blocked"]
    }
}

/// Reads the address of the page in a browser's front window, through Accessibility.
enum BrowserAddress {
    private static var madeAccessible = Set<pid_t>()

    static func read(pid: pid_t, bundleID: String) -> String? {
        let app = AXUIElementCreateApplication(pid)
        AXUIElementSetMessagingTimeout(app, 0.3)
        // Chromium-based browsers only build their web page tree when asked
        if !madeAccessible.contains(pid), !bundleID.hasPrefix("com.apple.") {
            madeAccessible.insert(pid)
            AXUIElementSetAttributeValue(app, "AXManualAccessibility" as CFString, kCFBooleanTrue)
        }
        guard let window: AXUIElement = attribute(app, kAXFocusedWindowAttribute) else { return nil }

        // The page itself (its URL), or failing that the address bar
        var queue: [AXUIElement] = [window]
        var fields: [(score: Int, value: String)] = []
        var visited = 0
        while !queue.isEmpty, visited < 600 {
            let element = queue.removeFirst()
            visited += 1
            let role: String = attribute(element, kAXRoleAttribute) ?? ""
            if role == "AXWebArea" {
                if let url: URL = attribute(element, "AXURL"), let scheme = url.scheme, scheme.hasPrefix("http") {
                    return url.absoluteString
                }
                continue // never the page's own content
            }
            if role == kAXTextFieldRole || role == kAXComboBoxRole {
                if let value: String = attribute(element, kAXValueAttribute), Sites.host(value) != nil {
                    let label = [attribute(element, kAXDescriptionAttribute) as String?, attribute(element, "AXIdentifier") as String?,
                                 attribute(element, kAXTitleAttribute) as String?].compactMap { $0 }.joined(separator: " ").lowercased()
                    let named = ["address", "url", "location", "search"].contains { label.contains($0) }
                    fields.append((named ? 1 : 0, value))
                }
                continue
            }
            if let children: [AXUIElement] = attribute(element, kAXChildrenAttribute) { queue.append(contentsOf: children) }
        }
        return fields.max { $0.score < $1.score }?.value
    }

    private static func attribute<T>(_ element: AXUIElement, _ name: String) -> T? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, name as CFString, &value) == .success, let value else { return nil }
        if T.self == AXUIElement.self { return CFGetTypeID(value) == AXUIElementGetTypeID() ? (value as! T) : nil }
        if T.self == [AXUIElement].self { return value as? [AXUIElement] as? T }
        if T.self == URL.self { return (value as? URL) as? T ?? (value as? String).flatMap(URL.init(string:)) as? T }
        return value as? T
    }
}

enum Format {
    static func time(_ ms: Ms) -> String {
        let date = Date(ms: ms)
        let f = DateFormatter()
        f.dateFormat = Calendar.current.isDateInToday(date) ? "h:mm a" : "EEE h:mm a"
        f.amSymbol = "am"; f.pmSymbol = "pm"
        return f.string(from: date)
    }

    /// "4:05" or "1:05:00", for a countdown
    static func clock(_ ms: Ms) -> String {
        let s = max(0, (ms + 999) / 1000)
        return s >= 3600 ? String(format: "%lld:%02lld:%02lld", s / 3600, s / 60 % 60, s % 60) : String(format: "%lld:%02lld", s / 60, s % 60)
    }

    /// "45m", "2h 5m", "3h"
    static func duration(_ ms: Ms) -> String {
        let minutes = Int((ms + 59_999) / 60_000)
        if minutes < 60 { return "\(minutes)m" }
        return minutes % 60 == 0 ? "\(minutes / 60)h" : "\(minutes / 60)h \(minutes % 60)m"
    }
}

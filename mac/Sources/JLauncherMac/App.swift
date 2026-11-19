import AppKit
import LockdownCore
import ServiceManagement

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var item: NSStatusItem!
    private let model = Model.shared

    func applicationDidFinishLaunching(_ notification: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu()
        menu.delegate = self
        menu.autoenablesItems = false
        item.menu = menu
        updateIcon()
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            MainActor.assumeIsolated { (NSApp.delegate as? AppDelegate)?.updateIcon() }
        }
        Blocker.shared.start()
        if !model.paired { Dialog.show { PairView() } }
    }

    /// Quitting while lockdown is on needs the unlock.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if quitting || !model.status.on { return .terminateNow }
        Unlocking.run(title: "Quit during lockdown?", action: "Quit") { [weak self] in
            self?.quitting = true
            NSApp.terminate(nil)
        }
        return .terminateCancel
    }
    private var quitting = false

    private func updateIcon() {
        let status = model.status
        item.button?.image = PixelLock.image(locked: status.on)
        // A short countdown beside the lock while timed
        if status.why == .timed, let until = status.until {
            item.button?.title = " " + Format.duration(until - Date().ms)
        } else {
            item.button?.title = ""
        }
        item.button?.imagePosition = .imageLeading
    }

    // --- The menu ---

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let status = model.status
        guard model.paired else {
            menu.addItem(label("Not paired"))
            menu.addItem(.separator())
            menu.addItem(action("Pair with phone…") { Dialog.show { PairView() } })
            menu.addItem(.separator())
            menu.addItem(action("Quit") { NSApp.terminate(nil) })
            return
        }

        menu.addItem(label(headline(status)))
        if model.waitingForPhone { menu.addItem(label("Waiting for phone")) }
        if let error = model.syncError { menu.addItem(label(error)) }
        else if let phone = model.phone, phone.updatedAt > 0 { menu.addItem(label("Phone updated \(Format.time(phone.updatedAt))")) }
        menu.addItem(.separator())

        if !status.on {
            menu.addItem(submenu("Start", items: Self.lengths.map { (title, ms) in action(title) { [model] in model.start(for: ms) } }
                + [.separator(), action("Until I turn it off") { [model] in model.start(for: nil) }]))
        } else {
            menu.addItem(submenu("Add time", items: Self.lengths.prefix(5).map { (title, ms) in action("+" + title) { [model] in model.add(ms) } }))
            if Lockdown.endable(status) {
                menu.addItem(action(endTitle(status)) { [model] in
                    Unlocking.run(title: status.why == .manual ? "Turn off lockdown?" : "End the timed lockdown now?", action: "End now") { model.end() }
                })
            }
        }

        menu.addItem(.separator())
        let count = model.macApps.count
        menu.addItem(action(count == 0 ? "Blocked Mac apps…" : "Blocked Mac apps (\(count))…") { Dialog.show(width: 400) { AppsView() } })
        if !Blocker.accessibilityOn {
            menu.addItem(action("Allow Accessibility (needed for websites)…") { Blocker.askForAccessibility() })
        }
        let login = action("Open at login") {
            let service = SMAppService.mainApp
            if service.status == .enabled { try? service.unregister() } else { try? service.register() }
        }
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
        menu.addItem(action("Unpair…") { [model] in
            if model.status.on { Unlocking.run(title: "Unpair during lockdown?", action: "Unpair") { model.unpair() } }
            else { model.unpair() }
        })
        menu.addItem(.separator())
        menu.addItem(action("Quit") { NSApp.terminate(nil) })
    }

    static let lengths: [(String, Ms)] = [("15m", 15), ("30m", 30), ("1h", 60), ("2h", 120), ("4h", 240), ("8h", 480), ("24h", 1440)]
        .map { ($0.0, Ms($0.1) * 60_000) }

    private func headline(_ status: Status) -> String {
        guard status.on else { return "Lockdown off" }
        if let until = status.until { return "On until \(Format.time(until))" }
        return status.why == .phoneOnly ? "On (from the phone)" : "On"
    }

    private func endTitle(_ status: Status) -> String {
        let base = status.why == .manual ? "Turn off…" : "End now…"
        guard model.unlock == .wait else { return base }
        switch model.wait {
        case .waiting(let left): return "\(base.dropLast()) · unlocks in \(Format.duration(left))"
        case .ready(let left): return "\(base.dropLast()) · unlocked for \(Format.duration(left))"
        case .none: return base
        }
    }

    // --- Menu helpers ---

    private func label(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func action(_ title: String, _ run: @escaping () -> Void) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: #selector(MenuAction.run), keyEquivalent: "")
        let target = MenuAction(run)
        item.target = target
        item.representedObject = target // keeps it alive
        return item
    }

    private func submenu(_ title: String, items: [NSMenuItem]) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        let menu = NSMenu()
        items.forEach(menu.addItem)
        item.submenu = menu
        return item
    }
}

final class MenuAction: NSObject {
    let block: () -> Void
    init(_ block: @escaping () -> Void) { self.block = block }
    @objc func run() { block() }
}

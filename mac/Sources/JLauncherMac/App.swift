import AppKit
import LockdownCore
import ServiceManagement

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var item: NSStatusItem!
    private var menu: NSMenu!
    private let model = Model.shared

    func applicationDidFinishLaunching(_ notification: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        menu = NSMenu()
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
        let onBreak = model.onBreak
        item.button?.image = PixelLock.image(locked: status.on && !onBreak)
        // A countdown beside the lock: the break's time left, or lockdown's while it has an end
        if onBreak {
            item.button?.title = " " + Format.clock(model.state.breakUntil - Date().ms)
        } else if status.on, let until = status.until {
            item.button?.title = " " + Format.duration(until - Date().ms)
        } else {
            item.button?.title = ""
        }
        item.button?.imagePosition = .imageLeading
        // During a break, a click on the lock ends it at once; otherwise it opens the menu
        if onBreak, item.menu != nil {
            item.menu = nil
            item.button?.target = self
            item.button?.action = #selector(endBreakClicked)
        } else if !onBreak, item.menu == nil {
            item.button?.action = nil
            item.menu = menu
        }
    }

    @objc private func endBreakClicked() {
        model.endBreak()
        updateIcon()
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
            menu.addItem(Updater.menuItem(action: action))
            menu.addItem(action("Quit") { NSApp.terminate(nil) })
            return
        }

        // On a break: ending it is the first thing
        if model.onBreak {
            menu.addItem(action("End break now (\(Format.clock(model.state.breakUntil - Date().ms)) left)") { [model] in model.endBreak() })
            menu.addItem(.separator())
        }
        menu.addItem(label(headline(status)))
        if let phone = model.phone, phone.updatedAt > 0 {
            menu.addItem(label("Phone: " + phoneLine(phone)))
        }
        if model.waitingForPhone { menu.addItem(label("Waiting for phone")) }
        if let error = model.syncError { menu.addItem(label(error)) }
        menu.addItem(.separator())

        let matched = model.state.matched
        func lengths(_ devices: Int, add: Bool) -> [NSMenuItem] {
            Self.lengths.prefix(add ? 5 : 7).map { (title, ms) in
                action((add ? "+" : "") + title) { [model] in add ? model.add(ms, devices: devices) : model.start(for: ms, devices: devices) }
            } + (add ? [NSMenuItem]() : [NSMenuItem.separator(), action("Until I turn it off") { [model] in model.start(for: nil, devices: devices) }])
        }
        let add = status.on
        let title = add ? "Add time" : "Start"
        if matched {
            menu.addItem(submenu(title, items: lengths(Devices.both, add: add)))
        } else {
            menu.addItem(submenu(title, items: lengths(Devices.mac, add: add)))
            menu.addItem(submenu(title + " on phone and Mac", items: lengths(Devices.both, add: model.phone?.phoneOn == true || add)))
        }
        if status.on && Lockdown.endable(model.state, now: Date().ms) {
            menu.addItem(action(endTitle(status)) { [model] in
                Unlocking.run(title: status.why == .manual ? "Turn off lockdown?" : "End lockdown now?", action: "End now") {
                    model.end(devices: matched ? Devices.both : Devices.mac)
                }
            })
        }
        // Breaks
        let breaks = model.breaks
        if breaks.on && status.on && !model.onBreak {
            switch model.canBreak {
            case .yes:
                menu.addItem(action("Take a \(breaks.minutes)m break…") { [model] in
                    Unlocking.run(title: "Take a \(breaks.minutes) minute break?", action: "Take break", challenge: breaks.challenge) { model.takeBreak() }
                })
            case let can:
                menu.addItem(label("Break: " + Self.cantBreak(can)))
            }
        }

        menu.addItem(.separator())
        let count = model.macApps.count + model.sites.count
        menu.addItem(action(count == 0 ? "Apps and websites…" : "Apps and websites (\(count) on this Mac)…") { Dialog.show(width: 460) { ItemsView() } })
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
        menu.addItem(Updater.menuItem(action: action))
        menu.addItem(action("Quit") { NSApp.terminate(nil) })
    }

    static let lengths: [(String, Ms)] = [("15m", 15), ("30m", 30), ("1h", 60), ("2h", 120), ("4h", 240), ("8h", 480), ("24h", 1440)]
        .map { ($0.0, Ms($0.1) * 60_000) }

    static func cantBreak(_ can: Breaks.Can) -> String {
        switch can {
        case .yes: return "available"
        case .off: return "off"
        case .notLocked: return "lockdown isn't on"
        case .onBreak(let until): return "on one until \(Format.time(until))"
        case .cooldown(let until): return "next at \(Format.time(until))"
        case .offNow(let until): return "none until \(Format.time(until))"
        case .limit(let period, let count, let until):
            let used = period == "hour" ? "an hour" : period == "day" ? "today" : "this week"
            return "\(count) \(used) used · next at \(Format.time(until))"
        }
    }

    private func headline(_ status: Status) -> String {
        guard status.on else { return "Lockdown off" }
        if model.onBreak { return "On a break until \(Format.time(model.state.breakUntil))" }
        if let until = status.until { return "On until \(Format.time(until))" }
        return "On"
    }

    private func phoneLine(_ phone: PhoneState) -> String {
        if !phone.phoneOn { return "off" }
        if phone.phoneBreakUntil > Date().ms { return "on a break until \(Format.time(phone.phoneBreakUntil))" }
        return phone.phoneUntil > 0 ? "on until \(Format.time(phone.phoneUntil))" : "on"
    }

    private func endTitle(_ status: Status) -> String {
        let base = status.why == .manual ? "Turn off…" : "End early…"
        guard model.challenge.type == .wait else { return base }
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

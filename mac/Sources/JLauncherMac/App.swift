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
            menu.addItem(action("End break now · \(Format.clock(model.state.breakUntil - Date().ms)) left") { [model] in model.endBreak() })
            menu.addItem(.separator())
        }
        menu.addItem(label(headline(status)))
        // One lockdown: the phone's own line only when it differs (this Mac is switched off on the phone)
        if let phone = model.phone, phone.updatedAt > 0, phone.phoneOn != status.on {
            menu.addItem(label("Phone · " + phoneLine(phone)))
        }
        if model.waitingForPhone { menu.addItem(label("Waiting for phone")) }
        for command in model.notApplied {
            menu.addItem(submenu("Not applied · " + LockdownSettings.name(command.key ?? ""), items: [
                action("Resend") { RulesMenu.resend(command) },
                action("Dismiss") { [model] in model.dismissNotApplied(command) },
            ]))
        }
        if let error = model.syncError { menu.addItem(label(error)) }
        menu.addItem(.separator())

        // The phone's lengths, then until a time; "Until turned off" starts it, or keeps it on past its end
        let add = status.on
        var lengths = Self.lengths.map { (title, ms) in
            action((add ? "+" : "") + title) { [model] in add ? model.add(ms) : model.start(for: ms) }
        }
        lengths.append(action("Until a time…") { Dialog.show { UntilTimeView() } })
        if !add {
            lengths += [.separator(), action("Until turned off") { [model] in model.start(for: nil) }]
        } else if !model.state.manual {
            lengths += [.separator(), action("Until turned off…") { Dialog.show { KeepOnView() } }]
        }
        menu.addItem(submenu(add ? "Add time" : "Start", items: lengths))
        if status.on && Lockdown.endable(model.state, now: Date().ms) {
            menu.addItem(action(endTitle(status)) { [model] in
                Unlocking.run(title: status.why == .manual ? "Turn off lockdown?" : "End lockdown now?", action: "End now") {
                    model.end()
                }
            })
        }
        // Breaks
        let breaks = model.breaks
        if breaks.on {
            let pool = "\(model.state.breakPool / 60_000)m"
            if status.on && !model.onBreak {
                switch model.canBreak {
                case .yes:
                    menu.addItem(action("Take a \(pool) break…") { [model] in
                        Unlocking.run(title: "Take a \(pool) break?", action: "Take break", challenge: breaks.challenge) { model.takeBreak() }
                    })
                case .empty(let next):
                    menu.addItem(label(Self.cantBreak(.empty(next: next))))
                case let can:
                    menu.addItem(label("Break · " + Self.cantBreak(can)))
                }
            }
            menu.addItem(label("Break pool · \(pool)"))
        }
        menu.addItem(.separator())
        let count = model.items.values.filter(\.blocked).count
        menu.addItem(action(count == 0 ? "Apps and websites…" : "Apps and websites · \(count)…") { Dialog.show(width: 520) { ItemsView() } })
        menu.addItem(RulesMenu.item())
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
        case .offNow(let until): return "none until \(Format.time(until))"
        case .empty(let next): return "No break time yet" + (next.map { " · More at \(Format.time($0))" } ?? "")
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

    private func label(_ title: String) -> NSMenuItem { Menus.label(title) }

    private func action(_ title: String, _ run: @escaping () -> Void) -> NSMenuItem { Menus.action(title, run) }

    private func submenu(_ title: String, items: [NSMenuItem]) -> NSMenuItem { Menus.submenu(title, items: items) }
}

/// Building menu items
@MainActor
enum Menus {
    static func label(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    static func action(_ title: String, enabled: Bool = true, ticked: Bool = false, _ run: @escaping () -> Void) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: #selector(MenuAction.run), keyEquivalent: "")
        let target = MenuAction(run)
        item.target = target
        item.representedObject = target // keeps it alive
        item.isEnabled = enabled
        item.state = ticked ? .on : .off
        return item
    }

    static func submenu(_ title: String, enabled: Bool = true, items: [NSMenuItem]) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        let menu = NSMenu()
        menu.autoenablesItems = false
        items.forEach(menu.addItem)
        item.submenu = menu
        item.isEnabled = enabled
        return item
    }
}

final class MenuAction: NSObject {
    let block: () -> Void
    init(_ block: @escaping () -> Void) { self.block = block }
    @objc func run() { block() }
}

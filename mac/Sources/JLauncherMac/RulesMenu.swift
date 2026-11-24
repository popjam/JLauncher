import AppKit
import LockdownCore
import SwiftUI

/// Rules ▸ in the menu: the phone's Turns on, Screen time, Breaks and Unlock, the parts that apply to
/// every device. One-of-a-few choices are ticked lists; schedules and Lock after open a card. Each
/// change sends the whole new value to the phone (Model.set); anything that loosens needs the unlock first.
@MainActor
enum RulesMenu {
    private static var model: Model { Model.shared }

    static func item() -> NSMenuItem {
        let state = model.state
        let waiting = model.waitingKeys
        func title(_ name: String, _ keys: [String]) -> String { keys.contains(where: waiting.contains) ? name + " · waiting for phone" : name }
        return Menus.submenu("Rules", items: [
            Menus.submenu(title("Turns on", [LockdownSettings.schedules]), items: turnsOn(state)),
            Menus.submenu(title("Screen time", [LockdownSettings.triggers]), items: screenTime(state)),
            Menus.submenu(title("Breaks", [LockdownSettings.breaks]), items: breaks(state)),
            Menus.submenu(title("Unlock", [LockdownSettings.challenge, LockdownSettings.phrase]), items: unlock(state)),
        ])
    }

    // --- Changing a setting ---

    private static var lockdownOn: Bool { model.status.on || model.phone?.phoneOn == true }

    /// Sends `value` for `key`: through the unlock when it loosens, after asking when it starts lockdown now
    static func change(_ key: String, _ value: String, startsNow: Bool = false) {
        let state = model.state
        guard value != LockdownSettings.value(key, in: state) else { return }
        let send = {
            model.set(key, value)
            if key == LockdownSettings.challenge { model.cancelWait() }
        }
        if LockdownSettings.loosens(key, to: value, from: state) { Loosening.run(send) }
        else if startsNow && !lockdownOn { Dialog.show { ConfirmView(title: "Lockdown starts now", action: "Save", then: send) } }
        else { send() }
    }

    /// Sends a change the phone didn't take again, as if made now
    static func resend(_ command: Command) {
        guard let key = command.key, let value = command.value else { return model.dismissNotApplied(command) }
        if value == LockdownSettings.value(key, in: model.state) { return model.dismissNotApplied(command) }
        change(key, value)
    }

    // --- Turns on ---

    private static func turnsOn(_ s: PhoneState) -> [NSMenuItem] {
        guard s.rulesKnown else { return [Menus.label("Needs the latest JLauncher on the phone")] }
        var items = [Menus.label("Schedule")]
        for schedule in s.schedules {
            items.append(Menus.submenu(Names.schedule(schedule), items: [
                Menus.action("Edit…") {
                    Dialog.show { ScheduleView(title: "Schedule", initial: schedule) { new in
                        setSchedules(model.state.schedules.map { $0 == schedule ? new : $0 }, adding: new)
                    } }
                },
                Menus.action("Remove…") { setSchedules(model.state.schedules.filter { $0 != schedule }) },
            ]))
        }
        items.append(Menus.action("Add…") {
            Dialog.show { ScheduleView(title: "Schedule", initial: Schedule(days: Schedule.weekdays, start: 22 * 60, end: 7 * 60)) { new in
                setSchedules(model.state.schedules + [new], adding: new)
            } }
        })
        return items
    }

    private static func setSchedules(_ list: [Schedule], adding: Schedule? = nil) {
        change(LockdownSettings.schedules, Schedule.format(unique(list)), startsNow: adding?.covers(Date()) == true)
    }

    private static func unique(_ list: [Schedule]) -> [Schedule] {
        var out: [Schedule] = []
        for s in list where !out.contains(where: { $0.encoded == s.encoded }) { out.append(s) }
        return out
    }

    // --- Screen time ---

    private static func screenTime(_ s: PhoneState) -> [NSMenuItem] {
        guard s.rulesKnown else { return [Menus.label("Needs the latest JLauncher on the phone")] }
        var items = [Menus.label("Lock after")]
        for trigger in s.triggers {
            items.append(Menus.submenu(Names.trigger(trigger), items: [
                Menus.action("Edit…") {
                    Dialog.show { TriggerView(initial: trigger) { new in
                        setTriggers(model.state.triggers.map { $0 == trigger ? new : $0 })
                    } }
                },
                Menus.action("Remove…") { setTriggers(model.state.triggers.filter { $0 != trigger }) },
            ]))
        }
        items.append(Menus.action("Add…") {
            Dialog.show { TriggerView(initial: Trigger(minutes: 180, until: 6 * 60)) { new in setTriggers(model.state.triggers + [new]) } }
        })
        return items
    }

    private static func setTriggers(_ list: [Trigger]) {
        var out: [Trigger] = []
        for t in list where !out.contains(t) { out.append(t) }
        change(LockdownSettings.triggers, Trigger.format(out))
    }

    // --- Breaks ---

    private static func breaks(_ s: PhoneState) -> [NSMenuItem] {
        let c = s.breaks
        let on = c.on
        func save(_ edit: (inout BreakConfig) -> Void) {
            var new = model.state.breaks
            edit(&new)
            change(LockdownSettings.breaks, new.encoded)
        }
        func ticks(_ title: String, _ options: [Int], _ current: Int, enabled: Bool = true, suffix: String = "",
                   _ set: @escaping (inout BreakConfig, Int) -> Void) -> NSMenuItem {
            let name = Names.minutes
            return Menus.submenu(title + " · " + name(current) + suffix, enabled: enabled, items: options.map { option in
                Menus.action(name(option), ticked: option == current) { save { set(&$0, option) } }
            })
        }
        var items: [NSMenuItem] = []
        if on { items.append(Menus.label("Pool · \(s.breakPool / 60_000)m")) }
        items.append(ticks("Earn", [0, 1, 2, 3, 5, 10, 15, 20, 30], c.minutes) { $0.minutes = $1 })
        items.append(ticks("Every", [15, 30, 45, 60, 90, 120, 180, 240], c.every, enabled: on, suffix: " of lockdown") { $0.every = $1 })
        items.append(ticks("Cap", [0, 10, 15, 20, 30, 45, 60, 120], c.cap, enabled: on) { $0.cap = $1 })
        items.append(Menus.action("Reset at midnight", enabled: on, ticked: c.reset) { save { $0.reset.toggle() } })
        let types: [Challenge.Kind] = [.nothing, .hold, .taps, .phrase, .random] + (s.passwordSet || c.challenge.type == .password ? [.password] : [])
        let needs = challengeMenu("Break needs", current: c.challenge, types: types, phrase: s.phrase) { picked in save { $0.challenge = picked } }
        needs.isEnabled = on
        items.append(needs)
        if c.challenge.type == .phrase { items.append(Menus.action("Phrase…", enabled: on) { Dialog.show(width: 440) { PhraseView() } }) }
        items.append(Menus.action("Make up break time", enabled: on, ticked: c.makeUp) { save { $0.makeUp.toggle() } })
        items.append(.separator())
        items.append(Menus.label("No breaks during"))
        for off in c.off {
            items.append(Menus.submenu(Names.schedule(off), enabled: on, items: [
                Menus.action("Edit…") {
                    Dialog.show { ScheduleView(title: "No breaks during", initial: off) { new in
                        save { $0.off = unique($0.off.map { $0 == off ? new : $0 }) }
                    } }
                },
                Menus.action("Remove…") { save { $0.off = $0.off.filter { $0 != off } } },
            ]))
        }
        items.append(Menus.action("Add…", enabled: on) {
            Dialog.show { ScheduleView(title: "No breaks during", initial: Schedule(days: Schedule.everyDay, start: 22 * 60, end: 6 * 60)) { new in
                save { $0.off = unique($0.off + [new]) }
            } }
        })
        items.append(.separator())
        // Automatic breaks don't need manual ones to be on
        let cycles = [(0, 0), (5, 25), (5, 50), (10, 60), (15, 90)]
        func autoName(_ m: Int, _ e: Int) -> String { m > 0 && e > m ? "\(Names.minutes(m)) every \(Names.minutes(e))" : "Off" }
        let current = c.autoOn ? autoName(c.autoMinutes, c.autoEvery) : "Off"
        items.append(Menus.submenu("Automatic breaks · " + current, items: cycles.map { (m, e) in
            Menus.action(autoName(m, e), ticked: c.autoOn ? (c.autoMinutes == m && c.autoEvery == e) : m == 0) {
                save { $0.autoMinutes = m; $0.autoEvery = e }
            }
        }))
        return items
    }

    // --- Unlock ---

    private static func unlock(_ s: PhoneState) -> [NSMenuItem] {
        let types: [Challenge.Kind] = [.nothing, .hold, .taps, .phrase, .random] + (s.passwordSet || s.challenge.type == .password ? [.password] : []) + [.wait, .never]
        var items = [challengeMenu("Unlock needs", current: s.challenge, types: types, phrase: s.phrase) { picked in
            change(LockdownSettings.challenge, picked.encoded)
        }]
        if s.challenge.type == .phrase { items.append(Menus.action("Phrase…") { Dialog.show(width: 440) { PhraseView() } }) }
        return items
    }

    /// The types, each with its amounts and how hard each is; the current one ticked
    private static func challengeMenu(_ title: String, current: Challenge, types: [Challenge.Kind], phrase: String,
                                      pick: @escaping (Challenge) -> Void) -> NSMenuItem {
        let length = phrase.isEmpty ? 40 : phrase.count
        let items = types.map { type -> NSMenuItem in
            let amounts = Challenge.amounts(type)
            if amounts.isEmpty { return Menus.action(Names.type(type), ticked: current.type == type) { pick(Challenge(type: type)) } }
            let sub = Menus.submenu(Names.type(type), items: amounts.map { amount in
                let c = Challenge(type: type, amount: amount)
                return Menus.action(Names.challenge(c) + " · " + Names.difficulty(c.difficulty(phraseLength: length)), ticked: current == c) { pick(c) }
            })
            sub.state = current.type == type ? .on : .off
            return sub
        }
        return Menus.submenu(title + " · " + Names.challenge(current), items: items)
    }
}

/// Loosening, as on the phone: nothing needed while lockdown is off; refused while a timer runs on any
/// device; otherwise what unlocking needs, after which loosening stays open for 5 minutes.
@MainActor
enum Loosening {
    private static var graceUntil: Ms = 0

    static func run(_ then: @escaping () -> Void) {
        let model = Model.shared
        let now = Date().ms
        guard model.status.on || model.phone?.phoneOn == true else { return then() }
        if now < graceUntil { return then() }
        if let until = Lockdown.lockedUntil(model.state, now: now) {
            return Dialog.show {
                Card {
                    Title(text: "Locked until \(Format.time(until))")
                    BoxButton(title: "OK") { Dialog.close() }
                }
            }
        }
        if model.challenge.type == .nothing { return then() }
        Unlocking.run(title: "Loosen lockdown", action: "Continue") {
            graceUntil = Date().ms + 5 * 60_000
            then()
        }
    }
}

/// The phone's words for settings
enum Names {
    static func minutes(_ m: Int) -> String { m <= 0 ? "Off" : Format.duration(Ms(m) * 60_000) }

    /// "22:00", or "10 pm" on a 12-hour Mac
    static func timeOfDay(_ minutes: Int) -> String {
        let (h, m) = (minutes / 60, minutes % 60)
        if Format.uses24Hour { return String(format: "%d:%02d", h, m) }
        let hour = h % 12 == 0 ? 12 : h % 12
        return (m == 0 ? "\(hour)" : String(format: "%d:%02d", hour, m)) + (h < 12 ? " am" : " pm")
    }

    /// "Weekdays 22:00–7:00", "Mon, Wed all day"
    static func schedule(_ s: Schedule) -> String {
        let short = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        let days: String
        switch s.days {
        case Schedule.everyDay: days = "Every day"
        case Schedule.weekdays: days = "Weekdays"
        case Schedule.weekends: days = "Weekends"
        default: days = (0..<7).filter { s.days & (1 << $0) != 0 }.map { short[$0] }.joined(separator: ", ")
        }
        if s.start == s.end { return days + " all day" }
        return days + " " + timeOfDay(s.start) + "–" + timeOfDay(s.end)
    }

    /// "Total over 5h · until 7:00"
    static func trigger(_ t: Trigger) -> String { "Total over \(minutes(t.minutes)) · until \(timeOfDay(t.until))" }

    static func type(_ type: Challenge.Kind) -> String {
        switch type {
        case .nothing: return "Nothing"
        case .hold: return "Keep a countdown open"
        case .taps: return "Tap"
        case .phrase: return "Type a phrase"
        case .random: return "Type random text"
        case .password: return "Password"
        case .wait: return "Ask, then wait"
        case .never: return "Not possible"
        }
    }

    static func challenge(_ c: Challenge) -> String {
        switch c.type {
        case .nothing: return "Nothing"
        case .hold: return "Keep open for " + (c.amount < 60 ? "\(c.amount)s" : Format.duration(Ms(c.amount) * 1000))
        case .taps: return c.amount == 1 ? "1 tap" : "\(c.amount) taps"
        case .phrase: return c.amount <= 1 ? "Type the phrase" : "Type the phrase \(c.amount) times"
        case .random: return "Type \(c.amount) random characters"
        case .password: return "Password"
        case .wait: return Format.duration(Ms(c.amount) * 60_000) + " wait"
        case .never: return "Not possible"
        }
    }

    static func difficulty(_ level: Int) -> String {
        ["Very easy", "Easy", "Medium", "Hard", "Very hard", "Almost impossible", "Impossible"][max(0, min(6, level))]
    }
}

// --- Cards ---

/// A time of day picked with the Mac's field, as minutes after midnight
struct TimeField: View {
    @Binding var minutes: Int

    var body: some View {
        DatePicker("", selection: Binding(
            get: { Calendar.current.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: Date()) ?? Date() },
            set: { let c = Calendar.current.dateComponents([.hour, .minute], from: $0); minutes = (c.hour ?? 0) * 60 + (c.minute ?? 0) }
        ), displayedComponents: .hourAndMinute)
        .labelsHidden()
        .datePickerStyle(.field)
        .fixedSize()
    }
}

/// A label on the left, its control on the right
struct FieldRow<Control: View>: View {
    let label: String
    @ViewBuilder var control: Control
    var body: some View {
        HStack {
            Text(label).font(Style.line).foregroundStyle(.white)
            Spacer()
            control
        }
        .padding(.vertical, 6)
    }
}

/// The days, then from and to
struct ScheduleView: View {
    let title: String
    let onSave: (Schedule) -> Void
    @State private var days: Int
    @State private var start: Int
    @State private var end: Int
    private static let letters = ["M", "T", "W", "T", "F", "S", "S"]

    init(title: String, initial: Schedule, onSave: @escaping (Schedule) -> Void) {
        self.title = title
        self.onSave = onSave
        _days = State(initialValue: initial.days)
        _start = State(initialValue: initial.start)
        _end = State(initialValue: initial.end)
    }

    var body: some View {
        Card {
            Title(text: title)
            HStack(spacing: 8) {
                ForEach(0..<7, id: \.self) { d in
                    let on = days & (1 << d) != 0
                    Button { days ^= 1 << d } label: {
                        Text(Self.letters[d]).font(.system(size: 14)).foregroundStyle(on ? Color.black : .white.opacity(0.8))
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(on ? Style.accent : Color.clear))
                            .overlay(Circle().strokeBorder(on ? Color.clear : Color.white.opacity(0.4), lineWidth: 1))
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.bottom, 10)
            FieldRow(label: "From") { TimeField(minutes: $start) }
            FieldRow(label: "To") { TimeField(minutes: $end) }
            BoxButton(title: "Save", enabled: days != 0) {
                Dialog.close()
                onSave(Schedule(days: days, start: start, end: end))
            }
            .padding(.top, 14)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }
}

/// How much total screen time, then until when
struct TriggerView: View {
    let onSave: (Trigger) -> Void
    @State private var amount: Int
    @State private var until: Int

    init(initial: Trigger, onSave: @escaping (Trigger) -> Void) {
        self.onSave = onSave
        _amount = State(initialValue: Trigger.amounts.contains(initial.minutes) ? initial.minutes : 180)
        _until = State(initialValue: initial.until)
    }

    var body: some View {
        Card {
            Title(text: "Lock after")
            FieldRow(label: "After") {
                Picker("", selection: $amount) {
                    ForEach(Trigger.amounts, id: \.self) { Text(Names.minutes($0)).tag($0) }
                }
                .labelsHidden().pickerStyle(.menu).fixedSize()
            }
            FieldRow(label: "Locked until") { TimeField(minutes: $until) }
            BoxButton(title: "Save") {
                Dialog.close()
                onSave(Trigger(minutes: amount, until: until))
            }
            .padding(.top, 14)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }
}

/// Start, or add time, until a time of day: today, or tomorrow once it's passed. Never shortens.
struct UntilTimeView: View {
    @ObservedObject private var model = Model.shared
    @State private var minutes: Int = {
        let hour = Calendar.current.component(.hour, from: Date())
        return (hour + 1) % 24 * 60
    }()

    private var target: Ms {
        let now = Date()
        let today = Calendar.current.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: now) ?? now
        return (today > now ? today : Calendar.current.date(byAdding: .day, value: 1, to: today) ?? today).ms
    }

    /// Lockdown already runs past then
    private var tooSoon: Bool {
        guard model.status.on, let until = model.status.until else { return false }
        return target <= until
    }

    var body: some View {
        Card {
            Title(text: "Until a time")
            FieldRow(label: "Until") { TimeField(minutes: $minutes) }
            Line(text: tooSoon ? "Lockdown already runs past then" : "It can’t be turned off before then",
                 color: tooSoon ? Style.red : Style.faint).padding(.top, 4)
            BoxButton(title: "Lock until \(Format.time(target))", enabled: !tooSoon) {
                Dialog.close()
                model.start(until: target)
            }
            .padding(.top, 18)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }
}

/// On until turned off while something else has it on, which keeps it on past their end
struct KeepOnView: View {
    @ObservedObject private var model = Model.shared

    var body: some View {
        Card {
            Title(text: model.status.until.map { "Keep lockdown on after \(Format.time($0))?" } ?? "Keep lockdown on?")
            Line(text: "It stays on until turned off")
            BoxButton(title: "Keep on") {
                Dialog.close()
                model.start(for: nil)
            }
            .padding(.top, 18)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }
}

/// The phrase typed out for "Type the phrase"; at least 4 characters, and a shorter one loosens
struct PhraseView: View {
    @State private var text = Model.shared.state.phrase

    private var cleaned: String {
        text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    var body: some View {
        Card {
            Title(text: "Phrase")
            BoxField(placeholder: "Phrase", text: $text, onSubmit: save)
            BoxButton(title: "Save", enabled: cleaned.count >= 4, action: save).padding(.top, 18)
            TextButton(title: "Cancel") { Dialog.close() }
        }
    }

    private func save() {
        guard cleaned.count >= 4 else { return }
        Dialog.close()
        RulesMenu.change(LockdownSettings.phrase, cleaned)
    }
}

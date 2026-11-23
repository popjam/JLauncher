import AppKit
import SwiftUI

/// The Update menu item: runs mac/update.sh from the repo the app was built from, which pulls the
/// latest version and, if it's newer, builds and reopens the app (this copy is replaced and quits).
/// Says so if it's already up to date or the update fails.
@MainActor
enum Updater {
    private static var running = false

    static var repo: String? { Bundle.main.object(forInfoDictionaryKey: "JLauncherRepo") as? String }
    static var commit: String { Bundle.main.object(forInfoDictionaryKey: "JLauncherCommit") as? String ?? "unknown" }
    static let log = NSHomeDirectory() + "/Library/Logs/JLauncher Lockdown update.log"

    static func menuItem(action: (String, @escaping () -> Void) -> NSMenuItem) -> NSMenuItem {
        let item = action(running ? "Updating…" : "Update (version \(commit))") { run() }
        item.isEnabled = !running && repo != nil
        return item
    }

    static func run() {
        guard !running, let repo else { return }
        let script = repo + "/mac/update.sh"
        guard FileManager.default.fileExists(atPath: script) else {
            return Dialog.show { message("Can't update", "update.sh isn't in \(repo). Pull the repo and run ./mac/build.sh once.") }
        }
        running = true
        FileManager.default.createFile(atPath: log, contents: nil)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/zsh")
        // A login shell, so git and swift are found as in Terminal
        process.arguments = ["-l", script]
        if let handle = FileHandle(forWritingAtPath: log) {
            process.standardOutput = handle
            process.standardError = handle
        }
        process.terminationHandler = { p in
            let status = p.terminationStatus
            Task { @MainActor in finished(status) }
        }
        do { try process.run() } catch {
            running = false
            Dialog.show { message("Update failed", error.localizedDescription) }
        }
    }

    /// Only reached when this copy wasn't replaced: already up to date, or something went wrong
    private static func finished(_ status: Int32) {
        running = false
        if status == 10 { return Dialog.show { message("Up to date", "Version \(commit) is the latest.") } }
        if status == 0 { return }
        let tail = ((try? String(contentsOfFile: log, encoding: .utf8)) ?? "").split(separator: "\n").suffix(8).joined(separator: "\n")
        Dialog.show(width: 460) { message("Update failed", tail.isEmpty ? "See \(log)" : tail) }
    }

    private static func message(_ title: String, _ text: String) -> some View {
        Card {
            Title(text: title)
            Line(text: text)
            BoxButton(title: "OK") { Dialog.close() }.padding(.top, 18)
        }
    }
}

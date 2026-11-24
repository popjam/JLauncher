import AppKit
import SwiftUI

/// The phone's look: black cards, a thin half-white edge, light titles, boxed main buttons.
enum Style {
    static let red = Color(red: 0xEF / 255, green: 0x53 / 255, blue: 0x50 / 255)
    static let orange = Color(red: 0xFF / 255, green: 0xA7 / 255, blue: 0x26 / 255)
    static let edge = Color.white.opacity(0.5)
    /// What's chosen: the blocked pill, a ticked day
    static let accent = Color(red: 0x8F / 255, green: 0xB3 / 255, blue: 1)
    static let faint = Color.white.opacity(195 / 255)
    /// Over a blocked app or site: nearly black
    static let shadeBlock = Color.black.opacity(0xF2 / 255)
    /// Over anything else: dimmed
    static let shadeDim = Color.black.opacity(0xB3 / 255)

    static func title(_ size: CGFloat = 23) -> Font { .system(size: size, weight: .light) }
    static let line = Font.system(size: 15)
}

/// The 16x16 pixel face; mood 0 (happy) to 4 (sad), as on the phone.
struct PixelFace: View {
    var mood = 4
    var color = Style.red

    static let outline = [
        ".....######.....",
        "...##......##...",
        "..#..........#..",
        ".#............#.",
        ".#............#.",
        "#..............#",
        "#....#....#....#",
        "#....#....#....#",
        "#..............#",
        "#..............#",
        "#..............#",
        ".#............#.",
        ".#............#.",
        "..#..........#..",
        "...##......##...",
        ".....######.....",
    ]

    static let mouths: [[Int: [Int]]] = [
        [9: [4, 11], 10: [5, 10], 11: Array(6...9)],
        [10: [4, 11], 11: Array(5...10)],
        [10: Array(5...10)],
        [10: Array(5...10), 11: [4, 11]],
        [9: Array(6...9), 10: [5, 10], 11: [4, 11]],
    ]

    var body: some View {
        Canvas { context, size in
            let cell = floor(min(size.width, size.height) / 16)
            let left = (size.width - cell * 16) / 2, top = (size.height - cell * 16) / 2
            func dot(_ x: Int, _ y: Int) {
                context.fill(Path(CGRect(x: left + CGFloat(x) * cell, y: top + CGFloat(y) * cell, width: cell, height: cell)), with: .color(color))
            }
            for (y, row) in Self.outline.enumerated() {
                for (x, c) in row.enumerated() where c == "#" { dot(x, y) }
            }
            for (y, xs) in Self.mouths[max(0, min(4, mood))] { xs.forEach { dot($0, y) } }
        }
    }
}

/// The menu-bar padlock, drawn in pixels like the phone's lock icon.
enum PixelLock {
    static let closed = [
        "...######...",
        "..#......#..",
        "..#......#..",
        "..#......#..",
        "############",
        "############",
        "#####..#####",
        "#####..#####",
        "######.#####",
        "############",
        "############",
    ]
    static let open = [
        "...######...",
        "..#......#..",
        "..#.........",
        "..#.........",
        "############",
        "#..........#",
        "#....##....#",
        "#....##....#",
        "#.....#....#",
        "#..........#",
        "############",
    ]

    static func image(locked: Bool) -> NSImage {
        let rows = locked ? closed : open
        let cell: CGFloat = 1.25
        let size = NSSize(width: 12 * cell, height: CGFloat(rows.count) * cell)
        let image = NSImage(size: size, flipped: true) { _ in
            NSColor.black.setFill()
            for (y, row) in rows.enumerated() {
                for (x, c) in row.enumerated() where c == "#" {
                    NSRect(x: CGFloat(x) * cell, y: CGFloat(y) * cell, width: cell, height: cell).fill()
                }
            }
            return true
        }
        image.isTemplate = true
        return image
    }
}

/// The black card with the thin edge.
struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(EdgeInsets(top: 20, leading: 20, bottom: 18, trailing: 20))
            .background(RoundedRectangle(cornerRadius: 22).fill(Color.black))
            .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(Style.edge, lineWidth: 0.8))
    }
}

/// The main action: a wide box.
struct BoxButton: View {
    let title: String
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 16)).foregroundStyle(.white.opacity(enabled ? 1 : 0.4))
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Style.edge, lineWidth: 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(!enabled)
    }
}

/// Other actions: plain text.
struct TextButton: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 16)).foregroundStyle(Style.faint)
                .frame(maxWidth: .infinity).padding(.vertical, 12).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A boxed text field, like the check-in's.
struct BoxField: View {
    let placeholder: String
    @Binding var text: String
    var secure = false
    var onSubmit: () -> Void = {}
    var body: some View {
        Group {
            if secure { SecureField("", text: $text, prompt: prompt) } else { TextField("", text: $text, prompt: prompt) }
        }
        .textFieldStyle(.plain).font(.system(size: 15)).foregroundStyle(.white)
        .padding(.horizontal, 14).padding(.vertical, 11)
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Style.edge, lineWidth: 1))
        .onSubmit(onSubmit)
    }
    var prompt: Text { Text(placeholder).foregroundColor(.white.opacity(0.4)) }
}

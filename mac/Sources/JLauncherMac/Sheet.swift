import AppKit
import SwiftUI

/// Lockdown's sheet over the whole screen: the shade, and the card at the bottom with the face, the
/// title, a line or two and a wide Close. Stays until Close or Esc.
@MainActor
final class Sheet {
    static let shared = Sheet()

    private var window: NSWindow?
    private var onClose: (() -> Void)?

    var showing: Bool { window != nil }

    func show(title: String, lines: [String], mood: Int = 4, block: Bool = true, onClose: (() -> Void)? = nil) {
        close(run: false)
        self.onClose = onClose
        let screen = NSScreen.screens.first { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) } ?? NSScreen.main!
        let window = KeyWindow(contentRect: screen.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.level = .screenSaver
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = false
        window.onEscape = { [weak self] in self?.close() }
        let view = SheetView(title: title, lines: lines, mood: mood, shade: block ? Style.shadeBlock : Style.shadeDim) { [weak self] in
            self?.close()
        }
        window.contentView = NSHostingView(rootView: view)
        window.setFrame(screen.frame, display: true)
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        self.window = window
    }

    func close(run: Bool = true) {
        window?.orderOut(nil)
        window = nil
        let action = onClose
        onClose = nil
        if run { action?() }
    }
}

final class KeyWindow: NSWindow {
    var onEscape: (() -> Void)?
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
    override func cancelOperation(_ sender: Any?) { onEscape?() }
    override func keyDown(with event: NSEvent) {
        if event.keyCode == 53 { onEscape?() } else { super.keyDown(with: event) }
    }
}

struct SheetView: View {
    let title: String
    let lines: [String]
    let mood: Int
    let shade: Color
    let close: () -> Void

    var body: some View {
        ZStack(alignment: .bottom) {
            // Nothing under the sheet can be clicked
            shade.ignoresSafeArea().contentShape(Rectangle()).onTapGesture {}
            Card {
                HStack(spacing: 14) {
                    PixelFace(mood: mood, color: mood >= 4 ? Style.red : Style.orange).frame(width: 36, height: 36)
                    Text(title).font(Style.title()).foregroundStyle(.white).lineLimit(2)
                    Spacer(minLength: 0)
                }
                ForEach(Array(lines.filter { !$0.isEmpty }.enumerated()), id: \.offset) { i, line in
                    Text(line).font(Style.line).foregroundStyle(Style.faint).lineSpacing(2)
                        .padding(.top, i == 0 ? 12 : 4)
                }
                BoxButton(title: "Close", action: close).padding(.top, 18)
                    .keyboardShortcut(.defaultAction)
            }
            .frame(maxWidth: 460)
            .padding(.horizontal, 12)
            .padding(.bottom, 40)
        }
    }
}

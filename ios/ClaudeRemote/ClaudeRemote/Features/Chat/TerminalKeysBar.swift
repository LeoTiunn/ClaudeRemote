import SwiftUI

/// Ports Android `TerminalKeysBar` — same keys, same escape sequences, same order.
struct TerminalKeysBar: View {
    @Environment(\.appColors) private var colors
    let onKey: (String) -> Void

    private let keys: [(String, String)] = [
        ("Esc", "\u{1b}"),
        ("C-c", "\u{03}"),
        ("C-b", "\u{02}"),
        ("Tab", "\t"),
        ("⇧Tab", "\u{1b}[Z"),
        ("Ent", "\r"),
        ("⌫", "\u{7f}"),
        ("←", "\u{1b}[D"),
        ("↑", "\u{1b}[A"),
        ("↓", "\u{1b}[B"),
        ("→", "\u{1b}[C"),
    ]

    var body: some View {
        HStack(spacing: 3) {
            ForEach(keys, id: \.0) { label, seq in
                key(label) { onKey(seq) }
            }
        }
        .padding(.horizontal, 4).padding(.vertical, 3)
    }

    private func key(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.custom(AppFont.mono, size: 11))
                .foregroundStyle(colors.onSurfaceVariant)
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 2).padding(.vertical, 6)
                .background(colors.surfaceVariant)
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
    }
}

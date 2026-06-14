import SwiftUI

/// Mirrors Android `ConnectionState` (core/ui/components/ConnectionStatusDot.kt).
enum ConnectionState {
    case connecting
    case connected
    case reconnecting
    case disconnected
}

/// Mirrors Android `ConnectionStatusDot` — a small colored circle reflecting SSH state.
struct ConnectionStatusDot: View {
    let state: ConnectionState
    var size: CGFloat = 10

    private var color: Color {
        switch state {
        case .connecting:   return Color(hex: 0xFFA000) // amber
        case .connected:    return Color(hex: 0x4CAF50) // green
        case .reconnecting: return Color(hex: 0xFFA000) // amber
        case .disconnected: return Color(hex: 0xF44336) // red
        }
    }

    var body: some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}

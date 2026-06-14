import SwiftUI
import UIKit

/// Mirrors Android `AppTheme` (settings/SettingsRepository.kt).
enum AppTheme: String, CaseIterable, Identifiable {
    case LIGHT, DARK, SYSTEM
    var id: String { rawValue }

    /// SwiftUI color scheme; nil = follow system (matches Android SYSTEM).
    var colorScheme: ColorScheme? {
        switch self {
        case .LIGHT:  return .light
        case .DARK:   return .dark
        case .SYSTEM: return nil
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// Claude-inspired warm palette, ported 1:1 from Android `Color.kt`.
/// Resolved against the active color scheme so views read like Material's colorScheme.*.
struct AppColors {
    let primary, onPrimary, primaryContainer, onPrimaryContainer: Color
    let secondaryContainer, onSecondaryContainer: Color
    let background, onBackground, surface, onSurface: Color
    let surfaceVariant, onSurfaceVariant: Color
    let error, onError, errorContainer, onErrorContainer: Color
    let outline: Color

    static let dark = AppColors(
        primary: Color(hex: 0xE8A66E), onPrimary: Color(hex: 0x3D1D00),
        primaryContainer: Color(hex: 0x5A3112), onPrimaryContainer: Color(hex: 0xF5DEC8),
        secondaryContainer: Color(hex: 0x52463A), onSecondaryContainer: Color(hex: 0xF3E1CF),
        background: Color(hex: 0x1C1917), onBackground: Color(hex: 0xEAE1D9),
        surface: Color(hex: 0x1C1917), onSurface: Color(hex: 0xEAE1D9),
        surfaceVariant: Color(hex: 0x2D2924), onSurfaceVariant: Color(hex: 0xD0C5BA),
        error: Color(hex: 0xFFB4AB), onError: Color(hex: 0x690005),
        errorContainer: Color(hex: 0x93000A), onErrorContainer: Color(hex: 0xFFDAD6),
        outline: Color(hex: 0x9C8E80)
    )

    static let light = AppColors(
        primary: Color(hex: 0xBF5B21), onPrimary: Color(hex: 0xFFFFFF),
        primaryContainer: Color(hex: 0xF5E6D3), onPrimaryContainer: Color(hex: 0x2D1600),
        secondaryContainer: Color(hex: 0xF3E1CF), onSecondaryContainer: Color(hex: 0x241B10),
        background: Color(hex: 0xFFF8F4), onBackground: Color(hex: 0x1F1B16),
        surface: Color(hex: 0xFFF8F4), onSurface: Color(hex: 0x1F1B16),
        surfaceVariant: Color(hex: 0xF0E0D0), onSurfaceVariant: Color(hex: 0x504539),
        error: Color(hex: 0xBA1A1A), onError: Color(hex: 0xFFFFFF),
        errorContainer: Color(hex: 0xFFDAD6), onErrorContainer: Color(hex: 0x410002),
        outline: Color(hex: 0x837468)
    )

    static func resolve(_ scheme: ColorScheme) -> AppColors {
        scheme == .dark ? .dark : .light
    }
}

private struct AppColorsKey: EnvironmentKey {
    static let defaultValue: AppColors = .dark
}

extension EnvironmentValues {
    var appColors: AppColors {
        get { self[AppColorsKey.self] }
        set { self[AppColorsKey.self] = newValue }
    }
}

/// The bundled Sarasa Mono SC font (Iosevka Latin + Source Han Sans CJK), self-registered
/// at runtime from the app bundle so no Info.plist UIAppFonts entry is needed.
enum AppFont {
    static let mono = "Sarasa Mono SC"   // PostScript/family name, resolved after registration

    private static let registered: Bool = {
        guard let url = Bundle.main.url(forResource: "SarasaMonoSC-Regular", withExtension: "ttf") else {
            return false
        }
        var err: Unmanaged<CFError>?
        return CTFontManagerRegisterFontsForURL(url as CFURL, .process, &err)
    }()

    /// Resolved UIFont — tries the registered family, falls back to the file's actual
    /// PostScript name, then to a monospaced system font.
    static func monoUI(_ size: CGFloat) -> UIFont {
        _ = registered
        if let f = UIFont(name: "SarasaMonoSC-Regular", size: size) { return f }
        if let f = UIFont(name: mono, size: size) { return f }
        return UIFont.monospacedSystemFont(ofSize: size, weight: .regular)
    }
}

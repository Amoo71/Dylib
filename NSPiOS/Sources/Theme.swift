import SwiftUI
import UIKit

// MARK: - Einstellungen

enum Prefs {
    static let theme = "appTheme"
    static let customColor = "customColorHex"
    static let wind = "rainWind"
    static let language = "appLanguage"
}

// MARK: - Sprache

enum AppLanguage: String, CaseIterable, Identifiable {
    case en, de
    var id: String { rawValue }
    var title: String { self == .en ? "English" : "Deutsch" }

    static var current: AppLanguage {
        AppLanguage(rawValue: UserDefaults.standard.string(forKey: Prefs.language) ?? "") ?? .en
    }
}

/// Lokalisierter Text – Englisch ist Standard, Deutsch per Einstellung.
func L(_ en: String, _ de: String) -> String {
    AppLanguage.current == .de ? de : en
}

// MARK: - Themes

enum AppTheme: String, CaseIterable, Identifiable {
    case dark, light, custom, rain
    var id: String { rawValue }

    var title: String {
        switch self {
        case .dark: return L("Dark", "Dunkel")
        case .light: return L("Light", "Hell")
        case .custom: return L("Color", "Farbe")
        case .rain: return L("Rain", "Regen")
        }
    }

    static var current: AppTheme {
        AppTheme(rawValue: UserDefaults.standard.string(forKey: Prefs.theme) ?? "") ?? .dark
    }
}

enum Theme {
    static let defaultCustomHex = "FF7A45"

    static var current: AppTheme { AppTheme.current }

    static var customColor: Color {
        Color(hex: UserDefaults.standard.string(forKey: Prefs.customColor) ?? defaultCustomHex)
            ?? Color(red: 1, green: 0.48, blue: 0.27)
    }

    static var accent: Color {
        switch current {
        case .dark: return Color(red: 0.38, green: 0.82, blue: 1.0)
        case .light: return Color(red: 0.0, green: 0.45, blue: 0.95)
        case .custom: return customColor
        case .rain: return Color(red: 0.56, green: 0.78, blue: 1.0)
        }
    }

    static var violet: Color {
        switch current {
        case .dark: return Color(red: 0.58, green: 0.45, blue: 1.0)
        case .light: return Color(red: 0.45, green: 0.32, blue: 0.95)
        case .custom: return customColor.mix(with: .white, by: 0.35)
        case .rain: return Color(red: 0.62, green: 0.66, blue: 0.92)
        }
    }

    static let danger = Color(red: 1.0, green: 0.36, blue: 0.40)

    static var rowBackground: Color {
        current == .light ? Color.white.opacity(0.7) : Color.white.opacity(0.06)
    }

    static var colorScheme: ColorScheme {
        current == .light ? .light : .dark
    }
}

// MARK: - Hintergründe

struct AmbientBackground: View {
    @AppStorage(Prefs.theme) private var themeRaw = AppTheme.dark.rawValue
    @AppStorage(Prefs.customColor) private var customHex = Theme.defaultCustomHex

    var body: some View {
        Group {
            switch AppTheme(rawValue: themeRaw) ?? .dark {
            case .dark: mesh(Self.darkColors)
            case .light: mesh(Self.lightColors)
            case .custom: customMesh
            case .rain: RainBackground()
            }
        }
        .ignoresSafeArea()
    }

    private func mesh(_ colors: [Color]) -> some View {
        MeshGradient(width: 3, height: 3, points: Self.points, colors: colors)
    }

    private var customMesh: some View {
        let c = Color(hex: customHex) ?? Theme.customColor
        return MeshGradient(
            width: 3, height: 3, points: Self.points,
            colors: [
                c.opacity(0.55), c.opacity(0.22), .clear,
                c.opacity(0.25), c.opacity(0.32), c.opacity(0.08),
                .clear, c.opacity(0.12), .clear
            ]
        )
        .background(Color(red: 0.02, green: 0.02, blue: 0.04))
    }

    private static let points: [SIMD2<Float>] = [
        [0, 0], [0.5, 0], [1, 0],
        [0, 0.5], [0.6, 0.45], [1, 0.5],
        [0, 1], [0.5, 1], [1, 1]
    ]

    private static let darkColors: [Color] = [
        Color(red: 0.16, green: 0.07, blue: 0.30), Color(red: 0.06, green: 0.08, blue: 0.20), Color(red: 0.02, green: 0.03, blue: 0.08),
        Color(red: 0.05, green: 0.10, blue: 0.24), Color(red: 0.03, green: 0.14, blue: 0.26), Color(red: 0.03, green: 0.04, blue: 0.10),
        Color(red: 0.02, green: 0.02, blue: 0.06), Color(red: 0.03, green: 0.05, blue: 0.12), Color(red: 0.01, green: 0.01, blue: 0.04)
    ]

    private static let lightColors: [Color] = [
        Color(red: 0.90, green: 0.88, blue: 1.00), Color(red: 0.95, green: 0.96, blue: 1.00), Color(red: 0.98, green: 0.98, blue: 1.00),
        Color(red: 0.88, green: 0.94, blue: 1.00), Color(red: 0.94, green: 0.97, blue: 1.00), Color(red: 0.97, green: 0.97, blue: 0.99),
        Color(red: 0.96, green: 0.96, blue: 0.99), Color(red: 0.93, green: 0.95, blue: 0.99), Color(red: 0.98, green: 0.98, blue: 0.99)
    ]
}

/// Animierter Regen mit böigem Wind (Canvas, ~60 fps, sehr leichtgewichtig).
struct RainBackground: View {
    @AppStorage(Prefs.wind) private var wind = 0.5
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private struct Drop {
        let x: Double, phase: Double, speed: Double, length: Double, near: Bool
    }

    private static let drops: [Drop] = {
        var seed: UInt64 = 0x9E37_79B9_7F4A_7C15
        func next() -> Double {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Double(seed >> 11) / Double(1 << 53)
        }
        return (0..<220).map { _ in
            let near = next() < 0.35
            return Drop(
                x: next(), phase: next(),
                speed: near ? 0.9 + next() * 0.5 : 0.55 + next() * 0.35,
                length: near ? 26 + next() * 18 : 12 + next() * 10,
                near: near
            )
        }
    }()

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.05, green: 0.07, blue: 0.11), Color(red: 0.09, green: 0.12, blue: 0.17), Color(red: 0.04, green: 0.05, blue: 0.08)],
                startPoint: .top, endPoint: .bottom
            )
            TimelineView(.animation(minimumInterval: 1.0 / 60, paused: reduceMotion)) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                Canvas { ctx, size in draw(ctx: ctx, size: size, t: t) }
            }
            // leichter Nebel unten
            LinearGradient(colors: [.clear, Color(red: 0.55, green: 0.65, blue: 0.8).opacity(0.10)], startPoint: .center, endPoint: .bottom)
        }
    }

    private func draw(ctx: GraphicsContext, size: CGSize, t: Double) {
        // Böen: Grundwind + langsame Welle + kurze Stöße
        let gust = 0.65 + 0.35 * sin(t * 0.33) + 0.2 * sin(t * 1.7 + 1.3) * max(0, sin(t * 0.21))
        let windX = wind * 1.6 * gust          // horizontale Geschwindigkeit relativ zur Fallgeschwindigkeit
        let span = size.height * windX + 60    // Puffer, damit schräge Tropfen von außen hereinkommen

        var far = Path()
        var near = Path()
        for drop in Self.drops {
            let fall = (drop.phase + t * drop.speed * 0.55).truncatingRemainder(dividingBy: 1.0)
            let y = fall * (size.height + 80) - 40
            let width = size.width + span
            var x = (drop.x * width + windX * y).truncatingRemainder(dividingBy: width) - span
            if x < -span { x += width }
            let dx = windX * drop.length
            let start = CGPoint(x: x, y: y)
            let end = CGPoint(x: x - dx, y: y - drop.length)
            if drop.near {
                near.move(to: start); near.addLine(to: end)
            } else {
                far.move(to: start); far.addLine(to: end)
            }
        }
        ctx.stroke(far, with: .color(.white.opacity(0.16)), lineWidth: 1)
        ctx.stroke(near, with: .color(Color(red: 0.75, green: 0.85, blue: 1.0).opacity(0.34)), style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
    }
}

// MARK: - Helfer

extension View {
    /// Liste auf transparentem Hintergrund über dem Theme-Hintergrund.
    func ambientList() -> some View {
        self
            .scrollContentBackground(.hidden)
            .background { AmbientBackground() }
    }

    /// Glas-Karte mit großem Radius.
    func glassCard(radius: CGFloat = 28, tint: Color? = nil) -> some View {
        self.glassEffect(tint.map { Glass.regular.tint($0.opacity(0.25)) } ?? .regular, in: .rect(cornerRadius: radius))
    }
}

extension Color {
    init?(hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet(charactersIn: "# "))
        guard cleaned.count == 6, let value = UInt32(cleaned, radix: 16) else { return nil }
        self.init(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }

    var hexString: String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(self).getRed(&r, green: &g, blue: &b, alpha: &a)
        func clamp(_ v: CGFloat) -> Int { Int((min(max(v, 0), 1) * 255).rounded()) }
        return String(format: "%02X%02X%02X", clamp(r), clamp(g), clamp(b))
    }
}

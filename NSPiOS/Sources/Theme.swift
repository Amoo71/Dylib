import SwiftUI

enum Theme {
    static let accent = Color(red: 0.38, green: 0.82, blue: 1.0)
    static let violet = Color(red: 0.58, green: 0.45, blue: 1.0)
    static let danger = Color(red: 1.0, green: 0.36, blue: 0.40)
    static let rowBackground = Color.white.opacity(0.055)
}

/// Dunkler, ruhiger Mesh-Hintergrund – gibt dem Liquid Glass etwas zum Brechen.
struct AmbientBackground: View {
    var body: some View {
        MeshGradient(
            width: 3, height: 3,
            points: [
                [0, 0], [0.5, 0], [1, 0],
                [0, 0.5], [0.6, 0.45], [1, 0.5],
                [0, 1], [0.5, 1], [1, 1]
            ],
            colors: [
                Color(red: 0.16, green: 0.07, blue: 0.30), Color(red: 0.06, green: 0.08, blue: 0.20), Color(red: 0.02, green: 0.03, blue: 0.08),
                Color(red: 0.05, green: 0.10, blue: 0.24), Color(red: 0.03, green: 0.14, blue: 0.26), Color(red: 0.03, green: 0.04, blue: 0.10),
                Color(red: 0.02, green: 0.02, blue: 0.06), Color(red: 0.03, green: 0.05, blue: 0.12), Color(red: 0.01, green: 0.01, blue: 0.04)
            ]
        )
        .ignoresSafeArea()
    }
}

extension View {
    /// Liste auf transparentem Hintergrund über dem Mesh.
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

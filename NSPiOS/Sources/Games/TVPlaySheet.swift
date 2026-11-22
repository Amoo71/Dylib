import SwiftUI
import UIKit

/// „Auf TV spielen“: Die Spiel-Shell (nxnative) gibt das Bild selbst auf einem Fernseher aus, sobald einer per AirPlay
/// (Bildschirmsynchronisierung) oder Kabel verbunden ist: 16:9 bis 1080p, Konsolenmodus Docked, das iPhone bleibt Controller.
/// Hier: Verbindung prüfen, eigene TV-Einstellungen pro Spiel, Start im TV-Modus.
struct TVPlaySheet: View {
    let game: GameEntry
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var library = GameLibrary.shared
    @State private var values: [String: String] = [:]
    @State private var connection = TVConnection.current()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private struct Option: Identifiable {
        let key: String
        let title: String
        let icon: String
        let pairs: [(String, String)]
        var id: String { key }
    }

    private var options: [Option] {
        [
            Option(key: "DM_FPS_TARGET", title: L("Frame rate", "Bildrate"), icon: "speedometer",
                   pairs: [("30", L("30 fps (steady)", "30 fps (ruhig)")), ("60", "60 fps")]),
            Option(key: "DM_RENDER_SCALE", title: L("Render scale", "Renderskala"), icon: "aspectratio",
                   pairs: [("0.75", "75 %"), ("0.85", "85 %"), ("1.0", "100 % (1080p)")]),
            Option(key: "DM_PRESENT_UPSCALE", title: L("Upscaling", "Hochskalieren"), icon: "wand.and.stars",
                   pairs: [("off", "Standard"), ("metalfx", "MetalFX")]),
            Option(key: "DM_SHOW_FPS", title: L("FPS counter", "FPS-Anzeige"), icon: "gauge.with.dots.needle.67percent",
                   pairs: [("1", L("On", "An")), ("0", L("Off", "Aus"))]),
        ]
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        Image(systemName: connection.icon)
                            .font(.title2)
                            .foregroundStyle(connection.isActive ? .green : .secondary)
                            .frame(width: 36)
                            .contentTransition(.symbolEffect(.replace))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(connection.title).font(.body.weight(.semibold))
                            Text(connection.detail).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                    .listRowBackground(Theme.rowBackground)
                }

                Section(L("How it works", "So geht’s")) {
                    step(1, L("Open Control Center and tap Screen Mirroring, then pick your Apple TV or AirPlay TV. A USB-C → HDMI cable works too (no delay).",
                              "Kontrollzentrum öffnen, auf Bildschirmsynchronisierung tippen und Apple TV bzw. AirPlay-TV wählen. Ein USB-C → HDMI-Kabel geht auch (ohne Verzögerung)."))
                    step(2, L("Tap „Play on TV“. The game moves to the TV in 16:9 up to 1080p and switches to Docked.",
                              "„Auf TV spielen“ tippen. Das Spiel wechselt in 16:9 bis 1080p auf den Fernseher und schaltet auf Docked."))
                    step(3, L("Your iPhone stays the controller – or connect a Bluetooth controller. Stop mirroring to bring the game back.",
                              "Dein iPhone bleibt der Controller – oder verbinde einen Bluetooth-Controller. Synchronisierung beenden holt das Spiel zurück."))
                }
                .listRowBackground(Theme.rowBackground)

                Section {
                    ForEach(options) { option in
                        Picker(selection: binding(option.key)) {
                            Text(L("Like on iPhone", "Wie auf dem iPhone")).tag("")
                            ForEach(Array(option.pairs.enumerated()), id: \.offset) { _, pair in
                                Text(pair.1).tag(pair.0)
                            }
                        } label: {
                            SettingLabel(option.title, icon: option.icon, color: Theme.accent)
                        }
                    }
                } header: {
                    Text(L("TV settings for this game", "TV-Einstellungen für dieses Spiel"))
                } footer: {
                    Text(L("Only used when you start with „Play on TV“. For smooth AirPlay: 30 fps or 85 % render scale take load off the iPhone and the stream.",
                           "Gelten nur beim Start über „Auf TV spielen“. Für flüssiges AirPlay: 30 fps oder 85 % Renderskala entlasten iPhone und Übertragung."))
                }
                .listRowBackground(Theme.rowBackground)
            }
            .scrollContentBackground(.hidden)
            .background { AmbientBackground() }
            .navigationTitle(L("Play on TV", "Auf TV spielen"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("Close", "Schließen")) { save(); dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    save()
                    library.launch(current, tv: true)
                    dismiss()
                } label: {
                    Label(L("Play on TV", "Auf TV spielen"), systemImage: "play.tv.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
            .onAppear {
                values = current.settings
                connection = TVConnection.current()
            }
            .onReceive(timer) { _ in
                withAnimation(.smooth) { connection = TVConnection.current() }
            }
        }
    }

    private var current: GameEntry { library.game(game.titleID) ?? game }

    private func step(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(number)")
                .font(.footnote.weight(.bold))
                .frame(width: 22, height: 22)
                .background(Circle().fill(Theme.accent.opacity(0.25)))
            Text(text).font(.subheadline)
        }
        .padding(.vertical, 2)
    }

    private func binding(_ key: String) -> Binding<String> {
        Binding(
            get: { values[GameLibrary.tvPrefix + key] ?? "" },
            set: { values[GameLibrary.tvPrefix + key] = $0.isEmpty ? nil : $0 }
        )
    }

    private func save() {
        var merged = current.settings.filter { !$0.key.hasPrefix(GameLibrary.tvPrefix) }
        for (key, value) in values where key.hasPrefix(GameLibrary.tvPrefix) && !value.isEmpty {
            merged[key] = value
        }
        if merged != current.settings {
            library.updateSettings(current, merged)
        }
    }
}

/// Ist ein Fernseher verbunden? AirPlay-Synchronisierung oder Kabel spiegeln NSPiOS (die Spiel-Shell übernimmt
/// den Fernseher dann als eigene Szene).
struct TVConnection: Equatable {
    let isActive: Bool
    let wired: Bool

    static func current() -> TVConnection {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let external = scenes.contains { $0.session.role == .windowExternalDisplayNonInteractive }
        let captured = scenes.contains { $0.traitCollection.sceneCaptureState == .active }
        return TVConnection(isActive: external || captured, wired: external && !captured)
    }

    var icon: String { isActive ? (wired ? "cable.connector" : "airplayvideo.circle.fill") : "tv.slash" }

    var title: String {
        isActive ? L("TV connected", "Fernseher verbunden") : L("No TV connected", "Kein Fernseher verbunden")
    }

    var detail: String {
        isActive
            ? L("Ready – the game will take over the TV.", "Bereit – das Spiel übernimmt den Fernseher.")
            : L("Start Screen Mirroring in Control Center (or plug in a cable).", "Bildschirmsynchronisierung im Kontrollzentrum starten (oder Kabel anstecken).")
    }
}

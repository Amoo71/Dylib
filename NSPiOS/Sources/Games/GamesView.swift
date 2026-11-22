import SwiftUI

/// Tab „Games“: gebaute Spiele starten, verwalten, Einstellungen pro Spiel.
struct GamesView: View {
    @EnvironmentObject private var store: FileStore
    @ObservedObject private var library = GameLibrary.shared
    @ObservedObject private var builder = GameBuilder.shared

    @State private var showAdd = false
    @State private var settingsFor: GameEntry?
    @State private var updateFor: GameEntry?
    @State private var deleteFor: GameEntry?
    @State private var logFor: GameEntry?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 14) {
                    if !LiveContainer.isAvailable {
                        Label(L("Games run inside LiveContainer – install NSPiOS there.",
                                "Spiele laufen in LiveContainer – installiere NSPiOS dort."),
                              systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .padding(14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .glassCard(radius: 20)
                    }
                    ForEach(library.games) { game in
                        GameCard(game: game,
                                 captured: library.captured[game.titleID] ?? 0,
                                 prepared: library.isPreparedByLiveContainer(game),
                                 progress: builder.running && isBuilding(game) ? builder.progress : nil)
                            .onTapGesture { tap(game) }
                            .contextMenu { menu(for: game) }
                    }
                }
                .padding(16)
            }
            .background { AmbientBackground() }
            .overlay {
                if library.games.isEmpty {
                    EmptyState(icon: "gamecontroller", title: L("No games yet", "Noch keine Spiele")) {
                        Button { showAdd = true } label: {
                            Label(L("Add game", "Spiel hinzufügen"), systemImage: "plus")
                                .padding(.horizontal, 8)
                        }
                        .buttonStyle(.glassProminent)
                        .controlSize(.large)
                    }
                }
            }
            .navigationTitle("Games")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                        .disabled(builder.running)
                }
            }
            .refreshable { await library.refresh() }
            .task { await library.refresh() }
            .sheet(isPresented: $showAdd) {
                AddGameSheet(programs: GameLibrary.availablePrograms(in: store.extractedDir)) { program in
                    Task { await library.add(program) }
                }
                .presentationDetents([.medium, .large])
            }
            .sheet(item: $updateFor) { game in
                AddGameSheet(programs: GameLibrary.availablePrograms(in: store.extractedDir).filter { $0.titleID == game.titleID },
                             title: L("Use other files", "Andere Dateien verwenden")) { program in
                    Task { await library.update(game, with: program) }
                }
                .presentationDetents([.medium])
            }
            .sheet(item: $settingsFor) { game in
                GameSettingsSheet(game: game)
                    .presentationDetents([.large])
            }
            .sheet(item: $logFor) { game in
                GameLogSheet(game: game)
            }
            .confirmationDialog(L("Delete game?", "Spiel löschen?"),
                                isPresented: Binding(get: { deleteFor != nil }, set: { if !$0 { deleteFor = nil } }),
                                titleVisibility: .visible, presenting: deleteFor) { game in
                Button(L("Delete, keep saves", "Löschen, Spielstände behalten"), role: .destructive) {
                    library.delete(game, keepSaves: true)
                }
                Button(L("Delete everything", "Alles löschen"), role: .destructive) {
                    library.delete(game, keepSaves: false)
                }
            }
            .alert(L("Error", "Fehler"),
                   isPresented: Binding(get: { library.errorMessage != nil }, set: { if !$0 { library.errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(library.errorMessage ?? "")
            }
            .onChange(of: library.message) { _, text in
                if let text { store.toast = text; library.message = nil }
            }
            .jobInset()
        }
    }

    private func isBuilding(_ game: GameEntry) -> Bool {
        game.state == .building || game.state == .buildingFinal
    }

    private func tap(_ game: GameEntry) {
        switch game.state {
        case .ready, .needsCapture: library.launch(game)
        case .failed: logFor = game
        case .building, .buildingFinal: break
        }
    }

    @ViewBuilder
    private func menu(for game: GameEntry) -> some View {
        Button { library.launch(game) } label: { Label(L("Play", "Spielen"), systemImage: "play.fill") }
            .disabled(isBuilding(game))
        Button { settingsFor = game } label: { Label(L("Settings", "Einstellungen"), systemImage: "slider.horizontal.3") }
        Divider()
        if game.state == .needsCapture {
            Button { Task { await library.buildAndInstall(game, final: true) } } label: {
                Label(L("Finish with captured shaders", "Mit aufgenommenen Shadern fertigstellen"), systemImage: "checkmark.seal")
            }
            .disabled(builder.running || library.capturedCount(game) == 0)
        }
        if game.state == .ready, library.newShaders(game) > 0 {
            Button { Task { await library.buildAndInstall(game, final: true) } } label: {
                Label(L("Update shaders (+\(library.newShaders(game)))", "Shader aktualisieren (+\(library.newShaders(game)))"),
                      systemImage: "sparkles")
            }
            .disabled(builder.running)
        }
        Button { Task { await library.rebuild(game, recapture: false) } } label: {
            Label(L("Rebuild", "Neu bauen"), systemImage: "arrow.triangle.2.circlepath")
        }
        .disabled(builder.running)
        if game.program.needsCapture {
            Button { Task { await library.rebuild(game, recapture: true) } } label: {
                Label(L("Capture shaders again", "Shader neu aufnehmen"), systemImage: "camera.metering.center.weighted")
            }
            .disabled(builder.running)
        }
        Button { updateFor = game } label: {
            Label(L("Update / other files", "Update / andere Dateien"), systemImage: "square.and.arrow.down.on.square")
        }
        .disabled(builder.running)
        Button { logFor = game } label: { Label(L("Logs", "Logs"), systemImage: "doc.text.magnifyingglass") }
        Divider()
        Button(role: .destructive) { deleteFor = game } label: { Label(L("Delete", "Löschen"), systemImage: "trash") }
            .disabled(isBuilding(game))
    }
}

// MARK: - Karte

private struct GameCard: View {
    let game: GameEntry
    let captured: Int
    let prepared: Bool
    let progress: String?

    var body: some View {
        HStack(spacing: 14) {
            icon
                .frame(width: 64, height: 64)
                .clipShape(.rect(cornerRadius: 15, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(game.name).font(.headline).lineLimit(2)
                status
            }
            Spacer(minLength: 0)
            trailing
        }
        .padding(14)
        .glassCard(radius: 24)
        .contentShape(.rect)
    }

    @ViewBuilder
    private var icon: some View {
        if let image = UIImage(contentsOfFile: GameLibrary.iconURL(game.titleID).path) {
            Image(uiImage: image).resizable().scaledToFill()
        } else {
            ZStack {
                LinearGradient(colors: [Theme.violet, Theme.accent], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: "gamecontroller.fill").font(.title).foregroundStyle(.white)
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        switch game.state {
        case .building, .buildingFinal:
            Text(progress ?? (game.state == .building ? L("Building …", "Baue …") : L("Finishing …", "Stelle fertig …")))
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(2)
        case .needsCapture:
            VStack(alignment: .leading, spacing: 4) {
                if !prepared { firstStartHint }
                Text(L("Start once – captures shaders (~1 min)", "Einmal starten – nimmt Shader auf (~1 Min.)"))
                    .font(.caption)
                    .foregroundStyle(.orange)
                if game.expectedShaders > 0 {
                    ProgressView(value: Double(min(captured, game.expectedShaders)), total: Double(game.expectedShaders))
                        .tint(Theme.accent)
                    Text("\(captured) / \(game.expectedShaders)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        case .ready:
            VStack(alignment: .leading, spacing: 4) {
                if prepared {
                    Label(L("Ready", "Bereit"), systemImage: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.green)
                } else {
                    firstStartHint
                }
                if let built = game.builtShaders, captured > built {
                    Label(L("+\(captured - built) new shaders – hold › Update shaders", "+\(captured - built) neue Shader – halten › Shader aktualisieren"),
                          systemImage: "sparkles")
                        .font(.caption)
                        .foregroundStyle(Theme.accent)
                }
            }
        case .failed:
            Text(game.lastError ?? L("Build failed", "Build fehlgeschlagen"))
                .font(.caption)
                .foregroundStyle(Theme.danger)
                .lineLimit(2)
        }
    }

    private var firstStartHint: some View {
        Label(L("First start in LiveContainer's list (signs once)", "Erster Start in LiveContainers Liste (signiert einmalig)"),
              systemImage: "signature")
            .font(.caption)
            .foregroundStyle(.orange)
    }

    @ViewBuilder
    private var trailing: some View {
        switch game.state {
        case .building, .buildingFinal:
            ProgressView()
        case .ready, .needsCapture:
            Image(systemName: "play.fill")
                .font(.title3)
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .glassEffect(.regular.tint(Theme.accent.opacity(0.6)).interactive(), in: .circle)
        case .failed:
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.danger)
        }
    }
}

// MARK: - Hinzufügen

struct AddGameSheet: View {
    let programs: [GameProgram]
    var title: String = L("Add game", "Spiel hinzufügen")
    let onPick: (GameProgram) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if programs.isEmpty {
                    Text(L("No extracted game found. Extract an NSP/XCI with your prod.keys (RomFS & ExeFS) first.",
                           "Kein entpacktes Spiel gefunden. Entpacke zuerst eine NSP/XCI mit deinen prod.keys (RomFS & ExeFS)."))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .listRowBackground(Theme.rowBackground)
                }
                ForEach(programs) { program in
                    Button {
                        onPick(program)
                        dismiss()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: program.isSupported ? "gamecontroller.fill" : "nosign")
                                .foregroundStyle(program.isSupported ? Theme.accent : .secondary)
                                .frame(width: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(program.name).font(.body.weight(.medium))
                                Text(program.unsupportedReason
                                     ?? "\(program.titleID) · \(program.exefs.deletingLastPathComponent().lastPathComponent)")
                                    .font(.caption.monospaced())
                                    .foregroundStyle(program.isSupported ? AnyShapeStyle(.secondary) : AnyShapeStyle(.orange))
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                    .disabled(!program.isSupported)
                    .listRowBackground(Theme.rowBackground)
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
        }
    }
}

// MARK: - Einstellungen pro Spiel (Documents/env.txt)

struct GameSettingsSheet: View {
    let game: GameEntry
    @Environment(\.dismiss) private var dismiss
    @State private var values: [String: String] = [:]

    private struct Choice: Hashable {
        let value: String
        let label: String
    }

    private struct Option: Identifiable {
        let key: String
        let title: String
        let icon: String
        let pairs: [(String, String)]   // (Wert, Anzeige)
        var id: String { key }
        var choices: [Choice] { pairs.map { Choice(value: $0.0, label: $0.1) } }
    }

    private var options: [Option] {
        let on = L("On", "An"), off = L("Off", "Aus")
        return [
            Option(key: "DM_OPMODE", title: L("Console mode", "Konsolenmodus"), icon: "tv",
                   pairs: [("1", "Docked"), ("0", "Handheld")]),
            Option(key: "DM_FPS_TARGET", title: L("Frame rate", "Bildrate"), icon: "speedometer",
                   pairs: [("30", "30 fps"), ("60", "60 fps")]),
            Option(key: "DM_RENDER_SCALE", title: L("Render scale", "Renderskala"), icon: "aspectratio",
                   pairs: [("0.5", "50 %"), ("0.75", "75 %"), ("1.0", "100 %")]),
            Option(key: "DM_PICTURE_FIT", title: L("Picture", "Bild"), icon: "rectangle.expand.vertical",
                   pairs: [("fit", L("Fit (16:9)", "Einpassen (16:9)")), ("fill", L("Fill screen", "Füllen"))]),
            Option(key: "DM_PRESENT_UPSCALE", title: L("Upscaling", "Hochskalieren"), icon: "wand.and.stars",
                   pairs: [("off", "Standard"), ("metalfx", "MetalFX")]),
            Option(key: "DM_SHOW_FPS", title: L("FPS counter", "FPS-Anzeige"), icon: "gauge.with.dots.needle.67percent",
                   pairs: [("1", on), ("0", off)]),
            Option(key: "DM_TOUCH_HAPTICS", title: L("Touch feedback", "Touch-Haptik"), icon: "hand.tap",
                   pairs: [("1", on), ("0", off)]),
            Option(key: "DM_RUMBLE", title: L("Rumble", "Vibration"), icon: "iphone.radiowaves.left.and.right",
                   pairs: [("1", on), ("0", off)]),
            Option(key: "DM_PSO_PREBUILD", title: L("Pipeline prebuild", "Pipelines vorbereiten"), icon: "bolt.horizontal",
                   pairs: [("1", on), ("0", off)]),
            Option(key: "DM_LAUNCHER", title: L("Settings screen at start", "Einstellungsbildschirm beim Start"), icon: "list.bullet.rectangle",
                   pairs: [("1", L("Show", "Zeigen")), ("0", L("Skip", "Überspringen"))]),
            Option(key: "DM_LANG", title: L("Game language", "Spielsprache"), icon: "globe",
                   pairs: [("en", "English"), ("de", "Deutsch"), ("fr", "Français"), ("es", "Español"),
                             ("it", "Italiano"), ("ja", "日本語")]),
        ]
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(options) { option in
                        Picker(selection: binding(option.key)) {
                            Text(L("Game default", "Standard des Spiels")).tag("")
                            ForEach(option.choices, id: \.self) { choice in
                                Text(choice.label).tag(choice.value)
                            }
                        } label: {
                            SettingLabel(option.title, icon: option.icon, color: Theme.accent)
                        }
                        .pickerStyle(.menu)
                        .listRowBackground(Theme.rowBackground)
                    }
                } footer: {
                    Text(L("Written to the game's env.txt – it wins over the in-game settings screen.",
                           "Wird in die env.txt des Spiels geschrieben – gilt vor dem Einstellungsbildschirm im Spiel."))
                }
            }
            .scrollContentBackground(.hidden)
            .background { AmbientBackground() }
            .navigationTitle(game.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        GameLibrary.shared.updateSettings(game, values)
                        dismiss()
                    } label: { Image(systemName: "checkmark") }
                }
            }
            .onAppear { values = game.settings }
        }
    }

    private func binding(_ key: String) -> Binding<String> {
        Binding(get: { values[key] ?? "" }, set: { values[key] = $0 })
    }
}

// MARK: - Build-Log

struct GameLogSheet: View {
    let game: GameEntry
    @Environment(\.dismiss) private var dismiss
    @State private var kind = 0          // 0 = Spiel-Log (log.txt), 1 = Build-Log
    @State private var text = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(text.isEmpty ? L("No log yet.", "Noch kein Log.") : text)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .safeAreaInset(edge: .top) {
                Picker("", selection: $kind) {
                    Text(L("Game log", "Spiel-Log")).tag(0)
                    Text(L("Build log", "Build-Log")).tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.vertical, 6)
            }
            .navigationTitle(game.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
                if !text.isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: text) { Image(systemName: "square.and.arrow.up") }
                    }
                }
            }
            .task(id: kind) { text = kind == 0 ? Self.gameLog(game) : Self.latestLog(game) }
        }
    }

    /// log.txt der Spiel-Shell (Documents des Spiels in LiveContainer) plus Shader-Status.
    @MainActor
    static func gameLog(_ game: GameEntry) -> String {
        let library = GameLibrary.shared
        var header = "state: \(game.state.rawValue) · shaders captured: \(library.capturedCount(game))/\(game.expectedShaders)"
        header += " · prepared by LiveContainer: \(library.isPreparedByLiveContainer(game) ? "yes" : "no")\n"
        guard let docs = library.documents(game) else { return header + "(no LiveContainer data folder)" }
        header += "data: \(docs.path)\n"
        if let env = try? String(contentsOf: docs.appendingPathComponent("env.txt"), encoding: .utf8) {
            header += "env.txt:\n\(env)\n"
        }
        guard let log = try? String(contentsOf: docs.appendingPathComponent("log.txt"), encoding: .utf8) else {
            return header + "(no log.txt yet – start the game once)"
        }
        return header + "\n" + (log.count > 80_000 ? "…\n" + String(log.suffix(80_000)) : log)
    }

    static func latestLog(_ game: GameEntry) -> String {
        let logs = GameBuilder.gamesRoot.appendingPathComponent(game.titleID).appendingPathComponent("logs")
        let files = ((try? FileManager.default.contentsOfDirectory(at: logs, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "log" }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
        var text = (game.lastError.map { "⚠️ \($0)\n\n" } ?? "")
        if let latest = files.first, let content = try? String(contentsOf: latest, encoding: .utf8) {
            text += content.count > 60_000 ? "…\n" + String(content.suffix(60_000)) : content
        }
        return text
    }
}

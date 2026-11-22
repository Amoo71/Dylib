import SwiftUI

/// „Update installieren …“: sucht in den Paketen das Update und das Basisspiel zu diesem Spiel, führt beide zusammen
/// (gepatchtes RomFS, Code des Updates) und baut das Spiel neu. Spielstände bleiben (gleicher LiveContainer-Datenordner).
struct InstallUpdateSheet: View {
    let game: GameEntry
    @EnvironmentObject private var store: FileStore
    @Environment(\.dismiss) private var dismiss

    private struct Found: Identifiable {
        let item: FileItem
        let meta: ContentMeta.Info
        var id: URL { item.url }
    }

    @State private var scanning = true
    @State private var updates: [Found] = []
    @State private var base: FileItem?

    private var titleID: UInt64 { UInt64(game.titleID, radix: 16) ?? 0 }
    private var wanted: UInt32 { game.program.wantedVersion }

    var body: some View {
        NavigationStack {
            List {
                if scanning {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(L("Looking through your packages …", "Durchsuche deine Pakete …")).foregroundStyle(.secondary)
                    }
                    .listRowBackground(Theme.rowBackground)
                } else {
                    Section {
                        statusRow(ok: base != nil,
                                  title: base?.name ?? L("Base game missing", "Basisspiel fehlt"),
                                  detail: base != nil ? L("Base game", "Basisspiel")
                                                      : L("Import the base game's NSP/XCI in Packages – the update only holds changes.",
                                                          "Importiere das NSP/XCI des Basisspiels unter Pakete – das Update enthält nur Änderungen."))
                    }
                    .listRowBackground(Theme.rowBackground)

                    Section {
                        if updates.isEmpty {
                            Text(L("No update for this game in Packages. Import the update NSP first.",
                                   "Kein Update für dieses Spiel unter Pakete. Importiere zuerst das Update-NSP."))
                                .foregroundStyle(.secondary)
                        }
                        ForEach(updates) { found in
                            Button { install(found) } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "arrow.down.app.fill")
                                        .font(.title2)
                                        .foregroundStyle(Theme.accent)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(found.item.name).font(.body.weight(.medium)).lineLimit(2)
                                        Text("v\(found.meta.version)" + (found.meta.version == wanted && wanted > 0
                                                ? L(" · the version the profile is made for", " · die Version des Profils") : ""))
                                            .font(.caption.monospaced())
                                            .foregroundStyle(found.meta.version == wanted ? AnyShapeStyle(.green) : AnyShapeStyle(.secondary))
                                    }
                                }
                            }
                            .tint(.primary)
                            .disabled(base == nil || store.job != nil)
                        }
                    } header: {
                        Text(L("Updates", "Updates"))
                    } footer: {
                        Text(L("NSPiOS merges base game and update into a new folder and rebuilds the game. Your saves stay.",
                               "NSPiOS führt Basisspiel und Update in einem neuen Ordner zusammen und baut das Spiel neu. Deine Spielstände bleiben."))
                    }
                    .listRowBackground(Theme.rowBackground)
                }
            }
            .scrollContentBackground(.hidden)
            .background { AmbientBackground() }
            .navigationTitle(L("Install update", "Update installieren"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("Close", "Schließen")) { dismiss() }
                }
            }
            .task { await scan() }
        }
    }

    private func statusRow(ok: Bool, title: String, detail: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .foregroundStyle(ok ? .green : .orange)
                .font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.medium)).lineLimit(2)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func scan() async {
        let packages = store.packages
        let tid = titleID
        let result = await Task.detached(priority: .userInitiated) { () -> ([Found], FileItem?) in
            guard let keys = KeyStore.load() else { return ([], nil) }
            var updates: [Found] = []
            var base: FileItem?
            for item in packages {
                for meta in PackageReader.contents(of: item.url, keys: keys) where meta.baseTitleID == tid {
                    if meta.isUpdate {
                        updates.append(Found(item: item, meta: meta))
                    } else if meta.type == 0x80, base == nil {
                        base = item
                    }
                }
            }
            return (updates.sorted { $0.meta.version > $1.meta.version }, base)
        }.value
        updates = result.0
        base = result.1
        scanning = false
    }

    private func install(_ found: Found) {
        guard let base else { return }
        let entry = game
        store.enqueueMerged(base: base.url, update: found.item.url) { folder in
            guard let program = GameBuilder.findPrograms(in: folder).first(where: { $0.titleID == entry.titleID }) else {
                GameLibrary.shared.errorMessage = L("The merged folder holds no program for this game.",
                                                    "Im zusammengeführten Ordner ist kein Programm für dieses Spiel.")
                return
            }
            Task { await GameLibrary.shared.update(GameLibrary.shared.game(entry.titleID) ?? entry, with: program) }
        }
        dismiss()
    }
}

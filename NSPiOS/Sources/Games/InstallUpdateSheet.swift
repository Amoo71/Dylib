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
                        if let base {
                            statusRow(ok: true, title: base.name, detail: L("Base game", "Basisspiel"))
                        } else if installedRomFS != nil {
                            statusRow(ok: true, title: L("Installed game", "Installiertes Spiel"),
                                      detail: L("No base package needed: the unchanged data comes from the installed game and is checked against the update's hashes.",
                                                "Kein Basispaket nötig: unveränderte Daten kommen aus dem installierten Spiel und werden gegen die Prüfsummen des Updates geprüft."))
                        } else {
                            statusRow(ok: false, title: L("Base game missing", "Basisspiel fehlt"),
                                      detail: L("Import the base game's NSP/XCI in Packages – the update only holds changes.",
                                                "Importiere das NSP/XCI des Basisspiels unter Pakete – das Update enthält nur Änderungen."))
                        }
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
                            .disabled((base == nil && installedRomFS == nil) || store.job != nil)
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

    /// RomFS-Ordner des installierten Spiels (Basisdaten, wenn kein Basispaket mehr da ist).
    private var installedRomFS: URL? {
        guard let app = GameLibrary.shared.installedApp(game) else { return nil }
        let romfs = app.appendingPathComponent("romfs")
        return FileManager.default.fileExists(atPath: romfs.path) ? romfs : nil
    }

    private func install(_ found: Found) {
        let tid = game.titleID
        let then: @MainActor @Sendable (URL) -> Void = { [store] folder in store.finishUpdate(tidHex: tid, folder: folder) }
        if let base {
            store.enqueueMerged(base: base.url, update: found.item.url, then: then)
        } else if let romfs = installedRomFS {
            store.enqueueUpdateOnInstalled(update: found.item.url, installedRomFS: romfs, then: then)
        }
        dismiss()
    }
}

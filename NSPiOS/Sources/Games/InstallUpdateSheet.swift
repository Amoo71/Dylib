import SwiftUI
import UniformTypeIdentifiers

/// „Update installieren …“: Update auswählen (erkannt, beliebiges Paket, entpackter Ordner oder aus der Dateien-App),
/// einspielen und das Spiel neu bauen. Spielstände bleiben (gleicher LiveContainer-Datenordner).
/// Ohne Basispaket kommen die unveränderten Daten aus dem installierten Spiel (geprüft gegen die Prüfsummen des Updates).
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
    @State private var detected: [Found] = []
    @State private var base: FileItem?
    @State private var pickFromFiles = false

    private var titleID: UInt64 { UInt64(game.titleID, radix: 16) ?? 0 }
    private var wanted: UInt32 { game.program.wantedVersion }

    /// RomFS-Ordner des installierten Spiels (Basisdaten, wenn kein Basispaket mehr da ist).
    private var installedRomFS: URL? {
        guard let app = GameLibrary.shared.installedApp(game) else { return nil }
        let romfs = app.appendingPathComponent("romfs")
        return FileManager.default.fileExists(atPath: romfs.path) ? romfs : nil
    }

    private var otherPackages: [FileItem] {
        store.packages.filter { item in !detected.contains { $0.item.url == item.url } && item.url != base?.url }
    }

    /// Entpackte Ordner mit NCA-Dateien (z. B. ein früher entpacktes Update)
    private var ncaFolders: [FileItem] {
        store.extracted.filter { $0.isDirectory && !FS.ncaFiles(in: $0.url).ncas.isEmpty }
    }

    private var canInstall: Bool { (base != nil || installedRomFS != nil) && store.job == nil }

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
                        if installedRomFS != nil {
                            statusRow(ok: true, title: L("Installed game", "Installiertes Spiel"),
                                      detail: L("No base package needed: unchanged data comes from the installed game and is checked against the update's hashes.",
                                                "Kein Basispaket nötig: unveränderte Daten kommen aus dem installierten Spiel und werden gegen die Prüfsummen des Updates geprüft."))
                        } else if let base {
                            statusRow(ok: true, title: base.name, detail: L("Base game", "Basisspiel"))
                        } else {
                            statusRow(ok: false, title: L("Base game missing", "Basisspiel fehlt"),
                                      detail: L("Build the game once, or import the base game's NSP/XCI.",
                                                "Baue das Spiel einmal oder importiere das NSP/XCI des Basisspiels."))
                        }
                    }
                    .listRowBackground(Theme.rowBackground)

                    Section {
                        Button { pickFromFiles = true } label: {
                            Label(L("Choose from Files …", "Aus Dateien wählen …"), systemImage: "folder")
                        }
                        .disabled(!canInstall)
                    } footer: {
                        Text(L("NSP, NSZ, XCI or a single .nca of the update.", "NSP, NSZ, XCI oder eine einzelne .nca des Updates."))
                    }
                    .listRowBackground(Theme.rowBackground)

                    if !detected.isEmpty {
                        Section(L("Updates for this game", "Updates für dieses Spiel")) {
                            ForEach(detected) { found in
                                row(found.item.url, title: found.item.name,
                                    detail: "v\(found.meta.version)" + (found.meta.version == wanted && wanted > 0
                                        ? L(" · the version the profile is made for", " · die Version des Profils") : ""),
                                    highlight: found.meta.version == wanted, icon: "arrow.down.app.fill")
                            }
                        }
                        .listRowBackground(Theme.rowBackground)
                    }

                    if !otherPackages.isEmpty {
                        Section(L("Other packages", "Andere Pakete")) {
                            ForEach(otherPackages) { item in
                                row(item.url, title: item.name, detail: item.url.pathExtension.uppercased(), highlight: false, icon: "shippingbox")
                            }
                        }
                        .listRowBackground(Theme.rowBackground)
                    }

                    if !ncaFolders.isEmpty {
                        Section {
                            ForEach(ncaFolders) { item in
                                row(item.url, title: item.name, detail: L("Extracted folder with .nca files", "Entpackter Ordner mit .nca-Dateien"),
                                    highlight: false, icon: "folder.fill")
                            }
                        } header: {
                            Text(L("Extracted folders", "Entpackte Ordner"))
                        } footer: {
                            Text(L("Works when the folder still holds the update's .nca files.", "Geht, wenn der Ordner noch die .nca-Dateien des Updates enthält."))
                        }
                        .listRowBackground(Theme.rowBackground)
                    }
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
            .fileImporter(isPresented: $pickFromFiles,
                          allowedContentTypes: FileTypes.importable + [UTType(filenameExtension: "nca") ?? .data],
                          allowsMultipleSelection: false) { result in
                if case .success(let urls) = result, let url = urls.first {
                    install(url, securityScoped: true)
                }
            }
            .task { await scan() }
        }
    }

    private func row(_ url: URL, title: String, detail: String, highlight: Bool, icon: String) -> some View {
        Button { install(url, securityScoped: false) } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundStyle(Theme.accent)
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.medium)).lineLimit(2)
                    Text(detail)
                        .font(.caption.monospaced())
                        .foregroundStyle(highlight ? AnyShapeStyle(.green) : AnyShapeStyle(.secondary))
                }
            }
        }
        .tint(.primary)
        .disabled(!canInstall)
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
        detected = result.0
        base = result.1
        scanning = false
    }

    private func install(_ url: URL, securityScoped: Bool) {
        let tid = game.titleID
        let then: @MainActor @Sendable (URL) -> Void = { [store] folder in store.finishUpdate(tidHex: tid, folder: folder) }
        let isPackage = PackageReader.supportedExtensions.contains(url.pathExtension.lowercased())
        if let romfs = installedRomFS {
            store.enqueueUpdateOnInstalled(update: url, installedRomFS: romfs, securityScoped: securityScoped, then: then)
        } else if let base, isPackage, !securityScoped {
            store.enqueueMerged(base: base.url, update: url, then: then)
        } else {
            store.alert = AlertMessage(title: L("Base game missing", "Basisspiel fehlt"),
                                       message: L("Build the game once, or import the base game's NSP/XCI.",
                                                  "Baue das Spiel einmal oder importiere das NSP/XCI des Basisspiels."))
        }
        dismiss()
    }
}

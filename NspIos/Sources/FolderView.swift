import SwiftUI

/// Durchsucht einen entpackten Ordner (inkl. Unterordner).
struct FolderView: View {
    @EnvironmentObject private var store: FileStore
    @Environment(\.dismiss) private var dismiss

    let url: URL

    @State private var items: [FileItem] = []
    @State private var loaded = false
    @State private var contentsItem: FileItem?
    @State private var confirmDelete = false

    var body: some View {
        List {
            if loaded && items.isEmpty {
                EmptyRow(text: "Dieser Ordner ist leer.")
            }
            ForEach(items) { item in
                if item.isDirectory {
                    NavigationLink {
                        FolderView(url: item.url)
                    } label: {
                        FileRow(item: item)
                    }
                    .itemActions(item)
                } else {
                    FileRow(item: item)
                        .itemActions(item) { contentsItem = $0 }
                }
            }
        }
        .navigationTitle(url.lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { store.export([url], asCopy: false) } label: {
                        Label("Ordner in Dateien verschieben", systemImage: "folder.badge.plus")
                    }
                    Button { store.export([url], asCopy: true) } label: {
                        Label("Ordner in Dateien kopieren", systemImage: "doc.on.doc")
                    }
                    Divider()
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Ordner löschen", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .confirmationDialog("„\(url.lastPathComponent)“ löschen?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) {
                store.delete([url])
                dismiss()
            }
            Button("Abbrechen", role: .cancel) {}
        }
        .sheet(item: $contentsItem) { item in
            PackageContentsView(url: item.url)
        }
        .refreshable { await load() }
        .task(id: store.revision) {
            await load()
            // Ordner wurde verschoben oder gelöscht → zurück
            if !FileManager.default.fileExists(atPath: url.path) { dismiss() }
        }
    }

    private func load() async {
        let folder = url
        items = await Task.detached(priority: .userInitiated) { FS.list(folder) }.value
        loaded = true
    }
}

/// Zeigt den Inhalt einer NSP-Datei, ohne sie zu entpacken.
struct PackageContentsView: View {
    let url: URL

    @Environment(\.dismiss) private var dismiss
    @State private var entries: [PFS0Entry] = []
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            List {
                if let errorText {
                    Text(errorText).foregroundStyle(.red)
                }
                if !entries.isEmpty {
                    Section {
                        ForEach(entries) { entry in
                            HStack {
                                Text(entry.name)
                                    .font(.callout.monospaced())
                                    .lineLimit(2)
                                Spacer()
                                Text(formatBytes(Int64(clamping: entry.size)))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    } footer: {
                        Text("\(entries.count) Dateien · \(formatBytes(Int64(clamping: entries.reduce(UInt64(0)) { $0 + $1.size })))")
                    }
                }
            }
            .navigationTitle(url.lastPathComponent)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { dismiss() }
                }
            }
            .task {
                let source = url
                let result = await Task.detached { Result { try PFS0.entries(of: source) } }.value
                switch result {
                case .success(let list): entries = list
                case .failure(let error): errorText = error.localizedDescription
                }
            }
        }
    }
}

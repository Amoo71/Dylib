import SwiftUI

/// Tab „Entpackt“: alle entpackten Ordner.
struct ExtractedView: View {
    @EnvironmentObject private var store: FileStore
    @State private var query = ""

    private var filtered: [FileItem] {
        query.isEmpty ? store.extracted : store.extracted.filter { $0.name.localizedStandardContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(filtered) { item in
                    NavigationLink {
                        FolderView(url: item.url)
                    } label: {
                        FileRow(item: item)
                    }
                    .listRowBackground(Theme.rowBackground)
                    .itemActions(item)
                }
            }
            .ambientList()
            .overlay {
                if store.extracted.isEmpty {
                    EmptyState(
                        icon: "folder",
                        title: "Noch nichts entpackt",
                        message: "Entpackte NSPs landen hier als Ordner. Von hier kannst du sie in die Dateien-App verschieben."
                    ) { EmptyView() }
                } else if filtered.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .searchable(text: $query, prompt: "Ordner suchen")
            .navigationTitle("Entpackt")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { store.export(store.extracted.map(\.url), asCopy: false) } label: {
                            Label("Alle in Dateien verschieben", systemImage: "folder.badge.plus")
                        }
                        Button { store.export(store.extracted.map(\.url), asCopy: true) } label: {
                            Label("Alle in Dateien kopieren", systemImage: "doc.on.doc")
                        }
                    } label: {
                        Image(systemName: "square.and.arrow.up.on.square")
                    }
                    .disabled(store.extracted.isEmpty)
                }
            }
            .refreshable { store.refresh() }
            .jobInset()
        }
    }
}

/// Durchsucht einen entpackten Ordner (inkl. Unterordner).
struct FolderView: View {
    @EnvironmentObject private var store: FileStore
    @Environment(\.dismiss) private var dismiss

    let url: URL

    @State private var items: [FileItem] = []
    @State private var loaded = false
    @State private var confirmDelete = false

    private var totalSize: Int64 { items.reduce(0) { $0 + $1.size } }

    var body: some View {
        List {
            if loaded && !items.isEmpty {
                Section {
                    ForEach(items) { item in
                        Group {
                            if item.isDirectory {
                                NavigationLink { FolderView(url: item.url) } label: { FileRow(item: item) }
                            } else {
                                FileRow(item: item)
                            }
                        }
                        .listRowBackground(Theme.rowBackground)
                        .itemActions(item)
                    }
                } footer: {
                    Text("\(items.count) Objekte · \(formatBytes(totalSize))")
                        .monospacedDigit()
                }
            }
        }
        .ambientList()
        .overlay {
            if loaded && items.isEmpty {
                ContentUnavailableView("Ordner ist leer", systemImage: "folder")
            }
        }
        .navigationTitle(url.lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { store.export([url], asCopy: false) } label: {
                        Label("In Dateien verschieben", systemImage: "folder.badge.plus")
                    }
                    Button { store.export([url], asCopy: true) } label: {
                        Label("In Dateien kopieren", systemImage: "doc.on.doc")
                    }
                    Divider()
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Ordner löschen", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                }
            }
        }
        .confirmationDialog("„\(url.lastPathComponent)“ löschen?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) {
                store.delete([url])
                dismiss()
            }
        }
        .refreshable { await load() }
        .task(id: store.revision) {
            await load()
            // Ordner wurde verschoben oder gelöscht → zurück
            if !FileManager.default.fileExists(atPath: url.path) { dismiss() }
        }
        .jobInset()
    }

    private func load() async {
        let folder = url
        let result = await Task.detached(priority: .userInitiated) { FS.list(folder) }.value
        withAnimation(.smooth) {
            items = result
            loaded = true
        }
    }
}

/// Zeigt den Inhalt einer NSP-Datei, ohne sie zu entpacken.
struct PackageContentsView: View {
    let url: URL

    @Environment(\.dismiss) private var dismiss
    @State private var entries: [PFS0Entry] = []
    @State private var errorText: String?

    private var total: Int64 { Int64(clamping: entries.reduce(UInt64(0)) { $0 + $1.size }) }

    var body: some View {
        NavigationStack {
            List {
                if let errorText {
                    Label(errorText, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Theme.danger)
                        .listRowBackground(Theme.rowBackground)
                }
                if !entries.isEmpty {
                    Section {
                        ForEach(entries) { entry in
                            HStack(spacing: 12) {
                                Image(systemName: FileRow.symbol(forExtension: (entry.name as NSString).pathExtension))
                                    .foregroundStyle(Theme.accent)
                                    .frame(width: 22)
                                Text(entry.name)
                                    .font(.callout.monospaced())
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                Spacer()
                                Text(formatBytes(Int64(clamping: entry.size)))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            .listRowBackground(Theme.rowBackground)
                        }
                    } footer: {
                        Text("\(entries.count) Dateien · \(formatBytes(total))")
                            .monospacedDigit()
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle(url.deletingPathExtension().lastPathComponent)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Schließen")
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

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
                    EmptyState(icon: "folder", title: L("Nothing extracted", "Noch nichts entpackt")) { EmptyView() }
                } else if filtered.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .searchable(text: $query, prompt: L("Search", "Suchen"))
            .navigationTitle(L("Extracted", "Entpackt"))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { store.export(store.extracted.map(\.url), asCopy: false) } label: {
                            Label(L("Move all to Files", "Alle in Dateien verschieben"), systemImage: "folder.badge.plus")
                        }
                        Button { store.export(store.extracted.map(\.url), asCopy: true) } label: {
                            Label(L("Copy all to Files", "Alle in Dateien kopieren"), systemImage: "doc.on.doc")
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
    @State private var watcher: DirectoryWatcher?
    @State private var programs: [GameProgram] = []
    @State private var showBuild = false

    private var totalSize: Int64 { items.reduce(0) { $0 + $1.size } }

    /// Ordner hat NCAs (auch in Partition-Unterordnern), aber noch keinen romfs-Ordner.
    private var canMakeRomFS: Bool {
        let names = items.map { $0.name.lowercased() }
        let hasRomFS = names.contains { $0.hasPrefix("romfs") }
        let hasNCA = names.contains { $0.hasSuffix(".nca") } || names.contains("secure")
        return hasNCA && !hasRomFS
    }

    var body: some View {
        List {
            if loaded && canMakeRomFS {
                Section {
                    Button {
                        store.extractRomFS(from: url)
                    } label: {
                        Label(L("Extract RomFS", "RomFS entpacken"), systemImage: store.keyCount > 0 ? "cube.transparent" : "key.slash")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.glassProminent)
                    .disabled(store.job != nil)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                }
            }
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
                    Text("\(items.count) · \(formatBytes(totalSize))")
                        .monospacedDigit()
                }
            }
        }
        .ambientList()
        .overlay {
            if loaded && items.isEmpty {
                ContentUnavailableView(L("Empty folder", "Ordner ist leer"), systemImage: "folder")
            }
        }
        .navigationTitle(url.lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    if !programs.isEmpty {
                        Button { showBuild = true } label: {
                            Label(L("Build game", "Spiel bauen"), systemImage: "gamecontroller.fill")
                        }
                    }
                    Button { store.extractRomFS(from: url) } label: {
                        Label(L("Extract RomFS", "RomFS entpacken"), systemImage: "cube.transparent")
                    }
                    Divider()
                    Button { store.export([url], asCopy: false) } label: {
                        Label(L("Move to Files", "In Dateien verschieben"), systemImage: "folder.badge.plus")
                    }
                    Button { store.export([url], asCopy: true) } label: {
                        Label(L("Copy to Files", "In Dateien kopieren"), systemImage: "doc.on.doc")
                    }
                    Divider()
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label(L("Delete folder", "Ordner löschen"), systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                }
            }
        }
        .confirmationDialog(L("Delete „\(url.lastPathComponent)“?", "„\(url.lastPathComponent)“ löschen?"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("Delete", "Löschen"), role: .destructive) {
                store.delete([url])
                dismiss()
            }
        }
        .sheet(isPresented: $showBuild) {
            AddGameSheet(programs: programs, title: L("Build game", "Spiel bauen")) { program in
                store.toast = L("Building \(program.name) – see Games", "Baue \(program.name) – siehe Games")
                Task { await GameLibrary.shared.add(program) }
            }
            .presentationDetents([.medium, .large])
        }
        .refreshable { await load() }
        .onAppear {
            // Änderungen in diesem Ordner (auch aus der Dateien-App) sofort übernehmen
            watcher = DirectoryWatcher(url: url) { store.scheduleRefresh() }
        }
        .onDisappear { watcher = nil }
        .task(id: store.revision) {
            await load()
            // Ordner wurde verschoben oder gelöscht → zurück
            if !FileManager.default.fileExists(atPath: url.path) { dismiss() }
        }
        .jobInset()
    }

    private func load() async {
        let folder = url
        let (result, found) = await Task.detached(priority: .userInitiated) {
            (FS.list(folder), GameBuilder.findPrograms(in: folder))
        }.value
        programs = found
        withAnimation(.smooth) {
            items = result
            loaded = true
        }
    }
}

/// Zeigt den Inhalt einer NSP/XCI-Datei, ohne sie zu entpacken (XCI nach Partition gruppiert).
struct PackageContentsView: View {
    let url: URL

    @Environment(\.dismiss) private var dismiss
    @State private var info: PackageInfo?
    @State private var errorText: String?

    private struct Partition: Identifiable {
        let name: String?
        let entries: [PackageEntry]
        var id: String { name ?? "" }
        var size: Int64 { Int64(clamping: entries.reduce(UInt64(0)) { $0 + $1.size }) }
    }

    private var groups: [Partition] {
        guard let info else { return [] }
        var order: [String?] = []
        var map: [String: [PackageEntry]] = [:]
        for entry in info.entries {
            let key = entry.folder ?? ""
            if map[key] == nil { order.append(entry.folder) }
            map[key, default: []].append(entry)
        }
        // "secure" (Spieldaten) zuerst
        order.sort { a, b in a == "secure" && b != "secure" }
        return order.map { Partition(name: $0, entries: map[$0 ?? ""] ?? []) }
    }

    var body: some View {
        NavigationStack {
            List {
                if let errorText {
                    Label(errorText, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Theme.danger)
                        .listRowBackground(Theme.rowBackground)
                }
                if let info {
                    HStack(spacing: 10) {
                        Text(info.format.rawValue)
                            .font(.caption.weight(.bold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .glassEffect(.regular.tint(Theme.accent.opacity(0.35)), in: .capsule)
                        Text("\(info.entries.count) · \(formatBytes(Int64(clamping: info.entries.reduce(UInt64(0)) { $0 + $1.size })))")
                            .font(.subheadline.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    .listRowBackground(Color.clear)
                }
                ForEach(groups) { group in
                    Section {
                        ForEach(group.entries) { entry in
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
                    } header: {
                        if let name = group.name {
                            HStack {
                                Text(name)
                                Spacer()
                                Text(formatBytes(group.size)).monospacedDigit()
                            }
                        }
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
                    .accessibilityLabel(L("Close", "Schließen"))
                }
            }
            .task {
                let source = url
                let result = await Task.detached { Result { try PackageReader.inspect(source) } }.value
                switch result {
                case .success(let value): info = value
                case .failure(let error): errorText = error.localizedDescription
                }
            }
        }
    }
}

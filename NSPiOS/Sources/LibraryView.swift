import SwiftUI

/// Tab „Packages“: importierte NSP/XCI-Dateien.
struct LibraryView: View {
    @EnvironmentObject private var store: FileStore
    @State private var query = ""

    private var filtered: [FileItem] {
        query.isEmpty ? store.packages : store.packages.filter { $0.name.localizedStandardContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(filtered) { item in
                    HStack(spacing: 12) {
                        FileRow(item: item)
                        Spacer(minLength: 4)
                        Button {
                            store.enqueueExtraction(of: item.url)
                        } label: {
                            Image(systemName: "arrow.down.to.line")
                                .font(.body.weight(.semibold))
                                .frame(width: 22, height: 22)
                        }
                        .buttonStyle(.glassProminent)
                        .buttonBorderShape(.circle)
                        .disabled(store.job != nil)
                        .accessibilityLabel(L("Extract", "Entpacken"))
                    }
                    .listRowBackground(Theme.rowBackground)
                    .itemActions(item)
                }
            }
            .ambientList()
            .overlay {
                if store.packages.isEmpty {
                    EmptyState(icon: "shippingbox", title: L("No packages", "Keine Pakete")) {
                        Button {
                            store.requestImport(.copy)
                        } label: {
                            Label(L("Import", "Importieren"), systemImage: "plus")
                                .padding(.horizontal, 8)
                        }
                        .buttonStyle(.glassProminent)
                        .controlSize(.large)
                    }
                } else if filtered.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .searchable(text: $query, prompt: L("Search", "Suchen"))
            .navigationTitle("NSPiOS")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    ImportMenu()
                }
            }
            .refreshable { store.refresh() }
            .jobInset()
            .sheet(item: $store.mergeBase) { base in
                UpdatePickerSheet(base: base)
                    .presentationDetents([.medium, .large])
            }
        }
    }
}

/// „Mit Update entpacken …“: Update-Paket zum gewählten Basisspiel aussuchen.
struct UpdatePickerSheet: View {
    @EnvironmentObject private var store: FileStore
    @Environment(\.dismiss) private var dismiss
    let base: FileItem

    private var candidates: [FileItem] {
        store.packages.filter { $0.url != base.url }
            .sorted { ($0.name.localizedCaseInsensitiveContains("update") ? 0 : 1, $0.name) < ($1.name.localizedCaseInsensitiveContains("update") ? 0 : 1, $1.name) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(candidates) { item in
                        Button {
                            store.enqueueMerged(base: base.url, update: item.url)
                            dismiss()
                        } label: {
                            FileRow(item: item)
                        }
                        .tint(.primary)
                    }
                } footer: {
                    Text(L("Base game: \(base.name). The update's code and the patched RomFS land in one folder – that is the version the game profiles are made for.",
                           "Basisspiel: \(base.name). Code des Updates und das gepatchte RomFS landen in einem Ordner – genau den Stand brauchen die Spielprofile."))
                }
            }
            .navigationTitle(L("Choose update", "Update wählen"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("Cancel", "Abbrechen")) { dismiss() }
                }
            }
        }
    }
}

struct ImportMenu: View {
    @EnvironmentObject private var store: FileStore

    var body: some View {
        Menu {
            Button { store.requestImport(.copy) } label: {
                Label(L("Import", "Importieren"), systemImage: "square.and.arrow.down")
            }
            Button { store.requestImport(.copyAndExtract) } label: {
                Label(L("Import & extract", "Importieren & entpacken"), systemImage: "archivebox")
            }
            Button { store.requestImport(.extractDirect) } label: {
                Label(L("Extract directly", "Direkt entpacken"), systemImage: "bolt.fill")
            }
        } label: {
            Image(systemName: "plus")
        }
    }
}

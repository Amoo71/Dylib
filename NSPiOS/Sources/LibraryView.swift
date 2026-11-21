import SwiftUI

/// Tab „Pakete“: importierte NSP/XCI-Dateien.
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
                        .accessibilityLabel("Entpacken")
                    }
                    .listRowBackground(Theme.rowBackground)
                    .itemActions(item)
                }
            }
            .ambientList()
            .overlay {
                if store.packages.isEmpty {
                    EmptyState(
                        icon: "shippingbox",
                        title: "Keine Pakete",
                        message: "Importiere .nsp, .nsz, .xci oder .xcz aus der Dateien-App – oder entpacke sie direkt ohne Kopie."
                    ) {
                        Button {
                            store.requestImport(.copy)
                        } label: {
                            Label("Importieren", systemImage: "plus")
                                .padding(.horizontal, 8)
                        }
                        .buttonStyle(.glassProminent)
                        .controlSize(.large)
                    }
                } else if filtered.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .searchable(text: $query, prompt: "NSP & XCI suchen")
            .navigationTitle("NSPiOS")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    ImportMenu()
                }
            }
            .refreshable { store.refresh() }
            .jobInset()
        }
    }
}

struct ImportMenu: View {
    @EnvironmentObject private var store: FileStore

    var body: some View {
        Menu {
            Button { store.requestImport(.copy) } label: {
                Label("Importieren", systemImage: "square.and.arrow.down")
            }
            Button { store.requestImport(.copyAndExtract) } label: {
                Label("Importieren & entpacken", systemImage: "archivebox")
            }
            Button { store.requestImport(.extractDirect) } label: {
                Label("Direkt entpacken (ohne Kopie)", systemImage: "bolt.fill")
            }
        } label: {
            Image(systemName: "plus")
        }
    }
}

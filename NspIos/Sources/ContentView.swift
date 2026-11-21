import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: FileStore
    @AppStorage(FileStore.deleteAfterExtractKey) private var deleteAfterExtract = false

    private enum ImportMode {
        case copy, copyAndExtract, extractDirect
    }

    @State private var importMode: ImportMode = .copy
    @State private var showImporter = false
    @State private var showDeleteDialog = false
    @State private var contentsItem: FileItem?

    var body: some View {
        NavigationStack {
            List {
                packagesSection
                extractedSection
                storageSection
                settingsSection
            }
            .navigationTitle("NspIos")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { startImport(.copy) } label: {
                            Label("NSP importieren", systemImage: "square.and.arrow.down")
                        }
                        Button { startImport(.copyAndExtract) } label: {
                            Label("Importieren & entpacken", systemImage: "archivebox")
                        }
                        Button { startImport(.extractDirect) } label: {
                            Label("Direkt entpacken (ohne Kopie)", systemImage: "bolt")
                        }
                    } label: {
                        Image(systemName: "plus.circle.fill")
                    }
                }
                ToolbarItem(placement: .secondaryAction) {
                    Button {
                        store.export(store.extracted.map(\.url), asCopy: false)
                    } label: {
                        Label("Alle Ordner in Dateien verschieben", systemImage: "folder.badge.plus")
                    }
                    .disabled(store.extracted.isEmpty)
                }
            }
            .refreshable { store.refresh() }
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: FileTypes.importable,
            allowsMultipleSelection: true
        ) { result in
            handleImport(result)
        }
        .sheet(item: $store.exportRequest) { request in
            ExportPicker(urls: request.urls, asCopy: request.asCopy) { success in
                store.exportFinished(request, success: success)
            }
            .ignoresSafeArea()
        }
        .sheet(item: $contentsItem) { item in
            PackageContentsView(url: item.url)
        }
        .alert(item: $store.alert) { message in
            Alert(title: Text(message.title), message: Text(message.message), dismissButton: .default(Text("OK")))
        }
        .confirmationDialog("Was soll gelöscht werden?", isPresented: $showDeleteDialog, titleVisibility: .visible) {
            Button("Alle NSP-Dateien", role: .destructive) { store.deleteAll(.packages) }
            Button("Alle entpackten Ordner", role: .destructive) { store.deleteAll(.extracted) }
            Button("Alles komplett löschen", role: .destructive) { store.deleteAll(.everything) }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Gelöschte Dateien können nicht wiederhergestellt werden.")
        }
        .overlay {
            if let job = store.job {
                ExtractionOverlay(job: job, queued: store.queuedCount) { store.cancelExtraction() }
            } else if let busy = store.busy {
                BusyOverlay(text: busy)
            }
        }
        .overlay(alignment: .bottom) {
            ToastView(text: $store.toast)
        }
    }

    // MARK: - Abschnitte

    private var packagesSection: some View {
        Section {
            if store.packages.isEmpty {
                EmptyRow(text: "Noch keine NSP-Dateien. Tippe auf + zum Importieren.")
            }
            ForEach(store.packages) { item in
                HStack {
                    FileRow(item: item)
                    Spacer(minLength: 8)
                    Button("Entpacken") { store.enqueueExtraction(of: item.url) }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                        .disabled(store.job != nil)
                }
                .itemActions(item) { contentsItem = $0 }
            }
        } header: {
            Text("NSP-Dateien")
        } footer: {
            if !store.packages.isEmpty {
                Text("Gedrückt halten für Inhalt anzeigen, Verschieben, Kopieren und Löschen.")
            }
        }
    }

    private var extractedSection: some View {
        Section("Entpackt") {
            if store.extracted.isEmpty {
                EmptyRow(text: "Noch nichts entpackt.")
            }
            ForEach(store.extracted) { item in
                NavigationLink {
                    FolderView(url: item.url)
                } label: {
                    FileRow(item: item)
                }
                .itemActions(item) { contentsItem = $0 }
            }
        }
    }

    private var storageSection: some View {
        Section {
            LabeledContent("Belegt durch NspIos", value: formatBytes(store.usedBytes))
            LabeledContent("Frei auf dem Gerät", value: formatBytes(store.freeBytes))
            Button {
                store.cleanUp()
            } label: {
                Label("Bereinigen (Temp, Cache, Reste)", systemImage: "sparkles")
            }
            Button(role: .destructive) {
                showDeleteDialog = true
            } label: {
                Label("Löschen …", systemImage: "trash")
            }
        } header: {
            Text("Speicher")
        } footer: {
            Text("Alle Dateien liegen auch in der Dateien-App unter „Auf meinem iPhone › NspIos“.")
        }
    }

    private var settingsSection: some View {
        Section("Einstellungen") {
            Toggle("NSP nach dem Entpacken löschen", isOn: $deleteAfterExtract)
        }
    }

    // MARK: - Import

    private func startImport(_ mode: ImportMode) {
        importMode = mode
        showImporter = true
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            switch importMode {
            case .copy:
                store.importFiles(urls, extractAfterwards: false)
            case .copyAndExtract:
                store.importFiles(urls, extractAfterwards: true)
            case .extractDirect:
                for url in urls { store.enqueueExtraction(of: url, securityScoped: true) }
            }
        case .failure(let error):
            store.alert = AlertMessage(title: "Import fehlgeschlagen", message: error.localizedDescription)
        }
    }
}

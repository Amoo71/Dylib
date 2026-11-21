import SwiftUI

@main
struct NSPiOSApp: App {
    @StateObject private var store = FileStore()
    @StateObject private var updater = UpdateManager()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(updater)
                .onOpenURL { store.handleOpenedURL($0) }
        }
    }
}

private enum AppTab: Hashable {
    case library, extracted, storage
}

struct RootView: View {
    @EnvironmentObject private var store: FileStore
    @EnvironmentObject private var updater: UpdateManager
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab: AppTab = .library

    var body: some View {
        TabView(selection: $tab) {
            Tab("Pakete", systemImage: "shippingbox.fill", value: AppTab.library) {
                LibraryView()
            }
            .badge(store.packages.count)

            Tab("Entpackt", systemImage: "folder.fill", value: AppTab.extracted) {
                ExtractedView()
            }

            Tab("Einstellungen", systemImage: "gearshape.fill", value: AppTab.storage) {
                StorageView()
            }
            .badge(updater.phase == .available ? 1 : 0)
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .tint(Theme.accent)
        .preferredColorScheme(.dark)
        .fileImporter(
            isPresented: $store.showImporter,
            allowedContentTypes: FileTypes.importable,
            allowsMultipleSelection: true
        ) { result in
            store.handleImporterResult(result)
        }
        .sheet(item: $store.exportRequest) { request in
            ExportPicker(urls: request.urls, asCopy: request.asCopy) { success in
                store.exportFinished(request, success: success)
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $updater.showSheet) {
            UpdateSheet()
        }
        .sheet(item: $store.inspected) { item in
            PackageContentsView(url: item.url)
                .presentationDetents([.medium, .large])
        }
        .alert(
            store.alert?.title ?? "",
            isPresented: Binding(
                get: { store.alert != nil },
                set: { if !$0 { store.alert = nil } }
            ),
            presenting: store.alert
        ) { _ in
            Button("OK", role: .cancel) {}
        } message: { message in
            Text(message.message)
        }
        .overlay(alignment: .top) {
            StatusPill()
        }
        .sensoryFeedback(.success, trigger: store.completedCount)
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active { updater.checkIfDue() }
        }
    }
}

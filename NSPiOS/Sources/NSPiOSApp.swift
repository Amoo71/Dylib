import SwiftUI

@main
struct NSPiOSApp: App {
    @StateObject private var store = FileStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .onOpenURL { store.handleOpenedURL($0) }
        }
    }
}

private enum AppTab: Hashable {
    case library, extracted, storage
}

struct RootView: View {
    @EnvironmentObject private var store: FileStore
    @State private var tab: AppTab = .library

    var body: some View {
        TabView(selection: $tab) {
            Tab("NSP", systemImage: "shippingbox.fill", value: AppTab.library) {
                LibraryView()
            }
            .badge(store.packages.count)

            Tab("Entpackt", systemImage: "folder.fill", value: AppTab.extracted) {
                ExtractedView()
            }

            Tab("Speicher", systemImage: "internaldrive.fill", value: AppTab.storage) {
                StorageView()
            }
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
    }
}

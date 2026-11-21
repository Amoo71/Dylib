import SwiftUI

@main
struct NspIosApp: App {
    @StateObject private var store = FileStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .onOpenURL { store.handleOpenedURL($0) }
        }
    }
}

import SwiftUI

@main
struct LiquidBrowserApp: App {
    var body: some Scene {
        WindowGroup {
            BrowserContainerView()
                .preferredColorScheme(.dark)
                .ignoresSafeArea(.container, edges: .bottom)
        }
    }
}

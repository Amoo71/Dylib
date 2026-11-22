import SwiftUI
import Foundation

@main
struct NSPiOSApp: App {
    @StateObject private var store = FileStore()
    @StateObject private var updater = UpdateManager()

    var body: some Scene {
        WindowGroup {
            RootView()
                .task { await EngineSelfTest.runFromLaunchArgumentIfRequested() }
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

    // Theme/Sprache: Änderungen bauen die Oberfläche einmal neu auf
    @AppStorage(Prefs.theme) private var themeRaw = AppTheme.dark.rawValue
    @AppStorage(Prefs.language) private var language = AppLanguage.en.rawValue
    @AppStorage(Prefs.customColor) private var customHex = Theme.defaultCustomHex

    var body: some View {
        TabView(selection: $tab) {
            Tab(L("Packages", "Pakete"), systemImage: "shippingbox.fill", value: AppTab.library) {
                LibraryView()
            }
            .badge(store.packages.count)

            Tab(L("Extracted", "Entpackt"), systemImage: "folder.fill", value: AppTab.extracted) {
                ExtractedView()
            }

            Tab(L("Settings", "Einstellungen"), systemImage: "gearshape.fill", value: AppTab.storage) {
                SettingsView()
            }
            .badge(updater.phase == .available ? 1 : 0)
        }
        .id("\(themeRaw)-\(language)-\(customHex)")
        .tabBarMinimizeBehavior(.onScrollDown)
        .tint(Theme.accent)
        .preferredColorScheme(Theme.colorScheme)
        .animation(.smooth, value: themeRaw)
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
            if phase == .active {
                store.refresh()          // Änderungen aus der Dateien-App übernehmen
                updater.checkIfDue()
            }
        }
    }
}

/// `-nspios-selftest`: Engine-Selbsttest ausgeben und beenden (für CI im Simulator).
enum EngineSelfTest {
    static func runFromLaunchArgumentIfRequested() async {
        if ProcessInfo.processInfo.arguments.contains("-nspios-packtest") {
            let output = await GameBuilder.smokeTest()
            FileHandle.standardOutput.write(Data((output + "\n").utf8))
            fflush(stdout)
            exit(0)
        }
        guard ProcessInfo.processInfo.arguments.contains("-nspios-selftest") else { return }
        let line: String
        FileHandle.standardOutput.write(Data("[selftest] start (native: \(PythonEngine.nativeLibraryPath ?? "missing"))\n".utf8))
        do {
            let result = try await PythonEngine.shared.selfTest()
            let json = (try? JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]))
                .map { String(decoding: $0, as: UTF8.self) } ?? "{}"
            line = "NSPIOS_SELFTEST_OK \(json)"
        } catch {
            line = "NSPIOS_SELFTEST_FAIL \(error.localizedDescription)"
        }
        FileHandle.standardOutput.write(Data((line + "\n").utf8))
        fflush(stdout)
        exit(0)
    }
}

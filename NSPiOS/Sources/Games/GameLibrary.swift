import SwiftUI
import UIKit

/// LiveContainer-Umgebung: NSPiOS liegt in `<LC>/Applications/NSPiOS.app`, Spiele werden daneben installiert.
enum LiveContainer {
    static var applicationsDir: URL? {
        let parent = Bundle.main.bundleURL.deletingLastPathComponent()
        guard parent.lastPathComponent == "Applications",
              FileManager.default.isWritableFile(atPath: parent.path) else { return nil }
        return parent
    }

    static var isAvailable: Bool { applicationsDir != nil }

    /// `<LC>/Data/Application` – Datencontainer der Apps in LiveContainer.
    static var dataRoot: URL? {
        applicationsDir?.deletingLastPathComponent().appendingPathComponent("Data/Application", isDirectory: true)
    }

    static func launchURL(bundleName: String) -> URL? {
        var components = URLComponents()
        components.scheme = "livecontainer"
        components.host = "livecontainer-launch"
        components.queryItems = [URLQueryItem(name: "bundle-name", value: bundleName)]
        return components.url
    }
}

enum GameState: String, Codable {
    case building, needsCapture, buildingFinal, ready, failed
}

struct GameEntry: Codable, Identifiable, Hashable {
    var titleID: String
    var name: String
    var exefsPath: String
    var romfsPath: String
    var dataUUID: String
    var state: GameState
    var packerTag: String
    var expectedShaders: Int
    var settings: [String: String] = [:]
    var lastError: String?
    var builtAt: Date?

    var id: String { titleID }
    var bundleName: String { "NSPiOS-\(titleID).app" }
    var program: GameProgram {
        GameProgram(titleID: titleID, exefs: URL(fileURLWithPath: exefsPath), romfs: URL(fileURLWithPath: romfsPath))
    }
}

/// Spielbibliothek: baut, installiert (LiveContainer), nimmt Shader auf, baut fertig, startet.
@MainActor
final class GameLibrary: ObservableObject {
    static let shared = GameLibrary()

    @Published private(set) var games: [GameEntry] = []
    @Published private(set) var captured: [String: Int] = [:]
    @Published var message: String?
    @Published var errorMessage: String?

    private var autoFinishing = Set<String>()

    init() { load() }

    // MARK: - Speicher

    private static func entryURL(_ tid: String) -> URL {
        GameBuilder.gamesRoot.appendingPathComponent(tid).appendingPathComponent("game.json")
    }

    func load() {
        let fm = FileManager.default
        let dirs = (try? fm.contentsOfDirectory(at: GameBuilder.gamesRoot, includingPropertiesForKeys: nil)) ?? []
        let decoder = JSONDecoder()
        games = dirs.compactMap { dir in
            guard let data = try? Data(contentsOf: dir.appendingPathComponent("game.json")) else { return nil }
            return try? decoder.decode(GameEntry.self, from: data)
        }
        .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private func save(_ entry: GameEntry) {
        let url = Self.entryURL(entry.titleID)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(entry) { try? data.write(to: url, options: .atomic) }
        if let i = games.firstIndex(where: { $0.titleID == entry.titleID }) {
            games[i] = entry
        } else {
            games.append(entry)
            games.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        }
    }

    func game(_ tid: String) -> GameEntry? { games.first { $0.titleID == tid } }

    // MARK: - Pfade im Spiel-Container

    func installedApp(_ entry: GameEntry) -> URL? {
        guard let apps = LiveContainer.applicationsDir else { return nil }
        let url = apps.appendingPathComponent(entry.bundleName, isDirectory: true)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Documents-Ordner des Spiels in LiveContainer (UUID aus der installierten Info.plist, sonst die vorab vergebene).
    func documents(_ entry: GameEntry) -> URL? {
        guard let root = LiveContainer.dataRoot else { return nil }
        var uuid = entry.dataUUID
        if let app = installedApp(entry),
           let plist = Self.readPlist(app.appendingPathComponent("Info.plist")),
           let assigned = plist["LCDataUUID"] as? String, !assigned.isEmpty {
            uuid = assigned
        }
        return root.appendingPathComponent(uuid, isDirectory: true).appendingPathComponent("Documents", isDirectory: true)
    }

    private static func readPlist(_ url: URL) -> [String: Any]? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
    }

    private static func writePlist(_ dict: [String: Any], to url: URL) throws {
        let data = try PropertyListSerialization.data(fromPropertyList: dict, format: .binary, options: 0)
        try data.write(to: url, options: .atomic)
    }

    /// Anzahl der Shader-Module, die die Aufnahme liefern muss (vk_capture.tsv des Profils).
    nonisolated static func expectedShaderCount(_ tid: String) -> Int {
        let tsv = GameBuilder.packerPath.appendingPathComponent("dm/games/\(tid)/vk_capture.tsv")
        let bundled = GameBuilder.bundledPacker?.appendingPathComponent("dm/games/\(tid)/vk_capture.tsv")
        for url in [tsv, bundled].compactMap({ $0 }) {
            if let text = try? String(contentsOf: url, encoding: .utf8) {
                return text.split(whereSeparator: \.isNewline).filter { !$0.hasPrefix("#") && !$0.isEmpty }.count
            }
        }
        return 0
    }

    // MARK: - Hinzufügen / Bauen

    /// Neues Spiel: Aufnahme-App (ohne Shader) bauen und installieren.
    func add(_ program: GameProgram) async {
        var entry = game(program.titleID) ?? GameEntry(
            titleID: program.titleID, name: program.name,
            exefsPath: program.exefs.path, romfsPath: program.romfs.path,
            dataUUID: UUID().uuidString, state: .building, packerTag: GameBuilder.shellTag,
            expectedShaders: Self.expectedShaderCount(program.titleID))
        entry.exefsPath = program.exefs.path
        entry.romfsPath = program.romfs.path
        entry.state = .building
        entry.lastError = nil
        save(entry)
        await buildAndInstall(entry, final: false)
    }

    /// Baut und installiert. `final == false`: Aufnahme-Lauf (Spiel nimmt beim Start seine Shader auf).
    func buildAndInstall(_ start: GameEntry, final: Bool) async {
        var entry = start
        entry.state = final ? .buildingFinal : .building
        entry.lastError = nil
        save(entry)
        do {
            let capture = final ? try keepCapture(entry) : nil
            let app = try await GameBuilder.shared.build(entry.program, noShaders: !final, vkCapture: capture)
            if entry.expectedShaders == 0 { entry.expectedShaders = Self.expectedShaderCount(entry.titleID) }
            try install(app, entry: entry)
            entry.state = final ? .ready : .needsCapture
            entry.packerTag = GameBuilder.shellTag
            entry.builtAt = Date()
            save(entry)
            try writeEnvironment(entry)
            message = final
                ? L("\(entry.name) is ready", "\(entry.name) ist bereit")
                : L("Start \(entry.name) once to capture its shaders", "\(entry.name) einmal starten, um die Shader aufzunehmen")
        } catch {
            entry.state = final ? .needsCapture : .failed
            entry.lastError = error.localizedDescription
            save(entry)
            errorMessage = error.localizedDescription
        }
    }

    /// Sichert die Aufnahme in NSPiOS (für schnelle Neubauten ohne erneutes Aufnehmen).
    private func keepCapture(_ entry: GameEntry) throws -> URL {
        let keep = GameBuilder.gamesRoot.appendingPathComponent(entry.titleID).appendingPathComponent("vkcap", isDirectory: true)
        let fm = FileManager.default
        if let docs = documents(entry) {
            let live = docs.appendingPathComponent("vkcap", isDirectory: true)
            if let files = try? fm.contentsOfDirectory(at: live, includingPropertiesForKeys: nil) {
                try fm.createDirectory(at: keep, withIntermediateDirectories: true)
                for file in files where file.pathExtension == "spv" {
                    let target = keep.appendingPathComponent(file.lastPathComponent)
                    if !fm.fileExists(atPath: target.path) { try fm.copyItem(at: file, to: target) }
                }
            }
        }
        let count = (try? fm.contentsOfDirectory(atPath: keep.path))?.filter { $0.hasSuffix(".spv") }.count ?? 0
        guard count > 0 else {
            throw GameBuilderError.pack(L("No captured shaders yet – start the game once.", "Noch keine Shader aufgenommen – starte das Spiel einmal."))
        }
        return keep
    }

    /// Verschiebt die gebaute App nach LiveContainer und behält Datencontainer & LiveContainer-Einträge.
    private func install(_ app: URL, entry: GameEntry) throws {
        guard let apps = LiveContainer.applicationsDir else {
            throw GameBuilderError.pack(L("Installing needs LiveContainer.", "Zum Installieren wird LiveContainer benötigt."))
        }
        let fm = FileManager.default
        let target = apps.appendingPathComponent(entry.bundleName, isDirectory: true)
        let plistURL = app.appendingPathComponent("Info.plist")
        var plist = Self.readPlist(plistURL) ?? [:]

        // Einträge von LiveContainer aus der alten Installation übernehmen (Datencontainer = Spielstände & Aufnahme)
        if let old = Self.readPlist(target.appendingPathComponent("Info.plist")) {
            for (key, value) in old where key.hasPrefix("LC") && key != "LCPatchRevision" && !key.contains("Sign") {
                plist[key] = value
            }
        }
        if (plist["LCDataUUID"] as? String)?.isEmpty ?? true {
            plist["LCDataUUID"] = entry.dataUUID
        }
        plist["CFBundleDisplayName"] = entry.name
        Self.writeIcon(for: entry, into: app, plist: &plist)
        try Self.writePlist(plist, to: plistURL)

        if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
        try fm.moveItem(at: app, to: target)
    }

    /// App-Icon aus den Control-Daten (icon_*.dat = JPEG) – der Packer kann es auf iOS nicht selbst schreiben.
    private static func writeIcon(for entry: GameEntry, into app: URL, plist: inout [String: Any]) {
        let control = URL(fileURLWithPath: entry.exefsPath).deletingLastPathComponent().appendingPathComponent("control")
        let files = (try? FileManager.default.contentsOfDirectory(at: control, includingPropertiesForKeys: nil)) ?? []
        let icons = files.filter { $0.lastPathComponent.lowercased().contains("icon_") }
            .sorted { $0.lastPathComponent.contains("AmericanEnglish") && !$1.lastPathComponent.contains("AmericanEnglish") }
        guard let source = icons.first, let image = UIImage(contentsOfFile: source.path) else { return }
        for (suffix, px) in [("@2x", 120.0), ("@3x", 180.0)] {
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            let png = UIGraphicsImageRenderer(size: CGSize(width: px, height: px), format: format).pngData { _ in
                image.draw(in: CGRect(x: 0, y: 0, width: px, height: px))
            }
            try? png.write(to: app.appendingPathComponent("AppIcon60x60\(suffix).png"))
        }
        plist["CFBundleIcons"] = ["CFBundlePrimaryIcon": ["CFBundleIconFiles": ["AppIcon60x60"]]]
        try? FileManager.default.createDirectory(at: GameBuilder.gamesRoot.appendingPathComponent(entry.titleID), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: iconURL(entry.titleID))
        try? FileManager.default.copyItem(at: source, to: iconURL(entry.titleID))
    }

    nonisolated static func iconURL(_ tid: String) -> URL {
        GameBuilder.gamesRoot.appendingPathComponent(tid).appendingPathComponent("icon.jpg")
    }

    // MARK: - Einstellungen (Documents/env.txt des Spiels)

    func updateSettings(_ entry: GameEntry, _ settings: [String: String]) {
        var entry = entry
        entry.settings = settings.filter { !$0.value.isEmpty }
        save(entry)
        do { try writeEnvironment(entry) } catch { errorMessage = error.localizedDescription }
    }

    private func writeEnvironment(_ entry: GameEntry) throws {
        guard let docs = documents(entry) else { return }
        let fm = FileManager.default
        try fm.createDirectory(at: docs, withIntermediateDirectories: true)
        var lines = entry.settings.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }
        if entry.state == .needsCapture {
            try fm.createDirectory(at: docs.appendingPathComponent("vkcap"), withIntermediateDirectories: true)
            lines.append("DM_VK_CAPTURE=vkcap")
        }
        let text = "# NSPiOS – per-game settings (edit them in NSPiOS)\n" + lines.joined(separator: "\n") + "\n"
        try text.write(to: docs.appendingPathComponent("env.txt"), atomically: true, encoding: .utf8)
    }

    // MARK: - Aufnahme prüfen (beim Zurückkehren nach NSPiOS)

    func refresh() async {
        load()
        for entry in games where entry.state == .needsCapture {
            let count = capturedCount(entry)
            captured[entry.titleID] = count
            // Aufnahme vollständig → automatisch fertig bauen
            if entry.expectedShaders > 0, count >= entry.expectedShaders, !autoFinishing.contains(entry.titleID),
               !GameBuilder.shared.running {
                autoFinishing.insert(entry.titleID)
                await buildAndInstall(entry, final: true)
                autoFinishing.remove(entry.titleID)
            }
        }
    }

    func capturedCount(_ entry: GameEntry) -> Int {
        guard let docs = documents(entry) else { return 0 }
        let live = (try? FileManager.default.contentsOfDirectory(atPath: docs.appendingPathComponent("vkcap").path)) ?? []
        let kept = (try? FileManager.default.contentsOfDirectory(
            atPath: GameBuilder.gamesRoot.appendingPathComponent(entry.titleID).appendingPathComponent("vkcap").path)) ?? []
        return Set((live + kept).filter { $0.hasSuffix(".spv") }).count
    }

    // MARK: - Starten / Löschen

    func launch(_ entry: GameEntry) {
        guard installedApp(entry) != nil else {
            errorMessage = L("The game is not installed in LiveContainer.", "Das Spiel ist nicht in LiveContainer installiert.")
            return
        }
        try? writeEnvironment(entry)
        guard let url = LiveContainer.launchURL(bundleName: entry.bundleName) else { return }
        UIApplication.shared.open(url) { [weak self] ok in
            guard !ok else { return }
            Task { @MainActor in
                self?.errorMessage = L("Open LiveContainer and start „\(entry.name)“ there.",
                                       "Öffne LiveContainer und starte dort „\(entry.name)“.")
            }
        }
    }

    func delete(_ entry: GameEntry, keepSaves: Bool) {
        let fm = FileManager.default
        if !keepSaves, let docs = documents(entry) {
            try? fm.removeItem(at: docs.deletingLastPathComponent())
        }
        if let app = installedApp(entry) { try? fm.removeItem(at: app) }
        try? fm.removeItem(at: GameBuilder.gamesRoot.appendingPathComponent(entry.titleID))
        games.removeAll { $0.titleID == entry.titleID }
        captured[entry.titleID] = nil
        message = L("\(entry.name) removed", "\(entry.name) entfernt")
    }

    /// Neu bauen: mit vorhandener Aufnahme direkt fertig, sonst Aufnahme-Lauf.
    func rebuild(_ entry: GameEntry, recapture: Bool) async {
        var entry = entry
        if recapture {
            try? FileManager.default.removeItem(at: GameBuilder.gamesRoot.appendingPathComponent(entry.titleID).appendingPathComponent("vkcap"))
            if let docs = documents(entry) { try? FileManager.default.removeItem(at: docs.appendingPathComponent("vkcap")) }
            entry.state = .needsCapture
            await buildAndInstall(entry, final: false)
        } else {
            await buildAndInstall(entry, final: capturedCount(entry) > 0)
        }
    }

    /// Update/andere Version: neue entpackte Dateien (z. B. mit eingespieltem Update) für dasselbe Spiel.
    func update(_ entry: GameEntry, with program: GameProgram) async {
        var entry = entry
        entry.exefsPath = program.exefs.path
        entry.romfsPath = program.romfs.path
        save(entry)
        await buildAndInstall(entry, final: capturedCount(entry) > 0)
    }

    var needsRebuildForShell: [String] {
        games.filter { $0.packerTag != GameBuilder.shellTag }.map(\.titleID)
    }

    /// Alle Programme in den entpackten Ordnern (für „Spiel hinzufügen“).
    nonisolated static func availablePrograms(in extractedDir: URL) -> [GameProgram] {
        let folders = (try? FileManager.default.contentsOfDirectory(at: extractedDir, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
        var seen = Set<String>()
        return folders.flatMap { GameBuilder.findPrograms(in: $0) }.filter { seen.insert($0.titleID).inserted }
    }
}

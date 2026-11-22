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
    /// Anzahl der Shader, mit denen die installierte Version gebaut wurde.
    var builtShaders: Int?
    /// Titelversion der Spieldateien (nil/0 = Basisspiel).
    var version: UInt32?

    var id: String { titleID }
    var bundleName: String { "NSPiOS-\(titleID).app" }
    var program: GameProgram {
        GameProgram(titleID: titleID, exefs: URL(fileURLWithPath: exefsPath), romfs: URL(fileURLWithPath: romfsPath),
                    version: version ?? 0)
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

    nonisolated static let cleanAfterBuildKey = "gamesCleanAfterBuild"
    nonisolated static let deleteSourceNCAKey = "gamesDeleteSourceNCAs"

    init() {
        UserDefaults.standard.register(defaults: [Self.cleanAfterBuildKey: true])
        load()
    }

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
           let info = Self.readPlist(app.appendingPathComponent("LCAppInfo.plist")),
           let assigned = info["LCDataUUID"] as? String, !assigned.isEmpty {
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
        entry.version = program.version
        entry.state = .building
        entry.lastError = nil
        save(entry)
        // Spiele, die ihre Shader erst beim Laufen erzeugen (Galaxy): erst Aufnahme-Lauf. Alle anderen direkt fertig.
        await buildAndInstall(entry, final: !program.needsCapture)
    }

    /// Baut und installiert. `final == false`: Aufnahme-Lauf (Spiel nimmt beim Start seine Shader auf).
    func buildAndInstall(_ start: GameEntry, final: Bool) async {
        var entry = start
        entry.state = final ? .buildingFinal : .building
        entry.lastError = nil
        save(entry)
        do {
            let capture = final && entry.program.needsCapture ? try keepCapture(entry) : nil
            let app = try await GameBuilder.shared.build(entry.program, noShaders: !final, vkCapture: capture)
            if entry.expectedShaders == 0 { entry.expectedShaders = Self.expectedShaderCount(entry.titleID) }
            try install(app, entry: entry)
            entry.state = final ? .ready : .needsCapture
            entry.builtShaders = final ? capturedCount(entry) : nil
            entry.packerTag = GameBuilder.shellTag
            entry.builtAt = Date()
            save(entry)
            try writeEnvironment(entry)
            message = final
                ? L("\(entry.name) is ready", "\(entry.name) ist bereit")
                : L("Start \(entry.name) once to capture its shaders", "\(entry.name) einmal starten, um die Shader aufzunehmen")
            if UserDefaults.standard.bool(forKey: Self.cleanAfterBuildKey) {
                await cleanUp(entry, deleteNCAs: final && UserDefaults.standard.bool(forKey: Self.deleteSourceNCAKey))
            }
        } catch {
            entry.state = final && entry.program.needsCapture ? .needsCapture : .failed
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
        plist["CFBundleDisplayName"] = entry.name
        Self.writeIcon(for: entry, into: app, plist: &plist)
        try Self.writePlist(plist, to: plistURL)

        let previous = Self.readPlist(target.appendingPathComponent("LCAppInfo.plist"))
        try writeLiveContainerInfo(in: app, previous: previous, entry: entry,
                                   bundleID: plist["CFBundleIdentifier"] as? String)

        if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
        try fm.moveItem(at: app, to: target)
    }

    /// LCAppInfo.plist (Datencontainer) wie LiveContainer selbst – ohne Vorbereitungs-/Signatur-Marker, damit
    /// LiveContainer die neue Binary beim nächsten Start aus seiner Liste vorbereitet und signiert.
    private func writeLiveContainerInfo(in app: URL, previous: [String: Any]?, entry: GameEntry, bundleID: String?,
                                        keepPreparation: Bool = false) throws {
        let fm = FileManager.default
        var lcInfo = previous ?? [:]
        if !keepPreparation {
            for key in Array(lcInfo.keys) where Self.isPreparationKey(key) { lcInfo.removeValue(forKey: key) }
        }
        let dataUUID = (lcInfo["LCDataUUID"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? entry.dataUUID
        lcInfo["LCDataUUID"] = dataUUID
        var containers = lcInfo["LCContainers"] as? [[String: Any]] ?? []
        if !containers.contains(where: { ($0["folderName"] as? String) == dataUUID }) {
            containers.insert(["folderName": dataUUID, "name": entry.name], at: 0)
        }
        lcInfo["LCContainers"] = containers
        try Self.writePlist(lcInfo, to: app.appendingPathComponent("LCAppInfo.plist"))

        // Datencontainer mit LCContainerInfo.plist, wie LiveContainer ihn anlegt
        if let dataRoot = LiveContainer.dataRoot {
            let container = dataRoot.appendingPathComponent(dataUUID, isDirectory: true)
            try fm.createDirectory(at: container.appendingPathComponent("Documents"), withIntermediateDirectories: true)
            let infoURL = container.appendingPathComponent("LCContainerInfo.plist")
            if !fm.fileExists(atPath: infoURL.path) {
                try Self.writePlist([
                    "appIdentifier": bundleID ?? "org.nxnative.\(entry.titleID.lowercased())",
                    "name": entry.name,
                    "keychainGroupId": 0,
                    "isolateAppGroup": false,
                    "spoofIdentifierForVendor": false,
                ], to: infoURL)
            }
        }
    }

    /// Installationen ohne (vollständige) LCAppInfo.plist für LiveContainer nachrüsten: fehlt die Datei oder darin der
    /// Datencontainer (LiveContainer legt sie beim Anzeigen ggf. selbst leer an), werden Container-Einträge ergänzt.
    /// Vorhandene Einträge – auch LiveContainers Signatur-Marker – bleiben dabei erhalten.
    private func repairLiveContainerInfo(_ entry: GameEntry) {
        guard let app = installedApp(entry) else { return }
        let infoURL = app.appendingPathComponent("LCAppInfo.plist")
        var info = Self.readPlist(infoURL) ?? [:]
        let containers = info["LCContainers"] as? [[String: Any]] ?? []
        let uuid = (info["LCDataUUID"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        if let uuid, containers.contains(where: { ($0["folderName"] as? String) == uuid }) { return }

        let plist = Self.readPlist(app.appendingPathComponent("Info.plist")) ?? [:]
        let dataUUID = uuid ?? (plist["LCDataUUID"] as? String) ?? entry.dataUUID
        info["LCDataUUID"] = dataUUID
        var list = containers
        if !list.contains(where: { ($0["folderName"] as? String) == dataUUID }) {
            list.insert(["folderName": dataUUID, "name": entry.name], at: 0)
        }
        info["LCContainers"] = list
        try? Self.writePlist(info, to: infoURL)
        // Container-Ordner + LCContainerInfo.plist anlegen (ohne vorhandene Marker anzufassen)
        try? writeLiveContainerInfo(in: app, previous: info, entry: entry, bundleID: plist["CFBundleIdentifier"] as? String,
                                    keepPreparation: true)
        try? writeEnvironment(entry)
    }

    /// Marker, mit denen LiveContainer eine vorbereitete/signierte Binary erkennt.
    private static func isPreparationKey(_ key: String) -> Bool {
        key == "LCPatchRevision" || key == "LCJITLessSignID" || key == "LCExpirationDate" || key == "LCTeamId"
    }

    /// Hat LiveContainer das (aktuelle) Spiel schon vorbereitet und signiert? Erst dann klappt der Start aus NSPiOS.
    func isPreparedByLiveContainer(_ entry: GameEntry) -> Bool {
        guard let app = installedApp(entry),
              let info = Self.readPlist(app.appendingPathComponent("LCAppInfo.plist")) else { return false }
        return info["LCPatchRevision"] != nil
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

    // MARK: - Aufräumen

    /// Löscht, was nach dem Bauen nicht mehr gebraucht wird. Behalten wird, was einen Neubau schnell macht:
    /// umgeschriebener Spielcode (build/patched) und die Shader-Aufnahme.
    func cleanUp(_ entry: GameEntry, deleteNCAs: Bool) async {
        let tid = entry.titleID
        let source = URL(fileURLWithPath: entry.exefsPath).deletingLastPathComponent()
        let freed = await Task.detached(priority: .utility) {
            Self.cleanWork(tid) + (deleteNCAs ? Self.deleteNCAs(in: source) : 0) + Self.cleanTemp() + Self.cleanTools()
        }.value
        if freed > 1_000_000 {
            message = L("Cleaned up · \(formatBytes(freed)) freed", "Aufgeräumt · \(formatBytes(freed)) frei")
        }
    }

    /// Alle Spiele aufräumen (Einstellungen › Bereinigen).
    func cleanUpAll() async -> Int64 {
        guard !GameBuilder.shared.running else { return 0 }
        let ids = games.map(\.titleID)
        return await Task.detached(priority: .utility) {
            ids.reduce(Int64(0)) { $0 + Self.cleanWork($1) } + Self.cleanTemp() + Self.cleanTools()
        }.value
    }

    nonisolated private static func remove(_ url: URL) -> Int64 {
        guard FileManager.default.fileExists(atPath: url.path) else { return 0 }
        let size = FS.size(of: url)
        return (try? FileManager.default.removeItem(at: url)) != nil ? size : 0
    }

    nonisolated static func cleanWork(_ tid: String) -> Int64 {
        let fm = FileManager.default
        let game = GameBuilder.gamesRoot.appendingPathComponent(tid)
        let pack = game.appendingPathComponent("dump/\(tid)_pack")
        var freed: Int64 = 0
        // App-Bäume (die fertige App liegt schon in LiveContainer), übersetzte Shader (stecken in der App), Zwischenstände
        for name in ["build/vkroot", "build/ios", "build/gl", "shader", "gl_prebuilt", "ue_shaders", "extract"] {
            freed += remove(pack.appendingPathComponent(name))
        }
        for child in (try? fm.contentsOfDirectory(at: pack, includingPropertiesForKeys: nil)) ?? []
        where child.lastPathComponent.hasPrefix("stage") {
            freed += remove(child)
        }
        // Logs: die letzten 5 behalten
        let logs = game.appendingPathComponent("logs")
        let files = ((try? fm.contentsOfDirectory(at: logs, includingPropertiesForKeys: nil)) ?? [])
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
        for old in files.dropFirst(5) { freed += remove(old) }
        return freed
    }

    /// Spiel-Shell, Packer-Arbeitskopie und geladene Profile – NSPiOS holt bzw. erstellt sie beim nächsten Bauen neu.
    nonisolated static func cleanTools() -> Int64 {
        remove(GameBuilder.shellPath.deletingLastPathComponent()) + remove(GameBuilder.packerPath)
    }

    /// Temporäre Dateien der Engine (Python-Aufrufe, Downloads, Probeläufe).
    nonisolated static func cleanTemp() -> Int64 {
        let tmp = FileManager.default.temporaryDirectory
        let items = (try? FileManager.default.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil)) ?? []
        return items.filter { $0.lastPathComponent.hasPrefix("nspios-") || $0.lastPathComponent.hasPrefix("NSPiOS-") || $0.pathExtension == "tmp" }
            .reduce(Int64(0)) { $0 + remove($1) }
    }

    /// Verschlüsselte NCA-Dateien im Quellordner – nach dem Bauen braucht sie niemand mehr (RomFS/ExeFS sind entpackt).
    nonisolated static func deleteNCAs(in folder: URL) -> Int64 {
        let fm = FileManager.default
        var freed: Int64 = 0
        let skip: Set<String> = ["romfs", "exefs", "control"]
        guard let enumerator = fm.enumerator(at: folder, includingPropertiesForKeys: [.isDirectoryKey]) else { return 0 }
        for case let file as URL in enumerator {
            let name = file.lastPathComponent.lowercased()
            if (try? file.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                if skip.contains(where: { name == $0 || name.hasPrefix($0 + "_") }) { enumerator.skipDescendants() }
                continue
            }
            if ["nca", "ncz", "tik", "cert"].contains(file.pathExtension.lowercased()) { freed += remove(file) }
        }
        return freed
    }

    // MARK: - Einstellungen (Documents/env.txt des Spiels)

    func updateSettings(_ entry: GameEntry, _ settings: [String: String]) {
        var entry = entry
        entry.settings = settings.filter { !$0.value.isEmpty }
        save(entry)
        do { try writeEnvironment(entry) } catch { errorMessage = error.localizedDescription }
    }

    /// Präfix für Einstellungen, die nur beim Spielen auf dem Fernseher gelten (TV-Modus).
    static let tvPrefix = "TV:"

    private func writeEnvironment(_ entry: GameEntry, tv: Bool = false) throws {
        guard let docs = documents(entry) else { return }
        let fm = FileManager.default
        try fm.createDirectory(at: docs, withIntermediateDirectories: true)
        var values = entry.settings.filter { !$0.key.hasPrefix(Self.tvPrefix) }
        if tv {
            // TV-Modus: eigene Werte des Spiels für den Fernseher gehen vor (Docked schaltet die Shell selbst)
            for (key, value) in entry.settings where key.hasPrefix(Self.tvPrefix) {
                values[String(key.dropFirst(Self.tvPrefix.count))] = value
            }
        }
        var lines = values.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }
        // Shader-Aufnahme immer an: in der Aufnahme-Version für den Fertigbau, in der fertigen Version für Shader,
        // die erst später im Spiel entstehen (Neubau übernimmt sie dann).
        if entry.program.needsCapture {
            try fm.createDirectory(at: docs.appendingPathComponent("vkcap"), withIntermediateDirectories: true)
            lines.append("DM_VK_CAPTURE=vkcap")
        }
        let text = "# NSPiOS – per-game settings (edit them in NSPiOS)\n" + lines.joined(separator: "\n") + "\n"
        try text.write(to: docs.appendingPathComponent("env.txt"), atomically: true, encoding: .utf8)
    }

    // MARK: - Aufnahme prüfen (beim Zurückkehren nach NSPiOS)

    func refresh() async {
        load()
        for entry in games { repairLiveContainerInfo(entry) }
        for entry in games where entry.state == .ready {
            captured[entry.titleID] = capturedCount(entry)
        }
        for entry in games where entry.state == .needsCapture {
            let count = capturedCount(entry)
            captured[entry.titleID] = count
            // Aufnahme vollständig → automatisch fertig bauen
            if entry.expectedShaders > 0, count >= Self.autoFinishThreshold(entry.expectedShaders),
               !autoFinishing.contains(entry.titleID),
               !GameBuilder.shared.running {
                autoFinishing.insert(entry.titleID)
                await buildAndInstall(entry, final: true)
                autoFinishing.remove(entry.titleID)
            }
        }
    }

    /// Ab hier wird automatisch fertig gebaut: 99 % – einzelne Shader entstehen erst später im Spiel und kommen
    /// mit einem späteren Neubau dazu.
    nonisolated static func autoFinishThreshold(_ expected: Int) -> Int {
        max(1, Int((Double(expected) * 0.99).rounded(.down)))
    }

    /// Neu aufgenommene Shader seit dem letzten Fertigbau (für „Shader aktualisieren“).
    func newShaders(_ entry: GameEntry) -> Int {
        guard entry.state == .ready, let built = entry.builtShaders else { return 0 }
        return max(0, capturedCount(entry) - built)
    }

    func capturedCount(_ entry: GameEntry) -> Int {
        guard let docs = documents(entry) else { return 0 }
        let live = (try? FileManager.default.contentsOfDirectory(atPath: docs.appendingPathComponent("vkcap").path)) ?? []
        let kept = (try? FileManager.default.contentsOfDirectory(
            atPath: GameBuilder.gamesRoot.appendingPathComponent(entry.titleID).appendingPathComponent("vkcap").path)) ?? []
        return Set((live + kept).filter { $0.hasSuffix(".spv") }).count
    }

    // MARK: - Starten / Löschen

    func launch(_ entry: GameEntry, tv: Bool = false) {
        guard installedApp(entry) != nil else {
            errorMessage = L("The game is not installed in LiveContainer.", "Das Spiel ist nicht in LiveContainer installiert.")
            return
        }
        try? writeEnvironment(entry, tv: tv)
        guard isPreparedByLiveContainer(entry) else {
            errorMessage = L("First start: open LiveContainer and tap „\(entry.name)“ in its app list. LiveContainer signs the game once – after that, Play works from NSPiOS.",
                             "Erster Start: Öffne LiveContainer und tippe dort in der App-Liste auf „\(entry.name)“. LiveContainer signiert das Spiel einmalig – danach startet es direkt aus NSPiOS.")
            return
        }
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
            await buildAndInstall(entry, final: !entry.program.needsCapture || capturedCount(entry) > 0)
        }
    }

    /// Update/andere Version: neue entpackte Dateien (z. B. mit eingespieltem Update) für dasselbe Spiel.
    func update(_ entry: GameEntry, with program: GameProgram) async {
        var entry = entry
        entry.exefsPath = program.exefs.path
        entry.romfsPath = program.romfs.path
        entry.version = program.version
        save(entry)
        await buildAndInstall(entry, final: !entry.program.needsCapture || capturedCount(entry) > 0)
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

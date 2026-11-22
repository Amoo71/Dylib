import SwiftUI
import UIKit

struct ExtractionJob {
    let title: String
    let cancel: CancelFlag
    let startedAt = Date()
    var currentFile: String = ""
    var done: UInt64 = 0
    var total: UInt64 = 0

    var fraction: Double { total == 0 ? 0 : Double(done) / Double(total) }

    /// Bytes pro Sekunde seit Start.
    var speed: Double {
        let elapsed = Date().timeIntervalSince(startedAt)
        return elapsed > 0.25 ? Double(done) / elapsed : 0
    }

    /// Geschätzte Restzeit in Sekunden.
    var remainingSeconds: Double? {
        let s = speed
        guard s > 0, total > done else { return nil }
        return Double(total - done) / s
    }
}

struct ExportRequest: Identifiable {
    let id = UUID()
    let urls: [URL]
    let asCopy: Bool
}

struct AlertMessage: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

enum DeleteScope: Sendable {
    case packages, extracted, everything
}

enum ImportMode {
    case copy, copyAndExtract, extractDirect, keys
}

@MainActor
final class FileStore: ObservableObject {
    nonisolated static let deleteAfterExtractKey = "deleteAfterExtract"
    nonisolated static let xciSecureOnlyKey = "xciSecureOnly"
    nonisolated static let decryptKey = "decryptRomFS"
    nonisolated static let keepNCAKey = "keepNCA"

    /// Anzahl geladener Schlüssel aus prod.keys (0 = keine).
    @Published private(set) var keyCount = 0

    @Published private(set) var packages: [FileItem] = []
    @Published private(set) var extracted: [FileItem] = []
    @Published private(set) var usedBytes: Int64 = 0
    @Published private(set) var freeBytes: Int64 = 0
    @Published private(set) var queuedCount = 0
    /// Wird bei jedem Refresh erhöht, damit Ordneransichten neu laden.
    @Published private(set) var revision = 0
    /// Zähler für Haptik-Feedback nach erfolgreichem Entpacken.
    @Published private(set) var completedCount = 0

    @Published var job: ExtractionJob?
    @Published var busy: String?
    @Published var toast: String?
    @Published var alert: AlertMessage?
    @Published var exportRequest: ExportRequest?
    @Published var inspected: FileItem?
    /// Basisspiel, zu dem gerade ein Update ausgewählt wird („Mit Update entpacken …“).
    @Published var mergeBase: FileItem?

    // Import-Dialog
    @Published var showImporter = false
    private(set) var importMode: ImportMode = .copy

    let documents: URL
    let packagesDir: URL
    let extractedDir: URL

    private struct PendingExtraction {
        let source: URL
        let securityScoped: Bool
        let deleteSource: Bool
        let options: PackageReader.ExtractOptions
        /// `true` = RomFS nachträglich aus NCA-Datei/Ordner erzeugen.
        var romfsOnly = false
        /// Update-Paket: Basisspiel + Update zu einem Ordner im Update-Stand zusammenführen.
        var update: URL? = nil
        /// Nach erfolgreichem Zusammenführen: Ordner des Ergebnisses (z. B. Spiel neu bauen).
        var then: (@MainActor @Sendable (URL) -> Void)? = nil
        /// Ohne Basispaket: unveränderte Daten aus dem RomFS-Ordner des installierten Spiels.
        var installedRomFS: URL? = nil
    }

    private var pending: [PendingExtraction] = []
    private var watchers: [DirectoryWatcher] = []
    private var refreshTask: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    init() {
        documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        packagesDir = documents.appendingPathComponent("NSP", isDirectory: true)
        extractedDir = documents.appendingPathComponent("Entpackt", isDirectory: true)
        keyCount = KeyStore.load()?.count ?? 0
        UserDefaults.standard.register(defaults: [Self.decryptKey: true])
        refresh()
    }

    // MARK: - Laden

    private func ensureDirectories() {
        var created = false
        for dir in [packagesDir, extractedDir] where !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            created = true
        }
        if created || watchers.isEmpty { startWatching() }
    }

    /// Ordner beobachten → Löschen/Verschieben (auch in der Dateien-App) wird sofort überall sichtbar.
    private func startWatching() {
        watchers = [documents, packagesDir, extractedDir].compactMap { url in
            DirectoryWatcher(url: url) { [weak self] in self?.scheduleRefresh() }
        }
    }

    /// Fasst schnelle Änderungsfolgen zu einem Refresh zusammen.
    func scheduleRefresh() {
        refreshTask?.cancel()
        refreshTask = Task {
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            refresh()
        }
    }

    func refresh() {
        ensureDirectories()
        let packagesDir = packagesDir, extractedDir = extractedDir, documents = documents
        Task.detached(priority: .utility) {
            let packages = FS.list(packagesDir).filter { !$0.isDirectory }
            let extracted = FS.list(extractedDir).filter { $0.url.pathExtension != "part" }
            var used = FS.size(of: documents) + FS.size(of: FileManager.default.temporaryDirectory)
            if let caches = FS.cachesDirectory { used += FS.size(of: caches) }
            let free = FS.freeSpace()
            await self.apply(packages: packages, extracted: extracted, used: used, free: free)
        }
    }

    private func apply(packages: [FileItem], extracted: [FileItem], used: Int64, free: Int64) {
        withAnimation(.smooth) {
            self.packages = packages
            self.extracted = extracted
        }
        self.usedBytes = used
        self.freeBytes = free
        self.revision += 1
        // Offene Ansichten auf gelöschte Dateien schließen
        if let inspected, !FileManager.default.fileExists(atPath: inspected.url.path) {
            self.inspected = nil
        }
    }

    // MARK: - Import

    func requestImport(_ mode: ImportMode) {
        importMode = mode
        showImporter = true
    }

    func handleImporterResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            switch importMode {
            case .copy:
                importFiles(urls, extractAfterwards: false)
            case .copyAndExtract:
                importFiles(urls, extractAfterwards: true)
            case .extractDirect:
                for url in urls { enqueueExtraction(of: url, securityScoped: true) }
            case .keys:
                if let url = urls.first { importKeys(url) }
            }
        case .failure(let error):
            alert = AlertMessage(title: L("Import failed", "Import fehlgeschlagen"), message: error.localizedDescription)
        }
    }

    /// Kopiert NSP/XCI-Dateien in den App-Ordner "NSP" (auf APFS als sofortiger Clone).
    func importFiles(_ urls: [URL], extractAfterwards: Bool) {
        guard !urls.isEmpty else { return }
        let dir = packagesDir
        busy = L("Importing …", "Importiere …")

        Task.detached(priority: .userInitiated) {
            var imported: [URL] = []
            var errors: [String] = []
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }

                let destination = FS.uniqueURL(in: dir, fileName: url.lastPathComponent, isDirectory: false)
                do {
                    try FS.coordinatedCopy(from: url, to: destination)
                    do {
                        _ = try PackageReader.entries(of: destination)
                    } catch {
                        try? FileManager.default.removeItem(at: destination)
                        throw error
                    }
                    imported.append(destination)
                } catch {
                    errors.append("\(url.lastPathComponent): \(error.localizedDescription)")
                }
            }
            let updates = Set(imported.filter { Self.isUpdatePackage($0) })
            await self.didImport(imported, errors: errors, extract: extractAfterwards, updates: updates)
        }
    }

    private func didImport(_ imported: [URL], errors: [String], extract: Bool, updates: Set<URL> = []) {
        busy = nil
        if !errors.isEmpty {
            alert = AlertMessage(title: L("Import failed", "Import fehlgeschlagen"), message: errors.joined(separator: "\n\n"))
        } else if !imported.isEmpty {
            toast = L("\(imported.count) imported", "\(imported.count) importiert")
        }
        if extract {
            // Updates allein zu entpacken bringt nichts (nur Änderungen): die spielt autoInstallUpdates ein
            for url in imported where !updates.contains(url) { enqueueExtraction(of: url) }
        }
        refresh()
        autoInstallUpdates(touching: imported)
    }

    /// Ist das Paket ein Update (CNMT-Typ Patch)? Liest nur die kleine CNMT-NCA.
    nonisolated static func isUpdatePackage(_ url: URL) -> Bool {
        guard let keys = KeyStore.load() else { return false }
        return PackageReader.contents(of: url, keys: keys).contains { $0.isUpdate }
    }

    // MARK: - Updates automatisch einspielen

    /// Nach einem Import: Gibt es für ein Spiel ein neueres Update und das Basisspiel unter Pakete, werden beide
    /// zusammengeführt und das Spiel neu gebaut (Spielstände bleiben). Noch nicht im Games-Tab → wird hinzugefügt.
    func autoInstallUpdates(touching urls: [URL]) {
        guard keyCount > 0, !urls.isEmpty else { return }
        let all = Array(Set(packages.map(\.url) + urls))
        Task.detached(priority: .userInitiated) {
            guard let keys = KeyStore.load() else { return }
            var metas: [URL: [ContentMeta.Info]] = [:]
            for url in all { metas[url] = PackageReader.contents(of: url, keys: keys) }
            await self.applyUpdates(touching: urls, metas: metas)
        }
    }

    private func applyUpdates(touching urls: [URL], metas: [URL: [ContentMeta.Info]]) {
        let touched = Set(urls.flatMap { metas[$0] ?? [] }.filter { $0.type == 0x80 || $0.isUpdate }.map(\.baseTitleID))
        for tid in touched {
            let updates = metas.flatMap { url, list in list.filter { $0.isUpdate && $0.baseTitleID == tid }.map { (url, $0) } }
                .sorted { $0.1.version > $1.1.version }
            guard let newest = updates.first else { continue }
            let (updateURL, update) = newest
            let tidHex = String(format: "%016llX", tid)
            let library = GameLibrary.shared
            if let game = library.game(tidHex), (game.version ?? 0) >= update.version { continue }
            let name = GameBuilder.profileIndex[tidHex]?.name ?? tidHex
            let then: @MainActor @Sendable (URL) -> Void = { folder in
                self.finishUpdate(tidHex: tidHex, folder: folder)
            }
            guard let baseURL = metas.first(where: { $0.value.contains { $0.type == 0x80 && $0.titleID == tid } })?.key else {
                // Kein Basispaket mehr: das installierte Spiel liefert die unveränderten Daten (geprüft per Prüfsummen)
                if let game = library.game(tidHex), let app = library.installedApp(game),
                   FileManager.default.fileExists(atPath: app.appendingPathComponent("romfs").path) {
                    toast = L("Update v\(update.version) for \(name) – installing on the installed game",
                              "Update v\(update.version) für \(name) – wird aufs installierte Spiel gespielt")
                    enqueueUpdateOnInstalled(update: updateURL, installedRomFS: app.appendingPathComponent("romfs"), then: then)
                    continue
                }
                alert = AlertMessage(
                    title: L("Update found", "Update erkannt"),
                    message: L("\(name) v\(update.version): the base game is missing. Import the base game's NSP/XCI as well – then NSPiOS continues on its own.",
                               "\(name) v\(update.version): Das Basisspiel fehlt. Importiere auch das NSP/XCI des Basisspiels – dann macht NSPiOS automatisch weiter."))
                continue
            }
            toast = L("Update v\(update.version) for \(name) – installing", "Update v\(update.version) für \(name) – wird eingespielt")
            enqueueMerged(base: baseURL, update: updateURL, then: then)
        }
    }

    /// Nach dem Zusammenführen: Spiel neu bauen (Spielstände bleiben) bzw. hinzufügen, alten Ordner aufräumen.
    func finishUpdate(tidHex: String, folder: URL) {
        let library = GameLibrary.shared
        let extracted = extractedDir
        guard let program = GameBuilder.findPrograms(in: folder).first(where: { $0.titleID == tidHex }) else {
            library.errorMessage = L("The merged folder holds no program for this game.", "Im zusammengeführten Ordner ist kein Programm für dieses Spiel.")
            return
        }
        Task { @MainActor in
            if let game = library.game(tidHex) {
                let oldFolder = URL(fileURLWithPath: game.exefsPath).deletingLastPathComponent().standardizedFileURL
                await library.update(game, with: program)
                // alten Ordner aufräumen, wenn das Spiel jetzt aus dem neuen gebaut ist
                if library.game(tidHex)?.state != .failed,
                   UserDefaults.standard.bool(forKey: GameLibrary.cleanAfterBuildKey),
                   oldFolder != folder.standardizedFileURL,
                   oldFolder.deletingLastPathComponent().standardizedFileURL == extracted.standardizedFileURL {
                    try? FileManager.default.removeItem(at: oldFolder)
                    self.refresh()
                }
            } else if program.isSupported {
                await library.add(program)
            }
        }
    }

    /// Datei, die über „Öffnen in …“ / Teilen an die App übergeben wurde.
    func handleOpenedURL(_ url: URL) {
        guard url.isFileURL else { return }
        let inbox = documents.appendingPathComponent("Inbox", isDirectory: true)
        if url.resolvingSymlinksInPath().path.hasPrefix(inbox.resolvingSymlinksInPath().path) {
            let destination = FS.uniqueURL(in: packagesDir, fileName: url.lastPathComponent, isDirectory: false)
            do {
                try FileManager.default.moveItem(at: url, to: destination)
                toast = L("Imported", "Importiert")
                autoInstallUpdates(touching: [destination])
            } catch {
                alert = AlertMessage(title: L("Import failed", "Import fehlgeschlagen"), message: error.localizedDescription)
            }
            refresh()
        } else {
            importFiles([url], extractAfterwards: false)
        }
    }

    // MARK: - Keys

    private func importKeys(_ url: URL) {
        busy = L("Loading keys …", "Lade Keys …")
        Task.detached(priority: .userInitiated) {
            let result = Result { try KeyStore.install(from: url) }
            await self.didImportKeys(result)
        }
    }

    private func didImportKeys(_ result: Result<KeySet, Error>) {
        busy = nil
        switch result {
        case .success(let set):
            keyCount = set.count
            toast = L("\(set.count) keys loaded", "\(set.count) Keys geladen")
        case .failure(let error):
            alert = AlertMessage(title: L("Invalid keys", "Keys ungültig"), message: error.localizedDescription)
        }
    }

    func removeKeys() {
        KeyStore.remove()
        keyCount = 0
        toast = L("Keys removed", "Keys entfernt")
    }

    // MARK: - Entpacken

    func enqueueExtraction(of url: URL, securityScoped: Bool = false) {
        if !securityScoped, keyCount > 0, packages.contains(where: { $0.url == url }), Self.isUpdatePackage(url) {
            autoInstallUpdates(touching: [url])
            return
        }
        let isOwnCopy = url.deletingLastPathComponent().resolvingSymlinksInPath().path
            == packagesDir.resolvingSymlinksInPath().path
        let deleteSource = !securityScoped && isOwnCopy
            && UserDefaults.standard.bool(forKey: Self.deleteAfterExtractKey)
        let defaults = UserDefaults.standard
        var options = PackageReader.ExtractOptions()
        options.partition = defaults.bool(forKey: Self.xciSecureOnlyKey) ? "secure" : nil
        options.decrypt = defaults.bool(forKey: Self.decryptKey) && keyCount > 0
        options.keepNCA = defaults.bool(forKey: Self.keepNCAKey)
        pending.append(PendingExtraction(source: url, securityScoped: securityScoped, deleteSource: deleteSource, options: options))
        queuedCount = pending.count
        startNextIfIdle()
    }

    /// RomFS/ExeFS nachträglich aus einer NCA-Datei oder einem entpackten Ordner erzeugen.
    func extractRomFS(from url: URL) {
        guard keyCount > 0 else {
            alert = AlertMessage(
                title: L("prod.keys missing", "prod.keys fehlt"),
                message: L("Load your prod.keys in Settings › RomFS first.", "Lade zuerst deine prod.keys unter Einstellungen › RomFS.")
            )
            return
        }
        pending.append(PendingExtraction(
            source: url, securityScoped: false, deleteSource: false,
            options: PackageReader.ExtractOptions(), romfsOnly: true
        ))
        queuedCount = pending.count
        startNextIfIdle()
    }

    /// Basisspiel + Update entpacken: RomFS mit eingespieltem Update, ExeFS aus dem Update.
    /// Update direkt auf das installierte Spiel (ohne Basispaket).
    func enqueueUpdateOnInstalled(update: URL, installedRomFS: URL, then: (@MainActor @Sendable (URL) -> Void)? = nil) {
        guard keyCount > 0 else { return }
        pending.append(PendingExtraction(
            source: update, securityScoped: false, deleteSource: false,
            options: PackageReader.ExtractOptions(), update: update, then: then, installedRomFS: installedRomFS
        ))
        queuedCount = pending.count
        startNextIfIdle()
    }

    func enqueueMerged(base: URL, update: URL, then: (@MainActor @Sendable (URL) -> Void)? = nil) {
        guard keyCount > 0 else {
            alert = AlertMessage(
                title: L("prod.keys missing", "prod.keys fehlt"),
                message: L("Load your prod.keys in Settings › RomFS first.", "Lade zuerst deine prod.keys unter Einstellungen › RomFS.")
            )
            return
        }
        pending.append(PendingExtraction(
            source: base, securityScoped: false, deleteSource: false,
            options: PackageReader.ExtractOptions(), update: update, then: then
        ))
        queuedCount = pending.count
        startNextIfIdle()
    }

    func cancelExtraction() {
        pending.removeAll()
        queuedCount = 0
        job?.cancel.cancel()
    }

    private func startNextIfIdle() {
        guard job == nil, !pending.isEmpty else { return }
        let next = pending.removeFirst()
        queuedCount = pending.count

        let flag = CancelFlag()
        withAnimation(.smooth) {
            job = ExtractionJob(title: next.source.lastPathComponent, cancel: flag)
        }

        beginBackgroundWork()

        if next.romfsOnly {
            startRomFSJob(next, flag: flag)
            return
        }

        if let update = next.update {
            startMergedJob(next, update: update, flag: flag)
            return
        }

        let baseName = next.source.deletingPathExtension().lastPathComponent
        let finalURL = FS.uniqueURL(in: extractedDir, fileName: baseName, isDirectory: true)
        let partURL = extractedDir.appendingPathComponent(finalURL.lastPathComponent + ".part", isDirectory: true)

        Task.detached(priority: .userInitiated) {
            let scoped = next.securityScoped && next.source.startAccessingSecurityScopedResource()
            defer { if scoped { next.source.stopAccessingSecurityScopedResource() } }

            let report: (UInt64, UInt64, String) -> Void = { done, total, file in
                Task { @MainActor in
                    self.updateProgress(flag: flag, done: done, total: total, file: file)
                }
            }

            var options = next.options
            if options.decrypt { options.keys = KeyStore.load() }

            let outcome: Result<PackageReader.ExtractResult, Error>
            do {
                try? FileManager.default.removeItem(at: partURL)
                let result: PackageReader.ExtractResult
                if next.securityScoped {
                    // Externe Dateien (iCloud, USB, SMB) koordiniert lesen → werden ggf. erst geladen.
                    result = try FS.coordinatedRead(next.source) { url in
                        try PackageReader.extract(url, to: partURL, options: options, cancel: flag, progress: report)
                    }
                } else {
                    result = try PackageReader.extract(next.source, to: partURL, options: options, cancel: flag, progress: report)
                }
                try FileManager.default.moveItem(at: partURL, to: finalURL)
                if next.deleteSource { try? FileManager.default.removeItem(at: next.source) }
                outcome = .success(result)
            } catch {
                try? FileManager.default.removeItem(at: partURL)
                outcome = .failure(error)
            }
            await self.finishJob(name: next.source.lastPathComponent, folder: finalURL.lastPathComponent, outcome: outcome)
        }
    }

    private func startMergedJob(_ next: PendingExtraction, update: URL, flag: CancelFlag) {
        let installedRomFS = next.installedRomFS
        let baseName = installedRomFS != nil
            ? update.deletingPathExtension().lastPathComponent
            : next.source.deletingPathExtension().lastPathComponent + " + Update"
        let finalURL = FS.uniqueURL(in: extractedDir, fileName: baseName, isDirectory: true)
        let partURL = extractedDir.appendingPathComponent(finalURL.lastPathComponent + ".part", isDirectory: true)
        let source = next.source
        let then = next.then

        Task.detached(priority: .userInitiated) {
            let report: (UInt64, UInt64, String) -> Void = { done, total, file in
                Task { @MainActor in
                    self.updateProgress(flag: flag, done: done, total: total, file: file)
                }
            }
            let outcome: Result<PackageReader.ExtractResult, Error>
            do {
                guard let keys = KeyStore.load() else { throw NCAError.missingKey("header_key") }
                try? FileManager.default.removeItem(at: partURL)
                let result = try installedRomFS.map {
                    try PackageReader.extractUpdate(update, onto: $0, to: partURL, keys: keys, cancel: flag, progress: report)
                } ?? PackageReader.extractMerged(base: source, update: update, to: partURL,
                                                 keys: keys, cancel: flag, progress: report)
                try FileManager.default.moveItem(at: partURL, to: finalURL)
                outcome = .success(result)
            } catch {
                try? FileManager.default.removeItem(at: partURL)
                outcome = .failure(error)
            }
            await self.finishJob(name: source.lastPathComponent, folder: finalURL.lastPathComponent, outcome: outcome)
            if case .success = outcome, let then {
                await then(finalURL)
            }
        }
    }

    private func startRomFSJob(_ next: PendingExtraction, flag: CancelFlag) {
        let source = next.source
        let isDirectory = (try? source.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
        let targetDir = isDirectory ? source : source.deletingLastPathComponent()
        let partURL = extractedDir.appendingPathComponent(".romfs-\(UUID().uuidString).part", isDirectory: true)

        Task.detached(priority: .userInitiated) {
            let report: (UInt64, UInt64, String) -> Void = { done, total, file in
                Task { @MainActor in
                    self.updateProgress(flag: flag, done: done, total: total, file: file)
                }
            }

            let outcome: Result<PackageReader.ExtractResult, Error>
            do {
                guard let keys = KeyStore.load() else { throw NCAError.missingKey("header_key") }
                let (ncas, tickets) = FS.ncaFiles(in: source)
                let result = try PackageReader.extractRomFS(
                    ncaFiles: ncas, ticketFiles: tickets, to: partURL,
                    keys: keys, cancel: flag, progress: report
                )
                // Ergebnis (romfs/, exefs/, control/ …) in den Zielordner verschieben
                let fm = FileManager.default
                for child in try fm.contentsOfDirectory(at: partURL, includingPropertiesForKeys: nil) {
                    let destination = FS.uniqueURL(in: targetDir, fileName: child.lastPathComponent, isDirectory: true)
                    try fm.moveItem(at: child, to: destination)
                }
                try? fm.removeItem(at: partURL)
                outcome = .success(result)
            } catch {
                try? FileManager.default.removeItem(at: partURL)
                outcome = .failure(error)
            }
            await self.finishJob(name: source.lastPathComponent, folder: targetDir.lastPathComponent, outcome: outcome)
        }
    }

    private func updateProgress(flag: CancelFlag, done: UInt64, total: UInt64, file: String) {
        guard var current = job, current.cancel === flag else { return }
        current.done = done
        current.total = total
        if !file.isEmpty { current.currentFile = file }
        job = current
    }

    private func finishJob(name: String, folder: String, outcome: Result<PackageReader.ExtractResult, Error>) {
        withAnimation(.smooth) { job = nil }
        switch outcome {
        case .success(let result):
            completedCount += 1
            toast = result.decryptedCount > 0
                ? L("RomFS ready · \(folder)", "RomFS fertig · \(folder)")
                : L("Extracted · \(folder)", "Entpackt · \(folder)")
            if result.decryptedCount == 0, result.ncaCount > 0, keyCount == 0 {
                alert = AlertMessage(
                    title: L("Only NCAs extracted", "Nur NCAs entpackt"),
                    message: L("Load your prod.keys in Settings › RomFS, then tap „Extract RomFS“ in the folder.", "Lade deine prod.keys unter Einstellungen › RomFS und tippe dann im Ordner auf „RomFS entpacken“.")
                )
            } else if !result.warnings.isEmpty {
                alert = AlertMessage(title: name, message: result.warnings.joined(separator: "\n\n"))
            }
        case .failure(let error):
            if case PackageError.cancelled = error {
                toast = L("Cancelled", "Abgebrochen")
            } else {
                alert = AlertMessage(title: L("Extraction failed", "Entpacken fehlgeschlagen"), message: "\(name)\n\n\(error.localizedDescription)")
            }
        }
        refresh()
        if pending.isEmpty {
            endBackgroundWork()
        } else {
            startNextIfIdle()
        }
    }

    private func beginBackgroundWork() {
        UIApplication.shared.isIdleTimerDisabled = true
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Paket entpacken") {
            Task { @MainActor in self.endBackgroundWork() }
        }
    }

    private func endBackgroundWork() {
        UIApplication.shared.isIdleTimerDisabled = false
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    // MARK: - Verwalten

    private var isExtracting: Bool {
        if job != nil {
            alert = AlertMessage(title: L("Please wait", "Bitte warten"), message: L("Extraction in progress.", "Es wird gerade entpackt."))
            return true
        }
        return false
    }

    /// Führt Dateiarbeit im Hintergrund aus und zeigt danach eine Meldung.
    private func perform(_ message: String, _ work: @escaping @Sendable () throws -> String?) {
        busy = message
        Task.detached(priority: .userInitiated) {
            let result = Result { try work() }
            await self.finishWork(result)
        }
    }

    private func finishWork(_ result: Result<String?, Error>) {
        busy = nil
        switch result {
        case .success(let message):
            if let message { toast = message }
        case .failure(let error):
            alert = AlertMessage(title: L("Error", "Fehler"), message: error.localizedDescription)
        }
        refresh()
    }

    func delete(_ urls: [URL]) {
        guard !isExtracting else { return }
        perform(L("Deleting …", "Lösche …")) {
            var freed: Int64 = 0
            for url in urls {
                let bytes = FS.size(of: url)
                try FileManager.default.removeItem(at: url)
                freed += bytes
            }
            return L("Deleted · \(formatBytes(freed)) freed", "Gelöscht · \(formatBytes(freed)) frei")
        }
    }

    func deleteAll(_ scope: DeleteScope) {
        guard !isExtracting else { return }
        let packagesDir = packagesDir, extractedDir = extractedDir, documents = documents
        perform(L("Deleting …", "Lösche …")) {
            let freed: Int64
            switch scope {
            case .packages:
                freed = FS.removeContents(of: packagesDir)
            case .extracted:
                freed = FS.removeContents(of: extractedDir)
            case .everything:
                freed = FS.removeContents(of: documents)
                    + FS.cleanTemporaryData(documents: documents, extractedDir: extractedDir)
            }
            return L("Deleted · \(formatBytes(freed)) freed", "Gelöscht · \(formatBytes(freed)) frei")
        }
    }

    func cleanUp() {
        guard !isExtracting else { return }
        let documents = documents, extractedDir = extractedDir
        perform(L("Cleaning …", "Bereinige …")) {
            let freed = FS.cleanTemporaryData(documents: documents, extractedDir: extractedDir)
            return L("Cleaned · \(formatBytes(freed)) freed", "Bereinigt · \(formatBytes(freed)) frei")
        }
    }

    // MARK: - In „Dateien“ verschieben / kopieren

    func export(_ urls: [URL], asCopy: Bool) {
        guard !urls.isEmpty, !isExtracting else { return }
        exportRequest = ExportRequest(urls: urls, asCopy: asCopy)
    }

    func exportFinished(_ request: ExportRequest, success: Bool) {
        exportRequest = nil
        if success {
            toast = request.asCopy ? L("Copied to Files", "In Dateien kopiert") : L("Moved to Files", "In Dateien verschoben")
        }
        refresh()
    }
}

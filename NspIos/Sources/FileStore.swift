import SwiftUI
import UIKit

struct ExtractionJob {
    let title: String
    var currentFile: String = ""
    var fraction: Double = 0
    let cancel: CancelFlag
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

enum DeleteScope {
    case packages, extracted, everything
}

@MainActor
final class FileStore: ObservableObject {
    nonisolated static let deleteAfterExtractKey = "deleteAfterExtract"

    @Published private(set) var packages: [FileItem] = []
    @Published private(set) var extracted: [FileItem] = []
    @Published private(set) var usedBytes: Int64 = 0
    @Published private(set) var freeBytes: Int64 = 0
    @Published private(set) var queuedCount = 0
    /// Wird bei jedem Refresh erhöht, damit Ordneransichten neu laden.
    @Published private(set) var revision = 0

    @Published var job: ExtractionJob?
    @Published var busy: String?
    @Published var toast: String?
    @Published var alert: AlertMessage?
    @Published var exportRequest: ExportRequest?

    let documents: URL
    let packagesDir: URL
    let extractedDir: URL

    private struct PendingExtraction {
        let source: URL
        let securityScoped: Bool
        let deleteSource: Bool
    }

    private var pending: [PendingExtraction] = []
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    init() {
        documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        packagesDir = documents.appendingPathComponent("NSP", isDirectory: true)
        extractedDir = documents.appendingPathComponent("Entpackt", isDirectory: true)
        refresh()
    }

    // MARK: - Laden

    private func ensureDirectories() {
        for dir in [packagesDir, extractedDir] {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
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
        self.packages = packages
        self.extracted = extracted
        self.usedBytes = used
        self.freeBytes = free
        self.revision += 1
    }

    // MARK: - Import

    /// Kopiert NSP-Dateien in den App-Ordner "NSP".
    func importFiles(_ urls: [URL], extractAfterwards: Bool) {
        guard !urls.isEmpty else { return }
        let dir = packagesDir
        busy = urls.count == 1 ? "Importiere …" : "Importiere \(urls.count) Dateien …"

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
                        _ = try PFS0.entries(of: destination)
                    } catch {
                        try? FileManager.default.removeItem(at: destination)
                        throw error
                    }
                    imported.append(destination)
                } catch {
                    errors.append("\(url.lastPathComponent): \(error.localizedDescription)")
                }
            }
            await self.didImport(imported, errors: errors, extract: extractAfterwards)
        }
    }

    private func didImport(_ imported: [URL], errors: [String], extract: Bool) {
        busy = nil
        if !errors.isEmpty {
            alert = AlertMessage(title: "Import fehlgeschlagen", message: errors.joined(separator: "\n\n"))
        } else if !imported.isEmpty {
            toast = imported.count == 1 ? "1 Datei importiert" : "\(imported.count) Dateien importiert"
        }
        if extract {
            for url in imported { enqueueExtraction(of: url) }
        }
        refresh()
    }

    /// Datei, die über „Öffnen in …“ / Teilen an die App übergeben wurde.
    func handleOpenedURL(_ url: URL) {
        guard url.isFileURL else { return }
        let inbox = documents.appendingPathComponent("Inbox", isDirectory: true)
        if url.resolvingSymlinksInPath().path.hasPrefix(inbox.resolvingSymlinksInPath().path) {
            let destination = FS.uniqueURL(in: packagesDir, fileName: url.lastPathComponent, isDirectory: false)
            do {
                try FileManager.default.moveItem(at: url, to: destination)
                toast = "\(url.lastPathComponent) importiert"
            } catch {
                alert = AlertMessage(title: "Import fehlgeschlagen", message: error.localizedDescription)
            }
            refresh()
        } else {
            importFiles([url], extractAfterwards: false)
        }
    }

    // MARK: - Entpacken

    func enqueueExtraction(of url: URL, securityScoped: Bool = false) {
        let isOwnCopy = url.deletingLastPathComponent().resolvingSymlinksInPath().path
            == packagesDir.resolvingSymlinksInPath().path
        let deleteSource = !securityScoped && isOwnCopy
            && UserDefaults.standard.bool(forKey: Self.deleteAfterExtractKey)
        pending.append(PendingExtraction(source: url, securityScoped: securityScoped, deleteSource: deleteSource))
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
        job = ExtractionJob(title: next.source.lastPathComponent, cancel: flag)

        let baseName = next.source.deletingPathExtension().lastPathComponent
        let finalURL = FS.uniqueURL(in: extractedDir, fileName: baseName, isDirectory: true)
        let partURL = extractedDir.appendingPathComponent(finalURL.lastPathComponent + ".part", isDirectory: true)

        beginBackgroundWork()

        Task.detached(priority: .userInitiated) {
            let scoped = next.securityScoped && next.source.startAccessingSecurityScopedResource()
            defer { if scoped { next.source.stopAccessingSecurityScopedResource() } }

            let outcome: Result<Int, Error>
            do {
                try? FileManager.default.removeItem(at: partURL)
                let entries = try PFS0.extract(next.source, to: partURL, cancel: flag) { fraction, file in
                    Task { @MainActor in
                        self.updateProgress(flag: flag, fraction: fraction, file: file)
                    }
                }
                try FileManager.default.moveItem(at: partURL, to: finalURL)
                if next.deleteSource { try? FileManager.default.removeItem(at: next.source) }
                outcome = .success(entries.count)
            } catch {
                try? FileManager.default.removeItem(at: partURL)
                outcome = .failure(error)
            }
            await self.finishJob(name: next.source.lastPathComponent, folder: finalURL.lastPathComponent, outcome: outcome)
        }
    }

    private func updateProgress(flag: CancelFlag, fraction: Double, file: String) {
        guard let current = job, current.cancel === flag else { return }
        job?.fraction = fraction
        if !file.isEmpty { job?.currentFile = file }
    }

    private func finishJob(name: String, folder: String, outcome: Result<Int, Error>) {
        job = nil
        switch outcome {
        case .success(let count):
            toast = "\(name) entpackt → \(folder) (\(count) Dateien)"
        case .failure(let error):
            if case PFS0Error.cancelled = error {
                toast = "Entpacken abgebrochen"
            } else {
                alert = AlertMessage(title: "Entpacken fehlgeschlagen", message: "\(name)\n\n\(error.localizedDescription)")
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
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "NSP entpacken") {
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
            alert = AlertMessage(title: "Bitte warten", message: "Es wird gerade entpackt. Warte, bis der Vorgang fertig ist, oder brich ihn ab.")
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
            alert = AlertMessage(title: "Fehler", message: error.localizedDescription)
        }
        refresh()
    }

    func delete(_ urls: [URL]) {
        guard !isExtracting else { return }
        perform("Lösche …") {
            var freed: Int64 = 0
            for url in urls {
                let bytes = FS.size(of: url)
                try FileManager.default.removeItem(at: url)
                freed += bytes
            }
            return "Gelöscht – \(formatBytes(freed)) frei"
        }
    }

    func deleteAll(_ scope: DeleteScope) {
        guard !isExtracting else { return }
        let packagesDir = packagesDir, extractedDir = extractedDir, documents = documents
        perform("Lösche …") {
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
            return "Gelöscht – \(formatBytes(freed)) frei"
        }
    }

    func cleanUp() {
        guard !isExtracting else { return }
        let documents = documents, extractedDir = extractedDir
        perform("Bereinige …") {
            let freed = FS.cleanTemporaryData(documents: documents, extractedDir: extractedDir)
            return "Bereinigt – \(formatBytes(freed)) frei"
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
            toast = request.asCopy ? "In Dateien kopiert" : "In Dateien verschoben"
        }
        refresh()
    }
}

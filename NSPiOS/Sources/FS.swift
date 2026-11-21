import Foundation
import UniformTypeIdentifiers

struct FileItem: Identifiable, Hashable {
    let url: URL
    let isDirectory: Bool
    let size: Int64
    let modified: Date

    var id: URL { url }
    var name: String { url.lastPathComponent }
    var isPackage: Bool {
        !isDirectory && PackageReader.supportedExtensions.contains(url.pathExtension.lowercased())
    }
}

enum FileTypes {
    static let nsp = UTType(importedAs: "de.amoo71.nspios.nsp", conformingTo: .data)
    static let nsz = UTType(importedAs: "de.amoo71.nspios.nsz", conformingTo: .data)
    static let xci = UTType(importedAs: "de.amoo71.nspios.xci", conformingTo: .data)
    static let xcz = UTType(importedAs: "de.amoo71.nspios.xcz", conformingTo: .data)
    static let importable: [UTType] = [nsp, nsz, xci, xcz, .data]
}

func formatBytes(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}

/// Dateisystem-Hilfen (nicht an den MainActor gebunden, laufen im Hintergrund).
enum FS {
    static var fm: FileManager { .default }

    static var cachesDirectory: URL? {
        fm.urls(for: .cachesDirectory, in: .userDomainMask).first
    }

    static func list(_ dir: URL) -> [FileItem] {
        let keys: Set<URLResourceKey> = [.isDirectoryKey, .contentModificationDateKey, .fileSizeKey]
        guard let urls = try? fm.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: Array(keys),
            options: [.skipsHiddenFiles]
        ) else { return [] }

        return urls.map { url in
            let values = try? url.resourceValues(forKeys: keys)
            let isDir = values?.isDirectory ?? false
            return FileItem(
                url: url,
                isDirectory: isDir,
                size: isDir ? size(of: url) : Int64(values?.fileSize ?? 0),
                modified: values?.contentModificationDate ?? .distantPast
            )
        }
        .sorted { a, b in
            if a.isDirectory != b.isDirectory { return a.isDirectory }
            return a.name.localizedStandardCompare(b.name) == .orderedAscending
        }
    }

    /// Größe einer Datei oder (rekursiv) eines Ordners.
    static func size(of url: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.isDirectoryKey, .fileSizeKey]
        guard let values = try? url.resourceValues(forKeys: keys) else { return 0 }
        if values.isDirectory != true { return Int64(values.fileSize ?? 0) }

        var total: Int64 = 0
        if let enumerator = fm.enumerator(at: url, includingPropertiesForKeys: Array(keys)) {
            for case let file as URL in enumerator {
                if let v = try? file.resourceValues(forKeys: keys), v.isDirectory != true {
                    total += Int64(v.fileSize ?? 0)
                }
            }
        }
        return total
    }

    static func freeSpace() -> Int64 {
        let home = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    /// Liefert eine freie URL im Ordner, z. B. "Spiel 2.nsp" falls "Spiel.nsp" existiert.
    static func uniqueURL(in dir: URL, fileName: String, isDirectory: Bool) -> URL {
        var candidate = dir.appendingPathComponent(fileName, isDirectory: isDirectory)
        guard fm.fileExists(atPath: candidate.path) else { return candidate }

        let ext = isDirectory ? "" : (fileName as NSString).pathExtension
        let base = isDirectory ? fileName : (fileName as NSString).deletingPathExtension
        var n = 2
        repeat {
            let name = ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)"
            candidate = dir.appendingPathComponent(name, isDirectory: isDirectory)
            n += 1
        } while fm.fileExists(atPath: candidate.path)
        return candidate
    }

    /// Kopiert eine (evtl. iCloud-/File-Provider-)Datei koordiniert.
    static func coordinatedCopy(from source: URL, to destination: URL) throws {
        var coordinatorError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: source, options: [.withoutChanges], error: &coordinatorError) { url in
            do {
                try fm.copyItem(at: url, to: destination)
            } catch {
                copyError = error
            }
        }
        if let copyError { throw copyError }
        if let coordinatorError { throw coordinatorError }
    }

    /// NCA- und Ticket-Dateien in einem Ordner (rekursiv, ohne bereits erzeugte romfs/exefs/control)
    /// bzw. eine einzelne NCA-Datei samt Tickets daneben.
    static func ncaFiles(in url: URL) -> (ncas: [URL], tickets: [URL]) {
        var ncas: [URL] = []
        var tickets: [URL] = []
        let isDirectory = (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true

        func consider(_ file: URL) {
            switch file.pathExtension.lowercased() {
            case "nca", "ncz": ncas.append(file)
            case "tik": tickets.append(file)
            default: break
            }
        }

        if isDirectory {
            if let enumerator = fm.enumerator(at: url, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) {
                for case let file as URL in enumerator {
                    if (try? file.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                        let name = file.lastPathComponent.lowercased()
                        if name.hasPrefix("romfs") || name.hasPrefix("exefs") || name == "control" {
                            enumerator.skipDescendants()
                        }
                        continue
                    }
                    consider(file)
                }
            }
        } else {
            consider(url)
            for sibling in (try? fm.contentsOfDirectory(at: url.deletingLastPathComponent(), includingPropertiesForKeys: nil)) ?? []
            where sibling.pathExtension.lowercased() == "tik" {
                tickets.append(sibling)
            }
        }
        ncas.sort { $0.lastPathComponent < $1.lastPathComponent }
        return (ncas, tickets)
    }

    /// Führt `body` mit koordiniertem Lesezugriff aus (lädt iCloud-/Provider-Dateien bei Bedarf).
    static func coordinatedRead<T>(_ url: URL, _ body: (URL) throws -> T) throws -> T {
        var coordinatorError: NSError?
        var result: Result<T, Error>?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinatorError) { readURL in
            result = Result { try body(readURL) }
        }
        if let result { return try result.get() }
        if let coordinatorError { throw coordinatorError }
        throw CocoaError(.fileReadUnknown)
    }

    /// Löscht den Inhalt eines Ordners (nicht den Ordner selbst) und gibt die freigegebenen Bytes zurück.
    @discardableResult
    static func removeContents(of dir: URL) -> Int64 {
        guard let children = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return 0 }
        var freed: Int64 = 0
        for child in children {
            let bytes = size(of: child)
            if (try? fm.removeItem(at: child)) != nil { freed += bytes }
        }
        return freed
    }

    /// Entfernt leere Unterordner. Gibt `true` zurück, wenn `dir` danach leer ist.
    @discardableResult
    static func removeEmptyDirectories(in dir: URL) -> Bool {
        guard let children = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey]) else {
            return false
        }
        var remaining = children.count
        for child in children {
            let isDir = (try? child.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
            if isDir, removeEmptyDirectories(in: child), (try? fm.removeItem(at: child)) != nil {
                remaining -= 1
            }
        }
        return remaining == 0
    }

    /// Temp, Cache, Inbox, abgebrochene Entpack-Reste und leere Ordner entfernen.
    static func cleanTemporaryData(documents: URL, extractedDir: URL) -> Int64 {
        var freed: Int64 = 0
        freed += removeContents(of: fm.temporaryDirectory)
        if let caches = cachesDirectory { freed += removeContents(of: caches) }

        let inbox = documents.appendingPathComponent("Inbox", isDirectory: true)
        if fm.fileExists(atPath: inbox.path) {
            let bytes = size(of: inbox)
            if (try? fm.removeItem(at: inbox)) != nil { freed += bytes }
        }

        for item in list(extractedDir) where item.url.pathExtension == "part" {
            if (try? fm.removeItem(at: item.url)) != nil { freed += item.size }
        }

        if let children = try? fm.contentsOfDirectory(at: extractedDir, includingPropertiesForKeys: [.isDirectoryKey]) {
            for child in children where (try? child.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                if removeEmptyDirectories(in: child) { try? fm.removeItem(at: child) }
            }
        }
        return freed
    }
}

/// Beobachtet einen Ordner (Anlegen, Löschen, Umbenennen von Einträgen) – z. B. Änderungen aus der Dateien-App.
final class DirectoryWatcher {
    private var source: DispatchSourceFileSystemObject?

    init?(url: URL, onChange: @escaping () -> Void) {
        let fd = open(url.path, O_EVTONLY)
        guard fd >= 0 else { return nil }
        let source = DispatchSource.makeFileSystemObjectSource(
            fileDescriptor: fd,
            eventMask: [.write, .delete, .rename, .link, .revoke],
            queue: .main
        )
        source.setEventHandler(handler: onChange)
        source.setCancelHandler { close(fd) }
        source.resume()
        self.source = source
    }

    deinit {
        source?.cancel()
    }
}

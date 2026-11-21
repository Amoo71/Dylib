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
        !isDirectory && ["nsp", "nsz"].contains(url.pathExtension.lowercased())
    }
}

enum FileTypes {
    static let nsp = UTType(importedAs: "de.amoo71.nspios.nsp", conformingTo: .data)
    static let nsz = UTType(importedAs: "de.amoo71.nspios.nsz", conformingTo: .data)
    static let importable: [UTType] = [nsp, nsz, .data]
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

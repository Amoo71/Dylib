import Foundation
import Darwin

/// Erkanntes Container-Format.
enum PackageFormat: String, Sendable {
    case nsp = "NSP"
    case xci = "XCI"
}

/// Eine Datei innerhalb eines NSP/XCI-Containers.
struct PackageEntry: Identifiable, Hashable, Sendable {
    /// Relativer Pfad, bei XCI mit Partition, z. B. "secure/abc.nca".
    let path: String
    /// Absoluter Offset der Daten in der Datei.
    let dataOffset: UInt64
    let size: UInt64

    var id: String { path }
    var name: String { (path as NSString).lastPathComponent }
    var folder: String? {
        let dir = (path as NSString).deletingLastPathComponent
        return dir.isEmpty ? nil : dir
    }
}

struct PackageInfo: Sendable {
    let format: PackageFormat
    let entries: [PackageEntry]
}

enum PackageError: LocalizedError {
    case invalidMagic
    case truncated
    case corruptHeader(String)
    case notEnoughSpace(needed: Int64, free: Int64)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .invalidMagic:
            return "Keine gültige NSP- oder XCI-Datei."
        case .truncated:
            return "Die Datei ist unvollständig oder beschädigt."
        case .corruptHeader(let detail):
            return "Beschädigter Header: \(detail)"
        case .notEnoughSpace(let needed, let free):
            return "Nicht genug Speicher: benötigt \(formatBytes(needed)), frei \(formatBytes(free))."
        case .cancelled:
            return "Abgebrochen."
        }
    }
}

/// Threadsicheres Abbruch-Flag für laufende Entpack-Vorgänge.
final class CancelFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }
        return value
    }

    func cancel() {
        lock.lock(); value = true; lock.unlock()
    }
}

/// Liest und entpackt NSP/NSZ (PFS0) und XCI/XCZ (Gamecard-Header + HFS0-Partitionen).
///
/// PFS0 / HFS0:
///   0x00  Magic "PFS0" / "HFS0"
///   0x04  UInt32  Anzahl Dateien
///   0x08  UInt32  Größe der String-Tabelle
///   0x0C  UInt32  reserviert
///   0x10  Einträge (PFS0: 0x18 Byte, HFS0: 0x40 Byte): UInt64 Offset, UInt64 Größe, UInt32 Namens-Offset, …
///   ...   String-Tabelle, danach Dateidaten
///
/// XCI:
///   0x100 Magic "HEAD" (bei Dumps mit Key-Area +0x1000)
///   0x130 UInt64 Offset der Root-HFS0 → Partitionen update / normal / secure / logo,
///         jede Partition ist wieder eine HFS0.
enum PackageReader {
    /// 16 MiB – große Blöcke = wenige Syscalls, volle Flash-Bandbreite.
    static let bufferSize = 16 * 1024 * 1024

    static let supportedExtensions: Set<String> = ["nsp", "nsz", "xci", "xcz"]

    // MARK: - Header lesen

    static func entries(of url: URL) throws -> [PackageEntry] {
        try inspect(url).entries
    }

    static func inspect(_ url: URL) throws -> PackageInfo {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let fileSize = try handle.seekToEnd()

        var usedPaths = Set<String>()

        // NSP / NSZ
        if try magic(handle, at: 0, fileSize: fileSize) == "PFS0" {
            let raw = try parseTable(handle, at: 0, magic: "PFS0", entrySize: 0x18, fileSize: fileSize)
            let entries = raw.enumerated().map { index, item in
                PackageEntry(
                    path: uniquePath(sanitize(item.name, index: index), used: &usedPaths),
                    dataOffset: item.offset,
                    size: item.size
                )
            }
            guard !entries.isEmpty else { throw PackageError.corruptHeader("keine Dateien") }
            return PackageInfo(format: .nsp, entries: entries)
        }

        // XCI / XCZ (mit oder ohne vorangestellte Key-Area)
        for base: UInt64 in [0, 0x1000] {
            guard try magic(handle, at: base + 0x100, fileSize: fileSize) == "HEAD" else { continue }
            let rootOffset = try base &+ readUInt64(handle, at: base + 0x130, fileSize: fileSize)
            let partitions = try parseTable(handle, at: rootOffset, magic: "HFS0", entrySize: 0x40, fileSize: fileSize)

            var entries: [PackageEntry] = []
            for (pIndex, partition) in partitions.enumerated() where partition.size > 0 {
                let partitionName = sanitize(partition.name, index: pIndex)
                // Leere/ungewöhnliche Partitionen (z. B. "logo" bei älteren Dumps) überspringen
                guard let files = try? parseTable(handle, at: partition.offset, magic: "HFS0", entrySize: 0x40, fileSize: fileSize) else {
                    continue
                }
                for (fIndex, file) in files.enumerated() {
                    let path = partitionName + "/" + sanitize(file.name, index: fIndex)
                    entries.append(PackageEntry(
                        path: uniquePath(path, used: &usedPaths),
                        dataOffset: file.offset,
                        size: file.size
                    ))
                }
            }
            guard !entries.isEmpty else { throw PackageError.corruptHeader("keine Partitionen mit Dateien") }
            return PackageInfo(format: .xci, entries: entries)
        }

        throw PackageError.invalidMagic
    }

    // MARK: - Entpacken

    /// Entpackt den Container nach `destination`.
    ///
    /// - Parameter partition: Nur bei XCI – z. B. "secure", um nur die Spieldaten
    ///   (direkt ohne Unterordner) zu entpacken. `nil` = alle Partitionen in Unterordnern.
    ///
    /// Nutzt POSIX `pread`/`write` mit einem einzigen wiederverwendeten 16-MiB-Puffer:
    /// keine Allokationen pro Block, kein Foundation-Overhead, konstanter RAM-Verbrauch.
    @discardableResult
    static func extract(
        _ source: URL,
        to destination: URL,
        partition: String? = nil,
        cancel: CancelFlag,
        progress: @escaping (_ done: UInt64, _ total: UInt64, _ currentFile: String) -> Void
    ) throws -> [PackageEntry] {
        let info = try inspect(source)

        // (Zielpfad relativ zu destination, Eintrag)
        var jobs: [(String, PackageEntry)] = info.entries.map { ($0.path, $0) }
        if info.format == .xci, let partition {
            let prefix = partition + "/"
            let selected = info.entries.filter { $0.path.hasPrefix(prefix) }
            if !selected.isEmpty {
                jobs = selected.map { (String($0.path.dropFirst(prefix.count)), $0) }
            }
        }

        let totalBytes = jobs.reduce(UInt64(0)) { $0 + $1.1.size }
        let free = FS.freeSpace()
        if free > 0, Int64(clamping: totalBytes) > free {
            throw PackageError.notEnoughSpace(needed: Int64(clamping: totalBytes), free: free)
        }

        let fm = FileManager.default
        try fm.createDirectory(at: destination, withIntermediateDirectories: true)
        for folder in Set(jobs.map { ($0.0 as NSString).deletingLastPathComponent }) where !folder.isEmpty {
            try fm.createDirectory(at: destination.appendingPathComponent(folder, isDirectory: true), withIntermediateDirectories: true)
        }

        let inFD = open(source.path, O_RDONLY)
        guard inFD >= 0 else { throw posixError() }
        defer { close(inFD) }
        _ = fcntl(inFD, F_RDAHEAD, 1)

        let buffer = UnsafeMutableRawPointer.allocate(byteCount: bufferSize, alignment: 16_384)
        defer { buffer.deallocate() }

        var done: UInt64 = 0
        var lastTick: UInt64 = 0
        progress(0, totalBytes, jobs.first?.1.name ?? "")

        for (relativePath, entry) in jobs {
            if cancel.isCancelled { throw PackageError.cancelled }

            let outPath = destination.appendingPathComponent(relativePath, isDirectory: false).path
            let outFD = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
            guard outFD >= 0 else { throw posixError() }
            defer { close(outFD) }

            var readOffset = off_t(entry.dataOffset)
            var remaining = entry.size

            while remaining > 0 {
                if cancel.isCancelled { throw PackageError.cancelled }

                let want = Int(min(UInt64(bufferSize), remaining))
                let got = pread(inFD, buffer, want, readOffset)
                if got < 0 {
                    if errno == EINTR { continue }
                    throw posixError()
                }
                if got == 0 { throw PackageError.truncated }

                try writeAll(outFD, buffer, got)

                readOffset += off_t(got)
                remaining -= UInt64(got)
                done += UInt64(got)

                // max. ~12 UI-Updates pro Sekunde
                let now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW)
                if now &- lastTick >= 80_000_000 {
                    lastTick = now
                    progress(done, totalBytes, entry.path)
                }
            }
        }
        progress(totalBytes, totalBytes, "")
        return jobs.map { $0.1 }
    }

    // MARK: - Tabellen (PFS0 / HFS0)

    private struct RawEntry {
        let name: String
        let offset: UInt64   // absolut
        let size: UInt64
    }

    private static func parseTable(_ handle: FileHandle, at base: UInt64, magic expected: String, entrySize: Int, fileSize: UInt64) throws -> [RawEntry] {
        guard base &+ 16 <= fileSize else { throw PackageError.truncated }
        try handle.seek(toOffset: base)
        let header = try readExactly(handle, count: 16)
        guard header.prefix(4).elementsEqual(expected.utf8) else { throw PackageError.invalidMagic }

        let count = Int(header.le32(4))
        let stringTableSize = Int(header.le32(8))
        guard count <= 65_536 else { throw PackageError.corruptHeader("ungültige Dateianzahl (\(count))") }
        guard stringTableSize <= 16 * 1024 * 1024 else { throw PackageError.corruptHeader("String-Tabelle zu groß") }

        let table = try readExactly(handle, count: count * entrySize)
        let strings = try readExactly(handle, count: stringTableSize)
        let dataStart = base + UInt64(16 + count * entrySize + stringTableSize)

        var result: [RawEntry] = []
        result.reserveCapacity(count)
        for index in 0..<count {
            let at = index * entrySize
            let offset = table.le64(at)
            let size = table.le64(at + 8)
            let nameOffset = Int(table.le32(at + 16))
            guard nameOffset < max(strings.count, 1) else {
                throw PackageError.corruptHeader("Namens-Offset außerhalb der Tabelle")
            }
            let nameBytes = strings.isEmpty ? Data() : strings[(strings.startIndex + nameOffset)...].prefix { $0 != 0 }

            let (absolute, overflowA) = dataStart.addingReportingOverflow(offset)
            let (end, overflowB) = absolute.addingReportingOverflow(size)
            guard !overflowA, !overflowB, end <= fileSize else { throw PackageError.truncated }

            result.append(RawEntry(name: String(decoding: nameBytes, as: UTF8.self), offset: absolute, size: size))
        }
        return result
    }

    // MARK: - Hilfsfunktionen

    private static func magic(_ handle: FileHandle, at offset: UInt64, fileSize: UInt64) throws -> String? {
        guard offset + 4 <= fileSize else { return nil }
        try handle.seek(toOffset: offset)
        guard let data = try handle.read(upToCount: 4), data.count == 4 else { return nil }
        return String(bytes: data, encoding: .ascii)
    }

    private static func readUInt64(_ handle: FileHandle, at offset: UInt64, fileSize: UInt64) throws -> UInt64 {
        guard offset + 8 <= fileSize else { throw PackageError.truncated }
        try handle.seek(toOffset: offset)
        return try readExactly(handle, count: 8).le64(0)
    }

    private static func writeAll(_ fd: Int32, _ buffer: UnsafeMutableRawPointer, _ count: Int) throws {
        var written = 0
        while written < count {
            let n = write(fd, buffer + written, count - written)
            if n < 0 {
                if errno == EINTR { continue }
                throw posixError()
            }
            written += n
        }
    }

    private static func posixError() -> Error {
        NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
    }

    private static func readExactly(_ handle: FileHandle, count: Int) throws -> Data {
        if count == 0 { return Data() }
        guard let data = try handle.read(upToCount: count), data.count == count else {
            throw PackageError.truncated
        }
        return data
    }

    private static func uniquePath(_ path: String, used: inout Set<String>) -> String {
        var candidate = path
        var n = 2
        while used.contains(candidate) {
            let dir = (path as NSString).deletingLastPathComponent
            let file = "\(n)_" + (path as NSString).lastPathComponent
            candidate = dir.isEmpty ? file : dir + "/" + file
            n += 1
        }
        used.insert(candidate)
        return candidate
    }

    /// Verhindert Pfad-Tricks wie "../" in Dateinamen.
    private static func sanitize(_ name: String, index: Int) -> String {
        var cleaned = name
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty || cleaned == "." || cleaned == ".." {
            cleaned = "datei_\(index)"
        }
        return cleaned
    }
}

private extension Data {
    func le32(_ offset: Int) -> UInt32 {
        var value: UInt32 = 0
        for i in 0..<4 {
            value |= UInt32(self[startIndex + offset + i]) << (8 * i)
        }
        return value
    }

    func le64(_ offset: Int) -> UInt64 {
        var value: UInt64 = 0
        for i in 0..<8 {
            value |= UInt64(self[startIndex + offset + i]) << (8 * i)
        }
        return value
    }
}

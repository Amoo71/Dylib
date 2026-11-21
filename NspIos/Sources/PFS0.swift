import Foundation

/// Ein Eintrag im PFS0-Container (NSP / NSZ).
struct PFS0Entry: Identifiable, Hashable {
    let name: String
    /// Absoluter Offset der Daten in der Datei.
    let dataOffset: UInt64
    let size: UInt64
    var id: String { name }
}

enum PFS0Error: LocalizedError {
    case invalidMagic
    case truncated
    case corruptHeader(String)
    case notEnoughSpace(needed: Int64, free: Int64)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .invalidMagic:
            return "Keine gültige NSP-Datei (PFS0-Kennung fehlt)."
        case .truncated:
            return "Die Datei ist unvollständig oder beschädigt."
        case .corruptHeader(let detail):
            return "Beschädigter NSP-Header: \(detail)"
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

/// Liest und entpackt PFS0-Container (das Format von .nsp und .nsz).
///
/// Aufbau:
///   0x00  "PFS0"
///   0x04  UInt32  Anzahl Dateien
///   0x08  UInt32  Größe der String-Tabelle
///   0x0C  UInt32  reserviert
///   0x10  Dateieinträge à 24 Byte: UInt64 Offset, UInt64 Größe, UInt32 Namens-Offset, UInt32 reserviert
///   ...   String-Tabelle (nullterminierte Namen)
///   ...   Dateidaten
enum PFS0 {
    static let chunkSize = 4 * 1024 * 1024

    static func entries(of url: URL) throws -> [PFS0Entry] {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        let fileSize = try handle.seekToEnd()
        try handle.seek(toOffset: 0)

        let header = try readExactly(handle, count: 16)
        guard header.prefix(4).elementsEqual("PFS0".utf8) else { throw PFS0Error.invalidMagic }

        let count = Int(header.le32(4))
        let stringTableSize = Int(header.le32(8))
        guard count > 0, count <= 65_536 else {
            throw PFS0Error.corruptHeader("ungültige Dateianzahl (\(count))")
        }
        guard stringTableSize <= 16 * 1024 * 1024 else {
            throw PFS0Error.corruptHeader("String-Tabelle zu groß")
        }

        let table = try readExactly(handle, count: count * 24)
        let strings = try readExactly(handle, count: stringTableSize)
        let dataStart = UInt64(16 + count * 24 + stringTableSize)

        var result: [PFS0Entry] = []
        var usedNames = Set<String>()

        for index in 0..<count {
            let base = index * 24
            let offset = table.le64(base)
            let size = table.le64(base + 8)
            let nameOffset = Int(table.le32(base + 16))

            guard nameOffset < strings.count else {
                throw PFS0Error.corruptHeader("Namens-Offset außerhalb der Tabelle")
            }
            let nameBytes = strings[(strings.startIndex + nameOffset)...].prefix { $0 != 0 }
            let rawName = String(decoding: nameBytes, as: UTF8.self)

            var name = sanitize(rawName, index: index)
            if usedNames.contains(name) {
                var n = 2
                while usedNames.contains("\(n)_\(name)") { n += 1 }
                name = "\(n)_\(name)"
            }
            usedNames.insert(name)

            let (absolute, overflowA) = dataStart.addingReportingOverflow(offset)
            let (end, overflowB) = absolute.addingReportingOverflow(size)
            guard !overflowA, !overflowB, end <= fileSize else { throw PFS0Error.truncated }

            result.append(PFS0Entry(name: name, dataOffset: absolute, size: size))
        }
        return result
    }

    /// Entpackt alle Dateien des Containers nach `destination`.
    @discardableResult
    static func extract(
        _ source: URL,
        to destination: URL,
        cancel: CancelFlag,
        progress: @escaping (_ fraction: Double, _ currentFile: String) -> Void
    ) throws -> [PFS0Entry] {
        let list = try entries(of: source)
        let fm = FileManager.default

        let totalBytes = list.reduce(UInt64(0)) { $0 + $1.size }
        let free = FS.freeSpace()
        if free > 0, Int64(clamping: totalBytes) > free {
            throw PFS0Error.notEnoughSpace(needed: Int64(clamping: totalBytes), free: free)
        }

        try fm.createDirectory(at: destination, withIntermediateDirectories: true)

        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }

        let total = Double(max(totalBytes, 1))
        var done: UInt64 = 0
        var lastReported = -1.0

        for entry in list {
            if cancel.isCancelled { throw PFS0Error.cancelled }
            progress(Double(done) / total, entry.name)

            let outURL = destination.appendingPathComponent(entry.name, isDirectory: false)
            guard fm.createFile(atPath: outURL.path, contents: nil) else {
                throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: outURL.path])
            }
            let output = try FileHandle(forWritingTo: outURL)
            defer { try? output.close() }

            try input.seek(toOffset: entry.dataOffset)
            var remaining = entry.size
            while remaining > 0 {
                if cancel.isCancelled { throw PFS0Error.cancelled }
                let n = Int(min(UInt64(chunkSize), remaining))
                try autoreleasepool {
                    let chunk = try readExactly(input, count: n)
                    try output.write(contentsOf: chunk)
                }
                remaining -= UInt64(n)
                done += UInt64(n)

                let fraction = Double(done) / total
                if fraction - lastReported >= 0.005 {
                    lastReported = fraction
                    progress(fraction, entry.name)
                }
            }
        }
        progress(1, "")
        return list
    }

    // MARK: - Hilfsfunktionen

    private static func readExactly(_ handle: FileHandle, count: Int) throws -> Data {
        if count == 0 { return Data() }
        guard let data = try handle.read(upToCount: count), data.count == count else {
            throw PFS0Error.truncated
        }
        return data
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

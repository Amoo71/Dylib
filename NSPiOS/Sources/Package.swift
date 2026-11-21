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

    struct ExtractOptions: Sendable {
        /// Nur bei XCI – z. B. "secure": nur Spieldaten direkt ohne Unterordner. `nil` = alle Partitionen.
        var partition: String? = nil
        /// RomFS / ExeFS aus den NCAs entschlüsseln (benötigt `keys`).
        var decrypt = false
        /// Entschlüsselte NCAs zusätzlich als Datei behalten.
        var keepNCA = false
        var keys: KeySet? = nil
    }

    struct ExtractResult: Sendable {
        let fileCount: Int
        let decryptedCount: Int
        /// Anzahl NCAs im Paket (für den Hinweis „Keys laden für RomFS“).
        let ncaCount: Int
        let warnings: [String]
    }

    /// Eine NCA-Quelle: Datei-Index (in `fds`), Offset, Größe.
    struct NCACandidate {
        let name: String
        let path: String
        let file: Int
        let offset: UInt64
        let size: UInt64
    }

    /// Entpackt den Container nach `destination` – optional inkl. RomFS/ExeFS.
    ///
    /// Nutzt POSIX `pread`/`write` mit festen 16-MiB-Puffern und hardwarebeschleunigtem AES-CTR:
    /// keine Allokationen pro Block, kein Foundation-Overhead, konstanter RAM-Verbrauch.
    @discardableResult
    static func extract(
        _ source: URL,
        to destination: URL,
        options: ExtractOptions = ExtractOptions(),
        cancel: CancelFlag,
        progress: @escaping (_ done: UInt64, _ total: UInt64, _ currentFile: String) -> Void
    ) throws -> ExtractResult {
        let info = try inspect(source)

        let inFD = open(source.path, O_RDONLY)
        guard inFD >= 0 else { throw posixError() }
        defer { close(inFD) }
        _ = fcntl(inFD, F_RDAHEAD, 1)

        // 1) Container-Dateien
        var entries = info.entries.map { (path: $0.path, entry: $0) }
        if info.format == .xci, let partition = options.partition {
            let prefix = partition + "/"
            let selected = info.entries.filter { $0.path.hasPrefix(prefix) }
            if !selected.isEmpty {
                entries = selected.map { (path: String($0.path.dropFirst(prefix.count)), entry: $0) }
            }
        }
        var jobs = entries.map {
            CopyJob(path: $0.path, size: $0.entry.size, source: .raw($0.entry.dataOffset), origin: $0.entry.path)
        }
        var usedPaths = Set(jobs.map(\.path))
        var warnings: [String] = []
        var decryptedNCAs = Set<String>()

        // NCAs (bei XCI nur aus der secure-Partition)
        let relevant = info.entries.filter { info.format == .nsp || $0.path.hasPrefix("secure/") }
        let candidates = relevant
            .filter { entry in
                let lower = entry.name.lowercased()
                return lower.hasSuffix(".nca") || lower.hasSuffix(".ncz")
            }
            .map { NCACandidate(name: $0.name, path: $0.path, file: 0, offset: $0.dataOffset, size: $0.size) }

        // 2) RomFS / ExeFS aus den NCAs
        if options.decrypt, let keys = options.keys {
            var tickets: [Data: Data] = [:]
            for entry in relevant where entry.name.lowercased().hasSuffix(".tik") && entry.size <= 0x10000 {
                if let data = readFully(inFD, offset: entry.dataOffset, count: Int(entry.size)),
                   let ticket = try? NCAReader.parseTicket(data) {
                    tickets[ticket.rightsID] = ticket.encryptedTitleKey
                }
            }
            let plan = planDecryption(fds: [inFD], candidates: candidates, tickets: tickets, keys: keys, usedPaths: &usedPaths)
            jobs += plan.jobs
            warnings += plan.warnings
            decryptedNCAs = plan.decryptedNCAs
            if !options.keepNCA {
                jobs.removeAll { job in job.origin.map { decryptedNCAs.contains($0) } ?? false }
            }
        }

        try run(jobs, fds: [inFD], destination: destination, cancel: cancel, progress: progress)
        return ExtractResult(fileCount: jobs.count, decryptedCount: decryptedNCAs.count, ncaCount: candidates.count, warnings: warnings)
    }

    /// RomFS / ExeFS / Control nachträglich aus bereits entpackten NCA-Dateien erzeugen.
    static func extractRomFS(
        ncaFiles: [URL],
        ticketFiles: [URL],
        to destination: URL,
        keys: KeySet,
        cancel: CancelFlag,
        progress: @escaping (_ done: UInt64, _ total: UInt64, _ currentFile: String) -> Void
    ) throws -> ExtractResult {
        var fds: [Int32] = []
        defer { fds.forEach { close($0) } }

        var candidates: [NCACandidate] = []
        for url in ncaFiles {
            let fd = open(url.path, O_RDONLY)
            guard fd >= 0 else { continue }
            _ = fcntl(fd, F_RDAHEAD, 1)
            fds.append(fd)
            let size = UInt64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            candidates.append(NCACandidate(name: url.lastPathComponent, path: url.path, file: fds.count - 1, offset: 0, size: size))
        }

        var tickets: [Data: Data] = [:]
        for url in ticketFiles {
            if let data = try? Data(contentsOf: url), data.count <= 0x10000,
               let ticket = try? NCAReader.parseTicket(data) {
                tickets[ticket.rightsID] = ticket.encryptedTitleKey
            }
        }

        var usedPaths = Set<String>()
        let plan = planDecryption(fds: fds, candidates: candidates, tickets: tickets, keys: keys, usedPaths: &usedPaths)
        guard !plan.jobs.isEmpty else {
            throw PackageError.corruptHeader(
                (["Keine RomFS gefunden."] + plan.warnings).joined(separator: "\n\n")
            )
        }
        try run(plan.jobs, fds: fds, destination: destination, cancel: cancel, progress: progress)
        return ExtractResult(fileCount: plan.jobs.count, decryptedCount: plan.decryptedNCAs.count, ncaCount: candidates.count, warnings: plan.warnings)
    }

    // MARK: - Ausführen

    private static func run(
        _ jobs: [CopyJob],
        fds: [Int32],
        destination: URL,
        cancel: CancelFlag,
        progress: @escaping (_ done: UInt64, _ total: UInt64, _ currentFile: String) -> Void
    ) throws {
        let totalBytes = jobs.reduce(UInt64(0)) { $0 + $1.size }
        let free = FS.freeSpace()
        if free > 0, Int64(clamping: totalBytes) > free {
            throw PackageError.notEnoughSpace(needed: Int64(clamping: totalBytes), free: free)
        }

        let fm = FileManager.default
        try fm.createDirectory(at: destination, withIntermediateDirectories: true)
        for folder in Set(jobs.map { ($0.path as NSString).deletingLastPathComponent }) where !folder.isEmpty {
            try fm.createDirectory(at: destination.appendingPathComponent(folder, isDirectory: true), withIntermediateDirectories: true)
        }

        let buffer = UnsafeMutableRawPointer.allocate(byteCount: bufferSize, alignment: 16_384)
        defer { buffer.deallocate() }
        let plain = UnsafeMutableRawPointer.allocate(byteCount: bufferSize, alignment: 16_384)
        defer { plain.deallocate() }

        var done: UInt64 = 0
        var lastTick: UInt64 = 0
        progress(0, totalBytes, jobs.first?.path ?? "")

        for job in jobs {
            if cancel.isCancelled { throw PackageError.cancelled }
            let inFD = fds[job.file]

            let outPath = destination.appendingPathComponent(job.path, isDirectory: false).path
            let outFD = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
            guard outFD >= 0 else { throw posixError() }
            defer { close(outFD) }

            // Lese-Startposition, ggf. CTR-Stream und zu überspringende Bytes (16-Byte-Ausrichtung)
            var readOffset: UInt64
            var skip = 0
            var ctr: AESCTR?
            switch job.source {
            case .raw(let offset):
                readOffset = offset
            case .section(let section, let offset):
                guard offset &+ job.size <= section.size else { throw PackageError.truncated }
                let absolute = section.offset + offset
                let aligned = section.encrypted ? absolute & ~UInt64(15) : absolute
                skip = Int(absolute - aligned)
                readOffset = section.ncaOffset + aligned
                if section.encrypted {
                    ctr = try AESCTR(key: section.key, iv: section.iv(atNCAOffset: aligned))
                }
            }

            var remaining = job.size
            while remaining > 0 {
                if cancel.isCancelled { throw PackageError.cancelled }

                let want = Int(min(UInt64(bufferSize), UInt64(skip) + remaining))
                let got = pread(inFD, buffer, want, off_t(readOffset))
                if got < 0 {
                    if errno == EINTR { continue }
                    throw posixError()
                }
                if got <= skip { throw PackageError.truncated }

                let data: UnsafeMutableRawPointer
                if let ctr {
                    try ctr.process(buffer, plain, count: got)
                    data = plain
                } else {
                    data = buffer
                }

                let usable = min(got - skip, Int(min(remaining, UInt64(Int.max))))
                try writeAll(outFD, data + skip, usable)

                readOffset += UInt64(got)
                remaining -= UInt64(usable)
                done += UInt64(usable)
                skip = 0

                // max. ~12 UI-Updates pro Sekunde
                let now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW)
                if now &- lastTick >= 80_000_000 {
                    lastTick = now
                    progress(done, totalBytes, job.path)
                }
            }
        }
        progress(totalBytes, totalBytes, "")
    }

    private static func readFully(_ fd: Int32, offset: UInt64, count: Int) -> Data? {
        guard count > 0 else { return nil }
        var data = Data(count: count)
        let got = data.withUnsafeMutableBytes { pread(fd, $0.baseAddress, count, off_t(offset)) }
        return got == count ? data : nil
    }

    /// Plant RomFS/ExeFS/Control für die übergebenen NCAs.
    private static func planDecryption(
        fds: [Int32],
        candidates: [NCACandidate],
        tickets: [Data: Data],
        keys: KeySet,
        usedPaths: inout Set<String>
    ) -> (jobs: [CopyJob], warnings: [String], decryptedNCAs: Set<String>) {
        var jobs: [CopyJob] = []
        var warnings: [String] = []
        var decrypted = Set<String>()

        guard keys.headerKey != nil else {
            return ([], ["RomFS: „header_key“ fehlt in der prod.keys."], [])
        }

        var programIndex = 0
        for candidate in candidates {
            let lower = candidate.name.lowercased()
            if lower.hasSuffix(".ncz") {
                warnings.append("\(candidate.name): \(NCAError.compressed.localizedDescription)")
                continue
            }
            guard lower.hasSuffix(".nca"), !lower.hasSuffix(".cnmt.nca") else { continue }
            let fd = fds[candidate.file]

            do {
                let header = try NCAReader.open(fd: fd, ncaOffset: candidate.offset, ncaSize: candidate.size, keys: keys, tickets: tickets)
                let titleHex = String(format: "%016llx", header.titleID)

                let romfsRoot: String
                var exefsRoot: String?
                if header.isProgram {
                    let suffix = programIndex == 0 ? "" : "_\(titleHex)"
                    romfsRoot = "romfs" + suffix
                    exefsRoot = "exefs" + suffix
                    programIndex += 1
                } else if header.isControl {
                    romfsRoot = "control"
                } else if header.isData {
                    romfsRoot = "romfs_\(titleHex)"
                } else {
                    continue
                }

                var ncaJobs: [CopyJob] = []
                for (index, fs, section) in header.sections {
                    switch fs[2] {
                    case 0: // RomFS
                        ncaJobs += try NCAReader.planRomFS(fd: fd, section: section, fsHeader: fs, root: romfsRoot)
                    case 1 where index == 0 && exefsRoot != nil: // ExeFS
                        ncaJobs += try NCAReader.planExeFS(fd: fd, section: section, fsHeader: fs, root: exefsRoot!)
                    default:
                        break
                    }
                }

                warnings += header.warnings.map { "\(candidate.name): \($0)" }
                guard !ncaJobs.isEmpty else { continue }

                for job in ncaJobs {
                    let path = uniquePath(job.path, used: &usedPaths)
                    jobs.append(CopyJob(path: path, size: job.size, source: job.source, file: candidate.file))
                }
                decrypted.insert(candidate.path)
            } catch {
                warnings.append("\(candidate.name): \(error.localizedDescription)")
            }
        }
        return (jobs, warnings, decrypted)
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

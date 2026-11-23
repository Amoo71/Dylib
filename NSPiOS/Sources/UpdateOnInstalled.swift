import Foundation
import Darwin
import CryptoKit

/// Unveränderte Teile des Basis-RomFS, zusammengesetzt aus den Dateien eines bereits installierten Spiels.
///
/// Ein Update verweist für unveränderte Daten auf Offsets im Original-Basispaket. Welche Datei dort lag, verrät das Update
/// selbst: Eine Datei, deren Daten ganz aus der Basis stammen, lag dort am Stück und ist im installierten Spiel unter
/// demselben Pfad vorhanden. Daraus entsteht eine Karte Basis-Offset → (Datei, Position). Danach wird jeder Datenblock,
/// dessen Prüfsumme das Update mitbringt, gegen die IVFC-SHA-256 geprüft – nur bei vollständiger Übereinstimmung geht es weiter.
final class ReconstructedBase: @unchecked Sendable {
    struct Span {
        let start: UInt64
        let end: UInt64
        let url: URL
    }

    private let spans: [Span]
    private var handles: [URL: Int32] = [:]
    private let lock = NSLock()

    init(spans: [Span]) {
        self.spans = spans.sorted { $0.start < $1.start }
    }

    deinit {
        handles.values.forEach { close($0) }
    }

    /// Kleine Lücken zwischen Dateien (Ausrichtung im RomFS) sind Nullen; größere Lücken sind unbekannte Daten.
    private static let maxPadding: UInt64 = 0x200
    /// Unbekannte Basisbereiche als Nullen lesen (Füllbereiche im Original). Nur zulässig, wenn jeder betroffene Block
    /// danach gegen eine Prüfsumme des Updates bestätigt wird (build → verify mit mustVerify).
    var unknownAsZero = false

    private func fd(_ url: URL) throws -> Int32 {
        lock.lock(); defer { lock.unlock() }
        if let fd = handles[url] { return fd }
        let fd = open(url.path, O_RDONLY)
        guard fd >= 0 else { throw NCAError.updateNeedsBase(url.lastPathComponent) }
        handles[url] = fd
        return fd
    }

    private func span(containing p: UInt64) -> (index: Int, inside: Bool) {
        var lo = 0, hi = spans.count - 1, found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if spans[mid].start <= p { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        if found >= 0, p < spans[found].end { return (found, true) }
        return (found, false)
    }

    func covers(_ start: UInt64, _ length: UInt64) -> Bool {
        var p = start
        let end = start + length
        while p < end {
            let (i, inside) = span(containing: p)
            if inside {
                p = spans[i].end
            } else {
                let next = i + 1 < spans.count ? spans[i + 1].start : UInt64.max
                let gapEnd = min(next, end)
                guard gapEnd - p <= Self.maxPadding else { return false }
                p = gapEnd
            }
        }
        return true
    }

    func read(at position: UInt64, into out: UnsafeMutableRawPointer, count: Int) throws {
        var done = 0
        while done < count {
            let p = position + UInt64(done)
            let (i, inside) = span(containing: p)
            if inside {
                let s = spans[i]
                let n = Int(min(UInt64(count - done), s.end - p))
                let handle = try fd(s.url)
                var got = 0
                while got < n {
                    let r = pread(handle, out + done + got, n - got, off_t(p - s.start) + off_t(got))
                    if r < 0 && errno == EINTR { continue }
                    guard r > 0 else { throw NCAError.updateNeedsBase(s.url.lastPathComponent) }
                    got += r
                }
                done += n
            } else {
                let next = i + 1 < spans.count ? spans[i + 1].start : UInt64.max
                let n = Int(min(UInt64(count - done), next - p))
                guard UInt64(n) <= Self.maxPadding || unknownAsZero else {
                    throw NCAError.updateNeedsBase(L("unknown base data", "unbekannte Basisdaten"))
                }
                memset(out + done, 0, n)
                done += n
            }
        }
    }

    // MARK: - Aufbau

    /// Baut die Karte für `patched` aus dem RomFS-Ordner des installierten Spiels und prüft sie.
    static func build(patched: PatchedSection, fds: [Int32], patchFS fs: Data, installedRomFS: URL,
                      cancel: CancelFlag, progress: (UInt64, UInt64, String) -> Void) throws -> ReconstructedBase {
        // 1) Dateiliste des gepatchten RomFS (Kopf und Tabellen müssen aus dem Update kommen)
        patched.baseSource = .none
        let jobs: [CopyJob]
        do {
            jobs = try NCAReader.planRomFS(patched: patched, fds: fds, fsHeader: fs, root: "")
        } catch NCAError.baseUnavailable {
            throw NCAError.updateNeedsBase(L("the file table is unchanged", "die Dateitabelle ist unverändert"))
        }

        // 2) Dateien des installierten Spiels
        // relative Pfade direkt vom Enumerator (unabhängig von /var ↔ /private/var)
        var installed: [String: (url: URL, size: UInt64)] = [:]
        let fm = FileManager.default
        let root = installedRomFS.resolvingSymlinksInPath()
        if let walker = fm.enumerator(atPath: root.path) {
            while let relative = walker.nextObject() as? String {
                guard let attributes = walker.fileAttributes,
                      attributes[.type] as? FileAttributeType == .typeRegular else { continue }
                let size = (attributes[.size] as? NSNumber)?.uint64Value ?? 0
                installed["/" + relative] = (root.appendingPathComponent(relative), size)
            }
        }
        guard !installed.isEmpty else { throw NCAError.updateNeedsBase(L("no installed game files", "keine installierten Spieldateien")) }

        // 3) Anker. Stark: Datei ganz aus der Basis, am Stück, gleiche Größe → lag dort unverändert.
        //    Schwach: geänderte Datei mit altem Inhalt → Startposition, die alle ihre Basis-Stücke abdeckt.
        var strong: [Span] = []
        var weak: [Span] = []
        var baseFragments: [(start: UInt64, length: UInt64, path: String, virtual: UInt64)] = []
        var used = Set<String>()
        for job in jobs {
            guard case .patched(_, let virtualStart) = job.source, job.size > 0 else { continue }
            let pieces = patched.fragments(at: virtualStart, count: job.size)
            let fromBase = pieces.filter { !$0.fromPatch }
            guard !fromBase.isEmpty else { continue }
            baseFragments += fromBase.map { ($0.physical, $0.length, job.path, $0.virtual) }
            guard let file = installed[job.path] else { continue }
            let candidates = fromBase.map { $0.physical &- ($0.virtual - virtualStart) }
            let contiguous = Set(candidates).count == 1
            if contiguous, fromBase.count == pieces.count, file.size == job.size {
                strong.append(Span(start: candidates[0], end: candidates[0] + file.size, url: file.url))
                used.insert(job.path)
                continue
            }
            // die Startposition, deren Bereich die meisten (am liebsten alle) Basis-Stücke enthält
            var best: (start: UInt64, inside: Int, votes: Int)?
            for c in Set(candidates) {
                let inside = fromBase.filter { $0.physical >= c && $0.physical + $0.length <= c + file.size }.count
                let votes = candidates.filter { $0 == c }.count
                if inside > 0, best == nil || (inside, votes) > (best!.inside, best!.votes) { best = (c, inside, votes) }
            }
            if let best {
                weak.append(Span(start: best.start, end: best.start + file.size, url: file.url))
                used.insert(job.path)
            }
        }
        var spans = strong
        for w in weak where !spans.contains(where: { $0.start < w.end && w.start < $0.end }) {
            spans.append(w)
        }

        // 3b) Lücken füllen: Basis-Stücke, die noch in keinem Bereich liegen, gehören zu Dateien, die im Update anders
        //     heißen oder fehlen. Eine noch nicht zugeordnete Datei, die in die Lücke passt und das Stück enthält, wird
        //     dort angenommen (die Prüfung gegen die Prüfsummen deckt Fehlgriffe auf).
        var free = installed.filter { !used.contains($0.key) }
        func align(_ v: UInt64) -> UInt64 { (v + 15) & ~15 }
        var changed = true
        while changed {
            changed = false
            let probe = ReconstructedBase(spans: spans)
            let sorted = spans.sorted { $0.start < $1.start }
            for fragment in baseFragments where !probe.covers(fragment.start, fragment.length) {
                let gapStart = align(sorted.last(where: { $0.start <= fragment.start })?.end ?? 0)
                let gapEnd = sorted.first(where: { $0.start > fragment.start })?.start ?? UInt64.max
                guard gapStart <= fragment.start, fragment.start + fragment.length <= gapEnd else { continue }
                let gap = gapEnd == UInt64.max ? UInt64.max : gapEnd - gapStart
                var choice: (key: String, span: Span, score: UInt64)?
                for (key, file) in free where file.size > 0 && align(file.size) <= gap {
                    for position in [gapStart, gapEnd == UInt64.max ? gapStart : (gapEnd - align(file.size)) & ~15] {
                        guard position <= fragment.start, fragment.start + fragment.length <= position + file.size else { continue }
                        // bevorzugt: Datei füllt die Lücke (fast) genau, sonst die mit dem kleinsten Rest
                        let rest = gap == UInt64.max ? UInt64.max - file.size : gap - align(file.size)
                        if choice == nil || rest < choice!.score {
                            choice = (key, Span(start: position, end: position + file.size, url: file.url), rest)
                        }
                    }
                }
                if let choice {
                    spans.append(choice.span)
                    free.removeValue(forKey: choice.key)
                    changed = true
                    break
                }
            }
        }
        let base = ReconstructedBase(spans: spans)

        // 4) Basis-Stücke, die in keiner Datei liegen: Füllbereiche des Originals (Nullen) – aber nur, wenn jeder Block
        //    mit so einem Stück gegen eine Prüfsumme des Updates bestätigt wird. Sonst lieber abbrechen.
        let missing = baseFragments.filter { !base.covers($0.start, $0.length) }
        var paths: [String] = []
        for m in missing where !paths.contains(m.path) && paths.count < 3 { paths.append(m.path) }
        let bytes = missing.reduce(UInt64(0)) { $0 + $1.length }
        let notFound = NCAError.updateNeedsBase(L("\(bytes / 1024) KB of base data not found in the installed game – \(paths.joined(separator: ", "))",
                                                  "\(bytes / 1024) KB Basisdaten nicht im installierten Spiel gefunden – \(paths.joined(separator: ", "))"))
        if !missing.isEmpty {
            NSLog("NSPiOS: update on installed game: \(bytes) bytes of base data outside any file, in \(Set(missing.map(\.path)).sorted()) – read as zeros, every block must verify")
            base.unknownAsZero = true
        }
        patched.baseSource = .files(base)

        // 5) Prüfen gegen die IVFC-Prüfsummen des Updates
        try verify(patched: patched, fds: fds, fs: fs, mustVerify: missing.map { ($0.virtual, $0.length) },
                   failure: notFound, cancel: cancel, progress: progress)
        return base
    }

    /// Jeder Datenblock mit Basis-Anteil, dessen SHA-256 im Update liegt, muss stimmen.
    private static func verify(patched: PatchedSection, fds: [Int32], fs: Data,
                               mustVerify: [(virtual: UInt64, length: UInt64)], failure: Error,
                               cancel: CancelFlag, progress: (UInt64, UInt64, String) -> Void) throws {
        guard fs[3] == 3, String(decoding: fs.bytes(0x8, 4), as: UTF8.self) == "IVFC" else {
            if !mustVerify.isEmpty { throw failure }
            return
        }
        let levels = Int(fs.le32(0x8 + 0xC))
        let dataLevel = min(levels - 2, 5)
        guard dataLevel >= 1 else {
            if !mustVerify.isEmpty { throw failure }
            return
        }
        func level(_ i: Int) -> (offset: UInt64, size: UInt64, block: UInt64) {
            let at = 0x8 + 0x10 + i * 0x18
            return (fs.le64(at), fs.le64(at + 8), UInt64(1) << UInt64(fs.le32(at + 16)))
        }
        let data = level(dataLevel)
        let hashes = level(dataLevel - 1)
        guard data.block >= 0x200, data.block <= 0x100000 else {
            if !mustVerify.isEmpty { throw failure }
            return
        }
        // Blöcke mit geratenen (Null-)Bereichen: müssen bestätigt werden
        var required = IndexSet()
        for piece in mustVerify where piece.length > 0 && piece.virtual >= data.offset {
            let first = (piece.virtual - data.offset) / data.block
            let last = (piece.virtual + piece.length - 1 - data.offset) / data.block
            required.insert(integersIn: Int(first)...Int(last))
        }

        // Blöcke mit Basis-Anteil
        var blocks = IndexSet()
        for piece in patched.fragments(at: data.offset, count: data.size) where !piece.fromPatch {
            let first = (piece.virtual - data.offset) / data.block
            let last = (piece.virtual + piece.length - 1 - data.offset) / data.block
            blocks.insert(integersIn: Int(first)...Int(last))
        }
        let total = UInt64(blocks.count)
        var checked: UInt64 = 0, verified = 0
        let buffer = UnsafeMutableRawPointer.allocate(byteCount: Int(data.block), alignment: 64)
        defer { buffer.deallocate() }

        for k in blocks {
            if cancel.isCancelled { throw PackageError.cancelled }
            checked += 1
            if checked % 64 == 0 { progress(checked, total, L("Checking game data …", "Prüfe Spieldaten …")) }
            let hashAt = hashes.offset + UInt64(k) * 32
            guard hashAt + 32 <= hashes.offset + hashes.size,
                  patched.fragments(at: hashAt, count: 32).allSatisfy(\.fromPatch) else {   // Prüfsumme unverändert: liegt nicht im Update
                if required.contains(k) { throw failure }
                continue
            }
            let expected = try patched.read(fds: fds, at: hashAt, count: 32)
            let blockStart = data.offset + UInt64(k) * data.block
            let length = Int(min(data.block, data.offset + data.size - blockStart))
            try patched.read(fds: fds, at: blockStart, into: buffer, count: length)
            let exact = Data(SHA256.hash(data: UnsafeRawBufferPointer(start: buffer, count: length)))
            var ok = exact == expected
            if !ok, length < Int(data.block) {
                memset(buffer + length, 0, Int(data.block) - length)
                ok = Data(SHA256.hash(data: UnsafeRawBufferPointer(start: buffer, count: Int(data.block)))) == expected
            }
            guard ok else {
                if required.contains(k) { throw failure }
                throw NCAError.updateNeedsBase(L("check failed at block \(k)", "Prüfung bei Block \(k) fehlgeschlagen"))
            }
            verified += 1
        }
        NSLog("NSPiOS: update on installed game: \(verified) of \(blocks.count) blocks checked against the update's hashes")
    }
}

extension PackageReader {
    /// Update auf ein installiertes Spiel anwenden, ohne Basispaket: unveränderte Daten kommen aus dessen RomFS-Ordner.
    /// `source`: Update-Paket (NSP/XCI), eine NCA-Datei oder ein Ordner mit den NCAs (+ .tik) des Updates.
    static func extractUpdate(
        _ source: URL,
        onto installedRomFS: URL,
        to destination: URL,
        keys: KeySet,
        cancel: CancelFlag,
        progress: @escaping (_ done: UInt64, _ total: UInt64, _ currentFile: String) -> Void
    ) throws -> ExtractResult {
        guard keys.headerKey != nil else { throw NCAError.missingKey("header_key") }
        var fds: [Int32] = []
        defer { fds.forEach { close($0) } }
        var ncas: [(name: String, file: Int, offset: UInt64, size: UInt64)] = []
        var tickets: [Data: Data] = [:]

        func openFile(_ url: URL) throws -> Int {
            let fd = open(url.path, O_RDONLY)
            guard fd >= 0 else { throw PackageError.corruptHeader(url.lastPathComponent) }
            _ = fcntl(fd, F_RDAHEAD, 1)
            fds.append(fd)
            return fds.count - 1
        }
        func addTicket(_ data: Data) {
            if let ticket = try? NCAReader.parseTicket(data) { tickets[ticket.rightsID] = ticket.encryptedTitleKey }
        }

        let isDirectory = (try? source.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
        if isDirectory || source.pathExtension.lowercased() == "nca" {
            let found = FS.ncaFiles(in: source)
            for url in found.ncas where url.pathExtension.lowercased() == "nca" {
                let size = UInt64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
                ncas.append((url.lastPathComponent, try openFile(url), 0, size))
            }
            for url in found.tickets {
                if let data = try? Data(contentsOf: url), data.count <= 0x10000 { addTicket(data) }
            }
        } else {
            let info = try inspect(source)
            let file = try openFile(source)
            let entries = info.entries.filter { info.format == .nsp || $0.path.hasPrefix("secure/") }
            for entry in entries where entry.name.lowercased().hasSuffix(".tik") && entry.size <= 0x10000 {
                var data = Data(count: Int(entry.size))
                let got = data.withUnsafeMutableBytes { pread(fds[file], $0.baseAddress, Int(entry.size), off_t(entry.dataOffset)) }
                if got == Int(entry.size) { addTicket(data) }
            }
            for entry in entries where entry.name.lowercased().hasSuffix(".nca") {
                ncas.append((entry.name, file, entry.dataOffset, entry.size))
            }
        }

        var jobs: [CopyJob] = []
        var version: UInt32?
        var programs = 0
        var patchFound = false
        for nca in ncas {
            let fd = fds[nca.file]
            guard let header = try? NCAReader.open(fd: fd, ncaOffset: nca.offset, ncaSize: nca.size, keys: keys, tickets: tickets) else { continue }
            if nca.name.lowercased().hasSuffix(".cnmt.nca") {
                version = version ?? ContentMeta.version(fd: fd, header: header)
                continue
            }
            var planned: [CopyJob] = []
            if header.isProgram {
                guard programs == 0 else { continue }   // ein Programm: das installierte Spiel
                programs += 1
                for (index, fs, section) in header.sections {
                    if fs[2] == 0 {
                        if section.isPatch {
                            patchFound = true
                            let patched = try PatchedSection(baseSource: .none, patch: section, patchFile: nca.file, patchFS: fs, fds: fds)
                            _ = try ReconstructedBase.build(patched: patched, fds: fds, patchFS: fs, installedRomFS: installedRomFS,
                                                            cancel: cancel, progress: progress)
                            planned += try NCAReader.planRomFS(patched: patched, fds: fds, fsHeader: fs, root: "romfs")
                        } else {
                            planned += try NCAReader.planRomFS(fd: fd, section: section, fsHeader: fs, root: "romfs")
                        }
                    } else if fs[2] == 1, index == 0 {
                        planned += try NCAReader.planExeFS(fd: fd, section: section, fsHeader: fs, root: "exefs")
                    }
                }
            } else if header.isControl {
                for (_, fs, section) in header.sections where fs[2] == 0 && !section.isPatch {
                    planned += try NCAReader.planRomFS(fd: fd, section: section, fsHeader: fs, root: "control")
                }
            }
            jobs += planned.map { CopyJob(path: $0.path, size: $0.size, source: $0.source, file: nca.file) }
        }
        guard programs > 0 else {
            throw PackageError.corruptHeader(L("No update program found here. Pick the update NSP/XCI, or a folder that still holds its .nca files.",
                                               "Hier ist kein Update-Programm. Wähle das Update-NSP/XCI oder einen Ordner, der noch seine .nca-Dateien hat."))
        }
        guard patchFound || jobs.contains(where: { $0.path.hasPrefix("romfs/") }) else {
            throw PackageError.corruptHeader(L("This folder no longer holds the update's RomFS patch (.nca). Pick the update NSP/XCI instead.",
                                               "Dieser Ordner enthält den RomFS-Patch des Updates (.nca) nicht mehr. Wähle stattdessen das Update-NSP/XCI."))
        }
        try runJobs(jobs, fds: fds, destination: destination, cancel: cancel, progress: progress)
        if let version {
            try? "\(version)\n".write(to: destination.appendingPathComponent(versionMarker), atomically: true, encoding: .utf8)
        }
        return ExtractResult(fileCount: jobs.count, decryptedCount: programs, ncaCount: ncas.count, warnings: [])
    }
}

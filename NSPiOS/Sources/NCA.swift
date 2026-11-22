import Foundation
import Darwin

/// Eine (entschlüsselbare) Section innerhalb einer NCA.
struct NCASection: Sendable {
    /// Absoluter Offset der NCA in der Container-Datei.
    let ncaOffset: UInt64
    /// Start der Section innerhalb der NCA.
    let offset: UInt64
    let size: UInt64
    /// `true` = AES-CTR, `false` = unverschlüsselt.
    let encrypted: Bool
    let key: Data
    /// Obere 8 Byte des CTR-Zählers.
    let counterHigh: [UInt8]
    /// Update-Patch-Section (AES-CTR-Ex + BKTR): nur zusammen mit dem Basisspiel lesbar.
    var isPatch = false

    /// IV für eine (16-Byte-ausgerichtete) Position innerhalb der NCA.
    func iv(atNCAOffset position: UInt64) -> [UInt8] {
        var block = position >> 4
        var low = [UInt8](repeating: 0, count: 8)
        for i in stride(from: 7, through: 0, by: -1) {
            low[i] = UInt8(block & 0xFF)
            block >>= 8
        }
        return counterHigh + low
    }

    /// Liest entschlüsselte Bytes (für Metadaten wie RomFS-Tabellen).
    func read(fd: Int32, at position: UInt64, count: Int) throws -> Data {
        guard count > 0 else { return Data() }
        guard position &+ UInt64(count) <= size else { throw PackageError.truncated }

        let absolute = offset + position
        let aligned = absolute & ~UInt64(15)
        let delta = Int(absolute - aligned)
        let length = delta + count

        var buffer = Data(count: length)
        let got = buffer.withUnsafeMutableBytes { pread(fd, $0.baseAddress, length, off_t(ncaOffset + aligned)) }
        guard got == length else { throw PackageError.truncated }

        if encrypted {
            buffer = try AESCTR(key: key, iv: iv(atNCAOffset: aligned)).process(buffer)
        }
        return buffer.bytes(delta, count)
    }
}

/// Eine Datei, die beim Entpacken geschrieben wird.
struct CopyJob: Sendable {
    enum Source: Sendable {
        /// Rohdaten ab absolutem Offset in der Container-Datei.
        case raw(UInt64)
        /// Entschlüsselte Daten ab Offset innerhalb einer NCA-Section.
        case section(NCASection, UInt64)
        /// Daten des gepatchten RomFS (Basisspiel + Update) ab virtuellem Offset.
        case patched(PatchedSection, UInt64)
    }

    let path: String
    let size: UInt64
    let source: Source
    /// Pfad des Container-Eintrags (nur bei Rohdaten).
    var origin: String? = nil
    /// Index der Quelldatei (mehrere lose NCA-Dateien).
    var file: Int = 0

    init(path: String, size: UInt64, source: Source, origin: String? = nil, file: Int = 0) {
        self.path = path
        self.size = size
        self.source = source
        self.origin = origin
        self.file = file
    }
}

enum NCAError: LocalizedError {
    case missingKey(String)
    case badHeader
    case missingTicket
    case personalizedTicket
    case compressed
    case compressedSection
    case missingBase(String)

    var errorDescription: String? {
        switch self {
        case .missingKey(let name):
            return L("Key „\(name)“ is missing in your prod.keys.", "Schlüssel „\(name)“ fehlt in deiner prod.keys.")
        case .badHeader:
            return L("Can’t decrypt NCA header (wrong header_key?).", "NCA-Header lässt sich nicht entschlüsseln (falscher header_key?).")
        case .missingTicket:
            return L("Ticket (.tik) for this NCA is missing.", "Ticket (.tik) für diese NCA fehlt.")
        case .personalizedTicket:
            return L("Personalized ticket – not supported.", "Personalisiertes Ticket – nicht unterstützt.")
        case .compressed:
            return L("NCZ (compressed) – RomFS needs .nca.", "NCZ (komprimiert) – RomFS nur aus .nca.")
        case .compressedSection:
            return L("This NCA uses compressed/sparse storage – not supported yet.", "Diese NCA nutzt komprimierten/sparse Speicher – noch nicht unterstützt.")
        case .missingBase(let tid):
            return L("The base game for \(tid) is missing (the update only holds changes).", "Das Basisspiel für \(tid) fehlt (das Update enthält nur Änderungen).")
        }
    }
}

struct NCAHeader {
    let contentType: UInt8
    let titleID: UInt64
    /// (Section-Index, FS-Header, Section)
    let sections: [(index: Int, fsHeader: Data, section: NCASection)]
    var warnings: [String]

    var isProgram: Bool { contentType == 0 }
    var isControl: Bool { contentType == 2 }
    var isData: Bool { contentType == 4 || contentType == 5 }
}

enum NCAReader {
    // MARK: - Tickets

    /// Liest Rights-ID und (common) Titlekey aus einem Ticket.
    static func parseTicket(_ ticket: Data) throws -> (rightsID: Data, encryptedTitleKey: Data) {
        guard ticket.count >= 8 else { throw PackageError.truncated }
        let signatureBlock: Int
        switch ticket.le32(0) {
        case 0x10000, 0x10003: signatureBlock = 0x200 + 0x3C
        case 0x10001, 0x10004: signatureBlock = 0x100 + 0x3C
        case 0x10002, 0x10005: signatureBlock = 0x3C + 0x40
        default: throw PackageError.corruptHeader("unbekannter Ticket-Signaturtyp")
        }
        let base = 4 + signatureBlock
        guard ticket.count >= base + 0x170 else { throw PackageError.truncated }
        if ticket[ticket.startIndex + base + 0x141] != 0 { throw NCAError.personalizedTicket }
        return (ticket.bytes(base + 0x160, 16), ticket.bytes(base + 0x40, 16))
    }

    // MARK: - Header

    static func open(fd: Int32, ncaOffset: UInt64, ncaSize: UInt64, keys: KeySet, tickets: [Data: Data]) throws -> NCAHeader {
        guard let headerKey = keys.headerKey else { throw NCAError.missingKey("header_key") }
        guard ncaSize >= 0xC00 else { throw PackageError.truncated }

        var raw = Data(count: 0xC00)
        let got = raw.withUnsafeMutableBytes { pread(fd, $0.baseAddress, 0xC00, off_t(ncaOffset)) }
        guard got == 0xC00 else { throw PackageError.truncated }

        var header = try AES.xtsDecryptNintendo(raw, key: headerKey)
        let magic = String(decoding: header.bytes(0x200, 4), as: UTF8.self)
        if magic == "NCA2" {
            // NCA2: jeder FS-Header ist einzeln mit Sektor 0 verschlüsselt
            for i in 0..<4 {
                let start = 0x400 + i * 0x200
                let fs = try AES.xtsDecryptNintendo(raw.bytes(start, 0x200), key: headerKey)
                header.replaceSubrange(start..<(start + 0x200), with: fs)
            }
        } else if magic != "NCA3" {
            throw NCAError.badHeader
        }

        let contentType = header[0x205]
        let keyAreaIndex = header[0x207]
        let generation = Int(max(header[0x206], header[0x220]))
        let revision = generation > 0 ? generation - 1 : 0
        let titleID = header.le64(0x210)
        let rightsID = header.bytes(0x230, 16)

        // Inhalts-Schlüssel bestimmen
        let contentKey: Data
        if rightsID.allSatisfy({ $0 == 0 }) {
            let kak = keys.keyAreaKey(index: keyAreaIndex, revision: revision)
            guard let kakKey = kak.key else { throw NCAError.missingKey(kak.name) }
            let keyArea = try AES.ecb(header.bytes(0x300, 0x40), key: kakKey, decrypt: true)
            contentKey = keyArea.bytes(0x20, 16)
        } else {
            guard let encrypted = tickets[rightsID] else { throw NCAError.missingTicket }
            let kek = keys.titleKek(revision: revision)
            guard let kekKey = kek.key else { throw NCAError.missingKey(kek.name) }
            contentKey = try AES.ecb(encrypted, key: kekKey, decrypt: true)
        }

        var sections: [(index: Int, fsHeader: Data, section: NCASection)] = []
        var warnings: [String] = []
        for i in 0..<4 {
            let start = UInt64(header.le32(0x240 + i * 0x10)) * 0x200
            let end = UInt64(header.le32(0x240 + i * 0x10 + 4)) * 0x200
            guard end > start, end <= ncaSize else { continue }

            let fs = header.bytes(0x400 + i * 0x200, 0x200)
            let encryption = fs[0x4]
            let counterHigh = (0..<8).map { fs[0x140 + 7 - $0] }

            let encrypted: Bool
            var isPatch = false
            switch encryption {
            case 1: encrypted = false
            case 3: encrypted = true
            case 4:
                encrypted = true
                isPatch = true
            default:
                warnings.append(L("Section \(i): encryption type \(encryption) not supported – skipped.", "Section \(i): Verschlüsselungstyp \(encryption) nicht unterstützt – übersprungen."))
                continue
            }

            sections.append((i, fs, NCASection(
                ncaOffset: ncaOffset,
                offset: start,
                size: end - start,
                encrypted: encrypted,
                key: contentKey,
                counterHigh: counterHigh,
                isPatch: isPatch
            )))
        }
        return NCAHeader(contentType: contentType, titleID: titleID, sections: sections, warnings: warnings)
    }

    // MARK: - RomFS

    static func planRomFS(fd: Int32, section: NCASection, fsHeader fs: Data, root: String) throws -> [CopyJob] {
        try planRomFS(fsHeader: fs, root: root,
                      read: { try section.read(fd: fd, at: $0, count: $1) },
                      source: { .section(section, $0) })
    }

    /// Gepatchtes RomFS (Basisspiel + Update): Tabellen und Dateien kommen aus dem zusammengesetzten Strom.
    static func planRomFS(patched: PatchedSection, fds: [Int32], fsHeader fs: Data, root: String) throws -> [CopyJob] {
        try planRomFS(fsHeader: fs, root: root,
                      read: { try patched.read(fds: fds, at: $0, count: $1) },
                      source: { .patched(patched, $0) })
    }

    static func checkPlainStorage(_ fs: Data) throws {
        // Sparse (0x148) oder Kompression (0x178): eigene Bucket-Tree-Schichten, die hier fehlen
        // wie LibHac: Sparse-Schicht bei Generation ≠ 0, Kompression bei Tabelle mit Offset und Größe
        let sparseGeneration = fs.count >= 0x172 ? UInt16(fs[fs.startIndex + 0x170]) | UInt16(fs[fs.startIndex + 0x171]) << 8 : 0
        if fs.count >= 0x1A0, sparseGeneration != 0 || (fs.le64(0x178) != 0 && fs.le64(0x180) != 0) {
            throw NCAError.compressedSection
        }
    }

    private static func planRomFS(fsHeader fs: Data, root: String,
                                  read: (UInt64, Int) throws -> Data,
                                  source: (UInt64) -> CopyJob.Source) throws -> [CopyJob] {
        try checkPlainStorage(fs)
        // Position der RomFS-Daten in der Section
        let romfsOffset: UInt64
        switch fs[3] {
        case 3: // HierarchicalIntegrity (IVFC)
            guard String(decoding: fs.bytes(0x8, 4), as: UTF8.self) == "IVFC" else {
                throw PackageError.corruptHeader("IVFC fehlt")
            }
            let levels = Int(fs.le32(0x8 + 0xC))
            let last = min(levels - 2, 5)
            guard last >= 0 else { throw PackageError.corruptHeader("IVFC-Level") }
            romfsOffset = fs.le64(0x8 + 0x10 + last * 0x18)
        case 2: // HierarchicalSha256
            let layers = Int(fs.le32(0x2C))
            guard (1...5).contains(layers) else { throw PackageError.corruptHeader("Hash-Layer") }
            romfsOffset = fs.le64(0x30 + (layers - 1) * 0x10)
        default:
            throw PackageError.corruptHeader("unbekannter RomFS-Hashtyp \(fs[3])")
        }

        let header = try read(romfsOffset, 0x50)
        let dirMetaOffset = header.le64(24), dirMetaSize = header.le64(32)
        let fileMetaOffset = header.le64(56), fileMetaSize = header.le64(64)
        let fileDataOffset = header.le64(72)
        let limit: UInt64 = 256 * 1024 * 1024
        guard dirMetaSize < limit, fileMetaSize < limit else { throw PackageError.corruptHeader("RomFS-Tabellen zu groß") }

        let dirs = try read(romfsOffset + dirMetaOffset, Int(dirMetaSize))
        let files = try read(romfsOffset + fileMetaOffset, Int(fileMetaSize))
        let none: UInt32 = 0xFFFF_FFFF

        func name(in table: Data, at offset: Int, lengthAt: Int) -> String? {
            guard offset + lengthAt + 4 <= table.count else { return nil }
            let length = Int(table.le32(offset + lengthAt))
            guard offset + lengthAt + 4 + length <= table.count else { return nil }
            return String(decoding: table.bytes(offset + lengthAt + 4, length), as: UTF8.self)
        }

        var jobs: [CopyJob] = []
        var visited = Set<UInt32>()
        var stack: [(UInt32, String)] = [(0, root)]

        while let item = stack.popLast() {
            let (dirOffset, path) = item
            guard visited.insert(dirOffset).inserted, Int(dirOffset) + 0x18 <= dirs.count else { continue }
            let d = Int(dirOffset)

            // Dateien dieses Ordners
            var fileOffset = dirs.le32(d + 12)
            var guardCount = 0
            while fileOffset != none, Int(fileOffset) + 0x20 <= files.count, guardCount < 1_000_000 {
                let f = Int(fileOffset)
                let dataOffset = files.le64(f + 8)
                let size = files.le64(f + 16)
                if let fileName = name(in: files, at: f, lengthAt: 0x1C) {
                    jobs.append(CopyJob(
                        path: path + "/" + sanitizeComponent(fileName),
                        size: size,
                        source: source(romfsOffset + fileDataOffset + dataOffset)
                    ))
                }
                fileOffset = files.le32(f + 4)
                guardCount += 1
            }

            // Unterordner
            var childOffset = dirs.le32(d + 8)
            guardCount = 0
            while childOffset != none, Int(childOffset) + 0x18 <= dirs.count, guardCount < 1_000_000 {
                let c = Int(childOffset)
                if let dirName = name(in: dirs, at: c, lengthAt: 0x14) {
                    stack.append((childOffset, path + "/" + sanitizeComponent(dirName)))
                }
                childOffset = dirs.le32(c + 4)
                guardCount += 1
            }
        }
        return jobs
    }

    // MARK: - ExeFS

    static func planExeFS(fd: Int32, section: NCASection, fsHeader fs: Data, root: String) throws -> [CopyJob] {
        guard fs[3] == 2 else { throw PackageError.corruptHeader("ExeFS-Hashtyp") }
        let layers = Int(fs.le32(0x2C))
        guard (1...5).contains(layers) else { throw PackageError.corruptHeader("Hash-Layer") }
        let pfsOffset = fs.le64(0x30 + (layers - 1) * 0x10)

        let head = try section.read(fd: fd, at: pfsOffset, count: 16)
        guard String(decoding: head.bytes(0, 4), as: UTF8.self) == "PFS0" else { throw PackageError.invalidMagic }
        let count = Int(head.le32(4))
        let stringSize = Int(head.le32(8))
        guard count <= 4096, stringSize <= 1024 * 1024 else { throw PackageError.corruptHeader("ExeFS") }

        let table = try section.read(fd: fd, at: pfsOffset + 16, count: count * 0x18 + stringSize)
        let strings = table.bytes(count * 0x18, stringSize)
        let dataStart = pfsOffset + UInt64(16 + count * 0x18 + stringSize)

        return (0..<count).map { i in
            let at = i * 0x18
            let nameOffset = Int(table.le32(at + 16))
            let nameBytes = nameOffset < strings.count ? strings.bytes(nameOffset, strings.count - nameOffset).prefix { $0 != 0 } : Data()
            let fileName = String(decoding: nameBytes, as: UTF8.self)
            return CopyJob(
                path: root + "/" + sanitizeComponent(fileName.isEmpty ? "datei_\(i)" : fileName),
                size: table.le64(at + 8),
                source: .section(section, dataStart + table.le64(at))
            )
        }
    }

    static func sanitizeComponent(_ name: String) -> String {
        var cleaned = name
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty || cleaned == "." || cleaned == ".." { cleaned = "_" }
        return cleaned
    }
}

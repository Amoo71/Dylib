import Foundation
import Darwin

/// Gepatchtes RomFS eines Updates: die Update-NCA enthält nur Änderungen (BKTR), der Rest kommt aus dem Basisspiel.
///
/// Aufbau (wie LibHac/hactool):
/// - Indirect-Tabelle (Bucket-Tree „BKTR“): virtueller Offset → (Basisspiel | Update, physischer Offset)
/// - AES-CTR-Ex-Tabelle: physischer Offset im Update → Generation, die in den Zähler (Bytes 4–7) eingesetzt wird
/// Beide Tabellen liegen am Ende der Update-Section und sind mit dem normalen Section-Zähler verschlüsselt.
final class PatchedSection: @unchecked Sendable {
    struct Relocation {
        let virtual: UInt64
        let physical: UInt64
        let fromPatch: Bool
    }

    struct Subsection {
        let offset: UInt64
        let plain: Bool
        let generation: UInt32
    }

    let base: NCASection
    let baseFile: Int
    let patch: NCASection
    let patchFile: Int
    let size: UInt64
    private let relocations: [Relocation]
    private let subsections: [Subsection]

    private static let nodeSize = 0x4000

    init(base: NCASection, baseFile: Int, patch: NCASection, patchFile: Int, patchFS fs: Data, fds: [Int32]) throws {
        self.base = base
        self.baseFile = baseFile
        self.patch = patch
        self.patchFile = patchFile

        let fd = fds[patchFile]
        // PatchInfo im FS-Header: 0x100 Indirect {Offset, Size, Header[0x10]}, 0x120 AesCtrEx {Offset, Size, Header[0x10]}
        func table(_ at: Int) throws -> (offset: UInt64, size: UInt64, count: Int) {
            guard String(decoding: fs.bytes(at + 0x10, 4), as: UTF8.self) == "BKTR" else {
                throw PackageError.corruptHeader("BKTR")
            }
            return (fs.le64(at), fs.le64(at + 8), Int(Int32(bitPattern: fs.le32(at + 0x18))))
        }
        let indirect = try table(0x100)
        let ctrEx = try table(0x120)

        // Tabellen mit dem normalen Zähler der Update-Section lesen
        let plainPatch = NCASection(ncaOffset: patch.ncaOffset, offset: patch.offset, size: patch.size,
                                    encrypted: true, key: patch.key, counterHigh: patch.counterHigh)
        let indirectData = try plainPatch.read(fd: fd, at: indirect.offset, count: Int(indirect.size))
        let ctrExData = try plainPatch.read(fd: fd, at: ctrEx.offset, count: Int(ctrEx.size))

        var relocations: [Relocation] = []
        let indirectEnd = try Self.entries(indirectData, entrySize: 0x14, count: indirect.count) { d, at in
            relocations.append(Relocation(virtual: d.le64(at), physical: d.le64(at + 8), fromPatch: d.le32(at + 16) != 0))
        }
        var subsections: [Subsection] = []
        _ = try Self.entries(ctrExData, entrySize: 0x10, count: ctrEx.count) { d, at in
            subsections.append(Subsection(offset: d.le64(at), plain: d[d.startIndex + at + 8] == 1, generation: d.le32(at + 12)))
        }
        guard !relocations.isEmpty else { throw PackageError.corruptHeader("BKTR leer") }
        self.relocations = relocations.sorted { $0.virtual < $1.virtual }
        self.subsections = subsections.sorted { $0.offset < $1.offset }
        self.size = indirectEnd
    }

    /// Liest alle Einträge eines Bucket-Trees (Knotengröße 0x4000). Gibt das Ende des abgedeckten Bereichs zurück.
    private static func entries(_ data: Data, entrySize: Int, count: Int, _ each: (Data, Int) -> Void) throws -> UInt64 {
        guard count > 0 else { return 0 }
        let perSet = (nodeSize - 0x10) / entrySize
        let offsetsPerNode = (nodeSize - 0x10) / 8
        let setCount = (count + perSet - 1) / perSet
        let l2Nodes = setCount <= offsetsPerNode ? 0 : (setCount + offsetsPerNode - 1) / offsetsPerNode
        let entryStorage = nodeSize * (1 + l2Nodes)
        var end: UInt64 = 0
        var seen = 0
        for set in 0..<setCount {
            let node = entryStorage + set * nodeSize
            guard node + 0x10 <= data.count else { throw PackageError.corruptHeader("BKTR-Tabelle") }
            let n = Int(Int32(bitPattern: data.le32(node + 4)))
            end = data.le64(node + 8)
            guard n >= 0, n <= perSet, node + 0x10 + n * entrySize <= data.count else {
                throw PackageError.corruptHeader("BKTR-Knoten")
            }
            for i in 0..<n {
                each(data, node + 0x10 + i * entrySize)
            }
            seen += n
        }
        guard seen == count else { throw PackageError.corruptHeader("BKTR-Anzahl") }
        return end
    }

    private func lastIndex<T>(_ array: [T], atOrBelow value: UInt64, key: (T) -> UInt64) -> Int {
        var lo = 0, hi = array.count - 1, found = 0
        while lo <= hi {
            let mid = (lo + hi) / 2
            if key(array[mid]) <= value { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return found
    }

    /// Kopiert `count` Bytes ab virtuellem Offset nach `out`.
    func read(fds: [Int32], at position: UInt64, into out: UnsafeMutableRawPointer, count: Int) throws {
        guard position &+ UInt64(count) <= size else { throw PackageError.truncated }
        var done = 0
        while done < count {
            let v = position + UInt64(done)
            let i = lastIndex(relocations, atOrBelow: v) { $0.virtual }
            let entry = relocations[i]
            let segmentEnd = i + 1 < relocations.count ? relocations[i + 1].virtual : size
            let chunk = Int(min(UInt64(count - done), segmentEnd - v))
            guard chunk > 0 else { throw PackageError.corruptHeader("BKTR-Lücke") }
            let physical = entry.physical + (v - entry.virtual)
            if entry.fromPatch {
                try readPatch(fd: fds[patchFile], at: physical, into: out + done, count: chunk)
            } else {
                let data = try base.read(fd: fds[baseFile], at: physical, count: chunk)
                data.withUnsafeBytes { out.advanced(by: done).copyMemory(from: $0.baseAddress!, byteCount: chunk) }
            }
            done += chunk
        }
    }

    func read(fds: [Int32], at position: UInt64, count: Int) throws -> Data {
        guard count > 0 else { return Data() }
        var data = Data(count: count)
        try data.withUnsafeMutableBytes { try read(fds: fds, at: position, into: $0.baseAddress!, count: count) }
        return data
    }

    /// Update-Daten: je Teilbereich eigener Zähler (Generation in Bytes 4–7).
    private func readPatch(fd: Int32, at position: UInt64, into out: UnsafeMutableRawPointer, count: Int) throws {
        var done = 0
        while done < count {
            let p = position + UInt64(done)
            let i = lastIndex(subsections, atOrBelow: p) { $0.offset }
            let sub = subsections.isEmpty ? Subsection(offset: 0, plain: false, generation: 0) : subsections[i]
            let end = i + 1 < subsections.count ? subsections[i + 1].offset : UInt64.max
            let chunk = Int(min(UInt64(count - done), end - p))

            let absolute = patch.offset + p
            let aligned = absolute & ~UInt64(15)
            let delta = Int(absolute - aligned)
            let length = delta + chunk
            var buffer = Data(count: length)
            let got = buffer.withUnsafeMutableBytes { pread(fd, $0.baseAddress, length, off_t(patch.ncaOffset + aligned)) }
            guard got == length else { throw PackageError.truncated }
            if !sub.plain {
                var iv = patch.iv(atNCAOffset: aligned)
                let g = sub.generation
                iv[4] = UInt8(g >> 24 & 0xFF); iv[5] = UInt8(g >> 16 & 0xFF)
                iv[6] = UInt8(g >> 8 & 0xFF); iv[7] = UInt8(g & 0xFF)
                buffer = try AESCTR(key: patch.key, iv: iv).process(buffer)
            }
            buffer.withUnsafeBytes { out.advanced(by: done).copyMemory(from: $0.baseAddress! + delta, byteCount: chunk) }
            done += chunk
        }
    }
}

/// Titelversion eines Updates aus seiner CNMT-NCA (PFS0 mit <Typ>_<ID>.cnmt: Titel-ID @0, Version @8).
enum ContentMeta {
    struct Info: Sendable {
        let titleID: UInt64
        let version: UInt32
        /// 0x80 Spiel, 0x81 Update (Patch), 0x82 DLC
        let type: UInt8
        var isUpdate: Bool { type == 0x81 }
        /// Titel-ID des Basisspiels (Update: ID − 0x800)
        var baseTitleID: UInt64 { type == 0x81 ? titleID & ~0x800 : titleID }
    }

    static func info(fd: Int32, header: NCAHeader) -> Info? {
        for (_, fs, section) in header.sections where fs[2] == 1 || fs[3] == 2 {
            guard let jobs = try? NCAReader.planExeFS(fd: fd, section: section, fsHeader: fs, root: "cnmt"),
                  let cnmt = jobs.first(where: { $0.path.hasSuffix(".cnmt") }),
                  case .section(let s, let offset) = cnmt.source,
                  let data = try? s.read(fd: fd, at: offset, count: 0x10) else { continue }
            return Info(titleID: data.le64(0), version: data.le32(8), type: data[data.startIndex + 0xC])
        }
        return nil
    }

    static func version(fd: Int32, header: NCAHeader) -> UInt32? {
        info(fd: fd, header: header)?.version
    }
}

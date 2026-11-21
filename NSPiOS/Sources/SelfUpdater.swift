import Foundation
import Compression

enum UnzipError: LocalizedError {
    case invalidArchive
    case unsupportedMethod(UInt16)
    case corrupt(String)

    var errorDescription: String? {
        switch self {
        case .invalidArchive: return "Ungültige IPA (ZIP)."
        case .unsupportedMethod(let m): return "ZIP-Kompression \(m) wird nicht unterstützt."
        case .corrupt(let name): return "Beschädigter Eintrag: \(name)"
        }
    }
}

/// Minimaler ZIP-Entpacker (stored + deflate) für IPAs.
enum Unzip {
    static func extract(_ archive: URL, to destination: URL) throws {
        let zip = try Data(contentsOf: archive, options: .alwaysMapped)
        guard zip.count >= 22 else { throw UnzipError.invalidArchive }

        // End of Central Directory suchen
        var eocd = -1
        var i = zip.count - 22
        let lowest = max(0, zip.count - 0xFFFF - 22)
        while i >= lowest {
            if zip.le32(i) == 0x0605_4B50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw UnzipError.invalidArchive }

        let count = Int(zip.le16(eocd + 10))
        var p = Int(zip.le32(eocd + 16))
        let fm = FileManager.default
        try fm.createDirectory(at: destination, withIntermediateDirectories: true)

        for _ in 0..<count {
            guard p + 46 <= zip.count, zip.le32(p) == 0x0201_4B50 else { throw UnzipError.invalidArchive }
            let method = zip.le16(p + 10)
            let compressedSize = Int(zip.le32(p + 20))
            let size = Int(zip.le32(p + 24))
            let nameLength = Int(zip.le16(p + 28))
            let extraLength = Int(zip.le16(p + 30))
            let commentLength = Int(zip.le16(p + 32))
            let mode = UInt16(zip.le32(p + 38) >> 16)
            let localOffset = Int(zip.le32(p + 42))
            let name = String(decoding: zip.bytes(p + 46, nameLength), as: UTF8.self)
            p += 46 + nameLength + extraLength + commentLength

            // Pfad-Sicherheit
            let parts = name.split(separator: "/")
            guard !name.hasPrefix("/"), !parts.contains("..") else { throw UnzipError.corrupt(name) }
            let target = destination.appendingPathComponent(name)

            if name.hasSuffix("/") {
                try fm.createDirectory(at: target, withIntermediateDirectories: true)
                continue
            }
            // Symlinks überspringen
            if mode & 0o170000 == 0o120000 { continue }

            guard localOffset + 30 <= zip.count, zip.le32(localOffset) == 0x0403_4B50 else { throw UnzipError.corrupt(name) }
            let start = localOffset + 30 + Int(zip.le16(localOffset + 26)) + Int(zip.le16(localOffset + 28))
            guard start + compressedSize <= zip.count else { throw UnzipError.corrupt(name) }
            let compressed = zip.bytes(start, compressedSize)

            let content: Data
            switch method {
            case 0: content = compressed
            case 8: content = try inflate(compressed, expectedSize: size, name: name)
            default: throw UnzipError.unsupportedMethod(method)
            }

            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try content.write(to: target)
            if mode & 0o777 != 0 {
                try? fm.setAttributes([.posixPermissions: Int(mode & 0o777)], ofItemAtPath: target.path)
            }
        }
    }

    private static func inflate(_ input: Data, expectedSize: Int, name: String) throws -> Data {
        guard expectedSize > 0 else { return Data() }
        var output = Data(count: expectedSize)
        let written = output.withUnsafeMutableBytes { out in
            input.withUnsafeBytes { inp in
                compression_decode_buffer(
                    out.bindMemory(to: UInt8.self).baseAddress!, expectedSize,
                    inp.bindMemory(to: UInt8.self).baseAddress!, input.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        guard written == expectedSize else { throw UnzipError.corrupt(name) }
        return output
    }
}

enum SelfUpdateError: LocalizedError {
    case notWritable
    case noAppInIPA

    var errorDescription: String? {
        switch self {
        case .notWritable: return "Das App-Bundle ist schreibgeschützt – Selbst-Update nur in LiveContainer möglich."
        case .noAppInIPA: return "In der IPA wurde keine App gefunden."
        }
    }
}

/// Ersetzt die eigenen App-Dateien (funktioniert, wenn das Bundle beschreibbar ist – z. B. in LiveContainer).
enum SelfUpdater {
    static var bundleURL: URL { Bundle.main.bundleURL }

    static var isSupported: Bool {
        FileManager.default.isWritableFile(atPath: bundleURL.path)
            && FileManager.default.isWritableFile(atPath: bundleURL.appendingPathComponent("Info.plist").path)
    }

    static var isLiveContainer: Bool {
        bundleURL.path.contains("/Documents/Applications/") || NSClassFromString("LCSharedUtils") != nil
    }

    static func apply(ipa: URL) throws {
        guard isSupported else { throw SelfUpdateError.notWritable }
        let fm = FileManager.default
        let staging = fm.temporaryDirectory.appendingPathComponent("NSPiOS-update-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: staging) }

        try Unzip.extract(ipa, to: staging)
        let payload = staging.appendingPathComponent("Payload", isDirectory: true)
        guard let newApp = try fm.contentsOfDirectory(at: payload, includingPropertiesForKeys: nil)
            .first(where: { $0.pathExtension == "app" }) else { throw SelfUpdateError.noAppInIPA }

        let target = bundleURL
        guard let enumerator = fm.enumerator(at: newApp, includingPropertiesForKeys: [.isDirectoryKey]) else {
            throw SelfUpdateError.noAppInIPA
        }

        for case let file as URL in enumerator {
            let relative = String(file.path.dropFirst(newApp.path.count + 1))
            let destination = target.appendingPathComponent(relative)
            if (try? file.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                try fm.createDirectory(at: destination, withIntermediateDirectories: true)
                continue
            }
            if relative == "Info.plist" {
                try mergeInfoPlist(new: file, into: destination)
            } else {
                try replaceAtomically(destination, with: file)
            }
        }

        // LiveContainer-Metadaten so anpassen, dass es die neue Binary beim nächsten Start neu vorbereitet
        let lcInfo = target.appendingPathComponent("LCAppInfo.plist")
        if fm.fileExists(atPath: lcInfo.path),
           let data = try? Data(contentsOf: lcInfo),
           var dict = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] {
            for key in dict.keys where isPreparationKey(key) { dict.removeValue(forKey: key) }
            if let out = try? PropertyListSerialization.data(fromPropertyList: dict, format: .xml, options: 0) {
                try? out.write(to: lcInfo, options: .atomic)
            }
        }
    }

    /// Neue Datei per atomarem rename über die alte legen (laufende Binary bleibt gültig).
    private static func replaceAtomically(_ destination: URL, with source: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temp = destination.deletingLastPathComponent()
            .appendingPathComponent(".\(destination.lastPathComponent).nspios-new")
        try? fm.removeItem(at: temp)
        try fm.copyItem(at: source, to: temp)
        if rename(temp.path, destination.path) != 0 {
            let code = errno
            try? fm.removeItem(at: temp)
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(code))
        }
    }

    /// Neue Info.plist übernehmen, aber Einträge von LiveContainer (LC…) und die Bundle-ID behalten.
    private static func mergeInfoPlist(new source: URL, into destination: URL) throws {
        let newData = try Data(contentsOf: source)
        guard var merged = try PropertyListSerialization.propertyList(from: newData, format: nil) as? [String: Any] else {
            try replaceAtomically(destination, with: source)
            return
        }
        if let oldData = try? Data(contentsOf: destination),
           let old = try? PropertyListSerialization.propertyList(from: oldData, format: nil) as? [String: Any] {
            for (key, value) in old where key.hasPrefix("LC") && !isPreparationKey(key) {
                merged[key] = value
            }
            if let bundleID = old["CFBundleIdentifier"] { merged["CFBundleIdentifier"] = bundleID }
        }
        let data = try PropertyListSerialization.data(fromPropertyList: merged, format: .binary, options: 0)
        let temp = destination.deletingLastPathComponent().appendingPathComponent(".Info.plist.nspios-new")
        try data.write(to: temp, options: .atomic)
        if rename(temp.path, destination.path) != 0 {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
    }

    /// Schlüssel, die LiveContainer beim Vorbereiten/Signieren setzt → entfernen erzwingt Neu-Vorbereitung.
    private static func isPreparationKey(_ key: String) -> Bool {
        key == "LCPatchRevision" || key.contains("Sign")
    }

    /// Beendet die App, damit die neue Version beim nächsten Öffnen startet.
    static func restart() -> Never {
        exit(0)
    }
}

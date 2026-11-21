import Foundation

/// Schlüssel aus einer prod.keys-Datei (Format: `name = HEX`).
struct KeySet: Sendable {
    let keys: [String: Data]

    var count: Int { keys.count }

    var headerKey: Data? {
        guard let key = keys["header_key"], key.count == 32 else { return nil }
        return key
    }

    func keyAreaKey(index: UInt8, revision: Int) -> (name: String, key: Data?) {
        let kinds = ["application", "ocean", "system"]
        let kind = kinds[Int(min(index, 2))]
        let name = "key_area_key_\(kind)_" + String(format: "%02x", revision)
        return (name, keys[name].flatMap { $0.count == 16 ? $0 : nil })
    }

    func titleKek(revision: Int) -> (name: String, key: Data?) {
        let name = "titlekek_" + String(format: "%02x", revision)
        return (name, keys[name].flatMap { $0.count == 16 ? $0 : nil })
    }

    static func parse(_ text: String) -> KeySet {
        var dict: [String: Data] = [:]
        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty, !line.hasPrefix(";"), !line.hasPrefix("#"),
                  let separator = line.firstIndex(where: { $0 == "=" || $0 == "," })
            else { continue }
            let name = line[..<separator].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: separator)...].trimmingCharacters(in: .whitespaces)
            if let data = Data(hex: value) { dict[name] = data }
        }
        return KeySet(keys: dict)
    }
}

enum KeyStoreError: LocalizedError {
    case noHeaderKey

    var errorDescription: String? {
        L("„header_key“ is missing. Use a complete prod.keys from your own console.", "„header_key“ fehlt. Nutze eine vollständige prod.keys deiner eigenen Konsole.")
    }
}

/// Speichert die prod.keys privat im App-Container (nicht im sichtbaren Dateien-Ordner).
enum KeyStore {
    static var url: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("Keys", isDirectory: true).appendingPathComponent("prod.keys")
    }

    static func load() -> KeySet? {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let set = KeySet.parse(text)
        return set.count > 0 ? set : nil
    }

    static func install(from source: URL) throws -> KeySet {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }

        var text = ""
        var readError: Error?
        var coordinatorError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinatorError) { url in
            do { text = try String(contentsOf: url, encoding: .utf8) } catch { readError = error }
        }
        if let readError { throw readError }
        if let coordinatorError { throw coordinatorError }

        let set = KeySet.parse(text)
        guard set.headerKey != nil else { throw KeyStoreError.noHeaderKey }

        let dir = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try text.write(to: url, atomically: true, encoding: .utf8)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        return set
    }

    static func remove() {
        try? FileManager.default.removeItem(at: url)
    }
}

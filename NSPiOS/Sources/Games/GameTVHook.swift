import Foundation

/// Legt NSPiOSTV.framework in eine Spiel-App und lässt die Shell es mitladen (LC_LOAD_WEAK_DYLIB im Programm).
/// „Weak“: fehlt die Bibliothek, startet das Spiel trotzdem. LiveContainer signiert die App danach ohnehin neu.
enum GameTVHook {
    static let frameworkName = "NSPiOSTV.framework"
    static let loadPath = "@loader_path/Frameworks/NSPiOSTV.framework/NSPiOSTV"

    static var bundled: URL? {
        guard let frameworks = Bundle.main.privateFrameworksURL else { return nil }
        let url = frameworks.appendingPathComponent(frameworkName, isDirectory: true)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Kopiert die Bibliothek in die App und trägt sie ins Hauptprogramm ein. Fehler sind nicht fatal (nur kein TV-Schwarz).
    @discardableResult
    static func install(into app: URL) -> Bool {
        guard let source = bundled else { return false }
        let fm = FileManager.default
        let frameworks = app.appendingPathComponent("Frameworks", isDirectory: true)
        let target = frameworks.appendingPathComponent(frameworkName, isDirectory: true)
        do {
            try fm.createDirectory(at: frameworks, withIntermediateDirectories: true)
            if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
            try fm.copyItem(at: source, to: target)
            // Eigene Signatur entfernen: LiveContainer signiert die ganze App neu
            try? fm.removeItem(at: target.appendingPathComponent("_CodeSignature"))

            let info = NSDictionary(contentsOf: app.appendingPathComponent("Info.plist"))
            guard let exe = info?["CFBundleExecutable"] as? String else { return false }
            return try addWeakDylib(to: app.appendingPathComponent(exe), path: loadPath)
        } catch {
            NSLog("NSPiOS: TV hook not installed: \(error.localizedDescription)")
            return false
        }
    }

    /// Fügt LC_LOAD_WEAK_DYLIB in ein dünnes arm64-Mach-O ein, wenn im Header Platz ist (Lücke vor dem ersten Abschnitt).
    static func addWeakDylib(to binary: URL, path: String) throws -> Bool {
        var data = try Data(contentsOf: binary)
        guard data.count > 32, data.le32(0) == 0xFEED_FACF else { return false }   // nur 64-Bit, nicht fett
        let ncmds = Int(data.le32(16))
        let sizeofcmds = Int(data.le32(20))
        let headerEnd = 32 + sizeofcmds
        guard headerEnd <= data.count else { return false }

        var offset = 32
        var firstSection = Int.max
        for _ in 0..<ncmds {
            guard offset + 8 <= headerEnd else { return false }
            let cmd = data.le32(offset)
            let size = Int(data.le32(offset + 4))
            guard size >= 8 else { return false }
            if cmd == 0x8000_0018 || cmd == 0xC {   // LC_LOAD_WEAK_DYLIB / LC_LOAD_DYLIB
                let nameOffset = Int(data.le32(offset + 8))
                if nameOffset < size {
                    let raw = data.bytes(offset + nameOffset, size - nameOffset).prefix { $0 != 0 }
                    if String(decoding: raw, as: UTF8.self) == path { return true }   // schon eingetragen
                }
            }
            if cmd == 0x19 {   // LC_SEGMENT_64: Dateioffsets der Abschnitte
                let nsects = Int(data.le32(offset + 64))
                for s in 0..<nsects {
                    let sectOffset = Int(data.le32(offset + 72 + s * 80 + 48))
                    if sectOffset > 0 { firstSection = min(firstSection, sectOffset) }
                }
            }
            offset += size
        }

        let pathBytes = Array(path.utf8) + [0]
        let cmdSize = (24 + pathBytes.count + 7) & ~7
        guard firstSection != .max, headerEnd + cmdSize <= firstSection,
              data[headerEnd..<(headerEnd + cmdSize)].allSatisfy({ $0 == 0 }) else { return false }

        var command = Data(count: cmdSize)
        command.putLE32(0x8000_0018, at: 0)      // LC_LOAD_WEAK_DYLIB
        command.putLE32(UInt32(cmdSize), at: 4)
        command.putLE32(24, at: 8)               // Name direkt nach dem dylib-Eintrag
        command.putLE32(2, at: 12)               // Zeitstempel
        command.putLE32(0x10000, at: 16)         // current_version 1.0.0
        command.putLE32(0x10000, at: 20)         // compatibility_version 1.0.0
        command.replaceSubrange(24..<(24 + pathBytes.count), with: pathBytes)

        data.replaceSubrange(headerEnd..<(headerEnd + cmdSize), with: command)
        data.putLE32(UInt32(ncmds + 1), at: 16)
        data.putLE32(UInt32(sizeofcmds + cmdSize), at: 20)
        try data.write(to: binary, options: .atomic)
        return true
    }
}

extension Data {
    mutating func putLE32(_ value: UInt32, at offset: Int) {
        for i in 0..<4 {
            self[startIndex + offset + i] = UInt8((value >> (8 * UInt32(i))) & 0xFF)
        }
    }
}

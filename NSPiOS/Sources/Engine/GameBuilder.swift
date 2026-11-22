import Foundation
import CryptoKit
import UIKit

/// Ein Programm (Spiel) in einem entpackten Ordner: exefs/ + romfs/ (bei Doppel-Cartridges exefs_<ID>/romfs_<ID>).
struct GameProgram: Identifiable, Hashable, Sendable {
    let titleID: String
    let exefs: URL
    let romfs: URL
    var id: String { titleID }

    var name: String { GameBuilder.knownNames[titleID] ?? titleID }
    var isSupported: Bool { GameBuilder.knownNames[titleID] != nil }
}

enum GameBuilderError: LocalizedError {
    case shellDownload(String)
    case shellChecksum
    case packerMissing
    case pack(String)

    var errorDescription: String? {
        switch self {
        case .shellDownload(let m): return L("Shell download failed: ", "Shell-Download fehlgeschlagen: ") + m
        case .shellChecksum: return L("Shell checksum mismatch.", "Prüfsumme der Shell stimmt nicht.")
        case .packerMissing: return L("Packer is missing from this build.", "Packer fehlt in diesem Build.")
        case .pack(let m): return m
        }
    }
}

/// Baut aus entpackten Spieldateien eine nxnative-App (ns2ios-shell), komplett auf dem Gerät.
@MainActor
final class GameBuilder: ObservableObject {
    static let shared = GameBuilder()

    // ns2ios-shell-Release, zu dem der gebündelte Packer gehört (scripts/fetch_deps.sh: NS2IOS_TAG)
    static let shellTag = "v0.0.0047"
    static let shellFile = "ns2ios-20261121-ba34f1b-ba34f1b.zip"
    static let shellURL = URL(string: "https://github.com/xinerqu/ns2ios-shell/releases/download/v0.0.0047/ns2ios-20261121-ba34f1b-ba34f1b.zip")!
    static let shellSHA256 = "fb5175921a6ac63c82797093d2d66ddf7737952d7e2c56452eebfcc0c23dec4c"

    static let knownNames: [String: String] = [
        "0100FD8022DAA000": "Super Mario Galaxy 2",
        "010099C022B96000": "Super Mario Galaxy",
    ]

    @Published private(set) var running = false
    @Published private(set) var progress: String?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    private func endBackgroundWork() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    nonisolated static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NSPiOS", isDirectory: true)
    }
    nonisolated static var gamesRoot: URL { root.appendingPathComponent("Games", isDirectory: true) }
    nonisolated static var shellPath: URL { root.appendingPathComponent("shell/\(shellFile)") }
    nonisolated static var packerPath: URL { root.appendingPathComponent("packer", isDirectory: true) }
    nonisolated static var bundledPacker: URL? {
        guard let resources = Bundle.main.resourceURL else { return nil }
        let url = resources.appendingPathComponent("py/packer", isDirectory: true)
        return FileManager.default.fileExists(atPath: url.appendingPathComponent("scripts/nxn_pack.py").path) ? url : nil
    }

    // MARK: - Programme finden

    /// Sucht exefs/romfs-Paare in einem entpackten Ordner (NSPiOS legt sie als romfs/exefs bzw. romfs_<ID>/exefs_<ID> an).
    nonisolated static func findPrograms(in folder: URL) -> [GameProgram] {
        let fm = FileManager.default
        guard let children = try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.isDirectoryKey]) else { return [] }
        let dirs = Dictionary(uniqueKeysWithValues: children
            .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true }
            .map { ($0.lastPathComponent.lowercased(), $0) })

        var result: [GameProgram] = []
        for (name, exefs) in dirs where name == "exefs" || name.hasPrefix("exefs_") {
            let suffix = String(name.dropFirst("exefs".count))
            guard let romfs = dirs["romfs" + suffix],
                  let tid = titleID(npdm: exefs.appendingPathComponent("main.npdm")) else { continue }
            result.append(GameProgram(titleID: tid, exefs: exefs, romfs: romfs))
        }
        return result.sorted { ($0.isSupported ? 0 : 1, $0.name) < ($1.isSupported ? 0 : 1, $1.name) }
    }

    /// Programm-ID aus main.npdm (ACI0), wie der Packer sie liest.
    nonisolated static func titleID(npdm: URL) -> String? {
        guard let data = try? Data(contentsOf: npdm), data.count >= 0x78,
              data.prefix(4) == Data("META".utf8) else { return nil }
        let offset = Int(data.le32(0x70))
        guard offset + 0x18 <= data.count, data.bytes(offset, 4) == Data("ACI0".utf8) else { return nil }
        return String(format: "%016llX", data.le64(offset + 0x10))
    }

    // MARK: - Shell & Packer

    func ensureShell() async throws -> URL {
        let path = Self.shellPath
        if FileManager.default.fileExists(atPath: path.path), (try? Self.sha256(of: path)) == Self.shellSHA256 {
            return path
        }
        progress = L("Downloading game shell …", "Lade Spiel-Shell …")
        let (temp, response) = try await URLSession.shared.download(from: Self.shellURL)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw GameBuilderError.shellDownload("HTTP \(http.statusCode)")
        }
        guard try Self.sha256(of: temp) == Self.shellSHA256 else { throw GameBuilderError.shellChecksum }
        try FileManager.default.createDirectory(at: path.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: path)
        try FileManager.default.moveItem(at: temp, to: path)
        return path
    }

    nonisolated static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 4 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    func ensurePacker() async throws -> URL {
        guard let bundled = Self.bundledPacker else { throw GameBuilderError.packerMissing }
        _ = try await PythonEngine.shared.call("nspios_engine.packer", "prepare",
                                               args: ["bundle": bundled.path, "dest": Self.packerPath.path])
        return Self.packerPath
    }

    // MARK: - Bauen

    /// Baut die Spiel-App. `noShaders` = Aufnahme-Lauf (Spiel erzeugt seine Shader beim ersten Start).
    @discardableResult
    func build(_ program: GameProgram, noShaders: Bool, vkCapture: URL? = nil) async throws -> URL {
        guard !running else { throw GameBuilderError.pack(L("A build is already running.", "Es läuft schon ein Build.")) }
        running = true
        // Bildschirm an lassen und kurz im Hintergrund weiterarbeiten dürfen (Builds dauern Minuten)
        UIApplication.shared.isIdleTimerDisabled = true
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "NSPiOS build") {
            Task { @MainActor in GameBuilder.shared.endBackgroundWork() }
        }
        defer {
            running = false
            progress = nil
            UIApplication.shared.isIdleTimerDisabled = false
            endBackgroundWork()
        }

        let shell = try await ensureShell()
        progress = L("Preparing packer …", "Bereite Packer vor …")
        let packer = try await ensurePacker()

        // Spielordner: base/exefs und base/romfs als Verweise auf die entpackten Daten (nichts wird kopiert)
        let fm = FileManager.default
        let gameDir = Self.gamesRoot.appendingPathComponent(program.titleID, isDirectory: true)
        let dump = gameDir.appendingPathComponent("dump", isDirectory: true)
        let base = dump.appendingPathComponent("base", isDirectory: true)
        try fm.createDirectory(at: base, withIntermediateDirectories: true)
        for (name, target) in [("exefs", program.exefs), ("romfs", program.romfs)] {
            let link = base.appendingPathComponent(name)
            try? fm.removeItem(at: link)
            try fm.createSymbolicLink(at: link, withDestinationURL: target)
        }

        let logs = gameDir.appendingPathComponent("logs", isDirectory: true)
        try fm.createDirectory(at: logs, withIntermediateDirectories: true)
        let log = logs.appendingPathComponent("pack-\(Int(Date().timeIntervalSince1970)).log")

        var args: [String: Any] = [
            "packer": packer.path, "shell": shell.path, "dump": dump.path, "log": log.path,
            "no_shaders": noShaders,
        ]
        if let vkCapture { args["vk_capture"] = vkCapture.path }

        progress = L("Packing …", "Packe …")
        let watcher = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                if let line = Self.lastLine(of: log) { self?.progress = line }
            }
        }
        defer { watcher.cancel() }

        let result = try await PythonEngine.shared.call("nspios_engine.packer", "pack", args: args)
        guard let app = (result as? [String: Any])?["app"] as? String else {
            throw GameBuilderError.pack(L("Packer returned no app.", "Packer lieferte keine App."))
        }
        return URL(fileURLWithPath: app)
    }

    nonisolated static func lastLine(of url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        let size = (try? handle.seekToEnd()) ?? 0
        try? handle.seek(toOffset: size > 2048 ? size - 2048 : 0)
        guard let data = try? handle.readToEnd() else { return nil }
        return String(decoding: data, as: UTF8.self)
            .split(whereSeparator: \.isNewline)
            .last { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .map { String($0.prefix(160)) }
    }

    // MARK: - CI-Probe

    /// `-nspios-packtest`: Shell laden, Packer vorbereiten und mit einem Fake-Spiel bis nach dem Entpacken laufen lassen.
    static func smokeTest() async -> String {
        do {
            let shell = try await shared.ensureShell()
            let packer = try await shared.ensurePacker()
            let result = try await PythonEngine.shared.call("nspios_engine.packer", "smoke",
                                                            args: ["packer": packer.path, "shell": shell.path])
            let dict = result as? [String: Any] ?? [:]
            let rc = dict["rc"] as? Int ?? -1
            let log = dict["log"] as? String ?? ""
            return (rc == 0 ? "NSPIOS_PACKTEST_OK" : "NSPIOS_PACKTEST_FAIL rc=\(rc)") + "\n" + log
        } catch {
            return "NSPIOS_PACKTEST_FAIL \(error.localizedDescription)"
        }
    }
}

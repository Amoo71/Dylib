import SwiftUI
import UIKit

/// Ein Release von GitHub (Tag `nspios-b<build>` mit angehängter IPA).
struct AppRelease: Equatable, Sendable {
    let build: Int
    let version: String
    let notes: String
    let ipaURL: URL
    let size: Int64
    let date: Date?
}

/// Wie das Update installiert wird. iOS erlaubt keine Selbst-Installation –
/// TrollStore / SideStore / AltStore übernehmen das Ersetzen der App.
enum InstallMethod: String, CaseIterable, Identifiable, Sendable {
    case selfUpdate, trollStore, sideStore, altStore, share

    var id: String { rawValue }

    var title: String {
        switch self {
        case .selfUpdate: return SelfUpdater.isLiveContainer ? "LiveContainer" : L("In-app", "In der App")
        case .trollStore: return "TrollStore"
        case .sideStore: return "SideStore"
        case .altStore: return "AltStore"
        case .share: return L("Download IPA", "IPA laden")
        }
    }

    var icon: String {
        switch self {
        case .selfUpdate: return "arrow.down.circle.fill"
        case .trollStore: return "bolt.fill"
        case .sideStore: return "arrow.triangle.2.circlepath"
        case .altStore: return "a.circle.fill"
        case .share: return "square.and.arrow.up"
        }
    }

    private var scheme: String? {
        switch self {
        case .trollStore: return "apple-magnifier"
        case .sideStore: return "sidestore"
        case .altStore: return "altstore"
        case .selfUpdate, .share: return nil
        }
    }

    func installURL(for ipa: URL) -> URL? {
        guard let scheme,
              let encoded = ipa.absoluteString.addingPercentEncoding(withAllowedCharacters: .alphanumerics)
        else { return nil }
        return URL(string: "\(scheme)://install?url=\(encoded)")
    }

    @MainActor var isAvailable: Bool {
        if self == .selfUpdate { return SelfUpdater.isSupported }
        guard let scheme, let probe = URL(string: "\(scheme)://") else { return true }
        return UIApplication.shared.canOpenURL(probe)
    }
}

@MainActor
final class UpdateManager: ObservableObject {
    nonisolated static let autoCheckKey = "updateAutoCheck"
    nonisolated static let autoInstallKey = "updateAutoInstall"
    nonisolated static let methodKey = "updateInstallMethod"
    private static let lastCheckKey = "updateLastCheck"
    private static let skippedBuildKey = "updateSkippedBuild"
    private static let checkInterval: TimeInterval = 3 * 60 * 60

    enum Phase: Equatable {
        case idle, checking, upToDate, available, installing, installed, failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var latest: AppRelease?
    @Published private(set) var downloadProgress: Double?
    @Published private(set) var downloadedIPA: URL?
    @Published var showSheet = false

    let currentBuild: Int
    let currentVersion: String
    let repo: String

    private let defaults = UserDefaults.standard

    init() {
        let info = Bundle.main.infoDictionary ?? [:]
        currentBuild = Int(info["CFBundleVersion"] as? String ?? "") ?? 0
        currentVersion = info["CFBundleShortVersionString"] as? String ?? "?"
        let configured = (info["NSPiOSUpdateRepo"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        repo = configured.isEmpty || configured.hasPrefix("$(") ? "Amoo71/Dylib" : configured
        defaults.register(defaults: [Self.autoCheckKey: true, Self.autoInstallKey: false])
    }

    var availableMethods: [InstallMethod] {
        InstallMethod.allCases.filter { $0.isAvailable }
    }

    /// Gewählte Methode, sonst die erste installierte (TrollStore > SideStore > AltStore).
    var preferredMethod: InstallMethod {
        let available = availableMethods
        if let stored = InstallMethod(rawValue: defaults.string(forKey: Self.methodKey) ?? ""), available.contains(stored) {
            return stored
        }
        return available.first ?? .share
    }

    // MARK: - Prüfen

    /// Beim Start / Aktivieren: prüft höchstens alle 3 Stunden.
    func checkIfDue() {
        guard defaults.bool(forKey: Self.autoCheckKey), phase != .checking else { return }
        let last = defaults.double(forKey: Self.lastCheckKey)
        guard Date().timeIntervalSince1970 - last > Self.checkInterval else { return }
        Task { await check(userInitiated: false) }
    }

    func check(userInitiated: Bool) async {
        guard phase != .checking, phase != .installing, phase != .installed else { return }
        phase = .checking
        do {
            let release = try await fetchLatestRelease()
            defaults.set(Date().timeIntervalSince1970, forKey: Self.lastCheckKey)

            guard let release, release.build > currentBuild else {
                latest = release
                phase = .upToDate
                return
            }
            latest = release
            phase = .available

            if userInitiated {
                showSheet = true
            } else if defaults.bool(forKey: Self.autoInstallKey), preferredMethod != .share {
                install(with: preferredMethod)
                showSheet = preferredMethod == .selfUpdate
            } else if defaults.integer(forKey: Self.skippedBuildKey) != release.build {
                showSheet = true
            }
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    func skipCurrentRelease() {
        if let latest { defaults.set(latest.build, forKey: Self.skippedBuildKey) }
        showSheet = false
    }

    // MARK: - Installieren

    func install(with method: InstallMethod) {
        guard let release = latest else { return }
        switch method {
        case .share:
            Task { downloadedIPA = await download(release) }
            return
        case .selfUpdate:
            Task { await selfUpdate(release) }
            return
        default:
            break
        }
        guard let url = method.installURL(for: release.ipaURL) else { return }
        UIApplication.shared.open(url) { success in
            if !success {
                Task { @MainActor in
                    self.phase = .failed(L("Couldn’t open \(method.title).", "\(method.title) konnte nicht geöffnet werden."))
                    self.showSheet = true
                }
            }
        }
    }

    private func download(_ release: AppRelease) async -> URL? {
        guard downloadProgress == nil else { return nil }
        downloadedIPA = nil
        downloadProgress = 0
        defer { downloadProgress = nil }
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("NSPiOS-\(release.version).ipa")
        do {
            let downloader = IPADownloader(destination: destination) { fraction in
                Task { @MainActor in self.downloadProgress = fraction }
            }
            return try await downloader.start(release.ipaURL)
        } catch {
            phase = .failed(L("Download failed: ", "Download fehlgeschlagen: ") + error.localizedDescription)
            return nil
        }
    }

    /// Lädt die IPA und ersetzt die eigenen App-Dateien (LiveContainer).
    private func selfUpdate(_ release: AppRelease) async {
        guard phase != .installing, let ipa = await download(release) else { return }
        phase = .installing
        let result = await Task.detached(priority: .userInitiated) {
            Result { try SelfUpdater.apply(ipa: ipa) }
        }.value
        try? FileManager.default.removeItem(at: ipa)
        switch result {
        case .success:
            phase = .installed
            showSheet = true
        case .failure(let error):
            phase = .failed(L("Update failed: ", "Update fehlgeschlagen: ") + error.localizedDescription)
        }
    }

    func restart() {
        SelfUpdater.restart()
    }

    // MARK: - GitHub

    private struct GHRelease: Decodable {
        let tagName: String
        let name: String?
        let body: String?
        let draft: Bool
        let publishedAt: Date?
        let assets: [GHAsset]
    }

    private struct GHAsset: Decodable {
        let name: String
        let browserDownloadUrl: URL
        let size: Int64
    }

    private func fetchLatestRelease() async throws -> AppRelease? {
        guard let url = URL(string: "https://api.github.com/repos/\(repo)/releases?per_page=40") else { return nil }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("NSPiOS-Updater", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw URLError(.badServerResponse, userInfo: [NSLocalizedDescriptionKey: L("GitHub returned status \(http.statusCode).", "GitHub antwortet mit Status \(http.statusCode).")])
        }

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
        let releases = try decoder.decode([GHRelease].self, from: data)

        let prefix = "nspios-b"
        return releases
            .compactMap { release -> AppRelease? in
                guard !release.draft, release.tagName.hasPrefix(prefix),
                      let build = Int(release.tagName.dropFirst(prefix.count)),
                      let asset = release.assets.first(where: { $0.name.lowercased().hasSuffix(".ipa") })
                else { return nil }
                let version = release.name?.replacingOccurrences(of: "NSPiOS ", with: "") ?? "Build \(build)"
                return AppRelease(
                    build: build,
                    version: version,
                    notes: (release.body ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                    ipaURL: asset.browserDownloadUrl,
                    size: asset.size,
                    date: release.publishedAt
                )
            }
            .max { $0.build < $1.build }
    }
}

/// Download mit Fortschritt.
private final class IPADownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destination: URL
    private let onProgress: @Sendable (Double) -> Void
    private var continuation: CheckedContinuation<URL, Error>?
    private let lock = NSLock()

    init(destination: URL, onProgress: @escaping @Sendable (Double) -> Void) {
        self.destination = destination
        self.onProgress = onProgress
    }

    func start(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock(); self.continuation = continuation; lock.unlock()
            let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
            session.downloadTask(with: url).resume()
        }
    }

    private func finish(_ result: Result<URL, Error>) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }
        onProgress(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            finish(.failure(URLError(.badServerResponse)))
            return
        }
        do {
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: location, to: destination)
            finish(.success(destination))
        } catch {
            finish(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)) }
        session.finishTasksAndInvalidate()
    }
}

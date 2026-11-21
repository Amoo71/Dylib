import SwiftUI

/// Karte im Tab „Einstellungen“.
struct UpdateCard: View {
    @EnvironmentObject private var updater: UpdateManager
    @AppStorage(UpdateManager.autoCheckKey) private var autoCheck = true
    @AppStorage(UpdateManager.autoInstallKey) private var autoInstall = false
    @AppStorage(UpdateManager.methodKey) private var method = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                Image(systemName: "arrow.down.app.fill")
                    .font(.title2)
                    .foregroundStyle(Theme.accent)
                    .frame(width: 40, height: 40)
                    .background(Theme.accent.opacity(0.16), in: .rect(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Updates").font(.headline)
                    Text("Version \(updater.currentVersion) · Build \(updater.currentBuild)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                status
            }

            if updater.phase == .installed {
                Button { updater.restart() } label: {
                    Label("Neu starten, um Update zu laden", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
            } else if updater.phase == .available, let latest = updater.latest {
                Button { updater.showSheet = true } label: {
                    Label("Update \(latest.version) installieren", systemImage: "sparkles")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
            } else {
                Button {
                    Task { await updater.check(userInitiated: true) }
                } label: {
                    Label("Nach Updates suchen", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.glass)
                .disabled(updater.phase == .checking)
            }

            Divider().opacity(0.4)

            Toggle("Automatisch suchen", isOn: $autoCheck)
            Toggle(isOn: $autoInstall) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Automatisch installieren")
                    Text("Installiert neue Versionen sofort – in LiveContainer direkt ohne Neu-Signieren.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(!autoCheck)

            Picker("Installieren mit", selection: $method) {
                Text("Automatisch (\(updater.preferredMethod.title))").tag("")
                ForEach(updater.availableMethods) { m in
                    Text(m.title).tag(m.rawValue)
                }
            }
            .pickerStyle(.menu)
        }
        .tint(Theme.accent)
        .padding(18)
        .glassCard(radius: 24)
    }

    @ViewBuilder
    private var status: some View {
        switch updater.phase {
        case .checking, .installing:
            ProgressView().controlSize(.small)
        case .installed:
            Image(systemName: "arrow.clockwise.circle.fill")
                .foregroundStyle(Theme.accent)
        case .upToDate:
            Label("Aktuell", systemImage: "checkmark.circle.fill")
                .labelStyle(.iconOnly)
                .foregroundStyle(.green)
        case .available:
            Text("Neu")
                .font(.caption.weight(.bold))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .glassEffect(.regular.tint(Theme.accent.opacity(0.45)), in: .capsule)
        case .failed(let message):
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Theme.danger)
                .help(message)
        case .idle:
            EmptyView()
        }
    }
}

/// Sheet mit Release-Infos und Installations-Optionen.
struct UpdateSheet: View {
    @EnvironmentObject private var updater: UpdateManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                if let release = updater.latest {
                    content(release)
                        .padding(20)
                } else {
                    ProgressView().padding(40)
                }
            }
            .background { AmbientBackground() }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Schließen")
                }
            }
        }
        .presentationDetents([.large])
    }

    private func content(_ release: AppRelease) -> some View {
        VStack(spacing: 22) {
            Image(systemName: "arrow.down.app.fill")
                .font(.system(size: 64, weight: .semibold))
                .foregroundStyle(
                    LinearGradient(colors: [Theme.accent, Theme.violet], startPoint: .topLeading, endPoint: .bottomTrailing)
                )
                .padding(22)
                .glassEffect(.regular, in: .rect(cornerRadius: 30))
                .padding(.top, 8)

            VStack(spacing: 6) {
                Text("NSPiOS \(release.version)")
                    .font(.title2.weight(.bold))
                Text(subtitle(release))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            if updater.phase == .installed {
                VStack(spacing: 12) {
                    Label("Update installiert", systemImage: "checkmark.seal.fill")
                        .font(.headline)
                        .foregroundStyle(Theme.accent)
                    Text(SelfUpdater.isLiveContainer
                         ? "Die App wird beendet. Öffne sie danach wieder in LiveContainer – die neue Version startet."
                         : "Die App wird beendet. Beim nächsten Öffnen startet die neue Version.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button { updater.restart() } label: {
                        Label("Jetzt neu starten", systemImage: "arrow.clockwise")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                    }
                    .buttonStyle(.glassProminent)
                }
                .padding(18)
                .glassCard(radius: 24)
            }

            if updater.phase == .installing {
                Label("Installiere …", systemImage: "arrow.down.circle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if case .failed(let message) = updater.phase {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
            }

            if !release.notes.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Was ist neu").font(.headline)
                    Text(release.notes)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(18)
                .glassCard(radius: 24)
            }

            if updater.phase != .installed {
            GlassEffectContainer(spacing: 12) {
                VStack(spacing: 12) {
                    ForEach(updater.availableMethods) { method in
                        if method == updater.preferredMethod {
                            installButton(method).buttonStyle(.glassProminent)
                        } else {
                            installButton(method).buttonStyle(.glass)
                        }
                    }
                }
            }
            }

            if let progress = updater.downloadProgress {
                VStack(spacing: 6) {
                    ProgressView(value: progress).tint(Theme.accent)
                    Text(progress, format: .percent.precision(.fractionLength(0)))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }

            if let file = updater.downloadedIPA {
                ShareLink(item: file) {
                    Label("IPA öffnen in …", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.glassProminent)
            }

            Button("Diese Version überspringen") { updater.skipCurrentRelease() }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
        }
    }

    private func installButton(_ method: InstallMethod) -> some View {
        Button { updater.install(with: method) } label: {
            Label(method == .share || method == .selfUpdate ? (method == .selfUpdate ? "Jetzt aktualisieren" : method.title) : "Mit \(method.title) installieren", systemImage: method.icon)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        }
        .disabled(updater.downloadProgress != nil || updater.phase == .installing)
    }

    private func subtitle(_ release: AppRelease) -> String {
        var parts = ["Build \(release.build)", formatBytes(release.size)]
        if let date = release.date { parts.append(date.formatted(date: .abbreviated, time: .shortened)) }
        return parts.joined(separator: " · ")
    }
}

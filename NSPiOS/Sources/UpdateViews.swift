import SwiftUI

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
                        .accessibilityLabel(L("Close", "Schließen"))
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func content(_ release: AppRelease) -> some View {
        VStack(spacing: 22) {
            Image(systemName: updater.phase == .installed ? "checkmark.seal.fill" : "arrow.down.app.fill")
                .font(.system(size: 56, weight: .semibold))
                .foregroundStyle(
                    LinearGradient(colors: [Theme.accent, Theme.violet], startPoint: .topLeading, endPoint: .bottomTrailing)
                )
                .contentTransition(.symbolEffect(.replace))
                .padding(20)
                .glassEffect(.regular, in: .rect(cornerRadius: 28))
                .padding(.top, 8)

            VStack(spacing: 4) {
                Text("NSPiOS \(release.version)")
                    .font(.title2.weight(.bold))
                Text("\(formatBytes(release.size)) · Build \(release.build)")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            if case .failed(let message) = updater.phase {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
            }

            if !release.notes.isEmpty, updater.phase != .installed {
                Text(release.notes)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .glassCard(radius: 22)
            }

            if updater.phase == .installed {
                primaryButton(L("Restart", "Neu starten"), icon: "arrow.clockwise") { updater.restart() }
            } else if updater.phase == .installing {
                ProgressView(L("Installing …", "Installiere …"))
            } else if let progress = updater.downloadProgress {
                ProgressView(value: progress).tint(Theme.accent)
            } else {
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

            if let file = updater.downloadedIPA {
                ShareLink(item: file) {
                    Label(L("Open in …", "Öffnen in …"), systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.glassProminent)
            }

            if updater.phase != .installed {
                Button(L("Skip this version", "Version überspringen")) { updater.skipCurrentRelease() }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func primaryButton(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        }
        .buttonStyle(.glassProminent)
    }

    private func installButton(_ method: InstallMethod) -> some View {
        Button { updater.install(with: method) } label: {
            Label(method == .selfUpdate ? L("Update now", "Jetzt aktualisieren") : method.title, systemImage: method.icon)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        }
    }
}

import SwiftUI

/// Tab „Einstellungen“: Speicher, Bereinigen, Löschen, Updates, Optionen.
struct StorageView: View {
    @EnvironmentObject private var store: FileStore
    @AppStorage(FileStore.deleteAfterExtractKey) private var deleteAfterExtract = false
    @AppStorage(FileStore.xciSecureOnlyKey) private var xciSecureOnly = false
    @State private var confirmScope: DeleteScope?

    private var usedFraction: Double {
        let total = Double(store.usedBytes + store.freeBytes)
        return total > 0 ? Double(store.usedBytes) / total : 0
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    overview
                    actions
                    DecryptionCard()
                    UpdateCard()
                    settings
                    Text("Alle Dateien liegen auch in der Dateien-App unter „Auf meinem iPhone › NSPiOS“.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .background { AmbientBackground() }
            .navigationTitle("Einstellungen")
            .refreshable { store.refresh() }
            .confirmationDialog(
                confirmTitle,
                isPresented: Binding(get: { confirmScope != nil }, set: { if !$0 { confirmScope = nil } }),
                titleVisibility: .visible,
                presenting: confirmScope
            ) { scope in
                Button("Löschen", role: .destructive) { store.deleteAll(scope) }
            } message: { _ in
                Text("Das kann nicht rückgängig gemacht werden.")
            }
            .jobInset()
        }
    }

    // MARK: - Übersicht

    private var overview: some View {
        HStack(spacing: 22) {
            ZStack {
                Circle()
                    .stroke(.white.opacity(0.08), lineWidth: 12)
                Circle()
                    .trim(from: 0, to: max(usedFraction, 0.004))
                    .stroke(
                        AngularGradient(colors: [Theme.violet, Theme.accent, Theme.violet], center: .center),
                        style: StrokeStyle(lineWidth: 12, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                    .animation(.smooth(duration: 0.6), value: usedFraction)
                VStack(spacing: 0) {
                    Text(usedFraction, format: .percent.precision(.fractionLength(usedFraction < 0.1 ? 1 : 0)))
                        .font(.title3.weight(.semibold).monospacedDigit())
                    Text("belegt")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: 110, height: 110)

            VStack(alignment: .leading, spacing: 10) {
                StatLine(title: "NSPiOS", value: formatBytes(store.usedBytes), color: Theme.accent)
                StatLine(title: "Frei", value: formatBytes(store.freeBytes), color: .white.opacity(0.5))
                StatLine(title: "Pakete · Ordner", value: "\(store.packages.count) · \(store.extracted.count)", color: Theme.violet)
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .glassCard()
    }

    // MARK: - Aktionen

    private var actions: some View {
        GlassEffectContainer(spacing: 12) {
            VStack(spacing: 12) {
                Button { store.cleanUp() } label: {
                    ActionLabel(title: "Bereinigen", subtitle: "Temp, Cache, Reste & leere Ordner", icon: "sparkles")
                }
                .buttonStyle(.glass)

                Button { store.export(store.extracted.map(\.url), asCopy: false) } label: {
                    ActionLabel(title: "Alles in Dateien verschieben", subtitle: "Alle entpackten Ordner", icon: "folder.badge.plus")
                }
                .buttonStyle(.glass)
                .disabled(store.extracted.isEmpty)

                HStack(spacing: 12) {
                    Button { confirmScope = .packages } label: {
                        CompactLabel(title: "Pakete löschen", icon: "shippingbox")
                    }
                    .buttonStyle(.glass)
                    .disabled(store.packages.isEmpty)

                    Button { confirmScope = .extracted } label: {
                        CompactLabel(title: "Ordner löschen", icon: "folder")
                    }
                    .buttonStyle(.glass)
                    .disabled(store.extracted.isEmpty)
                }

                Button { confirmScope = .everything } label: {
                    ActionLabel(title: "Alles komplett löschen", subtitle: "Pakete, Ordner, Cache – sauberer Neustart", icon: "trash.fill")
                }
                .buttonStyle(.glassProminent)
                .tint(Theme.danger)
            }
        }
    }

    private var settings: some View {
        VStack(spacing: 16) {
            Toggle(isOn: $deleteAfterExtract) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Paket nach dem Entpacken löschen")
                    Text("Spart Speicher – die Original-NSP/XCI wird nach Erfolg entfernt.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Divider().opacity(0.4)
            Toggle(isOn: $xciSecureOnly) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("XCI: nur Spieldaten")
                    Text("Entpackt nur die „secure“-Partition direkt in den Ordner. Aus: alle Partitionen (update, normal, secure, logo) in Unterordnern.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .tint(Theme.accent)
        .padding(18)
        .glassCard(radius: 24)
    }

    private var confirmTitle: String {
        switch confirmScope {
        case .packages: return "Alle Pakete löschen?"
        case .extracted: return "Alle entpackten Ordner löschen?"
        case .everything: return "Wirklich alles löschen?"
        case nil: return ""
        }
    }
}

private struct StatLine: View {
    let title: String
    let value: String
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.callout.weight(.semibold).monospacedDigit())
            }
        }
    }
}

private struct ActionLabel: View {
    let title: String
    let subtitle: String
    let icon: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.title3)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.semibold))
                Text(subtitle).font(.caption).opacity(0.7)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 6)
        .frame(maxWidth: .infinity)
        .contentShape(.rect)
    }
}

private struct CompactLabel: View {
    let title: String
    let icon: String

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.title3)
            Text(title).font(.subheadline.weight(.semibold))
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .contentShape(.rect)
    }
}

/// Karte „RomFS & ExeFS“: eigene prod.keys laden, Entschlüsselung steuern.
struct DecryptionCard: View {
    @EnvironmentObject private var store: FileStore
    @AppStorage(FileStore.decryptKey) private var decrypt = true
    @AppStorage(FileStore.keepNCAKey) private var keepNCA = false
    @State private var confirmRemove = false

    private var hasKeys: Bool { store.keyCount > 0 }

    private var importButton: some View {
        Button {
            store.requestImport(.keys)
        } label: {
            Label(hasKeys ? "prod.keys ersetzen" : "prod.keys laden", systemImage: "square.and.arrow.down")
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                Image(systemName: hasKeys ? "key.fill" : "key")
                    .font(.title2)
                    .foregroundStyle(hasKeys ? Theme.accent : .secondary)
                    .frame(width: 40, height: 40)
                    .background((hasKeys ? Theme.accent : Color.white).opacity(0.14), in: .rect(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 2) {
                    Text("RomFS & ExeFS").font(.headline)
                    Text(hasKeys ? "prod.keys geladen · \(store.keyCount) Schlüssel" : "Keine Keys – nur Container-Entpacken")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }

            if hasKeys {
                importButton.buttonStyle(.glass)
            } else {
                importButton.buttonStyle(.glassProminent)
            }

            Divider().opacity(0.4)

            Toggle(isOn: $decrypt) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("RomFS & ExeFS entpacken")
                    Text("Entschlüsselt die NCAs in die Ordner „romfs“, „exefs“, „control“ (DLC: „romfs_<TitleID>“).")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(!hasKeys)

            Toggle(isOn: $keepNCA) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("NCAs zusätzlich behalten")
                    Text("Aus: entschlüsselte NCAs werden nicht mit kopiert – spart viel Speicher.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .disabled(!hasKeys || !decrypt)

            if hasKeys {
                Button(role: .destructive) { confirmRemove = true } label: {
                    Label("Keys entfernen", systemImage: "trash")
                        .font(.subheadline)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.danger)
            }

            Text("Nutze nur Keys deiner eigenen Konsole. Sie bleiben privat in der App und werden nie hochgeladen.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .tint(Theme.accent)
        .padding(18)
        .glassCard(radius: 24)
        .confirmationDialog("Keys entfernen?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Entfernen", role: .destructive) { store.removeKeys() }
        }
    }
}

import SwiftUI

/// Tab „Settings“: kompakt, wenig Text.
struct SettingsView: View {
    @EnvironmentObject private var store: FileStore
    @EnvironmentObject private var updater: UpdateManager

    @AppStorage(Prefs.theme) private var themeRaw = AppTheme.dark.rawValue
    @AppStorage(Prefs.customColor) private var customHex = Theme.defaultCustomHex
    @AppStorage(Prefs.wind) private var wind = 0.5
    @AppStorage(Prefs.language) private var language = AppLanguage.en.rawValue

    @AppStorage(FileStore.deleteAfterExtractKey) private var deleteAfterExtract = false
    @AppStorage(FileStore.xciSecureOnlyKey) private var xciSecureOnly = false
    @AppStorage(FileStore.decryptKey) private var decrypt = true
    @AppStorage(FileStore.keepNCAKey) private var keepNCA = false

    @AppStorage(UpdateManager.autoCheckKey) private var autoCheck = true
    @AppStorage(UpdateManager.autoInstallKey) private var autoInstall = false
    @AppStorage(UpdateManager.methodKey) private var method = ""

    @State private var confirmScope: DeleteScope?
    @State private var confirmRemoveKeys = false

    private var theme: AppTheme { AppTheme(rawValue: themeRaw) ?? .dark }
    private var hasKeys: Bool { store.keyCount > 0 }

    var body: some View {
        NavigationStack {
            List {
                storageSection
                appearanceSection
                extractionSection
                romfsSection
                updateSection
            }
            .ambientList()
            .navigationTitle(L("Settings", "Einstellungen"))
            .refreshable { store.refresh() }
            .confirmationDialog(
                L("Delete?", "Löschen?"),
                isPresented: Binding(get: { confirmScope != nil }, set: { if !$0 { confirmScope = nil } }),
                titleVisibility: .visible,
                presenting: confirmScope
            ) { scope in
                Button(L("Delete", "Löschen"), role: .destructive) { store.deleteAll(scope) }
            }
            .confirmationDialog(L("Remove keys?", "Keys entfernen?"), isPresented: $confirmRemoveKeys, titleVisibility: .visible) {
                Button(L("Remove", "Entfernen"), role: .destructive) { store.removeKeys() }
            }
            .jobInset()
        }
    }

    // MARK: - Speicher

    private var usedFraction: Double {
        let total = Double(store.usedBytes + store.freeBytes)
        return total > 0 ? Double(store.usedBytes) / total : 0
    }

    private var storageSection: some View {
        Section {
            HStack(spacing: 16) {
                ZStack {
                    Circle().stroke(Color.primary.opacity(0.1), lineWidth: 7)
                    Circle()
                        .trim(from: 0, to: max(usedFraction, 0.004))
                        .stroke(
                            AngularGradient(colors: [Theme.violet, Theme.accent, Theme.violet], center: .center),
                            style: StrokeStyle(lineWidth: 7, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                        .animation(.smooth(duration: 0.6), value: usedFraction)
                }
                .frame(width: 46, height: 46)

                VStack(alignment: .leading, spacing: 2) {
                    Text(formatBytes(store.usedBytes))
                        .font(.title3.weight(.semibold).monospacedDigit())
                    Text(L("\(formatBytes(store.freeBytes)) free", "\(formatBytes(store.freeBytes)) frei"))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Label("\(store.packages.count)", systemImage: "shippingbox.fill")
                    .foregroundStyle(Theme.violet)
                Label("\(store.extracted.count)", systemImage: "folder.fill")
                    .foregroundStyle(Theme.accent)
            }
            .font(.subheadline.monospacedDigit())
            .labelStyle(CompactLabelStyle())
            .padding(.vertical, 4)
            .listRowBackground(Theme.rowBackground)

            Button { store.cleanUp() } label: {
                SettingLabel(L("Clean up", "Bereinigen"), icon: "sparkles", color: .teal)
            }
            .listRowBackground(Theme.rowBackground)

            Button { store.export(store.extracted.map(\.url), asCopy: false) } label: {
                SettingLabel(L("Move all to Files", "Alles in Dateien"), icon: "folder.badge.plus", color: .blue)
            }
            .disabled(store.extracted.isEmpty)
            .listRowBackground(Theme.rowBackground)

            Menu {
                Button(L("Packages", "Pakete"), role: .destructive) { confirmScope = .packages }
                Button(L("Extracted folders", "Entpackte Ordner"), role: .destructive) { confirmScope = .extracted }
                Divider()
                Button(L("Everything", "Alles"), role: .destructive) { confirmScope = .everything }
            } label: {
                SettingLabel(L("Delete …", "Löschen …"), icon: "trash.fill", color: Theme.danger)
            }
            .listRowBackground(Theme.rowBackground)
        }
        .foregroundStyle(.primary)
    }

    // MARK: - Darstellung

    private var appearanceSection: some View {
        Section(L("Appearance", "Darstellung")) {
            HStack(spacing: 0) {
                ForEach(AppTheme.allCases) { option in
                    ThemeSwatch(theme: option, customHex: customHex, selected: option == theme) {
                        withAnimation(.smooth) { themeRaw = option.rawValue }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(.vertical, 6)
            .listRowBackground(Theme.rowBackground)

            if theme == .custom {
                ColorPicker(selection: Binding(
                    get: { Color(hex: customHex) ?? Theme.customColor },
                    set: { customHex = $0.hexString }
                ), supportsOpacity: false) {
                    SettingLabel(L("Color", "Farbe"), icon: "paintpalette.fill", color: Color(hex: customHex) ?? .orange)
                }
                .listRowBackground(Theme.rowBackground)
            }

            if theme == .rain {
                HStack(spacing: 12) {
                    SettingIcon(icon: "wind", color: .cyan)
                    Slider(value: $wind, in: 0...1)
                }
                .listRowBackground(Theme.rowBackground)
            }

            Picker(selection: $language) {
                ForEach(AppLanguage.allCases) { lang in
                    Text(lang.title).tag(lang.rawValue)
                }
            } label: {
                SettingLabel(L("Language", "Sprache"), icon: "globe", color: .indigo)
            }
            .pickerStyle(.menu)
            .listRowBackground(Theme.rowBackground)
        }
    }

    // MARK: - Entpacken

    private var extractionSection: some View {
        Section(L("Extraction", "Entpacken")) {
            Toggle(isOn: $deleteAfterExtract) {
                SettingLabel(L("Delete package after", "Paket danach löschen"), icon: "trash", color: .orange)
            }
            .listRowBackground(Theme.rowBackground)

            Toggle(isOn: $xciSecureOnly) {
                SettingLabel(L("XCI: game data only", "XCI: nur Spieldaten"), icon: "sdcard.fill", color: .purple)
            }
            .listRowBackground(Theme.rowBackground)
        }
    }

    // MARK: - RomFS

    private var romfsSection: some View {
        Section("RomFS") {
            Button { store.requestImport(.keys) } label: {
                HStack {
                    SettingLabel("prod.keys", icon: hasKeys ? "key.fill" : "key", color: hasKeys ? .green : .gray)
                    Spacer()
                    Text(hasKeys ? "\(store.keyCount)" : L("Load", "Laden"))
                        .foregroundStyle(hasKeys ? AnyShapeStyle(.secondary) : AnyShapeStyle(Theme.accent))
                        .monospacedDigit()
                }
            }
            .foregroundStyle(.primary)
            .listRowBackground(Theme.rowBackground)
            .swipeActions {
                if hasKeys {
                    Button(L("Remove", "Entfernen"), role: .destructive) { confirmRemoveKeys = true }
                }
            }

            Toggle(isOn: $decrypt) {
                SettingLabel(L("RomFS & ExeFS", "RomFS & ExeFS"), icon: "cube.transparent", color: .mint)
            }
            .disabled(!hasKeys)
            .listRowBackground(Theme.rowBackground)

            Toggle(isOn: $keepNCA) {
                SettingLabel(L("Keep NCAs", "NCAs behalten"), icon: "cube.fill", color: .gray)
            }
            .disabled(!hasKeys || !decrypt)
            .listRowBackground(Theme.rowBackground)
        }
    }

    // MARK: - Updates

    private var updateSection: some View {
        Section("Updates") {
            Button {
                switch updater.phase {
                case .installed: updater.restart()
                case .available: updater.showSheet = true
                default: Task { await updater.check(userInitiated: true) }
                }
            } label: {
                HStack {
                    SettingLabel(updateTitle, icon: "arrow.down.app.fill", color: Theme.accent)
                    Spacer()
                    updateStatus
                }
            }
            .foregroundStyle(.primary)
            .disabled(updater.phase == .checking || updater.phase == .installing)
            .listRowBackground(Theme.rowBackground)

            Toggle(isOn: $autoCheck) {
                SettingLabel(L("Auto check", "Automatisch suchen"), icon: "clock.arrow.circlepath", color: .blue)
            }
            .listRowBackground(Theme.rowBackground)

            Toggle(isOn: $autoInstall) {
                SettingLabel(L("Auto install", "Automatisch installieren"), icon: "bolt.fill", color: .yellow)
            }
            .disabled(!autoCheck)
            .listRowBackground(Theme.rowBackground)

            Picker(selection: $method) {
                Text(L("Auto", "Automatisch")).tag("")
                ForEach(updater.availableMethods) { m in
                    Text(m.title).tag(m.rawValue)
                }
            } label: {
                SettingLabel(L("Install via", "Installieren mit"), icon: "square.and.arrow.down", color: .gray)
            }
            .pickerStyle(.menu)
            .listRowBackground(Theme.rowBackground)
        }
    }

    private var updateTitle: String {
        switch updater.phase {
        case .installed: return L("Restart to update", "Neu starten")
        case .available: return L("Update to \(updater.latest?.version ?? "")", "Update auf \(updater.latest?.version ?? "")")
        default: return L("Check for updates", "Nach Updates suchen")
        }
    }

    @ViewBuilder
    private var updateStatus: some View {
        switch updater.phase {
        case .checking, .installing:
            ProgressView().controlSize(.small)
        case .upToDate:
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .available:
            Circle().fill(Theme.accent).frame(width: 9, height: 9)
        case .failed:
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.danger)
        default:
            Text(updater.currentVersion)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Bausteine

/// Farbiges Symbol wie in den iOS-Einstellungen.
struct SettingIcon: View {
    let icon: String
    let color: Color

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 29, height: 29)
            .background(color.gradient, in: .rect(cornerRadius: 8))
    }
}

struct SettingLabel: View {
    let title: String
    let icon: String
    let color: Color

    init(_ title: String, icon: String, color: Color) {
        self.title = title
        self.icon = icon
        self.color = color
    }

    var body: some View {
        HStack(spacing: 12) {
            SettingIcon(icon: icon, color: color)
            Text(title)
        }
    }
}

private struct CompactLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.icon.font(.caption)
            configuration.title
        }
    }
}

/// Vorschau-Kreis für ein Theme.
private struct ThemeSwatch: View {
    let theme: AppTheme
    let customHex: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    Circle().fill(fill)
                    if theme == .rain {
                        Image(systemName: "cloud.rain.fill")
                            .font(.system(size: 16))
                            .foregroundStyle(.white.opacity(0.85))
                    }
                }
                .frame(width: 44, height: 44)
                .overlay {
                    Circle().strokeBorder(selected ? Theme.accent : Color.primary.opacity(0.15), lineWidth: selected ? 3 : 1)
                }
                .scaleEffect(selected ? 1.06 : 1)

                Text(theme.title)
                    .font(.caption2.weight(selected ? .semibold : .regular))
                    .foregroundStyle(selected ? .primary : .secondary)
            }
        }
        .buttonStyle(.plain)
    }

    private var fill: AnyShapeStyle {
        switch theme {
        case .dark:
            return AnyShapeStyle(LinearGradient(colors: [Color(red: 0.16, green: 0.07, blue: 0.30), Color(red: 0.02, green: 0.03, blue: 0.08)], startPoint: .topLeading, endPoint: .bottomTrailing))
        case .light:
            return AnyShapeStyle(LinearGradient(colors: [.white, Color(red: 0.88, green: 0.92, blue: 1.0)], startPoint: .topLeading, endPoint: .bottomTrailing))
        case .custom:
            let c = Color(hex: customHex) ?? .orange
            return AnyShapeStyle(LinearGradient(colors: [c, c.mix(with: .black, by: 0.55)], startPoint: .topLeading, endPoint: .bottomTrailing))
        case .rain:
            return AnyShapeStyle(LinearGradient(colors: [Color(red: 0.2, green: 0.28, blue: 0.4), Color(red: 0.05, green: 0.07, blue: 0.11)], startPoint: .top, endPoint: .bottom))
        }
    }
}

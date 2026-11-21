import SwiftUI
import UIKit

// MARK: - Zeilen

struct FileRow: View {
    let item: FileItem

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: item.isDirectory ? "folder.fill" : Self.symbol(forExtension: item.url.pathExtension))
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(tint.opacity(0.16), in: .rect(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name)
                    .font(.body.weight(.medium))
                    .lineLimit(2)
                Text(formatBytes(item.size))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private var tint: Color {
        if item.isDirectory { return Theme.accent }
        return item.isPackage ? Theme.violet : .secondary
    }

    static func symbol(forExtension ext: String) -> String {
        switch ext.lowercased() {
        case "nsp", "nsz": return "shippingbox.fill"
        case "xci", "xcz": return "sdcard.fill"
        case "nca", "ncz": return "cube.fill"
        case "tik", "cert": return "key.fill"
        case "xml", "json": return "doc.text.fill"
        case "jpg", "jpeg", "png": return "photo.fill"
        default: return "doc.fill"
        }
    }
}

struct EmptyState<Actions: View>: View {
    let icon: String
    let title: String
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: icon)
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(Theme.accent)
        } actions: {
            actions()
        }
    }
}

// MARK: - Aktionen (Kontextmenü + Wischen)

private struct ItemActions: ViewModifier {
    @EnvironmentObject private var store: FileStore
    let item: FileItem

    func body(content: Content) -> some View {
        content
            .contextMenu {
                if item.isPackage {
                    Button { store.enqueueExtraction(of: item.url) } label: {
                        Label(L("Extract", "Entpacken"), systemImage: "arrow.down.to.line")
                    }
                    Button { store.inspected = item } label: {
                        Label(L("Contents", "Inhalt"), systemImage: "list.bullet.rectangle")
                    }
                }
                if item.isDirectory || item.url.pathExtension.lowercased() == "nca" {
                    Button { store.extractRomFS(from: item.url) } label: {
                        Label(L("Extract RomFS", "RomFS entpacken"), systemImage: "cube.transparent")
                    }
                }
                Button { store.export([item.url], asCopy: false) } label: {
                    Label(L("Move to Files", "In Dateien verschieben"), systemImage: "folder.badge.plus")
                }
                Button { store.export([item.url], asCopy: true) } label: {
                    Label(L("Copy to Files", "In Dateien kopieren"), systemImage: "doc.on.doc")
                }
                if !item.isDirectory {
                    ShareLink(item: item.url) {
                        Label(L("Share", "Teilen"), systemImage: "square.and.arrow.up")
                    }
                }
                Divider()
                Button(role: .destructive) { store.delete([item.url]) } label: {
                    Label(L("Delete", "Löschen"), systemImage: "trash")
                }
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button(role: .destructive) { store.delete([item.url]) } label: {
                    Label(L("Delete", "Löschen"), systemImage: "trash")
                }
            }
            .swipeActions(edge: .leading) {
                Button { store.export([item.url], asCopy: false) } label: {
                    Label(L("Move", "Verschieben"), systemImage: "folder.badge.plus")
                }
                .tint(Theme.accent)
            }
    }
}

extension View {
    func itemActions(_ item: FileItem) -> some View {
        modifier(ItemActions(item: item))
    }

    /// Schwebende Liquid-Glass-Fortschrittskarte am unteren Rand.
    func jobInset() -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) {
            JobCard()
        }
    }
}

// MARK: - Fortschritt

struct JobCard: View {
    @EnvironmentObject private var store: FileStore

    var body: some View {
        if let job = store.job {
            GlassEffectContainer {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        Image(systemName: "arrow.down.to.line")
                            .font(.headline)
                            .foregroundStyle(Theme.accent)
                            .symbolEffect(.pulse, options: .repeating)
                            .frame(width: 36, height: 36)
                            .background(Theme.accent.opacity(0.18), in: .circle)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(job.title)
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Text(job.currentFile.isEmpty ? L("Preparing …", "Vorbereiten …") : job.currentFile)
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer(minLength: 0)
                        Button {
                            store.cancelExtraction()
                        } label: {
                            Image(systemName: "xmark")
                                .font(.subheadline.weight(.bold))
                                .frame(width: 20, height: 20)
                        }
                        .buttonStyle(.glass)
                        .buttonBorderShape(.circle)
                        .accessibilityLabel(L("Cancel", "Abbrechen"))
                    }

                    ProgressView(value: job.fraction)
                        .tint(Theme.accent)

                    HStack(spacing: 6) {
                        Text(job.fraction, format: .percent.precision(.fractionLength(0)))
                            .fontWeight(.semibold)
                            .foregroundStyle(.primary)
                        Text("·")
                        Text("\(formatBytes(Int64(job.speed)))/s")
                        if let eta = job.remainingSeconds {
                            Text("·")
                            Text(formatDuration(eta))
                        }
                        Spacer(minLength: 0)
                        if store.queuedCount > 0 {
                            Text("+\(store.queuedCount)")
                        }
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                }
                .padding(16)
                .glassCard(radius: 28)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private func formatDuration(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        if s < 60 { return "\(s) s" }
        if s < 3600 { return String(format: "%d:%02d min", s / 60, s % 60) }
        return String(format: "%d:%02d h", s / 3600, (s % 3600) / 60)
    }
}

// MARK: - Status oben (Busy + Toast)

struct StatusPill: View {
    @EnvironmentObject private var store: FileStore

    var body: some View {
        ZStack {
            if let busy = store.busy {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text(busy)
                }
                .pill()
                .transition(.move(edge: .top).combined(with: .opacity))
            } else if let toast = store.toast {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(Theme.accent)
                    Text(toast)
                        .lineLimit(2)
                }
                .pill()
                .onTapGesture { store.toast = nil }
                .transition(.move(edge: .top).combined(with: .opacity))
                .task(id: toast) {
                    try? await Task.sleep(for: .seconds(2.6))
                    if !Task.isCancelled, store.toast == toast { store.toast = nil }
                }
            }
        }
        .animation(.smooth(duration: 0.35), value: store.busy)
        .animation(.smooth(duration: 0.35), value: store.toast)
        .padding(.top, 4)
    }
}

private extension View {
    func pill() -> some View {
        self
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .glassEffect(.regular, in: .capsule)
            .padding(.horizontal, 24)
    }
}

// MARK: - Export-Picker (Verschieben/Kopieren in die Dateien-App)

struct ExportPicker: UIViewControllerRepresentable {
    let urls: [URL]
    let asCopy: Bool
    let onFinish: (Bool) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: urls, asCopy: asCopy)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onFinish: (Bool) -> Void

        init(onFinish: @escaping (Bool) -> Void) {
            self.onFinish = onFinish
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onFinish(true)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onFinish(false)
        }
    }
}

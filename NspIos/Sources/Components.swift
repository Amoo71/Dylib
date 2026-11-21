import SwiftUI
import UIKit

// MARK: - Zeilen

struct FileRow: View {
    let item: FileItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(color)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .lineLimit(2)
                Text("\(formatBytes(item.size)) · \(item.modified.formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var icon: String {
        if item.isDirectory { return "folder.fill" }
        switch item.url.pathExtension.lowercased() {
        case "nsp", "nsz": return "shippingbox.fill"
        case "nca", "ncz": return "cube.fill"
        case "tik", "cert": return "key.fill"
        case "xml", "json": return "doc.text.fill"
        case "jpg", "jpeg", "png": return "photo.fill"
        default: return "doc.fill"
        }
    }

    private var color: Color {
        if item.isDirectory { return .blue }
        return item.isPackage ? .red : .secondary
    }
}

struct EmptyRow: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(.secondary)
    }
}

// MARK: - Aktionen (Kontextmenü + Wischen)

private struct ItemActions: ViewModifier {
    @EnvironmentObject private var store: FileStore
    let item: FileItem
    let showContents: ((FileItem) -> Void)?

    func body(content: Content) -> some View {
        content
            .contextMenu {
                if item.isPackage {
                    Button { store.enqueueExtraction(of: item.url) } label: {
                        Label("Entpacken", systemImage: "archivebox")
                    }
                    if let showContents {
                        Button { showContents(item) } label: {
                            Label("Inhalt anzeigen", systemImage: "list.bullet.rectangle")
                        }
                    }
                }
                Button { store.export([item.url], asCopy: false) } label: {
                    Label("In Dateien verschieben", systemImage: "folder.badge.plus")
                }
                Button { store.export([item.url], asCopy: true) } label: {
                    Label("In Dateien kopieren", systemImage: "doc.on.doc")
                }
                if !item.isDirectory {
                    ShareLink(item: item.url) {
                        Label("Teilen", systemImage: "square.and.arrow.up")
                    }
                }
                Divider()
                Button(role: .destructive) { store.delete([item.url]) } label: {
                    Label("Löschen", systemImage: "trash")
                }
            }
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) { store.delete([item.url]) } label: {
                    Label("Löschen", systemImage: "trash")
                }
            }
            .swipeActions(edge: .leading) {
                Button { store.export([item.url], asCopy: false) } label: {
                    Label("Verschieben", systemImage: "folder")
                }
                .tint(.blue)
            }
    }
}

extension View {
    func itemActions(_ item: FileItem, showContents: ((FileItem) -> Void)? = nil) -> some View {
        modifier(ItemActions(item: item, showContents: showContents))
    }
}

// MARK: - Overlays

struct ExtractionOverlay: View {
    let job: ExtractionJob
    let queued: Int
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(spacing: 14) {
                Image(systemName: "archivebox.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.tint)
                Text("Entpacke")
                    .font(.headline)
                Text(job.title)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                ProgressView(value: job.fraction)
                HStack {
                    Text(job.currentFile)
                        .font(.caption.monospaced())
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    Text(job.fraction, format: .percent.precision(.fractionLength(0)))
                        .font(.caption.monospacedDigit())
                }
                .foregroundStyle(.secondary)
                if queued > 0 {
                    Text("Noch \(queued) in der Warteschlange")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button("Abbrechen", role: .destructive, action: onCancel)
                    .buttonStyle(.bordered)
            }
            .padding(22)
            .frame(maxWidth: 360)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(24)
        }
    }
}

struct BusyOverlay: View {
    let text: String

    var body: some View {
        ZStack {
            Color.black.opacity(0.25).ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView()
                Text(text).font(.subheadline)
            }
            .padding(24)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }
}

struct ToastView: View {
    @Binding var text: String?

    var body: some View {
        Group {
            if let text {
                Text(text)
                    .font(.subheadline.weight(.medium))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.thickMaterial, in: Capsule())
                    .shadow(radius: 6, y: 2)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .onTapGesture { self.text = nil }
                    .task(id: text) {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        if !Task.isCancelled { self.text = nil }
                    }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: text)
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

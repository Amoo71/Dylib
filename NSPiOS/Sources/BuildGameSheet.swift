import SwiftUI

/// Baut aus einem entpackten Ordner eine spielbare nxnative-App (Etappe 2: Aufnahme-Lauf ohne Shader).
struct BuildGameSheet: View {
    let programs: [GameProgram]

    @ObservedObject private var builder = GameBuilder.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selected: GameProgram?
    @State private var resultText: String?
    @State private var failed = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(programs) { program in
                        Button { selected = program } label: {
                            HStack(spacing: 12) {
                                Image(systemName: program.isSupported ? "gamecontroller.fill" : "questionmark.square.dashed")
                                    .foregroundStyle(program.isSupported ? Theme.accent : .secondary)
                                    .frame(width: 28)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(program.name).font(.body.weight(.medium))
                                    Text(program.titleID).font(.caption.monospaced()).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if selected == program {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.accent)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                        .listRowBackground(Theme.rowBackground)
                    }
                }

                Section {
                    Button {
                        guard let program = selected ?? programs.first else { return }
                        Task { await build(program) }
                    } label: {
                        Label(L("Build", "Bauen"), systemImage: "hammer.fill")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.glassProminent)
                    .disabled(builder.running || programs.isEmpty)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                }

                if builder.running || resultText != nil {
                    Section {
                        if builder.running {
                            HStack(spacing: 10) {
                                ProgressView().controlSize(.small)
                                Text(builder.progress ?? L("Working …", "Arbeite …"))
                                    .font(.caption.monospaced())
                                    .lineLimit(3)
                            }
                        }
                        if let resultText {
                            Text(resultText)
                                .font(.caption.monospaced())
                                .foregroundStyle(failed ? Theme.danger : .secondary)
                                .textSelection(.enabled)
                        }
                    }
                    .listRowBackground(Theme.rowBackground)
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle(L("Build game", "Spiel bauen"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .disabled(builder.running)
                }
            }
            .onAppear { selected = selected ?? programs.first }
            .interactiveDismissDisabled(builder.running)
        }
    }

    private func build(_ program: GameProgram) async {
        resultText = nil
        failed = false
        do {
            let app = try await builder.build(program, noShaders: true)
            resultText = L("App built (capture run): ", "App gebaut (Aufnahme-Lauf): ") + app.lastPathComponent
                + "\n" + app.deletingLastPathComponent().path
        } catch {
            failed = true
            resultText = error.localizedDescription
        }
    }
}

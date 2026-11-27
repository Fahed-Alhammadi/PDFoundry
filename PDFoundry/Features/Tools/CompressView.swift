import PDFKit
import SwiftUI

/// Compress from the Tools tab: choose Smaller or Smallest, then a compressed copy is saved in the library as
/// "<name> (compressed)". The source is only read, never written, and it is not added to the library.
struct CompressView: View {
    let item: PDFItem
    let onSaved: (PDFItem) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(DocumentStore.self) private var store

    @State private var source = ToolSource()
    @State private var level = Compress.Level.smaller
    @State private var isWorking = false
    @State private var result: String?
    @State private var errorMessage: String?

    private static func size(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Compress")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(result == nil ? "Cancel" : "Done") { dismiss() }
                    }
                    if source.pageCount != nil, result == nil {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Compress", action: compress)
                                .disabled(isWorking)
                        }
                    }
                }
        }
        .task { await source.load(item) }
        .alert("Couldn't Compress", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var content: some View {
        if source.pageCount != nil, let data = source.data {
            Form {
                Section {
                    Picker("Size", selection: $level) {
                        ForEach(Compress.Level.allCases, id: \.self) { level in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(level.title)
                                Text(level.detail).font(.caption).foregroundStyle(.secondary)
                            }
                            .tag(level)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    .disabled(isWorking || result != nil)
                } header: {
                    Text("“\(item.name)” · \(Self.size(data.count))")
                } footer: {
                    Text("Only pictures are made smaller. Text stays text, and “\(item.name)” stays as it is.")
                }

                if isWorking {
                    Section { ProgressView("Compressing…") }
                } else if let result {
                    Section { Label(result, systemImage: "checkmark.circle.fill") }
                }
            }
        } else {
            source.placeholder(for: item) {}
        }
    }

    private func compress() {
        guard let data = source.data else { return }
        isWorking = true
        Task {
            defer { isWorking = false }
            do {
                guard let smaller = try await Compress.run(data: data, password: source.password, level: level, name: item.name) else {
                    result = Compress.noGainMessage
                    return
                }
                let saved = try await store.saveCompressed(smaller, named: item.name)
                result = "\(Self.size(data.count)) → \(Self.size(smaller.count)). Saved as “\(saved.name)” in Documents."
                onSaved(saved)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

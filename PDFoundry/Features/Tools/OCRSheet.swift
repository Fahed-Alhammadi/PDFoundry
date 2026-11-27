import SwiftUI

/// Make Searchable, from the reader: says how many pages will be read, reads them with progress and Cancel, and
/// says what it found. Cancel changes nothing.
struct OCRSheet: View {
    let model: ReaderModel

    @Environment(\.dismiss) private var dismiss
    @State private var task: Task<Void, Never>?
    @State private var progress: (done: Int, total: Int)?
    @State private var outcome: String?
    @State private var errorMessage: String?

    private var pages: Int { model.pagesNeedingText.count }

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Image(systemName: "text.viewfinder")
                    .font(.system(size: 44, weight: .medium))
                    .foregroundStyle(.tint)
                    .padding(.top, 24)
                Text(headline)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                if let progress {
                    ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
                        .padding(.horizontal, 40)
                    Text("Reading page \(min(progress.done + 1, progress.total)) of \(progress.total)…")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                } else if outcome == nil {
                    Text("Text is added over the words, so you can search, select and copy it. The pages look the same.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity)
            .navigationTitle("Make Searchable")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(outcome == nil ? "Cancel" : "Done") {
                        task?.cancel()
                        dismiss()
                    }
                }
                if outcome == nil, progress == nil, pages > 0 {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Start", action: start)
                    }
                }
            }
        }
        .interactiveDismissDisabled(progress != nil)
        .alert("Couldn't Read the Pages", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var headline: String {
        if let outcome { return outcome }
        switch pages {
        case 0: return "Every page already has text."
        case 1: return "1 page has no text yet and will be read."
        default: return "\(pages) pages have no text yet and will be read."
        }
    }

    private func start() {
        progress = (0, pages)
        task = Task {
            do {
                let count = try await model.makeSearchable { done, total in progress = (done, total) }
                progress = nil
                outcome = count == 0 ? OCR.nothingFoundMessage
                    : (count == 1 ? "Text was added to 1 page." : "Text was added to \(count) pages.")
            } catch is CancellationError {
                progress = nil
            } catch {
                progress = nil
                errorMessage = error.localizedDescription
            }
        }
    }
}

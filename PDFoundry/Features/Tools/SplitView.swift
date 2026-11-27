import PDFKit
import SwiftUI

/// How Split PDF divides a document, and whether the choice can be used.
struct SplitPlan {
    enum Mode: Hashable, CaseIterable {
        case everyPage, everyN, custom

        var title: String {
            switch self {
            case .everyPage: "Every page"
            case .everyN: "Every N pages"
            case .custom: "Custom ranges"
            }
        }
    }

    let pageCount: Int
    var mode = Mode.everyN
    var n = 2
    var ranges = ""

    var parts: Result<[[Int]], RangeError> {
        switch mode {
        case .everyPage: .success(PageRanges.every(1, pageCount: pageCount))
        case .everyN: .success(PageRanges.every(n, pageCount: pageCount))
        case .custom: PageRanges.parse(ranges, pageCount: pageCount)
        }
    }

    var error: String? {
        if case .failure(let error) = parts { return error.localizedDescription }
        return nil
    }

    var canSplit: Bool {
        if case .success(let parts) = parts { return !parts.isEmpty }
        return false
    }

    /// "Makes 3 files.", or nil while the ranges can't be used.
    var summary: String? {
        guard case .success(let parts) = parts, !parts.isEmpty else { return nil }
        return parts.count == 1 ? "Makes 1 file." : "Makes \(parts.count) files."
    }
}

/// Split PDF from the Tools tab: choose how, then Split writes the parts as new documents in the library.
/// The source is only read, never written, and it is not added to the library.
struct SplitView: View {
    let item: PDFItem
    let onSplit: ([PDFItem]) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(DocumentStore.self) private var store

    @State private var source = ToolSource()
    @State private var plan: SplitPlan?
    @State private var isWorking = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Split PDF")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    if plan != nil {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Split", action: split)
                                .disabled(!(plan?.canSplit ?? false) || isWorking)
                        }
                    }
                }
        }
        .task {
            await source.load(item)
            if let count = source.pageCount { plan = SplitPlan(pageCount: count) }
        }
        .onChange(of: source.pageCount) { _, count in
            if plan == nil, let count { plan = SplitPlan(pageCount: count) }
        }
        .alert("Couldn't Split", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var content: some View {
        if let binding = Binding($plan) {
            form(binding)
        } else {
            source.placeholder(for: item) {}
        }
    }

    private func form(_ plan: Binding<SplitPlan>) -> some View {
        Form {
            Section {
                Picker("Split", selection: plan.mode) {
                    ForEach(SplitPlan.Mode.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("“\(item.name)” · \(plan.wrappedValue.pageCount == 1 ? "1 page" : "\(plan.wrappedValue.pageCount) pages")")
            }

            switch plan.wrappedValue.mode {
            case .everyN:
                Section {
                    Stepper(value: plan.n, in: 1...max(1, plan.wrappedValue.pageCount)) {
                        Text(plan.wrappedValue.n == 1 ? "Every page" : "Every \(plan.wrappedValue.n) pages")
                    }
                }
            case .custom:
                Section {
                    TextField("1-3, 4-6, 9", text: plan.ranges)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } footer: {
                    Text("Each range becomes one file. Separate ranges with commas.")
                }
            case .everyPage:
                EmptyView()
            }

            Section {
                if let error = plan.wrappedValue.error {
                    Label(error, systemImage: "exclamationmark.circle.fill")
                        .foregroundStyle(.red)
                } else if let summary = plan.wrappedValue.summary {
                    Label(summary, systemImage: "doc.on.doc")
                }
            } footer: {
                Text("The new files are named “\(item.name) (part 1)”, “(part 2)” and so on. “\(item.name)” stays as it is.")
            }
        }
    }

    private func split() {
        guard let plan, case .success(let parts) = plan.parts, !parts.isEmpty else { return }
        isWorking = true
        Task {
            defer { isWorking = false }
            do {
                let created = try await store.split(item.url, into: parts, password: source.password)
                onSplit(created)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

/// The file a tool reads (Split, Extract): read once into memory, unlocked with its password if it has one.
/// The password stays in memory for this one job.
@Observable
final class ToolSource {
    private(set) var isLoading = true
    private(set) var data: Data?
    private(set) var pageCount: Int?
    private(set) var needsPassword = false
    private(set) var password: String?
    private(set) var problem: String?
    private(set) var unlockError: String?

    func load(_ item: PDFItem) async {
        guard isLoading, data == nil else { return }
        data = await DocumentStore.readCoordinated(item.url)
        defer { isLoading = false }
        guard let data, let locked = await Self.isLocked(data) else {
            problem = "“\(item.name)” isn't a readable PDF."
            return
        }
        if locked {
            needsPassword = true
        } else {
            await take(item, password: nil)
        }
    }

    /// Whether the bytes are a PDF that asks for a password; nil if they aren't a PDF. Parsed off the main thread.
    @concurrent
    private nonisolated static func isLocked(_ data: Data) async -> Bool? {
        PDFDocument(data: data).map(\.isLocked)
    }

    /// Checks a password (it must give full access) and counts the pages, off the main thread.
    @concurrent
    private nonisolated static func check(_ data: Data?, password: String?, name: String) async -> Result<(pages: Int, encrypted: Bool), PDFoundryError> {
        do {
            let document = try PDFOperations.fullAccess(data, password: password, name: name)
            return .success((document.pageCount, document.isEncrypted))
        } catch let error as PDFoundryError {
            return .failure(error)
        } catch {
            return .failure(.unreadable(name))
        }
    }

    /// Checks the password (it must give full access) and reads the page count.
    func take(_ item: PDFItem, password: String?) async {
        switch await Self.check(data, password: password, name: item.name) {
        case .success(let result):
            self.password = result.encrypted ? password : nil
            pageCount = result.pages
            needsPassword = false
            unlockError = nil
        case .failure(.wrongPassword):
            unlockError = PDFoundryError.wrongPassword.localizedDescription
        case .failure(let error):
            needsPassword = false
            problem = error.localizedDescription
        }
    }

    /// What to show until the file is ready: progress, the password prompt, or why it can't be used.
    @ViewBuilder
    func placeholder(for item: PDFItem, onReady: @escaping () -> Void) -> some View {
        if isLoading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if needsPassword {
            PasswordPrompt(name: item.name, error: unlockError) { password in
                Task {
                    await self.take(item, password: password)
                    if self.pageCount != nil { onReady() }
                }
            }
        } else if let problem {
            ContentUnavailableView("Unable to Use", systemImage: "doc.questionmark", description: Text(problem))
        }
    }
}

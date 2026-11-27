import PDFKit
import SwiftUI

/// What Export as Images makes: which pages, the format and the resolution.
struct ExportPlan {
    enum Pages: Hashable { case all, custom }

    let pageCount: Int
    var pages = Pages.all
    var ranges = ""
    var png = true
    var print = false

    var dpi: CGFloat { print ? ExportImages.printDPI : ExportImages.screenDPI }

    /// The chosen pages (zero-based, in order, each once), or why the ranges can't be used.
    var chosen: Result<[Int], RangeError> {
        switch pages {
        case .all: return .success(Array(0..<pageCount))
        case .custom:
            return PageRanges.parse(ranges, pageCount: pageCount).map { parts in
                var seen = Set<Int>()
                return parts.flatMap { $0 }.filter { seen.insert($0).inserted }
            }
        }
    }

    var error: String? {
        if case .failure(let error) = chosen { return error.localizedDescription }
        return nil
    }

    var summary: String? {
        guard case .success(let pages) = chosen, !pages.isEmpty else { return nil }
        return pages.count == 1 ? "Makes 1 picture." : "Makes \(pages.count) pictures."
    }
}

/// Export as Images from the Tools tab: the pictures go to the share sheet (Save to Files, Save Image, AirDrop…).
/// Saving to Photos is the share sheet's own action; the system asks for permission then, not before.
struct ExportImagesView: View {
    let item: PDFItem

    @Environment(\.dismiss) private var dismiss
    @State private var source = ToolSource()
    @State private var plan: ExportPlan?
    @State private var isWorking = false
    @State private var shared: [URL]?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Export as Images")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    if plan != nil {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Export", action: export)
                                .disabled(plan?.summary == nil || isWorking)
                        }
                    }
                }
        }
        .task {
            await source.load(item)
            if let count = source.pageCount { plan = ExportPlan(pageCount: count) }
        }
        .onChange(of: source.pageCount) { _, count in
            if plan == nil, let count { plan = ExportPlan(pageCount: count) }
        }
        .sheet(isPresented: Binding(get: { shared != nil }, set: { if !$0 { finishSharing() } })) {
            if let shared { ShareSheet(items: shared) }
        }
        .alert("Couldn't Export", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
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

    private func form(_ plan: Binding<ExportPlan>) -> some View {
        Form {
            Section {
                Picker("Pages", selection: plan.pages) {
                    Text("All pages").tag(ExportPlan.Pages.all)
                    Text("Custom ranges").tag(ExportPlan.Pages.custom)
                }
                .pickerStyle(.segmented)
                if plan.wrappedValue.pages == .custom {
                    TextField("1-3, 5", text: plan.ranges)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
            } header: {
                Text("“\(item.name)” · \(plan.wrappedValue.pageCount == 1 ? "1 page" : "\(plan.wrappedValue.pageCount) pages")")
            }

            Section("Format") {
                Picker("Format", selection: plan.png) {
                    Text("PNG").tag(true)
                    Text("JPEG").tag(false)
                }
                .pickerStyle(.segmented)
                Picker("Quality", selection: plan.print) {
                    Text("Screen (150 dpi)").tag(false)
                    Text("Print (300 dpi)").tag(true)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }

            Section {
                if isWorking {
                    ProgressView("Making pictures…")
                } else if let error = plan.wrappedValue.error {
                    Label(error, systemImage: "exclamationmark.circle.fill").foregroundStyle(.red)
                } else if let summary = plan.wrappedValue.summary {
                    Label(summary, systemImage: "photo.stack")
                }
            } footer: {
                Text("The pictures are named “\(item.name) page 1” and so on. “\(item.name)” stays as it is.")
            }
        }
    }

    private func export() {
        guard let plan, case .success(let pages) = plan.chosen, let data = source.data,
              let document = PDFDocument(data: data) else { return }
        if document.isLocked, let password = source.password { _ = document.unlock(withPassword: password) }
        isWorking = true
        Task {
            defer { isWorking = false }
            do {
                shared = try await ExportImages.run(document, pages: pages, png: plan.png, dpi: plan.dpi, baseName: item.name)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// The share sheet is done: its temporary pictures are removed.
    private func finishSharing() {
        if let shared { ExportImages.cleanUp(shared) }
        shared = nil
    }
}

/// The system share sheet for one or more files.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [URL]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

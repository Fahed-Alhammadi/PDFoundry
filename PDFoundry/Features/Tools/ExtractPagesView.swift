import PDFKit
import SwiftUI

/// Extract Pages from the Tools tab: tick pages, then Extract saves them as a new document in the library.
///
/// Only a copy of the file is shown here, read into memory; nothing on this screen can change the source (on the
/// phones every change to an open document is saved straight into the real file). It doesn't open the reader or
/// add the source to the library.
struct ExtractPagesView: View {
    let item: PDFItem
    let onExtracted: (PDFItem) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(DocumentStore.self) private var store
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// The file read into memory, unlocked with its password if it has one.
    @State private var source = ToolSource()
    /// Renders the pages away from the main thread; nil until the file is read, or if it can't be.
    @State private var renderer: PageRenderer?
    @State private var pageCount = 0
    @State private var isLoading = true
    @State private var selection: Set<Int> = []
    @State private var isWorking = false
    @State private var errorMessage: String?

    private var isWide: Bool { sizeClass == .regular }
    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: isWide ? 170 : 100, maximum: isWide ? 240 : 150), spacing: isWide ? 22 : 14)]
    }

    var body: some View {
        NavigationStack {
            content
                .background(Color(.systemGroupedBackground))
                .navigationTitle(selection.isEmpty ? "Extract Pages" : "\(selection.count) Selected")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(selection.count == pageCount && pageCount > 0 ? "Deselect All" : "Select All") {
                            selection = selection.count == pageCount ? [] : Set(0..<pageCount)
                        }
                        .disabled(pageCount == 0)
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    if renderer != nil {
                        Button(action: extract) {
                            Text(selection.count == 1 ? "Extract 1 Page" : selection.isEmpty ? "Extract" : "Extract \(selection.count) Pages")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glassProminent)
                        .controlSize(.large)
                        .disabled(selection.isEmpty || isWorking)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 8)
                    }
                }
        }
        .task { await load() }
        .alert("Couldn't Extract", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var content: some View {
        if isLoading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let renderer {
            ScrollView {
                Text("Tap the pages to copy into a new document. “\(item.name)” stays as it is.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                    .padding(.top, 6)
                LazyVGrid(columns: columns, spacing: 18) {
                    ForEach(0..<pageCount, id: \.self) { index in
                        cell(renderer, index: index)
                    }
                }
                .padding(16)
            }
        } else if source.needsPassword || source.problem != nil {
            source.placeholder(for: item) {
                Task { await makeRenderer() }
            }
        } else {
            ContentUnavailableView("Unable to Open", systemImage: "doc.questionmark", description: Text("“\(item.name)” isn't a readable PDF."))
        }
    }

    private func cell(_ renderer: PageRenderer, index: Int) -> some View {
        let isSelected = selection.contains(index)
        return Button {
            if isSelected { selection.remove(index) } else { selection.insert(index) }
        } label: {
            VStack(spacing: 6) {
                ExtractThumbnail(renderer: renderer, index: index)
                    .overlay {
                        Rectangle().strokeBorder(isSelected ? Color.accentColor : Color.primary.opacity(0.1), lineWidth: isSelected ? 3 : 1)
                    }
                    .shadow(color: .black.opacity(0.1), radius: 4, y: 2)
                    .overlay(alignment: .bottomTrailing) {
                        // An empty ring on the white page until the page is picked.
                        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(isSelected ? Color.white : Color.black.opacity(0.3), Color.accentColor)
                            .padding(5)
                    }
                    .frame(height: isWide ? 220 : 140)
                Text("\(index + 1)")
                    .font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(isSelected ? Color.accentColor : .secondary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Page \(index + 1)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func load() async {
        guard renderer == nil else { return }
        // A copy in memory: the file itself is never opened for writing here.
        await source.load(item)
        await makeRenderer()
        isLoading = false
    }

    /// Once the file is read (and unlocked, if it has a password), its pages are drawn.
    private func makeRenderer() async {
        guard renderer == nil, let data = source.data, source.pageCount != nil else { return }
        let candidate = PageRenderer(data: data, password: source.password)
        // Parsed inside the renderer, off the main thread.
        if let count = await candidate.pageCount(), count > 0 {
            renderer = candidate
            pageCount = count
        }
    }

    private func extract() {
        isWorking = true
        let pages = IndexSet(selection)
        Task {
            defer { isWorking = false }
            do {
                let created = try await store.extractPages(from: item.url, pages: pages, password: source.password)
                onExtracted(created)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

/// Reads a PDF from bytes and draws page thumbnails, one at a time, away from the main thread.
private actor PageRenderer {
    private let data: Data
    private let password: String?
    private var document: PDFDocument?
    private var didParse = false

    init(data: Data, password: String?) {
        self.data = data
        self.password = password
    }

    private func parsed() -> PDFDocument? {
        if !didParse {
            didParse = true
            document = PDFDocument(data: data)
            if let password, document?.isLocked == true { _ = document?.unlock(withPassword: password) }
        }
        return document
    }

    /// The number of pages, or nil if the bytes aren't a readable PDF.
    func pageCount() -> Int? {
        guard let document = parsed(), !document.isLocked else { return nil }
        return document.pageCount
    }

    func thumbnail(at index: Int) -> UIImage? {
        parsed()?.page(at: index)?.thumbnail(of: CGSize(width: 420, height: 560), for: .cropBox)
    }
}

private struct ExtractThumbnail: View {
    let renderer: PageRenderer
    let index: Int
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                Color.white.aspectRatio(0.77, contentMode: .fit)
            }
        }
        .task(id: index) {
            if image == nil { image = await renderer.thumbnail(at: index) }
        }
    }
}

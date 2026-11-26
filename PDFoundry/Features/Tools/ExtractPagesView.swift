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

    @State private var document: PDFDocument?
    @State private var isLoading = true
    @State private var selection: Set<Int> = []
    @State private var isWorking = false
    @State private var errorMessage: String?

    private var pageCount: Int { document?.pageCount ?? 0 }
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
                    if document != nil {
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
        } else if let document {
            ScrollView {
                Text("Tap the pages to copy into a new document. “\(item.name)” stays as it is.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                    .padding(.top, 6)
                LazyVGrid(columns: columns, spacing: 18) {
                    ForEach(0..<document.pageCount, id: \.self) { index in
                        if let page = document.page(at: index) {
                            cell(page, index: index)
                        }
                    }
                }
                .padding(16)
            }
        } else {
            ContentUnavailableView("Unable to Open", systemImage: "doc.questionmark", description: Text("“\(item.name)” isn't a readable PDF."))
        }
    }

    private func cell(_ page: PDFPage, index: Int) -> some View {
        let isSelected = selection.contains(index)
        return Button {
            if isSelected { selection.remove(index) } else { selection.insert(index) }
        } label: {
            VStack(spacing: 6) {
                ExtractThumbnail(page: page)
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
        guard document == nil else { return }
        // A copy in memory: the file itself is never opened for writing here.
        document = await DocumentStore.readCoordinated(item.url).flatMap(PDFDocument.init(data:))
        isLoading = false
    }

    private func extract() {
        isWorking = true
        let pages = IndexSet(selection)
        Task {
            defer { isWorking = false }
            do {
                let created = try await store.extractPages(from: item.url, pages: pages)
                onExtracted(created)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

private struct ExtractThumbnail: View {
    let page: PDFPage
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                Color.white.aspectRatio(0.77, contentMode: .fit)
            }
        }
        .onAppear {
            if image == nil { image = page.thumbnail(of: CGSize(width: 420, height: 560), for: .cropBox) }
        }
    }
}

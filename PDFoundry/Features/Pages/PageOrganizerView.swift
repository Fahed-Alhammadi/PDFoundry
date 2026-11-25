import PDFKit
import SwiftUI

/// Reorder, rotate, duplicate, extract, add and delete pages.
struct PageOrganizerView: View {
    private struct PageItem: Identifiable {
        let id: UUID
        let page: PDFPage
    }

    @Environment(\.dismiss) private var dismiss
    @Environment(DocumentStore.self) private var store
    @Environment(\.horizontalSizeClass) private var sizeClass
    let model: ReaderModel

    @State private var pages: [PageItem] = []
    @State private var selection: Set<UUID> = []
    @State private var renderVersion = 0
    @State private var didChange = false
    @State private var confirmDelete = false
    @State private var notice: String?

    /// Larger thumbnails on iPad, where there is room for them.
    private var isWide: Bool { sizeClass == .regular }
    private var thumbnailHeight: CGFloat { isWide ? 250 : 150 }
    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: isWide ? 190 : 104, maximum: isWide ? 260 : 160), spacing: isWide ? 24 : 16)]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 18) {
                    ForEach(Array(pages.enumerated()), id: \.element.id) { index, item in
                        cell(item, number: index + 1)
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(selection.isEmpty ? "Pages" : "\(selection.count) Selected")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .safeAreaInset(edge: .top) {
                Text(notice ?? "Tap to select. Drag a page onto another to move it.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity)
            }
            .confirmationDialog(
                selection.count == 1 ? "Delete this page?" : "Delete \(selection.count) pages?",
                isPresented: $confirmDelete,
                titleVisibility: .visible
            ) {
                Button("Delete", role: .destructive, action: deleteSelected)
            } message: {
                Text("This can't be undone.")
            }
        }
        .onAppear(perform: reload)
        .onDisappear {
            if didChange { model.pagesDidChange() }
        }
    }

    private func cell(_ item: PageItem, number: Int) -> some View {
        let isSelected = selection.contains(item.id)
        return Button {
            if isSelected {
                selection.remove(item.id)
            } else {
                selection.insert(item.id)
            }
        } label: {
            VStack(spacing: 6) {
                PageThumbnail(page: item.page)
                    .id("\(item.id)-\(renderVersion)")
                    .overlay {
                        Rectangle().strokeBorder(isSelected ? Color.accentColor : Color.primary.opacity(0.1), lineWidth: isSelected ? 3 : 1)
                    }
                    .shadow(color: .black.opacity(0.1), radius: 4, y: 2)
                    .overlay(alignment: .topTrailing) {
                        if isSelected {
                            Image(systemName: "checkmark.circle.fill")
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, Color.accentColor)
                                .font(.title3)
                                .padding(5)
                        }
                    }
                    .padding(.horizontal, 4)
                    .frame(height: thumbnailHeight)
                Text("\(number)")
                    .font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(isSelected ? Color.accentColor : .secondary)
            }
        }
        .buttonStyle(.plain)
        .draggable(item.id.uuidString)
        .dropDestination(for: String.self) { ids, _ in
            guard let first = ids.first, let source = UUID(uuidString: first) else { return false }
            return move(source, onto: item.id)
        }
        .accessibilityLabel("Page \(number)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Menu("Add", systemImage: "plus") {
                Button("Add Blank Page", systemImage: "doc.badge.plus", action: addBlankPage)
                Button(selection.count == pages.count ? "Deselect All" : "Select All", systemImage: "checkmark.circle") {
                    selection = selection.count == pages.count ? [] : Set(pages.map(\.id))
                }
            }
        }
        ToolbarItem(placement: .confirmationAction) {
            Button("Done") { dismiss() }
        }
        ToolbarItemGroup(placement: .bottomBar) {
            Button("Rotate Left", systemImage: "rotate.left") { rotate(by: -90) }
            Spacer()
            Button("Rotate Right", systemImage: "rotate.right") { rotate(by: 90) }
            Spacer()
            Button("Duplicate", systemImage: "plus.square.on.square", action: duplicateSelected)
            Spacer()
            Button("Extract", systemImage: "arrow.up.doc", action: extractSelected)
            Spacer()
            Button("Delete", systemImage: "trash", role: .destructive) { confirmDelete = true }
                .disabled(selection.isEmpty || selection.count >= pages.count)
        }
    }

    // MARK: Actions

    private var document: PDFDocument? { model.document }

    private var selectedIndexes: [Int] {
        pages.indices.filter { selection.contains(pages[$0].id) }
    }

    /// Rebuilds the grid from the document, keeping the identity of pages already shown.
    private func reload() {
        guard let document else { return }
        var known: [ObjectIdentifier: UUID] = [:]
        for item in pages { known[ObjectIdentifier(item.page)] = item.id }
        pages = (0..<document.pageCount).compactMap { index in
            document.page(at: index).map { PageItem(id: known[ObjectIdentifier($0)] ?? UUID(), page: $0) }
        }
        selection.formIntersection(pages.map(\.id))
    }

    private func changed() {
        didChange = true
        notice = nil
        reload()
    }

    private func requireSelection() -> Bool {
        if selection.isEmpty {
            notice = "Select one or more pages first."
            return false
        }
        return true
    }

    private func rotate(by degrees: Int) {
        guard requireSelection() else { return }
        for index in selectedIndexes {
            let page = pages[index].page
            page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
        }
        renderVersion += 1
        changed()
    }

    private func duplicateSelected() {
        guard requireSelection(), let document else { return }
        for index in selectedIndexes.reversed() {
            guard let copy = pages[index].page.copy() as? PDFPage else { continue }
            document.insert(copy, at: index + 1)
        }
        changed()
    }

    private func deleteSelected() {
        guard let document, selection.count < pages.count else { return }
        for index in selectedIndexes.reversed() { document.removePage(at: index) }
        selection = []
        changed()
    }

    private func extractSelected() {
        guard requireSelection(), let document else { return }
        do {
            let extracted = try PDFOperations.extract(selectedIndexes, from: document)
            let count = extracted.pageCount
            Task {
                do {
                    let url = try await store.save(extracted, named: model.title + " extract")
                    notice = "Saved \(count == 1 ? "1 page" : "\(count) pages") as “\(url.deletingPathExtension().lastPathComponent)”."
                } catch {
                    notice = error.localizedDescription
                }
            }
        } catch {
            notice = error.localizedDescription
        }
    }

    private func addBlankPage() {
        guard let document else { return }
        let blank = PDFPage()
        let insertAt = (selectedIndexes.last ?? pages.count - 1) + 1
        if let neighbor = document.page(at: max(0, insertAt - 1)) {
            blank.setBounds(neighbor.bounds(for: .mediaBox), for: .mediaBox)
        }
        document.insert(blank, at: min(insertAt, document.pageCount))
        changed()
    }

    private func move(_ source: UUID, onto target: UUID) -> Bool {
        guard let document, source != target,
              let from = pages.firstIndex(where: { $0.id == source }),
              let to = pages.firstIndex(where: { $0.id == target }) else { return false }
        let page = pages[from].page
        document.removePage(at: from)
        document.insert(page, at: to)
        withAnimation(.snappy) { changed() }
        return true
    }
}

private struct PageThumbnail: View {
    let page: PDFPage
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            } else {
                Color.white.aspectRatio(0.77, contentMode: .fit)
            }
        }        .onAppear {
            if image == nil { image = page.thumbnail(of: CGSize(width: 510, height: 680), for: .cropBox) }
        }
    }
}

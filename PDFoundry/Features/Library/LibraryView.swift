import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VisionKit

struct LibraryView: View {
    @Environment(DocumentStore.self) private var store
    @Environment(\.horizontalSizeClass) private var sizeClass
    @AppStorage(SettingsKey.librarySort) private var sort = LibrarySort.recent

    @State private var path: [URL] = []
    @State private var searchText = ""
    @State private var isSelecting = false
    @State private var selection: Set<URL> = []

    @State private var showImporter = false
    @State private var showScanner = false
    @State private var showPhotoPicker = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var mergeCandidates: [PDFItem]?
    @State private var renameTarget: PDFItem?
    @State private var renameText = ""
    @State private var deleteTargets: [PDFItem] = []
    @State private var errorMessage: String?

    /// Larger cards on iPad, where there is room for them.
    private var columns: [GridItem] {
        let minimum: CGFloat = sizeClass == .regular ? 210 : 150
        return [GridItem(.adaptive(minimum: minimum, maximum: minimum + 90), spacing: 18, alignment: .top)]
    }

    private var visibleItems: [PDFItem] { store.sorted(sort, matching: searchText) }
    private var selectedItems: [PDFItem] { visibleItems.filter { selection.contains($0.url) } }

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationTitle(isSelecting ? selectionTitle : "Documents")
                .navigationDestination(for: URL.self) { ReaderView(url: $0) }
                .searchable(text: $searchText, prompt: "Search documents")
                .toolbar { toolbar }
                .toolbar(isSelecting ? .hidden : .automatic, for: .tabBar)
                .background(Color(.systemGroupedBackground))
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.pdf], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            perform { try await store.importFiles(urls) }
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItems, maxSelectionCount: 30, matching: .images)
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            photoItems = []
            perform { try await createFromPhotos(items) }
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScanner { images in
                perform { try await store.create(from: images, named: "Scan \(Date.now.formatted(date: .abbreviated, time: .shortened))") }
            }
            .ignoresSafeArea()
        }
        .sheet(item: Binding(get: { mergeCandidates.map(MergeRequest.init) }, set: { mergeCandidates = $0?.items })) { request in
            MergeSheet(items: request.items) { ordered, name, deleteOriginals in
                perform {
                    let url = try await store.merge(ordered, named: name, deleteOriginals: deleteOriginals)
                    endSelection()
                    path = [url]
                }
            }
        }
        .alert("Rename Document", isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") {
                guard let target = renameTarget else { return }
                perform { try await store.rename(target, to: renameText) }
            }
        }
        .confirmationDialog(deleteTitle, isPresented: Binding(get: { !deleteTargets.isEmpty }, set: { if !$0 { deleteTargets = [] } }), titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                let targets = deleteTargets
                Task {
                    await store.delete(targets)
                    endSelection()
                }
            }
        } message: {
            Text("This can't be undone.")
        }
        .alert("Something Went Wrong", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
        .onChange(of: store.pendingOpen) { _, url in
            guard let url else { return }
            store.pendingOpen = nil
            endSelection()
            path = [url]
        }
        .onChange(of: path) { _, newPath in
            if newPath.isEmpty { Task { await store.refresh() } }
        }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if !store.isLoaded {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if store.items.isEmpty {
            ContentUnavailableView {
                Label("No Documents", systemImage: "doc.viewfinder")
            } description: {
                Text("Import a PDF from Files, scan paper with the camera, or build one from your photos.")
            } actions: {
                Button("Import PDF") { showImporter = true }
                    .buttonStyle(.glassProminent)
            }
        } else if visibleItems.isEmpty {
            ContentUnavailableView.search(text: searchText)
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 22) {
                    ForEach(visibleItems) { item in
                        cell(for: item)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
            }
        }
    }

    private func cell(for item: PDFItem) -> some View {
        Button {
            if isSelecting {
                toggle(item)
            } else {
                path.append(item.url)
            }
        } label: {
            DocumentCell(item: item, isSelecting: isSelecting, isSelected: selection.contains(item.url))
        }
        .buttonStyle(.plain)
        .contextMenu {
            if !isSelecting {
                Button("Open", systemImage: "doc.text") { path.append(item.url) }
                Button("Rename", systemImage: "pencil") {
                    renameText = item.name
                    renameTarget = item
                }
                Button("Duplicate", systemImage: "plus.square.on.square") {
                    perform { try await store.duplicate(item) }
                }
                Button("Merge with Others", systemImage: "arrow.triangle.merge") {
                    isSelecting = true
                    selection = [item.url]
                }
                ShareLink(item: item.url) { Label("Share", systemImage: "square.and.arrow.up") }
                Divider()
                Button("Delete", systemImage: "trash", role: .destructive) { deleteTargets = [item] }
            }
        }
        .accessibilityLabel("\(item.name), \(item.detail)")
        .accessibilityAddTraits(selection.contains(item.url) ? .isSelected : [])
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if isSelecting {
            ToolbarItem(placement: .topBarLeading) {
                Button(selection.count == visibleItems.count ? "Deselect All" : "Select All") {
                    selection = selection.count == visibleItems.count ? [] : Set(visibleItems.map(\.url))
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { endSelection() }
            }
            ToolbarItemGroup(placement: .bottomBar) {
                Button("Merge", systemImage: "arrow.triangle.merge") { mergeCandidates = selectedItems }
                    .disabled(selection.count < 2)
                Spacer()
                ShareLink(items: selectedItems.map(\.url)) { Label("Share", systemImage: "square.and.arrow.up") }
                    .disabled(selection.isEmpty)
                Spacer()
                Button("Delete", systemImage: "trash", role: .destructive) { deleteTargets = selectedItems }
                    .disabled(selection.isEmpty)
            }
        } else {
            ToolbarItem(placement: .topBarLeading) {
                Button("Select") { isSelecting = true }
                    .disabled(store.items.isEmpty)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu("Sort", systemImage: "arrow.up.arrow.down") {
                    Picker("Sort By", selection: $sort) {
                        ForEach(LibrarySort.allCases) { Text($0.title).tag($0) }
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu("Add", systemImage: "plus") {
                    Button("Import from Files", systemImage: "folder") { showImporter = true }
                    if VNDocumentCameraViewController.isSupported {
                        Button("Scan Document", systemImage: "document.viewfinder") { showScanner = true }
                    }
                    Button("Create from Photos", systemImage: "photo.on.rectangle") { showPhotoPicker = true }
                }
            }
        }
    }

    // MARK: Actions

    private var selectionTitle: String {
        selection.isEmpty ? "Select Documents" : "\(selection.count) Selected"
    }

    private var deleteTitle: String {
        deleteTargets.count == 1 ? "Delete “\(deleteTargets[0].name)”?" : "Delete \(deleteTargets.count) documents?"
    }

    private func toggle(_ item: PDFItem) {
        if selection.contains(item.url) {
            selection.remove(item.url)
        } else {
            selection.insert(item.url)
        }
    }

    private func endSelection() {
        isSelecting = false
        selection = []
    }

    private func perform(_ work: @escaping () async throws -> Void) {
        Task {
            do {
                try await work()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func createFromPhotos(_ items: [PhotosPickerItem]) async throws {
        var images: [UIImage] = []
        for item in items {
            if let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                images.append(image)
            }
        }
        try await store.create(from: images, named: "Photos \(Date.now.formatted(date: .abbreviated, time: .shortened))")
    }
}

private struct MergeRequest: Identifiable {
    let items: [PDFItem]
    var id: [URL] { items.map(\.url) }
}

// MARK: - Cell

struct DocumentCell: View {
    let item: PDFItem
    let isSelecting: Bool
    let isSelected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            DocumentThumbnail(item: item)
                .aspectRatio(0.76, contentMode: .fit)
                .clipShape(.rect(cornerRadius: 12))
                .overlay {
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(isSelected ? Color.accentColor : Color.primary.opacity(0.08), lineWidth: isSelected ? 3 : 1)
                }
                .shadow(color: .black.opacity(0.10), radius: 8, y: 4)
                .overlay(alignment: .bottomTrailing) {
                    if isSelecting {
                        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                            .font(.title2)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, isSelected ? Color.accentColor : Color.black.opacity(0.25))
                            .padding(8)
                    }
                }

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(2, reservesSpace: true)
                    .multilineTextAlignment(.leading)
                Text(item.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .contentShape(.rect)
    }
}

struct DocumentThumbnail: View {
    let item: PDFItem
    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?

    var body: some View {
        Color.white
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: item.isLocked ? "lock.doc" : "doc.richtext")
                        .font(.largeTitle)
                        .foregroundStyle(.gray.opacity(0.5))
                }
            }
            .task(id: item) {
                image = await Thumbnailer.thumbnail(for: item, scale: displayScale)
            }
    }
}

// MARK: - Merge

struct MergeSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var items: [PDFItem]
    @State private var name = "Merged Document"
    @State private var deleteOriginals = false
    let onMerge: ([PDFItem], String, Bool) -> Void

    private var totalPages: Int { items.reduce(0) { $0 + $1.pageCount } }

    var body: some View {
        NavigationStack {
            List {
                Section("Name") {
                    TextField("Merged Document", text: $name)
                }
                Section {
                    ForEach(items) { item in
                        HStack(spacing: 12) {
                            DocumentThumbnail(item: item)
                                .frame(width: 34, height: 44)
                                .clipShape(.rect(cornerRadius: 4))
                                .overlay { RoundedRectangle(cornerRadius: 4).strokeBorder(.quaternary) }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name).lineLimit(1)
                                Text(item.detail).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onMove { items.move(fromOffsets: $0, toOffset: $1) }
                } header: {
                    Text("Order")
                } footer: {
                    Text("Drag to rearrange. The merged document will have \(totalPages) pages.")
                }
                Section {
                    Toggle("Delete Originals After Merging", isOn: $deleteOriginals)
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Merge \(items.count) Documents")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Merge") {
                        onMerge(items, name, deleteOriginals)
                        dismiss()
                    }
                    .disabled(DocumentStore.sanitized(name).isEmpty)
                }
            }
        }
    }
}

// MARK: - Scanner

/// The system document camera. It asks for camera access itself, when the user taps Scan.
struct DocumentScanner: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss
    let onScan: ([UIImage]) -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        private let parent: DocumentScanner

        init(_ parent: DocumentScanner) {
            self.parent = parent
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            let images = (0..<scan.pageCount).map { scan.imageOfPage(at: $0) }
            parent.dismiss()
            if !images.isEmpty { parent.onScan(images) }
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            parent.dismiss()
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
            parent.dismiss()
        }
    }
}

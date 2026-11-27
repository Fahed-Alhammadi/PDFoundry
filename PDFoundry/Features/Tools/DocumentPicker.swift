import SwiftUI
import UniformTypeIdentifiers

/// The documents ticked in a picker, in the order they were ticked. Kept by file address, not by item value, so a
/// list refresh (new item values for the same files) keeps the ticks, and a file is never in the list twice.
struct PickSelection {
    private(set) var urls: [URL] = []

    var count: Int { urls.count }

    private static func key(_ url: URL) -> URL { url.standardizedFileURL }

    func contains(_ item: PDFItem) -> Bool { urls.contains(Self.key(item.url)) }

    /// Ticks or unticks a file.
    mutating func toggle(_ url: URL) {
        let key = Self.key(url)
        if let index = urls.firstIndex(of: key) {
            urls.remove(at: index)
        } else {
            urls.append(key)
        }
    }

    /// Ticks a file unless it is already ticked. Returns whether it was added.
    @discardableResult
    mutating func add(_ url: URL) -> Bool {
        let key = Self.key(url)
        guard !urls.contains(key) else { return false }
        urls.append(key)
        return true
    }

    /// The ticked files as items, in tick order. `candidates` are searched in order, so the first match wins: pass
    /// the files picked in Files first, so a file that is both listed and picked is handled as picked.
    func items(from candidates: [PDFItem]) -> [PDFItem] {
        urls.compactMap { url in candidates.first { Self.key($0.url) == url } }
    }
}

/// Chooses the documents a tool works on: the library, with "Open from Files" at the top.
///
/// A file picked in Files is used for this job only: it is not opened in place or added to the library. With
/// `opensInPlace` (Password-protect, which changes the real file) it is opened in place and listed instead, as
/// "+ › Open from Files" does.
/// With `multiple`, documents are ticked and confirmed with the button at the top; otherwise a tap picks one.
struct DocumentPicker: View {
    let title: String
    let multiple: Bool
    /// The fewest documents the tool needs (Merge needs two).
    var minimum = 1
    var confirmTitle = "Next"
    /// Password-protected files can be picked (the tool asks for the password). Merge can't use them.
    var allowsLocked = false
    var opensInPlace = false
    let onPick: ([PDFItem]) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(DocumentStore.self) private var store
    @State private var selection = PickSelection()
    @State private var fromFiles: [PDFItem] = []
    @State private var showImporter = false
    @State private var isReading = false
    /// A file picked in Files in single mode, handed over once the Files sheet has fully closed.
    @State private var pendingPick: PDFItem?
    @State private var notice: String?

    init(title: String, multiple: Bool, minimum: Int = 1, confirmTitle: String = "Next", allowsLocked: Bool = false,
         opensInPlace: Bool = false, onPick: @escaping ([PDFItem]) -> Void) {
        self.title = title
        self.multiple = multiple
        self.minimum = minimum
        self.confirmTitle = confirmTitle
        self.allowsLocked = allowsLocked
        self.opensInPlace = opensInPlace
        self.onPick = onPick
    }

    private var library: [PDFItem] {
        let extra = Set(fromFiles.map(\.url.standardizedFileURL))
        return store.sorted(.recent, matching: "").filter { !extra.contains($0.url.standardizedFileURL) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        showImporter = true
                    } label: {
                        HStack {
                            Label("Open from Files", systemImage: "folder")
                            if isReading {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(isReading)
                } footer: {
                    if let notice {
                        Text(notice).foregroundStyle(.red)
                    }
                }

                if !fromFiles.isEmpty {
                    Section("From Files") {
                        ForEach(fromFiles) { row(for: $0) }
                    }
                }

                Section(fromFiles.isEmpty ? "" : "Documents") {
                    ForEach(library) { row(for: $0) }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                if multiple {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(confirmTitle) { finish(selection.items(from: fromFiles + store.items)) }
                            .disabled(selection.count < minimum)
                    }
                }
            }
            .overlay {
                if store.items.isEmpty && fromFiles.isEmpty {
                    ContentUnavailableView("No Documents", systemImage: "doc", description: Text("Open a PDF from Files to use it here."))
                }
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.pdf], allowsMultipleSelection: multiple) { result in
            guard case .success(let urls) = result else { return }
            Task { await take(urls) }
        }
        .onChange(of: showImporter) { finishPendingPick() }
    }

    /// Reads the files picked in Files (through a file coordinator, so files in iCloud download first).
    private func take(_ urls: [URL]) async {
        isReading = true
        var items: [PDFItem] = []
        if opensInPlace {
            // Opened where it is and listed, so the tool changes the real file.
            let opened = (try? await store.open(urls)) ?? []
            items = opened.compactMap { url in store.items.first { $0.url.standardizedFileURL == url.standardizedFileURL } }
        } else {
            for url in urls {
                if let item = await DocumentStore.pickedItem(url) { items.append(item) }
            }
        }
        isReading = false
        let locked = allowsLocked ? [] : items.filter(\.isLocked)
        let usable = items.filter { allowsLocked || !$0.isLocked }
        if let first = locked.first {
            notice = locked.count == 1 ? lockedNotice(first) : "\(locked.count) files are password protected, so they can't be merged. Use More › Remove Password first."
        } else {
            notice = items.count < urls.count ? "Some files couldn't be read as PDFs." : nil
        }
        if multiple {
            let known = Set(fromFiles.map(\.url.standardizedFileURL))
            for item in usable where !known.contains(item.url.standardizedFileURL) {
                fromFiles.append(item)
                selection.add(item.url) // a file already ticked in the list stays ticked once
            }
        } else if let item = usable.first {
            pendingPick = item
            finishPendingPick()
        }
    }

    /// Closes the picker with the file picked in Files, but only once the Files sheet is gone: dismissing this
    /// sheet while that one is still closing can leave the picker open.
    private func finishPendingPick() {
        guard !showImporter, let item = pendingPick else { return }
        pendingPick = nil
        Task {
            // Let the Files sheet's dismissal finish before this sheet starts its own.
            try? await Task.sleep(for: .milliseconds(350))
            finish([item])
        }
    }

    private func row(for item: PDFItem) -> some View {
        let isPicked = selection.contains(item)
        let refused = item.isLocked && !allowsLocked
        return Button {
            if refused {
                notice = lockedNotice(item)
            } else if multiple {
                selection.toggle(item.url)
            } else {
                finish([item])
            }
        } label: {
            HStack(spacing: 12) {
                DocumentThumbnail(item: item)
                    .frame(width: 34, height: 44)
                    .clipShape(.rect(cornerRadius: 4))
                    .overlay { RoundedRectangle(cornerRadius: 4).strokeBorder(.quaternary) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name).lineLimit(1)
                    Text([item.detail, item.location].compactMap(\.self).joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                if multiple {
                    Image(systemName: isPicked ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(isPicked ? Color.accentColor : Color.secondary)
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .opacity(refused ? 0.5 : 1)
        .accessibilityAddTraits(isPicked ? .isSelected : [])
    }

    private func lockedNotice(_ item: PDFItem) -> String {
        PDFoundryError.protectedSource(item.name).localizedDescription
    }

    private func finish(_ items: [PDFItem]) {
        onPick(items)
        dismiss()
    }
}

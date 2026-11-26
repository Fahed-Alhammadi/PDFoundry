import SwiftUI
import UniformTypeIdentifiers

/// Chooses the documents a tool works on: the library, with "Open from Files" at the top.
///
/// A file picked in Files is used for this job only: it is not opened in place or added to the library.
/// With `multiple`, documents are ticked and confirmed with the button at the top; otherwise a tap picks one.
struct DocumentPicker: View {
    let title: String
    let multiple: Bool
    /// The fewest documents the tool needs (Merge needs two).
    var minimum = 1
    var confirmTitle = "Next"
    let onPick: ([PDFItem]) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(DocumentStore.self) private var store
    @State private var picked: [PDFItem] = []
    @State private var fromFiles: [PDFItem] = []
    @State private var showImporter = false
    @State private var notice: String?

    init(title: String, multiple: Bool, minimum: Int = 1, confirmTitle: String = "Next", onPick: @escaping ([PDFItem]) -> Void) {
        self.title = title
        self.multiple = multiple
        self.minimum = minimum
        self.confirmTitle = confirmTitle
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
                        Label("Open from Files", systemImage: "folder")
                    }
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
                        Button(confirmTitle) { finish(picked) }
                            .disabled(picked.count < minimum)
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
            let items = urls.compactMap(DocumentStore.pickedItem)
            notice = items.count < urls.count ? "Some files couldn't be read as PDFs." : nil
            guard !items.isEmpty else { return }
            if multiple {
                let known = Set(fromFiles.map(\.url))
                let added = items.filter { !known.contains($0.url) && !$0.isLocked }
                fromFiles += added
                picked += added
            } else if let item = items.first {
                if item.isLocked {
                    notice = lockedNotice(item)
                } else {
                    finish([item])
                }
            }
        }
    }

    private func row(for item: PDFItem) -> some View {
        let isPicked = picked.contains(item)
        return Button {
            if item.isLocked {
                notice = lockedNotice(item)
            } else if multiple {
                if let index = picked.firstIndex(of: item) {
                    picked.remove(at: index)
                } else {
                    picked.append(item)
                }
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
        .opacity(item.isLocked ? 0.5 : 1)
        .accessibilityAddTraits(isPicked ? .isSelected : [])
    }

    private func lockedNotice(_ item: PDFItem) -> String {
        "“\(item.name)” is password protected, so it can't be used here."
    }

    private func finish(_ items: [PDFItem]) {
        onPick(items)
        dismiss()
    }
}

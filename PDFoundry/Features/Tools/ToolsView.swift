import PhotosUI
import SwiftUI
import VisionKit

/// A tool on the Tools tab: something done to whole files.
enum PDFTool: CaseIterable, Identifiable {
    case scan, photos, merge, extract, split, protect

    var id: Self { self }

    var title: String {
        switch self {
        case .scan: "Scan Document"
        case .photos: "Create from Photos"
        case .merge: "Merge PDFs"
        case .extract: "Extract Pages"
        case .split: "Split PDF"
        case .protect: "Password-protect"
        }
    }

    var detail: String {
        switch self {
        case .scan: "Turn paper into a PDF"
        case .photos: "Make a PDF from pictures"
        case .merge: "Join documents into one"
        case .extract: "Save pages as a new PDF"
        case .split: "Make several PDFs from one"
        case .protect: "Lock a PDF with a password"
        }
    }

    var symbol: String {
        switch self {
        case .scan: "document.viewfinder"
        case .photos: "photo.on.rectangle"
        case .merge: "arrow.triangle.merge"
        case .extract: "arrow.up.doc"
        case .split: "scissors"
        case .protect: "lock.doc"
        }
    }

    var section: ToolSection {
        switch self {
        case .scan, .photos: .create
        case .merge, .extract, .split: .combine
        case .protect: .protect
        }
    }
}

/// The groups on the Tools tab. A group without tools yet is not shown.
enum ToolSection: CaseIterable, Identifiable {
    case create, combine, protect, enhance

    var id: Self { self }

    var title: String {
        switch self {
        case .create: "Create"
        case .combine: "Combine & split"
        case .protect: "Protect & optimize"
        case .enhance: "Enhance"
        }
    }

    /// The tile colour for the group's tools, from the brand palette.
    var tint: Color {
        switch self {
        case .create: Brand.ember
        case .combine: Brand.steel
        case .protect, .enhance: Brand.graphite
        }
    }

    /// The tools in this group. Scan is left out where the device can't scan documents.
    func tools(scanAvailable: Bool) -> [PDFTool] {
        PDFTool.allCases.filter { $0.section == self && ($0 != .scan || scanAvailable) }
    }

    static func shown(scanAvailable: Bool) -> [ToolSection] {
        allCases.filter { !$0.tools(scanAvailable: scanAvailable).isEmpty }
    }
}

/// The Tools tab: a grid of whole-file tools, grouped.
struct ToolsView: View {
    @Environment(DocumentStore.self) private var store
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var showScanner = false
    @State private var showPhotoPicker = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var picker: PickerRequest?
    /// What the picker chose, waiting for the picker to finish closing before the next sheet opens.
    @State private var chosen: (PDFTool, [PDFItem])?
    @State private var mergeCandidates: [PDFItem]?
    @State private var extractSource: PDFItem?
    @State private var splitSource: PDFItem?
    @State private var protectSource: PDFItem?
    @State private var confirmation: String?
    @State private var errorMessage: String?
    @State private var confirmationTask: Task<Void, Never>?

    private let scanAvailable = VNDocumentCameraViewController.isSupported

    private var columns: [GridItem] {
        if sizeClass == .regular {
            return [GridItem(.adaptive(minimum: 220, maximum: 320), spacing: 16, alignment: .top)]
        }
        return [GridItem(.flexible(), spacing: 14, alignment: .top), GridItem(.flexible(), spacing: 14, alignment: .top)]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    ForEach(ToolSection.shown(scanAvailable: scanAvailable)) { section in
                        VStack(alignment: .leading, spacing: 12) {
                            Text(section.title)
                                .font(.title3.weight(.semibold))
                                .accessibilityAddTraits(.isHeader)
                            LazyVGrid(columns: columns, spacing: 14) {
                                ForEach(section.tools(scanAvailable: scanAvailable)) { tool in
                                    Button { run(tool) } label: {
                                        ToolTile(tool: tool, tint: section.tint)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Tools")
            .overlay(alignment: .bottom) { banner }
            .animation(.snappy, value: confirmation)
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItems, maxSelectionCount: 30, matching: .images)
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            photoItems = []
            perform { confirm(created: try await store.createFromPhotos(items)) }
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScanner { images in
                perform { confirm(created: try await store.createFromScan(images)) }
            }
            .ignoresSafeArea()
        }
        .sheet(item: $picker, onDismiss: openChosen) { request in
            DocumentPicker(
                title: request.tool.title,
                multiple: request.tool == .merge,
                minimum: request.tool == .merge ? 2 : 1,
                confirmTitle: "Next",
                // Merge can't use protected files. Protect works on the real file, so one from Files is opened
                // in place (and listed), never a copy that would leave the real file unprotected.
                allowsLocked: request.tool != .merge,
                opensInPlace: request.tool == .protect
            ) { items in
                chosen = (request.tool, items)
            }
        }
        .sheet(item: Binding(get: { mergeCandidates.map(MergeRequest.init) }, set: { mergeCandidates = $0?.items })) { request in
            MergeSheet(items: request.items) { ordered, name, deleteOriginals in
                perform {
                    let url = try await store.merge(ordered, named: name, deleteOriginals: deleteOriginals)
                    confirm(created: url, verb: "Merged into")
                }
            }
        }
        .sheet(item: $extractSource) { source in
            ExtractPagesView(item: source) { created in
                show(confirmation: "Saved \(created.pageCount == 1 ? "1 page" : "\(created.pageCount) pages") as “\(created.name)” in Documents.")
            }
            .presentationSizing(.page)
        }
        .sheet(item: $splitSource) { source in
            SplitView(item: source) { created in
                show(confirmation: created.count == 1
                     ? "Saved “\(created[0].name)” in Documents."
                     : "Split “\(source.name)” into \(created.count) documents in Documents.")
            }
        }
        .sheet(item: $protectSource) { source in
            ProtectSheet(name: source.name, url: source.url) { password in
                do {
                    try await store.protect(source.url, password: password)
                    show(confirmation: "“\(source.name)” is now protected with a password.")
                    return nil
                } catch {
                    return error.localizedDescription
                }
            }
        }
        .alert("Something Went Wrong", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var banner: some View {
        if let confirmation {
            Label(confirmation, systemImage: "checkmark.circle.fill")
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .glassEffect()
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityAddTraits(.isStaticText)
        }
    }

    // MARK: Actions

    private func run(_ tool: PDFTool) {
        switch tool {
        case .scan: showScanner = true
        case .photos: showPhotoPicker = true
        case .merge, .extract, .split, .protect: picker = PickerRequest(tool: tool)
        }
    }

    /// Opens the next step once the picker has closed, so the two sheets never overlap.
    private func openChosen() {
        guard let (tool, items) = chosen else { return }
        chosen = nil
        switch tool {
        case .merge where items.count >= 2: mergeCandidates = items
        case .extract: extractSource = items.first
        case .split: splitSource = items.first
        case .protect: protectSource = items.first
        default: break
        }
    }

    private func confirm(created url: URL, verb: String = "Saved") {
        show(confirmation: "\(verb) “\(url.deletingPathExtension().lastPathComponent)” in Documents.")
    }

    private func show(confirmation text: String) {
        confirmation = text
        confirmationTask?.cancel()
        confirmationTask = Task {
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { confirmation = nil }
        }
        UIAccessibility.post(notification: .announcement, argument: text)
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
}

private struct PickerRequest: Identifiable {
    let tool: PDFTool
    var id: PDFTool { tool }
}

/// A tool tile: a white symbol on a coloured rounded tile, the title and one line about it.
private struct ToolTile: View {
    let tool: PDFTool
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: tool.symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 42, height: 42)
                .background(tint.gradient, in: .rect(cornerRadius: 11))
            VStack(alignment: .leading, spacing: 2) {
                Text(tool.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(tool.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 18))
        .contentShape(.rect(cornerRadius: 18))
        .accessibilityElement(children: .combine)
    }
}

import PDFKit
import SwiftUI

struct ReaderView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(DocumentStore.self) private var store
    @Environment(SignatureStore.self) private var signatures
    @AppStorage(SettingsKey.horizontalScroll) private var horizontalScroll = false
    @State private var model: ReaderModel

    @State private var showPages = false
    @State private var showSignatures = false
    @State private var showTextPrompt = false
    @State private var newText = ""
    @State private var password = ""
    @State private var wrongPassword = false
    @State private var askToRevert = false
    @State private var shared: SharedFile?

    init(url: URL) {
        _model = State(initialValue: ReaderModel(url: url))
    }

    var body: some View {
        content
            .navigationTitle(model.title)
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(model.mode != .reading)
            .toolbar(.hidden, for: .tabBar)
            .toolbar { toolbar }
            .safeAreaInset(edge: .bottom) { bottomBar }
            .sheet(isPresented: $showPages) {
                PageOrganizerView(model: model)
                    .presentationSizing(.page)
            }
            .sheet(isPresented: $showSignatures) {
                SignatureSheet { image in
                    model.beginPlacing(signature: image)
                }
            }
            .alert("Add Text", isPresented: $showTextPrompt) {
                TextField("Text", text: $newText)
                Button("Cancel", role: .cancel) {}
                Button("Add") { model.beginPlacing(text: newText) }
            } message: {
                Text("Type the text, then drag it into place.")
            }
            .confirmationDialog(
                model.selectedAnnotation?.title ?? "",
                isPresented: Binding(get: { model.selectedAnnotation != nil }, set: { if !$0 { model.selectedAnnotation = nil } }),
                titleVisibility: .visible
            ) {
                Button("Delete", role: .destructive) { model.deleteSelectedAnnotation() }
            }
            .alert("Couldn't Save", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                Button("Save a Copy in PDFoundry") { saveCopy() }
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.errorMessage ?? "")
            }
            .confirmationDialog("Revert “\(model.title)” to the original?", isPresented: $askToRevert, titleVisibility: .visible) {
                Button("Revert to Original", role: .destructive) { model.revertToOriginal() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Everything you changed since opening this document will be removed.")
            }
            .sheet(item: $shared) { file in
                ActivityView(url: file.url)
            }
            .task { await model.load() }
    }

    /// When the file can't be changed where it is, the edited document is kept as a new one inside PDFoundry.
    private func saveCopy() {
        guard let document = model.document else { return }
        Task {
            if (try? await store.save(document, named: model.title)) != nil { dismiss() }
        }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if model.isLoading {
            // Reading a file on the device is too quick for a label; a download from iCloud says what it is doing.
            ProgressView(model.isDownloading ? "Downloading from iCloud…" : "")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if model.document == nil {
            ContentUnavailableView("Unable to Open", systemImage: "doc.questionmark", description: Text("This file isn't a readable PDF."))
        } else if model.isLocked {
            lockedView
        } else {
            ZStack {
                PDFKitView(model: model, horizontal: horizontalScroll)

                if model.mode == .drawing {
                    DrawingCanvas(drawing: $model.drawing)
                }
                if model.mode == .placing, let current = model.placement {
                    // The binding can be read once more after the placement is cleared, so it falls back to the last value.
                    PlacementOverlay(placement: Binding(get: { model.placement ?? current }, set: { model.placement = $0 }))
                }
            }
            .ignoresSafeArea()
            .overlay(alignment: .top) { banner }
            .overlay(alignment: .bottomLeading) { pageBadge }
            .animation(.snappy, value: model.hint)
        }
    }

    @ViewBuilder
    private var banner: some View {
        if let text = bannerText {
            Text(text)
                .font(.footnote.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .glassEffect()
                .padding(.top, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
        }
    }

    private var bannerText: String? {
        switch model.mode {
        case .drawing: "Draw on the page, then tap Done."
        case .placing: "Drag to move. Use the handle to resize."
        case .reading: model.hint
        }
    }

    @ViewBuilder
    private var pageBadge: some View {
        if model.mode == .reading, model.pageCount > 0 {
            Text("\(model.pageIndex + 1) of \(model.pageCount)")
                .font(.caption.weight(.semibold).monospacedDigit())
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .glassEffect()
                .padding(.leading, 16)
                .padding(.bottom, 10)
                .accessibilityLabel("Page \(model.pageIndex + 1) of \(model.pageCount)")
        }
    }

    private var lockedView: some View {
        VStack(spacing: 18) {
            Image(systemName: "lock.doc.fill")
                .font(.system(size: 54))
                .foregroundStyle(.tint)
            Text("This Document Is Locked")
                .font(.title3.weight(.semibold))
            Text("Enter its password to read it.")
                .foregroundStyle(.secondary)
            SecureField("Password", text: $password)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 280)
                .onSubmit(unlock)
            if wrongPassword {
                Text("That password didn't work. Try again.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
            Button("Unlock", action: unlock)
                .buttonStyle(.glassProminent)
                .disabled(password.isEmpty)
        }
        .padding()
    }

    private func unlock() {
        wrongPassword = !model.unlock(with: password)
        password = ""
    }

    // MARK: Bottom bar

    /// The mode bar while reading. Drawing and placing use the top bar's Cancel and Done / Place instead.
    @ViewBuilder
    private var bottomBar: some View {
        if model.document != nil, !model.isLocked, model.mode == .reading {
            if model.canEdit {
                ModeBar(model: model) {
                    showPages = true
                } onText: {
                    newText = ""
                    showTextPrompt = true
                } onSignature: {
                    showSignatures = true
                }
            } else {
                Label("Password-protected documents are read-only.", systemImage: "lock.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .glassEffect()
                    .padding(.bottom, 6)
            }
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if model.document != nil, !model.isLocked {
            switch model.mode {
            case .reading:
                readingToolbar
            case .drawing:
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.cancelMode() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { model.commitDrawing() }
                }
            case .placing:
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.cancelMode() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Place") { model.commitPlacement() }
                }
            }
        }
    }

    @ToolbarContentBuilder
    private var readingToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button("Search", systemImage: "magnifyingglass") { model.showSearch() }
            Menu("More", systemImage: "ellipsis") {
                Button("Share", systemImage: "square.and.arrow.up") {
                    if let url = model.temporaryCopy() { shared = SharedFile(url: url) }
                }
                Button("Print", systemImage: "printer") { model.printDocument() }
                if model.canEdit {
                    Divider()
                    Button("Revert to Original", systemImage: "arrow.uturn.backward.circle", role: .destructive) { askToRevert = true }
                        .disabled(!model.canRevert)
                }
            }
        }
    }
}

/// A file handed to the system share sheet.
private struct SharedFile: Identifiable {
    let url: URL
    var id: URL { url }
}

/// The system share sheet.
private struct ActivityView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

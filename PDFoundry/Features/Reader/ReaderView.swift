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
    @State private var showInitials = false
    @State private var showTextPrompt = false
    @State private var textPromptTitle = "Add Text"
    @State private var newText = ""
    @State private var password = ""
    @State private var wrongPassword = false
    @State private var askToRevert = false
    @State private var showProtect = false
    @State private var askToRemovePassword = false
    @State private var shared: SharedFile?
    @State private var askToApplyRedactions = false
    @State private var toolSheet: ReaderTool?
    /// A tool asked for by the Tools tab, waiting for a locked document to be unlocked.
    @State private var waitingTool: ReaderTool?

    init(url: URL) {
        _model = State(initialValue: ReaderModel(url: url))
    }

    var body: some View {
        content
            .navigationTitle(model.title)
            .navigationBarTitleDisplayMode(.inline)
            // While redaction boxes wait, the reader's own back button asks first (this also turns off swipe-back).
            .navigationBarBackButtonHidden(model.mode != .reading || model.toolMode == .redact || model.pendingRedactionCount > 0)
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
            .sheet(isPresented: Binding(get: { model.signingField != nil }, set: { if !$0 { model.signingField = nil } })) {
                // The field is taken now: the sheet clears `signingField` as it closes, before the pick arrives.
                if let field = model.signingField {
                    SignatureSheet { image in model.sign(field, with: image) }
                }
            }
            .sheet(isPresented: $showInitials) {
                SignatureSheet(kind: .initials) { image in
                    model.beginPlacing(signature: image)
                }
            }
            .alert(textPromptTitle, isPresented: $showTextPrompt) {
                TextField("Text", text: $newText)
                Button("Cancel", role: .cancel) {}
                Button("Add") { model.beginPlacing(text: newText) }
            } message: {
                Text("Type the text, then drag it into place.")
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
            .sheet(isPresented: $showProtect) {
                ProtectSheet(name: model.title) { password in
                    guard !model.protect(with: password) else { return nil }
                    // Shown in this sheet, not again in the reader's own alert.
                    let message = model.errorMessage
                    model.errorMessage = nil
                    return message ?? "The file couldn't be protected."
                }
            }
            .confirmationDialog("Remove the password from “\(model.title)”?", isPresented: $askToRemovePassword, titleVisibility: .visible) {
                Button("Remove Password", role: .destructive) { model.removePassword() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Anyone who has the file will be able to open it.")
            }
            .alert("Apply Redactions?", isPresented: $askToApplyRedactions) {
                Button("Apply", role: .destructive) { model.applyRedactions() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(Self.redactionWarning)
            }
            .alert("Apply the redactions?", isPresented: Binding(get: { model.leaveRequest != nil }, set: { if !$0 { model.leaveRequest = nil } })) {
                Button("Apply") {
                    let request = model.leaveRequest
                    model.applyRedactions()
                    // Undo right after Apply would only take the redactions back again.
                    if request != .undo { finishLeaving(request) }
                }
                Button("Discard", role: .destructive) {
                    let request = model.leaveRequest
                    model.discardRedactions()
                    finishLeaving(request)
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(Self.redactionWarning)
            }
            .sheet(item: $model.commentDraft) { draft in
                CommentSheet(text: draft.comment?.contents ?? "", isNew: draft.comment == nil) { text in
                    if let comment = draft.comment {
                        model.updateComment(comment, text: text)
                    } else if let point = draft.point {
                        model.addComment(at: point, text: text)
                    }
                } onDelete: {
                    if let comment = draft.comment { model.updateComment(comment, text: "") }
                }
                .presentationDetents([.medium, .large])
            }
            .sheet(item: $shared) { file in
                ActivityView(url: file.url)
            }
            .sheet(item: $toolSheet) { tool in
                switch tool {
                // Tall enough for the preview and every control without scrolling (iPad's form size is too short).
                case .pageNumbers: PageNumbersSheet(model: model).presentationSizing(.page)
                case .watermark: WatermarkSheet(model: model).presentationSizing(.page)
                case .makeSearchable: OCRSheet(model: model).presentationDetents([.medium])
                case .redact: EmptyView()
                }
            }
            .task {
                // So Protect on the Tools tab can hand this reader the password (it may still be alive in its tab).
                store.attach(model)
                await model.load()
                if let tool = store.takeReaderTool(for: model.url) {
                    if model.isLocked { waitingTool = tool } else { open(tool) }
                }
            }
            .onChange(of: model.isLocked) { _, locked in
                if !locked, let tool = waitingTool {
                    waitingTool = nil
                    open(tool)
                }
            }
            .onDisappear { model.syncFields() }
    }

    /// Opens a tool on the document. A document that can't be changed shows the read-only notice instead.
    private func open(_ tool: ReaderTool) {
        guard model.canEdit else {
            model.show(hint: "This document's owner doesn't allow changes, so it's read-only.", for: .seconds(4))
            return
        }
        if tool == .redact {
            model.beginRedaction()
        } else {
            toolSheet = tool
        }
    }

    static let redactionWarning = "Redacting removes everything under the boxes for good. Each redacted page becomes an image, so its text can no longer be selected or searched, and its form fields and marks are pressed into it."

    private func finishLeaving(_ request: ReaderModel.LeaveRequest?) {
        switch request {
        case .closeRedact: model.toolMode = .none
        case .leaveDocument: dismiss()
        case .revert: askToRevert = true
        case .removePassword: askToRemovePassword = true
        case .undo: model.undo()
        case .organizePages: showPages = true
        case nil: break
        }
    }

    /// Opens a comment to read, edit or delete it.
    private func openComment(_ comment: PDFAnnotation) {
        model.selectedAnnotation = nil
        model.commentDraft = ReaderModel.CommentDraft(comment: comment)
    }

    /// When the file can't be changed where it is, the edited document is kept as a new one inside PDFoundry.
    private func saveCopy() {
        guard let document = model.document else { return }
        Task {
            // A protected document's copy keeps its password.
            if (try? await store.save(document, named: model.title, password: model.password)) != nil { dismiss() }
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

                BoxOverlay(model: model)
                if model.mode == .reading, model.selectedAnnotation != nil {
                    SelectionOverlay(model: model) { comment in openComment(comment) }
                }
                if model.mode == .drawing {
                    DrawingCanvas(drawing: $model.drawing)
                }
                if model.mode == .placing, let current = model.placement {
                    // The binding can be read once more after the placement is cleared, so it falls back to the last value.
                    PlacementOverlay(placement: Binding(get: { model.placement ?? current }, set: { model.placement = $0 }),
                                     avoiding: model.lastPlacedRect)
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
        case .shaping: model.shapeKind.drawHint
        case .commenting: "Tap where the comment goes."
        case .reading:
            model.hint ?? (model.toolMode == .redact && model.pendingRedactionCount == 0
                ? "Drag over what to black out. Scroll with two fingers." : nil)
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
                    if !model.askAboutRedactions(before: .organizePages) { showPages = true }
                } onText: {
                    newText = ""
                    textPromptTitle = "Add Text"
                    showTextPrompt = true
                } onSignature: {
                    showSignatures = true
                } onInitials: {
                    showInitials = true
                } onDate: {
                    // Today's date, which can be changed before it is placed.
                    newText = ReaderModel.todayText()
                    textPromptTitle = "Add Date"
                    showTextPrompt = true
                } onApplyRedactions: {
                    askToApplyRedactions = true
                }
            } else {
                Label("This document's owner doesn't allow changes, so it's read-only.", systemImage: "lock.fill")
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
            case .shaping:
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { model.cancelMode() }
                }
            case .commenting:
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.cancelMode() }
                }
            }
        }
    }

    @ToolbarContentBuilder
    private var readingToolbar: some ToolbarContent {
        if model.toolMode == .redact || model.pendingRedactionCount > 0 {
            ToolbarItem(placement: .topBarLeading) {
                Button("Back", systemImage: "chevron.backward") {
                    if !model.shouldAskBeforeLeaving() { dismiss() }
                }
            }
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button("Search", systemImage: "magnifyingglass") { model.showSearch() }
            Menu("More", systemImage: "ellipsis") {
                Button("Share", systemImage: "square.and.arrow.up") {
                    if let url = model.temporaryCopy() { shared = SharedFile(url: url) }
                }
                Button("Print", systemImage: "printer") { model.printDocument() }
                if model.canEdit {
                    Divider()
                    Button("Make Searchable", systemImage: "text.viewfinder") { open(.makeSearchable) }
                    Button("Page Numbers", systemImage: "number") { open(.pageNumbers) }
                    Button("Watermark", systemImage: "seal") { open(.watermark) }
                    Divider()
                    if model.isProtected {
                        Button("Remove Password", systemImage: "lock.open") { if !model.askAboutRedactions(before: .removePassword) { askToRemovePassword = true } }
                    } else {
                        Button("Protect with Password", systemImage: "lock") { showProtect = true }
                    }
                    Divider()
                    Button("Revert to Original", systemImage: "arrow.uturn.backward.circle", role: .destructive) { if !model.askAboutRedactions(before: .revert) { askToRevert = true } }
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

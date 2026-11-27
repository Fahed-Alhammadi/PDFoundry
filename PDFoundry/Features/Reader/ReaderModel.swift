import Observation
import PDFKit
import PencilKit
import UIKit

/// Something waiting to be positioned on a page: a signature, or a line of typed text.
struct Placement {
    enum Content {
        case image(UIImage)
        case text(String)
    }

    let content: Content
    /// Width divided by height.
    let aspect: CGFloat
    /// The frame in the reader's coordinate space. Zero until the overlay lays it out.
    var rect: CGRect = .zero
}

struct AnnotationSelection {
    let annotation: PDFAnnotation
    let page: PDFPage

    var title: String {
        switch annotation.type ?? "" {
        case "Highlight": "Highlight"
        case "Underline": "Underline"
        case "StrikeOut": "Strikethrough"
        case "FreeText": "Text"
        case "Stamp": "Signature or Drawing"
        case "Ink": "Drawing"
        default: "Annotation"
        }
    }
}

/// State and editing actions for one open document.
///
/// The file is opened where it is, and every change is saved to that same file straight away, as phone apps do.
/// The file's contents from the moment it was opened are kept in memory, so "Revert to Original" can put them back
/// for as long as the document stays open.
@Observable
final class ReaderModel {
    /// What the reader is doing right now: reading, or drawing / placing something on the page.
    enum Mode { case reading, drawing, placing }
    /// The group of tools the bottom bar shows. It stays as it is while a tool draws or places something, so
    /// the bar comes back to the same tools afterwards (signing two places is one tap each).
    enum ToolMode { case none, markup, fillSign }

    let url: URL
    private(set) var document: PDFDocument?
    /// The file is being read. It has not been shown yet.
    private(set) var isLoading = true
    /// The file is in iCloud and is being downloaded first.
    private(set) var isDownloading = false
    private(set) var isLocked = false
    private(set) var pageIndex = 0
    private(set) var pageCount = 0
    private(set) var canUndo = false
    /// The file has been changed since it was opened, so there is something to revert.
    private(set) var canRevert = false
    var hasSelection = false
    var mode = Mode.reading
    /// Always `.none` when a document opens: the reader starts on the reading bar.
    var toolMode = ToolMode.none
    var placement: Placement?
    var drawing = PKDrawing()
    var selectedAnnotation: AnnotationSelection?
    var hint: String?
    var errorMessage: String?

    @ObservationIgnored weak var pdfView: PDFView?
    @ObservationIgnored private var undoStack: [[(PDFPage, PDFAnnotation)]] = []
    @ObservationIgnored private var hintTask: Task<Void, Never>?
    /// Keeps a file from the Files app readable and writable while it is open here.
    @ObservationIgnored private var access: FileAccess?
    @ObservationIgnored private var didLoad = false

    var title: String { url.deletingPathExtension().lastPathComponent }

    /// The file exactly as it was when it was opened.
    @ObservationIgnored private var original: Data?

    /// The password every save encrypts the file with. Set when a protected file is unlocked with full access, or
    /// when the user protects the open document. It lives only here, in memory, for as long as the document is open:
    /// never on disk, in preferences or in logs.
    @ObservationIgnored private(set) var password: String?
    /// Observable twin of `password != nil`, so menus update without the password itself being observed.
    private(set) var isProtected = false
    /// The password the file was unlocked with when it was opened, for putting an encrypted original back.
    @ObservationIgnored private var openedPassword: String?

    /// A protected file can be edited once it is unlocked with full access (its owner password); every save then
    /// keeps the password. With limited access, or a file with restrictions only, it stays read-only, so saving
    /// never strips a protection the user can't put back.
    var canEdit: Bool {
        guard let document else { return false }
        return !document.isEncrypted || (!document.isLocked && document.permissionsStatus == .owner)
    }

    /// Does no work: SwiftUI may create a model more than once for the same screen. `load` reads the file.
    init(url: URL) {
        self.url = url
    }

    /// Reads the file, away from the main thread, and shows it. Only the first call does anything.
    func load() async {
        guard !didLoad else { return }
        didLoad = true
        access = FileAccess(url)
        var data = await Self.data(at: url)
        if data == nil {
            // Perhaps a file in iCloud that is not on this device yet: reading it through a file coordinator
            // downloads it first.
            isDownloading = true
            data = await Self.coordinatedData(at: url)
            isDownloading = false
        }
        // The document is built from the bytes in memory, which are also the original kept for Revert.
        adopt(data.flatMap(PDFDocument.init(data:)))
        original = document == nil ? nil : data
        isLoading = false
    }

    @concurrent
    private nonisolated static func data(at url: URL) async -> Data? {
        try? Data(contentsOf: url)
    }

    private func adopt(_ loaded: PDFDocument?) {
        document = loaded
        isLocked = loaded?.isLocked ?? false
        pageCount = loaded?.pageCount ?? 0
    }

    @concurrent
    private nonisolated static func coordinatedData(at url: URL) async -> Data? {
        var data: Data?
        var error: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &error) { data = try? Data(contentsOf: $0) }
        return data
    }

    func unlock(with password: String) -> Bool {
        guard let document, document.unlock(withPassword: password) else { return false }
        isLocked = false
        pageCount = document.pageCount
        if document.permissionsStatus == .owner {
            setPassword(password)
            openedPassword = password
        }
        return true
    }

    private func setPassword(_ value: String?) {
        password = value
        isProtected = value != nil
    }

    // MARK: Password

    /// Protects the open document with `password`: the file is rewritten encrypted at once, and every later save
    /// keeps it. Returns false (nothing changed) if the document can't be edited, is already protected, or the
    /// file couldn't be written.
    @discardableResult
    func protect(with newPassword: String) -> Bool {
        guard canEdit, password == nil, !newPassword.isEmpty else { return false }
        setPassword(newPassword)
        guard save() else {
            setPassword(nil)
            return false
        }
        return true
    }

    /// Writes the file without a password. Returns false (still protected) if the file couldn't be written.
    ///
    /// PDFKit keeps an encrypted document encrypted on every write, so the reader moves to an unencrypted copy of
    /// the document. The steps so far can't be undone one by one after that (their pages are gone).
    @discardableResult
    func removePassword() -> Bool {
        guard canEdit, let old = password, let document else { return false }
        let plain = document.isEncrypted ? PDFOperations.unencrypted(document) : document
        let index = pageIndex
        let previous = self.document
        self.document = plain
        setPassword(nil)
        guard save() else {
            self.document = previous
            setPassword(old)
            return false
        }
        if plain !== previous {
            cancelMode()
            selectedAnnotation = nil
            undoStack.removeAll()
            canUndo = false
            adopt(plain)
            pdfView?.document = plain
            if let page = plain.page(at: index) { pdfView?.go(to: page) }
            syncPage()
        }
        return true
    }

    func syncPage() {
        guard let document, let page = pdfView?.currentPage else { return }
        pageIndex = document.index(for: page)
        pageCount = document.pageCount
    }

    // MARK: Text markup

    func markup(_ type: PDFAnnotationSubtype, color: UIColor) {
        guard let selection = pdfView?.currentSelection, !(selection.string ?? "").isEmpty else {
            show(hint: "Press and hold text to select it first.")
            return
        }
        var added: [(PDFPage, PDFAnnotation)] = []
        for line in selection.selectionsByLine() {
            for page in line.pages {
                let bounds = line.bounds(for: page)
                guard !bounds.isEmpty else { continue }
                let annotation = PDFAnnotation(bounds: bounds, forType: type, withProperties: nil)
                annotation.color = color
                page.addAnnotation(annotation)
                added.append((page, annotation))
            }
        }
        pdfView?.clearSelection()
        hasSelection = false
        commit(added)
    }

    // MARK: Placement (signatures and text)

    func beginPlacing(signature image: UIImage) {
        guard image.size.height > 0 else { return }
        placement = Placement(content: .image(image), aspect: image.size.width / image.size.height)
        mode = .placing
    }

    func beginPlacing(text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let size = (trimmed as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: 20)])
        placement = Placement(content: .text(trimmed), aspect: max(1, (size.width + 16) / (size.height + 8)))
        mode = .placing
    }

    func commitPlacement() {
        defer { cancelMode() }
        guard let placement, let pdfView, !placement.rect.isEmpty,
              let page = pdfView.page(for: CGPoint(x: placement.rect.midX, y: placement.rect.midY), nearest: true) else { return }
        let bounds = pdfView.convert(placement.rect, to: page)

        switch placement.content {
        case .image(let image):
            if let annotation = PDFOperations.stamp(image, on: page, bounds: bounds) {
                commit([(page, annotation)])
            }
        case .text(let text):
            let annotation = PDFAnnotation(bounds: bounds, forType: .freeText, withProperties: nil)
            annotation.contents = text
            annotation.font = UIFont(name: "Helvetica", size: max(6, bounds.height * 0.62)) ?? .systemFont(ofSize: max(6, bounds.height * 0.62))
            annotation.fontColor = .black
            annotation.color = .clear
            page.addAnnotation(annotation)
            commit([(page, annotation)])
        }
    }

    // MARK: Drawing

    func beginDrawing() {
        drawing = PKDrawing()
        mode = .drawing
    }

    func commitDrawing() {
        defer { cancelMode() }
        guard let pdfView, !drawing.strokes.isEmpty else { return }
        let rect = drawing.bounds.insetBy(dx: -4, dy: -4)
        guard let page = pdfView.page(for: CGPoint(x: rect.midX, y: rect.midY), nearest: true) else { return }
        var image = UIImage()
        let captured = drawing
        // Render with light traits so black ink stays black on the white page in Dark Mode.
        UITraitCollection(userInterfaceStyle: .light).performAsCurrent {
            image = captured.image(from: rect, scale: 3)
        }
        if let annotation = PDFOperations.stamp(image, on: page, bounds: pdfView.convert(rect, to: page)) {
            commit([(page, annotation)])
        }
    }

    func cancelMode() {
        mode = .reading
        placement = nil
        drawing = PKDrawing()
    }

    // MARK: Annotations

    func handleTap(at point: CGPoint) {
        guard mode == .reading, canEdit, let pdfView,
              let page = pdfView.page(for: point, nearest: false),
              let annotation = page.annotation(at: pdfView.convert(point, to: page)) else { return }
        let type = annotation.type ?? ""
        guard type != "Link", type != "Widget", type != "Popup" else { return }
        selectedAnnotation = AnnotationSelection(annotation: annotation, page: page)
    }

    func deleteSelectedAnnotation() {
        guard let selected = selectedAnnotation else { return }
        selected.page.removeAnnotation(selected.annotation)
        undoStack = undoStack.map { $0.filter { $0.1 !== selected.annotation } }.filter { !$0.isEmpty }
        canUndo = !undoStack.isEmpty
        selectedAnnotation = nil
        save()
    }

    func undo() {
        guard let last = undoStack.popLast() else { return }
        for (page, annotation) in last { page.removeAnnotation(annotation) }
        canUndo = !undoStack.isEmpty
        save()
    }

    /// Called after the page organiser changes the document's structure.
    func pagesDidChange() {
        // The annotations added so far stay in the document, but their pages may be gone, so they can no longer be undone one by one.
        undoStack.removeAll()
        canUndo = false
        pageCount = document?.pageCount ?? 0
        pdfView?.layoutDocumentView()
        save()
        syncPage()
    }

    private func commit(_ added: [(PDFPage, PDFAnnotation)]) {
        guard !added.isEmpty else { return }
        undoStack.append(added)
        canUndo = true
        save()
    }

    /// Writes the document to the file it was opened from. Called after every change.
    /// Returns false, with `errorMessage` set, when the file could not be written.
    @discardableResult
    func save() -> Bool {
        guard let document, canEdit else { return true }
        var written = false
        var error: NSError?
        // Coordinated, so iCloud Drive and other apps see one complete change.
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forReplacing, error: &error) { target in
            written = document.write(to: target, withOptions: PDFOperations.writeOptions(password: password))
        }
        guard written else {
            errorMessage = "This file can't be changed where it is. You can save a copy in PDFoundry instead."
            return false
        }
        canRevert = original != nil
        return true
    }

    /// Puts the file back exactly as it was when it was opened, and shows it.
    @discardableResult
    func revertToOriginal() -> Bool {
        guard let original, canRevert else { return false }
        var written = false
        var error: NSError?
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forReplacing, error: &error) { target in
            // Not an atomic write: that needs a temporary file beside the target, and a file picked in Files
            // gives access to the file alone, not its folder. The coordinator already makes it one change.
            written = (try? original.write(to: target)) != nil
        }
        guard written, let restored = PDFDocument(data: original) else {
            errorMessage = "The original couldn't be put back."
            return false
        }
        // The original may be encrypted (the file was protected when it was opened) or plain (it was protected
        // here later). The reader follows the file: unlocked with the password it was opened with, or no password.
        if restored.isEncrypted {
            if restored.isLocked, let openedPassword { _ = restored.unlock(withPassword: openedPassword) }
            setPassword(restored.permissionsStatus == .owner ? openedPassword : nil)
        } else {
            setPassword(nil)
        }
        cancelMode()
        selectedAnnotation = nil
        undoStack.removeAll()
        canUndo = false
        canRevert = false
        adopt(restored)
        pdfView?.document = restored
        syncPage()
        return true
    }

    /// The document as it is now, in a temporary file for sharing (a file opened in place can't be shared by its own address).
    func temporaryCopy() -> URL? {
        guard let document else { return nil }
        let folder = FileManager.default.temporaryDirectory.appending(path: "Share-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let copy = folder.appending(path: title + ".pdf")
        if password != nil {
            // Shared with the same password as the file.
            return document.write(to: copy, withOptions: PDFOperations.writeOptions(password: password)) ? copy : nil
        }
        if document.isEncrypted && !canEdit {
            // Writing the open document would save it without its password, so the file itself is shared, byte for
            // byte. It is read-only here, so the file is the document as shown.
            if (try? FileManager.default.copyItem(at: url, to: copy)) != nil { return copy }
            return (try? original?.write(to: copy)) != nil ? copy : nil
        }
        return document.write(to: copy) ? copy : nil
    }

    func show(hint text: String) {
        hint = text
        hintTask?.cancel()
        hintTask = Task {
            try? await Task.sleep(for: .seconds(2.5))
            if !Task.isCancelled { hint = nil }
        }
    }

    // MARK: Output

    func printDocument() {
        guard UIPrintInteractionController.isPrintingAvailable else { return }
        let controller = UIPrintInteractionController.shared
        let info = UIPrintInfo(dictionary: nil)
        info.jobName = title
        info.outputType = .general
        controller.printInfo = info
        controller.printingItem = document?.dataRepresentation() ?? url
        controller.present(animated: true)
    }

    func showSearch() {
        pdfView?.findInteraction.presentFindNavigator(showingReplace: false)
    }
}

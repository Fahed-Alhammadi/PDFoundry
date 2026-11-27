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
        case "Square": "Rectangle"
        case "Circle": "Ellipse"
        case "Line": annotation.endLineStyle == .openArrow ? "Arrow" : "Line"
        case "Text": "Comment"
        default: "Annotation"
        }
    }

    var isMovable: Bool { Annotations.isMovable(annotation) }
    var isComment: Bool { annotation.type == "Text" }
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
    /// Where the last item was placed, in the reader's space, so the next one doesn't start on top of it.
    @ObservationIgnored private(set) var lastPlacedRect: CGRect?
    var drawing = PKDrawing()
    var selectedAnnotation: AnnotationSelection?
    var hint: String?
    var errorMessage: String?

    @ObservationIgnored weak var pdfView: PDFView?
    /// One step Undo takes back: annotations that were added, or form fields that were changed (with their
    /// values from before).
    private enum UndoStep {
        case added([(PDFPage, PDFAnnotation)])
        case fields([(PDFAnnotation, FieldValue)])
        /// Marks moved or resized, with where they were before.
        case changed([(PDFAnnotation, AnnotationGeometry)])
        /// Marks deleted, with the page they were on.
        case removed([(PDFPage, PDFAnnotation)])
        /// Comment text edited, with the text from before.
        case edited(PDFAnnotation, String)
        /// Pages replaced by new ones (redaction, Make Searchable).
        case replaced([PageSwap])
    }

    /// A page put in place of another one. `moved` are annotations carried over from the old page to the new one,
    /// so Undo carries them back.
    struct PageSwap {
        let old: PDFPage
        let new: PDFPage
        var moved: [PDFAnnotation] = []
    }

    /// The selected mark as it was when the current drag began, so the finished drag is one Undo step.
    @ObservationIgnored private var dragStart: AnnotationGeometry?
    /// Changes whenever the page view scrolls or zooms, so overlays drawn over the page follow it.
    var viewportTick = 0

    @ObservationIgnored private var undoStack: [UndoStep] = []
    /// Every form field's value as last saved, to find the fields the user has changed.
    @ObservationIgnored private var fieldValues: [ObjectIdentifier: (widget: PDFAnnotation, value: FieldValue)] = [:]
    @ObservationIgnored private var fieldSyncTask: Task<Void, Never>?
    /// A signature field the user tapped: the reader asks which signature to put in it.
    var signingField: PDFAnnotation?
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
        if !isLocked { prepareFields() }
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
            // A file may have a separate open (user) password; PDFKit can't tell, and saves use the entered one
            // for both, so the user hears it once, when the document becomes editable.
            show(hint: Self.passwordNotice, for: .seconds(5))
        }
        prepareFields()
        return true
    }

    static let passwordNotice = "Saving will set this document's password to the one you entered."

    /// The file was protected elsewhere (Tools) while it is open here: every later save keeps that password.
    /// The document in memory is the same as the file, so nothing needs reading again.
    func adoptProtection(password newPassword: String) {
        guard canEdit else { return }
        setPassword(newPassword)
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
        syncFields()
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
        guard canEdit, let old = password else { return false }
        syncFields()
        guard let document else { return false }
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

    /// Places a Check or Cross, like a signature.
    func beginPlacing(mark: Mark) {
        beginPlacing(signature: mark.image())
    }

    /// Places today's date as text.
    func beginPlacingDate() {
        beginPlacing(text: Self.todayText())
    }

    /// Today's date in the device's medium date style for its language and region ("Nov 27, 2026"), no time.
    nonisolated static func todayText(_ date: Date = .now, locale: Locale = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    func commitPlacement() {
        defer { cancelMode() }
        guard let placement, let pdfView, !placement.rect.isEmpty,
              let page = pdfView.page(for: CGPoint(x: placement.rect.midX, y: placement.rect.midY), nearest: true) else { return }
        let bounds = pdfView.convert(placement.rect, to: page)
        lastPlacedRect = placement.rect

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
        // PDFView edits a form field on its own (ticks a box, picks a radio button); look for the change once it has.
        scheduleFieldSync()
        guard mode == .reading, canEdit, let pdfView else { return }
        if let page = pdfView.page(for: point, nearest: false),
           let annotation = page.annotation(at: pdfView.convert(point, to: page)),
           FormFields.isSignatureField(annotation), !annotation.isReadOnly {
            signingField = annotation
            return
        }
        select(at: point)
    }

    /// Selects the mark under `point` (in the page view), or clears the selection when there is none.
    func select(at point: CGPoint) {
        guard canEdit, let pdfView, let page = pdfView.page(for: point, nearest: false),
              let annotation = Annotations.mark(at: pdfView.convert(point, to: page), on: page) else {
            selectedAnnotation = nil
            return
        }
        selectedAnnotation = AnnotationSelection(annotation: annotation, page: page)
    }

    var selectionIsMovable: Bool { selectedAnnotation?.isMovable ?? false }

    /// Where the selected mark is in the page view, or nil.
    var selectionFrame: CGRect? {
        _ = viewportTick
        guard let selected = selectedAnnotation, let pdfView else { return nil }
        return pdfView.convert(selected.annotation.bounds, from: selected.page)
    }

    /// Moves or resizes the selected mark while a finger drags it (`viewRect` in the page view). Nothing is saved
    /// until `finishDrag`.
    func dragSelected(toViewRect viewRect: CGRect) {
        guard let selected = selectedAnnotation, selected.isMovable, let pdfView else { return }
        let start = dragStart ?? AnnotationGeometry(selected.annotation)
        dragStart = start
        start.moved(to: pdfView.convert(viewRect, to: selected.page).standardized).apply(to: selected.annotation)
        viewportTick &+= 1
    }

    /// The finger is lifted: the drag becomes one Undo step and the file is saved.
    func finishDrag() {
        guard let selected = selectedAnnotation, let start = dragStart else { return }
        dragStart = nil
        guard AnnotationGeometry(selected.annotation) != start else { return }
        record(.changed([(selected.annotation, start)]))
    }

    /// Moves or resizes the selected mark to `viewRect` in the page view, as one finished change.
    func moveSelected(toViewRect viewRect: CGRect) {
        dragSelected(toViewRect: viewRect)
        finishDrag()
    }

    /// Moves or resizes the selected mark to `bounds` (page space), as one finished change. Text markup and marks
    /// made by other apps stay where they are.
    func moveSelected(to bounds: CGRect) {
        guard let selected = selectedAnnotation, selected.isMovable else { return }
        let before = AnnotationGeometry(selected.annotation)
        let after = before.moved(to: bounds.standardized)
        guard after != before else { return }
        after.apply(to: selected.annotation)
        viewportTick &+= 1
        record(.changed([(selected.annotation, before)]))
    }

    func deleteSelectedAnnotation() {
        guard let selected = selectedAnnotation else { return }
        selected.page.removeAnnotation(selected.annotation)
        selectedAnnotation = nil
        record(.removed([(selected.page, selected.annotation)]))
    }

    /// Adds one Undo step and saves.
    private func record(_ step: UndoStep) {
        syncFields()
        undoStack.append(step)
        canUndo = true
        save()
    }

    func undo() {
        syncFields()
        guard let last = undoStack.popLast() else { return }
        selectedAnnotation = nil
        switch last {
        case .added(let added):
            for (page, annotation) in added { page.removeAnnotation(annotation) }
        case .changed(let changed):
            for (annotation, geometry) in changed { geometry.apply(to: annotation) }
        case .removed(let removed):
            for (page, annotation) in removed { page.addAnnotation(annotation) }
        case .edited(let annotation, let text):
            annotation.contents = text
        case .replaced(let swaps):
            restore(swaps)
        case .fields(let changed):
            // Back to front, so a radio group ends as it was.
            for (widget, old) in changed.reversed() {
                let readOnly = widget.isReadOnly
                widget.isReadOnly = false
                FormFields.set(widget, value: old)
                widget.isReadOnly = readOnly
            }
            for (widget, _) in changed {
                if let value = FormFields.value(of: widget) { fieldValues[ObjectIdentifier(widget)] = (widget, value) }
            }
        }
        canUndo = !undoStack.isEmpty
        save()
    }

    // MARK: Form fields

    /// Reads the fields of a document that has just been shown. In a document that can't be changed, every field
    /// is made read-only on screen (never saved), so nothing can be typed that would be lost.
    private func prepareFields() {
        guard let document, !document.isLocked else {
            fieldValues = [:]
            return
        }
        FormFields.repair(document)
        let widgets = FormFields.widgets(in: document)
        if !canEdit {
            for widget in widgets { widget.isReadOnly = true }
            for index in 0..<document.pageCount {
                for annotation in document.page(at: index)?.annotations ?? [] where FormFields.isSignatureField(annotation) {
                    annotation.isReadOnly = true
                }
            }
        }
        fieldValues = Dictionary(uniqueKeysWithValues: widgets.compactMap { widget in
            FormFields.value(of: widget).map { (ObjectIdentifier(widget), (widget, $0)) }
        })
    }

    /// Looks for field changes a moment after a tap, once PDFView has applied it.
    func scheduleFieldSync() {
        fieldSyncTask?.cancel()
        fieldSyncTask = Task {
            try? await Task.sleep(for: .milliseconds(300))
            if !Task.isCancelled { syncFields() }
        }
    }

    /// Finds the fields the user has changed since the last save and saves them as one undo step. Called when a
    /// text field stops editing, after taps, and before anything else is saved.
    func syncFields() {
        guard canEdit, !fieldValues.isEmpty else { return }
        // PDFView changes only the radio button that was tapped. Setting it through FormFields turns the others
        // off explicitly too, so each button is written with its own value (see FormFields.repair).
        for entry in fieldValues.values where entry.widget.widgetControlType == .radioButtonControl
            && entry.value == .on(false) && FormFields.value(of: entry.widget) == .on(true) {
            let readOnly = entry.widget.isReadOnly
            entry.widget.isReadOnly = false
            FormFields.set(entry.widget, value: .on(true))
            entry.widget.isReadOnly = readOnly
        }
        var changed: [(PDFAnnotation, FieldValue)] = []
        for (key, entry) in fieldValues {
            guard let now = FormFields.value(of: entry.widget), now != entry.value else { continue }
            changed.append((entry.widget, entry.value))
            fieldValues[key] = (entry.widget, now)
        }
        guard !changed.isEmpty else { return }
        undoStack.append(.fields(changed))
        canUndo = true
        save()
    }

    /// Puts a signature into a signature field: as large as fits, its shape kept, centred. The field itself stays
    /// an (unsigned) field; the signature is a stamp on top of it, like any placed signature.
    func sign(_ field: PDFAnnotation, with image: UIImage) {
        signingField = nil
        guard canEdit, let page = field.page, image.size.height > 0 else { return }
        let bounds = FormFields.fit(aspect: image.size.width / image.size.height, in: field.bounds, rotation: page.rotation)
        if let annotation = PDFOperations.stamp(image, on: page, bounds: bounds) {
            commit([(page, annotation)])
        }
    }

    /// Called after the page organiser changes the document's structure.
    func pagesDidChange() {
        // The annotations added so far stay in the document, but their pages may be gone, so they can no longer be undone one by one.
        syncFields()
        undoStack.removeAll()
        prepareFields()
        canUndo = false
        pageCount = document?.pageCount ?? 0
        pdfView?.layoutDocumentView()
        save()
        syncPage()
    }

    /// Adds marks made here as one Undo step, tagged as PDFoundry's own, and saves.
    private func commit(_ added: [(PDFPage, PDFAnnotation)]) {
        guard !added.isEmpty else { return }
        for (_, annotation) in added { Annotations.tag(annotation) }
        record(.added(added))
    }

    // MARK: Replacing pages

    /// Puts each swap's new page in place of its old one, carrying the listed annotations over, as one Undo step.
    func replacePages(_ swaps: [PageSwap]) {
        guard let document, !swaps.isEmpty else { return }
        syncFields()
        selectedAnnotation = nil
        for swap in swaps {
            let index = document.index(for: swap.old)
            guard index != NSNotFound, index < document.pageCount else { continue }
            for annotation in swap.moved {
                swap.old.removeAnnotation(annotation)
                swap.new.addAnnotation(annotation)
            }
            document.removePage(at: index)
            document.insert(swap.new, at: index)
        }
        pagesReplaced()
        record(.replaced(swaps))
    }

    private func restore(_ swaps: [PageSwap]) {
        guard let document else { return }
        for swap in swaps.reversed() {
            let index = document.index(for: swap.new)
            guard index != NSNotFound, index < document.pageCount else { continue }
            for annotation in swap.moved {
                swap.new.removeAnnotation(annotation)
                swap.old.addAnnotation(annotation)
            }
            document.removePage(at: index)
            document.insert(swap.old, at: index)
        }
        pagesReplaced()
    }

    /// The fields and the view after pages were swapped.
    private func pagesReplaced() {
        prepareFields()
        pdfView?.layoutDocumentView()
        syncPage()
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
            written = PDFOperations.writeInPlace(document, to: target, password: password,
                                                 atomic: PDFOperations.isInsideFolder(url, URL.documentsDirectory))
        }
        guard written else {
            errorMessage = "This file can't be changed where it is. You can save a copy in PDFoundry instead."
            return false
        }
        canRevert = original != nil
        return true
    }

    /// Puts the file back as it was when it was opened, and shows it. A password set here since then stays: the
    /// original contents are written with it (as on Android), so Revert never quietly unprotects a file.
    @discardableResult
    func revertToOriginal() -> Bool {
        guard let original, canRevert, let restored = PDFDocument(data: original) else { return false }
        let keep = restored.isEncrypted ? nil : password
        var written = false
        var error: NSError?
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forReplacing, error: &error) { target in
            if let keep {
                written = PDFOperations.writeInPlace(restored, to: target, password: keep,
                                                     atomic: PDFOperations.isInsideFolder(url, URL.documentsDirectory))
            } else {
                // The original bytes exactly. Written through the same safe path as every save.
                written = PDFOperations.writeInPlace(original, to: target,
                                                     atomic: PDFOperations.isInsideFolder(url, URL.documentsDirectory))
            }
        }
        guard written else {
            errorMessage = "The original couldn't be put back."
            return false
        }
        // The original may be encrypted (the file was protected when it was opened) or plain. The reader follows the
        // file: unlocked with the password it was opened with, or the one kept from this session, or none.
        if restored.isEncrypted {
            if restored.isLocked, let openedPassword { _ = restored.unlock(withPassword: openedPassword) }
            setPassword(restored.permissionsStatus == .owner ? openedPassword : nil)
        } else {
            setPassword(keep)
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

    func show(hint text: String, for duration: Duration = .seconds(2.5)) {
        hint = text
        hintTask?.cancel()
        hintTask = Task {
            try? await Task.sleep(for: duration)
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
        // Handed over as bytes in memory: no temporary file is left on disk.
        controller.printingItem = document?.dataRepresentation() ?? url
        controller.present(animated: true)
    }

    func showSearch() {
        pdfView?.findInteraction.presentFindNavigator(showingReplace: false)
    }
}

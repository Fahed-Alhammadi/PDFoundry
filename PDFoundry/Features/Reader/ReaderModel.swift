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
@Observable
final class ReaderModel {
    enum Mode { case reading, drawing, placing }

    let url: URL
    private(set) var document: PDFDocument?
    private(set) var isLocked = false
    private(set) var pageIndex = 0
    private(set) var pageCount = 0
    private(set) var canUndo = false
    var hasSelection = false
    var mode = Mode.reading
    var placement: Placement?
    var drawing = PKDrawing()
    var selectedAnnotation: AnnotationSelection?
    var hint: String?
    var errorMessage: String?

    @ObservationIgnored weak var pdfView: PDFView?
    @ObservationIgnored private var undoStack: [[(PDFPage, PDFAnnotation)]] = []
    @ObservationIgnored private var hintTask: Task<Void, Never>?

    var title: String { url.deletingPathExtension().lastPathComponent }

    /// Password-protected files open read-only, so saving never strips their protection.
    var canEdit: Bool { document.map { !$0.isEncrypted } ?? false }

    init(url: URL) {
        self.url = url
        document = PDFDocument(url: url)
        isLocked = document?.isLocked ?? false
        pageCount = document?.pageCount ?? 0
    }

    func unlock(with password: String) -> Bool {
        guard let document, document.unlock(withPassword: password) else { return false }
        isLocked = false
        pageCount = document.pageCount
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

    func save() {
        guard let document, canEdit else { return }
        if !document.write(to: url) {
            errorMessage = PDFoundryError.writeFailed.localizedDescription
        }
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
        controller.printingItem = url
        controller.present(animated: true)
    }

    func showSearch() {
        pdfView?.findInteraction.presentFindNavigator(showingReplace: false)
    }
}

import PDFKit
import UIKit

nonisolated enum PDFoundryError: LocalizedError, Equatable {
    case unreadable(String)
    case locked(String)
    case writeFailed
    case nothingToSave
    case alreadyProtected(String)
    case wrongPassword
    case limitedAccess(String)
    case protectedSource(String)

    var errorDescription: String? {
        switch self {
        case .unreadable(let name): "“\(name)” could not be opened. The file may be damaged."
        case .locked(let name): "“\(name)” is password protected. Open it and unlock it first."
        case .writeFailed: "The PDF could not be saved. Check that the device has free storage."
        case .nothingToSave: "There are no pages to save."
        case .alreadyProtected(let name): "“\(name)” is already protected with a password. To change it, open it, choose More › Remove Password, then protect it again."
        case .wrongPassword: "That password didn't work. Try again."
        case .protectedSource(let name): "“\(name)” is password protected, so it can't be merged. Use More › Remove Password first."
        case .limitedAccess(let name): "“\(name)” has restrictions set by its owner, so PDFoundry can't change it or copy its pages."
        }
    }
}

/// A stamp annotation that paints an image (a signature or a freehand drawing) onto a page.
/// PDFKit bakes the drawing into the annotation's appearance stream when the document is written.
nonisolated final class ImageStampAnnotation: PDFAnnotation {
    private let cgImage: CGImage?

    init(image: CGImage, bounds: CGRect) {
        cgImage = image
        super.init(bounds: bounds, forType: .stamp, withProperties: nil)
    }

    required init?(coder: NSCoder) {
        cgImage = nil
        super.init(coder: coder)
    }

    override func draw(with box: PDFDisplayBox, in context: CGContext) {
        guard let cgImage else {
            super.draw(with: box, in: context)
            return
        }
        context.saveGState()
        context.interpolationQuality = .high
        context.draw(cgImage, in: bounds)
        context.restoreGState()
    }
}

enum PDFOperations {
    /// Options for writing a document: with a password, it is encrypted, and the password is both the user (open)
    /// and the owner password, with every permission allowed. PDFKit chooses the cipher (AES-128). Without one,
    /// no options, so the file is written unencrypted.
    nonisolated static func writeOptions(password: String?) -> [PDFDocumentWriteOption: Any] {
        guard let password, !password.isEmpty else { return [:] }
        return [
            .userPasswordOption: password,
            .ownerPasswordOption: password,
            .accessPermissionsOption: NSNumber(value: 0xFF),
        ]
    }

    /// Writes `document` into the existing file at `url`, keeping that file (it is overwritten, not replaced).
    /// `PDFDocument.write(to:)` puts a new file in its place, and a file in "On My iPhone" then gets a new identity
    /// in the Files app: the bookmark PDFoundry keeps for a file opened in place stops resolving and the file drops
    /// off the list. Overwriting also works when only the file, not its folder, may be written (a file picked in
    /// Files). Files in PDFoundry's own folder (`atomic`) are written to a temporary file and swapped in.
    /// Callers wrap it in a file coordinator, so other apps see one change.
    nonisolated static func writeInPlace(_ document: PDFDocument, to url: URL, password: String?, atomic: Bool = false,
                                         write: ((Data, URL) throws -> Void)? = nil) -> Bool {
        guard let data = document.dataRepresentation(options: writeOptions(password: password)) else { return false }
        return writeInPlace(data, to: url, atomic: atomic, write: write)
    }

    /// Writes `data` over the file at `url` safely: refused up front when the disk hasn't room for it, and if the
    /// write fails part way (an overwrite has already cut the file short), the previous bytes are written back,
    /// so a protected file never ends up cut short or unprotected.
    nonisolated static func writeInPlace(_ data: Data, to url: URL, atomic: Bool = false,
                                         write: ((Data, URL) throws -> Void)? = nil) -> Bool {
        let folder = url.deletingLastPathComponent()
        if let free = try? folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage, free > 0, free < Int64(data.count) + 1_000_000 {
            return false
        }
        // Atomic: a temporary file beside it, then swapped in, so the old file stays whole until the new one is.
        let writer = write ?? { data, url in try data.write(to: url, options: atomic ? .atomic : []) }
        let previous = atomic ? nil : try? Data(contentsOf: url)
        do {
            try writer(data, url)
            return true
        } catch {
            if let previous { try? previous.write(to: url) }
            return false
        }
    }

    /// Whether `url` is directly inside `folder`.
    nonisolated static func isInsideFolder(_ url: URL, _ folder: URL) -> Bool {
        url.deletingLastPathComponent().standardizedFileURL.path == folder.standardizedFileURL.path
    }

    /// Reads a document with full access: unprotected, or unlocked with a password that is its owner password.
    /// A protected file without the password, with a wrong one, or with only limited access throws.
    nonisolated static func fullAccess(_ data: Data?, password: String?, name: String) throws -> PDFDocument {
        guard let data, let document = PDFDocument(data: data) else { throw PDFoundryError.unreadable(name) }
        guard document.isEncrypted else { return document }
        if document.isLocked {
            guard let password, !password.isEmpty else { throw PDFoundryError.locked(name) }
            guard document.unlock(withPassword: password) else { throw PDFoundryError.wrongPassword }
        }
        guard document.permissionsStatus == .owner else { throw PDFoundryError.limitedAccess(name) }
        return document
    }

    /// A copy of an unlocked document that is no longer encrypted. PDFKit writes an encrypted document encrypted
    /// again whatever the options say, so taking a password off means moving everything into a new document:
    /// the pages (with their annotations and form fields), the document's information and its outline.
    nonisolated static func unencrypted(_ source: PDFDocument) -> PDFDocument {
        let result = PDFDocument()
        for index in 0..<source.pageCount {
            guard let page = source.page(at: index)?.copy() as? PDFPage else { continue }
            result.insert(page, at: result.pageCount)
        }
        result.documentAttributes = source.documentAttributes
        // Not kept: links from one page to another and page labels. PDFKit writes a page moved or copied into another
        // document with its links pointing at page 1, and setting the destination again doesn't survive the write
        // either (tried); it has no API for page labels. Form fields are kept (PDFKit writes the form again).
        if let outline = source.outlineRoot {
            let root = PDFOutline()
            copyOutline(outline, into: root, from: source, to: result)
            result.outlineRoot = root
        }
        return result
    }

    private nonisolated static func copyOutline(_ source: PDFOutline, into target: PDFOutline, from old: PDFDocument, to new: PDFDocument) {
        for index in 0..<source.numberOfChildren {
            guard let child = source.child(at: index) else { continue }
            let copy = PDFOutline()
            copy.label = child.label
            copy.isOpen = child.isOpen
            if let destination = child.destination, let page = destination.page {
                let pageIndex = old.index(for: page)
                if let newPage = new.page(at: pageIndex) {
                    copy.destination = PDFDestination(page: newPage, at: destination.point)
                }
            }
            target.insertChild(copy, at: target.numberOfChildren)
            copyOutline(child, into: copy, from: old, to: new)
        }
    }

    /// Joins the given files, in order, into one new document.
    static func merge(_ urls: [URL]) throws -> PDFDocument {
        try merge(urls.map { (name: $0.deletingPathExtension().lastPathComponent, data: try? Data(contentsOf: $0)) })
    }

    /// Joins documents already read into memory, in order. A nil `data` is a file that couldn't be read.
    static func merge(_ sources: [(name: String, data: Data?)]) throws -> PDFDocument {
        let merged = PDFDocument()
        for (name, data) in sources {
            // Password-protected files and files with restrictions only are refused: a merged file has no password,
            // so it would carry their pages out unprotected.
            let source: PDFDocument
            do {
                source = try fullAccess(data, password: nil, name: name)
            } catch PDFoundryError.locked, PDFoundryError.limitedAccess {
                throw PDFoundryError.protectedSource(name)
            }
            for index in 0..<source.pageCount {
                guard let page = source.page(at: index)?.copy() as? PDFPage else { continue }
                merged.insert(page, at: merged.pageCount)
            }
        }
        guard merged.pageCount > 0 else { throw PDFoundryError.nothingToSave }
        return merged
    }

    /// Copies the pages at `indexes` into a new document.
    nonisolated static func extract(_ indexes: [Int], from document: PDFDocument) throws -> PDFDocument {
        let result = PDFDocument()
        for index in indexes.sorted() where index >= 0 && index < document.pageCount {
            guard let page = document.page(at: index)?.copy() as? PDFPage else { continue }
            result.insert(page, at: result.pageCount)
        }
        guard result.pageCount > 0 else { throw PDFoundryError.nothingToSave }
        return result
    }

    /// Builds a document with one page per image (scans or photos).
    static func document(from images: [UIImage]) throws -> PDFDocument {
        let result = PDFDocument()
        for image in images {
            guard let page = PDFPage(image: image) else { continue }
            result.insert(page, at: result.pageCount)
        }
        guard result.pageCount > 0 else { throw PDFoundryError.nothingToSave }
        return result
    }

    /// Stamps `image` onto `page`. `bounds` is in page space; the image is counter-rotated
    /// so it appears upright on pages that carry a rotation.
    @discardableResult
    static func stamp(_ image: UIImage, on page: PDFPage, bounds: CGRect) -> PDFAnnotation? {
        let upright = image.rotatedCounterClockwise(degrees: page.rotation)
        guard let cgImage = upright.cgImage else { return nil }
        let annotation = ImageStampAnnotation(image: cgImage, bounds: bounds)
        page.addAnnotation(annotation)
        return annotation
    }

    /// The short guide placed in the library on first launch.
    static func welcomeGuide() -> Data {
        let pageRect = CGRect(x: 0, y: 0, width: 612, height: 792)
        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        let ink = UIColor(red: 0.07, green: 0.08, blue: 0.11, alpha: 1)
        let ember = UIColor(red: 0.886, green: 0.318, blue: 0.106, alpha: 1)

        func draw(_ text: String, font: UIFont, color: UIColor, in rect: CGRect, spacing: CGFloat = 4) {
            let style = NSMutableParagraphStyle()
            style.lineSpacing = spacing
            NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: color, .paragraphStyle: style])
                .draw(in: rect)
        }

        return renderer.pdfData { context in
            // Page 1: what the app does.
            context.beginPage()
            ink.setFill()
            UIRectFill(CGRect(x: 0, y: 0, width: 612, height: 210))
            ember.setFill()
            UIRectFill(CGRect(x: 0, y: 210, width: 612, height: 6))
            draw("Welcome to PDFoundry", font: .systemFont(ofSize: 38, weight: .bold), color: .white,
                 in: CGRect(x: 56, y: 84, width: 500, height: 50))
            draw("Read, merge, edit and sign PDF documents.", font: .systemFont(ofSize: 17), color: UIColor(white: 1, alpha: 0.8),
                 in: CGRect(x: 56, y: 140, width: 500, height: 30))

            let sections: [(String, String)] = [
                ("Read", "Open any PDF from Files, scan paper with the camera, or build a PDF from your photos. Search the text and jump between pages."),
                ("Merge", "Tap Select in the library, choose two or more documents, then tap Merge. Drag the files into the order you want."),
                ("Edit", "Select text to highlight, underline or strike it out. Add typed text, draw with your finger or Apple Pencil, and reorder, rotate, duplicate or delete pages."),
                ("Sign", "Draw your signature once and reuse it. Drag it into place, resize it, and tap Place."),
                ("Private", "Everything stays on this device. PDFoundry has no account, no ads and no tracking.")
            ]
            var y: CGFloat = 262
            for (title, body) in sections {
                draw(title, font: .systemFont(ofSize: 19, weight: .semibold), color: ember, in: CGRect(x: 56, y: y, width: 500, height: 26))
                draw(body, font: .systemFont(ofSize: 14), color: ink, in: CGRect(x: 56, y: y + 28, width: 500, height: 60))
                y += 98
            }

            // Page 2: a practice sheet.
            context.beginPage()
            draw("Try it on this page", font: .systemFont(ofSize: 30, weight: .bold), color: ink, in: CGRect(x: 56, y: 72, width: 500, height: 40))
            draw("Press and hold this sentence to select it, then tap the highlighter in the toolbar.",
                 font: .systemFont(ofSize: 16), color: ink, in: CGRect(x: 56, y: 140, width: 500, height: 50))
            draw("Tap the pencil to draw anywhere on the page. Tap the text tool to type a note.",
                 font: .systemFont(ofSize: 16), color: ink, in: CGRect(x: 56, y: 210, width: 500, height: 50))
            draw("Tap the signature tool and place your signature on the line below.",
                 font: .systemFont(ofSize: 16), color: ink, in: CGRect(x: 56, y: 280, width: 500, height: 50))

            UIColor(white: 0.55, alpha: 1).setFill()
            UIRectFill(CGRect(x: 56, y: 560, width: 300, height: 1))
            draw("Signature", font: .systemFont(ofSize: 12), color: UIColor(white: 0.45, alpha: 1), in: CGRect(x: 56, y: 568, width: 300, height: 18))
            UIRectFill(CGRect(x: 396, y: 560, width: 160, height: 1))
            draw("Date", font: .systemFont(ofSize: 12), color: UIColor(white: 0.45, alpha: 1), in: CGRect(x: 396, y: 568, width: 160, height: 18))
        }
    }
}

extension UIImage {
    /// Returns the image turned counter-clockwise by a multiple of 90 degrees.
    func rotatedCounterClockwise(degrees: Int) -> UIImage {
        let normalized = ((degrees % 360) + 360) % 360
        guard normalized != 0 else { return self }
        let swapsSides = normalized == 90 || normalized == 270
        let newSize = swapsSides ? CGSize(width: size.height, height: size.width) : size
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: newSize, format: format).image { context in
            let cg = context.cgContext
            cg.translateBy(x: newSize.width / 2, y: newSize.height / 2)
            cg.rotate(by: -CGFloat(normalized) * .pi / 180)
            draw(in: CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height))
        }
    }
}

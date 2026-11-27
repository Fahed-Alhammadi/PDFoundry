import CoreText
import PDFKit
import UIKit
import Vision

/// Make Searchable: Vision reads the pages that have no text yet (on the device), and each of those pages is
/// rebuilt with invisible text laid over the words, so search, selection and copy work while the page looks
/// exactly the same.
///
/// PDFKit can't add to a page's content, so a page is rebuilt: its original content is drawn (as vectors, unchanged)
/// into a new page together with the invisible text, its annotations and form fields are moved over, and the new
/// page takes the old one's place. Known limit: outline entries and links that point at a rebuilt page may stop
/// working (scanned pages rarely have them).
enum OCR {
    static let nothingFoundMessage = "No text was found on these pages."
    /// Pages are read at this resolution.
    static let dpi: CGFloat = 300

    /// One recognised line: its text and where it is, as a fraction of the page as shown (origin bottom left).
    struct Line: Sendable {
        let text: String
        let box: CGRect
    }

    /// The pages (zero-based) that have no text that can be selected.
    static func pagesNeedingText(_ document: PDFDocument) -> [Int] {
        (0..<document.pageCount).filter { index in
            (document.page(at: index)?.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    /// The languages asked for: English and Arabic, as far as Vision on this device reads them.
    nonisolated static var languages: [String] {
        let supported = (try? VNRecognizeTextRequest().supportedRecognitionLanguages()) ?? ["en-US"]
        let wanted = ["en-US", "ar-SA", "ars-SA"]
        let chosen = wanted.filter(supported.contains)
        return chosen.isEmpty ? ["en-US"] : chosen
    }

    nonisolated static var readsArabic: Bool {
        languages.contains { $0.hasPrefix("ar") }
    }

    /// New pages, with invisible text, for the pages at `indexes` on which text was found. Nothing in `document`
    /// changes. Throws `CancellationError` when the task is cancelled. `progress` is told after each page.
    static func rebuild(_ document: PDFDocument, pages indexes: [Int],
                        progress: (Int, Int) -> Void) async throws -> [(index: Int, page: PDFPage)] {
        var results: [(index: Int, page: PDFPage)] = []
        for (step, index) in indexes.enumerated() {
            try Task.checkCancellation()
            guard let page = document.page(at: index), let image = PageImage.image(of: page, dpi: dpi) else { continue }
            let lines = try await recognize(image)
            try Task.checkCancellation()
            if !lines.isEmpty, let rebuilt = rebuilt(page, lines: lines) {
                results.append((index, rebuilt))
            }
            progress(step + 1, indexes.count)
        }
        try Task.checkCancellation()
        return results
    }

    /// Recognises the text on `pages` and puts the rebuilt pages in place, carrying each page's annotations over.
    /// Returns how many pages got text. When cancelled, `document` is left exactly as it was.
    @discardableResult
    static func run(_ document: PDFDocument, pages: [Int], progress: (Int, Int) -> Void) async throws -> Int {
        let rebuilt = try await rebuild(document, pages: pages, progress: progress)
        for (index, page) in rebuilt {
            guard let old = document.page(at: index) else { continue }
            for annotation in old.annotations {
                old.removeAnnotation(annotation)
                page.addAnnotation(annotation)
            }
            document.removePage(at: index)
            document.insert(page, at: index)
        }
        return rebuilt.count
    }

    @concurrent
    private nonisolated static func recognize(_ image: CGImage) async throws -> [Line] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.recognitionLanguages = languages
        request.automaticallyDetectsLanguage = true
        try VNImageRequestHandler(cgImage: image).perform([request])
        return (request.results ?? []).compactMap { observation in
            guard let candidate = observation.topCandidates(1).first, !candidate.string.isEmpty else { return nil }
            return Line(text: candidate.string, box: observation.boundingBox)
        }
    }

    /// `page`'s content, unchanged, with `lines` as invisible text, on a new page of the same size, crop box and
    /// rotation. Annotations are not drawn: they are moved over by the caller.
    static func rebuilt(_ page: PDFPage, lines: [Line]) -> PDFPage? {
        guard let content = page.pageRef else { return nil }
        let media = page.bounds(for: .mediaBox)
        let data = NSMutableData()
        var box = media
        guard let consumer = CGDataConsumer(data: data as CFMutableData),
              let context = CGContext(consumer: consumer, mediaBox: &box, nil) else { return nil }
        context.beginPDFPage([kCGPDFContextMediaBox as String: NSValue(cgRect: media)] as CFDictionary)
        context.saveGState()
        context.drawPDFPage(content)
        context.restoreGState()

        // The words are placed in the page as shown, then mapped back to page space.
        let shown = PageStamps.shownTransform(of: page)
        let size = PageImage.displayedSize(of: page)
        context.saveGState()
        context.concatenate(shown.inverted())
        context.setTextDrawingMode(.invisible)
        for line in lines {
            let rect = CGRect(x: line.box.minX * size.width, y: line.box.minY * size.height,
                              width: line.box.width * size.width, height: line.box.height * size.height)
            guard rect.width > 1, rect.height > 1 else { continue }
            let fontSize = rect.height * 0.85
            let font = CTFontCreateWithName("Helvetica" as CFString, fontSize, nil)
            let attributed = NSAttributedString(string: line.text, attributes: [.font: font, .foregroundColor: UIColor.black.cgColor])
            let ctLine = CTLineCreateWithAttributedString(attributed)
            let width = CTLineGetTypographicBounds(ctLine, nil, nil, nil)
            guard width > 0 else { continue }
            // Stretched to the word's width, so selecting the text covers the words on the picture.
            context.textMatrix = CGAffineTransform(scaleX: rect.width / width, y: 1)
            context.textPosition = CGPoint(x: rect.minX, y: rect.minY + CTFontGetDescent(font) * 0.5)
            CTLineDraw(ctLine, context)
        }
        context.restoreGState()
        context.endPDFPage()
        context.closePDF()

        guard let made = PDFOperations.detachedPage(from: data as Data) else { return nil }
        for box in [PDFDisplayBox.cropBox, .bleedBox, .trimBox, .artBox] {
            made.setBounds(page.bounds(for: box), for: box)
        }
        made.rotation = page.rotation
        return made
    }
}

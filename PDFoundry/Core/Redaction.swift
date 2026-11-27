import PDFKit
import UIKit

/// Redaction: each page with boxes is replaced by a picture of itself (page content, marks and form fields drawn,
/// at 300 dpi, lossless) with solid black boxes painted on, and no annotations or fields of its own. The page's
/// size, crop box, rotation and place in the document stay. Nothing of the old page is written again: PDFKit writes
/// the whole file from the document as it now is, so the old page's content, fonts and marks are gone from it.
enum Redaction {
    static let dpi: CGFloat = 300

    /// Replaces every page that has boxes in `boxes` (page index → boxes in page space). Returns `document`.
    @discardableResult
    static func apply(to document: PDFDocument, boxes: [Int: [CGRect]]) -> PDFDocument {
        for (index, page) in replacements(in: document, boxes: boxes) {
            document.removePage(at: index)
            document.insert(page, at: index)
        }
        return document
    }

    /// The redacted page for each page index with boxes, without changing the document.
    static func replacements(in document: PDFDocument, boxes: [Int: [CGRect]]) -> [(Int, PDFPage)] {
        boxes.keys.sorted().compactMap { index in
            guard let page = document.page(at: index), let rects = boxes[index], !rects.isEmpty,
                  let redacted = redactedPage(page, boxes: rects) else { return nil }
            return (index, redacted)
        }
    }

    /// A new page that looks like `page` with `boxes` blacked out, and is only an image.
    static func redactedPage(_ page: PDFPage, boxes: [CGRect]) -> PDFPage? {
        let media = page.bounds(for: .mediaBox)
        guard media.width > 0, media.height > 0, let image = picture(of: page, boxes: boxes) else { return nil }

        let data = NSMutableData()
        var box = media
        guard let consumer = CGDataConsumer(data: data as CFMutableData),
              let context = CGContext(consumer: consumer, mediaBox: &box, nil) else { return nil }
        context.beginPDFPage([kCGPDFContextMediaBox as String: NSValue(cgRect: media)] as CFDictionary)
        context.interpolationQuality = .high
        context.draw(image, in: media)
        context.endPDFPage()
        context.closePDF()

        guard let made = PDFDocument(data: data as Data)?.page(at: 0) else { return nil }
        for box in [PDFDisplayBox.cropBox, .bleedBox, .trimBox, .artBox] {
            made.setBounds(page.bounds(for: box), for: box)
        }
        made.rotation = page.rotation
        return made
    }

    /// The page's media box, unrotated, drawn with its marks and fields, with the boxes painted black.
    private static func picture(of page: PDFPage, boxes: [CGRect]) -> CGImage? {
        let media = page.bounds(for: .mediaBox)
        let scale = dpi / 72
        let width = Int((media.width * scale).rounded()), height = Int((media.height * scale).rounded())
        // Opaque RGB: the picture has no transparency to keep.
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.setFillColor(UIColor.white.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.scaleBy(x: scale, y: scale)

        // Drawn unrotated: the new page carries the same rotation, so it shows the same way.
        let rotation = page.rotation
        page.rotation = 0
        context.saveGState()
        page.draw(with: .mediaBox, to: context)
        context.restoreGState()
        page.rotation = rotation

        context.translateBy(x: -media.minX, y: -media.minY)
        context.setFillColor(UIColor.black.cgColor)
        for box in boxes { context.fill(box) }
        return context.makeImage()
    }
}

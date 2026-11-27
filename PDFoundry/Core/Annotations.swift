import PDFKit
import UIKit

/// Which annotations PDFoundry made, and what the reader lets the user do with each one.
///
/// Every mark the app adds is tagged with the app-private key `/PDFoundry true`. PDFKit writes custom keys set with
/// `setValue(_:forAnnotationKey:)` and reads them back (checked in `SelectionTests`); it does not write `/NM`, so the
/// tag is the key. `/NM` starting with `pdfoundry-` is still accepted, as the other apps also set it.
nonisolated enum Annotations {
    static let tagKey = PDFAnnotationKey(rawValue: "/PDFoundry")
    /// Page numbers and watermarks: tagged, but locked in place (not selectable, movable or deletable).
    static let pageStampKey = PDFAnnotationKey(rawValue: "/PDFoundryPageStamp")
    static let namePrefix = "pdfoundry-"

    /// Text markup belongs to its text: it can be deleted, not moved.
    static let textMarkup: Set<String> = ["Highlight", "Underline", "StrikeOut"]
    /// The kinds of mark the reader can select and delete, whoever made them.
    static let markKinds: Set<String> = textMarkup.union(["Ink", "Stamp", "FreeText", "Square", "Circle", "Line", "Text"])

    static func tag(_ annotation: PDFAnnotation) {
        annotation.setValue(true, forAnnotationKey: tagKey)
        annotation.setValue(namePrefix + UUID().uuidString, forAnnotationKey: .name)
    }

    static func isOurs(_ annotation: PDFAnnotation) -> Bool {
        if flag(annotation.value(forAnnotationKey: tagKey)) { return true }
        return (annotation.value(forAnnotationKey: .name) as? String)?.hasPrefix(namePrefix) ?? false
    }

    static func isPageStamp(_ annotation: PDFAnnotation) -> Bool {
        flag(annotation.value(forAnnotationKey: pageStampKey))
    }

    static func isDeletable(_ annotation: PDFAnnotation) -> Bool {
        markKinds.contains(annotation.type ?? "") && !isPageStamp(annotation)
    }

    static func isMovable(_ annotation: PDFAnnotation) -> Bool {
        isOurs(annotation) && isDeletable(annotation) && !textMarkup.contains(annotation.type ?? "")
    }

    /// Stamps keep their shape when resized from a corner; shapes stretch freely.
    static func keepsProportion(_ annotation: PDFAnnotation) -> Bool {
        ["Stamp", "FreeText", "Ink", "Text"].contains(annotation.type ?? "")
    }

    /// The topmost mark at `point` (page space) that the reader can select. Page stamps, links, form fields and
    /// pop-ups are passed over, so a mark under a watermark can still be picked.
    static func mark(at point: CGPoint, on page: PDFPage) -> PDFAnnotation? {
        page.annotations.reversed().first { annotation in
            guard isDeletable(annotation) else { return false }
            // Thin marks (an underline, a line) get a slightly larger target.
            return annotation.bounds.insetBy(dx: -6, dy: -6).contains(point)
        }
    }

    private static func flag(_ value: Any?) -> Bool {
        switch value {
        case let bool as Bool: bool
        case let number as NSNumber: number.boolValue
        case let string as String: string == "true" || string == "/true"
        default: false
        }
    }
}

/// What a move or resize changes on a mark, kept so Undo can put it back exactly.
struct AnnotationGeometry: Equatable {
    var bounds: CGRect
    var start: CGPoint?
    var end: CGPoint?
    var fontSize: CGFloat?

    init(_ annotation: PDFAnnotation) {
        bounds = annotation.bounds
        if annotation.type == "Line" {
            start = annotation.startPoint
            end = annotation.endPoint
        }
        if annotation.type == "FreeText" { fontSize = annotation.font?.pointSize }
    }

    /// Puts these values on `annotation`.
    func apply(to annotation: PDFAnnotation) {
        annotation.bounds = bounds
        if let start, let end {
            annotation.startPoint = start
            annotation.endPoint = end
        }
        if let fontSize, let font = annotation.font {
            annotation.font = font.withSize(fontSize)
        }
    }

    /// The same mark fitted into `target`: line ends and typed text scale with the box.
    func moved(to target: CGRect) -> AnnotationGeometry {
        var result = self
        result.bounds = target
        let sx = bounds.width > 0 ? target.width / bounds.width : 1
        let sy = bounds.height > 0 ? target.height / bounds.height : 1
        // PDFKit keeps line ends relative to the annotation's origin.
        if let start { result.start = CGPoint(x: start.x * sx, y: start.y * sy) }
        if let end { result.end = CGPoint(x: end.x * sx, y: end.y * sy) }
        if let fontSize { result.fontSize = max(4, fontSize * sy) }
        return result
    }
}

/// Draws pages to images: as the reader sees them (crop box and rotation), with their annotations, on white.
enum PageImage {
    /// The page as shown, `dpi` pixels per inch.
    static func image(of page: PDFPage, dpi: CGFloat, box: PDFDisplayBox = .cropBox) -> CGImage? {
        let size = displayedSize(of: page, box: box)
        let scale = dpi / 72
        let width = max(1, Int((size.width * scale).rounded())), height = max(1, Int((size.height * scale).rounded()))
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.setFillColor(UIColor.white.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.interpolationQuality = .high
        context.scaleBy(x: CGFloat(width) / size.width, y: CGFloat(height) / size.height)
        // PDFPage.draw applies the rotation and the box's origin itself.
        page.draw(with: box, to: context)
        return context.makeImage()
    }

    /// The size of `box` as shown, with the page's rotation applied.
    static func displayedSize(of page: PDFPage, box: PDFDisplayBox = .cropBox) -> CGSize {
        let bounds = page.bounds(for: box)
        let turned = ((page.rotation % 360) + 360) % 360
        return turned == 90 || turned == 270 ? CGSize(width: bounds.height, height: bounds.width) : bounds.size
    }
}
